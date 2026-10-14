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
class HomeInteractionRegressionTest {
    @Test fun pendingNavigationHasOnlyOneConsumerWhenCollectorsOverlap() = runTest {
        home { f ->
            configured(f)
            val first = mutableListOf<HomeViewModel.HomeEffect>()
            val second = mutableListOf<HomeViewModel.HomeEffect>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { f.vm.effects.collect { first += it } }
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { f.vm.effects.collect { second += it } }
            runCurrent(); assertEquals(1, first.size + second.size)
        }
    }
    @Test fun configurationBackgroundDefersNavigationUntilResume() = runTest {
        home { f ->
            configured(f); f.vm.onHostStopped(true)
            val events = mutableListOf<HomeViewModel.HomeEffect>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { f.vm.effects.collect { events += it } }
            runCurrent(); assertTrue(events.isEmpty())
            f.vm.onHostResumed(); runCurrent()
            assertEquals(listOf(HomeViewModel.HomeEffect.GoHome(f.id)), events)
        }
    }
    @Test fun realDepartureInvalidatesAlreadyPendingNavigation() = runTest {
        home { f ->
            configured(f); f.vm.onHostStopped(false); runCurrent(); f.vm.onHostResumed(); runCurrent()
            val events = mutableListOf<HomeViewModel.HomeEffect>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { f.vm.effects.collect { events += it } }
            runCurrent(); assertTrue(events.isEmpty())
        }
    }
    @Test fun expiryIsRecheckedAtConsumptionWithoutExplicitRefresh() = runTest {
        home { f ->
            configured(f); advanceTimeBy(120_000)
            val events = mutableListOf<HomeViewModel.HomeEffect>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { f.vm.effects.collect { events += it } }
            runCurrent(); assertTrue(events.isEmpty())
            assertEquals(CapturePhase.FAILED, f.capture.captureStore.state.value.activeSession!!.phase)
        }
    }
    @Test fun configurationResumeDoesNotDiscardPendingOperationFailureOrRetry() = runTest {
        home { f ->
            val gate = CommitGate()
            f.capture.captureStore.beforeCommit = { gate.pause(); throw IOException("start") }
            f.vm.startCapture(); runCurrent(); assertTrue(gate.entered.isCompleted)
            f.vm.onHostStopped(true); f.vm.onHostResumed(); runCurrent()
            gate.release.complete(Unit); runCurrent()
            assertEquals(HomeFeedback.STORAGE, f.vm.uiState.value.operationFeedback)
            assertTrue(f.vm.uiState.value.canRetry)
        }
    }
    @Test fun oldDepartureFailureDoesNotReplaceNewSettingsFeedback() = runTest {
        home { f ->
            configured(f)
            val gate = CommitGate()
            f.capture.captureStore.beforeCommit = { gate.pause(); throw IOException("departure") }
            f.vm.onHostStopped(false); runCurrent(); assertTrue(gate.entered.isCompleted)
            f.vm.reportSettingsLaunchFailure(); gate.release.complete(Unit); runCurrent()
            assertEquals(HomeFeedback.SETTINGS_FAILED, f.vm.uiState.value.operationFeedback)
            assertFalse(f.vm.uiState.value.canRetry)
        }
    }
    @Test fun oldReturnSuccessCannotClearNewSettingsFeedback() = runTest {
        home { f ->
            configured(f); f.vm.onHostStopped(false); runCurrent()
            val gate = CommitGate(); f.capture.captureStore.beforeCommit = gate::pause
            f.vm.onHostResumed(); runCurrent(); assertTrue(gate.entered.isCompleted)
            f.vm.reportSettingsLaunchFailure(); gate.release.complete(Unit); runCurrent()
            assertEquals(HomeFeedback.SETTINGS_FAILED, f.vm.uiState.value.operationFeedback)
        }
    }
    @Test fun settingsFailureReplacesSavedTargetPresentation() = runTest {
        home { f ->
            f.capture.captureStore.state.value = f.capture.initialCapture
            runCurrent(); f.vm.finishCapture(); runCurrent()
            f.capture.catalog.publish(f.capture.captureStore.state.value.samples); runCurrent()
            assertEquals(HomeFeedback.SAVED, f.vm.uiState.value.operationFeedback)
            f.vm.reportSettingsLaunchFailure(); runCurrent()
            assertEquals(HomeFeedback.SETTINGS_FAILED, f.vm.uiState.value.operationFeedback)
        }
    }
    @Test fun staleHomeFailureDoesNotStealNewOperationsFeedbackOwnership() = runTest {
        home { f ->
            val gate = CommitGate(); f.capture.captureStore.beforeCommit = { gate.pause(); throw IOException("delete") }
            f.vm.clearAllCapturedRules(); runCurrent(); f.vm.reportGoHomeFailure(-1)
            gate.release.complete(Unit); runCurrent()
            assertEquals(HomeFeedback.STORAGE, f.vm.uiState.value.operationFeedback)
            assertTrue(f.vm.uiState.value.canRetry)
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
