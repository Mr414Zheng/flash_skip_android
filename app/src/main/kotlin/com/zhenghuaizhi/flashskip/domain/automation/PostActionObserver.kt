package com.zhenghuaizhi.flashskip.domain.automation

import com.zhenghuaizhi.flashskip.domain.model.ActionMethod
import com.zhenghuaizhi.flashskip.domain.model.ActionReason
import com.zhenghuaizhi.flashskip.domain.model.AdRule
import com.zhenghuaizhi.flashskip.domain.model.NodeSelector
import com.zhenghuaizhi.flashskip.domain.ports.ActionTargetEvidence
import com.zhenghuaizhi.flashskip.domain.ports.AbsenceProof
import com.zhenghuaizhi.flashskip.domain.ports.TargetObservation

/** 只解释动作后的证据，不执行点击，也不把动作接受或页面边界当作关闭。 */
internal object PostActionObserver {
    const val WINDOW_MS = 2_400L
    const val FIRST_DELAY_MS = 250L
    const val EVENT_DEBOUNCE_MS = 120L
    const val TIMER_DELAY_MS = 400L
    const val STABLE_EVIDENCE_MS = 200L
    const val FALLBACK_AFTER_MS = 1_200L

    fun evaluate(
        pending: PendingActionObservation,
        observation: TargetObservation,
        now: Long,
        eventRevision: Long,
        lastEventAt: Long?,
        lastWindowEventAt: Long? = null,
    ): ObservationDecision {
        val sameEvidence = pending.lastObservation == observation &&
            (observation !is TargetObservation.Present || pending.stableRevision == eventRevision)
        val current = pending.copy(
            observations = pending.observations + 1,
            lastObservation = observation,
            stableSince = if (sameEvidence) pending.stableSince else now,
            stableRevision = if (sameEvidence) pending.stableRevision else eventRevision,
        )
        val supportingEvent = if ((observation as? TargetObservation.Absent)?.proof ==
            AbsenceProof.ORIGINAL_WINDOW_REMOVED
        ) lastWindowEventAt else lastEventAt
        val hasLaterEvent = supportingEvent != null && supportingEvent > pending.lastActionAt
        if (now >= current.deadlineAt) return ObservationDecision.Unconfirmed(current)
        if (observation is TargetObservation.Absent && hasLaterEvent && sameEvidence &&
            now - current.stableSince >= STABLE_EVIDENCE_MS
        ) return ObservationDecision.Confirmed(current)
        return ObservationDecision.Wait(current)
    }
}

/** 补点要求独立的正面证据；一次不可读既不触发点击，也不会永久阻断后续有效证据。 */
internal object ActionRetryPolicy {
    private const val RESPONSE_MS = 750L
    fun permits(pending: PendingActionObservation, now: Long, eventRevision: Long): Boolean =
        pending.method == ActionMethod.NODE_CLICK && pending.attempts < 2 &&
            pending.evidence != null && (pending.lastObservation as? TargetObservation.Present)?.unique == true &&
            now - pending.lastActionAt >= RESPONSE_MS &&
            now - pending.stableSince >= PostActionObserver.STABLE_EVIDENCE_MS &&
            pending.stableRevision == eventRevision && now < pending.deadlineAt
}

internal data class PendingActionObservation(
    val id: Long,
    val rule: AdRule,
    val selector: NodeSelector,
    val method: ActionMethod,
    val evidence: ActionTargetEvidence?,
    val acceptedAt: Long,
    val deadlineAt: Long,
    val lastActionAt: Long = acceptedAt,
    val attempts: Int = 1,
    val observations: Int = 0,
    val lastObservation: TargetObservation? = null,
    val stableSince: Long = acceptedAt,
    val stableRevision: Long = 0,
    val fallbackAttempts: Int = 0,
) {
    val reason: ActionReason get() =
        (lastObservation as? TargetObservation.Insufficient)?.reason ?: ActionReason.CLICK_UNCONFIRMED
}

internal sealed interface ObservationDecision {
    val pending: PendingActionObservation
    data class Confirmed(override val pending: PendingActionObservation) : ObservationDecision
    data class Wait(override val pending: PendingActionObservation) : ObservationDecision
    data class Unconfirmed(override val pending: PendingActionObservation) : ObservationDecision
}
