package com.zhenghuaizhi.flashskip.platform.accessibility

import com.zhenghuaizhi.flashskip.domain.model.CaptureEventClockStatus
import com.zhenghuaizhi.flashskip.domain.model.CaptureEventTiming

/** 只接受数字时钟样本；AccessibilityEvent.eventTime 属于 uptime 时钟域。 */
internal object CaptureEventTimingRecorder {
    fun sample(
        eventUptimeMs: Long,
        receivedUptimeMs: Long,
        receivedElapsedMs: Long,
        receivedEpochMs: Long,
    ): CaptureEventTiming {
        val status = when {
            receivedUptimeMs <= 0 || receivedElapsedMs <= 0 || receivedEpochMs <= 0 ||
                receivedElapsedMs < receivedUptimeMs -> CaptureEventClockStatus.INVALID_CLOCK
            eventUptimeMs <= 0 -> CaptureEventClockStatus.MISSING_TIMESTAMP
            eventUptimeMs > receivedUptimeMs -> CaptureEventClockStatus.FUTURE_TIMESTAMP
            else -> CaptureEventClockStatus.VALID
        }
        return CaptureEventTiming(
            eventUptimeMs = eventUptimeMs,
            receivedUptimeMs = receivedUptimeMs,
            receivedElapsedMs = receivedElapsedMs,
            receivedEpochMs = receivedEpochMs,
            dispatchDelayMs = if (status == CaptureEventClockStatus.VALID) {
                receivedUptimeMs - eventUptimeMs
            } else null,
            clockStatus = status,
        )
    }
}
