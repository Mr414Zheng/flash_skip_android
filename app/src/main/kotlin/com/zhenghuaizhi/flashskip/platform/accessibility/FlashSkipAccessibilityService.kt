package com.zhenghuaizhi.flashskip.platform.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.zhenghuaizhi.flashskip.application.rulecapture.CaptureDiagnosticSnapshot
import com.zhenghuaizhi.flashskip.FlashSkipApplication
import com.zhenghuaizhi.flashskip.domain.automation.AutomationEngine
import com.zhenghuaizhi.flashskip.domain.automation.ForegroundSignal
import com.zhenghuaizhi.flashskip.domain.model.CaptureFailure
import com.zhenghuaizhi.flashskip.domain.model.CapturePhase
import com.zhenghuaizhi.flashskip.domain.model.CaptureSession
import com.zhenghuaizhi.flashskip.domain.model.CaptureSample
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
import kotlinx.coroutines.flow.onCompletion
import com.zhenghuaizhi.flashskip.BuildConfig
import kotlinx.coroutines.launch
import com.zhenghuaizhi.flashskip.application.rulecapture.CaptureExpiryKey
import com.zhenghuaizhi.flashskip.application.rulecapture.CaptureOperationResult

class FlashSkipAccessibilityService : AccessibilityService() {
    private var serviceScope: CoroutineScope? = null
    private var engine: AutomationEngine? = null
    private var windowClassifier: ForegroundWindowClassifier? = null
    private var captureAdapter: AccessibilityCaptureAdapter? = null
    private var eventPackageResolver: AccessibilityEventPackageResolver? = null
    private var captureExpiryJob: Job? = null
    private var diagnosticsProviderRegistrationId: Long? = null
    private var captureConfigurationPackage: String? = null
    private var captureConfigured = false
    private var automationPaused = false
    private val targetWindowDiagnosticsTracker = TargetWindowDiagnosticsTracker()
    private val pendingForegroundWindow = PendingForegroundWindow()

    @Volatile
    private var latestIdentity: PageIdentity? = null

    @Volatile
    private var latestCaptureState = RuleCaptureState()

    private val container
        get() = (application as FlashSkipApplication).container

    override fun onCreate() {
        super.onCreate()
        CaptureTraceLog.lifecycle("service_created")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        CaptureTraceLog.boundary(latestCaptureState.activeSession?.id, "service_connected",
            "reconnected" to (serviceScope != null))
        latestCaptureState.activeSession?.let {
            traceCaptureSummary(it, CaptureTraceConclusion.SERVICE_RECONNECTED)
        }
        diagnosticsProviderRegistrationId?.let {
            container.ruleCaptureCoordinator.unregisterReturnDiagnosticsProvider(it)
        }
        diagnosticsProviderRegistrationId = null
        engine?.close()
        serviceScope?.cancel()
        latestIdentity = null
        pendingForegroundWindow.clear()
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
            targetWindowResolutionRecorder = { record ->
                val request = record.request
                targetWindowDiagnosticsTracker.recordResolution(
                    packageName = request.eventPackageName,
                    windowId = request.eventWindowId,
                    outcome = record.outcome,
                    isRetry = record.isRetry,
                    occurredAtElapsedMs = record.occurredAtElapsedMs,
                )
            },
        )
        diagnosticsProviderRegistrationId = container.ruleCaptureCoordinator
            .registerReturnDiagnosticsProvider { sessionId ->
                CaptureDiagnosticSnapshot(
                    failure = captureAdapter?.timeoutDiagnostics(sessionId),
                    targetWindow = targetWindowDiagnosticsTracker.snapshot(
                        expectedSessionId = sessionId,
                        nowElapsedMs = AndroidMonotonicClock.elapsedRealtimeMs(),
                    ),
                )
            }
        restoreDefaultEventConfiguration()
        observeCaptureState(scope)
        scope.launch {
            container.ruleCatalog.snapshot.collect { snapshot ->
                if (snapshot.targetPackages.isNotEmpty()) replayPendingForegroundWindow()
            }
        }
        container.serviceConnections.setConnected(true)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val safeEvent = event ?: return
        val captureSession = latestCaptureState.activeSession
        if (captureSession?.blocksAutomation == true) {
            pendingForegroundWindow.clear()
            // 配置或等待用户确认时没有采集工作，避免每个事件都访问系统窗口和包信息。
            if (captureSession.listensForCandidateClick) {
                if (safeEvent.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
                    handleCaptureEvent(safeEvent)
                }
                return
            }
            if (captureSession.phase != CapturePhase.WAITING_FOR_TARGET &&
                captureSession.phase != CapturePhase.OBSERVING_CLICK
            ) {
                return
            }
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
            CaptureTraceLog.event(session, event, CaptureTraceEventRoute.IGNORED, CaptureTraceReason.MISSING_PACKAGE)
            return
        }
        val verifiedHostPackage = captureAdapter?.verifiedHostPackageName()
        val packageName = if (reportedPackageName == SYSTEM_UI_PACKAGE) {
            verifiedHostPackage ?: run {
                targetWindowDiagnosticsTracker.recordIgnoredPackage()
                CaptureTraceLog.event(session, event, CaptureTraceEventRoute.IGNORED, CaptureTraceReason.SYSTEM_UI_WITHOUT_VERIFIED_HOST)
                return
            }
        } else {
            reportedPackageName
        }
        val lockedPackage = session.packageName ?: verifiedHostPackage
        if (lockedPackage != null && packageName != lockedPackage) {
            targetWindowDiagnosticsTracker.recordIgnoredPackage()
            CaptureTraceLog.event(session, event, CaptureTraceEventRoute.IGNORED, CaptureTraceReason.LOCKED_PACKAGE_MISMATCH)
            return
        }
        if (lockedPackage == null && packageName in captureIgnoredPackages()) {
            targetWindowDiagnosticsTracker.recordIgnoredPackage()
            CaptureTraceLog.event(session, event, CaptureTraceEventRoute.IGNORED, CaptureTraceReason.PACKAGE_IGNORED_BEFORE_TARGET)
            return
        }
        // 未建立经验证宿主前，内容和点击事件不能触发任何节点读取。
        if (lockedPackage == null &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) {
            targetWindowDiagnosticsTracker.recordIgnoredPackage()
            CaptureTraceLog.event(session, event, CaptureTraceEventRoute.IGNORED, CaptureTraceReason.HOST_NOT_VERIFIED)
            return
        }
        CaptureTraceLog.event(
            session,
            event,
            if (event.eventType in setOf(
                    AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                    AccessibilityEvent.TYPE_VIEW_CLICKED,
                )
            ) CaptureTraceEventRoute.DISPATCHED else CaptureTraceEventRoute.OBSERVED_ONLY,
        )
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val className = event.className?.toString()
                val metadata = snapshotWindowMetadata()
                if (metadata == null) {
                    CaptureTraceLog.stage(
                        session.id,
                        "window_state",
                        "outcome" to TargetWindowRecognitionOutcome.WINDOW_METADATA_UNAVAILABLE,
                    )
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
                    CaptureTraceLog.stage(
                        session.id,
                        "window_state",
                        "outcome" to boundaryOutcome,
                        "windows" to metadata.size,
                        "matching" to matchingWindows.size,
                        "type" to activeWindow?.type,
                        "active" to activeWindow?.isActive,
                    )
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
                CaptureTraceLog.stage(
                    session.id,
                    "window_state",
                    "outcome" to if (activityName != null) {
                        TargetWindowRecognitionOutcome.VERIFIED
                    } else {
                        TargetWindowRecognitionOutcome.ACTIVITY_UNVERIFIED
                    },
                    "windows" to metadata.size,
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
        val activeApplication = metadata.singleOrNull { it.id == windowId }?.let {
            it.isActive && it.type == AccessibilityWindowInfo.TYPE_APPLICATION
        } == true
        if (activeApplication) {
            pendingForegroundWindow.clear()
            if (currentTargetPackages().isEmpty()) pendingForegroundWindow.remember(
                PendingForegroundWindow.Entry(packageName, windowId, event.className?.toString(), occurredAt),
            )
        }
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

    @Suppress("DEPRECATION")
    private fun replayPendingForegroundWindow() {
        if (latestCaptureState.activeSession?.blocksAutomation == true || automationPaused) {
            pendingForegroundWindow.clear()
            return
        }
        val pending = pendingForegroundWindow.takeIfFresh(
            AndroidMonotonicClock.elapsedRealtimeMs(), currentTargetPackages(),
        ) ?: return
        val currentWindows = runCatching { windows }.getOrNull() ?: return
        val matches = try {
            val window = currentWindows.singleOrNull {
                it.id == pending.windowId && it.isActive && it.type == AccessibilityWindowInfo.TYPE_APPLICATION
            } ?: return
            val root = runCatching { window.root }.getOrNull() ?: return
            try { root.packageName?.toString() == pending.packageName } finally { root.recycle() }
        } finally { currentWindows.forEach(AccessibilityWindowInfo::recycle) }
        if (!matches || container.ruleCatalog.snapshot.value.rulesForPackage(pending.packageName).isEmpty()) return
        val activity = container.targetAppVersionReader.verifiedActivity(pending.packageName, pending.className)
        val identity = TargetWindowIdentityResolver.resolve(
            pending.packageName, pending.windowId, activity, null,
            container.targetAppVersionReader.versionFor(pending.packageName),
        ) ?: return
        latestIdentity = identity
        engine?.onForegroundSignal(ForegroundSignal.TargetActive(identity, pending.occurredAtElapsedMs))
    }

    private fun observeCaptureState(scope: CoroutineScope) {
        var firstEmission = true
        CaptureTraceLog.lifecycle("service_capture_observer_started")
        scope.launch {
            container.ruleCaptureCoordinator.state.onCompletion { error ->
                CaptureTraceLog.boundary(latestCaptureState.activeSession?.id, "service_capture_observer_completed",
                    "failed" to (error != null), "error" to error?.let(CaptureTraceError::from))
            }.collect { state ->
                if (firstEmission) CaptureTraceLog.boundary(state.activeSession?.id, "service_capture_first_state",
                    "hasSession" to (state.activeSession != null), "phase" to state.activeSession?.phase)
                // 必须先输出旧上下文，再让采集器和窗口计数器处理新会话并清空缓冲区。
                traceCaptureTransition(latestCaptureState, state)
                latestCaptureState = state
                CaptureTraceLog.state(state.activeSession)
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
                    setAutomationPaused(true)
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
                        if (session.listensForCandidateClick) {
                            setAutomationPaused(true)
                            if (!configureForCandidateClick(session.packageName)) {
                                container.ruleCaptureCoordinator.fail(
                                    session.id,
                                    CaptureFailure.SERVICE_UNAVAILABLE,
                                )
                            }
                        } else {
                            restoreDefaultEventConfiguration()
                        }
                    }

                    CapturePhase.FAILED, null -> {
                        restoreDefaultEventConfiguration()
                        setAutomationPaused(false)
                    }
                }
            }
        }
    }

    private fun traceCaptureTransition(previous: RuleCaptureState, next: RuleCaptureState) {
        val before = previous.activeSession
        val after = next.activeSession
        if (before != null && before.id != after?.id) {
            val saved = next.samples.firstOrNull { it.id == before.id && it !in previous.samples }
            traceCaptureSummary(
                before,
                when {
                    saved != null -> CaptureTraceConclusion.SAVED
                    after != null -> CaptureTraceConclusion.REPLACED
                    else -> CaptureTraceConclusion.CANCELLED
                },
                savedSample = saved,
            )
        }
        val conclusion = when {
            after?.phase == CapturePhase.FAILED &&
                (before?.id != after.id || before.phase != CapturePhase.FAILED) -> CaptureTraceConclusion.FAILED
            after?.phase == CapturePhase.CAPTURED &&
                (before?.id != after.id || before.phase != CapturePhase.CAPTURED) -> CaptureTraceConclusion.CLICK_CAPTURED
            after?.phase == CapturePhase.AWAITING_CONFIRMATION && before?.id == after.id &&
                before.listensForCandidateClick && !after.listensForCandidateClick ->
                CaptureTraceConclusion.CANDIDATE_LISTENING_STOPPED
            else -> null
        }
        if (after != null && conclusion != null) traceCaptureSummary(after, conclusion)
    }

    private fun traceCaptureSummary(
        session: CaptureSession,
        reason: CaptureTraceConclusion,
        savedSample: CaptureSample? = null,
    ) {
        CaptureTraceLog.summary(
            session,
            reason,
            diagnostics = { session.failureDiagnostics ?: captureAdapter?.timeoutDiagnostics(session.id) },
            targetWindow = {
                session.targetWindowDiagnostics ?: targetWindowDiagnosticsTracker.snapshot(
                    expectedSessionId = session.id,
                    nowElapsedMs = AndroidMonotonicClock.elapsedRealtimeMs(),
                )
            },
            savedSample = savedSample,
        )
    }

    private suspend fun setAutomationPaused(paused: Boolean) {
        if (paused == automationPaused) return
        engine?.setCapturePaused(paused)
        automationPaused = paused
        if (paused) {
            latestIdentity = null
            pendingForegroundWindow.clear()
        }
    }

    private fun configureForCapture(packageName: String?): Boolean {
        if (isCaptureConfigurationCurrent(packageName, CAPTURE_EVENT_TYPES)) return true
        return runCatching {
            val info = serviceInfo
            // 保留跨包边界识别，只订阅采集实际消费的窗口变化与点击事件。
            info.eventTypes = CAPTURE_EVENT_TYPES
            info.packageNames = null
            setServiceInfo(info)
            captureConfigurationPackage = packageName
            captureConfigured = true
            CaptureTraceLog.stage(
                latestCaptureState.activeSession?.id,
                "capture_configuration",
                "eventTypes" to CAPTURE_EVENT_TYPES,
            )
        }.onSuccess {
            traceConfiguration("capture_configuration_applied", CAPTURE_EVENT_TYPES)
        }.onFailure { error ->
            traceConfiguration("capture_configuration_failed", CAPTURE_EVENT_TYPES, error)
        }.isSuccess
    }

    private fun configureForCandidateClick(packageName: String?): Boolean {
        if (isCaptureConfigurationCurrent(packageName, AccessibilityEvent.TYPE_VIEW_CLICKED)) {
            return true
        }
        return runCatching {
            val info = serviceInfo
            info.eventTypes = AccessibilityEvent.TYPE_VIEW_CLICKED
            info.packageNames = null
            setServiceInfo(info)
            captureConfigurationPackage = packageName
            captureConfigured = true
            CaptureTraceLog.stage(
                latestCaptureState.activeSession?.id,
                "capture_configuration",
                "eventTypes" to AccessibilityEvent.TYPE_VIEW_CLICKED,
            )
        }.onSuccess {
            traceConfiguration("candidate_configuration_applied", AccessibilityEvent.TYPE_VIEW_CLICKED)
        }.onFailure { error ->
            traceConfiguration("candidate_configuration_failed", AccessibilityEvent.TYPE_VIEW_CLICKED, error)
        }.isSuccess
    }

    private fun isCaptureConfigurationCurrent(packageName: String?, eventTypes: Int): Boolean =
        captureConfigured &&
            captureConfigurationPackage == packageName &&
            serviceInfo?.eventTypes == eventTypes

    private fun restoreDefaultEventConfiguration() {
        if (serviceInfo == null) {
            traceConfiguration("default_configuration_unavailable", DEFAULT_EVENT_TYPES)
            return
        }
        runCatching {
            val info: AccessibilityServiceInfo = serviceInfo
            info.eventTypes = DEFAULT_EVENT_TYPES
            info.packageNames = null
            setServiceInfo(info)
            captureConfigurationPackage = null
            captureConfigured = false
            CaptureTraceLog.stage(
                latestCaptureState.activeSession?.id,
                "capture_configuration_restored",
                "eventTypes" to DEFAULT_EVENT_TYPES,
            )
        }.onSuccess {
            traceConfiguration("default_configuration_applied", DEFAULT_EVENT_TYPES)
        }.onFailure { error ->
            traceConfiguration("default_configuration_failed", DEFAULT_EVENT_TYPES, error)
        }
    }

    private fun traceConfiguration(name: String, requestedTypes: Int, error: Throwable? = null) {
        if (!BuildConfig.DEBUG) return
        val actual = runCatching { serviceInfo }.getOrNull()
        CaptureTraceLog.boundary(latestCaptureState.activeSession?.id, name,
            "requestedTypes" to requestedTypes, "hasServiceInfo" to (actual != null),
            "actualTypes" to actual?.eventTypes, "flags" to actual?.flags,
            "error" to error?.let(CaptureTraceError::from))
    }

    private fun scheduleExpiry(state: RuleCaptureState) {
        captureExpiryJob?.cancel()
        val session = state.activeSession?.takeUnless { it.phase == CapturePhase.FAILED } ?: return
        captureExpiryJob = serviceScope?.launch {
            val key = CaptureExpiryKey.from(session)
            while (true) {
                val plan = container.ruleCaptureCoordinator.expiryPlan(key) ?: return@launch
                delay((plan.deadlineAtElapsedMs - AndroidMonotonicClock.elapsedRealtimeMs()).coerceAtLeast(0))
                val targetWindowDiagnostics = targetWindowDiagnosticsTracker.snapshot(
                    expectedSessionId = session.id,
                    nowElapsedMs = AndroidMonotonicClock.elapsedRealtimeMs(),
                )
                val failureDiagnostics = captureAdapter?.timeoutDiagnostics(session.id)
                val result = container.ruleCaptureCoordinator.expireExpected(
                    key,
                    targetWindowDiagnostics = targetWindowDiagnostics,
                    failureDiagnostics = failureDiagnostics,
                )
                if (result != CaptureOperationResult.Pending) return@launch
            }
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
        CaptureTraceLog.boundary(latestCaptureState.activeSession?.id, "service_interrupted")
        latestCaptureState.activeSession?.let {
            traceCaptureSummary(it, CaptureTraceConclusion.SERVICE_INTERRUPTED)
        }
        disconnectCaptureWithDiagnostics()
    }

    override fun onDestroy() {
        CaptureTraceLog.boundary(latestCaptureState.activeSession?.id, "service_destroyed")
        latestCaptureState.activeSession?.let {
            traceCaptureSummary(it, CaptureTraceConclusion.SERVICE_DESTROYED)
        }
        disconnectCaptureWithDiagnostics()
        diagnosticsProviderRegistrationId?.let {
            container.ruleCaptureCoordinator.unregisterReturnDiagnosticsProvider(it)
        }
        diagnosticsProviderRegistrationId = null
        container.serviceConnections.setConnected(false)
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
        pendingForegroundWindow.clear()
        latestCaptureState = RuleCaptureState()
        captureConfigurationPackage = null
        captureConfigured = false
        automationPaused = false
        targetWindowDiagnosticsTracker.reset()
        CaptureTraceLog.state(null)
        super.onDestroy()
    }

    private fun disconnectCaptureWithDiagnostics() {
        pendingForegroundWindow.clear()
        latestIdentity = null
        val sessionId = latestCaptureState.activeSession?.id ?: return
        val now = AndroidMonotonicClock.elapsedRealtimeMs()
        val diagnostics = CaptureDiagnosticSnapshot(
            failure = captureAdapter?.timeoutDiagnostics(sessionId),
            targetWindow = targetWindowDiagnosticsTracker.snapshot(
                expectedSessionId = sessionId,
                nowElapsedMs = now,
            ),
        )
        container.ruleCaptureCoordinator.serviceDisconnected(sessionId, diagnostics)
    }

    private companion object {
        const val DEFAULT_EVENT_TYPES = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        const val CAPTURE_EVENT_TYPES = DEFAULT_EVENT_TYPES or AccessibilityEvent.TYPE_VIEW_CLICKED
        const val SYSTEM_UI_PACKAGE = "com.android.systemui"
    }

    private fun currentTargetPackages(): Set<String> =
        container.ruleCatalog.snapshot.value.targetPackages

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
