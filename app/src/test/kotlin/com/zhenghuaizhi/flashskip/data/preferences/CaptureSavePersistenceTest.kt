package com.zhenghuaizhi.flashskip.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.zhenghuaizhi.flashskip.application.rulecapture.CaptureOperationFailure
import com.zhenghuaizhi.flashskip.application.rulecapture.CaptureOperationResult
import com.zhenghuaizhi.flashskip.application.rulecapture.RuleCaptureCoordinator
import com.zhenghuaizhi.flashskip.domain.model.CapturePhase
import com.zhenghuaizhi.flashskip.domain.model.RuleCardStatus
import com.zhenghuaizhi.flashskip.domain.ports.MonotonicClock
import com.zhenghuaizhi.flashskip.domain.ports.WallClock
import com.zhenghuaizhi.flashskip.domain.rules.BuiltinRuleCatalog
import com.zhenghuaizhi.flashskip.domain.rules.CompositeRuleCatalog
import com.zhenghuaizhi.flashskip.testing.CaptureSaveFixture
import com.zhenghuaizhi.flashskip.testing.CommitGate
import com.zhenghuaizhi.flashskip.testing.OTHER_TARGET
import com.zhenghuaizhi.flashskip.testing.SESSION_ID
import com.zhenghuaizhi.flashskip.testing.TARGET
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CaptureSavePersistenceTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun ac01_ac06_ac07_saveSurvivesReopenAndRealCatalogRequiresExplicitEnable() = forEachPhase { fixture, files ->
        withStores(files) { stores ->
            stores.seed(fixture)
            assertEquals(CaptureOperationResult.Success(), stores.coordinator.finish(SESSION_ID))
            assertSaved(stores, fixture)
            stores.awaitReady()
            assertFalse(stores.flash.isAppEnabled(TARGET))
        }
        withStores(files) { reopened ->
            assertSaved(reopened, fixture)
            reopened.awaitReady()
            assertFalse(reopened.flash.isAppEnabled(TARGET))
            assertEquals(CaptureOperationResult.Success(), reopened.coordinator.setAppEnabled(TARGET, true))
        }
        withStores(files) { reopened ->
            assertEquals(savedState(fixture), reopened.capture.state.first())
            assertTrue(reopened.flash.isAppEnabled(TARGET))
            assertTrue(reopened.flash.isAppEnabled(OTHER_TARGET))
            assertEquals(fixture.initialPreferences.records, reopened.flash.preferences.first().records)
        }
    }

    @Test
    fun ac02_ac04_closeFailureRetriesOriginalPayloadAndPersistsOnce() = forEachPhase { fixture, files ->
        withStores(files) { stores ->
            stores.seed(fixture)
            stores.flashData.beforeUpdate = { throw IOException("关闭写入未提交") }
            val captureAttempts = stores.captureData.attempts

            assertEquals(storageFailure(), stores.coordinator.finish(SESSION_ID))
            assertEquals(captureAttempts, stores.captureData.attempts)
            assertEquals(fixture.initialCapture, stores.capture.state.first())
            assertTrue(stores.flash.isAppEnabled(TARGET))

            stores.flashData.beforeUpdate = {}
            assertEquals(CaptureOperationResult.Success(), stores.coordinator.finish(SESSION_ID))
            assertSaved(stores, fixture)
        }
        withStores(files) { assertSaved(it, fixture) }
    }

    @Test
    fun ac03_ac04_ac07_partialFailurePreservesPayloadThenRetrySurvivesReopen() = forEachPhase { fixture, files ->
        withStores(files) { stores ->
            stores.seed(fixture)
            stores.captureData.beforeUpdate = { throw IOException("采集写入未提交") }

            assertEquals(storageFailure(), stores.coordinator.finish(SESSION_ID))
            assertEquals(fixture.initialCapture, stores.capture.state.first())
            assertTrue(stores.capture.state.first().activeSession!!.blocksAutomation)
            assertFalse(stores.flash.isAppEnabled(TARGET))
            assertTrue(stores.flash.isAppEnabled(OTHER_TARGET))
            assertEquals(fixture.initialPreferences.records, stores.flash.preferences.first().records)

            stores.captureData.beforeUpdate = {}
            assertEquals(CaptureOperationResult.Success(), stores.coordinator.finish(SESSION_ID))
            assertSaved(stores, fixture)
        }
        withStores(files) { reopened ->
            assertSaved(reopened, fixture)
            reopened.awaitReady()
            assertFalse(reopened.flash.isAppEnabled(TARGET))
        }
    }

    @Test
    fun ac08_cancelAfterRealCommitSurvivesReopenAndReplayWritesNothing() = forEachPhase { fixture, files ->
        withStores(files) { stores ->
            stores.seed(fixture)
            val gate = CommitGate()
            stores.captureData.afterUpdate = gate::pause
            var propagated = false
            var returned: CaptureOperationResult? = null
            val saving = async {
                try {
                    stores.coordinator.finish(SESSION_ID).also { returned = it }
                } catch (cancelled: CancellationException) {
                    propagated = true
                    throw cancelled
                }
            }
            gate.entered.await()
            assertFalse(saving.isCompleted)
            assertSaved(stores, fixture)
            saving.cancelAndJoin()
            assertTrue(propagated)
            assertNull(returned)
            stores.captureData.afterUpdate = {}
            assertReplayIsWriteFree(stores, fixture)
        }
        withStores(files) { reopened ->
            assertSaved(reopened, fixture)
            assertReplayIsWriteFree(reopened, fixture)
            reopened.awaitReady()
            assertFalse(reopened.flash.isAppEnabled(TARGET))
        }
    }

    @Test
    fun ac08_ignoredSuccessfulReturnSurvivesReopenAndReplayWritesNothing() = forEachPhase { fixture, files ->
        withStores(files) { stores ->
            stores.seed(fixture)
            stores.coordinator.finish(SESSION_ID)
            assertSaved(stores, fixture)
        }
        withStores(files) { reopened ->
            assertReplayIsWriteFree(reopened, fixture)
            reopened.awaitReady()
            assertFalse(reopened.flash.isAppEnabled(TARGET))
        }
    }

    private fun forEachPhase(block: suspend TestScope.(CaptureSaveFixture, StoreFiles) -> Unit) = runTest {
        for (phase in listOf(CapturePhase.CAPTURED, CapturePhase.AWAITING_CONFIRMATION)) {
            val directory = temporaryFolder.newFolder(phase.name)
            val fixture = CaptureSaveFixture(backgroundScope, testScheduler, phase)
            block(fixture, StoreFiles(directory))
        }
    }

    private suspend fun TestScope.withStores(files: StoreFiles, block: suspend TestScope.(Stores) -> Unit) {
        val stores = Stores(this, files)
        try {
            // 先排入构造器清理，再以同一 DataStore 的更新作为屏障，避免把启动清理计入旧请求写入。
            runCurrent()
            stores.captureData.updateData { it }
            stores.flashData.updateData { it }
            block(stores)
        } finally {
            // 必须等待读者、目录和 DataStore 均结束，才能重开同一文件。
            stores.job.cancelAndJoin()
        }
    }

    private suspend fun assertReplayIsWriteFree(stores: Stores, fixture: CaptureSaveFixture) {
        val captureAttempts = stores.captureData.attempts
        val flashAttempts = stores.flashData.attempts
        assertEquals(CaptureOperationResult.Stale, stores.coordinator.finish(SESSION_ID))
        assertEquals(captureAttempts, stores.captureData.attempts)
        assertEquals(flashAttempts, stores.flashData.attempts)
        assertSaved(stores, fixture)
    }

    private suspend fun assertSaved(stores: Stores, fixture: CaptureSaveFixture) {
        val state = stores.capture.state.first()
        assertEquals(savedState(fixture), state)
        assertEquals(fixture.expectedSample, state.samples.single { it.id == SESSION_ID })
        assertFalse(stores.flash.isAppEnabled(TARGET))
        val preferences = stores.flash.preferences.first()
        // 真实适配器只编码已开启目标，关闭目标在映射中缺省即为 false。
        assertEquals(mapOf(OTHER_TARGET to true), preferences.enabledApps)
        assertEquals(fixture.initialPreferences.records, preferences.records)
    }

    private fun savedState(fixture: CaptureSaveFixture) = fixture.initialCapture.copy(
        samples = fixture.initialCapture.samples + fixture.expectedSample,
        activeSession = null,
        semanticDiffEvidence = fixture.initialCapture.semanticDiffEvidence.filterNot { it.targetAppId == TARGET },
    )

    private fun storageFailure() = CaptureOperationResult.Failure(CaptureOperationFailure.STORAGE)

    private class StoreFiles(directory: File) {
        val capture = File(directory, "capture.preferences_pb")
        val flash = File(directory, "flash.preferences_pb")
    }

    private class Stores(testScope: TestScope, files: StoreFiles) {
        val job = SupervisorJob()
        private val dispatcher = StandardTestDispatcher(testScope.testScheduler)
        private val scope = CoroutineScope(job + dispatcher)
        val captureData = ControlledDataStore(PreferenceDataStoreFactory.create(scope = scope) { files.capture })
        val flashData = ControlledDataStore(PreferenceDataStoreFactory.create(scope = scope) { files.flash })
        val capture = PreferencesRuleCaptureStore(captureData, scope)
        val flash = PreferencesFlashSkipStore(flashData, scope)
        val catalog = CompositeRuleCatalog(BuiltinRuleCatalog(), capture, scope, dispatcher)
        private val clock = object : MonotonicClock, WallClock {
            override fun elapsedRealtimeMs(): Long = testScope.testScheduler.currentTime
            override fun currentTimeMillis(): Long = 1_000_000L + testScope.testScheduler.currentTime
        }
        val coordinator = RuleCaptureCoordinator(capture, flash, clock, clock, scope, catalog)

        suspend fun seed(fixture: CaptureSaveFixture) {
            capture.update { fixture.initialCapture }
            fixture.initialPreferences.enabledApps.forEach { (target, enabled) -> flash.setAppEnabled(target, enabled) }
            fixture.initialPreferences.records.asReversed().forEach { flash.appendRecord(it) }
            assertEquals(fixture.initialCapture, capture.state.first())
            assertEquals(fixture.initialPreferences, flash.preferences.first())
            awaitReady()
        }

        suspend fun awaitReady() {
            val samples = capture.state.first().samples
            val snapshot = catalog.snapshot.first { it.sourceSamples == samples }
            assertEquals(RuleCardStatus.READY, snapshot.capturedTargets.single { it.targetAppId == TARGET }.analysis.status)
            assertTrue(snapshot.rulesForTarget(TARGET).isNotEmpty())
        }
    }

    /** 故障在真实更新前注入；返回屏障只在 DataStore 已完成更新后执行，不替代编码或文件存储。 */
    private class ControlledDataStore(private val delegate: DataStore<Preferences>) : DataStore<Preferences> {
        override val data = delegate.data
        var attempts = 0
            private set
        var beforeUpdate: suspend () -> Unit = {}
        var afterUpdate: suspend () -> Unit = {}

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            attempts++
            beforeUpdate()
            val committed = delegate.updateData(transform)
            afterUpdate()
            return committed
        }
    }
}
