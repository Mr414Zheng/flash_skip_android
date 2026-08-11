package com.zhenghuaizhi.flashskip.ui.home

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zhenghuaizhi.flashskip.domain.model.RuleAvailability
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
    fun unavailableProductionRulesExposeThreeDisabledSingleNodes() {
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
                .onNodeWithContentDescription("$appName，规则当前不可用")
                .assertIsNotEnabled()
        }
        composeRule.onAllNodes(
            hasContentDescription("QQ音乐，规则当前不可用") or
                hasContentDescription("淘宝，规则当前不可用") or
                hasContentDescription("京东，规则当前不可用"),
        ).assertCountEquals(3)
        composeRule.onAllNodes(hasClickAction()).assertCountEquals(1)
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
                .onNodeWithContentDescription("QQ音乐，已启用，服务开启后生效")
                .assertIsDisplayed()
        }

        assertDeferredState()
        composeRule.runOnIdle { status = ServiceStatus.CONFIRMING }
        assertDeferredState()
        composeRule.runOnIdle { status = ServiceStatus.ABNORMAL }
        assertDeferredState()
    }
}
