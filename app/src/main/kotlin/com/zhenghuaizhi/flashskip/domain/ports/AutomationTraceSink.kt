package com.zhenghuaizhi.flashskip.domain.ports

import com.zhenghuaizhi.flashskip.domain.model.ActionMethod
import com.zhenghuaizhi.flashskip.domain.model.ActionOutcome
import com.zhenghuaizhi.flashskip.domain.model.ActionReason
import com.zhenghuaizhi.flashskip.domain.model.ActionObservationStatus

/** 执行诊断仅传递数值和固定枚举，不携带页面文本、节点或坐标。 */
interface AutomationTraceSink {
    val enabled: Boolean
    fun record(event: AutomationTraceEvent)

    object None : AutomationTraceSink {
        override val enabled = false
        override fun record(event: AutomationTraceEvent) = Unit
    }
}

data class AutomationTraceEvent(
    val sessionId: Long,
    val elapsedMs: Long,
    val stage: AutomationTraceStage,
    val targetHash: Int,
    val windowId: Int,
    val ruleHash: Int? = null,
    val selectorIndex: Int? = null,
    val selectorHash: Int? = null,
    val resourceIdHash: Int? = null,
    val ancestorIdHash: Int? = null,
    val textKind: AutomationTraceTextKind? = null,
    val hasDescription: Boolean? = null,
    val ancestorDistance: Int? = null,
    val actionAncestorDistance: Int? = null,
    val result: AutomationTraceResult? = null,
    val attempt: Int? = null,
    val verificationIndex: Int? = null,
    val queryOnly: Boolean? = null,
    val durationMs: Long? = null,
    val delayMs: Long? = null,
    val queryCount: Int = 0,
    val ruleCount: Int = 0,
    val method: ActionMethod? = null,
    val outcome: ActionOutcome? = null,
    val reason: ActionReason? = null,
    val stopReason: AutomationStopReason? = null,
    val observation: ActionObservationStatus? = null,
    val proof: AbsenceProof? = null,
    val eventRevision: Long? = null,
    val fallback: Boolean? = null,
    val retryAllowed: Boolean? = null,
)

enum class AutomationTraceStage {
    SESSION_START,
    FOREGROUND_SKIPPED,
    RULE_SKIPPED,
    QUERY_SCHEDULED,
    QUERY,
    NODE_CLICK,
    VERIFY_QUERY,
    VERIFY_CLICK,
    VERIFY_RESULT,
    GESTURE,
    FINISHED,
    RECORD_WRITE_FAILED,
    INVALIDATED,
    ACTION_ACCEPTED,
    OBSERVATION_SCHEDULED,
    TARGET_OBSERVATION,
    RETRY_DECISION,
}

enum class AutomationTraceResult {
    UNAVAILABLE,
    NONE, UNIQUE, AMBIGUOUS, PAGE_CHANGED, TRAVERSAL_LIMIT,
    CLICKED, NOT_ACTIONABLE, NOT_SUPPORTED, NODE_DISAPPEARED,
    COMPLETED, REJECTED, CANCELLED, TIMED_OUT,
    CONFIRMED, RETRY, ABANDONED, FAILED,
    ACCEPTED, TARGET_PRESENT, EVIDENCE_INSUFFICIENT, TARGET_ABSENT,
}

enum class AutomationTraceTextKind { EXACT, PREFIX, SKIP_COUNTDOWN, NUMERIC_COUNTDOWN }

enum class AutomationStopReason {
    OUTSIDE_TARGETS, TARGET_REPLACED, VERSION_CHANGED, SESSION_EXPIRED,
    APP_DISABLED, MAILBOX_OVERFLOW, CAPTURE_PAUSED, ENGINE_STOPPED, STALE_VERIFICATION,
    CATALOG_CHANGED, NO_COMPATIBLE_RULES, STARTUP_DELAY, ACTIVITY_MISMATCH, VERSION_MISMATCH, COOLDOWN,
}
