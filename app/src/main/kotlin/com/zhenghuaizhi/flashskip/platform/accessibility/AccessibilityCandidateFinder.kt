package com.zhenghuaizhi.flashskip.platform.accessibility

import android.view.accessibility.AccessibilityNodeInfo
import com.zhenghuaizhi.flashskip.domain.model.NodeSelector
import com.zhenghuaizhi.flashskip.domain.model.TextCondition
import java.util.ArrayDeque

/**
 * 在已完成包名、窗口与当前页面校验的根节点内查找唯一候选。
 *
 * 平台索引只负责缩小候选集合，最终仍由完整 selector（含祖先约束）复核；索引无法给出
 * 确定结论时回退到原有有界遍历，避免为了提速放宽匹配条件。
 */
internal class AccessibilityCandidateFinder(
    private val selectorMatcher: AccessibilityNodeSelectorMatcher,
) {
    @Suppress("DEPRECATION")
    fun <T> findUnique(
        expectedPackage: String,
        root: AccessibilityNodeInfo,
        selector: NodeSelector,
        onUnique: (AccessibilityNodeInfo) -> T,
    ): CandidateSearchResult<T> {
        val startedAt = AndroidMonotonicClock.elapsedRealtimeMs()
        val indexedNodes = indexedCandidates(root, selector)
        if (indexedNodes != null) {
            when (
                val indexedResult = evaluateCandidates(
                    expectedPackage = expectedPackage,
                    nodes = indexedNodes,
                    selector = selector,
                    startedAt = startedAt,
                    onUnique = onUnique,
                )
            ) {
                CandidateSearchResult.None -> Unit
                else -> return indexedResult
            }
        }
        return traverseFallback(expectedPackage, root, selector, startedAt, onUnique)
    }

    private fun indexedCandidates(
        root: AccessibilityNodeInfo,
        selector: NodeSelector,
    ): List<AccessibilityNodeInfo>? = when {
        selector.resourceId != null -> runCatching {
            root.findAccessibilityNodeInfosByViewId(selector.resourceId)
        }.getOrNull()

        selector.text != null -> runCatching {
            root.findAccessibilityNodeInfosByText(selector.text.queryValue())
        }.getOrNull()

        else -> null
    }

    private fun TextCondition.queryValue(): String = when (this) {
        is TextCondition.Exact -> value
        is TextCondition.Prefix -> value
    }

    @Suppress("DEPRECATION")
    private fun <T> evaluateCandidates(
        expectedPackage: String,
        nodes: List<AccessibilityNodeInfo>,
        selector: NodeSelector,
        startedAt: Long,
        onUnique: (AccessibilityNodeInfo) -> T,
    ): CandidateSearchResult<T> {
        var firstMatch: AccessibilityNodeInfo? = null
        try {
            nodes.forEachIndexed { index, node ->
                if (index >= MAX_NODES || hasTimedOut(startedAt)) {
                    return CandidateSearchResult.TraversalLimit
                }
                if (node.packageName?.toString() != expectedPackage) return@forEachIndexed
                if (!selectorMatcher.matches(node, selector)) return@forEachIndexed
                if (firstMatch != null) return CandidateSearchResult.Ambiguous
                firstMatch = node
            }
            return firstMatch?.let { CandidateSearchResult.Value(onUnique(it)) }
                ?: CandidateSearchResult.None
        } finally {
            nodes.forEach { runCatching { it.recycle() } }
        }
    }

    @Suppress("DEPRECATION")
    private fun <T> traverseFallback(
        expectedPackage: String,
        root: AccessibilityNodeInfo,
        selector: NodeSelector,
        startedAt: Long,
        onUnique: (AccessibilityNodeInfo) -> T,
    ): CandidateSearchResult<T> {
        val queue = ArrayDeque<NodeAtDepth>().apply {
            add(NodeAtDepth(AccessibilityNodeInfo.obtain(root), 0))
        }
        var firstMatch: AccessibilityNodeInfo? = null
        var visited = 0
        try {
            while (queue.isNotEmpty()) {
                val item = queue.removeFirst()
                val node = item.node
                visited++
                if (visited > MAX_NODES || item.depth > MAX_DEPTH || hasTimedOut(startedAt)) {
                    node.recycle()
                    return CandidateSearchResult.TraversalLimit
                }
                if (node.packageName?.toString() == expectedPackage &&
                    selectorMatcher.matches(node, selector)
                ) {
                    if (firstMatch != null) {
                        node.recycle()
                        return CandidateSearchResult.Ambiguous
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
            return firstMatch?.let { CandidateSearchResult.Value(onUnique(it)) }
                ?: CandidateSearchResult.None
        } finally {
            firstMatch?.recycle()
            while (queue.isNotEmpty()) queue.removeFirst().node.recycle()
        }
    }

    private fun hasTimedOut(startedAt: Long): Boolean =
        AndroidMonotonicClock.elapsedRealtimeMs() - startedAt > MAX_TRAVERSAL_MS

    private data class NodeAtDepth(val node: AccessibilityNodeInfo, val depth: Int)

    private companion object {
        const val MAX_NODES = 500
        const val MAX_DEPTH = 40
        const val MAX_TRAVERSAL_MS = 100L
    }
}

internal sealed interface CandidateSearchResult<out T> {
    data object None : CandidateSearchResult<Nothing>
    data object Ambiguous : CandidateSearchResult<Nothing>
    data object PageChanged : CandidateSearchResult<Nothing>
    data object TraversalLimit : CandidateSearchResult<Nothing>
    data class Value<T>(val value: T) : CandidateSearchResult<T>
}
