package com.zhenghuaizhi.flashskip.domain.automation

import com.zhenghuaizhi.flashskip.domain.model.ActionOutcome
import com.zhenghuaizhi.flashskip.domain.model.ActionReason
import com.zhenghuaizhi.flashskip.domain.model.NodeSelector
import com.zhenghuaizhi.flashskip.domain.ports.ClickResult
import com.zhenghuaizhi.flashskip.domain.ports.GestureResult
import com.zhenghuaizhi.flashskip.domain.ports.QueryResult
import com.zhenghuaizhi.flashskip.testing.AutomationFixture
import com.zhenghuaizhi.flashskip.testing.TARGET
import com.zhenghuaizhi.flashskip.testing.executableRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AutomationEngineActionTest {
    @Test fun ac02_ambiguityOrPageChangeStopsWithoutActionAndDoesNotRearm() = runTest {
        listOf(QueryResult.Ambiguous, QueryResult.PageChanged).forEach { result ->
            val f = AutomationFixture(this)
            f.gateway.onQuery = { _, _ -> result }
            f.enter()
            f.advance(120)
            assertTrue(f.gateway.clicks.isEmpty())
            assertEquals(ActionOutcome.ABANDONED, f.records.single().outcome)
            assertFalse(f.records.single().actionAccepted)
            f.gateway.onQuery = { _, _ -> QueryResult.Unique }
            f.content()
            f.advance(120)
            assertTrue(f.gateway.clicks.isEmpty())
        }
    }

    @Test fun ac02_unreadableAndTraversalLimitedSelectorsAllowOtherSelectorsAndRetry() = runTest {
        listOf(QueryResult.Unavailable, QueryResult.TraversalLimit).forEach { result ->
            val rule = executableRule().copy(selectors = listOf(NodeSelector(resourceId = "app:id/first"), NodeSelector(resourceId = "app:id/second")))
            val f = AutomationFixture(this, rules = listOf(rule))
            f.gateway.onQuery = { _, selector -> if (selector == rule.selectors.first()) result else QueryResult.Unique }
            f.enter()
            f.advance(120)
            assertEquals(rule.selectors.last(), f.gateway.clicks.single().second)
            val retry = AutomationFixture(this)
            retry.gateway.onQuery = { _, _ -> result }
            retry.enter()
            retry.advance(120)
            retry.gateway.onQuery = { _, _ -> QueryResult.Unique }
            retry.advance(249)
            assertTrue(retry.gateway.clicks.isEmpty())
            retry.advance(1)
            assertEquals(1, retry.gateway.clicks.size)
        }
    }

    @Test fun ac04_notActionableNeverFallsBackToGestureButCanReactToLaterEvent() = runTest {
        val f = AutomationFixture(this)
        f.gateway.onClick = { _, _, _ -> ClickResult.NotActionable }
        f.enter()
        f.advance(120)
        assertTrue(f.gateway.gestures.isEmpty())
        assertTrue(f.records.isEmpty())
        f.gateway.onClick = { _, _, _ -> ClickResult.Clicked }
        f.content()
        f.advance(120)
        assertEquals(2, f.gateway.clicks.size)
        assertTrue(f.gateway.gestures.isEmpty())
    }

    @Test fun ac04_onlyUnsupportedClickFallsBackAndAcceptedOrCompletedGestureIsNotClosed() = runTest {
        listOf(GestureResult.Accepted, GestureResult.Completed).forEach { result ->
            val f = AutomationFixture(this)
            f.gateway.onClick = { _, _, _ -> ClickResult.NotSupported }
            f.gateway.onGesture = { result }
            val entered = testScheduler.currentTime
            f.enter()
            f.advance(120)
            assertEquals(listOf(entered + 1120), f.gateway.gestures)
            assertTrue(f.records.isEmpty())
            f.advance(2400)
            assertEquals(ActionOutcome.ABANDONED, f.records.single().outcome)
            assertTrue(f.records.single().actionAccepted)
            assertEquals(1, f.gateway.clicks.size)
        }
    }

    @Test fun ac04_rejectedCancelledAndTimedOutGesturesAreNeverAcceptedRecords() = runTest {
        listOf(
            GestureResult.Rejected to ActionReason.GESTURE_REJECTED,
            GestureResult.Cancelled to ActionReason.GESTURE_CANCELLED,
            GestureResult.TimedOut to ActionReason.GESTURE_TIMED_OUT,
        ).forEach { (result, reason) ->
            val f = AutomationFixture(this)
            f.gateway.onClick = { _, _, _ -> ClickResult.NotSupported }
            f.gateway.onGesture = { result }
            f.enter()
            f.advance(120)
            assertEquals(ActionOutcome.FAILED, f.records.single().outcome)
            assertEquals(reason, f.records.single().reason)
            assertFalse(f.records.single().actionAccepted)
        }
    }

    @Test fun ac04_suspendedGestureTimesOutAndLateCompletionCannotRestoreSession() = runTest {
        val f = AutomationFixture(this)
        val completion = CompletableDeferred<GestureResult>()
        f.gateway.onClick = { _, _, _ -> ClickResult.NotSupported }
        f.gateway.onGesture = { completion.await() }
        f.enter()
        f.advance(120)
        f.advance(999)
        assertTrue(f.records.isEmpty())
        f.advance(1)
        assertEquals(ActionReason.GESTURE_TIMED_OUT, f.records.single().reason)
        completion.complete(GestureResult.Completed)
        f.content()
        f.advance(500)
        assertEquals(1, f.records.size)
        assertEquals(1, f.gateway.clicks.size)
    }

    @Test fun ac07_disableWhileQuerySuspendedPreventsSubmission() = runTest {
        val f = AutomationFixture(this)
        val query = CompletableDeferred<QueryResult>()
        f.gateway.onQuery = { _, _ -> query.await() }
        f.enter()
        f.advance(120)
        f.store.setAppEnabled(TARGET, false)
        query.complete(QueryResult.Unique)
        f.advance(0)
        assertTrue(f.gateway.clicks.isEmpty())
        assertFalse(f.records.single().actionAccepted)
    }

    @Test fun ac01_queryReturningAtAbsoluteDeadlineCannotSubmit() = runTest {
        val f = AutomationFixture(this)
        val query = CompletableDeferred<QueryResult>()
        f.gateway.onQuery = { _, _ -> query.await() }
        f.enter()
        f.advance(120)
        f.advance(9880)
        query.complete(QueryResult.Unique)
        f.advance(0)
        assertTrue(f.gateway.clicks.isEmpty())
        assertEquals(ActionReason.QUERY_STALE, f.records.single().reason)
    }
}
