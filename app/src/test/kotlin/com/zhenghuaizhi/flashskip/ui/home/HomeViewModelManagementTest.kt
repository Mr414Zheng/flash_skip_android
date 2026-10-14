package com.zhenghuaizhi.flashskip.ui.home

import androidx.lifecycle.viewModelScope
import com.zhenghuaizhi.flashskip.application.*
import com.zhenghuaizhi.flashskip.domain.model.*
import com.zhenghuaizhi.flashskip.testing.*
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelManagementTest {
    @Test fun unchangedTargetRetryCompletesAndClearsStorageFeedback() = withHome { f, vm ->
        failDelete(f, vm)
        vm.retryLastOperation(); runCurrent()
        assertTrue(f.captureStore.state.value.samples.none { it.targetAppId == TARGET })
        assertNull(vm.uiState.value.operationFeedback)
        assertFalse(vm.uiState.value.canRetry)
    }

    @Test fun unchangedAllRetryAcceptsItsOwnPartialDisables() = withHome { f, vm ->
        f.flashStore.beforeCommit = { if (f.flashStore.attempts.size == 2) throw IOException("second disable") }
        vm.clearAllCapturedRules(); runCurrent()
        assertEquals(HomeFeedback.STORAGE, vm.uiState.value.operationFeedback)
        assertFalse(f.flashStore.isAppEnabled(TARGET))
        assertTrue(f.flashStore.isAppEnabled(OTHER_TARGET))
        f.flashStore.beforeCommit = {}
        vm.retryLastOperation(); runCurrent()
        assertEquals(RuleCaptureState(), f.captureStore.state.value)
        assertFalse(vm.uiState.value.canRetry)
    }

    @Test fun changedTargetSamplesInvalidateOldDeleteRetry() = staleTarget { state ->
        state.copy(samples = state.samples + captureSample(999))
    }
    @Test fun changedTargetSessionInvalidatesOldDeleteRetry() = staleTarget { state ->
        state.copy(activeSession = state.activeSession!!.copy(id = 999))
    }
    @Test fun changedTargetEvidenceInvalidatesOldDeleteRetry() = staleTarget { state ->
        state.copy(semanticDiffEvidence = state.semanticDiffEvidence.map { if (it.targetAppId == TARGET) it.copy(capturedAtEpochMs = 999) else it })
    }

    @Test fun unrelatedTargetChangeDoesNotInvalidateTargetRetry() = withHome { f, vm ->
        failDelete(f, vm)
        val other = captureSample(999, OTHER_TARGET)
        f.captureStore.state.value = f.captureStore.state.value.copy(samples = f.captureStore.state.value.samples + other)
        runCurrent(); vm.retryLastOperation(); runCurrent()
        assertEquals(f.initialCapture.samples.filter { it.targetAppId == OTHER_TARGET } + other, f.captureStore.state.value.samples)
        assertNull(vm.uiState.value.operationFeedback)
    }

    @Test fun newTargetInvalidatesOldAllDeleteRetry() = withHome { f, vm ->
        failDelete(f, vm, all = true)
        val changed = f.captureStore.state.value.copy(samples = f.captureStore.state.value.samples + captureSample(999, TargetAppId("com.example.new")))
        f.captureStore.state.value = changed; runCurrent()
        val attempts = f.captureStore.attempts
        vm.retryLastOperation(); runCurrent()
        assertEquals(changed, f.captureStore.state.value)
        assertEquals(attempts, f.captureStore.attempts)
        assertEquals(HomeFeedback.STALE, vm.uiState.value.operationFeedback)
        assertFalse(vm.uiState.value.canRetry)
    }

    @Test fun newOrphanSwitchInvalidatesOldAllDeleteRetry() = withHome { f, vm ->
        failDelete(f, vm, all = true)
        val target = TargetAppId("com.example.new")
        f.flashStore.setAppEnabled(target, true); runCurrent()
        val before = f.captureStore.state.value
        vm.retryLastOperation(); runCurrent()
        assertEquals(before, f.captureStore.state.value)
        assertTrue(f.flashStore.isAppEnabled(target))
        assertEquals(HomeFeedback.STALE, vm.uiState.value.operationFeedback)
    }

    @Test fun freshConfirmationCanDeleteChangedScope() = withHome { f, vm ->
        failDelete(f, vm)
        f.captureStore.state.value = f.captureStore.state.value.copy(samples = f.captureStore.state.value.samples + captureSample(999))
        runCurrent(); vm.clearCapturedRules(TARGET); runCurrent()
        assertTrue(f.captureStore.state.value.samples.none { it.targetAppId == TARGET })
        assertNull(vm.uiState.value.operationFeedback)
        assertFalse(vm.uiState.value.canRetry)
    }

    @Test fun newerSuccessfulOperationClearsOldDeleteRetry() = withHome { f, vm ->
        failDelete(f, vm)
        vm.setAppEnabled(OTHER_TARGET, false); runCurrent()
        val state = f.captureStore.state.value
        val attempts = f.captureStore.attempts
        vm.retryLastOperation(); runCurrent()
        assertEquals(state, f.captureStore.state.value)
        assertEquals(attempts, f.captureStore.attempts)
        assertNull(vm.uiState.value.operationFeedback)
    }

    @Test fun newerFailedOperationReplacesOldDeleteRetry() = withHome { f, vm ->
        failDelete(f, vm)
        f.flashStore.beforeCommit = { throw IOException("new toggle") }
        vm.setAppEnabled(OTHER_TARGET, false); runCurrent()
        f.flashStore.beforeCommit = {}
        vm.retryLastOperation(); runCurrent()
        assertFalse(f.flashStore.isAppEnabled(OTHER_TARGET))
        assertEquals(f.initialCapture, f.captureStore.state.value)
        assertNull(vm.uiState.value.operationFeedback)
    }

    @Test fun duplicateAndConflictingInputsDuringDeleteAreNotQueued() = withHome { f, vm ->
        val gate = CommitGate(); f.captureStore.beforeCommit = gate::pause
        vm.clearCapturedRules(TARGET); runCurrent()
        assertTrue(gate.entered.isCompleted)
        assertTrue(vm.uiState.value.operationInProgress)
        vm.clearCapturedRules(TARGET); vm.clearAllCapturedRules(); vm.finishCapture(); vm.setAppEnabled(OTHER_TARGET, false)
        vm.retryLastOperation(); runCurrent()
        assertEquals(1, f.captureStore.attempts)
        assertEquals(listOf(TARGET to false), f.flashStore.writes)
        gate.release.complete(Unit); runCurrent()
        assertEquals(1, f.captureStore.writes.size)
        assertTrue(f.flashStore.isAppEnabled(OTHER_TARGET))
        assertFalse(vm.uiState.value.operationInProgress)
    }

    @Test fun viewModelCancellationDoesNotTurnPartialDeleteIntoSuccess() = withHome { f, vm ->
        val gate = CommitGate(); f.captureStore.beforeCommit = gate::pause
        vm.clearCapturedRules(TARGET); runCurrent()
        assertTrue(gate.entered.isCompleted)
        vm.viewModelScope.cancel(); runCurrent()
        assertEquals(f.initialCapture, f.captureStore.state.value)
        assertFalse(f.flashStore.isAppEnabled(TARGET))
        assertTrue(f.captureStore.writes.isEmpty())
    }

    @Test fun repeatedStorageFailureRetainsRetryAndNeverReportsSuccess() = withHome { f, vm ->
        failDelete(f, vm)
        f.captureStore.beforeCommit = { throw IOException("still unavailable") }
        vm.retryLastOperation(); runCurrent()
        assertEquals(f.initialCapture, f.captureStore.state.value)
        assertEquals(HomeFeedback.STORAGE, vm.uiState.value.operationFeedback)
        assertTrue(vm.uiState.value.canRetry)
        assertFalse(vm.uiState.value.operationInProgress)
    }

    @Test fun recordOnlyChangeDoesNotInvalidateTargetDeleteRetryOrEraseHistory() = withHome { f, vm ->
        failDelete(f, vm)
        val record = f.initialPreferences.records.single().copy(timestampEpochMs = 999)
        f.flashStore.appendRecord(record); runCurrent()
        vm.retryLastOperation(); runCurrent()
        assertTrue(f.captureStore.state.value.samples.none { it.targetAppId == TARGET })
        assertEquals(listOf(record) + f.initialPreferences.records, f.flashStore.preferences.value.records)
        assertNull(vm.uiState.value.operationFeedback)
    }

    private fun staleTarget(change: (RuleCaptureState) -> RuleCaptureState) = withHome { f, vm ->
        failDelete(f, vm)
        val changed = change(f.captureStore.state.value)
        f.captureStore.state.value = changed; runCurrent()
        val attempts = f.captureStore.attempts
        val switches = f.flashStore.attempts.size
        vm.retryLastOperation(); runCurrent()
        assertEquals("旧重试不得删除后续数据", changed, f.captureStore.state.value)
        assertEquals(attempts, f.captureStore.attempts)
        assertEquals(switches, f.flashStore.attempts.size)
        assertEquals(HomeFeedback.STALE, vm.uiState.value.operationFeedback)
        assertFalse(vm.uiState.value.canRetry)
    }
    private fun TestScope.failDelete(f: CaptureSaveFixture, vm: HomeViewModel, all: Boolean = false) {
        f.captureStore.beforeCommit = { throw IOException("delete") }
        if (all) vm.clearAllCapturedRules() else vm.clearCapturedRules(TARGET)
        runCurrent()
        assertEquals(HomeFeedback.STORAGE, vm.uiState.value.operationFeedback)
        assertTrue(vm.uiState.value.canRetry)
        assertEquals(f.initialCapture, f.captureStore.state.value)
        f.captureStore.beforeCommit = {}
    }
    private fun withHome(block: suspend TestScope.(CaptureSaveFixture, HomeViewModel) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val f = CaptureSaveFixture(backgroundScope, testScheduler)
            val connection = ServiceConnectionRegistry().also { it.setConnected(true) }
            val status = ServiceStatusCoordinator(SystemServiceStatusReader { true }, connection, backgroundScope)
            status.refresh()
            val vm = HomeViewModel(f.flashStore, f.catalog, status, f.coordinator)
            val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
            try { runCurrent(); block(f, vm) }
            finally { collector.cancel(); vm.viewModelScope.cancel(); runCurrent() }
        } finally { Dispatchers.resetMain() }
    }
}
