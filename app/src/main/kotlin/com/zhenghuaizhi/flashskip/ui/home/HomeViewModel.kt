package com.zhenghuaizhi.flashskip.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.zhenghuaizhi.flashskip.application.ServiceStatusCoordinator
import com.zhenghuaizhi.flashskip.application.rulecapture.CaptureOperationFailure
import com.zhenghuaizhi.flashskip.application.rulecapture.CaptureOperationResult
import com.zhenghuaizhi.flashskip.application.rulecapture.RuleCaptureCoordinator
import com.zhenghuaizhi.flashskip.domain.model.CaptureFailure
import com.zhenghuaizhi.flashskip.domain.model.CapturePhase
import com.zhenghuaizhi.flashskip.domain.model.RuleCaptureState
import com.zhenghuaizhi.flashskip.domain.model.SemanticDiffOutcome
import com.zhenghuaizhi.flashskip.domain.model.ServiceStatus
import com.zhenghuaizhi.flashskip.domain.model.TargetAppId
import com.zhenghuaizhi.flashskip.domain.ports.FlashSkipStore
import com.zhenghuaizhi.flashskip.domain.rules.RuleCatalog
import com.zhenghuaizhi.flashskip.domain.rules.RuleCatalogSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
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
    private var lastOperation: RetryOperation? = null
    // 生命周期可能先于开始请求的落盘完成；先记住离开/返回，再绑定实际创建的会话 ID。
    private var pendingReturnSessionId: Long? = null
    private var observedDepartureSessionId: Long? = null
    private var captureStartPending = false
    private var stoppedWhileCaptureStartPending = false
    private var returnedWhileCaptureStartPending = false

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
        captureCoordinator.serviceFailure,
    ) { preferences, serviceStatus, catalog, operation, serviceFailure ->
        val captureState = catalog.captureState
        val activeSession = captureState.activeSession
        val currentServiceFailure = serviceFailure?.takeIf {
            it.sessionId != null && it.sessionId == activeSession?.id
        }
        val serviceWriteFailed = currentServiceFailure?.reason == CaptureOperationFailure.STORAGE
        val targets = catalog.snapshot.capturedTargets.map { captured ->
            val id = captured.targetAppId
            val available = catalog.snapshot.rulesForTarget(id).isNotEmpty()
            val enabled = preferences.enabledApps[id] == true
            TargetAppUiState(
                id = id,
                displayName = captured.displayName.takeUnless { it == id.packageName || it.isBlank() }
                    ?: "该应用",
                checked = enabled && available,
                status = when {
                    !available -> TargetAppStatus.RULE_UNAVAILABLE
                    !enabled -> TargetAppStatus.DISABLED
                    serviceStatus == ServiceStatus.RUNNING -> TargetAppStatus.ENABLED_AND_RUNNING
                    else -> TargetAppStatus.ENABLED_BUT_SERVICE_UNAVAILABLE
                },
                canEnable = available && catalog.matchesCurrentSamples &&
                    activeSession?.blocksAutomation != true && !operation.inProgress,
            )
        }
        val feedback = if (operation.savedTarget != null && catalog.matchesCurrentSamples) {
            if (catalog.snapshot.rulesForTarget(operation.savedTarget).isNotEmpty()) {
                HomeFeedback.SAVED
            } else {
                HomeFeedback.SAVED_NOT_READY
            }
        } else {
            operation.feedback
        }
        HomeUiState(
            serviceStatus = serviceStatus,
            targetApps = targets,
            capture = CaptureUiState(
                sessionId = activeSession?.id,
                operationInProgress = operation.inProgress,
                phase = if (serviceWriteFailed) CapturePhase.FAILED else activeSession?.phase,
                failure = when {
                    serviceWriteFailed -> CaptureIssue.STORAGE
                    activeSession?.failureDiagnostics?.semanticDiff?.outcome == SemanticDiffOutcome.AMBIGUOUS ->
                        CaptureIssue.AMBIGUOUS
                    else -> activeSession?.failure.toCaptureIssue()
                },
                targetAppName = activeSession?.targetAppName?.takeUnless {
                    it.isBlank() || it == activeSession.packageName
                },
                canStart = !operation.inProgress && serviceStatus == ServiceStatus.RUNNING &&
                    (activeSession == null || activeSession.phase == CapturePhase.FAILED || serviceWriteFailed),
            ),
            operationInProgress = operation.inProgress,
            operationFeedback = feedback,
            canRetry = operation.retryAvailable && !operation.inProgress,
            ruleActionsEnabled = catalog.matchesCurrentSamples,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeUiState(),
    )

    fun setAppEnabled(targetAppId: TargetAppId, enabled: Boolean) {
        launchOperation(RetryOperation.Toggle(targetAppId, enabled))
    }

    fun startCapture() {
        if (serviceStatusCoordinator.status.value != ServiceStatus.RUNNING ||
            operationState.value.inProgress
        ) return
        val failedSessionId = captureCoordinator.serviceFailure.value?.sessionId?.takeIf {
            it == uiState.value.capture.sessionId
        }
        captureStartPending = true
        stoppedWhileCaptureStartPending = false
        returnedWhileCaptureStartPending = false
        launchOperation(RetryOperation.Start(failedSessionId))
    }

    fun finishCapture() {
        val session = uiState.value.capture.sessionId ?: return
        launchOperation(RetryOperation.Finish(session))
    }

    fun cancelCapture() {
        val session = uiState.value.capture.sessionId ?: return
        launchOperation(RetryOperation.Cancel(session))
    }

    fun clearCapturedRules(targetAppId: TargetAppId) {
        launchOperation(RetryOperation.Clear(targetAppId))
    }

    fun clearAllCapturedRules() {
        launchOperation(RetryOperation.ClearAll)
    }

    /** 重试固定的原动作；会话动作始终保留原 ID，不能取消或保存替换会话。 */
    fun retryLastOperation() {
        val operation = lastOperation ?: return
        if (!operationState.value.retryAvailable || operationState.value.inProgress) return
        if (operation is RetryOperation.Start) {
            captureStartPending = true
            stoppedWhileCaptureStartPending = false
            returnedWhileCaptureStartPending = false
        }
        launchOperation(operation)
    }

    fun reportSettingsLaunchFailure() {
        operationState.value = operationState.value.copy(feedback = HomeFeedback.SETTINGS_FAILED, retryAvailable = false)
    }

    fun reportGoHomeFailure(sessionId: Long) {
        viewModelScope.launch {
            // 外跳反馈可能先于开始请求释放控件；等同一请求结束后再取消其会话。
            operationState.first { !it.inProgress }
            if (captureCoordinator.state.first().activeSession?.id == sessionId) {
                launchOperation(RetryOperation.CancelHomeFailure(sessionId))
            }
        }
    }

    fun refresh() {
        serviceStatusCoordinator.refresh()
        viewModelScope.launch { captureCoordinator.expireActive() }
    }

    fun onHostStopped(isChangingConfigurations: Boolean) {
        if (isChangingConfigurations) return
        val sessionId = pendingReturnSessionId
        if (sessionId == null) {
            if (captureStartPending) stoppedWhileCaptureStartPending = true
            return
        }
        observedDepartureSessionId = sessionId
        viewModelScope.launch { persistDeparture(sessionId) }
    }

    fun onHostResumed() {
        serviceStatusCoordinator.refresh()
        if (captureStartPending && stoppedWhileCaptureStartPending) {
            returnedWhileCaptureStartPending = true
        }
        val expectedSessionId = pendingReturnSessionId
        viewModelScope.launch {
            val sessionId = expectedSessionId ?: captureCoordinator.state.first().activeSession?.id
            val observedDeparture = sessionId != null && observedDepartureSessionId == sessionId
            reconcileReturn(sessionId, observedDeparture)
        }
    }

    private suspend fun beginCapture(operation: RetryOperation.Start): CaptureOperationResult {
        if (operation.previousSessionCancelled && captureCoordinator.state.first().activeSession != null) {
            captureStartPending = false
            return CaptureOperationResult.Stale
        }
        operation.failedSessionId?.takeUnless { operation.previousSessionCancelled }?.let { sessionId ->
            val cancelled = captureCoordinator.cancel(sessionId)
            if (cancelled !is CaptureOperationResult.Success) {
                captureStartPending = false
                return cancelled
            }
            // 重启包含两次写入；第二次失败时，重试只创建新会话，不再次取消已结束的旧会话。
            lastOperation = operation.copy(previousSessionCancelled = true)
        }
        try {
            val result = captureCoordinator.requestStart()
            if (result is CaptureOperationResult.Success && result.sessionId != null) {
                val sessionId = result.sessionId
                pendingReturnSessionId = sessionId
                captureStartPending = false
                if (stoppedWhileCaptureStartPending) {
                    observedDepartureSessionId = sessionId
                    viewModelScope.launch { persistDepartureAndMaybeReturn(sessionId) }
                }
                if (captureCoordinator.awaitConfigured(sessionId) &&
                    !stoppedWhileCaptureStartPending && !returnedWhileCaptureStartPending
                ) {
                    mutableEffects.emit(HomeEffect.GoHome(sessionId))
                }
            }
            return result
        } finally {
            captureStartPending = false
        }
    }

    private suspend fun reconcileReturn(sessionId: Long?, observedDeparture: Boolean) {
        val retry = sessionId?.let { RetryOperation.Return(it, observedDeparture) }
        try {
            val result = captureCoordinator.reconcileReturn(sessionId, observedDeparture)
            when (result) {
                is CaptureOperationResult.Failure -> reportLifecycleFailure(retry, HomeFeedback.RETURN_STORAGE)
                is CaptureOperationResult.Success -> {
                    clearLifecycleMarkers(result.sessionId)
                    if (!operationState.value.inProgress) operationState.value = OperationUiState()
                    captureCoordinator.expireActive()
                }
                CaptureOperationResult.Stale -> captureCoordinator.expireActive()
                CaptureOperationResult.Pending -> Unit
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            reportLifecycleFailure(retry, HomeFeedback.RETURN_STORAGE)
        }
    }

    private suspend fun persistDeparture(sessionId: Long) {
        try {
            val result = captureCoordinator.markLeftFlashSkip(sessionId)
            if (result is CaptureOperationResult.Failure) {
                reportLifecycleFailure(RetryOperation.Return(sessionId, true), HomeFeedback.DEPARTURE_STORAGE)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            reportLifecycleFailure(RetryOperation.Return(sessionId, true), HomeFeedback.DEPARTURE_STORAGE)
        }
    }

    private suspend fun persistDepartureAndMaybeReturn(sessionId: Long) {
        persistDeparture(sessionId)
        if (returnedWhileCaptureStartPending) reconcileReturn(sessionId, true)
    }

    private suspend fun reportLifecycleFailure(retry: RetryOperation?, feedback: HomeFeedback) {
        if (retry is RetryOperation.Return && captureCoordinator.state.first().activeSession?.id != retry.sessionId) return
        lastOperation = retry
        operationState.value = operationState.value.copy(
            feedback = feedback,
            retryAvailable = retry != null,
        )
    }

    private fun clearLifecycleMarkers(sessionId: Long?) {
        if (pendingReturnSessionId == sessionId) pendingReturnSessionId = null
        if (observedDepartureSessionId == sessionId) observedDepartureSessionId = null
    }

    private fun launchOperation(operation: RetryOperation) {
        if (operationState.value.inProgress) return
        lastOperation = operation
        operationState.value = OperationUiState(inProgress = true)
        viewModelScope.launch {
            try {
                val result = when (operation) {
                    is RetryOperation.Toggle -> captureCoordinator.setAppEnabled(operation.id, operation.enabled)
                    is RetryOperation.Start -> beginCapture(operation)
                    is RetryOperation.Finish -> captureCoordinator.finish(operation.sessionId)
                    is RetryOperation.Cancel -> captureCoordinator.cancel(operation.sessionId)
                    is RetryOperation.Clear -> captureCoordinator.clearTarget(operation.id)
                    RetryOperation.ClearAll -> captureCoordinator.clearAll()
                    is RetryOperation.Return -> captureCoordinator.reconcileReturn(operation.sessionId, operation.observedDeparture)
                    is RetryOperation.CancelHomeFailure -> captureCoordinator.cancel(operation.sessionId)
                }
                if (result is CaptureOperationResult.Success) {
                    val sessionId = when (operation) {
                        is RetryOperation.Return -> operation.sessionId
                        is RetryOperation.Cancel -> operation.sessionId
                        is RetryOperation.CancelHomeFailure -> operation.sessionId
                        else -> null
                    }
                    if (sessionId != null) clearLifecycleMarkers(sessionId)
                    val lifecycleFeedback = operationState.value.takeIf {
                        operation is RetryOperation.Start && it.feedback in setOf(HomeFeedback.RETURN_STORAGE, HomeFeedback.DEPARTURE_STORAGE)
                    }
                    operationState.value = OperationUiState(
                        feedback = if (operation is RetryOperation.CancelHomeFailure) HomeFeedback.GO_HOME_FAILED else lifecycleFeedback?.feedback,
                        retryAvailable = lifecycleFeedback?.retryAvailable == true,
                        savedTarget = if (operation is RetryOperation.Finish) {
                            // 完成后会话已清除，按最后新增样本查找展示目录中的可用状态。
                            captureCoordinator.state.first().samples.lastOrNull()?.targetAppId
                        } else null,
                    )
                } else {
                    operationState.value = OperationUiState(
                        feedback = result.toFeedback(operation is RetryOperation.Return),
                        retryAvailable = result is CaptureOperationResult.Failure && result.reason == CaptureOperationFailure.STORAGE,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                operationState.value = OperationUiState(
                    feedback = if (operation is RetryOperation.Return) HomeFeedback.RETURN_STORAGE else HomeFeedback.STORAGE,
                    retryAvailable = true,
                )
            } finally {
                operationState.value = operationState.value.copy(inProgress = false)
            }
        }
    }

    private fun CaptureOperationResult.toFeedback(isReturn: Boolean = false): HomeFeedback? = when (this) {
        is CaptureOperationResult.Success, CaptureOperationResult.Pending -> null
        CaptureOperationResult.Stale -> HomeFeedback.STALE
        is CaptureOperationResult.Failure -> when (reason) {
            CaptureOperationFailure.STORAGE -> if (isReturn) HomeFeedback.RETURN_STORAGE else HomeFeedback.STORAGE
            CaptureOperationFailure.CAPTURE_IN_PROGRESS -> HomeFeedback.CAPTURE_IN_PROGRESS
            CaptureOperationFailure.RULE_NOT_READY -> HomeFeedback.RULE_NOT_READY
            CaptureOperationFailure.RULES_UPDATING -> HomeFeedback.RULES_UPDATING
        }
    }

    private fun CaptureFailure?.toCaptureIssue(): CaptureIssue? = when (this) {
        CaptureFailure.SERVICE_UNAVAILABLE -> CaptureIssue.SERVICE_UNAVAILABLE
        CaptureFailure.CONFIGURATION_TIMEOUT -> CaptureIssue.CONFIGURATION_TIMEOUT
        CaptureFailure.TARGET_TIMEOUT -> CaptureIssue.TARGET_TIMEOUT
        CaptureFailure.CLICK_TIMEOUT, CaptureFailure.DISMISS_NOT_CAPTURED -> CaptureIssue.CLICK_NOT_CAPTURED
        CaptureFailure.COMPLETION_TIMEOUT -> CaptureIssue.COMPLETION_TIMEOUT
        CaptureFailure.RETURNED_BEFORE_TARGET -> CaptureIssue.RETURNED_BEFORE_TARGET
        CaptureFailure.ACTIVITY_UNAVAILABLE, CaptureFailure.CLICK_SOURCE_UNAVAILABLE,
        CaptureFailure.CLICK_SEMANTICS_UNAVAILABLE -> CaptureIssue.APP_UNAVAILABLE
        null -> null
    }

    private data class CatalogUiState(
        val captureState: RuleCaptureState,
        val snapshot: RuleCatalogSnapshot,
        val matchesCurrentSamples: Boolean,
    )

    private data class OperationUiState(
        val inProgress: Boolean = false,
        val feedback: HomeFeedback? = null,
        val retryAvailable: Boolean = false,
        val savedTarget: TargetAppId? = null,
    )

    private sealed interface RetryOperation {
        data class Toggle(val id: TargetAppId, val enabled: Boolean) : RetryOperation
        data class Start(val failedSessionId: Long?, val previousSessionCancelled: Boolean = false) : RetryOperation
        data class Finish(val sessionId: Long) : RetryOperation
        data class Cancel(val sessionId: Long) : RetryOperation
        data class Clear(val id: TargetAppId) : RetryOperation
        data object ClearAll : RetryOperation
        data class Return(val sessionId: Long, val observedDeparture: Boolean) : RetryOperation
        data class CancelHomeFailure(val sessionId: Long) : RetryOperation
    }

    sealed interface HomeEffect {
        data class GoHome(val sessionId: Long) : HomeEffect
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
            return HomeViewModel(store, ruleCatalog, serviceStatusCoordinator, captureCoordinator) as T
        }
    }
}
