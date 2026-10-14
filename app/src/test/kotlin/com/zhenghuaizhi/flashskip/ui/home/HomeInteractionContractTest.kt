package com.zhenghuaizhi.flashskip.ui.home

import androidx.lifecycle.viewModelScope
import com.zhenghuaizhi.flashskip.application.*
import com.zhenghuaizhi.flashskip.domain.model.CapturePhase
import com.zhenghuaizhi.flashskip.testing.*
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeInteractionContractTest {
    @Test fun absentConsumerReceivesPendingNavigationWhenItReturns() = runTest {
        home { f ->
            configured(f)
            val events = mutableListOf<HomeViewModel.HomeEffect>()
            val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { f.vm.effects.collect { events += it } }
            runCurrent()
            assertEquals(listOf(HomeViewModel.HomeEffect.GoHome(f.id)), events)
            collector.cancel()
        }
    }
    @Test fun consumedNavigationIsNotRepeatedAfterCollectorRecreation() = runTest {
        home { f ->
            val events = mutableListOf<HomeViewModel.HomeEffect>()
            val first = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { f.vm.effects.collect { events += it } }
            configured(f); first.cancel()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { f.vm.effects.collect { events += it } }
            runCurrent(); assertEquals(listOf(HomeViewModel.HomeEffect.GoHome(f.id)), events)
        }
    }
    @Test fun configurationStopWithMissingConsumerRetainsPendingNavigation() = runTest {
        home { f ->
            f.vm.startCapture(); runCurrent(); f.vm.onHostStopped(true)
            f.capture.coordinator.markConfigured(f.id); runCurrent(); f.vm.onHostResumed(); runCurrent()
            val events = mutableListOf<HomeViewModel.HomeEffect>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { f.vm.effects.collect { events += it } }
            runCurrent(); assertEquals(listOf(HomeViewModel.HomeEffect.GoHome(f.id)), events)
        }
    }
    @Test fun backgroundConfigurationCompletionDoesNotEmitNavigation() = runTest {
        home { f ->
            val events = mutableListOf<HomeViewModel.HomeEffect>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { f.vm.effects.collect { events += it } }
            f.vm.startCapture(); runCurrent(); f.vm.onHostStopped(false); runCurrent()
            f.capture.coordinator.markConfigured(f.id); runCurrent()
            assertTrue(events.isEmpty())
        }
    }
    @Test fun cancellationInvalidatesUnconsumedNavigation() = runTest {
        home { f ->
            configured(f); f.vm.cancelCapture(); runCurrent()
            val events = mutableListOf<HomeViewModel.HomeEffect>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { f.vm.effects.collect { events += it } }
            runCurrent(); assertTrue(events.isEmpty()); assertNull(f.capture.captureStore.state.value.activeSession)
        }
    }
    @Test fun replacedSessionDoesNotReceiveOldNavigation() = runTest {
        home { f ->
            configured(f)
            f.capture.captureStore.state.value = f.capture.captureStore.state.value.let { it.copy(activeSession = it.activeSession!!.copy(id = f.id + 10)) }
            runCurrent()
            val events = mutableListOf<HomeViewModel.HomeEffect>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { f.vm.effects.collect { events += it } }
            runCurrent(); assertTrue(events.isEmpty())
        }
    }
    @Test fun expiredSessionDoesNotReceiveOldNavigation() = runTest {
        home { f ->
            configured(f); advanceTimeBy(120_000); f.vm.refresh(); runCurrent()
            val events = mutableListOf<HomeViewModel.HomeEffect>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { f.vm.effects.collect { events += it } }
            runCurrent(); assertTrue(events.isEmpty())
        }
    }
    @Test fun newViewModelDoesNotReplayPreviousProcessNavigation() = runTest {
        home { f ->
            configured(f); f.vm.viewModelScope.cancel()
            val recreated = HomeViewModel(f.capture.flashStore, f.capture.catalog, f.status, f.capture.coordinator)
            val events = mutableListOf<HomeViewModel.HomeEffect>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { recreated.effects.collect { events += it } }
            runCurrent(); assertTrue(events.isEmpty()); recreated.viewModelScope.cancel()
        }
    }
    @Test fun homeLaunchFailureCancelsOnlyOriginalSessionAndReportsFailure() = runTest {
        home { f ->
            configured(f); f.vm.reportGoHomeFailure(f.id); runCurrent()
            assertNull(f.capture.captureStore.state.value.activeSession)
            assertEquals(HomeFeedback.GO_HOME_FAILED, f.vm.uiState.value.operationFeedback)
            assertFalse(f.vm.uiState.value.canRetry)
        }
    }
    @Test fun lateHomeFailureCannotCancelReplacement() = runTest {
        home { f ->
            configured(f); val old = f.id
            f.capture.captureStore.state.value = f.capture.captureStore.state.value.let { it.copy(activeSession = it.activeSession!!.copy(id = old + 10)) }
            runCurrent(); f.vm.reportGoHomeFailure(old); runCurrent()
            assertEquals(old + 10, f.id); assertNull(f.vm.uiState.value.operationFeedback)
        }
    }
    @Test fun navigationCancellationStorageFailureRetriesOriginalAction() = runTest {
        home { f ->
            configured(f)
            f.capture.captureStore.beforeCommit = { throw IOException("cancel") }
            f.vm.reportGoHomeFailure(f.id); runCurrent()
            assertEquals(HomeFeedback.STORAGE, f.vm.uiState.value.operationFeedback)
            assertTrue(f.vm.uiState.value.canRetry)
            f.capture.captureStore.beforeCommit = {}; f.vm.retryLastOperation(); runCurrent()
            assertNull(f.capture.captureStore.state.value.activeSession)
            assertEquals(HomeFeedback.GO_HOME_FAILED, f.vm.uiState.value.operationFeedback)
        }
    }
    @Test fun olderDeleteCompletionCannotEraseNewSettingsFailure() = runTest {
        home { f ->
            val gate = CommitGate(); f.capture.captureStore.beforeCommit = gate::pause
            f.vm.clearAllCapturedRules(); runCurrent(); assertTrue(gate.entered.isCompleted)
            f.vm.reportSettingsLaunchFailure()
            gate.release.complete(Unit); runCurrent()
            assertEquals(HomeFeedback.SETTINGS_FAILED, f.vm.uiState.value.operationFeedback)
        }
    }
    @Test fun olderDeleteFailureCannotReplaceNewSettingsFeedbackOrRetryOwner() = runTest {
        home { f ->
            val gate = CommitGate()
            f.capture.captureStore.beforeCommit = { gate.pause(); throw IOException("delete") }
            f.vm.clearAllCapturedRules(); runCurrent(); assertTrue(gate.entered.isCompleted)
            f.vm.reportSettingsLaunchFailure(); gate.release.complete(Unit); runCurrent()
            assertEquals(HomeFeedback.SETTINGS_FAILED, f.vm.uiState.value.operationFeedback)
            assertFalse(f.vm.uiState.value.canRetry)
        }
    }
    @Test fun disconnectedServiceKeepsUserSwitchButBlocksCapture() = runTest {
        home { f ->
            f.registry.setConnected(false); runCurrent()
            val card = f.vm.uiState.value.targetApps.first { it.id == TARGET }
            assertTrue(card.checked)
            assertEquals(TargetAppStatus.ENABLED_BUT_SERVICE_UNAVAILABLE, card.status)
            assertFalse(f.vm.uiState.value.capture.canStart)
            f.vm.startCapture(); runCurrent(); assertNull(f.capture.captureStore.state.value.activeSession)
        }
    }
    @Test fun serviceUnavailableDoesNotPreventExplicitDisable() = runTest {
        home { f ->
            f.registry.setConnected(false); runCurrent()
            f.vm.setAppEnabled(TARGET, false); runCurrent()
            assertFalse(f.vm.uiState.value.targetApps.first { it.id == TARGET }.checked)
            assertEquals(false, f.capture.flashStore.preferences.value.enabledApps[TARGET])
        }
    }
    @Test fun settingsFailureHasNoBusinessWritesOrAutomaticRetry() = runTest {
        home { f ->
            val before = f.capture.captureStore.state.value
            f.vm.reportSettingsLaunchFailure(); runCurrent(); f.vm.retryLastOperation(); runCurrent()
            assertEquals(HomeFeedback.SETTINGS_FAILED, f.vm.uiState.value.operationFeedback)
            assertFalse(f.vm.uiState.value.canRetry); assertEquals(before, f.capture.captureStore.state.value)
            assertTrue(f.capture.flashStore.writes.isEmpty())
        }
    }

    private suspend fun TestScope.configured(f: Fixture) {
        f.vm.startCapture(); runCurrent(); assertEquals(CapturePhase.CONFIGURING, f.capture.captureStore.state.value.activeSession!!.phase)
        f.capture.coordinator.markConfigured(f.id); runCurrent()
    }
    private suspend fun TestScope.home(block: suspend TestScope.(Fixture) -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val capture = CaptureSaveFixture(backgroundScope, testScheduler)
        capture.captureStore.state.value = capture.captureStore.state.value.copy(activeSession = null)
        val registry = ServiceConnectionRegistry().also { it.setConnected(true) }
        val status = ServiceStatusCoordinator({ true }, registry, backgroundScope).also { it.refresh() }
        val vm = HomeViewModel(capture.flashStore, capture.catalog, status, capture.coordinator)
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        try { runCurrent(); vm.onHostResumed(); runCurrent(); block(Fixture(capture, registry, status, vm)) }
        finally { collector.cancel(); vm.viewModelScope.cancel(); runCurrent(); Dispatchers.resetMain() }
    }
    private data class Fixture(val capture: CaptureSaveFixture, val registry: ServiceConnectionRegistry,
        val status: ServiceStatusCoordinator, val vm: HomeViewModel) {
        val id get() = capture.captureStore.state.value.activeSession!!.id
    }
}
