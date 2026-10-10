package com.zhenghuaizhi.flashskip.platform.accessibility

import com.zhenghuaizhi.flashskip.domain.model.CaptureDiagnosticClearReason
import com.zhenghuaizhi.flashskip.domain.model.CaptureDiagnosticRecordKind
import com.zhenghuaizhi.flashskip.domain.model.CaptureDiagnosticSummary
import com.zhenghuaizhi.flashskip.domain.model.CaptureDiagnosticTrace
import com.zhenghuaizhi.flashskip.domain.model.CaptureDiagnosticWindowSource
import com.zhenghuaizhi.flashskip.domain.model.ClickRecoveryDiagnostics
import com.zhenghuaizhi.flashskip.domain.model.ClickRecoveryOutcome
import com.zhenghuaizhi.flashskip.domain.model.TargetWindowRecognitionOutcome
import com.zhenghuaizhi.flashskip.domain.model.TraversalLimitReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureDiagnosticTrailTest {
    @Test
    fun boundedRecentRecordsRetainEarlierSessionTotals() {
        val trail = CaptureDiagnosticTrail(maxRecords = 2).apply { beginSession(7, 100) }
        trail.recordScan(
            sessionId = 7,
            occurredAtElapsedMs = 110,
            eventId = 2048,
            eventWindowId = 8,
            hostWindowId = 3,
            rootWindowId = 8,
            purpose = com.zhenghuaizhi.flashskip.domain.model.CaptureDiagnosticScanPurpose.PRE_CLICK_REFRESH,
            diagnostics = ClickRecoveryDiagnostics(
                outcome = ClickRecoveryOutcome.SUCCESS,
                visitedNodeCount = 20,
                knownDismissNodeCount = 3,
                explicitDismissNodeCount = 3,
                selectorCandidateNodeCount = 5,
                countdownSemanticNodeCount = 1,
            ),
        )
        trail.recordScan(
            sessionId = 7,
            occurredAtElapsedMs = 120,
            eventId = 2048,
            eventWindowId = 8,
            hostWindowId = 3,
            rootWindowId = 8,
            purpose = com.zhenghuaizhi.flashskip.domain.model.CaptureDiagnosticScanPurpose.PRE_CLICK_REFRESH,
            diagnostics = ClickRecoveryDiagnostics(
                outcome = ClickRecoveryOutcome.SUCCESS,
                visitedNodeCount = 12,
                selectorCandidateNodeCount = 4,
                traversalLimitReason = TraversalLimitReason.TIME,
            ),
        )
        trail.recordScan(
            sessionId = 7,
            occurredAtElapsedMs = 130,
            eventId = 2048,
            eventWindowId = 8,
            hostWindowId = 3,
            rootWindowId = 8,
            purpose = com.zhenghuaizhi.flashskip.domain.model.CaptureDiagnosticScanPurpose.PRE_CLICK_REFRESH,
            diagnostics = ClickRecoveryDiagnostics(
                outcome = ClickRecoveryOutcome.AMBIGUOUS,
                visitedNodeCount = 9,
                selectorCandidateNodeCount = 3,
            ),
        )

        val trace = requireNotNull(trail.snapshot(7))

        assertEquals(2, trace.records.size)
        assertEquals(3, trace.summary.totalRecordCount)
        assertEquals(1, trace.summary.droppedRecordCount)
        assertEquals(3, trace.summary.scanCount)
        assertEquals(1, trace.summary.completedScanCount)
        assertEquals(2, trace.summary.incompleteScanCount)
        assertEquals(1, trace.summary.traversalLimitScanCount)
        assertEquals(1, trace.summary.scansWithCloseSemantics)
        assertEquals(3, trace.summary.maxCloseSemanticCount)
        assertEquals(1, trace.summary.maxCountdownSemanticCount)
        assertEquals(5, trace.summary.maxEffectiveCandidateCount)
        assertEquals(2048, trace.records.last().eventId)
    }

    @Test
    fun newSessionClearsPriorTrailAndRejectsLateRecords() {
        val trail = CaptureDiagnosticTrail().apply { beginSession(1, 100) }
        recordSimpleScan(trail, sessionId = 1, at = 110)

        trail.beginSession(sessionId = 2, startedAtElapsedMs = 200)
        recordSimpleScan(trail, sessionId = 1, at = 210)

        assertNull(trail.snapshot(1))
        assertEquals(0, requireNotNull(trail.snapshot(2)).summary.scanCount)
        assertTrue(requireNotNull(trail.snapshot(2)).records.isEmpty())
    }

    @Test
    fun hostWindowChangeRecordsClearAndSnapshotDoesNotExposeMutableBuffer() {
        val trail = CaptureDiagnosticTrail().apply { beginSession(4, 100) }
        trail.recordHostWindowUpdate(
            sessionId = 4,
            occurredAtElapsedMs = 125,
            eventId = 32,
            eventWindowId = 9,
            previousWindowId = 3,
            currentWindowId = 9,
            differenceCleared = true,
            clearReason = CaptureDiagnosticClearReason.HOST_WINDOW_CHANGED,
        )

        val trace = requireNotNull(trail.snapshot(4))
        val update = trace.records.single()

        assertEquals(1, trace.summary.differenceClearCount)
        assertEquals(CaptureDiagnosticRecordKind.HOST_WINDOW_UPDATE, update.kind)
        assertEquals(3, update.previousHostWindowId)
        assertEquals(9, update.currentHostWindowId)
        assertTrue(update.differenceCleared == true)
        assertEquals(CaptureDiagnosticClearReason.HOST_WINDOW_CHANGED, update.clearReason)
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (trace.records as MutableList).clear()
        }
    }

    @Test
    fun rejectedWindowResolutionRetainsResultAndInspectedRootWithoutClaimingOwnership() {
        val trail = CaptureDiagnosticTrail().apply { beginSession(9, 0) }
        trail.recordWindowResolution(
            sessionId = 9,
            occurredAtElapsedMs = 20,
            eventId = 32,
            eventWindowId = 8,
            hostWindowId = 3,
            rootWindowId = 8,
            rootSource = CaptureDiagnosticWindowSource.WINDOW_LIST,
            ownedChild = null,
            fallbackAllowed = false,
            outcome = TargetWindowRecognitionOutcome.WINDOW_ROOT_MISMATCH,
            isRetry = false,
        )

        val record = requireNotNull(trail.snapshot(9)).records.single()

        assertEquals(TargetWindowRecognitionOutcome.WINDOW_ROOT_MISMATCH, record.resolutionOutcome)
        assertEquals(8, record.rootWindowId)
        assertFalse(record.ownedChild == true)
        assertEquals(false, record.fallbackAllowed)
    }

    @Test
    fun earlyWindowRejectAndHostUpdateRemainInSummaryAfterRecentRecordsDrop() {
        val trail = CaptureDiagnosticTrail().apply { beginSession(12, 0) }
        trail.recordWindowResolution(
            sessionId = 12,
            occurredAtElapsedMs = 1,
            eventId = 32,
            eventWindowId = 8,
            hostWindowId = 3,
            rootWindowId = 8,
            rootSource = CaptureDiagnosticWindowSource.WINDOW_LIST,
            ownedChild = null,
            fallbackAllowed = false,
            outcome = TargetWindowRecognitionOutcome.WINDOW_ROOT_MISMATCH,
            isRetry = false,
        )
        trail.recordHostWindowUpdate(
            sessionId = 12,
            occurredAtElapsedMs = 2,
            eventId = 32,
            eventWindowId = 8,
            previousWindowId = 3,
            currentWindowId = 8,
            differenceCleared = true,
            clearReason = CaptureDiagnosticClearReason.HOST_WINDOW_CHANGED,
        )
        repeat(49) { index ->
            trail.recordWindowResolution(
                sessionId = 12,
                occurredAtElapsedMs = index + 3L,
                eventId = 32,
                eventWindowId = 8,
                hostWindowId = 3,
                rootWindowId = 8,
                rootSource = CaptureDiagnosticWindowSource.WINDOW_LIST,
                ownedChild = false,
                fallbackAllowed = false,
                outcome = TargetWindowRecognitionOutcome.RESOLVED_FROM_WINDOW_LIST,
                isRetry = index % 2 == 0,
            )
        }

        val trace = requireNotNull(trail.snapshot(12))

        assertEquals(48, trace.records.size)
        assertEquals(51, trace.summary.totalRecordCount)
        assertEquals(3, trace.summary.droppedRecordCount)
        assertTrue(trace.records.none {
            it.resolutionOutcome == TargetWindowRecognitionOutcome.WINDOW_ROOT_MISMATCH
        })
        assertTrue(trace.records.none { it.kind == CaptureDiagnosticRecordKind.HOST_WINDOW_UPDATE })
        assertEquals(50, trace.summary.windowResolutionCount)
        assertEquals(25, trace.summary.windowResolutionRetryCount)
        assertEquals(
            1,
            trace.summary.windowResolutionOutcomeCounts[
                TargetWindowRecognitionOutcome.WINDOW_ROOT_MISMATCH
            ],
        )
        assertEquals(
            49,
            trace.summary.windowResolutionOutcomeCounts[
                TargetWindowRecognitionOutcome.RESOLVED_FROM_WINDOW_LIST
            ],
        )
        assertEquals(1, trace.summary.hostWindowUpdateCount)
        assertEquals(1, trace.summary.differenceClearCount)
    }

    @Test
    fun traceSnapshotCopiesAndFreezesWindowOutcomeCounts() {
        val mutableCounts = mutableMapOf(
            TargetWindowRecognitionOutcome.WINDOW_ROOT_MISMATCH to 1,
        )
        val trace = CaptureDiagnosticTrace.snapshot(
            summary = CaptureDiagnosticSummary(
                windowResolutionCount = 1,
                windowResolutionOutcomeCounts = mutableCounts,
            ),
            records = emptyList(),
        )

        mutableCounts[TargetWindowRecognitionOutcome.WINDOW_ROOT_MISMATCH] = 9

        assertEquals(
            1,
            trace.summary.windowResolutionOutcomeCounts[
                TargetWindowRecognitionOutcome.WINDOW_ROOT_MISMATCH
            ],
        )
        @Suppress("UNCHECKED_CAST")
        val frozenCounts = trace.summary.windowResolutionOutcomeCounts as
            MutableMap<TargetWindowRecognitionOutcome, Int>
        assertThrows(UnsupportedOperationException::class.java) {
            frozenCounts[TargetWindowRecognitionOutcome.WINDOW_ROOT_MISMATCH] = 5
        }
    }

    private fun recordSimpleScan(trail: CaptureDiagnosticTrail, sessionId: Long, at: Long) {
        trail.recordScan(
            sessionId = sessionId,
            occurredAtElapsedMs = at,
            eventId = 2048,
            eventWindowId = 3,
            hostWindowId = 3,
            rootWindowId = 3,
            purpose = com.zhenghuaizhi.flashskip.domain.model.CaptureDiagnosticScanPurpose.PRE_CLICK_REFRESH,
            diagnostics = ClickRecoveryDiagnostics(outcome = ClickRecoveryOutcome.NO_MATCH),
        )
    }
}
