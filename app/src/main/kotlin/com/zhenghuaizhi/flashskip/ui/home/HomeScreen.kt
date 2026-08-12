package com.zhenghuaizhi.flashskip.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhenghuaizhi.flashskip.ui.theme.FlashSkipTokens

@Composable
fun FlashSkipRoute(
    viewModel: HomeViewModel,
    onOpenAccessibilitySettings: () -> Unit,
    onGoHome: () -> Unit,
) {
    val state = viewModel.uiState.collectAsStateWithLifecycle().value
    LaunchedEffect(viewModel, onGoHome) {
        viewModel.effects.collect { effect ->
            when (effect) {
                HomeViewModel.HomeEffect.GoHome -> onGoHome()
            }
        }
    }
    FlashSkipScreen(
        state = state,
        onToggleApp = viewModel::setAppEnabled,
        onOpenAccessibilitySettings = onOpenAccessibilitySettings,
        onStartCapture = viewModel::startCapture,
        onFinishCapture = viewModel::finishCapture,
        onCancelCapture = viewModel::cancelCapture,
        onClearCapturedRules = viewModel::clearCapturedRules,
        onClearAllCapturedRules = viewModel::clearAllCapturedRules,
    )
}

@Composable
fun FlashSkipScreen(
    state: HomeUiState,
    onToggleApp: (com.zhenghuaizhi.flashskip.domain.model.TargetAppId, Boolean) -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    modifier: Modifier = Modifier,
    onStartCapture: () -> Unit = {},
    onFinishCapture: () -> Unit = {},
    onCancelCapture: () -> Unit = {},
    onClearCapturedRules: (com.zhenghuaizhi.flashskip.domain.model.TargetAppId) -> Unit = {},
    onClearAllCapturedRules: () -> Unit = {},
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.TopCenter,
    ) {
        val horizontalPadding = if (maxWidth < 360.dp) {
            FlashSkipTokens.Space16
        } else {
            FlashSkipTokens.Space24
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = FlashSkipTokens.MaxContentWidth),
            contentPadding = PaddingValues(
                horizontal = horizontalPadding,
                vertical = FlashSkipTokens.Space24,
            ),
        ) {
            item {
                Text(
                    text = "FlashSkip",
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(FlashSkipTokens.Space28))
                ServiceStatusBlock(
                    state = state,
                    onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                )
                Spacer(Modifier.height(FlashSkipTokens.Space40))
                SectionHeader("目标应用")
                Spacer(Modifier.height(FlashSkipTokens.Space20))
                CaptureControls(
                    state = state.capture,
                    targetAppName = state.capturingAppName,
                    onStartCapture = onStartCapture,
                    onFinishCapture = onFinishCapture,
                    onCancelCapture = onCancelCapture,
                )
                Spacer(Modifier.height(FlashSkipTokens.Space20))
                if (state.targetApps.isEmpty()) {
                    Text(
                        text = "尚无采集规则。成功采集后，应用会自动出现在这里。",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            state.targetApps.forEachIndexed { index, app ->
                item(key = app.target.id.packageName) {
                    TargetAppRow(
                        state = app,
                        compactIcon = maxWidth < 360.dp,
                        onToggle = { onToggleApp(app.target.id, it) },
                        onClearCapturedRules = { onClearCapturedRules(app.target.id) },
                    )
                    if (index < state.targetApps.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }

            item {
                Spacer(Modifier.height(FlashSkipTokens.Space32))
                SectionHeader("最近执行")
                Spacer(Modifier.height(FlashSkipTokens.Space20))
                if (state.records.isEmpty()) {
                    EmptyExecutionState()
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                        state.records.take(20).forEachIndexed { index, record ->
                            ExecutionItem(
                                record = record,
                                nowEpochMs = state.currentTimeEpochMs,
                                drawRail = index < state.records.take(20).lastIndex,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(FlashSkipTokens.Space28))
                PrivacyFooter(
                    hasCapturedRules = state.hasCapturedRules,
                    onClearAllCapturedRules = onClearAllCapturedRules,
                )
            }
        }
    }
}
