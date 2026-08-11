package com.zhenghuaizhi.flashskip.domain.automation

import com.zhenghuaizhi.flashskip.domain.model.NodeSelector
import com.zhenghuaizhi.flashskip.domain.model.NodeSnapshot
import com.zhenghuaizhi.flashskip.domain.model.TextCondition
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectorMatcherTest {
    private val node = NodeSnapshot(
        resourceId = "com.example:id/close",
        text = "跳过广告",
        contentDescription = "关闭",
        visibleToUser = true,
        hasUsableBounds = true,
    )

    @Test
    fun allConfiguredConditionsMustMatch() {
        assertTrue(
            SelectorMatcher.matches(
                NodeSelector(
                    resourceId = "com.example:id/close",
                    text = TextCondition.Prefix("跳过"),
                    contentDescription = "关闭",
                ),
                node,
            ),
        )
        assertFalse(
            SelectorMatcher.matches(
                NodeSelector(
                    resourceId = "com.example:id/other",
                    text = TextCondition.Prefix("跳过"),
                    contentDescription = "关闭",
                ),
                node,
            ),
        )
    }

    @Test
    fun exactAndPrefixTextRemainDistinct() {
        assertFalse(
            SelectorMatcher.matches(NodeSelector(text = TextCondition.Exact("跳过")), node),
        )
        assertTrue(
            SelectorMatcher.matches(NodeSelector(text = TextCondition.Prefix("跳过")), node),
        )
    }

    @Test
    fun invisibleOrEmptyBoundsNeverMatch() {
        assertFalse(
            SelectorMatcher.matches(
                NodeSelector(contentDescription = "关闭"),
                node.copy(visibleToUser = false),
            ),
        )
        assertFalse(
            SelectorMatcher.matches(
                NodeSelector(contentDescription = "关闭"),
                node.copy(hasUsableBounds = false),
            ),
        )
    }

    @Test
    fun ancestorConstraintScopesGenericTargetTextWithoutChangingActionTarget() {
        val selector = NodeSelector(
            text = TextCondition.Exact("跳过广告"),
            ancestorResourceId = "com.example:id/ad_container",
            ancestorDistance = 1,
        )
        val matchingAncestor = node.copy(
            resourceId = "com.example:id/ad_container",
            text = null,
            contentDescription = null,
        )

        assertTrue(SelectorMatcher.matches(selector, node, listOf(matchingAncestor)))
        assertFalse(
            SelectorMatcher.matches(
                selector,
                node,
                listOf(matchingAncestor.copy(resourceId = "android:id/content")),
            ),
        )
        assertFalse(SelectorMatcher.matches(selector, node))
    }
}
