package com.zhenghuaizhi.flashskip.ui.home

import com.zhenghuaizhi.flashskip.domain.model.ActionMethod
import com.zhenghuaizhi.flashskip.domain.model.ActionOutcome
import com.zhenghuaizhi.flashskip.domain.model.ActionReason
import com.zhenghuaizhi.flashskip.domain.model.ActionRecord
import com.zhenghuaizhi.flashskip.domain.model.TargetAppId
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal fun ActionRecord.title(): String = when (outcome) {
    ActionOutcome.CLOSED -> "已关闭"
    ActionOutcome.ABANDONED -> "已放弃"
    ActionOutcome.FAILED -> "执行失败"
}

internal fun ActionRecord.detail(nowEpochMs: Long): String {
    val action = when (method) {
        ActionMethod.NODE_CLICK -> "节点点击"
        ActionMethod.CENTER_GESTURE -> "中心手势"
        null -> reasonLabel(reason)
    }
    return buildList {
        add(action)
        timing?.let { timing ->
            timing.targetToFirstQueryMs?.let { add("查询 ${formatDuration(it)}") }
            timing.targetToFirstMatchMs?.let { add("匹配 ${formatDuration(it)}") }
            timing.targetToActionMs?.let { add("点击 ${formatDuration(it)}") }
            add("确认 ${formatDuration(timing.targetToCompletedMs)}")
        }
        add(formatTime(timestampEpochMs, nowEpochMs))
        add(ruleId)
    }.joinToString(" · ")
}

internal fun TargetAppId.displayName(): String = when (this) {
    TargetAppId.QQ_MUSIC -> "QQ音乐"
    TargetAppId.TAOBAO -> "淘宝"
    TargetAppId.JD -> "京东"
}

private fun reasonLabel(reason: ActionReason?): String = when (reason) {
    ActionReason.AMBIGUOUS_MATCH -> "匹配存在歧义"
    ActionReason.NODE_DISAPPEARED -> "节点已失效"
    ActionReason.PAGE_CHANGED -> "页面已切换"
    ActionReason.QUERY_STALE -> "查询已过期"
    ActionReason.TRAVERSAL_LIMIT -> "页面结构超出安全范围"
    ActionReason.CLICK_UNCONFIRMED -> "点击后广告仍存在"
    ActionReason.GESTURE_REJECTED -> "手势未被接受"
    ActionReason.GESTURE_CANCELLED -> "手势已取消"
    null -> "未执行"
}

internal fun formatTime(timestampEpochMs: Long, nowEpochMs: Long): String {
    val zone = ZoneId.systemDefault()
    val timestamp = Instant.ofEpochMilli(timestampEpochMs).atZone(zone)
    val today = Instant.ofEpochMilli(nowEpochMs).atZone(zone).toLocalDate()
    val prefix = when (timestamp.toLocalDate()) {
        today -> "今天"
        today.minusDays(1) -> "昨天"
        else -> timestamp.format(DateTimeFormatter.ofPattern("MM-dd"))
    }
    return "$prefix ${timestamp.format(DateTimeFormatter.ofPattern("HH:mm"))}"
}

private fun formatDuration(durationMs: Long): String = when {
    durationMs < 1_000 -> "${durationMs}毫秒"
    else -> String.format(Locale.ROOT, "%.3f秒", durationMs / 1_000.0)
}
