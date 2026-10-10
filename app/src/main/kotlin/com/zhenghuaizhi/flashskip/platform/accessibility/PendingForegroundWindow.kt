package com.zhenghuaizhi.flashskip.platform.accessibility

/** 目录启动期间只保留一个窗口元数据；重放使用原事件时间，不延长执行窗口。 */
internal class PendingForegroundWindow {
    data class Entry(
        val packageName: String,
        val windowId: Int,
        val className: String?,
        val occurredAtElapsedMs: Long,
    )

    private var entry: Entry? = null

    fun remember(value: Entry) { entry = value }
    fun clear() { entry = null }

    fun takeIfFresh(nowElapsedMs: Long, allowedPackages: Set<String>): Entry? {
        val current = entry ?: return null
        entry = null
        val age = nowElapsedMs - current.occurredAtElapsedMs
        return current.takeIf { age in 0 until MAX_AGE_MS && it.packageName in allowedPackages }
    }

    companion object { const val MAX_AGE_MS = 10_000L }
}
