package com.zhenghuaizhi.flashskip.domain.automation

import com.zhenghuaizhi.flashskip.domain.model.ActionMethod
import com.zhenghuaizhi.flashskip.domain.ports.ActionTargetEvidence
import com.zhenghuaizhi.flashskip.domain.ports.PageIdentity
import com.zhenghuaizhi.flashskip.domain.ports.TargetObservation
import com.zhenghuaizhi.flashskip.testing.TARGET
import com.zhenghuaizhi.flashskip.testing.executableRule
import org.junit.Assert.*
import org.junit.Test

class ActionRetryPolicyTest {
    private val rule = executableRule()
    private val pending = PendingActionObservation(
        1, rule, rule.selectors.single(), ActionMethod.NODE_CLICK,
        ActionTargetEvidence(PageIdentity(TARGET.packageName, "Activity", 1), rule.selectors.single(), 42),
        100, 2500, stableSince = 650, stableRevision = 3, lastObservation = TargetObservation.Present(true),
    )

    @Test fun ac06_retryWaitsForResponseAndStableTargetWithExactThresholds() {
        assertFalse(ActionRetryPolicy.permits(pending, 849, 3))
        assertTrue(ActionRetryPolicy.permits(pending, 850, 3))
        assertTrue(ActionRetryPolicy.permits(pending, 851, 3))
        val stable = pending.copy(stableSince = 700)
        assertFalse(ActionRetryPolicy.permits(stable, 899, 3))
        assertTrue(ActionRetryPolicy.permits(stable, 900, 3))
        assertTrue(ActionRetryPolicy.permits(stable, 901, 3))
    }

    @Test fun ac06_retryRequiresNodeMethodEvidenceUniquenessRevisionAndRemainingAttempt() {
        listOf(
            pending.copy(method = ActionMethod.CENTER_GESTURE), pending.copy(evidence = null),
            pending.copy(attempts = 2), pending.copy(lastObservation = null),
            pending.copy(lastObservation = TargetObservation.Present(false)), pending.copy(stableRevision = 2),
        ).forEach { assertFalse(it.toString(), ActionRetryPolicy.permits(it, 1000, 3)) }
    }

    @Test fun ac06_observationDeadlineIsExclusiveAndLastAcceptedActionControlsWait() {
        assertTrue(ActionRetryPolicy.permits(pending, 2499, 3))
        assertFalse(ActionRetryPolicy.permits(pending, 2500, 3))
        assertFalse(ActionRetryPolicy.permits(pending, 2501, 3))
        assertFalse(ActionRetryPolicy.permits(pending.copy(lastActionAt = 900), 1649, 3))
        assertTrue(ActionRetryPolicy.permits(pending.copy(lastActionAt = 900), 1650, 3))
    }
}
