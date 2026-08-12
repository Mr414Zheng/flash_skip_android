package com.zhenghuaizhi.flashskip.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.zhenghuaizhi.flashskip.domain.model.ActionRecord
import com.zhenghuaizhi.flashskip.domain.model.TargetAppId
import com.zhenghuaizhi.flashskip.domain.ports.FlashSkipPreferences
import com.zhenghuaizhi.flashskip.domain.ports.FlashSkipStore
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private val Context.flashSkipDataStore by preferencesDataStore(name = "flashskip_preferences")

class PreferencesFlashSkipStore(
    private val context: Context,
    cleanupScope: CoroutineScope,
) : FlashSkipStore {
    init {
        cleanupScope.launch {
            context.flashSkipDataStore.edit { values ->
                values.remove(LEGACY_QQ_MUSIC_ENABLED)
                values.remove(LEGACY_TAOBAO_ENABLED)
                values.remove(LEGACY_JD_ENABLED)
                values.remove(LEGACY_RECORDS)
            }
        }
    }

    override val preferences: Flow<FlashSkipPreferences> = context.flashSkipDataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map(::toDomain)

    override suspend fun setAppEnabled(targetAppId: TargetAppId, enabled: Boolean) {
        context.flashSkipDataStore.edit { values ->
            val enabledPackages = decodeEnabledPackages(values[ENABLED_PACKAGES]).toMutableSet()
            if (enabled) enabledPackages += targetAppId.packageName else enabledPackages -= targetAppId.packageName
            values[ENABLED_PACKAGES] = enabledPackages.sorted().joinToString("\n")
        }
    }

    override suspend fun isAppEnabled(targetAppId: TargetAppId): Boolean =
        preferences.first().enabledApps[targetAppId] == true

    override suspend fun appendRecord(record: ActionRecord) {
        context.flashSkipDataStore.edit { values ->
            val existing = ActionRecordCodec.decodeOrEmpty(values[RECORDS])
            values[RECORDS] = ActionRecordCodec.encode(
                (listOf(record) + existing).take(ActionRecordCodec.MAX_RECORDS),
            )
        }
    }

    private fun toDomain(values: androidx.datastore.preferences.core.Preferences): FlashSkipPreferences = FlashSkipPreferences(
        enabledApps = decodeEnabledPackages(values[ENABLED_PACKAGES])
            .mapNotNull { packageName -> runCatching { TargetAppId(packageName) }.getOrNull() }
            .associateWith { true },
        records = ActionRecordCodec.decodeOrEmpty(values[RECORDS]),
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
