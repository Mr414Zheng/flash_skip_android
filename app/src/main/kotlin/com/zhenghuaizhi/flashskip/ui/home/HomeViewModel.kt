package com.zhenghuaizhi.flashskip.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.zhenghuaizhi.flashskip.application.ServiceStatusCoordinator
import com.zhenghuaizhi.flashskip.application.rulecapture.RuleCaptureCoordinator
import com.zhenghuaizhi.flashskip.domain.model.ActionOutcome
import com.zhenghuaizhi.flashskip.domain.model.CapturePhase
import com.zhenghuaizhi.flashskip.domain.model.RuleAvailability
import com.zhenghuaizhi.flashskip.domain.model.ServiceStatus
import com.zhenghuaizhi.flashskip.domain.model.TargetApp
import com.zhenghuaizhi.flashskip.domain.model.TargetAppId
import com.zhenghuaizhi.flashskip.domain.ports.FlashSkipStore
import com.zhenghuaizhi.flashskip.domain.rulecapture.RuleCandidateAnalyzer
import com.zhenghuaizhi.flashskip.domain.rules.RuleCatalog
import kotlinx.coroutines.flow.MutableSharedFlow
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

    val uiState = combine(
        store.preferences,
        serviceStatusCoordinator.status,
        captureCoordinator.state,
        ruleCatalog.snapshot,
    ) { preferences, serviceStatus, captureState, catalogSnapshot ->
        val targets = captureState.samples
            .groupBy { it.targetAppId }
            .map { (targetAppId, samples) ->
                val latest = samples.maxBy { it.capturedAtEpochMs }
                val rules = catalogSnapshot.rulesForTarget(targetAppId)
                val analysis = RuleCandidateAnalyzer.analyze(
                    samples = captureState.samples,
                    targetAppId = targetAppId,
                )
                val target = TargetApp(
                    id = targetAppId,
                    displayName = latest.targetAppName,
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
            .sortedByDescending { target ->
                captureState.samples.filter { it.targetAppId == target.target.id }
                    .maxOf { it.capturedAtEpochMs }
            }
        val activeSession = captureState.activeSession
        HomeUiState(
            serviceStatus = serviceStatus,
            enabledAppCount = targets.count {
                it.enabled && it.target.ruleAvailability is RuleAvailability.Available
            },
            targetApps = targets,
            capture = CaptureUiState(
                phase = activeSession?.phase,
                failure = activeSession?.failure,
                canStart = serviceStatus == ServiceStatus.RUNNING &&
                    (activeSession == null || activeSession.phase == CapturePhase.FAILED),
            ),
            capturingAppName = activeSession?.targetAppName,
            records = preferences.records,
            latestSuccess = preferences.records.firstOrNull { it.outcome == ActionOutcome.CLOSED },
            hasCapturedRules = captureState.samples.isNotEmpty(),
            currentTimeEpochMs = System.currentTimeMillis(),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeUiState(),
    )

    fun setAppEnabled(targetAppId: TargetAppId, enabled: Boolean) {
        if (ruleCatalog.snapshot.value.rulesForTarget(targetAppId).isEmpty()) return
        viewModelScope.launch { store.setAppEnabled(targetAppId, enabled) }
    }

    fun startCapture() {
        if (serviceStatusCoordinator.status.value != ServiceStatus.RUNNING) return
        viewModelScope.launch {
            val sessionId = captureCoordinator.requestStart()
            if (captureCoordinator.awaitConfigured(sessionId)) {
                mutableEffects.emit(HomeEffect.GoHome)
            }
        }
    }

    fun finishCapture() {
        viewModelScope.launch {
            val session = captureCoordinator.state.first().activeSession?.takeIf {
                it.phase == CapturePhase.CAPTURED
            } ?: return@launch
            captureCoordinator.finish(session.id)
        }
    }

    fun cancelCapture() {
        viewModelScope.launch {
            val session = captureCoordinator.state.first().activeSession ?: return@launch
            captureCoordinator.cancel(session.id)
        }
    }

    fun clearCapturedRules(targetAppId: TargetAppId) {
        viewModelScope.launch { captureCoordinator.clearTarget(targetAppId) }
    }

    fun clearAllCapturedRules() {
        viewModelScope.launch { captureCoordinator.clearAll() }
    }

    fun refresh() {
        serviceStatusCoordinator.refresh()
        viewModelScope.launch { captureCoordinator.expireActive() }
    }

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
