package com.zhenghuaizhi.flashskip.data.preferences

import androidx.datastore.preferences.core.edit
import androidx.lifecycle.viewModelScope
import com.zhenghuaizhi.flashskip.application.ServiceConnectionRegistry
import com.zhenghuaizhi.flashskip.application.ServiceStatusCoordinator
import com.zhenghuaizhi.flashskip.application.SystemServiceStatusReader
import com.zhenghuaizhi.flashskip.domain.model.RuleCaptureState
import com.zhenghuaizhi.flashskip.domain.ports.StorageRead
import com.zhenghuaizhi.flashskip.domain.rules.BuiltinRuleCatalog
import com.zhenghuaizhi.flashskip.domain.rules.CompositeRuleCatalog
import com.zhenghuaizhi.flashskip.testing.CaptureSaveFixture
import com.zhenghuaizhi.flashskip.testing.TARGET
import com.zhenghuaizhi.flashskip.testing.captureSample
import com.zhenghuaizhi.flashskip.testing.executableRule
import com.zhenghuaizhi.flashskip.ui.home.HomeFeedback
import com.zhenghuaizhi.flashskip.ui.home.HomeViewModel
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class StorageAvailabilityRegressionTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun oneLongLivedCaptureSubscriptionReportsIoAndThenRecovers() = withStores { s ->
        val expected = RuleCaptureState(samples = listOf(captureSample()))
        s.seed(RuleCaptureCodec.encode(expected))
        s.captureData.beforeRead = { throw IOException("read unavailable") }
        s.start()
        val seen = mutableListOf<StorageRead<RuleCaptureState>>()
        val collector = backgroundScope.launch { s.capture.reads.collect { seen += it } }
        runCurrent()
        assertTrue(seen.isNotEmpty())
        assertFalse(seen.last().available)
        assertTrue(s.catalog.snapshot.value.rules.isEmpty())
        s.captureData.beforeRead = {}
        advanceTimeBy(1_000); runCurrent()
        assertEquals(expected, seen.last().requireAvailable())
        assertTrue(s.catalog.snapshot.value.rulesForTarget(TARGET).isNotEmpty())
        collector.cancel()
    }

    @Test fun oneLongLivedPreferencesSubscriptionReportsIoAndThenRecovers() = withStores { s ->
        s.seed(enabled = TARGET.packageName)
        s.flashData.beforeRead = { throw IOException("read unavailable") }
        s.start()
        val seen = mutableListOf<Boolean>()
        val collector = backgroundScope.launch { s.flash.reads.collect { seen += it.available } }
        runCurrent(); assertEquals(listOf(false), seen)
        s.flashData.beforeRead = {}
        advanceTimeBy(1_000); runCurrent()
        assertEquals(listOf(false, true), seen)
        assertTrue(s.flash.isAppEnabled(TARGET))
        collector.cancel()
    }

    @Test fun badSessionPublishesRecoverableSamplesButSuppressesLocalAndBuiltinRules() = withStores { s ->
        val state = CaptureSaveFixture(backgroundScope, testScheduler).initialCapture
        val raw = JSONObject(RuleCaptureCodec.encode(state)).apply { getJSONObject("activeSession").put("phase", "BROKEN") }.toString()
        s.seed(raw); s.start()
        val catalog = CompositeRuleCatalog(BuiltinRuleCatalog(listOf(executableRule("builtin"))), s.capture, s.scope, StandardTestDispatcher(testScheduler))
        assertTrue(catalog.snapshot.value.rules.isEmpty())
        val read = s.capture.reads.first()
        assertEquals(state.samples, read.value!!.samples)
        assertFalse(read.available)
        assertTrue(runCatching { read.requireAvailable() }.exceptionOrNull() is IOException)
        runCurrent()
        assertEquals(state.samples, catalog.snapshot.value.sourceSamples)
        assertTrue(catalog.snapshot.value.rules.isEmpty())
        assertEquals(raw, s.captureData.data.first()[CAPTURE_KEY])
    }

    @Test fun validThenUnknownThenRecoveredStateInvalidatesAndRepublishesCatalog() = withStores { s ->
        val state = RuleCaptureState(samples = listOf(captureSample()))
        s.seed(RuleCaptureCodec.encode(state)); s.start()
        s.catalog.snapshot.first { it.rules.isNotEmpty() }
        s.captureData.delegate.edit { it[CAPTURE_KEY] = """{"version":99,"samples":[]}""" }
        s.catalog.snapshot.first { it.rules.isEmpty() }
        assertFalse(s.capture.reads.first().available)
        s.captureData.delegate.edit { it[CAPTURE_KEY] = RuleCaptureCodec.encode(state) }
        s.catalog.snapshot.first { it.rules.isNotEmpty() }
        assertEquals(state, s.capture.state.first())
    }

    @Test fun unreadableStateRejectsTransformBeforeItCanObserveOrReplaceEmptyData() = withStores { s ->
        s.seed("{bad"); s.start(); runCurrent()
        var invoked = false
        assertTrue(runCatching { s.capture.update { invoked = true; RuleCaptureState() } }.exceptionOrNull() is IOException)
        assertFalse(invoked)
        assertEquals("{bad", s.captureData.data.first()[CAPTURE_KEY])
    }

    @Test fun unknownRecordsRejectFlagChangesIncludingDisableAndPreserveRawPreferences() = withStores { s ->
        s.seed(recordsRaw = """{"version":99,"records":[]}""", enabled = TARGET.packageName)
        s.start(); runCurrent()
        val before = s.flashData.data.first()
        assertTrue(runCatching { s.flash.setAppEnabled(TARGET, false) }.exceptionOrNull() is IOException)
        assertTrue(runCatching { s.flash.setAppEnabled(TARGET, true) }.exceptionOrNull() is IOException)
        assertEquals(before, s.flashData.data.first())
        assertFalse(s.flash.isAppEnabled(TARGET))
    }

    @Test fun retentionUsesTwentyValidEntriesAfterIsolatingAnEarlyBadRecord() {
        val records = (1L..22).map(::actionRecord)
        val raw = JSONObject(ActionRecordCodec.encode(records))
        raw.getJSONArray("records").getJSONObject(3).put("outcome", "BAD")
        assertEquals(records.filterIndexed { index, _ -> index != 3 }.take(20), ActionRecordCodec.decodeOrEmpty(raw.toString()))
    }

    @Test fun homeKeepsRecoverableCardsDisabledAndRecoversWithoutNewViewModel() = withStores { s ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val state = CaptureSaveFixture(backgroundScope, testScheduler).initialCapture
            val raw = JSONObject(RuleCaptureCodec.encode(state)).apply { getJSONObject("activeSession").put("phase", "BAD") }.toString()
            s.seed(raw, enabled = TARGET.packageName); s.start()
            val connection = ServiceConnectionRegistry().also { it.setConnected(true) }
            val status = ServiceStatusCoordinator(SystemServiceStatusReader { true }, connection, backgroundScope)
            status.refresh()
            val vm = HomeViewModel(s.flash, s.catalog, status, s.coordinator)
            val collector = backgroundScope.launch { vm.uiState.collect() }
            try {
                runCurrent()
                assertTrue(vm.uiState.value.targetApps.isNotEmpty())
                assertTrue(vm.uiState.value.targetApps.none { it.canEnable || it.checked })
                assertFalse(vm.uiState.value.ruleActionsEnabled)
                assertFalse(vm.uiState.value.capture.canStart)
                assertEquals(HomeFeedback.STORAGE, vm.uiState.value.operationFeedback)
                // 故障时首页生命周期回调也不能抛出未处理读取异常。
                vm.onHostResumed(); vm.reportGoHomeFailure(100); vm.refresh(); runCurrent()
                assertEquals(raw, s.captureData.data.first()[CAPTURE_KEY])
                s.captureData.delegate.edit { it[CAPTURE_KEY] = RuleCaptureCodec.encode(state.copy(activeSession = null)) }
                runCurrent()
                assertTrue(vm.uiState.value.ruleActionsEnabled)
                assertTrue(vm.uiState.value.targetApps.single { it.id == TARGET }.canEnable)
            } finally { collector.cancel(); vm.viewModelScope.cancel() }
        } finally { Dispatchers.resetMain() }
    }

    @Test fun wrongTypePendingPayloadIsUnavailableRatherThanSilentlyAbsent() = withStores { s ->
        val state = CaptureSaveFixture(backgroundScope, testScheduler).initialCapture
        val raw = JSONObject(RuleCaptureCodec.encode(state)).apply { getJSONObject("activeSession").put("pendingSample", "broken") }.toString()
        s.seed(raw); s.start(); runCurrent()
        assertFalse(s.capture.reads.first().available)
        assertEquals(state.samples, s.capture.reads.first().value!!.samples)
        assertEquals(raw, s.captureData.data.first()[CAPTURE_KEY])
    }

    @Test fun missingRequiredCompletionPayloadIsUnavailableAndCannotResume() = withStores { s ->
        val state = CaptureSaveFixture(backgroundScope, testScheduler).initialCapture
        val raw = JSONObject(RuleCaptureCodec.encode(state)).apply { getJSONObject("activeSession").remove("pendingSample") }.toString()
        s.seed(raw); s.start(); runCurrent()
        assertFalse(s.capture.reads.first().available)
        assertTrue(s.catalog.snapshot.value.rules.isEmpty())
        assertEquals(raw, s.captureData.data.first()[CAPTURE_KEY])
    }

    private fun withStores(block: suspend TestScope.(PersistenceStores) -> Unit) = runTest {
        val s = PersistenceStores(this, folder.newFolder())
        try { block(s) } finally { s.close() }
    }
}
