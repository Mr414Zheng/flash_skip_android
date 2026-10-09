package com.zhenghuaizhi.flashskip.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.zhenghuaizhi.flashskip.application.ServiceStatusCoordinator
import com.zhenghuaizhi.flashskip.application.rulecapture.CaptureOperationFailure
import com.zhenghuaizhi.flashskip.application.rulecapture.CaptureOperationResult
import com.zhenghuaizhi.flashskip.application.rulecapture.RuleCaptureCoordinator
import com.zhenghuaizhi.flashskip.domain.model.ActionOutcome
import com.zhenghuaizhi.flashskip.domain.model.CapturePhase
import com.zhenghuaizhi.flashskip.domain.model.RuleAvailability
import com.zhenghuaizhi.flashskip.domain.model.RuleCaptureState
import com.zhenghuaizhi.flashskip.domain.model.ServiceStatus
import com.zhenghuaizhi.flashskip.domain.model.TargetApp
import com.zhenghuaizhi.flashskip.domain.model.TargetAppId
import com.zhenghuaizhi.flashskip.domain.ports.FlashSkipStore
import com.zhenghuaizhi.flashskip.domain.rules.RuleCatalog
import com.zhenghuaizhi.flashskip.domain.rules.RuleCatalogSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(
    private val store: FlashSkipStore,
    private val ruleCatalog: RuleCatalog,
    private val serviceStatusCoordinator: ServiceStatusCoordinator,
    private val captureCoordinator: RuleCaptureCoordinator,
) : ViewModel() {
    private val mutableEffects = MutableSharedFlow<HomeEffect>(extraBufferCapacity = 1)
    val effects = mutableEffects.asSharedFlow()
    private val operationState = MutableStateFlow(OperationUiState())

    private val currentCatalog = combine(
        captureCoordinator.state,
        ruleCatalog.snapshot,
    ) { capture, snapshot ->
        // 同时检查采集状态与目录版本，目录计算期间不得操作旧规则开关。
        CatalogUiState(capture, snapshot, snapshot.sourceSamples == capture.samples)
    }

    val uiState = combine(
        store.preferences,
        serviceStatusCoordinator.status,
        currentCatalog,
        operationState,
    ) { preferences, serviceStatus, catalog, operation ->
        val captureState = catalog.captureState
        val targets = catalog.snapshot.capturedTargets.map { captured ->
            val targetAppId = captured.targetAppId
            val rules = catalog.snapshot.rulesForTarget(targetAppId)
            val analysis = captured.analysis
            val target = TargetApp(
                id = targetAppId,
                displayName = captured.displayName,
                packageName = targetAppId.packageName,
                ruleAvailability = if (rules.isNotEmpty()) {
                    RuleAvailability.Available(rules.size)
                } else {
                    RuleAvailability.Unavailable
                },
            )
            targetAppUiState(
                target = target,
                enabled = preferences.enabledApps[targetAppId] == true,
                serviceStatus = serviceStatus,
                ruleCard = RuleCardUiState(
                    status = analysis.status,
                    sampleCount = analysis.sampleCount,
                    ruleCount = analysis.rules.size,
                    stableSampleCount = analysis.stableSampleCount,
                    uniqueEvidenceCount = analysis.uniqueEvidenceCount,
                    selector = analysis.primarySelector,
                    activities = analysis.allowedActivities,
                ),
            )
        }
        val activeSession = captureState.activeSession
        HomeUiState(
            serviceStatus = serviceStatus,
            enabledAppCount = targets.count {
                it.enabled && it.target.ruleAvailability is RuleAvailability.Available
            },
            targetApps = targets,
            capture = CaptureUiState(
                sessionId = activeSession?.id,
                operationInProgress = operation.inProgress,
                phase = activeSession?.phase,
                failure = activeSession?.failure,
                failureDiagnostics = activeSession?.failureDiagnostics,
                targetWindowDiagnostics = activeSession?.targetWindowDiagnostics,
                canStart = !operation.inProgress && serviceStatus == ServiceStatus.RUNNING &&
                    (activeSession == null || activeSession.phase == CapturePhase.FAILED),
            ),
            capturingAppName = activeSession?.targetAppName,
            records = preferences.records,
            latestSuccess = preferences.records.firstOrNull { it.outcome == ActionOutcome.CLOSED },
            hasCapturedRules = captureState.samples.isNotEmpty(),
            currentTimeEpochMs = System.currentTimeMillis(),
            operationInProgress = operation.inProgress,
            operationMessage = operation.message,
            ruleActionsEnabled = catalog.matchesCurrentSamples,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeUiState(),
    )

    fun setAppEnabled(targetAppId: TargetAppId, enabled: Boolean) {
        launchOperation { captureCoordinator.setAppEnabled(targetAppId, enabled) }
    }

    fun startCapture() {
        if (serviceStatusCoordinator.status.value != ServiceStatus.RUNNING) return
        launchOperation {
            val result = captureCoordinator.requestStart()
            if (result is CaptureOperationResult.Success && result.sessionId != null &&
                captureCoordinator.awaitConfigured(result.sessionId)
            ) {
                mutableEffects.emit(HomeEffect.GoHome)
            }
            result
        }
    }

    fun finishCapture() {
        val sessionId = uiState.value.capture.sessionId ?: return
        launchOperation { captureCoordinator.finish(sessionId) }
    }

    fun cancelCapture() {
        val sessionId = uiState.value.capture.sessionId ?: return
        launchOperation { captureCoordinator.cancel(sessionId) }
    }

    fun clearCapturedRules(targetAppId: TargetAppId) {
        launchOperation { captureCoordinator.clearTarget(targetAppId) }
    }

    fun clearAllCapturedRules() {
        launchOperation { captureCoordinator.clearAll() }
    }

    fun refresh() {
        serviceStatusCoordinator.refresh()
        viewModelScope.launch {
            try {
                captureCoordinator.expireActive()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                operationState.value = operationState.value.copy(
                    message = CaptureOperationResult.Failure(CaptureOperationFailure.STORAGE).message(),
                )
            }
        }
    }

    private fun launchOperation(action: suspend () -> CaptureOperationResult) {
        if (operationState.value.inProgress) return
        operationState.value = OperationUiState(inProgress = true)
        viewModelScope.launch {
            try {
                val result = action()
                operationState.value = OperationUiState(message = result.message())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                operationState.value = OperationUiState(
                    message = CaptureOperationResult.Failure(CaptureOperationFailure.STORAGE).message(),
                )
            } finally {
                operationState.value = operationState.value.copy(inProgress = false)
            }
        }
    }

    private fun CaptureOperationResult.message(): String? = when (this) {
        is CaptureOperationResult.Success -> null
        CaptureOperationResult.Stale -> "操作已过期，请根据当前采集状态重试。"
        is CaptureOperationResult.Failure -> when (reason) {
            CaptureOperationFailure.STORAGE -> "未能保存更改，请检查可用存储空间后重试刚才的操作。"
            CaptureOperationFailure.CAPTURE_IN_PROGRESS -> "请先完成或取消采集，再开启自动关闭。"
            CaptureOperationFailure.RULE_NOT_READY -> "规则尚不可用，请重新采集后再开启自动关闭。"
            CaptureOperationFailure.RULES_UPDATING -> "规则正在更新，请稍候再试。"
        }
    }

    private data class CatalogUiState(
        val captureState: RuleCaptureState,
        val snapshot: RuleCatalogSnapshot,
        val matchesCurrentSamples: Boolean,
    )

    private data class OperationUiState(
        val inProgress: Boolean = false,
        val message: String? = null,
    )

    sealed interface HomeEffect {
        data object GoHome : HomeEffect
    }

    class Factory(
        private val store: FlashSkipStore,
        private val ruleCatalog: RuleCatalog,
        private val serviceStatusCoordinator: ServiceStatusCoordinator,
        private val captureCoordinator: RuleCaptureCoordinator,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(HomeViewModel::class.java))
            return HomeViewModel(
                store,
                ruleCatalog,
                serviceStatusCoordinator,
                captureCoordinator,
            ) as T
        }
    }
}
