package com.zhenghuaizhi.flashskip.domain.rulecapture

import com.zhenghuaizhi.flashskip.domain.model.CaptureEvidence
import com.zhenghuaizhi.flashskip.domain.model.CapturedNode
import com.zhenghuaizhi.flashskip.domain.model.CapturedSelector
import com.zhenghuaizhi.flashskip.domain.model.RuleCardStatus
import com.zhenghuaizhi.flashskip.domain.model.TextCondition
import com.zhenghuaizhi.flashskip.testing.OTHER_TARGET
import com.zhenghuaizhi.flashskip.testing.TARGET
import com.zhenghuaizhi.flashskip.testing.captureSample
import org.junit.Assert.*
import org.junit.Test

class RuleCandidateAnalyzerTest {
    private val sample = captureSample().copy(preClickUniqueSelectors = emptyList())
    private val node = sample.nodes.single()

    @Test fun ac01_singleEligibleSampleNeedsNoPreClickUniqueEvidence() {
        val result = RuleCandidateAnalyzer.analyze(listOf(sample), TARGET)
        assertEquals(RuleCardStatus.READY, result.status)
        assertEquals(1, result.stableSampleCount)
        assertEquals(0, result.uniqueEvidenceCount)
        assertEquals(0L, result.rule!!.startupDelayMs)
        assertEquals(10_000L, result.rule!!.cooldownMs)
        assertNull(result.rule!!.targetAppVersion)
    }

    @Test fun ac01_wrongTargetNegativeTimeBlankActivityAndEmptyNodesProduceNoRules() {
        listOf(
            sample.copy(targetAppId = OTHER_TARGET),
            sample.copy(clickedAfterTargetMs = -1),
            sample.copy(activityName = " "),
            sample.copy(nodes = emptyList()),
            sample.copy(nodes = listOf(node.copy(visibleToUser = false))),
            sample.copy(nodes = listOf(node.copy(hasUsableBounds = false))),
            sample.copy(nodes = listOf(node.copy(clickable = false, supportsActionClick = false))),
        ).forEach { invalid ->
            val result = RuleCandidateAnalyzer.analyze(listOf(invalid), TARGET)
            assertEquals(invalid.toString(), RuleCardStatus.CANDIDATE, result.status)
            assertTrue(result.rules.isEmpty())
        }
    }

    @Test fun ac01_groupsKeepActivitiesSeparateAndNewestEvidenceFirst() {
        val old = sample.copy(id = 1, activityName = "OldActivity", capturedAtEpochMs = 1)
        val same = sample.copy(id = 2, activityName = "SameActivity", capturedAtEpochMs = 2)
        val other = sample.copy(
            id = 3, activityName = "OtherActivity", capturedAtEpochMs = 3,
            nodes = listOf(node.copy(resourceId = "${TARGET.packageName}:id/other")),
        )
        val rules = RuleCandidateAnalyzer.readyRules(listOf(same, other, old), TARGET)
        assertEquals(2, rules.size)
        assertEquals(setOf("OtherActivity"), rules[0].allowedActivities)
        assertEquals(setOf("OldActivity", "SameActivity"), rules[1].allowedActivities)
        assertTrue(rules.all { it.selectors.size <= 3 })
    }

    @Test fun ac02_plainTextRemainsCandidateEvenWithManualConfirmation() {
        assertCandidate(node.copy(resourceId = null, text = "Continue to content"))
    }

    @Test fun ac02_plainDescriptionRemainsCandidate() {
        assertCandidate(node.copy(resourceId = null, text = null, contentDescription = "Dismiss promotion"))
    }

    @Test fun ac02_textAndDescriptionWithoutStructureRemainCandidate() {
        assertCandidate(node.copy(resourceId = null, text = "Continue", contentDescription = "Dismiss promotion"))
    }

    @Test fun ac02_frameworkResourceDoesNotProvideStableStructure() {
        listOf("android:id/button1", "", " ").forEach { resourceId ->
            assertCandidate(node.copy(resourceId = resourceId, text = "Continue"))
        }
        val frameworkAncestor = CapturedNode(
            resourceId = "android:id/content",
            visibleToUser = true, hasUsableBounds = true,
        )
        val result = RuleCandidateAnalyzer.analyze(
            listOf(sample.copy(nodes = listOf(node.copy(resourceId = null, text = "Continue"), frameworkAncestor))), TARGET,
        )
        assertEquals(RuleCardStatus.CANDIDATE, result.status)
        assertTrue(result.rules.isEmpty())
    }

    @Test fun ac02_stablePrimaryCannotCarryUnconstrainedFallbackSelectors() {
        val result = RuleCandidateAnalyzer.analyze(listOf(sample.copy(nodes = listOf(node.copy(text = "Continue")))), TARGET)
        assertEquals(RuleCardStatus.READY, result.status)
        assertTrue("每个可执行选择器都需要结构约束，包括备用选择器", result.rules.flatMap { it.selectors }.all {
            !it.resourceId.isNullOrBlank() && !it.resourceId.startsWith("android:") ||
                !it.ancestorResourceId.isNullOrBlank() && !it.ancestorResourceId.startsWith("android:") && it.ancestorDistance in 1..4
        })
    }

    @Test fun ac02_genericDismissRequiresAppResourceOrStableAncestor() {
        assertCandidate(node.copy(resourceId = null))
        val ancestor = CapturedNode(
            resourceId = "${TARGET.packageName}:id/ad_container",
            visibleToUser = true, hasUsableBounds = true,
        )
        val result = RuleCandidateAnalyzer.analyze(
            listOf(sample.copy(nodes = listOf(node.copy(resourceId = null), ancestor))), TARGET,
        )
        assertEquals(RuleCardStatus.READY, result.status)
        assertTrue(result.rules.flatMap { it.selectors }.all {
            it.ancestorResourceId == ancestor.resourceId && it.ancestorDistance == 1
        })
    }

    @Test fun ac03_numericNeedsDismissSemanticsWithinActualActionChain() {
        val number = node.copy(resourceId = "${TARGET.packageName}:id/count", text = "5")
        val outerClose = node.copy(resourceId = "${TARGET.packageName}:id/close", text = "关闭")
        assertFalse(CaptureRuleSafetyPolicy.accepts(listOf(number, outerClose)))
        assertTrue(RuleCandidateAnalyzer.readyRules(listOf(sample.copy(nodes = listOf(number, outerClose))), TARGET).isEmpty())
        val child = number.copy(clickable = false, supportsActionClick = false)
        assertTrue(CaptureRuleSafetyPolicy.accepts(listOf(child, outerClose)))
        val rules = RuleCandidateAnalyzer.readyRules(listOf(sample.copy(nodes = listOf(child, outerClose))), TARGET)
        assertTrue(rules.isNotEmpty())
        assertTrue(rules.flatMap { it.selectors }.any { it.actionAncestorDistance == 1 })
    }

    @Test fun ac03_numericHintOnActionNodeProducesConstrainedCountdown() {
        val numeric = node.copy(text = "５秒", contentDescription = "关闭")
        val selectors = RuleCandidateAnalyzer.readyRules(listOf(sample.copy(nodes = listOf(numeric))), TARGET)
            .flatMap { it.selectors }
        assertTrue(selectors.any { it.text == TextCondition.NumericCountdown && it.resourceId == node.resourceId })
    }

    @Test fun ac03_countdownConversionKeepsOrdinaryTextExactAndRequiresStructure() {
        assertEquals(TextCondition.Exact("跳过 5"), CapturedSelector(resourceId = node.resourceId, text = "跳过 5").toRuntimeSelector().text)
        assertEquals(TextCondition.SkipCountdown("跳过"), CapturedSelector(
            text = "跳过 5", ancestorResourceId = "app:id/container", ancestorDistance = 1,
        ).toRuntimeSelector().text)
        assertEquals(TextCondition.NumericCountdown, CapturedSelector(resourceId = node.resourceId, text = "5").toRuntimeSelector().text)
        assertEquals(TextCondition.Exact("5"), CapturedSelector(text = "5").toRuntimeSelector().text)
        listOf("", " ", "android:id/button1").forEach { resourceId ->
            assertEquals(TextCondition.Exact("5"), CapturedSelector(resourceId = resourceId, text = "5").toRuntimeSelector().text)
            assertEquals(TextCondition.Exact("跳过 5"), CapturedSelector(
                text = "跳过 5", ancestorResourceId = resourceId, ancestorDistance = 1,
            ).toRuntimeSelector().text)
        }
        listOf(0, 5).forEach { distance ->
            assertEquals(TextCondition.Exact("跳过 5"), CapturedSelector(
                text = "跳过 5", ancestorResourceId = "app:id/container", ancestorDistance = distance,
            ).toRuntimeSelector().text)
        }
        assertEquals(TextCondition.Exact("跳过设置向导"), CapturedSelector(
            text = "跳过设置向导", ancestorResourceId = "app:id/container", ancestorDistance = 1,
        ).toRuntimeSelector().text)
    }

    private fun assertCandidate(candidate: CapturedNode) {
        val input = sample.copy(nodes = listOf(candidate), evidence = CaptureEvidence.USER_CONFIRMED_UNREPORTED_CLICK)
        val result = RuleCandidateAnalyzer.analyze(listOf(input), TARGET)
        assertEquals("无稳定结构不得 READY: $candidate", RuleCardStatus.CANDIDATE, result.status)
        assertTrue(result.rules.isEmpty())
    }
}
