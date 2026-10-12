package com.zhenghuaizhi.flashskip.platform.accessibility

import com.zhenghuaizhi.flashskip.domain.model.CaptureDiagnosticClearReason
import com.zhenghuaizhi.flashskip.domain.model.CaptureDiagnosticRecord
import com.zhenghuaizhi.flashskip.domain.model.CaptureDiagnosticRecordKind
import com.zhenghuaizhi.flashskip.domain.model.CaptureDiagnosticScanPurpose
import com.zhenghuaizhi.flashskip.domain.model.CaptureDiagnosticSummary
import com.zhenghuaizhi.flashskip.domain.model.CaptureDiagnosticTrace
import com.zhenghuaizhi.flashskip.domain.model.CaptureDiagnosticWindowSource
import com.zhenghuaizhi.flashskip.domain.model.ClickRecoveryDiagnostics
import com.zhenghuaizhi.flashskip.domain.model.SemanticDiffDiagnostics
import com.zhenghuaizhi.flashskip.domain.model.TargetWindowRecognitionOutcome
import com.zhenghuaizhi.flashskip.domain.model.CandidateRecheckDiagnostics
import com.zhenghuaizhi.flashskip.domain.model.CaptureScanStrategy
import com.zhenghuaizhi.flashskip.domain.model.CaptureCandidateRejection

/** 当前服务进程内只保留定长轨迹；可选输出回调不影响纯计数与快照行为。 */
internal class CaptureDiagnosticTrail(
    private val maxRecords: Int = MAX_RECORDS,
    private val onRecord: (Long, CaptureDiagnosticRecord, ClickRecoveryDiagnostics?) -> Unit = { _, _, _ -> },
) {
    private var sessionId: Long? = null
    private var sessionStartedAtElapsedMs = 0L
    private val records = ArrayDeque<CaptureDiagnosticRecord>()
    private var summary = CaptureDiagnosticSummary()

    init {
        require(maxRecords > 0)
    }

    fun beginSession(sessionId: Long, startedAtElapsedMs: Long) {
        this.sessionId = sessionId
        sessionStartedAtElapsedMs = startedAtElapsedMs
        records.clear()
        summary = CaptureDiagnosticSummary()
    }

    fun reset() {
        sessionId = null
        sessionStartedAtElapsedMs = 0L
        records.clear()
        summary = CaptureDiagnosticSummary()
    }

    fun recordScan(
        sessionId: Long,
        occurredAtElapsedMs: Long,
        eventId: Int,
        eventWindowId: Int,
        hostWindowId: Int,
        rootWindowId: Int,
        purpose: CaptureDiagnosticScanPurpose,
        diagnostics: ClickRecoveryDiagnostics,
    ) {
        if (this.sessionId != sessionId) return
        val closeSemanticCount = diagnostics.explicitDismissNodeCount
        val complete = diagnostics.isCompleteTraversal()
        val previous = summary
        summary = previous.copy(
            scanCount = increment(previous.scanCount),
            completedScanCount = if (complete) increment(previous.completedScanCount)
            else previous.completedScanCount,
            incompleteScanCount = if (complete) previous.incompleteScanCount
            else increment(previous.incompleteScanCount),
            traversalLimitScanCount = if (diagnostics.traversalLimitReason != null) {
                increment(previous.traversalLimitScanCount)
            } else {
                previous.traversalLimitScanCount
            },
            scansWithCloseSemantics = if (closeSemanticCount > 0) {
                increment(previous.scansWithCloseSemantics)
            } else {
                previous.scansWithCloseSemantics
            },
            maxCloseSemanticCount = maxOf(
                previous.maxCloseSemanticCount,
                closeSemanticCount,
            ),
            maxCountdownSemanticCount = maxOf(
                previous.maxCountdownSemanticCount,
                diagnostics.countdownSemanticNodeCount,
            ),
            maxEffectiveCandidateCount = maxOf(
                previous.maxEffectiveCandidateCount,
                diagnostics.selectorCandidateNodeCount,
            ),
            rejectedCandidateCounts = mergeRejections(previous.rejectedCandidateCounts, diagnostics.rejectedCandidateCounts),
            continuedScanCount = if (diagnostics.scanStrategy == CaptureScanStrategy.CONTINUATION)
                increment(previous.continuedScanCount) else previous.continuedScanCount,
        )
        append(
            sessionId,
            occurredAtElapsedMs,
            CaptureDiagnosticRecord(
                elapsedMs = relativeTime(occurredAtElapsedMs),
                kind = CaptureDiagnosticRecordKind.SCAN,
                eventId = eventId,
                eventWindowId = eventWindowId,
                hostWindowId = hostWindowId,
                rootWindowId = rootWindowId,
                scanOutcome = diagnostics.outcome,
                scanPurpose = purpose,
                closeSemanticCount = closeSemanticCount,
                effectiveCandidateCount = diagnostics.selectorCandidateNodeCount,
                countdownSemanticCount = diagnostics.countdownSemanticNodeCount,
                visitedNodeCount = diagnostics.visitedNodeCount,
                scanCompleted = complete,
                traversalLimitReason = diagnostics.traversalLimitReason,
                scanStrategy = diagnostics.scanStrategy,
                coverageComplete = diagnostics.coverageComplete,
                continuationDepth = diagnostics.continuationDepth,
                budgetNodeReads = diagnostics.budgetNodeReads,
                rejectedCandidateCounts = diagnostics.rejectedCandidateCounts.toMap(),
                unavailableReason = diagnostics.unavailableReason,
            ),
            scanDiagnostics = diagnostics,
        )
    }

    fun recordCandidateRecheck(
        sessionId: Long,
        occurredAtElapsedMs: Long,
        eventId: Int,
        eventWindowId: Int,
        hostWindowId: Int,
        rootWindowId: Int,
        diagnostics: CandidateRecheckDiagnostics,
    ) {
        if (this.sessionId != sessionId ||
            diagnostics.queryCount == 0 && diagnostics.unknownCount == 0
        ) return
        val previous = summary
        summary = previous.copy(
            candidateRecheckCount = increment(previous.candidateRecheckCount),
            directedQueryCount = add(previous.directedQueryCount, diagnostics.queryCount),
            directedPresentCount = add(previous.directedPresentCount, diagnostics.presentCount),
            directedAbsentCount = add(previous.directedAbsentCount, diagnostics.absentCount),
            directedUnknownCount = add(previous.directedUnknownCount, diagnostics.unknownCount),
            directedAmbiguousCount = add(previous.directedAmbiguousCount, diagnostics.ambiguousCount),
            completedAnchorScanCount = add(previous.completedAnchorScanCount, diagnostics.completedAnchorScanCount),
        )
        append(sessionId, occurredAtElapsedMs, CaptureDiagnosticRecord(
            elapsedMs = relativeTime(occurredAtElapsedMs), kind = CaptureDiagnosticRecordKind.CANDIDATE_RECHECK,
            eventId = eventId, eventWindowId = eventWindowId, hostWindowId = hostWindowId,
            rootWindowId = rootWindowId, budgetNodeReads = diagnostics.budgetNodeReads,
            directedQueryCount = diagnostics.queryCount, directedPresentCount = diagnostics.presentCount,
            directedAbsentCount = diagnostics.absentCount, directedUnknownCount = diagnostics.unknownCount,
            directedAmbiguousCount = diagnostics.ambiguousCount,
            directedAnchorCompleteCount = diagnostics.completedAnchorScanCount,
            traversalLimitReason = diagnostics.traversalLimitReason,
        ))
    }

    fun recordSemanticDiff(
        sessionId: Long,
        occurredAtElapsedMs: Long,
        eventId: Int,
        eventWindowId: Int,
        hostWindowId: Int,
        rootWindowId: Int,
        diagnostics: SemanticDiffDiagnostics,
    ) {
        if (this.sessionId != sessionId) return
        val previous = summary
        summary = previous.copy(
            semanticDiffCount = increment(previous.semanticDiffCount),
            transitionSignalCount = if (diagnostics.transitionSignal) {
                increment(previous.transitionSignalCount)
            } else {
                previous.transitionSignalCount
            },
            maxDiffCurrentCandidateCount = maxOf(
                previous.maxDiffCurrentCandidateCount,
                diagnostics.currentCandidateCount,
            ),
            maxDiffStableCandidateCount = maxOf(
                previous.maxDiffStableCandidateCount,
                diagnostics.stableCandidateCount,
            ),
            maxDiffDisappearedCandidateCount = maxOf(
                previous.maxDiffDisappearedCandidateCount,
                diagnostics.disappearedCandidateCount,
            ),
        )
        append(
            sessionId,
            occurredAtElapsedMs,
            CaptureDiagnosticRecord(
                elapsedMs = relativeTime(occurredAtElapsedMs),
                kind = CaptureDiagnosticRecordKind.SEMANTIC_DIFF,
                eventId = eventId,
                eventWindowId = eventWindowId,
                hostWindowId = hostWindowId,
                rootWindowId = rootWindowId,
                semanticDiffOutcome = diagnostics.outcome,
                diffObservationCount = diagnostics.observationCount,
                diffCurrentCandidateCount = diagnostics.currentCandidateCount,
                diffStableCandidateCount = diagnostics.stableCandidateCount,
                diffDisappearedCandidateCount = diagnostics.disappearedCandidateCount,
                visitedNodeCount = diagnostics.visitedNodeCount,
                transitionSignal = diagnostics.transitionSignal,
                directedPresentCount = diagnostics.directedPresentCount,
                directedAbsentCount = diagnostics.directedAbsentCount,
                directedUnknownCount = diagnostics.directedUnknownCount,
                directedAmbiguousCount = diagnostics.directedAmbiguousCount,
            ),
        )
    }

    fun recordWindowResolution(
        sessionId: Long,
        occurredAtElapsedMs: Long,
        eventId: Int,
        eventWindowId: Int,
        hostWindowId: Int,
        rootWindowId: Int?,
        rootSource: CaptureDiagnosticWindowSource?,
        ownedChild: Boolean?,
        fallbackAllowed: Boolean,
        outcome: TargetWindowRecognitionOutcome,
        isRetry: Boolean,
    ) {
        if (this.sessionId != sessionId) return
        val previous = summary
        val outcomeCounts = previous.windowResolutionOutcomeCounts.toMutableMap()
        outcomeCounts[outcome] = increment(outcomeCounts[outcome] ?: 0)
        summary = previous.copy(
            windowResolutionCount = increment(previous.windowResolutionCount),
            windowResolutionRetryCount = if (isRetry) {
                increment(previous.windowResolutionRetryCount)
            } else {
                previous.windowResolutionRetryCount
            },
            windowResolutionOutcomeCounts = outcomeCounts,
        )
        append(
            sessionId,
            occurredAtElapsedMs,
            CaptureDiagnosticRecord(
                elapsedMs = relativeTime(occurredAtElapsedMs),
                kind = CaptureDiagnosticRecordKind.WINDOW_RESOLUTION,
                eventId = eventId,
                eventWindowId = eventWindowId,
                hostWindowId = hostWindowId,
                rootWindowId = rootWindowId,
                rootSource = rootSource,
                ownedChild = ownedChild,
                fallbackAllowed = fallbackAllowed,
                retry = isRetry,
                resolutionOutcome = outcome,
            ),
        )
    }

    fun recordHostWindowUpdate(
        sessionId: Long,
        occurredAtElapsedMs: Long,
        eventId: Int,
        eventWindowId: Int,
        previousWindowId: Int?,
        currentWindowId: Int,
        differenceCleared: Boolean,
        clearReason: CaptureDiagnosticClearReason?,
    ) {
        if (this.sessionId != sessionId) return
        val previous = summary
        summary = previous.copy(
            differenceClearCount = if (differenceCleared) {
                increment(previous.differenceClearCount)
            } else {
                previous.differenceClearCount
            },
            hostWindowUpdateCount = increment(previous.hostWindowUpdateCount),
        )
        append(
            sessionId,
            occurredAtElapsedMs,
            CaptureDiagnosticRecord(
                elapsedMs = relativeTime(occurredAtElapsedMs),
                kind = CaptureDiagnosticRecordKind.HOST_WINDOW_UPDATE,
                eventId = eventId,
                eventWindowId = eventWindowId,
                hostWindowId = currentWindowId,
                previousHostWindowId = previousWindowId,
                currentHostWindowId = currentWindowId,
                differenceCleared = differenceCleared,
                clearReason = clearReason,
            ),
        )
    }

    fun snapshot(expectedSessionId: Long): CaptureDiagnosticTrace? {
        if (sessionId != expectedSessionId) return null
        return CaptureDiagnosticTrace.snapshot(
            summary = summary.copy(
                totalRecordCount = summary.totalRecordCount,
                droppedRecordCount = (summary.totalRecordCount - records.size).coerceAtLeast(0),
            ),
            records = records.toList(),
        )
    }

    private fun append(
        sessionId: Long,
        occurredAtElapsedMs: Long,
        record: CaptureDiagnosticRecord,
        scanDiagnostics: ClickRecoveryDiagnostics? = null,
    ) {
        if (this.sessionId != sessionId) return
        val nextTotalCount = increment(summary.totalRecordCount)
        if (records.size == maxRecords) records.removeFirst()
        records.addLast(record.copy(elapsedMs = relativeTime(occurredAtElapsedMs)))
        summary = summary.copy(totalRecordCount = nextTotalCount)
        // 输出回调失败只丢失该输出，已保存的轨迹和后续采集流程继续工作。
        try { onRecord(sessionId, record, scanDiagnostics) } catch (_: Exception) { }
    }

    private fun relativeTime(nowElapsedMs: Long): Long =
        (nowElapsedMs - sessionStartedAtElapsedMs).coerceAtLeast(0)

    private fun mergeRejections(
        previous: Map<CaptureCandidateRejection, Int>,
        current: Map<CaptureCandidateRejection, Int>,
    ): Map<CaptureCandidateRejection, Int> = previous.toMutableMap().apply {
        current.forEach { (reason, count) -> this[reason] = add(getOrDefault(reason, 0), count) }
    }.toMap()

    private fun increment(value: Int): Int = if (value == Int.MAX_VALUE) value else value + 1
    private fun add(value: Int, count: Int): Int = (value.toLong() + count.coerceAtLeast(0))
        .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    private companion object {
        const val MAX_RECORDS = 48
    }
}
