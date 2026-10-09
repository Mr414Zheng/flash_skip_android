package com.zhenghuaizhi.flashskip.domain.automation

import com.zhenghuaizhi.flashskip.domain.model.ActionMethod
import com.zhenghuaizhi.flashskip.domain.model.ActionOutcome
import com.zhenghuaizhi.flashskip.domain.model.ActionReason
import com.zhenghuaizhi.flashskip.domain.model.ActionRecord
import com.zhenghuaizhi.flashskip.domain.model.AdRule
import com.zhenghuaizhi.flashskip.domain.model.NodeSelector
import com.zhenghuaizhi.flashskip.domain.model.TargetAppId
import com.zhenghuaizhi.flashskip.domain.ports.ClickResult
import com.zhenghuaizhi.flashskip.domain.ports.FlashSkipPreferences
import com.zhenghuaizhi.flashskip.domain.ports.FlashSkipStore
import com.zhenghuaizhi.flashskip.domain.ports.GestureResult
import com.zhenghuaizhi.flashskip.domain.ports.MonotonicClock
import com.zhenghuaizhi.flashskip.domain.ports.PageGateway
import com.zhenghuaizhi.flashskip.domain.ports.PageIdentity
import com.zhenghuaizhi.flashskip.domain.ports.QueryResult
import com.zhenghuaizhi.flashskip.domain.ports.WallClock
import com.zhenghuaizhi.flashskip.domain.rules.RuleCatalog
import com.zhenghuaizhi.flashskip.domain.rules.RuleCatalogSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AutomationEngineTest {
    @Test
    fun missingGestureCallbackTimesOutAfterOneSecondAndReleasesCommands() = runTest {
        val fixture = Fixture(this)
        fixture.startGesture()
        assertEquals(1_120L, fixture.gateway.gestureDeadline)
        val idle = async { fixture.engine.awaitIdle() }
        val paused = async { fixture.engine.setCapturePaused(true) }
        runCurrent()

        advanceTimeBy(999)
        runCurrent()
        assertFalse(idle.isCompleted)
        assertFalse(paused.isCompleted)
        assertTrue(fixture.store.records.isEmpty())

        advanceTimeBy(1)
        runCurrent()
        idle.await()
        paused.await()
        assertEquals(ActionReason.GESTURE_TIMED_OUT, fixture.store.records.single().reason)
        assertEquals(ActionOutcome.FAILED, fixture.store.records.single().outcome)
        assertTrue(fixture.gateway.gestureWaitCancelled)
        fixture.engine.close()
    }

    @Test
    fun gestureWaitUsesOnlyRemainingSessionBudget() = runTest {
        val fixture = Fixture(this, startupDelayMs = 9_750)
        fixture.startGesture(atElapsedMs = 9_750)
        assertEquals(10_000L, fixture.gateway.gestureDeadline)
        advanceTimeBy(249)
        runCurrent()
        assertTrue(fixture.store.records.isEmpty())

        advanceTimeBy(1)
        runCurrent()
        val record = fixture.store.records.single()
        assertEquals(ActionReason.GESTURE_TIMED_OUT, record.reason)
        assertEquals(10_000L, record.timing?.targetToCompletedMs)
        fixture.engine.close()
    }

    @Test
    fun lateCallbackCannotReplaceTimeoutOrRearmSameForegroundSession() = runTest {
        val fixture = Fixture(this)
        fixture.startGesture()
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(fixture.gateway.gestureCompletion.complete(GestureResult.Completed))
        fixture.engine.onContentChanged(IDENTITY, testScheduler.currentTime)
        fixture.engine.onForegroundSignal(
            ForegroundSignal.TargetActive(IDENTITY, testScheduler.currentTime),
        )
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(1, fixture.gateway.gestureCalls)
        assertEquals(ActionReason.GESTURE_TIMED_OUT, fixture.store.records.single().reason)
        fixture.engine.close()
    }

    @Test
    fun completedGestureHasOneTerminalRecord() = runTest {
        val fixture = Fixture(this)
        fixture.startGesture()
        fixture.gateway.gestureCompletion.complete(GestureResult.Completed)
        runCurrent()
        assertFalse(fixture.gateway.gestureCompletion.complete(GestureResult.Cancelled))
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(ActionOutcome.CLOSED, fixture.store.records.single().outcome)
        assertEquals(ActionMethod.CENTER_GESTURE, fixture.store.records.single().method)
        fixture.engine.close()
    }

    @Test
    fun platformRejectionAndCancellationKeepDistinctReasons() = runTest {
        for ((result, reason) in listOf(
            GestureResult.Rejected to ActionReason.GESTURE_REJECTED,
            GestureResult.Cancelled to ActionReason.GESTURE_CANCELLED,
            GestureResult.TimedOut to ActionReason.GESTURE_TIMED_OUT,
        )) {
            val fixture = Fixture(this)
            fixture.startGesture()
            fixture.gateway.gestureCompletion.complete(result)
            runCurrent()
            assertEquals(reason, fixture.store.records.single().reason)
            assertEquals(ActionOutcome.FAILED, fixture.store.records.single().outcome)
            fixture.engine.close()
            runCurrent()
        }
    }

    @Test
    fun closeCancelsQueuedWaitersAndCurrentGesture() = runTest {
        val fixture = Fixture(this)
        fixture.startGesture()
        val idle = async { runCatching { fixture.engine.awaitIdle() }.exceptionOrNull() }
        val paused = async {
            runCatching { fixture.engine.setCapturePaused(true) }.exceptionOrNull()
        }
        runCurrent()

        fixture.engine.close()
        runCurrent()
        assertTrue(idle.await() is CancellationException)
        assertTrue(paused.await() is CancellationException)
        assertTrue(fixture.gateway.gestureWaitCancelled)
        fixture.gateway.gestureCompletion.complete(GestureResult.Completed)
        runCurrent()
        assertTrue(fixture.store.records.isEmpty())
    }

    @Test
    fun closeBeforeActorStartsRejectsNewWaiters() = runTest {
        val fixture = Fixture(this)
        fixture.engine.close()
        val idle = async { runCatching { fixture.engine.awaitIdle() }.exceptionOrNull() }
        val paused = async {
            runCatching { fixture.engine.setCapturePaused(true) }.exceptionOrNull()
        }
        runCurrent()

        assertTrue(idle.await() is CancellationException)
        assertTrue(paused.await() is CancellationException)
        assertTrue(fixture.failures.isEmpty())
    }

    @Test
    fun actorFailureCancelsQueuedWaitersAndRejectsFutureCommands() = runTest {
        val fixture = Fixture(this)
        fixture.startGesture()
        val idle = async { runCatching { fixture.engine.awaitIdle() }.exceptionOrNull() }
        val paused = async {
            runCatching { fixture.engine.setCapturePaused(true) }.exceptionOrNull()
        }
        runCurrent()
        val failure = IllegalStateException("Gateway unavailable")
        fixture.gateway.gestureCompletion.completeExceptionally(failure)
        runCurrent()

        assertTrue(idle.await() is CancellationException)
        assertTrue(paused.await() is CancellationException)
        val reportedFailure = fixture.failures.single()
        assertEquals(failure::class.java, reportedFailure::class.java)
        assertEquals(failure.message, reportedFailure.message)
        assertTrue(runCatching { fixture.engine.awaitIdle() }.exceptionOrNull() is CancellationException)
        assertTrue(fixture.store.records.isEmpty())
    }

    @Test
    fun callerCancellationDoesNotCancelEngine() = runTest {
        val fixture = Fixture(this)
        fixture.startGesture()
        val idle = async { fixture.engine.awaitIdle() }
        runCurrent()
        idle.cancel()
        fixture.gateway.gestureCompletion.complete(GestureResult.Completed)
        runCurrent()

        assertTrue(idle.isCancelled)
        fixture.engine.awaitIdle()
        fixture.engine.setCapturePaused(true)
        assertEquals(ActionOutcome.CLOSED, fixture.store.records.single().outcome)
        fixture.engine.close()
    }

    @Test
    fun recordWriteCancellationPropagatesAndStopsActor() = runTest {
        val fixture = Fixture(this)
        fixture.store.recordFailure = CancellationException("Storage cancelled")
        fixture.startGesture()
        val idle = async { runCatching { fixture.engine.awaitIdle() }.exceptionOrNull() }
        runCurrent()
        fixture.gateway.gestureCompletion.complete(GestureResult.Completed)
        runCurrent()

        assertTrue(idle.await() is CancellationException)
        assertTrue(fixture.store.records.isEmpty())
        assertTrue(fixture.failures.isEmpty())
    }

    @Test
    fun closeCancelsScheduledQueries() = runTest {
        val fixture = Fixture(this)
        fixture.engine.onForegroundSignal(ForegroundSignal.TargetActive(IDENTITY, 0))
        runCurrent()
        fixture.engine.close()
        advanceTimeBy(10_000)
        runCurrent()

        assertEquals(0, fixture.gateway.gestureCalls)
        assertEquals(0, fixture.gateway.queryCalls)
        assertTrue(fixture.failures.isEmpty())
    }

    @Test
    fun fullMailboxSendersAreReleasedByClose() = runTest {
        val fixture = Fixture(this)
        fixture.startGesture()
        repeat(64) { fixture.engine.onContentChanged(IDENTITY, testScheduler.currentTime) }
        val idle = async { runCatching { fixture.engine.awaitIdle() }.exceptionOrNull() }
        val paused = async {
            runCatching { fixture.engine.setCapturePaused(true) }.exceptionOrNull()
        }
        runCurrent()
        assertFalse(idle.isCompleted)
        assertFalse(paused.isCompleted)

        fixture.engine.close()
        runCurrent()
        assertTrue(idle.await() is CancellationException)
        assertTrue(paused.await() is CancellationException)
    }

    @Test
    fun disabledTargetDoesNotQueryOrAct() = runTest {
        val fixture = Fixture(this)
        fixture.store.enabled = false
        fixture.engine.onForegroundSignal(ForegroundSignal.TargetActive(IDENTITY, 0))
        runCurrent()
        advanceTimeBy(1_000)
        fixture.engine.onContentChanged(IDENTITY, testScheduler.currentTime)
        fixture.engine.onForegroundSignal(
            ForegroundSignal.TargetActive(IDENTITY, testScheduler.currentTime),
        )
        runCurrent()

        assertEquals(0, fixture.gateway.queryCalls)
        assertEquals(0, fixture.gateway.clickCalls)
        assertEquals(0, fixture.gateway.gestureCalls)
        assertTrue(fixture.store.records.isEmpty())
        fixture.engine.close()
    }

    @Test
    fun capturePauseCancelsScheduledActionAndIgnoresEventsUntilResumed() = runTest {
        val fixture = Fixture(this)
        fixture.engine.onForegroundSignal(ForegroundSignal.TargetActive(IDENTITY, 0))
        runCurrent()
        fixture.engine.setCapturePaused(true)
        fixture.engine.onContentChanged(IDENTITY, testScheduler.currentTime)
        fixture.engine.onForegroundSignal(
            ForegroundSignal.TargetActive(IDENTITY, testScheduler.currentTime),
        )
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(0, fixture.gateway.queryCalls)
        assertEquals(0, fixture.gateway.clickCalls)
        assertEquals(0, fixture.gateway.gestureCalls)

        fixture.engine.setCapturePaused(false)
        fixture.startGesture()
        fixture.gateway.gestureCompletion.complete(GestureResult.Completed)
        runCurrent()
        assertEquals(ActionOutcome.CLOSED, fixture.store.records.single().outcome)
        fixture.engine.close()
    }

    @Test
    fun expiredSessionCannotBeRearmedBySamePackageWindowOrContentEvents() = runTest {
        val fixture = Fixture(this)
        fixture.gateway.queryResult = QueryResult.None
        fixture.engine.onForegroundSignal(ForegroundSignal.TargetActive(IDENTITY, 0))
        runCurrent()
        advanceTimeBy(120)
        runCurrent()
        assertEquals(1, fixture.gateway.queryCalls)

        advanceTimeBy(9_880)
        fixture.gateway.queryResult = QueryResult.Unique
        val nextWindow = IDENTITY.copy(windowId = 2)
        fixture.engine.onContentChanged(nextWindow, testScheduler.currentTime)
        fixture.engine.onForegroundSignal(
            ForegroundSignal.TargetActive(nextWindow, testScheduler.currentTime),
        )
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(1, fixture.gateway.queryCalls)
        assertEquals(0, fixture.gateway.clickCalls)
        assertEquals(0, fixture.gateway.gestureCalls)
        assertTrue(fixture.store.records.isEmpty())
        fixture.engine.close()
    }

    @Test
    fun ambiguousMatchAbandonsSessionWithoutAnyAction() = runTest {
        val fixture = Fixture(this)
        fixture.gateway.queryResult = QueryResult.Ambiguous
        fixture.engine.onForegroundSignal(ForegroundSignal.TargetActive(IDENTITY, 0))
        runCurrent()
        advanceTimeBy(120)
        runCurrent()
        fixture.gateway.queryResult = QueryResult.Unique
        fixture.engine.onContentChanged(IDENTITY, testScheduler.currentTime)
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(1, fixture.gateway.queryCalls)
        assertEquals(0, fixture.gateway.clickCalls)
        assertEquals(0, fixture.gateway.gestureCalls)
        assertEquals(ActionOutcome.ABANDONED, fixture.store.records.single().outcome)
        assertEquals(ActionReason.AMBIGUOUS_MATCH, fixture.store.records.single().reason)
        fixture.engine.close()
    }

    @Test
    fun mailboxOverflowDiscardsInFlightQueryAndDoesNotReplayActions() = runTest {
        val fixture = Fixture(this)
        val pendingQuery = CompletableDeferred<QueryResult>()
        fixture.gateway.pendingQuery = pendingQuery
        fixture.engine.onForegroundSignal(ForegroundSignal.TargetActive(IDENTITY, 0))
        runCurrent()
        advanceTimeBy(120)
        runCurrent()
        assertEquals(1, fixture.gateway.queryCalls)

        repeat(65) { fixture.engine.onContentChanged(IDENTITY, testScheduler.currentTime) }
        pendingQuery.complete(QueryResult.Unique)
        runCurrent()
        fixture.engine.onContentChanged(IDENTITY, testScheduler.currentTime)
        fixture.engine.onForegroundSignal(
            ForegroundSignal.TargetActive(IDENTITY, testScheduler.currentTime),
        )
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(1, fixture.gateway.queryCalls)
        assertEquals(0, fixture.gateway.clickCalls)
        assertEquals(0, fixture.gateway.gestureCalls)
        assertEquals(ActionOutcome.ABANDONED, fixture.store.records.single().outcome)
        assertEquals(ActionReason.QUERY_STALE, fixture.store.records.single().reason)
        fixture.engine.close()
    }

    private class Fixture(
        private val testScope: TestScope,
        startupDelayMs: Long = 0,
    ) {
        val store = FakeStore()
        val gateway = FakeGateway()
        val failures = mutableListOf<Throwable>()
        private val engineScope = CoroutineScope(
            testScope.backgroundScope.coroutineContext +
                SupervisorJob(testScope.backgroundScope.coroutineContext[Job]) +
                CoroutineExceptionHandler { _, failure -> failures.add(failure) },
        )
        val engine = AutomationEngine(
            catalog = object : RuleCatalog {
                override val snapshot = MutableStateFlow(
                    RuleCatalogSnapshot(
                        rules = listOf(
                            AdRule(
                                id = "skip-test",
                                targetAppId = TargetAppId(IDENTITY.packageName),
                                packageName = IDENTITY.packageName,
                                allowedActivities = setOf(requireNotNull(IDENTITY.activityName)),
                                selectors = listOf(NodeSelector(resourceId = "test.app:id/skip")),
                                startupDelayMs = startupDelayMs,
                                cooldownMs = 1_000,
                            ),
                        ),
                    ),
                )
            },
            store = store,
            pageGateway = gateway,
            monotonicClock = object : MonotonicClock {
                override fun elapsedRealtimeMs() = testScope.testScheduler.currentTime
            },
            wallClock = object : WallClock {
                override fun currentTimeMillis() = testScope.testScheduler.currentTime
            },
            scope = engineScope,
        )

        fun startGesture(atElapsedMs: Long = 120) {
            engine.onForegroundSignal(
                ForegroundSignal.TargetActive(IDENTITY, testScope.testScheduler.currentTime),
            )
            testScope.runCurrent()
            testScope.advanceTimeBy(atElapsedMs)
            testScope.runCurrent()
            assertEquals(1, gateway.gestureCalls)
        }
    }

    private class FakeGateway : PageGateway {
        val gestureCompletion = CompletableDeferred<GestureResult>()
        var gestureCalls = 0
        var gestureDeadline: Long? = null
        var queryCalls = 0
        var clickCalls = 0
        var queryResult: QueryResult = QueryResult.Unique
        var pendingQuery: CompletableDeferred<QueryResult>? = null
        var gestureWaitCancelled = false

        override suspend fun query(identity: PageIdentity, selector: NodeSelector): QueryResult {
            queryCalls++
            return pendingQuery?.await() ?: queryResult
        }

        override suspend fun click(identity: PageIdentity, selector: NodeSelector): ClickResult {
            clickCalls++
            return ClickResult.NotSupported
        }

        override suspend fun gestureAtCenter(
            identity: PageIdentity,
            selector: NodeSelector,
            deadlineElapsedMs: Long,
        ): GestureResult {
            gestureCalls++
            gestureDeadline = deadlineElapsedMs
            return try {
                gestureCompletion.await()
            } catch (cancelled: CancellationException) {
                gestureWaitCancelled = true
                throw cancelled
            }
        }
    }

    private class FakeStore : FlashSkipStore {
        override val preferences = MutableStateFlow(FlashSkipPreferences())
        val records = mutableListOf<ActionRecord>()
        var recordFailure: Exception? = null
        var enabled = true

        override suspend fun setAppEnabled(targetAppId: TargetAppId, enabled: Boolean) = Unit

        override suspend fun isAppEnabled(targetAppId: TargetAppId) = enabled

        override suspend fun appendRecord(record: ActionRecord) {
            recordFailure?.let { throw it }
            records.add(record)
        }
    }

    private companion object {
        val IDENTITY = PageIdentity("test.app", "test.app.MainActivity", 1)
    }
}
