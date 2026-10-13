package com.zhenghuaizhi.flashskip.ui.home

import androidx.lifecycle.viewModelScope
import com.zhenghuaizhi.flashskip.application.ServiceConnectionRegistry
import com.zhenghuaizhi.flashskip.application.ServiceStatusCoordinator
import com.zhenghuaizhi.flashskip.application.SystemServiceStatusReader
import com.zhenghuaizhi.flashskip.application.rulecapture.CaptureOperationResult
import com.zhenghuaizhi.flashskip.domain.model.CaptureEvidence
import com.zhenghuaizhi.flashskip.domain.model.CapturePhase
import com.zhenghuaizhi.flashskip.domain.model.RuleCardStatus
import com.zhenghuaizhi.flashskip.domain.model.ServiceStatus
import com.zhenghuaizhi.flashskip.testing.CaptureSaveFixture
import com.zhenghuaizhi.flashskip.testing.CommitGate
import com.zhenghuaizhi.flashskip.testing.OTHER_TARGET
import com.zhenghuaizhi.flashskip.testing.SESSION_ID
import com.zhenghuaizhi.flashskip.testing.TARGET
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(Parameterized::class)
class HomeViewModelSaveTest(private val phase: CapturePhase) {
    @Test
    fun ac02_ac04_closeFailureShowsStorageRetryThenSavesOriginalSession() = runTest {
        withHomeFixture { ui ->
            ui.capture.flashStore.beforeCommit = { throw IOException("关闭提交未发生") }

            ui.viewModel.finishCapture()
            runCurrent()

            assertStorageRetry(ui)
            assertEquals(ui.capture.initialCapture, ui.capture.captureStore.state.value)
            assertEquals(ui.capture.initialPreferences, ui.capture.flashStore.preferences.value)
            assertEquals(0, ui.capture.captureStore.attempts)
            assertTrue(ui.capture.flashStore.writes.isEmpty())

            ui.capture.flashStore.beforeCommit = {}
            ui.viewModel.retryLastOperation()
            runCurrent()

            assertRetrySavedOriginalSession(ui)
            assertEquals(2, ui.capture.flashStore.attempts.size)
            assertEquals(1, ui.capture.captureStore.attempts)
            ui.capture.catalog.publish(ui.capture.captureStore.state.value.samples)
            runCurrent()
            assertEquals(HomeFeedback.SAVED, ui.state.operationFeedback)
            assertFalse(ui.state.canRetry)
        }
    }

    @Test
    fun ac03_ac04_captureFailureShowsStorageRetryWithoutReenablingTarget() = runTest {
        withHomeFixture { ui ->
            ui.capture.captureStore.beforeCommit = { throw IOException("样本提交未发生") }

            ui.viewModel.finishCapture()
            runCurrent()

            assertStorageRetry(ui)
            assertEquals(ui.capture.initialCapture, ui.capture.captureStore.state.value)
            assertEquals(false, ui.capture.flashStore.preferences.value.enabledApps[TARGET])
            assertFalse(ui.target.checked)
            assertEquals(TargetAppStatus.DISABLED, ui.target.status)
            assertEquals(listOf(TARGET to false), ui.capture.flashStore.writes)

            ui.capture.captureStore.beforeCommit = {}
            ui.viewModel.retryLastOperation()
            runCurrent()

            assertRetrySavedOriginalSession(ui)
            assertEquals(listOf(TARGET to false, TARGET to false), ui.capture.flashStore.writes)
            assertEquals(2, ui.capture.captureStore.attempts)
            assertEquals(1, ui.capture.captureStore.writes.size)
            ui.capture.catalog.publish(ui.capture.captureStore.state.value.samples)
            runCurrent()
            assertEquals(HomeFeedback.SAVED, ui.state.operationFeedback)
            assertFalse(ui.state.canRetry)
        }
    }

    @Test
    fun ac01_ac06_savedFeedbackTracksCatalogLagAndReadyWithoutAutomaticEnable() = runTest {
        withHomeFixture { ui ->
            ui.viewModel.finishCapture()
            runCurrent()

            assertSavedWhileCatalogIsUpdating(ui)
            ui.capture.catalog.publish(ui.capture.captureStore.state.value.samples)
            runCurrent()

            assertEquals(HomeFeedback.SAVED, ui.state.operationFeedback)
            assertTrue(ui.state.ruleActionsEnabled)
            assertTrue(ui.target.canEnable)
            assertFalse(ui.target.checked)
            assertEquals(TargetAppStatus.DISABLED, ui.target.status)
            assertEquals(listOf(TARGET to false), ui.capture.flashStore.writes)
            assertEquals(false, ui.capture.flashStore.preferences.value.enabledApps[TARGET])
        }
    }

    @Test
    fun ac01_ac06_savedWithoutExecutableRulesShowsNotReadyAndRejectsEnable() = runTest {
        withHomeFixture { ui ->
            ui.viewModel.finishCapture()
            runCurrent()
            publishWithoutTargetRules(ui.capture)
            runCurrent()

            assertEquals(HomeFeedback.SAVED_NOT_READY, ui.state.operationFeedback)
            assertTrue(ui.state.ruleActionsEnabled)
            assertFalse(ui.target.canEnable)
            assertFalse(ui.target.checked)
            assertEquals(TargetAppStatus.RULE_UNAVAILABLE, ui.target.status)
            assertFalse(ui.state.canRetry)

            ui.viewModel.setAppEnabled(TARGET, true)
            runCurrent()

            assertEquals(HomeFeedback.RULE_NOT_READY, ui.state.operationFeedback)
            assertFalse(ui.state.canRetry)
            assertEquals(listOf(TARGET to false), ui.capture.flashStore.writes)
            assertEquals(false, ui.capture.flashStore.preferences.value.enabledApps[TARGET])
        }
    }

    @Test
    fun ac06_inputsDuringSaveAreIgnoredWithoutQueueAndLaterExplicitEnableWorks() = runTest {
        withHomeFixture { ui ->
            val gate = CommitGate()
            ui.capture.captureStore.beforeCommit = gate::pause
            ui.viewModel.finishCapture()
            runCurrent()
            assertTrue(gate.entered.isCompleted)
            assertTrue(ui.state.operationInProgress)
            assertTrue(ui.state.capture.operationInProgress)
            assertFalse(ui.target.canEnable)
            assertFalse(ui.state.capture.canStart)
            assertFalse(ui.state.canRetry)
            val pausedEvents = ui.capture.events.toList()

            // UI 只允许一个主动操作；这些输入不得排队到保存完成之后执行。
            ui.viewModel.setAppEnabled(TARGET, true)
            ui.viewModel.setAppEnabled(OTHER_TARGET, false)
            ui.viewModel.finishCapture()
            ui.viewModel.cancelCapture()
            ui.viewModel.clearCapturedRules(TARGET)
            ui.viewModel.clearAllCapturedRules()
            ui.viewModel.startCapture()
            ui.viewModel.retryLastOperation()
            runCurrent()

            assertEquals(pausedEvents, ui.capture.events)
            assertEquals(1, ui.capture.captureStore.attempts)
            assertEquals(1, ui.capture.flashStore.attempts.size)
            gate.release.complete(Unit)
            runCurrent()
            ui.capture.catalog.publish(ui.capture.captureStore.state.value.samples)
            runCurrent()

            assertFalse(ui.state.operationInProgress)
            assertEquals(HomeFeedback.SAVED, ui.state.operationFeedback)
            assertNull(ui.state.capture.sessionId)
            assertEquals(ui.capture.initialCapture.samples + ui.capture.expectedSample, ui.capture.captureStore.state.value.samples)
            assertEquals(listOf(TARGET to false), ui.capture.flashStore.writes)
            assertEquals(true, ui.capture.flashStore.preferences.value.enabledApps[OTHER_TARGET])
            assertFalse(ui.target.checked)
            assertTrue(ui.target.canEnable)

            ui.viewModel.setAppEnabled(TARGET, true)
            runCurrent()

            assertTrue(ui.target.checked)
            assertEquals(TargetAppStatus.ENABLED_AND_RUNNING, ui.target.status)
            assertNull(ui.state.operationFeedback)
            assertFalse(ui.state.canRetry)
            assertEquals(listOf(TARGET to false, TARGET to true), ui.capture.flashStore.writes)
            assertEquals(1, ui.capture.captureStore.attempts)
            val saved = ui.capture.commits.single { it.point == "capture:commit" }
            assertEquals(false, saved.preferences.enabledApps[TARGET])
            assertEquals(true, ui.capture.flashStore.preferences.value.enabledApps[TARGET])
        }
    }

    @Test
    fun ac05_oldViewModelRetryKeepsOriginalIdAfterCoordinatorCreatesNewSession() = runTest {
        withHomeFixture { ui ->
            ui.capture.captureStore.beforeCommit = { throw IOException("样本提交未发生") }
            ui.viewModel.finishCapture()
            runCurrent()
            assertStorageRetry(ui)
            ui.capture.captureStore.beforeCommit = {}

            // 绕过当前 ViewModel，保留它原有的失败重试；模拟另一合法入口替换会话。
            assertEquals(CaptureOperationResult.Success(), ui.capture.coordinator.cancel(SESSION_ID))
            val started = ui.capture.coordinator.requestStart() as CaptureOperationResult.Success
            val newId = requireNotNull(started.sessionId)
            assertNotEquals(SESSION_ID, newId)
            assertEquals(CaptureOperationResult.Success(newId), ui.capture.coordinator.markConfigured(newId))
            recordNewPayload(ui.capture, newId)
            runCurrent()
            assertEquals(newId, ui.state.capture.sessionId)
            assertTrue(ui.state.canRetry)
            assertEquals(HomeFeedback.STORAGE, ui.state.operationFeedback)
            val newCapture = ui.capture.captureStore.state.value
            val preferences = ui.capture.flashStore.preferences.value
            val events = ui.capture.events.toList()

            ui.viewModel.retryLastOperation()
            runCurrent()

            assertEquals(HomeFeedback.STALE, ui.state.operationFeedback)
            assertFalse(ui.state.canRetry)
            assertFalse(ui.state.operationInProgress)
            assertEquals(newId, ui.state.capture.sessionId)
            assertEquals(phase, ui.state.capture.phase)
            assertEquals(newCapture, ui.capture.captureStore.state.value)
            assertEquals(preferences, ui.capture.flashStore.preferences.value)
            assertEquals(events, ui.capture.events)
            assertEquals(ui.capture.initialCapture.samples, newCapture.samples)
        }
    }

    @Test
    fun ac05_successfulNewUiOperationClearsOldSaveRetry() = runTest {
        withHomeFixture { ui ->
            ui.capture.captureStore.beforeCommit = { throw IOException("样本提交未发生") }
            ui.viewModel.finishCapture()
            runCurrent()
            assertStorageRetry(ui)
            ui.capture.captureStore.beforeCommit = {}

            ui.viewModel.cancelCapture()
            runCurrent()

            assertNull(ui.state.capture.sessionId)
            assertNull(ui.state.operationFeedback)
            assertFalse(ui.state.canRetry)
            val cancelled = ui.capture.captureStore.state.value
            val events = ui.capture.events.toList()
            ui.viewModel.retryLastOperation()
            runCurrent()

            assertEquals(events, ui.capture.events)
            assertEquals(cancelled, ui.capture.captureStore.state.value)
            assertEquals(ui.capture.initialCapture.samples, cancelled.samples)
            assertEquals(listOf(TARGET to false), ui.capture.flashStore.writes)
        }
    }

    @Test
    fun ac05_newFailedUiOperationReplacesOldSaveRetryWithItsOwnAction() = runTest {
        withHomeFixture { ui ->
            ui.capture.captureStore.beforeCommit = { throw IOException("样本提交未发生") }
            ui.viewModel.finishCapture()
            runCurrent()
            assertStorageRetry(ui)
            ui.capture.captureStore.beforeCommit = {}
            ui.capture.flashStore.beforeCommit = { throw IOException("新关闭操作提交未发生") }

            ui.viewModel.setAppEnabled(OTHER_TARGET, false)
            runCurrent()

            assertStorageRetry(ui)
            assertEquals(OTHER_TARGET to false, ui.capture.flashStore.attempts.last())
            val pendingCapture = ui.capture.captureStore.state.value
            val captureAttempts = ui.capture.captureStore.attempts
            ui.capture.flashStore.beforeCommit = {}
            ui.viewModel.retryLastOperation()
            runCurrent()

            assertNull(ui.state.operationFeedback)
            assertFalse(ui.state.canRetry)
            assertEquals(pendingCapture, ui.capture.captureStore.state.value)
            assertEquals(captureAttempts, ui.capture.captureStore.attempts)
            assertEquals(SESSION_ID, ui.state.capture.sessionId)
            assertEquals(ui.capture.initialCapture.samples, pendingCapture.samples)
            assertEquals(listOf(TARGET to false, OTHER_TARGET to false), ui.capture.flashStore.writes)
            assertEquals(ui.capture.initialPreferences.records, ui.capture.flashStore.preferences.value.records)
        }
    }

    @Test
    fun ac07_pendingPayloadAndCatalogPublicationWaitForExplicitUiSave() = runTest {
        withHomeFixture { ui ->
            ui.capture.catalog.publish(ui.capture.captureStore.state.value.samples)
            runCurrent()

            assertEquals(SESSION_ID, ui.state.capture.sessionId)
            assertEquals(phase, ui.state.capture.phase)
            assertNull(ui.state.operationFeedback)
            assertFalse(ui.state.canRetry)
            assertEquals(ui.capture.initialCapture, ui.capture.captureStore.state.value)
            assertEquals(ui.capture.initialPreferences, ui.capture.flashStore.preferences.value)
            assertTrue(ui.capture.events.isEmpty())

            ui.viewModel.finishCapture()
            runCurrent()

            val sample = ui.capture.captureStore.state.value.samples.single { it.id == SESSION_ID }
            assertEquals(ui.capture.expectedSample, sample)
            assertEquals(
                if (phase == CapturePhase.CAPTURED) CaptureEvidence.ACCESSIBILITY_CLICK
                else CaptureEvidence.USER_CONFIRMED_UNREPORTED_CLICK,
                sample.evidence,
            )
            assertNull(ui.state.capture.sessionId)
            assertFalse(ui.state.canRetry)
            assertEquals(listOf(TARGET to false), ui.capture.flashStore.writes)
        }
    }

    private suspend fun TestScope.withHomeFixture(block: suspend TestScope.(UiFixture) -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val capture = CaptureSaveFixture(backgroundScope, testScheduler, phase = phase)
            val connection = ServiceConnectionRegistry().also { it.setConnected(true) }
            val status = ServiceStatusCoordinator(SystemServiceStatusReader { true }, connection, backgroundScope)
            status.refresh()
            val viewModel = HomeViewModel(capture.flashStore, capture.catalog, status, capture.coordinator)
            val observed = mutableListOf<HomeUiState>()
            val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.uiState.collect { observed += it }
            }
            try {
                runCurrent()
                assertTrue(observed.isNotEmpty())
                assertEquals(ServiceStatus.RUNNING, viewModel.uiState.value.serviceStatus)
                assertEquals(SESSION_ID, viewModel.uiState.value.capture.sessionId)
                assertEquals(phase, viewModel.uiState.value.capture.phase)
                block(UiFixture(capture, viewModel, observed))
            } finally {
                collector.cancel()
                viewModel.viewModelScope.cancel()
                runCurrent()
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun assertStorageRetry(ui: UiFixture) {
        assertEquals(HomeFeedback.STORAGE, ui.state.operationFeedback)
        assertTrue(ui.state.canRetry)
        assertFalse(ui.state.operationInProgress)
        assertFalse(ui.state.capture.operationInProgress)
        assertEquals(SESSION_ID, ui.state.capture.sessionId)
        assertEquals(phase, ui.state.capture.phase)
        assertFalse(ui.target.canEnable)
        assertTrue(requireNotNull(ui.capture.captureStore.state.value.activeSession).blocksAutomation)
        assertTrue(ui.observed.any { it.operationFeedback == HomeFeedback.STORAGE && it.canRetry })
    }

    private fun assertRetrySavedOriginalSession(ui: UiFixture) {
        assertEquals(ui.capture.initialCapture.samples + ui.capture.expectedSample, ui.capture.captureStore.state.value.samples)
        assertEquals(1, ui.capture.captureStore.state.value.samples.count { it.id == SESSION_ID })
        assertNull(ui.capture.captureStore.state.value.activeSession)
        assertNull(ui.state.capture.sessionId)
        assertFalse(ui.state.canRetry)
        assertFalse(ui.state.operationInProgress)
        assertNotEquals(HomeFeedback.STORAGE, ui.state.operationFeedback)
        assertEquals(false, ui.capture.flashStore.preferences.value.enabledApps[TARGET])
        assertEquals(true, ui.capture.flashStore.preferences.value.enabledApps[OTHER_TARGET])
        assertEquals(ui.capture.initialPreferences.records, ui.capture.flashStore.preferences.value.records)
        assertEquals(
            ui.capture.initialCapture.semanticDiffEvidence.filterNot { it.targetAppId == TARGET },
            ui.capture.captureStore.state.value.semanticDiffEvidence,
        )
        assertSavedWhileCatalogIsUpdating(ui)
    }

    private fun assertSavedWhileCatalogIsUpdating(ui: UiFixture) {
        assertFalse(ui.state.operationInProgress)
        assertFalse(ui.state.canRetry)
        assertFalse(ui.state.ruleActionsEnabled)
        assertFalse(ui.target.canEnable)
        assertFalse(ui.target.checked)
        assertEquals(TargetAppStatus.DISABLED, ui.target.status)
        assertNotEquals(HomeFeedback.STORAGE, ui.state.operationFeedback)
        assertNull(ui.state.capture.sessionId)
    }

    private fun publishWithoutTargetRules(capture: CaptureSaveFixture) {
        capture.catalog.publish(capture.captureStore.state.value.samples)
        val snapshot = capture.catalog.snapshot.value
        // 模拟目录最终发布无可执行规则；保留真实分析器生成的样本版本与其他目标。
        capture.catalog.snapshot.value = snapshot.copy(
            rules = snapshot.rules.filterNot { it.targetAppId == TARGET },
            capturedTargets = snapshot.capturedTargets.map { target ->
                if (target.targetAppId == TARGET) {
                    target.copy(analysis = target.analysis.copy(status = RuleCardStatus.CANDIDATE, rules = emptyList()))
                } else target
            },
        )
    }

    private suspend fun recordNewPayload(capture: CaptureSaveFixture, sessionId: Long) {
        val original = requireNotNull(capture.initialCapture.activeSession)
        val result = when (phase) {
            CapturePhase.CAPTURED -> capture.coordinator.recordCaptured(
                sessionId,
                requireNotNull(original.pendingSample).copy(id = sessionId),
            )
            CapturePhase.AWAITING_CONFIRMATION -> capture.coordinator.recordObservedCandidateResult(
                sessionId,
                requireNotNull(original.pendingObservedCandidate).copy(id = sessionId),
            )
            else -> error("测试阶段必须可以保存")
        }
        assertEquals(CaptureOperationResult.Success(sessionId), result)
    }

    private class UiFixture(
        val capture: CaptureSaveFixture,
        val viewModel: HomeViewModel,
        val observed: List<HomeUiState>,
    ) {
        val state: HomeUiState get() = viewModel.uiState.value
        val target: TargetAppUiState get() = state.targetApps.single { it.id == TARGET }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "phase={0}")
        fun phases(): List<Array<CapturePhase>> = listOf(
            arrayOf(CapturePhase.CAPTURED),
            arrayOf(CapturePhase.AWAITING_CONFIRMATION),
        )
    }
}
