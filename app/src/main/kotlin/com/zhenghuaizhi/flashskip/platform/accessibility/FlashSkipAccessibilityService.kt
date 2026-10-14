package com.zhenghuaizhi.flashskip.platform.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.zhenghuaizhi.flashskip.application.rulecapture.CaptureDiagnosticSnapshot
import com.zhenghuaizhi.flashskip.FlashSkipApplication
import com.zhenghuaizhi.flashskip.AppContainer
import com.zhenghuaizhi.flashskip.domain.automation.AutomationEngine
import com.zhenghuaizhi.flashskip.domain.automation.ForegroundSignal
import com.zhenghuaizhi.flashskip.domain.model.CaptureFailure
import com.zhenghuaizhi.flashskip.domain.model.CaptureEventTiming
import com.zhenghuaizhi.flashskip.domain.model.CapturePhase
import com.zhenghuaizhi.flashskip.domain.model.CaptureSession
import com.zhenghuaizhi.flashskip.domain.model.CaptureSample
import com.zhenghuaizhi.flashskip.domain.model.RuleCaptureState
import com.zhenghuaizhi.flashskip.domain.model.TargetWindowRecognitionOutcome
import com.zhenghuaizhi.flashskip.domain.ports.PageIdentity
import com.zhenghuaizhi.flashskip.domain.ports.PageGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import java.io.IOException
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.first
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
    private var captureObservationLoop: CaptureObservationLoop? = null
    private var captureIgnoredPackageNames: Set<String>? = null
    private var captureIgnoredPackagesSessionId: Long? = null
    private var deferredCaptureWindow: DeferredCaptureWindow? = null
    private var diagnosticsProviderRegistrationId: Long? = null
    private var captureConfigurationPackage: String? = null
    private var captureConfigured = false
    private var automationPaused: Boolean? = null
    private var serviceInterrupted = false
    private var serviceStateReady = false
    internal var runtimeFactory: (() -> AppContainer)? = null
    internal var automationEngineFactory: ((AppContainer, PageGateway, CoroutineScope) -> AutomationEngine)? = null
    private val targetWindowDiagnosticsTracker = TargetWindowDiagnosticsTracker()
    private var pendingVerifiedWindowPackage: String? = null
    private val pendingForegroundWindow = PendingForegroundWindow()

    @Volatile
    private var latestIdentity: PageIdentity? = null

    @Volatile
    private var latestCaptureState = RuleCaptureState()

    private val container by lazy {
        runtimeFactory?.invoke() ?: (application as FlashSkipApplication).container
    }

    override fun onCreate() {
        super.onCreate()
        CaptureTraceLog.lifecycle("service_created")
    }

    override fun onServiceConnected() {
        serviceStateReady = false
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
        captureObservationLoop?.cancel()
        captureIgnoredPackageNames = null
        captureIgnoredPackagesSessionId = null
        deferredCaptureWindow = null
        latestIdentity = null
        pendingForegroundWindow.clear()
        latestCaptureState = RuleCaptureState()
        serviceInterrupted = false
        automationPaused = null
        targetWindowDiagnosticsTracker.reset()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val gateway = AccessibilityPageGateway(this, ::currentTargetPackages, container.monotonicClock) { latestIdentity }
        serviceScope = scope
        windowClassifier = ForegroundWindowClassifier(
            targetPackages = ::currentTargetPackages,
            applicationWindowType = AccessibilityWindowInfo.TYPE_APPLICATION,
        )
        eventPackageResolver = AccessibilityEventPackageResolver(this)
        engine = automationEngineFactory?.invoke(container, gateway, scope) ?: container.createAutomationEngine(
            pageGateway = gateway,
            monotonicClock = container.monotonicClock,
            wallClock = container.wallClock,
            scope = scope,
        )
        // 新引擎在首份持久化状态到达前保持暂停；旧服务的缓存不能代替这次同步。
        scope.launch { setAutomationPaused(true) }
        captureAdapter = AccessibilityCaptureAdapter(
            service = this,
            coordinator = container.ruleCaptureCoordinator,
            scope = scope,
            stateProvider = { latestCaptureState },
            targetWindowDiagnosticsProvider = { sessionId ->
                targetWindowDiagnosticsTracker.snapshot(
                    expectedSessionId = sessionId,
                    nowElapsedMs = container.monotonicClock.elapsedRealtimeMs(),
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
            windowVerifiedRecorder = { sessionId, verifiedAtElapsedMs ->
                pendingVerifiedWindowPackage?.takeIf {
                    it == captureAdapter?.verifiedHostPackageName()
                }?.let {
                    targetWindowDiagnosticsTracker.confirmTargetPackage(sessionId, it)
                    captureAdapter?.verifiedHostWindowId()?.let { windowId ->
                        targetWindowDiagnosticsTracker.confirmStartupTarget(sessionId, it, windowId)
                    }
                }
                if (targetWindowDiagnosticsTracker.recordVerifiedWindow(
                        sessionId,
                        verifiedAtElapsedMs,
                        SystemClock.uptimeMillis(),
                    )
                ) {
                    targetWindowDiagnosticsTracker.timingSnapshot(sessionId)?.let {
                        CaptureTraceLog.timing(sessionId, it)
                    }
                }
            },
            scanStartedRecorder = { sessionId, scanStartedAtElapsedMs ->
                if (targetWindowDiagnosticsTracker.recordFirstScan(sessionId, scanStartedAtElapsedMs)) {
                    targetWindowDiagnosticsTracker.timingSnapshot(sessionId)?.let {
                        CaptureTraceLog.timing(sessionId, it)
                    }
                }
            },
            pageObservationRecorder = { sessionId, page ->
                targetWindowDiagnosticsTracker.recordStartupObservation(sessionId, page)
                CaptureTraceLog.startupPage(sessionId, page)
            },
        )
        captureObservationLoop = CaptureObservationLoop(
            scope, container.captureObserverDispatcher, { latestCaptureState.activeSession },
            { captureAdapter?.verifiedHostPackageName() != null },
            loadIgnoredPackages = {
                val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                // 每次会话只在后台查询一次桌面集合，避免同步包管理查询占据事件热路径。
                val homes = packageManager.queryIntentActivities(homeIntent, 0)
                    .mapNotNull { it.activityInfo?.packageName }.toSet()
                check(homes.isNotEmpty())
                homes + setOf(packageName, SYSTEM_UI_PACKAGE)
            },
            onPrepared = { sessionId, ignored ->
                captureIgnoredPackagesSessionId = sessionId
                captureIgnoredPackageNames = ignored
                CaptureTraceLog.boundary(sessionId, "startup_observer_ready")
                replayDeferredCaptureWindow(sessionId, ignored)
            },
            observerFactory = { AccessibilityStartupObserver(this)::inspect },
            onObservation = { sessionId, page ->
                targetWindowDiagnosticsTracker.recordStartupObservation(sessionId, page)
                CaptureTraceLog.startupPage(sessionId, page)
            },
            onVerifiedTick = { captureAdapter?.onActiveWindowObservation(it) },
            onUnavailable = { CaptureTraceLog.boundary(it, "startup_observer_unavailable") },
            elapsedClock = container.monotonicClock::elapsedRealtimeMs,
        )
        diagnosticsProviderRegistrationId = container.ruleCaptureCoordinator
            .registerReturnDiagnosticsProvider { sessionId ->
                CaptureDiagnosticSnapshot(
                    failure = captureAdapter?.timeoutDiagnostics(sessionId),
                    targetWindow = targetWindowDiagnosticsTracker.snapshot(
                        expectedSessionId = sessionId,
                        nowElapsedMs = container.monotonicClock.elapsedRealtimeMs(),
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
        if (serviceInterrupted || !serviceStateReady) return
        val safeEvent = event ?: return
        val captureSession = latestCaptureState.activeSession
        if (captureSession?.blocksAutomation == true) {
            pendingForegroundWindow.clear()
            val type = safeEvent.eventType
            if (type !in CAPTURE_RELEVANT_EVENT_TYPES) return
            val listening = captureSession.phase == CapturePhase.WAITING_FOR_TARGET ||
                captureSession.phase == CapturePhase.OBSERVING_CLICK ||
                (captureSession.listensForCandidateClick && type == AccessibilityEvent.TYPE_VIEW_CLICKED)
            // CONFIGURING 的早期事件只记时序，不进入窗口解析或节点读取。
            if (!listening && captureSession.phase != CapturePhase.CONFIGURING) return
            val receivedTiming = CaptureEventTimingRecorder.sample(
                eventUptimeMs = safeEvent.eventTime,
                receivedUptimeMs = SystemClock.uptimeMillis(),
                receivedElapsedMs = container.monotonicClock.elapsedRealtimeMs(),
                receivedEpochMs = container.wallClock.currentTimeMillis(),
            )
            val reportedPackageName = safeEvent.packageName?.toString()
            val selectedPackages = listOfNotNull(
                captureSession.targetAppId?.packageName,
                captureSession.packageName,
                captureAdapter?.verifiedHostPackageName(),
            )
            val timing = targetWindowDiagnosticsTracker.recordCaptureEvent(
                expectedSessionId = captureSession.id,
                sample = receivedTiming,
                isTarget = reportedPackageName != SYSTEM_UI_PACKAGE &&
                    reportedPackageName != null && reportedPackageName in selectedPackages,
                isClick = type == AccessibilityEvent.TYPE_VIEW_CLICKED,
                reportedPackageName = reportedPackageName?.takeIf {
                    selectedPackages.isEmpty() && it != SYSTEM_UI_PACKAGE
                },
            )
            if (!listening) {
                if (reportedPackageName == null) targetWindowDiagnosticsTracker.recordMissingPackage()
                CaptureTraceLog.event(
                    captureSession,
                    safeEvent,
                    CaptureTraceEventRoute.IGNORED,
                    CaptureTraceReason.CAPTURE_PHASE_NOT_LISTENING,
                    timing = timing,
                )
                return
            }
            handleCaptureEvent(safeEvent, captureSession, timing)
            return
        }
        when (safeEvent.eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> handleContentChanged(safeEvent)
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> handleWindowStateChanged(safeEvent)
            else -> Unit
        }
    }

    private fun handleCaptureEvent(
        event: AccessibilityEvent,
        session: CaptureSession,
        timing: CaptureEventTiming,
    ) {
        val occurredAt = timing.receivedElapsedMs
        val reportedPackageName = event.packageName?.toString() ?: run {
            targetWindowDiagnosticsTracker.recordMissingPackage()
            CaptureTraceLog.event(session, event, CaptureTraceEventRoute.IGNORED, CaptureTraceReason.MISSING_PACKAGE, timing)
            return
        }
        val verifiedHostPackage = captureAdapter?.verifiedHostPackageName()
        val packageName = if (reportedPackageName == SYSTEM_UI_PACKAGE) {
            verifiedHostPackage ?: run {
                targetWindowDiagnosticsTracker.recordIgnoredPackage()
                CaptureTraceLog.event(session, event, CaptureTraceEventRoute.IGNORED, CaptureTraceReason.SYSTEM_UI_WITHOUT_VERIFIED_HOST, timing)
                return
            }
        } else {
            reportedPackageName
        }
        val lockedPackage = session.packageName ?: verifiedHostPackage
        if (lockedPackage != null && packageName != lockedPackage) {
            targetWindowDiagnosticsTracker.recordIgnoredPackage()
            CaptureTraceLog.event(session, event, CaptureTraceEventRoute.IGNORED, CaptureTraceReason.LOCKED_PACKAGE_MISMATCH, timing)
            return
        }
        val ignoredPackages = captureIgnoredPackageNames?.takeIf { captureIgnoredPackagesSessionId == session.id }
        if (lockedPackage == null && ignoredPackages == null) {
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                deferredCaptureWindow = DeferredCaptureWindow(session.id, packageName,
                    event.className?.toString()?.take(240), event.windowId, occurredAt)
            }
            CaptureTraceLog.event(session, event, CaptureTraceEventRoute.IGNORED, CaptureTraceReason.HOST_NOT_VERIFIED, timing)
            return
        }
        if (lockedPackage == null && packageName in ignoredPackages.orEmpty()) {
            targetWindowDiagnosticsTracker.recordIgnoredPackage()
            CaptureTraceLog.event(session, event, CaptureTraceEventRoute.IGNORED, CaptureTraceReason.PACKAGE_IGNORED_BEFORE_TARGET, timing)
            return
        }
        // 未建立经验证宿主前，内容和点击事件不能触发任何节点读取。
        if (lockedPackage == null &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) {
            targetWindowDiagnosticsTracker.recordIgnoredPackage()
            CaptureTraceLog.event(session, event, CaptureTraceEventRoute.IGNORED, CaptureTraceReason.HOST_NOT_VERIFIED, timing)
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
            timing = timing,
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
                pendingVerifiedWindowPackage = reportedPackageName.takeIf { it != SYSTEM_UI_PACKAGE }
                try {
                    captureAdapter?.onVerifiedTargetWindow(
                        event = event,
                        packageName = packageName,
                        targetAppName = appName,
                        activityName = activityName,
                    )
                } finally {
                    pendingVerifiedWindowPackage = null
                }
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
        engine?.onContentChanged(identity, container.monotonicClock.elapsedRealtimeMs())
    }

    private fun handleWindowStateChanged(event: AccessibilityEvent) {
        val reportedPackageName = event.packageName?.toString() ?: return
        val windowId = event.windowId
        val packageName = effectiveSystemReportedPackageName(reportedPackageName, windowId)
        val occurredAt = container.monotonicClock.elapsedRealtimeMs()
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
        if (latestCaptureState.activeSession?.blocksAutomation == true || automationPaused != false) {
            pendingForegroundWindow.clear()
            return
        }
        val pending = pendingForegroundWindow.takeIfFresh(
            container.monotonicClock.elapsedRealtimeMs(), currentTargetPackages(),
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
            combine(
                container.ruleCaptureCoordinator.reads,
                container.store.reads.map { it.available }.distinctUntilChanged(),
            ) { capture, preferencesAvailable -> capture to preferencesAvailable }.onCompletion { error ->
                CaptureTraceLog.boundary(latestCaptureState.activeSession?.id, "service_capture_observer_completed",
                    "failed" to (error != null), "error" to error?.let(CaptureTraceError::from))
            }.collect { (read, preferencesAvailable) ->
                if (!read.available || !preferencesAvailable) {
                    firstEmission = true
                    blockForUnavailableStorage()
                    return@collect
                }
                try {
                    var state = read.requireAvailable()
                    if (firstEmission) CaptureTraceLog.boundary(state.activeSession?.id, "service_capture_first_state",
                        "hasSession" to (state.activeSession != null), "phase" to state.activeSession?.phase)
                    if (firstEmission) {
                        state.activeSession?.takeUnless { it.phase == CapturePhase.FAILED }?.let { session ->
                            setAutomationPaused(true)
                            val restored = container.ruleCaptureCoordinator.restoreServiceSession(session.id)
                            if (restored is CaptureOperationResult.Failure) {
                                // 关闭监听的持久化没有提交时，不消费旧快照继续采集。
                                restoreDefaultEventConfiguration()
                                captureObservationLoop?.cancel()
                                captureAdapter?.cancelPendingWindowResolution()
                                return@collect
                            }
                            // 有效载荷及 CONFIGURING 的恢复可能不写状态，必须直接推进这次配置。
                            state = container.ruleCaptureCoordinator.state.first()
                        }
                        firstEmission = false
                    }
                    // 必须先输出旧上下文，再让采集器和窗口计数器处理新会话并清空缓冲区。
                    traceCaptureTransition(latestCaptureState, state)
                    latestCaptureState = state
                    CaptureTraceLog.state(state.activeSession)
                    targetWindowDiagnosticsTracker.onSessionChanged(state.activeSession?.id)
                    captureAdapter?.onStateChanged(state)
                    scheduleExpiry(state)
                    val session = state.activeSession
                    if (serviceInterrupted) {
                        restoreDefaultEventConfiguration()
                        setAutomationPaused(true)
                        return@collect
                    }
                    captureObservationLoop?.update(session)
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
                            setAutomationPaused(true)
                            if (session.listensForCandidateClick) {
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
                    serviceStateReady = true
                } catch (_: IOException) {
                    // 恢复期间的再次读取也可能失败，不能让旧的可用标记继续放行事件。
                    firstEmission = true
                    blockForUnavailableStorage()
                }
            }
        }
    }

    private suspend fun blockForUnavailableStorage() {
        serviceStateReady = false
        setAutomationPaused(true)
        captureExpiryJob?.cancel()
        captureObservationLoop?.cancel()
        captureAdapter?.cancelPendingWindowResolution()
        deferredCaptureWindow = null
        restoreDefaultEventConfiguration()
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
                    nowElapsedMs = container.monotonicClock.elapsedRealtimeMs(),
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
        if (serviceInterrupted) return
        val session = state.activeSession?.takeUnless { it.phase == CapturePhase.FAILED } ?: return
        captureExpiryJob = serviceScope?.launch {
            val key = CaptureExpiryKey.from(session)
            while (true) {
                val plan = try {
                    container.ruleCaptureCoordinator.expiryPlan(key)
                } catch (_: IOException) {
                    // 当前会话已经暂停自动执行；等待有效存储发射重新安排期限。
                    return@launch
                } ?: return@launch
                delay((plan.deadlineAtElapsedMs - container.monotonicClock.elapsedRealtimeMs()).coerceAtLeast(0))
                val targetWindowDiagnostics = targetWindowDiagnosticsTracker.snapshot(
                    expectedSessionId = session.id,
                    nowElapsedMs = container.monotonicClock.elapsedRealtimeMs(),
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

    /** 桌面集合准备期间只暂存事件标量，重放时重新验证当前活动窗口与 Activity。 */
    private fun replayDeferredCaptureWindow(sessionId: Long, ignored: Set<String>) {
        val pending = deferredCaptureWindow
        deferredCaptureWindow = null
        val session = latestCaptureState.activeSession ?: return
        if (pending == null || pending.sessionId != sessionId || session.id != sessionId ||
            session.phase != CapturePhase.WAITING_FOR_TARGET || pending.packageName in ignored ||
            captureAdapter?.verifiedHostPackageName() != null
        ) return
        val matching = snapshotWindowMetadata()?.filter { it.id == pending.windowId }?.singleOrNull() ?: return
        if (!matching.isActive || matching.type != AccessibilityWindowInfo.TYPE_APPLICATION) return
        val activity = container.targetAppVersionReader.verifiedActivity(pending.packageName, pending.className) ?: return
        val name = container.targetAppVersionReader.displayNameFor(pending.packageName) ?: pending.packageName
        targetWindowDiagnosticsTracker.recordWindowState(pending.packageName, pending.className,
            pending.windowId, null, 1, matching.type, true, TargetWindowRecognitionOutcome.VERIFIED,
            pending.receivedAtElapsedMs)
        CaptureTraceLog.boundary(sessionId, "startup_deferred_window_replayed", "windowId" to pending.windowId)
        pendingVerifiedWindowPackage = pending.packageName
        try {
            captureAdapter?.onVerifiedTargetWindow(null, pending.packageName, name, activity, pending.windowId)
        } finally { pendingVerifiedWindowPackage = null }
    }

    private data class DeferredCaptureWindow(
        val sessionId: Long,
        val packageName: String,
        val className: String?,
        val windowId: Int,
        val receivedAtElapsedMs: Long,
    )

    override fun onInterrupt() {
        CaptureTraceLog.boundary(latestCaptureState.activeSession?.id, "service_interrupted")
        latestCaptureState.activeSession?.let {
            traceCaptureSummary(it, CaptureTraceConclusion.SERVICE_INTERRUPTED)
        }
        captureObservationLoop?.cancel()
        captureExpiryJob?.cancel()
        captureExpiryJob = null
        serviceInterrupted = true
        serviceStateReady = false
        captureAdapter?.cancelPendingWindowResolution()
        restoreDefaultEventConfiguration()
        serviceScope?.launch { setAutomationPaused(true) }
        disconnectCaptureWithDiagnostics()
    }

    override fun onDestroy() {
        CaptureTraceLog.boundary(latestCaptureState.activeSession?.id, "service_destroyed")
        latestCaptureState.activeSession?.let {
            traceCaptureSummary(it, CaptureTraceConclusion.SERVICE_DESTROYED)
        }
        disconnectCaptureWithDiagnostics()
        serviceInterrupted = true
        serviceStateReady = false
        captureAdapter?.cancelPendingWindowResolution()
        diagnosticsProviderRegistrationId?.let {
            container.ruleCaptureCoordinator.unregisterReturnDiagnosticsProvider(it)
        }
        diagnosticsProviderRegistrationId = null
        container.serviceConnections.setConnected(false)
        restoreDefaultEventConfiguration()
        captureExpiryJob?.cancel()
        captureExpiryJob = null
        captureObservationLoop?.cancel()
        captureObservationLoop = null
        captureIgnoredPackageNames = null
        captureIgnoredPackagesSessionId = null
        deferredCaptureWindow = null
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
        automationPaused = null
        targetWindowDiagnosticsTracker.reset()
        CaptureTraceLog.state(null)
        super.onDestroy()
    }

    private fun disconnectCaptureWithDiagnostics() {
        pendingForegroundWindow.clear()
        latestIdentity = null
        val sessionId = latestCaptureState.activeSession?.id ?: return
        val now = container.monotonicClock.elapsedRealtimeMs()
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
        val CAPTURE_RELEVANT_EVENT_TYPES = setOf(
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
        )
        const val SYSTEM_UI_PACKAGE = "com.android.systemui"
    }

    private fun currentTargetPackages(): Set<String> =
        container.ruleCatalog.snapshot.value.targetPackages

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
