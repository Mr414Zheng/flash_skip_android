package com.zhenghuaizhi.flashskip.platform.accessibility

import android.view.accessibility.AccessibilityNodeInfo
import com.zhenghuaizhi.flashskip.domain.model.NodeSelector
import com.zhenghuaizhi.flashskip.domain.model.TextCondition
import com.zhenghuaizhi.flashskip.domain.ports.MonotonicClock
import java.util.ArrayDeque

/**
 * 在已完成包名、窗口与当前页面校验的根节点内查找唯一候选。
 *
 * 平台索引只负责缩小候选集合，最终仍由完整 selector（含祖先约束）复核；索引无法给出
 * 确定结论时回退到原有有界遍历，避免为了提速放宽匹配条件。
 */
internal class AccessibilityCandidateFinder(
    private val selectorMatcher: AccessibilityNodeSelectorMatcher,
    private val monotonicClock: MonotonicClock = AndroidMonotonicClock,
) {
    @Suppress("DEPRECATION")
    fun <T> findUnique(
        expectedPackage: String,
        root: AccessibilityNodeInfo,
        selector: NodeSelector,
        onUnique: (AccessibilityNodeInfo) -> T,
        trace: ((CandidateCheck) -> Unit)? = null,
        diagnostics: SearchDiagnostics? = null,
    ): CandidateSearchResult<T> {
        val startedAt = monotonicClock.elapsedRealtimeMs()
        diagnostics?.phase = SearchPhase.INDEX
        try {
            val indexedNodes = indexedCandidates(root, selector)
            diagnostics?.apply {
                this.indexedNodes = indexedNodes?.size
                indexLookupMs = monotonicClock.elapsedRealtimeMs() - startedAt
            }
            if (indexedNodes != null) {
                when (
                    val indexedResult = evaluateCandidates(
                        expectedPackage = expectedPackage,
                        nodes = indexedNodes,
                        selector = selector,
                        startedAt = startedAt,
                        onUnique = onUnique,
                        trace = trace,
                        diagnostics = diagnostics,
                    )
                ) {
                    CandidateSearchResult.None -> Unit
                    else -> return indexedResult
                }
            }
            diagnostics?.phase = SearchPhase.FALLBACK
            return traverseFallback(expectedPackage, root, selector, startedAt, onUnique, trace, diagnostics)
        } finally {
            diagnostics?.searchMs = monotonicClock.elapsedRealtimeMs() - startedAt
        }
    }

    private fun indexedCandidates(
        root: AccessibilityNodeInfo,
        selector: NodeSelector,
    ): List<AccessibilityNodeInfo>? = when {
        selector.resourceId != null -> runCatching {
            root.findAccessibilityNodeInfosByViewId(selector.resourceId)
        }.getOrNull()

        selector.text?.queryValue() != null -> runCatching {
            root.findAccessibilityNodeInfosByText(requireNotNull(selector.text.queryValue()))
        }.getOrNull()

        else -> null
    }

    private fun TextCondition.queryValue(): String? = when (this) {
        is TextCondition.Exact -> value
        is TextCondition.Prefix -> value
        is TextCondition.SkipCountdown -> prefix
        TextCondition.NumericCountdown -> null
    }

    @Suppress("DEPRECATION")
    private fun <T> evaluateCandidates(
        expectedPackage: String,
        nodes: List<AccessibilityNodeInfo>,
        selector: NodeSelector,
        startedAt: Long,
        onUnique: (AccessibilityNodeInfo) -> T,
        trace: ((CandidateCheck) -> Unit)?,
        diagnostics: SearchDiagnostics?,
    ): CandidateSearchResult<T> {
        var firstMatch: AccessibilityNodeInfo? = null
        var unavailable = false
        try {
            nodes.forEachIndexed { index, node ->
                if (index >= MAX_NODES) return limited(SearchLimit.NODE_COUNT, diagnostics)
                if (hasTimedOut(startedAt)) return limited(SearchLimit.TIME, diagnostics)
                if (node.packageName?.toString() != expectedPackage) return@forEachIndexed
                when (checkCandidate(node, selector, expectedPackage, trace)) {
                    CandidateMatchResult.NO_MATCH -> return@forEachIndexed
                    CandidateMatchResult.UNAVAILABLE -> {
                        unavailable = true
                        return@forEachIndexed
                    }
                    CandidateMatchResult.MATCH -> Unit
                }
                if (firstMatch != null) return CandidateSearchResult.Ambiguous
                firstMatch = node
            }
            if (unavailable) return CandidateSearchResult.Unavailable
            if (hasTimedOut(startedAt)) return limited(SearchLimit.TIME, diagnostics)
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
        trace: ((CandidateCheck) -> Unit)?,
        diagnostics: SearchDiagnostics?,
    ): CandidateSearchResult<T> {
        val queue = ArrayDeque<NodeAtDepth>().apply {
            add(NodeAtDepth(AccessibilityNodeInfo.obtain(root), 0))
        }
        var firstMatch: AccessibilityNodeInfo? = null
        var visited = 0
        var unavailable = false
        try {
            while (queue.isNotEmpty()) {
                val item = queue.removeFirst()
                val node = item.node
                visited++
                diagnostics?.apply {
                    visitedNodes = visited
                    maxDepth = maxOf(maxDepth, item.depth)
                }
                val limit = when {
                    visited > MAX_NODES -> SearchLimit.NODE_COUNT
                    item.depth > MAX_DEPTH -> SearchLimit.DEPTH
                    hasTimedOut(startedAt) -> SearchLimit.TIME
                    else -> null
                }
                if (limit != null) {
                    node.recycle()
                    return limited(limit, diagnostics)
                }
                val match = if (node.packageName?.toString() == expectedPackage) {
                    checkCandidate(node, selector, expectedPackage, trace)
                } else {
                    CandidateMatchResult.NO_MATCH
                }
                if (match == CandidateMatchResult.UNAVAILABLE) unavailable = true
                if (match == CandidateMatchResult.MATCH) {
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
            if (unavailable) return CandidateSearchResult.Unavailable
            if (hasTimedOut(startedAt)) return limited(SearchLimit.TIME, diagnostics)
            return firstMatch?.let { CandidateSearchResult.Value(onUnique(it)) }
                ?: CandidateSearchResult.None
        } finally {
            firstMatch?.recycle()
            while (queue.isNotEmpty()) queue.removeFirst().node.recycle()
        }
    }

    private fun hasTimedOut(startedAt: Long): Boolean =
        monotonicClock.elapsedRealtimeMs() - startedAt > MAX_TRAVERSAL_MS

    private fun limited(limit: SearchLimit, diagnostics: SearchDiagnostics?): CandidateSearchResult<Nothing> {
        diagnostics?.limit = limit
        return CandidateSearchResult.TraversalLimit
    }

    private fun checkCandidate(
        node: AccessibilityNodeInfo,
        selector: NodeSelector,
        expectedPackage: String,
        trace: ((CandidateCheck) -> Unit)?,
    ): CandidateMatchResult {
        val cached = selectorMatcher.check(node, selector)
        val prefixLike = (selector.text as? TextCondition.SkipCountdown)?.let {
            node.text?.startsWith(it.prefix, ignoreCase = true) == true
        } ?: false
        val refreshed = if (cached == CandidateMatchResult.MATCH) {
            runCatching { node.refresh() }.getOrDefault(false)
        } else {
            null
        }
        // 刷新可能跨过页面变化，先重核包边界，再读取最新节点特征或执行动作。
        if (refreshed == true && node.packageName?.toString() != expectedPackage) {
            return CandidateMatchResult.UNAVAILABLE
        }
        val result = when {
            refreshed == false -> CandidateMatchResult.UNAVAILABLE
            refreshed == true -> selectorMatcher.check(node, selector, refreshAncestors = true)
            else -> cached
        }
        if (trace != null && (cached != CandidateMatchResult.NO_MATCH || prefixLike)) {
            trace(CandidateCheck(
                cachedMatch = cached == CandidateMatchResult.MATCH,
                refreshed = refreshed,
                result = result,
                prefixLike = prefixLike,
                resourceIdHash = node.viewIdResourceName?.hashCode(),
                classHash = node.className?.hashCode(),
                visible = node.isVisibleToUser,
                clickable = node.isClickable,
            ))
        }
        return result
    }

    private data class NodeAtDepth(val node: AccessibilityNodeInfo, val depth: Int)

    private companion object {
        const val MAX_NODES = 500
        const val MAX_DEPTH = 40
        const val MAX_TRAVERSAL_MS = 100L
    }
}

internal sealed interface CandidateSearchResult<out T> {
    data object Unavailable : CandidateSearchResult<Nothing>
    data object None : CandidateSearchResult<Nothing>
    data object Ambiguous : CandidateSearchResult<Nothing>
    data object PageChanged : CandidateSearchResult<Nothing>
    data object TraversalLimit : CandidateSearchResult<Nothing>
    data class Value<T>(val value: T) : CandidateSearchResult<T>
}
