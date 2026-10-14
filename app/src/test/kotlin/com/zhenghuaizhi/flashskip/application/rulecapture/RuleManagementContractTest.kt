package com.zhenghuaizhi.flashskip.application.rulecapture

import com.zhenghuaizhi.flashskip.domain.model.*
import com.zhenghuaizhi.flashskip.testing.*
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RuleManagementContractTest {
    @Test fun targetDeleteDisablesBeforeRemovingOnlyTargetPayload() = runTest {
        val f = CaptureSaveFixture(backgroundScope, testScheduler)
        assertEquals(CaptureOperationResult.Success(), f.coordinator.clearTarget(TARGET))
        assertEquals(listOf("switch:attempt", "switch:commit", "capture:attempt", "capture:commit"), f.events)
        assertEquals(f.initialCapture.samples.filter { it.targetAppId == OTHER_TARGET }, f.captureStore.state.value.samples)
        assertNull(f.captureStore.state.value.activeSession)
        assertEquals(f.initialCapture.semanticDiffEvidence.filter { it.targetAppId == OTHER_TARGET }, f.captureStore.state.value.semanticDiffEvidence)
        assertEquals(f.initialCapture, f.commits.first().capture)
        assertEquals(f.initialPreferences.records, f.flashStore.preferences.value.records)
        assertTrue(f.flashStore.isAppEnabled(OTHER_TARGET)); assertFalse(f.flashStore.isAppEnabled(TARGET))
    }

    @Test fun otherTargetDeletePreservesActiveSessionAndUnrelatedEvidence() = runTest {
        val f = CaptureSaveFixture(backgroundScope, testScheduler)
        f.coordinator.clearTarget(OTHER_TARGET)
        assertEquals(f.initialCapture.activeSession, f.captureStore.state.value.activeSession)
        assertEquals(f.initialCapture.samples.filter { it.targetAppId == TARGET }, f.captureStore.state.value.samples)
        assertEquals(f.initialCapture.semanticDiffEvidence.filter { it.targetAppId == TARGET }, f.captureStore.state.value.semanticDiffEvidence)
    }

    @Test fun targetDisableFailureLeavesAllCaptureAndSwitchesUntouched() = runTest {
        val f = CaptureSaveFixture(backgroundScope, testScheduler)
        f.flashStore.beforeCommit = { throw IOException("disable") }
        assertEquals(storageFailure(), f.coordinator.clearTarget(TARGET))
        assertEquals(f.initialCapture, f.captureStore.state.value)
        assertEquals(f.initialPreferences, f.flashStore.preferences.value)
        assertEquals(0, f.captureStore.attempts)
    }

    @Test fun captureDeleteFailureKeepsTargetDisabledAndOriginalPayload() = runTest {
        val f = CaptureSaveFixture(backgroundScope, testScheduler)
        f.captureStore.beforeCommit = { throw IOException("delete") }
        assertEquals(storageFailure(), f.coordinator.clearTarget(TARGET))
        assertEquals(f.initialCapture, f.captureStore.state.value)
        assertFalse(f.flashStore.isAppEnabled(TARGET))
        f.captureStore.beforeCommit = {}
        assertEquals(CaptureOperationResult.Success(), f.coordinator.clearTarget(TARGET))
        assertEquals(1, f.captureStore.writes.size)
    }

    @Test fun allDeleteIncludesOrphanSwitchAndSessionOnlyTarget() = runTest {
        val f = CaptureSaveFixture(backgroundScope, testScheduler)
        val orphan = TargetAppId("com.example.orphan")
        val sessionOnly = TargetAppId("com.example.session")
        f.flashStore.preferences.value = f.initialPreferences.copy(enabledApps = f.initialPreferences.enabledApps + (orphan to true))
        f.captureStore.state.value = f.initialCapture.copy(activeSession = f.initialCapture.activeSession!!.copy(targetAppId = sessionOnly))
        assertEquals(CaptureOperationResult.Success(), f.coordinator.clearAll())
        assertEquals(setOf(TARGET, OTHER_TARGET, orphan, sessionOnly), f.flashStore.writes.map { it.first }.toSet())
        assertEquals(RuleCaptureState(), f.captureStore.state.value)
        assertTrue(f.flashStore.preferences.value.enabledApps.values.none { it })
        assertEquals(f.initialPreferences.records, f.flashStore.preferences.value.records)
    }

    @Test fun allDeleteFirstDisableFailureDoesNotContinue() = bulkFailure(1)
    @Test fun allDeleteSecondDisableFailureRetainsFirstDisableAndAllPayload() = bulkFailure(2)
    @Test fun allDeleteLastDisableFailureRetainsEarlierDisablesAndAllPayload() = bulkFailure(3)

    @Test fun allDeleteFinalWriteFailureLeavesEveryTargetDisabledAndAllPayload() = runTest {
        val f = CaptureSaveFixture(backgroundScope, testScheduler)
        f.captureStore.beforeCommit = { throw IOException("capture") }
        assertEquals(storageFailure(), f.coordinator.clearAll())
        assertEquals(f.initialCapture, f.captureStore.state.value)
        assertTrue(f.flashStore.preferences.value.enabledApps.values.none { it })
        f.captureStore.beforeCommit = {}
        assertEquals(CaptureOperationResult.Success(), f.coordinator.clearAll())
        assertEquals(RuleCaptureState(), f.captureStore.state.value)
        assertEquals(f.initialPreferences.records, f.flashStore.preferences.value.records)
    }

    @Test fun deletingMissingTargetIsIdempotentAndPreservesOthers() = runTest {
        val f = CaptureSaveFixture(backgroundScope, testScheduler)
        val missing = TargetAppId("com.example.missing")
        repeat(2) { assertEquals(CaptureOperationResult.Success(), f.coordinator.clearTarget(missing)) }
        assertEquals(f.initialCapture, f.captureStore.state.value)
        assertTrue(f.flashStore.isAppEnabled(TARGET))
    }

    @Test fun repeatedAllDeleteKeepsRecordsAndEmptyCapture() = runTest {
        val f = CaptureSaveFixture(backgroundScope, testScheduler)
        repeat(2) { assertEquals(CaptureOperationResult.Success(), f.coordinator.clearAll()) }
        assertEquals(RuleCaptureState(), f.captureStore.state.value)
        assertEquals(f.initialPreferences.records, f.flashStore.preferences.value.records)
    }

    @Test fun cancellationBeforeDisableCommitLeavesEverythingUnchanged() = runTest {
        val f = CaptureSaveFixture(backgroundScope, testScheduler)
        val gate = CommitGate(); f.flashStore.beforeCommit = gate::pause
        val task = async { f.coordinator.clearTarget(TARGET) }
        gate.entered.await(); task.cancelAndJoin()
        assertTrue(task.isCancelled)
        assertEquals(f.initialCapture, f.captureStore.state.value)
        assertEquals(f.initialPreferences, f.flashStore.preferences.value)
        assertEquals(0, f.captureStore.attempts)
    }

    @Test fun cancellationBetweenStoresPreservesDisableWithoutDeletingPayload() = runTest {
        val f = CaptureSaveFixture(backgroundScope, testScheduler)
        val gate = CommitGate(); f.captureStore.beforeCommit = gate::pause
        val task = async { f.coordinator.clearTarget(TARGET) }
        gate.entered.await(); task.cancelAndJoin()
        assertTrue(task.isCancelled)
        assertFalse(f.flashStore.isAppEnabled(TARGET))
        assertEquals(f.initialCapture, f.captureStore.state.value)
        f.captureStore.beforeCommit = {}
        assertEquals(CaptureOperationResult.Success(), f.coordinator.clearTarget(TARGET))
    }

    @Test fun cancellationAfterDeleteCommitDoesNotRestorePayload() = runTest {
        val f = CaptureSaveFixture(backgroundScope, testScheduler)
        val gate = CommitGate(); f.captureStore.afterCommit = gate::pause
        val task = async { f.coordinator.clearAll() }
        gate.entered.await(); task.cancelAndJoin()
        assertTrue(task.isCancelled)
        assertEquals(RuleCaptureState(), f.captureStore.state.value)
        assertTrue(f.flashStore.preferences.value.enabledApps.values.none { it })
    }

    @Test fun queuedSaveAfterDeleteCannotRestoreDeletedSession() = runTest {
        val f = CaptureSaveFixture(backgroundScope, testScheduler)
        val gate = CommitGate(); f.captureStore.beforeCommit = gate::pause
        val delete = async { f.coordinator.clearTarget(TARGET) }
        gate.entered.await()
        val save = async { f.coordinator.finish(SESSION_ID) }; runCurrent()
        assertFalse(save.isCompleted)
        gate.release.complete(Unit)
        assertEquals(CaptureOperationResult.Success(), delete.await())
        assertEquals(CaptureOperationResult.Stale, save.await())
        assertTrue(f.captureStore.state.value.samples.none { it.targetAppId == TARGET })
    }

    @Test fun queuedDeleteAfterSaveRemovesJustSavedSample() = runTest {
        val f = CaptureSaveFixture(backgroundScope, testScheduler)
        val gate = CommitGate(); f.captureStore.beforeCommit = gate::pause
        val save = async { f.coordinator.finish(SESSION_ID) }
        gate.entered.await()
        val delete = async { f.coordinator.clearTarget(TARGET) }; runCurrent()
        assertFalse(delete.isCompleted)
        gate.release.complete(Unit)
        assertEquals(CaptureOperationResult.Success(), save.await())
        assertEquals(CaptureOperationResult.Success(), delete.await())
        assertTrue(f.captureStore.state.value.samples.none { it.targetAppId == TARGET })
    }

    @Test fun enableQueuedBehindDeleteRejectsStaleCatalogWithoutReenabling() = runTest {
        val f = CaptureSaveFixture(backgroundScope, testScheduler)
        val gate = CommitGate(); f.captureStore.beforeCommit = gate::pause
        val delete = async { f.coordinator.clearTarget(TARGET) }; gate.entered.await()
        val enable = async { f.coordinator.setAppEnabled(TARGET, true) }; runCurrent()
        assertFalse(enable.isCompleted)
        gate.release.complete(Unit); delete.await()
        assertEquals(CaptureOperationResult.Failure(CaptureOperationFailure.RULES_UPDATING), enable.await())
        assertFalse(f.flashStore.isAppEnabled(TARGET))
        f.catalog.publish(f.captureStore.state.value.samples)
        assertEquals(CaptureOperationResult.Failure(CaptureOperationFailure.RULE_NOT_READY), f.coordinator.setAppEnabled(TARGET, true))
    }

    @Test fun disablingIsAllowedDuringBlockingCaptureWithoutRemovingEvidence() = runTest {
        val f = CaptureSaveFixture(backgroundScope, testScheduler)
        assertEquals(CaptureOperationResult.Success(), f.coordinator.setAppEnabled(TARGET, false))
        assertEquals(f.initialCapture, f.captureStore.state.value)
        assertEquals(CaptureOperationResult.Failure(CaptureOperationFailure.CAPTURE_IN_PROGRESS), f.coordinator.setAppEnabled(TARGET, true))
    }

    private fun bulkFailure(attempt: Int) = runTest {
        val f = CaptureSaveFixture(backgroundScope, testScheduler)
        val third = TargetAppId("com.example.third")
        f.flashStore.preferences.value = f.initialPreferences.copy(enabledApps = f.initialPreferences.enabledApps + (third to true))
        f.flashStore.beforeCommit = { if (f.flashStore.attempts.size == attempt) throw IOException("disable $attempt") }
        assertEquals(storageFailure(), f.coordinator.clearAll())
        assertEquals(attempt, f.flashStore.attempts.size)
        assertEquals(attempt - 1, f.flashStore.writes.size)
        assertTrue(f.flashStore.writes.all { !it.second })
        assertEquals(f.initialCapture, f.captureStore.state.value)
        assertEquals(0, f.captureStore.attempts)
    }
    private fun storageFailure() = CaptureOperationResult.Failure(CaptureOperationFailure.STORAGE)
}
