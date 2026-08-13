package com.zhenghuaizhi.flashskip.platform.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo
import com.zhenghuaizhi.flashskip.domain.model.CapturedNode
import com.zhenghuaizhi.flashskip.domain.model.CapturedSelector
import com.zhenghuaizhi.flashskip.domain.model.ClickRecoveryDiagnostics
import com.zhenghuaizhi.flashskip.domain.model.ClickRecoveryOutcome
import com.zhenghuaizhi.flashskip.domain.model.TraversalLimitReason
import com.zhenghuaizhi.flashskip.domain.ports.PageIdentity
import com.zhenghuaizhi.flashskip.domain.rulecapture.ClickSemanticHint
import com.zhenghuaizhi.flashskip.domain.rulecapture.ClickSourceRecoveryMatcher
import com.zhenghuaizhi.flashskip.domain.rulecapture.RuleCandidateAnalyzer
import java.util.ArrayDeque

internal class AccessibilityCaptureProbe(
    private val service: AccessibilityService,
    private val targetPackages: () -> Set<String>,
    private val currentIdentity: () -> PageIdentity?,
    private val semanticSnapshotter: AccessibilitySemanticSnapshotter,
) {
    private val selectorMatcher = AccessibilityNodeSelectorMatcher(service)

    @Suppress("DEPRECATION")
    fun uniqueSelectors(
        expected: PageIdentity,
        selectors: List<CapturedSelector>,
    ): ProbeResult {
        if (selectors.isEmpty()) return ProbeResult.Success(emptySet())
        // 与自动化 Gateway 一致：白名单在身份、根节点和任何节点读取之前执行。
        if (expected.packageName !in targetPackages()) return ProbeResult.PageChanged
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

    @Suppress("DEPRECATION")
    fun recoverClickedSemanticChain(
        expected: PageIdentity,
        hint: ClickSemanticHint,
    ): RecoveryResult {
        val startedAt = AndroidMonotonicClock.elapsedRealtimeMs()
        // 恢复路径仍遵守与自动化 Gateway 相同的包和窗口边界，不能因 source 为空而放宽。
        if (expected.packageName !in targetPackages()) {
            return RecoveryResult.Failure(
                diagnostics(ClickRecoveryOutcome.OUTSIDE_TARGETS, startedAt),
            )
        }
        if (currentIdentity() != expected) {
            return RecoveryResult.Failure(
                diagnostics(ClickRecoveryOutcome.IDENTITY_CHANGED, startedAt),
            )
        }
        val root = service.rootInActiveWindow ?: return RecoveryResult.Failure(
            diagnostics(ClickRecoveryOutcome.NO_ROOT, startedAt),
        )
        if (root.packageName?.toString() != expected.packageName || root.windowId != expected.windowId) {
            root.recycle()
            return RecoveryResult.Failure(
                diagnostics(ClickRecoveryOutcome.ROOT_MISMATCH, startedAt),
            )
        }

        val queue = ArrayDeque<NodeAtDepth>().apply { add(NodeAtDepth(root, 0)) }
        var recovered: List<CapturedNode>? = null
        var ambiguous = false
        var visited = 0
        var maxDepth = 0
        var semanticNodes = 0
        var matchedNodes = 0
        var knownDismissNodes = 0
        var selectorCandidateNodes = 0
        try {
            while (queue.isNotEmpty() && !ambiguous) {
                val item = queue.removeFirst()
                val node = item.node
                visited++
                maxDepth = maxOf(maxDepth, item.depth)
                val limitReason = when {
                    visited > MAX_NODES -> TraversalLimitReason.NODE_COUNT
                    item.depth > MAX_DEPTH -> TraversalLimitReason.DEPTH
                    AndroidMonotonicClock.elapsedRealtimeMs() - startedAt > MAX_TRAVERSAL_MS ->
                        TraversalLimitReason.TIME
                    else -> null
                }
                if (limitReason != null) {
                    node.recycle()
                    return RecoveryResult.Failure(
                        diagnostics(
                            outcome = ClickRecoveryOutcome.TRAVERSAL_LIMIT,
                            startedAt = startedAt,
                            visitedNodeCount = visited,
                            maxDepth = maxDepth,
                            semanticNodeCount = semanticNodes,
                            matchedNodeCount = matchedNodes,
                            knownDismissNodeCount = knownDismissNodes,
                            selectorCandidateNodeCount = selectorCandidateNodes,
                            traversalLimitReason = limitReason,
                        ),
                    )
                }
                val snapshot = semanticSnapshotter.snapshot(node, expected.packageName)
                if (snapshot != null &&
                    snapshot.visibleToUser &&
                    (snapshot.resourceId != null ||
                        snapshot.text != null ||
                        snapshot.contentDescription != null)
                ) {
                    semanticNodes++
                }
                if (snapshot != null && ClickSourceRecoveryMatcher.matches(
                        snapshot,
                        ClickSemanticHint(),
                    )
                ) {
                    knownDismissNodes++
                }
                if (snapshot != null && ClickSourceRecoveryMatcher.matches(snapshot, hint)) {
                    matchedNodes++
                    val chain = semanticSnapshotter.copySemanticChain(
                        AccessibilityNodeInfo.obtain(node),
                        expected.packageName,
                    )
                    if (RuleCandidateAnalyzer.selectorCandidates(chain).isNotEmpty()) {
                        selectorCandidateNodes++
                        if (recovered == null) recovered = chain else ambiguous = true
                    }
                }
                if (!ambiguous) {
                    repeat(node.childCount) { index ->
                        node.getChild(index)?.let { child ->
                            queue.add(NodeAtDepth(child, item.depth + 1))
                        }
                    }
                }
                node.recycle()
            }
            val outcome = when {
                ambiguous -> ClickRecoveryOutcome.AMBIGUOUS
                recovered != null -> ClickRecoveryOutcome.SUCCESS
                else -> ClickRecoveryOutcome.NO_MATCH
            }
            val resultDiagnostics = diagnostics(
                outcome = outcome,
                startedAt = startedAt,
                visitedNodeCount = visited,
                maxDepth = maxDepth,
                semanticNodeCount = semanticNodes,
                matchedNodeCount = matchedNodes,
                knownDismissNodeCount = knownDismissNodes,
                selectorCandidateNodeCount = selectorCandidateNodes,
            )
            return if (recovered != null && !ambiguous) {
                RecoveryResult.Success(requireNotNull(recovered), resultDiagnostics)
            } else {
                RecoveryResult.Failure(resultDiagnostics)
            }
        } finally {
            while (queue.isNotEmpty()) queue.removeFirst().node.recycle()
        }
    }

    private fun diagnostics(
        outcome: ClickRecoveryOutcome,
        startedAt: Long,
        visitedNodeCount: Int = 0,
        maxDepth: Int = 0,
        semanticNodeCount: Int = 0,
        matchedNodeCount: Int = 0,
        knownDismissNodeCount: Int = 0,
        selectorCandidateNodeCount: Int = 0,
        traversalLimitReason: TraversalLimitReason? = null,
    ) = ClickRecoveryDiagnostics(
        outcome = outcome,
        visitedNodeCount = visitedNodeCount,
        maxDepth = maxDepth,
        elapsedMs = (AndroidMonotonicClock.elapsedRealtimeMs() - startedAt).coerceAtLeast(0),
        semanticNodeCount = semanticNodeCount,
        matchedNodeCount = matchedNodeCount,
        knownDismissNodeCount = knownDismissNodeCount,
        selectorCandidateNodeCount = selectorCandidateNodeCount,
        traversalLimitReason = traversalLimitReason,
    )

    private data class NodeAtDepth(val node: AccessibilityNodeInfo, val depth: Int)

    sealed interface ProbeResult {
        data class Success(val selectors: Set<CapturedSelector>) : ProbeResult
        data object NoRoot : ProbeResult
        data object PageChanged : ProbeResult
        data object TraversalLimit : ProbeResult
    }

    sealed interface RecoveryResult {
        val diagnostics: ClickRecoveryDiagnostics

        data class Success(
            val nodes: List<CapturedNode>,
            override val diagnostics: ClickRecoveryDiagnostics,
        ) : RecoveryResult

        data class Failure(
            override val diagnostics: ClickRecoveryDiagnostics,
        ) : RecoveryResult
    }

    private companion object {
        const val MAX_NODES = 500
        const val MAX_DEPTH = 40
        const val MAX_TRAVERSAL_MS = 100L
    }
}
