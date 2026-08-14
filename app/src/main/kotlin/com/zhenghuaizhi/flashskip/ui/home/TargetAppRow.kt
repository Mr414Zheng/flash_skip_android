package com.zhenghuaizhi.flashskip.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.zhenghuaizhi.flashskip.domain.model.CaptureFailure
import com.zhenghuaizhi.flashskip.domain.model.CapturePhase
import com.zhenghuaizhi.flashskip.domain.model.CapturedSelector
import com.zhenghuaizhi.flashskip.domain.model.RuleAvailability
import com.zhenghuaizhi.flashskip.domain.model.RuleCardStatus
import com.zhenghuaizhi.flashskip.domain.rulecapture.RuleCandidateAnalyzer
import com.zhenghuaizhi.flashskip.ui.theme.FlashSkipTokens

@Composable
internal fun TargetAppRow(
    state: TargetAppUiState,
    compactIcon: Boolean,
    onToggle: (Boolean) -> Unit,
    onClearCapturedRules: () -> Unit = {},
) {
    val ruleCount = (state.target.ruleAvailability as? RuleAvailability.Available)?.ruleCount
    val switchEnabled = ruleCount != null
    val checked = state.enabled && switchEnabled
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = FlashSkipTokens.Space16),
        verticalArrangement = Arrangement.spacedBy(FlashSkipTokens.Space12),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier
                    .size(if (compactIcon) FlashSkipTokens.AppIconCompact else FlashSkipTokens.AppIcon)
                    .clip(RoundedCornerShape(12.dp)),
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = state.target.displayName.take(1).uppercase(),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            Spacer(Modifier.width(FlashSkipTokens.Space16))
            Column(Modifier.weight(1f)) {
                Text(
                    text = state.target.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(FlashSkipTokens.Space4))
                Text(
                    text = automaticStatus(state, ruleCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(FlashSkipTokens.Space12))
            Switch(
                checked = checked,
                onCheckedChange = if (switchEnabled) onToggle else null,
                enabled = switchEnabled,
                modifier = Modifier.semantics {
                    contentDescription = "${state.target.displayName}自动关闭开关"
                    stateDescription = if (!switchEnabled) "规则不可用" else if (checked) "已开启" else "已关闭"
                },
                colors = SwitchDefaults.colors(
                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                    disabledCheckedTrackColor = MaterialTheme.colorScheme.outlineVariant,
                ),
            )
        }

        state.ruleCard?.let { card ->
            RuleCard(
                appName = state.target.displayName,
                card = card,
                onClear = onClearCapturedRules,
            )
        }
    }
}

@Composable
internal fun CaptureControls(
    state: CaptureUiState,
    targetAppName: String?,
    onStartCapture: () -> Unit,
    onFinishCapture: () -> Unit,
    onCancelCapture: () -> Unit,
) {
    val phase = state.phase
    val explanation = when (phase) {
        CapturePhase.CONFIGURING -> "正在暂停自动关闭并准备点击监听…"
        CapturePhase.WAITING_FOR_TARGET -> "请从 Home 手动启动任意应用，广告出现后只点击一次关闭按钮。"
        CapturePhase.OBSERVING_CLICK -> "正在等待首次点击；请只点击广告关闭按钮。"
        CapturePhase.AWAITING_CONFIRMATION ->
            "已记住${targetAppName ?: "目标应用"}弹窗中的唯一关闭按钮。" +
                "确认你刚才已手动关闭广告后，再保存规则。"
        CapturePhase.CAPTURED -> "已采集${targetAppName ?: "目标应用"}的点击信息，请确认完成。"
        CapturePhase.FAILED -> failureMessage(state.failure)
        null -> when {
            !state.canStart -> "开启并保持 FlashSkip 无障碍服务运行后可采集。"
            else -> "无需预先添加应用；从 Home 启动任意应用并采集一次关闭按钮。"
        }
    }
    Text(
        text = explanation,
        style = MaterialTheme.typography.bodySmall,
        color = if (phase == CapturePhase.FAILED) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
    if (phase == CapturePhase.FAILED &&
        (state.failureDiagnostics != null || state.targetWindowDiagnostics != null)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(12.dp),
        ) {
            Column(
                modifier = Modifier.padding(FlashSkipTokens.Space12),
                verticalArrangement = Arrangement.spacedBy(FlashSkipTokens.Space4),
            ) {
                Text(
                    text = "本地诊断",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                state.targetWindowDiagnostics?.let { diagnostics ->
                    Text(
                        text = "目标窗口\n${formatTargetWindowDiagnostics(diagnostics)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                state.failureDiagnostics?.let { diagnostics ->
                    val isClickTimeout = state.failure == CaptureFailure.CLICK_TIMEOUT
                    Text(
                        text = if (isClickTimeout) {
                            "关闭按钮探测\n${formatCaptureTimeoutDiagnostics(diagnostics)}"
                        } else {
                            "点击恢复\n${formatCaptureFailureDiagnostics(diagnostics)}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(FlashSkipTokens.Space8),
    ) {
        when (phase) {
            CapturePhase.CONFIGURING,
            CapturePhase.WAITING_FOR_TARGET,
            CapturePhase.OBSERVING_CLICK,
            -> {
                OutlinedButton(
                    onClick = onCancelCapture,
                    modifier = Modifier.heightIn(min = FlashSkipTokens.TouchMin),
                ) {
                    Text("取消采集")
                }
            }

            CapturePhase.CAPTURED -> {
                Button(
                    onClick = onFinishCapture,
                    modifier = Modifier.heightIn(min = FlashSkipTokens.TouchMin),
                ) {
                    Text("采集完成")
                }
                TextButton(
                    onClick = onCancelCapture,
                    modifier = Modifier.heightIn(min = FlashSkipTokens.TouchMin),
                ) {
                    Text("放弃本次")
                }
            }

            CapturePhase.AWAITING_CONFIRMATION -> {
                Button(
                    onClick = onFinishCapture,
                    modifier = Modifier.heightIn(min = FlashSkipTokens.TouchMin),
                ) {
                    Text("确认已手动关闭")
                }
                TextButton(
                    onClick = onCancelCapture,
                    modifier = Modifier.heightIn(min = FlashSkipTokens.TouchMin),
                ) {
                    Text("不是这个按钮")
                }
            }

            CapturePhase.FAILED, null -> {
                OutlinedButton(
                    onClick = onStartCapture,
                    enabled = state.canStart,
                    modifier = Modifier.heightIn(min = FlashSkipTokens.TouchMin),
                ) {
                    Text("采集规则")
                }
                if (phase == CapturePhase.FAILED) {
                    TextButton(
                        onClick = onCancelCapture,
                        modifier = Modifier.heightIn(min = FlashSkipTokens.TouchMin),
                    ) {
                        Text("关闭提示")
                    }
                }
            }
        }
    }
}

@Composable
private fun RuleCard(
    appName: String,
    card: RuleCardUiState,
    onClear: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "$appName 本机规则卡" },
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier.padding(FlashSkipTokens.Space16),
            verticalArrangement = Arrangement.spacedBy(FlashSkipTokens.Space8),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = when (card.status) {
                        RuleCardStatus.CANDIDATE ->
                            "候选规则 · ${card.stableSampleCount}/${RuleCandidateAnalyzer.REQUIRED_STABLE_SAMPLES}"
                        RuleCardStatus.READY -> "本机规则可用"
                    },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    color = when (card.status) {
                        RuleCardStatus.READY -> MaterialTheme.colorScheme.primary
                        RuleCardStatus.CANDIDATE -> MaterialTheme.colorScheme.onSurface
                    },
                )
                TextButton(onClick = onClear) { Text("清除") }
            }
            Text(
                text = "成功采集 ${card.sampleCount} 次 · 累计规则 ${card.ruleCount} 条 · " +
                    "唯一性记录 ${card.uniqueEvidenceCount}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (card.activities.isNotEmpty()) {
                Text(
                    text = "Activity ${card.activities.sorted().joinToString()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            card.selector?.let { selector ->
                Text(
                    text = selectorSummary(selector),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (card.status == RuleCardStatus.READY) {
                Text(
                    text = "规则开关保持关闭，请确认后手动开启。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun automaticStatus(state: TargetAppUiState, ruleCount: Int?): String = when {
    state.status == TargetAppStatus.RULE_UNAVAILABLE -> "自动关闭不可用 · 请先采集规则"
    state.status == TargetAppStatus.ENABLED_BUT_SERVICE_UNAVAILABLE -> "已启用 · 服务开启后生效"
    state.status == TargetAppStatus.ENABLED_AND_RUNNING -> "已启用 · $ruleCount 条规则"
    else -> "自动关闭已停用 · $ruleCount 条规则可用"
}

private fun failureMessage(failure: CaptureFailure?): String = when (failure) {
    CaptureFailure.SERVICE_UNAVAILABLE -> "采集失败：无障碍服务已断开，请检查后重试。"
    CaptureFailure.CONFIGURATION_TIMEOUT -> "采集失败：监听配置超时。"
    CaptureFailure.TARGET_TIMEOUT -> "采集失败：90 秒内未进入目标应用。"
    CaptureFailure.CLICK_TIMEOUT -> "采集失败：进入目标应用后 15 秒内没有点击。"
    CaptureFailure.COMPLETION_TIMEOUT -> "采集失败：10 分钟内未确认采集完成。"
    CaptureFailure.ACTIVITY_UNAVAILABLE -> "采集失败：无法验证当前 Activity。"
    CaptureFailure.CLICK_SOURCE_UNAVAILABLE -> "采集失败：系统未提供点击节点，且未找到唯一关闭节点。"
    CaptureFailure.CLICK_SEMANTICS_UNAVAILABLE -> "该广告暂不支持：关闭节点没有可用语义。"
    null -> "采集失败，请重试。"
}

private fun selectorSummary(selector: CapturedSelector): String = buildList {
    selector.resourceId?.let { add("ID $it") }
    selector.text?.let { add("文本 $it") }
    selector.contentDescription?.let { add("描述 $it") }
    selector.ancestorResourceId?.let {
        add("祖先ID $it（${selector.ancestorDistance}层）")
    }
}.joinToString(" · ")
