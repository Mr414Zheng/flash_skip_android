package com.zhenghuaizhi.flashskip.platform.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.zhenghuaizhi.flashskip.application.rulecapture.RuleCaptureCoordinator
import com.zhenghuaizhi.flashskip.domain.model.CaptureFailure
import com.zhenghuaizhi.flashskip.domain.model.CaptureFailureDiagnostics
import com.zhenghuaizhi.flashskip.domain.model.CapturePhase
import com.zhenghuaizhi.flashskip.domain.model.CaptureSample
import com.zhenghuaizhi.flashskip.domain.model.CapturedSelector
import com.zhenghuaizhi.flashskip.domain.model.ClickRecoveryDiagnostics
import com.zhenghuaizhi.flashskip.domain.model.ObservedDismissCandidate
import com.zhenghuaizhi.flashskip.domain.model.RuleCaptureState
import com.zhenghuaizhi.flashskip.domain.model.SnapshotRecoveryDiagnostics
import com.zhenghuaizhi.flashskip.domain.model.TargetAppId
import com.zhenghuaizhi.flashskip.domain.model.TargetWindowDiagnostics
import com.zhenghuaizhi.flashskip.domain.model.TargetWindowRecognitionOutcome
import com.zhenghuaizhi.flashskip.domain.ports.PageIdentity
import com.zhenghuaizhi.flashskip.domain.rulecapture.ClickSemanticHint
import com.zhenghuaizhi.flashskip.domain.rulecapture.RuleCandidateAnalyzer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal class AccessibilityCaptureAdapter(
    private val service: AccessibilityService,
    private val coordinator: RuleCaptureCoordinator,
    private val scope: CoroutineScope,
    private val stateProvider: () -> RuleCaptureState,
    private val targetWindowDiagnosticsProvider: (Long) -> TargetWindowDiagnostics?,
    private val targetWindowResolutionRecorder: (
        packageName: String,
        windowId: Int,
        outcome: TargetWindowRecognitionOutcome,
        isRetry: Boolean,
        occurredAtElapsedMs: Long,
    ) -> Unit,
) {
    private var verifiedHostIdentity: PageIdentity? = null
    private var currentScanIdentity: PageIdentity? = null
    private var latestUniqueSelectors: Set<CapturedSelector> = emptySet()
    private var lockedSessionId: Long? = null
    private var observedSessionId: Long? = null
    private var verifiedTargetEnteredAtElapsedMs: Long? = null
    private var currentTargetAppName: String? = null
    private var lastWindowVerifiedAtElapsedMs: Long? = null
    private var lastContentChangedAtElapsedMs: Long? = null
    private var currentWindowAllowsObservedCandidate = false
    private val semanticSnapshotter = AccessibilitySemanticSnapshotter(service)
    private val probe = AccessibilityCaptureProbe(
        service = service,
        targetPackages = {
            val packageName = stateProvider().activeSession?.packageName
                ?: verifiedHostIdentity?.packageName
            packageName?.let(::setOf).orEmpty()
        },
        currentIdentity = { currentScanIdentity },
        semanticSnapshotter = semanticSnapshotter,
    )
    private val preClickRecovery = AccessibilityPreClickRecovery(
        elapsedRealtimeMs = coordinator::elapsedRealtimeMs,
        probe = probe,
    )
    private val windowResolutionSession = CaptureWindowResolutionSession(
        service = service,
        scope = scope,
        elapsedRealtimeMs = coordinator::elapsedRealtimeMs,
        retryRequestProvider = ::retryRequestFor,
        onRetryResolved = { request, resolved ->
            acceptResolvedRoot(
                state = stateProvider(),
                identity = request.host.toPageIdentity(request.eventWindowId),
                resolved = resolved,
                kind = CaptureEventKind.RETRY,
                clickEvent = null,
            )
        },
        recordResolution = targetWindowResolutionRecorder,
    )

    fun verifiedHostPackageName(): String? = verifiedHostIdentity?.packageName

    fun onStateChanged(state: RuleCaptureState) {
        val session = state.activeSession
        if (session?.id != observedSessionId) {
            observedSessionId = session?.id
            lockedSessionId = null
            verifiedHostIdentity = null
            currentScanIdentity = null
            verifiedTargetEnteredAtElapsedMs = null
            currentTargetAppName = null
            latestUniqueSelectors = emptySet()
            lastWindowVerifiedAtElapsedMs = null
            lastContentChangedAtElapsedMs = null
            currentWindowAllowsObservedCandidate = false
            windowResolutionSession.cancel()
            preClickRecovery.clear()
        }
        if (session == null || session.phase in setOf(
                CapturePhase.CONFIGURING,
                CapturePhase.WAITING_FOR_TARGET,
                CapturePhase.AWAITING_CONFIRMATION,
                CapturePhase.CAPTURED,
                CapturePhase.FAILED,
            )
        ) {
            verifiedHostIdentity = null
            currentScanIdentity = null
            verifiedTargetEnteredAtElapsedMs = null
            currentTargetAppName = null
            latestUniqueSelectors = emptySet()
            lastWindowVerifiedAtElapsedMs = null
            lastContentChangedAtElapsedMs = null
            currentWindowAllowsObservedCandidate = false
            windowResolutionSession.cancel()
            preClickRecovery.clear()
        }
    }

    fun timeoutDiagnostics(sessionId: Long): CaptureFailureDiagnostics? {
        if (observedSessionId != sessionId) return null
        val preClickProbe = preClickRecovery.latestProbeDiagnostics ?: return null
        val now = coordinator.elapsedRealtimeMs()
        return CaptureFailureDiagnostics(
            sourceAvailable = false,
            eventTextCount = 0,
            eventHasContentDescription = false,
            preClickProbe = preClickProbe,
            lastWindowVerifiedAgeMs = elapsedSince(now, lastWindowVerifiedAtElapsedMs),
            lastContentChangedAgeMs = elapsedSince(now, lastContentChangedAtElapsedMs),
        )
    }

    fun onVerifiedTargetWindow(
        event: AccessibilityEvent,
        packageName: String,
        targetAppName: String,
        activityName: String,
    ) {
        val state = stateProvider()
        val session = state.activeSession ?: return
        if (session.packageName != null && packageName != session.packageName) return
        // 状态落盘前先在回调栈内锁住首次验证包，避免快速切换应用改写本次目标。
        if (session.packageName == null &&
            verifiedHostIdentity?.packageName?.let { it != packageName } == true
        ) {
            return
        }
        if (session.phase !in setOf(CapturePhase.WAITING_FOR_TARGET, CapturePhase.OBSERVING_CLICK)) {
            return
        }
        val now = coordinator.elapsedRealtimeMs()
        val enteredAt = session.targetEnteredAtElapsedMs ?: now
        val identity = PageIdentity(
            packageName = packageName,
            activityName = activityName,
            windowId = event.windowId,
            appVersion = null,
        )
        if (verifiedHostIdentity != null && verifiedHostIdentity != identity) {
            windowResolutionSession.cancel()
            currentScanIdentity = null
            latestUniqueSelectors = emptySet()
            preClickRecovery.clear()
        }
        verifiedHostIdentity = identity
        currentScanIdentity = null
        currentTargetAppName = targetAppName
        currentWindowAllowsObservedCandidate = false
        verifiedTargetEnteredAtElapsedMs = enteredAt
        lastWindowVerifiedAtElapsedMs = now
        lastContentChangedAtElapsedMs = null
        resolveEventWindow(state, event, packageName, CaptureEventKind.WINDOW_STATE)
        scope.launch {
            coordinator.observeTarget(
                sessionId = session.id,
                targetAppId = TargetAppId(packageName),
                targetAppName = targetAppName,
                packageName = packageName,
                activityName = activityName,
                windowId = event.windowId,
                enteredAtElapsedMs = enteredAt,
            )
        }
    }

    fun onTransientTargetWindow(
        event: AccessibilityEvent,
        packageName: String,
    ) {
        val state = stateProvider()
        val session = state.activeSession ?: return
        if (session.phase !in setOf(CapturePhase.WAITING_FOR_TARGET, CapturePhase.OBSERVING_CLICK) ||
            lockedSessionId == session.id
        ) {
            return
        }
        if (session.packageName != null && packageName != session.packageName) return
        val ownerIdentity = verifiedHostIdentity?.takeIf {
            it.packageName == packageName && it.activityName?.isNotBlank() == true
        } ?: return
        resolveEventWindow(state, event, ownerIdentity.packageName, CaptureEventKind.WINDOW_STATE)
    }

    fun onContentChanged(event: AccessibilityEvent, packageName: String) {
        val state = stateProvider()
        val session = state.activeSession ?: return
        if (session.phase != CapturePhase.OBSERVING_CLICK) return
        if (session.packageName != null && packageName != session.packageName) return
        if (verifiedHostIdentity?.packageName != packageName) return
        lastContentChangedAtElapsedMs = coordinator.elapsedRealtimeMs()
        resolveEventWindow(state, event, packageName, CaptureEventKind.CONTENT_CHANGED)
    }

    fun onClicked(event: AccessibilityEvent, packageName: String) {
        val state = stateProvider()
        val session = state.activeSession ?: return
        if (session.phase !in setOf(
                CapturePhase.WAITING_FOR_TARGET,
                CapturePhase.OBSERVING_CLICK,
            ) || lockedSessionId == session.id
        ) {
            return
        }
        if (session.packageName != null && packageName != session.packageName) return
        if (verifiedHostIdentity?.packageName != packageName) return
        resolveEventWindow(state, event, packageName, CaptureEventKind.VIEW_CLICKED)
    }

    @Suppress("DEPRECATION")
    private fun captureResolvedClick(
        state: RuleCaptureState,
        event: AccessibilityEvent,
        packageName: String,
        identity: PageIdentity,
        verifiedRoot: AccessibilityNodeInfo,
    ) {
        val session = state.activeSession ?: return
        lockedSessionId = session.id
        val hint = ClickSemanticHint(
            texts = event.text.mapNotNull { value ->
                semanticSnapshotter.sanitizeSemantic(value.toString())
            }.toSet(),
            contentDescription = semanticSnapshotter.sanitizeSemantic(
                event.contentDescription?.toString(),
            ),
            allowBareNumericCountdown = currentWindowAllowsObservedCandidate,
        )
        val eventSource = event.source
        val source = eventSource?.takeIf {
            it.packageName?.toString() == packageName && it.windowId == identity.windowId
        }
        if (eventSource != null && source == null) eventSource.recycle()
        val nodes = if (source != null) {
            semanticSnapshotter.copySemanticChain(source, packageName)
        } else {
            // 点击回调到达时按钮可能已经消失；即时恢复失败后仅使用同窗口的近期唯一快照。
            val immediate = probe.recoverClickedSemanticChainOnVerifiedRoot(
                expected = identity,
                verifiedRoot = verifiedRoot,
                hint = hint,
            )
            val immediateSuccess = immediate as? AccessibilityCaptureProbe.RecoveryResult.Success
            if (immediateSuccess != null) {
                immediateSuccess.nodes
            } else {
                val snapshot = preClickRecovery.recover(
                    identity = identity,
                    hint = hint,
                    allowBareNumericCountdown = currentWindowAllowsObservedCandidate,
                )
                snapshot.nodes ?: run {
                    fail(
                        sessionId = session.id,
                        failure = CaptureFailure.CLICK_SOURCE_UNAVAILABLE,
                        diagnostics = clickFailureDiagnostics(
                            sourceAvailable = false,
                            hint = hint,
                            immediateRecovery = immediate.diagnostics,
                            snapshotRecovery = snapshot.diagnostics,
                        ),
                    )
                    return
                }
            }
        }
        val candidates = RuleCandidateAnalyzer.selectorCandidates(nodes)
        if (candidates.isEmpty()) {
            fail(
                sessionId = session.id,
                failure = CaptureFailure.CLICK_SEMANTICS_UNAVAILABLE,
                diagnostics = clickFailureDiagnostics(
                    sourceAvailable = source != null,
                    hint = hint,
                    copiedNodeCount = nodes.size,
                    selectorCandidateCount = candidates.size,
                ),
            )
            return
        }
        // 窗口已同步验证但状态仍在异步落盘时，也必须接住用户的首次快速点击。
        val enteredAt = session.targetEnteredAtElapsedMs ?: verifiedTargetEnteredAtElapsedMs
        val activityName = identity.activityName
        if (enteredAt == null || activityName.isNullOrBlank()) {
            fail(session.id, CaptureFailure.ACTIVITY_UNAVAILABLE)
            return
        }
        val sample = CaptureSample(
            id = session.id,
            targetAppId = session.targetAppId ?: TargetAppId(packageName),
            targetAppName = session.targetAppName ?: currentTargetAppName ?: packageName,
            activityName = activityName.take(MAX_ACTIVITY_LENGTH),
            clickedAfterTargetMs = (coordinator.elapsedRealtimeMs() - enteredAt).coerceAtLeast(0),
            capturedAtEpochMs = coordinator.currentTimeMillis(),
            nodes = nodes,
            preClickUniqueSelectors = candidates.filter { it in latestUniqueSelectors },
        )
        scope.launch { coordinator.recordCaptured(session.id, sample) }
    }

    private fun resolveEventWindow(
        state: RuleCaptureState,
        event: AccessibilityEvent,
        packageName: String,
        kind: CaptureEventKind,
    ) {
        val session = state.activeSession ?: return
        val request = requestFor(session.id, packageName, event.windowId) ?: return
        windowResolutionSession.resolve(request, event) { resolved ->
            acceptResolvedRoot(
                state = state,
                identity = request.host.toPageIdentity(event.windowId),
                resolved = resolved,
                kind = kind,
                clickEvent = event.takeIf { kind == CaptureEventKind.VIEW_CLICKED },
            )
        }
    }

    private fun acceptResolvedRoot(
        state: RuleCaptureState,
        identity: PageIdentity,
        resolved: AccessibilityCaptureWindowResolver.ResolvedRoot,
        kind: CaptureEventKind,
        clickEvent: AccessibilityEvent?,
    ) {
        val deadlineAtElapsedMs = resolved.deadlineAtElapsedMs
        if (deadlineAtElapsedMs != null &&
            coordinator.elapsedRealtimeMs() >= deadlineAtElapsedMs
        ) {
            return
        }
        currentScanIdentity = identity
        currentWindowAllowsObservedCandidate = resolved.ownedChild
        lastWindowVerifiedAtElapsedMs = coordinator.elapsedRealtimeMs()
        if (kind == CaptureEventKind.WINDOW_STATE) lastContentChangedAtElapsedMs = null
        preClickRecovery.refresh(
            identity = identity,
            verifiedRoot = resolved.node,
            allowBareNumericCountdown = currentWindowAllowsObservedCandidate,
            deadlineAtElapsedMs = deadlineAtElapsedMs,
        )
        refreshUniqueEvidence(state, identity, resolved.node, deadlineAtElapsedMs)
        if (clickEvent != null) {
            captureResolvedClick(
                state = state,
                event = clickEvent,
                packageName = identity.packageName,
                identity = identity,
                verifiedRoot = resolved.node,
            )
        } else {
            tryRecordObservedDismissCandidate(state, identity, deadlineAtElapsedMs)
        }
    }

    private fun requestFor(
        sessionId: Long,
        packageName: String,
        windowId: Int,
    ): AccessibilityCaptureWindowResolver.Request? {
        val identity = verifiedHostIdentity?.takeIf {
            it.packageName == packageName && it.activityName?.isNotBlank() == true
        } ?: return null
        return AccessibilityCaptureWindowResolver.Request(
            sessionId = sessionId,
            eventPackageName = packageName,
            eventWindowId = windowId,
            host = VerifiedCaptureHost(
                sessionId = sessionId,
                packageName = identity.packageName,
                activityName = requireNotNull(identity.activityName),
                windowId = identity.windowId,
            ),
        )
    }

    private fun retryRequestFor(
        key: WindowRetryKey,
    ): AccessibilityCaptureWindowResolver.Request? {
        val session = stateProvider().activeSession ?: return null
        if (session.id != key.sessionId ||
            session.phase !in setOf(
                CapturePhase.WAITING_FOR_TARGET,
                CapturePhase.OBSERVING_CLICK,
            ) ||
            lockedSessionId == session.id
        ) {
            return null
        }
        return requestFor(key.sessionId, key.packageName, key.windowId)
    }

    private fun tryRecordObservedDismissCandidate(
        state: RuleCaptureState,
        identity: PageIdentity,
        deadlineAtElapsedMs: Long? = null,
    ) {
        val session = state.activeSession ?: return
        if (deadlineAtElapsedMs != null &&
            coordinator.elapsedRealtimeMs() >= deadlineAtElapsedMs
        ) {
            return
        }
        if (!currentWindowAllowsObservedCandidate || lockedSessionId == session.id) return
        val snapshotNodes = preClickRecovery.freshNodes(identity) ?: return
        val candidates = RuleCandidateAnalyzer.selectorCandidates(snapshotNodes)
        if (candidates.isEmpty()) return
        val enteredAt = session.targetEnteredAtElapsedMs ?: verifiedTargetEnteredAtElapsedMs
            ?: return
        val activityName = identity.activityName?.takeIf(String::isNotBlank) ?: return
        val packageName = identity.packageName
        val candidate = ObservedDismissCandidate(
            id = session.id,
            targetAppId = session.targetAppId ?: TargetAppId(packageName),
            targetAppName = session.targetAppName ?: currentTargetAppName ?: packageName,
            activityName = activityName.take(MAX_ACTIVITY_LENGTH),
            observedAfterTargetMs =
                (coordinator.elapsedRealtimeMs() - enteredAt).coerceAtLeast(0),
            observedAtEpochMs = coordinator.currentTimeMillis(),
            nodes = snapshotNodes,
            preClickUniqueSelectors = candidates.filter { it in latestUniqueSelectors },
        )
        // 候选只进入待确认状态；只有用户回到 FlashSkip 显式确认后才会转换为规则样本。
        lockedSessionId = session.id
        scope.launch { coordinator.recordObservedCandidate(session.id, candidate) }
    }

    private fun refreshUniqueEvidence(
        state: RuleCaptureState,
        identity: PageIdentity,
        verifiedRoot: AccessibilityNodeInfo,
        deadlineAtElapsedMs: Long?,
    ) {
        val session = state.activeSession ?: return
        val targetAppId = session.targetAppId ?: TargetAppId(identity.packageName)
        val selectors = RuleCandidateAnalyzer.analyze(
            samples = state.samples,
            targetAppId = targetAppId,
        ).probeSelectors
        latestUniqueSelectors = when (
            val result = probe.uniqueSelectorsOnVerifiedRoot(
                expected = identity,
                verifiedRoot = verifiedRoot,
                selectors = selectors,
                deadlineAtElapsedMs = deadlineAtElapsedMs,
            )
        ) {
            is AccessibilityCaptureProbe.ProbeResult.Success -> result.selectors
            AccessibilityCaptureProbe.ProbeResult.NoRoot,
            AccessibilityCaptureProbe.ProbeResult.PageChanged,
            AccessibilityCaptureProbe.ProbeResult.TraversalLimit,
            -> emptySet()
        }
    }

    private fun clickFailureDiagnostics(
        sourceAvailable: Boolean,
        hint: ClickSemanticHint,
        copiedNodeCount: Int = 0,
        selectorCandidateCount: Int = 0,
        immediateRecovery: ClickRecoveryDiagnostics? = null,
        snapshotRecovery: SnapshotRecoveryDiagnostics? = null,
    ): CaptureFailureDiagnostics {
        val now = coordinator.elapsedRealtimeMs()
        return CaptureFailureDiagnostics(
            sourceAvailable = sourceAvailable,
            eventTextCount = hint.texts.size,
            eventHasContentDescription = hint.contentDescription != null,
            copiedNodeCount = copiedNodeCount,
            selectorCandidateCount = selectorCandidateCount,
            immediateRecovery = immediateRecovery,
            preClickProbe = preClickRecovery.latestProbeDiagnostics,
            snapshotRecovery = snapshotRecovery,
            lastWindowVerifiedAgeMs = elapsedSince(now, lastWindowVerifiedAtElapsedMs),
            lastContentChangedAgeMs = elapsedSince(now, lastContentChangedAtElapsedMs),
        )
    }

    private fun elapsedSince(now: Long, timestamp: Long?): Long? = timestamp?.let {
        (now - it).coerceAtLeast(0)
    }

    private fun fail(
        sessionId: Long,
        failure: CaptureFailure,
        diagnostics: CaptureFailureDiagnostics? = null,
    ) {
        scope.launch {
            coordinator.fail(
                sessionId = sessionId,
                failure = failure,
                diagnostics = diagnostics,
                targetWindowDiagnostics = targetWindowDiagnosticsProvider(sessionId),
            )
        }
    }

    private companion object {
        const val MAX_ACTIVITY_LENGTH = 240
    }

    private enum class CaptureEventKind {
        WINDOW_STATE,
        CONTENT_CHANGED,
        VIEW_CLICKED,
        RETRY,
    }

    private fun VerifiedCaptureHost.toPageIdentity(windowId: Int) = PageIdentity(
        packageName = packageName,
        activityName = activityName,
        windowId = windowId,
        appVersion = null,
    )
}
