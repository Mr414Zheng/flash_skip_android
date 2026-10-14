package com.zhenghuaizhi.flashskip.platform.accessibility

import com.zhenghuaizhi.flashskip.domain.model.CapturedSelector
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], shadows = [FaultableAccessibilityNodeShadow::class, CopyingAccessibilityServiceShadow::class])
class AccessibilityCandidateRecheckTest {
    @Test fun ac05_indexedCurrentTargetProvidesOnlySelectorPositiveEvidence() {
        val f = AccessibilityFixture()
        val result = check(f, CapturedSelector(resourceId = f.target.viewIdResourceName))
        assertEquals(SelectorPresence.UNIQUE_PRESENT, result.presence)
        assertEquals(SelectorEvidenceScope.NONE, result.scope)
        assertEquals(SelectorEvidenceReason.UNIQUE_RESOURCE_ID, result.reason)
        assertEquals(f.target.viewIdResourceName, result.nodes.first().resourceId)
    }

    @Test fun ac05_duplicateIndexedMatchesAreAmbiguous() {
        val f = AccessibilityFixture()
        f.index(f.root, f.target, f.node("skip", "跳过", true))
        assertEquals(SelectorPresence.AMBIGUOUS_PRESENT, check(f, CapturedSelector(resourceId = f.target.viewIdResourceName)).presence)
    }

    @Test fun ac05_emptyIndexNeedsCompleteWindowToProveAbsence() {
        val f = AccessibilityFixture()
        val selector = CapturedSelector(resourceId = "app:id/missing")
        val complete = check(f, selector)
        assertEquals(SelectorPresence.VERIFIED_ABSENT, complete.presence)
        assertEquals(SelectorEvidenceScope.FULL_WINDOW, complete.scope)
        f.add(f.root, f.node("unknown"))
        FaultableAccessibilityNodeShadow.unreadableChildren += f.root.viewIdResourceName to 1
        assertEquals(SelectorPresence.UNKNOWN, check(f, selector).presence)
    }

    @Test fun ac05_stillPresentButChangedLabelsAreUnknownNotAbsent() {
        val f = AccessibilityFixture()
        val selector = CapturedSelector(resourceId = f.target.viewIdResourceName, text = "跳过")
        FaultableAccessibilityNodeShadow.refreshHooks[f.target.viewIdResourceName] = { it.text = "Buy"; true }
        val result = check(f, selector)
        assertEquals(SelectorPresence.UNKNOWN, result.presence)
        assertEquals(SelectorEvidenceReason.CHANGED_NODE, result.reason)
    }

    @Test fun ac05_queryExceptionAndFailedRefreshCannotProveAbsence() {
        val f = AccessibilityFixture()
        val selector = CapturedSelector(resourceId = f.target.viewIdResourceName)
        val unavailable = AccessibilityCandidateRecheck({ f.currentIdentity }, AccessibilitySemanticSnapshotter(f.service), { f.now }, { true }, { _, _ -> error("binder") })
        assertEquals(SelectorPresence.UNKNOWN, unavailable.check(f.identity, f.root, listOf(selector), budget(f)).evidence.getValue(selector).presence)
        shadowOf(f.target).setRefreshReturnValue(false)
        assertEquals(SelectorPresence.UNKNOWN, check(f, selector).presence)
    }

    @Test fun ac05_uniqueStableAnchorCanProveAbsenceInOnlyItsCompleteSubtree() {
        val f = AccessibilityFixture()
        val anchor = f.node("anchor")
        f.add(f.root, anchor)
        f.index(f.root, anchor)
        val selector = CapturedSelector(resourceId = "app:id/missing", ancestorResourceId = anchor.viewIdResourceName, ancestorDistance = 1)
        val result = check(f, selector)
        assertEquals(SelectorPresence.VERIFIED_ABSENT, result.presence)
        assertEquals(SelectorEvidenceScope.ANCHORED_SUBTREE, result.scope)
        f.index(f.root, anchor, f.node("anchor"))
        assertEquals(SelectorPresence.UNKNOWN, check(f, selector).presence)
    }

    @Test fun ac05_missingAnchorAndExhaustedBudgetRemainUnknown() {
        val f = AccessibilityFixture()
        val selector = CapturedSelector(resourceId = "app:id/missing", ancestorResourceId = "app:id/anchor", ancestorDistance = 1)
        assertEquals(SelectorEvidenceReason.EMPTY_QUERY, check(f, selector).reason)
        assertEquals(SelectorPresence.UNKNOWN, f.captureProbe().checkStableSelectorsOnVerifiedRoot(f.identity, f.root, listOf(selector), CaptureRoundBudget({ f.now }, 250, 0)).evidence.getValue(selector).presence)
    }

    @Test fun ac05_identityChangeDuringQueryInvalidatesAllEvidence() {
        val f = AccessibilityFixture()
        val selector = CapturedSelector(resourceId = f.target.viewIdResourceName)
        val checker = AccessibilityCandidateRecheck({ f.currentIdentity }, AccessibilitySemanticSnapshotter(f.service), { f.now }, { true }, { _, _ ->
            f.currentIdentity = f.identity.copy(windowId = 8)
            listOf(android.view.accessibility.AccessibilityNodeInfo.obtain(f.target))
        })
        val result = checker.check(f.identity, f.root, listOf(selector), budget(f))
        assertEquals(SelectorPresence.UNKNOWN, result.evidence.getValue(selector).presence)
        assertEquals(SelectorEvidenceReason.IDENTITY_CHANGED, result.evidence.getValue(selector).reason)
    }

    private fun check(f: AccessibilityFixture, selector: CapturedSelector): SelectorEvidence =
        f.captureProbe().checkStableSelectorsOnVerifiedRoot(f.identity, f.root, listOf(selector), budget(f)).evidence.getValue(selector)
    private fun budget(f: AccessibilityFixture) = CaptureRoundBudget({ f.now }, f.now + 250)
}
