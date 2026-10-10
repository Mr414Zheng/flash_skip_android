package com.zhenghuaizhi.flashskip.platform.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.zhenghuaizhi.flashskip.domain.model.ActionReason
import com.zhenghuaizhi.flashskip.domain.model.NodeSelector
import com.zhenghuaizhi.flashskip.domain.model.TextCondition
import com.zhenghuaizhi.flashskip.domain.ports.AbsenceProof
import com.zhenghuaizhi.flashskip.domain.ports.ActionTargetEvidence
import com.zhenghuaizhi.flashskip.domain.ports.ClickResult
import com.zhenghuaizhi.flashskip.domain.ports.PageIdentity
import com.zhenghuaizhi.flashskip.domain.ports.TargetObservation
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], shadows = [
    AccessibilityActionObservationTest.IndexedNodeShadow::class,
    AccessibilityCandidateFreshnessTest.CopyingWindowsShadow::class,
])
@Suppress("DEPRECATION")
class AccessibilityActionObservationTest {
    private lateinit var controller: ServiceController<TestService>
    private lateinit var window: AccessibilityWindowInfo
    private lateinit var root: AccessibilityNodeInfo
    private lateinit var container: AccessibilityNodeInfo
    private lateinit var target: AccessibilityNodeInfo
    private lateinit var gateway: AccessibilityPageGateway
    private var identity = IDENTITY

    @Before
    fun setUp() {
        IndexedNodeShadow.nodes = emptyList()
        IndexedNodeShadow.textQueries = 0
        IndexedNodeShadow.childReads = 0
        IndexedNodeShadow.emptyIds = emptySet()
        controller = Robolectric.buildService(TestService::class.java).create()
        window = AccessibilityWindowInfo.obtain()
        shadowOf(window).apply { setId(1); setType(AccessibilityWindowInfo.TYPE_APPLICATION); setActive(true) }
        root = node("android:id/content", "FrameLayout")
        container = node("test.app:id/ad", "AdLayout")
        target = node(null, "TextView").apply {
            text = "跳过 3秒"
            isClickable = true
            addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
            shadowOf(this).setOnPerformActionListener { _, _ -> true }
        }
        shadowOf(container).addChild(target)
        shadowOf(container).addChild(node("test.app:id/art", "ImageView"))
        shadowOf(root).addChild(container)
        installRoot(root, listOf(root, container, target))
        gateway = AccessibilityPageGateway(controller.get(), { setOf("test.app") }, currentIdentity = { identity })
        assertEquals(IDENTITY, identity)
        assertEquals(1, root.windowId)
    }

    @After
    fun tearDown() {
        IndexedNodeShadow.nodes = emptyList()
        IndexedNodeShadow.emptyIds = emptySet()
        controller.destroy()
    }

    @Test
    fun originalContainerRemovedAboveComplexHomeDoesNotRequireWholePageSearch() = runTest {
        val evidence = clickEvidence()
        val homeRoot = node("android:id/content", "FrameLayout")
        val home = node("test.app:id/home", "HomeLayout")
        repeat(600) { shadowOf(home).addChild(node("test.app:id/item-$it", "TextView")) }
        shadowOf(homeRoot).addChild(home)
        installRoot(homeRoot, listOf(homeRoot, home))
        IndexedNodeShadow.textQueries = 0
        val result = gateway.observeAction(IDENTITY, evidence, allowPageFallback = false)
        assertTrue(result is TargetObservation.Absent)
        assertEquals(AbsenceProof.LOCAL_CONTAINER_REMOVED, (result as TargetObservation.Absent).proof)
        assertEquals(0, IndexedNodeShadow.textQueries)
        assertTrue(shadowOf(home).performedActions.isEmpty())
    }

    @Test
    fun emptyContainerIndexAloneCannotProveItsRemoval() = runTest {
        val evidence = clickEvidence()
        IndexedNodeShadow.emptyIds = setOf("test.app:id/ad")
        val result = gateway.observeAction(IDENTITY, evidence, allowPageFallback = false)
        assertTrue(result !is TargetObservation.Absent)
    }

    @Test
    fun hiddenButtonWhileAdContainerRemainsIsInsufficient() = runTest {
        val evidence = clickEvidence()
        target.isVisibleToUser = false
        val result = gateway.observeAction(IDENTITY, evidence, allowPageFallback = false)
        assertTrue(result is TargetObservation.Insufficient)
    }

    @Test
    fun removingOnlyButtonWithoutReplacingAdContentIsInsufficient() = runTest {
        val evidence = clickEvidence()
        val replacementRoot = node("android:id/content", "FrameLayout")
        val remainingAd = node("test.app:id/ad", "AdLayout")
        shadowOf(remainingAd).addChild(node("test.app:id/art", "ImageView"))
        shadowOf(replacementRoot).addChild(remainingAd)
        installRoot(replacementRoot, listOf(replacementRoot, remainingAd))
        assertTrue(gateway.observeAction(IDENTITY, evidence, false) is TargetObservation.Insufficient)
    }

    @Test
    fun countdownTextChangeKeepsOriginalTargetIdentityAndDoesNotTriggerAction() = runTest {
        val evidence = clickEvidence()
        target.text = "跳过 2秒"
        val before = shadowOf(target).performedActions.size
        assertEquals(TargetObservation.Present(true), gateway.observeAction(IDENTITY, evidence, false))
        assertEquals(before, shadowOf(target).performedActions.size)
    }

    @Test
    fun changedWindowOrUnreadableParentNeverBecomesAbsenceProof() = runTest {
        val evidence = clickEvidence()
        shadowOf(root).setRefreshReturnValue(false)
        assertEquals(TargetObservation.Insufficient(ActionReason.NODE_UNAVAILABLE), gateway.observeAction(IDENTITY, evidence, false))
        identity = IDENTITY.copy(windowId = 2)
        assertEquals(TargetObservation.Insufficient(ActionReason.PAGE_CHANGED), gateway.observeAction(identity, evidence, false))
    }

    @Test
    fun removedOriginalWindowRequiresVerifiedSameAppWindowRatherThanPageChangedAlone() = runTest {
        val evidence = clickEvidence()
        window = AccessibilityWindowInfo.obtain()
        shadowOf(window).apply { setId(2); setType(AccessibilityWindowInfo.TYPE_APPLICATION); setActive(true) }
        identity = IDENTITY.copy(windowId = 2)
        val newRoot = node("android:id/content", "FrameLayout")
        installRoot(newRoot, listOf(newRoot))
        val result = gateway.observeAction(identity, evidence, false)
        assertTrue(result is TargetObservation.Absent)
        assertEquals(AbsenceProof.ORIGINAL_WINDOW_REMOVED, (result as TargetObservation.Absent).proof)
        identity = identity.copy(activityName = "test.app.OtherActivity")
        assertEquals(TargetObservation.Insufficient(ActionReason.PAGE_CHANGED), gateway.observeAction(identity, evidence, false))
    }

    @Test
    fun retryRejectsDifferentSemanticTargetEvenWhenRuleStillMatches() = runTest {
        val evidence = clickEvidence()
        target.className = "DifferentView"
        val before = shadowOf(target).performedActions.size
        assertEquals(ClickResult.NotActionable, gateway.submitClick(IDENTITY, SELECTOR, evidence).result)
        assertEquals(before, shadowOf(target).performedActions.size)
    }

    @Test
    fun anotherMatchingTargetPreventsAbsenceAndUniqueRetryEvidence() = runTest {
        val evidence = clickEvidence()
        val replacement = node(null, "DifferentView").apply { text = "跳过" }
        shadowOf(container).addChild(replacement)
        assertEquals(TargetObservation.Present(false), gateway.observeAction(IDENTITY, evidence, false))
        target.isVisibleToUser = false
        assertTrue(gateway.observeAction(IDENTITY, evidence, true) is TargetObservation.Insufficient)
    }

    @Test
    fun newWindowContainingSameAdvertisementDoesNotConfirmClosure() = runTest {
        val evidence = clickEvidence()
        window = AccessibilityWindowInfo.obtain()
        shadowOf(window).apply { setId(2); setType(AccessibilityWindowInfo.TYPE_APPLICATION); setActive(true) }
        identity = IDENTITY.copy(windowId = 2)
        val newRoot = node("android:id/content", "FrameLayout")
        val newAd = node("test.app:id/ad", "AdLayout")
        val newSkip = node(null, "TextView").apply { text = "跳过 2秒" }
        shadowOf(newAd).addChild(newSkip)
        shadowOf(newRoot).addChild(newAd)
        installRoot(newRoot, listOf(newRoot, newAd, newSkip))
        assertEquals(TargetObservation.Present(true), gateway.observeAction(identity, evidence, true))
    }

    @Test
    fun fullPageFallbackFindsRelocatedAdWhenIndexesAreEmpty() = runTest {
        val evidence = clickEvidence().copy(parentResourceId = null, parentContext = null)
        IndexedNodeShadow.emptyIds = setOf("test.app:id/ad")
        assertEquals(TargetObservation.Present(true), gateway.observeAction(IDENTITY, evidence, true))
        target.isVisibleToUser = false
        assertTrue(gateway.observeAction(IDENTITY, evidence, true) is TargetObservation.Insufficient)
    }

    @Test
    fun completePageFallbackRequiresOriginalAdRegionAndTargetBothRemoved() = runTest {
        val evidence = clickEvidence().copy(parentResourceId = null, parentContext = null)
        val home = node("android:id/content", "FrameLayout")
        installRoot(home, listOf(home))
        val result = gateway.observeAction(IDENTITY, evidence, true)
        assertTrue(result is TargetObservation.Absent)
        assertEquals(AbsenceProof.COMPLETE_PAGE, (result as TargetObservation.Absent).proof)
    }

    @Test
    fun incompletePageFallbackNeverBecomesAbsenceProof() = runTest {
        val evidence = clickEvidence().copy(parentResourceId = null, parentContext = null)
        val home = node("android:id/content", "FrameLayout")
        repeat(510) { shadowOf(home).addChild(node(null, "HomeCard")) }
        installRoot(home, listOf(home))
        IndexedNodeShadow.childReads = 0
        assertEquals(TargetObservation.Insufficient(ActionReason.TRAVERSAL_LIMIT), gateway.observeAction(IDENTITY, evidence, true))
        assertTrue(IndexedNodeShadow.childReads <= 500)
    }

    private suspend fun clickEvidence(): ActionTargetEvidence {
        val submission = gateway.submitClick(IDENTITY, SELECTOR)
        assertEquals(ClickResult.Clicked, submission.result)
        val evidence = requireNotNull(submission.evidence)
        assertEquals("test.app:id/ad", evidence.containerResourceId)
        assertEquals("android:id/content", evidence.parentResourceId)
        assertNotNull(evidence.parentContext)
        return evidence
    }

    private fun installRoot(newRoot: AccessibilityNodeInfo, nodes: List<AccessibilityNodeInfo>) {
        shadowOf(window).setRoot(newRoot)
        shadowOf(controller.get()).setWindows(listOf(window))
        IndexedNodeShadow.nodes = nodes
    }

    private fun node(id: String?, className: String): AccessibilityNodeInfo = AccessibilityNodeInfo.obtain().apply {
        packageName = "test.app"
        viewIdResourceName = id
        this.className = className
        isVisibleToUser = true
        setBoundsInScreen(Rect(10, 10, 60, 60))
        shadowOf(this).setAccessibilityWindowInfo(this@AccessibilityActionObservationTest.window)
    }

    @Implements(AccessibilityNodeInfo::class)
    class IndexedNodeShadow : ShadowAccessibilityNodeInfo() {
        @Implementation
        fun findAccessibilityNodeInfosByViewId(id: String): List<AccessibilityNodeInfo> =
            if (id in emptyIds) emptyList() else nodes.filter { it.viewIdResourceName == id }.map(AccessibilityNodeInfo::obtain)

        @Implementation
        fun findAccessibilityNodeInfosByText(text: String): List<AccessibilityNodeInfo> {
            textQueries++
            return nodes.filter { it.text?.contains(text) == true }.map(AccessibilityNodeInfo::obtain)
        }

        @Implementation
        public override fun getChild(index: Int): AccessibilityNodeInfo? {
            childReads++
            return super.getChild(index)?.let(AccessibilityNodeInfo::obtain)
        }

        @Implementation
        public override fun getParent(): AccessibilityNodeInfo? = super.getParent()?.let(AccessibilityNodeInfo::obtain)

        companion object {
            var nodes: List<AccessibilityNodeInfo> = emptyList()
            var textQueries = 0
            var childReads = 0
            var emptyIds: Set<String> = emptySet()
        }
    }

    class TestService : AccessibilityService() {
        override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
        override fun onInterrupt() = Unit
    }

    companion object {
        private val IDENTITY = PageIdentity("test.app", "test.app.MainActivity", 1)
        private val SELECTOR = NodeSelector(text = TextCondition.SkipCountdown("跳过"),
            ancestorResourceId = "test.app:id/ad", ancestorDistance = 1)
    }
}
