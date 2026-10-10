package com.zhenghuaizhi.flashskip.application.rulecapture

import com.zhenghuaizhi.flashskip.domain.model.ActionRecord
import com.zhenghuaizhi.flashskip.domain.model.CaptureFailure
import com.zhenghuaizhi.flashskip.domain.model.CaptureFailureDiagnostics
import com.zhenghuaizhi.flashskip.domain.model.CapturePhase
import com.zhenghuaizhi.flashskip.domain.model.CaptureSample
import com.zhenghuaizhi.flashskip.domain.model.CaptureSession
import com.zhenghuaizhi.flashskip.domain.model.CaptureEvidence
import com.zhenghuaizhi.flashskip.domain.model.CaptureDiagnosticSummary
import com.zhenghuaizhi.flashskip.domain.model.CaptureDiagnosticTrace
import com.zhenghuaizhi.flashskip.domain.model.CapturedNode
import com.zhenghuaizhi.flashskip.domain.model.ObservedDismissCandidate
import com.zhenghuaizhi.flashskip.domain.model.RuleCaptureState
import com.zhenghuaizhi.flashskip.domain.model.TargetAppId
import com.zhenghuaizhi.flashskip.domain.model.TargetWindowDiagnostics
import com.zhenghuaizhi.flashskip.domain.ports.FlashSkipPreferences
import com.zhenghuaizhi.flashskip.domain.ports.FlashSkipStore
import com.zhenghuaizhi.flashskip.domain.ports.MonotonicClock
import com.zhenghuaizhi.flashskip.domain.ports.PageIdentity
import com.zhenghuaizhi.flashskip.domain.ports.RuleCaptureStore
import com.zhenghuaizhi.flashskip.domain.ports.WallClock
import com.zhenghuaizhi.flashskip.domain.rules.BuiltinRuleCatalog
import com.zhenghuaizhi.flashskip.domain.rules.CompositeRuleCatalog
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RuleCaptureCoordinatorTest {
    @Test
    fun numericRecommendationCannotEnterConfirmationOrCapturedPhase() = runTest {
        val fixture = fixture(observingState())
        val nodes = listOf(CapturedNode(
            resourceId = "${TARGET.packageName}:id/recommendation_count", text = "30",
            clickable = true, supportsActionClick = true, visibleToUser = true, hasUsableBounds = true,
        ))
        val candidate = candidateForClick().copy(nodes = nodes)
        assertFalse(fixture.coordinator.recordObservedCandidate(SESSION_ID, candidate))
        assertEquals(CapturePhase.OBSERVING_CLICK, fixture.capture.state.value.activeSession?.phase)
        fixture.coordinator.recordCaptured(SESSION_ID, sample().copy(nodes = nodes))
        assertEquals(CapturePhase.OBSERVING_CLICK, fixture.capture.state.value.activeSession?.phase)
        assertNull(fixture.capture.state.value.activeSession?.pendingSample)
    }

    @Test
    fun finishKeepsAutomationBlockedUntilDisableIsPersisted() = runTest {
        val fixture = fixture(capturedState())
        val releaseDisable = CompletableDeferred<Unit>()
        fixture.preferences.beforeWrite = { _, _ -> releaseDisable.await() }

        val finish = async { fixture.coordinator.finish(SESSION_ID) }
        runCurrent()
        assertTrue(fixture.capture.state.value.activeSession!!.blocksAutomation)
        assertTrue(fixture.capture.state.value.samples.isEmpty())
        assertTrue(fixture.preferences.isAppEnabled(TARGET))

        releaseDisable.complete(Unit)
        assertEquals(CaptureOperationResult.Success(), finish.await())
        assertFalse(fixture.preferences.isAppEnabled(TARGET))
        assertNull(fixture.capture.state.value.activeSession)
        assertEquals(listOf(sample()), fixture.capture.state.value.samples)
    }

    @Test
    fun disableFailureRetainsCaptureAndDoesNotSaveSample() = runTest {
        val fixture = fixture(capturedState())
        fixture.preferences.beforeWrite = { _, _ -> throw IOException("disk full") }

        assertEquals(storageFailure(), fixture.coordinator.finish(SESSION_ID))
        assertEquals(capturedState(), fixture.capture.state.value)
        assertTrue(fixture.capture.state.value.activeSession!!.blocksAutomation)
        assertTrue(fixture.preferences.isAppEnabled(TARGET))
    }

    @Test
    fun sampleWriteFailureLeavesTargetDisabledAndCanRetry() = runTest {
        val fixture = fixture(capturedState())
        fixture.capture.beforeUpdate = { throw IOException("write failed") }

        assertEquals(storageFailure(), fixture.coordinator.finish(SESSION_ID))
        assertFalse(fixture.preferences.isAppEnabled(TARGET))
        assertEquals(capturedState(), fixture.capture.state.value)

        fixture.capture.beforeUpdate = {}
        assertEquals(CaptureOperationResult.Success(), fixture.coordinator.finish(SESSION_ID))
        assertEquals(1, fixture.capture.state.value.samples.size)
    }

    @Test
    fun repeatedFinishDoesNotDisableSubsequentExplicitEnablement() = runTest {
        val fixture = fixture(capturedState())
        assertEquals(CaptureOperationResult.Success(), fixture.coordinator.finish(SESSION_ID))
        runCurrent()
        assertEquals(CaptureOperationResult.Success(), fixture.coordinator.setAppEnabled(TARGET, true))

        assertEquals(CaptureOperationResult.Stale, fixture.coordinator.finish(SESSION_ID))
        assertTrue(fixture.preferences.isAppEnabled(TARGET))
        assertEquals(1, fixture.capture.state.value.samples.size)
    }

    @Test
    fun finishSerializesWithCancelAndDuplicateFinish() = runTest {
        val fixture = fixture(capturedState())
        val releaseDisable = CompletableDeferred<Unit>()
        fixture.preferences.beforeWrite = { _, _ -> releaseDisable.await() }
        val finish = async { fixture.coordinator.finish(SESSION_ID) }
        runCurrent()
        val cancel = async { fixture.coordinator.cancel(SESSION_ID) }
        val duplicate = async { fixture.coordinator.finish(SESSION_ID) }
        runCurrent()
        assertFalse(cancel.isCompleted)
        assertFalse(duplicate.isCompleted)

        releaseDisable.complete(Unit)
        assertEquals(CaptureOperationResult.Success(), finish.await())
        assertEquals(CaptureOperationResult.Stale, cancel.await())
        assertEquals(CaptureOperationResult.Stale, duplicate.await())
        assertEquals(1, fixture.capture.state.value.samples.size)
    }

    @Test
    fun cancelBeforeFinishDoesNotSaveOrDisableExistingRule() = runTest {
        val fixture = fixture(capturedState())

        assertEquals(CaptureOperationResult.Success(), fixture.coordinator.cancel(SESSION_ID))
        assertEquals(CaptureOperationResult.Stale, fixture.coordinator.finish(SESSION_ID))
        assertTrue(fixture.capture.state.value.samples.isEmpty())
        assertTrue(fixture.preferences.isAppEnabled(TARGET))
    }

    @Test
    fun oldCallbacksAndCancelCannotAffectNewSession() = runTest {
        val fixture = fixture(capturedState())
        fixture.coordinator.cancel(SESSION_ID)
        val next = fixture.coordinator.requestStart() as CaptureOperationResult.Success
        val sessionId = requireNotNull(next.sessionId)
        val before = fixture.capture.state.value

        fixture.coordinator.markConfigured(SESSION_ID)
        fixture.coordinator.recordCaptured(SESSION_ID, sample())
        fixture.coordinator.fail(SESSION_ID, CaptureFailure.SERVICE_UNAVAILABLE)
        assertEquals(CaptureOperationResult.Stale, fixture.coordinator.cancel(SESSION_ID))
        assertEquals(CaptureOperationResult.Stale, fixture.coordinator.finish(SESSION_ID))
        assertEquals(before, fixture.capture.state.value)
        assertEquals(sessionId, fixture.capture.state.value.activeSession!!.id)
    }

    @Test
    fun duplicateStartKeepsCurrentSession() = runTest {
        val fixture = fixture(RuleCaptureState())
        val first = fixture.coordinator.requestStart() as CaptureOperationResult.Success
        assertEquals(CaptureOperationResult.Stale, fixture.coordinator.requestStart())
        assertEquals(first.sessionId, fixture.capture.state.value.activeSession!!.id)
    }

    @Test
    fun onlyARealDepartureCanReconcileWaitingSessionAndRepeatResumeIsHarmless() = runTest {
        val initial = waitingState()
        val fixture = fixture(initial)

        assertEquals(CaptureOperationResult.Stale, fixture.coordinator.reconcileReturn())
        assertEquals(initial, fixture.capture.state.value)
        assertEquals(CaptureOperationResult.Stale, fixture.coordinator.reconcileReturn(SESSION_ID))
        assertEquals(initial, fixture.capture.state.value)

        assertEquals(CaptureOperationResult.Success(SESSION_ID), fixture.coordinator.markLeftFlashSkip(SESSION_ID))
        val marked = fixture.capture.state.value
        assertEquals(1_000L, marked.activeSession?.leftFlashSkipAtEpochMs)
        assertEquals(CaptureOperationResult.Success(SESSION_ID), fixture.coordinator.reconcileReturn(SESSION_ID))
        assertEquals(CaptureFailure.RETURNED_BEFORE_TARGET, fixture.capture.state.value.activeSession?.failure)
        assertEquals(CapturePhase.FAILED, fixture.capture.state.value.activeSession?.phase)
        assertNull(fixture.capture.state.value.activeSession?.leftFlashSkipAtEpochMs)

        assertEquals(CaptureOperationResult.Stale, fixture.coordinator.reconcileReturn(SESSION_ID))
        assertEquals(CaptureFailure.RETURNED_BEFORE_TARGET, fixture.capture.state.value.activeSession?.failure)
    }

    @Test
    fun returnedAfterTargetWithoutEvidenceKeepsServiceDiagnostics() = runTest {
        val fixture = fixture(observingState())
        val trace = CaptureDiagnosticTrace.snapshot(
            summary = CaptureDiagnosticSummary(scanCount = 2, maxEffectiveCandidateCount = 3),
            records = emptyList(),
        )
        val failureDiagnostics = CaptureFailureDiagnostics(
            sourceAvailable = false,
            eventTextCount = 0,
            eventHasContentDescription = false,
            selectorCandidateCount = 2,
            sessionTrace = trace,
        )
        val windowDiagnostics = TargetWindowDiagnostics(viewClickedEventCount = 1)
        val oldProvider = fixture.coordinator.registerReturnDiagnosticsProvider {
            CaptureDiagnosticSnapshot(failureDiagnostics, windowDiagnostics)
        }
        val replacementProvider = fixture.coordinator.registerReturnDiagnosticsProvider {
            CaptureDiagnosticSnapshot(
                failure = failureDiagnostics.copy(selectorCandidateCount = 3),
                targetWindow = windowDiagnostics.copy(viewClickedEventCount = 4),
            )
        }
        fixture.coordinator.unregisterReturnDiagnosticsProvider(oldProvider)

        fixture.coordinator.markLeftFlashSkip(SESSION_ID)
        assertEquals(CaptureOperationResult.Success(SESSION_ID), fixture.coordinator.reconcileReturn(SESSION_ID))
        val result = requireNotNull(fixture.capture.state.value.activeSession)
        assertEquals(CaptureFailure.DISMISS_NOT_CAPTURED, result.failure)
        assertEquals(3, result.failureDiagnostics?.selectorCandidateCount)
        assertEquals(trace, result.failureDiagnostics?.sessionTrace)
        assertEquals(4, result.targetWindowDiagnostics?.viewClickedEventCount)

        fixture.coordinator.unregisterReturnDiagnosticsProvider(replacementProvider)
    }

    @Test
    fun returnDoesNotDropTraceAlreadySavedOnTheActiveSession() = runTest {
        val savedTrace = CaptureDiagnosticTrace.snapshot(
            summary = CaptureDiagnosticSummary(scanCount = 1, maxEffectiveCandidateCount = 2),
            records = emptyList(),
        )
        val initial = observingState().let { state ->
            state.copy(
                activeSession = requireNotNull(state.activeSession).copy(
                    failureDiagnostics = CaptureFailureDiagnostics(
                        sourceAvailable = false,
                        eventTextCount = 0,
                        eventHasContentDescription = false,
                        sessionTrace = savedTrace,
                    ),
                ),
            )
        }
        val fixture = fixture(initial)
        val provider = fixture.coordinator.registerReturnDiagnosticsProvider {
            CaptureDiagnosticSnapshot(
                failure = CaptureFailureDiagnostics(
                    sourceAvailable = false,
                    eventTextCount = 0,
                    eventHasContentDescription = false,
                ),
                targetWindow = null,
            )
        }

        fixture.coordinator.markLeftFlashSkip(SESSION_ID)
        fixture.coordinator.reconcileReturn(SESSION_ID)

        assertEquals(
            savedTrace,
            fixture.capture.state.value.activeSession?.failureDiagnostics?.sessionTrace,
        )
        fixture.coordinator.unregisterReturnDiagnosticsProvider(provider)
    }

    @Test
    fun targetTimeoutPersistsSessionTraceWithoutShowingClickDiagnostics() = runTest {
        val fixture = fixture(waitingState())
        val trace = CaptureDiagnosticTrace.snapshot(
            summary = CaptureDiagnosticSummary(scanCount = 1, incompleteScanCount = 1),
            records = emptyList(),
        )
        val failureDiagnostics = CaptureFailureDiagnostics(
            sourceAvailable = false,
            eventTextCount = 0,
            eventHasContentDescription = false,
            clickDiagnosticApplicable = false,
            sessionTrace = trace,
        )
        testScheduler.advanceTimeBy(RuleCaptureCoordinator.TARGET_TIMEOUT_MS)

        fixture.coordinator.expireActive(failureDiagnostics = failureDiagnostics)

        val failedSession = requireNotNull(fixture.capture.state.value.activeSession)
        assertEquals(CaptureFailure.TARGET_TIMEOUT, failedSession.failure)
        assertEquals(trace, failedSession.failureDiagnostics?.sessionTrace)
        assertEquals(false, failedSession.failureDiagnostics?.clickDiagnosticApplicable)
    }

    @Test
    fun returnPreservesCapturedSampleAndObservedCandidateForExplicitConfirmation() = runTest {
        val sampleFixture = fixture(capturedState())
        sampleFixture.coordinator.markLeftFlashSkip(SESSION_ID)
        assertEquals(CaptureOperationResult.Success(SESSION_ID), sampleFixture.coordinator.reconcileReturn(SESSION_ID))
        assertEquals(CapturePhase.CAPTURED, sampleFixture.capture.state.value.activeSession?.phase)
        assertEquals(sample(), sampleFixture.capture.state.value.activeSession?.pendingSample)
        assertTrue(sampleFixture.capture.state.value.samples.isEmpty())

        val candidate = ObservedDismissCandidate(
            id = 102L,
            targetAppId = TARGET,
            targetAppName = "Target",
            activityName = "com.example.target.MainActivity",
            observedAfterTargetMs = 500L,
            observedAtEpochMs = 1_000L,
            nodes = sample().nodes,
            preClickUniqueSelectors = emptyList(),
        )
        val candidateState = observingState().copy(
            activeSession = observingState().activeSession!!.copy(
                phase = CapturePhase.AWAITING_CONFIRMATION,
                candidateClickListeningEnabled = true,
                pendingObservedCandidate = candidate,
            ),
        )
        val candidateFixture = fixture(candidateState)
        candidateFixture.coordinator.markLeftFlashSkip(SESSION_ID)
        assertEquals(CaptureOperationResult.Success(SESSION_ID), candidateFixture.coordinator.reconcileReturn(SESSION_ID))
        assertEquals(CapturePhase.AWAITING_CONFIRMATION, candidateFixture.capture.state.value.activeSession?.phase)
        assertEquals(candidate, candidateFixture.capture.state.value.activeSession?.pendingObservedCandidate)
        assertFalse(candidateFixture.capture.state.value.activeSession!!.listensForCandidateClick)
        assertTrue(candidateFixture.capture.state.value.samples.isEmpty())
    }

    @Test
    fun observedCandidateClickUpgradesOnlyTheStillPendingMatchingCandidate() = runTest {
        val fixture = fixture(observingState())
        val candidate = candidateForClick()
        assertTrue(fixture.coordinator.recordObservedCandidate(SESSION_ID, candidate))
        assertTrue(fixture.capture.state.value.activeSession!!.listensForCandidateClick)

        val identity = PageIdentity(
            packageName = TARGET.packageName,
            activityName = candidate.activityName,
            windowId = 7,
        )
        val clickSample = sample().copy(
            id = SESSION_ID,
            targetAppName = candidate.targetAppName,
            nodes = candidate.nodes,
            evidence = CaptureEvidence.ACCESSIBILITY_CLICK,
        )

        assertEquals(
            CaptureOperationResult.Success(SESSION_ID),
            fixture.coordinator.upgradeObservedCandidateClick(
                sessionId = SESSION_ID,
                candidate = candidate,
                candidateIdentity = identity,
                clickedIdentity = identity,
                sample = clickSample,
            ),
        )
        val upgraded = requireNotNull(fixture.capture.state.value.activeSession)
        assertEquals(CapturePhase.CAPTURED, upgraded.phase)
        assertEquals(clickSample, upgraded.pendingSample)
        assertFalse(upgraded.listensForCandidateClick)
        assertNull(upgraded.pendingObservedCandidate)
    }

    @Test
    fun clickUpgradeWaitsBehindCandidatePersistenceAndThenUsesThatPendingCandidate() = runTest {
        val fixture = fixture(observingState())
        val candidate = candidateForClick()
        val releaseWrite = CompletableDeferred<Unit>()
        fixture.capture.beforeUpdate = { releaseWrite.await() }

        val recording = async {
            fixture.coordinator.recordObservedCandidate(SESSION_ID, candidate)
        }
        runCurrent()
        val identity = PageIdentity(
            packageName = TARGET.packageName,
            activityName = candidate.activityName,
            windowId = 9,
        )
        val clickSample = sample().copy(
            id = SESSION_ID,
            targetAppName = candidate.targetAppName,
            nodes = candidate.nodes,
            evidence = CaptureEvidence.ACCESSIBILITY_CLICK,
        )
        val upgrade = async {
            fixture.coordinator.upgradeObservedCandidateClick(
                sessionId = SESSION_ID,
                candidate = candidate,
                candidateIdentity = identity,
                clickedIdentity = identity,
                sample = clickSample,
            )
        }
        runCurrent()
        assertFalse(upgrade.isCompleted)

        releaseWrite.complete(Unit)
        assertTrue(recording.await())
        assertEquals(CaptureOperationResult.Success(SESSION_ID), upgrade.await())
        assertEquals(clickSample, fixture.capture.state.value.activeSession?.pendingSample)
    }

    @Test
    fun mismatchedCandidateClickKeepsManualConfirmationAvailable() = runTest {
        val fixture = fixture(observingState())
        val candidate = candidateForClick()
        assertTrue(fixture.coordinator.recordObservedCandidate(SESSION_ID, candidate))
        val candidateIdentity = PageIdentity(
            packageName = TARGET.packageName,
            activityName = candidate.activityName,
            windowId = 7,
        )
        val wrongWindow = candidateIdentity.copy(windowId = 8)
        val clickSample = sample().copy(
            id = SESSION_ID,
            targetAppName = candidate.targetAppName,
            nodes = candidate.nodes,
            evidence = CaptureEvidence.ACCESSIBILITY_CLICK,
        )

        assertEquals(
            CaptureOperationResult.Stale,
            fixture.coordinator.upgradeObservedCandidateClick(
                sessionId = SESSION_ID,
                candidate = candidate,
                candidateIdentity = candidateIdentity,
                clickedIdentity = wrongWindow,
                sample = clickSample,
            ),
        )
        val pending = requireNotNull(fixture.capture.state.value.activeSession)
        assertEquals(CapturePhase.AWAITING_CONFIRMATION, pending.phase)
        assertTrue(pending.listensForCandidateClick)
        assertEquals(CaptureOperationResult.Success(), fixture.coordinator.finish(SESSION_ID))
        val manuallyConfirmed = fixture.capture.state.value.samples.single()
        assertEquals(CaptureEvidence.USER_CONFIRMED_UNREPORTED_CLICK, manuallyConfirmed.evidence)
        assertEquals(candidate.nodes, manuallyConfirmed.nodes)
    }

    @Test
    fun candidateClickListeningStopsAfterCancelAndCompletionTimeout() = runTest {
        val cancelled = fixture(observingState())
        assertTrue(cancelled.coordinator.recordObservedCandidate(SESSION_ID, candidateForClick()))
        assertTrue(cancelled.capture.state.value.activeSession!!.listensForCandidateClick)
        assertEquals(CaptureOperationResult.Success(), cancelled.coordinator.cancel(SESSION_ID))
        assertNull(cancelled.capture.state.value.activeSession)

        val expired = fixture(observingState())
        assertTrue(expired.coordinator.recordObservedCandidate(SESSION_ID, candidateForClick()))
        testScheduler.advanceTimeBy(RuleCaptureCoordinator.COMPLETION_TIMEOUT_MS)
        expired.coordinator.expireActive()
        val failedSession = requireNotNull(expired.capture.state.value.activeSession)
        assertEquals(CapturePhase.FAILED, failedSession.phase)
        assertFalse(failedSession.listensForCandidateClick)
    }

    @Test
    fun queuedCaptureWinsReturnSettlementAndLateCaptureCannotUndoReturnFailure() = runTest {
        val queuedFixture = fixture(observingState())
        queuedFixture.coordinator.markLeftFlashSkip(SESSION_ID)
        val releaseWrite = CompletableDeferred<Unit>()
        queuedFixture.capture.beforeUpdate = { releaseWrite.await() }

        val capture = async { queuedFixture.coordinator.recordCaptured(SESSION_ID, sample()) }
        runCurrent()
        val returned = async { queuedFixture.coordinator.reconcileReturn(SESSION_ID) }
        runCurrent()
        assertFalse(returned.isCompleted)

        releaseWrite.complete(Unit)
        capture.await()
        assertEquals(CaptureOperationResult.Success(SESSION_ID), returned.await())
        assertEquals(CapturePhase.CAPTURED, queuedFixture.capture.state.value.activeSession?.phase)
        assertEquals(sample(), queuedFixture.capture.state.value.activeSession?.pendingSample)

        val returnedFirst = fixture(observingState())
        returnedFirst.coordinator.markLeftFlashSkip(SESSION_ID)
        returnedFirst.coordinator.reconcileReturn(SESSION_ID)
        returnedFirst.coordinator.recordCaptured(SESSION_ID, sample())
        assertEquals(CaptureFailure.DISMISS_NOT_CAPTURED, returnedFirst.capture.state.value.activeSession?.failure)
        assertNull(returnedFirst.capture.state.value.activeSession?.pendingSample)
    }

    @Test
    fun oldReturnCannotFailReplacementSessionAndStorageFailureCanRetry() = runTest {
        val fixture = fixture(observingState())
        fixture.coordinator.markLeftFlashSkip(SESSION_ID)
        fixture.coordinator.cancel(SESSION_ID)
        val replacement = fixture.coordinator.requestStart() as CaptureOperationResult.Success
        val replacementId = requireNotNull(replacement.sessionId)
        val before = fixture.capture.state.value

        assertEquals(CaptureOperationResult.Stale, fixture.coordinator.reconcileReturn(SESSION_ID))
        fixture.coordinator.recordCaptured(SESSION_ID, sample())
        assertEquals(before, fixture.capture.state.value)
        assertEquals(replacementId, fixture.capture.state.value.activeSession?.id)

        val retryFixture = fixture(waitingState())
        retryFixture.coordinator.markLeftFlashSkip(SESSION_ID)
        retryFixture.capture.beforeUpdate = { throw IOException("disk full") }
        assertEquals(storageFailure(), retryFixture.coordinator.reconcileReturn(SESSION_ID))
        assertEquals(CapturePhase.WAITING_FOR_TARGET, retryFixture.capture.state.value.activeSession?.phase)
        assertEquals(1_000L, retryFixture.capture.state.value.activeSession?.leftFlashSkipAtEpochMs)
        retryFixture.capture.beforeUpdate = {}
        assertEquals(CaptureOperationResult.Success(SESSION_ID), retryFixture.coordinator.reconcileReturn(SESSION_ID))
        assertEquals(CaptureFailure.RETURNED_BEFORE_TARGET, retryFixture.capture.state.value.activeSession?.failure)
    }

    @Test
    fun enablingRequiresReadyRulesAndNoActiveCapture() = runTest {
        val fixture = fixture(capturedState())
        assertEquals(
            CaptureOperationResult.Failure(CaptureOperationFailure.CAPTURE_IN_PROGRESS),
            fixture.coordinator.setAppEnabled(TARGET, true),
        )
        assertEquals(CaptureOperationResult.Success(), fixture.coordinator.setAppEnabled(TARGET, false))
        fixture.coordinator.cancel(SESSION_ID)
        assertEquals(
            CaptureOperationResult.Failure(CaptureOperationFailure.RULE_NOT_READY),
            fixture.coordinator.setAppEnabled(TARGET, true),
        )
        assertFalse(fixture.preferences.isAppEnabled(TARGET))
    }

    @Test
    fun clearThenQueuedEnableCannotRestoreDeletedRule() = runTest {
        val fixture = fixture(RuleCaptureState(samples = listOf(sample())))
        val releaseDisable = CompletableDeferred<Unit>()
        fixture.preferences.beforeWrite = { _, _ -> releaseDisable.await() }
        val clear = async { fixture.coordinator.clearTarget(TARGET) }
        runCurrent()
        val enable = async { fixture.coordinator.setAppEnabled(TARGET, true) }
        runCurrent()
        assertFalse(enable.isCompleted)

        releaseDisable.complete(Unit)
        assertEquals(CaptureOperationResult.Success(), clear.await())
        val enableResult = enable.await()
        assertTrue(enableResult is CaptureOperationResult.Failure)
        assertTrue(
            (enableResult as CaptureOperationResult.Failure).reason in setOf(
                CaptureOperationFailure.RULE_NOT_READY,
                CaptureOperationFailure.RULES_UPDATING,
            ),
        )
        assertFalse(fixture.preferences.isAppEnabled(TARGET))
        assertTrue(fixture.capture.state.value.samples.isEmpty())
    }

    @Test
    fun clearAllDisablesOrphanedSwitches() = runTest {
        val fixture = fixture(RuleCaptureState())
        assertTrue(fixture.preferences.isAppEnabled(TARGET))

        assertEquals(CaptureOperationResult.Success(), fixture.coordinator.clearAll())
        assertFalse(fixture.preferences.isAppEnabled(TARGET))
        assertEquals(RuleCaptureState(), fixture.capture.state.value)
    }

    @Test
    fun cancellationDuringSavePropagatesAndReleasesOperationLock() = runTest {
        val fixture = fixture(capturedState())
        fixture.capture.beforeUpdate = { throw CancellationException("cancel save") }
        val finish = async { fixture.coordinator.finish(SESSION_ID) }
        try {
            finish.await()
            throw AssertionError("Cancellation must propagate")
        } catch (_: CancellationException) {
            assertTrue(finish.isCancelled)
        }
        assertFalse(fixture.preferences.isAppEnabled(TARGET))
        assertTrue(fixture.capture.state.value.activeSession!!.blocksAutomation)

        fixture.capture.beforeUpdate = {}
        assertEquals(CaptureOperationResult.Success(), fixture.coordinator.cancel(SESSION_ID))
    }

    @Test
    fun cancellationDuringDisableDoesNotReleaseCapture() = runTest {
        val fixture = fixture(capturedState())
        val releaseDisable = CompletableDeferred<Unit>()
        fixture.preferences.beforeWrite = { _, _ -> releaseDisable.await() }
        val finish = async { fixture.coordinator.finish(SESSION_ID) }
        runCurrent()
        finish.cancel()
        finish.join()

        assertTrue(finish.isCancelled)
        assertEquals(capturedState(), fixture.capture.state.value)
        assertTrue(fixture.capture.state.value.activeSession!!.blocksAutomation)
        assertTrue(fixture.preferences.isAppEnabled(TARGET))
    }

    @Test
    fun waitingForConfigurationStopsWhenSessionIsCancelled() = runTest {
        val fixture = fixture(RuleCaptureState())
        val start = fixture.coordinator.requestStart() as CaptureOperationResult.Success
        val sessionId = requireNotNull(start.sessionId)
        val configured = async { fixture.coordinator.awaitConfigured(sessionId) }
        runCurrent()
        fixture.coordinator.cancel(sessionId)

        assertFalse(configured.await())
        assertNull(fixture.capture.state.value.activeSession)
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun configurationWaitAcceptsSessionThatAlreadyAdvanced() = runTest {
        val fixture = fixture(capturedState())
        assertTrue(fixture.coordinator.awaitConfigured(SESSION_ID))
        assertEquals(capturedState(), fixture.capture.state.value)
    }

    @Test
    fun configurationWaitPreservesServiceFailure() = runTest {
        val fixture = fixture(capturedState())
        fixture.coordinator.fail(SESSION_ID, CaptureFailure.SERVICE_UNAVAILABLE)
        assertFalse(fixture.coordinator.awaitConfigured(SESSION_ID))
        assertEquals(
            CaptureFailure.SERVICE_UNAVAILABLE,
            fixture.capture.state.value.activeSession!!.failure,
        )
    }

    @Test
    fun clearFailureRetainsSavedRulesWithDisabledSwitch() = runTest {
        val initial = RuleCaptureState(samples = listOf(sample()))
        val fixture = fixture(initial)
        fixture.capture.beforeUpdate = { throw IOException("clear failed") }

        assertEquals(storageFailure(), fixture.coordinator.clearTarget(TARGET))
        assertEquals(initial, fixture.capture.state.value)
        assertFalse(fixture.preferences.isAppEnabled(TARGET))
    }

    @Test
    fun delayedDisconnectForCancelledSessionCannotFailReplacement() = runTest {
        val fixture = fixture(capturedState())
        fixture.coordinator.serviceDisconnected(SESSION_ID)
        fixture.coordinator.cancel(SESSION_ID)
        val replacement = fixture.coordinator.requestStart() as CaptureOperationResult.Success
        val beforeCallback = fixture.capture.state.value

        runCurrent()

        assertEquals(beforeCallback, fixture.capture.state.value)
        assertEquals(replacement.sessionId, fixture.capture.state.value.activeSession!!.id)
        assertEquals(CapturePhase.CONFIGURING, fixture.capture.state.value.activeSession!!.phase)
    }

    @Test
    fun delayedDisconnectCannotChangeCompletedCaptureOrLaterEnablement() = runTest {
        val fixture = fixture(capturedState())
        assertEquals(CaptureOperationResult.Success(), fixture.coordinator.finish(SESSION_ID))
        runCurrent()
        assertEquals(CaptureOperationResult.Success(), fixture.coordinator.setAppEnabled(TARGET, true))
        fixture.coordinator.serviceDisconnected(SESSION_ID)
        val completed = fixture.capture.state.value

        runCurrent()

        assertEquals(completed, fixture.capture.state.value)
        assertNull(fixture.capture.state.value.activeSession)
        assertTrue(fixture.preferences.isAppEnabled(TARGET))
    }

    @Test
    fun delayedDisconnectPreservesExistingFailureReason() = runTest {
        val fixture = fixture(capturedState())
        fixture.coordinator.serviceDisconnected(SESSION_ID)
        fixture.coordinator.fail(SESSION_ID, CaptureFailure.COMPLETION_TIMEOUT)
        runCurrent()

        assertEquals(
            CaptureFailure.COMPLETION_TIMEOUT,
            fixture.capture.state.value.activeSession!!.failure,
        )
    }

    @Test
    fun serviceDisconnectPersistsSynchronousTraceAndReturnKeepsIt() = runTest {
        val fixture = fixture(observingState())
        val trace = CaptureDiagnosticTrace.snapshot(
            summary = CaptureDiagnosticSummary(scanCount = 3, maxCloseSemanticCount = 1),
            records = emptyList(),
        )
        val failureDiagnostics = CaptureFailureDiagnostics(
            sourceAvailable = false,
            eventTextCount = 0,
            eventHasContentDescription = false,
            sessionTrace = trace,
        )
        val targetWindowDiagnostics = TargetWindowDiagnostics(viewClickedEventCount = 2)

        fixture.coordinator.serviceDisconnected(
            expectedSessionId = SESSION_ID,
            diagnostics = CaptureDiagnosticSnapshot(
                failure = failureDiagnostics,
                targetWindow = targetWindowDiagnostics,
            ),
        )
        runCurrent()

        val failed = requireNotNull(fixture.capture.state.value.activeSession)
        assertEquals(CaptureFailure.SERVICE_UNAVAILABLE, failed.failure)
        assertEquals(trace, failed.failureDiagnostics?.sessionTrace)
        assertEquals(targetWindowDiagnostics, failed.targetWindowDiagnostics)

        fixture.coordinator.reconcileReturn(SESSION_ID, observedDeparture = true)

        val returned = requireNotNull(fixture.capture.state.value.activeSession)
        assertEquals(CaptureFailure.SERVICE_UNAVAILABLE, returned.failure)
        assertEquals(trace, returned.failureDiagnostics?.sessionTrace)
        assertEquals(targetWindowDiagnostics, returned.targetWindowDiagnostics)
    }

    @Test
    fun hashCollidingRuleIdsCannotEnableCatalogThatFellBackToEmpty() = runTest {
        val samples = listOf("Aa", "BB").mapIndexed { index, suffix ->
            sample().copy(
                id = index.toLong(),
                nodes = listOf(sample().nodes.single().copy(resourceId = "com.example.target:id/$suffix")),
            )
        }
        val fixture = fixture(RuleCaptureState(samples = samples))
        runCurrent()
        val analyzedRules = fixture.catalog.snapshot.value.capturedTargets.single().analysis.rules
        assertEquals(2, analyzedRules.size)
        assertEquals(1, analyzedRules.map { it.id }.distinct().size)
        assertTrue(fixture.catalog.snapshot.value.rules.isEmpty())
        fixture.coordinator.setAppEnabled(TARGET, false)

        assertEquals(
            CaptureOperationResult.Failure(CaptureOperationFailure.RULE_NOT_READY),
            fixture.coordinator.setAppEnabled(TARGET, true),
        )
        assertFalse(fixture.preferences.isAppEnabled(TARGET))
        assertEquals(CaptureOperationResult.Success(), fixture.coordinator.clearTarget(TARGET))
        assertTrue(fixture.capture.state.value.samples.isEmpty())
    }

    @Test
    fun enablingWaitsForMatchingCatalogButDisablingRemainsAvailable() = runTest {
        val fixture = fixture(RuleCaptureState(samples = listOf(sample())))
        fixture.coordinator.setAppEnabled(TARGET, false)
        assertEquals(
            CaptureOperationResult.Failure(CaptureOperationFailure.RULES_UPDATING),
            fixture.coordinator.setAppEnabled(TARGET, true),
        )
        assertFalse(fixture.preferences.isAppEnabled(TARGET))
        runCurrent()
        assertEquals(CaptureOperationResult.Success(), fixture.coordinator.setAppEnabled(TARGET, true))

        fixture.capture.state.value = RuleCaptureState(samples = listOf(sample().copy(targetAppName = "新版样本")))
        assertEquals(CaptureOperationResult.Success(), fixture.coordinator.setAppEnabled(TARGET, false))
        assertEquals(
            CaptureOperationResult.Failure(CaptureOperationFailure.RULES_UPDATING),
            fixture.coordinator.setAppEnabled(TARGET, true),
        )
        assertFalse(fixture.preferences.isAppEnabled(TARGET))
    }

    private fun TestScope.fixture(state: RuleCaptureState): Fixture {
        val capture = FakeCaptureStore(state)
        val preferences = FakePreferencesStore()
        val catalog = CompositeRuleCatalog(
            BuiltinRuleCatalog(),
            capture,
            backgroundScope,
            StandardTestDispatcher(testScheduler),
        )
        return Fixture(
            capture,
            preferences,
            RuleCaptureCoordinator(
                capture,
                preferences,
                object : MonotonicClock {
                    override fun elapsedRealtimeMs(): Long = testScheduler.currentTime
                },
                object : WallClock {
                    override fun currentTimeMillis(): Long = 1_000L + testScheduler.currentTime
                },
                backgroundScope,
                catalog,
            ),
            catalog,
        )
    }

    private data class Fixture(
        val capture: FakeCaptureStore,
        val preferences: FakePreferencesStore,
        val coordinator: RuleCaptureCoordinator,
        val catalog: CompositeRuleCatalog,
    )

    private class FakeCaptureStore(initial: RuleCaptureState) : RuleCaptureStore {
        override val state = MutableStateFlow(initial)
        var beforeUpdate: suspend () -> Unit = {}

        override suspend fun update(transform: (RuleCaptureState) -> RuleCaptureState) {
            beforeUpdate()
            state.value = transform(state.value)
        }
    }

    private class FakePreferencesStore : FlashSkipStore {
        override val preferences = MutableStateFlow(FlashSkipPreferences(mapOf(TARGET to true)))
        var beforeWrite: suspend (TargetAppId, Boolean) -> Unit = { _, _ -> }

        override suspend fun setAppEnabled(targetAppId: TargetAppId, enabled: Boolean) {
            beforeWrite(targetAppId, enabled)
            preferences.value = preferences.value.copy(
                enabledApps = preferences.value.enabledApps + (targetAppId to enabled),
            )
        }

        override suspend fun isAppEnabled(targetAppId: TargetAppId): Boolean =
            preferences.value.enabledApps[targetAppId] == true

        override suspend fun appendRecord(record: ActionRecord) = Unit
    }

    private companion object {
        val TARGET = TargetAppId("com.example.target")
        const val SESSION_ID = 42L

        fun storageFailure() = CaptureOperationResult.Failure(CaptureOperationFailure.STORAGE)

        fun sample() = CaptureSample(
            id = 101L,
            targetAppId = TARGET,
            activityName = "com.example.target.MainActivity",
            clickedAfterTargetMs = 500L,
            capturedAtEpochMs = 1_000L,
            nodes = listOf(
                CapturedNode(
                    resourceId = "com.example.target:id/skip",
                    text = "跳过",
                    clickable = true,
                    visibleToUser = true,
                    hasUsableBounds = true,
                ),
            ),
            preClickUniqueSelectors = emptyList(),
        )

        fun candidateForClick() = ObservedDismissCandidate(
            id = SESSION_ID,
            targetAppId = TARGET,
            targetAppName = "Target",
            activityName = "com.example.target.MainActivity",
            observedAfterTargetMs = 100L,
            observedAtEpochMs = 1_000L,
            nodes = sample().nodes,
            preClickUniqueSelectors = emptyList(),
        )

        fun capturedState() = RuleCaptureState(
            activeSession = CaptureSession(
                id = SESSION_ID,
                targetAppId = TARGET,
                phase = CapturePhase.CAPTURED,
                phaseStartedAtEpochMs = 1_000L,
                deadlineAtEpochMs = 601_000L,
                pendingSample = sample(),
            ),
        )

        fun waitingState() = RuleCaptureState(
            activeSession = CaptureSession(
                id = SESSION_ID,
                phase = CapturePhase.WAITING_FOR_TARGET,
                phaseStartedAtEpochMs = 1_000L,
                deadlineAtEpochMs = 91_000L,
            ),
        )

        fun observingState() = RuleCaptureState(
            activeSession = CaptureSession(
                id = SESSION_ID,
                targetAppId = TARGET,
                targetAppName = "Target",
                packageName = TARGET.packageName,
                phase = CapturePhase.OBSERVING_CLICK,
                phaseStartedAtEpochMs = 1_000L,
                deadlineAtEpochMs = 16_000L,
                targetEnteredAtElapsedMs = 500L,
            ),
        )
    }
}
