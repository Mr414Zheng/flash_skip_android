package com.zhenghuaizhi.flashskip.application.rulecapture

import com.zhenghuaizhi.flashskip.domain.model.RuleCaptureState
import com.zhenghuaizhi.flashskip.testing.*
import java.io.IOException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RuleDeletionRetryRegressionTest {
    @Test fun waitingRetryChecksChangedDataInsideOperationLockBeforeAnyDisable() = runTest {
        val f = CaptureSaveFixture(backgroundScope, testScheduler)
        val request = RuleDeletionRequest(TARGET)
        f.captureStore.beforeCommit = { throw IOException("delete") }
        assertEquals(storageFailure(), f.coordinator.clear(request))
        f.captureStore.beforeCommit = {}
        val gate = CommitGate(); f.flashStore.beforeCommit = gate::pause
        val save = async { f.coordinator.finish(SESSION_ID) }; gate.entered.await()
        val retry = async { f.coordinator.clear(request) }; runCurrent()
        assertFalse(retry.isCompleted)
        gate.release.complete(Unit); assertEquals(CaptureOperationResult.Success(), save.await())
        val switches = f.flashStore.attempts.size
        val payload = f.captureStore.state.value
        assertEquals(CaptureOperationResult.Stale, retry.await())
        assertEquals(switches, f.flashStore.attempts.size)
        assertEquals(payload, f.captureStore.state.value)
    }

    @Test fun storageSideMutationBetweenDisableAndDeleteCannotLoseNewSample() = runTest {
        val f = CaptureSaveFixture(backgroundScope, testScheduler)
        val replacement = f.initialCapture.copy(samples = f.initialCapture.samples + captureSample(999))
        f.captureStore.beforeCommit = { f.captureStore.state.value = replacement }
        assertEquals(CaptureOperationResult.Stale, f.coordinator.clearTarget(TARGET))
        assertEquals(replacement, f.captureStore.state.value)
        assertFalse(f.flashStore.isAppEnabled(TARGET))
        assertTrue(f.captureStore.writes.isEmpty())
    }

    @Test fun successfulPartialDisableThenExternalReenableInvalidatesAllRetry() = runTest {
        val f = CaptureSaveFixture(backgroundScope, testScheduler)
        val request = RuleDeletionRequest(null)
        f.flashStore.beforeCommit = { if (f.flashStore.attempts.size == 2) throw IOException("second") }
        assertEquals(storageFailure(), f.coordinator.clear(request))
        f.flashStore.beforeCommit = {}
        f.flashStore.setAppEnabled(TARGET, true)
        val attempts = f.flashStore.attempts.size
        assertEquals(CaptureOperationResult.Stale, f.coordinator.clear(request))
        assertEquals(attempts, f.flashStore.attempts.size)
        assertEquals(f.initialCapture, f.captureStore.state.value)
    }

    @Test fun lostDisableReturnMayRetryItsOwnCommittedSwitchWithoutRollback() = runTest {
        val f = CaptureSaveFixture(backgroundScope, testScheduler)
        val request = RuleDeletionRequest(null)
        f.flashStore.afterCommit = { throw IOException("return lost") }
        assertEquals(storageFailure(), f.coordinator.clear(request))
        assertFalse(f.flashStore.isAppEnabled(TARGET))
        assertEquals(f.initialCapture, f.captureStore.state.value)
        f.flashStore.afterCommit = {}
        assertEquals(CaptureOperationResult.Success(), f.coordinator.clear(request))
        assertEquals(RuleCaptureState(), f.captureStore.state.value)
    }

    @Test fun lostFinalDeleteReturnCannotDeleteLaterReplacementData() = runTest {
        val f = CaptureSaveFixture(backgroundScope, testScheduler)
        val request = RuleDeletionRequest(TARGET)
        f.captureStore.afterCommit = { throw IOException("return lost") }
        assertEquals(storageFailure(), f.coordinator.clear(request))
        f.captureStore.afterCommit = {}
        val replacement = RuleCaptureState(samples = listOf(captureSample(999)))
        f.captureStore.state.value = replacement
        val attempts = f.flashStore.attempts.size
        assertEquals(CaptureOperationResult.Stale, f.coordinator.clear(request))
        assertEquals(replacement, f.captureStore.state.value)
        assertEquals(attempts, f.flashStore.attempts.size)
    }

    private fun storageFailure() = CaptureOperationResult.Failure(CaptureOperationFailure.STORAGE)
}
