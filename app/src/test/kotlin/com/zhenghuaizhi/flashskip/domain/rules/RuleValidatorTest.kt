package com.zhenghuaizhi.flashskip.domain.rules

import com.zhenghuaizhi.flashskip.domain.model.AdRule
import com.zhenghuaizhi.flashskip.domain.model.NodeSelector
import com.zhenghuaizhi.flashskip.domain.model.TargetAppId
import com.zhenghuaizhi.flashskip.domain.model.TextCondition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleValidatorTest {
    @Test
    fun productionCatalogHasNoPreRegisteredTargetsWithoutEvidence() {
        val catalog = BuiltinRuleCatalog()

        assertTrue(catalog.snapshot.value.rules.isEmpty())
    }

    @Test
    fun validTestRulePassesCatalogValidation() {
        assertEquals(emptySet<RuleValidationIssue>(), RuleValidator.validate(listOf(validRule())))
    }

    @Test
    fun invalidRuleReportsIndependentIssues() {
        val invalid = validRule().copy(
            id = "",
            packageName = "wrong.package",
            allowedActivities = emptySet(),
            selectors = listOf(NodeSelector()),
            startupDelayMs = -1,
            cooldownMs = -1,
        )

        val issues = RuleValidator.validate(listOf(invalid, invalid))

        assertTrue(RuleValidationIssue.DUPLICATE_ID in issues)
        assertTrue(RuleValidationIssue.EMPTY_ID in issues)
        assertTrue(RuleValidationIssue.PACKAGE_MISMATCH in issues)
        assertTrue(RuleValidationIssue.NO_ACTIVITY in issues)
        assertTrue(RuleValidationIssue.EMPTY_SELECTOR in issues)
        assertTrue(RuleValidationIssue.NEGATIVE_DELAY in issues)
        assertTrue(RuleValidationIssue.NEGATIVE_COOLDOWN in issues)
    }

    @Test
    fun invalidCatalogFailsClosed() {
        val catalog = BuiltinRuleCatalog(
            listOf(validRule().copy(packageName = "wrong.package")),
        )

        assertTrue(catalog.validationIssues.isNotEmpty())
        assertTrue(catalog.rulesForTarget(TargetAppId("com.example.music")).isEmpty())
    }

    @Test
    fun blankSelectorValueIsRejected() {
        val rule = validRule().copy(
            selectors = listOf(NodeSelector(text = TextCondition.Prefix(""))),
        )

        assertTrue(RuleValidationIssue.EMPTY_SELECTOR in RuleValidator.validate(listOf(rule)))
    }

    @Test
    fun ancestorConstraintRequiresIdAndBoundedDistance() {
        val missingDistance = validRule().copy(
            selectors = listOf(
                NodeSelector(
                    text = TextCondition.Exact("跳过"),
                    ancestorResourceId = "com.example.music:id/jap",
                ),
            ),
        )
        val excessiveDistance = validRule().copy(
            selectors = listOf(
                NodeSelector(
                    text = TextCondition.Exact("跳过"),
                    ancestorResourceId = "com.example.music:id/jap",
                    ancestorDistance = 5,
                ),
            ),
        )

        assertTrue(
            RuleValidationIssue.INVALID_ANCESTOR in RuleValidator.validate(listOf(missingDistance)),
        )
        assertTrue(
            RuleValidationIssue.INVALID_ANCESTOR in RuleValidator.validate(listOf(excessiveDistance)),
        )
    }

    private fun validRule() = AdRule(
        id = "test-rule",
        targetAppId = TargetAppId("com.example.music"),
        packageName = "com.example.music",
        allowedActivities = setOf("com.example.TestSplashActivity"),
        selectors = listOf(NodeSelector(text = TextCondition.Exact("test-close"))),
        startupDelayMs = 0,
        cooldownMs = 500,
    )
}
