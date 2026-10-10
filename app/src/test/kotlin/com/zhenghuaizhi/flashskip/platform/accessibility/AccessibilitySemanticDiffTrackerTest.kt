package com.zhenghuaizhi.flashskip.platform.accessibility

import com.zhenghuaizhi.flashskip.domain.model.CapturedNode
import com.zhenghuaizhi.flashskip.domain.model.CapturedSelector
import com.zhenghuaizhi.flashskip.domain.model.ClickRecoveryDiagnostics
import com.zhenghuaizhi.flashskip.domain.model.ClickRecoveryOutcome
import com.zhenghuaizhi.flashskip.domain.model.SemanticDiffOutcome
import com.zhenghuaizhi.flashskip.domain.model.TraversalLimitReason
import com.zhenghuaizhi.flashskip.domain.ports.PageIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilitySemanticDiffTrackerTest {
    @Test
    fun equalVisitedCountCannotConfirmPartialDisappearanceAfterTreeReordering() {
        val fixture = Fixture()
        fixture.observe(listOf(candidate(visitedAtNodeCount = 10)), visited = 10)
        fixture.advance()
        fixture.observe(listOf(candidate(visitedAtNodeCount = 10)), visited = 10)
        fixture.advance()

        val missing = fixture.observe(
            candidates = emptyList(),
            visited = 10,
            traversalComplete = false,
        )

        assertEquals(SemanticDiffOutcome.INSUFFICIENT_COVERAGE, missing.diagnostics.outcome)
        assertEquals(0, missing.diagnostics.disappearedCandidateCount)
        assertEquals(1, missing.diagnostics.stableCandidateCount)
    }

    @Test
    fun completeTraversalCanConfirmStableDismissCandidateDisappearance() {
        val fixture = Fixture()
        fixture.observe(listOf(candidate(visitedAtNodeCount = 12)), visited = 12)
        fixture.advance()
        fixture.observe(listOf(candidate(visitedAtNodeCount = 12)), visited = 12)
        fixture.advance()

        val missing = fixture.observe(
            candidates = emptyList(),
            visited = 2,
            traversalComplete = true,
        )

        assertTrue(missing is AccessibilitySemanticDiffTracker.Result.Candidate)
        assertEquals(SemanticDiffOutcome.CANDIDATE, missing.diagnostics.outcome)
        assertEquals(1, missing.diagnostics.disappearedCandidateCount)
    }

    @Test
    fun explicitTransitionCanConfirmCompleteCandidateSetOnPartialScan() {
        val fixture = Fixture()
        fixture.observe(listOf(candidate(visitedAtNodeCount = 12)), visited = 12)
        fixture.advance()
        fixture.observe(listOf(candidate(visitedAtNodeCount = 12)), visited = 12)
        fixture.advance()

        val missing = fixture.observe(
            candidates = emptyList(),
            visited = 2,
            traversalComplete = false,
            transitionSignal = true,
        )

        assertTrue(missing is AccessibilitySemanticDiffTracker.Result.Candidate)
        assertEquals(SemanticDiffOutcome.CANDIDATE, missing.diagnostics.outcome)
        assertTrue(missing.diagnostics.transitionSignal)
    }

    @Test
    fun incompleteSemanticCandidateSetCannotConfirmAbsenceEvenWithTransition() {
        val fixture = Fixture()
        fixture.observe(listOf(candidate(visitedAtNodeCount = 12)), visited = 12)
        fixture.advance()
        fixture.observe(listOf(candidate(visitedAtNodeCount = 12)), visited = 12)
        fixture.advance()

        val missing = fixture.observe(
            candidates = emptyList(),
            visited = 2,
            traversalComplete = true,
            semanticCandidatesComplete = false,
            transitionSignal = true,
        )

        assertEquals(SemanticDiffOutcome.INSUFFICIENT_COVERAGE, missing.diagnostics.outcome)
        assertEquals(0, missing.diagnostics.disappearedCandidateCount)
        assertEquals(1, missing.diagnostics.stableCandidateCount)
    }

    @Test
    fun selectorsDroppedForUniquenessCannotMakeCandidateLookDisappeared() {
        val fixture = Fixture()
        fixture.observe(listOf(candidate(visitedAtNodeCount = 12)), visited = 12)
        fixture.advance()
        fixture.observe(listOf(candidate(visitedAtNodeCount = 12)), visited = 12)
        fixture.advance()

        val current = fixture.observe(
            candidates = listOf(candidate(visitedAtNodeCount = 4, text = "替代")),
            visited = 20,
            traversalComplete = true,
            semanticCandidatesComplete = false,
        )

        assertEquals(SemanticDiffOutcome.INSUFFICIENT_COVERAGE, current.diagnostics.outcome)
        assertEquals(0, current.diagnostics.disappearedCandidateCount)
        assertEquals(1, current.diagnostics.stableCandidateCount)
    }

    @Test
    fun manyPartialBatchesKeepCandidateHistoryBoundedAndReappearanceResetsStability() {
        val fixture = Fixture()
        val firstBatch = (0 until MAX_TRACKED_CANDIDATES).map { candidate(0, "row-$it") }
        fixture.observe(firstBatch, visited = MAX_TRACKED_CANDIDATES)
        fixture.advance()
        val initiallyStable = fixture.observe(
            firstBatch,
            visited = MAX_TRACKED_CANDIDATES,
        )
        assertEquals(MAX_TRACKED_CANDIDATES, initiallyStable.diagnostics.stableCandidateCount)

        repeat(8) { batchIndex ->
            fixture.advance()
            val batch = (0 until MAX_TRACKED_CANDIDATES).map { index ->
                candidate(0, "batch-$batchIndex-row-$index")
            }
            val partial = fixture.observe(batch, visited = MAX_TRACKED_CANDIDATES)
            assertEquals(MAX_TRACKED_CANDIDATES, partial.diagnostics.currentCandidateCount)
            assertEquals(0, partial.diagnostics.stableCandidateCount)
            assertEquals(0, partial.diagnostics.disappearedCandidateCount)
        }

        fixture.advance()
        val reappeared = fixture.observe(
            candidates = listOf(firstBatch.first()),
            visited = 1,
            traversalComplete = true,
        )
        assertEquals(0, reappeared.diagnostics.stableCandidateCount)
        fixture.advance()
        val stableAgain = fixture.observe(
            candidates = listOf(firstBatch.first()),
            visited = 1,
            traversalComplete = true,
        )
        assertEquals(1, stableAgain.diagnostics.stableCandidateCount)
    }

    @Test
    fun ambiguousAndLimitedScansAreNotCompleteTraversalEvidence() {
        assertFalse(
            diagnostics(ClickRecoveryOutcome.AMBIGUOUS).isCompleteTraversal(),
        )
        assertFalse(
            diagnostics(
                outcome = ClickRecoveryOutcome.TRAVERSAL_LIMIT,
                limit = TraversalLimitReason.TIME,
            ).isCompleteTraversal(),
        )
        assertFalse(
            diagnostics(
                outcome = ClickRecoveryOutcome.SUCCESS,
                limit = TraversalLimitReason.NODE_COUNT,
            ).isCompleteTraversal(),
        )
        assertTrue(diagnostics(ClickRecoveryOutcome.NO_MATCH).isCompleteTraversal())
        assertTrue(diagnostics(ClickRecoveryOutcome.SUCCESS).isCompleteTraversal())
    }

    private class Fixture {
        private var now = 0L
        private val tracker = AccessibilitySemanticDiffTracker { now }

        fun observe(
            candidates: List<AccessibilityCaptureProbe.SemanticCandidate>,
            visited: Int,
            traversalComplete: Boolean = false,
            semanticCandidatesComplete: Boolean = true,
            transitionSignal: Boolean = false,
        ): AccessibilitySemanticDiffTracker.Result {
            return tracker.observe(
                identity = IDENTITY,
                candidates = candidates,
                visitedNodeCount = visited,
                traversalComplete = traversalComplete,
                semanticCandidatesComplete = semanticCandidatesComplete,
                transitionSignal = transitionSignal,
            )
        }

        fun advance() {
            now += 201L
        }
    }

    private companion object {
        val IDENTITY = PageIdentity(
            packageName = "com.example.target",
            activityName = "com.example.target.MainActivity",
            windowId = 7,
        )

        const val MAX_TRACKED_CANDIDATES = 64

        fun candidate(visitedAtNodeCount: Int, text: String = "关闭") =
            AccessibilityCaptureProbe.SemanticCandidate(
                nodes = listOf(
                    CapturedNode(
                        text = text,
                        visibleToUser = true,
                        hasUsableBounds = true,
                    ),
                ),
                selectors = listOf(CapturedSelector(text = text)),
                visitedAtNodeCount = visitedAtNodeCount,
            )

        fun diagnostics(
            outcome: ClickRecoveryOutcome,
            limit: TraversalLimitReason? = null,
        ) = ClickRecoveryDiagnostics(
            outcome = outcome,
            traversalLimitReason = limit,
        )
    }
}
