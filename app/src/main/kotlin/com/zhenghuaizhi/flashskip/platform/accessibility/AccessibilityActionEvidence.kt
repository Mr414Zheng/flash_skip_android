package com.zhenghuaizhi.flashskip.platform.accessibility

import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log
import com.zhenghuaizhi.flashskip.platform.diagnostics.ProcessDiagnosticIdentifiers
import com.zhenghuaizhi.flashskip.BuildConfig
import com.zhenghuaizhi.flashskip.domain.model.ActionReason
import com.zhenghuaizhi.flashskip.domain.model.NodeSelector
import com.zhenghuaizhi.flashskip.domain.model.TextCondition
import com.zhenghuaizhi.flashskip.domain.ports.AbsenceProof
import com.zhenghuaizhi.flashskip.domain.ports.ActionTargetEvidence
import com.zhenghuaizhi.flashskip.domain.ports.MonotonicClock
import com.zhenghuaizhi.flashskip.domain.ports.PageIdentity
import com.zhenghuaizhi.flashskip.domain.ports.TargetObservation
import java.util.ArrayDeque

/** 只在单次调用内持有节点；动作后观察使用有限语义摘要和原局部区域。 */
@Suppress("DEPRECATION")
internal class AccessibilityActionEvidence(
    private val matcher: AccessibilityNodeSelectorMatcher,
    private val clock: MonotonicClock,
) {
    fun capture(node: AccessibilityNodeInfo, identity: PageIdentity, selector: NodeSelector): ActionTargetEvidence {
        val base = ActionTargetEvidence(identity, selector, targetSignature(node, selector), structuralSignature(node))
        val containerId = selector.ancestorResourceId ?: selector.resourceId ?: return base
        var container = AccessibilityNodeInfo.obtain(node)
        try {
            repeat(if (selector.ancestorResourceId != null) selector.ancestorDistance ?: 0 else 0) {
                val parent = container.parent ?: return base
                container.recycle()
                container = parent
            }
            if (!fresh(container, identity) || container.viewIdResourceName != containerId) return base
            val local = read(container, base, LOCAL_DEPTH, "CAPTURE_CONTAINER")
            var result = base.copy(
                containerResourceId = containerId,
                containerSignature = structuralSignature(container),
                containerContext = local.takeIf { it.complete }?.signature,
            )
            var parent: AccessibilityNodeInfo? = container.parent
            try {
                for (distance in 1..PARENT_DEPTH) {
                    val current = parent ?: break
                    if (!fresh(current, identity)) break
                    val resourceId = current.viewIdResourceName
                    if (resourceId != null && resourceId != containerId) {
                        val region = read(current, result, distance, "CAPTURE_PARENT")
                        if (region.complete && region.containsContainer) result = result.copy(
                            parentResourceId = resourceId,
                            parentSignature = structuralSignature(current),
                            parentContext = region.signature,
                            parentToContainerDepth = distance,
                        )
                        break
                    }
                    val next = current.parent
                    current.recycle()
                    parent = next
                }
            } finally {
                parent?.recycle()
            }
            return result
        } finally {
            container.recycle()
        }
    }

    fun observe(root: AccessibilityNodeInfo, evidence: ActionTargetEvidence, fallback: Boolean): TargetObservation {
        val container = evidence.containerResourceId?.let { uniqueFreshById(root, it, evidence.identity) }
        if (container != null) {
            try {
                if (structuralSignature(container) == evidence.containerSignature) {
                    val local = read(container, evidence, LOCAL_DEPTH, "LOCAL_CONTAINER")
                    if (local.present) return TargetObservation.Present(local.unique)
                    if (!container.isVisibleToUser || local.potentialTarget ||
                        local.complete && local.signature == evidence.containerContext
                    ) return TargetObservation.Insufficient(ActionReason.CLICK_UNCONFIRMED)
                    if (local.complete && !local.potentialTarget && evidence.containerContext != null &&
                        local.signature != evidence.containerContext
                    ) return TargetObservation.Absent(AbsenceProof.LOCAL_TARGET_REMOVED, local.signature)
                }
            } finally {
                container.recycle()
            }
        }

        val parent = evidence.parentResourceId?.let { uniqueFreshById(root, it, evidence.identity) }
        if (parent != null) {
            try {
                if (parent.isVisibleToUser && structuralSignature(parent) == evidence.parentSignature) {
                    val region = read(parent, evidence, evidence.parentToContainerDepth ?: PARENT_DEPTH, "LOCAL_PARENT")
                    if (region.present) return TargetObservation.Present(region.unique)
                    if (region.complete && region.containsContainer && container == null) {
                        return TargetObservation.Insufficient(ActionReason.NODE_UNAVAILABLE)
                    }
                    // 空索引本身不参与确认：必须完整读取原容器所在的局部区域，并证明其结构改变。
                    if (region.complete && !region.containsContainer && !region.potentialTarget &&
                        evidence.parentContext != null && region.signature != evidence.parentContext
                    ) return TargetObservation.Absent(AbsenceProof.LOCAL_CONTAINER_REMOVED, region.signature)
                }
            } finally {
                parent.recycle()
            }
        }
        if (!fallback) return TargetObservation.Insufficient(ActionReason.NODE_UNAVAILABLE)
        // 独立读取原目标及广告区域；唯一查找器的 None 会漏掉隐藏/改文案的原控件，不能作为关闭证据。
        val page = read(root, evidence, PAGE_DEPTH, "PAGE_FALLBACK", fullPage = true)
        if (page.present) return TargetObservation.Present(page.unique)
        if (!page.complete) return TargetObservation.Insufficient(page.failure ?: ActionReason.NODE_UNAVAILABLE)
        if (page.potentialTarget || page.containsContainer ||
            evidence.containerContext == null && evidence.parentContext == null
        ) return TargetObservation.Insufficient(ActionReason.CLICK_UNCONFIRMED)
        return TargetObservation.Absent(AbsenceProof.COMPLETE_PAGE, page.signature)
    }

    fun matchesOriginal(node: AccessibilityNodeInfo, evidence: ActionTargetEvidence): Boolean =
        targetSignature(node, evidence.selector) == evidence.targetSignature

    private fun uniqueFreshById(
        root: AccessibilityNodeInfo,
        id: String,
        identity: PageIdentity,
    ): AccessibilityNodeInfo? {
        val nodes = runCatching { root.findAccessibilityNodeInfosByViewId(id) }.getOrNull() ?: return null
        try {
            if (nodes.size != 1) return null
            val node = nodes.single()
            if (!fresh(node, identity) || node.viewIdResourceName != id) return null
            return AccessibilityNodeInfo.obtain(node)
        } finally {
            nodes.forEach { runCatching { it.recycle() } }
        }
    }

    private fun read(
        root: AccessibilityNodeInfo,
        evidence: ActionTargetEvidence,
        depthLimit: Int,
        phase: String,
        fullPage: Boolean = false,
    ): LocalRead {
        val startedAt = clock.elapsedRealtimeMs()
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>().apply {
            add(AccessibilityNodeInfo.obtain(root) to 0)
        }
        var signature = 17L
        var visited = 0
        var present = 0
        var allMatches = 0
        var potential = false
        var containsContainer = false
        fun finish(complete: Boolean, limit: String? = null): LocalRead {
            if (BuildConfig.DEBUG) runCatching {
                val identifiers = ProcessDiagnosticIdentifiers.Process
                Log.d(AutomationTraceLog.TAG, "source=platform process=${identifiers.scope} stage=ACTION_EVIDENCE_READ phase=$phase " +
                    "targetToken=${identifiers.token("target", evidence.identity.packageName.hashCode())} " +
                    "windowId=${evidence.identity.windowId} selectorToken=${identifiers.token("selector", evidence.selector.hashCode())} " +
                    "complete=$complete visitedNodes=$visited durationMs=${clock.elapsedRealtimeMs() - startedAt} " +
                    "originalMatches=$present allMatches=$allMatches potentialTarget=$potential containsContainer=$containsContainer" +
                    (limit?.let { " limit=$it" } ?: ""))
            }
            val failure = when (limit) {
                "TIME", "NODE_COUNT", "DEPTH" -> ActionReason.TRAVERSAL_LIMIT
                null -> null
                else -> ActionReason.NODE_UNAVAILABLE
            }
            return LocalRead(complete, signature, present, allMatches, potential, containsContainer, failure)
        }
        val maxNodes = if (fullPage) PAGE_NODES else LOCAL_NODES
        val maxMs = if (fullPage) PAGE_MS else LOCAL_MS
        try {
            while (queue.isNotEmpty()) {
                val (node, depth) = queue.removeFirst()
                try {
                    if (++visited > maxNodes) return finish(false, "NODE_COUNT")
                    if (clock.elapsedRealtimeMs() - startedAt > maxMs) return finish(false, "TIME")
                    if (!fresh(node, evidence.identity)) return finish(false, "UNAVAILABLE")
                    val original = targetSignature(node, evidence.selector) == evidence.targetSignature
                    val match = matcher.check(node, evidence.selector, refreshAncestors = true)
                    if (match == CandidateMatchResult.UNAVAILABLE) return finish(false, "UNAVAILABLE")
                    potential = potential || original || match == CandidateMatchResult.MATCH ||
                        ((!fullPage || evidence.selector.resourceId != null) &&
                            structuralSignature(node) == evidence.targetStructuralSignature)
                    if (match == CandidateMatchResult.MATCH) {
                        allMatches++
                        if (original) present++
                    }
                    containsContainer = containsContainer || node.viewIdResourceName == evidence.containerResourceId
                    // 排除关闭控件本身：仅按钮隐藏或文案变化，不足以证明广告内容已经被移除。
                    if (!original) signature = signature * 31 + structuralSignature(node) * 31 +
                        if (node.isVisibleToUser) 1 else 0
                    if (depth < depthLimit) {
                        repeat(node.childCount) { index ->
                            // getChild 自身可能跨进程耗时；节点与时间预算也必须约束待访问队列的构建。
                            if (visited + queue.size >= maxNodes) return finish(false, "NODE_COUNT")
                            if (clock.elapsedRealtimeMs() - startedAt > maxMs) return finish(false, "TIME")
                            val child = node.getChild(index)
                                ?: return finish(false, "UNAVAILABLE")
                            queue.add(child to depth + 1)
                        }
                    } else if ((fullPage || depthLimit == LOCAL_DEPTH) && node.childCount > 0) {
                        return finish(false, "DEPTH")
                    }
                } finally {
                    node.recycle()
                }
            }
            return if (clock.elapsedRealtimeMs() - startedAt <= maxMs) finish(true) else finish(false, "TIME")
        } finally {
            while (queue.isNotEmpty()) queue.removeFirst().first.recycle()
        }
    }

    private fun fresh(node: AccessibilityNodeInfo, identity: PageIdentity): Boolean =
        runCatching { node.refresh() }.getOrDefault(false) &&
            node.packageName?.toString() == identity.packageName && node.windowId == identity.windowId

    private fun structuralSignature(node: AccessibilityNodeInfo): Long =
        ((node.packageName?.hashCode() ?: 0).toLong() * 31 + (node.viewIdResourceName?.hashCode() ?: 0)) * 31 +
            (node.className?.hashCode() ?: 0)

    private fun targetSignature(node: AccessibilityNodeInfo, selector: NodeSelector): Long {
        val text = when (val condition = selector.text) {
            is TextCondition.SkipCountdown -> condition.prefix.takeIf { condition.matches(node.text?.toString()) }
                ?: node.text?.toString()
            TextCondition.NumericCountdown -> "#countdown".takeIf { TextCondition.NumericCountdown.matches(node.text?.toString()) }
                ?: node.text?.toString()
            else -> node.text?.toString()
        }
        return structuralSignature(node) * 31 * 31 + (text?.hashCode() ?: 0) * 31 +
            (node.contentDescription?.hashCode() ?: 0)
    }

    private data class LocalRead(
        val complete: Boolean,
        val signature: Long,
        val matches: Int,
        val allMatches: Int,
        val potentialTarget: Boolean,
        val containsContainer: Boolean,
        val failure: ActionReason?,
    ) {
        val present: Boolean get() = matches > 0
        val unique: Boolean get() = complete && matches == 1 && allMatches == 1
    }

    private companion object {
        const val LOCAL_NODES = 64
        const val LOCAL_DEPTH = 8
        const val PARENT_DEPTH = 4
        const val LOCAL_MS = 60L
        const val PAGE_NODES = 500
        const val PAGE_DEPTH = 40
        const val PAGE_MS = 100L
    }
}
