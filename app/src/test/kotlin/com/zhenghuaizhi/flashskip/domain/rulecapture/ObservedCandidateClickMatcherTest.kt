package com.zhenghuaizhi.flashskip.domain.rulecapture

import com.zhenghuaizhi.flashskip.domain.model.ObservedDismissCandidate
import com.zhenghuaizhi.flashskip.domain.model.AppVersion
import com.zhenghuaizhi.flashskip.domain.ports.PageIdentity
import com.zhenghuaizhi.flashskip.testing.captureSample
import org.junit.Assert.*
import org.junit.Test

class ObservedCandidateClickMatcherTest {
    private val sample = captureSample()
    private val node = sample.nodes.single()
    private val identity = PageIdentity(sample.targetAppId.packageName, sample.activityName, 7)
    private val candidate = ObservedDismissCandidate(sample.id, sample.targetAppId, sample.targetAppName,
        sample.activityName, 100, 1000, sample.nodes, sample.preClickUniqueSelectors)

    @Test fun ac07_sameStableSourceAndCountdownSemanticCanUpgrade() {
        assertTrue(matches(listOf(node)))
        val countdown = candidate.copy(nodes = listOf(node.copy(text = "跳过 5")))
        assertTrue(ObservedCandidateClickMatcher.matches(countdown, identity, identity, listOf(node.copy(text = "跳过 4"))))
    }

    @Test fun ac07_packageActivityWindowAndVersionMustAllMatch() {
        listOf(identity.copy(packageName = "other"), identity.copy(activityName = "OtherActivity"),
            identity.copy(windowId = 8), identity.copy(appVersion = AppVersion("2", 2))).forEach {
            assertFalse(ObservedCandidateClickMatcher.matches(candidate, identity, it, listOf(node)))
        }
        assertFalse(ObservedCandidateClickMatcher.matches(candidate, identity.copy(activityName = ""), identity.copy(activityName = ""), listOf(node)))
    }

    @Test fun ac07_sameTextWithoutStableIdentityCannotUpgrade() {
        assertFalse(matches(listOf(node.copy(resourceId = null))))
        assertFalse(matches(listOf(node.copy(resourceId = "android:id/button1"))))
        assertFalse(matches(listOf(node.copy(resourceId = "app:id/other"))))
    }

    @Test fun ac07_conflictingSourceOrActionLabelsCannotUpgrade() {
        assertFalse(matches(listOf(node.copy(contentDescription = "Buy now"))))
        assertFalse(matches(listOf(node.copy(text = "关闭"))))
        assertFalse(matches(listOf(node.copy(clickable = false, supportsActionClick = false), node.copy(text = "Buy now"))))
    }

    @Test fun ac07_unreadableEmptyInvisibleAndNonActionableCannotUpgrade() {
        assertFalse(matches(emptyList()))
        assertFalse(matches(listOf(node.copy(visibleToUser = false))))
        assertFalse(matches(listOf(node.copy(hasUsableBounds = false))))
        assertFalse(matches(listOf(node.copy(clickable = false, supportsActionClick = false))))
    }

    @Test fun ac07_clickOnCandidateActionAncestorUsesStableActionIdentity() {
        val child = node.copy(resourceId = "app:id/label", clickable = false, supportsActionClick = false)
        val parent = node.copy(resourceId = "app:id/skip")
        val nested = candidate.copy(nodes = listOf(child, parent))
        assertTrue(ObservedCandidateClickMatcher.matches(nested, identity, identity, listOf(parent)))
    }

    private fun matches(nodes: List<com.zhenghuaizhi.flashskip.domain.model.CapturedNode>) =
        ObservedCandidateClickMatcher.matches(candidate, identity, identity, nodes)
}
