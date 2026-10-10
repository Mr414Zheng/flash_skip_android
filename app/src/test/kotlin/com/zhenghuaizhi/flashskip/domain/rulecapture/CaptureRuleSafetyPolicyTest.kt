package com.zhenghuaizhi.flashskip.domain.rulecapture

import com.zhenghuaizhi.flashskip.domain.model.CaptureEvidence
import com.zhenghuaizhi.flashskip.domain.model.CaptureFailure
import com.zhenghuaizhi.flashskip.domain.model.CapturePhase
import com.zhenghuaizhi.flashskip.domain.model.CaptureSample
import com.zhenghuaizhi.flashskip.domain.model.CaptureSession
import com.zhenghuaizhi.flashskip.domain.model.CapturedNode
import com.zhenghuaizhi.flashskip.domain.model.CapturedSelector
import com.zhenghuaizhi.flashskip.domain.model.ObservedDismissCandidate
import com.zhenghuaizhi.flashskip.domain.model.RuleCaptureState
import com.zhenghuaizhi.flashskip.domain.model.SemanticDiffEvidence
import com.zhenghuaizhi.flashskip.domain.model.TargetAppId
import com.zhenghuaizhi.flashskip.domain.model.TextCondition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureRuleSafetyPolicyTest {
    @Test
    fun recommendationCountWithStableParentCannotCreateRuleEvenAfterUserConfirmation() {
        val nodes = listOf(
            node("count", text = "30", clickable = false),
            node("recommendation", description = "每日音频推荐"),
            node("container", clickable = false),
        )
        assertFalse(CaptureRuleSafetyPolicy.accepts(nodes))
        assertTrue(RuleCandidateAnalyzer.selectorCandidates(nodes).isEmpty())
        assertTrue(RuleCandidateAnalyzer.stableDiffSelectorCandidates(nodes).isEmpty())
        for (evidence in CaptureEvidence.entries) {
            val sample = sample(1, nodes).copy(evidence = evidence)
            assertTrue(RuleCandidateAnalyzer.readyRules(listOf(sample), TARGET).isEmpty())
            assertEquals(0, RuleCandidateAnalyzer.analyze(listOf(sample), TARGET).sampleCount)
        }
    }

    @Test
    fun numericCountdownRequiresDismissHintOnSourceOrActualActionTarget() {
        val numeric = node("counter", text = "5", clickable = false)
        val validChains = listOf(
            listOf(numeric.copy(contentDescription = "跳过"), node("obfuscated_button")),
            listOf(numeric, node("obfuscated_button", description = "关闭广告")),
            listOf(numeric, node("ad_skip")),
            listOf(numeric.copy(resourceId = "${TARGET.packageName}:id/skip_countdown", clickable = true)),
        )
        for (chain in validChains) {
            assertTrue(CaptureRuleSafetyPolicy.accepts(chain))
            assertTrue(RuleCandidateAnalyzer.readyRules(listOf(sample(1, chain)), TARGET).isNotEmpty())
            val numericSelector = RuleCandidateAnalyzer.selectorCandidates(chain).first { it.text == "5" }
            assertEquals(TextCondition.NumericCountdown, numericSelector.toRuntimeSelector().text)
        }
    }

    @Test
    fun unrelatedOuterDismissContainerDoesNotAuthorizeInnerNumber() {
        val nodes = listOf(node("counter", text = "5", clickable = false), node("card"), node("close"))
        assertFalse(CaptureRuleSafetyPolicy.accepts(nodes))
        assertFalse(CaptureRuleSafetyPolicy.accepts(listOf(
            node("counter", text = "5", clickable = false),
            node("card"),
            node("skip", clickable = false).copy(visibleToUser = false),
        )))
    }

    @Test
    fun realSkipWithObfuscatedAncestorRetainsItsRuleIdentity() {
        val sample = sample(2, listOf(node(null, text = "跳过"), node("obfuscated", clickable = false)))
        val rules = RuleCandidateAnalyzer.readyRules(listOf(sample), TARGET)
        assertEquals(1, rules.size)
        assertEquals("${TARGET.packageName}:id/obfuscated", rules.single().selectors.single().ancestorResourceId)
        assertEquals(TextCondition.SkipCountdown("跳过"), rules.single().selectors.single().text)
    }

    @Test
    fun cleanupRemovesOnlyRejectedSamplesAndTheirConvergenceEvidenceAndIsIdempotent() {
        val rejected = sample(1, listOf(node("count", text = "30", clickable = false), node("card")))
        val valid = sample(2, listOf(node("skip", text = "跳过")))
        val otherEvidence = SemanticDiffEvidence(TARGET, "test.music.OtherActivity", emptyList(), 1)
        val state = RuleCaptureState(
            samples = listOf(rejected, valid),
            semanticDiffEvidence = listOf(
                SemanticDiffEvidence(TARGET, rejected.activityName, listOf(CapturedSelector(resourceId = "test.music:id/card")), 1),
                otherEvidence,
            ),
        )
        val clean = CaptureRuleSafetyPolicy.sanitize(state)
        assertEquals(listOf(valid), clean.samples)
        assertEquals(listOf(otherEvidence), clean.semanticDiffEvidence)
        assertEquals(clean, CaptureRuleSafetyPolicy.sanitize(clean))
    }

    @Test
    fun rejectedPendingCandidateCannotResumeListeningOrBeSaved() {
        val rejected = sample(1, listOf(node("count", text = "30")))
        val candidate = ObservedDismissCandidate(1, TARGET, "Music", rejected.activityName, 10, 10, rejected.nodes, emptyList())
        val session = CaptureSession(
            id = 1, targetAppId = TARGET, phase = CapturePhase.AWAITING_CONFIRMATION,
            phaseStartedAtEpochMs = 10, deadlineAtEpochMs = 100,
            activityName = rejected.activityName, pendingObservedCandidate = candidate,
            candidateClickListeningEnabled = true,
        )
        val clean = requireNotNull(CaptureRuleSafetyPolicy.sanitize(RuleCaptureState(activeSession = session)).activeSession)
        assertEquals(CapturePhase.FAILED, clean.phase)
        assertEquals(CaptureFailure.DISMISS_NOT_CAPTURED, clean.failure)
        assertEquals(null, clean.pendingObservedCandidate)
        assertFalse(clean.listensForCandidateClick)
        assertFalse(clean.blocksAutomation)
    }

    private fun node(id: String?, text: String? = null, description: String? = null, clickable: Boolean = true) = CapturedNode(
        resourceId = id?.let { "${TARGET.packageName}:id/$it" },
        text = text, contentDescription = description, clickable = clickable,
        supportsActionClick = clickable, visibleToUser = true, hasUsableBounds = true,
    )

    private fun sample(id: Long, nodes: List<CapturedNode>) = CaptureSample(
        id, TARGET, "Music", "test.music.StartActivity", 100, 100, nodes, emptyList(),
    )

    private companion object {
        val TARGET = TargetAppId("test.music")
    }
}
