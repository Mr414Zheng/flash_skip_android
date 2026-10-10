package com.zhenghuaizhi.flashskip.platform.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.zhenghuaizhi.flashskip.domain.model.NodeSelector
import com.zhenghuaizhi.flashskip.domain.model.TextCondition
import com.zhenghuaizhi.flashskip.domain.ports.AutomationTraceResult
import com.zhenghuaizhi.flashskip.domain.ports.MonotonicClock
import com.zhenghuaizhi.flashskip.domain.ports.PageIdentity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAccessibilityNodeInfo
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], shadows = [AccessibilitySearchDiagnosticsTest.TimedNodeShadow::class])
@Suppress("DEPRECATION")
class AccessibilitySearchDiagnosticsTest {
    private lateinit var controller: ServiceController<AccessibilityCandidateFreshnessTest.TestService>
    private lateinit var finder: AccessibilityCandidateFinder
    private lateinit var root: AccessibilityNodeInfo
    private lateinit var clock: TestClock
    private var actions = 0

    @Before
    fun setUp() {
        TimedNodeShadow.onIndex = null
        TimedNodeShadow.onChild = null
        ShadowLog.clear()
        controller = Robolectric.buildService(AccessibilityCandidateFreshnessTest.TestService::class.java).create()
        clock = TestClock()
        finder = AccessibilityCandidateFinder(AccessibilityNodeSelectorMatcher(controller.get()), clock)
        root = node()
    }

    @After
    fun tearDown() {
        TimedNodeShadow.onIndex = null
        TimedNodeShadow.onChild = null
        controller.destroy()
    }

    @Test
    fun slowEmptyIndexIsNotProofOfAbsenceAndReportsIndexTimeLimit() {
        TimedNodeShadow.onIndex = { clock.elapsedMs = 101 }
        val diagnostics = SearchDiagnostics()

        assertEquals(CandidateSearchResult.TraversalLimit, search(diagnostics))
        assertEquals(SearchPhase.INDEX, diagnostics.phase)
        assertEquals(SearchLimit.TIME, diagnostics.limit)
        assertEquals(0, diagnostics.indexedNodes)
        assertEquals(101L, diagnostics.indexLookupMs)
        assertEquals(101L, diagnostics.searchMs)
        assertEquals(0, diagnostics.visitedNodes)
        assertEquals(0, actions)
    }

    @Test
    fun slowFallbackIsNotProofOfAbsenceAndReportsTraversalTimeLimit() {
        shadowOf(root).addChild(node())
        TimedNodeShadow.onChild = { clock.elapsedMs = 101 }
        val diagnostics = SearchDiagnostics()

        assertEquals(CandidateSearchResult.TraversalLimit, search(diagnostics))
        assertEquals(SearchPhase.FALLBACK, diagnostics.phase)
        assertEquals(SearchLimit.TIME, diagnostics.limit)
        assertEquals(0L, diagnostics.indexLookupMs)
        assertEquals(2, diagnostics.visitedNodes)
        assertEquals(1, diagnostics.maxDepth)
        assertEquals(101L, diagnostics.searchMs)
        assertEquals(0, actions)
    }

    @Test
    fun wideTreeReportsNodeLimitEvenWhenNoCandidateWasFound() {
        repeat(500) { shadowOf(root).addChild(node()) }
        val diagnostics = SearchDiagnostics()

        assertEquals(CandidateSearchResult.TraversalLimit, search(diagnostics))
        assertEquals(SearchLimit.NODE_COUNT, diagnostics.limit)
        assertEquals(501, diagnostics.visitedNodes)
        assertEquals(1, diagnostics.maxDepth)
        assertEquals(0, actions)
    }

    @Test
    fun deepTreeReportsDepthLimitEvenWhenNoCandidateWasFound() {
        var parent = root
        repeat(41) {
            val child = node()
            shadowOf(parent).addChild(child)
            parent = child
        }
        val diagnostics = SearchDiagnostics()

        assertEquals(CandidateSearchResult.TraversalLimit, search(diagnostics))
        assertEquals(SearchLimit.DEPTH, diagnostics.limit)
        assertEquals(42, diagnostics.visitedNodes)
        assertEquals(41, diagnostics.maxDepth)
        assertEquals(0, actions)
    }

    @Test
    fun onlyCompleteFallbackCanReturnNoneWithoutLimit() {
        shadowOf(root).addChild(node())
        val diagnostics = SearchDiagnostics()

        assertEquals(CandidateSearchResult.None, search(diagnostics))
        assertEquals(SearchPhase.FALLBACK, diagnostics.phase)
        assertNull(diagnostics.limit)
        assertEquals(2, diagnostics.visitedNodes)
        assertEquals(0, actions)
    }

    @Test
    fun scanSummaryKeepsLimitCauseAndCountersWithoutPageContentOrPerNodeFlooding() {
        root.text = "个人页面内容"
        shadowOf(root).addChild(node())
        TimedNodeShadow.onChild = { clock.elapsedMs = 101 }
        val trace = AccessibilityScanTraceLog(true, IDENTITY, SELECTOR, ScanOperation.QUERY, clock)

        assertEquals(CandidateSearchResult.TraversalLimit, search(requireNotNull(trace.searchDiagnostics)))
        trace.finish(AutomationTraceResult.TRAVERSAL_LIMIT)

        val logs = ShadowLog.getLogsForTag(AutomationTraceLog.TAG).map { it.msg }
        assertEquals(1, logs.size)
        val summary = logs.single()
        assertTrue(summary.contains("phase=FALLBACK"))
        assertTrue(summary.contains("limit=TIME"))
        assertTrue(summary.contains("indexedNodes=0 indexLookupMs=0 visitedNodes=2 maxDepth=1 searchMs=101"))
        assertTrue(summary.contains("durationMs=101"))
        assertTrue(summary.contains("candidates=0"))
        assertTrue(!summary.contains("个人页面内容") && !summary.contains("跳过") && !summary.contains("test.app"))
    }

    @Test
    fun disabledLoggerDoesNotAllocateSearchDiagnosticsOrEmitLimitData() {
        val trace = AccessibilityScanTraceLog(false, IDENTITY, SELECTOR, ScanOperation.QUERY, clock)
        assertNull(trace.searchDiagnostics)
        assertNull(trace.candidateSink)
        trace.finish(AutomationTraceResult.TRAVERSAL_LIMIT)
        assertTrue(ShadowLog.getLogsForTag(AutomationTraceLog.TAG).isEmpty())
    }

    private fun search(diagnostics: SearchDiagnostics): CandidateSearchResult<Unit> = finder.findUnique(
        expectedPackage = "test.app",
        root = root,
        selector = SELECTOR,
        onUnique = { actions++ },
        diagnostics = diagnostics,
    )

    private fun node() = AccessibilityNodeInfo.obtain().apply {
        packageName = "test.app"
        isVisibleToUser = true
        setBoundsInScreen(Rect(10, 10, 60, 60))
    }

    @Implements(AccessibilityNodeInfo::class)
    class TimedNodeShadow : ShadowAccessibilityNodeInfo() {
        @Implementation
        fun findAccessibilityNodeInfosByText(text: String): List<AccessibilityNodeInfo> {
            onIndex?.invoke()
            return emptyList()
        }

        @Implementation
        public override fun getChild(index: Int): AccessibilityNodeInfo? {
            onChild?.invoke()
            return super.getChild(index)?.let(AccessibilityNodeInfo::obtain)
        }

        companion object {
            var onIndex: (() -> Unit)? = null
            var onChild: (() -> Unit)? = null
        }
    }

    private class TestClock : MonotonicClock {
        var elapsedMs = 0L
        override fun elapsedRealtimeMs(): Long = elapsedMs
    }

    companion object {
        private val IDENTITY = PageIdentity("test.app", "test.app.MainActivity", 1)
        private val SELECTOR = NodeSelector(text = TextCondition.SkipCountdown("跳过"))
    }
}
