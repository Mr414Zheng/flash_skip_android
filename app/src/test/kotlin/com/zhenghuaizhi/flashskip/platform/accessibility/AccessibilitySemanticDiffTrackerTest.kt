package com.zhenghuaizhi.flashskip.platform.accessibility

import com.zhenghuaizhi.flashskip.domain.model.*
import com.zhenghuaizhi.flashskip.domain.ports.PageIdentity
import com.zhenghuaizhi.flashskip.testing.captureSample
import org.junit.Assert.*
import org.junit.Test

class AccessibilitySemanticDiffTrackerTest {
    private var now = 0L
    private val sample = captureSample()
    private val selector = sample.preClickUniqueSelectors.single()
    private val candidate = AccessibilityCaptureProbe.SemanticCandidate(sample.nodes, listOf(selector), 1)
    private val identity = PageIdentity(sample.targetAppId.packageName, sample.activityName, 7)
    private val tracker = AccessibilitySemanticDiffTracker { now }

    @Test fun ac06_twoStableObservationsThenCompleteAbsenceProduceOnlyCandidate() {
        assertEquals(SemanticDiffOutcome.BASELINE, observe(listOf(candidate)).diagnostics.outcome)
        now = 200
        observe(listOf(candidate))
        now = 201
        assertTrue(observe(emptyList()) is AccessibilitySemanticDiffTracker.Result.Candidate)
    }

    @Test fun ac06_oneObservationAndTooShortStabilityCannotSelect() {
        observe(listOf(candidate))
        now = 100
        assertFalse(observe(emptyList()) is AccessibilitySemanticDiffTracker.Result.Candidate)
        tracker.clear(); now = 0
        observe(listOf(candidate)); now = 199
        observe(listOf(candidate))
        assertFalse(observe(emptyList()) is AccessibilitySemanticDiffTracker.Result.Candidate)
    }

    @Test fun ac06_partialOrLossyScanAndTransitionCannotProveDisappearances() {
        stable()
        val partial = observe(emptyList(), complete = false, transition = true)
        assertEquals(SemanticDiffOutcome.INSUFFICIENT_COVERAGE, partial.diagnostics.outcome)
        assertEquals(0, partial.diagnostics.disappearedCandidateCount)
        assertFalse(tracker.observe(identity, emptyList(), 1, true, false, true) is AccessibilitySemanticDiffTracker.Result.Candidate)
        assertTrue(observe(emptyList()) is AccessibilitySemanticDiffTracker.Result.Candidate)
    }

    @Test fun ac06_identityChangeAndOldHistoryCannotSelect() {
        stable()
        assertEquals(SemanticDiffOutcome.BASELINE, tracker.observe(identity.copy(windowId = 8), emptyList(), 1, true, true, true).diagnostics.outcome)
        tracker.clear(); now = 0; stable()
        now = 10_201
        assertFalse(observe(emptyList()) is AccessibilitySemanticDiffTracker.Result.Candidate)
    }

    @Test fun ac06_twoSafeDisappearancesAreAmbiguousAndPriorIntersectionNarrows() {
        val other = candidate.copy(nodes = listOf(sample.nodes.single().copy(resourceId = "app:id/close")), selectors = listOf(selector.copy(resourceId = "app:id/close")))
        observe(listOf(candidate, other)); now = 200; observe(listOf(candidate, other))
        assertTrue(observe(emptyList()) is AccessibilitySemanticDiffTracker.Result.Ambiguous)
        tracker.clear(); now = 0; observe(listOf(candidate, other)); now = 200; observe(listOf(candidate, other))
        assertTrue(tracker.observe(identity, emptyList(), 1, true, true, false, listOf(selector)) is AccessibilitySemanticDiffTracker.Result.Candidate)
    }

    @Test fun ac06_directedCompleteScopeAbsenceNeedsStablePositiveHistory() {
        val present = SelectorEvidence(SelectorPresence.UNIQUE_PRESENT, nodes = sample.nodes)
        observe(listOf(candidate), evidence = mapOf(selector to present))
        now = 200; observe(listOf(candidate), evidence = mapOf(selector to present))
        val absent = SelectorEvidence(SelectorPresence.VERIFIED_ABSENT, SelectorEvidenceScope.ANCHORED_SUBTREE)
        assertTrue(observe(emptyList(), complete = false, evidence = mapOf(selector to absent)) is AccessibilitySemanticDiffTracker.Result.Candidate)
    }

    @Test fun ac06_unknownAmbiguousAndUnscopedAbsenceCannotProveDirectedDisappearance() {
        listOf(SelectorPresence.UNKNOWN, SelectorPresence.AMBIGUOUS_PRESENT, SelectorPresence.VERIFIED_ABSENT).forEach { presence ->
            tracker.clear(); now = 0
            val present = SelectorEvidence(SelectorPresence.UNIQUE_PRESENT, nodes = sample.nodes)
            observe(listOf(candidate), evidence = mapOf(selector to present)); now = 200
            observe(listOf(candidate), evidence = mapOf(selector to present))
            assertFalse(observe(emptyList(), complete = false, evidence = mapOf(selector to SelectorEvidence(presence))) is AccessibilitySemanticDiffTracker.Result.Candidate)
        }
    }

    @Test fun ac06_rotationEnumeratesOnlyRecheckableSafeCandidateSelectors() {
        val candidates = (0..9).map { index -> candidate.copy(nodes = listOf(sample.nodes.single().copy(resourceId = "app:id/skip$index")), selectors = listOf(selector.copy(resourceId = "app:id/skip$index"))) }
        observe(candidates)
        val first = tracker.selectorsForRecheck(identity)
        val next = tracker.selectorsForRecheck(identity)
        assertEquals(8, first.size)
        assertEquals(10, (first + next).toSet().size)
        assertTrue(tracker.selectorsForRecheck(identity.copy(windowId = 8)).isEmpty())
    }

    @Test fun ac06_unknownCompetitorBlocksOneVerifiedDirectedDisappearance() {
        val otherSelector = selector.copy(resourceId = "app:id/close")
        val other = candidate.copy(nodes = listOf(sample.nodes.single().copy(resourceId = otherSelector.resourceId)), selectors = listOf(otherSelector))
        val positives = mapOf(selector to SelectorEvidence(SelectorPresence.UNIQUE_PRESENT, nodes = sample.nodes),
            otherSelector to SelectorEvidence(SelectorPresence.UNIQUE_PRESENT, nodes = other.nodes))
        observe(listOf(candidate, other), evidence = positives)
        now = 200; observe(listOf(candidate, other), evidence = positives)
        val result = observe(emptyList(), complete = false, evidence = mapOf(
            selector to SelectorEvidence(SelectorPresence.VERIFIED_ABSENT, SelectorEvidenceScope.FULL_WINDOW),
            otherSelector to SelectorEvidence(SelectorPresence.UNKNOWN),
        ))
        assertFalse(result is AccessibilitySemanticDiffTracker.Result.Candidate)
        assertEquals(1, result.diagnostics.directedAbsentCount)
        assertTrue(result.diagnostics.unresolvedDismissCount > 0)
    }

    @Test fun ac06_overflowedDismissHistoryBlocksSelectionUntilCoverageIsRestored() {
        val candidates = (0..64).map { index ->
            val key = selector.copy(resourceId = "app:id/skip$index")
            candidate.copy(nodes = listOf(sample.nodes.single().copy(resourceId = key.resourceId)), selectors = listOf(key))
        }
        val first = candidates.first()
        val present = mapOf(first.selectors.single() to SelectorEvidence(SelectorPresence.UNIQUE_PRESENT, nodes = first.nodes))
        observe(candidates, complete = false, evidence = present)
        now = 200; observe(candidates, complete = false, evidence = present)
        val result = observe(emptyList(), complete = false, evidence = mapOf(first.selectors.single() to
            SelectorEvidence(SelectorPresence.VERIFIED_ABSENT, SelectorEvidenceScope.ANCHORED_SUBTREE)))
        assertFalse(result is AccessibilitySemanticDiffTracker.Result.Candidate)
        assertTrue(result.diagnostics.unresolvedDismissCount > 0)
        assertEquals(1, result.diagnostics.directedAbsentCount)
    }

    private fun stable() { observe(listOf(candidate)); now = 200; observe(listOf(candidate)) }
    private fun observe(candidates: List<AccessibilityCaptureProbe.SemanticCandidate>, complete: Boolean = true,
        transition: Boolean = false, evidence: Map<CapturedSelector, SelectorEvidence> = emptyMap()) =
        tracker.observe(identity, candidates, 1, complete, true, transition, selectorEvidence = evidence)
}
