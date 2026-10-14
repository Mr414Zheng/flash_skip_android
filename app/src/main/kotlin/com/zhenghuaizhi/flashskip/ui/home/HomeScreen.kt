package com.zhenghuaizhi.flashskip.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhenghuaizhi.flashskip.R
import com.zhenghuaizhi.flashskip.domain.model.TargetAppId
import com.zhenghuaizhi.flashskip.platform.settings.NavigationLaunchResult
import com.zhenghuaizhi.flashskip.ui.theme.FlashSkipTokens

@Composable
fun FlashSkipRoute(
    viewModel: HomeViewModel,
    onOpenAccessibilitySettings: () -> NavigationLaunchResult,
    onGoHome: () -> NavigationLaunchResult,
) {
    val state = viewModel.uiState.collectAsStateWithLifecycle().value
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(viewModel, onGoHome, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    is HomeViewModel.HomeEffect.GoHome -> {
                        if (onGoHome() == NavigationLaunchResult.UNAVAILABLE) {
                            viewModel.reportGoHomeFailure(effect.sessionId)
                        }
                    }
                }
            }
        }
    }
    FlashSkipScreen(
        state = state,
        onToggleApp = viewModel::setAppEnabled,
        onOpenAccessibilitySettings = {
            if (onOpenAccessibilitySettings() == NavigationLaunchResult.UNAVAILABLE) {
                viewModel.reportSettingsLaunchFailure()
            }
        },
        onStartCapture = viewModel::startCapture,
        onFinishCapture = viewModel::finishCapture,
        onCancelCapture = viewModel::cancelCapture,
        onClearCapturedRules = viewModel::clearCapturedRules,
        onClearAllCapturedRules = viewModel::clearAllCapturedRules,
        onRetry = viewModel::retryLastOperation,
    )
}

@Composable
fun FlashSkipScreen(
    state: HomeUiState,
    onToggleApp: (TargetAppId, Boolean) -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    modifier: Modifier = Modifier,
    onStartCapture: () -> Unit = {},
    onFinishCapture: () -> Unit = {},
    onCancelCapture: () -> Unit = {},
    onClearCapturedRules: (TargetAppId) -> Unit = {},
    onClearAllCapturedRules: () -> Unit = {},
    onRetry: () -> Unit = {},
) {
    var showExplanation by rememberSaveable { mutableStateOf(false) }
    var deletePackage by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteAll by rememberSaveable { mutableStateOf(false) }
    var deleteSubmitted by rememberSaveable { mutableStateOf(false) }
    val deletionTarget = state.targetApps.firstOrNull { it.id.packageName == deletePackage }
    LaunchedEffect(state.targetApps) {
        if (deletePackage != null && deletionTarget == null) deletePackage = null
        if (state.targetApps.isEmpty()) deleteAll = false
    }
    BoxWithConstraints(
        modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.TopCenter,
    ) {
        val horizontalPadding = if (maxWidth < 360.dp) 16.dp else 24.dp
        LazyColumn(
            modifier = Modifier.widthIn(max = FlashSkipTokens.MaxContentWidth).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = horizontalPadding, vertical = 24.dp),
        ) {
            item {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(24.dp))
                ServiceStatusBlock(state, onOpenAccessibilitySettings)
                Spacer(Modifier.height(32.dp))
                Text(stringResource(R.string.applications), style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(16.dp))
                CaptureControls(state.capture, { showExplanation = true }, onFinishCapture, onCancelCapture)
                OperationFeedback(state, onRetry)
                Spacer(Modifier.height(16.dp))
                if (state.targetApps.isEmpty()) {
                    Text(stringResource(R.string.empty_applications), style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.empty_applications_hint), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            state.targetApps.forEachIndexed { index, app ->
                item(key = app.id.packageName) {
                    TargetAppRow(
                        state = app,
                        actionsEnabled = !state.operationInProgress && state.ruleActionsEnabled,
                        onToggle = { onToggleApp(app.id, it) },
                        onDelete = { deleteSubmitted = false; deletePackage = app.id.packageName },
                    )
                    if (index < state.targetApps.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
            item {
                Spacer(Modifier.height(24.dp))
                if (state.targetApps.isNotEmpty()) {
                    TextButton(onClick = { deleteSubmitted = false; deleteAll = true }, enabled = !state.operationInProgress && state.ruleActionsEnabled,
                        modifier = Modifier.heightIn(min = FlashSkipTokens.TouchMin)) {
                        Text(stringResource(R.string.delete_all_applications), color = MaterialTheme.colorScheme.error)
                    }
                    Spacer(Modifier.height(16.dp))
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.privacy), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (showExplanation) {
        ConfirmationDialog(
            title = stringResource(R.string.add_application),
            description = stringResource(R.string.add_explanation),
            confirmLabel = stringResource(R.string.start_adding),
            enabled = state.capture.canStart,
            onConfirm = { showExplanation = false; onStartCapture() },
            onDismiss = { showExplanation = false },
        )
    }
    if (deletionTarget != null || deleteAll) {
        ConfirmationDialog(
            title = if (deleteAll) stringResource(R.string.delete_all_title)
                else stringResource(R.string.delete_title, requireNotNull(deletionTarget).displayName),
            description = stringResource(if (deleteAll) R.string.delete_all_description else R.string.delete_description),
            confirmLabel = stringResource(if (deleteAll) R.string.delete_all_confirm else R.string.delete),
            enabled = !state.operationInProgress && state.ruleActionsEnabled,
            destructive = true,
            dismissEnabled = !state.operationInProgress,
            feedback = if (deleteSubmitted) state.operationFeedback else null,
            canRetry = deleteSubmitted && state.canRetry,
            onRetry = onRetry,
            onConfirm = {
                deleteSubmitted = true
                if (deleteAll) onClearAllCapturedRules()
                else deletionTarget?.let { onClearCapturedRules(it.id) }
            },
            onDismiss = {
                if (!state.operationInProgress) { deletePackage = null; deleteAll = false }
            },
        )
    }
}

@Composable
private fun OperationFeedback(state: HomeUiState, onRetry: () -> Unit) {
    if (!state.operationInProgress && state.operationFeedback == null) return
    Spacer(Modifier.height(8.dp))
    val feedback = state.operationFeedback
    Text(
        text = if (feedback != null) stringResource(feedback.resource()) else stringResource(R.string.processing),
        style = MaterialTheme.typography.bodyMedium,
        color = if (feedback != null && feedback != HomeFeedback.SAVED && feedback != HomeFeedback.SAVED_NOT_READY) {
            MaterialTheme.colorScheme.error
        } else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
    if (state.canRetry) {
        TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = FlashSkipTokens.TouchMin)) {
            Text(stringResource(R.string.retry))
        }
    }
}

@Composable
private fun ConfirmationDialog(
    title: String,
    description: String,
    confirmLabel: String,
    enabled: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
    dismissEnabled: Boolean = true,
    feedback: HomeFeedback? = null,
    canRetry: Boolean = false,
    onRetry: () -> Unit = {},
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(description, style = MaterialTheme.typography.bodyLarge)
                if (feedback != null && feedback != HomeFeedback.SAVED && feedback != HomeFeedback.SAVED_NOT_READY) {
                    Text(stringResource(feedback.resource()), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
            }
        },
        confirmButton = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                if (canRetry) {
                    TextButton(onClick = onRetry, enabled = enabled, modifier = Modifier.heightIn(min = FlashSkipTokens.TouchMin)) {
                        Text(stringResource(R.string.retry))
                    }
                }
                TextButton(onClick = onConfirm, enabled = enabled, modifier = Modifier.heightIn(min = FlashSkipTokens.TouchMin)) {
                    Text(confirmLabel, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                }
                TextButton(onClick = onDismiss, enabled = dismissEnabled, modifier = Modifier.heightIn(min = FlashSkipTokens.TouchMin)) {
                    Text(stringResource(R.string.cancel))
                }
            }
        },
    )
}
