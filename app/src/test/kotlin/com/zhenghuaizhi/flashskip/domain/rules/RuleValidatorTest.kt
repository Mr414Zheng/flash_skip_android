package com.zhenghuaizhi.flashskip.domain.rules

import com.zhenghuaizhi.flashskip.domain.model.NodeSelector
import com.zhenghuaizhi.flashskip.domain.model.TextCondition
import com.zhenghuaizhi.flashskip.testing.executableRule
import org.junit.Assert.*
import org.junit.Test

class RuleValidatorTest {
    private val rule = executableRule()

    @Test fun ac04_rejectsEachMalformedRuleField() {
        val invalid = listOf(
            rule.copy(id = " ") to RuleValidationIssue.EMPTY_ID,
            rule.copy(packageName = "wrong.package") to RuleValidationIssue.PACKAGE_MISMATCH,
            rule.copy(allowedActivities = emptySet()) to RuleValidationIssue.NO_ACTIVITY,
            rule.copy(allowedActivities = setOf(" ")) to RuleValidationIssue.NO_ACTIVITY,
            rule.copy(selectors = emptyList()) to RuleValidationIssue.NO_SELECTOR,
            rule.copy(selectors = listOf(NodeSelector())) to RuleValidationIssue.EMPTY_SELECTOR,
            rule.copy(selectors = listOf(NodeSelector(resourceId = " "))) to RuleValidationIssue.EMPTY_SELECTOR,
            rule.copy(selectors = listOf(NodeSelector(text = TextCondition.Exact(" ")))) to RuleValidationIssue.EMPTY_SELECTOR,
            rule.copy(selectors = listOf(NodeSelector(contentDescription = " "))) to RuleValidationIssue.EMPTY_SELECTOR,
            rule.copy(startupDelayMs = -1) to RuleValidationIssue.NEGATIVE_DELAY,
            rule.copy(cooldownMs = -1) to RuleValidationIssue.NEGATIVE_COOLDOWN,
        )
        invalid.forEach { (input, expected) ->
            assertTrue("$input must reject $expected", expected in RuleValidator.validate(listOf(input)))
        }
    }

    @Test fun ac04_duplicateIdsInvalidateEntireBuiltinSet() {
        assertEquals(setOf(RuleValidationIssue.DUPLICATE_ID), RuleValidator.validate(listOf(rule, rule)))
        assertTrue(BuiltinRuleCatalog(listOf(rule, rule)).snapshot.value.rules.isEmpty())
        assertTrue(BuiltinRuleCatalog(listOf(rule, rule.copy(id = "bad", startupDelayMs = -1))).snapshot.value.rules.isEmpty())
    }

    @Test fun ac03_ac04_ancestorAndActionDistancesAreOneThroughFour() {
        for (distance in listOf(-1, 0, 1, 4, 5)) {
            val selector = rule.selectors.single().copy(ancestorResourceId = "app:id/container", ancestorDistance = distance)
            assertEquals("ancestor $distance", distance !in 1..4, RuleValidationIssue.INVALID_ANCESTOR in RuleValidator.validate(listOf(rule.copy(selectors = listOf(selector)))))
            val action = rule.selectors.single().copy(actionAncestorDistance = distance)
            assertEquals("action $distance", distance !in 1..4, RuleValidationIssue.INVALID_ACTION_TARGET in RuleValidator.validate(listOf(rule.copy(selectors = listOf(action)))))
        }
        listOf(
            NodeSelector(resourceId = "app:id/button", ancestorResourceId = "app:id/container"),
            NodeSelector(resourceId = "app:id/button", ancestorDistance = 1),
            NodeSelector(resourceId = "app:id/button", ancestorResourceId = " ", ancestorDistance = 1),
        ).forEach {
            assertTrue(RuleValidationIssue.INVALID_ANCESTOR in RuleValidator.validate(listOf(rule.copy(selectors = listOf(it)))))
        }
    }

    @Test fun ac03_dynamicTextNeedsAppSpecificStructure() {
        listOf(TextCondition.NumericCountdown, TextCondition.SkipCountdown("跳过")).forEach { text ->
            val plain = NodeSelector(text = text)
            assertTrue(RuleValidationIssue.UNSAFE_DYNAMIC_TEXT in RuleValidator.validate(listOf(rule.copy(selectors = listOf(plain)))))
            assertTrue(RuleValidationIssue.UNSAFE_DYNAMIC_TEXT in RuleValidator.validate(listOf(rule.copy(selectors = listOf(plain.copy(resourceId = "android:id/button1"))))))
            assertTrue(RuleValidator.validate(listOf(rule.copy(selectors = listOf(plain.copy(ancestorResourceId = "app:id/container", ancestorDistance = 4))))).isEmpty())
        }
    }

    @Test fun ac02_plainTextCannotEnterBuiltinCatalog() {
        assertRejectedByAllEntries(NodeSelector(text = TextCondition.Exact("Continue")))
    }

    @Test fun ac02_plainDescriptionCannotEnterBuiltinCatalog() {
        assertRejectedByAllEntries(NodeSelector(contentDescription = "Dismiss promotion"))
    }

    @Test fun ac02_combinedTextAndDescriptionCannotEnterBuiltinCatalog() {
        assertRejectedByAllEntries(NodeSelector(text = TextCondition.Exact("Continue"), contentDescription = "Dismiss promotion"))
    }

    @Test fun ac02_frameworkIdCannotBypassStableStructurePolicy() {
        listOf("android:id/button1", "", " ").forEach { resourceId ->
            assertRejectedByAllEntries(NodeSelector(resourceId = resourceId, text = TextCondition.Exact("Continue")))
            assertRejectedByAllEntries(NodeSelector(
                text = TextCondition.Exact("Continue"), ancestorResourceId = resourceId, ancestorDistance = 1,
            ))
        }
        listOf(0, 5).forEach { distance ->
            assertRejectedByAllEntries(NodeSelector(
                text = TextCondition.Exact("Continue"), ancestorResourceId = "app:id/container", ancestorDistance = distance,
            ))
        }
    }

    @Test fun ac02_stablePrimaryCannotAuthorizeUnconstrainedFallbackSelector() {
        val input = rule.copy(selectors = rule.selectors + NodeSelector(text = TextCondition.Exact("Continue")))
        assertTrue("备用选择器也必须有稳定结构", RuleValidator.validate(listOf(input)).isNotEmpty())
        assertTrue(BuiltinRuleCatalog(listOf(input)).snapshot.value.rules.isEmpty())
    }

    @Test fun ac02_stableResourceAndStableAncestorAreAccepted() {
        val selectors = listOf(rule.selectors.single(), NodeSelector(
            text = TextCondition.Exact("Continue"), ancestorResourceId = "app:id/container", ancestorDistance = 1,
        )) + (1..4).map { distance ->
            NodeSelector(
                resourceId = "android:id/button1",
                text = TextCondition.Exact("Continue"), ancestorResourceId = "app:id/container", ancestorDistance = distance,
            )
        }
        selectors.forEach { selector ->
            val input = rule.copy(selectors = listOf(selector))
            assertTrue(RuleValidator.validate(listOf(input)).isEmpty())
            assertEquals(listOf(input), BuiltinRuleCatalog(listOf(input)).snapshot.value.rules)
        }
    }

    private fun assertRejectedByAllEntries(selector: NodeSelector) {
        val input = rule.copy(selectors = listOf(selector))
        assertTrue("统一校验须拒绝无稳定结构的规则", RuleValidationIssue.MISSING_STABLE_STRUCTURE in RuleValidator.validate(listOf(input)))
        assertTrue("内置目录不得绕过同一政策", BuiltinRuleCatalog(listOf(input)).snapshot.value.rules.isEmpty())
    }
}
