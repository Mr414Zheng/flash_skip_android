package com.zhenghuaizhi.flashskip.ui.home

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.zhenghuaizhi.flashskip.domain.model.RuleAvailability
import com.zhenghuaizhi.flashskip.domain.model.CapturePhase
import com.zhenghuaizhi.flashskip.domain.model.CapturedSelector
import com.zhenghuaizhi.flashskip.domain.model.RuleCardStatus
import com.zhenghuaizhi.flashskip.domain.model.ServiceStatus
import com.zhenghuaizhi.flashskip.domain.model.TargetApp
import com.zhenghuaizhi.flashskip.domain.model.TargetAppId
import com.zhenghuaizhi.flashskip.ui.theme.FlashSkipTheme
import org.junit.Rule
import org.junit.Test

class HomeScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun emptyStateExposesOneGenericCaptureButton() {
        composeRule.setContent {
            FlashSkipTheme {
                FlashSkipScreen(
                    state = HomeUiState(),
                    onToggleApp = { _, _ -> error("Unavailable rule must not toggle") },
                    onOpenAccessibilitySettings = {},
                )
            }
        }

        composeRule.onNodeWithText("FlashSkip").assertIsDisplayed()
        composeRule.onAllNodesWithText("采集规则").assertCountEquals(1)
    }

    @Test
    fun enabledRuleShowsDeferredStateWheneverServiceIsUnavailable() {
        var status by mutableStateOf(ServiceStatus.NOT_ENABLED)
        composeRule.setContent {
            FlashSkipTheme {
                TargetAppRow(
                    state = targetAppUiState(
                        target = TargetApp(
                            id = TargetAppId("com.example.music"),
                            displayName = "示例音乐",
                            packageName = "com.example.music",
                            ruleAvailability = RuleAvailability.Available(1),
                        ),
                        enabled = true,
                        serviceStatus = status,
                    ),
                    compactIcon = false,
                    onToggle = {},
                )
            }
        }

        fun assertDeferredState() {
            composeRule.onNodeWithText("已启用 · 服务开启后生效").assertIsDisplayed()
            composeRule
                .onNodeWithContentDescription("示例音乐自动关闭开关")
                .assertIsDisplayed()
        }

        assertDeferredState()
        composeRule.runOnIdle { status = ServiceStatus.CONFIRMING }
        assertDeferredState()
        composeRule.runOnIdle { status = ServiceStatus.ABNORMAL }
        assertDeferredState()
    }

    @Test
    fun capturedStateExposesCompletionActionSeparateFromSwitch() {
        composeRule.setContent {
            FlashSkipTheme {
                CaptureControls(
                    state = CaptureUiState(phase = CapturePhase.CAPTURED),
                    targetAppName = "示例音乐",
                    onStartCapture = {},
                    onFinishCapture = {},
                    onCancelCapture = {},
                )
            }
        }

        composeRule.onNodeWithText("采集完成").assertIsEnabled()
    }

    @Test
    fun captureControlsRemainReachableAtTwoHundredPercentFontScale() {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f),
            ) {
                FlashSkipTheme {
                    CaptureControls(
                        state = CaptureUiState(canStart = true),
                        targetAppName = null,
                        onStartCapture = {},
                        onFinishCapture = {},
                        onCancelCapture = {},
                    )
                }
            }
        }

        composeRule.onNodeWithText("采集规则").assertIsDisplayed().assertIsEnabled()
    }

    @Test
    fun candidateRuleCardShowsAccumulatedCaptureHistory() {
        composeRule.setContent {
            FlashSkipTheme {
                TargetAppRow(
                    state = targetAppUiState(
                        target = TargetApp(
                            id = TargetAppId("com.example.music"),
                            displayName = "示例音乐",
                            packageName = "com.example.music",
                            ruleAvailability = RuleAvailability.Unavailable,
                        ),
                        enabled = false,
                        serviceStatus = ServiceStatus.RUNNING,
                        ruleCard = RuleCardUiState(
                            status = RuleCardStatus.CANDIDATE,
                            sampleCount = 1,
                            ruleCount = 0,
                            stableSampleCount = 1,
                            uniqueEvidenceCount = 0,
                            selector = CapturedSelector(resourceId = "com.example.music:id/close"),
                        ),
                    ),
                    compactIcon = false,
                    onToggle = {},
                )
            }
        }

        composeRule.onNodeWithText("候选规则 · 1/1").assertIsDisplayed()
        composeRule.onNodeWithText("成功采集 1 次 · 累计规则 0 条 · 唯一性记录 0").assertIsDisplayed()
    }
}
