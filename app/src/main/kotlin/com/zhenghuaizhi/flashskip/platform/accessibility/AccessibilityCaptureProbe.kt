package com.zhenghuaizhi.flashskip.platform.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo
import com.zhenghuaizhi.flashskip.domain.model.CapturedSelector
import com.zhenghuaizhi.flashskip.domain.ports.PageIdentity
import java.util.ArrayDeque

internal class AccessibilityCaptureProbe(
    private val service: AccessibilityService,
    targetPackages: Set<String>,
    private val currentIdentity: () -> PageIdentity?,
) {
    private val targetPackages = targetPackages.toSet()
    private val selectorMatcher = AccessibilityNodeSelectorMatcher(service)

    @Suppress("DEPRECATION")
    fun uniqueSelectors(
        expected: PageIdentity,
        selectors: List<CapturedSelector>,
    ): ProbeResult {
        if (selectors.isEmpty()) return ProbeResult.Success(emptySet())
        // 与自动化 Gateway 一致：白名单在身份、根节点和任何节点读取之前执行。
        if (expected.packageName !in targetPackages) return ProbeResult.PageChanged
        if (currentIdentity() != expected) return ProbeResult.PageChanged
        val root = service.rootInActiveWindow ?: return ProbeResult.NoRoot
        if (root.packageName?.toString() != expected.packageName || root.windowId != expected.windowId) {
            root.recycle()
            return ProbeResult.PageChanged
        }

        val queue = ArrayDeque<NodeAtDepth>().apply { add(NodeAtDepth(root, 0)) }
        val counts = selectors.associateWith { 0 }.toMutableMap()
        var visited = 0
        val startedAt = AndroidMonotonicClock.elapsedRealtimeMs()
        try {
            while (queue.isNotEmpty()) {
                val item = queue.removeFirst()
                val node = item.node
                visited++
                if (visited > MAX_NODES ||
                    item.depth > MAX_DEPTH ||
                    AndroidMonotonicClock.elapsedRealtimeMs() - startedAt > MAX_TRAVERSAL_MS
                ) {
                    node.recycle()
                    return ProbeResult.TraversalLimit
                }
                selectors.forEach { selector ->
                    if (counts.getValue(selector) < 2 &&
                        selectorMatcher.matches(node, selector.toNodeSelector())
                    ) {
                        counts[selector] = counts.getValue(selector) + 1
                    }
                }
                repeat(node.childCount) { index ->
                    node.getChild(index)?.let { child ->
                        queue.add(NodeAtDepth(child, item.depth + 1))
                    }
                }
                node.recycle()
            }
            return ProbeResult.Success(
                counts.filterValues { it == 1 }.keys,
            )
        } finally {
            while (queue.isNotEmpty()) queue.removeFirst().node.recycle()
        }
    }

    private data class NodeAtDepth(val node: AccessibilityNodeInfo, val depth: Int)

    sealed interface ProbeResult {
        data class Success(val selectors: Set<CapturedSelector>) : ProbeResult
        data object NoRoot : ProbeResult
        data object PageChanged : ProbeResult
        data object TraversalLimit : ProbeResult
    }

    private companion object {
        const val MAX_NODES = 500
        const val MAX_DEPTH = 40
        const val MAX_TRAVERSAL_MS = 100L
    }
}
