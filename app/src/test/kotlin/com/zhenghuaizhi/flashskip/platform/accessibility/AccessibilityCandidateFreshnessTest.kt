package com.zhenghuaizhi.flashskip.platform.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.zhenghuaizhi.flashskip.domain.model.NodeSelector
import com.zhenghuaizhi.flashskip.domain.model.TextCondition
import com.zhenghuaizhi.flashskip.domain.ports.ClickResult
import com.zhenghuaizhi.flashskip.domain.ports.PageIdentity
import com.zhenghuaizhi.flashskip.domain.ports.QueryResult
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
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
import org.robolectric.annotation.RealObject
import org.robolectric.shadows.ShadowAccessibilityNodeInfo
import org.robolectric.shadows.ShadowAccessibilityService
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], shadows = [
    AccessibilityCandidateFreshnessTest.RefreshingNodeShadow::class,
    AccessibilityCandidateFreshnessTest.CopyingWindowsShadow::class,
])
@Suppress("DEPRECATION")
class AccessibilityCandidateFreshnessTest {
    private lateinit var controller: ServiceController<TestService>
    private lateinit var root: AccessibilityNodeInfo
    private lateinit var window: AccessibilityWindowInfo
    private lateinit var gateway: AccessibilityPageGateway

    @Before
    fun setUp() {
        ShadowLog.clear()
        RefreshingNodeShadow.indexed = null
        RefreshingNodeShadow.onRefresh = null
        controller = Robolectric.buildService(TestService::class.java).create()
        window = AccessibilityWindowInfo.obtain()
        shadowOf(window).apply {
            setId(IDENTITY.windowId)
            setType(AccessibilityWindowInfo.TYPE_APPLICATION)
            setActive(true)
        }
        root = node().apply { viewIdResourceName = "test.app:id/ad_parent" }
        shadowOf(root).setAccessibilityWindowInfo(window)
        shadowOf(window).setRoot(root)
        shadowOf(controller.get()).setWindows(listOf(window))
        gateway = AccessibilityPageGateway(controller.get(), { setOf("test.app") }, currentIdentity = { IDENTITY })
    }

    @After
    fun tearDown() {
        RefreshingNodeShadow.indexed = null
        RefreshingNodeShadow.onRefresh = null
        controller.destroy()
    }

    @Test
    fun ordinaryPageWithoutSkipNeverClicks() = runTest {
        assertEquals(QueryResult.None, gateway.query(IDENTITY, SELECTOR))
        assertEquals(ClickResult.NodeDisappeared, gateway.click(IDENTITY, SELECTOR))
        assertTrue(shadowOf(root).performedActions.isEmpty())
    }

    @Test
    fun ordinarySkipPrefixIsRejectedWithoutPrintingItsText() = runTest {
        val candidate = addCandidate("跳过推荐内容")
        RefreshingNodeShadow.indexed = listOf(candidate)
        assertEquals(QueryResult.None, gateway.query(IDENTITY, SELECTOR))
        assertEquals(ClickResult.NodeDisappeared, gateway.click(IDENTITY, SELECTOR))
        assertTrue(shadowOf(candidate).performedActions.isEmpty())
        val logs = logs()
        assertTrue(logs.any { it.contains("prefixLike=true") && it.contains("result=NO_MATCH") })
        assertTrue(logs.none { it.contains("跳过推荐内容") })
    }

    @Test
    fun indexedCachedSkipThatBecomesHiddenAfterRefreshNeverClicks() = runTest {
        val candidate = addCandidate("跳过")
        RefreshingNodeShadow.indexed = listOf(candidate)
        RefreshingNodeShadow.onRefresh = { node ->
            if (node.text == "跳过") node.isVisibleToUser = false
            null
        }
        assertEquals(QueryResult.None, gateway.query(IDENTITY, SELECTOR))
        assertEquals(ClickResult.NodeDisappeared, gateway.click(IDENTITY, SELECTOR))
        assertTrue(shadowOf(candidate).performedActions.isEmpty())
        assertTrue(logs().any {
            it.contains("cachedMatch=true") && it.contains("refreshed=true") && it.contains("result=NO_MATCH")
        })
    }

    @Test
    fun obsoleteIndexedSkipIsUnavailableAndCannotProveAbsenceOrReceiveClick() = runTest {
        val candidate = addCandidate("跳过")
        RefreshingNodeShadow.indexed = listOf(candidate)
        RefreshingNodeShadow.onRefresh = { node -> if (node.text == "跳过") false else null }
        assertEquals(QueryResult.Unavailable, gateway.query(IDENTITY, SELECTOR))
        assertEquals(ClickResult.Unavailable, gateway.click(IDENTITY, SELECTOR))
        assertTrue(shadowOf(candidate).performedActions.isEmpty())
        assertTrue(logs().any { it.contains("refreshed=false") && it.contains("result=UNAVAILABLE") })
    }

    @Test
    fun candidateThatChangesPackageDuringRefreshCannotReceiveAction() = runTest {
        val candidate = addCandidate("跳过")
        RefreshingNodeShadow.indexed = listOf(candidate)
        RefreshingNodeShadow.onRefresh = { node ->
            if (node.text == "跳过") node.packageName = "outside.app"
            null
        }
        assertEquals(QueryResult.Unavailable, gateway.query(IDENTITY, SELECTOR))
        assertEquals(ClickResult.Unavailable, gateway.click(IDENTITY, SELECTOR))
        assertTrue(shadowOf(candidate).performedActions.isEmpty())
    }

    @Test
    fun missingOrObsoleteRootDoesNotMeanDismissButtonDisappeared() = runTest {
        shadowOf(root).setRefreshReturnValue(false)
        assertEquals(QueryResult.Unavailable, gateway.query(IDENTITY, SELECTOR))
        shadowOf(window).setRoot(null)
        assertEquals(QueryResult.Unavailable, gateway.query(IDENTITY, SELECTOR))
    }

    @Test
    fun freshSkipWithStableAncestorStillReceivesNodeClick() = runTest {
        val candidate = addCandidate("跳过 3秒")
        RefreshingNodeShadow.indexed = listOf(candidate)
        assertEquals(QueryResult.Unique, gateway.query(IDENTITY, SELECTOR))
        assertEquals(ClickResult.Clicked, gateway.click(IDENTITY, SELECTOR))
        assertTrue(logs().any { it.contains("refreshed=true") && it.contains("result=MATCH") })
    }

    private fun addCandidate(text: String): AccessibilityNodeInfo = node().apply {
        this.text = text
        isClickable = true
        addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
        shadowOf(this).setAccessibilityWindowInfo(this@AccessibilityCandidateFreshnessTest.window)
        shadowOf(this).setOnPerformActionListener { _, _ -> true }
        shadowOf(root).addChild(this)
    }

    private fun node() = AccessibilityNodeInfo.obtain().apply {
        packageName = "test.app"
        isVisibleToUser = true
        setBoundsInScreen(Rect(10, 10, 60, 60))
    }

    private fun logs() = ShadowLog.getLogsForTag(AutomationTraceLog.TAG).map { it.msg }

    @Implements(AccessibilityNodeInfo::class)
    class RefreshingNodeShadow : ShadowAccessibilityNodeInfo() {
        @RealObject private lateinit var node: AccessibilityNodeInfo

        @Implementation
        public override fun refresh(): Boolean = onRefresh?.invoke(node) ?: super.refresh()

        @Implementation
        public override fun getParent(): AccessibilityNodeInfo? =
            super.getParent()?.let(AccessibilityNodeInfo::obtain)

        @Implementation
        fun findAccessibilityNodeInfosByText(text: String): List<AccessibilityNodeInfo> =
            indexed?.map(AccessibilityNodeInfo::obtain).orEmpty()

        companion object {
            var indexed: List<AccessibilityNodeInfo>? = null
            var onRefresh: ((AccessibilityNodeInfo) -> Boolean?)? = null
        }
    }

    @Implements(AccessibilityService::class)
    class CopyingWindowsShadow : ShadowAccessibilityService() {
        @Implementation
        public override fun getWindows(): List<AccessibilityWindowInfo> =
            super.getWindows().map(AccessibilityWindowInfo::obtain)
    }

    class TestService : AccessibilityService() {
        override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
        override fun onInterrupt() = Unit
    }

    companion object {
        private val IDENTITY = PageIdentity("test.app", "test.app.MainActivity", 1)
        private val SELECTOR = NodeSelector(
            text = TextCondition.SkipCountdown("跳过"), ancestorResourceId = "test.app:id/ad_parent",
            ancestorDistance = 1,
        )
    }
}
