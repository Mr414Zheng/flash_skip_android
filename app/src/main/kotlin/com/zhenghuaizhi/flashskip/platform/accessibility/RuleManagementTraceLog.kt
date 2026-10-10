package com.zhenghuaizhi.flashskip.platform.accessibility

import android.util.Log
import com.zhenghuaizhi.flashskip.application.rulecapture.RuleManagementOperation
import com.zhenghuaizhi.flashskip.application.rulecapture.RuleManagementTraceEvent
import com.zhenghuaizhi.flashskip.application.rulecapture.RuleManagementTraceSink

internal class RuleManagementTraceLog(override val enabled: Boolean) : RuleManagementTraceSink {
    private var repeatedServiceEvent: RuleManagementTraceEvent? = null
    private var repeatCount = 0

    @Synchronized
    override fun record(event: RuleManagementTraceEvent) {
        if (!enabled) return
        // 磁盘持续失败时合并重复后台事件；发布、开关和删除仍各自记录结果。
        if (event.operation == RuleManagementOperation.SERVICE_UPDATE &&
            event == repeatedServiceEvent
        ) {
            repeatCount++
            if (repeatCount == MAX_REPEATS) flushRepeated()
            return
        }
        flushRepeated()
        repeatedServiceEvent = event.takeIf {
            it.operation == RuleManagementOperation.SERVICE_UPDATE
        }
        write(event)
    }

    private fun flushRepeated() {
        val event = repeatedServiceEvent
        if (event != null && repeatCount > 0) write(event, " repeatCount=$repeatCount")
        repeatCount = 0
    }

    private fun write(event: RuleManagementTraceEvent, suffix: String = "") {
        Log.d("FlashSkipCapture", "operation=${event.operation} outcome=${event.outcome}" +
            " sessionHash=${event.sessionHash} ruleCount=${event.ruleCount} durationMs=${event.durationMs}$suffix")
    }

    private companion object {
        const val MAX_REPEATS = 32
    }
}
