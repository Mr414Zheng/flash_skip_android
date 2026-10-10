package com.zhenghuaizhi.flashskip.platform.accessibility

import android.util.Log
import com.zhenghuaizhi.flashskip.domain.ports.AutomationTraceEvent
import com.zhenghuaizhi.flashskip.domain.ports.AutomationTraceSink
import com.zhenghuaizhi.flashskip.domain.ports.AutomationTraceStage
import java.util.concurrent.atomic.AtomicLong

internal class AutomationTraceLog(override val enabled: Boolean) : AutomationTraceSink {
    private val engineId = nextEngineId.incrementAndGet()
    private var sequence = 0L
    private var sessionId: Long? = null
    private val repeated = linkedMapOf<Pair<AutomationTraceStage, Pair<Int?, Int?>>, Repeated>()

    @Synchronized
    override fun record(event: AutomationTraceEvent) {
        if (!enabled) return
        if (sessionId != event.sessionId) {
            flush()
            repeated.clear()
            sessionId = event.sessionId
        }
        when (event.stage) {
            AutomationTraceStage.QUERY,
            AutomationTraceStage.QUERY_SCHEDULED,
            AutomationTraceStage.FOREGROUND_SKIPPED,
            AutomationTraceStage.RULE_SKIPPED,
            -> {
                val key = event.stage to (event.ruleHash to event.selectorIndex)
                val previous = repeated[key]
                val signature = event.copy(elapsedMs = 0, durationMs = null, delayMs = null, queryCount = 0)
                if (previous?.signature == signature) {
                    previous.latest = event
                    previous.count++
                    return
                }
                previous?.let(::flushRepeat)
                if (previous == null && repeated.size >= MAX_REPEATED_KEYS) {
                    repeated.entries.first().let { entry ->
                        flushRepeat(entry.value)
                        repeated.remove(entry.key)
                    }
                }
                repeated[key] = Repeated(signature, event)
                write(event)
            }
            else -> {
                flush()
                write(event)
                if (event.stage == AutomationTraceStage.FINISHED ||
                    event.stage == AutomationTraceStage.INVALIDATED
                ) {
                    repeated.clear()
                }
            }
        }
    }

    private fun flush() = repeated.values.forEach(::flushRepeat)

    private fun flushRepeat(repeat: Repeated) {
        if (repeat.count == 0) return
        write(repeat.latest, repeat.count)
        repeat.count = 0
    }

    private fun write(event: AutomationTraceEvent, repeatCount: Int = 0) {
        val fields = listOf(
            "engine" to engineId,
            "session" to event.sessionId,
            "seq" to ++sequence,
            "relativeMs" to event.elapsedMs,
            "stage" to event.stage,
            "targetHash" to event.targetHash.toUInt().toString(16),
            "windowId" to event.windowId,
            "ruleHash" to event.ruleHash?.toUInt()?.toString(16),
            "selectorIndex" to event.selectorIndex,
            "selectorHash" to event.selectorHash?.toUInt()?.toString(16),
            "resourceIdHash" to event.resourceIdHash?.toUInt()?.toString(16),
            "ancestorIdHash" to event.ancestorIdHash?.toUInt()?.toString(16),
            "textKind" to event.textKind,
            "hasDescription" to event.hasDescription,
            "ancestorDistance" to event.ancestorDistance,
            "actionAncestorDistance" to event.actionAncestorDistance,
            "result" to event.result,
            "attempt" to event.attempt,
            "verificationIndex" to event.verificationIndex,
            "queryOnly" to event.queryOnly,
            "durationMs" to event.durationMs,
            "delayMs" to event.delayMs,
            "queryCount" to event.queryCount,
            "ruleCount" to event.ruleCount,
            "method" to event.method,
            "outcome" to event.outcome,
            "reason" to event.reason,
            "stopReason" to event.stopReason,
            "observation" to event.observation,
            "proof" to event.proof,
            "eventRevision" to event.eventRevision,
            "fallbackAllowed" to event.fallback,
            "retryAllowed" to event.retryAllowed,
            "repeatCount" to repeatCount.takeIf { it > 0 },
        )
        Log.d(TAG, fields.filter { it.second != null }.joinToString(" ") { "${it.first}=${it.second}" })
    }

    private data class Repeated(
        val signature: AutomationTraceEvent,
        var latest: AutomationTraceEvent,
        var count: Int = 0,
    )

    companion object {
        const val TAG = "FlashSkipAutomation"
        private val nextEngineId = AtomicLong()
        private const val MAX_REPEATED_KEYS = 64
    }
}
