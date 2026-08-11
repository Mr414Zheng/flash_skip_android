package com.zhenghuaizhi.flashskip.domain.automation

import com.zhenghuaizhi.flashskip.domain.model.NodeSelector
import com.zhenghuaizhi.flashskip.domain.model.NodeSnapshot
import com.zhenghuaizhi.flashskip.domain.model.TextCondition

object SelectorMatcher {
    fun matches(
        selector: NodeSelector,
        node: NodeSnapshot,
        ancestors: List<NodeSnapshot> = emptyList(),
    ): Boolean {
        if (!matchesTarget(selector, node)) return false

        val ancestorResourceId = selector.ancestorResourceId
        val ancestorDistance = selector.ancestorDistance
        if (ancestorResourceId == null && ancestorDistance == null) return true
        if (ancestorResourceId == null || ancestorDistance == null) return false
        val ancestor = ancestors.getOrNull(ancestorDistance - 1) ?: return false
        return ancestor.visibleToUser &&
            ancestor.hasUsableBounds &&
            ancestor.resourceId == ancestorResourceId
    }

    fun matchesTarget(selector: NodeSelector, node: NodeSnapshot): Boolean {
        if (!node.visibleToUser || !node.hasUsableBounds) return false
        if (selector.resourceId != null && selector.resourceId != node.resourceId) return false
        if (selector.contentDescription != null &&
            selector.contentDescription != node.contentDescription
        ) {
            return false
        }
        val textMatches = when (val condition = selector.text) {
            is TextCondition.Exact -> node.text == condition.value
            is TextCondition.Prefix -> node.text?.startsWith(condition.value) == true
            null -> true
        }
        return textMatches
    }
}
