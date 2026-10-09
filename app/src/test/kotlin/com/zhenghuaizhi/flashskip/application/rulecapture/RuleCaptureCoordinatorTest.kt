package com.zhenghuaizhi.flashskip.application.rulecapture

import com.zhenghuaizhi.flashskip.domain.model.ActionRecord
import com.zhenghuaizhi.flashskip.domain.model.CaptureFailure
import com.zhenghuaizhi.flashskip.domain.model.CapturePhase
import com.zhenghuaizhi.flashskip.domain.model.CaptureSample
import com.zhenghuaizhi.flashskip.domain.model.CaptureSession
import com.zhenghuaizhi.flashskip.domain.model.CapturedNode
import com.zhenghuaizhi.flashskip.domain.model.RuleCaptureState
import com.zhenghuaizhi.flashskip.domain.model.TargetAppId
import com.zhenghuaizhi.flashskip.domain.ports.FlashSkipPreferences
import com.zhenghuaizhi.flashskip.domain.ports.FlashSkipStore
import com.zhenghuaizhi.flashskip.domain.ports.MonotonicClock
import com.zhenghuaizhi.flashskip.domain.ports.RuleCaptureStore
import com.zhenghuaizhi.flashskip.domain.ports.WallClock
import com.zhenghuaizhi.flashskip.domain.rules.BuiltinRuleCatalog
import com.zhenghuaizhi.flashskip.domain.rules.CompositeRuleCatalog
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RuleCaptureCoordinatorTest {
    @Test
    fun finishKeepsAutomationBlockedUntilDisableIsPersisted() = runTest {
        val fixture = fixture(capturedState())
        val releaseDisable = CompletableDeferred<Unit>()
        fixture.preferences.beforeWrite = { _, _ -> releaseDisable.await() }

        val finish = async { fixture.coordinator.finish(SESSION_ID) }
        runCurrent()
        assertTrue(fixture.capture.state.value.activeSession!!.blocksAutomation)
        assertTrue(fixture.capture.state.value.samples.isEmpty())
        assertTrue(fixture.preferences.isAppEnabled(TARGET))

        releaseDisable.complete(Unit)
        assertEquals(CaptureOperationResult.Success(), finish.await())
        assertFalse(fixture.preferences.isAppEnabled(TARGET))
        assertNull(fixture.capture.state.value.activeSession)
        assertEquals(listOf(sample()), fixture.capture.state.value.samples)
    }

    @Test
    fun disableFailureRetainsCaptureAndDoesNotSaveSample() = runTest {
        val fixture = fixture(capturedState())
        fixture.preferences.beforeWrite = { _, _ -> throw IOException("disk full") }

        assertEquals(storageFailure(), fixture.coordinator.finish(SESSION_ID))
        assertEquals(capturedState(), fixture.capture.state.value)
        assertTrue(fixture.capture.state.value.activeSession!!.blocksAutomation)
        assertTrue(fixture.preferences.isAppEnabled(TARGET))
    }

    @Test
    fun sampleWriteFailureLeavesTargetDisabledAndCanRetry() = runTest {
        val fixture = fixture(capturedState())
        fixture.capture.beforeUpdate = { throw IOException("write failed") }

        assertEquals(storageFailure(), fixture.coordinator.finish(SESSION_ID))
        assertFalse(fixture.preferences.isAppEnabled(TARGET))
        assertEquals(capturedState(), fixture.capture.state.value)

        fixture.capture.beforeUpdate = {}
        assertEquals(CaptureOperationResult.Success(), fixture.coordinator.finish(SESSION_ID))
        assertEquals(1, fixture.capture.state.value.samples.size)
    }

    @Test
    fun repeatedFinishDoesNotDisableSubsequentExplicitEnablement() = runTest {
        val fixture = fixture(capturedState())
        assertEquals(CaptureOperationResult.Success(), fixture.coordinator.finish(SESSION_ID))
        runCurrent()
        assertEquals(CaptureOperationResult.Success(), fixture.coordinator.setAppEnabled(TARGET, true))

        assertEquals(CaptureOperationResult.Stale, fixture.coordinator.finish(SESSION_ID))
        assertTrue(fixture.preferences.isAppEnabled(TARGET))
        assertEquals(1, fixture.capture.state.value.samples.size)
    }

    @Test
    fun finishSerializesWithCancelAndDuplicateFinish() = runTest {
        val fixture = fixture(capturedState())
        val releaseDisable = CompletableDeferred<Unit>()
        fixture.preferences.beforeWrite = { _, _ -> releaseDisable.await() }
        val finish = async { fixture.coordinator.finish(SESSION_ID) }
        runCurrent()
        val cancel = async { fixture.coordinator.cancel(SESSION_ID) }
        val duplicate = async { fixture.coordinator.finish(SESSION_ID) }
        runCurrent()
        assertFalse(cancel.isCompleted)
        assertFalse(duplicate.isCompleted)

        releaseDisable.complete(Unit)
        assertEquals(CaptureOperationResult.Success(), finish.await())
        assertEquals(CaptureOperationResult.Stale, cancel.await())
        assertEquals(CaptureOperationResult.Stale, duplicate.await())
        assertEquals(1, fixture.capture.state.value.samples.size)
    }

    @Test
    fun cancelBeforeFinishDoesNotSaveOrDisableExistingRule() = runTest {
        val fixture = fixture(capturedState())

        assertEquals(CaptureOperationResult.Success(), fixture.coordinator.cancel(SESSION_ID))
        assertEquals(CaptureOperationResult.Stale, fixture.coordinator.finish(SESSION_ID))
        assertTrue(fixture.capture.state.value.samples.isEmpty())
        assertTrue(fixture.preferences.isAppEnabled(TARGET))
    }

    @Test
    fun oldCallbacksAndCancelCannotAffectNewSession() = runTest {
        val fixture = fixture(capturedState())
        fixture.coordinator.cancel(SESSION_ID)
        val next = fixture.coordinator.requestStart() as CaptureOperationResult.Success
        val sessionId = requireNotNull(next.sessionId)
        val before = fixture.capture.state.value

        fixture.coordinator.markConfigured(SESSION_ID)
        fixture.coordinator.recordCaptured(SESSION_ID, sample())
        fixture.coordinator.fail(SESSION_ID, CaptureFailure.SERVICE_UNAVAILABLE)
        assertEquals(CaptureOperationResult.Stale, fixture.coordinator.cancel(SESSION_ID))
        assertEquals(CaptureOperationResult.Stale, fixture.coordinator.finish(SESSION_ID))
        assertEquals(before, fixture.capture.state.value)
        assertEquals(sessionId, fixture.capture.state.value.activeSession!!.id)
    }

    @Test
    fun duplicateStartKeepsCurrentSession() = runTest {
        val fixture = fixture(RuleCaptureState())
        val first = fixture.coordinator.requestStart() as CaptureOperationResult.Success
        assertEquals(CaptureOperationResult.Stale, fixture.coordinator.requestStart())
        assertEquals(first.sessionId, fixture.capture.state.value.activeSession!!.id)
    }

    @Test
    fun enablingRequiresReadyRulesAndNoActiveCapture() = runTest {
        val fixture = fixture(capturedState())
        assertEquals(
            CaptureOperationResult.Failure(CaptureOperationFailure.CAPTURE_IN_PROGRESS),
            fixture.coordinator.setAppEnabled(TARGET, true),
        )
        assertEquals(CaptureOperationResult.Success(), fixture.coordinator.setAppEnabled(TARGET, false))
        fixture.coordinator.cancel(SESSION_ID)
        assertEquals(
            CaptureOperationResult.Failure(CaptureOperationFailure.RULE_NOT_READY),
            fixture.coordinator.setAppEnabled(TARGET, true),
        )
        assertFalse(fixture.preferences.isAppEnabled(TARGET))
    }

    @Test
    fun clearThenQueuedEnableCannotRestoreDeletedRule() = runTest {
        val fixture = fixture(RuleCaptureState(samples = listOf(sample())))
        val releaseDisable = CompletableDeferred<Unit>()
        fixture.preferences.beforeWrite = { _, _ -> releaseDisable.await() }
        val clear = async { fixture.coordinator.clearTarget(TARGET) }
        runCurrent()
        val enable = async { fixture.coordinator.setAppEnabled(TARGET, true) }
        runCurrent()
        assertFalse(enable.isCompleted)

        releaseDisable.complete(Unit)
        assertEquals(CaptureOperationResult.Success(), clear.await())
        val enableResult = enable.await()
        assertTrue(enableResult is CaptureOperationResult.Failure)
        assertTrue(
            (enableResult as CaptureOperationResult.Failure).reason in setOf(
                CaptureOperationFailure.RULE_NOT_READY,
                CaptureOperationFailure.RULES_UPDATING,
            ),
        )
        assertFalse(fixture.preferences.isAppEnabled(TARGET))
        assertTrue(fixture.capture.state.value.samples.isEmpty())
    }

    @Test
    fun clearAllDisablesOrphanedSwitches() = runTest {
        val fixture = fixture(RuleCaptureState())
        assertTrue(fixture.preferences.isAppEnabled(TARGET))

        assertEquals(CaptureOperationResult.Success(), fixture.coordinator.clearAll())
        assertFalse(fixture.preferences.isAppEnabled(TARGET))
        assertEquals(RuleCaptureState(), fixture.capture.state.value)
    }

    @Test
    fun cancellationDuringSavePropagatesAndReleasesOperationLock() = runTest {
        val fixture = fixture(capturedState())
        fixture.capture.beforeUpdate = { throw CancellationException("cancel save") }
        val finish = async { fixture.coordinator.finish(SESSION_ID) }
        try {
            finish.await()
            throw AssertionError("Cancellation must propagate")
        } catch (_: CancellationException) {
            assertTrue(finish.isCancelled)
        }
        assertFalse(fixture.preferences.isAppEnabled(TARGET))
        assertTrue(fixture.capture.state.value.activeSession!!.blocksAutomation)

        fixture.capture.beforeUpdate = {}
        assertEquals(CaptureOperationResult.Success(), fixture.coordinator.cancel(SESSION_ID))
    }

    @Test
    fun cancellationDuringDisableDoesNotReleaseCapture() = runTest {
        val fixture = fixture(capturedState())
        val releaseDisable = CompletableDeferred<Unit>()
        fixture.preferences.beforeWrite = { _, _ -> releaseDisable.await() }
        val finish = async { fixture.coordinator.finish(SESSION_ID) }
        runCurrent()
        finish.cancel()
        finish.join()

        assertTrue(finish.isCancelled)
        assertEquals(capturedState(), fixture.capture.state.value)
        assertTrue(fixture.capture.state.value.activeSession!!.blocksAutomation)
        assertTrue(fixture.preferences.isAppEnabled(TARGET))
    }

    @Test
    fun waitingForConfigurationStopsWhenSessionIsCancelled() = runTest {
        val fixture = fixture(RuleCaptureState())
        val start = fixture.coordinator.requestStart() as CaptureOperationResult.Success
        val sessionId = requireNotNull(start.sessionId)
        val configured = async { fixture.coordinator.awaitConfigured(sessionId) }
        runCurrent()
        fixture.coordinator.cancel(sessionId)

        assertFalse(configured.await())
        assertNull(fixture.capture.state.value.activeSession)
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun configurationWaitAcceptsSessionThatAlreadyAdvanced() = runTest {
        val fixture = fixture(capturedState())
        assertTrue(fixture.coordinator.awaitConfigured(SESSION_ID))
        assertEquals(capturedState(), fixture.capture.state.value)
    }

    @Test
    fun configurationWaitPreservesServiceFailure() = runTest {
        val fixture = fixture(capturedState())
        fixture.coordinator.fail(SESSION_ID, CaptureFailure.SERVICE_UNAVAILABLE)
        assertFalse(fixture.coordinator.awaitConfigured(SESSION_ID))
        assertEquals(
            CaptureFailure.SERVICE_UNAVAILABLE,
            fixture.capture.state.value.activeSession!!.failure,
        )
    }

    @Test
    fun clearFailureRetainsSavedRulesWithDisabledSwitch() = runTest {
        val initial = RuleCaptureState(samples = listOf(sample()))
        val fixture = fixture(initial)
        fixture.capture.beforeUpdate = { throw IOException("clear failed") }

        assertEquals(storageFailure(), fixture.coordinator.clearTarget(TARGET))
        assertEquals(initial, fixture.capture.state.value)
        assertFalse(fixture.preferences.isAppEnabled(TARGET))
    }

    @Test
    fun delayedDisconnectForCancelledSessionCannotFailReplacement() = runTest {
        val fixture = fixture(capturedState())
        fixture.coordinator.serviceDisconnected(SESSION_ID)
        fixture.coordinator.cancel(SESSION_ID)
        val replacement = fixture.coordinator.requestStart() as CaptureOperationResult.Success
        val beforeCallback = fixture.capture.state.value

        runCurrent()

        assertEquals(beforeCallback, fixture.capture.state.value)
        assertEquals(replacement.sessionId, fixture.capture.state.value.activeSession!!.id)
        assertEquals(CapturePhase.CONFIGURING, fixture.capture.state.value.activeSession!!.phase)
    }

    @Test
    fun delayedDisconnectCannotChangeCompletedCaptureOrLaterEnablement() = runTest {
        val fixture = fixture(capturedState())
        assertEquals(CaptureOperationResult.Success(), fixture.coordinator.finish(SESSION_ID))
        runCurrent()
        assertEquals(CaptureOperationResult.Success(), fixture.coordinator.setAppEnabled(TARGET, true))
        fixture.coordinator.serviceDisconnected(SESSION_ID)
        val completed = fixture.capture.state.value

        runCurrent()

        assertEquals(completed, fixture.capture.state.value)
        assertNull(fixture.capture.state.value.activeSession)
        assertTrue(fixture.preferences.isAppEnabled(TARGET))
    }

    @Test
    fun delayedDisconnectPreservesExistingFailureReason() = runTest {
        val fixture = fixture(capturedState())
        fixture.coordinator.serviceDisconnected(SESSION_ID)
        fixture.coordinator.fail(SESSION_ID, CaptureFailure.COMPLETION_TIMEOUT)
        runCurrent()

        assertEquals(
            CaptureFailure.COMPLETION_TIMEOUT,
            fixture.capture.state.value.activeSession!!.failure,
        )
    }

    @Test
    fun hashCollidingRuleIdsCannotEnableCatalogThatFellBackToEmpty() = runTest {
        val samples = listOf("Aa", "BB").mapIndexed { index, suffix ->
            sample().copy(
                id = index.toLong(),
                nodes = listOf(sample().nodes.single().copy(resourceId = "com.example.target:id/$suffix")),
            )
        }
        val fixture = fixture(RuleCaptureState(samples = samples))
        runCurrent()
        val analyzedRules = fixture.catalog.snapshot.value.capturedTargets.single().analysis.rules
        assertEquals(2, analyzedRules.size)
        assertEquals(1, analyzedRules.map { it.id }.distinct().size)
        assertTrue(fixture.catalog.snapshot.value.rules.isEmpty())
        fixture.coordinator.setAppEnabled(TARGET, false)

        assertEquals(
            CaptureOperationResult.Failure(CaptureOperationFailure.RULE_NOT_READY),
            fixture.coordinator.setAppEnabled(TARGET, true),
        )
        assertFalse(fixture.preferences.isAppEnabled(TARGET))
        assertEquals(CaptureOperationResult.Success(), fixture.coordinator.clearTarget(TARGET))
        assertTrue(fixture.capture.state.value.samples.isEmpty())
    }

    @Test
    fun enablingWaitsForMatchingCatalogButDisablingRemainsAvailable() = runTest {
        val fixture = fixture(RuleCaptureState(samples = listOf(sample())))
        fixture.coordinator.setAppEnabled(TARGET, false)
        assertEquals(
            CaptureOperationResult.Failure(CaptureOperationFailure.RULES_UPDATING),
            fixture.coordinator.setAppEnabled(TARGET, true),
        )
        assertFalse(fixture.preferences.isAppEnabled(TARGET))
        runCurrent()
        assertEquals(CaptureOperationResult.Success(), fixture.coordinator.setAppEnabled(TARGET, true))

        fixture.capture.state.value = RuleCaptureState(samples = listOf(sample().copy(targetAppName = "新版样本")))
        assertEquals(CaptureOperationResult.Success(), fixture.coordinator.setAppEnabled(TARGET, false))
        assertEquals(
            CaptureOperationResult.Failure(CaptureOperationFailure.RULES_UPDATING),
            fixture.coordinator.setAppEnabled(TARGET, true),
        )
        assertFalse(fixture.preferences.isAppEnabled(TARGET))
    }

    private fun TestScope.fixture(state: RuleCaptureState): Fixture {
        val capture = FakeCaptureStore(state)
        val preferences = FakePreferencesStore()
        val catalog = CompositeRuleCatalog(
            BuiltinRuleCatalog(),
            capture,
            backgroundScope,
            StandardTestDispatcher(testScheduler),
        )
        return Fixture(
            capture,
            preferences,
            RuleCaptureCoordinator(
                capture,
                preferences,
                object : MonotonicClock {
                    override fun elapsedRealtimeMs(): Long = testScheduler.currentTime
                },
                object : WallClock {
                    override fun currentTimeMillis(): Long = 1_000L + testScheduler.currentTime
                },
                backgroundScope,
                catalog,
            ),
            catalog,
        )
    }

    private data class Fixture(
        val capture: FakeCaptureStore,
        val preferences: FakePreferencesStore,
        val coordinator: RuleCaptureCoordinator,
        val catalog: CompositeRuleCatalog,
    )

    private class FakeCaptureStore(initial: RuleCaptureState) : RuleCaptureStore {
        override val state = MutableStateFlow(initial)
        var beforeUpdate: suspend () -> Unit = {}

        override suspend fun update(transform: (RuleCaptureState) -> RuleCaptureState) {
            beforeUpdate()
            state.value = transform(state.value)
        }
    }

    private class FakePreferencesStore : FlashSkipStore {
        override val preferences = MutableStateFlow(FlashSkipPreferences(mapOf(TARGET to true)))
        var beforeWrite: suspend (TargetAppId, Boolean) -> Unit = { _, _ -> }

        override suspend fun setAppEnabled(targetAppId: TargetAppId, enabled: Boolean) {
            beforeWrite(targetAppId, enabled)
            preferences.value = preferences.value.copy(
                enabledApps = preferences.value.enabledApps + (targetAppId to enabled),
            )
        }

        override suspend fun isAppEnabled(targetAppId: TargetAppId): Boolean =
            preferences.value.enabledApps[targetAppId] == true

        override suspend fun appendRecord(record: ActionRecord) = Unit
    }

    private companion object {
        val TARGET = TargetAppId("com.example.target")
        const val SESSION_ID = 42L

        fun storageFailure() = CaptureOperationResult.Failure(CaptureOperationFailure.STORAGE)

        fun sample() = CaptureSample(
            id = 101L,
            targetAppId = TARGET,
            activityName = "com.example.target.MainActivity",
            clickedAfterTargetMs = 500L,
            capturedAtEpochMs = 1_000L,
            nodes = listOf(
                CapturedNode(
                    resourceId = "com.example.target:id/skip",
                    text = "跳过",
                    clickable = true,
                    visibleToUser = true,
                    hasUsableBounds = true,
                ),
            ),
            preClickUniqueSelectors = emptyList(),
        )

        fun capturedState() = RuleCaptureState(
            activeSession = CaptureSession(
                id = SESSION_ID,
                targetAppId = TARGET,
                phase = CapturePhase.CAPTURED,
                phaseStartedAtEpochMs = 1_000L,
                deadlineAtEpochMs = 601_000L,
                pendingSample = sample(),
            ),
        )
    }
}
