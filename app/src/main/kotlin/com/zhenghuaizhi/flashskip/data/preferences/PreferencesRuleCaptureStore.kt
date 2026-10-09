package com.zhenghuaizhi.flashskip.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.zhenghuaizhi.flashskip.domain.model.RuleCaptureState
import com.zhenghuaizhi.flashskip.domain.ports.RuleCaptureStore
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private val Context.ruleCaptureDataStore by preferencesDataStore(name = "flashskip_rule_capture")

class PreferencesRuleCaptureStore internal constructor(
    private val dataStore: DataStore<Preferences>,
    cleanupScope: CoroutineScope,
) : RuleCaptureStore {
    constructor(context: Context, cleanupScope: CoroutineScope) :
        this(context.ruleCaptureDataStore, cleanupScope)

    init {
        cleanupScope.launch {
            dataStore.edit { values -> values.remove(LEGACY_STATE) }
        }
    }

    override val state: Flow<RuleCaptureState> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { values -> RuleCaptureCodec.decodeOrEmpty(values[STATE]) }

    override suspend fun update(transform: (RuleCaptureState) -> RuleCaptureState) {
        dataStore.edit { values ->
            val current = RuleCaptureCodec.decodeOrEmpty(values[STATE])
            values[STATE] = RuleCaptureCodec.encode(transform(current))
        }
    }

    private companion object {
        val LEGACY_STATE = stringPreferencesKey("rule_capture_state_v1")
        val STATE = stringPreferencesKey("rule_capture_state_v2")
    }
}
