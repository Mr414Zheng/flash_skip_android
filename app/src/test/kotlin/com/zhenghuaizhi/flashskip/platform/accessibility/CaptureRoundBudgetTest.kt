package com.zhenghuaizhi.flashskip.platform.accessibility

import com.zhenghuaizhi.flashskip.domain.model.TraversalLimitReason
import org.junit.Assert.*
import org.junit.Test

class CaptureRoundBudgetTest {
    @Test fun ac04_nodeLimitRejectsNextReadWithoutIncreasingAccounting() {
        val budget = CaptureRoundBudget({ 0 }, 100, 2)
        assertNull(budget.tryVisit()); assertNull(budget.tryVisit())
        assertEquals(TraversalLimitReason.NODE_COUNT, budget.tryVisit())
        assertEquals(2, budget.nodeReadCount)
    }

    @Test fun ac04_exactDeadlineRejectsReadsAndOverridesNodeLimit() {
        var now = 99L
        val budget = CaptureRoundBudget({ now }, 100, 1)
        assertNull(budget.tryVisit())
        now = 100
        assertTrue(budget.expired())
        assertEquals(TraversalLimitReason.TIME, budget.tryVisit())
        assertEquals(1, budget.nodeReadCount)
    }

    @Test fun ac04_childSliceConsumesParentButLeavesRemainingDiscoveryBudget() {
        val parent = CaptureRoundBudget({ 0 }, 250, 5)
        val child = parent.childSlice(2, 80)
        assertNull(child.tryVisit()); assertNull(child.tryVisit())
        assertEquals(TraversalLimitReason.NODE_COUNT, child.tryVisit())
        assertEquals(2, parent.nodeReadCount)
        assertEquals(3, parent.remainingNodeReads)
        assertNull(parent.tryVisit())
    }

    @Test fun ac04_parentDeadlineAndResolverDeadlineConstrainAllSlices() {
        var now = 0L
        val budget = CaptureRoundBudget.create({ now }, 50)
        assertEquals(50, budget.deadlineAtElapsedMs)
        val child = budget.childSlice(100, 80)
        now = 50
        assertEquals(TraversalLimitReason.TIME, child.tryVisit())
        assertEquals(0, budget.nodeReadCount)
    }
}
