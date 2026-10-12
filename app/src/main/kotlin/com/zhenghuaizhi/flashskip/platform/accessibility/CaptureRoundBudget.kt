package com.zhenghuaizhi.flashskip.platform.accessibility

import com.zhenghuaizhi.flashskip.domain.model.TraversalLimitReason

/** 一次采集回调的共享软预算；同步 Binder 调用返回后仍须检查截止时间。 */
internal class CaptureRoundBudget(
    private val clock: () -> Long,
    val deadlineAtElapsedMs: Long,
    val maxNodeReads: Int = 500,
    private val parent: CaptureRoundBudget? = null,
) {
    val startedAtElapsedMs: Long = clock()
    var nodeReadCount: Int = 0
        private set
    val remainingNodeReads: Int get() = minOf(
        (maxNodeReads - nodeReadCount).coerceAtLeast(0),
        parent?.remainingNodeReads ?: Int.MAX_VALUE,
    )

    fun limitReason(): TraversalLimitReason? {
        parent?.limitReason()?.let { return it }
        return when {
            expired() -> TraversalLimitReason.TIME
            remainingNodeReads == 0 -> TraversalLimitReason.NODE_COUNT
            else -> null
        }
    }

    fun tryVisit(): TraversalLimitReason? {
        val reason = limitReason()
        if (reason != null) return reason
        val parentReason = parent?.tryVisit()
        if (parentReason != null) return parentReason
        nodeReadCount++
        return null
    }

    fun expired(): Boolean = clock() >= deadlineAtElapsedMs

    /** 子步骤可提前耗尽，已消费读次数仍进入父账本，后续扫描继续使用父预算。 */
    fun childSlice(maxNodeReads: Int, maxDurationMs: Long): CaptureRoundBudget = CaptureRoundBudget(
        clock = clock,
        deadlineAtElapsedMs = minOf(deadlineAtElapsedMs, clock() + maxDurationMs),
        maxNodeReads = maxNodeReads,
        parent = this,
    )

    companion object {
        fun create(
            clock: () -> Long,
            resolverDeadlineAtElapsedMs: Long? = null,
            maxNodeReads: Int = 500,
            maxDurationMs: Long = 250,
        ): CaptureRoundBudget {
            val deadline = clock() + maxDurationMs
            return CaptureRoundBudget(
                clock,
                minOf(deadline, resolverDeadlineAtElapsedMs ?: deadline),
                maxNodeReads,
            )
        }
    }
}
