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
    fun matches(node: AccessibilityNodeInfo, selector: NodeSelector): Boolean =
        check(node, selector) == CandidateMatchResult.MATCH

    @Suppress("DEPRECATION")
    fun check(
        node: AccessibilityNodeInfo,
        selector: NodeSelector,
        refreshAncestors: Boolean = false,
    ): CandidateMatchResult {
        val targetSnapshot = node.toSnapshot()
        if (!SelectorMatcher.matchesTarget(selector, targetSnapshot)) return CandidateMatchResult.NO_MATCH
        if (!selector.hasAncestorConstraint) return CandidateMatchResult.MATCH
        val distance = selector.ancestorDistance ?: return CandidateMatchResult.NO_MATCH
        if (selector.ancestorResourceId == null ||
            distance !in 1..NodeSelector.MAX_ANCESTOR_DISTANCE
        ) {
            return CandidateMatchResult.NO_MATCH
        }

        val targetPackage = node.packageName?.toString() ?: return CandidateMatchResult.NO_MATCH
        val ancestors = mutableListOf<NodeSnapshot>()
        var ownedAncestor: AccessibilityNodeInfo? = null
        try {
            repeat(distance) {
                val parent = runCatching { (ownedAncestor ?: node).parent }.getOrNull()
                    ?: return CandidateMatchResult.UNAVAILABLE
                ownedAncestor?.recycle()
                ownedAncestor = parent
                if (refreshAncestors && !runCatching { parent.refresh() }.getOrDefault(false)) {
                    return CandidateMatchResult.UNAVAILABLE
                }
                if (parent.packageName?.toString() != targetPackage) return CandidateMatchResult.NO_MATCH
                ancestors += parent.toSnapshot()
            }
            return if (SelectorMatcher.matches(selector, targetSnapshot, ancestors)) {
                CandidateMatchResult.MATCH
            } else {
                CandidateMatchResult.NO_MATCH
            }
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

internal enum class CandidateMatchResult { MATCH, NO_MATCH, UNAVAILABLE }
