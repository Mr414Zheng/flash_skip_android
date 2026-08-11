package com.zhenghuaizhi.flashskip.platform.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.zhenghuaizhi.flashskip.FlashSkipApplication
import com.zhenghuaizhi.flashskip.domain.automation.AutomationEngine
import com.zhenghuaizhi.flashskip.domain.automation.ForegroundSignal
import com.zhenghuaizhi.flashskip.domain.ports.PageIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class FlashSkipAccessibilityService : AccessibilityService() {
    private var serviceScope: CoroutineScope? = null
    private var engine: AutomationEngine? = null
    private var windowClassifier: ForegroundWindowClassifier? = null
    private var targetPackages: Set<String> = emptySet()

    @Volatile
    private var latestIdentity: PageIdentity? = null

    private val container
        get() = (application as FlashSkipApplication).container

    override fun onServiceConnected() {
        super.onServiceConnected()
        engine?.close()
        serviceScope?.cancel()
        latestIdentity = null
        targetPackages = container.targetPackages
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val gateway = AccessibilityPageGateway(this, targetPackages) { latestIdentity }
        serviceScope = scope
        windowClassifier = ForegroundWindowClassifier(
            targetPackages = targetPackages,
            applicationWindowType = AccessibilityWindowInfo.TYPE_APPLICATION,
        )
        engine = container.createAutomationEngine(
            pageGateway = gateway,
            monotonicClock = AndroidMonotonicClock,
            wallClock = AndroidWallClock,
            scope = scope,
        )
        container.serviceConnections.setConnected(true)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val safeEvent = event ?: return
        when (safeEvent.eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> handleContentChanged(safeEvent)
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> handleWindowStateChanged(safeEvent)
            else -> Unit
        }
    }

    private fun handleContentChanged(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return
        if (packageName !in targetPackages) return
        val identity = latestIdentity?.takeIf {
            it.packageName == packageName && it.windowId == event.windowId
        } ?: return
        engine?.onContentChanged(identity, AndroidMonotonicClock.elapsedRealtimeMs())
    }

    private fun handleWindowStateChanged(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return
        val windowId = event.windowId
        val occurredAt = AndroidMonotonicClock.elapsedRealtimeMs()
        val metadata = snapshotWindowMetadata() ?: return
        when (windowClassifier?.classify(packageName, windowId, metadata)) {
            BoundaryClassification.Target -> {
                val identity = PageIdentity(
                    packageName = packageName,
                    activityName = event.className?.toString(),
                    windowId = windowId,
                )
                latestIdentity = identity
                engine?.onForegroundSignal(ForegroundSignal.TargetActive(identity, occurredAt))
            }

            BoundaryClassification.OutsideTargets -> {
                // 即时清空节点访问身份，再把不含非目标数据的边界信号交给领域层。
                latestIdentity = null
                engine?.onForegroundSignal(ForegroundSignal.OutsideTargets(occurredAt))
            }

            BoundaryClassification.Ignore, null -> Unit
        }
    }

    @Suppress("DEPRECATION")
    private fun snapshotWindowMetadata(): List<WindowMetadata>? {
        val currentWindows = runCatching { windows }.getOrNull() ?: return null
        return try {
            currentWindows.map { window ->
                WindowMetadata(window.id, window.type, window.isActive)
            }
        } finally {
            // 窗口对象不缓存、不跨协程，只在本次回调栈内读取并释放。
            currentWindows.forEach(AccessibilityWindowInfo::recycle)
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        container.serviceConnections.setConnected(false)
        engine?.close()
        engine = null
        serviceScope?.cancel()
        serviceScope = null
        windowClassifier = null
        targetPackages = emptySet()
        latestIdentity = null
        super.onDestroy()
    }
}
