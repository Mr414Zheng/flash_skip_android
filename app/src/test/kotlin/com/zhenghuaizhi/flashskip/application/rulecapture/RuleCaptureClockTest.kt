package com.zhenghuaizhi.flashskip.application.rulecapture

import com.zhenghuaizhi.flashskip.domain.model.CaptureFailure
import com.zhenghuaizhi.flashskip.domain.model.CapturePhase
import com.zhenghuaizhi.flashskip.domain.ports.MonotonicClock
import com.zhenghuaizhi.flashskip.testing.CaptureLifecycleFixture
import com.zhenghuaizhi.flashskip.testing.DualCaptureClock
import com.zhenghuaizhi.flashskip.testing.LIFECYCLE_EPOCH_MS
import com.zhenghuaizhi.flashskip.testing.SESSION_ID
import com.zhenghuaizhi.flashskip.testing.capturePhaseBudget
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class RuleCaptureClockTest {
    @Test
    fun restoredStageUsesRemainingWallBudgetForAllActivePhases() = runTest {
        for (phase in ACTIVE_PHASES) {
            val remaining = 500L
            val clock = DualCaptureClock(LIFECYCLE_EPOCH_MS + capturePhaseBudget(phase) - remaining, 4_000L)
            val f = CaptureLifecycleFixture(backgroundScope, phase, clock)

            assertEquals(clock.elapsedMs + remaining, f.deadline())
            assertEquals(f.initial, f.store.state.value)
            assertEquals(0, f.store.attempts)
        }
    }

    @Test
    fun restoredDeadlineNeverExceedsConfiguredPhaseBudget() = runTest {
        for (phase in ACTIVE_PHASES) {
            val f = CaptureLifecycleFixture(backgroundScope, phase, sessionTransform = {
                it.copy(deadlineAtEpochMs = LIFECYCLE_EPOCH_MS + capturePhaseBudget(phase) * 10)
            })
            assertEquals(f.clock.elapsedMs + capturePhaseBudget(phase), f.deadline())
        }
    }

    @Test
    fun expiredOrFutureStartedRestoredStagesHaveZeroRemainingBudget() = runTest {
        for (phase in ACTIVE_PHASES) {
            for (futureStart in listOf(false, true)) {
                val f = CaptureLifecycleFixture(backgroundScope, phase, sessionTransform = {
                    if (futureStart) it.copy(phaseStartedAtEpochMs = LIFECYCLE_EPOCH_MS + 1)
                    else it.copy(deadlineAtEpochMs = LIFECYCLE_EPOCH_MS - 1)
                })
                val key = CaptureExpiryKey.from(f.session)
                assertEquals(f.clock.elapsedMs, f.deadline())
                assertEquals(CaptureOperationResult.Success(SESSION_ID), f.coordinator.expireExpected(key))
                assertEquals(expectedTimeout(phase), f.session.failure)
                assertEquals(CapturePhase.FAILED, f.session.phase)
                assertEquals(f.initial.samples, f.store.state.value.samples)
            }
        }
    }

    @Test
    fun wallClockJumpsCannotRenewOrExpireAnEstablishedMonotonicPlan() = runTest {
        for (phase in ACTIVE_PHASES) {
            for (wallJump in listOf(-1_000_000L, 1_000_000L)) {
                val f = CaptureLifecycleFixture(backgroundScope, phase)
                val key = CaptureExpiryKey.from(f.session)
                val deadline = f.deadline()
                f.clock.wallMs += wallJump
                f.clock.elapsedMs = deadline - 1
                assertEquals(deadline, f.deadline())
                assertEquals(CaptureOperationResult.Pending, f.coordinator.expireExpected(key))
                assertEquals(f.initial, f.store.state.value)
                assertEquals(0, f.store.attempts)
                f.clock.elapsedMs = deadline
                assertEquals(CaptureOperationResult.Success(SESSION_ID), f.coordinator.expireExpected(key))
                assertEquals(expectedTimeout(phase), f.session.failure)
            }
        }
    }

    @Test
    fun samePhaseTargetObservationAndDepartureReturnDoNotReplenishBudget() = runTest {
        for (phase in listOf(CapturePhase.OBSERVING_CLICK, CapturePhase.CAPTURED, CapturePhase.AWAITING_CONFIRMATION)) {
            val f = CaptureLifecycleFixture(backgroundScope, phase)
            val deadline = f.deadline()
            f.clock.elapsedMs += 100
            f.clock.wallMs += 100
            if (phase == CapturePhase.OBSERVING_CLICK) {
                assertEquals(CaptureOperationResult.Success(SESSION_ID), f.observe())
            } else {
                assertEquals(CaptureOperationResult.Success(SESSION_ID), f.coordinator.markLeftFlashSkip(SESSION_ID))
                f.clock.elapsedMs += 100
                f.clock.wallMs += 100
                assertEquals(CaptureOperationResult.Success(SESSION_ID), f.coordinator.reconcileReturn(SESSION_ID))
            }
            assertEquals(deadline, f.deadline())
            assertEquals(f.initial.activeSession?.phaseStartedAtEpochMs, f.session.phaseStartedAtEpochMs)
            assertEquals(f.initial.activeSession?.deadlineAtEpochMs, f.session.deadlineAtEpochMs)
        }
    }

    @Test
    fun oldExpiryKeyCannotTerminateAStageThatAlreadyAdvanced() = runTest {
        val f = CaptureLifecycleFixture(backgroundScope)
        val oldKey = CaptureExpiryKey.from(f.session)
        val oldDeadline = f.deadline()
        assertEquals(CaptureOperationResult.Success(SESSION_ID), f.coordinator.markConfigured(SESSION_ID))
        val configured = f.store.state.value
        val attempts = f.store.attempts
        f.clock.elapsedMs = oldDeadline + 1

        assertEquals(CaptureOperationResult.Stale, f.coordinator.expireExpected(oldKey))

        assertEquals(configured, f.store.state.value)
        assertEquals(attempts, f.store.attempts)
        assertNull(f.coordinator.expiryPlan(oldKey))
    }

    @Test
    fun samePhaseStartOrDeadlineChangeRejectsOldExpiryKeyWithoutWriting() = runTest {
        for (changeStart in listOf(true, false)) {
            val f = CaptureLifecycleFixture(backgroundScope, CapturePhase.OBSERVING_CLICK)
            val original = f.session
            val oldKey = CaptureExpiryKey.from(original)
            val oldDeadline = f.deadline()
            val replacement = if (changeStart) original.copy(phaseStartedAtEpochMs = original.phaseStartedAtEpochMs + 1)
            else original.copy(deadlineAtEpochMs = original.deadlineAtEpochMs + 1)
            f.store.state.value = f.initial.copy(activeSession = replacement)
            f.clock.elapsedMs = oldDeadline + 1

            assertEquals(CaptureOperationResult.Stale, f.coordinator.expireExpected(oldKey))

            assertEquals(replacement, f.session)
            assertEquals(0, f.store.attempts)
            assertNull(f.coordinator.expiryPlan(oldKey))
        }
    }

    @Test
    fun firstDeadlineCheckRejectsZeroRestoredBudgetEvenWhenClockAdvancesBetweenReads() = runTest {
        val f = CaptureLifecycleFixture(backgroundScope, sessionTransform = {
            it.copy(phaseStartedAtEpochMs = LIFECYCLE_EPOCH_MS + 1)
        })
        var elapsed = f.clock.elapsedMs
        val tickingClock = object : MonotonicClock {
            override fun elapsedRealtimeMs(): Long = ++elapsed
        }
        val coordinator = RuleCaptureCoordinator(f.store, f.preferences, tickingClock, f.clock, backgroundScope, f.catalog)

        assertEquals(CaptureOperationResult.Stale, coordinator.markConfigured(SESSION_ID))

        assertEquals(f.initial, f.store.state.value)
        assertEquals(0, f.store.attempts)
        assertEquals(CaptureOperationResult.Success(SESSION_ID), coordinator.expireExpected(CaptureExpiryKey.from(f.session)))
        assertEquals(CaptureFailure.CONFIGURATION_TIMEOUT, f.session.failure)
    }

    @Test
    fun saveKeepsExistingLockOrderingWithoutAddingHardDeadline() = runTest {
        for (phase in listOf(CapturePhase.CAPTURED, CapturePhase.AWAITING_CONFIRMATION)) {
            val f = CaptureLifecycleFixture(backgroundScope, phase)
            f.clock.elapsedMs = f.deadline() + 1

            assertEquals(CaptureOperationResult.Success(), f.coordinator.finish(SESSION_ID))

            assertNull(f.store.state.value.activeSession)
            assertEquals(1, f.store.state.value.samples.count { it.id == SESSION_ID })
            assertEquals(listOf("switch:attempt", "switch:commit", "capture:attempt", "capture:commit"), f.events)
            assertEquals(false, f.preferences.preferences.value.enabledApps[f.sample.targetAppId])
        }
    }

    private fun expectedTimeout(phase: CapturePhase): CaptureFailure = when (phase) {
        CapturePhase.CONFIGURING -> CaptureFailure.CONFIGURATION_TIMEOUT
        CapturePhase.WAITING_FOR_TARGET -> CaptureFailure.TARGET_TIMEOUT
        CapturePhase.OBSERVING_CLICK -> CaptureFailure.CLICK_TIMEOUT
        CapturePhase.CAPTURED, CapturePhase.AWAITING_CONFIRMATION -> CaptureFailure.COMPLETION_TIMEOUT
        CapturePhase.FAILED -> error("失败阶段没有恢复预算")
    }

    companion object {
        private val ACTIVE_PHASES = CapturePhase.entries.filterNot { it == CapturePhase.FAILED }
    }
}
