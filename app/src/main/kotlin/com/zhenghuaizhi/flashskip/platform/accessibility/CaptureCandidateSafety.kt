package com.zhenghuaizhi.flashskip.platform.accessibility

import com.zhenghuaizhi.flashskip.domain.model.CapturedNode
import com.zhenghuaizhi.flashskip.domain.rulecapture.CaptureRuleSafetyPolicy
import com.zhenghuaizhi.flashskip.domain.rulecapture.DismissSemantics

/** 定向复查只能借用实际动作链中的关闭提示，不能借用外层页面的无关按钮。 */
internal fun List<CapturedNode>.hasSafeDismissAction(): Boolean {
    if (!CaptureRuleSafetyPolicy.accepts(this)) return false
    val actionIndex = indexOfFirst {
        it.visibleToUser && it.hasUsableBounds && (it.clickable || it.supportsActionClick)
    }
    if (actionIndex < 0) return false
    return take(actionIndex + 1).any {
        it.visibleToUser && it.hasUsableBounds &&
            (DismissSemantics.isKnown(it.text) || DismissSemantics.isKnown(it.contentDescription) ||
                DismissSemantics.isKnownResourceId(it.resourceId))
    }
}
