package com.zhenghuaizhi.flashskip.platform.accessibility

import com.zhenghuaizhi.flashskip.domain.model.CaptureSample
import com.zhenghuaizhi.flashskip.domain.model.CapturedNode
import com.zhenghuaizhi.flashskip.domain.model.TargetAppId
import com.zhenghuaizhi.flashskip.domain.rulecapture.RuleCandidateAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureProbeSelectorCacheTest {
    @Test
    fun repeatedEventsAndEqualSampleListsReuseAnalysis() {
        val nodes = CountingList(sample(1L).nodes)
        val samples = listOf(sample(1L).copy(nodes = nodes))
        val cache = CaptureProbeSelectorCache()
        val selectors = cache.selectorsFor(samples, TARGET)
        assertTrue(nodes.reads > 0)
        nodes.reads = 0

        repeat(20) { assertSame(selectors, cache.selectorsFor(samples.toList(), TARGET)) }
        assertEquals(0, nodes.reads)
    }

    @Test
    fun emptyAnalysisIsAlsoCached() {
        val nodes = CountingList(listOf(CapturedNode(text = "跳过")))
        val samples = listOf(sample(1L).copy(nodes = nodes))
        val cache = CaptureProbeSelectorCache()
        assertTrue(cache.selectorsFor(samples, TARGET).isEmpty())
        assertTrue(nodes.reads > 0)
        nodes.reads = 0

        repeat(20) { assertTrue(cache.selectorsFor(samples, TARGET).isEmpty()) }
        assertEquals(0, nodes.reads)
    }

    @Test
    fun changedSamplesRecomputeSelectors() {
        val original = listOf(sample(1L))
        val nodes = CountingList(listOf(sample(2L).nodes.single().copy(resourceId = "com.example.target:id/close")))
        val updated = listOf(sample(2L).copy(nodes = nodes))
        val cache = CaptureProbeSelectorCache()
        cache.selectorsFor(original, TARGET)
        val selectors = cache.selectorsFor(updated, TARGET)

        assertTrue(nodes.reads > 0)
        assertEquals(RuleCandidateAnalyzer.analyze(updated, TARGET).probeSelectors, selectors)
        assertTrue(selectors.all { it.resourceId == "com.example.target:id/close" })
    }

    @Test
    fun changingTargetReplacesOnlyCacheEntryAndClearForcesFreshAnalysis() {
        val other = TargetAppId("com.example.other")
        val nodes = CountingList(sample(1L).nodes)
        val samples = listOf(sample(1L).copy(nodes = nodes), sample(2L).copy(targetAppId = other))
        val cache = CaptureProbeSelectorCache()
        cache.selectorsFor(samples, TARGET)
        cache.selectorsFor(samples, other)
        nodes.reads = 0
        cache.selectorsFor(samples, TARGET)
        assertTrue(nodes.reads > 0)
        nodes.reads = 0
        cache.clear()
        cache.selectorsFor(samples, TARGET)
        assertTrue(nodes.reads > 0)
    }

    @Test
    fun repeatedEventsAvoidReanalysisFor100HistoricalSamples() = verifyWorkload(100)

    @Test
    fun repeatedEventsAvoidReanalysisFor1000HistoricalSamples() = verifyWorkload(1_000)

    private fun verifyWorkload(sampleCount: Int) {
        val nodeLists = List(sampleCount) { CountingList(sample(it.toLong()).nodes) }
        val samples = List(sampleCount) { sample(it.toLong()).copy(nodes = nodeLists[it]) }
        val expected = RuleCandidateAnalyzer.analyze(samples, TARGET).probeSelectors
        val singleAnalysisReads = nodeLists.sumOf { it.reads }
        nodeLists.forEach { it.reads = 0 }
        val baselineStartedAt = System.nanoTime()
        repeat(EVENT_COUNT) {
            assertEquals(expected, RuleCandidateAnalyzer.analyze(samples, TARGET).probeSelectors)
        }
        val baselineMs = (System.nanoTime() - baselineStartedAt) / 1_000_000.0
        val uncachedReads = nodeLists.sumOf { it.reads }
        nodeLists.forEach { it.reads = 0 }
        val cache = CaptureProbeSelectorCache()
        val startedAt = System.nanoTime()
        repeat(EVENT_COUNT) { assertEquals(expected, cache.selectorsFor(samples, TARGET)) }
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000.0
        val cachedReads = nodeLists.sumOf { it.reads }

        assertEquals(singleAnalysisReads, cachedReads)
        assertEquals(cachedReads * EVENT_COUNT, uncachedReads)
        println("probeCache samples=$sampleCount events=$EVENT_COUNT uncachedNodeReads=$uncachedReads cachedNodeReads=$cachedReads baselineMs=$baselineMs cachedMs=$elapsedMs")
    }

    private class CountingList<T>(private val values: List<T>) : AbstractList<T>() {
        var reads = 0
        override val size: Int get() = values.size
        override fun get(index: Int): T {
            reads++
            return values[index]
        }
    }

    private companion object {
        val TARGET = TargetAppId("com.example.target")
        const val EVENT_COUNT = 100

        fun sample(id: Long) = CaptureSample(
            id = id,
            targetAppId = TARGET,
            activityName = "com.example.target.MainActivity",
            clickedAfterTargetMs = 100L,
            capturedAtEpochMs = 1_000L + id,
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
    }
}
