package com.zhenghuaizhi.flashskip.platform.accessibility

import com.zhenghuaizhi.flashskip.domain.model.*
import com.zhenghuaizhi.flashskip.domain.rulecapture.ClickSemanticHint
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], shadows = [FaultableAccessibilityNodeShadow::class, CopyingAccessibilityServiceShadow::class])
class AccessibilityCaptureProbeTest {
    @Test fun ac02_completeUniqueExplicitSemanticRecoversActionChain() {
        val f = AccessibilityFixture()
        val result = f.captureProbe().recoverClickedSemanticChainOnVerifiedRoot(f.identity, f.root, ClickSemanticHint(setOf("跳过")))
        assertTrue(result is AccessibilityCaptureProbe.RecoveryResult.Success)
        assertTrue(result.diagnostics.coverageComplete)
        assertEquals(f.target.viewIdResourceName, (result as AccessibilityCaptureProbe.RecoveryResult.Success).nodes.first().resourceId)
    }

    @Test fun ac02_twoMatchingSemanticTargetsAreAmbiguous() {
        val f = AccessibilityFixture()
        f.add(f.root, f.node("second_skip", "跳过", true))
        val result = f.captureProbe().recoverClickedSemanticChainOnVerifiedRoot(f.identity, f.root, ClickSemanticHint(setOf("跳过")))
        assertTrue(result is AccessibilityCaptureProbe.RecoveryResult.Failure)
        assertEquals(ClickRecoveryOutcome.AMBIGUOUS, result.diagnostics.outcome)
    }

    @Test fun ac02_explicitHintMismatchProducesCompleteNoMatch() {
        val f = AccessibilityFixture()
        val result = f.captureProbe().recoverClickedSemanticChainOnVerifiedRoot(f.identity, f.root, ClickSemanticHint(setOf("Buy")))
        assertTrue(result is AccessibilityCaptureProbe.RecoveryResult.Failure)
        assertEquals(ClickRecoveryOutcome.NO_MATCH, result.diagnostics.outcome)
        assertTrue(result.diagnostics.coverageComplete)
    }

    @Test fun ac02_changedIdentityWrongRootAndOutsideTargetCannotRecover() {
        val f = AccessibilityFixture()
        val probe = f.captureProbe()
        f.currentIdentity = f.identity.copy(windowId = 8)
        assertEquals(ClickRecoveryOutcome.IDENTITY_CHANGED, probe.recoverClickedSemanticChainOnVerifiedRoot(f.identity, f.root, ClickSemanticHint()).diagnostics.outcome)
        f.currentIdentity = f.identity
        val wrongRoot = f.node("wrong").also { it.packageName = "other" }
        assertEquals(ClickRecoveryOutcome.ROOT_MISMATCH, probe.recoverClickedSemanticChainOnVerifiedRoot(f.identity, wrongRoot, ClickSemanticHint()).diagnostics.outcome)
        f.targets = emptySet()
        assertEquals(ClickRecoveryOutcome.OUTSIDE_TARGETS, probe.recoverClickedSemanticChainOnVerifiedRoot(f.identity, f.root, ClickSemanticHint()).diagnostics.outcome)
    }

    @Test fun ac04_unreadableChildPreventsUniquenessAndAbsentProof() {
        val f = AccessibilityFixture()
        f.add(f.root, f.node("unreadable"))
        FaultableAccessibilityNodeShadow.unreadableChildren += f.root.viewIdResourceName to 1
        val result = f.captureProbe().recoverClickedSemanticChainOnVerifiedRoot(f.identity, f.root, ClickSemanticHint(setOf("跳过")))
        assertTrue(result is AccessibilityCaptureProbe.RecoveryResult.Failure)
        assertFalse(result.diagnostics.coverageComplete)
        assertEquals(ClickRecoveryOutcome.UNAVAILABLE, result.diagnostics.outcome)
        assertTrue(f.captureProbe().uniqueSelectorsOnVerifiedRoot(f.identity, f.root, listOf(CapturedSelector(resourceId = f.target.viewIdResourceName))) is AccessibilityCaptureProbe.ProbeResult.Unavailable)
    }

    @Test fun policy03_partialScanCannotRecoverARealClickAsUnique() {
        val f = AccessibilityFixture()
        f.add(f.root, f.node("unvisited"))
        f.add(f.root, f.node("first_scanned_skip", "跳过", true))
        val result = f.captureProbe().recoverClickedSemanticChainOnVerifiedRoot(f.identity, f.root,
            ClickSemanticHint(setOf("跳过")), budget = CaptureRoundBudget({ f.now }, 250, 5))
        assertFalse(result.diagnostics.coverageComplete)
        assertTrue("单次不完整扫描不能证明唯一点击目标", result is AccessibilityCaptureProbe.RecoveryResult.Failure)
    }

    @Test fun ac04_uniqueSelectorEvidenceRequiresCompleteWindow() {
        val f = AccessibilityFixture()
        val selector = CapturedSelector(resourceId = f.target.viewIdResourceName)
        assertEquals(AccessibilityCaptureProbe.ProbeResult.Success(setOf(selector)), f.captureProbe().uniqueSelectorsOnVerifiedRoot(f.identity, f.root, listOf(selector)))
        assertEquals(AccessibilityCaptureProbe.ProbeResult.TraversalLimit, f.captureProbe().uniqueSelectorsOnVerifiedRoot(f.identity, f.root, listOf(selector), budget = CaptureRoundBudget({ 0 }, 250, 1)))
    }
}
