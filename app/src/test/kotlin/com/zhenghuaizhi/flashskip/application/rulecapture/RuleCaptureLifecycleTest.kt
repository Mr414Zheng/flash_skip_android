package com.zhenghuaizhi.flashskip.application.rulecapture

import com.zhenghuaizhi.flashskip.domain.model.CaptureEvidence
import com.zhenghuaizhi.flashskip.domain.model.CaptureFailure
import com.zhenghuaizhi.flashskip.domain.model.CapturePhase
import com.zhenghuaizhi.flashskip.testing.CaptureLifecycleFixture
import com.zhenghuaizhi.flashskip.testing.LIFECYCLE_EPOCH_MS
import com.zhenghuaizhi.flashskip.testing.OTHER_TARGET
import com.zhenghuaizhi.flashskip.testing.SESSION_ID
import com.zhenghuaizhi.flashskip.testing.TARGET
import com.zhenghuaizhi.flashskip.testing.capturePhaseBudget
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(Parameterized::class)
class RuleCaptureLifecycleTest(private val phase: CapturePhase) {
    @Test
    fun startRejectsBlockingSessionAndReplacesOnlyFailedSession() = runTest {
        val f = CaptureLifecycleFixture(backgroundScope, phase)
        val result = f.coordinator.requestStart()
        if (phase == CapturePhase.FAILED) {
            val id = requireNotNull((result as CaptureOperationResult.Success).sessionId)
            assertTrue(id > SESSION_ID)
            assertEquals(CapturePhase.CONFIGURING, f.session.phase)
            assertEquals(f.clock.wallMs + capturePhaseBudget(CapturePhase.CONFIGURING), f.session.deadlineAtEpochMs)
            assertNull(f.session.pendingSample)
            assertNull(f.session.pendingObservedCandidate)
        } else {
            assertEquals(CaptureOperationResult.Stale, result)
            assertEquals(f.initial, f.store.state.value)
            assertEquals(0, f.store.attempts)
        }
        assertEquals(f.initial.samples, f.store.state.value.samples)
        assertEquals(f.initialPreferences, f.preferences.preferences.value)
    }

    @Test
    fun configurationAdvancesOnlyConfiguringPhase() = runTest {
        val f = CaptureLifecycleFixture(backgroundScope, phase)
        val result = f.coordinator.markConfigured(SESSION_ID)
        if (phase == CapturePhase.CONFIGURING) {
            assertEquals(CaptureOperationResult.Success(SESSION_ID), result)
            assertEquals(CapturePhase.WAITING_FOR_TARGET, f.session.phase)
            assertEquals(f.clock.wallMs, f.session.phaseStartedAtEpochMs)
            assertEquals(f.clock.wallMs + capturePhaseBudget(CapturePhase.WAITING_FOR_TARGET), f.session.deadlineAtEpochMs)
        } else {
            assertEquals(CaptureOperationResult.Stale, result)
            assertEquals(f.initial, f.store.state.value)
        }
    }

    @Test
    fun targetObservationAdvancesWaitingOrUpdatesSameObservingTarget() = runTest {
        val f = CaptureLifecycleFixture(backgroundScope, phase)
        val old = f.session
        val result = f.observe()
        if (phase in setOf(CapturePhase.WAITING_FOR_TARGET, CapturePhase.OBSERVING_CLICK)) {
            assertEquals(CaptureOperationResult.Success(SESSION_ID), result)
            assertEquals(CapturePhase.OBSERVING_CLICK, f.session.phase)
            assertEquals(TARGET, f.session.targetAppId)
            assertEquals(7, f.session.windowId)
            if (phase == CapturePhase.WAITING_FOR_TARGET) {
                assertEquals(f.clock.elapsedMs, f.session.targetEnteredAtElapsedMs)
            } else {
                assertEquals(old.phaseStartedAtEpochMs, f.session.phaseStartedAtEpochMs)
                assertEquals(old.deadlineAtEpochMs, f.session.deadlineAtEpochMs)
                assertEquals(old.targetEnteredAtElapsedMs, f.session.targetEnteredAtElapsedMs)
                val beforeWrongTarget = f.store.state.value
                assertEquals(CaptureOperationResult.Stale, f.coordinator.observeTarget(
                    SESSION_ID, OTHER_TARGET, "其他应用", OTHER_TARGET.packageName, f.sample.activityName, 9, f.clock.elapsedMs,
                ))
                assertEquals(beforeWrongTarget, f.store.state.value)
            }
        } else {
            assertEquals(CaptureOperationResult.Stale, result)
            assertEquals(f.initial, f.store.state.value)
        }
    }

    @Test
    fun capturedEvidenceIsAcceptedOnlyInWaitingOrObserving() = runTest {
        val f = CaptureLifecycleFixture(backgroundScope, phase)
        val result = f.coordinator.recordCaptured(SESSION_ID, f.sample)
        if (phase in setOf(CapturePhase.WAITING_FOR_TARGET, CapturePhase.OBSERVING_CLICK)) {
            assertEquals(CaptureOperationResult.Success(SESSION_ID), result)
            assertEquals(CapturePhase.CAPTURED, f.session.phase)
            assertEquals(f.sample, f.session.pendingSample)
            assertNull(f.session.pendingObservedCandidate)
            assertFalse(f.session.candidateClickListeningEnabled)
        } else {
            assertEquals(CaptureOperationResult.Stale, result)
            assertEquals(f.initial, f.store.state.value)
        }
        assertEquals(f.initial.samples, f.store.state.value.samples)
    }

    @Test
    fun candidateEvidenceIsAcceptedOnlyInWaitingOrObserving() = runTest {
        val f = CaptureLifecycleFixture(backgroundScope, phase)
        val result = f.coordinator.recordObservedCandidateResult(SESSION_ID, f.candidate)
        if (phase in setOf(CapturePhase.WAITING_FOR_TARGET, CapturePhase.OBSERVING_CLICK)) {
            assertEquals(CaptureOperationResult.Success(SESSION_ID), result)
            assertEquals(CapturePhase.AWAITING_CONFIRMATION, f.session.phase)
            assertEquals(f.candidate, f.session.pendingObservedCandidate)
            assertNull(f.session.pendingSample)
            assertTrue(f.session.candidateClickListeningEnabled)
        } else {
            assertEquals(CaptureOperationResult.Stale, result)
            assertEquals(f.initial, f.store.state.value)
        }
        assertEquals(f.initial.samples, f.store.state.value.samples)
    }

    @Test
    fun realClickUpgradeRequiresListeningCandidateAndKeepsRealEvidence() = runTest {
        val f = CaptureLifecycleFixture(backgroundScope, phase)
        val result = f.upgrade()
        if (phase == CapturePhase.AWAITING_CONFIRMATION) {
            assertEquals(CaptureOperationResult.Success(SESSION_ID), result)
            assertEquals(CapturePhase.CAPTURED, f.session.phase)
            assertEquals(CaptureEvidence.ACCESSIBILITY_CLICK, f.session.pendingSample?.evidence)
            assertNull(f.session.pendingObservedCandidate)
            assertFalse(f.session.candidateClickListeningEnabled)
        } else {
            assertEquals(CaptureOperationResult.Stale, result)
            assertEquals(f.initial, f.store.state.value)
        }
    }

    @Test
    fun departureIsIdempotentAndDoesNotRenewStageBudget() = runTest {
        val f = CaptureLifecycleFixture(backgroundScope, phase)
        if (phase == CapturePhase.FAILED) {
            assertEquals(CaptureOperationResult.Stale, f.coordinator.markLeftFlashSkip(SESSION_ID))
            assertEquals(f.initial, f.store.state.value)
            return@runTest
        }
        val deadline = f.deadline()
        assertEquals(CaptureOperationResult.Success(SESSION_ID), f.coordinator.markLeftFlashSkip(SESSION_ID))
        assertEquals(LIFECYCLE_EPOCH_MS, f.session.leftFlashSkipAtEpochMs)
        val afterFirst = f.store.state.value
        val attempts = f.store.attempts
        f.clock.wallMs += 50_000
        f.clock.elapsedMs += 100
        assertEquals(CaptureOperationResult.Success(SESSION_ID), f.coordinator.markLeftFlashSkip(SESSION_ID))
        assertEquals(afterFirst, f.store.state.value)
        assertEquals(attempts, f.store.attempts)
        assertEquals(deadline, f.deadline())
    }

    @Test
    fun returnWithoutObservedOrPersistedDepartureIsStale() = runTest {
        val f = CaptureLifecycleFixture(backgroundScope, phase)
        assertEquals(CaptureOperationResult.Stale, f.coordinator.reconcileReturn(SESSION_ID))
        assertEquals(f.initial, f.store.state.value)
        assertEquals(0, f.store.attempts)
    }

    @Test
    fun persistedDepartureReturnSettlesPhaseAndPreservesPendingEvidence() = runTest {
        val f = CaptureLifecycleFixture(backgroundScope, phase, sessionTransform = { it.copy(leftFlashSkipAtEpochMs = LIFECYCLE_EPOCH_MS - 10) })
        assertEquals(CaptureOperationResult.Success(SESSION_ID), f.coordinator.reconcileReturn(SESSION_ID))
        assertReturnSettlement(f)
    }

    @Test
    fun observedDepartureFallbackSettlesEvenWhenDepartureWriteWasLost() = runTest {
        val f = CaptureLifecycleFixture(backgroundScope, phase)
        assertEquals(CaptureOperationResult.Success(SESSION_ID), f.coordinator.reconcileReturn(SESSION_ID, observedDeparture = true))
        assertReturnSettlement(f)
    }

    @Test
    fun oldSessionRequestsNeverWriteOrDamageCurrentSession() = runTest {
        val f = CaptureLifecycleFixture(backgroundScope, phase)
        val old = SESSION_ID - 1
        val results = listOf(
            f.coordinator.markConfigured(old), f.observe(old),
            f.coordinator.recordCaptured(old, f.sample),
            f.coordinator.recordObservedCandidateResult(old, f.candidate), f.upgrade(old),
            f.coordinator.markLeftFlashSkip(old), f.coordinator.reconcileReturn(old, true),
            f.coordinator.cancel(old), f.coordinator.fail(old, CaptureFailure.SERVICE_UNAVAILABLE),
        )
        results.forEach { assertEquals(CaptureOperationResult.Stale, it) }
        f.coordinator.serviceDisconnected(old)
        runCurrent()
        assertEquals(f.initial, f.store.state.value)
        assertEquals(0, f.store.attempts)
        assertEquals(f.initialPreferences, f.preferences.preferences.value)
    }

    @Test
    fun awaitConfiguredRecognizesConfiguredStagesWithoutMutatingThem() = runTest {
        val f = CaptureLifecycleFixture(backgroundScope, phase)
        if (phase == CapturePhase.CONFIGURING) {
            val awaiting = async { f.coordinator.awaitConfigured(SESSION_ID) }
            runCurrent()
            assertFalse(awaiting.isCompleted)
            assertEquals(CaptureOperationResult.Success(SESSION_ID), f.coordinator.markConfigured(SESSION_ID))
            assertTrue(awaiting.await())
        } else {
            assertEquals(phase != CapturePhase.FAILED, f.coordinator.awaitConfigured(SESSION_ID))
            assertEquals(f.initial, f.store.state.value)
        }
    }

    private fun assertReturnSettlement(f: CaptureLifecycleFixture) {
        assertNull(f.session.leftFlashSkipAtEpochMs)
        when (phase) {
            CapturePhase.CONFIGURING, CapturePhase.WAITING_FOR_TARGET -> assertEquals(CaptureFailure.RETURNED_BEFORE_TARGET, f.session.failure)
            CapturePhase.OBSERVING_CLICK -> assertEquals(CaptureFailure.DISMISS_NOT_CAPTURED, f.session.failure)
            CapturePhase.CAPTURED -> assertEquals(f.sample, f.session.pendingSample)
            CapturePhase.AWAITING_CONFIRMATION -> {
                assertEquals(f.candidate, f.session.pendingObservedCandidate)
                assertFalse(f.session.candidateClickListeningEnabled)
            }
            CapturePhase.FAILED -> assertEquals(CapturePhase.FAILED, f.session.phase)
        }
        if (phase in setOf(CapturePhase.CONFIGURING, CapturePhase.WAITING_FOR_TARGET, CapturePhase.OBSERVING_CLICK)) {
            assertEquals(CapturePhase.FAILED, f.session.phase)
            assertFalse(f.session.blocksAutomation)
        } else assertEquals(phase, f.session.phase)
        assertEquals(f.initial.samples, f.store.state.value.samples)
        assertEquals(f.initial.semanticDiffEvidence, f.store.state.value.semanticDiffEvidence)
        assertEquals(f.initialPreferences, f.preferences.preferences.value)
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "phase={0}")
        fun phases(): List<Array<CapturePhase>> = CapturePhase.entries.map { arrayOf(it) }
    }
}
