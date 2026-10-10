package com.zhenghuaizhi.flashskip.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.zhenghuaizhi.flashskip.R
import com.zhenghuaizhi.flashskip.ui.theme.FlashSkipTokens

@Composable
internal fun TargetAppRow(
    state: TargetAppUiState,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
    actionsEnabled: Boolean,
) {
    val status = stringResource(when (state.status) {
        TargetAppStatus.RULE_UNAVAILABLE -> R.string.app_needs_adding
        TargetAppStatus.DISABLED -> R.string.app_disabled
        TargetAppStatus.ENABLED_AND_RUNNING -> R.string.app_enabled
        TargetAppStatus.ENABLED_BUT_SERVICE_UNAVAILABLE -> R.string.app_service_unavailable
    })
    val deleteLabel = stringResource(R.string.delete)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val nameWidth = measurer.measure(AnnotatedString(state.displayName), MaterialTheme.typography.titleMedium).size.width
    val statusWidth = measurer.measure(AnnotatedString(status), MaterialTheme.typography.bodyMedium).size.width
    val deleteWidth = measurer.measure(AnnotatedString(deleteLabel), MaterialTheme.typography.labelLarge).size.width
    BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
        // 名称或系统字号需要更多空间时，把控件放到下一行，保留正文完整宽度。
        val controlsWidth = with(density) { 52.dp.toPx() + 8.dp.toPx() + maxOf(48.dp.toPx(), deleteWidth + 24.dp.toPx()) }
        val textWidth = with(density) { maxWidth.toPx() - controlsWidth - 8.dp.toPx() }
        val stacked = maxOf(nameWidth, statusWidth) > textWidth
        if (stacked) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AppDescription(state.displayName, status)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    AppActions(state, onToggle, onDelete, actionsEnabled)
                }
            }
        } else {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                AppDescription(state.displayName, status, Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                AppActions(state, onToggle, onDelete, actionsEnabled)
            }
        }
    }
}

@Composable
private fun AppDescription(name: String, status: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(4.dp))
        Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AppActions(state: TargetAppUiState, onToggle: (Boolean) -> Unit, onDelete: () -> Unit, actionsEnabled: Boolean) {
    val switchLabel = stringResource(R.string.switch_label, state.displayName)
    val switchState = stringResource(when {
        state.status == TargetAppStatus.RULE_UNAVAILABLE -> R.string.app_needs_adding
        state.checked -> R.string.switch_enabled
        else -> R.string.switch_disabled
    })
    val deleteLabel = stringResource(R.string.delete_label, state.displayName)
    Switch(
        checked = state.checked,
        onCheckedChange = onToggle,
        enabled = actionsEnabled && (state.checked || state.canEnable),
        modifier = Modifier.sizeIn(minWidth = FlashSkipTokens.TouchMin, minHeight = FlashSkipTokens.TouchMin).semantics {
            contentDescription = switchLabel
            stateDescription = switchState
        },
    )
    Spacer(Modifier.width(8.dp))
    TextButton(onClick = onDelete, enabled = actionsEnabled,
        modifier = Modifier.sizeIn(minWidth = FlashSkipTokens.TouchMin, minHeight = FlashSkipTokens.TouchMin).semantics {
            contentDescription = deleteLabel
        }) { Text(stringResource(R.string.delete)) }
}
