package com.zhenghuaizhi.flashskip.domain.model

data class ActionRecord(
    val timestampEpochMs: Long,
    val targetAppId: TargetAppId,
    val targetAppName: String = targetAppId.packageName,
    val ruleId: String,
    val method: ActionMethod?,
    val outcome: ActionOutcome,
    val reason: ActionReason? = null,
    val timing: ExecutionTiming? = null,
)

/** 仅记录相对目标应用进入前台的阶段耗时，不包含页面内容或节点数据。 */
data class ExecutionTiming(
    val targetToFirstQueryMs: Long? = null,
    val targetToFirstMatchMs: Long? = null,
    val targetToActionMs: Long? = null,
    val targetToCompletedMs: Long,
    val selectorQueryCount: Int,
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
