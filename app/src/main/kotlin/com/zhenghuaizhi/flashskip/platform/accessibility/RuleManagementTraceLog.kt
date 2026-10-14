package com.zhenghuaizhi.flashskip.platform.accessibility

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
        if (event.operation in REPEATED_OPERATIONS &&
            event == repeatedServiceEvent
        ) {
            repeatCount++
            if (repeatCount == MAX_REPEATS) flushRepeated()
            return
        }
        flushRepeated()
        repeatedServiceEvent = event.takeIf { it.operation in REPEATED_OPERATIONS }
        write(event)
    }

    private fun flushRepeated() {
        val event = repeatedServiceEvent
        if (event != null && repeatCount > 0) write(event, repeatCount)
        repeatCount = 0
    }

    private fun write(event: RuleManagementTraceEvent, repeats: Int = 0) {
        if (event.newSession && event.sessionId != null) CaptureTraceLog.beginSession(event.sessionId)
        CaptureTraceLog.management(event.sessionId,
            "operation" to event.operation, "outcome" to event.outcome,
            "ruleCount" to event.ruleCount,
            "durationMs" to event.durationMs, "repeatCount" to repeats,
            "phase" to event.phase, "failure" to event.failure,
            "serviceOperation" to event.serviceOperation, "newSession" to event.newSession)
    }

    private companion object {
        const val MAX_REPEATS = 32
        val REPEATED_OPERATIONS = setOf(RuleManagementOperation.SERVICE_UPDATE,
            RuleManagementOperation.COMMIT_CAPTURE,
            RuleManagementOperation.READ_CAPTURE, RuleManagementOperation.READ_PREFERENCES,
            RuleManagementOperation.DECODE_CAPTURE)
    }
}
