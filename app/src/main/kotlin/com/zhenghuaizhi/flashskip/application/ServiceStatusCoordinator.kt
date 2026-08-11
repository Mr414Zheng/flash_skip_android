package com.zhenghuaizhi.flashskip.application

import com.zhenghuaizhi.flashskip.domain.model.ServiceStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch

fun interface SystemServiceStatusReader {
    fun isFlashSkipEnabled(): Boolean
}

object ServiceStatusResolver {
    fun resolve(systemEnabled: Boolean, connected: Boolean, confirming: Boolean): ServiceStatus =
        when {
            !systemEnabled -> ServiceStatus.NOT_ENABLED
            connected -> ServiceStatus.RUNNING
            confirming -> ServiceStatus.CONFIRMING
            else -> ServiceStatus.ABNORMAL
        }
}

class ServiceStatusCoordinator(
    private val statusReader: SystemServiceStatusReader,
    private val connectionRegistry: ServiceConnectionRegistry,
    private val scope: CoroutineScope,
    private val confirmationMs: Long = 2_000L,
) {
    private val systemEnabled = MutableStateFlow(false)
    private val confirming = MutableStateFlow(false)
    private var confirmationJob: Job? = null

    val status: StateFlow<ServiceStatus> = combine(
        systemEnabled,
        connectionRegistry.connected,
        confirming,
        ServiceStatusResolver::resolve,
    ).stateIn(scope, SharingStarted.Eagerly, ServiceStatus.NOT_ENABLED)

    init {
        scope.launch {
            connectionRegistry.connected.collect { connected ->
                if (connected) {
                    confirmationJob?.cancel()
                    confirming.value = false
                }
            }
        }
    }

    fun refresh() {
        val enabled = statusReader.isFlashSkipEnabled()
        systemEnabled.value = enabled
        confirmationJob?.cancel()
        if (!enabled || connectionRegistry.connected.value) {
            confirming.value = false
            return
        }
        confirming.value = true
        confirmationJob = scope.launch {
            delay(confirmationMs)
            confirming.value = false
        }
    }
}
