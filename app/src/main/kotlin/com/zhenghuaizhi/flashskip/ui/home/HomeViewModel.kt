package com.zhenghuaizhi.flashskip.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.zhenghuaizhi.flashskip.application.ServiceStatusCoordinator
import com.zhenghuaizhi.flashskip.application.rulecapture.RuleCaptureCoordinator
import com.zhenghuaizhi.flashskip.domain.model.ActionOutcome
import com.zhenghuaizhi.flashskip.domain.model.AppVersion
import com.zhenghuaizhi.flashskip.domain.model.CapturePhase
import com.zhenghuaizhi.flashskip.domain.model.RuleAvailability
import com.zhenghuaizhi.flashskip.domain.model.ServiceStatus
import com.zhenghuaizhi.flashskip.domain.model.TargetAppId
import com.zhenghuaizhi.flashskip.domain.ports.FlashSkipStore
import com.zhenghuaizhi.flashskip.domain.ports.TargetAppVersionReader
import com.zhenghuaizhi.flashskip.domain.rulecapture.RuleCandidateAnalyzer
import com.zhenghuaizhi.flashskip.domain.rules.RuleCatalog
import com.zhenghuaizhi.flashskip.domain.rules.TargetAppRegistry
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
    private val targetAppVersionReader: TargetAppVersionReader,
) : ViewModel() {
    private val installedVersions = MutableStateFlow(targetAppVersionReader.installedVersions())
    private val mutableEffects = MutableSharedFlow<HomeEffect>(extraBufferCapacity = 1)
    val effects = mutableEffects.asSharedFlow()

    val uiState = combine(
        store.preferences,
        serviceStatusCoordinator.status,
        captureCoordinator.state,
        ruleCatalog.snapshot,
        installedVersions,
    ) { preferences, serviceStatus, captureState, catalogSnapshot, versions ->
        val activeSession = captureState.activeSession
        val targets = TargetAppRegistry.targets.map { baseTarget ->
            val version = versions[baseTarget.id]
            val ruleCount = catalogSnapshot.rulesForTarget(baseTarget.id, version).size
            val target = baseTarget.copy(
                ruleAvailability = if (ruleCount > 0) {
                    RuleAvailability.Available(ruleCount)
                } else {
                    RuleAvailability.Unavailable
                },
            )
            val analysis = RuleCandidateAnalyzer.analyze(
                samples = captureState.samples,
                targetAppId = target.id,
                currentVersion = version,
            )
            val hasAnySamples = captureState.samples.any { it.targetAppId == target.id }
            val targetSession = activeSession?.takeIf { it.targetAppId == target.id }
            targetAppUiState(
                target = target,
                enabled = preferences.enabledApps[target.id] == true,
                serviceStatus = serviceStatus,
                installedVersion = version,
                capture = CaptureUiState(
                    phase = targetSession?.phase,
                    failure = targetSession?.failure,
                    canStart = serviceStatus == ServiceStatus.RUNNING &&
                        version != null &&
                        (activeSession == null || targetSession?.phase == CapturePhase.FAILED),
                    blockedByOtherApp = activeSession != null && targetSession == null,
                ),
                ruleCard = if (hasAnySamples) {
                    RuleCardUiState(
                        status = analysis.status,
                        sampleCount = analysis.currentVersionSampleCount,
                        stableSampleCount = analysis.stableSampleCount,
                        uniqueEvidenceCount = analysis.uniqueEvidenceCount,
                        version = analysis.appVersion,
                        selector = analysis.primarySelector,
                        activities = analysis.allowedActivities,
                    )
                } else {
                    null
                },
            )
        }
        HomeUiState(
            serviceStatus = serviceStatus,
            enabledAppCount = targets.count {
                it.enabled && it.target.ruleAvailability is RuleAvailability.Available
            },
            targetApps = targets,
            records = preferences.records,
            latestSuccess = preferences.records.firstOrNull {
                it.outcome == ActionOutcome.CLOSED
            },
            hasCapturedRules = captureState.samples.isNotEmpty(),
            currentTimeEpochMs = System.currentTimeMillis(),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeUiState(
            targetApps = TargetAppRegistry.targets.map {
                targetAppUiState(it, enabled = false, serviceStatus = ServiceStatus.NOT_ENABLED)
            },
        ),
    )

    fun setAppEnabled(targetAppId: TargetAppId, enabled: Boolean) {
        val version = installedVersions.value[targetAppId]
        if (ruleCatalog.snapshot.value.rulesForTarget(targetAppId, version).isEmpty()) return
        viewModelScope.launch { store.setAppEnabled(targetAppId, enabled) }
    }

    fun startCapture(targetAppId: TargetAppId) {
        if (serviceStatusCoordinator.status.value != ServiceStatus.RUNNING) return
        val version = targetAppVersionReader.versionFor(targetAppId) ?: return
        installedVersions.value = installedVersions.value + (targetAppId to version)
        viewModelScope.launch {
            val sessionId = captureCoordinator.requestStart(targetAppId, version)
            if (captureCoordinator.awaitConfigured(sessionId)) {
                mutableEffects.emit(HomeEffect.GoHome)
            }
        }
    }

    fun finishCapture(targetAppId: TargetAppId) {
        viewModelScope.launch {
            val session = captureCoordinator.state.first().activeSession?.takeIf {
                it.targetAppId == targetAppId && it.phase == CapturePhase.CAPTURED
            } ?: return@launch
            captureCoordinator.finish(session.id)
            refreshInstalledVersions()
        }
    }

    fun cancelCapture(targetAppId: TargetAppId) {
        viewModelScope.launch {
            val session = captureCoordinator.state.first().activeSession?.takeIf {
                it.targetAppId == targetAppId
            } ?: return@launch
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
        refreshInstalledVersions()
        viewModelScope.launch { captureCoordinator.expireActive() }
    }

    private fun refreshInstalledVersions() {
        val versions = targetAppVersionReader.installedVersions()
        installedVersions.value = versions
        viewModelScope.launch {
            val preferences = store.preferences.first()
            TargetAppId.entries.forEach { targetAppId ->
                if (preferences.enabledApps[targetAppId] == true &&
                    ruleCatalog.snapshot.value.rulesForTarget(
                        targetAppId,
                        versions[targetAppId],
                    ).isEmpty()
                ) {
                    store.setAppEnabled(targetAppId, false)
                }
            }
        }
    }

    sealed interface HomeEffect {
        data object GoHome : HomeEffect
    }

    class Factory(
        private val store: FlashSkipStore,
        private val ruleCatalog: RuleCatalog,
        private val serviceStatusCoordinator: ServiceStatusCoordinator,
        private val captureCoordinator: RuleCaptureCoordinator,
        private val targetAppVersionReader: TargetAppVersionReader,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(HomeViewModel::class.java))
            return HomeViewModel(
                store,
                ruleCatalog,
                serviceStatusCoordinator,
                captureCoordinator,
                targetAppVersionReader,
            ) as T
        }
    }
}
