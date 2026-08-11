package com.zhenghuaizhi.flashskip.ui.home

import com.zhenghuaizhi.flashskip.domain.model.ActionRecord
import com.zhenghuaizhi.flashskip.domain.model.ServiceStatus
import com.zhenghuaizhi.flashskip.domain.model.TargetApp
import com.zhenghuaizhi.flashskip.domain.model.RuleAvailability

data class HomeUiState(
    val serviceStatus: ServiceStatus = ServiceStatus.NOT_ENABLED,
    val enabledAppCount: Int = 0,
    val targetApps: List<TargetAppUiState> = emptyList(),
    val records: List<ActionRecord> = emptyList(),
    val latestSuccess: ActionRecord? = null,
    val currentTimeEpochMs: Long = System.currentTimeMillis(),
)

data class TargetAppUiState(
    val target: TargetApp,
    val enabled: Boolean,
    val status: TargetAppStatus,
)

enum class TargetAppStatus {
    RULE_UNAVAILABLE,
    DISABLED,
    ENABLED_AND_RUNNING,
    ENABLED_BUT_SERVICE_UNAVAILABLE,
}

fun targetAppUiState(
    target: TargetApp,
    enabled: Boolean,
    serviceStatus: ServiceStatus,
): TargetAppUiState {
    val status = when {
        target.ruleAvailability !is RuleAvailability.Available -> TargetAppStatus.RULE_UNAVAILABLE
        !enabled -> TargetAppStatus.DISABLED
        serviceStatus == ServiceStatus.RUNNING -> TargetAppStatus.ENABLED_AND_RUNNING
        else -> TargetAppStatus.ENABLED_BUT_SERVICE_UNAVAILABLE
    }
    return TargetAppUiState(target = target, enabled = enabled, status = status)
}
