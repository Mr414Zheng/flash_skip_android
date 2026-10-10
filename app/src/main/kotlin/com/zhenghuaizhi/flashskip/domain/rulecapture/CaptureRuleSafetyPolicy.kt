package com.zhenghuaizhi.flashskip.domain.rulecapture

import com.zhenghuaizhi.flashskip.domain.model.CaptureFailure
import com.zhenghuaizhi.flashskip.domain.model.CapturePhase
import com.zhenghuaizhi.flashskip.domain.model.CapturedNode
import com.zhenghuaizhi.flashskip.domain.model.RuleCaptureState

object CaptureRuleSafetyPolicy {
    /** 纯数字也可能是条目数量；稳定 ID 或用户确认都不能替代关闭语义。 */
    fun accepts(nodes: List<CapturedNode>): Boolean {
        val source = nodes.firstOrNull() ?: return true
        if (!DismissSemantics.isBareNumericCountdown(source.text) &&
            !DismissSemantics.isBareNumericCountdown(source.contentDescription)
        ) {
            return true
        }
        val actionIndex = nodes.indexOfFirst {
            it.visibleToUser && it.hasUsableBounds && (it.clickable || it.supportsActionClick)
        }
        if (actionIndex < 0) return false
        // 只接受数字节点到实际点击控件之间的提示，不能借用外层页面的关闭按钮。
        return nodes.take(actionIndex + 1).any {
            it.visibleToUser && it.hasUsableBounds &&
                (DismissSemantics.isKnown(it.text) ||
                    DismissSemantics.isKnown(it.contentDescription) ||
                    DismissSemantics.isKnownResourceId(it.resourceId))
        }
    }

    /** 读写都使用同一策略，启动迁移尚未落盘时也不能重新启用旧候选。 */
    fun sanitize(state: RuleCaptureState): RuleCaptureState {
        val rejected = state.samples.filterNot { accepts(it.nodes) }
        val session = state.activeSession
        val rejectedPending = session?.pendingSample?.let { !accepts(it.nodes) } == true ||
            session?.pendingObservedCandidate?.let { !accepts(it.nodes) } == true
        if (rejected.isEmpty() && !rejectedPending) return state
        val rejectedScopes = rejected.map { it.targetAppId to it.activityName }.toSet() +
            if (rejectedPending) setOf(session.targetAppId to session.activityName) else emptySet()
        return state.copy(
            samples = state.samples.filter { accepts(it.nodes) },
            semanticDiffEvidence = state.semanticDiffEvidence.filterNot {
                (it.targetAppId to it.activityName) in rejectedScopes
            },
            activeSession = if (rejectedPending) session.copy(
                phase = CapturePhase.FAILED,
                failure = CaptureFailure.DISMISS_NOT_CAPTURED,
                pendingSample = null,
                pendingObservedCandidate = null,
                candidateClickListeningEnabled = false,
                leftFlashSkipAtEpochMs = null,
            ) else session,
        )
    }
}
