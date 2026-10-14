package com.zhenghuaizhi.flashskip.data.preferences

import android.content.Context
import com.zhenghuaizhi.flashskip.application.rulecapture.RuleManagementOperation
import com.zhenghuaizhi.flashskip.application.rulecapture.RuleManagementOutcome
import com.zhenghuaizhi.flashskip.application.rulecapture.RuleManagementTraceEvent
import com.zhenghuaizhi.flashskip.application.rulecapture.RuleManagementTraceSink
import com.zhenghuaizhi.flashskip.application.rulecapture.recordSafely
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.zhenghuaizhi.flashskip.domain.model.ActionRecord
import com.zhenghuaizhi.flashskip.domain.model.TargetAppId
import com.zhenghuaizhi.flashskip.domain.ports.FlashSkipPreferences
import com.zhenghuaizhi.flashskip.domain.ports.FlashSkipStore
import com.zhenghuaizhi.flashskip.domain.ports.StorageRead
import com.zhenghuaizhi.flashskip.domain.ports.retryStorageReads
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private val Context.flashSkipDataStore by preferencesDataStore(name = "flashskip_preferences")

class PreferencesFlashSkipStore internal constructor(
    private val dataStore: DataStore<Preferences>,
    cleanupScope: CoroutineScope,
    private val traceSink: RuleManagementTraceSink = RuleManagementTraceSink.None,
) : FlashSkipStore {
    constructor(context: Context, cleanupScope: CoroutineScope, traceSink: RuleManagementTraceSink = RuleManagementTraceSink.None) :
        this(context.flashSkipDataStore, cleanupScope, traceSink)

    init {
        cleanupScope.launch {
            try {
                dataStore.edit { values ->
                    values.remove(LEGACY_QQ_MUSIC_ENABLED)
                    values.remove(LEGACY_TAOBAO_ENABLED)
                    values.remove(LEGACY_JD_ENABLED)
                    values.remove(LEGACY_RECORDS)
                }
            } catch (_: IOException) {
                // 清理失败不终止应用；读取仍执行安全过滤，后续写入可由用户重试。
                traceSink.recordSafely { RuleManagementTraceEvent(RuleManagementOperation.INITIALIZE_PREFERENCES, RuleManagementOutcome.STORAGE_FAILURE) }
            }
        }
    }

    override val reads: Flow<StorageRead<FlashSkipPreferences>> = dataStore.data
        .catch { error ->
            if (error is IOException) {
                traceSink.recordSafely { RuleManagementTraceEvent(RuleManagementOperation.READ_PREFERENCES, RuleManagementOutcome.STORAGE_FAILURE) }
            }
            throw error
        }
        .map { values ->
            val records = ActionRecordCodec.decode(values[RECORDS])
            StorageRead(toDomain(values, records.value.orEmpty()), records.failure)
        }
        .retryStorageReads()

    override val preferences: Flow<FlashSkipPreferences> = reads.map { it.requireAvailable() }

    override suspend fun setAppEnabled(targetAppId: TargetAppId, enabled: Boolean) {
        dataStore.edit { values ->
            // 同一事务内检查版本，未知或坏整包不得被普通管理操作继续改写。
            ActionRecordCodec.decode(values[RECORDS]).requireAvailable()
            val enabledPackages = decodeEnabledPackages(values[ENABLED_PACKAGES]).toMutableSet()
            if (enabled) enabledPackages += targetAppId.packageName else enabledPackages -= targetAppId.packageName
            values[ENABLED_PACKAGES] = enabledPackages.sorted().joinToString("\n")
        }
    }

    override suspend fun isAppEnabled(targetAppId: TargetAppId): Boolean = try {
        val values = dataStore.data.first()
        // 开关不能绕过同一偏好库的未知版本或不可用状态。
        ActionRecordCodec.decode(values[RECORDS]).requireAvailable()
        targetAppId.packageName in decodeEnabledPackages(values[ENABLED_PACKAGES])
    } catch (_: IOException) {
        false
    }

    override suspend fun appendRecord(record: ActionRecord) {
        dataStore.edit { values ->
            val existing = ActionRecordCodec.decode(values[RECORDS]).requireAvailable()
            values[RECORDS] = ActionRecordCodec.encode(
                (listOf(record) + existing).take(ActionRecordCodec.MAX_RECORDS),
            )
        }
    }

    private fun toDomain(values: Preferences, records: List<ActionRecord>): FlashSkipPreferences = FlashSkipPreferences(
        enabledApps = decodeEnabledPackages(values[ENABLED_PACKAGES])
            .mapNotNull { packageName -> runCatching { TargetAppId(packageName) }.getOrNull() }
            .associateWith { true },
        records = records,
    )

    private fun decodeEnabledPackages(raw: String?): Set<String> = raw
        ?.lineSequence()
        ?.map(String::trim)
        ?.filter(String::isNotEmpty)
        ?.toSet()
        .orEmpty()

    private companion object {
        val ENABLED_PACKAGES = stringPreferencesKey("enabled_packages_v2")
        val RECORDS = stringPreferencesKey("action_records_v2")
        val LEGACY_QQ_MUSIC_ENABLED = booleanPreferencesKey("qq_music_enabled")
        val LEGACY_TAOBAO_ENABLED = booleanPreferencesKey("taobao_enabled")
        val LEGACY_JD_ENABLED = booleanPreferencesKey("jd_enabled")
        val LEGACY_RECORDS = stringPreferencesKey("action_records_v1")
    }
}
