package com.zhenghuaizhi.flashskip.application.rulecapture

import com.zhenghuaizhi.flashskip.domain.model.CaptureEvidence
import com.zhenghuaizhi.flashskip.domain.model.CaptureFailure
import com.zhenghuaizhi.flashskip.domain.model.CapturePhase
import com.zhenghuaizhi.flashskip.testing.CaptureSaveFixture
import com.zhenghuaizhi.flashskip.testing.CommitGate
import com.zhenghuaizhi.flashskip.testing.SESSION_ID
import com.zhenghuaizhi.flashskip.testing.SaveSnapshot
import com.zhenghuaizhi.flashskip.testing.TARGET
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(Parameterized::class)
class RuleCaptureSaveTest(private val phase: CapturePhase) {
    @Test
    fun ac01_saveClosesTargetBeforeAppendingOneSampleAndPreservesOtherData() = runTest {
        val fixture = fixture()
        val beforeClose = CommitGate()
        fixture.flashStore.beforeCommit = beforeClose::pause

        val saving = async { fixture.coordinator.finish(SESSION_ID) }
        beforeClose.entered.await()

        assertEquals(listOf("switch:attempt"), fixture.events)
        assertEquals(0, fixture.captureStore.attempts)
        assertEquals(fixture.initialCapture, fixture.captureStore.state.value)
        assertEquals(fixture.initialPreferences, fixture.flashStore.preferences.value)

        beforeClose.release.complete(Unit)
        assertEquals(CaptureOperationResult.Success(), saving.await())

        assertEquals(
            listOf("switch:attempt", "switch:commit", "capture:attempt", "capture:commit"),
            fixture.events,
        )
        val switchCommit = fixture.commits.single { it.point == "switch:commit" }
        assertEquals(fixture.initialCapture, switchCommit.capture)
        assertEquals(disabledPreferences(fixture), switchCommit.preferences)
        assertSaved(fixture, fixture.commits.single { it.point == "capture:commit" })
        assertEquals(listOf(TARGET to false), fixture.flashStore.writes)
        assertEquals(1, fixture.captureStore.writes.size)
        // 目录尚未同步时，保存成功本身不能使目标获得开启资格。
        assertNotEquals(fixture.captureStore.state.value.samples, fixture.catalog.snapshot.value.sourceSamples)
        assertEquals(
            CaptureOperationResult.Failure(CaptureOperationFailure.RULES_UPDATING),
            fixture.coordinator.setAppEnabled(TARGET, true),
        )
        assertEquals(listOf(TARGET to false), fixture.flashStore.writes)
    }

    @Test
    fun ac02_closeFailurePreservesEnabledOrDisabledStateWithoutCaptureWrite() = runTest {
        for (enabled in listOf(true, false)) {
            val fixture = fixture(targetEnabled = enabled)
            fixture.flashStore.beforeCommit = { throw IOException("关闭提交未发生") }

            assertStorageFailure(fixture.coordinator.finish(SESSION_ID))

            assertEquals(listOf("switch:attempt"), fixture.events)
            assertEquals(1, fixture.flashStore.attempts.size)
            assertTrue(fixture.flashStore.writes.isEmpty())
            assertEquals(0, fixture.captureStore.attempts)
            assertTrue(fixture.captureStore.writes.isEmpty())
            assertTrue(fixture.commits.isEmpty())
            assertEquals(fixture.initialPreferences, fixture.flashStore.preferences.value)
            assertCaptureUnchangedAndBlocking(fixture)
        }
    }

    @Test
    fun ac03_captureFailureKeepsTargetDisabledAndPendingEvidenceIntact() = runTest {
        val fixture = fixture()
        fixture.captureStore.beforeCommit = { throw IOException("样本提交未发生") }

        assertStorageFailure(fixture.coordinator.finish(SESSION_ID))

        assertEquals(listOf("switch:attempt", "switch:commit", "capture:attempt"), fixture.events)
        assertEquals(listOf(TARGET to false), fixture.flashStore.writes)
        assertTrue(fixture.captureStore.writes.isEmpty())
        assertEquals(disabledPreferences(fixture), fixture.flashStore.preferences.value)
        assertCaptureUnchangedAndBlocking(fixture)
        assertEquals(
            CaptureOperationResult.Failure(CaptureOperationFailure.CAPTURE_IN_PROGRESS),
            fixture.coordinator.setAppEnabled(TARGET, true),
        )
        assertEquals(listOf(TARGET to false), fixture.flashStore.writes)
        assertEquals(1, fixture.captureStore.attempts)
    }

    @Test
    fun ac04_retryAfterCloseFailureSavesOriginalSessionExactlyOnce() = runTest {
        for (enabled in listOf(true, false)) {
            val fixture = fixture(targetEnabled = enabled)
            fixture.flashStore.beforeCommit = { throw IOException("关闭提交未发生") }
            assertStorageFailure(fixture.coordinator.finish(SESSION_ID))
            assertCaptureUnchangedAndBlocking(fixture)

            fixture.flashStore.beforeCommit = {}
            assertEquals(CaptureOperationResult.Success(), fixture.coordinator.finish(SESSION_ID))

            assertSaved(fixture, fixture.commits.single { it.point == "capture:commit" })
            assertEquals(2, fixture.flashStore.attempts.size)
            assertEquals(listOf(TARGET to false), fixture.flashStore.writes)
            assertEquals(1, fixture.captureStore.attempts)
        }
    }

    @Test
    fun ac04_retryAfterCaptureFailureAllowsIdempotentCloseAndSavesExactlyOnce() = runTest {
        val fixture = fixture()
        fixture.captureStore.beforeCommit = { throw IOException("样本提交未发生") }
        assertStorageFailure(fixture.coordinator.finish(SESSION_ID))
        assertCaptureUnchangedAndBlocking(fixture)

        fixture.captureStore.beforeCommit = {}
        assertEquals(CaptureOperationResult.Success(), fixture.coordinator.finish(SESSION_ID))

        assertSaved(fixture, fixture.commits.single { it.point == "capture:commit" })
        assertEquals(listOf(TARGET to false, TARGET to false), fixture.flashStore.writes)
        assertEquals(2, fixture.captureStore.attempts)
        assertEquals(1, fixture.captureStore.writes.size)
    }

    @Test
    fun ac05_sequentialDuplicateIsStaleAndDoesNotWriteEitherStore() = runTest {
        val fixture = fixture()
        assertEquals(CaptureOperationResult.Success(), fixture.coordinator.finish(SESSION_ID))
        val saved = fixture.captureStore.state.value
        val preferences = fixture.flashStore.preferences.value
        val events = fixture.events.toList()

        assertEquals(CaptureOperationResult.Stale, fixture.coordinator.finish(SESSION_ID))

        assertEquals(events, fixture.events)
        assertEquals(saved, fixture.captureStore.state.value)
        assertEquals(preferences, fixture.flashStore.preferences.value)
        assertEquals(1, fixture.captureStore.attempts)
        assertEquals(1, fixture.flashStore.attempts.size)
        assertSaved(fixture, fixture.commits.single { it.point == "capture:commit" })
    }

    @Test
    fun ac05_concurrentDuplicateWaitsForLockThenBecomesStaleWithoutWriting() = runTest {
        val fixture = fixture()
        val gate = CommitGate()
        fixture.captureStore.beforeCommit = gate::pause
        val first = async { fixture.coordinator.finish(SESSION_ID) }
        gate.entered.await()
        val second = async { fixture.coordinator.finish(SESSION_ID) }
        runCurrent()

        assertFalse(second.isCompleted)
        assertEquals(1, fixture.captureStore.attempts)
        assertEquals(1, fixture.flashStore.attempts.size)
        gate.release.complete(Unit)

        assertEquals(CaptureOperationResult.Success(), first.await())
        assertEquals(CaptureOperationResult.Stale, second.await())
        assertEquals(1, fixture.captureStore.attempts)
        assertEquals(1, fixture.flashStore.attempts.size)
        assertSaved(fixture, fixture.commits.single { it.point == "capture:commit" })
    }

    @Test
    fun ac05_missingPayloadIsStaleWithoutAnyWrite() = runTest {
        val fixture = fixture()
        val session = requireNotNull(fixture.initialCapture.activeSession)
        val state = fixture.initialCapture.copy(
            activeSession = when (phase) {
                CapturePhase.CAPTURED -> session.copy(pendingSample = null)
                CapturePhase.AWAITING_CONFIRMATION -> session.copy(pendingObservedCandidate = null)
                else -> error("测试阶段必须可以保存")
            },
        )
        fixture.captureStore.state.value = state

        assertEquals(CaptureOperationResult.Stale, fixture.coordinator.finish(SESSION_ID))

        assertEquals(state, fixture.captureStore.state.value)
        assertEquals(fixture.initialPreferences, fixture.flashStore.preferences.value)
        assertTrue(fixture.events.isEmpty())
        assertEquals(0, fixture.captureStore.attempts)
        assertEquals(0, fixture.flashStore.attempts.size)
    }

    @Test
    fun ac05_invalidationCommitsFirstAndQueuedSaveIsStale() = runTest {
        for (invalidation in Invalidation.entries) {
            val fixture = fixture()
            val key = prepareInvalidation(fixture, invalidation)
            val gate = CommitGate()
            fixture.captureStore.beforeCommit = gate::pause
            val invalidating = async { invalidate(fixture, invalidation, key) }
            gate.entered.await()
            val saving = async { fixture.coordinator.finish(SESSION_ID) }
            runCurrent()

            assertFalse(saving.isCompleted)
            assertEquals(0, fixture.flashStore.attempts.size)
            gate.release.complete(Unit)
            assertTrue(invalidating.await() is CaptureOperationResult.Success)
            val afterInvalidation = fixture.captureStore.state.value
            val events = fixture.events.toList()

            assertEquals(CaptureOperationResult.Stale, saving.await())
            assertEquals(events, fixture.events)
            assertEquals(afterInvalidation, fixture.captureStore.state.value)
            assertEquals(fixture.initialCapture.samples, afterInvalidation.samples)
            assertEquals(fixture.initialCapture.semanticDiffEvidence, afterInvalidation.semanticDiffEvidence)
            assertEquals(fixture.initialPreferences, fixture.flashStore.preferences.value)
            assertEquals(1, fixture.captureStore.attempts)
            assertEquals(0, fixture.flashStore.attempts.size)
            if (invalidation == Invalidation.CANCEL) {
                assertNull(afterInvalidation.activeSession)
            } else {
                assertEquals(CapturePhase.FAILED, afterInvalidation.activeSession?.phase)
                assertNull(afterInvalidation.activeSession?.pendingSample)
                assertNull(afterInvalidation.activeSession?.pendingObservedCandidate)
                assertEquals(
                    if (invalidation == Invalidation.FAIL) CaptureFailure.SERVICE_UNAVAILABLE
                    else CaptureFailure.COMPLETION_TIMEOUT,
                    afterInvalidation.activeSession?.failure,
                )
            }
        }
    }

    @Test
    fun ac05_saveCommitsFirstAndQueuedOldInvalidationCannotRemoveSample() = runTest {
        for (invalidation in Invalidation.entries) {
            val fixture = fixture()
            val key = prepareInvalidation(fixture, invalidation)
            val gate = CommitGate()
            fixture.captureStore.beforeCommit = gate::pause
            val saving = async { fixture.coordinator.finish(SESSION_ID) }
            gate.entered.await()
            val invalidating = async { invalidate(fixture, invalidation, key) }
            runCurrent()

            assertFalse(invalidating.isCompleted)
            gate.release.complete(Unit)
            assertEquals(CaptureOperationResult.Success(), saving.await())
            val saved = fixture.captureStore.state.value
            val events = fixture.events.toList()

            assertEquals(CaptureOperationResult.Stale, invalidating.await())
            assertEquals(events, fixture.events)
            assertEquals(saved, fixture.captureStore.state.value)
            assertEquals(1, fixture.captureStore.attempts)
            assertEquals(1, fixture.flashStore.attempts.size)
            assertSaved(fixture, fixture.commits.single { it.point == "capture:commit" })
        }
    }

    @Test
    fun ac05_replacedSessionRejectsOldIdWithoutChangingNewSessionOrOtherData() = runTest {
        val fixture = fixture()
        assertEquals(CaptureOperationResult.Success(), fixture.coordinator.cancel(SESSION_ID))
        val started = fixture.coordinator.requestStart() as CaptureOperationResult.Success
        val newId = requireNotNull(started.sessionId)
        assertNotEquals(SESSION_ID, newId)
        assertEquals(CaptureOperationResult.Success(newId), fixture.coordinator.markConfigured(newId))
        recordNewPayload(fixture, newId)
        val replaced = fixture.captureStore.state.value
        val events = fixture.events.toList()
        val attempts = fixture.captureStore.attempts

        assertEquals(CaptureOperationResult.Stale, fixture.coordinator.finish(SESSION_ID))

        assertEquals(events, fixture.events)
        assertEquals(attempts, fixture.captureStore.attempts)
        assertEquals(0, fixture.flashStore.attempts.size)
        assertEquals(replaced, fixture.captureStore.state.value)
        assertEquals(fixture.initialPreferences, fixture.flashStore.preferences.value)
        assertEquals(fixture.initialCapture.samples, replaced.samples)
        assertEquals(fixture.initialCapture.semanticDiffEvidence, replaced.semanticDiffEvidence)
    }

    @Test
    fun ac05_laterLegalSessionForSameTargetCanSaveAnotherSample() = runTest {
        val fixture = fixture()
        assertEquals(CaptureOperationResult.Success(), fixture.coordinator.finish(SESSION_ID))
        val started = fixture.coordinator.requestStart() as CaptureOperationResult.Success
        val newId = requireNotNull(started.sessionId)
        assertEquals(CaptureOperationResult.Success(newId), fixture.coordinator.markConfigured(newId))
        recordNewPayload(fixture, newId)

        assertEquals(CaptureOperationResult.Success(), fixture.coordinator.finish(newId))

        val saved = fixture.captureStore.state.value
        assertEquals(fixture.initialCapture.samples + fixture.expectedSample + fixture.expectedSample.copy(id = newId), saved.samples)
        assertEquals(1, saved.samples.count { it.id == SESSION_ID })
        assertEquals(1, saved.samples.count { it.id == newId })
        assertNull(saved.activeSession)
        assertEquals(disabledPreferences(fixture), fixture.flashStore.preferences.value)
        assertEquals(listOf(TARGET to false, TARGET to false), fixture.flashStore.writes)
    }

    @Test
    fun ac07_payloadAndCatalogPublicationDoNotSaveWithoutExplicitConfirmation() = runTest {
        val fixture = fixture()
        val session = requireNotNull(fixture.initialCapture.activeSession)
        fixture.captureStore.state.value = fixture.initialCapture.copy(
            activeSession = session.copy(
                phase = CapturePhase.WAITING_FOR_TARGET,
                pendingSample = null,
                pendingObservedCandidate = null,
            ),
        )

        recordNewPayload(fixture, SESSION_ID)
        fixture.catalog.publish(fixture.captureStore.state.value.samples)
        runCurrent()

        assertEquals(fixture.initialCapture.samples, fixture.captureStore.state.value.samples)
        assertEquals(phase, fixture.captureStore.state.value.activeSession?.phase)
        assertTrue(fixture.flashStore.writes.isEmpty())
        assertEquals(0, fixture.flashStore.attempts.size)
        val captureAttempts = fixture.captureStore.attempts

        assertEquals(CaptureOperationResult.Success(), fixture.coordinator.finish(SESSION_ID))

        assertEquals(captureAttempts + 1, fixture.captureStore.attempts)
        val sample = fixture.captureStore.state.value.samples.single { it.id == SESSION_ID }
        assertEquals(fixture.expectedSample, sample)
        assertEquals(
            if (phase == CapturePhase.CAPTURED) CaptureEvidence.ACCESSIBILITY_CLICK
            else CaptureEvidence.USER_CONFIRMED_UNREPORTED_CLICK,
            sample.evidence,
        )
        assertSaved(fixture, fixture.commits.last { it.point == "capture:commit" })
    }

    private fun TestScope.fixture(targetEnabled: Boolean = true) = CaptureSaveFixture(
        backgroundScope,
        testScheduler,
        phase = phase,
        targetEnabled = targetEnabled,
    )

    private fun assertCaptureUnchangedAndBlocking(fixture: CaptureSaveFixture) {
        assertEquals(fixture.initialCapture, fixture.captureStore.state.value)
        assertTrue(requireNotNull(fixture.captureStore.state.value.activeSession).blocksAutomation)
    }

    private fun assertStorageFailure(result: CaptureOperationResult) {
        assertEquals(CaptureOperationResult.Failure(CaptureOperationFailure.STORAGE), result)
    }

    private fun disabledPreferences(fixture: CaptureSaveFixture) = fixture.initialPreferences.copy(
        enabledApps = fixture.initialPreferences.enabledApps + (TARGET to false),
    )

    private fun assertSaved(fixture: CaptureSaveFixture, snapshot: SaveSnapshot) {
        assertEquals(
            fixture.initialCapture.copy(
                samples = fixture.initialCapture.samples + fixture.expectedSample,
                activeSession = null,
                semanticDiffEvidence = fixture.initialCapture.semanticDiffEvidence.filterNot {
                    it.targetAppId == TARGET
                },
            ),
            snapshot.capture,
        )
        assertEquals(disabledPreferences(fixture), snapshot.preferences)
        assertEquals(1, snapshot.capture.samples.count { it.id == SESSION_ID })
        assertEquals(fixture.expectedSample, snapshot.capture.samples.single { it.id == SESSION_ID })
    }

    private suspend fun TestScope.prepareInvalidation(
        fixture: CaptureSaveFixture,
        invalidation: Invalidation,
    ): CaptureExpiryKey {
        val key = CaptureExpiryKey.from(requireNotNull(fixture.captureStore.state.value.activeSession))
        if (invalidation == Invalidation.EXPIRE) {
            val plan = requireNotNull(fixture.coordinator.expiryPlan(key))
            // 只推进既有失效转换的调度时间，不对保存新增严格截止判断。
            advanceTimeBy(plan.deadlineAtElapsedMs - testScheduler.currentTime)
        }
        return key
    }

    private suspend fun invalidate(
        fixture: CaptureSaveFixture,
        invalidation: Invalidation,
        key: CaptureExpiryKey,
    ): CaptureOperationResult = when (invalidation) {
        Invalidation.CANCEL -> fixture.coordinator.cancel(SESSION_ID)
        Invalidation.FAIL -> fixture.coordinator.fail(SESSION_ID, CaptureFailure.SERVICE_UNAVAILABLE)
        Invalidation.EXPIRE -> fixture.coordinator.expireExpected(key)
    }

    private suspend fun recordNewPayload(fixture: CaptureSaveFixture, sessionId: Long) {
        val original = requireNotNull(fixture.initialCapture.activeSession)
        val result = when (phase) {
            CapturePhase.CAPTURED -> fixture.coordinator.recordCaptured(
                sessionId,
                requireNotNull(original.pendingSample).copy(id = sessionId),
            )
            CapturePhase.AWAITING_CONFIRMATION -> fixture.coordinator.recordObservedCandidateResult(
                sessionId,
                requireNotNull(original.pendingObservedCandidate).copy(id = sessionId),
            )
            else -> error("测试阶段必须可以保存")
        }
        assertEquals(CaptureOperationResult.Success(sessionId), result)
    }

    private enum class Invalidation { CANCEL, FAIL, EXPIRE }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "phase={0}")
        fun phases(): List<Array<CapturePhase>> = listOf(
            arrayOf(CapturePhase.CAPTURED),
            arrayOf(CapturePhase.AWAITING_CONFIRMATION),
        )
    }
}
