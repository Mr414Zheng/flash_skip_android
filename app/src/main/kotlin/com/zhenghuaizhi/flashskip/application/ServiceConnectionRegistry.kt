package com.zhenghuaizhi.flashskip.application

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 连接身份与恢复就绪分开；旧服务的迟到清理不能撤销新服务的状态。 */
internal data class ServiceAvailability(
    val cycle: Long = 0,
    val connected: Boolean = false,
    val ready: Boolean = false,
)

class ServiceConnectionRegistry {
    private val mutableConnected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = mutableConnected.asStateFlow()
    private val mutableAvailability = MutableStateFlow(ServiceAvailability())
    internal val availability = mutableAvailability.asStateFlow()

    /** 兼容已经完成恢复的连接发布者；真实服务使用分阶段的内部入口。 */
    @Synchronized
    fun setConnected(connected: Boolean) {
        val previous = mutableAvailability.value
        mutableAvailability.value = ServiceAvailability(
            cycle = previous.cycle + if (previous.connected != connected) 1 else 0,
            connected = connected,
            ready = connected,
        )
        mutableConnected.value = connected
    }

    @Synchronized
    internal fun beginConnection(): Long {
        val cycle = mutableAvailability.value.cycle + 1
        mutableAvailability.value = ServiceAvailability(cycle, connected = true)
        mutableConnected.value = true
        return cycle
    }

    @Synchronized
    internal fun setReady(cycle: Long, ready: Boolean) {
        val current = mutableAvailability.value
        if (current.cycle == cycle && current.connected) mutableAvailability.value = current.copy(ready = ready)
    }

    @Synchronized
    internal fun endConnection(cycle: Long) {
        if (mutableAvailability.value.cycle != cycle) return
        mutableAvailability.value = ServiceAvailability(cycle + 1)
        mutableConnected.value = false
    }
}
