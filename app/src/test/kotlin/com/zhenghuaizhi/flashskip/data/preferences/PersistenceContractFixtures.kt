package com.zhenghuaizhi.flashskip.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.zhenghuaizhi.flashskip.application.rulecapture.RuleCaptureCoordinator
import com.zhenghuaizhi.flashskip.domain.model.*
import com.zhenghuaizhi.flashskip.domain.ports.MonotonicClock
import com.zhenghuaizhi.flashskip.domain.ports.WallClock
import com.zhenghuaizhi.flashskip.domain.rules.BuiltinRuleCatalog
import com.zhenghuaizhi.flashskip.domain.rules.CompositeRuleCatalog
import com.zhenghuaizhi.flashskip.testing.TARGET
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.*

internal val CAPTURE_KEY = stringPreferencesKey("rule_capture_state_v2")
internal val RECORDS_KEY = stringPreferencesKey("action_records_v2")
internal val ENABLED_KEY = stringPreferencesKey("enabled_packages_v2")

internal fun actionRecord(id: Long = 1) = ActionRecord(
    timestampEpochMs = id,
    targetAppId = TARGET,
    ruleId = "rule-$id",
    method = ActionMethod.NODE_CLICK,
    outcome = ActionOutcome.CLOSED,
)

/** 仅在真实 DataStore 读写边界注入故障，不实现解码、过滤或管理决策。 */
internal class BoundaryDataStore(val delegate: DataStore<Preferences>) : DataStore<Preferences> {
    var beforeRead: suspend () -> Unit = {}
    var beforeUpdate: suspend () -> Unit = {}
    var afterUpdate: suspend () -> Unit = {}
    var attempts = 0
    override val data: Flow<Preferences> = flow {
        beforeRead()
        emitAll(delegate.data)
    }
    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
        attempts++
        beforeUpdate()
        val value = delegate.updateData(transform)
        afterUpdate()
        return value
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
internal class PersistenceStores(testScope: TestScope, directory: File) {
    val job = SupervisorJob()
    private val dispatcher = StandardTestDispatcher(testScope.testScheduler)
    val scope = CoroutineScope(job + dispatcher)
    val captureData = BoundaryDataStore(PreferenceDataStoreFactory.create(scope = scope) {
        File(directory, "capture.preferences_pb")
    })
    val flashData = BoundaryDataStore(PreferenceDataStoreFactory.create(scope = scope) {
        File(directory, "flash.preferences_pb")
    })
    lateinit var capture: PreferencesRuleCaptureStore
    lateinit var flash: PreferencesFlashSkipStore
    lateinit var catalog: CompositeRuleCatalog
    lateinit var coordinator: RuleCaptureCoordinator
    private val clock = object : MonotonicClock, WallClock {
        override fun elapsedRealtimeMs() = testScope.testScheduler.currentTime
        override fun currentTimeMillis() = 1_000_000L + testScope.testScheduler.currentTime
    }

    suspend fun seed(captureRaw: String? = null, recordsRaw: String? = null, enabled: String? = null) {
        captureData.delegate.edit { if (captureRaw != null) it[CAPTURE_KEY] = captureRaw }
        flashData.delegate.edit {
            if (recordsRaw != null) it[RECORDS_KEY] = recordsRaw
            if (enabled != null) it[ENABLED_KEY] = enabled
        }
    }

    fun start() {
        capture = PreferencesRuleCaptureStore(captureData, scope)
        flash = PreferencesFlashSkipStore(flashData, scope)
        catalog = CompositeRuleCatalog(BuiltinRuleCatalog(), capture, scope, dispatcher)
        coordinator = RuleCaptureCoordinator(capture, flash, clock, clock, scope, catalog)
    }

    suspend fun close() = job.cancelAndJoin()
}
