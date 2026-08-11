package com.zhenghuaizhi.flashskip.platform.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.zhenghuaizhi.flashskip.application.rulecapture.RuleCaptureCoordinator
import com.zhenghuaizhi.flashskip.domain.model.AppVersion
import com.zhenghuaizhi.flashskip.domain.model.CaptureFailure
import com.zhenghuaizhi.flashskip.domain.model.CapturePhase
import com.zhenghuaizhi.flashskip.domain.model.CaptureSample
import com.zhenghuaizhi.flashskip.domain.model.CapturedNode
import com.zhenghuaizhi.flashskip.domain.model.CapturedSelector
import com.zhenghuaizhi.flashskip.domain.model.RuleCaptureState
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
    targetPackages: Set<String>,
    private val stateProvider: () -> RuleCaptureState,
) {
    private val targetPackages = targetPackages.toSet()
    private var currentIdentity: PageIdentity? = null
    private var latestUniqueSelectors: Set<CapturedSelector> = emptySet()
    private var lockedSessionId: Long? = null
    private var observedSessionId: Long? = null
    private var verifiedTargetEnteredAtElapsedMs: Long? = null
    private var preClickRecoverySnapshot: PreClickRecoverySnapshot? = null
    private var lastRecoveryProbeAtElapsedMs: Long? = null
    private val semanticSnapshotter = AccessibilitySemanticSnapshotter(service)
    private val probe = AccessibilityCaptureProbe(
        service = service,
        targetPackages = targetPackages,
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
            latestUniqueSelectors = emptySet()
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
            latestUniqueSelectors = emptySet()
            clearPreClickRecoverySnapshot()
        }
    }

    fun onVerifiedTargetWindow(
        event: AccessibilityEvent,
        activityName: String,
        appVersion: AppVersion,
    ) {
        val state = stateProvider()
        val session = state.activeSession ?: return
        val packageName = event.packageName?.toString() ?: return
        if (packageName !in targetPackages || packageName != session.packageName) return
        if (session.phase !in setOf(CapturePhase.WAITING_FOR_TARGET, CapturePhase.OBSERVING_CLICK)) {
            return
        }
        if (appVersion != session.appVersion) {
            lockAndFail(session.id, CaptureFailure.APP_VERSION_CHANGED)
            return
        }
        val enteredAt = session.targetEnteredAtElapsedMs ?: coordinator.elapsedRealtimeMs()
        val identity = PageIdentity(
            packageName = packageName,
            activityName = activityName,
            windowId = event.windowId,
            appVersion = appVersion,
        )
        currentIdentity = identity
        verifiedTargetEnteredAtElapsedMs = enteredAt
        refreshPreClickRecoverySnapshot(identity)
        refreshUniqueEvidence(state, identity)
        scope.launch {
            coordinator.observeTarget(
                sessionId = session.id,
                appVersion = appVersion,
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
            packageName in targetPackages &&
                packageName == session.packageName &&
                it.packageName == packageName &&
                it.windowId == event.windowId
        } ?: return
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
        if (packageName !in targetPackages || packageName != session.packageName) return
        val identity = currentIdentity
        if (identity == null || identity.windowId != event.windowId) {
            lockAndFail(session.id, CaptureFailure.ACTIVITY_UNAVAILABLE)
            return
        }

        lockedSessionId = session.id
        val source = event.source
        val nodes = if (source != null) {
            semanticSnapshotter.copySemanticChain(source, packageName)
        } else {
            val hint = ClickSemanticHint(
                texts = event.text.mapNotNull { value ->
                    semanticSnapshotter.sanitizeSemantic(value.toString())
                }.toSet(),
                contentDescription = semanticSnapshotter.sanitizeSemantic(
                    event.contentDescription?.toString(),
                ),
            )
            // 点击回调到达时按钮可能已经消失；即时恢复失败后仅使用同窗口的近期唯一快照。
            val immediate = probe.recoverClickedSemanticChain(identity, hint)
            (immediate as? AccessibilityCaptureProbe.RecoveryResult.Success)?.nodes
                ?: recoverFromPreClickSnapshot(identity, hint)
                ?: run {
                    fail(session.id, CaptureFailure.CLICK_SOURCE_UNAVAILABLE)
                    return
                }
        }
        val candidates = RuleCandidateAnalyzer.selectorCandidates(nodes)
        if (candidates.isEmpty()) {
            fail(session.id, CaptureFailure.CLICK_SEMANTICS_UNAVAILABLE)
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
            targetAppId = session.targetAppId,
            appVersion = session.appVersion,
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
    ): List<CapturedNode>? {
        val snapshot = preClickRecoverySnapshot?.takeIf {
            it.identity == identity &&
                coordinator.elapsedRealtimeMs() - it.capturedAtElapsedMs in 0..SNAPSHOT_MAX_AGE_MS
        } ?: return null
        val semanticNode = snapshot.nodes.firstOrNull() ?: return null
        val matchesEvent = ClickSourceRecoveryMatcher.matches(semanticNode, hint)
        val matchesKnownDismiss = ClickSourceRecoveryMatcher.matches(
            semanticNode,
            ClickSemanticHint(),
        )
        return snapshot.nodes.takeIf { matchesEvent || matchesKnownDismiss }
    }

    private fun clearPreClickRecoverySnapshot() {
        preClickRecoverySnapshot = null
        lastRecoveryProbeAtElapsedMs = null
    }

    private data class PreClickRecoverySnapshot(
        val identity: PageIdentity,
        val capturedAtElapsedMs: Long,
        val nodes: List<CapturedNode>,
    )

    private fun refreshUniqueEvidence(state: RuleCaptureState, identity: PageIdentity) {
        val session = state.activeSession ?: return
        val selectors = RuleCandidateAnalyzer.analyze(
            samples = state.samples,
            targetAppId = session.targetAppId,
            currentVersion = session.appVersion,
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

    private fun fail(sessionId: Long, failure: CaptureFailure) {
        scope.launch { coordinator.fail(sessionId, failure) }
    }

    private companion object {
        const val MAX_ACTIVITY_LENGTH = 240
        const val RECOVERY_PROBE_INTERVAL_MS = 200L
        const val SNAPSHOT_MAX_AGE_MS = 10_000L
    }
}
