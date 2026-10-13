package com.zhenghuaizhi.flashskip.domain.automation

import com.zhenghuaizhi.flashskip.domain.model.ActionMethod
import com.zhenghuaizhi.flashskip.domain.model.ActionReason
import com.zhenghuaizhi.flashskip.domain.ports.AbsenceProof
import com.zhenghuaizhi.flashskip.domain.ports.TargetObservation
import com.zhenghuaizhi.flashskip.testing.executableRule
import org.junit.Assert.*
import org.junit.Test

class PostActionObserverTest {
    private val rule = executableRule()
    private val absent = TargetObservation.Absent(AbsenceProof.LOCAL_TARGET_REMOVED, 42)
    private val pending = PendingActionObservation(1, rule, rule.selectors.single(), ActionMethod.NODE_CLICK, null, 100, 2500)

    @Test fun ac05_absenceNeedsTwoStableObservationsAndLaterEvent() {
        val first = PostActionObserver.evaluate(pending, absent, 350, 1, 200)
        assertTrue(first is ObservationDecision.Wait)
        assertTrue(PostActionObserver.evaluate(first.pending, absent, 549, 1, 200) is ObservationDecision.Wait)
        assertTrue(PostActionObserver.evaluate(first.pending, absent, 550, 1, 200) is ObservationDecision.Confirmed)
        assertTrue(PostActionObserver.evaluate(first.pending, absent, 551, 1, 200) is ObservationDecision.Confirmed)
        listOf(null, 99L, 100L).forEach {
            assertTrue(PostActionObserver.evaluate(first.pending, absent, 550, 1, it) is ObservationDecision.Wait)
        }
    }

    @Test fun ac05_evidenceChangeResetsStabilityAndDeadlineWinsAtEquality() {
        val first = PostActionObserver.evaluate(pending, absent, 350, 1, 200).pending
        val changed = PostActionObserver.evaluate(first, absent.copy(contextSignature = 43), 550, 1, 200)
        assertTrue(changed is ObservationDecision.Wait)
        assertEquals(550L, changed.pending.stableSince)
        assertTrue(PostActionObserver.evaluate(first, absent, 2499, 1, 200) is ObservationDecision.Confirmed)
        assertTrue(PostActionObserver.evaluate(first, absent, 2500, 1, 200) is ObservationDecision.Unconfirmed)
        assertTrue(PostActionObserver.evaluate(first, absent, 2501, 1, 200) is ObservationDecision.Unconfirmed)
    }

    @Test fun ac05_originalWindowRemovalRequiresWindowEventInsteadOfContentEvent() {
        val removed = absent.copy(proof = AbsenceProof.ORIGINAL_WINDOW_REMOVED)
        val first = PostActionObserver.evaluate(pending, removed, 350, 1, 200).pending
        assertTrue(PostActionObserver.evaluate(first, removed, 550, 1, 200, null) is ObservationDecision.Wait)
        assertTrue(PostActionObserver.evaluate(first, removed, 550, 1, 200, 100) is ObservationDecision.Wait)
        assertTrue(PostActionObserver.evaluate(first, removed, 550, 1, 200, 101) is ObservationDecision.Confirmed)

        val retried = pending.copy(lastActionAt = 900, attempts = 2)
        val afterRetry = PostActionObserver.evaluate(retried, removed, 1100, 2, 901, 899).pending
        listOf(null, 899L, 900L).forEach { windowEventAt ->
            assertTrue(PostActionObserver.evaluate(afterRetry, removed, 1300, 2, 901, windowEventAt) is ObservationDecision.Wait)
        }
        assertTrue(PostActionObserver.evaluate(afterRetry, removed, 1300, 2, 901, 901) is ObservationDecision.Confirmed)
    }

    @Test fun ac05_presentAndInsufficientNeverConfirmClosed() {
        listOf(TargetObservation.Present(true), TargetObservation.Present(false), TargetObservation.Insufficient(ActionReason.NODE_UNAVAILABLE)).forEach { observation ->
            val first = PostActionObserver.evaluate(pending, observation, 350, 1, 200).pending
            assertTrue(PostActionObserver.evaluate(first, observation, 550, 1, 200) is ObservationDecision.Wait)
        }
    }

    @Test fun ac06_eventBeforeRetryAcceptanceCannotConfirmEvenAfterStableAbsence() {
        val retried = pending.copy(lastActionAt = 900, attempts = 2)
        val first = PostActionObserver.evaluate(retried, absent, 1100, 1, 899).pending
        assertTrue("补点前事件不能支持第二次动作关闭", PostActionObserver.evaluate(first, absent, 1300, 1, 899) is ObservationDecision.Wait)
    }

    @Test fun ac06_eventAtRetryAcceptanceCannotConfirm() {
        val retried = pending.copy(lastActionAt = 900, attempts = 2)
        val first = PostActionObserver.evaluate(retried, absent, 1100, 1, 900).pending
        assertTrue(PostActionObserver.evaluate(first, absent, 1300, 1, 900) is ObservationDecision.Wait)
    }

    @Test fun ac06_eventAfterRetryAcceptanceConfirmsWithoutExtendingOriginalDeadline() {
        val retried = pending.copy(lastActionAt = 900, attempts = 2)
        val first = PostActionObserver.evaluate(retried, absent, 1100, 1, 901).pending
        assertTrue(PostActionObserver.evaluate(first, absent, 1300, 1, 901) is ObservationDecision.Confirmed)
        assertEquals(2500L, first.deadlineAt)
        assertTrue(PostActionObserver.evaluate(first, absent, 2500, 1, 901) is ObservationDecision.Unconfirmed)
    }
}
