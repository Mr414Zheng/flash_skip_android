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
class AccessibilityPreClickRecoveryTest {
    @Test fun ac02_completeSnapshotRecoversExplicitMatchingHintWithinExactAgeBoundary() {
        val f = AccessibilityFixture()
        val recovery = populated(f)
        f.now = 10_000
        assertNotNull(recovery.recover(f.identity, ClickSemanticHint(setOf("跳过"))).nodes)
        f.now = 10_001
        assertEquals(SnapshotRecoveryOutcome.STALE, recovery.recover(f.identity, ClickSemanticHint(setOf("跳过"))).diagnostics.outcome)
        assertNull(recovery.freshNodes(f.identity))
    }

    @Test fun ac02_identityMismatchClearAndNegativeAgeCannotReuseSnapshot() {
        val f = AccessibilityFixture()
        val recovery = populated(f)
        assertEquals(SnapshotRecoveryOutcome.IDENTITY_MISMATCH, recovery.recover(f.identity.copy(windowId = 8), ClickSemanticHint(setOf("跳过"))).diagnostics.outcome)
        f.now = -1
        assertNull(recovery.recover(f.identity, ClickSemanticHint(setOf("跳过"))).nodes)
        recovery.clear()
        assertEquals(SnapshotRecoveryOutcome.NOT_AVAILABLE, recovery.recover(f.identity, ClickSemanticHint(setOf("跳过"))).diagnostics.outcome)
    }

    @Test fun policy01_missingEventSemanticsCannotRecoverTrueClickFromSnapshot() {
        val f = AccessibilityFixture()
        assertNull("事件缺少明确语义时只能保留独立候选", populated(f).recover(f.identity, ClickSemanticHint()).nodes)
    }

    @Test fun policy02_conflictingEventSemanticsCannotRecoverDismissSnapshot() {
        val f = AccessibilityFixture()
        assertNull("事件语义冲突不得升级真实点击", populated(f).recover(f.identity, ClickSemanticHint(setOf("Buy now"))).nodes)
    }

    @Test fun policy04_directedPositiveCannotFormSnapshotFromPartialDiscoveryScan() {
        val f = AccessibilityFixture()
        repeat(5) { f.add(f.root, f.node("ordinary$it")) }
        val recovery = AccessibilityPreClickRecovery({ f.now }, f.captureProbe())
        val result = recovery.refresh(f.identity, f.root, false,
            selectorsForRecheck = listOf(CapturedSelector(resourceId = f.target.viewIdResourceName)),
            budget = CaptureRoundBudget({ f.now }, 250, 5))!!
        assertFalse(result.traversalComplete)
        assertNull("定向阳性不能把局部发现提升为单次完整唯一快照", recovery.freshNodes(f.identity))
        assertEquals(SelectorPresence.UNIQUE_PRESENT, result.selectorEvidence.values.single().presence)
        assertTrue("定向阳性仍可贡献独立候选线索", result.semanticCandidates.any { candidate ->
            candidate.nodes.first().resourceId == f.target.viewIdResourceName
        })
    }

    @Test fun ac04_throttledEventsDoNotInvokeRecheckProvider() {
        val f = AccessibilityFixture()
        val recovery = AccessibilityPreClickRecovery({ f.now }, f.captureProbe())
        var calls = 0
        val provider = { calls++; emptyList<CapturedSelector>() }
        assertNotNull(recovery.refresh(f.identity, f.root, false, recheckSelectorProvider = provider))
        f.now = 199
        assertNull(recovery.refresh(f.identity, f.root, false, recheckSelectorProvider = provider))
        assertEquals(1, calls)
        f.now = 200
        assertNotNull(recovery.refresh(f.identity, f.root, false, recheckSelectorProvider = provider))
        assertEquals(2, calls)
    }

    @Test fun ac02_lossyDuplicateSelectorScanCannotFormSnapshotFromDirectedPositive() {
        val f = AccessibilityFixture()
        f.target.text = null
        f.add(f.root, f.node("skip", actionable = true))
        val recovery = AccessibilityPreClickRecovery({ f.now }, f.captureProbe())
        val result = recovery.refresh(f.identity, f.root, false,
            selectorsForRecheck = listOf(CapturedSelector(resourceId = f.target.viewIdResourceName)))!!
        assertTrue(result.traversalComplete)
        assertFalse("完整树中重复结构被候选去重省略，集合有损", result.semanticCandidatesComplete)
        assertEquals(SelectorPresence.UNIQUE_PRESENT, result.selectorEvidence.values.single().presence)
        assertNull("定向阳性不能掩盖树扫描中未证明唯一的竞争节点", recovery.freshNodes(f.identity))
    }

    private fun populated(f: AccessibilityFixture) = AccessibilityPreClickRecovery({ f.now }, f.captureProbe()).also {
        assertNotNull(it.refresh(f.identity, f.root, false))
        assertNotNull(it.freshNodes(f.identity))
    }
}
