package com.zhenghuaizhi.flashskip.platform.accessibility

import android.os.SystemClock
import android.util.Log
import com.zhenghuaizhi.flashskip.platform.diagnostics.ProcessDiagnosticIdentifiers
import com.zhenghuaizhi.flashskip.platform.diagnostics.CaptureDiagnosticSink
import com.zhenghuaizhi.flashskip.domain.model.RuleCaptureState
import com.zhenghuaizhi.flashskip.domain.model.CapturePhase
import java.io.IOException
import android.view.accessibility.AccessibilityEvent
import com.zhenghuaizhi.flashskip.BuildConfig
import com.zhenghuaizhi.flashskip.domain.model.CaptureDiagnosticRecord
import com.zhenghuaizhi.flashskip.domain.model.CaptureDiagnosticTrace
import com.zhenghuaizhi.flashskip.domain.model.CaptureFailureDiagnostics
import com.zhenghuaizhi.flashskip.domain.model.CaptureSession
import com.zhenghuaizhi.flashskip.domain.model.CaptureSample
import com.zhenghuaizhi.flashskip.domain.model.ClickRecoveryDiagnostics
import com.zhenghuaizhi.flashskip.domain.model.CandidateRecheckDiagnostics
import com.zhenghuaizhi.flashskip.domain.model.CaptureTimingDiagnostics
import com.zhenghuaizhi.flashskip.domain.model.CaptureEventTiming
import com.zhenghuaizhi.flashskip.domain.model.CaptureCandidateRejection
import com.zhenghuaizhi.flashskip.domain.model.TargetWindowDiagnostics
import com.zhenghuaizhi.flashskip.domain.model.CaptureStartupDiagnostics

/** 采集诊断仅限 Debug 构建，且只输出状态、计数与耗时，不读取事件源节点。 */
internal object CaptureTraceLog : CaptureTraceLogger(enabled = BuildConfig.DEBUG)

internal open class CaptureTraceLogger(
    private val enabled: Boolean,
    private val identifiers: ProcessDiagnosticIdentifiers = ProcessDiagnosticIdentifiers.Process,
    private val logOutput: (Int, String, String) -> Unit = { priority, tag, message -> Log.println(priority, tag, message); Unit },
) {
    private var localSink: CaptureDiagnosticSink? = null
    private var sequence = 0L
    private val sessionLabels = linkedMapOf<Long, Long>()
    private var nextSessionLabel = 0L
    private var currentSessionId: Long? = null
    private var stateObserved = false
    private var lastState: List<Pair<String, Any?>>? = null
    private val sessionStarts = linkedMapOf<Long, Long>()
    private val repeatedStages = linkedMapOf<String, RepeatedStage>()
    private var lastTrace: Pair<Long, CaptureDiagnosticTrace>? = null
    private var lastSample: Pair<Long, List<Pair<String, Any?>>>? = null

    /** 本地写入器归应用进程所有，服务销毁不会取消尚未保存的诊断。 */
    @Synchronized
    fun installSink(sink: CaptureDiagnosticSink) {
        if (enabled) localSink = sink
    }

    @Synchronized
    fun beginSession(sessionId: Long) {
        if (!enabled) return
        try { localSink?.beginSession(identifiers.sessionFileId(sessionId)) } catch (_: Exception) { }
    }

    /** 启动与服务边界没有会话时也必须留证；字段仍只接受安全元数据。 */
    @Synchronized
    fun lifecycle(name: String, vararg fields: Pair<String, Any?>) {
        if (enabled) write(null, name, fields.toList(), essential = true)
    }

    @Synchronized
    fun boundary(sessionId: Long?, name: String, vararg fields: Pair<String, Any?>) {
        if (enabled) write(sessionId, name, fields.toList(), essential = true)
    }

    /** 应用侧观察持久状态，不改变服务侧阶段合并、时序和上下文清理顺序。 */
    @Synchronized
    fun persistedState(previous: RuleCaptureState?, current: RuleCaptureState) {
        if (!enabled || previous == current) return
        val before = previous?.activeSession
        val session = current.activeSession
        // DataStore 可先发布状态再恢复提交者；观察到新会话时先登记，避免先行记录被误丢弃。
        if (session != null && before?.id != session.id) beginSession(session.id)
        if (before != null && before.id != session?.id) {
            val saved = current.samples.any { it.id == before.id && it !in previous.samples }
            write(before.id, "persisted_session_ended", listOf(
                "conclusion" to if (saved) CaptureTraceConclusion.SAVED else CaptureTraceConclusion.CANCELLED,
                "phase" to before.phase,
                "failure" to before.failure,
            ), essential = true)
        }
        write(session?.id, "persisted_capture_state", listOf(
            "firstRead" to (previous == null),
            "hasSession" to (session != null),
            "phase" to session?.phase,
            "failure" to session?.failure,
            "hasPendingSample" to (session?.pendingSample != null),
            "hasObservedCandidate" to (session?.pendingObservedCandidate != null),
            "hasFailureDiagnostics" to (session?.failureDiagnostics != null),
            "hasTargetDiagnostics" to (session?.targetWindowDiagnostics != null),
            "sampleCount" to current.samples.size,
        ), essential = true)
        if (session?.phase == CapturePhase.FAILED &&
            (before?.id != session.id || before.failureDiagnostics != session.failureDiagnostics ||
                before.phase != session.phase)
        ) {
            summary(session, CaptureTraceConclusion.FAILED,
                diagnostics = { session.failureDiagnostics },
                targetWindow = { session.targetWindowDiagnostics })
        }
    }

    @Synchronized
    fun management(sessionId: Long?, vararg fields: Pair<String, Any?>) {
        if (enabled) write(sessionId, "rule_management", fields.toList(), essential = true)
    }

    @Synchronized
    fun state(session: CaptureSession?) {
        if (!enabled) return
        stateObserved = true
        if (session?.id != currentSessionId) {
            flush()
            repeatedStages.clear()
            lastState = null
            if (lastTrace?.first != session?.id) lastTrace = null
            if (lastSample?.first != session?.id) lastSample = null
            val previousId = currentSessionId
            currentSessionId = session?.id
            if (session == null) {
                if (previousId != null) write(previousId, "capture_session_cleared", emptyList())
                return
            }
            beginSession(session.id)
            if (session.id !in sessionStarts) rememberSession(session.id)
        }
        if (session == null) return
        val fields = listOf(
            "phase" to session.phase,
            "failure" to session.failure,
            "hasPendingSample" to (session.pendingSample != null),
            "hasObservedCandidate" to (session.pendingObservedCandidate != null),
            "candidateClickListening" to session.listensForCandidateClick,
            "evidence" to session.pendingSample?.evidence,
        )
        if (fields == lastState) return
        flush()
        lastState = fields
        write(session.id, "capture_state", fields)
        sample(session)
    }

    fun event(
        session: CaptureSession,
        event: AccessibilityEvent,
        route: CaptureTraceEventRoute = CaptureTraceEventRoute.DISPATCHED,
        reason: CaptureTraceReason? = null,
        timing: CaptureEventTiming? = null,
    ) {
        if (!enabled) return
        // 诊断不需要页面语义；即使已验证目标，也不读取 text、description 或 source。
        stage(
            sessionId = session.id,
            name = "accessibility_event",
            "phase" to session.phase,
            "type" to event.eventType,
            "action" to event.action,
            "contentChanges" to event.contentChangeTypes,
            "recordCount" to event.recordCount,
            "eventWindowId" to event.windowId,
            "route" to route,
            "reason" to reason,
            "eventUptimeMs" to timing?.eventUptimeMs,
            "receivedUptimeMs" to timing?.receivedUptimeMs,
            "receivedElapsedMs" to timing?.receivedElapsedMs,
            "receivedEpochMs" to timing?.receivedEpochMs,
            "dispatchDelayMs" to timing?.dispatchDelayMs,
            "eventClockStatus" to timing?.clockStatus,
        )
    }

    @Synchronized
    fun timing(sessionId: Long, diagnostics: CaptureTimingDiagnostics) {
        if (!enabled) return
        val first = diagnostics.firstTargetEvent
        val last = diagnostics.lastTargetEvent
        write(sessionId, "capture_timing_summary", listOf(
            "receivedEvents" to diagnostics.receivedEventCount,
            "targetEvents" to diagnostics.targetEventCount,
            "preVerificationClicks" to diagnostics.preVerificationTargetClickCount,
            "invalidTimestamps" to diagnostics.invalidTimestampEventCount,
            "pendingPackageEvictions" to diagnostics.pendingPackageEvictionCount,
            "firstTargetEventUptimeMs" to first?.eventUptimeMs,
            "firstTargetReceivedUptimeMs" to first?.receivedUptimeMs,
            "firstTargetReceivedElapsedMs" to first?.receivedElapsedMs,
            "firstTargetReceivedEpochMs" to first?.receivedEpochMs,
            "firstTargetEventClockStatus" to first?.clockStatus,
            "lastTargetEventUptimeMs" to last?.eventUptimeMs,
            "lastTargetReceivedEpochMs" to last?.receivedEpochMs,
            "maxTargetDispatchDelayMs" to diagnostics.maxTargetDispatchDelayMs,
            "firstVerifiedAtElapsedMs" to diagnostics.firstVerifiedAtElapsedMs,
            "firstVerifiedAtUptimeMs" to diagnostics.firstVerifiedAtUptimeMs,
            "firstScanAtElapsedMs" to diagnostics.firstScanAtElapsedMs,
            "targetEventToVerifiedUptimeMs" to diagnostics.targetEventToVerifiedUptimeMs,
            "targetReceivedToVerifiedMs" to diagnostics.targetReceivedToVerifiedMs,
            "verifiedToFirstScanMs" to diagnostics.verifiedToFirstScanMs,
        ))
    }

    @Synchronized
    fun startupPage(sessionId: Long, page: StartupPageObservation) {
        if (!enabled) return
        val caps = page.capabilities
        stage(sessionId, "startup_page_observation",
            "outcome" to page.outcome, "windowId" to page.windowId,
            "hostVerified" to page.hostVerified,
            "observedAtElapsedMs" to page.observedAtElapsedMs,
            "observedAtEpochMs" to page.observedAtEpochMs,
            "visited" to page.scan?.visitedNodeCount, "nodeReads" to page.scan?.budgetNodeReads,
            "coverageComplete" to page.scan?.coverageComplete, "limit" to page.scan?.traversalLimitReason,
            "strategy" to page.scan?.scanStrategy, "scanMs" to page.scan?.elapsedMs,
            "visibleNodes" to caps.visibleNodeCount, "actionableNodes" to caps.actionableNodeCount,
            "actionClickNodes" to caps.actionClickNodeCount, "dismissHints" to caps.dismissHintNodeCount,
            "dismissActionChains" to caps.dismissActionChainCount,
            "stableDismissCandidates" to page.stableDismissCandidateCount)
    }

    @Synchronized
    fun startupSummary(sessionId: Long, startup: CaptureStartupDiagnostics) {
        if (!enabled) return
        write(sessionId, "startup_observation_summary", listOf(
            "targetConfirmed" to startup.targetConfirmed,
            "pageObservations" to startup.pageObservationCount,
            "provisionalObservations" to startup.provisionalObservationCount,
            "firstObservedAtElapsedMs" to startup.firstObservedAtElapsedMs,
            "firstObservedAtEpochMs" to startup.firstObservedAtEpochMs,
            "firstDismissActionAtEpochMs" to startup.firstDismissActionAtEpochMs,
            "maxVisibleNodes" to startup.maxVisibleNodeCount,
            "maxActionableNodes" to startup.maxActionableNodeCount,
            "maxActionClickNodes" to startup.maxActionClickNodeCount,
            "maxDismissHints" to startup.maxDismissHintNodeCount,
            "maxDismissActionChains" to startup.maxDismissActionChainCount,
            "maxStableDismissCandidates" to startup.maxStableDismissCandidateCount,
            "completeScans" to startup.completeScanCount,
            "observedBeforeFirstTargetEventReceived" to startup.observedBeforeFirstTargetEventReceived,
            "observedBeforeVerification" to startup.observedBeforeVerification,
            "pendingBoundaryEvictions" to startup.pendingBoundaryEvictionCount))
    }

    fun windowResolution(record: CaptureWindowResolutionRecord) {
        if (!enabled) return
        val request = record.request
        val details = record.details
        stage(
            request.sessionId,
            "window_resolution",
            "eventId" to request.eventType,
            "eventWindowId" to request.eventWindowId,
            "hostWindowId" to request.host.windowId,
            "rootWindowId" to details.rootWindowId,
            "rootSource" to details.rootSource,
            "ownedChild" to details.ownedChild,
            "activeHostFallback" to request.allowActiveHostRootFallback,
            "windowCount" to details.matchingWindowCount,
            "windowType" to details.matchingWindowType,
            "windowActive" to details.matchingWindowActive,
            "windowRoot" to details.matchingRootAvailable,
            "activeRootType" to details.activeRootWindowType,
            "activeRootActive" to details.activeRootWindowActive,
            "sourceWindowType" to details.sourceWindowType,
            "sourceWindowActive" to details.sourceWindowActive,
            "outcome" to record.outcome,
            "rejectReason" to details.rejectReason,
            "retry" to record.isRetry,
        )
    }

    @Synchronized
    fun record(
        sessionId: Long,
        record: CaptureDiagnosticRecord,
        scanDiagnostics: ClickRecoveryDiagnostics? = null,
        allowBareNumericCountdown: Boolean = false,
        terminal: Boolean = false,
    ) {
        if (!enabled) return
        val fields = listOf(
            "kind" to record.kind,
            "recordElapsedMs" to record.elapsedMs,
            "eventId" to record.eventId,
            "eventWindowId" to record.eventWindowId,
            "hostWindowId" to record.hostWindowId,
            "rootWindowId" to record.rootWindowId,
            "rootSource" to record.rootSource,
            "ownedChild" to record.ownedChild,
            "fallbackAllowed" to record.fallbackAllowed,
            "retry" to record.retry,
            "resolutionOutcome" to record.resolutionOutcome,
            "scanOutcome" to record.scanOutcome,
            "scanPurpose" to record.scanPurpose,
            "closeSemantics" to record.closeSemanticCount,
            "selectorCandidates" to record.effectiveCandidateCount,
            "countdownSemantics" to record.countdownSemanticCount,
            "visited" to record.visitedNodeCount,
            "complete" to record.scanCompleted,
            "limit" to record.traversalLimitReason,
            "diffOutcome" to record.semanticDiffOutcome,
            "observations" to record.diffObservationCount,
            "current" to record.diffCurrentCandidateCount,
            "stable" to record.diffStableCandidateCount,
            "disappeared" to record.diffDisappearedCandidateCount,
            "transition" to record.transitionSignal,
            "previousHostWindowId" to record.previousHostWindowId,
            "currentHostWindowId" to record.currentHostWindowId,
            "differenceCleared" to record.differenceCleared,
            "clearReason" to record.clearReason,
            "scanStrategy" to record.scanStrategy,
            "coverageComplete" to record.coverageComplete,
            "continuationDepth" to record.continuationDepth,
            "budgetNodeReads" to record.budgetNodeReads,
            "directedQueries" to record.directedQueryCount,
            "directedPresent" to record.directedPresentCount,
            "directedAbsent" to record.directedAbsentCount,
            "directedUnknown" to record.directedUnknownCount,
            "directedAmbiguous" to record.directedAmbiguousCount,
            "anchorComplete" to record.directedAnchorCompleteCount,
            "unavailableReason" to record.unavailableReason,
        ) + rejectionFields(record.rejectedCandidateCounts) + if (scanDiagnostics == null) emptyList() else listOf(
            "allowBareNumeric" to allowBareNumericCountdown,
            "depth" to scanDiagnostics.maxDepth,
            "elapsedMs" to scanDiagnostics.elapsedMs,
            "semantic" to scanDiagnostics.semanticNodeCount,
            "matched" to scanDiagnostics.matchedNodeCount,
            "knownDismiss" to scanDiagnostics.knownDismissNodeCount,
            "countdownActionChains" to scanDiagnostics.countdownActionChainCount,
            "cursorReset" to scanDiagnostics.cursorReset,
            "pendingBranches" to scanDiagnostics.pendingBranchCount,
        )
        if (terminal) write(sessionId, "capture_recent_record", fields)
        else stage(sessionId, "capture_trace_${record.kind.name.lowercase()}", *fields.toTypedArray())
    }

    /** 在上下文清空前惰性取快照；Release 不读取诊断提供者。 */
    @Synchronized
    fun summary(
        session: CaptureSession,
        reason: CaptureTraceConclusion,
        diagnostics: () -> CaptureFailureDiagnostics?,
        targetWindow: () -> TargetWindowDiagnostics?,
        savedSample: CaptureSample? = null,
    ) {
        if (!enabled) return
        flush()
        if (session.id !in sessionStarts) rememberSession(session.id)
        val failure = diagnosticSnapshot(session.id, diagnostics)
        val traceUnchanged = failure?.sessionTrace?.let { lastTrace == (session.id to it) } == true
        write(
            session.id,
            "capture_summary",
            listOf(
                "conclusion" to reason,
                "phase" to session.phase,
                "failure" to session.failure,
                "candidateClickListening" to session.listensForCandidateClick,
                "hasPendingSample" to (session.pendingSample != null),
                "hasObservedCandidate" to (session.pendingObservedCandidate != null),
                "evidence" to (savedSample?.evidence ?: session.pendingSample?.evidence),
                "savedSample" to (savedSample != null),
                "trailUnchanged" to traceUnchanged,
            ),
        )
        sample(session, savedSample)
        diagnosticSnapshot(session.id, targetWindow)?.let { window ->
            window.captureTiming?.let { timing(session.id, it) }
            window.startupObservation?.let { startupSummary(session.id, it) }
            write(
                session.id,
                "target_window_summary",
                listOf(
                    "windowEvents" to window.windowStateEventCount,
                    "contentEvents" to window.contentChangedEventCount,
                    "clickEvents" to window.viewClickedEventCount,
                    "ignoredPackageEvents" to window.ignoredPackageEventCount,
                    "missingPackageEvents" to window.missingPackageEventCount,
                    "resolutionAttempts" to window.windowResolutionAttemptCount,
                    "retryAttempts" to window.windowRetryAttemptCount,
                    "lastEventWindowId" to window.lastEventWindowId,
                    "windows" to window.lastWindowCount,
                    "matchingWindows" to window.lastMatchingWindowCount,
                    "windowType" to window.lastMatchingWindowType,
                    "windowActive" to window.lastMatchingWindowActive,
                    "lastOutcome" to window.lastOutcome,
                    "lastEventAgeMs" to window.lastEventAgeMs,
                ),
            )
        }
        failure?.let { failure ->
            write(
                session.id,
                "capture_diagnostics",
                listOf(
                    "clickApplicable" to failure.clickDiagnosticApplicable,
                    "sourceAvailable" to failure.sourceAvailable,
                    "eventTextCount" to failure.eventTextCount,
                    "eventHasDescription" to failure.eventHasContentDescription,
                    "copiedNodes" to failure.copiedNodeCount,
                    "selectorCandidates" to failure.selectorCandidateCount,
                    "snapshotOutcome" to failure.snapshotRecovery?.outcome,
                    "snapshotAgeMs" to failure.snapshotRecovery?.ageMs,
                    "windowVerifiedAgeMs" to failure.lastWindowVerifiedAgeMs,
                    "contentChangedAgeMs" to failure.lastContentChangedAgeMs,
                    "diffOutcome" to failure.semanticDiff?.outcome,
                    "diffObservations" to failure.semanticDiff?.observationCount,
                    "diffCurrent" to failure.semanticDiff?.currentCandidateCount,
                    "diffStable" to failure.semanticDiff?.stableCandidateCount,
                    "diffDisappeared" to failure.semanticDiff?.disappearedCandidateCount,
                    "diffVisited" to failure.semanticDiff?.visitedNodeCount,
                    "diffTransition" to failure.semanticDiff?.transitionSignal,
                    "directedPresent" to failure.semanticDiff?.directedPresentCount,
                    "directedAbsent" to failure.semanticDiff?.directedAbsentCount,
                    "directedUnknown" to failure.semanticDiff?.directedUnknownCount,
                    "directedAmbiguous" to failure.semanticDiff?.directedAmbiguousCount,
                    "unresolvedDismiss" to failure.semanticDiff?.unresolvedDismissCount,
                ),
            )
            failure.immediateRecovery?.let { recovery(session.id, "immediate_recovery_summary", it) }
            failure.preClickProbe?.let { recovery(session.id, "pre_click_probe_summary", it) }
            failure.candidateRecheck?.let { recheck(session.id, it) }
            failure.sessionTrace?.let traceSnapshot@{ trace ->
                if (traceUnchanged) return@traceSnapshot
                lastTrace = session.id to trace
                val totals = trace.summary
                write(
                    session.id,
                    "capture_scan_summary",
                    listOf(
                        "scans" to totals.scanCount,
                        "complete" to totals.completedScanCount,
                        "incomplete" to totals.incompleteScanCount,
                        "limited" to totals.traversalLimitScanCount,
                        "scansWithCloseSemantics" to totals.scansWithCloseSemantics,
                        "maxCloseSemantics" to totals.maxCloseSemanticCount,
                        "maxCountdownSemantics" to totals.maxCountdownSemanticCount,
                        "maxSelectorCandidates" to totals.maxEffectiveCandidateCount,
                        "diffs" to totals.semanticDiffCount,
                        "transitions" to totals.transitionSignalCount,
                        "maxDiffCurrent" to totals.maxDiffCurrentCandidateCount,
                        "maxDiffStable" to totals.maxDiffStableCandidateCount,
                        "maxDiffDisappeared" to totals.maxDiffDisappearedCandidateCount,
                        "differenceClears" to totals.differenceClearCount,
                        "resolutions" to totals.windowResolutionCount,
                        "retries" to totals.windowResolutionRetryCount,
                        "hostUpdates" to totals.hostWindowUpdateCount,
                        "totalRecords" to totals.totalRecordCount,
                        "retainedRecords" to trace.records.size,
                        "droppedRecords" to totals.droppedRecordCount,
                        "continuedScans" to totals.continuedScanCount,
                        "rechecks" to totals.candidateRecheckCount,
                        "directedQueries" to totals.directedQueryCount,
                        "directedPresent" to totals.directedPresentCount,
                        "directedAbsent" to totals.directedAbsentCount,
                        "directedUnknown" to totals.directedUnknownCount,
                        "directedAmbiguous" to totals.directedAmbiguousCount,
                        "anchorComplete" to totals.completedAnchorScanCount,
                    ) + rejectionFields(totals.rejectedCandidateCounts),
                )
                totals.windowResolutionOutcomeCounts.forEach { (outcome, count) ->
                    write(session.id, "capture_window_outcome", listOf("outcome" to outcome, "count" to count))
                }
                trace.records.forEach { record(session.id, it, terminal = true) }
            }
        }
    }

    private fun <T> diagnosticSnapshot(sessionId: Long, provider: () -> T?): T? = try {
        provider()
    } catch (error: Exception) {
        write(sessionId, "diagnostic_provider_failed", listOf("error" to CaptureTraceError.from(error)), essential = true)
        null
    }

    private fun recovery(sessionId: Long, name: String, diagnostics: ClickRecoveryDiagnostics) {
        write(
            sessionId,
            name,
            listOf(
                "outcome" to diagnostics.outcome,
                "visited" to diagnostics.visitedNodeCount,
                "depth" to diagnostics.maxDepth,
                "elapsedMs" to diagnostics.elapsedMs,
                "semantic" to diagnostics.semanticNodeCount,
                "matched" to diagnostics.matchedNodeCount,
                "knownDismiss" to diagnostics.knownDismissNodeCount,
                "closeSemantics" to diagnostics.explicitDismissNodeCount,
                "selectorCandidates" to diagnostics.selectorCandidateNodeCount,
                "countdownSemantics" to diagnostics.countdownSemanticNodeCount,
                "countdownActionChains" to diagnostics.countdownActionChainCount,
                "complete" to diagnostics.isCompleteTraversal(),
                "limit" to diagnostics.traversalLimitReason,
                "scanStrategy" to diagnostics.scanStrategy,
                "coverageComplete" to diagnostics.coverageComplete,
                "continuationDepth" to diagnostics.continuationDepth,
                "pendingBranches" to diagnostics.pendingBranchCount,
                "cursorReset" to diagnostics.cursorReset,
                "budgetNodeReads" to diagnostics.budgetNodeReads,
                "unavailableReason" to diagnostics.unavailableReason,
            ) + rejectionFields(diagnostics.rejectedCandidateCounts),
        )
    }

    private fun rejectionFields(counts: Map<CaptureCandidateRejection, Int>): List<Pair<String, Any?>> =
        CaptureCandidateRejection.entries.mapNotNull { reason ->
            counts[reason]?.takeIf { it > 0 }?.let { "reject_${reason.name}" to it }
        }

    @Synchronized
    fun recheck(sessionId: Long, diagnostics: CandidateRecheckDiagnostics) {
        if (!enabled) return
        write(sessionId, "candidate_recheck_summary", listOf(
            "queries" to diagnostics.queryCount, "anchorQueries" to diagnostics.anchorQueryCount,
            "present" to diagnostics.presentCount, "absent" to diagnostics.absentCount,
            "unknown" to diagnostics.unknownCount, "ambiguous" to diagnostics.ambiguousCount,
            "emptyQueries" to diagnostics.emptyQueryCount, "anchorScans" to diagnostics.anchorScanCount,
            "anchorComplete" to diagnostics.completedAnchorScanCount,
            "unreadableNodes" to diagnostics.unreadableNodeCount,
            "budgetNodeReads" to diagnostics.budgetNodeReads, "elapsedMs" to diagnostics.elapsedMs,
            "limit" to diagnostics.traversalLimitReason,
        ))
    }

    private fun sample(session: CaptureSession, savedSample: CaptureSample? = null) {
        val sample = savedSample ?: session.pendingSample
        val nodes = sample?.nodes ?: session.pendingObservedCandidate?.nodes ?: return
        val fields = listOf(
            "evidence" to sample?.evidence,
            "pendingCandidate" to (sample == null && session.pendingObservedCandidate != null),
            "savedSample" to (savedSample != null),
            "nodes" to nodes.size,
            "resourceIdNodes" to nodes.count { it.resourceId != null },
            "textNodes" to nodes.count { it.text != null },
            "descriptionNodes" to nodes.count { it.contentDescription != null },
            "actionableNodes" to nodes.count { it.clickable || it.supportsActionClick },
            "uniqueSelectors" to (sample?.preClickUniqueSelectors?.size
                ?: session.pendingObservedCandidate?.preClickUniqueSelectors?.size),
        )
        if (lastSample == (session.id to fields)) return
        lastSample = session.id to fields
        write(session.id, if (savedSample == null) "capture_sample_summary" else "sample_saved", fields)
    }

    /** 阶段与字段名必须使用代码常量；值仅接受状态枚举、布尔与整数。 */
    @Synchronized
    fun stage(sessionId: Long?, name: String, vararg fields: Pair<String, Any?>) {
        if (!enabled || sessionId == null) return
        if (!stateObserved && currentSessionId == null) {
            currentSessionId = sessionId
            beginSession(sessionId)
            rememberSession(sessionId)
        }
        val safe = safeFields(fields.toList())
        // 迟到协程只写自己的会话标记，不能重置当前会话的缓冲区和时序。
        if (sessionId != currentSessionId) {
            write(sessionId, name, safe + ("late" to true))
            return
        }
        if (name in IMMEDIATE_STAGES || (name == "accessibility_event" && fields.any {
                it.first == "type" && it.second == AccessibilityEvent.TYPE_VIEW_CLICKED
            })
        ) {
            flush()
            write(sessionId, name, safe, essential = name == "accessibility_event" &&
                safe.any { it.first == "type" && it.second == AccessibilityEvent.TYPE_VIEW_CLICKED })
            return
        }
        // 时间变化不拆散同类高频事件；合并摘要保留首末事件及最大的 uptime 排队延迟。
        val identity = safe.filterNot { it.first == "recordElapsedMs" || it.first in EVENT_TIMING_FIELDS }
        val previous = repeatedStages[name]
        val now = SystemClock.elapsedRealtime()
        if (previous?.identity == identity) {
            previous.repeatCount = if (previous.repeatCount == Int.MAX_VALUE) {
                Int.MAX_VALUE
            } else {
                previous.repeatCount + 1
            }
            previous.fields = safe
            val delay = safe.longField("dispatchDelayMs")
            if (delay != null) previous.maxDispatchDelayMs = maxOf(previous.maxDispatchDelayMs ?: delay, delay)
            previous.lastAtElapsedMs = now
            return
        }
        if (previous != null) flushStage(name, previous)
        if (previous == null && repeatedStages.size == MAX_REPEATED_STAGES) {
            val oldest = repeatedStages.entries.first()
            flushStage(oldest.key, oldest.value)
            repeatedStages.remove(oldest.key)
        }
        write(sessionId, name, safe)
        repeatedStages[name] = RepeatedStage(identity, safe, firstAtElapsedMs = now, lastAtElapsedMs = now,
            firstEventUptimeMs = safe.longField("eventUptimeMs"),
            firstReceivedUptimeMs = safe.longField("receivedUptimeMs"),
            maxDispatchDelayMs = safe.longField("dispatchDelayMs"))
    }

    @Synchronized
    fun flush() {
        if (!enabled) return
        repeatedStages.forEach { (name, stage) -> flushStage(name, stage) }
    }

    private fun flushStage(name: String, stage: RepeatedStage) {
        if (stage.repeatCount == 0) return
        val sessionId = currentSessionId ?: return
        write(
            sessionId,
            name,
            stage.fields + listOf(
                "repeatCount" to stage.repeatCount,
                "firstRelativeMs" to relativeTime(sessionId, stage.firstAtElapsedMs),
                "lastRelativeMs" to relativeTime(sessionId, stage.lastAtElapsedMs),
                "firstEventUptimeMs" to stage.firstEventUptimeMs,
                "firstReceivedUptimeMs" to stage.firstReceivedUptimeMs,
                "maxDispatchDelayMs" to stage.maxDispatchDelayMs,
            ),
        )
        stage.repeatCount = 0
        stage.firstAtElapsedMs = stage.lastAtElapsedMs
        stage.firstEventUptimeMs = stage.fields.longField("eventUptimeMs")
        stage.firstReceivedUptimeMs = stage.fields.longField("receivedUptimeMs")
        stage.maxDispatchDelayMs = stage.fields.longField("dispatchDelayMs")
    }

    private fun List<Pair<String, Any?>>.longField(name: String): Long? =
        firstOrNull { it.first == name }?.second as? Long

    private fun rememberSession(sessionId: Long) {
        sessionStarts[sessionId] = SystemClock.elapsedRealtime()
        if (sessionStarts.size > MAX_SESSION_TIMES) sessionStarts.remove(sessionStarts.keys.first())
    }

    private fun relativeTime(sessionId: Long, now: Long): Long? =
        sessionStarts[sessionId]?.let { (now - it).coerceAtLeast(0) }

    private fun safeFields(fields: List<Pair<String, Any?>>): List<Pair<String, Any?>> =
        fields.filter { (_, value) -> value is Boolean || value is Int || value is Long || value is Enum<*> }

    private fun write(sessionId: Long?, name: String, fields: List<Pair<String, Any?>>, essential: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        val relative = sessionId?.let { relativeTime(it, now) }?.let { " relativeMs=$it" }.orEmpty()
        val details = safeFields(fields).joinToString(" ") { (key, value) ->
            "$key=${if (value is Enum<*>) value.name else value}"
        }
        val label = sessionId?.let { id ->
            sessionLabels.getOrPut(id) { ++nextSessionLabel }.also {
                if (sessionLabels.size > 128) sessionLabels.remove(sessionLabels.keys.first())
            }
        } ?: 0
        val message = "process=${identifiers.scope} session=$label seq=${++sequence}$relative stage=$name $details"
        val important = essential || name in ESSENTIAL_STAGES || name.endsWith("_summary")
        // 两个目的地各自失败，不能因 Logcat 不可用或过滤而跳过本地保存。
        try { localSink?.append(sessionId?.let(identifiers::sessionFileId), message, important) } catch (_: Exception) { }
        try {
            if (name == "capture_diagnostics_ready" || name.startsWith("service_")) logOutput(Log.INFO, TAG, message)
            else if (name == "capture_summary" && fields.any { it.second == CaptureTraceConclusion.FAILED }) logOutput(Log.WARN, TAG, message)
            else logOutput(Log.DEBUG, TAG, message)
        } catch (_: Exception) { }
    }

    private data class RepeatedStage(
        val identity: List<Pair<String, Any?>>,
        var fields: List<Pair<String, Any?>>,
        var repeatCount: Int = 0,
        var firstAtElapsedMs: Long,
        var lastAtElapsedMs: Long,
        var firstEventUptimeMs: Long? = null,
        var firstReceivedUptimeMs: Long? = null,
        var maxDispatchDelayMs: Long? = null,
    )

    companion object {
        const val TAG = "FlashSkipCapture"
        private const val MAX_REPEATED_STAGES = 24
        private const val MAX_SESSION_TIMES = 2
        private val EVENT_TIMING_FIELDS = setOf("eventUptimeMs", "receivedUptimeMs", "receivedElapsedMs", "receivedEpochMs", "dispatchDelayMs")
        private val ESSENTIAL_STAGES = setOf(
            "capture_state", "capture_session_cleared", "capture_diagnostics", "capture_recent_record",
            "capture_failed", "click_capture", "click_semantics", "sample_saved",
        )
        private val IMMEDIATE_STAGES = setOf(
            "capture_failed", "observed_candidate", "semantic_diff_candidate", "sample_captured",
            "candidate_click_ignored", "candidate_click_matched", "candidate_click_upgrade_stale",
            "candidate_write_failed", "candidate_click_recovery_write_failed", "click_capture", "click_semantics",
        )
    }
}

internal enum class CaptureTraceEventRoute {
    DISPATCHED,
    IGNORED,
    OBSERVED_ONLY,
}

/** 异常只留固定分类，避免异常文本携带路径、包名或页面内容。 */
internal enum class CaptureTraceError {
    IO, SECURITY, CANCELLED, OTHER;

    companion object {
        fun from(error: Throwable): CaptureTraceError = when (error) {
            is IOException -> IO
            is SecurityException -> SECURITY
            is kotlinx.coroutines.CancellationException -> CANCELLED
            else -> OTHER
        }
    }
}

internal enum class CaptureTraceConclusion {
    FAILED,
    CLICK_CAPTURED,
    CANDIDATE_LISTENING_STOPPED,
    SAVED,
    CANCELLED,
    REPLACED,
    SERVICE_INTERRUPTED,
    SERVICE_DESTROYED,
    SERVICE_RECONNECTED,
}

internal enum class CaptureTraceReason {
    MISSING_PACKAGE,
    SYSTEM_UI_WITHOUT_VERIFIED_HOST,
    LOCKED_PACKAGE_MISMATCH,
    PACKAGE_IGNORED_BEFORE_TARGET,
    HOST_NOT_VERIFIED,
    CAPTURE_PHASE_NOT_LISTENING,
    SESSION_LOCKED,
    WINDOW_POLICY,
    NO_FRESH_SNAPSHOT,
    NO_SELECTOR_CANDIDATE,
    CANDIDATE_WINDOW_MISMATCH,
    CANDIDATE_IDENTITY_MISMATCH,
    SOURCE_UNAVAILABLE,
    SOURCE_IDENTITY_MISMATCH,
    CANDIDATE_MISMATCH,
    TARGET_ENTRY_UNAVAILABLE,
}
