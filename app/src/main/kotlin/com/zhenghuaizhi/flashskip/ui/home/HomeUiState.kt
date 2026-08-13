package com.zhenghuaizhi.flashskip.ui.home

import com.zhenghuaizhi.flashskip.domain.model.ActionRecord
import com.zhenghuaizhi.flashskip.domain.model.CaptureFailure
import com.zhenghuaizhi.flashskip.domain.model.CaptureFailureDiagnostics
import com.zhenghuaizhi.flashskip.domain.model.CapturePhase
import com.zhenghuaizhi.flashskip.domain.model.CapturedSelector
import com.zhenghuaizhi.flashskip.domain.model.RuleCardStatus
import com.zhenghuaizhi.flashskip.domain.model.ServiceStatus
import com.zhenghuaizhi.flashskip.domain.model.TargetWindowDiagnostics
import com.zhenghuaizhi.flashskip.domain.model.TargetApp
import com.zhenghuaizhi.flashskip.domain.model.RuleAvailability

data class HomeUiState(
    val serviceStatus: ServiceStatus = ServiceStatus.NOT_ENABLED,
    val enabledAppCount: Int = 0,
    val targetApps: List<TargetAppUiState> = emptyList(),
    val capture: CaptureUiState = CaptureUiState(),
    val capturingAppName: String? = null,
    val records: List<ActionRecord> = emptyList(),
    val latestSuccess: ActionRecord? = null,
    val hasCapturedRules: Boolean = false,
    val currentTimeEpochMs: Long = System.currentTimeMillis(),
)

data class TargetAppUiState(
    val target: TargetApp,
    val enabled: Boolean,
    val status: TargetAppStatus,
    val ruleCard: RuleCardUiState? = null,
)

data class CaptureUiState(
    val phase: CapturePhase? = null,
    val failure: CaptureFailure? = null,
    val failureDiagnostics: CaptureFailureDiagnostics? = null,
    val targetWindowDiagnostics: TargetWindowDiagnostics? = null,
    val canStart: Boolean = false,
)

data class RuleCardUiState(
    val status: RuleCardStatus,
    val sampleCount: Int,
    val ruleCount: Int,
    val stableSampleCount: Int,
    val uniqueEvidenceCount: Int,
    val selector: CapturedSelector?,
    val activities: Set<String> = emptySet(),
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
    ruleCard: RuleCardUiState? = null,
): TargetAppUiState {
    val status = when {
        target.ruleAvailability !is RuleAvailability.Available -> TargetAppStatus.RULE_UNAVAILABLE
        !enabled -> TargetAppStatus.DISABLED
        serviceStatus == ServiceStatus.RUNNING -> TargetAppStatus.ENABLED_AND_RUNNING
        else -> TargetAppStatus.ENABLED_BUT_SERVICE_UNAVAILABLE
    }
    return TargetAppUiState(
        target = target,
        enabled = enabled,
        status = status,
        ruleCard = ruleCard,
    )
}
