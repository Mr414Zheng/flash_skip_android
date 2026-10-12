package com.zhenghuaizhi.flashskip.platform.accessibility

import android.view.accessibility.AccessibilityNodeInfo
import com.zhenghuaizhi.flashskip.domain.automation.SelectorMatcher
import com.zhenghuaizhi.flashskip.domain.model.CandidateRecheckDiagnostics
import com.zhenghuaizhi.flashskip.domain.model.CapturedNode
import com.zhenghuaizhi.flashskip.domain.model.CapturedSelector
import com.zhenghuaizhi.flashskip.domain.model.NodeSelector
import com.zhenghuaizhi.flashskip.domain.model.NodeSnapshot
import com.zhenghuaizhi.flashskip.domain.ports.PageIdentity
import com.zhenghuaizhi.flashskip.domain.rulecapture.CaptureRuleSafetyPolicy
import com.zhenghuaizhi.flashskip.domain.rulecapture.RuleCandidateAnalyzer

internal enum class SelectorPresence { UNIQUE_PRESENT, AMBIGUOUS_PRESENT, VERIFIED_ABSENT, UNKNOWN }
internal enum class SelectorEvidenceScope { NONE, FULL_WINDOW, ANCHORED_SUBTREE }
internal enum class SelectorEvidenceReason {
    UNIQUE_RESOURCE_ID, COMPLETE_WINDOW, COMPLETE_ANCHORED_SUBTREE, EMPTY_QUERY,
    AMBIGUOUS_QUERY, UNAVAILABLE, IDENTITY_CHANGED, BUDGET_EXHAUSTED, CHANGED_NODE,
    NO_STABLE_ANCHOR, SELECTOR_LIMIT,
}
internal data class SelectorEvidence(
    val presence: SelectorPresence,
    val scope: SelectorEvidenceScope = SelectorEvidenceScope.NONE,
    val reason: SelectorEvidenceReason = SelectorEvidenceReason.UNAVAILABLE,
    val nodes: List<CapturedNode> = emptyList(),
)
internal data class SelectorCheckBatch(
    val evidence: Map<CapturedSelector, SelectorEvidence>,
    val diagnostics: CandidateRecheckDiagnostics,
)

/** 资源 ID 索引仅提供阳性证据；空结果也可能来自 Binder 失败，不能证明消失。 */
internal class AccessibilityCandidateRecheck(
    private val currentIdentity: () -> PageIdentity?,
    private val snapshotter: AccessibilitySemanticSnapshotter,
    private val clock: () -> Long,
    private val refreshNode: (AccessibilityNodeInfo) -> Boolean,
    private val queryById: (AccessibilityNodeInfo, String) -> List<AccessibilityNodeInfo>,
) {
    @Suppress("DEPRECATION")
    fun check(
        expected: PageIdentity,
        root: AccessibilityNodeInfo,
        selectors: List<CapturedSelector>,
        budget: CaptureRoundBudget,
    ): SelectorCheckBatch {
        val started = clock()
        val evidence = linkedMapOf<CapturedSelector, SelectorEvidence>()
        var queries = 0
        var anchorQueries = 0
        var emptyQueries = 0
        var anchorScans = 0
        var completedAnchorScans = 0
        var unreadable = 0
        fun unknown(reason: SelectorEvidenceReason) = SelectorEvidence(SelectorPresence.UNKNOWN, reason = reason)
        fun valid(node: AccessibilityNodeInfo): Boolean = node.packageName?.toString() == expected.packageName && node.windowId == expected.windowId
        fun identityValid() = currentIdentity() == expected && runCatching { valid(root) }.getOrDefault(false)
        fun query(resourceId: String, anchor: Boolean): Query {
            if (budget.limitReason() != null) return Query(emptyList(), SelectorEvidenceReason.BUDGET_EXHAUSTED)
            if (!identityValid()) return Query(emptyList(), SelectorEvidenceReason.IDENTITY_CHANGED)
            queries++
            if (anchor) anchorQueries++
            val nodes = runCatching { queryById(root, resourceId) }.getOrNull()
                ?: return Query(emptyList(), SelectorEvidenceReason.UNAVAILABLE)
            if (nodes.isEmpty()) emptyQueries++
            if (budget.expired()) return Query(nodes, SelectorEvidenceReason.BUDGET_EXHAUSTED)
            if (nodes.size > MAX_QUERY_NODES) return Query(nodes, SelectorEvidenceReason.UNAVAILABLE)
            return Query(nodes, null)
        }
        fun fresh(node: AccessibilityNodeInfo): Boolean {
            if (budget.tryVisit() != null) return false
            val available = runCatching { refreshNode(node) && valid(node) }.getOrDefault(false)
            if (!available) unreadable++
            return available && !budget.expired() && identityValid()
        }
        fun match(node: AccessibilityNodeInfo, selector: CapturedSelector): List<CapturedNode>? {
            val chain = copyBudgetedChain(node, expected.packageName, snapshotter, budget, refreshNode)
                ?: return null
            if (chain.isEmpty()) return null
            val runtime = selector.toNodeSelector()
            if (!SelectorMatcher.matches(runtime, chain.first().toMatcherSnapshot(), chain.drop(1).map { it.toMatcherSnapshot() })) return emptyList()
            val actionDistance = selector.actionAncestorDistance ?: 0
            val action = chain.getOrNull(actionDistance) ?: return emptyList()
            if (!action.visibleToUser || !action.hasUsableBounds || !(action.clickable || action.supportsActionClick)) return emptyList()
            if (!CaptureRuleSafetyPolicy.accepts(chain) || RuleCandidateAnalyzer.selectorCandidates(chain).isEmpty()) return emptyList()
            return chain
        }
        fun scan(scopeRoot: AccessibilityNodeInfo, selector: CapturedSelector, scope: SelectorEvidenceScope): SelectorEvidence {
            var changedTarget = false
            var unavailable = false
            val matches = mutableListOf<List<CapturedNode>>()
            val traversal = CaptureCoverageCursor().traverse(expected, scopeRoot, budget) { node, _ ->
                if (!runCatching { refreshNode(node) }.getOrDefault(false) || !valid(node)) {
                    unavailable = true
                    unreadable++
                    return@traverse false
                }
                val snapshot = snapshotter.snapshot(node, expected.packageName)
                if (snapshot == null) { unavailable = true; return@traverse false }
                val targetIdPresent = selector.resourceId != null && snapshot.resourceId == selector.resourceId
                if (targetIdPresent || selector.resourceId == null && SelectorMatcher.matchesTarget(selector.toNodeSelector(), snapshot.toMatcherSnapshot())) {
                    val chain = match(node, selector)
                    if (chain == null) unavailable = true
                    else if (chain.isNotEmpty()) matches += chain
                    else if (targetIdPresent) changedTarget = true
                }
                !unavailable && matches.size < 2 && !budget.expired()
            }
            if (!identityValid()) return unknown(SelectorEvidenceReason.IDENTITY_CHANGED)
            if (budget.expired() || traversal.limitReason != null) return unknown(SelectorEvidenceReason.BUDGET_EXHAUSTED)
            if (unavailable || traversal.unreadable) return unknown(SelectorEvidenceReason.UNAVAILABLE)
            if (matches.size > 1) return SelectorEvidence(SelectorPresence.AMBIGUOUS_PRESENT, scope, SelectorEvidenceReason.AMBIGUOUS_QUERY)
            if (!traversal.complete) return unknown(SelectorEvidenceReason.UNAVAILABLE)
            if (scope == SelectorEvidenceScope.ANCHORED_SUBTREE) completedAnchorScans++
            if (matches.size == 1) return SelectorEvidence(SelectorPresence.UNIQUE_PRESENT, scope, if (scope == SelectorEvidenceScope.FULL_WINDOW) SelectorEvidenceReason.COMPLETE_WINDOW else SelectorEvidenceReason.COMPLETE_ANCHORED_SUBTREE, matches.single())
            if (changedTarget) return unknown(SelectorEvidenceReason.CHANGED_NODE)
            return SelectorEvidence(SelectorPresence.VERIFIED_ABSENT, scope, if (scope == SelectorEvidenceScope.FULL_WINDOW) SelectorEvidenceReason.COMPLETE_WINDOW else SelectorEvidenceReason.COMPLETE_ANCHORED_SUBTREE)
        }
        fun checkOne(selector: CapturedSelector): SelectorEvidence {
            if (!selector.toNodeSelector().hasConditions) return unknown(SelectorEvidenceReason.NO_STABLE_ANCHOR)
            if (budget.limitReason() != null) return unknown(SelectorEvidenceReason.BUDGET_EXHAUSTED)
            if (!identityValid()) return unknown(SelectorEvidenceReason.IDENTITY_CHANGED)
            if (budget.tryVisit() != null) return unknown(SelectorEvidenceReason.BUDGET_EXHAUSTED)
            if (!runCatching { refreshNode(root) && valid(root) }.getOrDefault(false)) return unknown(SelectorEvidenceReason.UNAVAILABLE)
            if (budget.expired()) return unknown(SelectorEvidenceReason.BUDGET_EXHAUSTED)
            var targetQueryWasEmpty = false
            selector.resourceId?.let { resourceId ->
                val query = query(resourceId, false)
                try {
                    query.failure?.let { return unknown(it) }
                    targetQueryWasEmpty = query.nodes.isEmpty()
                    if (query.nodes.isNotEmpty()) {
                        val matches = mutableListOf<List<CapturedNode>>()
                        var changed = false
                        for (node in query.nodes) {
                            if (!fresh(node) || node.viewIdResourceName != resourceId) return unknown(SelectorEvidenceReason.UNAVAILABLE)
                            val chain = match(node, selector) ?: return unknown(SelectorEvidenceReason.UNAVAILABLE)
                            if (chain.isNotEmpty()) matches += chain else changed = true
                        }
                        if (budget.expired()) return unknown(SelectorEvidenceReason.BUDGET_EXHAUSTED)
                        if (!identityValid()) return unknown(SelectorEvidenceReason.IDENTITY_CHANGED)
                        if (matches.size > 1) return SelectorEvidence(SelectorPresence.AMBIGUOUS_PRESENT, reason = SelectorEvidenceReason.AMBIGUOUS_QUERY)
                        if (matches.size == 1 && !changed) return SelectorEvidence(SelectorPresence.UNIQUE_PRESENT, reason = SelectorEvidenceReason.UNIQUE_RESOURCE_ID, nodes = matches.single())
                        return unknown(SelectorEvidenceReason.CHANGED_NODE)
                    }
                } finally { query.nodes.forEach { it.recycle() } }
            }
            val anchorId = selector.ancestorResourceId
            val distance = selector.ancestorDistance
            if (anchorId != null && anchorId.isNotBlank() && !anchorId.startsWith("android:") && distance != null && distance in 1..NodeSelector.MAX_ANCESTOR_DISTANCE) {
                val query = query(anchorId, true)
                try {
                    query.failure?.let { return unknown(it) }
                    if (query.nodes.isEmpty()) return unknown(SelectorEvidenceReason.EMPTY_QUERY)
                    // 所有返回节点都必须可读；重复 anchor 无法限定唯一的验证范围。
                    for (node in query.nodes) {
                        if (!fresh(node) || node.viewIdResourceName != anchorId) return unknown(SelectorEvidenceReason.UNAVAILABLE)
                        val snapshot = snapshotter.snapshot(node, expected.packageName) ?: return unknown(SelectorEvidenceReason.UNAVAILABLE)
                        if (!snapshot.visibleToUser || !snapshot.hasUsableBounds) return unknown(SelectorEvidenceReason.CHANGED_NODE)
                    }
                    if (query.nodes.size != 1) return unknown(SelectorEvidenceReason.AMBIGUOUS_QUERY)
                    anchorScans++
                    return scan(query.nodes.single(), selector, SelectorEvidenceScope.ANCHORED_SUBTREE)
                } finally { query.nodes.forEach { it.recycle() } }
            }
            // 没有稳定 anchor 时只接受当轮真正完整的窗口遍历；空索引自身始终未知。
            val result = scan(root, selector, SelectorEvidenceScope.FULL_WINDOW)
            return if (targetQueryWasEmpty && result.presence == SelectorPresence.UNKNOWN) unknown(SelectorEvidenceReason.EMPTY_QUERY) else result
        }
        selectors.distinct().forEachIndexed { index, selector ->
            evidence[selector] = if (index >= MAX_SELECTORS) unknown(SelectorEvidenceReason.SELECTOR_LIMIT) else runCatching { checkOne(selector) }.getOrElse { unreadable++; unknown(SelectorEvidenceReason.UNAVAILABLE) }
        }
        if (budget.expired() || !identityValid()) {
            val reason = if (budget.expired()) SelectorEvidenceReason.BUDGET_EXHAUSTED else SelectorEvidenceReason.IDENTITY_CHANGED
            evidence.keys.toList().forEach { evidence[it] = unknown(reason) }
        }
        return SelectorCheckBatch(evidence, CandidateRecheckDiagnostics(
            queryCount = queries, anchorQueryCount = anchorQueries,
            presentCount = evidence.values.count { it.presence == SelectorPresence.UNIQUE_PRESENT },
            absentCount = evidence.values.count { it.presence == SelectorPresence.VERIFIED_ABSENT },
            ambiguousCount = evidence.values.count { it.presence == SelectorPresence.AMBIGUOUS_PRESENT },
            unknownCount = evidence.values.count { it.presence == SelectorPresence.UNKNOWN },
            emptyQueryCount = emptyQueries, anchorScanCount = anchorScans,
            completedAnchorScanCount = completedAnchorScans, unreadableNodeCount = unreadable,
            budgetNodeReads = budget.nodeReadCount, elapsedMs = (clock() - started).coerceAtLeast(0),
            traversalLimitReason = budget.limitReason(),
        ))
    }

    private data class Query(val nodes: List<AccessibilityNodeInfo>, val failure: SelectorEvidenceReason?)
    private companion object { const val MAX_SELECTORS = 8; const val MAX_QUERY_NODES = 500 }
}

internal fun CapturedNode.toMatcherSnapshot() = NodeSnapshot(resourceId, text, contentDescription, visibleToUser, hasUsableBounds)

/** 自身已计入预算，父链读取继续消费同一预算，节点不离开当前回调。 */
@Suppress("DEPRECATION")
internal fun copyBudgetedChain(
    initial: AccessibilityNodeInfo,
    expectedPackage: String,
    snapshotter: AccessibilitySemanticSnapshotter,
    budget: CaptureRoundBudget,
    refreshNode: ((AccessibilityNodeInfo) -> Boolean)? = null,
    initialSnapshot: CapturedNode? = null,
): List<CapturedNode>? {
    val chain = mutableListOf<CapturedNode>()
    var current: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(initial)
    try {
        for (depth in 0 until 5) {
            val node = current ?: break
            if (budget.expired()) return null
            if (depth > 0 && refreshNode != null && !runCatching { refreshNode(node) }.getOrDefault(false)) return null
            if (node.packageName?.toString() != expectedPackage || node.windowId != initial.windowId) return null
            val snapshot = if (depth == 0 && initialSnapshot != null) initialSnapshot else snapshotter.snapshot(node, expectedPackage)
            if (snapshot == null) return null
            chain += snapshot
            if (depth == 4) break
            // 父节点查询也共享预算，不能在候选链复制时绕过节点上限。
            if (budget.tryVisit() != null) return null
            val parent = try { node.parent } catch (_: Exception) { return null }
            node.recycle()
            current = parent
        }
        return chain.takeUnless { budget.expired() }
    } finally { current?.recycle() }
}
