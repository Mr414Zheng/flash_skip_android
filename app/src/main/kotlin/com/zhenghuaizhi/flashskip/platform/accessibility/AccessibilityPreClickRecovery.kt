package com.zhenghuaizhi.flashskip.platform.accessibility

import android.view.accessibility.AccessibilityNodeInfo
import com.zhenghuaizhi.flashskip.domain.model.CapturedNode
import com.zhenghuaizhi.flashskip.domain.model.ClickRecoveryDiagnostics
import com.zhenghuaizhi.flashskip.domain.model.SnapshotRecoveryDiagnostics
import com.zhenghuaizhi.flashskip.domain.model.SnapshotRecoveryOutcome
import com.zhenghuaizhi.flashskip.domain.ports.PageIdentity
import com.zhenghuaizhi.flashskip.domain.rulecapture.ClickSemanticHint
import com.zhenghuaizhi.flashskip.domain.rulecapture.ClickSourceRecoveryMatcher

internal class AccessibilityPreClickRecovery(
    private val elapsedRealtimeMs: () -> Long,
    private val probe: AccessibilityCaptureProbe,
) {
    private var snapshot: Snapshot? = null
    private var lastProbeAtElapsedMs: Long? = null

    var latestProbeDiagnostics: ClickRecoveryDiagnostics? = null
        private set

    fun clear() {
        snapshot = null
        lastProbeAtElapsedMs = null
        latestProbeDiagnostics = null
    }

    fun refresh(
        identity: PageIdentity,
        verifiedRoot: AccessibilityNodeInfo,
        allowBareNumericCountdown: Boolean,
        deadlineAtElapsedMs: Long? = null,
    ): RefreshResult? {
        val now = elapsedRealtimeMs()
        if (deadlineAtElapsedMs != null && now >= deadlineAtElapsedMs) return null
        val existing = snapshot
        if (existing != null && existing.identity != identity) {
            clear()
        } else if (existing != null && now - existing.capturedAtElapsedMs <= MAX_AGE_MS) {
            return null
        }
        if (lastProbeAtElapsedMs?.let { now - it < PROBE_INTERVAL_MS } == true) return null
        lastProbeAtElapsedMs = now
        val recovery = probe.recoverClickedSemanticChainOnVerifiedRoot(
            expected = identity,
            verifiedRoot = verifiedRoot,
            hint = ClickSemanticHint(
                allowBareNumericCountdown = allowBareNumericCountdown,
            ),
            deadlineAtElapsedMs = deadlineAtElapsedMs,
        )
        latestProbeDiagnostics = recovery.diagnostics
        if (recovery is AccessibilityCaptureProbe.RecoveryResult.Success) {
            snapshot = Snapshot(
                identity = identity,
                capturedAtElapsedMs = now,
                nodes = recovery.nodes,
            )
        }
        return RefreshResult(
            diagnostics = recovery.diagnostics,
            semanticCandidates = recovery.semanticCandidates,
            traversalComplete = recovery.diagnostics.isCompleteTraversal(),
            semanticCandidatesComplete = recovery.semanticCandidatesComplete,
        )
    }

    fun recover(
        identity: PageIdentity,
        hint: ClickSemanticHint,
        allowBareNumericCountdown: Boolean,
    ): Result {
        val current = snapshot ?: return Result(
            diagnostics = SnapshotRecoveryDiagnostics(SnapshotRecoveryOutcome.NOT_AVAILABLE),
        )
        if (current.identity != identity) {
            return Result(
                diagnostics = SnapshotRecoveryDiagnostics(
                    SnapshotRecoveryOutcome.IDENTITY_MISMATCH,
                ),
            )
        }
        val ageMs = elapsedRealtimeMs() - current.capturedAtElapsedMs
        if (ageMs !in 0..MAX_AGE_MS) {
            return Result(
                diagnostics = SnapshotRecoveryDiagnostics(
                    outcome = SnapshotRecoveryOutcome.STALE,
                    ageMs = ageMs,
                ),
            )
        }
        val semanticNode = current.nodes.firstOrNull() ?: return Result(
            diagnostics = SnapshotRecoveryDiagnostics(
                outcome = SnapshotRecoveryOutcome.EMPTY,
                ageMs = ageMs,
            ),
        )
        val matchesEvent = ClickSourceRecoveryMatcher.matches(semanticNode, hint)
        val matchesKnownDismiss = ClickSourceRecoveryMatcher.matches(
            semanticNode,
            ClickSemanticHint(allowBareNumericCountdown = allowBareNumericCountdown),
        )
        val outcome = when {
            matchesEvent -> SnapshotRecoveryOutcome.MATCHED_EVENT
            matchesKnownDismiss -> SnapshotRecoveryOutcome.MATCHED_DISMISS
            else -> SnapshotRecoveryOutcome.NO_MATCH
        }
        return Result(
            nodes = current.nodes.takeIf { matchesEvent || matchesKnownDismiss },
            diagnostics = SnapshotRecoveryDiagnostics(outcome = outcome, ageMs = ageMs),
        )
    }

    fun freshNodes(identity: PageIdentity): List<CapturedNode>? = snapshot?.takeIf {
        it.identity == identity && elapsedRealtimeMs() - it.capturedAtElapsedMs in 0..MAX_AGE_MS
    }?.nodes

    data class Result(
        val nodes: List<CapturedNode>? = null,
        val diagnostics: SnapshotRecoveryDiagnostics,
    )

    data class RefreshResult(
        val diagnostics: ClickRecoveryDiagnostics,
        val semanticCandidates: List<AccessibilityCaptureProbe.SemanticCandidate>,
        val traversalComplete: Boolean,
        val semanticCandidatesComplete: Boolean,
    )

    private data class Snapshot(
        val identity: PageIdentity,
        val capturedAtElapsedMs: Long,
        val nodes: List<CapturedNode>,
    )

    private companion object {
        const val PROBE_INTERVAL_MS = 200L
        const val MAX_AGE_MS = 10_000L
    }
}
