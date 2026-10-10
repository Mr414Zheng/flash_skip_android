package com.zhenghuaizhi.flashskip.ui.home

import com.zhenghuaizhi.flashskip.domain.model.CapturePhase
import com.zhenghuaizhi.flashskip.domain.model.ServiceStatus
import com.zhenghuaizhi.flashskip.domain.model.TargetAppId

/** 首页只保留用户能理解的展示状态，不携带执行历史和采集诊断。 */
data class HomeUiState(
    val serviceStatus: ServiceStatus = ServiceStatus.NOT_ENABLED,
    val targetApps: List<TargetAppUiState> = emptyList(),
    val capture: CaptureUiState = CaptureUiState(),
    val operationInProgress: Boolean = false,
    val operationFeedback: HomeFeedback? = null,
    val canRetry: Boolean = false,
    val ruleActionsEnabled: Boolean = true,
)

data class TargetAppUiState(
    val id: TargetAppId,
    val displayName: String,
    val checked: Boolean,
    val status: TargetAppStatus,
    val canEnable: Boolean,
)

data class CaptureUiState(
    val sessionId: Long? = null,
    val operationInProgress: Boolean = false,
    val phase: CapturePhase? = null,
    val failure: CaptureIssue? = null,
    val targetAppName: String? = null,
    val canStart: Boolean = false,
)

enum class CaptureIssue {
    SERVICE_UNAVAILABLE, CONFIGURATION_TIMEOUT, TARGET_TIMEOUT, CLICK_NOT_CAPTURED,
    COMPLETION_TIMEOUT, RETURNED_BEFORE_TARGET, APP_UNAVAILABLE, AMBIGUOUS, UNKNOWN,
    STORAGE,
}

enum class HomeFeedback {
    STORAGE, CAPTURE_IN_PROGRESS, RULE_NOT_READY, RULES_UPDATING, STALE,
    RETURN_STORAGE, DEPARTURE_STORAGE, GO_HOME_FAILED, SETTINGS_FAILED,
    SAVED, SAVED_NOT_READY,
}

enum class TargetAppStatus {
    RULE_UNAVAILABLE,
    DISABLED,
    ENABLED_AND_RUNNING,
    ENABLED_BUT_SERVICE_UNAVAILABLE,
}
