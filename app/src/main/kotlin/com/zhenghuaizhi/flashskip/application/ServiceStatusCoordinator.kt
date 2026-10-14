package com.zhenghuaizhi.flashskip.application

import com.zhenghuaizhi.flashskip.domain.model.ServiceStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private var systemEnabled = false
    private var systemReadFailed = false
    private var confirmationCycle: Long? = null
    private var confirming = false
    private var confirmationJob: Job? = null
    private val mutableStatus = MutableStateFlow(ServiceStatus.NOT_ENABLED)
    val status: StateFlow<ServiceStatus> = mutableStatus.asStateFlow()

    internal val isReady: Boolean
        get() = systemEnabled && !systemReadFailed && connectionRegistry.availability.value.ready

    init {
        scope.launch { connectionRegistry.availability.collect { update() } }
    }

    fun refresh() {
        try {
            systemEnabled = statusReader.isFlashSkipEnabled()
            systemReadFailed = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            systemReadFailed = true
        }
        update()
    }

    private fun update() {
        val availability = connectionRegistry.availability.value
        if (!systemEnabled) {
            confirmationJob?.cancel()
            confirmationCycle = null
            confirming = false
        } else if (availability.ready) {
            confirmationJob?.cancel()
            confirmationCycle = availability.cycle
            confirming = false
        } else if (confirmationCycle != availability.cycle) {
            confirmationJob?.cancel()
            confirmationCycle = availability.cycle
            confirming = true
            // 截止从本轮第一次观察开始；刷新和恢复失败不能重新开启同一轮。
            confirmationJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                delay(confirmationMs)
                if (confirmationCycle == availability.cycle) {
                    confirming = false
                    publish()
                }
            }
        }
        publish()
    }

    private fun publish() {
        mutableStatus.value = if (systemReadFailed) ServiceStatus.ABNORMAL else
            ServiceStatusResolver.resolve(systemEnabled, isReady, confirming)
    }
}
