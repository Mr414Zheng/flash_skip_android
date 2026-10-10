package com.zhenghuaizhi.flashskip.domain.automation

import com.zhenghuaizhi.flashskip.domain.model.ActionMethod
import com.zhenghuaizhi.flashskip.domain.model.ActionReason
import com.zhenghuaizhi.flashskip.domain.model.AdRule
import com.zhenghuaizhi.flashskip.domain.model.NodeSelector
import com.zhenghuaizhi.flashskip.domain.model.TargetAppId
import com.zhenghuaizhi.flashskip.domain.ports.AbsenceProof
import com.zhenghuaizhi.flashskip.domain.ports.ActionTargetEvidence
import com.zhenghuaizhi.flashskip.domain.ports.PageIdentity
import com.zhenghuaizhi.flashskip.domain.ports.TargetObservation
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PostActionObserverTest {
    @Test
    fun missingEvidenceAndBoundaryAreNotClosureProofForEitherActionMethod() {
        for (method in ActionMethod.entries) {
            for (reason in listOf(ActionReason.PAGE_CHANGED, ActionReason.TRAVERSAL_LIMIT, ActionReason.NODE_UNAVAILABLE)) {
                val pending = initial(method)
                val result = PostActionObserver.evaluate(pending, TargetObservation.Insufficient(reason), 300, 1, 200)
                assertTrue(result is ObservationDecision.Wait)
                assertFalse(ActionRetryPolicy.permits(result.pending, 1_000, 1))
            }
        }
    }

    @Test
    fun localAbsenceNeedsStableContextAndAnEventAfterAcceptedAction() {
        val absent = TargetObservation.Absent(AbsenceProof.LOCAL_CONTAINER_REMOVED, 10)
        val first = PostActionObserver.evaluate(initial(), absent, 300, 1, 200)
        assertTrue(first is ObservationDecision.Wait)
        assertTrue(PostActionObserver.evaluate(first.pending, absent, 500, 1, 200) is ObservationDecision.Confirmed)
        assertTrue(PostActionObserver.evaluate(first.pending, absent, 500, 1, 100) is ObservationDecision.Wait)
        assertTrue(PostActionObserver.evaluate(first.pending, absent.copy(contextSignature = 11), 500, 1, 200) is ObservationDecision.Wait)
    }

    @Test
    fun unreadableIntervalBreaksConsecutiveAbsenceEvidence() {
        val absent = TargetObservation.Absent(AbsenceProof.LOCAL_CONTAINER_REMOVED, 10)
        val first = PostActionObserver.evaluate(initial(), absent, 300, 1, 200)
        val gap = PostActionObserver.evaluate(first.pending, TargetObservation.Insufficient(ActionReason.NODE_UNAVAILABLE), 500, 1, 200)
        assertTrue(PostActionObserver.evaluate(gap.pending, absent, 700, 1, 200) is ObservationDecision.Wait)
    }

    @Test
    fun windowRemovalNeedsAWindowEventAndCannotUseContentEventAlone() {
        val absent = TargetObservation.Absent(AbsenceProof.ORIGINAL_WINDOW_REMOVED, 10)
        val first = PostActionObserver.evaluate(initial(), absent, 300, 1, 200)
        assertTrue(PostActionObserver.evaluate(first.pending, absent, 500, 1, 200) is ObservationDecision.Wait)
        assertTrue(PostActionObserver.evaluate(first.pending, absent, 500, 1, 200, 200) is ObservationDecision.Confirmed)
    }

    @Test
    fun expiredObservationCannotBeUpgradedToConfirmed() {
        val absent = TargetObservation.Absent(AbsenceProof.LOCAL_CONTAINER_REMOVED, 10)
        val first = PostActionObserver.evaluate(initial(), absent, 300, 1, 200)
        assertTrue(PostActionObserver.evaluate(first.pending, absent, 2_500, 1, 200) is ObservationDecision.Unconfirmed)
    }

    @Test
    fun retryNeedsOriginalUniqueTargetStableBoundaryAndResponseTime() {
        val first = PostActionObserver.evaluate(initial(), TargetObservation.Present(true), 300, 1, 200)
        assertFalse(ActionRetryPolicy.permits(first.pending, 500, 1))
        assertTrue(ActionRetryPolicy.permits(first.pending, 900, 1))
        assertFalse(ActionRetryPolicy.permits(first.pending, 900, 2))
        assertFalse(ActionRetryPolicy.permits(first.pending.copy(attempts = 2), 900, 1))
        assertFalse(ActionRetryPolicy.permits(first.pending.copy(method = ActionMethod.CENTER_GESTURE), 900, 1))
        assertFalse(ActionRetryPolicy.permits(first.pending.copy(lastObservation = TargetObservation.Present(false)), 900, 1))
        assertFalse(ActionRetryPolicy.permits(first.pending.copy(evidence = null), 900, 1))
    }

    private fun initial(method: ActionMethod = ActionMethod.NODE_CLICK): PendingActionObservation {
        val identity = PageIdentity("test.app", "test.app.Main", 1)
        val selector = NodeSelector(resourceId = "test.app:id/skip")
        return PendingActionObservation(
            id = 1,
            rule = AdRule("rule", TargetAppId("test.app"), packageName = "test.app",
                allowedActivities = setOf("test.app.Main"), selectors = listOf(selector), startupDelayMs = 0, cooldownMs = 0),
            selector = selector,
            method = method,
            evidence = ActionTargetEvidence(identity, selector, 1),
            acceptedAt = 100,
            deadlineAt = 2_500,
        )
    }
}
