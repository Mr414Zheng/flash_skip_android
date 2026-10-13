package com.zhenghuaizhi.flashskip.platform.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.zhenghuaizhi.flashskip.domain.model.NodeSelector
import com.zhenghuaizhi.flashskip.domain.ports.MonotonicClock
import com.zhenghuaizhi.flashskip.domain.ports.PageIdentity
import com.zhenghuaizhi.flashskip.testing.TARGET
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.annotation.Resetter
import org.robolectric.shadows.ShadowAccessibilityNodeInfo
import org.robolectric.shadows.ShadowAccessibilityService

class P2AccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
}

/** 系统每次查询返回独立窗口对象；避免测试缓存被被测适配器的正常 recycle 清空。 */
@Implements(AccessibilityService::class)
@Suppress("DEPRECATION")
class CopyingAccessibilityServiceShadow : ShadowAccessibilityService() {
    @Implementation
    override fun getWindows(): List<AccessibilityWindowInfo> =
        super.getWindows().map { AccessibilityWindowInfo.obtain(it) }
}

/** 仅注入系统索引、刷新变化及不可读子树，不实现候选匹配或消失判断。 */
@Implements(AccessibilityNodeInfo::class)
@Suppress("DEPRECATION")
class FaultableAccessibilityNodeShadow : ShadowAccessibilityNodeInfo() {
    @RealObject private lateinit var realNode: AccessibilityNodeInfo

    @Implementation
    override fun refresh(): Boolean = refreshHooks[realNode.viewIdResourceName]?.invoke(realNode) ?: super.refresh()

    @Implementation
    fun findAccessibilityNodeInfosByViewId(viewId: String): List<AccessibilityNodeInfo> =
        index[realNode.viewIdResourceName to viewId].orEmpty().map { AccessibilityNodeInfo.obtain(it) }

    @Implementation
    fun findAccessibilityNodeInfosByText(text: String): List<AccessibilityNodeInfo> =
        textIndex[realNode.viewIdResourceName to text].orEmpty().map { AccessibilityNodeInfo.obtain(it) }

    @Implementation
    override fun getChild(index: Int): AccessibilityNodeInfo? =
        if (realNode.viewIdResourceName to index in unreadableChildren) null else super.getChild(index)

    companion object {
        val index = mutableMapOf<Pair<String?, String>, List<AccessibilityNodeInfo>>()
        val textIndex = mutableMapOf<Pair<String?, String>, List<AccessibilityNodeInfo>>()
        val refreshHooks = mutableMapOf<String?, (AccessibilityNodeInfo) -> Boolean>()
        val unreadableChildren = mutableSetOf<Pair<String?, Int>>()

        @JvmStatic @Resetter fun reset() {
            index.clear()
            textIndex.clear()
            refreshHooks.clear()
            unreadableChildren.clear()
        }
    }
}

@Suppress("DEPRECATION")
internal class AccessibilityFixture {
    init {
        FaultableAccessibilityNodeShadow.reset()
    }

    val service = Robolectric.buildService(P2AccessibilityService::class.java).create().get()
    val serviceShadow = shadowOf(service)
    val window: AccessibilityWindowInfo = AccessibilityWindowInfo.obtain().also {
        shadowOf(it).setId(7)
        shadowOf(it).setType(AccessibilityWindowInfo.TYPE_APPLICATION)
        shadowOf(it).setActive(true)
    }
    var now = 0L
    var clockRead: () -> Long = { now }
    val clock = object : MonotonicClock { override fun elapsedRealtimeMs() = clockRead() }
    val identity = PageIdentity(TARGET.packageName, "${TARGET.packageName}.LaunchActivity", 7)
    var currentIdentity: PageIdentity? = identity
    var targets = setOf(TARGET.packageName)
    val selector = NodeSelector(resourceId = "${TARGET.packageName}:id/skip")
    val matcher = AccessibilityNodeSelectorMatcher(service)
    val finder = AccessibilityCandidateFinder(matcher, clock)
    val evidence = AccessibilityActionEvidence(matcher, clock)
    val gateway = AccessibilityPageGateway(service, { targets }, clock) { currentIdentity }
    val root = node("root")
    val target = node("skip", "跳过", actionable = true)

    init {
        add(root, target)
        install(root)
        index(root, target)
        serviceShadow.setCanDispatchGestures(true)
    }

    fun node(id: String, text: String? = null, actionable: Boolean = false): AccessibilityNodeInfo =
        AccessibilityNodeInfo.obtain().also { node ->
            node.packageName = TARGET.packageName
            node.className = "android.widget.Button"
            node.viewIdResourceName = "${TARGET.packageName}:id/$id"
            node.text = text
            node.isVisibleToUser = true
            node.isClickable = actionable
            node.setBoundsInScreen(Rect(10, 10, 30, 30))
            shadowOf(node).setAccessibilityWindowInfo(window)
            shadowOf(node).setRefreshReturnValue(true)
            shadowOf(node).setOnPerformActionListener { _, _ -> true }
        }

    fun add(parent: AccessibilityNodeInfo, child: AccessibilityNodeInfo) { shadowOf(parent).addChild(child) }

    fun index(rootNode: AccessibilityNodeInfo, vararg nodes: AccessibilityNodeInfo) {
        nodes.groupBy { it.viewIdResourceName }.forEach { (id, values) ->
            FaultableAccessibilityNodeShadow.index[rootNode.viewIdResourceName to id] = values
        }
    }

    fun install(rootNode: AccessibilityNodeInfo, windowInfo: AccessibilityWindowInfo = window) {
        shadowOf(rootNode).setAccessibilityWindowInfo(windowInfo)
        shadowOf(windowInfo).setRoot(rootNode)
        serviceShadow.setWindows(listOf(windowInfo))
        serviceShadow.setRootInActiveWindow(rootNode)
    }
}
