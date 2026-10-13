package com.zhenghuaizhi.flashskip.domain.rules

import com.zhenghuaizhi.flashskip.domain.model.CapturePhase
import com.zhenghuaizhi.flashskip.domain.model.CaptureSession
import com.zhenghuaizhi.flashskip.domain.model.RuleCaptureState
import com.zhenghuaizhi.flashskip.domain.model.RuleCardStatus
import com.zhenghuaizhi.flashskip.domain.model.TargetAppId
import com.zhenghuaizhi.flashskip.domain.rulecapture.RuleCandidateAnalyzer
import com.zhenghuaizhi.flashskip.testing.OTHER_TARGET
import com.zhenghuaizhi.flashskip.testing.TARGET
import com.zhenghuaizhi.flashskip.testing.TestRuleCaptureStore
import com.zhenghuaizhi.flashskip.testing.captureSample
import com.zhenghuaizhi.flashskip.testing.executableRule
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CompositeRuleCatalogTest {
    @Test fun ac04_localRulesPrecedeBuiltinAndSnapshotContainsSameSamplesAndAnalysis() = runTest {
        val sample = captureSample()
        val store = TestRuleCaptureStore(RuleCaptureState(samples = listOf(sample)), mutableListOf()) {}
        val builtin = executableRule("builtin")
        val catalog = CompositeRuleCatalog(BuiltinRuleCatalog(listOf(builtin)), store, backgroundScope, StandardTestDispatcher(testScheduler))
        runCurrent()
        val value = catalog.snapshot.value
        assertEquals(listOf(sample), value.sourceSamples)
        assertEquals(RuleCardStatus.READY, value.capturedTargets.single().analysis.status)
        assertEquals(value.capturedTargets.single().analysis.rules + builtin, value.rules)
    }

    @Test fun ac04_duplicateAcrossTargetsDropsAllLocalRulesButKeepsReadyAnalysis() = runTest {
        val sample = captureSample()
        val other = captureSample(101, OTHER_TARGET)
        val localId = RuleCandidateAnalyzer.readyRules(listOf(sample), TARGET).single().id
        val conflictingBuiltin = executableRule(localId, OTHER_TARGET)
        val store = TestRuleCaptureStore(RuleCaptureState(samples = listOf(sample, other)), mutableListOf()) {}
        val catalog = CompositeRuleCatalog(BuiltinRuleCatalog(listOf(conflictingBuiltin)), store, backgroundScope, StandardTestDispatcher(testScheduler))
        runCurrent()
        assertEquals(listOf(conflictingBuiltin), catalog.snapshot.value.rules)
        assertEquals(2, catalog.snapshot.value.capturedTargets.size)
        assertTrue(catalog.snapshot.value.capturedTargets.all { it.analysis.status == RuleCardStatus.READY })
        assertTrue(catalog.snapshot.value.rulesForTarget(TARGET).isEmpty())
    }

    @Test fun ac04_invalidLocalRuleIsFilteredWithoutDroppingUnrelatedValidRule() = runTest {
        val invalid = captureSample().let { it.copy(nodes = listOf(it.nodes.single().copy(resourceId = " "))) }
        val valid = captureSample(101, OTHER_TARGET)
        val store = TestRuleCaptureStore(RuleCaptureState(samples = listOf(invalid, valid)), mutableListOf()) {}
        val catalog = CompositeRuleCatalog(BuiltinRuleCatalog(), store, backgroundScope, StandardTestDispatcher(testScheduler))
        runCurrent()
        assertTrue(catalog.snapshot.value.rulesForTarget(TARGET).isEmpty())
        assertTrue(catalog.snapshot.value.rulesForTarget(OTHER_TARGET).isNotEmpty())
        assertEquals(listOf(invalid, valid), catalog.snapshot.value.sourceSamples)
    }

    @Test fun ac05_sessionOnlyChangeDoesNotPublishAnotherRuleSnapshot() = runTest {
        val store = TestRuleCaptureStore(RuleCaptureState(samples = listOf(captureSample())), mutableListOf()) {}
        val catalog = CompositeRuleCatalog(BuiltinRuleCatalog(), store, backgroundScope, StandardTestDispatcher(testScheduler))
        val seen = mutableListOf<RuleCatalogSnapshot>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { catalog.snapshot.collect { seen += it } }
        runCurrent()
        val before = catalog.snapshot.value
        val count = seen.size
        store.update { it.copy(activeSession = CaptureSession(1, phase = CapturePhase.CONFIGURING, phaseStartedAtEpochMs = 0, deadlineAtEpochMs = 100)) }
        runCurrent()
        assertSame(before, catalog.snapshot.value)
        assertEquals(count, seen.size)
    }

    @Test fun ac05_newSamplesCancelInFlightComputationAndDeletionNeverResurrectsOldRules() = runTest {
        val dispatcher = QueuedDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val old = (1..100).map { captureSample(it.toLong(), TargetAppId("example.target$it")) }
        val store = TestRuleCaptureStore(RuleCaptureState(samples = old), mutableListOf()) {}
        val catalog = CompositeRuleCatalog(BuiltinRuleCatalog(), store, scope, dispatcher)
        val seen = mutableListOf<RuleCatalogSnapshot>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { catalog.snapshot.collect { seen += it } }
        try {
            // 逐个推进调度任务，旧批次已开始逐组计算但尚未经过全部 100 个让出点。
            repeat(20) { assertTrue(dispatcher.runOne()) }
            assertTrue(catalog.snapshot.value.sourceSamples.isEmpty())
            val latest = listOf(captureSample(900))
            store.update { it.copy(samples = latest) }
            dispatcher.drain()
            runCurrent()
            assertEquals(latest, catalog.snapshot.value.sourceSamples)
            assertEquals(setOf(TARGET), catalog.snapshot.value.capturedTargets.map { it.targetAppId }.toSet())
            assertFalse(seen.any { it.sourceSamples == old })
            store.update { it.copy(samples = emptyList()) }
            dispatcher.drain()
            runCurrent()
            assertTrue(catalog.snapshot.value.rules.isEmpty())
            assertTrue(catalog.snapshot.value.capturedTargets.isEmpty())
            val deletion = seen.indexOfLast { it.sourceSamples.isEmpty() }
            dispatcher.drain()
            runCurrent()
            assertTrue(seen.drop(deletion).all { it.rules.isEmpty() })
            seen.filter { it.sourceSamples.isNotEmpty() }.forEach { snapshot ->
                assertEquals(snapshot.sourceSamples.map { it.targetAppId }.toSet(), snapshot.capturedTargets.map { it.targetAppId }.toSet())
                assertEquals(snapshot.capturedTargets.flatMap { it.analysis.rules }, snapshot.rules)
            }
        } finally {
            scope.cancel()
            dispatcher.drain()
        }
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val queue = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queue.addLast(block) }
        fun runOne(): Boolean = queue.removeFirstOrNull()?.let { it.run(); true } ?: false
        fun drain() {
            repeat(10_000) { if (!runOne()) return }
            error("目录调度未收敛")
        }
    }
}
