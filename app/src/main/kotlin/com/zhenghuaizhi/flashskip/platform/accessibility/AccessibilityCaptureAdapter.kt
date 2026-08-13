package com.zhenghuaizhi.flashskip.platform.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.zhenghuaizhi.flashskip.application.rulecapture.RuleCaptureCoordinator
import com.zhenghuaizhi.flashskip.domain.model.CaptureFailure
import com.zhenghuaizhi.flashskip.domain.model.CaptureFailureDiagnostics
import com.zhenghuaizhi.flashskip.domain.model.CapturePhase
import com.zhenghuaizhi.flashskip.domain.model.CaptureSample
import com.zhenghuaizhi.flashskip.domain.model.CapturedNode
import com.zhenghuaizhi.flashskip.domain.model.CapturedSelector
import com.zhenghuaizhi.flashskip.domain.model.ClickRecoveryDiagnostics
import com.zhenghuaizhi.flashskip.domain.model.RuleCaptureState
import com.zhenghuaizhi.flashskip.domain.model.SnapshotRecoveryDiagnostics
import com.zhenghuaizhi.flashskip.domain.model.SnapshotRecoveryOutcome
import com.zhenghuaizhi.flashskip.domain.model.TargetAppId
import com.zhenghuaizhi.flashskip.domain.model.TargetWindowDiagnostics
import com.zhenghuaizhi.flashskip.domain.ports.PageIdentity
import com.zhenghuaizhi.flashskip.domain.rulecapture.ClickSemanticHint
import com.zhenghuaizhi.flashskip.domain.rulecapture.ClickSourceRecoveryMatcher
import com.zhenghuaizhi.flashskip.domain.rulecapture.RuleCandidateAnalyzer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal class AccessibilityCaptureAdapter(
    private val service: AccessibilityService,
    private val coordinator: RuleCaptureCoordinator,
    private val scope: CoroutineScope,
    private val stateProvider: () -> RuleCaptureState,
    private val targetWindowDiagnosticsProvider: (Long) -> TargetWindowDiagnostics?,
) {
    private var currentIdentity: PageIdentity? = null
    private var latestUniqueSelectors: Set<CapturedSelector> = emptySet()
    private var lockedSessionId: Long? = null
    private var observedSessionId: Long? = null
    private var verifiedTargetEnteredAtElapsedMs: Long? = null
    private var currentTargetAppName: String? = null
    private var preClickRecoverySnapshot: PreClickRecoverySnapshot? = null
    private var lastRecoveryProbeAtElapsedMs: Long? = null
    private var latestPreClickProbeDiagnostics: ClickRecoveryDiagnostics? = null
    private var lastWindowVerifiedAtElapsedMs: Long? = null
    private var lastContentChangedAtElapsedMs: Long? = null
    private val semanticSnapshotter = AccessibilitySemanticSnapshotter(service)
    private val probe = AccessibilityCaptureProbe(
        service = service,
        targetPackages = {
            val packageName = stateProvider().activeSession?.packageName
                ?: currentIdentity?.packageName
            packageName?.let(::setOf).orEmpty()
        },
        currentIdentity = { currentIdentity },
        semanticSnapshotter = semanticSnapshotter,
    )

    fun onStateChanged(state: RuleCaptureState) {
        val session = state.activeSession
        if (session?.id != observedSessionId) {
            observedSessionId = session?.id
            lockedSessionId = null
            currentIdentity = null
            verifiedTargetEnteredAtElapsedMs = null
            currentTargetAppName = null
            latestUniqueSelectors = emptySet()
            lastWindowVerifiedAtElapsedMs = null
            lastContentChangedAtElapsedMs = null
            clearPreClickRecoverySnapshot()
        }
        if (session == null || session.phase in setOf(
                CapturePhase.CONFIGURING,
                CapturePhase.WAITING_FOR_TARGET,
                CapturePhase.FAILED,
            )
        ) {
            currentIdentity = null
            verifiedTargetEnteredAtElapsedMs = null
            currentTargetAppName = null
            latestUniqueSelectors = emptySet()
            lastWindowVerifiedAtElapsedMs = null
            lastContentChangedAtElapsedMs = null
            clearPreClickRecoverySnapshot()
        }
    }

    fun onVerifiedTargetWindow(
        event: AccessibilityEvent,
        targetAppName: String,
        activityName: String,
    ) {
        val state = stateProvider()
        val session = state.activeSession ?: return
        val packageName = event.packageName?.toString() ?: return
        if (session.packageName != null && packageName != session.packageName) return
        // 状态落盘前先在回调栈内锁住首次验证包，避免快速切换应用改写本次目标。
        if (session.packageName == null && currentIdentity?.packageName?.let { it != packageName } == true) {
            return
        }
        if (session.phase !in setOf(CapturePhase.WAITING_FOR_TARGET, CapturePhase.OBSERVING_CLICK)) {
            return
        }
        val now = coordinator.elapsedRealtimeMs()
        val enteredAt = session.targetEnteredAtElapsedMs ?: now
        val identity = PageIdentity(
            packageName = packageName,
            activityName = activityName,
            windowId = event.windowId,
            appVersion = null,
        )
        currentIdentity = identity
        currentTargetAppName = targetAppName
        verifiedTargetEnteredAtElapsedMs = enteredAt
        lastWindowVerifiedAtElapsedMs = now
        lastContentChangedAtElapsedMs = null
        refreshPreClickRecoverySnapshot(identity)
        refreshUniqueEvidence(state, identity)
        scope.launch {
            coordinator.observeTarget(
                sessionId = session.id,
                targetAppId = TargetAppId(packageName),
                targetAppName = targetAppName,
                packageName = packageName,
                activityName = activityName,
                windowId = event.windowId,
                enteredAtElapsedMs = enteredAt,
            )
        }
    }

    fun onContentChanged(event: AccessibilityEvent) {
        val state = stateProvider()
        val session = state.activeSession ?: return
        if (session.phase != CapturePhase.OBSERVING_CLICK) return
        val packageName = event.packageName?.toString() ?: return
        val identity = currentIdentity?.takeIf {
            (session.packageName == null || packageName == session.packageName) &&
                it.packageName == packageName &&
                it.windowId == event.windowId
        } ?: return
        lastContentChangedAtElapsedMs = coordinator.elapsedRealtimeMs()
        refreshPreClickRecoverySnapshot(identity)
        refreshUniqueEvidence(state, identity)
    }

    fun onClicked(event: AccessibilityEvent) {
        val state = stateProvider()
        val session = state.activeSession ?: return
        if (session.phase !in setOf(
                CapturePhase.WAITING_FOR_TARGET,
                CapturePhase.OBSERVING_CLICK,
            ) || lockedSessionId == session.id
        ) {
            return
        }
        val packageName = event.packageName?.toString() ?: return
        // 包和窗口检查必须先于 event.source，确保非目标点击零节点读取。
        if (session.packageName != null && packageName != session.packageName) return
        val identity = currentIdentity
        if (identity == null ||
            identity.packageName != packageName ||
            identity.windowId != event.windowId
        ) {
            lockAndFail(session.id, CaptureFailure.ACTIVITY_UNAVAILABLE)
            return
        }

        lockedSessionId = session.id
        val hint = ClickSemanticHint(
            texts = event.text.mapNotNull { value ->
                semanticSnapshotter.sanitizeSemantic(value.toString())
            }.toSet(),
            contentDescription = semanticSnapshotter.sanitizeSemantic(
                event.contentDescription?.toString(),
            ),
        )
        val source = event.source
        val nodes = if (source != null) {
            semanticSnapshotter.copySemanticChain(source, packageName)
        } else {
            // 点击回调到达时按钮可能已经消失；即时恢复失败后仅使用同窗口的近期唯一快照。
            val immediate = probe.recoverClickedSemanticChain(identity, hint)
            val immediateSuccess = immediate as? AccessibilityCaptureProbe.RecoveryResult.Success
            if (immediateSuccess != null) {
                immediateSuccess.nodes
            } else {
                val snapshot = recoverFromPreClickSnapshot(identity, hint)
                snapshot.nodes ?: run {
                    fail(
                        sessionId = session.id,
                        failure = CaptureFailure.CLICK_SOURCE_UNAVAILABLE,
                        diagnostics = clickFailureDiagnostics(
                            sourceAvailable = false,
                            hint = hint,
                            immediateRecovery = immediate.diagnostics,
                            snapshotRecovery = snapshot.diagnostics,
                        ),
                    )
                    return
                }
            }
        }
        val candidates = RuleCandidateAnalyzer.selectorCandidates(nodes)
        if (candidates.isEmpty()) {
            fail(
                sessionId = session.id,
                failure = CaptureFailure.CLICK_SEMANTICS_UNAVAILABLE,
                diagnostics = clickFailureDiagnostics(
                    sourceAvailable = source != null,
                    hint = hint,
                    copiedNodeCount = nodes.size,
                    selectorCandidateCount = candidates.size,
                ),
            )
            return
        }
        // 窗口已同步验证但状态仍在异步落盘时，也必须接住用户的首次快速点击。
        val enteredAt = session.targetEnteredAtElapsedMs ?: verifiedTargetEnteredAtElapsedMs
        val activityName = identity.activityName
        if (enteredAt == null || activityName.isNullOrBlank()) {
            fail(session.id, CaptureFailure.ACTIVITY_UNAVAILABLE)
            return
        }
        val sample = CaptureSample(
            id = session.id,
            targetAppId = session.targetAppId ?: TargetAppId(packageName),
            targetAppName = session.targetAppName ?: currentTargetAppName ?: packageName,
            activityName = activityName.take(MAX_ACTIVITY_LENGTH),
            clickedAfterTargetMs = (coordinator.elapsedRealtimeMs() - enteredAt).coerceAtLeast(0),
            capturedAtEpochMs = coordinator.currentTimeMillis(),
            nodes = nodes,
            preClickUniqueSelectors = candidates.filter { it in latestUniqueSelectors },
        )
        scope.launch { coordinator.recordCaptured(session.id, sample) }
    }

    private fun refreshPreClickRecoverySnapshot(identity: PageIdentity) {
        val now = coordinator.elapsedRealtimeMs()
        val existing = preClickRecoverySnapshot
        if (existing != null && existing.identity != identity) {
            clearPreClickRecoverySnapshot()
        } else if (existing != null && now - existing.capturedAtElapsedMs <= SNAPSHOT_MAX_AGE_MS) {
            return
        }
        if (lastRecoveryProbeAtElapsedMs?.let { now - it < RECOVERY_PROBE_INTERVAL_MS } == true) {
            return
        }
        lastRecoveryProbeAtElapsedMs = now
        val recovery = probe.recoverClickedSemanticChain(identity, ClickSemanticHint())
        latestPreClickProbeDiagnostics = recovery.diagnostics
        if (recovery is AccessibilityCaptureProbe.RecoveryResult.Success) {
            preClickRecoverySnapshot = PreClickRecoverySnapshot(
                identity = identity,
                capturedAtElapsedMs = now,
                nodes = recovery.nodes,
            )
        }
    }

    private fun recoverFromPreClickSnapshot(
        identity: PageIdentity,
        hint: ClickSemanticHint,
    ): SnapshotRecoveryResult {
        val snapshot = preClickRecoverySnapshot ?: return SnapshotRecoveryResult(
            diagnostics = SnapshotRecoveryDiagnostics(SnapshotRecoveryOutcome.NOT_AVAILABLE),
        )
        if (snapshot.identity != identity) {
            return SnapshotRecoveryResult(
                diagnostics = SnapshotRecoveryDiagnostics(
                    SnapshotRecoveryOutcome.IDENTITY_MISMATCH,
                ),
            )
        }
        val ageMs = coordinator.elapsedRealtimeMs() - snapshot.capturedAtElapsedMs
        if (ageMs !in 0..SNAPSHOT_MAX_AGE_MS) {
            return SnapshotRecoveryResult(
                diagnostics = SnapshotRecoveryDiagnostics(
                    outcome = SnapshotRecoveryOutcome.STALE,
                    ageMs = ageMs,
                ),
            )
        }
        val semanticNode = snapshot.nodes.firstOrNull() ?: return SnapshotRecoveryResult(
            diagnostics = SnapshotRecoveryDiagnostics(
                outcome = SnapshotRecoveryOutcome.EMPTY,
                ageMs = ageMs,
            ),
        )
        val matchesEvent = ClickSourceRecoveryMatcher.matches(semanticNode, hint)
        val matchesKnownDismiss = ClickSourceRecoveryMatcher.matches(
            semanticNode,
            ClickSemanticHint(),
        )
        val outcome = when {
            matchesEvent -> SnapshotRecoveryOutcome.MATCHED_EVENT
            matchesKnownDismiss -> SnapshotRecoveryOutcome.MATCHED_DISMISS
            else -> SnapshotRecoveryOutcome.NO_MATCH
        }
        return SnapshotRecoveryResult(
            nodes = snapshot.nodes.takeIf { matchesEvent || matchesKnownDismiss },
            diagnostics = SnapshotRecoveryDiagnostics(outcome = outcome, ageMs = ageMs),
        )
    }

    private fun clearPreClickRecoverySnapshot() {
        preClickRecoverySnapshot = null
        lastRecoveryProbeAtElapsedMs = null
        latestPreClickProbeDiagnostics = null
    }

    private data class PreClickRecoverySnapshot(
        val identity: PageIdentity,
        val capturedAtElapsedMs: Long,
        val nodes: List<CapturedNode>,
    )

    private data class SnapshotRecoveryResult(
        val nodes: List<CapturedNode>? = null,
        val diagnostics: SnapshotRecoveryDiagnostics,
    )

    private fun refreshUniqueEvidence(state: RuleCaptureState, identity: PageIdentity) {
        val session = state.activeSession ?: return
        val targetAppId = session.targetAppId ?: TargetAppId(identity.packageName)
        val selectors = RuleCandidateAnalyzer.analyze(
            samples = state.samples,
            targetAppId = targetAppId,
        ).probeSelectors
        latestUniqueSelectors = when (val result = probe.uniqueSelectors(identity, selectors)) {
            is AccessibilityCaptureProbe.ProbeResult.Success -> result.selectors
            AccessibilityCaptureProbe.ProbeResult.NoRoot,
            AccessibilityCaptureProbe.ProbeResult.PageChanged,
            AccessibilityCaptureProbe.ProbeResult.TraversalLimit,
            -> emptySet()
        }
    }

    private fun lockAndFail(sessionId: Long, failure: CaptureFailure) {
        if (lockedSessionId == sessionId) return
        lockedSessionId = sessionId
        fail(sessionId, failure)
    }

    private fun clickFailureDiagnostics(
        sourceAvailable: Boolean,
        hint: ClickSemanticHint,
        copiedNodeCount: Int = 0,
        selectorCandidateCount: Int = 0,
        immediateRecovery: ClickRecoveryDiagnostics? = null,
        snapshotRecovery: SnapshotRecoveryDiagnostics? = null,
    ): CaptureFailureDiagnostics {
        val now = coordinator.elapsedRealtimeMs()
        return CaptureFailureDiagnostics(
            sourceAvailable = sourceAvailable,
            eventTextCount = hint.texts.size,
            eventHasContentDescription = hint.contentDescription != null,
            copiedNodeCount = copiedNodeCount,
            selectorCandidateCount = selectorCandidateCount,
            immediateRecovery = immediateRecovery,
            preClickProbe = latestPreClickProbeDiagnostics,
            snapshotRecovery = snapshotRecovery,
            lastWindowVerifiedAgeMs = elapsedSince(now, lastWindowVerifiedAtElapsedMs),
            lastContentChangedAgeMs = elapsedSince(now, lastContentChangedAtElapsedMs),
        )
    }

    private fun elapsedSince(now: Long, timestamp: Long?): Long? = timestamp?.let {
        (now - it).coerceAtLeast(0)
    }

    private fun fail(
        sessionId: Long,
        failure: CaptureFailure,
        diagnostics: CaptureFailureDiagnostics? = null,
    ) {
        scope.launch {
            coordinator.fail(
                sessionId = sessionId,
                failure = failure,
                diagnostics = diagnostics,
                targetWindowDiagnostics = targetWindowDiagnosticsProvider(sessionId),
            )
        }
    }

    private companion object {
        const val MAX_ACTIVITY_LENGTH = 240
        const val RECOVERY_PROBE_INTERVAL_MS = 200L
        const val SNAPSHOT_MAX_AGE_MS = 10_000L
    }
}
