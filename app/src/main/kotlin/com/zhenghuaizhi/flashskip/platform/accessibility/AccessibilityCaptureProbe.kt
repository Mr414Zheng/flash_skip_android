package com.zhenghuaizhi.flashskip.platform.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo
import com.zhenghuaizhi.flashskip.domain.automation.SelectorMatcher
import com.zhenghuaizhi.flashskip.domain.model.CandidateRecheckDiagnostics
import com.zhenghuaizhi.flashskip.domain.model.CapturedNode
import com.zhenghuaizhi.flashskip.domain.model.CapturedSelector
import com.zhenghuaizhi.flashskip.domain.model.CaptureCandidateRejection
import com.zhenghuaizhi.flashskip.domain.model.CaptureScanUnavailableReason
import com.zhenghuaizhi.flashskip.domain.model.CaptureScanStrategy
import com.zhenghuaizhi.flashskip.domain.model.ClickRecoveryDiagnostics
import com.zhenghuaizhi.flashskip.domain.model.ClickRecoveryOutcome
import com.zhenghuaizhi.flashskip.domain.model.TraversalLimitReason
import com.zhenghuaizhi.flashskip.domain.ports.PageIdentity
import com.zhenghuaizhi.flashskip.domain.rulecapture.ClickSemanticHint
import com.zhenghuaizhi.flashskip.domain.rulecapture.ClickSourceRecoveryMatcher
import com.zhenghuaizhi.flashskip.domain.rulecapture.DismissSemantics
import com.zhenghuaizhi.flashskip.domain.rulecapture.RuleCandidateAnalyzer
import com.zhenghuaizhi.flashskip.domain.rulecapture.hasStableResourceId
import com.zhenghuaizhi.flashskip.domain.rulecapture.hasStableAncestor

internal class AccessibilityCaptureProbe(
    private val service: AccessibilityService,
    private val targetPackages: () -> Set<String>,
    private val currentIdentity: () -> PageIdentity?,
    private val semanticSnapshotter: AccessibilitySemanticSnapshotter,
    private val elapsedRealtimeMs: () -> Long = AndroidMonotonicClock::elapsedRealtimeMs,
    private val refreshNode: (AccessibilityNodeInfo) -> Boolean = { it.refresh() },
    private val queryById: (AccessibilityNodeInfo, String) -> List<AccessibilityNodeInfo> = { root, id -> root.findAccessibilityNodeInfosByViewId(id) },
    private val onCandidateSnapshot: (CapturedNode, List<CapturedNode>?) -> Unit = { _, _ -> },
) {
    private val rootResolver = AccessibilityWindowRootResolver(service, targetPackages)
    private val coverageCursor = CaptureCoverageCursor()
    private val recheck = AccessibilityCandidateRecheck(currentIdentity, semanticSnapshotter, elapsedRealtimeMs, refreshNode, queryById)

    fun newRoundBudget(deadlineAtElapsedMs: Long? = null): CaptureRoundBudget =
        CaptureRoundBudget.create(elapsedRealtimeMs, deadlineAtElapsedMs)

    fun resetCoverage() = coverageCursor.reset()

    fun checkStableSelectorsOnVerifiedRoot(
        expected: PageIdentity,
        verifiedRoot: AccessibilityNodeInfo,
        selectors: List<CapturedSelector>,
        budget: CaptureRoundBudget,
    ): SelectorCheckBatch {
        if (expected.packageName !in targetPackages()) return SelectorCheckBatch(
            selectors.associateWith { SelectorEvidence(SelectorPresence.UNKNOWN, reason = SelectorEvidenceReason.IDENTITY_CHANGED) },
            CandidateRecheckDiagnostics(unknownCount = selectors.size, budgetNodeReads = budget.nodeReadCount),
        )
        return recheck.check(expected, verifiedRoot, selectors, budget)
    }

    @Suppress("DEPRECATION")
    fun uniqueSelectors(expected: PageIdentity, selectors: List<CapturedSelector>): ProbeResult {
        if (selectors.isEmpty()) return ProbeResult.Success(emptySet())
        if (expected.packageName !in targetPackages() || currentIdentity() != expected) return ProbeResult.PageChanged
        val root = when (val resolved = rootResolver.resolve(expected)) {
            is AccessibilityWindowRootResolver.Result.Success -> resolved.root
            AccessibilityWindowRootResolver.Result.NoRoot -> return ProbeResult.NoRoot
            else -> return ProbeResult.PageChanged
        }
        return try { uniqueSelectorsOnVerifiedRoot(expected, root, selectors) } finally { root.recycle() }
    }

    fun uniqueSelectorsOnVerifiedRoot(
        expected: PageIdentity,
        verifiedRoot: AccessibilityNodeInfo,
        selectors: List<CapturedSelector>,
        deadlineAtElapsedMs: Long? = null,
        budget: CaptureRoundBudget? = null,
    ): ProbeResult {
        if (selectors.isEmpty()) return ProbeResult.Success(emptySet())
        if (expected.packageName !in targetPackages() || !verifiedRoot.matches(expected) || currentIdentity() != expected) return ProbeResult.PageChanged
        val round = budget ?: newRoundBudget(deadlineAtElapsedMs)
        val counts = selectors.associateWith { 0 }.toMutableMap()
        var unavailable = false
        val traversal = CaptureCoverageCursor().traverse(expected, verifiedRoot, round) { node, _ ->
            val snapshot = semanticSnapshotter.snapshot(node, expected.packageName)
            if (snapshot == null) { unavailable = true; return@traverse false }
            val matching = selectors.filter { counts.getValue(it) < 2 && SelectorMatcher.matchesTarget(it.toNodeSelector(), snapshot.toMatcherSnapshot()) }
            if (matching.isNotEmpty()) {
                val chain = copyBudgetedChain(node, expected.packageName, semanticSnapshotter, round, initialSnapshot = snapshot)
                if (chain == null) { unavailable = true; return@traverse false }
                matching.forEach { selector ->
                    if (SelectorMatcher.matches(selector.toNodeSelector(), snapshot.toMatcherSnapshot(), chain.drop(1).map { it.toMatcherSnapshot() })) counts[selector] = counts.getValue(selector) + 1
                }
            }
            true
        }
        if (currentIdentity() != expected || !verifiedRoot.matches(expected)) return ProbeResult.PageChanged
        if (traversal.limitReason != null || round.expired() || round.limitReason() != null && !traversal.complete) return ProbeResult.TraversalLimit
        if (unavailable || traversal.unreadable || !traversal.complete) return ProbeResult.Unavailable
        return ProbeResult.Success(counts.filterValues { it == 1 }.keys)
    }

    @Suppress("DEPRECATION")
    fun recoverClickedSemanticChain(expected: PageIdentity, hint: ClickSemanticHint): RecoveryResult {
        val started = elapsedRealtimeMs()
        if (expected.packageName !in targetPackages()) return failure(ClickRecoveryOutcome.OUTSIDE_TARGETS, started)
        if (currentIdentity() != expected) { resetCoverage(); return failure(ClickRecoveryOutcome.IDENTITY_CHANGED, started) }
        val root = when (val resolved = rootResolver.resolve(expected)) {
            is AccessibilityWindowRootResolver.Result.Success -> resolved.root
            AccessibilityWindowRootResolver.Result.NoRoot -> return failure(ClickRecoveryOutcome.NO_ROOT, started)
            AccessibilityWindowRootResolver.Result.OutsideTargets -> return failure(ClickRecoveryOutcome.OUTSIDE_TARGETS, started)
            AccessibilityWindowRootResolver.Result.PageChanged -> return failure(ClickRecoveryOutcome.ROOT_MISMATCH, started)
        }
        return try { recoverClickedSemanticChainOnVerifiedRoot(expected, root, hint) } finally { root.recycle() }
    }

    fun recoverClickedSemanticChainOnVerifiedRoot(
        expected: PageIdentity,
        verifiedRoot: AccessibilityNodeInfo,
        hint: ClickSemanticHint,
        deadlineAtElapsedMs: Long? = null,
        budget: CaptureRoundBudget? = null,
        continueCoverage: Boolean = false,
    ): RecoveryResult {
        val started = elapsedRealtimeMs()
        if (expected.packageName !in targetPackages()) { resetCoverage(); return failure(ClickRecoveryOutcome.OUTSIDE_TARGETS, started) }
        if (currentIdentity() != expected) { resetCoverage(); return failure(ClickRecoveryOutcome.IDENTITY_CHANGED, started) }
        if (!verifiedRoot.matches(expected)) { resetCoverage(); return failure(ClickRecoveryOutcome.ROOT_MISMATCH, started) }
        val round = budget ?: newRoundBudget(deadlineAtElapsedMs)
        var recovered: List<CapturedNode>? = null
        var ambiguous = false
        var unavailable = false
        var unavailableReason: CaptureScanUnavailableReason? = null
        var semanticNodes = 0
        var matchedNodes = 0
        var knownDismissNodes = 0
        var explicitDismissNodes = 0
        var selectorCandidateNodes = 0
        var countdownSemanticNodes = 0
        var countdownActionChains = 0
        val candidates = mutableListOf<SemanticCandidate>()
        val rejected = mutableMapOf<CaptureCandidateRejection, Int>()
        var overflow = false
        var visited = 0
        val traversal = coverageCursor.traverse(expected, verifiedRoot, round, continueCoverage) { node, _ ->
            visited++
            val snapshot = semanticSnapshotter.snapshot(node, expected.packageName)
            if (snapshot == null) {
                unavailable = true
                unavailableReason = CaptureScanUnavailableReason.NODE_UNREADABLE
                return@traverse false
            }
            if (snapshot.visibleToUser && (snapshot.resourceId != null || snapshot.text != null || snapshot.contentDescription != null)) semanticNodes++
            var copiedChain: List<CapturedNode>? = null
            var stableSelectors: List<CapturedSelector>? = null
            var selectors: List<CapturedSelector>? = null
            if (snapshot.visibleToUser && snapshot.hasUsableBounds && (snapshot.resourceId != null || snapshot.clickable || snapshot.supportsActionClick)) {
                copiedChain = copyBudgetedChain(node, expected.packageName, semanticSnapshotter, round, initialSnapshot = snapshot)
                if (copiedChain == null) {
                    unavailable = true
                    if (round.limitReason() == null) {
                        unavailableReason = CaptureScanUnavailableReason.NODE_UNREADABLE
                        rejected.merge(CaptureCandidateRejection.ACTION_CHAIN_UNAVAILABLE, 1, Int::plus)
                    }
                    return@traverse false
                }
                stableSelectors = RuleCandidateAnalyzer.stableDiffSelectorCandidates(copiedChain)
                if (stableSelectors.isNotEmpty()) {
                    val candidate = SemanticCandidate(copiedChain, stableSelectors, visited)
                    if (candidates.size < MAX_SEMANTIC_CANDIDATES) candidates += candidate
                    else {
                        overflow = true
                        // 有界列表优先保留真实关闭动作；普通结构节点不能挤掉后发现的关闭候选。
                        if (copiedChain.hasSafeDismissAction()) {
                            val replaceAt = candidates.indexOfLast { !it.nodes.hasSafeDismissAction() }
                            if (replaceAt >= 0) candidates[replaceAt] = candidate
                        }
                    }
                }
            }
            val isCountdown = hint.allowBareNumericCountdown && ClickSourceRecoveryMatcher.matches(snapshot, ClickSemanticHint(allowBareNumericCountdown = true)) && DismissSemantics.isBareNumericCountdown(snapshot.text)
            if (isCountdown) countdownSemanticNodes++
            if (ClickSourceRecoveryMatcher.matches(snapshot, ClickSemanticHint(allowBareNumericCountdown = hint.allowBareNumericCountdown))) knownDismissNodes++
            if (ClickSourceRecoveryMatcher.matches(snapshot, ClickSemanticHint())) explicitDismissNodes++
            if (ClickSourceRecoveryMatcher.matches(snapshot, hint)) {
                matchedNodes++
                val chain = copiedChain ?: copyBudgetedChain(node, expected.packageName, semanticSnapshotter, round, initialSnapshot = snapshot)
                if (chain == null) {
                    unavailable = true
                    if (round.limitReason() == null) {
                        unavailableReason = CaptureScanUnavailableReason.NODE_UNREADABLE
                        rejected.merge(CaptureCandidateRejection.ACTION_CHAIN_UNAVAILABLE, 1, Int::plus)
                    }
                    return@traverse false
                }
                if (isCountdown && chain.any { it.visibleToUser && it.hasUsableBounds && (it.clickable || it.supportsActionClick) }) countdownActionChains++
                selectors = RuleCandidateAnalyzer.selectorCandidates(chain)
                if (selectors.isNotEmpty()) {
                    selectorCandidateNodes++
                    if (recovered == null) recovered = chain else ambiguous = true
                }
                copiedChain = chain
            }
            val finalChain = copiedChain
            if (finalChain != null && selectors == null) {
                selectors = stableSelectors?.takeIf { it.isNotEmpty() } ?: RuleCandidateAnalyzer.selectorCandidates(finalChain)
            }
            // 只复用已取得的节点链；事件提示可能允许采集真实点击，但不会替代稳定关闭候选条件。
            val diagnosticStableSelectors = stableSelectors ?: selectors?.filter {
                it.hasStableResourceId() || it.hasStableAncestor()
            }
            val rejection = captureCandidateRejection(snapshot, finalChain, selectors, diagnosticStableSelectors)
            if (rejection != null) rejected.merge(rejection, 1, Int::plus)
            // 诊断复用已有快照和动作链，不增加节点读取，也不能中断业务扫描。
            try { onCandidateSnapshot(snapshot, finalChain) } catch (_: Exception) { }
            !ambiguous
        }
        val identityChanged = currentIdentity() != expected || !verifiedRoot.matches(expected)
        if (identityChanged) resetCoverage()
        val limit = traversal.limitReason ?: round.limitReason().takeIf { unavailable }
        val expired = round.expired()
        // 单轮完整遍历才可证明唯一；续扫和定向阳性仍保留候选线索，不能提升点击归因。
        val canKeep = recovered != null && selectorCandidateNodes == 1 &&
            traversal.complete && limit == null && !ambiguous && !unavailable &&
            !traversal.unreadable && !identityChanged && !expired
        val outcome = when {
            identityChanged -> ClickRecoveryOutcome.IDENTITY_CHANGED
            expired -> ClickRecoveryOutcome.TRAVERSAL_LIMIT
            ambiguous -> ClickRecoveryOutcome.AMBIGUOUS
            canKeep -> ClickRecoveryOutcome.SUCCESS
            limit != null -> ClickRecoveryOutcome.TRAVERSAL_LIMIT
            unavailable || traversal.unreadable -> ClickRecoveryOutcome.UNAVAILABLE
            else -> ClickRecoveryOutcome.NO_MATCH
        }
        val snapshot = if (identityChanged || expired) SemanticCandidateSnapshot(emptyList(), false) else uniqueSemanticCandidates(candidates).let { it.copy(complete = it.complete && !overflow) }
        val diagnostics = ClickRecoveryDiagnostics(
            outcome = outcome, visitedNodeCount = traversal.visited, maxDepth = traversal.maxDepth,
            elapsedMs = (elapsedRealtimeMs() - started).coerceAtLeast(0), semanticNodeCount = semanticNodes,
            matchedNodeCount = matchedNodes, knownDismissNodeCount = knownDismissNodes,
            explicitDismissNodeCount = explicitDismissNodes, selectorCandidateNodeCount = selectorCandidateNodes,
            countdownSemanticNodeCount = countdownSemanticNodes, countdownActionChainCount = countdownActionChains,
            traversalLimitReason = if (expired) TraversalLimitReason.TIME else limit,
            scanStrategy = if (traversal.resumed) CaptureScanStrategy.CONTINUATION else CaptureScanStrategy.FULL_ROOT,
            coverageComplete = traversal.complete && !unavailable && !identityChanged && !expired,
            continuationDepth = traversal.continuationDepth, pendingBranchCount = traversal.pendingBranchCount,
            cursorReset = traversal.cursorReset, budgetNodeReads = round.nodeReadCount,
            rejectedCandidateCounts = rejected.toMap(),
            unavailableReason = if (identityChanged) CaptureScanUnavailableReason.IDENTITY_CHANGED
                else unavailableReason ?: traversal.unavailableReason,
        )
        return if (canKeep) RecoveryResult.Success(requireNotNull(recovered), diagnostics, snapshot.candidates, snapshot.complete && !traversal.unreadable && !unavailable)
        else RecoveryResult.Failure(diagnostics, snapshot.candidates, snapshot.complete && !traversal.unreadable && !unavailable)
    }

    private fun uniqueSemanticCandidates(candidates: List<SemanticCandidate>): SemanticCandidateSnapshot {
        val counts = candidates.flatMap { it.selectors }.groupingBy { it.stableKey() }.eachCount()
        var omitted = false
        val unique = candidates.mapNotNull { candidate ->
            val selectors = candidate.selectors.filter { counts[it.stableKey()] == 1 }
            if (selectors.size != candidate.selectors.size) omitted = true
            candidate.copy(selectors = selectors).takeIf { selectors.isNotEmpty() }
        }
        return SemanticCandidateSnapshot(unique, !omitted)
    }

    private fun failure(outcome: ClickRecoveryOutcome, started: Long) = RecoveryResult.Failure(ClickRecoveryDiagnostics(outcome, elapsedMs = (elapsedRealtimeMs() - started).coerceAtLeast(0), coverageComplete = false))
    private fun AccessibilityNodeInfo.matches(expected: PageIdentity): Boolean = packageName?.toString() == expected.packageName && windowId == expected.windowId

    sealed interface ProbeResult {
        data class Success(val selectors: Set<CapturedSelector>) : ProbeResult
        data object NoRoot : ProbeResult
        data object Unavailable : ProbeResult
        data object PageChanged : ProbeResult
        data object TraversalLimit : ProbeResult
    }
    sealed interface RecoveryResult {
        val diagnostics: ClickRecoveryDiagnostics
        val semanticCandidates: List<SemanticCandidate>
        val semanticCandidatesComplete: Boolean
        data class Success(val nodes: List<CapturedNode>, override val diagnostics: ClickRecoveryDiagnostics, override val semanticCandidates: List<SemanticCandidate> = emptyList(), override val semanticCandidatesComplete: Boolean = false) : RecoveryResult
        data class Failure(override val diagnostics: ClickRecoveryDiagnostics, override val semanticCandidates: List<SemanticCandidate> = emptyList(), override val semanticCandidatesComplete: Boolean = false) : RecoveryResult
    }
    data class SemanticCandidate(val nodes: List<CapturedNode>, val selectors: List<CapturedSelector>, val visitedAtNodeCount: Int)
    private data class SemanticCandidateSnapshot(val candidates: List<SemanticCandidate>, val complete: Boolean)
    private companion object { const val MAX_SEMANTIC_CANDIDATES = 64 }
}

internal fun ClickRecoveryDiagnostics.isCompleteTraversal(): Boolean =
    coverageComplete && traversalLimitReason == null && outcome in setOf(ClickRecoveryOutcome.NO_MATCH, ClickRecoveryOutcome.SUCCESS)
