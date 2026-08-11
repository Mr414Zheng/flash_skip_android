package com.zhenghuaizhi.flashskip.platform.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.zhenghuaizhi.flashskip.domain.model.CapturedNode

internal class AccessibilitySemanticSnapshotter(
    private val service: AccessibilityService,
) {
    fun snapshot(node: AccessibilityNodeInfo, expectedPackage: String): CapturedNode? {
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
    ): List<CapturedNode> {
        val nodes = mutableListOf<CapturedNode>()
        var current: AccessibilityNodeInfo? = initialNode
        var depth = 0
        while (current != null && depth < MAX_CAPTURE_DEPTH) {
            val node = current
            val parent = runCatching { node.parent }.getOrNull()
            try {
                snapshot(node, expectedPackage)?.let(nodes::add)
            } finally {
                node.recycle()
            }
            current = parent
            depth++
        }
        current?.recycle()
        return nodes
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
