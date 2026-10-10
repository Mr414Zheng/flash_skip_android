package com.zhenghuaizhi.flashskip.platform.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Xml
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.zhenghuaizhi.flashskip.domain.model.CapturePhase
import com.zhenghuaizhi.flashskip.domain.model.CaptureSession
import com.zhenghuaizhi.flashskip.domain.model.ObservedDismissCandidate
import com.zhenghuaizhi.flashskip.domain.model.RuleCaptureState
import com.zhenghuaizhi.flashskip.domain.model.TargetAppId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAccessibilityService
import org.robolectric.shadow.api.Shadow
import org.xmlpull.v1.XmlPullParser

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], shadows = [FlashSkipAccessibilityServiceTest.ConfigurationShadow::class])
class FlashSkipAccessibilityServiceTest {
    @Test
    @Suppress("DEPRECATION")
    fun idleCapturePhasesIgnoreEventStormWithoutWindowReads() {
        val service = Robolectric.buildService(FlashSkipAccessibilityService::class.java).get()
        val shadow = Shadow.extract<ConfigurationShadow>(service)
        for (phase in listOf(
            CapturePhase.CONFIGURING,
            CapturePhase.AWAITING_CONFIRMATION,
            CapturePhase.CAPTURED,
        )) {
            setCapturePhase(service, phase)
            for (type in listOf(
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                AccessibilityEvent.TYPE_VIEW_CLICKED,
            )) {
                val event = AccessibilityEvent.obtain(type).apply { packageName = "target.example" }
                try {
                    repeat(100) { service.onAccessibilityEvent(event) }
                } finally {
                    event.recycle()
                }
            }
        }
        assertEquals(0, shadow.windowReads)
    }

    @Test
    @Suppress("DEPRECATION")
    fun activeCaptureResumesWindowInspectionAfterConfirmationPhase() {
        val service = Robolectric.buildService(FlashSkipAccessibilityService::class.java).get()
        val shadow = Shadow.extract<ConfigurationShadow>(service)
        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply {
            packageName = "target.example"
        }
        try {
            setCapturePhase(service, CapturePhase.WAITING_FOR_TARGET)
            service.onAccessibilityEvent(event)
            assertEquals(1, shadow.windowReads)

            setCapturePhase(service, CapturePhase.AWAITING_CONFIRMATION)
            repeat(100) { service.onAccessibilityEvent(event) }
            assertEquals(1, shadow.windowReads)

            setCapturePhase(service, CapturePhase.OBSERVING_CLICK, sessionId = 2L)
            service.onAccessibilityEvent(event)
            assertEquals(2, shadow.windowReads)
        } finally {
            event.recycle()
        }
    }

    @Test
    @Suppress("DEPRECATION")
    fun candidateClickListeningConsumesOnlyClickEventsWithoutWindowReads() {
        val service = Robolectric.buildService(FlashSkipAccessibilityService::class.java).get()
        val shadow = Shadow.extract<ConfigurationShadow>(service)
        val tracker = FlashSkipAccessibilityService::class.java
            .getDeclaredField("targetWindowDiagnosticsTracker")
            .apply { isAccessible = true }
            .get(service) as TargetWindowDiagnosticsTracker
        tracker.onSessionChanged(1L)
        setCapturePhase(
            service,
            CapturePhase.AWAITING_CONFIRMATION,
            candidateClickListening = true,
        )
        for (type in listOf(
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
        )) {
            val event = AccessibilityEvent.obtain(type).apply { packageName = "target.example" }
            try {
                repeat(100) { service.onAccessibilityEvent(event) }
            } finally {
                event.recycle()
            }
        }
        assertEquals(0, shadow.windowReads)
        val candidateSnapshot = requireNotNull(tracker.snapshot(1L, 1L))
        assertEquals(100, candidateSnapshot.viewClickedEventCount)
        assertEquals(0, candidateSnapshot.contentChangedEventCount)
        assertEquals(0, candidateSnapshot.windowStateEventCount)

        setCapturePhase(service, CapturePhase.AWAITING_CONFIRMATION)
        val ignoredClick = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_CLICKED).apply {
            packageName = "target.example"
        }
        try {
            repeat(20) { service.onAccessibilityEvent(ignoredClick) }
        } finally {
            ignoredClick.recycle()
        }
        assertEquals(100, requireNotNull(tracker.snapshot(1L, 1L)).viewClickedEventCount)
    }

    @Test
    fun captureSubscribesOnlyToConsumedEventsAndRestoresAutomationEvents() {
        val service = Robolectric.buildService(FlashSkipAccessibilityService::class.java).get()

        val configured = FlashSkipAccessibilityService::class.java
            .getDeclaredMethod("configureForCapture", String::class.java)
            .apply { isAccessible = true }
            .invoke(service, "private-target")

        assertEquals(true, configured)
        assertEquals(
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or AccessibilityEvent.TYPE_VIEW_CLICKED,
            service.serviceInfo.eventTypes,
        )
        assertNull(service.serviceInfo.packageNames)

        val candidateConfigured = FlashSkipAccessibilityService::class.java
            .getDeclaredMethod("configureForCandidateClick", String::class.java)
            .apply { isAccessible = true }
            .invoke(service, "private-target")
        assertEquals(true, candidateConfigured)
        assertEquals(AccessibilityEvent.TYPE_VIEW_CLICKED, service.serviceInfo.eventTypes)

        FlashSkipAccessibilityService::class.java
            .getDeclaredMethod("restoreDefaultEventConfiguration")
            .apply { isAccessible = true }
            .invoke(service)

        assertEquals(
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            service.serviceInfo.eventTypes,
        )
        assertNull(service.serviceInfo.packageNames)
    }

    @Test
    @Suppress("DEPRECATION")
    fun manifestServiceMetadataStartsWithMinimalAutomationEvents() {
        val context = RuntimeEnvironment.getApplication()
        val intent = Intent(context, FlashSkipAccessibilityService::class.java)
        val resolved = requireNotNull(
            context.packageManager.resolveService(intent, PackageManager.GET_META_DATA),
        )
        val parser = requireNotNull(
            resolved.serviceInfo.loadXmlMetaData(
                context.packageManager,
                AccessibilityService.SERVICE_META_DATA,
            ),
        )
        try {
            while (parser.next() != XmlPullParser.START_TAG) {
                check(parser.eventType != XmlPullParser.END_DOCUMENT)
            }
            val attributes = context.resources.obtainAttributes(
                Xml.asAttributeSet(parser),
                intArrayOf(
                    android.R.attr.accessibilityEventTypes,
                    android.R.attr.canRetrieveWindowContent,
                ),
            )
            try {
                assertEquals(
                    AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                    attributes.getInt(0, 0),
                )
                assertTrue(attributes.getBoolean(1, false))
            } finally {
                attributes.recycle()
            }
        } finally {
            parser.close()
        }
        assertEquals("android.permission.BIND_ACCESSIBILITY_SERVICE", resolved.serviceInfo.permission)
    }

    private fun setCapturePhase(
        service: FlashSkipAccessibilityService,
        phase: CapturePhase,
        sessionId: Long = 1L,
        candidateClickListening: Boolean = false,
    ) {
        val targetAppId = TargetAppId("target.example")
        val state = RuleCaptureState(
            activeSession = CaptureSession(
                id = sessionId,
                targetAppId = targetAppId,
                packageName = "target.example",
                phase = phase,
                phaseStartedAtEpochMs = 0L,
                deadlineAtEpochMs = 60_000L,
                candidateClickListeningEnabled = candidateClickListening,
                pendingObservedCandidate = if (candidateClickListening) {
                    ObservedDismissCandidate(
                        id = sessionId,
                        targetAppId = targetAppId,
                        targetAppName = "Target",
                        activityName = "target.example.MainActivity",
                        observedAfterTargetMs = 0L,
                        observedAtEpochMs = 1L,
                        nodes = emptyList(),
                        preClickUniqueSelectors = emptyList(),
                    )
                } else {
                    null
                },
            ),
        )
        FlashSkipAccessibilityService::class.java.getDeclaredField("latestCaptureState")
            .apply { isAccessible = true }
            .set(service, state)
    }

    // Robolectric 不建立系统连接；计数系统窗口访问，保留真实服务的事件和配置逻辑。
    @Implements(AccessibilityService::class)
    class ConfigurationShadow : ShadowAccessibilityService() {
        private var info = AccessibilityServiceInfo()
        var windowReads = 0
            private set

        @Implementation
        public override fun getWindows(): List<AccessibilityWindowInfo> {
            windowReads++
            return emptyList()
        }

        @Implementation
        fun getServiceInfo(): AccessibilityServiceInfo = info

        @Implementation
        fun setServiceInfo(value: AccessibilityServiceInfo) {
            info = value
        }
    }
}
