package com.zhenghuaizhi.flashskip.domain.rulecapture

import com.zhenghuaizhi.flashskip.domain.model.AppVersion
import com.zhenghuaizhi.flashskip.domain.model.CaptureSample
import com.zhenghuaizhi.flashskip.domain.model.CapturedNode
import com.zhenghuaizhi.flashskip.domain.model.CapturedSelector
import com.zhenghuaizhi.flashskip.domain.model.RuleCardStatus
import com.zhenghuaizhi.flashskip.domain.model.TargetAppId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RuleCandidateAnalyzerTest {
    @Test
    fun oneSampleCreatesCandidateAndFiveStableSamplesCreateReadyRule() {
        val selector = CapturedSelector(
            resourceId = "com.tencent.qqmusic:id/close",
            contentDescription = "关闭启动广告",
        )
        val one = RuleCandidateAnalyzer.analyze(
            listOf(sample(1, selector, unique = true)),
            TargetAppId.QQ_MUSIC,
            VERSION,
        )
        assertEquals(RuleCardStatus.CANDIDATE, one.status)
        assertNull(one.rule)

        val ready = RuleCandidateAnalyzer.analyze(
            (1L..5L).map { sample(it, selector, unique = it <= 2) },
            TargetAppId.QQ_MUSIC,
            VERSION,
        )
        assertEquals(RuleCardStatus.READY, ready.status)
        assertEquals(5, ready.stableSampleCount)
        assertEquals(2, ready.uniqueEvidenceCount)
        assertEquals(1_001L, ready.minimumClickDelayMs)
        assertNotNull(ready.rule)
        assertEquals(VERSION, requireNotNull(ready.rule).targetAppVersion)
    }

    @Test
    fun genericSingleTextNeverBecomesReady() {
        val selector = CapturedSelector(text = "关闭")
        val analysis = RuleCandidateAnalyzer.analyze(
            (1L..5L).map { sample(it, selector, unique = true) },
            TargetAppId.QQ_MUSIC,
            VERSION,
        )

        assertEquals(RuleCardStatus.CANDIDATE, analysis.status)
        assertNull(analysis.rule)
    }

    @Test
    fun divergentLatestCaptureWithdrawsReadyRule() {
        val stable = CapturedSelector(resourceId = "com.tencent.qqmusic:id/close")
        val divergent = CapturedSelector(resourceId = "com.tencent.qqmusic:id/dismiss")
        val samples = (1L..5L).map { sample(it, stable, unique = it <= 2) } +
            sample(6, divergent, unique = true)

        val analysis = RuleCandidateAnalyzer.analyze(
            samples,
            TargetAppId.QQ_MUSIC,
            VERSION,
        )

        assertEquals(RuleCardStatus.CANDIDATE, analysis.status)
        assertNull(analysis.rule)
    }

    @Test
    fun differentInstalledVersionMarksExistingSamplesStale() {
        val selector = CapturedSelector(resourceId = "com.tencent.qqmusic:id/close")
        val analysis = RuleCandidateAnalyzer.analyze(
            listOf(sample(1, selector, unique = true)),
            TargetAppId.QQ_MUSIC,
            VERSION.copy(versionCode = 43),
        )

        assertEquals(RuleCardStatus.STALE, analysis.status)
        assertEquals(0, analysis.currentVersionSampleCount)
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
            ancestorResourceId = "com.tencent.qqmusic:id/jap",
            ancestorDistance = 1,
        )
        val samples = (1L..5L).map { id ->
            qqMusicSample(
                id = id,
                uniqueSelectors = if (id <= 2) listOf(structural) else emptyList(),
            )
        }

        val analysis = RuleCandidateAnalyzer.analyze(
            samples,
            TargetAppId.QQ_MUSIC,
            VERSION,
        )

        assertEquals(RuleCardStatus.READY, analysis.status)
        assertEquals(structural, analysis.primarySelector)
        assertEquals(structural.toNodeSelector(), requireNotNull(analysis.rule).selectors.first())
    }

    @Test
    fun nonClickableParentIdCannotBecomeFlatActionSelector() {
        val oldParentEvidence = CapturedSelector(
            resourceId = "com.tencent.qqmusic:id/jap",
        )
        val samples = (1L..5L).map { id ->
            qqMusicSample(
                id = id,
                uniqueSelectors = if (id <= 2) listOf(oldParentEvidence) else emptyList(),
            )
        }

        val analysis = RuleCandidateAnalyzer.analyze(
            samples,
            TargetAppId.QQ_MUSIC,
            VERSION,
        )

        assertEquals(RuleCardStatus.CANDIDATE, analysis.status)
        assertEquals(0, analysis.uniqueEvidenceCount)
        assertNull(analysis.rule)
        assertFalse(
            RuleCandidateAnalyzer.selectorCandidates(samples.first().nodes)
                .contains(oldParentEvidence),
        )
    }

    private fun sample(
        id: Long,
        selector: CapturedSelector,
        unique: Boolean,
    ) = CaptureSample(
        id = id,
        targetAppId = TargetAppId.QQ_MUSIC,
        appVersion = VERSION,
        activityName = "com.tencent.qqmusic.activity.AppStarterActivity",
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

    private fun qqMusicSample(
        id: Long,
        uniqueSelectors: List<CapturedSelector>,
    ) = CaptureSample(
        id = id,
        targetAppId = TargetAppId.QQ_MUSIC,
        appVersion = VERSION,
        activityName = "com.tencent.qqmusic.activity.AppStarterActivity",
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
                resourceId = "com.tencent.qqmusic:id/jap",
                visibleToUser = true,
                hasUsableBounds = true,
            ),
        ),
        preClickUniqueSelectors = uniqueSelectors,
    )

    private companion object {
        val VERSION = AppVersion("13.2.0", 42)
    }
}
