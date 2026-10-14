package com.zhenghuaizhi.flashskip.application.rulecapture

import com.zhenghuaizhi.flashskip.testing.CaptureLifecycleFixture
import com.zhenghuaizhi.flashskip.testing.CommitGate
import com.zhenghuaizhi.flashskip.testing.LifecycleTransition
import com.zhenghuaizhi.flashskip.testing.SESSION_ID
import com.zhenghuaizhi.flashskip.testing.capturePhaseBudget
import com.zhenghuaizhi.flashskip.testing.perform
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(Parameterized::class)
class RuleCaptureDeadlineTest(private val transition: LifecycleTransition) {
    @Test
    fun oneMillisecondBeforeDeadlineAcceptsValidTransition() = runTest {
        val f = CaptureLifecycleFixture(backgroundScope, transition.source)
        f.clock.elapsedMs = f.deadline() - 1

        assertEquals(CaptureOperationResult.Success(SESSION_ID), f.perform(transition))

        assertEquals(transition.destination, f.session.phase)
        assertEquals(f.initial.samples, f.store.state.value.samples)
        assertEquals(f.initialPreferences, f.preferences.preferences.value)
        assertNull(f.coordinator.serviceFailure.value)
    }

    @Test
    fun exactlyAtDeadlineRejectsTransitionAndLeavesExpirySettlementToTimer() = runTest {
        assertExpiredTransition(offset = 0)
    }

    @Test
    fun oneMillisecondAfterDeadlineRejectsTransitionAndPreservesEvidence() = runTest {
        assertExpiredTransition(offset = 1)
    }

    @Test
    fun waitingForOperationLockRechecksDeadlineAtEquality() = runTest {
        assertLockCrossing(offset = 0)
    }

    @Test
    fun waitingForOperationLockRechecksDeadlineAfterExpiry() = runTest {
        assertLockCrossing(offset = 1)
    }

    @Test
    fun storageWaitConsumesNewStageBudgetInsteadOfStartingItAfterCommit() = runTest {
        val f = CaptureLifecycleFixture(backgroundScope, transition.source)
        val oldDeadline = f.deadline()
        f.clock.elapsedMs = oldDeadline - 1
        val startedAt = f.clock.elapsedMs
        val gate = CommitGate()
        f.store.beforeCommit = gate::pause
        val updating = async { f.perform(transition) }
        gate.entered.await()

        f.clock.elapsedMs += 500
        f.clock.wallMs += 500
        gate.release.complete(Unit)

        assertEquals(CaptureOperationResult.Success(SESSION_ID), updating.await())
        assertEquals(transition.destination, f.session.phase)
        if (transition == LifecycleTransition.TARGET_UPDATE) {
            // 同阶段窗口更新只消耗既有预算，不得重新发放观察期限。
            assertEquals(oldDeadline, f.deadline())
            assertEquals(f.initial.activeSession?.phaseStartedAtEpochMs, f.session.phaseStartedAtEpochMs)
            assertEquals(f.initial.activeSession?.deadlineAtEpochMs, f.session.deadlineAtEpochMs)
        } else {
            assertEquals(startedAt + capturePhaseBudget(transition.destination), f.deadline())
            assertEquals(capturePhaseBudget(transition.destination) - 500, f.deadline() - f.clock.elapsedMs)
        }
    }

    private suspend fun TestScope.assertExpiredTransition(offset: Long) {
        val f = CaptureLifecycleFixture(backgroundScope, transition.source)
        val originalKey = CaptureExpiryKey.from(f.session)
        f.clock.elapsedMs = f.deadline() + offset

        assertEquals(CaptureOperationResult.Stale, f.perform(transition))

        assertEquals(f.initial, f.store.state.value)
        assertEquals(0, f.store.attempts)
        assertEquals(f.initialPreferences, f.preferences.preferences.value)
        assertNull(f.coordinator.serviceFailure.value)
        assertEquals(CaptureOperationResult.Success(SESSION_ID), f.coordinator.expireExpected(originalKey))
        assertFalse(f.session.blocksAutomation)
    }

    private suspend fun TestScope.assertLockCrossing(offset: Long) {
        val f = CaptureLifecycleFixture(backgroundScope, transition.source)
        val deadline = f.deadline()
        f.clock.elapsedMs = deadline - 1
        val gate = CommitGate()
        val lockHolder = async {
            f.coordinator.runServiceUpdate(CaptureServiceOperation.OBSERVE_TARGET, SESSION_ID) {
                gate.pause()
                CaptureOperationResult.Pending
            }
        }
        gate.entered.await()
        val incoming = async { f.perform(transition) }
        runCurrent()
        assertFalse(incoming.isCompleted)
        assertEquals(0, f.store.attempts)

        f.clock.elapsedMs = deadline + offset
        gate.release.complete(Unit)
        assertEquals(CaptureOperationResult.Pending, lockHolder.await())

        assertEquals(CaptureOperationResult.Stale, incoming.await())
        assertEquals(f.initial, f.store.state.value)
        assertEquals(0, f.store.attempts)
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "transition={0}")
        fun transitions(): List<Array<LifecycleTransition>> = LifecycleTransition.entries.map { arrayOf(it) }
    }
}
