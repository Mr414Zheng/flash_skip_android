package com.zhenghuaizhi.flashskip.platform.accessibility

internal class CaptureWindowRetryGate(
    private val maxAttempts: Int = 3,
    private val maxElapsedMs: Long = 250L,
) {
    private var state: State? = null

    fun begin(key: WindowRetryKey, nowElapsedMs: Long): Boolean {
        if (state?.key == key) return false
        state = State(key = key, startedAtElapsedMs = nowElapsedMs)
        return true
    }

    fun claimAttempt(key: WindowRetryKey, nowElapsedMs: Long): Boolean {
        val current = state?.takeIf { it.key == key } ?: return false
        if (current.attemptCount >= maxAttempts ||
            nowElapsedMs - current.startedAtElapsedMs !in 0..maxElapsedMs
        ) {
            return false
        }
        state = current.copy(attemptCount = current.attemptCount + 1)
        return true
    }

    fun isActive(key: WindowRetryKey): Boolean = state?.key == key

    fun deadlineAtElapsedMs(key: WindowRetryKey): Long? = state
        ?.takeIf { it.key == key }
        ?.let { it.startedAtElapsedMs + maxElapsedMs }

    fun clear() {
        state = null
    }

    private data class State(
        val key: WindowRetryKey,
        val startedAtElapsedMs: Long,
        val attemptCount: Int = 0,
    )
}

internal data class WindowRetryKey(
    val sessionId: Long,
    val packageName: String,
    val windowId: Int,
)
