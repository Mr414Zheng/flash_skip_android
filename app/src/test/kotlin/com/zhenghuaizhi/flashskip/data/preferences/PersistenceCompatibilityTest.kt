package com.zhenghuaizhi.flashskip.data.preferences

import androidx.datastore.preferences.core.*
import com.zhenghuaizhi.flashskip.domain.model.*
import com.zhenghuaizhi.flashskip.testing.*
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
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
class PersistenceCompatibilityTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun legacyKeysAreRemovedWithoutRestoringFlagsRecordsOrCapture() = stores { s ->
        s.captureData.delegate.edit { it[stringPreferencesKey("rule_capture_state_v1")] = "legacy" }
        s.flashData.delegate.edit {
            it[stringPreferencesKey("action_records_v1")] = "legacy"
            listOf("qq_music_enabled", "taobao_enabled", "jd_enabled").forEach { name -> it[booleanPreferencesKey(name)] = true }
        }
        s.start(); settle(s)
        assertTrue(s.captureData.data.first().asMap().isEmpty())
        assertTrue(s.flashData.data.first().asMap().isEmpty())
        assertEquals(RuleCaptureState(), s.capture.state.first())
        assertTrue(s.flash.preferences.first().enabledApps.isEmpty())
        assertTrue(s.flash.preferences.first().records.isEmpty())
    }

    @Test fun legacyCleanupFailureStillReadsValidV2() = stores { s ->
        val state = RuleCaptureState(samples = listOf(captureSample()))
        s.seed(RuleCaptureCodec.encode(state), ActionRecordCodec.encode(listOf(actionRecord())), TARGET.packageName)
        s.captureData.beforeUpdate = { throw IOException("cleanup") }
        s.flashData.beforeUpdate = { throw IOException("cleanup") }
        s.start(); runCurrent()
        assertEquals(state, s.capture.state.first())
        assertEquals(listOf(actionRecord()), s.flash.preferences.first().records)
        assertTrue(s.flash.isAppEnabled(TARGET))
    }

    @Test fun futureCaptureVersionSurvivesInitializationAndReopen() = runTest {
        val directory = folder.newFolder()
        val raw = """{"version":99,"samples":[{"future":"payload"}]}"""
        val s = PersistenceStores(this, directory)
        try { s.seed(raw); s.start(); settle(s); assertEquals(raw, s.captureData.data.first()[CAPTURE_KEY]) }
        finally { s.close() }
        val reopened = PersistenceStores(this, directory)
        try { reopened.start(); settle(reopened); assertEquals(raw, reopened.captureData.data.first()[CAPTURE_KEY]) }
        finally { reopened.close() }
    }

    @Test fun futureCaptureVersionBlocksOverwrite() = blockedCaptureWrite("""{"version":99,"samples":[]}""")
    @Test fun malformedCaptureRootBlocksOverwrite() = blockedCaptureWrite("{broken")
    @Test fun missingCaptureEnvelopeFieldsBlockOverwrite() = blockedCaptureWrite("""{"version":2}""")

    @Test fun futureRecordVersionBlocksAppend() = blockedRecordWrite("""{"version":99,"records":[]}""")
    @Test fun malformedRecordRootBlocksAppend() = blockedRecordWrite("{broken")

    @Test fun unknownRecordVersionBlocksEnabledLookup() = stores { s ->
        s.seed(recordsRaw = """{"version":99,"records":[]}""", enabled = TARGET.packageName)
        s.start(); settle(s)
        assertFalse("未知版本不得继续自动执行", s.flash.isAppEnabled(TARGET))
    }

    @Test fun damagedActiveSessionKeepsSavedRulesButBlocksAutomation() = stores { s ->
        val state = CaptureSaveFixture(backgroundScope, testScheduler).initialCapture
        val raw = JSONObject(RuleCaptureCodec.encode(state)).apply {
            getJSONObject("activeSession").put("phase", "BROKEN")
        }.toString()
        s.seed(raw); s.start(); settle(s)
        val result = runCatching { s.capture.state.first() }
        assertTrue("损坏会话不得回退为可执行空状态", result.isFailure || result.getOrNull()?.activeSession?.blocksAutomation == true)
    }

    @Test fun damagedActiveSessionBlocksWriteAndPreservesRaw() = runTest {
        val state = CaptureSaveFixture(backgroundScope, testScheduler).initialCapture
        val raw = JSONObject(RuleCaptureCodec.encode(state)).apply { getJSONObject("activeSession").put("phase", "BROKEN") }.toString()
        withStores { s -> assertBlockedCaptureWrite(s, raw) }
    }

    @Test fun captureReadIOExceptionIsNotReportedAsHealthyEmptyState() = stores { s ->
        s.start(); settle(s)
        s.captureData.beforeRead = { throw IOException("read") }
        assertTrue("读取失败须显式区别于正常空库", runCatching { s.capture.state.first() }.isFailure)
    }

    @Test fun preferencesReadIOExceptionIsNotReportedAsHealthyEmptyState() = stores { s ->
        s.start(); settle(s)
        s.flashData.beforeRead = { throw IOException("read") }
        assertTrue("读取失败须显式区别于正常空库", runCatching { s.flash.preferences.first() }.isFailure)
    }

    @Test fun actionSafetyLookupFailsClosedOnReadIOException() = stores { s ->
        s.seed(enabled = TARGET.packageName); s.start(); settle(s)
        s.flashData.beforeRead = { throw IOException("read") }
        assertFalse(s.flash.isAppEnabled(TARGET))
    }

    @Test fun successfulRereadRecoversActualCaptureAndPreferences() = stores { s ->
        val state = RuleCaptureState(samples = listOf(captureSample()))
        s.seed(RuleCaptureCodec.encode(state), enabled = TARGET.packageName); s.start(); settle(s)
        s.captureData.beforeRead = { throw IOException("read") }
        s.flashData.beforeRead = { throw IOException("read") }
        runCatching { s.capture.state.first() }; runCatching { s.flash.preferences.first() }
        s.captureData.beforeRead = {}; s.flashData.beforeRead = {}
        assertEquals(state, s.capture.state.first())
        assertTrue(s.flash.isAppEnabled(TARGET))
    }

    @Test fun readCancellationPropagatesForBothStores() = stores { s ->
        s.start(); settle(s)
        s.captureData.beforeRead = { throw CancellationException("cancel") }
        s.flashData.beforeRead = { throw CancellationException("cancel") }
        assertTrue(runCatching { s.capture.state.first() }.exceptionOrNull() is CancellationException)
        assertTrue(runCatching { s.flash.preferences.first() }.exceptionOrNull() is CancellationException)
        assertTrue(runCatching { s.flash.isAppEnabled(TARGET) }.exceptionOrNull() is CancellationException)
    }

    @Test fun nonIoReadFailureIsNotHidden() = stores { s ->
        s.start(); settle(s)
        s.captureData.beforeRead = { throw IllegalStateException("unexpected") }
        assertTrue(runCatching { s.capture.state.first() }.exceptionOrNull() is IllegalStateException)
    }

    @Test fun unsafePersistedSampleAndMatchingEvidenceAreRemovedOnReopen() = runTest {
        val directory = folder.newFolder()
        val initial = CaptureSaveFixture(backgroundScope, testScheduler).initialCapture.copy(activeSession = null)
        val unsafe = initial.samples.first().copy(nodes = listOf(CapturedNode(text = "5", resourceId = "id/count", clickable = true, supportsActionClick = true, visibleToUser = true, hasUsableBounds = true)))
        val expected = initial.copy(samples = initial.samples.drop(1), semanticDiffEvidence = initial.semanticDiffEvidence.filterNot { it.targetAppId == TARGET })
        val s = PersistenceStores(this, directory)
        try {
            s.seed(RuleCaptureCodec.encode(initial.copy(samples = listOf(unsafe) + initial.samples.drop(1))))
            s.start(); settle(s)
            assertEquals(expected, s.capture.state.first())
            assertEquals(expected, RuleCaptureCodec.decodeOrEmpty(s.captureData.data.first()[CAPTURE_KEY]))
        } finally { s.close() }
        val reopened = PersistenceStores(this, directory)
        try { reopened.start(); settle(reopened); assertEquals(expected, reopened.capture.state.first()) }
        finally { reopened.close() }
    }

    @Test fun unsafePendingPayloadCannotResumeListening() = stores { s ->
        val initial = CaptureSaveFixture(backgroundScope, testScheduler).initialCapture
        val session = initial.activeSession!!
        val unsafe = session.pendingSample!!.copy(nodes = listOf(CapturedNode(text = "5", clickable = true, visibleToUser = true, hasUsableBounds = true)))
        s.seed(RuleCaptureCodec.encode(initial.copy(activeSession = session.copy(pendingSample = unsafe, candidateClickListeningEnabled = true, leftFlashSkipAtEpochMs = 1))))
        s.start(); settle(s)
        val actual = s.capture.state.first().activeSession!!
        assertEquals(CapturePhase.FAILED, actual.phase)
        assertEquals(CaptureFailure.DISMISS_NOT_CAPTURED, actual.failure)
        assertNull(actual.pendingSample); assertNull(actual.pendingObservedCandidate)
        assertFalse(actual.candidateClickListeningEnabled); assertNull(actual.leftFlashSkipAtEpochMs)
    }

    @Test fun initializationQueuedBeforeUserWriteDoesNotOverwriteNewData() = stores { s ->
        val gate = CommitGate()
        s.captureData.beforeUpdate = gate::pause
        s.start(); runCurrent()
        assertTrue(gate.entered.isCompleted)
        s.captureData.beforeUpdate = {}
        val state = RuleCaptureState(samples = listOf(captureSample(999)))
        s.capture.update { state }
        gate.release.complete(Unit); settle(s)
        assertEquals(state, s.capture.state.first())
    }

    @Test fun failedWriteLeavesOriginalBytesAndCanRetry() = stores { s ->
        val state = RuleCaptureState(samples = listOf(captureSample()))
        s.seed(RuleCaptureCodec.encode(state)); s.start(); settle(s)
        val original = s.captureData.data.first()[CAPTURE_KEY]
        s.captureData.beforeUpdate = { throw IOException("before commit") }
        assertTrue(runCatching { s.capture.update { RuleCaptureState() } }.exceptionOrNull() is IOException)
        assertEquals(original, s.captureData.data.first()[CAPTURE_KEY])
        s.captureData.beforeUpdate = {}
        s.capture.update { RuleCaptureState() }
        assertEquals(RuleCaptureState(), s.capture.state.first())
    }

    @Test fun enabledPackagesAreTrimmedDeduplicatedAndInvalidNamesAreNotExposed() = stores { s ->
        s.seed(enabled = "  ${TARGET.packageName} \n${TARGET.packageName}\n!\n\n${OTHER_TARGET.packageName}")
        s.start(); settle(s)
        assertEquals(setOf(TARGET, OTHER_TARGET), s.flash.preferences.first().enabledApps.keys)
        s.flash.setAppEnabled(TARGET, false)
        assertFalse(s.flash.isAppEnabled(TARGET)); assertTrue(s.flash.isAppEnabled(OTHER_TARGET))
    }

    @Test fun recordsKeepTwentyMostRecentWritesAndDuplicatesAcrossReopen() = runTest {
        val directory = folder.newFolder()
        val records = (30L downTo 7).map(::actionRecord) + listOf(actionRecord(7), actionRecord(7))
        val s = PersistenceStores(this, directory)
        try {
            s.start(); settle(s)
            records.forEach { s.flash.appendRecord(it) }
            assertEquals(records.reversed().take(20), s.flash.preferences.first().records)
        } finally { s.close() }
        val reopened = PersistenceStores(this, directory)
        try { reopened.start(); settle(reopened); assertEquals(records.reversed().take(20), reopened.flash.preferences.first().records) }
        finally { reopened.close() }
    }

    @Test fun legacyCleanupPreservesCoexistingV2Values() = stores { s ->
        val state = RuleCaptureState(samples = listOf(captureSample()))
        s.seed(RuleCaptureCodec.encode(state), ActionRecordCodec.encode(listOf(actionRecord())), TARGET.packageName)
        s.captureData.delegate.edit { it[stringPreferencesKey("rule_capture_state_v1")] = "legacy" }
        s.flashData.delegate.edit { it[stringPreferencesKey("action_records_v1")] = "legacy"; it[booleanPreferencesKey("jd_enabled")] = true }
        s.start(); settle(s)
        assertEquals(state, s.capture.state.first())
        assertEquals(listOf(actionRecord()), s.flash.preferences.first().records)
        assertEquals(setOf(TARGET), s.flash.preferences.first().enabledApps.keys)
        assertNull(s.captureData.data.first()[stringPreferencesKey("rule_capture_state_v1")])
        assertNull(s.flashData.data.first()[stringPreferencesKey("action_records_v1")])
    }

    @Test fun cleanupWriteFailureStillFiltersUnsafeReadWithoutRewritingBytes() = stores { s ->
        val sample = captureSample().copy(nodes = listOf(CapturedNode(text = "5", clickable = true, visibleToUser = true, hasUsableBounds = true)))
        val raw = RuleCaptureCodec.encode(RuleCaptureState(samples = listOf(sample)))
        s.seed(raw)
        s.captureData.beforeUpdate = { throw IOException("cleanup") }
        s.start(); runCurrent()
        assertTrue(s.capture.state.first().samples.isEmpty())
        assertEquals(raw, s.captureData.data.first()[CAPTURE_KEY])
    }

    @Test fun unsafeSampleCannotBeIntroducedThroughUpdate() = stores { s ->
        s.start(); settle(s)
        val unsafe = captureSample().copy(nodes = listOf(CapturedNode(text = "5", clickable = true, visibleToUser = true, hasUsableBounds = true)))
        s.capture.update { RuleCaptureState(samples = listOf(unsafe, captureSample(2, OTHER_TARGET))) }
        val expected = listOf(captureSample(2, OTHER_TARGET))
        assertEquals(expected, s.capture.state.first().samples)
        assertEquals(expected, RuleCaptureCodec.decodeOrEmpty(s.captureData.data.first()[CAPTURE_KEY]).samples)
    }

    @Test fun appendFailureDoesNotLoseExistingRecordOrEnabledFlag() = stores { s ->
        s.seed(recordsRaw = ActionRecordCodec.encode(listOf(actionRecord())), enabled = TARGET.packageName)
        s.start(); settle(s)
        s.flashData.beforeUpdate = { throw IOException("append") }
        assertTrue(runCatching { s.flash.appendRecord(actionRecord(2)) }.exceptionOrNull() is IOException)
        assertEquals(listOf(actionRecord()), s.flash.preferences.first().records)
        assertTrue(s.flash.isAppEnabled(TARGET))
        s.flashData.beforeUpdate = {}
        s.flash.appendRecord(actionRecord(2))
        assertEquals(listOf(actionRecord(2), actionRecord()), s.flash.preferences.first().records)
    }

    @Test fun cancelledAppendBeforeCommitDoesNotPersistRecord() = stores { s ->
        s.start(); settle(s)
        val gate = CommitGate(); s.flashData.beforeUpdate = gate::pause
        val task = async { s.flash.appendRecord(actionRecord()) }
        gate.entered.await(); task.cancelAndJoin()
        assertTrue(task.isCancelled)
        assertTrue(s.flash.preferences.first().records.isEmpty())
    }

    @Test fun cancelledAppendAfterCommitPersistsRecordOnReopen() = runTest {
        val directory = folder.newFolder()
        val s = PersistenceStores(this, directory)
        try {
            s.start(); settle(s)
            val gate = CommitGate(); s.flashData.afterUpdate = gate::pause
            val task = async { s.flash.appendRecord(actionRecord()) }
            gate.entered.await(); task.cancelAndJoin(); assertTrue(task.isCancelled)
        } finally { s.close() }
        val reopened = PersistenceStores(this, directory)
        try { reopened.start(); settle(reopened); assertEquals(listOf(actionRecord()), reopened.flash.preferences.first().records) }
        finally { reopened.close() }
    }

    @Test fun supportedDataReplacesUnavailableInputOnlyThroughExternalRecoveryThenAllowsWrites() = stores { s ->
        s.seed("""{"version":99,"samples":[]}""")
        s.start(); settle(s)
        // 模拟受支持版本或明确恢复后的原始存储；不添加恢复 API，也不经应用写入口覆盖未知数据。
        val state = RuleCaptureState(samples = listOf(captureSample()))
        s.captureData.delegate.edit { it[CAPTURE_KEY] = RuleCaptureCodec.encode(state) }
        assertEquals(state, s.capture.state.first())
        s.capture.update { it.copy(samples = it.samples + captureSample(2)) }
        assertEquals(listOf(captureSample(), captureSample(2)), s.capture.state.first().samples)
    }

    private fun blockedCaptureWrite(raw: String) = stores { assertBlockedCaptureWrite(it, raw) }
    private suspend fun TestScope.assertBlockedCaptureWrite(s: PersistenceStores, raw: String) {
        s.seed(raw); s.start(); settle(s)
        val result = runCatching { s.capture.update { RuleCaptureState(samples = listOf(captureSample())) } }
        assertEquals("不可用原文不可被当前版本覆盖", raw, s.captureData.data.first()[CAPTURE_KEY])
        assertTrue("写入应明确失败", result.isFailure)
    }
    private fun blockedRecordWrite(raw: String) = stores { s ->
        s.seed(recordsRaw = raw); s.start(); settle(s)
        val result = runCatching { s.flash.appendRecord(actionRecord()) }
        assertEquals(raw, s.flashData.data.first()[RECORDS_KEY])
        assertTrue(result.isFailure)
    }
    private fun stores(block: suspend TestScope.(PersistenceStores) -> Unit) = runTest { withStores(block) }
    private suspend fun TestScope.withStores(block: suspend TestScope.(PersistenceStores) -> Unit) {
        val s = PersistenceStores(this, folder.newFolder())
        try { block(s) } finally { s.close() }
    }
    private suspend fun TestScope.settle(s: PersistenceStores) {
        runCurrent()
        s.captureData.delegate.updateData { it }; s.flashData.delegate.updateData { it }
        runCurrent()
    }
}
