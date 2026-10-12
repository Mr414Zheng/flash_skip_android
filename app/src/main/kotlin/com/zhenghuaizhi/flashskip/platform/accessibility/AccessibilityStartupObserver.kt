package com.zhenghuaizhi.flashskip.platform.accessibility

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.zhenghuaizhi.flashskip.domain.model.CapturedNode
import com.zhenghuaizhi.flashskip.domain.model.ClickRecoveryDiagnostics
import com.zhenghuaizhi.flashskip.domain.ports.PageIdentity
import com.zhenghuaizhi.flashskip.domain.rulecapture.ClickSemanticHint
import com.zhenghuaizhi.flashskip.domain.rulecapture.DismissSemantics

internal enum class StartupObservationOutcome {
    OBSERVED, NO_ACTIVE_WINDOW, AMBIGUOUS_WINDOW, NON_APPLICATION_WINDOW,
    IGNORED_PACKAGE, ROOT_UNAVAILABLE, ROOT_MISMATCH, WINDOW_CHANGED, UNAVAILABLE,
    CACHE_REFRESH_UNSUPPORTED,
}

internal data class StartupPageCapabilities(
    val visibleNodeCount: Int = 0,
    val actionableNodeCount: Int = 0,
    val actionClickNodeCount: Int = 0,
    val dismissHintNodeCount: Int = 0,
    val dismissActionChainCount: Int = 0,
    val firstDismissActionAtEpochMs: Long? = null,
)

internal class StartupPageCapabilityCounter(private val epochClock: () -> Long = System::currentTimeMillis) {
    private var counts = StartupPageCapabilities()
    fun reset() { counts = StartupPageCapabilities() }
    fun snapshot() = counts
    fun record(node: CapturedNode, chain: List<CapturedNode>?) {
        if (!node.visibleToUser || !node.hasUsableBounds) return
        val hint = DismissSemantics.isKnown(node.text) ||
            DismissSemantics.isKnown(node.contentDescription) ||
            DismissSemantics.isKnownResourceId(node.resourceId)
        val dismissAction = hint && chain?.hasSafeDismissAction() == true
        counts = counts.copy(
            visibleNodeCount = counts.visibleNodeCount + 1,
            actionableNodeCount = counts.actionableNodeCount + if (node.clickable || node.supportsActionClick) 1 else 0,
            actionClickNodeCount = counts.actionClickNodeCount + if (node.supportsActionClick) 1 else 0,
            dismissHintNodeCount = counts.dismissHintNodeCount + if (hint) 1 else 0,
            dismissActionChainCount = counts.dismissActionChainCount + if (dismissAction) 1 else 0,
            firstDismissActionAtEpochMs = counts.firstDismissActionAtEpochMs ?: if (dismissAction) epochClock() else null,
        )
    }
}

/** 只返回纯诊断值；预验证节点、选择器和动作链不能进入正式采集器。 */
internal data class StartupPageObservation(
    val outcome: StartupObservationOutcome,
    val packageName: String? = null,
    val windowId: Int? = null,
    val observedAtElapsedMs: Long? = null,
    val observedAtEpochMs: Long? = null,
    val capabilities: StartupPageCapabilities = StartupPageCapabilities(),
    val stableDismissCandidateCount: Int = 0,
    val scan: ClickRecoveryDiagnostics? = null,
    val hostVerified: Boolean = false,
)

/** 单个后台观测任务专用；Android 窗口与节点只在一次 inspect 调用中持有。 */
internal class AccessibilityStartupObserver(
    private val service: AccessibilityService,
    private val elapsedClock: () -> Long = AndroidMonotonicClock::elapsedRealtimeMs,
    private val epochClock: () -> Long = System::currentTimeMillis,
    private val refreshRoot: (AccessibilityNodeInfo) -> Boolean = { it.refresh() },
    private val prepareWindowRead: () -> Boolean = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            service.clearCache()
            true
        } else false
    },
) {
    private var identity: PageIdentity? = null
    private val counter = StartupPageCapabilityCounter(epochClock)
    private val probe = AccessibilityCaptureProbe(
        service, { identity?.packageName?.let(::setOf).orEmpty() }, { identity },
        AccessibilitySemanticSnapshotter(service), elapsedRealtimeMs = elapsedClock,
        onCandidateSnapshot = counter::record,
    )

    @Suppress("DEPRECATION")
    fun inspect(ignoredPackages: Set<String>): StartupPageObservation {
        var root: AccessibilityNodeInfo? = null
        var windows: List<AccessibilityWindowInfo> = emptyList()
        var observed = false
        try {
            // getWindows 会命中 SDK 缓存，而其失效通知也可能滞留在主线程事件队列。
            // 使用公开 API 刷新本服务缓存；旧系统不把缓存窗口作为预验证前台证据。
            if (!prepareWindowRead()) return StartupPageObservation(StartupObservationOutcome.CACHE_REFRESH_UNSUPPORTED)
            windows = service.windows
            val active = windows.filter { it.isActive }
            val window = active.singleOrNull() ?: return StartupPageObservation(
                if (active.isEmpty()) StartupObservationOutcome.NO_ACTIVE_WINDOW else StartupObservationOutcome.AMBIGUOUS_WINDOW,
            )
            if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) {
                return StartupPageObservation(StartupObservationOutcome.NON_APPLICATION_WINDOW, windowId = window.id)
            }
            root = window.root ?: return StartupPageObservation(StartupObservationOutcome.ROOT_UNAVAILABLE, windowId = window.id)
            val packageName = root.packageName?.toString()
                ?: return StartupPageObservation(StartupObservationOutcome.ROOT_MISMATCH, windowId = window.id)
            if (packageName in ignoredPackages) return StartupPageObservation(StartupObservationOutcome.IGNORED_PACKAGE, windowId = window.id)
            if (window.id < 0 || root.windowId != window.id || !refreshRoot(root) ||
                root.packageName?.toString() != packageName || root.windowId != window.id
            ) return StartupPageObservation(StartupObservationOutcome.ROOT_MISMATCH, windowId = window.id)
            val expected = PageIdentity(packageName, null, window.id, null)
            if (identity != expected) probe.resetCoverage()
            identity = expected
            counter.reset()
            val started = elapsedClock()
            val epoch = epochClock()
            val budget = CaptureRoundBudget.create(elapsedClock, maxDurationMs = 100)
            val result = probe.recoverClickedSemanticChainOnVerifiedRoot(
                expected, root, ClickSemanticHint(), budget = budget, continueCoverage = true,
            )
            // 前后都验证活动窗口；中途切换得到的快照不能归因于原目标。
            if (!prepareWindowRead()) return StartupPageObservation(StartupObservationOutcome.UNAVAILABLE)
            val after = service.windows
            val sameWindow = try {
                after.filter { it.isActive }.singleOrNull()?.let {
                    it.id == expected.windowId && it.type == AccessibilityWindowInfo.TYPE_APPLICATION
                } == true
            } finally { after.forEach(AccessibilityWindowInfo::recycle) }
            if (!sameWindow) return StartupPageObservation(StartupObservationOutcome.WINDOW_CHANGED, windowId = window.id)
            observed = true
            return StartupPageObservation(
                StartupObservationOutcome.OBSERVED, packageName, window.id, started, epoch,
                counter.snapshot(), result.semanticCandidates.count { it.nodes.hasSafeDismissAction() }, result.diagnostics,
            )
        } catch (_: Exception) {
            return StartupPageObservation(StartupObservationOutcome.UNAVAILABLE)
        } finally {
            root?.recycle()
            windows.forEach(AccessibilityWindowInfo::recycle)
            if (!observed) { identity = null; probe.resetCoverage() }
        }
    }
}
