package com.zhenghuaizhi.flashskip.application

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ServiceConnectionRegistry {
    private val mutableConnected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = mutableConnected.asStateFlow()

    fun setConnected(connected: Boolean) {
        mutableConnected.value = connected
    }
}
