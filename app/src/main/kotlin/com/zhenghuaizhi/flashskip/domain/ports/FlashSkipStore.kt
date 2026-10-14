package com.zhenghuaizhi.flashskip.domain.ports

import com.zhenghuaizhi.flashskip.domain.model.ActionRecord
import com.zhenghuaizhi.flashskip.domain.model.TargetAppId
import kotlinx.coroutines.flow.Flow

data class FlashSkipPreferences(
    val enabledApps: Map<TargetAppId, Boolean> = emptyMap(),
    val records: List<ActionRecord> = emptyList(),
)

interface FlashSkipStore {
    val preferences: Flow<FlashSkipPreferences>

    /** 长期订阅包含不可用状态，不把读取失败当作所有开关已关闭。 */
    val reads: Flow<StorageRead<FlashSkipPreferences>> get() = preferences.observeStorageReads()

    suspend fun setAppEnabled(targetAppId: TargetAppId, enabled: Boolean)

    suspend fun isAppEnabled(targetAppId: TargetAppId): Boolean

    suspend fun appendRecord(record: ActionRecord)
}
