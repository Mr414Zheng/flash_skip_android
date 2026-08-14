package com.zhenghuaizhi.flashskip.platform.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.zhenghuaizhi.flashskip.application.rulecapture.RuleCaptureCoordinator
import com.zhenghuaizhi.flashskip.domain.model.CaptureFailure
import com.zhenghuaizhi.flashskip.domain.model.CaptureFailureDiagnostics
import com.zhenghuaizhi.flashskip.domain.model.CapturePhase
import com.zhenghuaizhi.flashskip.domain.model.CaptureSample
import com.zhenghuaizhi.flashskip.domain.model.CaptureSession
import com.zhenghuaizhi.flashskip.domain.model.CapturedSelector
import com.zhenghuaizhi.flashskip.domain.model.ClickRecoveryDiagnostics
import com.zhenghuaizhi.flashskip.domain.model.ObservedDismissCandidate
import com.zhenghuaizhi.flashskip.domain.model.RuleCaptureState
import com.zhenghuaizhi.flashskip.domain.model.SemanticDiffEvidence
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
    private val semanticDiffTracker = AccessibilitySemanticDiffTracker(
        elapsedRealtimeMs = coordinator::elapsedRealtimeMs,
    )
    private val windowResolutionSession = CaptureWindowResolutionSession(
        service = service,
        scope = scope,
        elapsedRealtimeMs = coordinator::elapsedRealtimeMs,
        retryRequestProvider = ::retryRequestFor,
        onRetryResolved = { request, resolved ->
            acceptResolvedRoot(
                state = stateProvider(),
                identity = request.host.toPageIdentity(resolved.node.windowId),
                resolved = resolved,
                kind = CaptureEventKind.RETRY,
                clickEvent = null,
                eventWindowId = request.eventWindowId,
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
            semanticDiffTracker.clear()
        }
        if (session == null || session.phase in setOf(
                CapturePhase.CONFIGURING,
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
            semanticDiffTracker.clear()
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
            semanticDiff = semanticDiffTracker.latestDiagnostics,
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
        val enteredAt = session.targetEnteredAtElapsedMs
            ?: verifiedTargetEnteredAtElapsedMs
            ?: now
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
            semanticDiffTracker.clear()
        }
        verifiedHostIdentity = identity
        currentScanIdentity = null
        currentTargetAppName = targetAppName
        currentWindowAllowsObservedCandidate = false
        verifiedTargetEnteredAtElapsedMs = enteredAt
        lastWindowVerifiedAtElapsedMs = now
        lastContentChangedAtElapsedMs = null
        CaptureTraceLog.stage(
            session.id,
            "verified_host",
            "package" to packageName,
            "activity" to activityName,
            "windowId" to event.windowId,
            "enteredAt" to enteredAt,
        )
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
        // Activity 已在当前回调栈完成验证后立即观察内容，不能等待异步存储把状态切到 OBSERVING_CLICK。
        if (session.phase !in setOf(
                CapturePhase.WAITING_FOR_TARGET,
                CapturePhase.OBSERVING_CLICK,
            )
        ) {
            return
        }
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
        CaptureTraceLog.stage(
            session.id,
            "click_capture",
            "windowId" to identity.windowId,
            "sourceAvailable" to (source != null),
            "eventText" to hint.texts,
            "eventDescription" to hint.contentDescription,
            "allowBareNumeric" to hint.allowBareNumericCountdown,
        )
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
        CaptureTraceLog.stage(
            session.id,
            "click_semantics",
            "copiedNodes" to nodes.size,
            "selectorCandidates" to candidates.size,
            "sourceAvailable" to (source != null),
        )
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
        CaptureTraceLog.stage(
            session.id,
            "sample_captured",
            "package" to packageName,
            "activity" to activityName,
            "windowId" to identity.windowId,
            "nodes" to nodes.size,
            "selectors" to candidates.size,
            "clickedAfterTargetMs" to sample.clickedAfterTargetMs,
        )
    }

    private fun resolveEventWindow(
        state: RuleCaptureState,
        event: AccessibilityEvent,
        packageName: String,
        kind: CaptureEventKind,
    ) {
        val session = state.activeSession ?: return
        val request = requestFor(
            sessionId = session.id,
            packageName = packageName,
            windowId = event.windowId,
            allowActiveHostRootFallback = kind != CaptureEventKind.VIEW_CLICKED &&
                event.packageName?.toString() == packageName,
        ) ?: return
        windowResolutionSession.resolve(request, event) { resolved ->
            acceptResolvedRoot(
                state = state,
                identity = request.host.toPageIdentity(resolved.node.windowId),
                resolved = resolved,
                kind = kind,
                clickEvent = event.takeIf { kind == CaptureEventKind.VIEW_CLICKED },
                eventWindowId = event.windowId,
            )
        }
    }

    private fun acceptResolvedRoot(
        state: RuleCaptureState,
        identity: PageIdentity,
        resolved: AccessibilityCaptureWindowResolver.ResolvedRoot,
        kind: CaptureEventKind,
        clickEvent: AccessibilityEvent?,
        eventWindowId: Int,
    ) {
        val session = state.activeSession ?: return
        val deadlineAtElapsedMs = resolved.deadlineAtElapsedMs
        if (deadlineAtElapsedMs != null &&
            coordinator.elapsedRealtimeMs() >= deadlineAtElapsedMs
        ) {
            return
        }
        currentScanIdentity = identity
        currentWindowAllowsObservedCandidate = allowsObservedCandidate(
            session = session,
            identity = identity,
            resolved = resolved,
        )
        lastWindowVerifiedAtElapsedMs = coordinator.elapsedRealtimeMs()
        if (kind == CaptureEventKind.WINDOW_STATE) lastContentChangedAtElapsedMs = null
        val refresh = preClickRecovery.refresh(
            identity = identity,
            verifiedRoot = resolved.node,
            allowBareNumericCountdown = currentWindowAllowsObservedCandidate,
            deadlineAtElapsedMs = deadlineAtElapsedMs,
        )
        refresh?.diagnostics?.let { diagnostics ->
            CaptureTraceLog.probe(
                sessionId = state.activeSession?.id ?: return@let,
                windowId = identity.windowId,
                allowBareNumericCountdown = currentWindowAllowsObservedCandidate,
                diagnostics = diagnostics,
            )
        }
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
            if (lockedSessionId != session.id &&
                allowsSemanticDiff(session, identity, resolved) &&
                refresh != null
            ) {
                observeSemanticDiff(
                    state = state,
                    identity = identity,
                    refresh = refresh,
                    transitionSignal = eventWindowId != identity.windowId,
                )
            }
        }
    }

    private fun allowsObservedCandidate(
        session: CaptureSession,
        identity: PageIdentity,
        resolved: AccessibilityCaptureWindowResolver.ResolvedRoot,
    ): Boolean {
        if (resolved.ownedChild) return true
        val host = verifiedHostIdentity ?: return false
        if (identity.packageName != host.packageName ||
            identity.windowId != host.windowId
        ) {
            return false
        }
        val enteredAt = session.targetEnteredAtElapsedMs
            ?: verifiedTargetEnteredAtElapsedMs
            ?: return false
        val ageMs = coordinator.elapsedRealtimeMs() - enteredAt
        return ageMs in 0..PRIMARY_WINDOW_CANDIDATE_WINDOW_MS
    }

    private fun allowsSemanticDiff(
        session: CaptureSession,
        identity: PageIdentity,
        resolved: AccessibilityCaptureWindowResolver.ResolvedRoot,
    ): Boolean {
        val host = verifiedHostIdentity ?: return false
        if (identity.packageName != host.packageName ||
            (!resolved.ownedChild && identity.windowId != host.windowId)
        ) {
            return false
        }
        val enteredAt = session.targetEnteredAtElapsedMs
            ?: verifiedTargetEnteredAtElapsedMs
            ?: return false
        val ageMs = coordinator.elapsedRealtimeMs() - enteredAt
        return ageMs in 0..RuleCaptureCoordinator.CLICK_TIMEOUT_MS
    }

    private fun requestFor(
        sessionId: Long,
        packageName: String,
        windowId: Int,
        allowActiveHostRootFallback: Boolean = false,
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
            allowActiveHostRootFallback = allowActiveHostRootFallback,
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
        return requestFor(
            sessionId = key.sessionId,
            packageName = key.packageName,
            windowId = key.windowId,
            allowActiveHostRootFallback = key.allowActiveHostRootFallback,
        )
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
        if (!currentWindowAllowsObservedCandidate || lockedSessionId == session.id) {
            CaptureTraceLog.stage(
                session.id,
                "observed_candidate_skipped",
                "reason" to if (lockedSessionId == session.id) "session_locked" else "window_policy",
                "windowId" to identity.windowId,
            )
            return
        }
        val snapshotNodes = preClickRecovery.freshNodes(identity) ?: run {
            CaptureTraceLog.stage(
                session.id,
                "observed_candidate_skipped",
                "reason" to "no_fresh_snapshot",
                "windowId" to identity.windowId,
            )
            return
        }
        val candidates = RuleCandidateAnalyzer.selectorCandidates(snapshotNodes)
        if (candidates.isEmpty()) {
            CaptureTraceLog.stage(
                session.id,
                "observed_candidate_skipped",
                "reason" to "no_selector_candidate",
                "windowId" to identity.windowId,
                "snapshotNodes" to snapshotNodes.size,
            )
            return
        }
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
        CaptureTraceLog.stage(
            session.id,
            "observed_candidate",
            "package" to packageName,
            "activity" to activityName,
            "windowId" to identity.windowId,
            "nodes" to snapshotNodes.size,
            "selectors" to candidates.size,
            "observedAfterTargetMs" to candidate.observedAfterTargetMs,
        )
        scope.launch { coordinator.recordObservedCandidate(session.id, candidate) }
    }

    private fun observeSemanticDiff(
        state: RuleCaptureState,
        identity: PageIdentity,
        refresh: AccessibilityPreClickRecovery.RefreshResult,
        transitionSignal: Boolean,
    ) {
        val session = state.activeSession ?: return
        val targetAppId = session.targetAppId ?: TargetAppId(identity.packageName)
        val priorEvidence = state.semanticDiffEvidence.lastOrNull { evidence ->
            evidence.targetAppId == targetAppId && evidence.activityName == identity.activityName
        }
        val result = semanticDiffTracker.observe(
            identity = identity,
            candidates = refresh.semanticCandidates,
            visitedNodeCount = refresh.diagnostics.visitedNodeCount,
            transitionSignal = transitionSignal,
            priorSelectors = priorEvidence?.selectors.orEmpty(),
        )
        val diagnostics = result.diagnostics
        CaptureTraceLog.stage(
            session.id,
            "semantic_diff",
            "outcome" to diagnostics.outcome,
            "observations" to diagnostics.observationCount,
            "current" to diagnostics.currentCandidateCount,
            "stable" to diagnostics.stableCandidateCount,
            "disappeared" to diagnostics.disappearedCandidateCount,
            "visited" to diagnostics.visitedNodeCount,
            "transition" to diagnostics.transitionSignal,
            "priorSelectors" to priorEvidence?.selectors?.size,
        )
        if (result is AccessibilitySemanticDiffTracker.Result.Ambiguous) {
            // 每个候选只保存一个最强稳定身份，避免前排候选耗尽上限后丢失后续交集证据。
            val selectors = result.candidates
                .mapNotNull { it.selectors.firstOrNull() }
                .distinct()
            CaptureTraceLog.stage(
                session.id,
                "semantic_diff_evidence",
                "package" to identity.packageName,
                "activity" to identity.activityName,
                "candidates" to result.candidates.size,
                "selectors" to selectors.size,
            )
            scope.launch {
                coordinator.recordSemanticDiffEvidence(
                    sessionId = session.id,
                    evidence = SemanticDiffEvidence(
                        targetAppId = targetAppId,
                        activityName = identity.activityName.orEmpty(),
                        selectors = selectors,
                        capturedAtEpochMs = coordinator.currentTimeMillis(),
                    ),
                )
            }
            return
        }
        val selected = (result as? AccessibilitySemanticDiffTracker.Result.Candidate)
            ?.candidate
            ?: return
        val enteredAt = session.targetEnteredAtElapsedMs ?: verifiedTargetEnteredAtElapsedMs
            ?: return
        val activityName = identity.activityName?.takeIf(String::isNotBlank) ?: return
        val packageName = identity.packageName
        val candidate = ObservedDismissCandidate(
            id = session.id,
            targetAppId = targetAppId,
            targetAppName = session.targetAppName ?: currentTargetAppName ?: packageName,
            activityName = activityName.take(MAX_ACTIVITY_LENGTH),
            observedAfterTargetMs =
                (coordinator.elapsedRealtimeMs() - enteredAt).coerceAtLeast(0),
            observedAtEpochMs = coordinator.currentTimeMillis(),
            nodes = selected.nodes,
            preClickUniqueSelectors = selected.selectors.filter { it in latestUniqueSelectors },
        )
        lockedSessionId = session.id
        CaptureTraceLog.stage(
            session.id,
            "semantic_diff_candidate",
            "package" to packageName,
            "activity" to activityName,
            "windowId" to identity.windowId,
            "resourceId" to selected.selectors.firstOrNull()?.resourceId,
            "ancestorResourceId" to selected.selectors.firstOrNull()?.ancestorResourceId,
            "nodes" to selected.nodes.size,
            "selectors" to selected.selectors.size,
        )
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
        CaptureTraceLog.stage(
            session.id,
            "unique_selector_probe",
            "windowId" to identity.windowId,
            "requested" to selectors.size,
            "unique" to latestUniqueSelectors.size,
        )
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
            semanticDiff = semanticDiffTracker.latestDiagnostics,
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
        CaptureTraceLog.stage(
            sessionId,
            "capture_failed",
            "failure" to failure,
            "sourceAvailable" to diagnostics?.sourceAvailable,
            "eventTextCount" to diagnostics?.eventTextCount,
            "copiedNodes" to diagnostics?.copiedNodeCount,
            "selectorCandidates" to diagnostics?.selectorCandidateCount,
            "immediateRecovery" to diagnostics?.immediateRecovery?.outcome,
            "preClickProbe" to diagnostics?.preClickProbe?.outcome,
            "snapshotRecovery" to diagnostics?.snapshotRecovery?.outcome,
            "semanticDiff" to diagnostics?.semanticDiff?.outcome,
        )
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
        const val PRIMARY_WINDOW_CANDIDATE_WINDOW_MS = 8_000L
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
