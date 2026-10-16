package com.zhenghuaizhi.flashskip.ui.home

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import com.zhenghuaizhi.flashskip.domain.model.CapturePhase
import com.zhenghuaizhi.flashskip.domain.model.ServiceStatus
import com.zhenghuaizhi.flashskip.domain.model.TargetAppId
import com.zhenghuaizhi.flashskip.ui.theme.FlashSkipTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** 控件直接消费状态，回调只记录调用；业务接线另由 Route 集成测试验证。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class, qualifiers = "zh-rCN-w411dp-h891dp")
@LooperMode(LooperMode.Mode.PAUSED)
class HomeScreenComposeTest {
    @get:Rule val compose = createComposeRule()
    private val target = TargetAppId("com.example.compose")
    private val card = TargetAppUiState(target, "测试应用", true, TargetAppStatus.ENABLED_AND_RUNNING, true)
    private val state = mutableStateOf(HomeUiState())
    private val calls = mutableListOf<String>()

    @Composable
    private fun Content() {
        FlashSkipTheme {
            FlashSkipScreen(state.value,
                onToggleApp = { id, enabled -> calls += "toggle:${id.packageName}:$enabled" },
                onOpenAccessibilitySettings = { calls += "settings" },
                onStartCapture = { calls += "start" },
                onFinishCapture = { calls += "finish" },
                onCancelCapture = { calls += "cancel" },
                onClearCapturedRules = { calls += "delete:${it.packageName}"; busy() },
                onClearAllCapturedRules = { calls += "deleteAll"; busy() },
                onRetry = { calls += "retry" },
            )
        }
    }
    private fun show(value: HomeUiState = HomeUiState()) {
        state.value = value
        compose.setContent { Content() }
    }
    private fun ready() = HomeUiState(serviceStatus = ServiceStatus.RUNNING, capture = CaptureUiState(canStart = true))
    private fun busy() { state.value = state.value.copy(operationInProgress = true) }
    private fun button(text: String) = compose.onNode(hasText(text) and hasClickAction())
    private fun click(text: String) {
        val node = button(text)
        if (text == "删除全部应用") node.performScrollTo()
        node.performClick()
    }
    private fun role(role: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)

    @Test fun notEnabledShowsSettingsAndBlocksStart() {
        show()
        compose.onNodeWithText("需要开启").assertIsDisplayed()
        button("去开启").assertIsEnabled().assert(role(Role.Button)).performClick()
        button("添加应用").assertIsNotEnabled().performClick()
        assertEquals(listOf("settings"), calls)
    }
    @Test fun confirmingDoesNotExposeReadyOrSettingsAction() {
        show(HomeUiState(serviceStatus = ServiceStatus.CONFIRMING))
        compose.onNodeWithText("正在准备").assertIsDisplayed()
        compose.onNodeWithText("已准备好").assertDoesNotExist()
        button("添加应用").assertIsNotEnabled()
        button("去开启").assertDoesNotExist(); button("检查设置").assertDoesNotExist()
    }
    @Test fun abnormalSettingsActionDoesNotStartCapture() {
        show(HomeUiState(serviceStatus = ServiceStatus.ABNORMAL))
        compose.onNodeWithText("暂时无法使用").assertIsDisplayed()
        click("检查设置"); button("添加应用").assertIsNotEnabled()
        assertEquals(listOf("settings"), calls)
    }
    @Test fun readyRequiresExplanationConfirmationAndOnlyStartsOnce() {
        show(ready())
        compose.onNodeWithText("已准备好").assertIsDisplayed()
        click("添加应用"); assertTrue(calls.isEmpty())
        compose.onNode(isDialog()).assertExists()
        click("开始添加"); compose.onNode(isDialog()).assertDoesNotExist()
        assertEquals(listOf("start"), calls)
    }
    @Test fun explanationCancelDoesNotStartAndReopeningStillRequiresConfirmation() {
        show(ready()); click("添加应用"); click("取消")
        assertTrue(calls.isEmpty()); compose.onNode(isDialog()).assertDoesNotExist()
        click("添加应用"); button("开始添加").assertIsEnabled()
        assertTrue(calls.isEmpty())
    }
    @Test fun readinessLossDisablesOpenExplanationConfirmation() {
        show(ready()); click("添加应用")
        compose.runOnIdle { state.value = HomeUiState(serviceStatus = ServiceStatus.ABNORMAL) }
        button("开始添加").assertIsNotEnabled().performClick()
        assertTrue(calls.isEmpty()); click("取消")
    }
    @Test fun allCapturePhasesExposeTheCorrectSaveOrCancelAction() {
        show(ready())
        val phases = listOf(
            CapturePhase.CONFIGURING to "取消添加", CapturePhase.WAITING_FOR_TARGET to "取消添加",
            CapturePhase.OBSERVING_CLICK to "取消添加", CapturePhase.CAPTURED to "保存",
            CapturePhase.AWAITING_CONFIRMATION to "确认并保存", CapturePhase.FAILED to "关闭提示",
        )
        phases.forEach { (phase, label) ->
            compose.runOnIdle { state.value = ready().copy(capture = CaptureUiState(phase = phase, canStart = phase == CapturePhase.FAILED)) }
            click(label)
        }
        assertEquals(listOf("cancel", "cancel", "cancel", "finish", "finish", "cancel"), calls)
    }
    @Test fun activeOperationDisablesCaptureSaveAndCancel() {
        show(ready().copy(capture = CaptureUiState(phase = CapturePhase.CAPTURED, operationInProgress = true)))
        button("保存").assertIsNotEnabled().performClick()
        button("不保存").assertIsNotEnabled().performClick()
        assertTrue(calls.isEmpty())
    }
    @Test fun unavailableServiceRetainsCheckedSwitchAndAllowsExplicitDisable() {
        show(HomeUiState(serviceStatus = ServiceStatus.ABNORMAL,
            targetApps = listOf(card.copy(status = TargetAppStatus.ENABLED_BUT_SERVICE_UNAVAILABLE, canEnable = false))))
        compose.onNodeWithText("已开启，暂时无法使用").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("测试应用，自动关闭")
            .assert(role(Role.Switch)).assertIsOn().assertIsEnabled()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已开启")).performClick()
        assertEquals(listOf("toggle:${target.packageName}:false"), calls)
        button("添加应用").assertIsNotEnabled()
    }
    @Test fun unavailableRuleCannotBeEnabledAndExplainsItsSwitchState() {
        show(ready().copy(targetApps = listOf(card.copy(checked = false, status = TargetAppStatus.RULE_UNAVAILABLE, canEnable = false))))
        compose.onNodeWithContentDescription("测试应用，自动关闭").performScrollTo()
            .assertIsOff().assertIsNotEnabled()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "需要重新添加")).performClick()
        assertTrue(calls.isEmpty())
    }
    @Test fun storageFailureDisablesAllManagementAndAnnouncesFeedback() {
        show(HomeUiState(targetApps = listOf(card), ruleActionsEnabled = false, operationFeedback = HomeFeedback.STORAGE))
        compose.onNodeWithContentDescription("测试应用，自动关闭").performScrollTo().assertIsNotEnabled().performClick()
        compose.onNodeWithContentDescription("删除测试应用").assertIsNotEnabled().performClick()
        button("删除全部应用").performScrollTo().assertIsNotEnabled().performClick()
        compose.onNodeWithText("未能保存更改，请检查可用存储空间后重试刚才的操作。")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        assertTrue(calls.isEmpty())
    }
    @Test fun singleDeleteCancelThenConfirmLocksDuplicateSubmission() {
        show(ready().copy(targetApps = listOf(card)))
        compose.onNodeWithContentDescription("删除测试应用").performScrollTo().performClick()
        compose.onNodeWithText("删除「测试应用」？").assertIsDisplayed()
        click("取消"); assertTrue(calls.isEmpty())
        compose.onNodeWithContentDescription("删除测试应用").performClick()
        compose.onNode(hasText("删除") and hasAnyAncestor(isDialog()) and hasClickAction()).performClick()
        compose.onNode(hasText("删除") and hasAnyAncestor(isDialog()) and hasClickAction()).assertIsNotEnabled().performClick()
        button("取消").assertIsNotEnabled()
        assertEquals(listOf("delete:${target.packageName}"), calls)
    }
    @Test fun deleteAllDialogConfirmsOnlyAfterExplicitAction() {
        show(ready().copy(targetApps = listOf(card)))
        click("删除全部应用"); compose.onNodeWithText("删除全部应用？").assertIsDisplayed()
        click("取消"); assertTrue(calls.isEmpty())
        click("删除全部应用"); click("全部删除")
        button("全部删除").assertIsNotEnabled().performClick()
        assertEquals(listOf("deleteAll"), calls)
    }
    @Test fun removedTargetDismissesItsOpenDeleteDialogWithoutCallback() {
        show(ready().copy(targetApps = listOf(card)))
        compose.onNodeWithContentDescription("删除测试应用").performScrollTo().performClick()
        compose.runOnIdle { state.value = ready() }
        compose.onNode(isDialog()).assertDoesNotExist(); assertTrue(calls.isEmpty())
    }
    @Test fun removedAllTargetsDismissesBulkDeleteDialog() {
        show(ready().copy(targetApps = listOf(card))); click("删除全部应用")
        compose.runOnIdle { state.value = ready() }
        compose.onNode(isDialog()).assertDoesNotExist(); assertTrue(calls.isEmpty())
    }
    @Test fun failedDeleteExposesRetryInDialogAndUsesRetryCallback() {
        show(ready().copy(targetApps = listOf(card)))
        compose.onNodeWithContentDescription("删除测试应用").performScrollTo().performClick()
        compose.onNode(hasText("删除") and hasAnyAncestor(isDialog()) and hasClickAction()).performClick()
        compose.runOnIdle { state.value = state.value.copy(operationInProgress = false, operationFeedback = HomeFeedback.STORAGE, canRetry = true) }
        compose.onNode(hasText("重试") and hasAnyAncestor(isDialog()) and hasClickAction()).performClick()
        assertEquals(listOf("delete:${target.packageName}", "retry"), calls)
    }
    @Test fun standaloneFeedbackRetryIsWiredAndPolite() {
        show(HomeUiState(operationFeedback = HomeFeedback.RETURN_STORAGE, canRetry = true))
        compose.onNodeWithText("本次结果暂未保存，请重试。")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        click("重试"); assertEquals(listOf("retry"), calls)
    }
    @Test fun explanationSurvivesSavedStateRestorationWithoutStartingCapture() {
        state.value = ready()
        val restoration = StateRestorationTester(compose)
        restoration.setContent { Content() }
        click("添加应用"); restoration.emulateSavedInstanceStateRestore()
        button("开始添加").assertIsEnabled(); assertTrue(calls.isEmpty())
        click("开始添加"); assertEquals(listOf("start"), calls)
    }
    @Test fun deleteTargetSurvivesSavedStateRestorationWithoutDeleting() {
        state.value = ready().copy(targetApps = listOf(card))
        val restoration = StateRestorationTester(compose)
        restoration.setContent { Content() }
        compose.onNodeWithContentDescription("删除测试应用").performScrollTo().performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("删除「测试应用」？").assertIsDisplayed(); assertTrue(calls.isEmpty())
    }
}
