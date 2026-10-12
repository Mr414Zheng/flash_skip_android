package com.zhenghuaizhi.flashskip.platform.accessibility

import com.zhenghuaizhi.flashskip.domain.model.CaptureCandidateRejection
import com.zhenghuaizhi.flashskip.domain.model.CapturedNode
import com.zhenghuaizhi.flashskip.domain.model.CapturedSelector
import com.zhenghuaizhi.flashskip.domain.rulecapture.CaptureRuleSafetyPolicy

/** 仅用扫描中已取得的快照和选择器归因，不查询无障碍节点。 */
internal fun captureCandidateRejection(
    snapshot: CapturedNode,
    chain: List<CapturedNode>?,
    selectors: List<CapturedSelector>?,
    stableSelectors: List<CapturedSelector>?,
): CaptureCandidateRejection? {
    if (listOf(snapshot.resourceId, snapshot.text, snapshot.contentDescription).all { it == null } &&
        !snapshot.clickable && !snapshot.supportsActionClick) return null
    if (!snapshot.visibleToUser) return CaptureCandidateRejection.NOT_VISIBLE
    if (!snapshot.hasUsableBounds) return CaptureCandidateRejection.UNUSABLE_BOUNDS
    // 未进入父链检查不能断言没有动作目标，诊断本身也不补做节点查询。
    if (chain == null) return CaptureCandidateRejection.CHAIN_NOT_INSPECTED
    if (chain.none { it.visibleToUser && it.hasUsableBounds && (it.clickable || it.supportsActionClick) }) {
        return CaptureCandidateRejection.NO_ACTION_TARGET
    }
    if (!CaptureRuleSafetyPolicy.accepts(chain)) return CaptureCandidateRejection.NUMERIC_WITHOUT_DISMISS_HINT
    if (selectors.isNullOrEmpty()) return CaptureCandidateRejection.NO_SELECTOR
    if (stableSelectors.isNullOrEmpty()) return CaptureCandidateRejection.NO_STABLE_SELECTOR
    if (!chain.hasSafeDismissAction()) return CaptureCandidateRejection.NO_DISMISS_HINT
    return null
}
