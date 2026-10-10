package com.zhenghuaizhi.flashskip.application.rulecapture

/** 规则管理诊断只允许枚举、计数、耗时和会话散列，不接收页面或节点信息。 */
interface RuleManagementTraceSink {
    val enabled: Boolean
    fun record(event: RuleManagementTraceEvent)

    data object None : RuleManagementTraceSink {
        override val enabled = false
        override fun record(event: RuleManagementTraceEvent) = Unit
    }
}

enum class RuleManagementOperation {
    INITIALIZE_CAPTURE, INITIALIZE_PREFERENCES, PUBLISH, FINISH, ENABLE, DISABLE,
    DELETE_TARGET, DELETE_ALL, SERVICE_UPDATE,
}

enum class RuleManagementOutcome { SUCCESS, REJECTED, STORAGE_FAILURE }

data class RuleManagementTraceEvent(
    val operation: RuleManagementOperation,
    val outcome: RuleManagementOutcome,
    val sessionHash: Int? = null,
    val ruleCount: Int? = null,
    val durationMs: Long? = null,
)

/** 关闭时不创建事件，诊断后端的异常也不改变业务结果。 */
inline fun RuleManagementTraceSink.recordSafely(event: () -> RuleManagementTraceEvent) {
    if (!enabled) return
    try {
        record(event())
    } catch (_: Exception) {
        // 平台日志不可用时继续原业务操作。
    }
}
