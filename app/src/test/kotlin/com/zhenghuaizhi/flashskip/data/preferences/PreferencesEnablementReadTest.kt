package com.zhenghuaizhi.flashskip.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.zhenghuaizhi.flashskip.domain.model.TargetAppId
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PreferencesEnablementReadTest {
    @Test
    fun repeatedEnablementChecksOnlyReadEnablementKeyAndNeverDecodeRecords() = runTest {
        val dataStore = TrackingDataStore(
            preferencesOf(
                ENABLED_PACKAGES to "\n org.example.reader \norg.example.other\n",
                RECORDS to "records-must-never-be-read-by-an-enablement-check",
            ),
        )
        val store = PreferencesFlashSkipStore(dataStore, backgroundScope)
        runCurrent()
        dataStore.readKeys.clear()

        repeat(100) { assertTrue(store.isAppEnabled(TARGET)) }

        assertEquals(List(100) { ENABLED_PACKAGES.name }, dataStore.readKeys)
    }

    @Test
    fun writesAndExternalStoreUpdatesAreVisibleOnNextCheck() = runTest {
        val dataStore = TrackingDataStore(emptyPreferences())
        val store = PreferencesFlashSkipStore(dataStore, backgroundScope)
        runCurrent()

        assertFalse(store.isAppEnabled(TARGET))
        store.setAppEnabled(TARGET, true)
        assertTrue(store.isAppEnabled(TARGET))
        store.setAppEnabled(TARGET, false)
        assertFalse(store.isAppEnabled(TARGET))

        dataStore.replace(preferencesOf(ENABLED_PACKAGES to TARGET.packageName))
        assertTrue(store.isAppEnabled(TARGET))
        dataStore.replace(emptyPreferences())
        assertFalse(store.isAppEnabled(TARGET))
    }

    @Test
    fun ioReadFailureSafelyDisablesExecution() = runTest {
        val store = PreferencesFlashSkipStore(
            ReadOnlyDataStore(flow { throw IOException("Storage unavailable") }),
            backgroundScope,
        )

        assertFalse(store.isAppEnabled(TARGET))
    }

    @Test
    fun upstreamCancellationIsNotConvertedToDisabled() = runTest {
        val store = PreferencesFlashSkipStore(
            ReadOnlyDataStore(flow { throw CancellationException("Read cancelled") }),
            backgroundScope,
        )

        val failure = runCatching { store.isAppEnabled(TARGET) }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertEquals("Read cancelled", failure?.message)
    }

    @Test
    fun callerCanCancelPendingDataStoreRead() = runTest {
        val store = PreferencesFlashSkipStore(
            ReadOnlyDataStore(MutableSharedFlow()),
            backgroundScope,
        )
        val result = async { store.isAppEnabled(TARGET) }
        runCurrent()
        assertFalse(result.isCompleted)

        result.cancel()
        runCurrent()

        assertTrue(result.isCancelled)
    }

    @Test
    fun nonIoFailureIsNotSilentlyConvertedToDisabled() = runTest {
        val store = PreferencesFlashSkipStore(
            ReadOnlyDataStore(flow { throw IllegalStateException("Unexpected store failure") }),
            backgroundScope,
        )

        val failure = runCatching { store.isAppEnabled(TARGET) }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals("Unexpected store failure", failure?.message)
    }

    private class TrackingDataStore(initial: Preferences) : DataStore<Preferences> {
        val readKeys = mutableListOf<String>()
        private val values = MutableStateFlow<Preferences>(
            EnablementReadTrackingPreferences(initial, readKeys),
        )
        override val data: Flow<Preferences> = values

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            replace(transform(values.value))
            return values.value
        }

        fun replace(preferences: Preferences) {
            values.value = EnablementReadTrackingPreferences(preferences, readKeys)
        }
    }

    private class ReadOnlyDataStore(override val data: Flow<Preferences>) : DataStore<Preferences> {
        // 本组错误路径只验证读取；构造器的旧版本键清理与所模拟的读取故障相互独立。
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            emptyPreferences()
    }

    private companion object {
        val TARGET = TargetAppId("org.example.reader")
        val ENABLED_PACKAGES = stringPreferencesKey("enabled_packages_v2")
        val RECORDS = stringPreferencesKey("action_records_v2")
    }
}
