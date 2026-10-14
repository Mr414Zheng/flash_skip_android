package com.zhenghuaizhi.flashskip.ui.home

import androidx.lifecycle.viewModelScope
import com.zhenghuaizhi.flashskip.application.ServiceConnectionRegistry
import com.zhenghuaizhi.flashskip.application.ServiceStatusCoordinator
import com.zhenghuaizhi.flashskip.application.SystemServiceStatusReader
import com.zhenghuaizhi.flashskip.domain.model.CaptureFailure
import com.zhenghuaizhi.flashskip.domain.model.CapturePhase
import com.zhenghuaizhi.flashskip.domain.model.ServiceStatus
import com.zhenghuaizhi.flashskip.testing.CaptureSaveFixture
import com.zhenghuaizhi.flashskip.testing.CommitGate
import com.zhenghuaizhi.flashskip.testing.SESSION_ID
import com.zhenghuaizhi.flashskip.testing.TARGET
import com.zhenghuaizhi.flashskip.ui.home.HomeViewModel.HomeEffect
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelLifecycleTest {
    @Test fun startWaitsForServiceConfigurationBeforeRequestingHomeNavigation() = runTest {
        withHomeLifecycle(initiallyEmpty = true) { f ->
            f.viewModel.startCapture()
            runCurrent()
            val id = f.activeId
            assertEquals(CapturePhase.CONFIGURING, f.capture.captureStore.state.value.activeSession!!.phase)
            assertTrue(f.effects.isEmpty())
            f.capture.coordinator.markConfigured(id)
            runCurrent()
            assertEquals(listOf(HomeEffect.GoHome(id)), f.effects)
            assertEquals(CapturePhase.WAITING_FOR_TARGET, f.capture.captureStore.state.value.activeSession!!.phase)
        }
    }

    @Test fun configurationChangeStopDoesNotMarkDepartureOrSettleWaitingCapture() = runTest {
        withHomeLifecycle(initiallyEmpty = true) { f ->
            startConfigured(f)
            val before = f.capture.captureStore.state.value
            val attempts = f.capture.captureStore.attempts
            f.viewModel.onHostStopped(isChangingConfigurations = true)
            f.viewModel.onHostResumed()
            runCurrent()
            assertEquals(before, f.capture.captureStore.state.value)
            assertEquals(attempts, f.capture.captureStore.attempts)
        }
    }

    @Test fun returnAfterRealDepartureBeforeTargetSettlesSameSessionAsFailure() = runTest {
        withHomeLifecycle(initiallyEmpty = true) { f ->
            startConfigured(f)
            f.viewModel.onHostStopped(false)
            runCurrent()
            assertNotNull(f.capture.captureStore.state.value.activeSession!!.leftFlashSkipAtEpochMs)
            f.viewModel.onHostResumed()
            runCurrent()
            val session = f.capture.captureStore.state.value.activeSession!!
            assertEquals(CapturePhase.FAILED, session.phase)
            assertEquals(CaptureFailure.RETURNED_BEFORE_TARGET, session.failure)
            assertNull(session.leftFlashSkipAtEpochMs)
            assertFalse(session.blocksAutomation)
        }
    }

    @Test fun returnWhileObservingReportsDismissNotCapturedWithoutSavingSample() = runTest {
        withHomeLifecycle(initiallyEmpty = true) { f ->
            startConfigured(f)
            f.capture.coordinator.observeTarget(f.activeId, TARGET, "目标", TARGET.packageName,
                "${TARGET.packageName}.LaunchActivity", 7, testScheduler.currentTime)
            f.viewModel.onHostStopped(false)
            runCurrent()
            f.viewModel.onHostResumed()
            runCurrent()
            assertEquals(CaptureFailure.DISMISS_NOT_CAPTURED, f.capture.captureStore.state.value.activeSession!!.failure)
            assertEquals(f.capture.initialCapture.samples, f.capture.captureStore.state.value.samples)
            assertTrue(f.capture.flashStore.writes.isEmpty())
        }
    }

    @Test fun returnKeepsCapturedPayloadBlockingUntilExplicitSave() = runTest {
        withHomeLifecycle { f ->
            val before = f.capture.captureStore.state.value.activeSession!!
            f.capture.coordinator.markLeftFlashSkip(SESSION_ID)
            f.viewModel.onHostResumed()
            runCurrent()
            val returned = f.capture.captureStore.state.value.activeSession!!
            assertEquals(CapturePhase.CAPTURED, returned.phase)
            assertEquals(before.pendingSample, returned.pendingSample)
            assertNull(returned.leftFlashSkipAtEpochMs)
            assertTrue(returned.blocksAutomation)
            assertEquals(f.capture.initialCapture.samples, f.capture.captureStore.state.value.samples)
            assertTrue(f.capture.flashStore.writes.isEmpty())
        }
    }

    @Test fun returnKeepsCandidateButTurnsOffClickListeningWithoutSavingIt() = runTest {
        withHomeLifecycle(phase = CapturePhase.AWAITING_CONFIRMATION) { f ->
            val before = f.capture.captureStore.state.value.activeSession!!
            f.capture.coordinator.markLeftFlashSkip(SESSION_ID)
            f.viewModel.onHostResumed()
            runCurrent()
            val returned = f.capture.captureStore.state.value.activeSession!!
            assertEquals(CapturePhase.AWAITING_CONFIRMATION, returned.phase)
            assertEquals(before.pendingObservedCandidate, returned.pendingObservedCandidate)
            assertFalse(returned.listensForCandidateClick)
            assertTrue(returned.blocksAutomation)
            assertNull(returned.leftFlashSkipAtEpochMs)
            assertEquals(f.capture.initialCapture.samples, f.capture.captureStore.state.value.samples)
            assertTrue(f.capture.flashStore.writes.isEmpty())
        }
    }

    @Test fun observedDepartureStillSettlesReturnAfterDepartureWriteFailed() = runTest {
        withHomeLifecycle(initiallyEmpty = true) { f ->
            startConfigured(f)
            f.capture.captureStore.beforeCommit = { throw IOException("离开未提交") }
            f.viewModel.onHostStopped(false)
            runCurrent()
            assertNull(f.capture.captureStore.state.value.activeSession!!.leftFlashSkipAtEpochMs)
            assertEquals(HomeFeedback.DEPARTURE_STORAGE, f.viewModel.uiState.value.operationFeedback)
            f.capture.captureStore.beforeCommit = {}
            f.viewModel.onHostResumed()
            runCurrent()
            assertEquals(CaptureFailure.RETURNED_BEFORE_TARGET, f.capture.captureStore.state.value.activeSession!!.failure)
            assertFalse(f.viewModel.uiState.value.canRetry)
        }
    }

    @Test fun leaveAndReturnWhileStartCommitIsPendingNeverNavigatesHomeLater() = runTest {
        withHomeLifecycle(initiallyEmpty = true) { f ->
            val gate = CommitGate()
            f.capture.captureStore.beforeCommit = gate::pause
            f.viewModel.startCapture()
            runCurrent()
            assertTrue(gate.entered.isCompleted)
            f.viewModel.onHostStopped(false)
            f.viewModel.onHostResumed()
            f.capture.captureStore.beforeCommit = {}
            gate.release.complete(Unit)
            runCurrent()
            val session = f.capture.captureStore.state.value.activeSession!!
            assertEquals(CapturePhase.FAILED, session.phase)
            assertEquals(CaptureFailure.RETURNED_BEFORE_TARGET, session.failure)
            assertTrue(f.effects.isEmpty())
        }
    }

    @Test fun initialResumeWithoutDepartureKeepsEvidenceAndDoesNotWrite() = runTest {
        withHomeLifecycle { f ->
            val before = f.capture.captureStore.state.value
            val attempts = f.capture.captureStore.attempts
            f.viewModel.onHostResumed()
            runCurrent()
            assertEquals(before, f.capture.captureStore.state.value)
            assertEquals(attempts, f.capture.captureStore.attempts)
        }
    }

    private suspend fun TestScope.startConfigured(f: HomeLifecycleFixture) {
        f.viewModel.startCapture()
        runCurrent()
        f.capture.coordinator.markConfigured(f.activeId)
        runCurrent()
    }

    private suspend fun TestScope.withHomeLifecycle(
        phase: CapturePhase = CapturePhase.CAPTURED,
        initiallyEmpty: Boolean = false,
        block: suspend TestScope.(HomeLifecycleFixture) -> Unit,
    ) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val capture = CaptureSaveFixture(backgroundScope, testScheduler, phase)
        capture.captureStore.state.value = capture.captureStore.state.value.copy(
            activeSession = if (initiallyEmpty) null else capture.captureStore.state.value.activeSession!!.copy(
                candidateClickListeningEnabled = phase == CapturePhase.AWAITING_CONFIRMATION,
            ),
        )
        val connection = ServiceConnectionRegistry().also { it.setConnected(true) }
        val status = ServiceStatusCoordinator(SystemServiceStatusReader { true }, connection, backgroundScope)
        status.refresh()
        val viewModel = HomeViewModel(capture.flashStore, capture.catalog, status, capture.coordinator)
        val stateCollector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect() }
        val effects = mutableListOf<HomeEffect>()
        val effectCollector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.effects.collect { effects += it } }
        try {
            runCurrent()
            assertEquals(ServiceStatus.RUNNING, viewModel.uiState.value.serviceStatus)
            block(HomeLifecycleFixture(capture, viewModel, effects))
        } finally {
            stateCollector.cancel()
            effectCollector.cancel()
            viewModel.viewModelScope.cancel()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    private data class HomeLifecycleFixture(
        val capture: CaptureSaveFixture,
        val viewModel: HomeViewModel,
        val effects: MutableList<HomeEffect>,
    ) {
        val activeId: Long get() = capture.captureStore.state.value.activeSession!!.id
    }
}
