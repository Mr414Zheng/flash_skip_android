package com.zhenghuaizhi.flashskip.platform.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.zhenghuaizhi.flashskip.domain.automation.SelectorMatcher
import com.zhenghuaizhi.flashskip.domain.model.NodeSelector
import com.zhenghuaizhi.flashskip.domain.model.NodeSnapshot

internal class AccessibilityNodeSelectorMatcher(
    private val service: AccessibilityService,
) {
    @Suppress("DEPRECATION")
    fun matches(node: AccessibilityNodeInfo, selector: NodeSelector): Boolean {
        val targetSnapshot = node.toSnapshot()
        if (!SelectorMatcher.matchesTarget(selector, targetSnapshot)) return false
        if (!selector.hasAncestorConstraint) return true
        val distance = selector.ancestorDistance ?: return false
        if (selector.ancestorResourceId == null ||
            distance !in 1..NodeSelector.MAX_ANCESTOR_DISTANCE
        ) {
            return false
        }

        val targetPackage = node.packageName?.toString() ?: return false
        val ancestors = mutableListOf<NodeSnapshot>()
        var ownedAncestor: AccessibilityNodeInfo? = null
        try {
            repeat(distance) {
                val parent = runCatching { (ownedAncestor ?: node).parent }.getOrNull()
                    ?: return false
                ownedAncestor?.recycle()
                ownedAncestor = parent
                if (parent.packageName?.toString() != targetPackage) return false
                ancestors += parent.toSnapshot()
            }
            return SelectorMatcher.matches(selector, targetSnapshot, ancestors)
        } finally {
            ownedAncestor?.recycle()
        }
    }

    private fun AccessibilityNodeInfo.toSnapshot(): NodeSnapshot {
        val bounds = Rect().also(::getBoundsInScreen)
        val displayMetrics = service.resources.displayMetrics
        return NodeSnapshot(
            resourceId = viewIdResourceName,
            text = text?.toString(),
            contentDescription = contentDescription?.toString(),
            visibleToUser = isVisibleToUser,
            hasUsableBounds = bounds.width() > 0 && bounds.height() > 0 &&
                bounds.centerX() in 0 until displayMetrics.widthPixels &&
                bounds.centerY() in 0 until displayMetrics.heightPixels,
        )
    }
}
