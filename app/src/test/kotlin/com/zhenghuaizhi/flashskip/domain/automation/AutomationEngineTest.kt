package com.zhenghuaizhi.flashskip.domain.automation

import com.zhenghuaizhi.flashskip.domain.model.ActionMethod
import com.zhenghuaizhi.flashskip.domain.model.ActionOutcome
import com.zhenghuaizhi.flashskip.domain.model.ActionReason
import com.zhenghuaizhi.flashskip.domain.model.ActionRecord
import com.zhenghuaizhi.flashskip.domain.model.AdRule
import com.zhenghuaizhi.flashskip.domain.model.NodeSelector
import com.zhenghuaizhi.flashskip.domain.model.TargetAppId
import com.zhenghuaizhi.flashskip.domain.ports.ClickResult
import com.zhenghuaizhi.flashskip.domain.ports.AutomationTraceEvent
import com.zhenghuaizhi.flashskip.domain.ports.AutomationTraceSink
import com.zhenghuaizhi.flashskip.domain.ports.AutomationTraceStage
import com.zhenghuaizhi.flashskip.domain.ports.AutomationTraceResult
import com.zhenghuaizhi.flashskip.domain.ports.AutomationStopReason
import com.zhenghuaizhi.flashskip.domain.model.TextCondition
import com.zhenghuaizhi.flashskip.domain.ports.FlashSkipPreferences
import com.zhenghuaizhi.flashskip.domain.ports.FlashSkipStore
import com.zhenghuaizhi.flashskip.domain.ports.GestureResult
import com.zhenghuaizhi.flashskip.domain.ports.MonotonicClock
import com.zhenghuaizhi.flashskip.domain.ports.AbsenceProof
import com.zhenghuaizhi.flashskip.domain.ports.ActionTargetEvidence
import com.zhenghuaizhi.flashskip.domain.ports.NodeActionSubmission
import com.zhenghuaizhi.flashskip.domain.ports.GestureActionSubmission
import com.zhenghuaizhi.flashskip.domain.ports.TargetObservation
import com.zhenghuaizhi.flashskip.domain.model.ActionObservationStatus
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
    fun completedGestureUsesSameEvidenceStandardAndHasOneTerminalRecord() = runTest {
        val fixture = Fixture(this)
        fixture.startGesture()
        fixture.gateway.gestureCompletion.complete(GestureResult.Completed)
        runCurrent()
        assertFalse(fixture.gateway.gestureCompletion.complete(GestureResult.Cancelled))
        fixture.gateway.observationResult = TargetObservation.Absent(AbsenceProof.LOCAL_CONTAINER_REMOVED, 42)
        advanceTimeBy(1)
        fixture.engine.onContentChanged(IDENTITY, testScheduler.currentTime)
        runCurrent()
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
        assertEquals(ActionOutcome.ABANDONED, fixture.store.records.single().outcome)
        fixture.engine.close()
    }

    @Test
    fun recordWriteCancellationPropagatesAndStopsActor() = runTest {
        val fixture = Fixture(this)
        fixture.store.recordFailure = CancellationException("Storage cancelled")
        fixture.startGesture()
        fixture.gateway.gestureCompletion.complete(GestureResult.Completed)
        runCurrent()
        val idle = async { runCatching { fixture.engine.setCapturePaused(true) }.exceptionOrNull() }
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
        fixture.gateway.observationResult = TargetObservation.Absent(AbsenceProof.LOCAL_CONTAINER_REMOVED, 42)
        runCurrent()
        advanceTimeBy(1)
        fixture.engine.onContentChanged(IDENTITY, testScheduler.currentTime)
        advanceTimeBy(1_000)
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

    @Test
    fun acceptedClickWaitsForResponseAndOnlyOneSeparatelyValidatedRetry() = runTest {
        val fixture = Fixture(this, collectTrace = true)
        fixture.startClick()
        advanceTimeBy(749)
        runCurrent()
        assertEquals(1, fixture.gateway.clickCalls)
        assertTrue(fixture.store.records.isEmpty())
        advanceTimeBy(1_700)
        runCurrent()
        assertEquals(2, fixture.gateway.clickCalls)
        val record = fixture.store.records.single()
        assertEquals(ActionOutcome.ABANDONED, record.outcome)
        assertEquals(ActionMethod.NODE_CLICK, record.method)
        assertEquals(ActionObservationStatus.TARGET_PRESENT, record.observation)
        assertTrue(record.actionAccepted)
        assertEquals(listOf(2), fixture.traces.filter { it.stage == AutomationTraceStage.VERIFY_CLICK }.map { it.attempt })
        assertEquals(1, fixture.gateway.queryCalls)
        assertEquals(0, fixture.gateway.fallbackCalls)
        fixture.engine.close()
    }

    @Test
    fun insufficientEvidenceNeverRetriesActionAndUsesAtMostTwoPageFallbacks() = runTest {
        val fixture = Fixture(this, collectTrace = true)
        fixture.startClick()
        fixture.gateway.observationResult = TargetObservation.Insufficient(ActionReason.TRAVERSAL_LIMIT)
        advanceTimeBy(2_500)
        runCurrent()
        val record = fixture.store.records.single()
        assertEquals(ActionOutcome.ABANDONED, record.outcome)
        assertEquals(ActionReason.TRAVERSAL_LIMIT, record.reason)
        assertEquals(ActionObservationStatus.INSUFFICIENT, record.observation)
        assertEquals(1, fixture.gateway.clickCalls)
        assertEquals(2, fixture.gateway.fallbackCalls)
        assertTrue(fixture.gateway.observationCalls <= 6)
        assertEquals(1, fixture.traces.count { it.stage == AutomationTraceStage.FINISHED })
        fixture.engine.close()
    }

    @Test
    fun unreadableEvidenceCanRecoverButCannotCauseImmediateRetry() = runTest {
        val fixture = Fixture(this)
        fixture.startClick()
        fixture.gateway.observationResult = TargetObservation.Insufficient(ActionReason.NODE_UNAVAILABLE)
        advanceTimeBy(650)
        runCurrent()
        assertEquals(1, fixture.gateway.clickCalls)
        fixture.gateway.observationResult = TargetObservation.Present(unique = true)
        advanceTimeBy(400)
        runCurrent()
        assertEquals(1, fixture.gateway.clickCalls)
        advanceTimeBy(400)
        runCurrent()
        assertEquals(2, fixture.gateway.clickCalls)
        fixture.engine.close()
    }

    @Test
    fun localRemovalAndLaterEventConfirmWithoutRepeatingWholePageQuery() = runTest {
        val fixture = Fixture(this, collectTrace = true)
        fixture.startClick()
        fixture.gateway.observationResult = TargetObservation.Absent(AbsenceProof.LOCAL_CONTAINER_REMOVED, 42)
        advanceTimeBy(1)
        fixture.engine.onContentChanged(IDENTITY, testScheduler.currentTime)
        runCurrent()
        advanceTimeBy(800)
        runCurrent()
        val record = fixture.store.records.single()
        assertEquals(ActionOutcome.CLOSED, record.outcome)
        assertEquals(ActionObservationStatus.CONFIRMED, record.observation)
        assertEquals(1, fixture.gateway.clickCalls)
        assertEquals(1, fixture.gateway.queryCalls)
        assertEquals(2, fixture.gateway.observationCalls)
        assertEquals(0, fixture.gateway.fallbackCalls)
        assertTrue(fixture.traces.any { it.proof == AbsenceProof.LOCAL_CONTAINER_REMOVED })
        fixture.engine.close()
    }

    @Test
    fun absenceWithoutLaterPageEvidenceDoesNotConfirmClosure() = runTest {
        val fixture = Fixture(this)
        fixture.startClick()
        fixture.gateway.observationResult = TargetObservation.Absent(AbsenceProof.LOCAL_CONTAINER_REMOVED, 42)
        advanceTimeBy(2_500)
        runCurrent()
        assertEquals(ActionOutcome.ABANDONED, fixture.store.records.single().outcome)
        assertEquals(1, fixture.gateway.clickCalls)
        fixture.engine.close()
    }

    @Test
    fun pageEventProducedDuringAcceptedActionIsObservedEvenBeforeGatewayReturns() = runTest {
        val fixture = Fixture(this)
        fixture.gateway.submittedAt = 100
        fixture.startClick()
        fixture.gateway.observationResult = TargetObservation.Absent(AbsenceProof.LOCAL_CONTAINER_REMOVED, 42)
        fixture.engine.onContentChanged(IDENTITY, 110)
        runCurrent()
        advanceTimeBy(800)
        runCurrent()
        val record = fixture.store.records.single()
        assertEquals(ActionOutcome.CLOSED, record.outcome)
        assertEquals(100L, record.timing?.targetToActionMs)
        assertEquals(1, fixture.gateway.clickCalls)
        fixture.engine.close()
    }

    @Test
    fun pageBoundaryNeverCountsAsConfirmationAndKeepsAcceptedAction() = runTest {
        val fixture = Fixture(this)
        fixture.startClick()
        fixture.engine.onForegroundSignal(ForegroundSignal.OutsideTargets(testScheduler.currentTime))
        runCurrent()
        advanceTimeBy(3_000)
        runCurrent()
        val record = fixture.store.records.single()
        assertEquals(ActionOutcome.ABANDONED, record.outcome)
        assertEquals(ActionReason.PAGE_CHANGED, record.reason)
        assertEquals(ActionMethod.NODE_CLICK, record.method)
        assertTrue(record.actionAccepted)
        assertEquals(1, fixture.gateway.clickCalls)
        assertEquals(0, fixture.gateway.observationCalls)
        fixture.engine.close()
    }

    @Test
    fun closingEnginePreservesAcceptedActionExactlyOnce() = runTest {
        val fixture = Fixture(this)
        fixture.startClick()
        fixture.engine.close()
        runCurrent()
        advanceTimeBy(3_000)
        runCurrent()
        assertTrue(fixture.store.records.single().actionAccepted)
        assertEquals(ActionOutcome.ABANDONED, fixture.store.records.single().outcome)
        assertEquals(1, fixture.gateway.clickCalls)
    }

    @Test
    fun rejectedRetryCannotBeRepeatedAndUsesObservationDeadline() = runTest {
        val fixture = Fixture(this)
        fixture.startClick()
        fixture.gateway.clickResult = ClickResult.NotActionable
        advanceTimeBy(2_500)
        runCurrent()
        assertEquals(2, fixture.gateway.clickCalls)
        assertEquals(listOf(10_000L, 2_520L), fixture.gateway.clickDeadlines)
        assertEquals(ActionOutcome.ABANDONED, fixture.store.records.single().outcome)
        fixture.engine.close()
    }

    @Test
    fun expiredActionSubmissionCannotStartGestureOrObservation() = runTest {
        val fixture = Fixture(this)
        fixture.gateway.clickResult = ClickResult.Expired
        fixture.engine.onForegroundSignal(ForegroundSignal.TargetActive(IDENTITY, 0))
        runCurrent()
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(0, fixture.gateway.gestureCalls)
        assertEquals(0, fixture.gateway.observationCalls)
        assertFalse(fixture.store.records.single().actionAccepted)
        assertEquals(ActionReason.QUERY_STALE, fixture.store.records.single().reason)
        fixture.engine.close()
    }

    @Test
    fun pendingObservationCannotReadPastSessionDeadline() = runTest {
        val fixture = Fixture(this, startupDelayMs = 9_750)
        fixture.engine.onForegroundSignal(ForegroundSignal.TargetActive(IDENTITY, 0))
        fixture.gateway.clickResult = ClickResult.Clicked
        runCurrent()
        advanceTimeBy(11_000)
        runCurrent()
        assertEquals(1, fixture.gateway.clickCalls)
        assertEquals(0, fixture.gateway.observationCalls)
        assertEquals(ActionOutcome.ABANDONED, fixture.store.records.single().outcome)
        assertTrue(fixture.store.records.single().actionAccepted)
        fixture.engine.close()
    }

    @Test
    fun initialUnavailableNodeDoesNotTriggerAnyActionOrFailureRecord() = runTest {
        val fixture = Fixture(this)
        fixture.gateway.queryResult = QueryResult.Unavailable
        fixture.engine.onForegroundSignal(ForegroundSignal.TargetActive(IDENTITY, 0))
        runCurrent()
        advanceTimeBy(11_000)
        runCurrent()
        assertEquals(0, fixture.gateway.clickCalls)
        assertTrue(fixture.store.records.isEmpty())
        fixture.engine.close()
    }

    @Test
    fun observationEventsCoalesceAndCannotKeepPostponingChecks() = runTest {
        val fixture = Fixture(this)
        fixture.startClick()
        repeat(30) {
            advanceTimeBy(20)
            fixture.engine.onContentChanged(IDENTITY, testScheduler.currentTime)
            runCurrent()
        }
        assertTrue(fixture.gateway.observationCalls in 1..4)
        assertEquals(1, fixture.gateway.clickCalls)
        fixture.engine.close()
    }

    @Test
    fun gestureCompletionAloneCannotCountAsClosed() = runTest {
        val fixture = Fixture(this)
        fixture.startGesture()
        fixture.gateway.gestureCompletion.complete(GestureResult.Completed)
        runCurrent()
        assertTrue(fixture.store.records.isEmpty())
        fixture.gateway.observationResult = TargetObservation.Insufficient(ActionReason.NODE_UNAVAILABLE)
        advanceTimeBy(2_500)
        runCurrent()
        val record = fixture.store.records.single()
        assertEquals(ActionOutcome.ABANDONED, record.outcome)
        assertEquals(ActionMethod.CENTER_GESTURE, record.method)
        assertTrue(record.actionAccepted)
        assertEquals(1, fixture.gateway.gestureCalls)
        assertEquals(1, fixture.gateway.clickCalls)
        fixture.engine.close()
    }

    @Test
    fun traceNeverCopiesSelectorTextOrDescriptionAndPauseIsExplicit() = runTest {
        val fixture = Fixture(this, collectTrace = true, selector = NodeSelector(
            resourceId = "test.app:id/private-resource", text = TextCondition.Exact("private-page-text"),
            contentDescription = "private-description", ancestorResourceId = "test.app:id/private-parent",
            ancestorDistance = 1, actionAncestorDistance = 1,
        ))
        fixture.gateway.clickResult = ClickResult.Clicked
        fixture.engine.onForegroundSignal(ForegroundSignal.TargetActive(IDENTITY, 0))
        runCurrent()
        advanceTimeBy(120)
        runCurrent()
        fixture.engine.setCapturePaused(true)
        assertFalse(fixture.traces.joinToString().contains("private-"))
        val click = fixture.traces.single { it.stage == AutomationTraceStage.NODE_CLICK }
        assertEquals(0, click.selectorIndex)
        assertEquals(1, click.actionAncestorDistance)
        assertTrue(fixture.traces.any { it.stopReason == AutomationStopReason.CAPTURE_PAUSED })
        fixture.engine.close()
    }

    @Test
    fun failingDiagnosticSinkCannotStopAutomationOrChangeItsResult() = runTest {
        val fixture = Fixture(this, collectTrace = true, traceFailure = true)
        fixture.gateway.clickResult = ClickResult.Clicked
        fixture.engine.onForegroundSignal(ForegroundSignal.TargetActive(IDENTITY, 0))
        runCurrent()
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(ActionReason.CLICK_UNCONFIRMED, fixture.store.records.single().reason)
        assertTrue(fixture.failures.isEmpty())
        fixture.engine.close()
    }

    @Test
    fun disabledTargetAndActivityMismatchExplainWhyNoQueryOrClickOccurs() = runTest {
        val disabled = Fixture(this, collectTrace = true)
        disabled.store.enabled = false
        disabled.engine.onForegroundSignal(ForegroundSignal.TargetActive(IDENTITY, 0))
        runCurrent()
        assertEquals(0, disabled.gateway.queryCalls)
        assertEquals(AutomationTraceStage.FOREGROUND_SKIPPED, disabled.traces.single().stage)
        assertEquals(AutomationStopReason.APP_DISABLED, disabled.traces.single().stopReason)
        disabled.engine.close()

        val mismatch = Fixture(this, collectTrace = true)
        mismatch.engine.onForegroundSignal(ForegroundSignal.TargetActive(
            IDENTITY.copy(activityName = "test.app.OtherActivity"), testScheduler.currentTime,
        ))
        runCurrent()
        advanceTimeBy(120)
        runCurrent()
        assertEquals(0, mismatch.gateway.queryCalls)
        assertEquals(0, mismatch.gateway.clickCalls)
        assertEquals(AutomationStopReason.ACTIVITY_MISMATCH, mismatch.traces.last().stopReason)
        assertEquals(AutomationTraceStage.RULE_SKIPPED, mismatch.traces.last().stage)
        mismatch.engine.close()
    }

    private class Fixture(
        private val testScope: TestScope,
        startupDelayMs: Long = 0,
        collectTrace: Boolean = false,
        selector: NodeSelector = NodeSelector(resourceId = "test.app:id/skip"),
        traceFailure: Boolean = false,
    ) {
        val traces = mutableListOf<AutomationTraceEvent>()
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
                                selectors = listOf(selector),
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
            traceSink = object : AutomationTraceSink {
                override val enabled = collectTrace
                override fun record(event: AutomationTraceEvent) {
                    if (traceFailure) error("diagnostic receiver unavailable")
                    traces += event
                }
            },
        )

        fun startClick() {
            gateway.clickResult = ClickResult.Clicked
            engine.onForegroundSignal(ForegroundSignal.TargetActive(IDENTITY, testScope.testScheduler.currentTime))
            testScope.runCurrent()
            testScope.advanceTimeBy(120)
            testScope.runCurrent()
            assertEquals(1, gateway.clickCalls)
        }

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
        val clickDeadlines = mutableListOf<Long>()
        var submittedAt: Long? = null
        var clickResult: ClickResult = ClickResult.NotSupported
        var queryResult: QueryResult = QueryResult.Unique
        var pendingQuery: CompletableDeferred<QueryResult>? = null
        var gestureWaitCancelled = false

        var observationResult: TargetObservation = TargetObservation.Present(unique = true)
        var observationCalls = 0
        var fallbackCalls = 0
        override suspend fun observeAction(
            identity: PageIdentity, evidence: ActionTargetEvidence, allowPageFallback: Boolean,
        ): TargetObservation {
            observationCalls++
            if (allowPageFallback) fallbackCalls++
            return observationResult
        }
        override suspend fun submitClick(
            identity: PageIdentity, selector: NodeSelector, expectedTarget: ActionTargetEvidence?,
            deadlineElapsedMs: Long,
        ): NodeActionSubmission {
            clickDeadlines += deadlineElapsedMs
            val result = click(identity, selector)
            return NodeActionSubmission(result, if (result == ClickResult.Clicked) ActionTargetEvidence(identity, selector, 1) else null, submittedAt)
        }
        override suspend fun submitGesture(
            identity: PageIdentity, selector: NodeSelector, deadlineElapsedMs: Long,
        ): GestureActionSubmission {
            val result = gestureAtCenter(identity, selector, deadlineElapsedMs)
            return GestureActionSubmission(result, if (result == GestureResult.Completed || result == GestureResult.Accepted)
                ActionTargetEvidence(identity, selector, 1) else null)
        }

        override suspend fun query(identity: PageIdentity, selector: NodeSelector): QueryResult {
            queryCalls++
            return pendingQuery?.await() ?: queryResult
        }

        override suspend fun click(identity: PageIdentity, selector: NodeSelector): ClickResult {
            clickCalls++
            return clickResult
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
