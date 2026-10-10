package com.zhenghuaizhi.flashskip.platform.accessibility

import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.zhenghuaizhi.flashskip.domain.model.CaptureDiagnosticWindowSource
import com.zhenghuaizhi.flashskip.domain.model.CaptureFailureDiagnostics
import com.zhenghuaizhi.flashskip.domain.model.TargetWindowRecognitionOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class CaptureWindowResolutionSessionTest {
    @Test
    fun rejectedResolutionRetainsEventAndRootScalarsForCaptureTrail() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val request = AccessibilityCaptureWindowResolver.Request(
                sessionId = 6,
                eventPackageName = "target.example",
                eventWindowId = 8,
                host = VerifiedCaptureHost(
                    sessionId = 6,
                    packageName = "target.example",
                    activityName = "target.example.Main",
                    windowId = 3,
                ),
                allowActiveHostRootFallback = false,
                eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            )
            val records = mutableListOf<CaptureWindowResolutionRecord>()
            assertEquals(request.toRetryKey(), request.copy(eventType = AccessibilityEvent.TYPE_VIEW_CLICKED).toRetryKey())
            val resolution = CaptureWindowResolutionSession(
                scope = scope,
                elapsedRealtimeMs = { 400L },
                attemptResolver = CaptureWindowAttemptResolver { _, _, _, _ ->
                    AccessibilityCaptureWindowResolver.Result.Rejected(
                        reason = CaptureWindowBoundaryPolicy.RejectReason.WINDOW_ROOT_MISMATCH,
                        details = AccessibilityCaptureWindowResolver.ResolutionDetails(
                            rootWindowId = 8,
                            rootSource = CaptureWindowResolutionSource.WINDOW_LIST,
                            ownedChild = null,
                        ),
                    )
                },
                retryRequestProvider = { _, _ -> null },
                onRetryResolved = { _, _ -> error("rejected result cannot resolve a root") },
                recordResolution = records::add,
            )

            resolution.resolve(request, event = null) { error("rejected result cannot scan") }

            val record = records.single()
            assertEquals(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, record.request.eventType)
            assertEquals(8, record.request.eventWindowId)
            assertEquals(3, record.request.host.windowId)
            assertEquals(8, record.details.rootWindowId)
            assertEquals(CaptureWindowResolutionSource.WINDOW_LIST, record.details.rootSource)
            assertNull(record.details.ownedChild)
            assertEquals(TargetWindowRecognitionOutcome.WINDOW_ROOT_MISMATCH, record.outcome)
            assertEquals(400L, record.occurredAtElapsedMs)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun successfulResolutionIsInFailureSnapshotBeforeResolvedRootCallback() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val window = AccessibilityWindowInfo.obtain()
        val root = AccessibilityNodeInfo.obtain()
        try {
            shadowOf(window).setId(8)
            shadowOf(root).setAccessibilityWindowInfo(window)
            val request = AccessibilityCaptureWindowResolver.Request(
                sessionId = 6,
                eventPackageName = "target.example",
                eventWindowId = 8,
                host = VerifiedCaptureHost(
                    sessionId = 6,
                    packageName = "target.example",
                    activityName = "target.example.Main",
                    windowId = 3,
                ),
                allowActiveHostRootFallback = false,
                eventType = AccessibilityEvent.TYPE_VIEW_CLICKED,
            )
            val trail = CaptureDiagnosticTrail().apply { beginSession(6, 100) }
            var failureSnapshot: CaptureFailureDiagnostics? = null
            val resolution = CaptureWindowResolutionSession(
                scope = scope,
                elapsedRealtimeMs = { 400L },
                attemptResolver = CaptureWindowAttemptResolver { _, _, _, block ->
                    val details = AccessibilityCaptureWindowResolver.ResolutionDetails(
                        rootWindowId = root.windowId,
                        rootSource = CaptureWindowResolutionSource.WINDOW_LIST,
                        ownedChild = false,
                    )
                    block(
                        AccessibilityCaptureWindowResolver.ResolvedRoot(
                            node = root,
                            source = CaptureWindowResolutionSource.WINDOW_LIST,
                            ownedChild = false,
                        ),
                    )
                    AccessibilityCaptureWindowResolver.Result.Success(
                        value = Unit,
                        source = CaptureWindowResolutionSource.WINDOW_LIST,
                        ownedChild = false,
                        details = details,
                    )
                },
                retryRequestProvider = { _, _ -> null },
                onRetryResolved = { _, _ -> error("initial resolution cannot retry") },
                recordResolution = { record ->
                    trail.recordWindowResolution(
                        sessionId = record.request.sessionId,
                        occurredAtElapsedMs = record.occurredAtElapsedMs,
                        eventId = record.request.eventType,
                        eventWindowId = record.request.eventWindowId,
                        hostWindowId = record.request.host.windowId,
                        rootWindowId = record.details.rootWindowId,
                        rootSource = record.details.rootSource?.let {
                            CaptureDiagnosticWindowSource.valueOf(it.name)
                        },
                        ownedChild = record.details.ownedChild,
                        fallbackAllowed = record.request.allowActiveHostRootFallback,
                        outcome = record.outcome,
                        isRetry = record.isRetry,
                    )
                },
            )

            resolution.resolve(request, event = null) {
                failureSnapshot = CaptureFailureDiagnostics(
                    sourceAvailable = false,
                    eventTextCount = 0,
                    eventHasContentDescription = false,
                    sessionTrace = trail.snapshot(6),
                )
            }

            val trace = requireNotNull(failureSnapshot?.sessionTrace)
            assertEquals(1, trace.records.size)
            assertEquals(AccessibilityEvent.TYPE_VIEW_CLICKED, trace.records.single().eventId)
            assertEquals(8, trace.records.single().rootWindowId)
            assertEquals(
                TargetWindowRecognitionOutcome.RESOLVED_FROM_WINDOW_LIST,
                trace.records.single().resolutionOutcome,
            )
        } finally {
            root.recycle()
            window.recycle()
            scope.cancel()
        }
    }
}
