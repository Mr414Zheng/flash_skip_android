package com.zhenghuaizhi.flashskip.platform.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.zhenghuaizhi.flashskip.domain.model.CaptureSample
import com.zhenghuaizhi.flashskip.domain.model.CapturedNode
import com.zhenghuaizhi.flashskip.domain.model.ClickRecoveryOutcome
import com.zhenghuaizhi.flashskip.domain.model.TraversalLimitReason
import com.zhenghuaizhi.flashskip.domain.model.TargetAppId
import com.zhenghuaizhi.flashskip.domain.ports.PageIdentity
import com.zhenghuaizhi.flashskip.domain.rulecapture.ClickSemanticHint
import com.zhenghuaizhi.flashskip.domain.rulecapture.RuleCandidateAnalyzer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Suppress("DEPRECATION")
class CachedProbeEvidenceTest {
    private lateinit var controller: ServiceController<TestAccessibilityService>
    private lateinit var probe: AccessibilityCaptureProbe
    private lateinit var semanticSnapshotter: AccessibilitySemanticSnapshotter
    private val nodes = mutableListOf<AccessibilityNodeInfo>()
    private val windows = mutableListOf<AccessibilityWindowInfo>()
    private val semanticReadCounts = mutableMapOf<AccessibilitySemanticRead, Int>()
    private var currentIdentity: PageIdentity? = IDENTITY
    private var nowMs = 0L
    private var clockStepMs = 0L

    @Before
    fun setUp() {
        controller = Robolectric.buildService(TestAccessibilityService::class.java).create()
        val service = controller.get()
        semanticSnapshotter = AccessibilitySemanticSnapshotter(service) { read ->
            semanticReadCounts[read] = semanticReadCounts.getOrDefault(read, 0) + 1
        }
        probe = AccessibilityCaptureProbe(
            service,
            targetPackages = { setOf(TARGET.packageName) },
            currentIdentity = { currentIdentity },
            semanticSnapshotter = semanticSnapshotter,
            elapsedRealtimeMs = { nowMs.also { nowMs += clockStepMs } },
        )
    }

    @After
    fun tearDown() {
        nodes.forEach(AccessibilityNodeInfo::recycle)
        nodes.clear()
        windows.forEach(AccessibilityWindowInfo::recycle)
        windows.clear()
        controller.destroy()
    }

    @Test
    fun cachedSelectorsStillRejectSecondRootWithMultipleMatches() {
        val cache = CaptureProbeSelectorCache()
        val samples = samples()
        val selectors = cache.selectorsFor(samples, TARGET)
        assertTrue(selectors.isNotEmpty())
        val unique = probe.uniqueSelectorsOnVerifiedRoot(IDENTITY, root(matches = 1), selectors)
        assertEquals(AccessibilityCaptureProbe.ProbeResult.Success(selectors.toSet()), unique)

        val cached = cache.selectorsFor(samples, TARGET)
        assertSame(selectors, cached)
        val ambiguous = probe.uniqueSelectorsOnVerifiedRoot(IDENTITY, root(matches = 2), cached)
        assertEquals(AccessibilityCaptureProbe.ProbeResult.Success(emptySet()), ambiguous)
    }

    @Test
    fun cachedSelectorsStillRejectChangedWindowIdentity() {
        val cache = CaptureProbeSelectorCache()
        val samples = samples()
        val selectors = cache.selectorsFor(samples, TARGET)
        assertEquals(
            AccessibilityCaptureProbe.ProbeResult.Success(selectors.toSet()),
            probe.uniqueSelectorsOnVerifiedRoot(IDENTITY, root(matches = 1), selectors),
        )
        currentIdentity = IDENTITY.copy(windowId = IDENTITY.windowId + 1)
        assertEquals(
            AccessibilityCaptureProbe.ProbeResult.PageChanged,
            probe.uniqueSelectorsOnVerifiedRoot(IDENTITY, root(matches = 1), cache.selectorsFor(samples, TARGET)),
        )
    }

    @Test
    fun explicitDismissCountSurvivesOverlapWithNumericCountdownSemantics() {
        val result = probe.recoverClickedSemanticChainOnVerifiedRoot(
            expected = IDENTITY,
            verifiedRoot = root(matches = 1, text = "5", contentDescription = "跳过"),
            hint = ClickSemanticHint(allowBareNumericCountdown = true),
        )

        assertEquals(1, result.diagnostics.knownDismissNodeCount)
        assertEquals(1, result.diagnostics.countdownSemanticNodeCount)
        assertEquals(1, result.diagnostics.explicitDismissNodeCount)
    }

    @Test
    fun ordinaryNumericItemWithStableIdCannotBeRecoveredAsDismissCandidate() {
        val result = probe.recoverClickedSemanticChainOnVerifiedRoot(
            expected = IDENTITY,
            verifiedRoot = plainRoot(listOf(Child(
                resourceId = "${TARGET.packageName}:id/item_count", text = "30", clickable = true,
            ))),
            hint = ClickSemanticHint(allowBareNumericCountdown = true),
        )
        assertEquals(ClickRecoveryOutcome.NO_MATCH, result.diagnostics.outcome)
        assertEquals(0, result.diagnostics.selectorCandidateNodeCount)
        assertTrue(result.semanticCandidates.isEmpty())
    }

    @Test
    fun nodeCountLimitExcludesPoppedButUnprocessedTriggerNode() {
        val result = probe.recoverClickedSemanticChainOnVerifiedRoot(
            expected = IDENTITY,
            verifiedRoot = plainRoot(childCount = MAX_NODES),
            hint = ClickSemanticHint(),
        )

        assertEquals(ClickRecoveryOutcome.TRAVERSAL_LIMIT, result.diagnostics.outcome)
        assertEquals(TraversalLimitReason.NODE_COUNT, result.diagnostics.traversalLimitReason)
        assertEquals(MAX_NODES, result.diagnostics.visitedNodeCount)
    }

    @Test
    fun timeLimitExcludesPoppedButUnprocessedTriggerNode() {
        clockStepMs = 100L

        val result = probe.recoverClickedSemanticChainOnVerifiedRoot(
            expected = IDENTITY,
            verifiedRoot = plainRoot(childCount = 4),
            hint = ClickSemanticHint(),
        )

        assertEquals(ClickRecoveryOutcome.TRAVERSAL_LIMIT, result.diagnostics.outcome)
        assertEquals(TraversalLimitReason.TIME, result.diagnostics.traversalLimitReason)
        assertEquals(2, result.diagnostics.visitedNodeCount)
    }

    @Test
    fun deepProbeChainReusesSelfSnapshotAndSkipsUnusedParentQueryAfterFiveNodes() {
        val result = probe.recoverClickedSemanticChainOnVerifiedRoot(
            expected = IDENTITY,
            verifiedRoot = deepCandidateRoot(),
            hint = ClickSemanticHint(),
        )

        assertTrue(result is AccessibilityCaptureProbe.RecoveryResult.Success)
        val success = result as AccessibilityCaptureProbe.RecoveryResult.Success
        val optimizedReads = semanticReadCounts.toMap()
        assertEquals(5, success.nodes.size)
        assertEquals(1, success.semanticCandidates.size)
        assertEquals(success.nodes, success.semanticCandidates.single().nodes)
        assertEquals(4, optimizedReads.getValue(AccessibilitySemanticRead.PARENT))
        assertEquals(9, optimizedReads.getValue(AccessibilitySemanticRead.SNAPSHOT))

        val previousChain = semanticSnapshotter.copySemanticChain(
            AccessibilityNodeInfo.obtain(requireNotNull(nodes.last())),
            TARGET.packageName,
        )
        assertEquals(success.nodes, previousChain)
        assertEquals(
            5,
            semanticReadCounts.getValue(AccessibilitySemanticRead.PARENT) -
                optimizedReads.getValue(AccessibilitySemanticRead.PARENT),
        )
        assertEquals(
            5,
            semanticReadCounts.getValue(AccessibilitySemanticRead.SNAPSHOT) -
                optimizedReads.getValue(AccessibilitySemanticRead.SNAPSHOT),
        )
    }

    @Test
    fun sparseNodesDoNotAddParentOrSnapshotReads() {
        val result = probe.recoverClickedSemanticChainOnVerifiedRoot(
            expected = IDENTITY,
            verifiedRoot = plainRoot(childCount = 4),
            hint = ClickSemanticHint(),
        )

        assertEquals(ClickRecoveryOutcome.NO_MATCH, result.diagnostics.outcome)
        assertEquals(0, semanticReadCounts.getOrDefault(AccessibilitySemanticRead.PARENT, 0))
        assertEquals(5, semanticReadCounts.getValue(AccessibilitySemanticRead.SNAPSHOT))
    }

    @Test
    fun verifiedSubtreeRootRetainsActualAncestorLimitAndPackageFilter() {
        val verifiedRoot = candidateWithExternalAncestors()
        val result = probe.recoverClickedSemanticChainOnVerifiedRoot(
            expected = IDENTITY,
            verifiedRoot = verifiedRoot,
            hint = ClickSemanticHint(),
        )

        assertTrue(result is AccessibilityCaptureProbe.RecoveryResult.Success)
        val success = result as AccessibilityCaptureProbe.RecoveryResult.Success
        val legacyChain = semanticSnapshotter.copySemanticChain(
            AccessibilityNodeInfo.obtain(verifiedRoot),
            TARGET.packageName,
        )

        assertEquals(legacyChain, success.nodes)
        assertEquals(
            listOf("candidate", "ancestor_1", "ancestor_3", "ancestor_4"),
            success.nodes.map { it.resourceId?.substringAfter(":id/") },
        )
        assertEquals(
            RuleCandidateAnalyzer.stableDiffSelectorCandidates(legacyChain),
            success.semanticCandidates.single().selectors,
        )
    }

    @Test
    fun completeTraversalDropsOnlyRepeatedSelectorsAndKeepsUniqueScopedCandidates() {
        val result = probe.recoverClickedSemanticChainOnVerifiedRoot(
            expected = IDENTITY,
            verifiedRoot = sharedSelectorRoot(),
            hint = ClickSemanticHint(),
        )

        assertEquals(ClickRecoveryOutcome.NO_MATCH, result.diagnostics.outcome)
        assertTrue(result.diagnostics.isCompleteTraversal())
        assertEquals(2, result.semanticCandidates.size)
        assertTrue(result.semanticCandidates.all { candidate ->
            candidate.selectors.isNotEmpty() && candidate.selectors.all { selector ->
                selector.resourceId == SHARED_RESOURCE_ID &&
                    selector.ancestorResourceId != null
            }
        })
        assertEquals(
            setOf(
                "com.example.target:id/container_one",
                "com.example.target:id/container_two",
            ),
            result.semanticCandidates.flatMap { it.selectors }
                .mapNotNull { it.ancestorResourceId }
                .toSet(),
        )
        assertFalse(result.semanticCandidatesComplete)
    }

    @Test
    fun completeTraversalWithCandidateOverflowMarksCandidateSetIncomplete() {
        val root = plainRoot(
            List(MAX_SEMANTIC_CANDIDATES + 1) { index ->
                Child("com.example.target:id/item_$index", "ordinary", clickable = true)
            },
        )

        val result = probe.recoverClickedSemanticChainOnVerifiedRoot(
            expected = IDENTITY,
            verifiedRoot = root,
            hint = ClickSemanticHint(),
        )

        assertEquals(ClickRecoveryOutcome.NO_MATCH, result.diagnostics.outcome)
        assertTrue(result.diagnostics.isCompleteTraversal())
        assertTrue(result.semanticCandidates.isEmpty())
        assertFalse(result.semanticCandidatesComplete)
    }

    private fun root(
        matches: Int,
        text: String = "跳过",
        contentDescription: String? = null,
    ): AccessibilityNodeInfo {
        val window = AccessibilityWindowInfo.obtain().also { windows += it }
        shadowOf(window).setId(IDENTITY.windowId)
        fun node() = AccessibilityNodeInfo.obtain().apply {
            packageName = TARGET.packageName
            viewIdResourceName = "${TARGET.packageName}:id/skip"
            this.text = text
            this.contentDescription = contentDescription
            isVisibleToUser = true
            setBoundsInScreen(Rect(10, 10, 60, 60))
            shadowOf(this).setAccessibilityWindowInfo(window)
        }.also { nodes += it }
        return node().also { root ->
            repeat(matches - 1) { shadowOf(root).addChild(node()) }
        }
    }

    private fun plainRoot(childCount: Int): AccessibilityNodeInfo =
        plainRoot(List(childCount) { Child(null, null) })

    private fun plainRoot(children: List<Child>): AccessibilityNodeInfo {
        val window = AccessibilityWindowInfo.obtain().also { windows += it }
        shadowOf(window).setId(IDENTITY.windowId)
        val root = plainNode(window, Child(null, null))
        children.forEach { child -> shadowOf(root).addChild(plainNode(window, child)) }
        return root
    }

    private fun sharedSelectorRoot(): AccessibilityNodeInfo {
        val window = AccessibilityWindowInfo.obtain().also { windows += it }
        shadowOf(window).setId(IDENTITY.windowId)
        val root = plainNode(window, Child(null, null))
        listOf("container_one", "container_two").forEach { parentId ->
            val parent = plainNode(
                window,
                Child(
                    resourceId = "${TARGET.packageName}:id/$parentId",
                    text = null,
                    hasBounds = true,
                ),
            )
            val child = plainNode(
                window,
                Child(
                    resourceId = SHARED_RESOURCE_ID,
                    text = null,
                    clickable = true,
                ),
            )
            shadowOf(root).addChild(parent)
            shadowOf(parent).addChild(child)
        }
        return root
    }

    private fun deepCandidateRoot(): AccessibilityNodeInfo {
        val window = AccessibilityWindowInfo.obtain().also { windows += it }
        shadowOf(window).setId(IDENTITY.windowId)
        val root = plainNode(window, Child(null, null))
        var parent = root
        repeat(3) {
            val child = plainNode(window, Child(null, null))
            shadowOf(parent).addChild(child)
            parent = child
        }
        val candidate = plainNode(
            window,
            Child(
                resourceId = "${TARGET.packageName}:id/skip",
                text = "关闭",
                clickable = true,
            ),
        )
        shadowOf(parent).addChild(candidate)
        return root
    }

    private fun candidateWithExternalAncestors(): AccessibilityNodeInfo {
        val window = AccessibilityWindowInfo.obtain().also { windows += it }
        shadowOf(window).setId(IDENTITY.windowId)
        val physicalNodes = (0..6).map { index ->
            val packageName = if (index == 2) "com.example.foreign" else TARGET.packageName
            val id = when (index) {
                0 -> "candidate"
                2 -> "foreign_ancestor"
                else -> "ancestor_$index"
            }
            AccessibilityNodeInfo.obtain().apply {
                this.packageName = packageName
                viewIdResourceName = "$packageName:id/$id"
                text = if (index == 0) "关闭" else null
                isClickable = index == 0
                isVisibleToUser = true
                setBoundsInScreen(Rect(10, 10, 60, 60))
                shadowOf(this).setAccessibilityWindowInfo(window)
            }.also { nodes += it }
        }
        for (index in 0 until physicalNodes.lastIndex) {
            shadowOf(physicalNodes[index + 1]).addChild(physicalNodes[index])
        }
        return physicalNodes.first()
    }

    private fun plainNode(window: AccessibilityWindowInfo, child: Child) =
        AccessibilityNodeInfo.obtain().apply {
            packageName = TARGET.packageName
            viewIdResourceName = child.resourceId
            text = child.text
            isClickable = child.clickable
            isVisibleToUser = true
            if (child.hasBounds) setBoundsInScreen(Rect(10, 10, 60, 60))
            shadowOf(this).setAccessibilityWindowInfo(window)
        }.also { nodes += it }

    private data class Child(
        val resourceId: String?,
        val text: String?,
        val clickable: Boolean = false,
        val hasBounds: Boolean = clickable,
    )

    class TestAccessibilityService : AccessibilityService() {
        override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
        override fun onInterrupt() = Unit
    }

    private companion object {
        val TARGET = TargetAppId("com.example.target")
        val IDENTITY = PageIdentity(TARGET.packageName, "com.example.target.MainActivity", 7)
        const val MAX_NODES = 500
        const val MAX_SEMANTIC_CANDIDATES = 64
        const val SHARED_RESOURCE_ID = "com.example.target:id/shared"

        fun samples() = listOf(
            CaptureSample(
                id = 1L,
                targetAppId = TARGET,
                activityName = requireNotNull(IDENTITY.activityName),
                clickedAfterTargetMs = 100L,
                capturedAtEpochMs = 1_000L,
                nodes = listOf(
                    CapturedNode(
                        resourceId = "${TARGET.packageName}:id/skip",
                        text = "跳过",
                        clickable = true,
                        visibleToUser = true,
                        hasUsableBounds = true,
                    ),
                ),
                preClickUniqueSelectors = emptyList(),
            ),
        )
    }
}
