package com.zhenghuaizhi.flashskip.platform.accessibility

import com.zhenghuaizhi.flashskip.domain.model.ActionReason
import com.zhenghuaizhi.flashskip.domain.ports.AbsenceProof
import com.zhenghuaizhi.flashskip.domain.ports.TargetObservation
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], shadows = [FaultableAccessibilityNodeShadow::class, CopyingAccessibilityServiceShadow::class])
class AccessibilityActionEvidenceTest {
    @Test fun ac05_freshOriginalTargetIsPresentAndUnique() {
        val f = AccessibilityFixture()
        val evidence = f.evidence.capture(f.target, f.identity, f.selector)
        assertEquals(TargetObservation.Present(true), f.evidence.observe(f.root, evidence, false))
    }

    @Test fun ac05_hiddenTargetIsNotProofOfRemoval() {
        val f = AccessibilityFixture()
        val evidence = f.evidence.capture(f.target, f.identity, f.selector)
        f.target.isVisibleToUser = false
        assertTrue(f.evidence.observe(f.root, evidence, true) is TargetObservation.Insufficient)
    }

    @Test fun ac05_renamedTargetIsNotProofOfRemoval() {
        val f = AccessibilityFixture()
        val evidence = f.evidence.capture(f.target, f.identity, f.selector)
        f.target.text = "Changed label"
        assertTrue(f.evidence.observe(f.root, evidence, true) is TargetObservation.Insufficient)
    }

    @Test fun ac05_emptyIndexWithoutIndependentScanCannotProveAbsence() {
        val f = AccessibilityFixture()
        val evidence = f.evidence.capture(f.target, f.identity, f.selector)
        FaultableAccessibilityNodeShadow.index.clear()
        assertTrue(f.evidence.observe(f.root, evidence, false) is TargetObservation.Insufficient)
        assertEquals(TargetObservation.Present(true), f.evidence.observe(f.root, evidence, true))
    }

    @Test fun ac05_changedCompleteLocalRegionCanProveContainerRemoval() {
        val f = AccessibilityFixture()
        f.add(f.root, f.node("ad_content"))
        val evidence = f.evidence.capture(f.target, f.identity, f.selector)
        assertNotNull(evidence.parentContext)
        val after = f.node("root")
        f.add(after, f.node("home_content"))
        FaultableAccessibilityNodeShadow.index.clear()
        f.index(after, after)
        val observation = f.evidence.observe(after, evidence, false)
        assertTrue(observation is TargetObservation.Absent)
        assertEquals(AbsenceProof.LOCAL_CONTAINER_REMOVED, (observation as TargetObservation.Absent).proof)
    }

    @Test fun ac05_removingOnlyButtonWithoutChangingContextDoesNotProveDismissal() {
        val f = AccessibilityFixture()
        val evidence = f.evidence.capture(f.target, f.identity, f.selector)
        val after = f.node("root")
        FaultableAccessibilityNodeShadow.index.clear()
        f.index(after, after)
        assertTrue(f.evidence.observe(after, evidence, false) is TargetObservation.Insufficient)
    }

    @Test fun ac05_fullPageScanNeedsPriorContextAndCompleteAbsence() {
        val f = AccessibilityFixture()
        val evidence = f.evidence.capture(f.target, f.identity, f.selector)
        val after = f.node("new_page")
        FaultableAccessibilityNodeShadow.index.clear()
        val result = f.evidence.observe(after, evidence, true)
        assertTrue(result is TargetObservation.Absent)
        assertEquals(AbsenceProof.COMPLETE_PAGE, (result as TargetObservation.Absent).proof)
        assertTrue(f.evidence.observe(after, evidence.copy(containerContext = null, parentContext = null), true) is TargetObservation.Insufficient)
    }

    @Test fun ac05_unreadableSubtreeCannotProveAbsenceOrUniqueness() {
        val f = AccessibilityFixture()
        val evidence = f.evidence.capture(f.target, f.identity, f.selector)
        val after = f.node("after")
        f.add(after, f.node("unreadable"))
        FaultableAccessibilityNodeShadow.index.clear()
        FaultableAccessibilityNodeShadow.unreadableChildren += after.viewIdResourceName to 0
        assertEquals(TargetObservation.Insufficient(ActionReason.NODE_UNAVAILABLE), f.evidence.observe(after, evidence, true))
    }

    @Test fun ac05_incompleteLocalScanDoesNotAuthorizeRetryAsUnique() {
        val f = AccessibilityFixture()
        val evidence = f.evidence.capture(f.target, f.identity, f.selector)
        repeat(64) { f.add(f.target, f.node("child$it")) }
        assertEquals(TargetObservation.Present(false), f.evidence.observe(f.root, evidence, false))
    }

    @Test fun ac05_pageNodeDepthAndTimeLimitsRemainInsufficient() {
        val f = AccessibilityFixture()
        val evidence = f.evidence.capture(f.target, f.identity, f.selector)
        FaultableAccessibilityNodeShadow.index.clear()
        val wide = f.node("wide")
        repeat(500) { f.add(wide, f.node("wide$it")) }
        assertEquals(TargetObservation.Insufficient(ActionReason.TRAVERSAL_LIMIT), f.evidence.observe(wide, evidence, true))
        val deep = f.node("deep")
        var parent = deep
        repeat(41) { index -> val next = f.node("deep$index"); f.add(parent, next); parent = next }
        assertEquals(TargetObservation.Insufficient(ActionReason.TRAVERSAL_LIMIT), f.evidence.observe(deep, evidence, true))
        var ticks = 0L
        f.clockRead = { ticks.also { ticks += 101 } }
        assertEquals(TargetObservation.Insufficient(ActionReason.TRAVERSAL_LIMIT), f.evidence.observe(f.node("empty"), evidence, true))
    }

    @Test fun ac05_wrongWindowOrRefreshFailureCannotProveAbsence() {
        val f = AccessibilityFixture()
        val evidence = f.evidence.capture(f.target, f.identity, f.selector)
        FaultableAccessibilityNodeShadow.index.clear()
        val after = f.node("after")
        shadowOf(after).setRefreshReturnValue(false)
        assertEquals(TargetObservation.Insufficient(ActionReason.NODE_UNAVAILABLE), f.evidence.observe(after, evidence, true))
        shadowOf(after).setRefreshReturnValue(true)
        assertEquals(TargetObservation.Insufficient(ActionReason.NODE_UNAVAILABLE), f.evidence.observe(after, evidence.copy(identity = f.identity.copy(windowId = 8)), true))
    }
}
