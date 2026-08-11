package com.zhenghuaizhi.flashskip.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zhenghuaizhi.flashskip.domain.model.ActionOutcome
import com.zhenghuaizhi.flashskip.domain.model.ActionRecord
import com.zhenghuaizhi.flashskip.domain.model.ServiceStatus
import com.zhenghuaizhi.flashskip.ui.theme.FlashSkipTokens
import com.zhenghuaizhi.flashskip.ui.theme.SuccessIndicatorColor
import com.zhenghuaizhi.flashskip.ui.theme.TimelineNeutralColor
import com.zhenghuaizhi.flashskip.ui.theme.WarningColor

@Composable
internal fun SectionHeader(title: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(width = 4.dp, height = 20.dp)
                .clip(RoundedCornerShape(2.dp))
                .clearAndSetSemantics { }
                .background(MaterialTheme.colorScheme.primary),
        )
        Spacer(Modifier.width(FlashSkipTokens.Space12))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
internal fun ServiceStatusBlock(
    state: HomeUiState,
    onOpenAccessibilitySettings: () -> Unit,
) {
    val status = state.serviceStatus
    Column(verticalArrangement = Arrangement.spacedBy(FlashSkipTokens.Space8)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ServiceGlyph(status)
            Spacer(Modifier.width(FlashSkipTokens.Space12))
            Text(
                text = statusTitle(status),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Text(
            text = statusDescription(status, state.enabledAppCount),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when (status) {
            ServiceStatus.RUNNING -> Text(
                text = state.latestSuccess?.let { record ->
                    "最近成功：${formatTime(record.timestampEpochMs, state.currentTimeEpochMs)} · " +
                        record.targetAppId.displayName()
                } ?: "尚无成功记录",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            ServiceStatus.NOT_ENABLED,
            ServiceStatus.ABNORMAL,
            -> InlineAction(
                label = if (status == ServiceStatus.NOT_ENABLED) {
                    "前往系统设置"
                } else {
                    "检查系统设置"
                },
                onClick = onOpenAccessibilitySettings,
            )

            ServiceStatus.CONFIRMING -> Unit
        }
    }
}

@Composable
private fun ServiceGlyph(status: ServiceStatus) {
    when (status) {
        ServiceStatus.RUNNING -> Canvas(
            Modifier
                .size(12.dp)
                .clearAndSetSemantics { },
        ) { drawCircle(SuccessIndicatorColor) }

        ServiceStatus.CONFIRMING -> CircularProgressIndicator(
            modifier = Modifier
                .size(20.dp)
                .clearAndSetSemantics { },
            strokeWidth = 2.dp,
        )

        ServiceStatus.NOT_ENABLED,
        ServiceStatus.ABNORMAL,
        -> Box(
            modifier = Modifier
                .size(20.dp)
                .border(
                    2.dp,
                    if (status == ServiceStatus.NOT_ENABLED) WarningColor else MaterialTheme.colorScheme.error,
                    CircleShape,
                )
                .clearAndSetSemantics { },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "!",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = if (status == ServiceStatus.NOT_ENABLED) WarningColor else MaterialTheme.colorScheme.error,
            )
        }
    }
}

private fun statusTitle(status: ServiceStatus): String = when (status) {
    ServiceStatus.NOT_ENABLED -> "无障碍服务未开启"
    ServiceStatus.CONFIRMING -> "正在确认服务状态"
    ServiceStatus.RUNNING -> "服务运行正常"
    ServiceStatus.ABNORMAL -> "服务状态异常"
}

private fun statusDescription(status: ServiceStatus, enabledCount: Int): String = when (status) {
    ServiceStatus.NOT_ENABLED -> "开启后，仅在目标应用启动后的 10 秒内检查关闭节点"
    ServiceStatus.CONFIRMING -> "通常会在短时间内自动更新"
    ServiceStatus.RUNNING -> "已启用 $enabledCount 个目标应用 · 不确定时不会执行"
    ServiceStatus.ABNORMAL -> "系统显示已开启，但服务当前不可用"
}

@Composable
private fun InlineAction(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.heightIn(min = FlashSkipTokens.TouchMin),
        shape = RoundedCornerShape(8.dp),
        contentPadding = ButtonDefaults.ContentPadding,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
internal fun ExecutionItem(
    record: ActionRecord,
    nowEpochMs: Long,
    drawRail: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp),
    ) {
        TimelineMarker(record.outcome, drawRail)
        Spacer(Modifier.width(FlashSkipTokens.Space16))
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = FlashSkipTokens.Space12),
        ) {
            Text(
                text = "${record.targetAppId.displayName()} · ${record.title()}",
                style = MaterialTheme.typography.titleMedium,
                color = when (record.outcome) {
                    ActionOutcome.FAILED -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
            Spacer(Modifier.height(FlashSkipTokens.Space4))
            Text(
                text = record.detail(nowEpochMs),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (drawRail) {
                Spacer(Modifier.height(FlashSkipTokens.Space12))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
private fun TimelineMarker(outcome: ActionOutcome, drawRail: Boolean) {
    val nodeColor = when (outcome) {
        ActionOutcome.CLOSED -> MaterialTheme.colorScheme.primary
        ActionOutcome.ABANDONED -> TimelineNeutralColor
        ActionOutcome.FAILED -> MaterialTheme.colorScheme.error
    }
    val railColor = MaterialTheme.colorScheme.outlineVariant
    Canvas(
        modifier = Modifier
            .width(12.dp)
            .heightIn(min = 76.dp)
            .clearAndSetSemantics { },
    ) {
        val centerX = size.width / 2f
        val nodeY = 20.dp.toPx()
        if (drawRail) {
            drawLine(
                color = railColor,
                start = Offset(centerX, nodeY),
                end = Offset(centerX, size.height),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
        drawCircle(color = nodeColor, radius = 6.dp.toPx(), center = Offset(centerX, nodeY))
    }
}

@Composable
internal fun EmptyExecutionState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = FlashSkipTokens.Space28,
                top = FlashSkipTokens.Space16,
                bottom = FlashSkipTokens.Space16,
            ),
    ) {
        Text(
            text = "暂无执行记录",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(FlashSkipTokens.Space8))
        Text(
            text = "成功关闭、执行失败或匹配后放弃时会显示在这里。",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun PrivacyFooter(
    hasCapturedRules: Boolean = false,
    onClearAllCapturedRules: () -> Unit = {},
) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = FlashSkipTokens.Space24),
        verticalArrangement = Arrangement.spacedBy(FlashSkipTokens.Space8),
    ) {
        Row(
            modifier = Modifier.clearAndSetSemantics {
                contentDescription = "完全离线，不截图，仅在本机私有存储保存限长规则字段"
            },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ShieldGlyph(
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(FlashSkipTokens.Space12))
            Text(
                text = "完全离线 · 不截图 · 规则字段仅存本机",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (hasCapturedRules) {
            androidx.compose.material3.TextButton(
                onClick = onClearAllCapturedRules,
                modifier = Modifier.heightIn(min = FlashSkipTokens.TouchMin),
            ) {
                Text("清除全部采集数据")
            }
        }
    }
}

@Composable
private fun ShieldGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.clearAndSetSemantics { }) {
        val path = Path().apply {
            moveTo(size.width / 2f, 1.dp.toPx())
            lineTo(size.width - 3.dp.toPx(), 5.dp.toPx())
            lineTo(size.width - 4.dp.toPx(), size.height * 0.62f)
            quadraticTo(size.width / 2f, size.height - 1.dp.toPx(), 4.dp.toPx(), size.height * 0.62f)
            lineTo(3.dp.toPx(), 5.dp.toPx())
            close()
        }
        drawPath(path, color = color, style = Stroke(width = 1.8.dp.toPx()))
        drawLine(
            color = color,
            start = Offset(size.width * 0.34f, size.height * 0.5f),
            end = Offset(size.width * 0.47f, size.height * 0.63f),
            strokeWidth = 1.8.dp.toPx(),
            cap = StrokeCap.Round,
        )
        drawLine(
            color = color,
            start = Offset(size.width * 0.47f, size.height * 0.63f),
            end = Offset(size.width * 0.7f, size.height * 0.35f),
            strokeWidth = 1.8.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}
