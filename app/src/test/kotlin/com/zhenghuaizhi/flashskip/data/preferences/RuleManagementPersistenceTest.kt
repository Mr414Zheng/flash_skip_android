package com.zhenghuaizhi.flashskip.data.preferences

import com.zhenghuaizhi.flashskip.application.rulecapture.*
import com.zhenghuaizhi.flashskip.domain.model.*
import com.zhenghuaizhi.flashskip.testing.*
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
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
class RuleManagementPersistenceTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun targetDeleteSurvivesReopenAndPreservesOtherTargetAndRecords() = runTest {
        val directory = folder.newFolder()
        val f = CaptureSaveFixture(backgroundScope, testScheduler)
        val expected = f.initialCapture.copy(
            samples = f.initialCapture.samples.filterNot { it.targetAppId == TARGET },
            activeSession = null,
            semanticDiffEvidence = f.initialCapture.semanticDiffEvidence.filterNot { it.targetAppId == TARGET },
        )
        withStores(directory) { s -> seed(s, f); assertEquals(CaptureOperationResult.Success(), s.coordinator.clearTarget(TARGET)) }
        withStores(directory) { s ->
            assertEquals(expected, s.capture.state.first())
            assertFalse(s.flash.isAppEnabled(TARGET)); assertTrue(s.flash.isAppEnabled(OTHER_TARGET))
            assertEquals(f.initialPreferences.records, s.flash.preferences.first().records)
            val snapshot = s.catalog.snapshot.first { it.sourceSamples == expected.samples }
            assertTrue(snapshot.rulesForTarget(TARGET).isEmpty())
            assertTrue(snapshot.rulesForTarget(OTHER_TARGET).isNotEmpty())
        }
    }

    @Test fun allDeletePersistsRemovalOfOrphanSwitchesAndRetainsRecords() = runTest {
        val directory = folder.newFolder()
        val f = CaptureSaveFixture(backgroundScope, testScheduler)
        withStores(directory) { s ->
            seed(s, f); s.flash.setAppEnabled(TargetAppId("com.example.orphan"), true)
            assertEquals(CaptureOperationResult.Success(), s.coordinator.clearAll())
        }
        withStores(directory) { s ->
            assertEquals(RuleCaptureState(), s.capture.state.first())
            assertTrue(s.flash.preferences.first().enabledApps.isEmpty())
            assertEquals(f.initialPreferences.records, s.flash.preferences.first().records)
        }
    }

    @Test fun secondDisableFailurePersistsPartialStateAndRetryCompletesAfterReopen() = runTest {
        val directory = folder.newFolder()
        val f = CaptureSaveFixture(backgroundScope, testScheduler)
        withStores(directory) { s ->
            seed(s, f)
            val initial = s.flashData.attempts
            s.flashData.beforeUpdate = { if (s.flashData.attempts == initial + 2) throw IOException("second") }
            assertEquals(storageFailure(), s.coordinator.clearAll())
            assertEquals(f.initialCapture, s.capture.state.first())
        }
        withStores(directory) { s ->
            assertEquals(f.initialCapture, s.capture.state.first())
            assertFalse(s.flash.isAppEnabled(TARGET)); assertTrue(s.flash.isAppEnabled(OTHER_TARGET))
            assertEquals(CaptureOperationResult.Success(), s.coordinator.clearAll())
        }
        withStores(directory) { s -> assertEquals(RuleCaptureState(), s.capture.state.first()); assertTrue(s.flash.preferences.first().enabledApps.isEmpty()) }
    }

    @Test fun finalDeleteWriteFailureRetainsPayloadAfterReopen() = runTest {
        val directory = folder.newFolder()
        val f = CaptureSaveFixture(backgroundScope, testScheduler)
        withStores(directory) { s ->
            seed(s, f)
            s.captureData.beforeUpdate = { throw IOException("before delete commit") }
            assertEquals(storageFailure(), s.coordinator.clearAll())
        }
        withStores(directory) { s ->
            assertEquals(f.initialCapture, s.capture.state.first())
            assertTrue(s.flash.preferences.first().enabledApps.isEmpty())
            assertEquals(f.initialPreferences.records, s.flash.preferences.first().records)
        }
    }

    @Test fun cancellationBetweenRealStoresRetainsPayloadAfterReopen() = runTest {
        val directory = folder.newFolder()
        val f = CaptureSaveFixture(backgroundScope, testScheduler)
        withStores(directory) { s ->
            seed(s, f)
            val gate = CommitGate(); s.captureData.beforeUpdate = gate::pause
            val task = async { s.coordinator.clearTarget(TARGET) }
            gate.entered.await(); task.cancelAndJoin(); assertTrue(task.isCancelled)
        }
        withStores(directory) { s ->
            assertEquals(f.initialCapture, s.capture.state.first())
            assertFalse(s.flash.isAppEnabled(TARGET)); assertTrue(s.flash.isAppEnabled(OTHER_TARGET))
        }
    }

    @Test fun cancellationAfterRealDeleteCommitKeepsDeletionAfterReopen() = runTest {
        val directory = folder.newFolder()
        val f = CaptureSaveFixture(backgroundScope, testScheduler)
        withStores(directory) { s ->
            seed(s, f)
            val gate = CommitGate(); s.captureData.afterUpdate = gate::pause
            val task = async { s.coordinator.clearAll() }
            gate.entered.await(); task.cancelAndJoin(); assertTrue(task.isCancelled)
        }
        withStores(directory) { s ->
            assertEquals(RuleCaptureState(), s.capture.state.first())
            assertTrue(s.flash.preferences.first().enabledApps.isEmpty())
            assertEquals(f.initialPreferences.records, s.flash.preferences.first().records)
        }
    }

    @Test fun captureReadFailurePreventsAllDeleteInsteadOfTreatingDataAsEmpty() = runTest {
        withStores(folder.newFolder()) { s ->
            val f = CaptureSaveFixture(backgroundScope, testScheduler); seed(s, f)
            val raw = s.captureData.data.first()[CAPTURE_KEY]
            s.captureData.beforeRead = { throw IOException("read capture") }
            val result = s.coordinator.clearAll()
            assertEquals(raw, s.captureData.delegate.data.first()[CAPTURE_KEY])
            assertEquals(storageFailure(), result)
        }
    }

    @Test fun preferencesReadFailurePreventsDeletingCaptureWhileOrphanRemainsEnabled() = runTest {
        withStores(folder.newFolder()) { s ->
            val f = CaptureSaveFixture(backgroundScope, testScheduler); seed(s, f)
            val orphan = TargetAppId("com.example.orphan"); s.flash.setAppEnabled(orphan, true)
            s.flashData.beforeRead = { throw IOException("read preferences") }
            assertEquals(storageFailure(), s.coordinator.clearAll())
            assertEquals(f.initialCapture, s.capture.state.first())
        }
    }

    @Test fun unknownCaptureVersionCannotBeDeletedThroughManagementEntry() = runTest {
        withStores(folder.newFolder()) { s ->
            val raw = """{"version":99,"samples":[]}"""
            s.seed(raw, enabled = TARGET.packageName)
            val result = s.coordinator.clearAll()
            assertEquals(raw, s.captureData.data.first()[CAPTURE_KEY])
            assertTrue(result is CaptureOperationResult.Failure)
        }
    }

    private suspend fun TestScope.seed(s: PersistenceStores, f: CaptureSaveFixture) {
        s.capture.update { f.initialCapture }
        f.initialPreferences.enabledApps.forEach { (target, enabled) -> s.flash.setAppEnabled(target, enabled) }
        f.initialPreferences.records.forEach { s.flash.appendRecord(it) }
        s.catalog.snapshot.first { it.sourceSamples == f.initialCapture.samples }
    }
    private suspend fun TestScope.withStores(directory: java.io.File, block: suspend TestScope.(PersistenceStores) -> Unit) {
        val s = PersistenceStores(this, directory)
        try {
            s.start(); runCurrent()
            s.captureData.delegate.updateData { it }; s.flashData.delegate.updateData { it }; runCurrent()
            block(s)
        } finally { s.close() }
    }
    private fun storageFailure() = CaptureOperationResult.Failure(CaptureOperationFailure.STORAGE)
}
