package com.zhenghuaizhi.flashskip.platform.accessibility

import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.zhenghuaizhi.flashskip.domain.model.CapturePhase
import com.zhenghuaizhi.flashskip.domain.model.CaptureDiagnosticRecord
import com.zhenghuaizhi.flashskip.domain.model.CaptureDiagnosticRecordKind
import com.zhenghuaizhi.flashskip.domain.model.CaptureDiagnosticSummary
import com.zhenghuaizhi.flashskip.domain.model.CaptureDiagnosticTrace
import com.zhenghuaizhi.flashskip.domain.model.CaptureFailureDiagnostics
import com.zhenghuaizhi.flashskip.domain.model.TargetWindowDiagnostics
import com.zhenghuaizhi.flashskip.domain.model.TargetWindowRecognitionOutcome
import com.zhenghuaizhi.flashskip.domain.model.TraversalLimitReason
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
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], shadows = [CaptureTraceLogTest.CountingRecordShadow::class])
class CaptureTraceLogTest {
    private lateinit var logger: CaptureTraceLogger

    @Before
    fun resetLogs() {
        ShadowLog.clear()
        CountingRecordShadow.sourceReads = 0
        logger = CaptureTraceLogger(enabled = true)
    }

    @Test
    fun debugEventAndStateLogOnlyMetadataWithoutReadingSource() {
        val event = sensitiveEvent()
        // 先证明 shadow 能捕获节点读取，避免“从未读取”的断言因测试替身失效而误通过。
        event.source
        assertEquals(1, CountingRecordShadow.sourceReads)
        CountingRecordShadow.sourceReads = 0

        logger.state(session())
        logger.state(session())
        logger.event(session(), event)

        val output = traceOutput()
        assertTrue(output.contains("stage=accessibility_event"))
        assertTrue(output.contains("phase=OBSERVING_CLICK"))
        assertTrue(output.contains("recordCount=0"))
        assertEquals(1, logs("capture_state").size)
        assertFalse(output.contains("private-"))
        assertEquals(0, CountingRecordShadow.sourceReads)
    }

    @Test
    fun resolutionLogDoesNotExposeHostOrSourceIdentity() {
        val host = VerifiedCaptureHost(7, "private-host", "private-activity", 3)
        val request = AccessibilityCaptureWindowResolver.Request(
            sessionId = 7,
            host = host,
            eventPackageName = "private-event",
            eventWindowId = 3,
        )
        logger.windowResolution(
            CaptureWindowResolutionRecord(
                request = request,
                outcome = TargetWindowRecognitionOutcome.WINDOW_ROOT_MISMATCH,
                isRetry = false,
                occurredAtElapsedMs = 0,
                details = AccessibilityCaptureWindowResolver.ResolutionDetails(
                    matchingWindowCount = 1,
                    rejectReason = CaptureWindowBoundaryPolicy.RejectReason.PACKAGE_MISMATCH,
                ),
            ),
        )

        val output = traceOutput()
        assertTrue(output.contains("windowCount=1"))
        assertTrue(output.contains("rejectReason=PACKAGE_MISMATCH"))
        assertFalse(output.contains("private-"))
    }

    @Test
    fun arbitraryTextCollectionsAndObjectsAreNotFormatted() {
        logger.stage(
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
        logger.record(
            7,
            CaptureDiagnosticRecord(0, CaptureDiagnosticRecordKind.SCAN),
            scanDiagnostics = ClickRecoveryDiagnostics(ClickRecoveryOutcome.SUCCESS),
        )
        logger.summary(session(), CaptureTraceConclusion.FAILED, diagnostics = { error("disabled diagnostics") }, targetWindow = { error("disabled window") })
        logger.stage(7, "capture_failed", "failure" to ClickRecoveryOutcome.NO_ROOT)

        assertTrue(ShadowLog.getLogsForTag(CaptureTraceLogger.TAG).isEmpty())
        assertEquals(0, CountingRecordShadow.sourceReads)
    }

    @Test
    fun interleavedStagesAggregateDuplicatesAndFlushAccurateCounts() {
        logger.state(session())
        val event = sensitiveEvent().apply { eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED }
        repeat(5) {
            logger.event(session(), event)
            logger.stage(7, "window_resolution", "outcome" to TargetWindowRecognitionOutcome.RESOLVED_FROM_WINDOW_LIST)
        }

        assertEquals(1, logs("accessibility_event").size)
        assertEquals(1, logs("window_resolution").size)
        logger.flush()

        assertEquals(2, logs("accessibility_event").size)
        assertEquals(2, logs("window_resolution").size)
        assertTrue(logs("accessibility_event").last().contains("repeatCount=4"))
        assertTrue(logs("window_resolution").last().contains("repeatCount=4"))
        logger.flush()
        assertEquals(2, logs("window_resolution").size)
        logger.stage(7, "window_resolution", "outcome" to TargetWindowRecognitionOutcome.WINDOW_ROOT_MISMATCH)
        assertEquals(3, logs("window_resolution").size)
    }

    @Test
    fun everyClickIsLoggedEvenWhenMetadataMatches() {
        logger.state(session())
        val event = sensitiveEvent()
        repeat(5) { logger.event(session(), event) }

        assertEquals(5, logs("accessibility_event").size)
        assertTrue(logs("accessibility_event").none { it.contains("repeatCount") })
        assertEquals(0, CountingRecordShadow.sourceReads)
    }

    @Test
    fun lateSessionCannotResetCurrentAggregationAndNewSessionFlushesOldSession() {
        logger.state(session())
        repeat(3) { logger.stage(7, "window_resolution", "accepted" to true) }
        logger.state(session().copy(id = 8))
        repeat(2) { logger.stage(8, "window_resolution", "accepted" to false) }
        logger.stage(7, "late_callback", "accepted" to true)
        logger.flush()

        assertTrue(logs("window_resolution").any { it.contains("session=7") && it.contains("repeatCount=2") })
        assertTrue(logs("window_resolution").any { it.contains("session=8") && it.contains("repeatCount=1") })
        assertTrue(logs("late_callback").single().contains("late=true"))
        assertTrue(logs("late_callback").single().contains("session=7"))
    }

    @Test
    fun aggregationEvictsOldestStageWithoutDroppingItsRepeatCount() {
        logger.state(session())
        repeat(2) { logger.stage(7, "oldest", "count" to 1) }
        repeat(24) { logger.stage(7, "fixed_stage_$it", "count" to it) }

        assertEquals(2, logs("oldest").size)
        assertTrue(logs("oldest").last().contains("repeatCount=1"))
    }

    @Test
    fun scanContainsOriginalProbeFactsInOneLogLine() {
        logger.state(session())
        logger.record(
            7,
            CaptureDiagnosticRecord(20, CaptureDiagnosticRecordKind.SCAN, scanOutcome = ClickRecoveryOutcome.TRAVERSAL_LIMIT, traversalLimitReason = TraversalLimitReason.DEPTH, closeSemanticCount = 1, scanCompleted = false),
            scanDiagnostics = ClickRecoveryDiagnostics(ClickRecoveryOutcome.TRAVERSAL_LIMIT, maxDepth = 9, elapsedMs = 30, semanticNodeCount = 4, matchedNodeCount = 2, knownDismissNodeCount = 1, countdownActionChainCount = 3),
            allowBareNumericCountdown = true,
        )

        val line = logs("capture_trace_scan").single()
        for (field in listOf("depth=9", "elapsedMs=30", "semantic=4", "matched=2", "knownDismiss=1", "countdownActionChains=3", "allowBareNumeric=true", "closeSemantics=1", "limit=DEPTH", "complete=false")) {
            assertTrue(field, line.contains(field))
        }
        assertTrue(logs("pre_click_probe").isEmpty())
    }

    @Test
    fun terminalSummaryMigratesNumericSnapshotAndDoesNotRepeatUnchangedTrail() {
        val trace = CaptureDiagnosticTrace.snapshot(
            CaptureDiagnosticSummary(scanCount = 15, completedScanCount = 1, incompleteScanCount = 14, windowResolutionOutcomeCounts = mapOf(TargetWindowRecognitionOutcome.WINDOW_ROOT_MISMATCH to 14, TargetWindowRecognitionOutcome.RESOLVED_FROM_WINDOW_LIST to 1)),
            List(60) { CaptureDiagnosticRecord(it.toLong(), CaptureDiagnosticRecordKind.SCAN, eventWindowId = it, traversalLimitReason = TraversalLimitReason.TIME) },
        )
        val diagnostics = CaptureFailureDiagnostics(false, 2, true, sessionTrace = trace)
        val target = TargetWindowDiagnostics(viewClickedEventCount = 0, lastPackageName = "private-package", lastClassName = "private-class")
        logger.summary(session(), CaptureTraceConclusion.FAILED, { diagnostics }, { target })
        logger.state(session())
        logger.summary(session(), CaptureTraceConclusion.CANCELLED, { diagnostics }, { target })

        assertEquals(2, logs("capture_summary").size)
        assertTrue(logs("capture_summary").last().contains("trailUnchanged=true"))
        assertEquals(1, logs("capture_scan_summary").size)
        assertTrue(logs("capture_scan_summary").single().contains("scans=15 complete=1 incomplete=14"))
        assertTrue(logs("capture_scan_summary").single().contains("droppedRecords=12"))
        assertEquals(48, logs("capture_recent_record").size)
        assertEquals(2, logs("capture_window_outcome").size)
        assertTrue(traceOutput().contains("clickEvents=0"))
        assertFalse(traceOutput().contains("private-"))
        assertEquals(0, CountingRecordShadow.sourceReads)
    }

    private fun logs(stage: String): List<String> = ShadowLog.getLogsForTag(CaptureTraceLogger.TAG)
        .map { it.msg }.filter { it.contains("stage=$stage ") }

    private fun session() = CaptureSession(
        id = 7,
        targetAppName = "private-app-name",
        packageName = "private-package",
        activityName = "private-activity",
        phase = CapturePhase.OBSERVING_CLICK,
        phaseStartedAtEpochMs = 0,
        deadlineAtEpochMs = 30_000,
    )

    // 事件池可能带入其他配置的 shadow，直接构造以保证节点读取计数替身确实生效。
    private fun sensitiveEvent() = ReflectionHelpers.callConstructor(AccessibilityEvent::class.java).apply {
        eventType = AccessibilityEvent.TYPE_VIEW_CLICKED
        packageName = "private-package"
        className = "private-class"
        contentDescription = "private-description"
        text.add("private-page-text")
    }

    private fun traceOutput(): String = ShadowLog.getLogsForTag(CaptureTraceLogger.TAG)
        .joinToString("\n") { it.msg }

    @Implements(AccessibilityEvent::class)
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
