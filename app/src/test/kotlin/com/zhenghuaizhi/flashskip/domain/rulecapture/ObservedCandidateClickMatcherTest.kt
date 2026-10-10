package com.zhenghuaizhi.flashskip.domain.rulecapture

import com.zhenghuaizhi.flashskip.domain.model.CapturedNode
import com.zhenghuaizhi.flashskip.domain.model.ObservedDismissCandidate
import com.zhenghuaizhi.flashskip.domain.model.TargetAppId
import com.zhenghuaizhi.flashskip.domain.ports.PageIdentity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ObservedCandidateClickMatcherTest {
    @Test
    fun matchesSameDismissSourceWhenCountdownChangesAndActionIsOnParent() {
        val candidate = candidate(
            CapturedNode(
                resourceId = TARGET_ID,
                text = "Skip 05",
            ),
            CapturedNode(
                resourceId = "target:id/generic_action_container",
                clickable = true,
            ),
        )
        val clicked = listOf(
            CapturedNode(
                resourceId = TARGET_ID,
                text = "Skip 03",
            ),
            CapturedNode(
                resourceId = "target:id/generic_action_container",
                clickable = true,
            ),
        )

        assertTrue(matches(candidate, clicked))
    }

    @Test
    fun doesNotMatchDifferentSourceMerelyBecauseItSharesClickableParent() {
        val candidate = candidate(
            CapturedNode(text = "Skip 05"),
            CapturedNode(
                resourceId = "target:id/generic_action_container",
                clickable = true,
            ),
        )
        val clicked = listOf(
            CapturedNode(resourceId = "target:id/unlabeled_icon"),
            CapturedNode(
                resourceId = "target:id/generic_action_container",
                clickable = true,
            ),
        )

        assertFalse(matches(candidate, clicked))
    }

    @Test
    fun doesNotMatchDifferentSourceIdsThatShareAnAppRoot() {
        val candidate = candidate(
            CapturedNode(
                resourceId = "target:id/skip_button_a",
                text = "Skip",
                clickable = true,
            ),
            CapturedNode(resourceId = "target:id/app_root"),
        )
        val clicked = listOf(
            CapturedNode(
                resourceId = "target:id/skip_button_b",
                text = "Skip",
                clickable = true,
            ),
            CapturedNode(resourceId = "target:id/app_root"),
        )

        assertFalse(matches(candidate, clicked))
    }

    @Test
    fun acceptsClickSourceReportedAsTheCandidateActionParent() {
        val candidate = candidate(
            CapturedNode(resourceId = TARGET_ID, text = "Skip"),
            CapturedNode(resourceId = ACTION_ID, clickable = true),
        )
        val clicked = listOf(
            CapturedNode(resourceId = ACTION_ID, clickable = true),
        )

        assertTrue(matches(candidate, clicked))
    }

    @Test
    fun rejectsDifferentStableSourceIdsEvenWhenBothShareSameDismissParent() {
        val candidate = candidate(
            CapturedNode(resourceId = "target:id/skip_child_a", text = "Skip"),
            CapturedNode(resourceId = ACTION_ID, text = "Skip", clickable = true),
        )
        val clicked = listOf(
            CapturedNode(resourceId = "target:id/skip_child_b", text = "Skip"),
            CapturedNode(resourceId = ACTION_ID, text = "Skip", clickable = true),
        )

        assertFalse(matches(candidate, clicked))
    }

    @Test
    fun doesNotMatchDismissIdWhenClickedSourceHasConflictingPurchaseLabel() {
        val candidate = candidate(
            CapturedNode(
                resourceId = TARGET_ID,
                text = "Skip",
                clickable = true,
            ),
        )
        val clicked = listOf(
            CapturedNode(
                resourceId = TARGET_ID,
                text = "立即购买",
                clickable = true,
            ),
        )

        assertFalse(matches(candidate, clicked))
    }

    @Test
    fun insufficientSourceEvidenceLeavesCandidateForManualConfirmation() {
        val candidate = candidate(
            CapturedNode(text = "Skip"),
            CapturedNode(resourceId = "target:id/generic_action_container", clickable = true),
        )
        val clicked = listOf(
            CapturedNode(contentDescription = "Image"),
            CapturedNode(resourceId = "target:id/generic_action_container", clickable = true),
        )

        assertFalse(matches(candidate, clicked))
    }

    private fun matches(
        candidate: ObservedDismissCandidate,
        clickedNodes: List<CapturedNode>,
    ): Boolean = ObservedCandidateClickMatcher.matches(
        candidate = candidate,
        candidateIdentity = IDENTITY,
        clickedIdentity = IDENTITY,
        clickedNodes = visible(clickedNodes),
    )

    private fun candidate(vararg nodes: CapturedNode): ObservedDismissCandidate =
        ObservedDismissCandidate(
            id = 7L,
            targetAppId = TargetAppId(IDENTITY.packageName),
            targetAppName = "Target",
            activityName = IDENTITY.activityName.orEmpty(),
            observedAfterTargetMs = 0L,
            observedAtEpochMs = 1L,
            nodes = visible(nodes.toList()),
            preClickUniqueSelectors = emptyList(),
        )

    private fun visible(nodes: List<CapturedNode>): List<CapturedNode> = nodes.map {
        it.copy(visibleToUser = true, hasUsableBounds = true)
    }

    private companion object {
        const val TARGET_ID = "target:id/skip_button"
        const val ACTION_ID = "target:id/skip_action"
        val IDENTITY = PageIdentity(
            packageName = "target.example",
            activityName = "target.example.MainActivity",
            windowId = 42,
        )
    }
}
