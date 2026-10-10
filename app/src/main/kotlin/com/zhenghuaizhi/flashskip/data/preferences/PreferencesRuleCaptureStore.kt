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
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.zhenghuaizhi.flashskip.domain.model.RuleCaptureState
import com.zhenghuaizhi.flashskip.domain.ports.RuleCaptureStore
import com.zhenghuaizhi.flashskip.domain.rulecapture.CaptureRuleSafetyPolicy
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
    private val traceSink: RuleManagementTraceSink = RuleManagementTraceSink.None,
) : RuleCaptureStore {
    constructor(context: Context, cleanupScope: CoroutineScope, traceSink: RuleManagementTraceSink = RuleManagementTraceSink.None) :
        this(context.ruleCaptureDataStore, cleanupScope, traceSink)

    init {
        cleanupScope.launch {
            try {
                dataStore.edit { values ->
                    values.remove(LEGACY_STATE)
                    val raw = values[STATE] ?: return@edit
                    val current = RuleCaptureCodec.decodeOrEmpty(raw)
                    val sanitized = CaptureRuleSafetyPolicy.sanitize(current)
                    if (sanitized != current) values[STATE] = RuleCaptureCodec.encode(sanitized)
                }
            } catch (_: IOException) {
                // 清理失败不终止应用；读取仍执行安全过滤，后续写入可由用户重试。
                traceSink.recordSafely { RuleManagementTraceEvent(RuleManagementOperation.INITIALIZE_CAPTURE, RuleManagementOutcome.STORAGE_FAILURE) }
            }
        }
    }

    override val state: Flow<RuleCaptureState> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { values -> CaptureRuleSafetyPolicy.sanitize(RuleCaptureCodec.decodeOrEmpty(values[STATE])) }

    override suspend fun update(transform: (RuleCaptureState) -> RuleCaptureState) {
        dataStore.edit { values ->
            val current = CaptureRuleSafetyPolicy.sanitize(RuleCaptureCodec.decodeOrEmpty(values[STATE]))
            values[STATE] = RuleCaptureCodec.encode(CaptureRuleSafetyPolicy.sanitize(transform(current)))
        }
    }

    private companion object {
        val LEGACY_STATE = stringPreferencesKey("rule_capture_state_v1")
        val STATE = stringPreferencesKey("rule_capture_state_v2")
    }
}
