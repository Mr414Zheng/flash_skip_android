package com.zhenghuaizhi.flashskip.ui.home

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import com.zhenghuaizhi.flashskip.application.ServiceConnectionRegistry
import com.zhenghuaizhi.flashskip.application.ServiceStatusCoordinator
import com.zhenghuaizhi.flashskip.domain.model.CapturePhase
import com.zhenghuaizhi.flashskip.platform.settings.NavigationLaunchResult
import com.zhenghuaizhi.flashskip.testing.CaptureSaveFixture
import com.zhenghuaizhi.flashskip.testing.CommitGate
import com.zhenghuaizhi.flashskip.testing.TARGET
import com.zhenghuaizhi.flashskip.ui.theme.FlashSkipTheme
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** 使用真实 Route、ViewModel、协调器；替身只控制存储提交和系统导航的返回值。 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class, qualifiers = "zh-rCN-w411dp-h891dp")
@LooperMode(LooperMode.Mode.PAUSED)
class HomeRouteComposeTest {
    @get:Rule val compose = createComposeRule()
    private val scheduler = TestCoroutineScheduler()
    private val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(scheduler))
    private val capture = CaptureSaveFixture(scope, scheduler)
    private val registry = ServiceConnectionRegistry().also { it.setConnected(true) }
    private val status = ServiceStatusCoordinator({ true }, registry, scope)
    private lateinit var vm: HomeViewModel
    private val owner = object : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle = registry
    }
    private val visible = mutableStateOf(true)
    private var homeCalls = 0
    private var settingsCalls = 0
    private var homeResult = NavigationLaunchResult.STARTED
    private var settingsResult = NavigationLaunchResult.STARTED

    @Before fun setUp() {
        capture.captureStore.state.value = capture.initialCapture.copy(activeSession = null,
            samples = capture.initialCapture.samples.map { it.copy(targetAppName = if (it.targetAppId == TARGET) "测试目标" else "其他目标") })
        capture.catalog.publish(capture.captureStore.state.value.samples)
        status.refresh()
        vm = HomeViewModel(capture.flashStore, capture.catalog, status, capture.coordinator)
        owner.registry.currentState = Lifecycle.State.RESUMED
        compose.setContent {
            FlashSkipTheme {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    if (visible.value) FlashSkipRoute(vm,
                        onOpenAccessibilitySettings = { settingsCalls++; settingsResult },
                        onGoHome = { homeCalls++; homeResult },
                    )
                }
            }
        }
        settle()
    }
    @After fun tearDown() {
        compose.runOnIdle { vm.viewModelScope.cancel(); scope.cancel() }
        scheduler.runCurrent()
    }
    private fun settle() {
        scheduler.runCurrent(); compose.waitForIdle()
        scheduler.runCurrent(); compose.waitForIdle()
    }
    private fun click(text: String) { compose.onNode(hasText(text) and hasClickAction()).performClick(); settle() }
    private fun start(): Long {
        click("添加应用"); click("开始添加")
        val session = requireNotNull(capture.captureStore.state.value.activeSession)
        assertEquals(CapturePhase.CONFIGURING, session.phase)
        assertEquals(0, homeCalls)
        return session.id
    }
    private fun configured(id: Long) { runBlocking { capture.coordinator.markConfigured(id) }; settle() }
    private fun stop(changingConfiguration: Boolean) {
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED; vm.onHostStopped(changingConfiguration) }
        settle()
    }
    private fun resume() {
        compose.runOnIdle { vm.onHostResumed(); owner.registry.currentState = Lifecycle.State.RESUMED }
        settle()
    }
    private fun hide() { compose.runOnIdle { visible.value = false }; settle() }
    private fun show() { compose.runOnIdle { visible.value = true }; settle() }

    @Test fun realStartClickWaitsForConfigurationAndNavigatesOnlyOnce() {
        val id = start(); configured(id)
        assertEquals(1, homeCalls)
        hide(); show(); assertEquals(1, homeCalls)
        assertEquals(id, capture.captureStore.state.value.activeSession!!.id)
    }
    @Test fun nonResumedLifecycleCannotConsumePendingNavigation() {
        val id = start()
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
        configured(id); assertEquals(0, homeCalls)
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }; settle()
        assertEquals(1, homeCalls)
    }
    @Test fun absentRouteReceivesStillValidNavigationOnceWhenRestored() {
        val id = start(); hide(); configured(id)
        assertEquals(0, homeCalls); show(); assertEquals(1, homeCalls)
        hide(); show(); assertEquals(1, homeCalls)
    }
    @Test fun configurationStopAndCompositionRecreationPreserveSessionAndNavigation() {
        val id = start(); stop(true); hide(); configured(id)
        assertEquals(0, homeCalls)
        assertNull(capture.captureStore.state.value.activeSession!!.leftFlashSkipAtEpochMs)
        show(); assertEquals(0, homeCalls)
        resume(); assertEquals(1, homeCalls)
        assertEquals(id, capture.captureStore.state.value.activeSession!!.id)
    }
    @Test fun realDepartureWhileConfiguringDoesNotNavigateAfterReturn() {
        val id = start(); stop(false); configured(id)
        assertEquals(0, homeCalls); resume(); assertEquals(0, homeCalls)
        assertNotEquals(CapturePhase.WAITING_FOR_TARGET, capture.captureStore.state.value.activeSession!!.phase)
    }
    @Test fun cancellationWhileReceiverAbsentDropsPendingNavigation() {
        val id = start(); hide(); configured(id)
        compose.runOnIdle { vm.cancelCapture() }; settle()
        assertNull(capture.captureStore.state.value.activeSession)
        show(); assertEquals(0, homeCalls)
    }
    @Test fun replacementSessionCannotReceiveOldPendingNavigation() {
        val id = start(); hide(); configured(id)
        val state = capture.captureStore.state.value
        capture.captureStore.state.value = state.copy(activeSession = state.activeSession!!.copy(id = id + 1))
        show(); assertEquals(0, homeCalls)
        assertEquals(id + 1, capture.captureStore.state.value.activeSession!!.id)
    }
    @Test fun expiredPendingNavigationIsRejectedByActualRouteConsumer() {
        val id = start(); hide(); configured(id)
        scheduler.advanceTimeBy(120_000); show()
        assertEquals(0, homeCalls)
        assertEquals(CapturePhase.FAILED, capture.captureStore.state.value.activeSession!!.phase)
    }
    @Test fun desktopFailureCancelsOriginalSessionAndDisplaysFeedback() {
        homeResult = NavigationLaunchResult.UNAVAILABLE
        val id = start(); configured(id)
        assertEquals(1, homeCalls); assertNull(capture.captureStore.state.value.activeSession)
        compose.onNodeWithText("无法回到桌面，本次添加已取消。请重新添加。").assertIsDisplayed()
        assertFalse(vm.uiState.value.canRetry)
    }
    @Test fun desktopFailureWithFailedCancellationRetriesTheOriginalCancellation() {
        homeResult = NavigationLaunchResult.UNAVAILABLE
        val id = start()
        // 配置提交成功后再注入取消写入失败，避免把配置失败冒充导航失败。
        capture.captureStore.afterCommit = {
            if (capture.captureStore.state.value.activeSession?.phase == CapturePhase.WAITING_FOR_TARGET) {
                capture.captureStore.beforeCommit = { throw IOException("cancel-fixture") }
            }
        }
        configured(id)
        assertEquals(1, homeCalls); assertEquals(id, capture.captureStore.state.value.activeSession!!.id)
        assertEquals(HomeFeedback.STORAGE, vm.uiState.value.operationFeedback)
        capture.captureStore.beforeCommit = {}; capture.captureStore.afterCommit = {}
        click("重试")
        assertNull(capture.captureStore.state.value.activeSession)
        assertEquals(HomeFeedback.GO_HOME_FAILED, vm.uiState.value.operationFeedback)
        assertEquals(1, homeCalls)
    }
    @Test fun settingsFailureUsesRealCallbackWithoutBusinessWritesOrRetry() {
        registry.setConnected(false); status.refresh(); scheduler.advanceTimeBy(2000); settle()
        settingsResult = NavigationLaunchResult.UNAVAILABLE
        val before = capture.captureStore.state.value
        click("检查设置")
        assertEquals(1, settingsCalls); assertEquals(before, capture.captureStore.state.value)
        assertTrue(capture.flashStore.writes.isEmpty())
        assertEquals(HomeFeedback.SETTINGS_FAILED, vm.uiState.value.operationFeedback)
        compose.onNode(hasText("重试") and hasClickAction()).assertDoesNotExist()
    }
    @Test fun actualSwitchClickDisablesStoredRuleWhileServiceIsUnavailable() {
        registry.setConnected(false); status.refresh(); scheduler.advanceTimeBy(2000); settle()
        val row = vm.uiState.value.targetApps.first { it.id == TARGET }
        compose.onNodeWithContentDescription("${row.displayName}，自动关闭").performScrollTo().assertIsOn().performClick()
        settle()
        assertEquals(false, capture.flashStore.preferences.value.enabledApps[TARGET])
        compose.onNodeWithContentDescription("${row.displayName}，自动关闭").assertIsOff()
        compose.onNode(hasText("添加应用") and hasClickAction()).assertIsNotEnabled()
    }
    @Test fun olderDeleteFailureCannotOverwriteLaterSettingsFailureInComposedScreen() {
        registry.setConnected(false); status.refresh(); scheduler.advanceTimeBy(2000); settle()
        val gate = CommitGate()
        capture.captureStore.beforeCommit = { gate.pause(); throw IOException("delete-fixture") }
        val row = vm.uiState.value.targetApps.first { it.id == TARGET }
        compose.onNodeWithContentDescription("删除${row.displayName}").performScrollTo().performClick()
        compose.onNode(hasText("删除") and hasAnyAncestor(isDialog()) and hasClickAction()).performClick(); settle()
        assertTrue(gate.entered.isCompleted)
        // 系统结果可能在删除提交等待期间到达，生产 ViewModel 必须保留该更新的归属。
        compose.runOnIdle { vm.reportSettingsLaunchFailure() }
        gate.release.complete(Unit); settle()
        assertEquals(HomeFeedback.SETTINGS_FAILED, vm.uiState.value.operationFeedback)
        assertFalse(vm.uiState.value.canRetry)
        compose.onAllNodesWithText("无法打开系统设置。请手动打开手机设置，在无障碍中开启 FlashSkip，再返回这里。").assertCountEquals(2)
    }
}
