package com.zhenghuaizhi.flashskip.domain.model

data class ActionRecord(
    val timestampEpochMs: Long,
    val targetAppId: TargetAppId,
    val ruleId: String,
    val method: ActionMethod?,
    val outcome: ActionOutcome,
    val reason: ActionReason? = null,
)

enum class ActionMethod {
    NODE_CLICK,
    CENTER_GESTURE,
}

enum class ActionOutcome {
    CLOSED,
    ABANDONED,
    FAILED,
}

enum class ActionReason {
    AMBIGUOUS_MATCH,
    NODE_DISAPPEARED,
    PAGE_CHANGED,
    QUERY_STALE,
    TRAVERSAL_LIMIT,
    CLICK_UNCONFIRMED,
    GESTURE_REJECTED,
    GESTURE_CANCELLED,
}

enum class ServiceStatus {
    NOT_ENABLED,
    CONFIRMING,
    RUNNING,
    ABNORMAL,
}
