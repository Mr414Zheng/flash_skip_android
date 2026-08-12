package com.zhenghuaizhi.flashskip.platform.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.zhenghuaizhi.flashskip.FlashSkipApplication
import com.zhenghuaizhi.flashskip.domain.automation.AutomationEngine
import com.zhenghuaizhi.flashskip.domain.automation.ForegroundSignal
import com.zhenghuaizhi.flashskip.domain.model.CaptureFailure
import com.zhenghuaizhi.flashskip.domain.model.CapturePhase
import com.zhenghuaizhi.flashskip.domain.model.RuleCaptureState
import com.zhenghuaizhi.flashskip.domain.ports.PageIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class FlashSkipAccessibilityService : AccessibilityService() {
    private var serviceScope: CoroutineScope? = null
    private var engine: AutomationEngine? = null
    private var windowClassifier: ForegroundWindowClassifier? = null
    private var captureAdapter: AccessibilityCaptureAdapter? = null
    private var captureExpiryJob: Job? = null
    private var captureConfigurationPackage: String? = null
    private var captureConfigured = false
    private var automationPaused = false

    @Volatile
    private var latestIdentity: PageIdentity? = null

    @Volatile
    private var latestCaptureState = RuleCaptureState()

    private val container
        get() = (application as FlashSkipApplication).container

    override fun onServiceConnected() {
        super.onServiceConnected()
        engine?.close()
        serviceScope?.cancel()
        latestIdentity = null
        latestCaptureState = RuleCaptureState()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val gateway = AccessibilityPageGateway(this, ::currentTargetPackages) { latestIdentity }
        serviceScope = scope
        windowClassifier = ForegroundWindowClassifier(
            targetPackages = ::currentTargetPackages,
            applicationWindowType = AccessibilityWindowInfo.TYPE_APPLICATION,
        )
        engine = container.createAutomationEngine(
            pageGateway = gateway,
            monotonicClock = AndroidMonotonicClock,
            wallClock = AndroidWallClock,
            scope = scope,
        )
        captureAdapter = AccessibilityCaptureAdapter(
            service = this,
            coordinator = container.ruleCaptureCoordinator,
            scope = scope,
            stateProvider = { latestCaptureState },
        )
        restoreDefaultEventConfiguration()
        observeCaptureState(scope)
        container.serviceConnections.setConnected(true)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val safeEvent = event ?: return
        if (latestCaptureState.activeSession?.blocksAutomation == true) {
            handleCaptureEvent(safeEvent)
            return
        }
        when (safeEvent.eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> handleContentChanged(safeEvent)
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> handleWindowStateChanged(safeEvent)
            else -> Unit
        }
    }

    private fun handleCaptureEvent(event: AccessibilityEvent) {
        val session = latestCaptureState.activeSession ?: return
        val packageName = event.packageName?.toString() ?: return
        if (session.packageName != null && packageName != session.packageName) return
        if (session.packageName == null && packageName in captureIgnoredPackages()) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val metadata = snapshotWindowMetadata() ?: return
                val activeWindow = metadata.singleOrNull { it.id == event.windowId }
                if (activeWindow?.isActive != true ||
                    activeWindow.type != AccessibilityWindowInfo.TYPE_APPLICATION
                ) return
                val activityName = container.targetAppVersionReader.verifiedActivity(
                    packageName = packageName,
                    className = event.className?.toString(),
                ) ?: return
                val appName = container.targetAppVersionReader.displayNameFor(packageName)
                    ?: packageName
                captureAdapter?.onVerifiedTargetWindow(event, appName, activityName)
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> captureAdapter?.onContentChanged(event)
            AccessibilityEvent.TYPE_VIEW_CLICKED -> captureAdapter?.onClicked(event)
            else -> Unit
        }
    }

    private fun handleContentChanged(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return
        if (packageName !in currentTargetPackages()) return
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
                if (container.ruleCatalog.snapshot.value.rulesForPackage(packageName).isEmpty()) return
                val activityName = container.targetAppVersionReader.verifiedActivity(
                    packageName = packageName,
                    className = event.className?.toString(),
                ) ?: return
                val identity = PageIdentity(
                    packageName = packageName,
                    activityName = activityName,
                    windowId = windowId,
                    appVersion = container.targetAppVersionReader.versionFor(packageName),
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

    private fun observeCaptureState(scope: CoroutineScope) {
        var firstEmission = true
        scope.launch {
            container.ruleCaptureCoordinator.state.collect { state ->
                latestCaptureState = state
                captureAdapter?.onStateChanged(state)
                scheduleExpiry(state)
                val session = state.activeSession
                if (firstEmission && session?.phase in setOf(
                        CapturePhase.WAITING_FOR_TARGET,
                        CapturePhase.OBSERVING_CLICK,
                        CapturePhase.CAPTURED,
                    )
                ) {
                    firstEmission = false
                    container.ruleCaptureCoordinator.fail(
                        requireNotNull(session).id,
                        CaptureFailure.SERVICE_UNAVAILABLE,
                    )
                    return@collect
                }
                firstEmission = false
                when (session?.phase) {
                    CapturePhase.CONFIGURING -> {
                        setAutomationPaused(true)
                        if (configureForCapture(session.packageName)) {
                            container.ruleCaptureCoordinator.markConfigured(session.id)
                        } else {
                            container.ruleCaptureCoordinator.fail(
                                session.id,
                                CaptureFailure.SERVICE_UNAVAILABLE,
                            )
                        }
                    }

                    CapturePhase.WAITING_FOR_TARGET,
                    CapturePhase.OBSERVING_CLICK,
                    -> {
                        setAutomationPaused(true)
                        if (!configureForCapture(session.packageName)) {
                            container.ruleCaptureCoordinator.fail(
                                session.id,
                                CaptureFailure.SERVICE_UNAVAILABLE,
                            )
                        }
                    }

                    CapturePhase.CAPTURED -> {
                        restoreDefaultEventConfiguration()
                        setAutomationPaused(true)
                    }

                    CapturePhase.FAILED, null -> {
                        restoreDefaultEventConfiguration()
                        setAutomationPaused(false)
                    }
                }
            }
        }
    }

    private suspend fun setAutomationPaused(paused: Boolean) {
        if (paused == automationPaused) return
        engine?.setCapturePaused(paused)
        automationPaused = paused
        if (paused) latestIdentity = null
    }

    private fun configureForCapture(packageName: String?): Boolean {
        if (captureConfigured && captureConfigurationPackage == packageName) return true
        return runCatching {
            val info = serviceInfo
            info.eventTypes = DEFAULT_EVENT_TYPES or AccessibilityEvent.TYPE_VIEW_CLICKED
            info.packageNames = packageName?.let { arrayOf(it) }
            setServiceInfo(info)
            captureConfigurationPackage = packageName
            captureConfigured = true
        }.isSuccess
    }

    private fun restoreDefaultEventConfiguration() {
        if (serviceInfo == null) return
        runCatching {
            val info: AccessibilityServiceInfo = serviceInfo
            info.eventTypes = DEFAULT_EVENT_TYPES
            info.packageNames = null
            setServiceInfo(info)
            captureConfigurationPackage = null
            captureConfigured = false
        }
    }

    private fun scheduleExpiry(state: RuleCaptureState) {
        captureExpiryJob?.cancel()
        val session = state.activeSession?.takeUnless { it.phase == CapturePhase.FAILED } ?: return
        captureExpiryJob = serviceScope?.launch {
            delay((session.deadlineAtEpochMs - AndroidWallClock.currentTimeMillis()).coerceAtLeast(0))
            container.ruleCaptureCoordinator.expireActive()
        }
    }

    private fun failCapture(sessionId: Long, failure: CaptureFailure) {
        serviceScope?.launch { container.ruleCaptureCoordinator.fail(sessionId, failure) }
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

    override fun onInterrupt() {
        container.ruleCaptureCoordinator.serviceDisconnected()
    }

    override fun onDestroy() {
        container.serviceConnections.setConnected(false)
        container.ruleCaptureCoordinator.serviceDisconnected()
        restoreDefaultEventConfiguration()
        captureExpiryJob?.cancel()
        captureExpiryJob = null
        serviceScope?.cancel()
        serviceScope = null
        engine?.close()
        engine = null
        windowClassifier = null
        captureAdapter = null
        latestIdentity = null
        latestCaptureState = RuleCaptureState()
        captureConfigurationPackage = null
        captureConfigured = false
        automationPaused = false
        super.onDestroy()
    }

    private companion object {
        const val DEFAULT_EVENT_TYPES = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
    }

    private fun currentTargetPackages(): Set<String> =
        container.ruleCatalog.snapshot.value.rules.mapTo(mutableSetOf()) { it.packageName }

    private fun captureIgnoredPackages(): Set<String> = buildSet {
        add(packageName)
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        packageManager.resolveActivity(homeIntent, 0)?.activityInfo?.packageName?.let(::add)
    }
}
