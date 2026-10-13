package com.zhenghuaizhi.flashskip.domain.automation

import com.zhenghuaizhi.flashskip.domain.model.NodeSelector
import com.zhenghuaizhi.flashskip.domain.model.NodeSnapshot
import com.zhenghuaizhi.flashskip.domain.model.TextCondition
import org.junit.Assert.*
import org.junit.Test

class SelectorMatcherTest {
    private val node = NodeSnapshot("app:id/skip", "跳过", "关闭", true, true)

    @Test fun ac03_allSpecifiedConditionsVisibilityAndBoundsMustMatch() {
        val selector = NodeSelector(node.resourceId, TextCondition.Exact(node.text!!), node.contentDescription)
        assertTrue(SelectorMatcher.matches(selector, node))
        listOf(
            node.copy(resourceId = "other"), node.copy(text = "other"),
            node.copy(contentDescription = "other"), node.copy(visibleToUser = false),
            node.copy(hasUsableBounds = false),
        ).forEach { assertFalse(SelectorMatcher.matches(selector, it)) }
    }

    @Test fun ac03_countdownsDoNotMatchOrdinaryTextOrOversizedNumbers() {
        val skip = TextCondition.SkipCountdown("跳过")
        listOf("跳过", "跳过 5", "跳过５秒", "跳过 12s").forEach { assertTrue(it, skip.matches(it)) }
        listOf("跳过设置", "跳过 123", "请跳过", "跳过 -1", "跳过 1.5").forEach { assertFalse(it, skip.matches(it)) }
        listOf("5", "０５秒", "12S").forEach { assertTrue(it, TextCondition.NumericCountdown.matches(it)) }
        listOf("123", "-1", "1.5", "5 条", "第5秒").forEach { assertFalse(it, TextCondition.NumericCountdown.matches(it)) }
    }

    @Test fun ac03_ancestorMustMatchExactDistanceAndRemainVisibleWithBounds() {
        val ancestor = node.copy(resourceId = "app:id/container")
        val selector = NodeSelector(resourceId = node.resourceId, ancestorResourceId = ancestor.resourceId, ancestorDistance = 2)
        assertTrue(SelectorMatcher.matches(selector, node, listOf(node, ancestor)))
        assertFalse(SelectorMatcher.matches(selector, node, listOf(ancestor)))
        assertFalse(SelectorMatcher.matches(selector, node, listOf(ancestor, node)))
        assertFalse(SelectorMatcher.matches(selector, node, listOf(node, ancestor.copy(visibleToUser = false))))
        assertFalse(SelectorMatcher.matches(selector, node, listOf(node, ancestor.copy(hasUsableBounds = false))))
    }
}
