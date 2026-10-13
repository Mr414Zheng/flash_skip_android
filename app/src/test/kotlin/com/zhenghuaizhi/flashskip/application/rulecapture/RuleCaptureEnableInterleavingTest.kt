package com.zhenghuaizhi.flashskip.application.rulecapture

import com.zhenghuaizhi.flashskip.domain.model.CapturePhase
import com.zhenghuaizhi.flashskip.domain.model.RuleCardStatus
import com.zhenghuaizhi.flashskip.testing.CaptureSaveFixture
import com.zhenghuaizhi.flashskip.testing.CommitGate
import com.zhenghuaizhi.flashskip.testing.SESSION_ID
import com.zhenghuaizhi.flashskip.testing.TARGET
import com.zhenghuaizhi.flashskip.testing.captureSample
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(Parameterized::class)
class RuleCaptureEnableInterleavingTest(private val phase: CapturePhase) {
    @Test
    fun enableBeforeSaveIsRejectedWhileCaptureStillBlocksAutomation() = runTest {
        val fixture = CaptureSaveFixture(backgroundScope, testScheduler, phase, targetEnabled = false)
        fixture.catalog.publish(fixture.initialCapture.samples)
        assertReadyCatalog(fixture)

        val result = fixture.coordinator.setAppEnabled(TARGET, true)

        assertEquals(failure(CaptureOperationFailure.CAPTURE_IN_PROGRESS), result)
        assertEquals(fixture.initialCapture, fixture.captureStore.state.value)
        assertEquals(fixture.initialPreferences, fixture.flashStore.preferences.value)
        assertTrue(fixture.captureStore.state.value.activeSession!!.blocksAutomation)
        assertEquals(0, fixture.flashStore.attempts.size)
        assertEquals(0, fixture.captureStore.attempts)
        assertTrue(fixture.events.isEmpty())
    }

    @Test
    fun enableWaitsBehindSaveThenRejectsUnsynchronizedCatalogWithoutReplay() = runTest {
        val fixture = CaptureSaveFixture(backgroundScope, testScheduler, phase)
        fixture.catalog.publish(fixture.initialCapture.samples)
        assertReadyCatalog(fixture)
        val gate = CommitGate()
        fixture.captureStore.beforeCommit = { gate.pause() }

        val save = async { fixture.coordinator.finish(SESSION_ID) }
        runCurrent()
        assertTrue(gate.entered.isCompleted)
        assertEquals(fixture.initialCapture, fixture.captureStore.state.value)
        assertFalse(fixture.flashStore.preferences.value.enabledApps.getValue(TARGET))
        val enable = async { fixture.coordinator.setAppEnabled(TARGET, true) }
        runCurrent()

        assertFalse(enable.isCompleted)
        assertEquals(listOf(TARGET to false), fixture.flashStore.writes)
        assertEquals(1, fixture.flashStore.attempts.size)
        assertEquals(1, fixture.captureStore.attempts)
        assertEquals(listOf("switch:attempt", "switch:commit", "capture:attempt"), fixture.events)

        gate.release.complete(Unit)
        assertEquals(CaptureOperationResult.Success(), save.await())
        assertEquals(failure(CaptureOperationFailure.RULES_UPDATING), enable.await())
        assertSavedWhileDisabled(fixture)
        val writesAfterRejection = fixture.flashStore.writes.toList()

        fixture.catalog.publish(fixture.captureStore.state.value.samples)
        runCurrent()

        assertReadyCatalog(fixture)
        assertFalse(fixture.flashStore.preferences.value.enabledApps.getValue(TARGET))
        assertEquals(writesAfterRejection, fixture.flashStore.writes)
        assertEquals(1, fixture.flashStore.attempts.size)
        assertEquals(1, fixture.captureStore.attempts)
    }

    @Test
    fun queuedExplicitEnableUsesLatestReadyCatalogAfterSaveReleasesLock() = runTest {
        val fixture = CaptureSaveFixture(backgroundScope, testScheduler, phase)
        fixture.catalog.publish(fixture.initialCapture.samples)
        val gate = CommitGate()
        fixture.captureStore.afterCommit = { gate.pause() }

        val save = async { fixture.coordinator.finish(SESSION_ID) }
        runCurrent()
        assertTrue(gate.entered.isCompleted)
        assertFalse(save.isCompleted)
        assertSavedWhileDisabled(fixture)
        val enable = async { fixture.coordinator.setAppEnabled(TARGET, true) }
        runCurrent()
        assertFalse(enable.isCompleted)
        assertEquals(1, fixture.flashStore.attempts.size)

        // 目录在保存仍持有操作锁时就绪，排队请求取得锁后必须重读最新快照。
        fixture.catalog.publish(fixture.captureStore.state.value.samples)
        runCurrent()
        assertReadyCatalog(fixture)
        assertFalse(enable.isCompleted)
        assertEquals(listOf(TARGET to false), fixture.flashStore.writes)

        gate.release.complete(Unit)
        assertEquals(CaptureOperationResult.Success(), save.await())
        assertEquals(CaptureOperationResult.Success(), enable.await())

        assertSavedWhileDisabled(fixture)
        assertEquals(listOf(TARGET to false, TARGET to true), fixture.flashStore.writes)
        assertEquals(
            listOf("switch:attempt", "switch:commit", "capture:attempt", "capture:commit", "switch:attempt", "switch:commit"),
            fixture.events,
        )
        assertEquals(
            fixture.initialPreferences.copy(enabledApps = fixture.initialPreferences.enabledApps + (TARGET to true)),
            fixture.flashStore.preferences.value,
        )
        assertEquals("switch:commit", fixture.commits.last().point)
        assertTrue(fixture.commits.last().preferences.enabledApps.getValue(TARGET))
        assertEquals(savedCapture(fixture), fixture.commits.last().capture)
    }

    @Test
    fun rejectedEnableRemainsRejectedUntilAnotherExplicitRequest() = runTest {
        val fixture = CaptureSaveFixture(backgroundScope, testScheduler, phase)
        fixture.catalog.publish(fixture.initialCapture.samples)
        assertEquals(CaptureOperationResult.Success(), fixture.coordinator.finish(SESSION_ID))

        assertEquals(
            failure(CaptureOperationFailure.RULES_UPDATING),
            fixture.coordinator.setAppEnabled(TARGET, true),
        )
        assertEquals(listOf(TARGET to false), fixture.flashStore.writes)
        fixture.catalog.publish(fixture.captureStore.state.value.samples)
        runCurrent()

        assertReadyCatalog(fixture)
        assertEquals(listOf(TARGET to false), fixture.flashStore.writes)
        assertFalse(fixture.flashStore.preferences.value.enabledApps.getValue(TARGET))
        assertEquals(CaptureOperationResult.Success(), fixture.coordinator.setAppEnabled(TARGET, true))
        assertEquals(listOf(TARGET to false, TARGET to true), fixture.flashStore.writes)
        assertTrue(fixture.flashStore.preferences.value.enabledApps.getValue(TARGET))
        assertSavedWhileDisabled(fixture)
    }

    @Test
    fun synchronizedCandidateCatalogRejectsEnableWithoutWriting() = runTest {
        val fixture = CaptureSaveFixture(backgroundScope, testScheduler, phase, targetEnabled = false)
        val candidate = captureSample().let { sample ->
            sample.copy(
                nodes = sample.nodes.map { it.copy(resourceId = null) },
                preClickUniqueSelectors = emptyList(),
            )
        }
        val state = fixture.initialCapture.copy(
            samples = fixture.initialCapture.samples.filterNot { it.targetAppId == TARGET } + candidate,
            activeSession = null,
        )
        fixture.captureStore.state.value = state
        fixture.catalog.publish(state.samples)
        assertEquals(state.samples, fixture.catalog.snapshot.value.sourceSamples)
        assertEquals(
            RuleCardStatus.CANDIDATE,
            fixture.catalog.snapshot.value.capturedTargets.single { it.targetAppId == TARGET }.analysis.status,
        )

        val result = fixture.coordinator.setAppEnabled(TARGET, true)

        assertEquals(failure(CaptureOperationFailure.RULE_NOT_READY), result)
        assertEquals(state, fixture.captureStore.state.value)
        assertEquals(fixture.initialPreferences, fixture.flashStore.preferences.value)
        assertEquals(0, fixture.flashStore.attempts.size)
        assertEquals(0, fixture.captureStore.attempts)
    }

    @Test
    fun synchronizedReadyAnalysisWithNoFinalTargetRulesRejectsEnable() = runTest {
        val fixture = CaptureSaveFixture(backgroundScope, testScheduler, phase)
        assertEquals(CaptureOperationResult.Success(), fixture.coordinator.finish(SESSION_ID))
        fixture.catalog.publish(fixture.captureStore.state.value.samples)
        assertReadyCatalog(fixture)
        val ready = fixture.catalog.snapshot.value
        // 最终规则可能经过整体校验而被移除；READY 分析不能独自授权开启。
        fixture.catalog.snapshot.value = ready.copy(rules = ready.rules.filterNot { it.targetAppId == TARGET })
        val beforeEnable = fixture.flashStore.preferences.value

        val result = fixture.coordinator.setAppEnabled(TARGET, true)

        assertEquals(failure(CaptureOperationFailure.RULE_NOT_READY), result)
        assertEquals(fixture.captureStore.state.value.samples, fixture.catalog.snapshot.value.sourceSamples)
        assertEquals(RuleCardStatus.READY, ready.capturedTargets.single { it.targetAppId == TARGET }.analysis.status)
        assertTrue(fixture.catalog.snapshot.value.rulesForTarget(TARGET).isEmpty())
        assertEquals(beforeEnable, fixture.flashStore.preferences.value)
        assertEquals(listOf(TARGET to false), fixture.flashStore.writes)
        assertEquals(1, fixture.flashStore.attempts.size)
        assertEquals(1, fixture.captureStore.attempts)
        assertSavedWhileDisabled(fixture)
    }

    @Test
    fun saveAndReadyCatalogPublicationNeverImplicitlyEnableTarget() = runTest {
        val fixture = CaptureSaveFixture(backgroundScope, testScheduler, phase)
        fixture.catalog.publish(fixture.initialCapture.samples)

        assertEquals(CaptureOperationResult.Success(), fixture.coordinator.finish(SESSION_ID))
        assertSavedWhileDisabled(fixture)
        fixture.catalog.publish(fixture.captureStore.state.value.samples)
        runCurrent()

        assertReadyCatalog(fixture)
        assertFalse(fixture.flashStore.preferences.value.enabledApps.getValue(TARGET))
        assertEquals(listOf(TARGET to false), fixture.flashStore.writes)
        assertEquals(1, fixture.flashStore.attempts.size)
        assertEquals(1, fixture.captureStore.attempts)
    }

    @Test
    fun partialFailureRetryAndCatalogPublicationNeverImplicitlyEnableTarget() = runTest {
        val fixture = CaptureSaveFixture(backgroundScope, testScheduler, phase)
        fixture.captureStore.beforeCommit = { throw IOException("save did not commit") }

        assertEquals(failure(CaptureOperationFailure.STORAGE), fixture.coordinator.finish(SESSION_ID))
        assertEquals(fixture.initialCapture, fixture.captureStore.state.value)
        assertFalse(fixture.flashStore.preferences.value.enabledApps.getValue(TARGET))
        fixture.catalog.publish(fixture.initialCapture.samples)
        runCurrent()
        assertEquals(
            failure(CaptureOperationFailure.CAPTURE_IN_PROGRESS),
            fixture.coordinator.setAppEnabled(TARGET, true),
        )
        assertEquals(listOf(TARGET to false), fixture.flashStore.writes)

        fixture.captureStore.beforeCommit = {}
        assertEquals(CaptureOperationResult.Success(), fixture.coordinator.finish(SESSION_ID))
        fixture.catalog.publish(fixture.captureStore.state.value.samples)
        runCurrent()

        assertReadyCatalog(fixture)
        assertSavedWhileDisabled(fixture)
        assertEquals(listOf(TARGET to false, TARGET to false), fixture.flashStore.writes)
        assertFalse(fixture.flashStore.preferences.value.enabledApps.getValue(TARGET))
        assertEquals(2, fixture.flashStore.attempts.size)
        assertEquals(2, fixture.captureStore.attempts)
    }

    private fun assertReadyCatalog(fixture: CaptureSaveFixture) {
        val snapshot = fixture.catalog.snapshot.value
        assertEquals(fixture.captureStore.state.value.samples, snapshot.sourceSamples)
        assertEquals(
            RuleCardStatus.READY,
            snapshot.capturedTargets.single { it.targetAppId == TARGET }.analysis.status,
        )
        assertTrue(snapshot.rulesForTarget(TARGET).isNotEmpty())
    }

    private fun assertSavedWhileDisabled(fixture: CaptureSaveFixture) {
        val saved = fixture.commits.single { it.point == "capture:commit" }
        assertEquals(savedCapture(fixture), saved.capture)
        assertNull(saved.capture.activeSession)
        assertEquals(
            fixture.initialPreferences.copy(enabledApps = fixture.initialPreferences.enabledApps + (TARGET to false)),
            saved.preferences,
        )
    }

    private fun savedCapture(fixture: CaptureSaveFixture) = fixture.initialCapture.copy(
        samples = fixture.initialCapture.samples + fixture.expectedSample,
        activeSession = null,
        semanticDiffEvidence = fixture.initialCapture.semanticDiffEvidence.filterNot { it.targetAppId == TARGET },
    )

    private fun failure(reason: CaptureOperationFailure) = CaptureOperationResult.Failure(reason)

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun phases(): Collection<Array<Any>> = listOf(
            arrayOf<Any>(CapturePhase.CAPTURED),
            arrayOf<Any>(CapturePhase.AWAITING_CONFIRMATION),
        )
    }
}
