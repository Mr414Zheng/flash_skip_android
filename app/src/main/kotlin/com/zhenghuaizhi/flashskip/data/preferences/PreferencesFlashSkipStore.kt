package com.zhenghuaizhi.flashskip.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.zhenghuaizhi.flashskip.domain.model.ActionRecord
import com.zhenghuaizhi.flashskip.domain.model.TargetAppId
import com.zhenghuaizhi.flashskip.domain.ports.FlashSkipPreferences
import com.zhenghuaizhi.flashskip.domain.ports.FlashSkipStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.flashSkipDataStore by preferencesDataStore(name = "flashskip_preferences")

class PreferencesFlashSkipStore(
    private val context: Context,
) : FlashSkipStore {
    override val preferences: Flow<FlashSkipPreferences> = context.flashSkipDataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map(::toDomain)

    override suspend fun setAppEnabled(targetAppId: TargetAppId, enabled: Boolean) {
        context.flashSkipDataStore.edit { values ->
            values[keyFor(targetAppId)] = enabled
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

    private fun toDomain(values: Preferences): FlashSkipPreferences = FlashSkipPreferences(
        enabledApps = TargetAppId.entries.associateWith { values[keyFor(it)] ?: false },
        records = ActionRecordCodec.decodeOrEmpty(values[RECORDS]),
    )

    private fun keyFor(targetAppId: TargetAppId): Preferences.Key<Boolean> = when (targetAppId) {
        TargetAppId.QQ_MUSIC -> QQ_MUSIC_ENABLED
        TargetAppId.TAOBAO -> TAOBAO_ENABLED
        TargetAppId.JD -> JD_ENABLED
    }

    private companion object {
        val QQ_MUSIC_ENABLED = booleanPreferencesKey("qq_music_enabled")
        val TAOBAO_ENABLED = booleanPreferencesKey("taobao_enabled")
        val JD_ENABLED = booleanPreferencesKey("jd_enabled")
        val RECORDS = stringPreferencesKey("action_records_v1")
    }
}
