package com.zhenghuaizhi.flashskip.platform.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import android.app.Application
import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.zhenghuaizhi.flashskip.AppContainer
import com.zhenghuaizhi.flashskip.AppContainerRuntime
import com.zhenghuaizhi.flashskip.application.rulecapture.CaptureOperationFailure
import com.zhenghuaizhi.flashskip.domain.automation.AutomationEngine
import com.zhenghuaizhi.flashskip.domain.automation.ForegroundSignal
import com.zhenghuaizhi.flashskip.domain.model.CaptureFailure
import com.zhenghuaizhi.flashskip.domain.model.CapturePhase
import com.zhenghuaizhi.flashskip.domain.model.CapturedSelector
import com.zhenghuaizhi.flashskip.domain.model.NodeSelector
import com.zhenghuaizhi.flashskip.domain.model.RuleCaptureState
import com.zhenghuaizhi.flashskip.domain.ports.MonotonicClock
import com.zhenghuaizhi.flashskip.domain.ports.PageGateway
import com.zhenghuaizhi.flashskip.domain.ports.PageIdentity
import com.zhenghuaizhi.flashskip.domain.ports.QueryResult
import com.zhenghuaizhi.flashskip.domain.ports.WallClock
import com.zhenghuaizhi.flashskip.data.preferences.RuleCaptureCodec
import com.zhenghuaizhi.flashskip.data.preferences.PreferencesFlashSkipStore
import com.zhenghuaizhi.flashskip.data.preferences.PreferencesRuleCaptureStore
import com.zhenghuaizhi.flashskip.testing.CaptureSaveFixture
import com.zhenghuaizhi.flashskip.testing.SESSION_ID
import com.zhenghuaizhi.flashskip.testing.TARGET
import com.zhenghuaizhi.flashskip.testing.captureSample
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAccessibilityService
import org.robolectric.shadows.ShadowApplicationPackageManager
import org.robolectric.util.ReflectionHelpers

@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class, shadows = [P3FlashServiceShadow::class, FaultableAccessibilityNodeShadow::class, P3ActivityPackageManagerShadow::class])
class FlashSkipAccessibilityServiceLifecycleTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun firstCapturedRestorePreservesRealStoredPayloadAndDefaultEventConfiguration() = runTest {
        withService { f ->
            val pending = f.state().activeSession!!.pendingSample
            f.connect()
            val restored = f.state().activeSession!!
            assertEquals(CapturePhase.CAPTURED, restored.phase)
            assertEquals(pending, restored.pendingSample)
            assertTrue(restored.blocksAutomation)
            assertEquals(DEFAULT_TYPES, f.service.serviceInfo.eventTypes)
            assertTrue(f.flash.preferences.first().records.isEmpty())
        }
    }

    @Test fun firstCandidateRestorePreservesPayloadStopsListeningAndRequiresExplicitSave() = runTest {
        withService(CapturePhase.AWAITING_CONFIRMATION) { f ->
            val candidate = f.state().activeSession!!.pendingObservedCandidate
            f.connect()
            val restored = f.state().activeSession!!
            assertEquals(CapturePhase.AWAITING_CONFIRMATION, restored.phase)
            assertEquals(candidate, restored.pendingObservedCandidate)
            assertFalse(restored.listensForCandidateClick)
            assertTrue(restored.blocksAutomation)
            assertEquals(DEFAULT_TYPES, f.service.serviceInfo.eventTypes)
            assertEquals(f.initial.samples, f.state().samples)
            assertTrue(f.flash.preferences.first().records.isEmpty())
        }
    }

    @Test fun expiredCompletionPayloadFailsOnFirstEmissionForBothEvidenceTypes() = runTest {
        for (phase in listOf(CapturePhase.CAPTURED, CapturePhase.AWAITING_CONFIRMATION)) {
            withService(phase, remainingMs = 0) { f ->
                f.connect()
                val restored = f.state().activeSession!!
                assertEquals(CapturePhase.FAILED, restored.phase)
                assertEquals(CaptureFailure.COMPLETION_TIMEOUT, restored.failure)
                assertNull(restored.pendingSample)
                assertNull(restored.pendingObservedCandidate)
                assertEquals(DEFAULT_TYPES, f.service.serviceInfo.eventTypes)
            }
        }
    }

    @Test fun firstWaitingOrObservingRestoreTerminatesUnavailableInsteadOfContinuingOldWindow() = runTest {
        for (phase in listOf(CapturePhase.WAITING_FOR_TARGET, CapturePhase.OBSERVING_CLICK)) {
            withService(phase) { f ->
                f.connect()
                assertEquals(CapturePhase.FAILED, f.state().activeSession!!.phase)
                assertEquals(CaptureFailure.SERVICE_UNAVAILABLE, f.state().activeSession!!.failure)
                assertEquals(DEFAULT_TYPES, f.service.serviceInfo.eventTypes)
                assertTrue(f.engines.single().queries.isEmpty())
            }
        }
    }

    @Test fun configuringRestoreWithoutStateWriteStillAppliesCaptureConfiguration() = runTest {
        withService(CapturePhase.CONFIGURING, remainingMs = 500) { f ->
            f.connect()
            assertEquals(CapturePhase.WAITING_FOR_TARGET, f.state().activeSession!!.phase)
            assertEquals(DEFAULT_TYPES or AccessibilityEvent.TYPE_VIEW_CLICKED, f.service.serviceInfo.eventTypes)
            assertTrue(f.engines.single().queries.isEmpty())
        }
    }

    @Test fun configuringRestoreConsumesRemainingBudgetAndDoesNotRestartTwoSecondDeadline() = runTest {
        withService(CapturePhase.CONFIGURING, remainingMs = 500) { f ->
            f.shadow.onConfiguration = { types ->
                if (types and AccessibilityEvent.TYPE_VIEW_CLICKED != 0) f.elapsedOffset = 500
            }
            f.connect()
            assertEquals(CapturePhase.FAILED, f.state().activeSession!!.phase)
            assertEquals(CaptureFailure.CONFIGURATION_TIMEOUT, f.state().activeSession!!.failure)
            assertEquals(DEFAULT_TYPES, f.service.serviceInfo.eventTypes)
        }
    }

    @Test fun newEngineOnReconnectIsPausedIndependentlyOfServiceEventRoutingThenCanActAfterCancel() = runTest {
        withService { f ->
            f.installTargetWindow()
            f.connect()
            f.engines.last().engine.onForegroundSignal(ForegroundSignal.TargetActive(f.identity, f.now()))
            advanceTimeBy(120)
            runCurrent()
            assertTrue(f.engines.last().queries.isEmpty())
            assertTrue(shadowOf(f.nodes.target).performedActions.isEmpty())

            f.connect()
            assertEquals(2, f.engines.size)
            f.engines.last().engine.onForegroundSignal(ForegroundSignal.TargetActive(f.identity, f.now()))
            advanceTimeBy(120)
            runCurrent()
            assertTrue(f.engines.last().queries.isEmpty())
            assertTrue(shadowOf(f.nodes.target).performedActions.isEmpty())

            f.container.ruleCaptureCoordinator.cancel(SESSION_ID)
            runCurrent()
            assertEquals(f.identity.activityName, f.container.targetAppVersionReader.verifiedActivity(TARGET.packageName, f.identity.activityName))
            assertTrue(f.container.ruleCatalog.snapshot.value.rulesForTarget(TARGET).isNotEmpty())
            assertTrue(f.flash.isAppEnabled(TARGET))
            f.sendTargetWindowEvent()
            advanceTimeBy(120)
            runCurrent()
            assertTrue(f.engines.last().queries.isNotEmpty())
            assertEquals(listOf(AccessibilityNodeInfo.ACTION_CLICK), shadowOf(f.nodes.target).performedActions)
        }
    }

    @Test fun interruptRetainsCompletionEvidenceAndDoesNotRestartObservationForLaterStateEmission() = runTest {
        withService { f ->
            f.connect()
            val pending = f.state().activeSession!!.pendingSample
            f.service.onInterrupt()
            runCurrent()
            assertEquals(pending, f.state().activeSession!!.pendingSample)
            assertEquals(CapturePhase.CAPTURED, f.state().activeSession!!.phase)
            assertTrue(f.state().activeSession!!.blocksAutomation)
            f.container.ruleCaptureCoordinator.cancel(SESSION_ID)
            f.container.ruleCaptureCoordinator.requestStart()
            runCurrent()
            val before = f.state()
            val writes = f.captureData.attempts
            val reads = f.shadow.windowReads
            advanceTimeBy(3_000)
            runCurrent()
            assertEquals(before, f.state())
            assertEquals(writes, f.captureData.attempts)
            assertEquals(reads, f.shadow.windowReads)
            assertEquals(CapturePhase.CONFIGURING, f.state().activeSession!!.phase)
            assertEquals(DEFAULT_TYPES, f.service.serviceInfo.eventTypes)
        }
    }

    @Test fun destroySettlesPendingEvidenceThenCancelledServiceTasksCannotWriteLater() = runTest {
        withService(CapturePhase.AWAITING_CONFIRMATION) { f ->
            f.connect()
            val candidate = f.state().activeSession!!.pendingObservedCandidate
            f.destroy()
            runCurrent()
            val settled = f.state()
            assertEquals(candidate, settled.activeSession!!.pendingObservedCandidate)
            assertFalse(settled.activeSession!!.listensForCandidateClick)
            assertTrue(settled.activeSession!!.blocksAutomation)
            assertFalse(f.container.serviceConnections.connected.value)
            val writes = f.captureData.attempts
            advanceTimeBy(700_000)
            runCurrent()
            assertEquals(settled, f.state())
            assertEquals(writes, f.captureData.attempts)
        }
    }

    @Test fun asynchronousDisconnectWriteFailureRetainsPayloadAndReconnectRetriesSettlement() = runTest {
        withService(CapturePhase.AWAITING_CONFIRMATION) { f ->
            f.connect()
            f.capture.update { state -> state.copy(activeSession = state.activeSession!!.copy(candidateClickListeningEnabled = true)) }
            runCurrent()
            assertEquals(AccessibilityEvent.TYPE_VIEW_CLICKED, f.service.serviceInfo.eventTypes)
            val before = f.state()
            f.captureData.beforeUpdate = { throw IOException("断连结算未提交") }
            f.service.onInterrupt()
            runCurrent()
            assertEquals(before, f.state())
            assertEquals(CaptureOperationFailure.STORAGE, f.container.ruleCaptureCoordinator.serviceFailure.value!!.reason)
            assertEquals(DEFAULT_TYPES, f.service.serviceInfo.eventTypes)
            f.engines.last().engine.onForegroundSignal(ForegroundSignal.TargetActive(f.identity, f.now()))
            advanceTimeBy(120)
            runCurrent()
            assertTrue(f.engines.last().queries.isEmpty())

            f.captureData.beforeUpdate = {}
            f.connect()
            assertEquals(before.activeSession!!.pendingObservedCandidate, f.state().activeSession!!.pendingObservedCandidate)
            assertFalse(f.state().activeSession!!.listensForCandidateClick)
            assertNull(f.container.ruleCaptureCoordinator.serviceFailure.value)
        }
    }

    @Test fun delayedFirstStoreReadRejectsEventsAndKeepsNewEnginePausedBeforeRecovery() = runTest {
        withService { f ->
            f.installTargetWindow()
            val readGate = CompletableDeferred<Unit>()
            f.captureData.readGate = readGate
            f.connect()
            f.sendTargetWindowEvent()
            f.engines.last().engine.onForegroundSignal(ForegroundSignal.TargetActive(f.identity, f.now()))
            advanceTimeBy(120)
            runCurrent()
            assertEquals(0, f.shadow.windowReads)
            assertTrue(f.engines.last().queries.isEmpty())
            assertTrue(shadowOf(f.nodes.target).performedActions.isEmpty())
            readGate.complete(Unit)
            runCurrent()
            assertEquals(CapturePhase.CAPTURED, f.state().activeSession!!.phase)
            assertEquals(DEFAULT_TYPES, f.service.serviceInfo.eventTypes)
        }
    }

    @Test fun failedRecoveryWriteDoesNotReenablePlatformListeningOrObservation() = runTest {
        for (phase in listOf(CapturePhase.AWAITING_CONFIRMATION, CapturePhase.WAITING_FOR_TARGET, CapturePhase.OBSERVING_CLICK, CapturePhase.CONFIGURING)) {
            withService(phase, remainingMs = if (phase == CapturePhase.CONFIGURING) 0 else 60_000) { f ->
                f.installTargetWindow()
                val before = f.state()
                f.captureData.beforeUpdate = { throw IOException("恢复关闭监听未提交") }
                f.connect()
                assertEquals(before, f.state())
                assertEquals(CaptureOperationFailure.STORAGE, f.container.ruleCaptureCoordinator.serviceFailure.value!!.reason)
                assertEquals(DEFAULT_TYPES, f.service.serviceInfo.eventTypes)
                f.sendTargetWindowEvent()
                f.engines.last().engine.onForegroundSignal(ForegroundSignal.TargetActive(f.identity, f.now()))
                advanceTimeBy(1_000)
                runCurrent()
                assertEquals(0, f.shadow.windowReads)
                assertTrue(f.engines.last().queries.isEmpty())
                assertEquals(before, f.state())
                f.captureData.beforeUpdate = {}
                f.connect()
                if (phase == CapturePhase.AWAITING_CONFIRMATION) {
                    assertFalse(f.state().activeSession!!.listensForCandidateClick)
                    assertEquals(before.activeSession!!.pendingObservedCandidate, f.state().activeSession!!.pendingObservedCandidate)
                } else {
                    assertEquals(CapturePhase.FAILED, f.state().activeSession!!.phase)
                }
            }
        }
    }

    @Test fun sameSessionEmissionRetriesRecoveryBeforeEnablingPlatformAfterStorageRecovers() = runTest {
        for (phase in listOf(CapturePhase.AWAITING_CONFIRMATION, CapturePhase.WAITING_FOR_TARGET)) {
            withService(phase) { f ->
                f.captureData.beforeUpdate = { throw IOException("首发恢复未提交") }
                f.connect()
                f.captureData.beforeUpdate = {}
                f.container.ruleCaptureCoordinator.markLeftFlashSkip(SESSION_ID)
                runCurrent()
                val settled = f.state().activeSession!!
                if (phase == CapturePhase.AWAITING_CONFIRMATION) {
                    assertEquals(CapturePhase.AWAITING_CONFIRMATION, settled.phase)
                    assertFalse(settled.listensForCandidateClick)
                    assertEquals(f.initial.activeSession!!.pendingObservedCandidate, settled.pendingObservedCandidate)
                } else {
                    assertEquals(CapturePhase.FAILED, settled.phase)
                    assertEquals(CaptureFailure.SERVICE_UNAVAILABLE, settled.failure)
                }
                assertEquals(DEFAULT_TYPES, f.service.serviceInfo.eventTypes)
                assertEquals(0, f.shadow.windowReads)
            }
        }
    }

    @Test fun sameSessionEmissionCannotBypassRecoveryWhileSettlementWriteStillFails() = runTest {
        withService(CapturePhase.AWAITING_CONFIRMATION) { f ->
            f.captureData.beforeUpdate = { throw IOException("首发恢复未提交") }
            f.connect()
            var updates = 0
            f.captureData.beforeUpdate = {
                updates++
                if (updates > 1) throw IOException("离开已提交但恢复仍失败")
            }
            f.container.ruleCaptureCoordinator.markLeftFlashSkip(SESSION_ID)
            runCurrent()
            assertEquals(2, updates)
            val pending = f.state().activeSession!!
            assertTrue(pending.listensForCandidateClick)
            assertNotNull(pending.leftFlashSkipAtEpochMs)
            assertEquals(DEFAULT_TYPES, f.service.serviceInfo.eventTypes)
            f.sendTargetWindowEvent()
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(0, f.shadow.windowReads)
            assertEquals(CaptureOperationFailure.STORAGE, f.container.ruleCaptureCoordinator.serviceFailure.value!!.reason)
        }
    }

    @Test fun p4UnknownCaptureVersionBlocksRealServiceUntilSupportedDataReturns() = runTest {
        withService { f ->
            f.installTargetWindow()
            f.captureData.replaceRaw("rule_capture_state_v2", """{"version":99,"samples":[]}""")
            f.connect()
            assertServiceCannotAct(f)
            f.captureData.replaceRaw("rule_capture_state_v2", RuleCaptureCodec.encode(f.initial.copy(activeSession = null)))
            assertServiceCanAct(f)
        }
    }

    @Test fun p4DamagedSessionKeepsValidRulesNonExecutableUntilExplicitRecovery() = runTest {
        withService { f ->
            f.installTargetWindow()
            val broken = org.json.JSONObject(RuleCaptureCodec.encode(f.initial)).apply {
                getJSONObject("activeSession").put("phase", "BAD")
            }.toString()
            f.captureData.replaceRaw("rule_capture_state_v2", broken)
            f.connect()
            assertEquals(f.initial.samples, f.capture.reads.first().value!!.samples)
            assertServiceCannotAct(f)
            f.captureData.replaceRaw("rule_capture_state_v2", RuleCaptureCodec.encode(f.initial.copy(activeSession = null)))
            assertServiceCanAct(f)
        }
    }

    @Test fun p4UnknownRecordVersionPausesAlreadyConnectedServiceAndRecovers() = runTest {
        withService { f ->
            f.installTargetWindow()
            f.connect()
            f.container.ruleCaptureCoordinator.cancel(SESSION_ID)
            runCurrent()
            f.flashData.replaceRaw("action_records_v2", """{"version":99,"records":[]}""")
            runCurrent()
            assertServiceCannotAct(f)
            f.flashData.replaceRaw("action_records_v2", """{"version":2,"records":[]}""")
            assertServiceCanAct(f)
        }
    }

    @Test fun p4CaptureReadIoAtConnectDoesNotCrashAndSameSubscriptionRecovers() = runTest {
        withService { f ->
            f.installTargetWindow()
            f.capture.update { it.copy(activeSession = null) }
            runCurrent()
            f.captureData.readFailure = IOException("read unavailable")
            f.connect()
            assertServiceCannotAct(f)
            f.captureData.readFailure = null
            advanceTimeBy(1_000); runCurrent()
            assertServiceCanAct(f)
        }
    }

    @Test fun p4PreferencesReadIoAtConnectKeepsEnginePausedUntilValidRead() = runTest {
        withService { f ->
            f.installTargetWindow()
            f.capture.update { it.copy(activeSession = null) }
            runCurrent()
            f.flashData.readFailure = IOException("read unavailable")
            f.connect()
            assertServiceCannotAct(f)
            f.flashData.readFailure = null
            advanceTimeBy(1_000); runCurrent()
            assertServiceCanAct(f)
        }
    }

    @Test fun p5WaitingForFirstStorageReadMustNotReportReady() = runTest {
        withService { f ->
            val status = homeServiceStatus(f)
            val gate = CompletableDeferred<Unit>()
            f.captureData.readGate = gate
            try {
                f.connect()
                assertNotEquals(com.zhenghuaizhi.flashskip.domain.model.ServiceStatus.RUNNING, status.status.value)
            } finally { gate.complete(Unit) }
        }
    }

    @Test fun p5InterruptedServiceMustNotReportReady() = runTest {
        withService { f ->
            val status = homeServiceStatus(f)
            f.connect(); f.service.onInterrupt(); runCurrent()
            assertNotEquals(com.zhenghuaizhi.flashskip.domain.model.ServiceStatus.RUNNING, status.status.value)
        }
    }

    @Test fun p5UnavailablePreferencesMustNotReportReady() = runTest {
        withService { f ->
            val status = homeServiceStatus(f)
            f.flashData.readFailure = IOException("unavailable")
            f.connect()
            assertNotEquals(com.zhenghuaizhi.flashskip.domain.model.ServiceStatus.RUNNING, status.status.value)
        }
    }

    @Test fun p5FailedRecoveryMustNotReportReady() = runTest {
        withService(phase = CapturePhase.AWAITING_CONFIRMATION) { f ->
            val status = homeServiceStatus(f)
            f.captureData.beforeUpdate = { throw IOException("recovery") }
            f.connect()
            assertNotEquals(com.zhenghuaizhi.flashskip.domain.model.ServiceStatus.RUNNING, status.status.value)
        }
    }

    @Test fun p5NormalPendingSaveDoesNotMakeReadyServiceAbnormal() = runTest {
        withService { f ->
            val status = homeServiceStatus(f)
            f.connect()
            assertEquals(CapturePhase.CAPTURED, f.state().activeSession!!.phase)
            assertEquals(com.zhenghuaizhi.flashskip.domain.model.ServiceStatus.RUNNING, status.status.value)
        }
    }

    @Test fun p5DestroyedServiceIsNotReady() = runTest {
        withService { f ->
            val status = homeServiceStatus(f)
            f.connect(); f.destroy(); runCurrent()
            assertNotEquals(com.zhenghuaizhi.flashskip.domain.model.ServiceStatus.RUNNING, status.status.value)
        }
    }

    @Test fun p5RecoveredStorageRestoresReadyStatus() = runTest {
        withService { f ->
            val status = homeServiceStatus(f)
            f.flashData.readFailure = IOException("unavailable"); f.connect()
            f.flashData.readFailure = null; advanceTimeBy(1_000); runCurrent()
            assertEquals(com.zhenghuaizhi.flashskip.domain.model.ServiceStatus.RUNNING, status.status.value)
        }
    }

    @Test fun p5InterruptedServiceBlocksHomeStartEvenWhenCaptureIsEmpty() = runTest {
        withService { f ->
            val status = homeServiceStatus(f)
            f.connect(); f.container.ruleCaptureCoordinator.cancel(SESSION_ID); runCurrent()
            val vm = com.zhenghuaizhi.flashskip.ui.home.HomeViewModel(f.flash, f.container.ruleCatalog, status, f.container.ruleCaptureCoordinator)
            val collector = backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect { } }
            try {
                f.service.onInterrupt(); runCurrent()
                assertFalse(vm.uiState.value.capture.canStart)
                vm.startCapture(); runCurrent()
                assertNull(f.state().activeSession)
            } finally {
                collector.cancel(); vm.viewModelScope.cancel(); runCurrent()
            }
        }
    }

    @Test fun p5ReadyServiceDowngradesOnUnknownStorageAndRecoversAfterRepair() = runTest {
        withService { f ->
            val status = homeServiceStatus(f)
            f.connect(); assertEquals(com.zhenghuaizhi.flashskip.domain.model.ServiceStatus.RUNNING, status.status.value)
            f.flashData.replaceRaw("action_records_v2", """{"version":99,"records":[]}""")
            runCurrent()
            assertFalse(status.isReady)
            assertNotEquals(com.zhenghuaizhi.flashskip.domain.model.ServiceStatus.RUNNING, status.status.value)
            f.flashData.replaceRaw("action_records_v2", """{"version":2,"records":[]}""")
            runCurrent(); assertTrue(status.isReady)
        }
    }

    @Test fun p5ReconnectCannotReusePriorReadinessWhileNewReadIsBlocked() = runTest {
        withService { f ->
            val status = homeServiceStatus(f)
            f.connect(); assertTrue(status.isReady)
            val gate = CompletableDeferred<Unit>(); f.captureData.readGate = gate
            try {
                f.connect(); assertFalse(status.isReady)
            } finally { gate.complete(Unit) }
            runCurrent(); assertTrue(status.isReady)
        }
    }

    @Test fun p5InterruptedServiceStaysUnavailableAcrossStorageUpdatesUntilReconnect() = runTest {
        withService { f ->
            val status = homeServiceStatus(f)
            f.connect(); f.service.onInterrupt(); runCurrent()
            f.capture.update { it.copy(activeSession = null) }; runCurrent()
            assertFalse(status.isReady)
            f.connect(); assertTrue(status.isReady)
        }
    }

    private fun TestScope.homeServiceStatus(f: ServiceFixture) =
        com.zhenghuaizhi.flashskip.application.ServiceStatusCoordinator(
            com.zhenghuaizhi.flashskip.application.SystemServiceStatusReader { true },
            f.container.serviceConnections,
            backgroundScope,
        ).also { it.refresh(); runCurrent() }

    private suspend fun TestScope.assertServiceCannotAct(f: ServiceFixture) {
        f.sendTargetWindowEvent()
        // 同时绕过服务事件路由投递信号，验证引擎暂停本身生效。
        f.engines.last().engine.onForegroundSignal(ForegroundSignal.TargetActive(f.identity, f.now()))
        advanceTimeBy(120); runCurrent()
        assertTrue(f.engines.last().queries.isEmpty())
        assertTrue(shadowOf(f.nodes.target).performedActions.isEmpty())
        assertEquals(DEFAULT_TYPES, f.service.serviceInfo.eventTypes)
    }

    private fun TestScope.assertServiceCanAct(f: ServiceFixture) {
        runCurrent()
        assertTrue(f.container.ruleCatalog.snapshot.value.rulesForTarget(TARGET).isNotEmpty())
        f.sendTargetWindowEvent()
        advanceTimeBy(120); runCurrent()
        assertTrue(f.engines.last().queries.isNotEmpty())
        assertEquals(listOf(AccessibilityNodeInfo.ACTION_CLICK), shadowOf(f.nodes.target).performedActions)
    }

    private suspend fun TestScope.withService(
        phase: CapturePhase = CapturePhase.CAPTURED,
        remainingMs: Long = 60_000,
        block: suspend TestScope.(ServiceFixture) -> Unit,
    ) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = ServiceFixture(this, temporaryFolder.newFolder(), phase, remainingMs)
        try {
            f.seed()
            block(f)
        } finally {
            f.captureData.beforeUpdate = {}
            f.captureData.readFailure = null
            f.flashData.readFailure = null
            f.destroy()
            runCurrent()
            f.job.cancelAndJoin()
            Dispatchers.resetMain()
        }
    }

    private class ServiceFixture(
        private val testScope: TestScope,
        directory: File,
        phase: CapturePhase,
        remainingMs: Long,
    ) {
        val job = SupervisorJob()
        private val dispatcher = StandardTestDispatcher(testScope.testScheduler)
        private val scope = CoroutineScope(job + dispatcher)
        var elapsedOffset = 0L
        fun now(): Long = testScope.testScheduler.currentTime + elapsedOffset
        private val clock = object : MonotonicClock, WallClock {
            override fun elapsedRealtimeMs() = now()
            override fun currentTimeMillis() = 1_000_000L + now()
        }
        val captureData = LifecycleDataStore(PreferenceDataStoreFactory.create(scope = scope) { File(directory, "capture.preferences_pb") })
        val flashData = LifecycleDataStore(PreferenceDataStoreFactory.create(scope = scope) { File(directory, "flash.preferences_pb") })
        val capture = PreferencesRuleCaptureStore(captureData, scope)
        val flash = PreferencesFlashSkipStore(flashData, scope)
        private val context = RuntimeEnvironment.getApplication()
        val container = AppContainer(context, AppContainerRuntime(scope, flash, capture, clock, clock, dispatcher, dispatcher))
        val service = Robolectric.buildService(FlashSkipAccessibilityService::class.java).create().get()
        val shadow: P3FlashServiceShadow = shadowOf(service) as P3FlashServiceShadow
        val nodes = AccessibilityFixture()
        val identity: PageIdentity get() = nodes.identity
        private val saved = captureSample(10).copy(
            nodes = captureSample(10).nodes.map { it.copy(resourceId = nodes.selector.resourceId) },
            preClickUniqueSelectors = listOf(CapturedSelector(resourceId = nodes.selector.resourceId)),
        )
        val initial = CaptureSaveFixture(testScope.backgroundScope, testScope.testScheduler, phase).initialCapture.let { state ->
            state.copy(samples = listOf(saved), activeSession = state.activeSession!!.copy(
                phaseStartedAtEpochMs = 1_000_000L - if (phase == CapturePhase.CONFIGURING) 1_500 else 0,
                deadlineAtEpochMs = 1_000_000L + remainingMs,
                candidateClickListeningEnabled = phase == CapturePhase.AWAITING_CONFIRMATION,
            ))
        }
        val engines = mutableListOf<EngineEvidence>()
        private var destroyed = false

        init {
            service.runtimeFactory = { container }
            service.automationEngineFactory = { actualContainer, gateway, engineScope ->
                val queries = mutableListOf<PageIdentity>()
                val observingGateway = object : PageGateway by gateway {
                    override suspend fun query(identity: PageIdentity, selector: NodeSelector): QueryResult {
                        queries += identity
                        return gateway.query(identity, selector)
                    }
                }
                actualContainer.createAutomationEngine(observingGateway, clock, clock, engineScope).also {
                    engines += EngineEvidence(it, queries)
                }
            }
        }

        suspend fun seed() {
            capture.update { initial }
            flash.setAppEnabled(TARGET, true)
            container.ruleCatalog.snapshot.first { it.sourceSamples == initial.samples }
            testScope.runCurrent()
        }

        fun connect() {
            ReflectionHelpers.callInstanceMethod<Unit>(service, "onServiceConnected")
            testScope.runCurrent()
        }
        fun destroy() {
            if (!destroyed) { destroyed = true; service.onDestroy() }
        }
        suspend fun state(): RuleCaptureState = capture.state.first()

        fun installTargetWindow() {
            val appInfo = ApplicationInfo().apply {
                packageName = TARGET.packageName
                enabled = true
            }
            val activity = ActivityInfo().apply {
                packageName = TARGET.packageName
                name = identity.activityName!!
                applicationInfo = appInfo
                enabled = true
                exported = true
            }
            shadowOf(context.packageManager).installPackage(PackageInfo().apply {
                packageName = TARGET.packageName
                applicationInfo = appInfo
                activities = arrayOf(activity)
                versionName = "1"
            })
            shadowOf(context.packageManager).addOrUpdateActivity(activity)
            context.packageManager.getActivityInfo(
                ComponentName(TARGET.packageName, identity.activityName!!),
                PackageManager.ComponentInfoFlags.of(0),
            )
            shadow.setWindows(listOf(nodes.window))
            shadow.setRootInActiveWindow(nodes.root)
        }

        fun sendTargetWindowEvent() {
            val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
            event.packageName = TARGET.packageName
            event.className = identity.activityName
            shadowOf(event).setWindowId(7)
            try { service.onAccessibilityEvent(event) } finally { event.recycle() }
            testScope.runCurrent()
        }
    }

    private data class EngineEvidence(val engine: AutomationEngine, val queries: MutableList<PageIdentity>)

    private class LifecycleDataStore(private val delegate: DataStore<Preferences>) : DataStore<Preferences> {
        var readGate: CompletableDeferred<Unit>? = null
        var readFailure: IOException? = null
        override val data = flow {
            readGate?.await()
            readFailure?.let { throw it }
            emitAll(delegate.data)
        }
        suspend fun replaceRaw(name: String, raw: String) {
            delegate.updateData { it.toMutablePreferences().apply { this[stringPreferencesKey(name)] = raw } }
        }
        var attempts = 0
        var beforeUpdate: suspend () -> Unit = {}
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            attempts++
            beforeUpdate()
            return delegate.updateData(transform)
        }
    }

    private companion object {
        const val DEFAULT_TYPES = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
    }
}

/** 提供系统连接信息与窗口副本，业务仍走真实服务、协调器、目录和引擎。 */
@Suppress("DEPRECATION")
@Implements(AccessibilityService::class)
class P3FlashServiceShadow : ShadowAccessibilityService() {
    private var configuration = AccessibilityServiceInfo()
    var onConfiguration: (Int) -> Unit = {}
    var windowReads = 0
    @Implementation fun getServiceInfo(): AccessibilityServiceInfo = configuration
    @Implementation fun setServiceInfo(info: AccessibilityServiceInfo) {
        onConfiguration(info.eventTypes)
        configuration = info
    }
    @Implementation override fun getWindows(): List<AccessibilityWindowInfo> {
        windowReads++
        return super.getWindows().map { AccessibilityWindowInfo.obtain(it) }
    }
}

/** Robolectric 尚未桥接新 flags 签名；仍由已有包索引查询实现验证 Activity。 */
@Implements(className = "android.app.ApplicationPackageManager")
class P3ActivityPackageManagerShadow : ShadowApplicationPackageManager() {
    @Implementation(minSdk = 33)
    fun getActivityInfo(component: ComponentName, flags: PackageManager.ComponentInfoFlags): ActivityInfo =
        super.getActivityInfo(component, flags.value.toInt())
}
