package com.zhenghuaizhi.flashskip.domain.rulecapture

import com.zhenghuaizhi.flashskip.domain.model.*
import com.zhenghuaizhi.flashskip.testing.*
import org.junit.Assert.*
import org.junit.Test

class CaptureRuleSafetyPolicyTest {
    private val sample = captureSample()
    private val action = sample.nodes.single()
    private val number = action.copy(text = "5", resourceId = "app:id/count")

    @Test fun ac03_numericNeedsHintBeforeOrAtFirstAction() {
        assertFalse(CaptureRuleSafetyPolicy.accepts(listOf(number, action)))
        assertTrue(CaptureRuleSafetyPolicy.accepts(listOf(number.copy(clickable = false, supportsActionClick = false), action)))
        assertTrue(CaptureRuleSafetyPolicy.accepts(listOf(number.copy(contentDescription = "关闭"))))
        assertTrue(CaptureRuleSafetyPolicy.accepts(listOf(number.copy(resourceId = "app:id/ad_close"))))
    }

    @Test fun ac03_invisibleHintAndMissingActionDoNotRescueNumericNode() {
        assertFalse(CaptureRuleSafetyPolicy.accepts(listOf(number.copy(clickable = false, supportsActionClick = false), action.copy(visibleToUser = false))))
        assertFalse(CaptureRuleSafetyPolicy.accepts(listOf(number.copy(clickable = false, supportsActionClick = false))))
    }

    @Test fun ac03_sanitizationClearsOnlyRejectedScopeAndPendingEvidence() {
        val unsafe = sample.copy(nodes = listOf(number))
        val other = captureSample(101, OTHER_TARGET)
        val evidence = listOf(sample, other).map { SemanticDiffEvidence(it.targetAppId, it.activityName, it.preClickUniqueSelectors, 1) }
        val pending = ObservedDismissCandidate(sample.id, sample.targetAppId, sample.targetAppName, sample.activityName, 1, 1, unsafe.nodes, emptyList())
        val state = RuleCaptureState(listOf(unsafe, other), CaptureSession(sample.id, sample.targetAppId,
            phase = CapturePhase.AWAITING_CONFIRMATION, phaseStartedAtEpochMs = 1, deadlineAtEpochMs = 2,
            activityName = sample.activityName, pendingObservedCandidate = pending, candidateClickListeningEnabled = true), evidence)
        val sanitized = CaptureRuleSafetyPolicy.sanitize(state)
        assertEquals(listOf(other), sanitized.samples)
        assertEquals(listOf(evidence.last()), sanitized.semanticDiffEvidence)
        assertEquals(CapturePhase.FAILED, sanitized.activeSession?.phase)
        assertNull(sanitized.activeSession?.pendingObservedCandidate)
        assertFalse(sanitized.activeSession!!.candidateClickListeningEnabled)
        assertEquals(sanitized, CaptureRuleSafetyPolicy.sanitize(sanitized))
    }
}
