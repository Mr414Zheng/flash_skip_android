package com.zhenghuaizhi.flashskip.platform.accessibility

import com.zhenghuaizhi.flashskip.domain.model.TargetWindowDiagnostics
import com.zhenghuaizhi.flashskip.domain.model.TargetWindowRecognitionOutcome

internal class TargetWindowDiagnosticsTracker {
    private var sessionId: Long? = null
    private var windowStateEventCount = 0
    private var contentChangedEventCount = 0
    private var viewClickedEventCount = 0
    private var ignoredPackageEventCount = 0
    private var missingPackageEventCount = 0
    private var windowResolutionAttemptCount = 0
    private var windowRetryAttemptCount = 0
    private var lastPackageName: String? = null
    private var lastClassName: String? = null
    private var lastEventWindowId: Int? = null
    private var lastWindowCount: Int? = null
    private var lastMatchingWindowCount: Int? = null
    private var lastMatchingWindowType: Int? = null
    private var lastMatchingWindowActive: Boolean? = null
    private var lastOutcome = TargetWindowRecognitionOutcome.NO_WINDOW_STATE_EVENT
    private var lastEventAtElapsedMs: Long? = null

    fun onSessionChanged(newSessionId: Long?) {
        if (sessionId == newSessionId) return
        sessionId = newSessionId
        resetCounters()
    }

    fun reset() {
        sessionId = null
        resetCounters()
    }

    private fun resetCounters() {
        windowStateEventCount = 0
        contentChangedEventCount = 0
        viewClickedEventCount = 0
        ignoredPackageEventCount = 0
        missingPackageEventCount = 0
        windowResolutionAttemptCount = 0
        windowRetryAttemptCount = 0
        lastPackageName = null
        lastClassName = null
        lastEventWindowId = null
        lastWindowCount = null
        lastMatchingWindowCount = null
        lastMatchingWindowType = null
        lastMatchingWindowActive = null
        lastOutcome = TargetWindowRecognitionOutcome.NO_WINDOW_STATE_EVENT
        lastEventAtElapsedMs = null
    }

    fun recordMissingPackage() {
        missingPackageEventCount++
    }

    fun recordIgnoredPackage() {
        ignoredPackageEventCount++
    }

    fun recordContentChanged(packageName: String, occurredAtElapsedMs: Long) {
        contentChangedEventCount++
        recordObservedEvent(packageName, occurredAtElapsedMs)
    }

    fun recordViewClicked(packageName: String, occurredAtElapsedMs: Long) {
        viewClickedEventCount++
        recordObservedEvent(packageName, occurredAtElapsedMs)
    }

    fun recordWindowState(
        packageName: String,
        className: String?,
        windowId: Int,
        windowCount: Int?,
        matchingWindowCount: Int?,
        matchingWindowType: Int?,
        matchingWindowActive: Boolean?,
        outcome: TargetWindowRecognitionOutcome,
        occurredAtElapsedMs: Long,
    ) {
        windowStateEventCount++
        lastPackageName = sanitize(packageName, MAX_PACKAGE_LENGTH)
        lastClassName = sanitize(className, MAX_CLASS_LENGTH)
        lastEventWindowId = windowId
        lastWindowCount = windowCount
        lastMatchingWindowCount = matchingWindowCount
        lastMatchingWindowType = matchingWindowType
        lastMatchingWindowActive = matchingWindowActive
        lastOutcome = outcome
        lastEventAtElapsedMs = occurredAtElapsedMs
    }

    fun recordResolution(
        packageName: String,
        windowId: Int,
        outcome: TargetWindowRecognitionOutcome,
        isRetry: Boolean,
        occurredAtElapsedMs: Long,
    ) {
        if (outcome != TargetWindowRecognitionOutcome.RETRY_EXHAUSTED) {
            windowResolutionAttemptCount++
            if (isRetry) windowRetryAttemptCount++
        }
        lastPackageName = sanitize(packageName, MAX_PACKAGE_LENGTH)
        lastEventWindowId = windowId
        lastOutcome = outcome
        lastEventAtElapsedMs = occurredAtElapsedMs
    }

    fun snapshot(expectedSessionId: Long, nowElapsedMs: Long): TargetWindowDiagnostics? {
        if (sessionId != expectedSessionId) return null
        return TargetWindowDiagnostics(
            windowStateEventCount = windowStateEventCount,
            contentChangedEventCount = contentChangedEventCount,
            viewClickedEventCount = viewClickedEventCount,
            ignoredPackageEventCount = ignoredPackageEventCount,
            missingPackageEventCount = missingPackageEventCount,
            windowResolutionAttemptCount = windowResolutionAttemptCount,
            windowRetryAttemptCount = windowRetryAttemptCount,
            lastPackageName = lastPackageName,
            lastClassName = lastClassName,
            lastEventWindowId = lastEventWindowId,
            lastWindowCount = lastWindowCount,
            lastMatchingWindowCount = lastMatchingWindowCount,
            lastMatchingWindowType = lastMatchingWindowType,
            lastMatchingWindowActive = lastMatchingWindowActive,
            lastOutcome = lastOutcome,
            lastEventAgeMs = lastEventAtElapsedMs?.let {
                (nowElapsedMs - it).coerceAtLeast(0)
            },
        )
    }

    private fun recordObservedEvent(packageName: String, occurredAtElapsedMs: Long) {
        lastPackageName = sanitize(packageName, MAX_PACKAGE_LENGTH)
        lastEventAtElapsedMs = occurredAtElapsedMs
    }

    private fun sanitize(value: String?, maxLength: Int): String? = value
        ?.map { if (it.isISOControl()) ' ' else it }
        ?.joinToString("")
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.take(maxLength)

    private companion object {
        const val MAX_PACKAGE_LENGTH = 180
        const val MAX_CLASS_LENGTH = 240
    }
}
