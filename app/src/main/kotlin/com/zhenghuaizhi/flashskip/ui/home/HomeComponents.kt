package com.zhenghuaizhi.flashskip.ui.home

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.zhenghuaizhi.flashskip.R
import com.zhenghuaizhi.flashskip.domain.model.CapturePhase
import com.zhenghuaizhi.flashskip.domain.model.ServiceStatus
import com.zhenghuaizhi.flashskip.ui.theme.FlashSkipTokens

@Composable
internal fun ServiceStatusBlock(state: HomeUiState, onOpenAccessibilitySettings: () -> Unit) {
    val status = state.serviceStatus
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(12.dp).clearAndSetSemantics { }.background(
                    color = if (status == ServiceStatus.ABNORMAL) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
                    shape = CircleShape,
                ),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                stringResource(status.titleResource()),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Text(
            stringResource(status.descriptionResource()),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (status == ServiceStatus.NOT_ENABLED || status == ServiceStatus.ABNORMAL) {
            Button(onClick = onOpenAccessibilitySettings, modifier = Modifier.heightIn(min = FlashSkipTokens.TouchMin)) {
                Text(stringResource(if (status == ServiceStatus.NOT_ENABLED) R.string.go_enable else R.string.check_settings))
            }
        }
    }
}

@Composable
internal fun CaptureControls(
    state: CaptureUiState,
    onShowExplanation: () -> Unit,
    onFinishCapture: () -> Unit,
    onCancelCapture: () -> Unit,
) {
    val phase = state.phase
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (phase != null) {
            val name = state.targetAppName ?: stringResource(R.string.this_application)
            val explanation = when (phase) {
                CapturePhase.CONFIGURING -> stringResource(R.string.capture_configuring)
                CapturePhase.WAITING_FOR_TARGET -> stringResource(R.string.capture_waiting_target)
                CapturePhase.OBSERVING_CLICK -> stringResource(R.string.capture_observing)
                CapturePhase.CAPTURED -> stringResource(R.string.capture_captured, name)
                CapturePhase.AWAITING_CONFIRMATION -> stringResource(R.string.capture_confirm, name)
                CapturePhase.FAILED -> stringResource(state.failure.resource())
            }
            Text(
                explanation,
                style = MaterialTheme.typography.bodyLarge,
                color = if (phase == CapturePhase.FAILED) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        when (phase) {
            null, CapturePhase.FAILED -> {
                Button(
                    onClick = onShowExplanation,
                    enabled = state.canStart,
                    modifier = Modifier.fillMaxWidth().heightIn(min = FlashSkipTokens.TouchMin),
                ) { Text(stringResource(if (phase == null) R.string.add_application else R.string.add_again)) }
                if (phase == CapturePhase.FAILED) {
                    TextButton(onClick = onCancelCapture, enabled = !state.operationInProgress,
                        modifier = Modifier.heightIn(min = FlashSkipTokens.TouchMin)) {
                        Text(stringResource(R.string.dismiss_feedback))
                    }
                }
            }
            CapturePhase.CONFIGURING, CapturePhase.WAITING_FOR_TARGET, CapturePhase.OBSERVING_CLICK -> {
                TextButton(onClick = onCancelCapture, enabled = !state.operationInProgress,
                    modifier = Modifier.heightIn(min = FlashSkipTokens.TouchMin)) {
                    Text(stringResource(R.string.cancel_adding))
                }
            }
            CapturePhase.CAPTURED, CapturePhase.AWAITING_CONFIRMATION -> {
                Button(onClick = onFinishCapture, enabled = !state.operationInProgress,
                    modifier = Modifier.fillMaxWidth().heightIn(min = FlashSkipTokens.TouchMin)) {
                    Text(stringResource(if (phase == CapturePhase.CAPTURED) R.string.save else R.string.confirm_and_save))
                }
                TextButton(onClick = onCancelCapture, enabled = !state.operationInProgress,
                    modifier = Modifier.heightIn(min = FlashSkipTokens.TouchMin)) {
                    Text(stringResource(if (phase == CapturePhase.CAPTURED) R.string.do_not_save else R.string.reject_adding))
                }
            }
        }
    }
}

@StringRes
private fun ServiceStatus.titleResource(): Int = when (this) {
    ServiceStatus.NOT_ENABLED -> R.string.status_not_enabled
    ServiceStatus.CONFIRMING -> R.string.status_confirming
    ServiceStatus.RUNNING -> R.string.status_running
    ServiceStatus.ABNORMAL -> R.string.status_abnormal
}

@StringRes
private fun ServiceStatus.descriptionResource(): Int = when (this) {
    ServiceStatus.NOT_ENABLED -> R.string.status_not_enabled_description
    ServiceStatus.CONFIRMING -> R.string.status_confirming_description
    ServiceStatus.RUNNING -> R.string.status_running_description
    ServiceStatus.ABNORMAL -> R.string.status_abnormal_description
}

@StringRes
private fun CaptureIssue?.resource(): Int = when (this) {
    CaptureIssue.SERVICE_UNAVAILABLE -> R.string.capture_failure_service
    CaptureIssue.CONFIGURATION_TIMEOUT -> R.string.capture_failure_configuring
    CaptureIssue.TARGET_TIMEOUT -> R.string.capture_failure_target
    CaptureIssue.CLICK_NOT_CAPTURED -> R.string.capture_failure_click
    CaptureIssue.COMPLETION_TIMEOUT -> R.string.capture_failure_completion
    CaptureIssue.RETURNED_BEFORE_TARGET -> R.string.capture_failure_returned
    CaptureIssue.APP_UNAVAILABLE -> R.string.capture_failure_app
    CaptureIssue.AMBIGUOUS -> R.string.capture_failure_ambiguous
    CaptureIssue.STORAGE -> R.string.capture_failure_storage
    CaptureIssue.UNKNOWN, null -> R.string.capture_failure_unknown
}

@StringRes
internal fun HomeFeedback.resource(): Int = when (this) {
    HomeFeedback.STORAGE -> R.string.feedback_storage
    HomeFeedback.CAPTURE_IN_PROGRESS -> R.string.feedback_capture_in_progress
    HomeFeedback.RULE_NOT_READY -> R.string.feedback_rule_not_ready
    HomeFeedback.RULES_UPDATING -> R.string.feedback_rules_updating
    HomeFeedback.STALE -> R.string.feedback_stale
    HomeFeedback.RETURN_STORAGE -> R.string.feedback_return_storage
    HomeFeedback.DEPARTURE_STORAGE -> R.string.feedback_departure_storage
    HomeFeedback.GO_HOME_FAILED -> R.string.feedback_go_home_failed
    HomeFeedback.SETTINGS_FAILED -> R.string.feedback_settings_failed
    HomeFeedback.SAVED -> R.string.feedback_saved
    HomeFeedback.SAVED_NOT_READY -> R.string.feedback_saved_not_ready
}
