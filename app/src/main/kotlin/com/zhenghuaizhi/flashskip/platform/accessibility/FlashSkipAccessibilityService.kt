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
import com.zhenghuaizhi.flashskip.domain.model.TargetWindowRecognitionOutcome
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
    private var eventPackageResolver: AccessibilityEventPackageResolver? = null
    private var captureExpiryJob: Job? = null
    private var captureConfigurationPackage: String? = null
    private var captureConfigured = false
    private var automationPaused = false
    private val targetWindowDiagnosticsTracker = TargetWindowDiagnosticsTracker()

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
        targetWindowDiagnosticsTracker.reset()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val gateway = AccessibilityPageGateway(this, ::currentTargetPackages) { latestIdentity }
        serviceScope = scope
        windowClassifier = ForegroundWindowClassifier(
            targetPackages = ::currentTargetPackages,
            applicationWindowType = AccessibilityWindowInfo.TYPE_APPLICATION,
        )
        eventPackageResolver = AccessibilityEventPackageResolver(this)
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
            targetWindowDiagnosticsProvider = { sessionId ->
                targetWindowDiagnosticsTracker.snapshot(
                    expectedSessionId = sessionId,
                    nowElapsedMs = AndroidMonotonicClock.elapsedRealtimeMs(),
                )
            },
            targetWindowResolutionRecorder = { packageName, windowId, outcome, isRetry, occurredAt ->
                targetWindowDiagnosticsTracker.recordResolution(
                    packageName = packageName,
                    windowId = windowId,
                    outcome = outcome,
                    isRetry = isRetry,
                    occurredAtElapsedMs = occurredAt,
                )
            },
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
        val occurredAt = AndroidMonotonicClock.elapsedRealtimeMs()
        val reportedPackageName = event.packageName?.toString() ?: run {
            targetWindowDiagnosticsTracker.recordMissingPackage()
            return
        }
        val verifiedHostPackage = captureAdapter?.verifiedHostPackageName()
        val packageName = if (reportedPackageName == SYSTEM_UI_PACKAGE) {
            verifiedHostPackage ?: run {
                targetWindowDiagnosticsTracker.recordIgnoredPackage()
                return
            }
        } else {
            reportedPackageName
        }
        val lockedPackage = session.packageName ?: verifiedHostPackage
        if (lockedPackage != null && packageName != lockedPackage) {
            targetWindowDiagnosticsTracker.recordIgnoredPackage()
            return
        }
        if (lockedPackage == null && packageName in captureIgnoredPackages()) {
            targetWindowDiagnosticsTracker.recordIgnoredPackage()
            return
        }
        // 未建立经验证宿主前，内容和点击事件不能触发任何节点读取。
        if (lockedPackage == null &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) {
            targetWindowDiagnosticsTracker.recordIgnoredPackage()
            return
        }
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val className = event.className?.toString()
                val metadata = snapshotWindowMetadata()
                if (metadata == null) {
                    targetWindowDiagnosticsTracker.recordWindowState(
                        packageName = packageName,
                        className = className,
                        windowId = event.windowId,
                        windowCount = null,
                        matchingWindowCount = null,
                        matchingWindowType = null,
                        matchingWindowActive = null,
                        outcome = TargetWindowRecognitionOutcome.WINDOW_METADATA_UNAVAILABLE,
                        occurredAtElapsedMs = occurredAt,
                    )
                    if (verifiedHostPackage == packageName) {
                        captureAdapter?.onTransientTargetWindow(event, packageName)
                    }
                    return
                }
                val matchingWindows = metadata.filter { it.id == event.windowId }
                val activeWindow = matchingWindows.singleOrNull()
                val boundaryOutcome = when {
                    matchingWindows.isEmpty() ->
                        TargetWindowRecognitionOutcome.EVENT_WINDOW_NOT_FOUND
                    matchingWindows.size > 1 ->
                        TargetWindowRecognitionOutcome.EVENT_WINDOW_AMBIGUOUS
                    activeWindow?.isActive != true ->
                        TargetWindowRecognitionOutcome.WINDOW_NOT_ACTIVE
                    activeWindow.type != AccessibilityWindowInfo.TYPE_APPLICATION ->
                        TargetWindowRecognitionOutcome.NON_APPLICATION_WINDOW
                    else -> null
                }
                if (boundaryOutcome != null) {
                    targetWindowDiagnosticsTracker.recordWindowState(
                        packageName = packageName,
                        className = className,
                        windowId = event.windowId,
                        windowCount = metadata.size,
                        matchingWindowCount = matchingWindows.size,
                        matchingWindowType = activeWindow?.type,
                        matchingWindowActive = activeWindow?.isActive,
                        outcome = boundaryOutcome,
                        occurredAtElapsedMs = occurredAt,
                    )
                    if (verifiedHostPackage == packageName) {
                        captureAdapter?.onTransientTargetWindow(event, packageName)
                    }
                    return
                }
                val activityName = container.targetAppVersionReader.verifiedActivity(
                    packageName = packageName,
                    className = className,
                )
                targetWindowDiagnosticsTracker.recordWindowState(
                    packageName = packageName,
                    className = className,
                    windowId = event.windowId,
                    windowCount = metadata.size,
                    matchingWindowCount = matchingWindows.size,
                    matchingWindowType = activeWindow?.type,
                    matchingWindowActive = activeWindow?.isActive,
                    outcome = if (activityName != null) {
                        TargetWindowRecognitionOutcome.VERIFIED
                    } else {
                        TargetWindowRecognitionOutcome.ACTIVITY_UNVERIFIED
                    },
                    occurredAtElapsedMs = occurredAt,
                )
                if (activityName == null) {
                    if (verifiedHostPackage == packageName) {
                        captureAdapter?.onTransientTargetWindow(event, packageName)
                    }
                    return
                }
                val appName = container.targetAppVersionReader.displayNameFor(packageName)
                    ?: packageName
                captureAdapter?.onVerifiedTargetWindow(
                    event = event,
                    packageName = packageName,
                    targetAppName = appName,
                    activityName = activityName,
                )
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                targetWindowDiagnosticsTracker.recordContentChanged(packageName, occurredAt)
                captureAdapter?.onContentChanged(event, packageName)
            }

            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                targetWindowDiagnosticsTracker.recordViewClicked(packageName, occurredAt)
                captureAdapter?.onClicked(event, packageName)
            }
            else -> Unit
        }
    }

    private fun handleContentChanged(event: AccessibilityEvent) {
        val reportedPackageName = event.packageName?.toString() ?: return
        val identity = latestIdentity?.takeIf {
            it.windowId == event.windowId &&
                (it.packageName == reportedPackageName ||
                    reportedPackageName == SYSTEM_UI_PACKAGE)
        } ?: return
        if (identity.packageName !in currentTargetPackages()) return
        engine?.onContentChanged(identity, AndroidMonotonicClock.elapsedRealtimeMs())
    }

    private fun handleWindowStateChanged(event: AccessibilityEvent) {
        val reportedPackageName = event.packageName?.toString() ?: return
        val windowId = event.windowId
        val packageName = effectiveSystemReportedPackageName(reportedPackageName, windowId)
        val occurredAt = AndroidMonotonicClock.elapsedRealtimeMs()
        val metadata = snapshotWindowMetadata() ?: return
        when (windowClassifier?.classify(packageName, windowId, metadata)) {
            BoundaryClassification.Target -> {
                if (container.ruleCatalog.snapshot.value.rulesForPackage(packageName).isEmpty()) return
                val verifiedActivityName = container.targetAppVersionReader.verifiedActivity(
                    packageName = packageName,
                    className = event.className?.toString(),
                )
                val identity = TargetWindowIdentityResolver.resolve(
                    packageName = packageName,
                    windowId = windowId,
                    verifiedActivityName = verifiedActivityName,
                    currentIdentity = latestIdentity,
                    appVersion = container.targetAppVersionReader.versionFor(packageName),
                ) ?: return
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
                targetWindowDiagnosticsTracker.onSessionChanged(state.activeSession?.id)
                captureAdapter?.onStateChanged(state)
                scheduleExpiry(state)
                val session = state.activeSession
                if (firstEmission && session?.phase in setOf(
                        CapturePhase.WAITING_FOR_TARGET,
                        CapturePhase.OBSERVING_CLICK,
                        CapturePhase.AWAITING_CONFIRMATION,
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

                    CapturePhase.AWAITING_CONFIRMATION -> {
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
            val targetWindowDiagnostics = targetWindowDiagnosticsTracker.snapshot(
                expectedSessionId = session.id,
                nowElapsedMs = AndroidMonotonicClock.elapsedRealtimeMs(),
            )
            val failureDiagnostics = captureAdapter?.timeoutDiagnostics(session.id)
            container.ruleCaptureCoordinator.expireActive(
                targetWindowDiagnostics = targetWindowDiagnostics,
                failureDiagnostics = failureDiagnostics,
            )
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
        eventPackageResolver = null
        latestIdentity = null
        latestCaptureState = RuleCaptureState()
        captureConfigurationPackage = null
        captureConfigured = false
        automationPaused = false
        targetWindowDiagnosticsTracker.reset()
        super.onDestroy()
    }

    private companion object {
        const val DEFAULT_EVENT_TYPES = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        const val SYSTEM_UI_PACKAGE = "com.android.systemui"
    }

    private fun currentTargetPackages(): Set<String> =
        container.ruleCatalog.snapshot.value.rules.mapTo(mutableSetOf()) { it.packageName }

    private fun captureIgnoredPackages(): Set<String> = buildSet {
        add(packageName)
        add(SYSTEM_UI_PACKAGE)
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        packageManager.resolveActivity(homeIntent, 0)?.activityInfo?.packageName?.let(::add)
    }

    private fun effectiveSystemReportedPackageName(
        reportedPackageName: String,
        eventWindowId: Int,
    ): String {
        if (reportedPackageName != SYSTEM_UI_PACKAGE) return reportedPackageName
        // 部分 OEM 会把活动应用弹窗上报为 SystemUI；只信任同 ID 的活动应用窗口根节点包名。
        return eventPackageResolver?.resolve(reportedPackageName, eventWindowId)
            ?: reportedPackageName
    }
}
