package com.zhenghuaizhi.flashskip.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.zhenghuaizhi.flashskip.application.ServiceStatusCoordinator
import com.zhenghuaizhi.flashskip.application.rulecapture.CaptureOperationFailure
import com.zhenghuaizhi.flashskip.application.rulecapture.CaptureOperationResult
import com.zhenghuaizhi.flashskip.application.rulecapture.RuleCaptureCoordinator
import com.zhenghuaizhi.flashskip.application.rulecapture.RuleDeletionRequest
import com.zhenghuaizhi.flashskip.domain.model.CaptureFailure
import com.zhenghuaizhi.flashskip.domain.model.CapturePhase
import com.zhenghuaizhi.flashskip.domain.model.RuleCaptureState
import com.zhenghuaizhi.flashskip.domain.model.SemanticDiffOutcome
import com.zhenghuaizhi.flashskip.domain.model.ServiceStatus
import com.zhenghuaizhi.flashskip.domain.model.TargetAppId
import com.zhenghuaizhi.flashskip.domain.ports.FlashSkipStore
import com.zhenghuaizhi.flashskip.domain.ports.FlashSkipPreferences
import com.zhenghuaizhi.flashskip.domain.rules.RuleCatalog
import com.zhenghuaizhi.flashskip.domain.rules.RuleCatalogSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
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
    private val pendingNavigation = MutableStateFlow<Long?>(null)
    private val hostForeground = MutableStateFlow(true)
    // 请求保存在 ViewModel 会话内；消费者取得前重新验证，缺少订阅者不会丢失。
    val effects: Flow<HomeEffect> = flow {
        combine(pendingNavigation, hostForeground, captureCoordinator.reads) { id, foreground, read ->
            Triple(id, foreground, read)
        }.collect { (id, foreground, read) ->
            if (id == null || !read.available) return@collect
            val session = read.requireAvailable().activeSession
            if (session?.id != id || session.phase != CapturePhase.WAITING_FOR_TARGET ||
                session.leftFlashSkipAtEpochMs != null || observedDepartureSessionId == id
            ) {
                pendingNavigation.compareAndSet(id, null)
                return@collect
            }
            if (!foreground) return@collect
            // 没有服务定时器或订阅者时也不能把已过期请求变成桌面跳转。
            if (captureCoordinator.expireActive() != CaptureOperationResult.Pending) {
                pendingNavigation.compareAndSet(id, null)
                return@collect
            }
            val current = captureCoordinator.reads.first()
            if (hostForeground.value && current.available &&
                current.requireAvailable().activeSession == session && observedDepartureSessionId != id &&
                pendingNavigation.compareAndSet(id, null)
            ) emit(HomeEffect.GoHome(id))
        }
    }
    private var feedbackOwner = 0L
    private val operationState = MutableStateFlow(OperationUiState())
    private var lastOperation: RetryOperation? = null
    // 生命周期可能先于开始请求的落盘完成；先记住离开/返回，再绑定实际创建的会话 ID。
    private var pendingReturnSessionId: Long? = null
    private var observedDepartureSessionId: Long? = null
    private var captureStartPending = false
    private var stoppedWhileCaptureStartPending = false
    private var returnedWhileCaptureStartPending = false

    private val currentCatalog = combine(
        captureCoordinator.reads,
        ruleCatalog.snapshot,
    ) { read, snapshot ->
        val capture = read.value ?: RuleCaptureState()
        // 同时检查采集状态与目录版本，目录计算期间不得操作旧规则开关。
        CatalogUiState(capture, snapshot, read.available && snapshot.sourceSamples == capture.samples, read.available)
    }

    val uiState = combine(
        store.reads,
        serviceStatusCoordinator.status,
        currentCatalog,
        operationState,
        captureCoordinator.serviceFailure,
    ) { preferenceRead, serviceStatus, catalog, operation, serviceFailure ->
        val preferences = preferenceRead.value ?: FlashSkipPreferences()
        val storageAvailable = preferenceRead.available && catalog.storageAvailable
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
                checked = enabled && available && storageAvailable,
                status = when {
                    !storageAvailable -> TargetAppStatus.RULE_UNAVAILABLE
                    !available -> TargetAppStatus.RULE_UNAVAILABLE
                    !enabled -> TargetAppStatus.DISABLED
                    serviceStatus == ServiceStatus.RUNNING -> TargetAppStatus.ENABLED_AND_RUNNING
                    else -> TargetAppStatus.ENABLED_BUT_SERVICE_UNAVAILABLE
                },
                canEnable = storageAvailable && available && catalog.matchesCurrentSamples &&
                    activeSession?.blocksAutomation != true && !operation.inProgress,
            )
        }
        val feedback = if (!storageAvailable) {
            HomeFeedback.STORAGE
        } else if (operation.savedTarget != null && catalog.matchesCurrentSamples) {
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
                canStart = storageAvailable && !operation.inProgress && serviceStatus == ServiceStatus.RUNNING &&
                    (activeSession == null || activeSession.phase == CapturePhase.FAILED || serviceWriteFailed),
            ),
            operationInProgress = operation.inProgress,
            operationFeedback = feedback,
            canRetry = operation.retryAvailable && !operation.inProgress,
            ruleActionsEnabled = storageAvailable && catalog.matchesCurrentSamples,
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
        if (!serviceStatusCoordinator.isReady ||
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
        launchOperation(RetryOperation.Clear(RuleDeletionRequest(targetAppId)))
    }

    fun clearAllCapturedRules() {
        launchOperation(RetryOperation.Clear(RuleDeletionRequest(null)))
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
        feedbackOwner++
        lastOperation = null
        operationState.value = operationState.value.copy(feedback = HomeFeedback.SETTINGS_FAILED, retryAvailable = false, savedTarget = null)
    }

    fun reportGoHomeFailure(sessionId: Long) {
        val owner = feedbackOwner
        viewModelScope.launch {
            // 外跳反馈可能先于开始请求释放控件；等同一请求结束后再取消其会话。
            operationState.first { !it.inProgress }
            val read = captureCoordinator.reads.first()
            if (owner != feedbackOwner) return@launch
            if (!read.available) {
                reportLifecycleFailure(RetryOperation.CancelHomeFailure(sessionId), HomeFeedback.STORAGE, owner)
            } else if (read.requireAvailable().activeSession?.id == sessionId) {
                launchOperation(RetryOperation.CancelHomeFailure(sessionId))
            }
        }
    }

    fun refresh() {
        serviceStatusCoordinator.refresh()
        viewModelScope.launch { captureCoordinator.expireActive() }
    }

    fun onHostStopped(isChangingConfigurations: Boolean) {
        hostForeground.value = false
        if (isChangingConfigurations) return
        pendingNavigation.value = null
        val owner = feedbackOwner
        val sessionId = pendingReturnSessionId
        if (sessionId == null) {
            if (captureStartPending) stoppedWhileCaptureStartPending = true
            return
        }
        observedDepartureSessionId = sessionId
        viewModelScope.launch { persistDeparture(sessionId, owner) }
    }

    fun onHostResumed() {
        hostForeground.value = true
        val owner = feedbackOwner
        serviceStatusCoordinator.refresh()
        if (captureStartPending && stoppedWhileCaptureStartPending) {
            returnedWhileCaptureStartPending = true
        }
        val expectedSessionId = pendingReturnSessionId
        viewModelScope.launch {
            val read = captureCoordinator.reads.first()
            if (!read.available) {
                reportLifecycleFailure(expectedSessionId?.let { RetryOperation.Return(it, observedDepartureSessionId == it) }, HomeFeedback.RETURN_STORAGE, owner)
                return@launch
            }
            val sessionId = expectedSessionId ?: read.requireAvailable().activeSession?.id
            val observedDeparture = sessionId != null && observedDepartureSessionId == sessionId
            reconcileReturn(sessionId, observedDeparture, owner)
        }
    }

    private suspend fun beginCapture(operation: RetryOperation.Start, owner: Long): CaptureOperationResult {
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
            if (owner == feedbackOwner) lastOperation = operation.copy(previousSessionCancelled = true)
        }
        try {
            val result = captureCoordinator.requestStart()
            if (result is CaptureOperationResult.Success && result.sessionId != null) {
                val sessionId = result.sessionId
                pendingReturnSessionId = sessionId
                captureStartPending = false
                if (stoppedWhileCaptureStartPending) {
                    observedDepartureSessionId = sessionId
                    val departureOwner = feedbackOwner
                    viewModelScope.launch { persistDepartureAndMaybeReturn(sessionId, departureOwner) }
                }
                if (captureCoordinator.awaitConfigured(sessionId) &&
                    !stoppedWhileCaptureStartPending && !returnedWhileCaptureStartPending &&
                    observedDepartureSessionId != sessionId
                ) {
                    pendingNavigation.value = sessionId
                }
            }
            return result
        } finally {
            captureStartPending = false
        }
    }

    private suspend fun reconcileReturn(sessionId: Long?, observedDeparture: Boolean, owner: Long) {
        val retry = sessionId?.let { RetryOperation.Return(it, observedDeparture) }
        try {
            val result = captureCoordinator.reconcileReturn(sessionId, observedDeparture)
            when (result) {
                is CaptureOperationResult.Failure -> reportLifecycleFailure(retry, HomeFeedback.RETURN_STORAGE, owner)
                is CaptureOperationResult.Success -> {
                    clearLifecycleMarkers(result.sessionId)
                    if (owner == feedbackOwner && !operationState.value.inProgress) operationState.value = OperationUiState()
                    captureCoordinator.expireActive()
                }
                CaptureOperationResult.Stale -> captureCoordinator.expireActive()
                CaptureOperationResult.Pending -> Unit
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            reportLifecycleFailure(retry, HomeFeedback.RETURN_STORAGE, owner)
        }
    }

    private suspend fun persistDeparture(sessionId: Long, owner: Long) {
        try {
            val result = captureCoordinator.markLeftFlashSkip(sessionId)
            if (result is CaptureOperationResult.Failure) {
                reportLifecycleFailure(RetryOperation.Return(sessionId, true), HomeFeedback.DEPARTURE_STORAGE, owner)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            reportLifecycleFailure(RetryOperation.Return(sessionId, true), HomeFeedback.DEPARTURE_STORAGE, owner)
        }
    }

    private suspend fun persistDepartureAndMaybeReturn(sessionId: Long, owner: Long) {
        persistDeparture(sessionId, owner)
        if (returnedWhileCaptureStartPending) reconcileReturn(sessionId, true, owner)
    }

    private suspend fun reportLifecycleFailure(retry: RetryOperation?, feedback: HomeFeedback, owner: Long) {
        if (retry is RetryOperation.Return) {
            val read = captureCoordinator.reads.first()
            if (read.available && read.requireAvailable().activeSession?.id != retry.sessionId) return
        }
        if (owner != feedbackOwner) return
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
        val owner = ++feedbackOwner
        lastOperation = operation
        operationState.value = OperationUiState(inProgress = true)
        viewModelScope.launch {
            try {
                val result = when (operation) {
                    is RetryOperation.Toggle -> captureCoordinator.setAppEnabled(operation.id, operation.enabled)
                    is RetryOperation.Start -> beginCapture(operation, owner)
                    is RetryOperation.Finish -> captureCoordinator.finish(operation.sessionId)
                    is RetryOperation.Cancel -> captureCoordinator.cancel(operation.sessionId)
                    is RetryOperation.Clear -> captureCoordinator.clear(operation.request)
                    is RetryOperation.Return -> captureCoordinator.reconcileReturn(operation.sessionId, operation.observedDeparture)
                    is RetryOperation.CancelHomeFailure -> captureCoordinator.cancel(operation.sessionId)
                }
                // 事务仍完成自身工作；只有当前请求可以发布反馈或重新绑定重试。
                if (owner != feedbackOwner) return@launch
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
                    // 读取保存结果可能挂起，恢复后仍须核对反馈归属。
                    val savedTarget = if (operation is RetryOperation.Finish) {
                        captureCoordinator.state.first().samples.lastOrNull()?.targetAppId
                    } else null
                    if (owner != feedbackOwner) return@launch
                    operationState.value = OperationUiState(
                        feedback = if (operation is RetryOperation.CancelHomeFailure) HomeFeedback.GO_HOME_FAILED else lifecycleFeedback?.feedback,
                        retryAvailable = lifecycleFeedback?.retryAvailable == true,
                        savedTarget = savedTarget,
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
                if (owner != feedbackOwner) return@launch
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
        val storageAvailable: Boolean,
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
        data class Clear(val request: RuleDeletionRequest) : RetryOperation
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
