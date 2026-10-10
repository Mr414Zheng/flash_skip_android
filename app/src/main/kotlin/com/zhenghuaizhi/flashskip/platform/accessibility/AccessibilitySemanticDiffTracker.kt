package com.zhenghuaizhi.flashskip.platform.accessibility

import com.zhenghuaizhi.flashskip.domain.model.CapturedSelector
import com.zhenghuaizhi.flashskip.domain.model.SemanticDiffDiagnostics
import com.zhenghuaizhi.flashskip.domain.model.SemanticDiffOutcome
import com.zhenghuaizhi.flashskip.domain.ports.PageIdentity
import com.zhenghuaizhi.flashskip.domain.rulecapture.DismissSemantics
import com.zhenghuaizhi.flashskip.platform.accessibility.AccessibilityCaptureProbe.SemanticCandidate

/**
 * 有界语义副本只在当前会话内比较；跨次收敛仅持久化候选 selector，不保存 Android
 * 节点、坐标或完整页面树。候选稳定出现两次并从覆盖充分的新快照中消失后才进入确认。
 */
internal class AccessibilitySemanticDiffTracker(
    private val elapsedRealtimeMs: () -> Long,
) {
    private var identity: PageIdentity? = null
    private var trackedCandidates: List<TrackedCandidate> = emptyList()
    private var observationCount = 0

    var latestDiagnostics: SemanticDiffDiagnostics? = null
        private set

    fun clear() {
        identity = null
        trackedCandidates = emptyList()
        observationCount = 0
        latestDiagnostics = null
    }

    fun observe(
        identity: PageIdentity,
        candidates: List<SemanticCandidate>,
        visitedNodeCount: Int,
        traversalComplete: Boolean,
        semanticCandidatesComplete: Boolean,
        transitionSignal: Boolean,
        priorSelectors: List<CapturedSelector> = emptyList(),
    ): Result {
        if (this.identity?.sameCaptureScope(identity) != true) {
            clear()
        }
        this.identity = identity
        val now = elapsedRealtimeMs()
        observationCount++
        val currentCandidates = candidates.deduplicated()
        val absenceEvidenceComplete = semanticCandidatesComplete &&
            (traversalComplete || transitionSignal)
        val previous = trackedCandidates
        val newlyTracked = currentCandidates.map { current ->
            val existing = previous.firstOrNull { it.candidate.overlaps(current) }
            if (existing == null) {
                TrackedCandidate(current, firstSeenAtElapsedMs = now)
            } else {
                existing.copy(
                    candidate = current,
                    consecutiveObservationCount = existing.consecutiveObservationCount + 1,
                )
            }
        }
        val coverageConfirmedDisappearances = previous.filter { tracked ->
            tracked.isStableAt(now) &&
                currentCandidates.none { current -> tracked.candidate.overlaps(current) } &&
                absenceEvidenceComplete
        }
        val insufficientCoverageCount = previous.count { tracked ->
            tracked.isStableAt(now) &&
                currentCandidates.none { current -> tracked.candidate.overlaps(current) } &&
                !absenceEvidenceComplete
        }
        // 部分扫描或候选集合有损时，缺失不能作为消失证据，也不能丢掉旧候选稳定性。
        val carriedCandidates = previous.filter { tracked ->
            currentCandidates.none { current -> tracked.candidate.overlaps(current) } &&
                !absenceEvidenceComplete
        }
        val nextTrackedCandidates = (newlyTracked + carriedCandidates)
            .take(MAX_TRACKED_CANDIDATES)
        val eligibleDisappearances = coverageConfirmedDisappearances.filter { tracked ->
            transitionSignal || tracked.candidate.hasDismissHint()
        }
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
        val selected = candidatePool.singleOrNull()
        val outcome = when {
            observationCount == 1 -> SemanticDiffOutcome.BASELINE
            selected != null -> SemanticDiffOutcome.CANDIDATE
            candidatePool.isNotEmpty() || priorConflict -> SemanticDiffOutcome.AMBIGUOUS
            insufficientCoverageCount > 0 -> SemanticDiffOutcome.INSUFFICIENT_COVERAGE
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
        )
        latestDiagnostics = diagnostics
        trackedCandidates = nextTrackedCandidates
        return when {
            selected != null -> Result.Candidate(selected.candidate, diagnostics)
            candidatePool.isNotEmpty() || priorConflict -> Result.Ambiguous(
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

    private fun SemanticCandidate.hasDismissHint(): Boolean =
        selectors.any { selector ->
            DismissSemantics.isKnownResourceId(selector.resourceId) ||
                DismissSemantics.isKnownResourceId(selector.ancestorResourceId) ||
                DismissSemantics.isKnown(selector.text) ||
                DismissSemantics.isKnown(selector.contentDescription)
        }

    private fun PageIdentity.sameCaptureScope(other: PageIdentity): Boolean =
        packageName == other.packageName && activityName == other.activityName

    private data class TrackedCandidate(
        val candidate: SemanticCandidate,
        val firstSeenAtElapsedMs: Long,
        val consecutiveObservationCount: Int = 1,
    ) {
        fun isStableAt(now: Long): Boolean =
            consecutiveObservationCount >= REQUIRED_OBSERVATIONS &&
                now - firstSeenAtElapsedMs >= MIN_STABILITY_MS
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
    }
}
