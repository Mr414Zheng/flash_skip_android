package com.zhenghuaizhi.flashskip.platform.accessibility

import com.zhenghuaizhi.flashskip.domain.model.TargetWindowDiagnostics
import com.zhenghuaizhi.flashskip.domain.model.TargetWindowRecognitionOutcome
import com.zhenghuaizhi.flashskip.domain.model.CaptureEventClockStatus
import com.zhenghuaizhi.flashskip.domain.model.CaptureEventTiming
import com.zhenghuaizhi.flashskip.domain.model.CaptureTimingDiagnostics
import android.os.SystemClock

internal class TargetWindowDiagnosticsTracker(
    private val elapsedClock: () -> Long = SystemClock::elapsedRealtime,
    private val uptimeClock: () -> Long = SystemClock::uptimeMillis,
) {
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
    private var timing = CaptureTimingDiagnostics()
    private var previousReceivedTiming: CaptureEventTiming? = null
    private var receivedSequence = 0L
    private var firstTargetSequence: Long? = null
    private var lastTargetSequence: Long? = null
    private val pendingPackages = LinkedHashMap<String, PendingPackageTiming>()
    private var clockTimelineValid = true
    private val startupObservations = CaptureStartupObservationTracker()

    fun onSessionChanged(newSessionId: Long?) {
        if (sessionId == newSessionId) return
        sessionId = newSessionId
        resetCounters()
        startupObservations.reset(newSessionId)
    }

    fun reset() {
        sessionId = null
        resetCounters()
        startupObservations.reset()
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
        timing = CaptureTimingDiagnostics()
        previousReceivedTiming = null
        receivedSequence = 0L
        firstTargetSequence = null
        lastTargetSequence = null
        pendingPackages.clear()
        clockTimelineValid = true
    }

    /** 返回本次归一化样本，供同一事件的所有日志分支复用。 */
    fun recordCaptureEvent(
        expectedSessionId: Long,
        sample: CaptureEventTiming,
        isTarget: Boolean,
        isClick: Boolean,
        reportedPackageName: String? = null,
    ): CaptureEventTiming {
        if (sessionId != expectedSessionId) return sample
        val previous = previousReceivedTiming
        val clockRolledBack = previous != null && (
            sample.receivedUptimeMs < previous.receivedUptimeMs ||
                sample.receivedElapsedMs < previous.receivedElapsedMs ||
                sample.receivedEpochMs < previous.receivedEpochMs
            )
        // 发现会话内时钟回拨后，跨事件耗时不再有可靠的时间顺序。
        val eventTiming = if (clockRolledBack) {
            clockTimelineValid = false
            sample.copy(dispatchDelayMs = null, clockStatus = CaptureEventClockStatus.INVALID_CLOCK)
        } else sample
        if (eventTiming.clockStatus == CaptureEventClockStatus.INVALID_CLOCK) clockTimelineValid = false
        previousReceivedTiming = eventTiming
        if (receivedSequence < Long.MAX_VALUE) receivedSequence++
        val sequence = receivedSequence
        if (isTarget) {
            if (firstTargetSequence == null) firstTargetSequence = sequence
            lastTargetSequence = sequence
        } else if (reportedPackageName != null &&
            reportedPackageName != SYSTEM_UI_PACKAGE && timing.firstVerifiedAtElapsedMs == null
        ) {
            val pending = pendingPackages[reportedPackageName] ?: run {
                if (pendingPackages.size >= MAX_PENDING_PACKAGES) {
                    pendingPackages.remove(pendingPackages.keys.first())
                    timing = timing.copy(
                        pendingPackageEvictionCount = saturatingAdd(timing.pendingPackageEvictionCount),
                    )
                }
                PendingPackageTiming(sequence, eventTiming).also {
                    pendingPackages[reportedPackageName] = it
                }
            }
            pending.record(sequence, eventTiming, isClick)
        }
        timing = timing.copy(
            receivedEventCount = saturatingAdd(timing.receivedEventCount),
            targetEventCount = saturatingAdd(timing.targetEventCount, if (isTarget) 1 else 0),
            preVerificationTargetClickCount = saturatingAdd(
                timing.preVerificationTargetClickCount,
                if (isTarget && isClick && timing.firstVerifiedAtElapsedMs == null) 1 else 0,
            ),
            invalidTimestampEventCount = saturatingAdd(
                timing.invalidTimestampEventCount,
                if (eventTiming.clockStatus == CaptureEventClockStatus.VALID) 0 else 1,
            ),
            firstTargetEvent = if (isTarget) timing.firstTargetEvent ?: eventTiming else timing.firstTargetEvent,
            lastTargetEvent = if (isTarget) eventTiming else timing.lastTargetEvent,
            maxTargetDispatchDelayMs = if (isTarget && eventTiming.dispatchDelayMs != null) {
                maxOf(timing.maxTargetDispatchDelayMs ?: 0, eventTiming.dispatchDelayMs)
            } else timing.maxTargetDispatchDelayMs,
        )
        return eventTiming
    }

    /** 只由真实验证宿主的回调确认包名；其余暂存包不能进入目标统计。 */
    fun confirmTargetPackage(expectedSessionId: Long, reportedPackageName: String): Boolean {
        if (sessionId != expectedSessionId || reportedPackageName == SYSTEM_UI_PACKAGE) return false
        val pending = pendingPackages.remove(reportedPackageName)
        pendingPackages.clear()
        if (pending == null) return false
        val firstSequence = firstTargetSequence
        val lastSequence = lastTargetSequence
        val choosePendingFirst = firstSequence == null || pending.firstSequence < firstSequence
        val choosePendingLast = lastSequence == null || pending.lastSequence > lastSequence
        if (choosePendingFirst) firstTargetSequence = pending.firstSequence
        if (choosePendingLast) lastTargetSequence = pending.lastSequence
        timing = timing.copy(
            targetEventCount = saturatingAdd(timing.targetEventCount, pending.eventCount),
            preVerificationTargetClickCount = saturatingAdd(
                timing.preVerificationTargetClickCount,
                pending.clickCount,
            ),
            firstTargetEvent = if (choosePendingFirst) pending.first else timing.firstTargetEvent,
            lastTargetEvent = if (choosePendingLast) pending.last else timing.lastTargetEvent,
            maxTargetDispatchDelayMs = listOfNotNull(
                timing.maxTargetDispatchDelayMs,
                pending.maxDispatchDelayMs,
            ).maxOrNull(),
        )
        return true
    }

    /** 仅首次实际通过目标窗口验证时记录；跨时钟域或回拨的差值保持未知。 */
    fun recordVerifiedWindow(
        expectedSessionId: Long,
        verifiedAtElapsedMs: Long = elapsedClock(),
        verifiedAtUptimeMs: Long = uptimeClock(),
    ): Boolean {
        if (sessionId != expectedSessionId || timing.firstVerifiedAtElapsedMs != null) return false
        val verifiedElapsed = verifiedAtElapsedMs
        val verifiedUptime = verifiedAtUptimeMs
        if (verifiedElapsed <= 0 || verifiedUptime <= 0) return false
        val first = timing.firstTargetEvent
        timing = timing.copy(
            firstVerifiedAtElapsedMs = verifiedElapsed,
            firstVerifiedAtUptimeMs = verifiedUptime,
            targetEventToVerifiedUptimeMs = first?.takeIf {
                clockTimelineValid &&
                    it.clockStatus == CaptureEventClockStatus.VALID &&
                    verifiedUptime >= it.eventUptimeMs
            }?.let { verifiedUptime - it.eventUptimeMs },
            targetReceivedToVerifiedMs = first?.takeIf {
                clockTimelineValid &&
                    it.clockStatus != CaptureEventClockStatus.INVALID_CLOCK &&
                    verifiedElapsed >= it.receivedElapsedMs
            }?.let { verifiedElapsed - it.receivedElapsedMs },
        )
        return true
    }

    /** 扫描入口只在至少访问一个节点后回报，后续扫描不覆盖首次时刻。 */
    fun recordFirstScan(expectedSessionId: Long, scanStartedAtElapsedMs: Long): Boolean {
        if (sessionId != expectedSessionId || timing.firstScanAtElapsedMs != null ||
            scanStartedAtElapsedMs <= 0) return false
        val verified = timing.firstVerifiedAtElapsedMs
        timing = timing.copy(
            firstScanAtElapsedMs = scanStartedAtElapsedMs,
            verifiedToFirstScanMs = verified?.takeIf {
                clockTimelineValid && scanStartedAtElapsedMs >= it
            }
                ?.let { scanStartedAtElapsedMs - it },
        )
        return true
    }

    fun timingSnapshot(expectedSessionId: Long): CaptureTimingDiagnostics? =
        timing.takeIf { sessionId == expectedSessionId }

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
            captureTiming = timing,
            startupObservation = startupObservations.snapshot(timing, clockTimelineValid),
        )
    }

    fun recordStartupObservation(expectedSessionId: Long, observation: StartupPageObservation): Boolean =
        startupObservations.record(expectedSessionId, observation)

    fun confirmStartupTarget(expectedSessionId: Long, packageName: String, windowId: Int) =
        startupObservations.confirm(expectedSessionId, packageName, windowId)

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

    private class PendingPackageTiming(
        val firstSequence: Long,
        val first: CaptureEventTiming,
    ) {
        var lastSequence: Long = firstSequence
            private set
        var last: CaptureEventTiming = first
            private set
        var eventCount: Int = 0
            private set
        var clickCount: Int = 0
            private set
        var maxDispatchDelayMs: Long? = null
            private set

        fun record(sequence: Long, sample: CaptureEventTiming, isClick: Boolean) {
            eventCount = saturatingAdd(eventCount)
            if (isClick) clickCount = saturatingAdd(clickCount)
            lastSequence = sequence
            last = sample
            sample.dispatchDelayMs?.let {
                maxDispatchDelayMs = maxOf(maxDispatchDelayMs ?: 0, it)
            }
        }
    }

    private companion object {
        fun saturatingAdd(value: Int, increment: Int = 1): Int =
            if (increment >= Int.MAX_VALUE - value) Int.MAX_VALUE else value + increment

        const val MAX_PACKAGE_LENGTH = 180
        const val MAX_CLASS_LENGTH = 240
        const val MAX_PENDING_PACKAGES = 8
        const val SYSTEM_UI_PACKAGE = "com.android.systemui"
    }
}
