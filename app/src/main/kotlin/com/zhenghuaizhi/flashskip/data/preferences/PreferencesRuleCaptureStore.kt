package com.zhenghuaizhi.flashskip.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.zhenghuaizhi.flashskip.domain.model.RuleCaptureState
import com.zhenghuaizhi.flashskip.domain.ports.RuleCaptureStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.ruleCaptureDataStore by preferencesDataStore(name = "flashskip_rule_capture")

class PreferencesRuleCaptureStore(
    private val context: Context,
) : RuleCaptureStore {
    override val state: Flow<RuleCaptureState> = context.ruleCaptureDataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { values -> RuleCaptureCodec.decodeOrEmpty(values[STATE]) }

    override suspend fun update(transform: (RuleCaptureState) -> RuleCaptureState) {
        context.ruleCaptureDataStore.edit { values ->
            val current = RuleCaptureCodec.decodeOrEmpty(values[STATE])
            values[STATE] = RuleCaptureCodec.encode(transform(current))
        }
    }

    private companion object {
        val STATE = stringPreferencesKey("rule_capture_state_v1")
    }
}
