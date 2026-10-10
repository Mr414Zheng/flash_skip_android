package com.zhenghuaizhi.flashskip.domain.automation

import com.zhenghuaizhi.flashskip.domain.model.NodeSelector
import com.zhenghuaizhi.flashskip.domain.model.NodeSnapshot
import com.zhenghuaizhi.flashskip.domain.model.TextCondition
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkipCountdownSelectorTest {
    @Test
    fun skipOnlyMatchesItsLiteralOrCountdownAndRetainsAncestorConstraints() {
        val selector = NodeSelector(
            text = TextCondition.SkipCountdown("跳过"), ancestorResourceId = "test.app:id/ad_parent",
            ancestorDistance = 1,
        )
        val ancestor = NodeSnapshot("test.app:id/ad_parent", null, null, true, true)
        for (text in listOf("跳过", "跳过 5秒", "跳过3s", "跳过 ４ 秒")) {
            assertTrue(SelectorMatcher.matches(selector, node(text), listOf(ancestor)))
        }
        for (text in listOf("跳过推荐", "跳过此歌曲", "跳过介绍", "跳过 3秒后进入详情", "跳过999秒")) {
            assertFalse(SelectorMatcher.matches(selector, node(text), listOf(ancestor)))
        }
        assertFalse(SelectorMatcher.matches(selector, node("跳过"), listOf(ancestor.copy(resourceId = "other"))))
        assertFalse(SelectorMatcher.matches(selector, node("跳过").copy(visibleToUser = false), listOf(ancestor)))
        assertTrue(TextCondition.SkipCountdown("skip").matches("SKIP 3s"))
        assertTrue(TextCondition.SkipCountdown("跳过广告").matches("跳过广告 3秒"))
    }

    private fun node(text: String) = NodeSnapshot(null, text, null, true, true)
}
