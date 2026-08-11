package com.zhenghuaizhi.flashskip.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.zhenghuaizhi.flashskip.application.ServiceStatusCoordinator
import com.zhenghuaizhi.flashskip.domain.model.ActionOutcome
import com.zhenghuaizhi.flashskip.domain.model.RuleAvailability
import com.zhenghuaizhi.flashskip.domain.model.ServiceStatus
import com.zhenghuaizhi.flashskip.domain.model.TargetAppId
import com.zhenghuaizhi.flashskip.domain.ports.FlashSkipStore
import com.zhenghuaizhi.flashskip.domain.rules.BuiltinRuleCatalog
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(
    private val store: FlashSkipStore,
    private val ruleCatalog: BuiltinRuleCatalog,
    private val serviceStatusCoordinator: ServiceStatusCoordinator,
) : ViewModel() {
    val uiState = combine(
        store.preferences,
        serviceStatusCoordinator.status,
    ) { preferences, serviceStatus ->
        val targets = ruleCatalog.targets.map { target ->
            targetAppUiState(
                target = target,
                enabled = preferences.enabledApps[target.id] == true,
                serviceStatus = serviceStatus,
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
            currentTimeEpochMs = System.currentTimeMillis(),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeUiState(
            targetApps = ruleCatalog.targets.map {
                targetAppUiState(it, enabled = false, serviceStatus = ServiceStatus.NOT_ENABLED)
            },
        ),
    )

    fun setAppEnabled(targetAppId: TargetAppId, enabled: Boolean) {
        val target = ruleCatalog.targets.find { it.id == targetAppId } ?: return
        if (target.ruleAvailability !is RuleAvailability.Available) return
        viewModelScope.launch { store.setAppEnabled(targetAppId, enabled) }
    }

    fun refreshServiceStatus() {
        serviceStatusCoordinator.refresh()
    }

    class Factory(
        private val store: FlashSkipStore,
        private val ruleCatalog: BuiltinRuleCatalog,
        private val serviceStatusCoordinator: ServiceStatusCoordinator,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(HomeViewModel::class.java))
            return HomeViewModel(store, ruleCatalog, serviceStatusCoordinator) as T
        }
    }
}
