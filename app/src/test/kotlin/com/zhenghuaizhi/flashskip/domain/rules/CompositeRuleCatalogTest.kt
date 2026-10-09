package com.zhenghuaizhi.flashskip.domain.rules

import com.zhenghuaizhi.flashskip.domain.model.AdRule
import com.zhenghuaizhi.flashskip.domain.model.CapturePhase
import com.zhenghuaizhi.flashskip.domain.model.CaptureSample
import com.zhenghuaizhi.flashskip.domain.model.CaptureSession
import com.zhenghuaizhi.flashskip.domain.model.CapturedNode
import com.zhenghuaizhi.flashskip.domain.model.CapturedSelector
import com.zhenghuaizhi.flashskip.domain.model.RuleCaptureState
import com.zhenghuaizhi.flashskip.domain.model.RuleCardStatus
import com.zhenghuaizhi.flashskip.domain.model.SemanticDiffEvidence
import com.zhenghuaizhi.flashskip.domain.model.TargetAppId
import com.zhenghuaizhi.flashskip.domain.ports.RuleCaptureStore
import com.zhenghuaizhi.flashskip.domain.rulecapture.RuleCandidateAnalyzer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CompositeRuleCatalogTest {
    @Test
    fun sharedSnapshotPreservesRulesCandidateStatusNamesAndRecentOrder() = runTest {
        val first = sample(1L, target(0), 100L, "旧名称")
        val candidate = sample(2L, target(1), 200L, "候选应用").copy(
            nodes = listOf(CapturedNode(text = "跳过", clickable = true, visibleToUser = true, hasUsableBounds = true)),
        )
        val latest = sample(3L, target(0), 300L, "最新名称")
        val samples = listOf(first, candidate, latest)
        val builtin = RuleCandidateAnalyzer.readyRules(listOf(sample(4L, target(2))), target(2))
        val expected = legacySnapshot(samples, builtin)
        val fixture = fixture(samples, builtin)
        runCurrent()

        assertEquals(expected, fixture.catalog.snapshot.value)
        val targets = fixture.catalog.snapshot.value.capturedTargets
        assertEquals(listOf(target(0), target(1)), targets.map { it.targetAppId })
        assertEquals("最新名称", targets.first().displayName)
        assertEquals(300L, targets.first().latestCapturedAtEpochMs)
        assertEquals(RuleCardStatus.READY, targets.first().analysis.status)
        assertEquals(2, targets.first().analysis.sampleCount)
        assertEquals(RuleCardStatus.CANDIDATE, targets.last().analysis.status)
        assertEquals(builtin, fixture.catalog.snapshot.value.rules.takeLast(builtin.size))
    }

    @Test
    fun duplicateLocalAndBuiltinIdKeepsExistingWholeCatalogFallback() = runTest {
        val samples = listOf(sample(1L, target(0)))
        val builtin = RuleCandidateAnalyzer.readyRules(samples, target(0))
        val fixture = fixture(samples, builtin)
        runCurrent()

        assertEquals(legacySnapshot(samples, builtin), fixture.catalog.snapshot.value)
        assertEquals(builtin, fixture.catalog.snapshot.value.rules)
        assertEquals(RuleCardStatus.READY, fixture.catalog.snapshot.value.capturedTargets.single().analysis.status)
    }

    @Test
    fun sessionAndDiagnosticsUpdatesDoNotReanalyzeUnchangedSamples() = runTest {
        val nodes = CountingList(sample(1L, target(0)).nodes)
        val sample = sample(1L, target(0)).copy(nodes = nodes)
        val fixture = fixture(listOf(sample))
        runCurrent()
        assertTrue(nodes.reads > 0)
        val originalSnapshot = fixture.catalog.snapshot.value
        nodes.reads = 0

        fixture.store.state.value = RuleCaptureState(
            samples = listOf(sample),
            activeSession = CaptureSession(1L, phase = CapturePhase.CONFIGURING, phaseStartedAtEpochMs = 10L, deadlineAtEpochMs = 2_010L),
        )
        runCurrent()
        fixture.store.state.value = fixture.store.state.value.copy(
            samples = listOf(sample),
            semanticDiffEvidence = listOf(
                SemanticDiffEvidence(target(0), "com.example.Main", listOf(CapturedSelector(resourceId = "com.example:id/skip")), 20L),
            ),
        )
        runCurrent()

        assertEquals(0, nodes.reads)
        assertSame(originalSnapshot, fixture.catalog.snapshot.value)
        fixture.store.state.value = fixture.store.state.value.copy(
            samples = listOf(sample.copy(targetAppName = "新名称")),
        )
        runCurrent()
        assertTrue(nodes.reads > 0)
        assertEquals("新名称", fixture.catalog.snapshot.value.capturedTargets.single().displayName)
    }

    @Test
    fun latestSamplesReplaceOldSnapshotWithoutRetainingClearedTargets() = runTest {
        val fixture = fixture(listOf(sample(1L, target(0))))
        runCurrent()
        fixture.store.state.value = RuleCaptureState(samples = listOf(sample(2L, target(1))))
        fixture.store.state.value = RuleCaptureState(samples = listOf(sample(3L, target(2))))
        runCurrent()

        assertEquals(listOf(target(2)), fixture.catalog.snapshot.value.capturedTargets.map { it.targetAppId })
        assertEquals(fixture.store.state.value.samples, fixture.catalog.snapshot.value.sourceSamples)
        fixture.store.state.value = RuleCaptureState()
        runCurrent()
        assertEquals(RuleCatalogSnapshot(), fixture.catalog.snapshot.value)
    }

    @Test
    fun sampleChangeDuringAnalysisCancelsRemainingGroupsAndOldPublication() = runTest {
        verifyChangeDuringAnalysis(targetCount = 3)
    }

    @Test
    fun sampleChangeDuringOnlyGroupAnalysisPreventsOldPublication() = runTest {
        verifyChangeDuringAnalysis(targetCount = 1)
    }

    private fun TestScope.verifyChangeDuringAnalysis(targetCount: Int) {
        val nodeLists = List(targetCount) { CountingList(sample(it.toLong(), target(it)).nodes) }
        val original = List(targetCount) { sample(it.toLong(), target(it)).copy(nodes = nodeLists[it]) }
        val replacement = listOf(sample(100L, target(9)))
        val fixture = fixture(original)
        val emitted = mutableListOf<RuleCatalogSnapshot>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            fixture.catalog.snapshot.collect { emitted += it }
        }
        nodeLists.first().onRead = {
            fixture.store.state.value = RuleCaptureState(samples = replacement)
        }

        runCurrent()

        assertTrue(nodeLists.first().reads > 0)
        assertTrue(nodeLists.drop(1).all { it.reads == 0 })
        assertTrue(emitted.none { it.sourceSamples == original })
        assertEquals(replacement, fixture.catalog.snapshot.value.sourceSamples)
    }

    @Test
    fun sharedAnalysisPreservesOutputsAndReducesWorkFor100Samples() = runTest {
        verifyWorkload(100)
    }

    @Test
    fun sharedAnalysisPreservesOutputsAndReducesWorkFor1000Samples() = runTest {
        verifyWorkload(1_000)
    }

    private fun TestScope.verifyWorkload(count: Int) {
        val nodeLists = ArrayList<CountingList<CapturedNode>>()
        val samples = List(count) { index ->
            val sample = sample(index.toLong(), target(index % 10), index.toLong())
            val nodes = CountingList(sample.nodes)
            nodeLists += nodes
            sample.copy(nodes = nodes)
        }
        val baselineStartedAt = System.nanoTime()
        val expected = legacySnapshot(samples)
        val baselineMs = (System.nanoTime() - baselineStartedAt) / 1_000_000.0
        val baselineReads = nodeLists.sumOf { it.reads }
        nodeLists.forEach { it.reads = 0 }
        val fixture = fixture(samples)
        val sharedStartedAt = System.nanoTime()
        runCurrent()
        val sharedMs = (System.nanoTime() - sharedStartedAt) / 1_000_000.0
        val sharedReads = nodeLists.sumOf { it.reads }

        assertEquals(expected, fixture.catalog.snapshot.value)
        assertTrue("Shared analysis must remove duplicate node inspection", sharedReads < baselineReads)
        println("samples=$count baselineNodeReads=$baselineReads sharedNodeReads=$sharedReads baselineMs=$baselineMs sharedMs=$sharedMs")
    }

    // 固定原目录和首页的计算方式，核对外部输出并比较重复分析的工作量。
    private fun legacySnapshot(samples: List<CaptureSample>, builtin: List<AdRule> = emptyList()): RuleCatalogSnapshot {
        val local = samples.map { it.targetAppId }.distinct().flatMap {
            RuleCandidateAnalyzer.readyRules(samples, it)
        }.filter { RuleValidator.validate(listOf(it)).isEmpty() }
        val combined = local + builtin
        val summaries = samples.groupBy { it.targetAppId }.map { (id, group) ->
            val latest = group.maxBy { it.capturedAtEpochMs }
            CapturedTargetRules(id, latest.targetAppName, latest.capturedAtEpochMs, RuleCandidateAnalyzer.analyze(samples, id))
        }.sortedByDescending { it.latestCapturedAtEpochMs }
        return RuleCatalogSnapshot(
            rules = if (RuleValidator.validate(combined).isEmpty()) combined else builtin,
            capturedTargets = summaries,
            sourceSamples = samples,
        )
    }

    private fun TestScope.fixture(samples: List<CaptureSample>, builtin: List<AdRule> = emptyList()): Fixture {
        val store = FakeCaptureStore(RuleCaptureState(samples = samples))
        return Fixture(
            store,
            CompositeRuleCatalog(BuiltinRuleCatalog(builtin), store, backgroundScope, StandardTestDispatcher(testScheduler)),
        )
    }

    private data class Fixture(val store: FakeCaptureStore, val catalog: CompositeRuleCatalog)

    private class FakeCaptureStore(initial: RuleCaptureState) : RuleCaptureStore {
        override val state = MutableStateFlow(initial)
        override suspend fun update(transform: (RuleCaptureState) -> RuleCaptureState) {
            state.value = transform(state.value)
        }
    }

    private class CountingList<T>(private val values: List<T>) : AbstractList<T>() {
        var reads = 0
        var onRead: (() -> Unit)? = null
        override val size: Int get() = values.size
        override fun get(index: Int): T {
            reads++
            onRead?.let { callback ->
                onRead = null
                callback()
            }
            return values[index]
        }
    }

    private companion object {
        fun target(index: Int) = TargetAppId("com.example.target$index")
        fun sample(id: Long, target: TargetAppId, at: Long = 100L, name: String = target.packageName) = CaptureSample(
            id = id,
            targetAppId = target,
            targetAppName = name,
            activityName = "${target.packageName}.MainActivity",
            clickedAfterTargetMs = 500L,
            capturedAtEpochMs = at,
            nodes = listOf(CapturedNode(resourceId = "${target.packageName}:id/skip", text = "跳过", clickable = true, visibleToUser = true, hasUsableBounds = true)),
            preClickUniqueSelectors = emptyList(),
        )
    }
}
