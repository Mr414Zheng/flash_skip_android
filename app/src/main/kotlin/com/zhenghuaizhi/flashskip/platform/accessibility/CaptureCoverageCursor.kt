package com.zhenghuaizhi.flashskip.platform.accessibility

import android.view.accessibility.AccessibilityNodeInfo
import com.zhenghuaizhi.flashskip.domain.model.CaptureScanUnavailableReason
import com.zhenghuaizhi.flashskip.domain.model.TraversalLimitReason
import com.zhenghuaizhi.flashskip.domain.ports.PageIdentity

/** 仅保留整数路径与分支进度；不同回调的局部扫描绝不拼接成完整窗口证据。 */
internal class CaptureCoverageCursor {
    private var identity: PageIdentity? = null
    private var checkpoint: List<Position> = emptyList()

    fun reset() {
        identity = null
        checkpoint = emptyList()
    }

    @Suppress("DEPRECATION")
    fun traverse(
        expected: PageIdentity,
        root: AccessibilityNodeInfo,
        budget: CaptureRoundBudget,
        continueCoverage: Boolean = false,
        onNode: (AccessibilityNodeInfo, Int) -> Boolean,
    ): Traversal {
        val frames = mutableListOf<Frame>()
        var resumed = continueCoverage && identity == expected && checkpoint.isNotEmpty()
        var reset = continueCoverage && identity != null && identity != expected
        var visited = 0
        var maxDepth = 0
        var unreadable = false
        var unavailableReason: CaptureScanUnavailableReason? = null
        var limit: TraversalLimitReason? = null
        var stopped = false
        var structureChanged = false
        var restoring = resumed
        val continuationDepth = if (resumed) checkpoint.lastIndex else 0
        fun matches(node: AccessibilityNodeInfo) =
            node.packageName?.toString() == expected.packageName && node.windowId == expected.windowId
        fun mount(node: AccessibilityNodeInfo, index: Int, position: Position? = null) {
            val childCount = try { node.childCount } catch (failure: Exception) { node.recycle(); throw failure }
            frames += Frame(node, index, childCount, position?.nextChildIndex ?: (childCount - 1), position?.processed ?: false)
        }
        try {
            budget.tryVisit()?.let { limit = it; return Traversal(0, 0, false, false, it, resumed, continuationDepth, 0, reset) }
            mount(AccessibilityNodeInfo.obtain(root), -1, checkpoint.firstOrNull().takeIf { resumed })
            if (resumed) {
                val saved = checkpoint
                if (frames[0].childCount != saved[0].childCount) {
                    resumed = false
                    unavailableReason = CaptureScanUnavailableReason.STRUCTURE_CHANGED
                }
                for (depth in 1 until saved.size) {
                    if (!resumed) break
                    val readLimit = budget.tryVisit()
                    if (readLimit != null) { limit = readLimit; break }
                    val parent = frames.last()
                    val index = saved[depth].childIndex
                    val child = if (index in 0 until parent.childCount) runCatching { parent.node.getChild(index) }.getOrNull() else null
                    if (child == null) {
                        unavailableReason = if (index in 0 until parent.childCount) CaptureScanUnavailableReason.CHILD_UNREADABLE
                        else CaptureScanUnavailableReason.STRUCTURE_CHANGED
                        resumed = false
                        break
                    }
                    val sameIdentity = runCatching { matches(child) }.getOrDefault(false)
                    val sameStructure = sameIdentity && runCatching { child.childCount == saved[depth].childCount }.getOrDefault(false)
                    if (!sameIdentity || !sameStructure) {
                        unavailableReason = if (!sameIdentity) CaptureScanUnavailableReason.IDENTITY_CHANGED
                        else CaptureScanUnavailableReason.STRUCTURE_CHANGED
                        child.recycle()
                        resumed = false
                        break
                    }
                    mount(child, index, saved[depth])
                }
                if (limit == null) restoring = false
                if (!resumed) {
                    restoring = false
                    reset = true
                    frames.forEach { it.node.recycle() }
                    frames.clear()
                    mount(AccessibilityNodeInfo.obtain(root), -1)
                }
            }
            while (frames.isNotEmpty() && limit == null && !stopped) {
                budget.limitReason()?.let { if (it == TraversalLimitReason.TIME) limit = it }
                if (limit != null) break
                val frame = frames.last()
                if (!frame.processed) {
                    frame.processed = true
                    visited++
                    maxDepth = maxOf(maxDepth, frames.lastIndex)
                    stopped = !onNode(frame.node, frames.lastIndex)
                    // refresh 可改变子节点列表；旧 frame 不能被当作新树的完整遍历。
                    if (!budget.expired() && runCatching { frame.node.childCount }.getOrNull() != frame.childCount) {
                        unreadable = true
                        structureChanged = true
                        unavailableReason = CaptureScanUnavailableReason.STRUCTURE_CHANGED
                        reset = true
                        stopped = true
                    }
                    if (budget.expired()) {
                        limit = TraversalLimitReason.TIME
                        frame.processed = false
                    }
                    if (stopped && budget.limitReason() != null) {
                        limit = budget.limitReason()
                        frame.processed = false
                    }
                    if (stopped || limit != null) break
                }
                if (frame.nextChildIndex < 0) {
                    frames.removeAt(frames.lastIndex).node.recycle()
                    continue
                }
                if (frames.lastIndex >= MAX_DEPTH) {
                    unreadable = true
                    frame.nextChildIndex = -1
                    continue
                }
                val readLimit = budget.tryVisit()
                if (readLimit != null) { limit = readLimit; break }
                val index = frame.nextChildIndex--
                val child = runCatching { frame.node.getChild(index) }.getOrNull()
                if (child == null) {
                    unreadable = true
                    unavailableReason = CaptureScanUnavailableReason.CHILD_UNREADABLE
                } else if (!runCatching { matches(child) }.getOrDefault(false)) {
                    child.recycle()
                    unreadable = true
                    unavailableReason = CaptureScanUnavailableReason.IDENTITY_CHANGED
                }
                else mount(child, index)
                if (budget.expired()) limit = TraversalLimitReason.TIME
            }
            if (continueCoverage) {
                identity = expected
                checkpoint = if (structureChanged) {
                    emptyList()
                } else if (restoring) {
                    checkpoint
                } else if (limit != null) {
                    frames.map { Position(it.childIndex, it.childCount, it.nextChildIndex, it.processed) }
                } else emptyList()
            }
            val completed = frames.isEmpty() && !unreadable && limit == null && !stopped && !resumed
            return Traversal(visited, maxDepth, completed, unreadable, limit ?: if (unreadable && maxDepth >= MAX_DEPTH) TraversalLimitReason.DEPTH else null, resumed, continuationDepth, frames.sumOf { (it.nextChildIndex + 1).coerceAtLeast(0) }, reset, unavailableReason)
        } catch (_: Exception) {
            if (continueCoverage) this.reset()
            return Traversal(visited, maxDepth, false, true, limit, resumed, continuationDepth, 0, true, CaptureScanUnavailableReason.NODE_UNREADABLE)
        } finally {
            frames.forEach { it.node.recycle() }
        }
    }

    data class Traversal(
        val visited: Int,
        val maxDepth: Int,
        val complete: Boolean,
        val unreadable: Boolean,
        val limitReason: TraversalLimitReason?,
        val resumed: Boolean,
        val continuationDepth: Int,
        val pendingBranchCount: Int,
        val cursorReset: Boolean,
        val unavailableReason: CaptureScanUnavailableReason? = null,
    )

    private data class Position(val childIndex: Int, val childCount: Int, val nextChildIndex: Int, val processed: Boolean)
    private data class Frame(val node: AccessibilityNodeInfo, val childIndex: Int, val childCount: Int, var nextChildIndex: Int, var processed: Boolean)
    private companion object { const val MAX_DEPTH = 40 }
}
