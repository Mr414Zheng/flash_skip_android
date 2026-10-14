package com.zhenghuaizhi.flashskip.application.rulecapture

import com.zhenghuaizhi.flashskip.domain.model.CaptureFailure
import com.zhenghuaizhi.flashskip.domain.model.CaptureFailureDiagnostics
import com.zhenghuaizhi.flashskip.domain.model.CapturePhase
import com.zhenghuaizhi.flashskip.domain.model.CapturedSelector
import com.zhenghuaizhi.flashskip.domain.model.SemanticDiffEvidence
import com.zhenghuaizhi.flashskip.testing.CaptureLifecycleFixture
import com.zhenghuaizhi.flashskip.testing.CommitGate
import com.zhenghuaizhi.flashskip.testing.DualCaptureClock
import com.zhenghuaizhi.flashskip.testing.LIFECYCLE_EPOCH_MS
import com.zhenghuaizhi.flashskip.testing.LifecycleTransition
import com.zhenghuaizhi.flashskip.testing.OTHER_TARGET
import com.zhenghuaizhi.flashskip.testing.SESSION_ID
import com.zhenghuaizhi.flashskip.testing.TARGET
import com.zhenghuaizhi.flashskip.testing.captureSample
import com.zhenghuaizhi.flashskip.testing.perform
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RuleCaptureServiceUpdateTest {
    @Test
    fun disconnectRetainsValidCapturedPayloadAndContinuesBlockingAutomation() = runTest {
        assertRetainedOnDisconnect(CapturePhase.CAPTURED)
    }

    @Test
    fun disconnectRetainsValidCandidatePayloadAndStopsClickListening() = runTest {
        assertRetainedOnDisconnect(CapturePhase.AWAITING_CONFIRMATION)
    }

    @Test
    fun disconnectFailsWaitingAndObservingWithServiceUnavailable() = runTest {
        for (phase in listOf(CapturePhase.WAITING_FOR_TARGET, CapturePhase.OBSERVING_CLICK)) {
            val f = CaptureLifecycleFixture(backgroundScope, phase)
            f.coordinator.serviceDisconnected(SESSION_ID)
            runCurrent()

            assertEquals(CapturePhase.FAILED, f.session.phase)
            assertEquals(CaptureFailure.SERVICE_UNAVAILABLE, f.session.failure)
            assertFalse(f.session.blocksAutomation)
            assertEquals(f.initial.samples, f.store.state.value.samples)
            assertEquals(f.initialPreferences, f.preferences.preferences.value)
        }
    }

    @Test
    fun disconnectSettlesExpiredCapturedPayloadAsCompletionTimeout() = runTest {
        assertExpiredOnDisconnect(CapturePhase.CAPTURED)
    }

    @Test
    fun disconnectSettlesExpiredCandidatePayloadAsCompletionTimeout() = runTest {
        assertExpiredOnDisconnect(CapturePhase.AWAITING_CONFIRMATION)
    }

    @Test
    fun disconnectWithMissingIdIsNoOp() = runTest {
        val f = CaptureLifecycleFixture(backgroundScope, CapturePhase.AWAITING_CONFIRMATION)
        f.coordinator.serviceDisconnected(null)
        runCurrent()
        assertEquals(f.initial, f.store.state.value)
        assertEquals(0, f.store.attempts)
    }

    @Test
    fun genericFailStillClearsCapturedAndCandidatePayloads() = runTest {
        for (phase in listOf(CapturePhase.CAPTURED, CapturePhase.AWAITING_CONFIRMATION)) {
            val f = CaptureLifecycleFixture(backgroundScope, phase)
            assertEquals(CaptureOperationResult.Success(SESSION_ID), f.coordinator.fail(SESSION_ID, CaptureFailure.SERVICE_UNAVAILABLE))
            assertEquals(CapturePhase.FAILED, f.session.phase)
            assertEquals(CaptureFailure.SERVICE_UNAVAILABLE, f.session.failure)
            assertNull(f.session.pendingSample)
            assertNull(f.session.pendingObservedCandidate)
            assertFalse(f.session.candidateClickListeningEnabled)
            assertFalse(f.session.blocksAutomation)
            assertEquals(f.initial.samples, f.store.state.value.samples)
        }
    }

    @Test
    fun serviceWriteIOExceptionPreservesStateAndRetryClearsMatchingFailure() = runTest {
        for (transition in LifecycleTransition.entries) {
            val f = CaptureLifecycleFixture(backgroundScope, transition.source)
            f.store.beforeCommit = { throw IOException("服务状态未提交") }

            assertEquals(CaptureOperationResult.Failure(CaptureOperationFailure.STORAGE), f.perform(transition))
            assertEquals(f.initial, f.store.state.value)
            assertEquals(SESSION_ID, f.coordinator.serviceFailure.value?.sessionId)
            assertEquals(CaptureOperationFailure.STORAGE, f.coordinator.serviceFailure.value?.reason)
            assertTrue(f.store.writes.isEmpty())

            f.store.beforeCommit = {}
            assertEquals(CaptureOperationResult.Success(SESSION_ID), f.perform(transition))
            assertNull(f.coordinator.serviceFailure.value)
            assertEquals(transition.destination, f.session.phase)
        }
    }

    @Test
    fun cancellingServiceWritePropagatesCancellationPreservesStateAndReleasesLock() = runTest {
        for (transition in LifecycleTransition.entries) {
            val f = CaptureLifecycleFixture(backgroundScope, transition.source)
            val gate = CommitGate()
            f.store.beforeCommit = gate::pause
            val updating = async { f.perform(transition) }
            gate.entered.await()
            updating.cancel()
            try {
                updating.await()
                fail("取消必须向调用方传播")
            } catch (_: CancellationException) {
                assertTrue(updating.isCancelled)
            }
            assertEquals(f.initial, f.store.state.value)
            assertNull(f.coordinator.serviceFailure.value)
            assertTrue(f.store.writes.isEmpty())

            f.store.beforeCommit = {}
            assertEquals(CaptureOperationResult.Success(SESSION_ID), f.perform(transition))
            assertEquals(transition.destination, f.session.phase)
        }
    }

    @Test
    fun firstUnrecoveredEvidenceErrorSurvivesOtherFailuresAndWindowUpdates() = runTest {
        val f = CaptureLifecycleFixture(backgroundScope, CapturePhase.OBSERVING_CLICK)
        f.store.beforeCommit = { throw IOException("证据未提交") }
        assertEquals(CaptureOperationResult.Failure(CaptureOperationFailure.STORAGE), f.coordinator.recordCaptured(SESSION_ID, f.sample))
        val firstFailure = f.coordinator.serviceFailure.value
        assertEquals(CaptureServiceOperation.RECORD_CAPTURED, firstFailure?.operation)
        assertEquals(CaptureOperationResult.Failure(CaptureOperationFailure.STORAGE), f.observe())
        assertEquals(firstFailure, f.coordinator.serviceFailure.value)

        f.store.beforeCommit = {}
        assertEquals(CaptureOperationResult.Success(SESSION_ID), f.observe())
        assertEquals(firstFailure, f.coordinator.serviceFailure.value)
        assertEquals(CaptureOperationResult.Stale, f.coordinator.recordCaptured(SESSION_ID - 1, f.sample))
        assertEquals(firstFailure, f.coordinator.serviceFailure.value)

        assertEquals(CaptureOperationResult.Success(SESSION_ID), f.coordinator.recordObservedCandidateResult(SESSION_ID, f.candidate))
        assertNull(f.coordinator.serviceFailure.value)
    }

    @Test
    fun cancellingSessionClearsItsServiceErrorAndLaterIdsIncreaseDespiteWallRollback() = runTest {
        val f = CaptureLifecycleFixture(backgroundScope)
        f.store.beforeCommit = { throw IOException("配置未提交") }
        assertEquals(CaptureOperationResult.Failure(CaptureOperationFailure.STORAGE), f.coordinator.markConfigured(SESSION_ID))
        f.store.beforeCommit = {}
        assertEquals(CaptureOperationResult.Success(), f.coordinator.cancel(SESSION_ID))
        assertNull(f.coordinator.serviceFailure.value)

        val first = requireNotNull((f.coordinator.requestStart() as CaptureOperationResult.Success).sessionId)
        assertEquals(CaptureOperationResult.Success(), f.coordinator.cancel(first))
        f.clock.wallMs = 0
        val second = requireNotNull((f.coordinator.requestStart() as CaptureOperationResult.Success).sessionId)
        assertTrue(first > SESSION_ID)
        assertTrue(second > first)
        assertEquals(f.initial.samples, f.store.state.value.samples)
    }

    @Test
    fun notificationsWithNoActiveSessionAreStaleWithoutWrites() = runTest {
        val f = CaptureLifecycleFixture(backgroundScope)
        f.store.state.value = f.initial.copy(activeSession = null)
        val empty = f.store.state.value
        val results = listOf(
            f.coordinator.markConfigured(SESSION_ID), f.observe(),
            f.coordinator.recordCaptured(SESSION_ID, f.sample),
            f.coordinator.recordObservedCandidateResult(SESSION_ID, f.candidate), f.upgrade(),
            f.coordinator.markLeftFlashSkip(SESSION_ID), f.coordinator.reconcileReturn(SESSION_ID, true),
            f.coordinator.cancel(SESSION_ID), f.coordinator.fail(SESSION_ID, CaptureFailure.SERVICE_UNAVAILABLE),
        )
        results.forEach { assertEquals(CaptureOperationResult.Stale, it) }
        f.coordinator.serviceDisconnected(SESSION_ID)
        runCurrent()
        assertFalse(f.coordinator.awaitConfigured(SESSION_ID))
        assertEquals(empty, f.store.state.value)
        assertEquals(0, f.store.attempts)
    }

    @Test
    fun pendingConfigurationTimeoutFailsOriginalSessionAndOldWaitCannotFailReplacement() = runTest {
        val f = CaptureLifecycleFixture(backgroundScope)
        val waiting = async { f.coordinator.awaitConfigured(SESSION_ID) }
        runCurrent()
        f.clock.elapsedMs += 2_500
        f.clock.wallMs += 2_500
        advanceTimeBy(2_500)
        runCurrent()
        assertFalse(waiting.await())
        assertEquals(CaptureFailure.CONFIGURATION_TIMEOUT, f.session.failure)
        assertEquals(CapturePhase.FAILED, f.session.phase)

        val newId = requireNotNull((f.coordinator.requestStart() as CaptureOperationResult.Success).sessionId)
        val replacement = f.store.state.value
        val attempts = f.store.attempts
        assertFalse(f.coordinator.awaitConfigured(SESSION_ID))
        assertEquals(newId, f.session.id)
        assertEquals(replacement, f.store.state.value)
        assertEquals(attempts, f.store.attempts)
    }

    @Test
    fun diagnosticsProviderExceptionsCannotReplaceReturnSettlementAndOldRegistrationCannotUnregisterNew() = runTest {
        val f = CaptureLifecycleFixture(backgroundScope, CapturePhase.OBSERVING_CLICK)
        val old = f.coordinator.registerReturnDiagnosticsProvider { throw IllegalStateException("旧诊断失效") }
        val returned = CaptureFailureDiagnostics(sourceAvailable = false, eventTextCount = 2, eventHasContentDescription = false)
        var requestedId: Long? = null
        f.coordinator.registerReturnDiagnosticsProvider { id ->
            requestedId = id
            CaptureDiagnosticSnapshot(returned, null)
        }
        f.coordinator.unregisterReturnDiagnosticsProvider(old)

        assertEquals(CaptureOperationResult.Success(SESSION_ID), f.coordinator.reconcileReturn(SESSION_ID, true))
        assertEquals(SESSION_ID, requestedId)
        assertEquals(returned, f.session.failureDiagnostics)
        assertEquals(CaptureFailure.DISMISS_NOT_CAPTURED, f.session.failure)

        val broken = CaptureLifecycleFixture(backgroundScope, CapturePhase.WAITING_FOR_TARGET)
        broken.coordinator.registerReturnDiagnosticsProvider { throw IllegalStateException("诊断失效") }
        assertEquals(CaptureOperationResult.Success(SESSION_ID), broken.coordinator.reconcileReturn(SESSION_ID, true))
        assertEquals(CaptureFailure.RETURNED_BEFORE_TARGET, broken.session.failure)
    }

    @Test
    fun diagnosticProviderCancellationPropagatesWithoutReturnCommit() = runTest {
        val f = CaptureLifecycleFixture(backgroundScope, CapturePhase.OBSERVING_CLICK)
        f.coordinator.registerReturnDiagnosticsProvider { throw CancellationException("诊断请求被取消") }
        try {
            f.coordinator.reconcileReturn(SESSION_ID, true)
            fail("诊断取消必须传播")
        } catch (_: CancellationException) {
            assertEquals(f.initial, f.store.state.value)
            assertEquals(0, f.store.attempts)
        }
        assertNull(f.coordinator.serviceFailure.value)
    }

    @Test
    fun targetMismatchCannotReplaceCurrentObservedEvidence() = runTest {
        val f = CaptureLifecycleFixture(backgroundScope, CapturePhase.OBSERVING_CLICK)
        assertEquals(CaptureOperationResult.Stale, f.coordinator.recordCaptured(SESSION_ID, captureSample(target = OTHER_TARGET)))
        assertEquals(CaptureOperationResult.Stale, f.coordinator.recordObservedCandidateResult(SESSION_ID, f.candidate.copy(targetAppId = OTHER_TARGET)))
        assertEquals(f.initial, f.store.state.value)
        assertNull(f.coordinator.serviceFailure.value)
    }

    @Test
    fun semanticDiffDeduplicatesCaps64AndReplacesOnlySameTargetActivityScope() = runTest {
        val f = CaptureLifecycleFixture(backgroundScope, CapturePhase.OBSERVING_CLICK)
        val selectors = (0 until 70).map { CapturedSelector(resourceId = "${TARGET.packageName}:id/close_$it") }
        val evidence = SemanticDiffEvidence(TARGET, f.sample.activityName, selectors.flatMap { listOf(it, it) }, LIFECYCLE_EPOCH_MS)

        assertEquals(CaptureOperationResult.Success(SESSION_ID), f.coordinator.recordSemanticDiffEvidence(SESSION_ID, evidence))

        assertEquals(
            f.initial.semanticDiffEvidence.filterNot { it.targetAppId == TARGET } + evidence.copy(selectors = selectors.take(64)),
            f.store.state.value.semanticDiffEvidence,
        )
        assertEquals(f.initial.samples, f.store.state.value.samples)
        assertEquals(f.initial.activeSession, f.store.state.value.activeSession)
    }

    @Test
    fun semanticDiffKeepsOnlyLatest10TargetActivityScopes() = runTest {
        val f = CaptureLifecycleFixture(backgroundScope, CapturePhase.OBSERVING_CLICK)
        val evidence = (0 until 12).map { index ->
            SemanticDiffEvidence(TARGET, "${TARGET.packageName}.Activity$index", f.sample.preClickUniqueSelectors, LIFECYCLE_EPOCH_MS + index)
        }
        for (item in evidence) {
            assertEquals(CaptureOperationResult.Success(SESSION_ID), f.coordinator.recordSemanticDiffEvidence(SESSION_ID, item))
        }
        assertEquals(evidence.takeLast(10), f.store.state.value.semanticDiffEvidence)
        assertEquals(f.initial.samples, f.store.state.value.samples)
    }

    @Test
    fun semanticDiffSurvivesSessionReplacementButOldIdCannotOverwriteNewEvidence() = runTest {
        val f = CaptureLifecycleFixture(backgroundScope, CapturePhase.OBSERVING_CLICK)
        val evidence = SemanticDiffEvidence(TARGET, f.sample.activityName, f.sample.preClickUniqueSelectors, LIFECYCLE_EPOCH_MS)
        assertEquals(CaptureOperationResult.Success(SESSION_ID), f.coordinator.recordSemanticDiffEvidence(SESSION_ID, evidence))
        val retained = f.store.state.value.semanticDiffEvidence
        assertEquals(CaptureOperationResult.Success(), f.coordinator.cancel(SESSION_ID))
        val newId = requireNotNull((f.coordinator.requestStart() as CaptureOperationResult.Success).sessionId)
        assertEquals(CaptureOperationResult.Success(newId), f.coordinator.markConfigured(newId))
        assertEquals(CaptureOperationResult.Success(newId), f.observe(newId))
        assertEquals(retained, f.store.state.value.semanticDiffEvidence)
        val before = f.store.state.value
        val attempts = f.store.attempts
        assertEquals(CaptureOperationResult.Stale, f.coordinator.recordSemanticDiffEvidence(SESSION_ID, evidence.copy(selectors = emptyList())))
        assertEquals(before, f.store.state.value)
        assertEquals(attempts, f.store.attempts)
        val newer = evidence.copy(capturedAtEpochMs = LIFECYCLE_EPOCH_MS + 1)
        assertEquals(CaptureOperationResult.Success(newId), f.coordinator.recordSemanticDiffEvidence(newId, newer))
        assertEquals(newer, f.store.state.value.semanticDiffEvidence.single { it.targetAppId == TARGET })
    }

    @Test
    fun configuringServiceRestoreKeepsOriginalRemainingBudgetWithoutWriting() = runTest {
        val f = CaptureLifecycleFixture(backgroundScope, clock = DualCaptureClock(LIFECYCLE_EPOCH_MS + 1_500, 10_000))

        assertEquals(CaptureOperationResult.Success(SESSION_ID), f.coordinator.restoreServiceSession(SESSION_ID))

        assertEquals(f.initial, f.store.state.value)
        assertEquals(0, f.store.attempts)
        assertEquals(10_500L, f.deadline())
        f.clock.wallMs += 100_000
        f.clock.elapsedMs += 400
        assertEquals(CaptureOperationResult.Success(SESSION_ID), f.coordinator.restoreServiceSession(SESSION_ID))
        assertEquals(10_500L, f.deadline())
        assertEquals(0, f.store.attempts)
    }

    @Test
    fun expiredConfiguringServiceRestoreSettlesOriginalConfigurationTimeout() = runTest {
        val f = CaptureLifecycleFixture(backgroundScope, clock = DualCaptureClock(LIFECYCLE_EPOCH_MS + 2_000, 10_000))

        assertEquals(CaptureOperationResult.Success(SESSION_ID), f.coordinator.restoreServiceSession(SESSION_ID))

        assertEquals(CapturePhase.FAILED, f.session.phase)
        assertEquals(CaptureFailure.CONFIGURATION_TIMEOUT, f.session.failure)
        assertEquals(f.initial.samples, f.store.state.value.samples)
        assertEquals(1, f.store.attempts)
    }

    @Test
    fun firstServiceRestoreUsesSamePendingAndIncompletePhaseClassificationAsDisconnect() = runTest {
        for (phase in listOf(CapturePhase.CAPTURED, CapturePhase.AWAITING_CONFIRMATION, CapturePhase.WAITING_FOR_TARGET, CapturePhase.OBSERVING_CLICK)) {
            val f = CaptureLifecycleFixture(backgroundScope, phase)

            assertEquals(CaptureOperationResult.Success(SESSION_ID), f.coordinator.restoreServiceSession(SESSION_ID))

            if (phase in listOf(CapturePhase.CAPTURED, CapturePhase.AWAITING_CONFIRMATION)) {
                assertEquals(phase, f.session.phase)
                assertEquals(f.initial.activeSession?.pendingSample, f.session.pendingSample)
                assertEquals(f.initial.activeSession?.pendingObservedCandidate, f.session.pendingObservedCandidate)
                assertFalse(f.session.candidateClickListeningEnabled)
                assertTrue(f.session.blocksAutomation)
                assertNull(f.session.failure)
            } else {
                assertEquals(CapturePhase.FAILED, f.session.phase)
                assertEquals(CaptureFailure.SERVICE_UNAVAILABLE, f.session.failure)
                assertFalse(f.session.blocksAutomation)
            }
            assertEquals(f.initial.samples, f.store.state.value.samples)
            assertEquals(f.initialPreferences, f.preferences.preferences.value)
        }
    }

    @Test
    fun restoreForOldIdCannotTouchCurrentSessionOrPublishFailure() = runTest {
        val f = CaptureLifecycleFixture(backgroundScope, CapturePhase.AWAITING_CONFIRMATION)

        assertEquals(CaptureOperationResult.Stale, f.coordinator.restoreServiceSession(SESSION_ID - 1))

        assertEquals(f.initial, f.store.state.value)
        assertEquals(0, f.store.attempts)
        assertNull(f.coordinator.serviceFailure.value)
    }

    @Test
    fun restoringCandidateWriteFailureKeepsPayloadAndRetryStopsListening() = runTest {
        val f = CaptureLifecycleFixture(backgroundScope, CapturePhase.AWAITING_CONFIRMATION)
        f.store.beforeCommit = { throw IOException("恢复监听状态未提交") }

        assertEquals(CaptureOperationResult.Failure(CaptureOperationFailure.STORAGE), f.coordinator.restoreServiceSession(SESSION_ID))

        assertEquals(f.initial, f.store.state.value)
        assertEquals(CaptureServiceOperation.SERVICE_DISCONNECTED, f.coordinator.serviceFailure.value?.operation)
        f.store.beforeCommit = {}
        assertEquals(CaptureOperationResult.Success(SESSION_ID), f.coordinator.restoreServiceSession(SESSION_ID))
        assertEquals(f.candidate, f.session.pendingObservedCandidate)
        assertFalse(f.session.candidateClickListeningEnabled)
        assertNull(f.coordinator.serviceFailure.value)
        assertTrue(f.session.blocksAutomation)
    }

    @Test
    fun candidateRestoreBeforeSavePreservesPayloadForQueuedExplicitSave() = runTest {
        val f = CaptureLifecycleFixture(backgroundScope, CapturePhase.AWAITING_CONFIRMATION)
        val gate = CommitGate()
        f.store.beforeCommit = gate::pause
        val restoring = async { f.coordinator.restoreServiceSession(SESSION_ID) }
        gate.entered.await()
        val saving = async { f.coordinator.finish(SESSION_ID) }
        runCurrent()
        assertFalse(saving.isCompleted)
        assertTrue(f.preferences.writes.isEmpty())
        gate.release.complete(Unit)

        assertEquals(CaptureOperationResult.Success(SESSION_ID), restoring.await())
        assertEquals(CaptureOperationResult.Success(), saving.await())
        assertNull(f.store.state.value.activeSession)
        assertEquals(1, f.store.state.value.samples.count { it.id == SESSION_ID })
        assertEquals(f.initial.samples, f.store.state.value.samples.filterNot { it.id == SESSION_ID })
        assertEquals(listOf(TARGET to false), f.preferences.writes)
    }

    @Test
    fun saveBeforeExpiredServiceRestoreWinsByLockOrderAndOldRestoreCannotRemoveSample() = runTest {
        for (phase in listOf(CapturePhase.CAPTURED, CapturePhase.AWAITING_CONFIRMATION)) {
            val f = CaptureLifecycleFixture(backgroundScope, phase)
            f.clock.elapsedMs = f.deadline()
            val gate = CommitGate()
            f.store.beforeCommit = gate::pause
            val saving = async { f.coordinator.finish(SESSION_ID) }
            gate.entered.await()
            val restoring = async { f.coordinator.restoreServiceSession(SESSION_ID) }
            runCurrent()
            assertFalse(restoring.isCompleted)
            gate.release.complete(Unit)

            assertEquals(CaptureOperationResult.Success(), saving.await())
            assertEquals(CaptureOperationResult.Stale, restoring.await())
            assertNull(f.store.state.value.activeSession)
            assertEquals(1, f.store.state.value.samples.count { it.id == SESSION_ID })
            assertEquals(1, f.store.attempts)
            assertEquals(listOf(TARGET to false), f.preferences.writes)
        }
    }

    @Test
    fun expiredServiceRestoreBeforeSaveWinsByLockOrderAndQueuedSaveIsStale() = runTest {
        for (phase in listOf(CapturePhase.CAPTURED, CapturePhase.AWAITING_CONFIRMATION)) {
            val f = CaptureLifecycleFixture(backgroundScope, phase)
            f.clock.elapsedMs = f.deadline()
            val gate = CommitGate()
            f.store.beforeCommit = gate::pause
            val restoring = async { f.coordinator.restoreServiceSession(SESSION_ID) }
            gate.entered.await()
            val saving = async { f.coordinator.finish(SESSION_ID) }
            runCurrent()
            assertFalse(saving.isCompleted)
            gate.release.complete(Unit)

            assertEquals(CaptureOperationResult.Success(SESSION_ID), restoring.await())
            assertEquals(CaptureOperationResult.Stale, saving.await())
            assertEquals(CaptureFailure.COMPLETION_TIMEOUT, f.session.failure)
            assertEquals(CapturePhase.FAILED, f.session.phase)
            assertEquals(f.initial.samples, f.store.state.value.samples)
            assertEquals(1, f.store.attempts)
            assertTrue(f.preferences.writes.isEmpty())
        }
    }

    private suspend fun TestScope.assertRetainedOnDisconnect(phase: CapturePhase) {
        val f = CaptureLifecycleFixture(backgroundScope, phase)
        val deadline = f.deadline()
        f.clock.elapsedMs = deadline - 1
        f.coordinator.serviceDisconnected(SESSION_ID)
        runCurrent()

        assertEquals(phase, f.session.phase)
        assertEquals(f.initial.activeSession?.pendingSample, f.session.pendingSample)
        assertEquals(f.initial.activeSession?.pendingObservedCandidate, f.session.pendingObservedCandidate)
        assertFalse(f.session.candidateClickListeningEnabled)
        assertTrue(f.session.blocksAutomation)
        assertNull(f.session.failure)
        assertEquals(deadline, f.deadline())
        assertEquals(f.initial.samples, f.store.state.value.samples)
        assertEquals(f.initialPreferences, f.preferences.preferences.value)
        assertTrue(f.preferences.writes.isEmpty())
    }

    private suspend fun TestScope.assertExpiredOnDisconnect(phase: CapturePhase) {
        val f = CaptureLifecycleFixture(backgroundScope, phase)
        f.clock.elapsedMs = f.deadline()
        f.coordinator.serviceDisconnected(SESSION_ID)
        runCurrent()

        assertEquals(CapturePhase.FAILED, f.session.phase)
        assertEquals(CaptureFailure.COMPLETION_TIMEOUT, f.session.failure)
        assertNull(f.session.pendingSample)
        assertNull(f.session.pendingObservedCandidate)
        assertFalse(f.session.blocksAutomation)
        assertEquals(f.initial.samples, f.store.state.value.samples)
    }
}
