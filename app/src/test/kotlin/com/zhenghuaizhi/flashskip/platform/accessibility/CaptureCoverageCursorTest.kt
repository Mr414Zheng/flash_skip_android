package com.zhenghuaizhi.flashskip.platform.accessibility

import com.zhenghuaizhi.flashskip.domain.model.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], shadows = [FaultableAccessibilityNodeShadow::class, CopyingAccessibilityServiceShadow::class])
class CaptureCoverageCursorTest {
    @Test fun ac04_completeSingleTraversalRecordsAllNodes() {
        val f = AccessibilityFixture()
        val seen = mutableListOf<String>()
        val result = CaptureCoverageCursor().traverse(f.identity, f.root, budget()) { node, _ -> seen += node.viewIdResourceName!!; true }
        assertTrue(result.complete)
        assertEquals(2, result.visited)
        assertEquals(setOf(f.root.viewIdResourceName, f.target.viewIdResourceName), seen.toSet())
    }

    @Test fun ac04_finishedContinuationIsNeverCompleteWindowEvidence() {
        val f = AccessibilityFixture()
        repeat(3) { f.add(f.root, f.node("extra$it")) }
        val cursor = CaptureCoverageCursor()
        val first = cursor.traverse(f.identity, f.root, budget(2), true) { _, _ -> true }
        assertEquals(TraversalLimitReason.NODE_COUNT, first.limitReason)
        val next = cursor.traverse(f.identity, f.root, budget(), true) { _, _ -> true }
        assertTrue(next.resumed)
        assertFalse(next.complete)
        assertEquals(0, next.pendingBranchCount)
        assertTrue(cursor.traverse(f.identity, f.root, budget(), true) { _, _ -> true }.complete)
    }

    @Test fun ac04_unreadableChildAndForeignPackagePreventCoverage() {
        val f = AccessibilityFixture()
        FaultableAccessibilityNodeShadow.unreadableChildren += f.root.viewIdResourceName to 0
        val missing = CaptureCoverageCursor().traverse(f.identity, f.root, budget()) { _, _ -> true }
        assertFalse(missing.complete)
        assertEquals(CaptureScanUnavailableReason.CHILD_UNREADABLE, missing.unavailableReason)
        FaultableAccessibilityNodeShadow.unreadableChildren.clear()
        f.add(f.root, f.node("foreign").also { it.packageName = "other" })
        val foreign = CaptureCoverageCursor().traverse(f.identity, f.root, budget()) { _, _ -> true }
        assertFalse(foreign.complete)
        assertEquals(CaptureScanUnavailableReason.IDENTITY_CHANGED, foreign.unavailableReason)
    }

    @Test fun ac04_changedTreeResetsContinuationBeforeScanningNewStructure() {
        val f = AccessibilityFixture()
        val cursor = CaptureCoverageCursor()
        cursor.traverse(f.identity, f.root, budget(1), true) { _, _ -> true }
        f.add(f.root, f.node("new"))
        val result = cursor.traverse(f.identity, f.root, budget(), true) { _, _ -> true }
        assertTrue(result.cursorReset)
        assertFalse(result.resumed)
        assertEquals(3, result.visited)
    }

    @Test fun ac04_depthLimitAndCallbackStopDoNotProveCompleteCoverage() {
        val f = AccessibilityFixture()
        var parent = f.root
        repeat(41) { i -> val child = f.node("deep$i"); f.add(parent, child); parent = child }
        val deep = CaptureCoverageCursor().traverse(f.identity, f.root, budget()) { _, _ -> true }
        assertFalse(deep.complete)
        assertEquals(TraversalLimitReason.DEPTH, deep.limitReason)
        assertFalse(CaptureCoverageCursor().traverse(f.identity, f.root, budget()) { _, _ -> false }.complete)
    }

    private fun budget(reads: Int = 500) = CaptureRoundBudget({ 0 }, 250, reads)
}
