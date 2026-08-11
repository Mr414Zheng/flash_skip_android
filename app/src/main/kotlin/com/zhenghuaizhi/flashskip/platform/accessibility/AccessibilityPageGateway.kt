package com.zhenghuaizhi.flashskip.platform.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.zhenghuaizhi.flashskip.domain.model.NodeSelector
import com.zhenghuaizhi.flashskip.domain.ports.ClickResult
import com.zhenghuaizhi.flashskip.domain.ports.GestureResult
import com.zhenghuaizhi.flashskip.domain.ports.PageGateway
import com.zhenghuaizhi.flashskip.domain.ports.PageIdentity
import com.zhenghuaizhi.flashskip.domain.ports.QueryResult
import java.util.ArrayDeque
import kotlinx.coroutines.CompletableDeferred

class AccessibilityPageGateway(
    private val service: AccessibilityService,
    targetPackages: Set<String>,
    private val currentIdentity: () -> PageIdentity?,
) : PageGateway {
    private val targetPackages = targetPackages.toSet()
    private val selectorMatcher = AccessibilityNodeSelectorMatcher(service)

    override suspend fun query(identity: PageIdentity, selector: NodeSelector): QueryResult =
        when (scan(identity, selector) { Unit }) {
            ScanResult.None -> QueryResult.None
            ScanResult.Ambiguous -> QueryResult.Ambiguous
            ScanResult.PageChanged -> QueryResult.PageChanged
            ScanResult.TraversalLimit -> QueryResult.TraversalLimit
            is ScanResult.Value -> QueryResult.Unique
        }

    override suspend fun click(identity: PageIdentity, selector: NodeSelector): ClickResult =
        when (val result = scan(identity, selector) { node ->
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }) {
            ScanResult.None -> ClickResult.NodeDisappeared
            ScanResult.Ambiguous -> ClickResult.Ambiguous
            ScanResult.PageChanged -> ClickResult.PageChanged
            ScanResult.TraversalLimit -> ClickResult.TraversalLimit
            is ScanResult.Value -> if (result.value) ClickResult.Clicked else ClickResult.NotSupported
        }

    override suspend fun gestureAtCenter(
        identity: PageIdentity,
        selector: NodeSelector,
    ): GestureResult {
        val completion = CompletableDeferred<GestureResult>()
        val scan = scan(identity, selector) { node ->
            val bounds = Rect().also(node::getBoundsInScreen)
            val path = Path().apply { moveTo(bounds.exactCenterX(), bounds.exactCenterY()) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, GESTURE_DURATION_MS))
                .build()
            service.dispatchGesture(
                gesture,
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription) {
                        completion.complete(GestureResult.Completed)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription) {
                        completion.complete(GestureResult.Cancelled)
                    }
                },
                null,
            )
        }
        return when (scan) {
            ScanResult.None -> GestureResult.NodeDisappeared
            ScanResult.Ambiguous -> GestureResult.Ambiguous
            ScanResult.PageChanged -> GestureResult.PageChanged
            ScanResult.TraversalLimit -> GestureResult.TraversalLimit
            is ScanResult.Value -> if (scan.value) completion.await() else GestureResult.Rejected
        }
    }

    @Suppress("DEPRECATION")
    private fun <T> scan(
        expected: PageIdentity,
        selector: NodeSelector,
        onUnique: (AccessibilityNodeInfo) -> T,
    ): ScanResult<T> {
        // 包白名单必须先于身份、根节点及任何节点读取，避免上层误调用扩大数据访问范围。
        if (expected.packageName !in targetPackages) return ScanResult.PageChanged
        if (currentIdentity() != expected) return ScanResult.PageChanged
        val root = service.rootInActiveWindow ?: return ScanResult.None
        if (root.packageName?.toString() != expected.packageName || root.windowId != expected.windowId) {
            root.recycle()
            return ScanResult.PageChanged
        }

        val queue = ArrayDeque<NodeAtDepth>().apply { add(NodeAtDepth(root, 0)) }
        var firstMatch: AccessibilityNodeInfo? = null
        var visited = 0
        val startedAt = AndroidMonotonicClock.elapsedRealtimeMs()
        try {
            // API 26 的子节点需要在本次调用栈内回收；新系统中 recycle 为兼容性空操作。
            while (queue.isNotEmpty()) {
                val item = queue.removeFirst()
                val node = item.node
                visited++
                if (visited > MAX_NODES ||
                    item.depth > MAX_DEPTH ||
                    AndroidMonotonicClock.elapsedRealtimeMs() - startedAt > MAX_TRAVERSAL_MS
                ) {
                    node.recycle()
                    return ScanResult.TraversalLimit
                }
                if (selectorMatcher.matches(node, selector)) {
                    if (firstMatch != null) {
                        node.recycle()
                        return ScanResult.Ambiguous
                    }
                    firstMatch = node
                }
                repeat(node.childCount) { index ->
                    node.getChild(index)?.let { child ->
                        queue.add(NodeAtDepth(child, item.depth + 1))
                    }
                }
                if (node !== firstMatch) node.recycle()
            }
            return firstMatch?.let { ScanResult.Value(onUnique(it)) } ?: ScanResult.None
        } finally {
            firstMatch?.recycle()
            while (queue.isNotEmpty()) queue.removeFirst().node.recycle()
        }
    }

    private data class NodeAtDepth(val node: AccessibilityNodeInfo, val depth: Int)

    private sealed interface ScanResult<out T> {
        data object None : ScanResult<Nothing>
        data object Ambiguous : ScanResult<Nothing>
        data object PageChanged : ScanResult<Nothing>
        data object TraversalLimit : ScanResult<Nothing>
        data class Value<T>(val value: T) : ScanResult<T>
    }

    private companion object {
        const val MAX_NODES = 500
        const val MAX_DEPTH = 40
        const val MAX_TRAVERSAL_MS = 100L
        const val GESTURE_DURATION_MS = 60L
    }
}
