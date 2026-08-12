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
import com.zhenghuaizhi.flashskip.domain.model.AppVersion
import com.zhenghuaizhi.flashskip.domain.model.CapturePhase
import com.zhenghuaizhi.flashskip.domain.model.CapturedSelector
import com.zhenghuaizhi.flashskip.domain.model.RuleCardStatus
import com.zhenghuaizhi.flashskip.domain.model.ServiceStatus
import com.zhenghuaizhi.flashskip.domain.model.TargetApp
import com.zhenghuaizhi.flashskip.domain.model.TargetAppId
import com.zhenghuaizhi.flashskip.domain.rules.BuiltinRuleCatalog
import com.zhenghuaizhi.flashskip.ui.theme.FlashSkipTheme
import org.junit.Rule
import org.junit.Test

class HomeScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun unavailableRulesExposeSeparateDisabledSwitchesAndCaptureButtons() {
        composeRule.setContent {
            FlashSkipTheme {
                FlashSkipScreen(
                    state = HomeUiState(
                        targetApps = BuiltinRuleCatalog().targets.map {
                            targetAppUiState(
                                target = it,
                                enabled = false,
                                serviceStatus = ServiceStatus.NOT_ENABLED,
                            )
                        },
                    ),
                    onToggleApp = { _, _ -> error("Unavailable rule must not toggle") },
                    onOpenAccessibilitySettings = {},
                )
            }
        }

        composeRule.onNodeWithText("FlashSkip").assertIsDisplayed()
        listOf("QQ音乐", "淘宝", "京东").forEach { appName ->
            composeRule
                .onNodeWithContentDescription("${appName}自动关闭开关")
                .assertIsNotEnabled()
        }
        composeRule.onAllNodes(
            hasContentDescription("QQ音乐自动关闭开关") or
                hasContentDescription("淘宝自动关闭开关") or
                hasContentDescription("京东自动关闭开关"),
        ).assertCountEquals(3)
        composeRule.onAllNodesWithText("采集规则").assertCountEquals(3)
    }

    @Test
    fun enabledRuleShowsDeferredStateWheneverServiceIsUnavailable() {
        var status by mutableStateOf(ServiceStatus.NOT_ENABLED)
        composeRule.setContent {
            FlashSkipTheme {
                TargetAppRow(
                    state = targetAppUiState(
                        target = TargetApp(
                            id = TargetAppId.QQ_MUSIC,
                            displayName = "QQ音乐",
                            packageName = "com.tencent.qqmusic",
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
                .onNodeWithContentDescription("QQ音乐自动关闭开关")
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
                TargetAppRow(
                    state = targetAppUiState(
                        target = TargetApp(
                            id = TargetAppId.QQ_MUSIC,
                            displayName = "QQ音乐",
                            packageName = "com.tencent.qqmusic",
                            ruleAvailability = RuleAvailability.Unavailable,
                        ),
                        enabled = false,
                        serviceStatus = ServiceStatus.RUNNING,
                        installedVersion = AppVersion("13.2", 42),
                        capture = CaptureUiState(
                            phase = CapturePhase.CAPTURED,
                            canStart = false,
                        ),
                    ),
                    compactIcon = false,
                    onToggle = {},
                    onFinishCapture = {},
                )
            }
        }

        composeRule.onNodeWithText("采集完成").assertIsEnabled()
        composeRule.onNodeWithContentDescription("QQ音乐自动关闭开关").assertIsNotEnabled()
    }

    @Test
    fun captureControlsRemainReachableAtTwoHundredPercentFontScale() {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f),
            ) {
                FlashSkipTheme {
                    TargetAppRow(
                        state = targetAppUiState(
                            target = TargetApp(
                                id = TargetAppId.QQ_MUSIC,
                                displayName = "QQ音乐",
                                packageName = "com.tencent.qqmusic",
                                ruleAvailability = RuleAvailability.Unavailable,
                            ),
                            enabled = false,
                            serviceStatus = ServiceStatus.RUNNING,
                            installedVersion = AppVersion("13.2", 42),
                            capture = CaptureUiState(canStart = true),
                        ),
                        compactIcon = true,
                        onToggle = {},
                        onStartCapture = {},
                    )
                }
            }
        }

        composeRule.onNodeWithText("采集规则").assertIsDisplayed().assertIsEnabled()
    }

    @Test
    fun candidateRuleCardShowsSingleSampleThreshold() {
        composeRule.setContent {
            FlashSkipTheme {
                TargetAppRow(
                    state = targetAppUiState(
                        target = TargetApp(
                            id = TargetAppId.QQ_MUSIC,
                            displayName = "QQ音乐",
                            packageName = "com.tencent.qqmusic",
                            ruleAvailability = RuleAvailability.Unavailable,
                        ),
                        enabled = false,
                        serviceStatus = ServiceStatus.RUNNING,
                        installedVersion = AppVersion("13.2", 42),
                        capture = CaptureUiState(canStart = true),
                        ruleCard = RuleCardUiState(
                            status = RuleCardStatus.CANDIDATE,
                            sampleCount = 1,
                            stableSampleCount = 1,
                            uniqueEvidenceCount = 0,
                            version = AppVersion("13.2", 42),
                            selector = CapturedSelector(resourceId = "com.tencent.qqmusic:id/close"),
                        ),
                    ),
                    compactIcon = false,
                    onToggle = {},
                )
            }
        }

        composeRule.onNodeWithText("候选规则 · 1/1").assertIsDisplayed()
        composeRule.onNodeWithText("有效样本 1/1 · 唯一性记录 0（非门槛）").assertIsDisplayed()
    }
}
