package com.zhenghuaizhi.flashskip.platform.accessibility

import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityRecord
import com.zhenghuaizhi.flashskip.domain.model.CapturePhase
import com.zhenghuaizhi.flashskip.domain.model.CaptureSession
import com.zhenghuaizhi.flashskip.domain.model.ClickRecoveryDiagnostics
import com.zhenghuaizhi.flashskip.domain.model.ClickRecoveryOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAccessibilityRecord
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], shadows = [CaptureTraceLogTest.CountingRecordShadow::class])
class CaptureTraceLogTest {
    @Before
    fun resetLogs() {
        ShadowLog.clear()
        CountingRecordShadow.sourceReads = 0
    }

    @Test
    fun debugEventAndStateLogOnlyMetadataWithoutReadingSource() {
        val event = sensitiveEvent()
        // 先证明 shadow 能捕获节点读取，避免“从未读取”的断言因测试替身失效而误通过。
        event.source
        assertEquals(1, CountingRecordShadow.sourceReads)
        CountingRecordShadow.sourceReads = 0

        CaptureTraceLog.state(session())
        CaptureTraceLog.event(session(), event)

        val output = traceOutput()
        assertTrue(output.contains("stage=accessibility_event"))
        assertTrue(output.contains("phase=OBSERVING_CLICK"))
        assertTrue(output.contains("recordCount=0"))
        assertFalse(output.contains("private-"))
        assertEquals(0, CountingRecordShadow.sourceReads)
    }

    @Test
    fun boundaryLogDoesNotExposeHostOrSourceIdentity() {
        val host = VerifiedCaptureHost(7, "private-host", "private-activity", 3)
        val request = AccessibilityCaptureWindowResolver.Request(
            sessionId = 7,
            host = host,
            eventPackageName = "private-event",
            eventWindowId = 3,
        )
        val evidence = CaptureWindowBoundaryEvidence(
            sessionId = 7,
            eventPackageName = "private-event",
            eventWindowId = 3,
            matchingWindowCount = 1,
            matchingRootPackageName = "private-root",
            activeRootPackageName = "private-active-root",
            sourcePackageName = "private-source",
        )

        CaptureTraceLog.windowBoundary(
            request,
            evidence,
            CaptureWindowBoundaryPolicy.Decision.Reject(
                CaptureWindowBoundaryPolicy.RejectReason.PACKAGE_MISMATCH,
            ),
            deadlineAtElapsedMs = 1_000,
        )

        val output = traceOutput()
        assertTrue(output.contains("windowCount=1"))
        assertTrue(output.contains("resolution=PACKAGE_MISMATCH"))
        assertFalse(output.contains("private-"))
    }

    @Test
    fun arbitraryTextCollectionsAndObjectsAreNotFormatted() {
        CaptureTraceLog.stage(
            7,
            "click_capture",
            "text" to "private-page-text",
            "description" to "private-description",
            "resourceId" to "private-resource-id",
            "nodes" to listOf("private-node"),
            "source" to object {
                override fun toString(): String = error("Page objects must not be formatted")
            },
            "copiedNodes" to 2,
            "sourceAvailable" to false,
        )

        val output = traceOutput()
        assertFalse(output.contains("private-"))
        assertFalse(output.contains("resourceId="))
        assertFalse(output.contains("nodes="))
        assertTrue(output.contains("copiedNodes=2"))
        assertTrue(output.contains("sourceAvailable=false"))
    }

    @Test
    fun disabledLoggerEmitsNothingAndDoesNotReadEventSource() {
        val logger = CaptureTraceLogger(enabled = false)
        logger.state(session())
        logger.event(session(), sensitiveEvent())
        logger.probe(
            7,
            allowBareNumericCountdown = false,
            diagnostics = ClickRecoveryDiagnostics(ClickRecoveryOutcome.SUCCESS),
        )
        logger.stage(7, "capture_failed", "failure" to ClickRecoveryOutcome.NO_ROOT)

        assertTrue(ShadowLog.getLogsForTag(CaptureTraceLogger.TAG).isEmpty())
        assertEquals(0, CountingRecordShadow.sourceReads)
    }

    private fun session() = CaptureSession(
        id = 7,
        targetAppName = "private-app-name",
        packageName = "private-package",
        activityName = "private-activity",
        phase = CapturePhase.OBSERVING_CLICK,
        phaseStartedAtEpochMs = 0,
        deadlineAtEpochMs = 30_000,
    )

    @Suppress("DEPRECATION")
    private fun sensitiveEvent() = AccessibilityEvent.obtain().apply {
        eventType = AccessibilityEvent.TYPE_VIEW_CLICKED
        packageName = "private-package"
        className = "private-class"
        contentDescription = "private-description"
        text.add("private-page-text")
    }

    private fun traceOutput(): String = ShadowLog.getLogsForTag(CaptureTraceLogger.TAG)
        .joinToString("\n") { it.msg }

    @Implements(AccessibilityRecord::class)
    class CountingRecordShadow : ShadowAccessibilityRecord() {
        @Implementation
        public override fun getSource(): AccessibilityNodeInfo? {
            sourceReads++
            return null
        }

        companion object {
            var sourceReads = 0
        }
    }
}
