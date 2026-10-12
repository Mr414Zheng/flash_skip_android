package com.zhenghuaizhi.flashskip.platform.accessibility

import com.zhenghuaizhi.flashskip.domain.model.CapturedNode
import com.zhenghuaizhi.flashskip.domain.model.CapturedSelector
import com.zhenghuaizhi.flashskip.domain.model.SemanticDiffDiagnostics
import com.zhenghuaizhi.flashskip.domain.model.SemanticDiffOutcome
import com.zhenghuaizhi.flashskip.domain.ports.PageIdentity
import com.zhenghuaizhi.flashskip.domain.rulecapture.hasStableAncestor
import com.zhenghuaizhi.flashskip.domain.rulecapture.hasStableResourceId
import com.zhenghuaizhi.flashskip.platform.accessibility.AccessibilityCaptureProbe.SemanticCandidate

/**
 * 有界语义副本只在当前会话内比较；跨次收敛仅持久化候选 selector，不保存 Android
 * 节点、坐标或完整页面树。定向复查只证明对应 selector，不提升部分扫描的整体覆盖。
 */
internal class AccessibilitySemanticDiffTracker(
    private val elapsedRealtimeMs: () -> Long,
) {
    private var identity: PageIdentity? = null
    private var trackedCandidates: List<TrackedCandidate> = emptyList()
    private var observationCount = 0
    private var recheckOffset = 0
    private var lostDismissHistory = false

    var latestDiagnostics: SemanticDiffDiagnostics? = null
        private set

    fun clear() {
        identity = null
        trackedCandidates = emptyList()
        observationCount = 0
        recheckOffset = 0
        lostDismissHistory = false
        latestDiagnostics = null
    }

    /** 只轮换可重新查询的自身 ID；稳定祖先优先，空候选不会产生索引查询。 */
    fun selectorsForRecheck(identity: PageIdentity, maxSelectors: Int = 8): List<CapturedSelector> {
        if (this.identity != null && this.identity != identity) clear()
        if (maxSelectors <= 0) return emptyList()
        val selectors = trackedCandidates.filter { it.candidate.hasSafeDismissChain() }
            .flatMap { it.candidate.recheckSelectors() }.distinct()
        if (selectors.isEmpty()) return emptyList()
        val count = minOf(maxSelectors, MAX_RECHECK_BATCH, selectors.size)
        val start = recheckOffset % selectors.size
        return List(count) { selectors[(start + it) % selectors.size] }.also {
            recheckOffset = (start + count) % selectors.size
        }
    }

    fun observe(
        identity: PageIdentity,
        candidates: List<SemanticCandidate>,
        visitedNodeCount: Int,
        traversalComplete: Boolean,
        semanticCandidatesComplete: Boolean,
        transitionSignal: Boolean,
        priorSelectors: List<CapturedSelector> = emptyList(),
        selectorEvidence: Map<CapturedSelector, SelectorEvidence> = emptyMap(),
    ): Result {
        if (this.identity != identity) {
            clear()
        }
        this.identity = identity
        val now = elapsedRealtimeMs()
        observationCount++
        val directedCandidates = selectorEvidence.mapNotNull { (selector, evidence) ->
            if (evidence.presence != SelectorPresence.UNIQUE_PRESENT ||
                evidence.nodes.isEmpty() || !selector.isRecheckable()
            ) null else SemanticCandidate(evidence.nodes, listOf(selector), visitedAtNodeCount = 0)
        }
        val currentCandidates = (candidates + directedCandidates).deduplicated()
        // 窗口过渡不能补齐本轮未遍历的范围，续扫局部批次仍然不能证明整页缺失。
        val absenceEvidenceComplete = semanticCandidatesComplete && traversalComplete
        val previous = trackedCandidates
        val newlyTracked = currentCandidates.map { current ->
            val existing = previous.firstOrNull { it.candidate.overlaps(current) }
                ?.takeIf { now - it.lastSeenAtElapsedMs in 0..MAX_AGE_MS }
            if (existing == null) {
                TrackedCandidate(current, firstSeenAtElapsedMs = now, lastSeenAtElapsedMs = now)
            } else {
                existing.copy(
                    candidate = current,
                    consecutiveObservationCount = existing.consecutiveObservationCount + 1,
                    lastSeenAtElapsedMs = now,
                )
            }
        }
        val globalDisappearances = previous.filter { tracked ->
            tracked.isStableAt(now) &&
                currentCandidates.none { current -> tracked.candidate.overlaps(current) } &&
                absenceEvidenceComplete
        }
        val directedDisappearances = previous.filter { tracked ->
            currentCandidates.none { tracked.candidate.overlaps(it) } &&
                tracked.candidate.recheckSelectors().any { selector ->
                    val evidence = selectorEvidence[selector]
                    evidence?.presence == SelectorPresence.VERIFIED_ABSENT &&
                        evidence.scope != SelectorEvidenceScope.NONE &&
                        tracked.directed[selector]?.isStableAt(now) == true &&
                        tracked.candidate.hasSafeDismissChain()
                }
        }
        val coverageConfirmedDisappearances = (globalDisappearances + directedDisappearances)
            .distinctBy { it.candidate }
        val insufficientCoverageCount = previous.count { tracked ->
            tracked.isStableAt(now) &&
                currentCandidates.none { current -> tracked.candidate.overlaps(current) } &&
                !absenceEvidenceComplete && tracked !in directedDisappearances
        }
        // 部分扫描或候选集合有损时，缺失不能作为消失证据，也不能丢掉旧候选稳定性。
        val carriedCandidates = previous.filter { tracked ->
            currentCandidates.none { current -> tracked.candidate.overlaps(current) } &&
                !absenceEvidenceComplete
        }
        val nextCandidates = (newlyTracked + carriedCandidates)
            .map { tracked -> tracked.withDirectedEvidence(selectorEvidence, now) }
            .sortedByDescending { it.candidate.hasSafeDismissChain() }
        // 普通结构节点不能挤掉待复查的关闭候选；丢失关闭历史时不能再假定没有竞争者。
        val dismissedHistoryIncomplete = lostDismissHistory ||
            nextCandidates.count { it.candidate.hasSafeDismissChain() } > MAX_TRACKED_CANDIDATES
        val nextTrackedCandidates = nextCandidates.take(MAX_TRACKED_CANDIDATES)
        lostDismissHistory = dismissedHistoryIncomplete && !absenceEvidenceComplete
        val eligibleGlobal = globalDisappearances.filter { tracked ->
            tracked.candidate.nodes.hasSafeDismissAction()
        }
        val eligibleDisappearances = (eligibleGlobal + directedDisappearances).distinctBy { it.candidate }
        // 已确认一个 selector 消失，也不能绕过另一个尚未排除歧义的关闭控件。
        val unresolvedDismissCount = (previous + newlyTracked).distinctBy { it.candidate }.count { tracked ->
            tracked.candidate.hasSafeDismissChain() && tracked !in coverageConfirmedDisappearances &&
                !(absenceEvidenceComplete && currentCandidates.any { tracked.candidate.overlaps(it) }) &&
                tracked.candidate.recheckSelectors().none { selector ->
                    val evidence = selectorEvidence[selector]
                    evidence?.presence == SelectorPresence.UNIQUE_PRESENT &&
                        evidence.nodes.hasSafeDismissChain()
                }
        } + if (dismissedHistoryIncomplete) 1 else 0
        val priorKeys = priorSelectors.map(CapturedSelector::stableKey).toSet()
        val narrowedDisappearances = eligibleDisappearances.filter { tracked ->
            tracked.candidate.selectors.any { it.stableKey() in priorKeys }
        }
        val priorConflict = priorKeys.isNotEmpty() &&
            eligibleDisappearances.isNotEmpty() &&
            narrowedDisappearances.isEmpty()
        val candidatePool = when {
            priorKeys.isEmpty() -> eligibleDisappearances
            else -> narrowedDisappearances
        }
        val selected = candidatePool.singleOrNull()?.takeIf { unresolvedDismissCount == 0 }
        val outcome = when {
            observationCount == 1 -> SemanticDiffOutcome.BASELINE
            selected != null -> SemanticDiffOutcome.CANDIDATE
            candidatePool.size > 1 || priorConflict -> SemanticDiffOutcome.AMBIGUOUS
            insufficientCoverageCount > 0 || candidatePool.isNotEmpty() -> SemanticDiffOutcome.INSUFFICIENT_COVERAGE
            else -> SemanticDiffOutcome.TRACKING
        }
        val diagnostics = SemanticDiffDiagnostics(
            outcome = outcome,
            observationCount = observationCount,
            currentCandidateCount = currentCandidates.size,
            stableCandidateCount = nextTrackedCandidates.count { it.isStableAt(now) },
            disappearedCandidateCount = coverageConfirmedDisappearances.size,
            visitedNodeCount = visitedNodeCount,
            transitionSignal = transitionSignal,
            directedPresentCount = selectorEvidence.values.count { it.presence == SelectorPresence.UNIQUE_PRESENT },
            directedAbsentCount = selectorEvidence.values.count { it.presence == SelectorPresence.VERIFIED_ABSENT },
            directedUnknownCount = selectorEvidence.values.count { it.presence == SelectorPresence.UNKNOWN },
            directedAmbiguousCount = selectorEvidence.values.count { it.presence == SelectorPresence.AMBIGUOUS_PRESENT },
            unresolvedDismissCount = unresolvedDismissCount,
        )
        latestDiagnostics = diagnostics
        trackedCandidates = nextTrackedCandidates
        return when {
            selected != null -> Result.Candidate(selected.candidate, diagnostics)
            candidatePool.size > 1 || priorConflict -> Result.Ambiguous(
                candidates = (candidatePool.takeIf { it.isNotEmpty() }
                    ?: eligibleDisappearances).map(TrackedCandidate::candidate),
                diagnostics = diagnostics,
            )
            else -> Result.NoCandidate(diagnostics)
        }
    }

    private fun List<SemanticCandidate>.deduplicated(): List<SemanticCandidate> = buildList {
        this@deduplicated.forEach { candidate ->
            if (none { existing -> candidate.overlaps(existing) }) add(candidate)
        }
    }

    private fun SemanticCandidate.overlaps(
        other: SemanticCandidate,
    ): Boolean {
        val keys = selectors.asSequence().map(CapturedSelector::stableKey).toSet()
        return other.selectors.any { it.stableKey() in keys }
    }

    private fun CapturedSelector.isRecheckable(): Boolean =
        hasStableResourceId() && resourceId?.isNotBlank() == true

    private fun SemanticCandidate.recheckSelectors(): List<CapturedSelector> = selectors
        .filter { it.isRecheckable() }
        .distinct()
        .sortedByDescending { it.hasStableAncestor() }
        .take(MAX_DIRECTED_ALIASES)

    private fun SemanticCandidate.hasSafeDismissChain(): Boolean = nodes.hasSafeDismissChain()

    private fun List<CapturedNode>.hasSafeDismissChain(): Boolean = hasSafeDismissAction()

    private fun TrackedCandidate.withDirectedEvidence(
        evidence: Map<CapturedSelector, SelectorEvidence>,
        now: Long,
    ): TrackedCandidate {
        val next = linkedMapOf<CapturedSelector, DirectedObservation>()
        candidate.recheckSelectors().forEach { selector ->
            val previous = directed[selector]?.takeIf { now - it.lastSeenAtElapsedMs in 0..MAX_AGE_MS }
            val current = evidence[selector]
            when (current?.presence) {
                SelectorPresence.UNIQUE_PRESENT -> if (current.nodes.hasSafeDismissChain()) {
                    next[selector] = if (previous == null) DirectedObservation(now, now) else previous.copy(
                        lastSeenAtElapsedMs = now, count = previous.count + 1,
                    )
                }
                SelectorPresence.UNKNOWN, SelectorPresence.AMBIGUOUS_PRESENT -> Unit
                SelectorPresence.VERIFIED_ABSENT -> if (previous?.isStableAt(now) == true) next[selector] = previous
                null -> if (previous != null) next[selector] = previous
            }
        }
        return copy(directed = next)
    }

    private data class TrackedCandidate(
        val candidate: SemanticCandidate,
        val firstSeenAtElapsedMs: Long,
        val lastSeenAtElapsedMs: Long,
        val consecutiveObservationCount: Int = 1,
        val directed: Map<CapturedSelector, DirectedObservation> = emptyMap(),
    ) {
        fun isStableAt(now: Long): Boolean =
            consecutiveObservationCount >= REQUIRED_OBSERVATIONS &&
                now - firstSeenAtElapsedMs >= MIN_STABILITY_MS &&
                now - lastSeenAtElapsedMs in 0..MAX_AGE_MS
    }

    private data class DirectedObservation(
        val firstSeenAtElapsedMs: Long,
        val lastSeenAtElapsedMs: Long,
        val count: Int = 1,
    ) {
        fun isStableAt(now: Long): Boolean = count >= REQUIRED_OBSERVATIONS &&
            lastSeenAtElapsedMs - firstSeenAtElapsedMs >= MIN_STABILITY_MS &&
            now - lastSeenAtElapsedMs in 0..MAX_AGE_MS
    }

    sealed interface Result {
        val diagnostics: SemanticDiffDiagnostics

        data class Candidate(
            val candidate: SemanticCandidate,
            override val diagnostics: SemanticDiffDiagnostics,
        ) : Result

        data class Ambiguous(
            val candidates: List<SemanticCandidate>,
            override val diagnostics: SemanticDiffDiagnostics,
        ) : Result

        data class NoCandidate(
            override val diagnostics: SemanticDiffDiagnostics,
        ) : Result
    }

    private companion object {
        const val REQUIRED_OBSERVATIONS = 2
        const val MIN_STABILITY_MS = 200L
        const val MAX_TRACKED_CANDIDATES = 64
        const val MAX_DIRECTED_ALIASES = 8
        const val MAX_RECHECK_BATCH = 8
        const val MAX_AGE_MS = 10_000L
    }
}
