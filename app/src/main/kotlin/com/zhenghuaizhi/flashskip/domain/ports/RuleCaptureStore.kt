package com.zhenghuaizhi.flashskip.domain.ports

import com.zhenghuaizhi.flashskip.domain.model.AppVersion
import com.zhenghuaizhi.flashskip.domain.model.RuleCaptureState
import kotlinx.coroutines.flow.Flow

interface RuleCaptureStore {
    val state: Flow<RuleCaptureState>

    /** 长期订阅包含不可用状态；命令读取仍使用 state 并处理 IOException。 */
    val reads: Flow<StorageRead<RuleCaptureState>> get() = state.observeStorageReads()

    suspend fun update(transform: (RuleCaptureState) -> RuleCaptureState)
}

interface TargetAppVersionReader {
    fun versionFor(packageName: String): AppVersion?

    fun displayNameFor(packageName: String): String?

    fun verifiedActivity(packageName: String, className: String?): String?
}
