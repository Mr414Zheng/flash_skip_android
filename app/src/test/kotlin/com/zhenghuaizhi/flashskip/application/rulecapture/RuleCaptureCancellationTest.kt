package com.zhenghuaizhi.flashskip.application.rulecapture

import com.zhenghuaizhi.flashskip.domain.model.CapturePhase
import com.zhenghuaizhi.flashskip.testing.CaptureSaveFixture
import com.zhenghuaizhi.flashskip.testing.CommitGate
import com.zhenghuaizhi.flashskip.testing.SESSION_ID
import com.zhenghuaizhi.flashskip.testing.TARGET
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
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
class RuleCaptureCancellationTest(private val phase: CapturePhase) {
    @Test
    fun ac03_ac04_cancellationBetweenCommitsPreservesEvidenceAndReleasesLockForRetry() = runTest {
        val fixture = CaptureSaveFixture(backgroundScope, testScheduler, phase)
        val gate = CommitGate()
        fixture.captureStore.beforeCommit = gate::pause
        var cancellationPropagated = false
        var returned: CaptureOperationResult? = null
        val saving = async {
            try {
                fixture.coordinator.finish(SESSION_ID).also { returned = it }
            } catch (cancelled: CancellationException) {
                cancellationPropagated = true
                throw cancelled
            }
        }
        runCurrent()
        assertTrue(gate.entered.isCompleted)
        assertFalse(saving.isCompleted)
        assertEquals(listOf(TARGET to false), fixture.flashStore.writes)
        assertTrue(fixture.captureStore.writes.isEmpty())
        assertEquals(fixture.initialCapture, fixture.captureStore.state.value)

        saving.cancelAndJoin()
        assertTrue(cancellationPropagated)
        assertNull(returned)
        assertFalse(fixture.flashStore.isAppEnabled(TARGET))
        assertEquals(fixture.initialCapture, fixture.coordinator.state.first())
        assertTrue(fixture.captureStore.state.value.activeSession!!.blocksAutomation)

        // 已取消的协程不能用于重试；屏障解除后也不能复活原保存动作。
        gate.release.complete(Unit)
        runCurrent()
        assertTrue(fixture.captureStore.writes.isEmpty())
        fixture.captureStore.beforeCommit = {}
        val retry = async { fixture.coordinator.finish(SESSION_ID) }
        runCurrent()
        assertTrue("取消后协调器锁必须释放", retry.isCompleted)
        assertEquals(CaptureOperationResult.Success(), retry.await())
        assertSaved(fixture)
        assertEquals(1, fixture.captureStore.writes.size)
    }

    @Test
    fun ac08_cancellationAfterCommitPropagatesWithoutRollingBackOrDuplicatingSample() = runTest {
        val fixture = CaptureSaveFixture(backgroundScope, testScheduler, phase)
        val gate = CommitGate()
        fixture.captureStore.afterCommit = gate::pause
        var cancellationPropagated = false
        var returned: CaptureOperationResult? = null
        val saving = async {
            try {
                fixture.coordinator.finish(SESSION_ID).also { returned = it }
            } catch (cancelled: CancellationException) {
                cancellationPropagated = true
                throw cancelled
            }
        }
        runCurrent()
        assertTrue(gate.entered.isCompleted)
        assertFalse(saving.isCompleted)
        assertSaved(fixture)
        val committed = fixture.commits.single { it.point == "capture:commit" }
        assertEquals(fixture.initialCapture.samples + fixture.expectedSample, committed.capture.samples)
        assertNull(committed.capture.activeSession)
        assertEquals(false, committed.preferences.enabledApps[TARGET])

        saving.cancelAndJoin()
        assertTrue(cancellationPropagated)
        assertNull(returned)
        fixture.captureStore.afterCommit = {}
        gate.release.complete(Unit)
        assertSaved(fixture)
        val attemptsBeforeRetry = fixture.events.toList()
        assertEquals(CaptureOperationResult.Stale, fixture.coordinator.finish(SESSION_ID))
        assertEquals(attemptsBeforeRetry, fixture.events)
        // 通过重新读取端口状态确认具体样本；Stale 本身不能证明保存成功。
        val reread = fixture.coordinator.state.first()
        assertEquals(fixture.expectedSample, reread.samples.single { it.id == SESSION_ID })
        assertEquals(fixture.initialCapture.samples + fixture.expectedSample, reread.samples)
    }

    @Test
    fun ac08_ignoredSuccessfulReturnStillMakesReplayStaleAndWriteFree() = runTest {
        val fixture = CaptureSaveFixture(backgroundScope, testScheduler, phase)
        fixture.coordinator.finish(SESSION_ID)
        assertSaved(fixture)
        val attempts = fixture.events.toList()

        assertEquals(CaptureOperationResult.Stale, fixture.coordinator.finish(SESSION_ID))

        assertEquals(attempts, fixture.events)
        assertSaved(fixture)
        assertEquals(1, fixture.captureStore.writes.size)
    }

    @Test
    fun ac08_replayAfterLostResultDoesNotClearSubsequentSession() = runTest {
        val fixture = CaptureSaveFixture(backgroundScope, testScheduler, phase)
        val gate = CommitGate()
        fixture.captureStore.afterCommit = gate::pause
        val saving = async { fixture.coordinator.finish(SESSION_ID) }
        runCurrent()
        assertTrue(gate.entered.isCompleted)
        assertSaved(fixture)
        saving.cancelAndJoin()
        fixture.captureStore.afterCommit = {}

        val started = fixture.coordinator.requestStart() as CaptureOperationResult.Success
        assertNotEquals(SESSION_ID, started.sessionId)
        val newState = fixture.coordinator.state.first()
        assertEquals(started.sessionId, newState.activeSession!!.id)
        val attempts = fixture.events.toList()

        assertEquals(CaptureOperationResult.Stale, fixture.coordinator.finish(SESSION_ID))

        assertEquals(attempts, fixture.events)
        assertEquals(newState, fixture.coordinator.state.first())
        assertEquals(fixture.initialCapture.samples + fixture.expectedSample, newState.samples)
    }

    @Test
    fun ac08_replayAfterLostResultDoesNotDisableSubsequentExplicitEnable() = runTest {
        val fixture = CaptureSaveFixture(backgroundScope, testScheduler, phase)
        val gate = CommitGate()
        fixture.captureStore.afterCommit = gate::pause
        val saving = async { fixture.coordinator.finish(SESSION_ID) }
        runCurrent()
        assertTrue(gate.entered.isCompleted)
        assertSaved(fixture)
        saving.cancelAndJoin()
        fixture.captureStore.afterCommit = {}
        fixture.catalog.publish(fixture.captureStore.state.value.samples)

        assertEquals(CaptureOperationResult.Success(), fixture.coordinator.setAppEnabled(TARGET, true))
        val enabledState = fixture.flashStore.preferences.value
        val attempts = fixture.events.toList()
        assertEquals(CaptureOperationResult.Stale, fixture.coordinator.finish(SESSION_ID))

        assertEquals(attempts, fixture.events)
        assertEquals(enabledState, fixture.flashStore.preferences.value)
        assertTrue(fixture.flashStore.isAppEnabled(TARGET))
        assertEquals(false, fixture.commits.single { it.point == "capture:commit" }.preferences.enabledApps[TARGET])
        assertEquals(fixture.initialCapture.samples + fixture.expectedSample, fixture.captureStore.state.value.samples)
    }

    private fun assertSaved(fixture: CaptureSaveFixture) {
        val state = fixture.captureStore.state.value
        assertEquals(fixture.initialCapture.samples + fixture.expectedSample, state.samples)
        assertNull(state.activeSession)
        assertEquals(
            fixture.initialCapture.semanticDiffEvidence.filterNot { it.targetAppId == TARGET },
            state.semanticDiffEvidence,
        )
        assertEquals(
            fixture.initialPreferences.copy(enabledApps = fixture.initialPreferences.enabledApps + (TARGET to false)),
            fixture.flashStore.preferences.value,
        )
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun phases(): List<Array<Any>> = listOf(
            arrayOf(CapturePhase.CAPTURED),
            arrayOf(CapturePhase.AWAITING_CONFIRMATION),
        )
    }
}
