package com.zhenghuaizhi.flashskip.platform.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.zhenghuaizhi.flashskip.application.rulecapture.RuleCaptureCoordinator
import com.zhenghuaizhi.flashskip.domain.model.CaptureFailure
import com.zhenghuaizhi.flashskip.domain.model.CaptureFailureDiagnostics
import com.zhenghuaizhi.flashskip.domain.model.CaptureEvidence
import com.zhenghuaizhi.flashskip.domain.model.CapturePhase
import com.zhenghuaizhi.flashskip.domain.model.CaptureSample
import com.zhenghuaizhi.flashskip.domain.model.CaptureSession
import com.zhenghuaizhi.flashskip.domain.model.CaptureDiagnosticClearReason
import com.zhenghuaizhi.flashskip.domain.model.CaptureDiagnosticRecordKind
import com.zhenghuaizhi.flashskip.domain.model.CaptureDiagnosticScanPurpose
import com.zhenghuaizhi.flashskip.domain.model.CaptureDiagnosticWindowSource
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
import com.zhenghuaizhi.flashskip.domain.rulecapture.ObservedCandidateClickMatcher
import com.zhenghuaizhi.flashskip.domain.rulecapture.RuleCandidateAnalyzer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal class AccessibilityCaptureAdapter(
    private val service: AccessibilityService,
    private val coordinator: RuleCaptureCoordinator,
    private val scope: CoroutineScope,
    private val stateProvider: () -> RuleCaptureState,
    private val targetWindowDiagnosticsProvider: (Long) -> TargetWindowDiagnostics?,
    private val targetWindowResolutionRecorder: (CaptureWindowResolutionRecord) -> Unit,
) {
    private var verifiedHostIdentity: PageIdentity? = null
    private var currentScanIdentity: PageIdentity? = null
    private var latestUniqueSelectors: Set<CapturedSelector> = emptySet()
    private val probeSelectorCache = CaptureProbeSelectorCache()
    private var candidateLockedSessionId: Long? = null
    private var clickLockedSessionId: Long? = null
    private var frozenCandidate: FrozenCandidate? = null
    private var pendingCandidateClick: PendingCandidateClick? = null
    private var observedSessionId: Long? = null
    private var verifiedTargetEnteredAtElapsedMs: Long? = null
    private var currentTargetAppName: String? = null
    private var lastWindowVerifiedAtElapsedMs: Long? = null
    private var lastContentChangedAtElapsedMs: Long? = null
    private var currentWindowAllowsObservedCandidate = false
    private val diagnosticTrail = CaptureDiagnosticTrail(onRecord = { sessionId, record, scan ->
        if (record.kind != CaptureDiagnosticRecordKind.WINDOW_RESOLUTION) {
            CaptureTraceLog.record(sessionId, record, scan, allowBareNumericCountdown = currentWindowAllowsObservedCandidate)
        }
    })
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
                eventType = request.eventType,
            )
        },
        recordResolution = { record ->
            diagnosticTrail.recordWindowResolution(
                sessionId = record.request.sessionId,
                occurredAtElapsedMs = record.occurredAtElapsedMs,
                eventId = record.request.eventType,
                eventWindowId = record.request.eventWindowId,
                hostWindowId = record.request.host.windowId,
                rootWindowId = record.details.rootWindowId,
                rootSource = record.details.rootSource.toDiagnosticSource(),
                ownedChild = record.details.ownedChild,
                fallbackAllowed = record.request.allowActiveHostRootFallback,
                outcome = record.outcome,
                isRetry = record.isRetry,
            )
            targetWindowResolutionRecorder(record)
            CaptureTraceLog.windowResolution(record)
        },
    )

    fun verifiedHostPackageName(): String? = verifiedHostIdentity?.packageName

    fun onStateChanged(state: RuleCaptureState) {
        val session = state.activeSession
        if (session?.id != observedSessionId) {
            observedSessionId = session?.id
            if (session == null) {
                diagnosticTrail.reset()
            } else {
                diagnosticTrail.beginSession(session.id, coordinator.elapsedRealtimeMs())
            }
            candidateLockedSessionId = null
            clickLockedSessionId = null
            frozenCandidate = null
            pendingCandidateClick = null
            verifiedHostIdentity = null
            currentScanIdentity = null
            verifiedTargetEnteredAtElapsedMs = null
            currentTargetAppName = null
            latestUniqueSelectors = emptySet()
            probeSelectorCache.clear()
            lastWindowVerifiedAtElapsedMs = null
            lastContentChangedAtElapsedMs = null
            currentWindowAllowsObservedCandidate = false
            windowResolutionSession.cancel()
            preClickRecovery.clear()
            semanticDiffTracker.clear()
        }
        val candidateListening = session?.listensForCandidateClick == true
        val hasFrozenCandidate = candidateListening && frozenCandidate?.let { frozen ->
            frozen.sessionId == session.id && frozen.candidate == session.pendingObservedCandidate
        } == true
        if (session == null || session.phase in setOf(
                CapturePhase.CONFIGURING,
                CapturePhase.CAPTURED,
                CapturePhase.FAILED,
            ) || (session.phase == CapturePhase.AWAITING_CONFIRMATION && !candidateListening)
        ) {
            clearCaptureContext(
                clearHostIdentity = true,
                clearFrozenCandidate = true,
                preserveTargetEntry = false,
            )
        } else if (candidateListening) {
            clearCaptureContext(
                clearHostIdentity = !hasFrozenCandidate,
                clearFrozenCandidate = !hasFrozenCandidate,
                preserveTargetEntry = hasFrozenCandidate,
            )
        }
    }

    private fun clearCaptureContext(
        clearHostIdentity: Boolean,
        clearFrozenCandidate: Boolean,
        preserveTargetEntry: Boolean,
    ) {
        if (clearHostIdentity) verifiedHostIdentity = null
        if (clearFrozenCandidate) {
            frozenCandidate = null
            pendingCandidateClick = null
            candidateLockedSessionId = null
            clickLockedSessionId = null
        }
        currentScanIdentity = null
        if (!preserveTargetEntry) verifiedTargetEnteredAtElapsedMs = null
        currentTargetAppName = null
        latestUniqueSelectors = emptySet()
        probeSelectorCache.clear()
        lastWindowVerifiedAtElapsedMs = null
        lastContentChangedAtElapsedMs = null
        currentWindowAllowsObservedCandidate = false
        windowResolutionSession.cancel()
        preClickRecovery.clear()
        semanticDiffTracker.clear()
    }

    fun timeoutDiagnostics(sessionId: Long): CaptureFailureDiagnostics? {
        if (observedSessionId != sessionId) return null
        val now = coordinator.elapsedRealtimeMs()
        val preClickProbe = preClickRecovery.latestProbeDiagnostics
        return CaptureFailureDiagnostics(
            sourceAvailable = false,
            eventTextCount = 0,
            eventHasContentDescription = false,
            selectorCandidateCount = preClickProbe?.selectorCandidateNodeCount ?: 0,
            preClickProbe = preClickProbe,
            semanticDiff = semanticDiffTracker.latestDiagnostics,
            lastWindowVerifiedAgeMs = elapsedSince(now, lastWindowVerifiedAtElapsedMs),
            lastContentChangedAgeMs = elapsedSince(now, lastContentChangedAtElapsedMs),
            clickDiagnosticApplicable = stateProvider().activeSession
                ?.takeIf { it.id == sessionId }
                ?.phase == CapturePhase.OBSERVING_CLICK,
            sessionTrace = diagnosticTrail.snapshot(sessionId),
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
        if (isCandidateOrClickLocked(session.id)) return
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
        val previousHost = verifiedHostIdentity
        if (previousHost != null && previousHost != identity) {
            windowResolutionSession.cancel()
            currentScanIdentity = null
            latestUniqueSelectors = emptySet()
            preClickRecovery.clear()
            semanticDiffTracker.clear()
            diagnosticTrail.recordHostWindowUpdate(
                sessionId = session.id,
                occurredAtElapsedMs = now,
                eventId = event.eventType,
                eventWindowId = event.windowId,
                previousWindowId = previousHost.windowId,
                currentWindowId = identity.windowId,
                differenceCleared = true,
                clearReason = if (previousHost.windowId != identity.windowId) {
                    CaptureDiagnosticClearReason.HOST_WINDOW_CHANGED
                } else {
                    CaptureDiagnosticClearReason.HOST_IDENTITY_CHANGED
                },
            )
        } else if (previousHost == null) {
            diagnosticTrail.recordHostWindowUpdate(
                sessionId = session.id,
                occurredAtElapsedMs = now,
                eventId = event.eventType,
                eventWindowId = event.windowId,
                previousWindowId = null,
                currentWindowId = identity.windowId,
                differenceCleared = false,
                clearReason = null,
            )
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
            isCandidateOrClickLocked(session.id)
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
        if (isCandidateOrClickLocked(session.id)) return
        if (session.packageName != null && packageName != session.packageName) return
        if (verifiedHostIdentity?.packageName != packageName) return
        lastContentChangedAtElapsedMs = coordinator.elapsedRealtimeMs()
        resolveEventWindow(state, event, packageName, CaptureEventKind.CONTENT_CHANGED)
    }

    fun onClicked(event: AccessibilityEvent, packageName: String) {
        val state = stateProvider()
        val session = state.activeSession ?: return
        val pendingCandidate = frozenCandidateFor(session)
        if (pendingCandidate != null) {
            if (clickLockedSessionId == session.id ||
                session.phase !in setOf(
                    CapturePhase.WAITING_FOR_TARGET,
                    CapturePhase.OBSERVING_CLICK,
                    CapturePhase.AWAITING_CONFIRMATION,
                )
            ) {
                return
            }
            if (session.packageName != null && packageName != session.packageName) return
            if (verifiedHostIdentity?.packageName != packageName) return
            if (event.windowId != pendingCandidate.identity.windowId ||
                packageName != pendingCandidate.identity.packageName
            ) {
                CaptureTraceLog.stage(
                    session.id,
                    "candidate_click_ignored",
                    "reason" to CaptureTraceReason.CANDIDATE_WINDOW_MISMATCH,
                    "eventWindowId" to event.windowId,
                    "candidateWindowId" to pendingCandidate.identity.windowId,
                )
                return
            }
            resolveEventWindow(state, event, packageName, CaptureEventKind.VIEW_CLICKED)
            return
        }
        if (session.phase !in setOf(
                CapturePhase.WAITING_FOR_TARGET,
                CapturePhase.OBSERVING_CLICK,
            ) || isCandidateOrClickLocked(session.id)
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
        clickLockedSessionId = session.id
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
            "sourceAvailable" to (source != null),
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
            diagnosticTrail.recordScan(
                sessionId = session.id,
                occurredAtElapsedMs = coordinator.elapsedRealtimeMs(),
                eventId = event.eventType,
                eventWindowId = event.windowId,
                hostWindowId = verifiedHostIdentity?.windowId ?: identity.windowId,
                rootWindowId = verifiedRoot.windowId,
                purpose = CaptureDiagnosticScanPurpose.CLICK_RECOVERY,
                diagnostics = immediate.diagnostics,
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
        scope.launch {
            val result = coordinator.recordCaptured(session.id, sample)
            if (result is com.zhenghuaizhi.flashskip.application.rulecapture.CaptureOperationResult.Failure &&
                clickLockedSessionId == session.id
            ) {
                clickLockedSessionId = null
            }
            if (result is com.zhenghuaizhi.flashskip.application.rulecapture.CaptureOperationResult.Success) {
                CaptureTraceLog.stage(
                    session.id, "sample_captured", "nodes" to nodes.size,
                    "selectors" to candidates.size, "clickedAfterTargetMs" to sample.clickedAfterTargetMs,
                )
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun captureObservedCandidateClick(
        session: CaptureSession,
        candidate: FrozenCandidate,
        event: AccessibilityEvent,
        identity: PageIdentity,
    ) {
        if (clickLockedSessionId == session.id || identity != candidate.identity) {
            CaptureTraceLog.stage(
                session.id,
                "candidate_click_ignored",
                "reason" to if (clickLockedSessionId == session.id) CaptureTraceReason.SESSION_LOCKED
                else CaptureTraceReason.CANDIDATE_IDENTITY_MISMATCH,
            )
            return
        }
        clickLockedSessionId = session.id
        val clickedAtElapsedMs = coordinator.elapsedRealtimeMs()
        val clickedAtEpochMs = coordinator.currentTimeMillis()
        val eventSource = event.source ?: run {
            clickLockedSessionId = null
            CaptureTraceLog.stage(session.id, "candidate_click_ignored", "reason" to CaptureTraceReason.SOURCE_UNAVAILABLE)
            return
        }
        if (eventSource.packageName?.toString() != identity.packageName ||
            eventSource.windowId != identity.windowId
        ) {
            eventSource.recycle()
            clickLockedSessionId = null
            CaptureTraceLog.stage(session.id, "candidate_click_ignored", "reason" to CaptureTraceReason.SOURCE_IDENTITY_MISMATCH)
            return
        }
        val nodes = semanticSnapshotter.copySemanticChain(eventSource, identity.packageName)
        if (!ObservedCandidateClickMatcher.matches(
                candidate = candidate.candidate,
                candidateIdentity = candidate.identity,
                clickedIdentity = identity,
                clickedNodes = nodes,
            )
        ) {
            clickLockedSessionId = null
            CaptureTraceLog.stage(
                session.id,
                "candidate_click_ignored",
                "reason" to CaptureTraceReason.CANDIDATE_MISMATCH,
                "copiedNodes" to nodes.size,
                "resourceIdNodes" to nodes.count { it.resourceId != null },
            )
            return
        }

        val enteredAt = session.targetEnteredAtElapsedMs ?: verifiedTargetEnteredAtElapsedMs
        if (enteredAt == null || identity.activityName.isNullOrBlank()) {
            clickLockedSessionId = null
            CaptureTraceLog.stage(session.id, "candidate_click_ignored", "reason" to CaptureTraceReason.TARGET_ENTRY_UNAVAILABLE)
            return
        }
        val sample = CaptureSample(
            id = session.id,
            targetAppId = candidate.candidate.targetAppId,
            targetAppName = candidate.candidate.targetAppName,
            activityName = identity.activityName,
            clickedAfterTargetMs = (clickedAtElapsedMs - enteredAt).coerceAtLeast(0),
            capturedAtEpochMs = clickedAtEpochMs,
            nodes = nodes,
            preClickUniqueSelectors = candidate.candidate.preClickUniqueSelectors,
            evidence = CaptureEvidence.ACCESSIBILITY_CLICK,
        )
        CaptureTraceLog.stage(
            session.id,
            "candidate_click_matched",
            "copiedNodes" to nodes.size,
            "clickedAfterTargetMs" to sample.clickedAfterTargetMs,
        )
        pendingCandidateClick = PendingCandidateClick(identity, sample)
        launchPendingCandidateClickUpgrade(candidate)
    }

    private fun frozenCandidateFor(session: CaptureSession): FrozenCandidate? {
        val candidate = frozenCandidate ?: return null
        if (candidate.sessionId != session.id ||
            candidateLockedSessionId != session.id ||
            (session.packageName != null && session.packageName != candidate.identity.packageName)
        ) {
            return null
        }
        return when (session.phase) {
            CapturePhase.WAITING_FOR_TARGET,
            CapturePhase.OBSERVING_CLICK,
            -> candidate

            CapturePhase.AWAITING_CONFIRMATION -> candidate.takeIf {
                session.listensForCandidateClick &&
                    session.pendingObservedCandidate == candidate.candidate
            }

            else -> null
        }
    }

    private fun isCandidateOrClickLocked(sessionId: Long): Boolean =
        candidateLockedSessionId == sessionId || clickLockedSessionId == sessionId

    private fun freezeAndRecordCandidate(
        session: CaptureSession,
        candidate: ObservedDismissCandidate,
        identity: PageIdentity,
    ) {
        if (isCandidateOrClickLocked(session.id)) return
        val frozen = FrozenCandidate(
            sessionId = session.id,
            candidate = candidate,
            identity = identity,
        )
        candidateLockedSessionId = session.id
        frozenCandidate = frozen
        scope.launch {
            val result = coordinator.recordObservedCandidateResult(session.id, candidate)
            if (result is com.zhenghuaizhi.flashskip.application.rulecapture.CaptureOperationResult.Failure) {
                handleCandidateWriteFailure(frozen)
                return@launch
            }
            val recorded = result is com.zhenghuaizhi.flashskip.application.rulecapture.CaptureOperationResult.Success
            if (frozenCandidate !== frozen) return@launch
            if (!recorded) {
                releaseFrozenCandidate(frozen)
                return@launch
            }
            frozen.recorded = true
            launchPendingCandidateClickUpgrade(frozen)
        }
    }

    private suspend fun handleCandidateWriteFailure(candidate: FrozenCandidate) {
        if (frozenCandidate !== candidate) return
        val active = stateProvider().activeSession
        if (active?.id == candidate.sessionId &&
            active.listensForCandidateClick &&
            active.pendingObservedCandidate == candidate.candidate
        ) {
            candidate.recorded = true
            launchPendingCandidateClickUpgrade(candidate)
            return
        }

        val verifiedPendingClick = pendingCandidateClick?.takeIf { pending ->
            clickLockedSessionId == candidate.sessionId &&
                pending.sample.id == candidate.sessionId &&
                pending.sample.evidence == CaptureEvidence.ACCESSIBILITY_CLICK
        }
        CaptureTraceLog.stage(candidate.sessionId, "candidate_write_failed")
        releaseFrozenCandidate(candidate)
        if (verifiedPendingClick != null) {
            val result = coordinator.recordCaptured(candidate.sessionId, verifiedPendingClick.sample)
            if (result is com.zhenghuaizhi.flashskip.application.rulecapture.CaptureOperationResult.Failure) {
                CaptureTraceLog.stage(candidate.sessionId, "candidate_click_recovery_write_failed")
            }
        }
    }

    private fun releaseFrozenCandidate(candidate: FrozenCandidate) {
        if (frozenCandidate !== candidate) return
        frozenCandidate = null
        pendingCandidateClick = null
        if (candidateLockedSessionId == candidate.sessionId) candidateLockedSessionId = null
        if (clickLockedSessionId == candidate.sessionId) clickLockedSessionId = null
    }

    private fun launchPendingCandidateClickUpgrade(candidate: FrozenCandidate) {
        val pending = pendingCandidateClick ?: return
        if (!candidate.recorded || frozenCandidate !== candidate ||
            clickLockedSessionId != candidate.sessionId
        ) {
            return
        }
        pendingCandidateClick = null
        scope.launch {
            val result = coordinator.upgradeObservedCandidateClick(
                sessionId = candidate.sessionId,
                candidate = candidate.candidate,
                candidateIdentity = candidate.identity,
                clickedIdentity = pending.identity,
                sample = pending.sample,
            )
            if (result !is com.zhenghuaizhi.flashskip.application.rulecapture.CaptureOperationResult.Success &&
                frozenCandidate === candidate
            ) {
                clickLockedSessionId = null
                CaptureTraceLog.stage(
                    candidate.sessionId,
                    "candidate_click_upgrade_stale",
                )
            }
        }
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
            eventType = event.eventType,
        ) ?: return
        windowResolutionSession.resolve(request, event) { resolved ->
            acceptResolvedRoot(
                state = state,
                identity = request.host.toPageIdentity(resolved.node.windowId),
                resolved = resolved,
                kind = kind,
                clickEvent = event.takeIf { kind == CaptureEventKind.VIEW_CLICKED },
                eventWindowId = event.windowId,
                eventType = event.eventType,
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
        eventType: Int,
    ) {
        val session = state.activeSession ?: return
        val deadlineAtElapsedMs = resolved.deadlineAtElapsedMs
        if (deadlineAtElapsedMs != null &&
            coordinator.elapsedRealtimeMs() >= deadlineAtElapsedMs
        ) {
            return
        }
        val pendingCandidate = frozenCandidateFor(session)
        if (pendingCandidate != null) {
            if (kind == CaptureEventKind.VIEW_CLICKED && clickEvent != null &&
                identity == pendingCandidate.identity
            ) {
                captureObservedCandidateClick(
                    session = session,
                    candidate = pendingCandidate,
                    event = clickEvent,
                    identity = identity,
                )
            }
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
            diagnosticTrail.recordScan(
                sessionId = session.id,
                occurredAtElapsedMs = coordinator.elapsedRealtimeMs(),
                eventId = eventType,
                eventWindowId = eventWindowId,
                hostWindowId = verifiedHostIdentity?.windowId ?: identity.windowId,
                rootWindowId = identity.windowId,
                purpose = CaptureDiagnosticScanPurpose.PRE_CLICK_REFRESH,
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
            if (!isCandidateOrClickLocked(session.id) &&
                allowsSemanticDiff(session, identity, resolved) &&
                refresh != null
            ) {
                observeSemanticDiff(
                    state = state,
                    identity = identity,
                    refresh = refresh,
                    transitionSignal = eventWindowId != identity.windowId,
                    eventId = eventType,
                    eventWindowId = eventWindowId,
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
        eventType: Int = 0,
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
            eventType = eventType,
        )
    }

    private fun retryRequestFor(
        key: WindowRetryKey,
        eventType: Int,
    ): AccessibilityCaptureWindowResolver.Request? {
        val session = stateProvider().activeSession ?: return null
        if (session.id != key.sessionId ||
            session.phase !in setOf(
                CapturePhase.WAITING_FOR_TARGET,
                CapturePhase.OBSERVING_CLICK,
            ) ||
            isCandidateOrClickLocked(session.id)
        ) {
            return null
        }
        return requestFor(
            sessionId = key.sessionId,
            packageName = key.packageName,
            windowId = key.windowId,
            allowActiveHostRootFallback = key.allowActiveHostRootFallback,
            eventType = eventType,
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
        if (!currentWindowAllowsObservedCandidate || isCandidateOrClickLocked(session.id)) {
            CaptureTraceLog.stage(
                session.id,
                "observed_candidate_skipped",
                "reason" to if (isCandidateOrClickLocked(session.id)) {
                    CaptureTraceReason.SESSION_LOCKED
                } else {
                    CaptureTraceReason.WINDOW_POLICY
                },
            )
            return
        }
        val snapshotNodes = preClickRecovery.freshNodes(identity) ?: run {
            CaptureTraceLog.stage(
                session.id,
                "observed_candidate_skipped",
                "reason" to CaptureTraceReason.NO_FRESH_SNAPSHOT,
            )
            return
        }
        val candidates = RuleCandidateAnalyzer.selectorCandidates(snapshotNodes)
        if (candidates.isEmpty()) {
            CaptureTraceLog.stage(
                session.id,
                "observed_candidate_skipped",
                "reason" to CaptureTraceReason.NO_SELECTOR_CANDIDATE,
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
        freezeAndRecordCandidate(session, candidate, identity)
        CaptureTraceLog.stage(
            session.id,
            "observed_candidate",
            "nodes" to snapshotNodes.size,
            "selectors" to candidates.size,
            "observedAfterTargetMs" to candidate.observedAfterTargetMs,
        )
    }

    private fun observeSemanticDiff(
        state: RuleCaptureState,
        identity: PageIdentity,
        refresh: AccessibilityPreClickRecovery.RefreshResult,
        transitionSignal: Boolean,
        eventId: Int,
        eventWindowId: Int,
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
            traversalComplete = refresh.traversalComplete,
            semanticCandidatesComplete = refresh.semanticCandidatesComplete,
            transitionSignal = transitionSignal,
            priorSelectors = priorEvidence?.selectors.orEmpty(),
        )
        val diagnostics = result.diagnostics
        diagnosticTrail.recordSemanticDiff(
            sessionId = session.id,
            occurredAtElapsedMs = coordinator.elapsedRealtimeMs(),
            eventId = eventId,
            eventWindowId = eventWindowId,
            hostWindowId = verifiedHostIdentity?.windowId ?: identity.windowId,
            rootWindowId = identity.windowId,
            diagnostics = diagnostics,
        )
        if (result is AccessibilitySemanticDiffTracker.Result.Ambiguous) {
            // 每个候选只保存一个最强稳定身份，避免前排候选耗尽上限后丢失后续交集证据。
            val selectors = result.candidates
                .mapNotNull { it.selectors.firstOrNull() }
                .distinct()
            CaptureTraceLog.stage(
                session.id,
                "semantic_diff_evidence",
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
        freezeAndRecordCandidate(session, candidate, identity)
        CaptureTraceLog.stage(
            session.id,
            "semantic_diff_candidate",
            "nodes" to selected.nodes.size,
            "selectors" to selected.selectors.size,
        )
    }

    private fun refreshUniqueEvidence(
        state: RuleCaptureState,
        identity: PageIdentity,
        verifiedRoot: AccessibilityNodeInfo,
        deadlineAtElapsedMs: Long?,
    ) {
        val session = state.activeSession ?: return
        val targetAppId = session.targetAppId ?: TargetAppId(identity.packageName)
        val selectors = probeSelectorCache.selectorsFor(state.samples, targetAppId)
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
            AccessibilityCaptureProbe.ProbeResult.Unavailable,
            AccessibilityCaptureProbe.ProbeResult.PageChanged,
            AccessibilityCaptureProbe.ProbeResult.TraversalLimit,
            -> emptySet()
        }
        CaptureTraceLog.stage(
            session.id,
            "unique_selector_probe",
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
            sessionTrace = observedSessionId?.let(diagnosticTrail::snapshot),
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
        val failureDiagnostics = diagnostics?.copy(
            sessionTrace = diagnostics.sessionTrace ?: diagnosticTrail.snapshot(sessionId),
        ) ?: timeoutDiagnostics(sessionId)
        CaptureTraceLog.stage(
            sessionId,
            "capture_failed",
            "failure" to failure,
            "sourceAvailable" to failureDiagnostics?.sourceAvailable,
            "eventTextCount" to failureDiagnostics?.eventTextCount,
            "copiedNodes" to failureDiagnostics?.copiedNodeCount,
            "selectorCandidates" to failureDiagnostics?.selectorCandidateCount,
            "immediateRecovery" to failureDiagnostics?.immediateRecovery?.outcome,
            "preClickProbe" to failureDiagnostics?.preClickProbe?.outcome,
            "snapshotRecovery" to failureDiagnostics?.snapshotRecovery?.outcome,
            "semanticDiff" to failureDiagnostics?.semanticDiff?.outcome,
        )
        scope.launch {
            coordinator.fail(
                sessionId = sessionId,
                failure = failure,
                diagnostics = failureDiagnostics,
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

    private data class FrozenCandidate(
        val sessionId: Long,
        val candidate: ObservedDismissCandidate,
        val identity: PageIdentity,
        var recorded: Boolean = false,
    )

    private data class PendingCandidateClick(
        val identity: PageIdentity,
        val sample: CaptureSample,
    )

    private fun VerifiedCaptureHost.toPageIdentity(windowId: Int) = PageIdentity(
        packageName = packageName,
        activityName = activityName,
        windowId = windowId,
        appVersion = null,
    )
}

private fun CaptureWindowResolutionSource?.toDiagnosticSource(): CaptureDiagnosticWindowSource? = when (this) {
    CaptureWindowResolutionSource.WINDOW_LIST -> CaptureDiagnosticWindowSource.WINDOW_LIST
    CaptureWindowResolutionSource.ACTIVE_ROOT -> CaptureDiagnosticWindowSource.ACTIVE_ROOT
    CaptureWindowResolutionSource.EVENT_SOURCE -> CaptureDiagnosticWindowSource.EVENT_SOURCE
    null -> null
}
