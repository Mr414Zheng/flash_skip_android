package com.zhenghuaizhi.flashskip.domain.rulecapture

import com.zhenghuaizhi.flashskip.domain.model.CapturedNode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClickSourceRecoveryMatcherTest {
    @Test
    fun exactEventSemanticsMatchOnlySameActionableNode() {
        val hint = ClickSemanticHint(
            texts = setOf("跳过 3 秒"),
            contentDescription = "关闭启动广告",
        )

        assertTrue(
            ClickSourceRecoveryMatcher.matches(
                node(text = "跳过 3 秒", description = "关闭启动广告"),
                hint,
            ),
        )
        assertFalse(
            ClickSourceRecoveryMatcher.matches(
                node(text = "跳过 2 秒", description = "关闭启动广告"),
                hint,
            ),
        )
        assertFalse(
            ClickSourceRecoveryMatcher.matches(
                node(text = "跳过 3 秒", description = "其他按钮"),
                hint,
            ),
        )
    }

    @Test
    fun missingEventSemanticsAllowOnlyKnownDismissAction() {
        val hint = ClickSemanticHint()

        assertTrue(ClickSourceRecoveryMatcher.matches(node(text = "跳过"), hint))
        assertTrue(ClickSourceRecoveryMatcher.matches(node(text = "跳过 3 秒"), hint))
        assertTrue(ClickSourceRecoveryMatcher.matches(node(description = "Close"), hint))
        assertFalse(ClickSourceRecoveryMatcher.matches(node(text = "立即领取"), hint))
    }

    @Test
    fun hiddenOrUnboundedNodesCannotProvideRecoverySemantics() {
        val hint = ClickSemanticHint(texts = setOf("跳过"))

        assertFalse(
            ClickSourceRecoveryMatcher.matches(node(text = "跳过").copy(visibleToUser = false), hint),
        )
        assertFalse(
            ClickSourceRecoveryMatcher.matches(node(text = "跳过").copy(hasUsableBounds = false), hint),
        )
        assertTrue(
            ClickSourceRecoveryMatcher.matches(
                node(text = "跳过").copy(clickable = false, supportsActionClick = false),
                hint,
            ),
        )
    }

    private fun node(
        text: String? = null,
        description: String? = null,
    ) = CapturedNode(
        text = text,
        contentDescription = description,
        clickable = true,
        supportsActionClick = true,
        visibleToUser = true,
        hasUsableBounds = true,
    )
}
