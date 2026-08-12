package com.zhenghuaizhi.flashskip.domain.rulecapture

import com.zhenghuaizhi.flashskip.domain.model.CaptureSample
import com.zhenghuaizhi.flashskip.domain.model.CapturedNode
import com.zhenghuaizhi.flashskip.domain.model.CapturedSelector
import com.zhenghuaizhi.flashskip.domain.model.RuleCardStatus
import com.zhenghuaizhi.flashskip.domain.model.TargetAppId
import com.zhenghuaizhi.flashskip.domain.model.TextCondition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RuleCandidateAnalyzerTest {
    @Test
    fun oneStrongSampleCreatesReadyRuleWithoutUniqueProbe() {
        val selector = CapturedSelector(
            resourceId = "com.example.music:id/close",
            contentDescription = "关闭启动广告",
        )
        val ready = RuleCandidateAnalyzer.analyze(
            listOf(sample(1, selector, unique = false)),
            TargetAppId("com.example.music"),
        )
        assertEquals(RuleCardStatus.READY, ready.status)
        assertEquals(1, ready.stableSampleCount)
        assertEquals(0, ready.uniqueEvidenceCount)
        assertEquals(1_001L, ready.minimumClickDelayMs)
        assertNotNull(ready.rule)
        val rule = requireNotNull(ready.rule)
        assertEquals(0L, rule.startupDelayMs)
        assertNull(rule.targetAppVersion)
    }

    @Test
    fun genericSingleTextNeverBecomesReady() {
        val selector = CapturedSelector(text = "关闭")
        val analysis = RuleCandidateAnalyzer.analyze(
            listOf(sample(1, selector, unique = false)),
            TargetAppId("com.example.music"),
        )

        assertEquals(RuleCardStatus.CANDIDATE, analysis.status)
        assertNull(analysis.rule)
    }

    @Test
    fun divergentSuccessfulCapturesAccumulateIndependentRules() {
        val stable = CapturedSelector(resourceId = "com.example.music:id/close")
        val divergent = CapturedSelector(resourceId = "com.example.music:id/dismiss")
        val samples = (1L..2L).map { sample(it, stable, unique = it == 2L) } +
            sample(3, divergent, unique = true)

        val analysis = RuleCandidateAnalyzer.analyze(
            samples,
            TargetAppId("com.example.music"),
        )

        assertEquals(RuleCardStatus.READY, analysis.status)
        assertEquals(2, analysis.rules.size)
        assertEquals("com.example.music:id/dismiss", analysis.primarySelector?.resourceId)
    }

    @Test
    fun repeatedSelectorMergesEvidenceAndActivities() {
        val selector = CapturedSelector(resourceId = "com.example.music:id/close")
        val original = RuleCandidateAnalyzer.analyze(
            listOf(sample(1, selector, unique = false)),
            TargetAppId("com.example.music"),
        )
        val analysis = RuleCandidateAnalyzer.analyze(
            listOf(
                sample(1, selector, unique = false),
                sample(2, selector, unique = true).copy(
                    activityName = "com.example.music.activity.NewStarterActivity",
                ),
            ),
            TargetAppId("com.example.music"),
        )

        assertEquals(RuleCardStatus.READY, analysis.status)
        assertEquals(2, analysis.sampleCount)
        assertEquals(1, analysis.rules.size)
        assertNull(requireNotNull(analysis.rule).targetAppVersion)
        assertEquals(
            setOf(
                "com.example.music.activity.AppStarterActivity",
                "com.example.music.activity.NewStarterActivity",
            ),
            requireNotNull(analysis.rule).allowedActivities,
        )
        assertEquals(original.rule?.id, analysis.rule?.id)
    }

    @Test
    fun invisibleOrUnboundedNodesDoNotProduceSelectors() {
        val nodes = listOf(
            CapturedNode(resourceId = "hidden", visibleToUser = false, hasUsableBounds = true),
            CapturedNode(resourceId = "unbounded", visibleToUser = true, hasUsableBounds = false),
        )

        assertEquals(emptyList<CapturedSelector>(), RuleCandidateAnalyzer.selectorCandidates(nodes))
    }

    @Test
    fun genericClickedTextBecomesReadyOnlyWhenScopedByStableAncestor() {
        val structural = CapturedSelector(
            text = "跳过",
            ancestorResourceId = "com.example.music:id/jap",
            ancestorDistance = 1,
        )
        val samples = listOf(capturedSample(id = 1, uniqueSelectors = emptyList()))

        val analysis = RuleCandidateAnalyzer.analyze(
            samples,
            TargetAppId("com.example.music"),
        )

        assertEquals(RuleCardStatus.READY, analysis.status)
        assertEquals(structural, analysis.primarySelector)
        assertEquals(
            TextCondition.Prefix("跳过"),
            requireNotNull(analysis.rule).selectors.first().text,
        )
    }

    @Test
    fun countdownPrefixRequiresStableAncestorAndDoesNotBroadenCloseText() {
        assertEquals(
            TextCondition.Exact("跳过"),
            CapturedSelector(text = "跳过").toRuntimeSelector().text,
        )
        assertEquals(
            TextCondition.Exact("关闭"),
            CapturedSelector(
                text = "关闭",
                ancestorResourceId = "com.example.music:id/jap",
                ancestorDistance = 1,
            ).toRuntimeSelector().text,
        )
        assertEquals(
            TextCondition.Exact("跳过"),
            CapturedSelector(
                text = "跳过",
                ancestorResourceId = "android:id/content",
                ancestorDistance = 1,
            ).toRuntimeSelector().text,
        )
    }

    @Test
    fun nonClickableParentIdCannotBecomeFlatActionSelector() {
        val oldParentEvidence = CapturedSelector(
            resourceId = "com.example.music:id/jap",
        )
        val samples = (1L..2L).map { id ->
            capturedSample(
                id = id,
                uniqueSelectors = if (id == 2L) listOf(oldParentEvidence) else emptyList(),
            )
        }

        val analysis = RuleCandidateAnalyzer.analyze(
            samples,
            TargetAppId("com.example.music"),
        )

        assertEquals(RuleCardStatus.READY, analysis.status)
        assertEquals(0, analysis.uniqueEvidenceCount)
        assertNotNull(analysis.rule)
        assertFalse(
            requireNotNull(analysis.rule).selectors.any {
                it.resourceId == oldParentEvidence.resourceId &&
                    it.text == null &&
                    it.contentDescription == null
            },
        )
    }

    @Test
    fun oneStrongSampleWithoutUniqueProbeStillBecomesReady() {
        val selector = CapturedSelector(resourceId = "com.example.music:id/close")
        val analysis = RuleCandidateAnalyzer.analyze(
            listOf(sample(1, selector, unique = false)),
            TargetAppId("com.example.music"),
        )

        assertEquals(RuleCardStatus.READY, analysis.status)
        assertEquals(1, analysis.stableSampleCount)
        assertEquals(0, analysis.uniqueEvidenceCount)
        assertNotNull(analysis.rule)
    }

    private fun sample(
        id: Long,
        selector: CapturedSelector,
        unique: Boolean,
    ) = CaptureSample(
        id = id,
        targetAppId = TargetAppId("com.example.music"),
        activityName = "com.example.music.activity.AppStarterActivity",
        clickedAfterTargetMs = 1_000L + id,
        capturedAtEpochMs = 10_000L + id,
        nodes = listOf(
            CapturedNode(
                resourceId = selector.resourceId,
                text = selector.text,
                contentDescription = selector.contentDescription,
                clickable = true,
                supportsActionClick = true,
                visibleToUser = true,
                hasUsableBounds = true,
            ),
        ),
        preClickUniqueSelectors = if (unique) listOf(selector) else emptyList(),
    )

    private fun capturedSample(
        id: Long,
        uniqueSelectors: List<CapturedSelector>,
    ) = CaptureSample(
        id = id,
        targetAppId = TargetAppId("com.example.music"),
        activityName = "com.example.music.activity.AppStarterActivity",
        clickedAfterTargetMs = 200L + id,
        capturedAtEpochMs = 20_000L + id,
        nodes = listOf(
            CapturedNode(
                text = "跳过",
                clickable = true,
                supportsActionClick = true,
                visibleToUser = true,
                hasUsableBounds = true,
            ),
            CapturedNode(
                resourceId = "com.example.music:id/jap",
                visibleToUser = true,
                hasUsableBounds = true,
            ),
        ),
        preClickUniqueSelectors = uniqueSelectors,
    )
}
