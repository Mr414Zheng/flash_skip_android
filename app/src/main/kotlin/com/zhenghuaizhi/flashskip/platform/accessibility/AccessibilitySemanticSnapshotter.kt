package com.zhenghuaizhi.flashskip.platform.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.zhenghuaizhi.flashskip.domain.model.CapturedNode

internal class AccessibilitySemanticSnapshotter(
    private val service: AccessibilityService,
    private val onRead: (AccessibilitySemanticRead) -> Unit = {},
) {
    fun snapshot(node: AccessibilityNodeInfo, expectedPackage: String): CapturedNode? {
        onRead(AccessibilitySemanticRead.SNAPSHOT)
        if (node.packageName?.toString() != expectedPackage) return null
        val bounds = Rect().also(node::getBoundsInScreen)
        val metrics = service.resources.displayMetrics
        return CapturedNode(
            resourceId = sanitize(node.viewIdResourceName, MAX_RESOURCE_ID_LENGTH),
            text = sanitize(node.text?.toString(), MAX_SEMANTIC_LENGTH),
            contentDescription = sanitize(
                node.contentDescription?.toString(),
                MAX_SEMANTIC_LENGTH,
            ),
            clickable = node.isClickable,
            supportsActionClick = node.actionList.any {
                it.id == AccessibilityNodeInfo.ACTION_CLICK
            },
            visibleToUser = node.isVisibleToUser,
            hasUsableBounds = bounds.width() > 0 && bounds.height() > 0 &&
                bounds.centerX() in 0 until metrics.widthPixels &&
                bounds.centerY() in 0 until metrics.heightPixels,
        )
    }

    /** 接管 initialNode 及其新取得父节点的释放责任，不把 Android 节点带出当前调用栈。 */
    @Suppress("DEPRECATION")
    fun copySemanticChain(
        initialNode: AccessibilityNodeInfo,
        expectedPackage: String,
    ): List<CapturedNode> = copySemanticChain(
        initialNode = initialNode,
        expectedPackage = expectedPackage,
        initialSnapshot = null,
        reuseInitialSnapshot = false,
        skipUnusedTerminalParentQuery = false,
    )

    /** 复用本次probe已取得的自身快照；五层链之外不再读取第六个物理节点。 */
    @Suppress("DEPRECATION")
    fun copySemanticChainReusingSnapshot(
        initialNode: AccessibilityNodeInfo,
        expectedPackage: String,
        initialSnapshot: CapturedNode?,
    ): List<CapturedNode> = copySemanticChain(
        initialNode = initialNode,
        expectedPackage = expectedPackage,
        initialSnapshot = initialSnapshot,
        reuseInitialSnapshot = true,
        skipUnusedTerminalParentQuery = true,
    )

    @Suppress("DEPRECATION")
    private fun copySemanticChain(
        initialNode: AccessibilityNodeInfo,
        expectedPackage: String,
        initialSnapshot: CapturedNode?,
        reuseInitialSnapshot: Boolean,
        skipUnusedTerminalParentQuery: Boolean,
    ): List<CapturedNode> {
        val nodes = mutableListOf<CapturedNode>()
        var current: AccessibilityNodeInfo? = initialNode
        var depth = 0
        while (current != null && depth < MAX_CAPTURE_DEPTH) {
            val node = current
            val parent = if (skipUnusedTerminalParentQuery && depth == MAX_CAPTURE_DEPTH - 1) {
                null
            } else {
                parent(node)
            }
            try {
                val captured = if (reuseInitialSnapshot && depth == 0) {
                    initialSnapshot
                } else {
                    snapshot(node, expectedPackage)
                }
                captured?.let(nodes::add)
            } finally {
                node.recycle()
            }
            current = parent
            depth++
        }
        current?.recycle()
        return nodes
    }

    @Suppress("DEPRECATION")
    private fun parent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        onRead(AccessibilitySemanticRead.PARENT)
        return runCatching { node.parent }.getOrNull()
    }

    fun sanitizeSemantic(value: String?): String? = sanitize(value, MAX_SEMANTIC_LENGTH)

    private fun sanitize(value: String?, maxLength: Int): String? = value
        ?.map { if (it.isISOControl()) ' ' else it }
        ?.joinToString("")
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.take(maxLength)

    private companion object {
        const val MAX_CAPTURE_DEPTH = 5
        const val MAX_RESOURCE_ID_LENGTH = 180
        const val MAX_SEMANTIC_LENGTH = 80
    }
}

internal enum class AccessibilitySemanticRead {
    SNAPSHOT,
    PARENT,
}
