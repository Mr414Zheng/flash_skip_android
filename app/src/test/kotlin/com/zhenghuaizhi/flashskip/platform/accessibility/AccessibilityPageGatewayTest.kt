package com.zhenghuaizhi.flashskip.platform.accessibility

import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.zhenghuaizhi.flashskip.domain.model.ActionReason
import com.zhenghuaizhi.flashskip.domain.ports.AbsenceProof
import com.zhenghuaizhi.flashskip.domain.ports.ClickResult
import com.zhenghuaizhi.flashskip.domain.ports.GestureResult
import com.zhenghuaizhi.flashskip.domain.ports.QueryResult
import com.zhenghuaizhi.flashskip.domain.ports.TargetObservation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], shadows = [FaultableAccessibilityNodeShadow::class, CopyingAccessibilityServiceShadow::class])
class AccessibilityPageGatewayTest {
    @Test fun ac04_clickSubmitsActionAndReturnsEvidenceWithoutClaimingClosed() = runTest {
        val f = AccessibilityFixture()
        assertEquals(QueryResult.Unique, f.gateway.query(f.identity, f.selector))
        val result = f.gateway.submitClick(f.identity, f.selector, deadlineElapsedMs = 100)
        assertEquals(ClickResult.Clicked, result.result)
        assertNotNull(result.evidence)
        assertEquals(f.identity, result.evidence!!.identity)
        assertEquals(0L, result.submittedAtElapsedMs)
        assertEquals(listOf(AccessibilityNodeInfo.ACTION_CLICK), shadowOf(f.target).performedActions)
    }

    @Test fun ac02_ac04_wrongIdentityPackageOrWindowCannotQueryOrClick() = runTest {
        val f = AccessibilityFixture()
        f.currentIdentity = f.identity.copy(windowId = 8)
        assertEquals(QueryResult.PageChanged, f.gateway.query(f.identity, f.selector))
        assertEquals(ClickResult.PageChanged, f.gateway.submitClick(f.identity, f.selector).result)
        f.currentIdentity = f.identity
        f.targets = emptySet()
        assertEquals(QueryResult.PageChanged, f.gateway.query(f.identity, f.selector))
        f.targets = setOf(f.identity.packageName)
        shadowOf(f.window).setActive(false)
        assertEquals(QueryResult.PageChanged, f.gateway.query(f.identity, f.selector))
        assertTrue(shadowOf(f.target).performedActions.isEmpty())
    }

    @Test fun ac02_duplicateWindowMetadataAndUnreadableRootAreRejected() = runTest {
        val f = AccessibilityFixture()
        f.serviceShadow.setWindows(listOf(f.window, f.window))
        assertEquals(QueryResult.PageChanged, f.gateway.query(f.identity, f.selector))
        f.serviceShadow.setWindows(listOf(f.window))
        shadowOf(f.root).setRefreshReturnValue(false)
        assertEquals(QueryResult.Unavailable, f.gateway.query(f.identity, f.selector))
        assertTrue(shadowOf(f.target).performedActions.isEmpty())
    }

    @Test fun ac04_notActionableAndUnsupportedAreDistinct() = runTest {
        val f = AccessibilityFixture()
        f.target.isClickable = false
        assertEquals(ClickResult.NotActionable, f.gateway.submitClick(f.identity, f.selector).result)
        assertTrue(shadowOf(f.target).performedActions.isEmpty())
        f.target.isClickable = true
        shadowOf(f.target).setOnPerformActionListener { _, _ -> false }
        assertEquals(ClickResult.NotSupported, f.gateway.submitClick(f.identity, f.selector).result)
        assertTrue(f.serviceShadow.gesturesDispatched.isEmpty())
    }

    @Test fun ac04_selectedChildActsOnlyOnCapturedActionAncestor() = runTest {
        val f = AccessibilityFixture()
        f.target.isClickable = false
        f.root.isClickable = true
        val result = f.gateway.submitClick(f.identity, f.selector.copy(actionAncestorDistance = 1))
        assertEquals(ClickResult.Clicked, result.result)
        assertTrue(shadowOf(f.target).performedActions.isEmpty())
        assertEquals(listOf(AccessibilityNodeInfo.ACTION_CLICK), shadowOf(f.root).performedActions)
    }

    @Test fun ac04_expectedOriginalEvidenceRejectsChangedTargetBeforeRetry() = runTest {
        val f = AccessibilityFixture()
        val first = f.gateway.submitClick(f.identity, f.selector)
        f.target.text = "Changed"
        assertEquals(ClickResult.NotActionable, f.gateway.submitClick(f.identity, f.selector, first.evidence).result)
        assertEquals(1, shadowOf(f.target).performedActions.size)
    }

    @Test fun ac04_pageChangeDuringRefreshCannotSubmitAction() = runTest {
        val f = AccessibilityFixture()
        FaultableAccessibilityNodeShadow.refreshHooks[f.target.viewIdResourceName] = {
            f.currentIdentity = f.identity.copy(windowId = 8)
            true
        }
        assertEquals(ClickResult.NotActionable, f.gateway.submitClick(f.identity, f.selector).result)
        assertTrue(shadowOf(f.target).performedActions.isEmpty())
    }

    @Test fun ac04_deadlineBeforeAndDuringScanPreventsClickAndGesture() = runTest {
        val f = AccessibilityFixture()
        f.now = 100
        assertEquals(ClickResult.Expired, f.gateway.submitClick(f.identity, f.selector, deadlineElapsedMs = 100).result)
        assertEquals(GestureResult.TimedOut, f.gateway.submitGesture(f.identity, f.selector, 100).result)
        f.now = 0
        FaultableAccessibilityNodeShadow.refreshHooks[f.target.viewIdResourceName] = { f.now = 100; true }
        assertEquals(ClickResult.Expired, f.gateway.submitClick(f.identity, f.selector, deadlineElapsedMs = 100).result)
        f.now = 0
        assertEquals(GestureResult.TimedOut, f.gateway.submitGesture(f.identity, f.selector, 100).result)
        assertTrue(shadowOf(f.target).performedActions.isEmpty())
        assertTrue(f.serviceShadow.gesturesDispatched.isEmpty())
    }

    @Test fun ac04_cancellationDuringScanPropagatesBeforeSubmission() = runTest {
        val f = AccessibilityFixture()
        var job: Job? = null
        FaultableAccessibilityNodeShadow.refreshHooks[f.target.viewIdResourceName] = { job!!.cancel(); true }
        val submission = async { f.gateway.submitClick(f.identity, f.selector) }
        job = submission
        runCurrent()
        try {
            submission.await()
            fail("提交前取消必须传播")
        } catch (_: CancellationException) {
            assertTrue(shadowOf(f.target).performedActions.isEmpty())
        }
    }

    @Test fun ac04_gestureAcceptanceReturnsImmediatelyAndCallbackCannotChangeItsResult() = runTest {
        val f = AccessibilityFixture()
        val submission = f.gateway.submitGesture(f.identity, f.selector, 100)
        assertEquals(GestureResult.Accepted, submission.result)
        assertNotNull(submission.evidence)
        val dispatch = f.serviceShadow.gesturesDispatched.single()
        dispatch.callback()!!.onCompleted(dispatch.description())
        dispatch.callback()!!.onCancelled(dispatch.description())
        assertEquals(GestureResult.Accepted, submission.result)
        assertEquals(1, f.serviceShadow.gesturesDispatched.size)
    }

    @Test fun ac04_rejectedGestureHasNoAcceptedEvidence() = runTest {
        val f = AccessibilityFixture()
        f.serviceShadow.setCanDispatchGestures(false)
        val result = f.gateway.submitGesture(f.identity, f.selector, 100)
        assertEquals(GestureResult.Rejected, result.result)
        assertNull(result.evidence)
        assertNull(result.submittedAtElapsedMs)
    }

    @Test fun ac04_waitingGestureDistinguishesCompletedAndCancelledCallbacks() = runTest {
        val f = AccessibilityFixture()
        val completed = async { f.gateway.gestureAtCenter(f.identity, f.selector, 100) }
        runCurrent()
        assertFalse(completed.isCompleted)
        val first = f.serviceShadow.gesturesDispatched.last()
        first.callback()!!.onCompleted(first.description())
        assertEquals(GestureResult.Completed, completed.await())
        val cancelled = async { f.gateway.gestureAtCenter(f.identity, f.selector, 100) }
        runCurrent()
        val second = f.serviceShadow.gesturesDispatched.last()
        second.callback()!!.onCancelled(second.description())
        assertEquals(GestureResult.Cancelled, cancelled.await())
    }

    @Test fun ac04_cancelledWaitIgnoresLateGestureCompletion() = runTest {
        val f = AccessibilityFixture()
        val result = async { f.gateway.gestureAtCenter(f.identity, f.selector, 100) }
        runCurrent()
        val dispatch = f.serviceShadow.gesturesDispatched.single()
        result.cancel()
        runCurrent()
        dispatch.callback()!!.onCompleted(dispatch.description())
        assertTrue(result.isCancelled)
        assertEquals(1, f.serviceShadow.gesturesDispatched.size)
    }

    @Test fun ac05_windowChangeAloneIsInsufficientButVerifiedRemovalHasSpecificProof() = runTest {
        val f = AccessibilityFixture()
        val evidence = f.gateway.submitClick(f.identity, f.selector).evidence!!
        val nextWindow = AccessibilityWindowInfo.obtain().also {
            shadowOf(it).setId(8)
            shadowOf(it).setType(AccessibilityWindowInfo.TYPE_APPLICATION)
            shadowOf(it).setActive(true)
        }
        val next = f.identity.copy(windowId = 8)
        f.currentIdentity = next
        val page = f.node("home")
        f.install(page, nextWindow)
        FaultableAccessibilityNodeShadow.index.clear()
        assertTrue(f.gateway.observeAction(next, evidence, false) is TargetObservation.Insufficient)
        val confirmedAbsence = f.gateway.observeAction(next, evidence, true)
        assertTrue(confirmedAbsence is TargetObservation.Absent)
        assertEquals(AbsenceProof.ORIGINAL_WINDOW_REMOVED, (confirmedAbsence as TargetObservation.Absent).proof)
        f.currentIdentity = next.copy(activityName = "OtherActivity")
        assertEquals(TargetObservation.Insufficient(ActionReason.PAGE_CHANGED), f.gateway.observeAction(f.currentIdentity!!, evidence, true))
    }

    @Test fun ac05_replacementWindowStillContainingTargetCannotProveClose() = runTest {
        val f = AccessibilityFixture()
        val evidence = f.gateway.submitClick(f.identity, f.selector).evidence!!
        val nextWindow = AccessibilityWindowInfo.obtain().also {
            shadowOf(it).setId(8)
            shadowOf(it).setType(AccessibilityWindowInfo.TYPE_APPLICATION)
            shadowOf(it).setActive(true)
        }
        val next = f.identity.copy(windowId = 8)
        f.currentIdentity = next
        shadowOf(f.target).setAccessibilityWindowInfo(nextWindow)
        f.install(f.root, nextWindow)
        assertEquals(TargetObservation.Present(true), f.gateway.observeAction(next, evidence, true))
    }
}
