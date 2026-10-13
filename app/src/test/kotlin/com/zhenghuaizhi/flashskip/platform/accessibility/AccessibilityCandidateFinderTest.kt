package com.zhenghuaizhi.flashskip.platform.accessibility

import com.zhenghuaizhi.flashskip.domain.model.TextCondition
import com.zhenghuaizhi.flashskip.testing.TARGET
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], shadows = [FaultableAccessibilityNodeShadow::class, CopyingAccessibilityServiceShadow::class])
class AccessibilityCandidateFinderTest {
    @Test fun ac02_emptyIndexFallsBackToTreeAndFullSelectorControlsUniqueness() {
        val f = AccessibilityFixture()
        FaultableAccessibilityNodeShadow.index.clear()
        assertEquals(CandidateSearchResult.Value("跳过"), f.finder.findUnique(TARGET.packageName, f.root, f.selector, onUnique = { it.text.toString() }))
        assertEquals(CandidateSearchResult.None, f.finder.findUnique(TARGET.packageName, f.root, f.selector.copy(text = TextCondition.Exact("other")), onUnique = { Unit }))
    }

    @Test fun ac02_multipleMatchingIndexedCandidatesAreAmbiguousAndCallbackNeverRuns() {
        val f = AccessibilityFixture()
        val duplicate = f.node("skip", "跳过", true)
        f.index(f.root, f.target, duplicate)
        var actions = 0
        val result = f.finder.findUnique(TARGET.packageName, f.root, f.selector, { actions++; Unit })
        assertEquals(CandidateSearchResult.Ambiguous, result)
        assertEquals(0, actions)
    }

    @Test fun ac02_unreadableCandidateCannotProveUniqueOrAbsent() {
        val f = AccessibilityFixture()
        shadowOf(f.target).setRefreshReturnValue(false)
        assertEquals(CandidateSearchResult.Unavailable, f.finder.findUnique(TARGET.packageName, f.root, f.selector, onUnique = { Unit }))
    }

    @Test fun ac02_refreshChangingPackageRejectsCachedMatch() {
        val f = AccessibilityFixture()
        FaultableAccessibilityNodeShadow.refreshHooks[f.target.viewIdResourceName] = { node -> node.packageName = "other.package"; true }
        assertEquals(CandidateSearchResult.Unavailable, f.finder.findUnique(TARGET.packageName, f.root, f.selector, onUnique = { Unit }))
    }

    @Test fun ac02_refreshChangingTextCannotUseCachedSelectorMatch() {
        val f = AccessibilityFixture()
        FaultableAccessibilityNodeShadow.refreshHooks[f.target.viewIdResourceName] = { node -> node.text = "Changed"; true }
        assertEquals(CandidateSearchResult.None, f.finder.findUnique(TARGET.packageName, f.root, f.selector.copy(text = TextCondition.Exact("跳过")), onUnique = { Unit }))
    }

    @Test fun ac02_unreadableChildMakesFallbackUnavailableEvenWithKnownMatch() {
        val f = AccessibilityFixture()
        FaultableAccessibilityNodeShadow.index.clear()
        f.add(f.root, f.node("unknown"))
        FaultableAccessibilityNodeShadow.unreadableChildren += f.root.viewIdResourceName to 1
        assertEquals(CandidateSearchResult.Unavailable, f.finder.findUnique(TARGET.packageName, f.root, f.selector, onUnique = { Unit }))
    }

    @Test fun ac02_nodeBudgetPreventsUnboundedTraversal() {
        val f = AccessibilityFixture()
        FaultableAccessibilityNodeShadow.index.clear()
        repeat(500) { f.add(f.root, f.node("child$it")) }
        assertEquals(CandidateSearchResult.TraversalLimit, f.finder.findUnique(TARGET.packageName, f.root, f.selector, onUnique = { Unit }))
    }

    @Test fun ac02_depthBudgetPreventsUnboundedTraversal() {
        val f = AccessibilityFixture()
        FaultableAccessibilityNodeShadow.index.clear()
        var parent = f.root
        repeat(41) { i -> val child = f.node("deep$i"); f.add(parent, child); parent = child }
        assertEquals(CandidateSearchResult.TraversalLimit, f.finder.findUnique(TARGET.packageName, f.root, f.selector, onUnique = { Unit }))
    }

    @Test fun ac02_elapsedScanBudgetProducesTraversalLimitNotAbsence() {
        val f = AccessibilityFixture()
        var ticks = 0L
        f.clockRead = { ticks.also { ticks += 51 } }
        assertEquals(CandidateSearchResult.TraversalLimit, f.finder.findUnique(TARGET.packageName, f.root, f.selector, onUnique = { Unit }))
    }

    @Test fun ac02_ancestorConstraintIsRecheckedAfterRefresh() {
        val f = AccessibilityFixture()
        val constrained = f.selector.copy(ancestorResourceId = f.root.viewIdResourceName, ancestorDistance = 1)
        assertEquals(CandidateSearchResult.Value(Unit), f.finder.findUnique(TARGET.packageName, f.root, constrained, onUnique = { Unit }))
        shadowOf(f.root).setRefreshReturnValue(false)
        assertEquals(CandidateSearchResult.Unavailable, f.finder.findUnique(TARGET.packageName, f.root, constrained, onUnique = { Unit }))
    }
}
