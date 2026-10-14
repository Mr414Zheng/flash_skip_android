package com.zhenghuaizhi.flashskip.domain.rulecapture

import com.zhenghuaizhi.flashskip.testing.captureSample
import org.junit.Assert.*
import org.junit.Test

class ClickSourceRecoveryMatcherTest {
    private val node = captureSample().nodes.single()

    @Test fun ac02_explicitTextAndDescriptionMustBothMatch() {
        val labelled = node.copy(contentDescription = "close ad")
        assertTrue(ClickSourceRecoveryMatcher.matches(labelled, ClickSemanticHint(setOf("跳过"), "close ad")))
        assertFalse(ClickSourceRecoveryMatcher.matches(labelled, ClickSemanticHint(setOf("other"), "close ad")))
        assertFalse(ClickSourceRecoveryMatcher.matches(labelled, ClickSemanticHint(setOf("跳过"), "other")))
    }

    @Test fun ac02_multipleEventTextsAreAlternativesAndMatchingIsExact() {
        assertTrue(ClickSourceRecoveryMatcher.matches(node, ClickSemanticHint(setOf("other", "跳过"))))
        assertFalse(ClickSourceRecoveryMatcher.matches(node, ClickSemanticHint(setOf(" 跳过 "))))
        assertFalse(ClickSourceRecoveryMatcher.matches(node.copy(text = "Skip"), ClickSemanticHint(setOf("skip"))))
    }

    @Test fun ac02_invisibleOrUnusableNodeCannotMatchAnExplicitEvent() {
        val hint = ClickSemanticHint(setOf("跳过"))
        assertFalse(ClickSourceRecoveryMatcher.matches(node.copy(visibleToUser = false), hint))
        assertFalse(ClickSourceRecoveryMatcher.matches(node.copy(hasUsableBounds = false), hint))
    }

    @Test fun ac03_passiveDiscoveryRequiresKnownDismissOrExplicitNumericPermission() {
        assertTrue(ClickSourceRecoveryMatcher.matches(node, ClickSemanticHint()))
        assertFalse(ClickSourceRecoveryMatcher.matches(node.copy(text = "Buy now"), ClickSemanticHint()))
        assertFalse(ClickSourceRecoveryMatcher.matches(node.copy(text = "5"), ClickSemanticHint()))
        assertTrue(ClickSourceRecoveryMatcher.matches(node.copy(text = "5"), ClickSemanticHint(allowBareNumericCountdown = true)))
    }
}
