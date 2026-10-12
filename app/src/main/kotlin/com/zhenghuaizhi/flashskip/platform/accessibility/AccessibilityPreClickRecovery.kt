package com.zhenghuaizhi.flashskip.platform.accessibility

import android.view.accessibility.AccessibilityNodeInfo
import com.zhenghuaizhi.flashskip.domain.model.CapturedNode
import com.zhenghuaizhi.flashskip.domain.model.ClickRecoveryDiagnostics
import com.zhenghuaizhi.flashskip.domain.model.CandidateRecheckDiagnostics
import com.zhenghuaizhi.flashskip.domain.model.CapturedSelector
import com.zhenghuaizhi.flashskip.domain.model.SnapshotRecoveryDiagnostics
import com.zhenghuaizhi.flashskip.domain.model.SnapshotRecoveryOutcome
import com.zhenghuaizhi.flashskip.domain.ports.PageIdentity
import com.zhenghuaizhi.flashskip.domain.rulecapture.ClickSemanticHint
import com.zhenghuaizhi.flashskip.domain.rulecapture.ClickSourceRecoveryMatcher
import com.zhenghuaizhi.flashskip.domain.rulecapture.RuleCandidateAnalyzer
import com.zhenghuaizhi.flashskip.domain.rulecapture.hasStableResourceId

internal class AccessibilityPreClickRecovery(
    private val elapsedRealtimeMs: () -> Long,
    private val probe: AccessibilityCaptureProbe,
) {
    private var snapshot: Snapshot? = null
    private var lastProbeAtElapsedMs: Long? = null

    var latestProbeDiagnostics: ClickRecoveryDiagnostics? = null
        private set
    var latestRecheckDiagnostics: CandidateRecheckDiagnostics? = null
        private set

    fun clear() {
        snapshot = null
        lastProbeAtElapsedMs = null
        latestProbeDiagnostics = null
        latestRecheckDiagnostics = null
        probe.resetCoverage()
    }

    fun refresh(
        identity: PageIdentity,
        verifiedRoot: AccessibilityNodeInfo,
        allowBareNumericCountdown: Boolean,
        deadlineAtElapsedMs: Long? = null,
        selectorsForRecheck: List<CapturedSelector> = emptyList(),
        budget: CaptureRoundBudget? = null,
        recheckSelectorProvider: (() -> List<CapturedSelector>)? = null,
    ): RefreshResult? {
        val now = elapsedRealtimeMs()
        if (deadlineAtElapsedMs != null && now >= deadlineAtElapsedMs) return null
        val existing = snapshot
        if (existing != null && existing.identity != identity) {
            clear()
        } else if (existing != null && now - existing.capturedAtElapsedMs <= MAX_AGE_MS &&
            selectorsForRecheck.isEmpty() && recheckSelectorProvider == null
        ) {
            return null
        }
        if (lastProbeAtElapsedMs?.let { now - it < PROBE_INTERVAL_MS } == true) return null
        lastProbeAtElapsedMs = now
        val round = budget ?: probe.newRoundBudget(deadlineAtElapsedMs)
        // 只有实际执行的轮次才取下一批，节流事件不能悄悄推进轮转游标。
        val priorSelectors = (recheckSelectorProvider?.invoke() ?: selectorsForRecheck)
            .distinct().take(MAX_RECHECK_SELECTORS)
        val priorCheck = probe.checkStableSelectorsOnVerifiedRoot(identity, verifiedRoot, priorSelectors, round.childSlice(MAX_PRIOR_RECHECK_READS, MAX_PRIOR_RECHECK_MS))
        val recovery = probe.recoverClickedSemanticChainOnVerifiedRoot(
            expected = identity,
            verifiedRoot = verifiedRoot,
            hint = ClickSemanticHint(
                allowBareNumericCountdown = allowBareNumericCountdown,
            ),
            deadlineAtElapsedMs = deadlineAtElapsedMs,
            budget = round,
            continueCoverage = true,
        )
        latestProbeDiagnostics = recovery.diagnostics
        val discoveredSelectors = recovery.semanticCandidates.asSequence()
            .filter { it.nodes.hasSafeDismissAction() }
            .flatMap { it.selectors.asSequence() }
            .filter { it.hasStableResourceId() }
            .distinctBy { it.resourceId }
            .filter { it !in priorSelectors }
            .take((MAX_RECHECK_SELECTORS - priorSelectors.size).coerceAtLeast(0))
            .toList()
        val discoveredCheck = probe.checkStableSelectorsOnVerifiedRoot(identity, verifiedRoot, discoveredSelectors, round)
        val evidence = priorCheck.evidence + discoveredCheck.evidence
        val recheckDiagnostics = priorCheck.diagnostics.plus(discoveredCheck.diagnostics, round.nodeReadCount)
        latestRecheckDiagnostics = recheckDiagnostics
        val verifiedCandidates = evidence.values.asSequence()
            .filter { it.presence == SelectorPresence.UNIQUE_PRESENT && it.nodes.hasSafeDismissAction() }
            .map { nodes -> AccessibilityCaptureProbe.SemanticCandidate(
                nodes = nodes.nodes,
                selectors = RuleCandidateAnalyzer.stableDiffSelectorCandidates(nodes.nodes),
                visitedAtNodeCount = 0,
            ) }
            .filter { it.selectors.isNotEmpty() }
            .distinctBy { it.selectors.first().resourceId }
            .toList()
        val semanticCandidates = (verifiedCandidates + recovery.semanticCandidates).distinctBy {
            it.selectors.map(CapturedSelector::stableKey).toSet()
        }
        val recoveredNodes = (recovery as? AccessibilityCaptureProbe.RecoveryResult.Success)?.nodes
        fun hasNoUnverifiedCompetitor(nodes: List<CapturedNode>): Boolean {
            val resourceIds = RuleCandidateAnalyzer.selectorCandidates(nodes)
                .mapNotNull(CapturedSelector::resourceId).toSet()
            // 未复查完的另一关闭候选不能被忽略；已扫描部分出现的竞争候选也必须保留。
            return (priorSelectors + discoveredSelectors).all { it.resourceId in resourceIds } &&
                recovery.semanticCandidates.filter { it.nodes.hasSafeDismissAction() }.all { other ->
                    other.selectors.any { it.resourceId in resourceIds }
                }
        }
        val verifiedNodes = verifiedCandidates.singleOrNull()?.nodes
            ?.takeIf(::hasNoUnverifiedCompetitor)
        val safeRecoveredNodes = recoveredNodes?.takeIf { nodes ->
            recovery.diagnostics.isCompleteTraversal() ||
                hasNoUnverifiedCompetitor(nodes) &&
                RuleCandidateAnalyzer.selectorCandidates(nodes).any {
                    evidence[it]?.presence == SelectorPresence.UNIQUE_PRESENT
                }
        }
        val snapshotNodes = safeRecoveredNodes ?: verifiedNodes
        if (snapshotNodes != null && !round.expired()) {
            snapshot = Snapshot(
                identity = identity,
                capturedAtElapsedMs = now,
                nodes = snapshotNodes,
            )
        } else if (snapshot != null && evidence.any { (selector, checked) ->
                selector.resourceId == snapshot?.nodes?.firstOrNull()?.resourceId &&
                    checked.presence != SelectorPresence.UNIQUE_PRESENT
            }) {
            snapshot = null
        }
        return RefreshResult(
            diagnostics = recovery.diagnostics,
            semanticCandidates = semanticCandidates,
            traversalComplete = recovery.diagnostics.isCompleteTraversal(),
            semanticCandidatesComplete = recovery.semanticCandidatesComplete,
            selectorEvidence = evidence,
            recheckDiagnostics = recheckDiagnostics,
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
        val selectorEvidence: Map<CapturedSelector, SelectorEvidence> = emptyMap(),
        val recheckDiagnostics: CandidateRecheckDiagnostics = CandidateRecheckDiagnostics(),
    )

    private data class Snapshot(
        val identity: PageIdentity,
        val capturedAtElapsedMs: Long,
        val nodes: List<CapturedNode>,
    )

    private companion object {
        const val PROBE_INTERVAL_MS = 200L
        const val MAX_AGE_MS = 10_000L
        const val MAX_RECHECK_SELECTORS = 8
        // 定向验证不能耗尽新分支的发现预算；所有子预算仍扣减同一总账本。
        const val MAX_PRIOR_RECHECK_READS = 160
        const val MAX_PRIOR_RECHECK_MS = 80L
    }
}

private fun CandidateRecheckDiagnostics.plus(other: CandidateRecheckDiagnostics, totalReads: Int) =
    CandidateRecheckDiagnostics(
        queryCount = queryCount + other.queryCount,
        anchorQueryCount = anchorQueryCount + other.anchorQueryCount,
        presentCount = presentCount + other.presentCount,
        absentCount = absentCount + other.absentCount,
        ambiguousCount = ambiguousCount + other.ambiguousCount,
        unknownCount = unknownCount + other.unknownCount,
        emptyQueryCount = emptyQueryCount + other.emptyQueryCount,
        anchorScanCount = anchorScanCount + other.anchorScanCount,
        completedAnchorScanCount = completedAnchorScanCount + other.completedAnchorScanCount,
        unreadableNodeCount = unreadableNodeCount + other.unreadableNodeCount,
        budgetNodeReads = totalReads,
        elapsedMs = elapsedMs + other.elapsedMs,
        traversalLimitReason = other.traversalLimitReason ?: traversalLimitReason,
    )
