package com.zhenghuaizhi.flashskip.platform.accessibility

import com.zhenghuaizhi.flashskip.domain.model.CaptureStartupDiagnostics
import com.zhenghuaizhi.flashskip.domain.model.CaptureTimingDiagnostics

/** 暂存最多四个包/窗口的纯计数；正式验证只能认领完全相同的边界。 */
internal class CaptureStartupObservationTracker {
    private var sessionId: Long? = null
    private var confirmed: Pair<String, Int>? = null
    private val pages = linkedMapOf<Pair<String, Int>, CaptureStartupDiagnostics>()
    private var evictions = 0

    @Synchronized
    fun reset(nextSessionId: Long? = null) {
        sessionId = nextSessionId
        confirmed = null
        pages.clear()
        evictions = 0
    }

    @Synchronized
    fun record(expectedSessionId: Long, observation: StartupPageObservation): Boolean {
        if (sessionId != expectedSessionId || observation.outcome != StartupObservationOutcome.OBSERVED) return false
        val key = (observation.packageName ?: return false) to (observation.windowId ?: return false)
        val target = confirmed
        // 只有正式 resolver 已验证的同包子窗口可并入宿主；预验证窗口仍须完全匹配。
        val recordKey = if (target == null || target == key) key
            else if (observation.hostVerified && target.first == key.first) target
            else return false
        val firstElapsed = observation.observedAtElapsedMs ?: return false
        val firstEpoch = observation.observedAtEpochMs ?: return false
        val previous = pages.remove(recordKey) ?: CaptureStartupDiagnostics()
        if (pages.size >= 4) {
            pages.remove(pages.keys.first())
            if (evictions < Int.MAX_VALUE) evictions++
        }
        val capabilities = observation.capabilities
        // 后台较早开始的扫描可能晚于主线程扫描返回，首次时间按观测顺序而非写入顺序。
        val earlier = previous.firstObservedAtElapsedMs?.let { firstElapsed < it } ?: true
        val actionEpoch = capabilities.firstDismissActionAtEpochMs
            ?: firstEpoch.takeIf { capabilities.dismissActionChainCount > 0 }
        pages[recordKey] = previous.copy(
            pageObservationCount = if (previous.pageObservationCount == Int.MAX_VALUE) Int.MAX_VALUE else previous.pageObservationCount + 1,
            provisionalObservationCount = previous.provisionalObservationCount +
                if (!observation.hostVerified && previous.provisionalObservationCount < Int.MAX_VALUE) 1 else 0,
            firstObservedAtElapsedMs = if (earlier) firstElapsed else previous.firstObservedAtElapsedMs,
            firstObservedAtEpochMs = if (earlier) firstEpoch else previous.firstObservedAtEpochMs,
            firstDismissActionAtEpochMs = listOfNotNull(previous.firstDismissActionAtEpochMs, actionEpoch).minOrNull(),
            maxVisibleNodeCount = maxOf(previous.maxVisibleNodeCount, capabilities.visibleNodeCount),
            maxActionableNodeCount = maxOf(previous.maxActionableNodeCount, capabilities.actionableNodeCount),
            maxActionClickNodeCount = maxOf(previous.maxActionClickNodeCount, capabilities.actionClickNodeCount),
            maxDismissHintNodeCount = maxOf(previous.maxDismissHintNodeCount, capabilities.dismissHintNodeCount),
            maxDismissActionChainCount = maxOf(previous.maxDismissActionChainCount, capabilities.dismissActionChainCount),
            maxStableDismissCandidateCount = maxOf(previous.maxStableDismissCandidateCount, observation.stableDismissCandidateCount),
            completeScanCount = previous.completeScanCount +
                if (previous.completeScanCount < Int.MAX_VALUE && observation.scan?.isCompleteTraversal() == true) 1 else 0,
        )
        return true
    }

    @Synchronized
    fun confirm(expectedSessionId: Long, packageName: String, windowId: Int) {
        if (sessionId != expectedSessionId) return
        confirmed = packageName to windowId
        pages.keys.retainAll(setOf(requireNotNull(confirmed)))
    }

    @Synchronized
    fun snapshot(timing: CaptureTimingDiagnostics, clockValid: Boolean): CaptureStartupDiagnostics? {
        val value = if (confirmed != null) pages[confirmed] ?: CaptureStartupDiagnostics()
            else pages.values.lastOrNull() ?: return null
        val first = value.firstObservedAtElapsedMs
        val canCompare = clockValid && confirmed != null
        return value.copy(
            targetConfirmed = confirmed != null,
            observedBeforeFirstTargetEventReceived = if (canCompare && first != null) timing.firstTargetEvent?.let {
                first < it.receivedElapsedMs
            } else null,
            observedBeforeVerification = if (canCompare && first != null) timing.firstVerifiedAtElapsedMs?.let {
                first < it
            } else null,
            pendingBoundaryEvictionCount = evictions,
        )
    }
}
