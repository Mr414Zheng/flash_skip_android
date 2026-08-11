package com.zhenghuaizhi.flashskip.platform.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.zhenghuaizhi.flashskip.application.rulecapture.RuleCaptureCoordinator
import com.zhenghuaizhi.flashskip.domain.model.AppVersion
import com.zhenghuaizhi.flashskip.domain.model.CaptureFailure
import com.zhenghuaizhi.flashskip.domain.model.CapturePhase
import com.zhenghuaizhi.flashskip.domain.model.CaptureSample
import com.zhenghuaizhi.flashskip.domain.model.CapturedNode
import com.zhenghuaizhi.flashskip.domain.model.CapturedSelector
import com.zhenghuaizhi.flashskip.domain.model.RuleCaptureState
import com.zhenghuaizhi.flashskip.domain.ports.PageIdentity
import com.zhenghuaizhi.flashskip.domain.rulecapture.RuleCandidateAnalyzer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal class AccessibilityCaptureAdapter(
    private val service: AccessibilityService,
    private val coordinator: RuleCaptureCoordinator,
    private val scope: CoroutineScope,
    targetPackages: Set<String>,
    private val stateProvider: () -> RuleCaptureState,
) {
    private val targetPackages = targetPackages.toSet()
    private var currentIdentity: PageIdentity? = null
    private var latestUniqueSelectors: Set<CapturedSelector> = emptySet()
    private var lockedSessionId: Long? = null
    private var observedSessionId: Long? = null
    private val probe = AccessibilityCaptureProbe(service, targetPackages) { currentIdentity }

    fun onStateChanged(state: RuleCaptureState) {
        val session = state.activeSession
        if (session?.id != observedSessionId) {
            observedSessionId = session?.id
            lockedSessionId = null
            currentIdentity = null
            latestUniqueSelectors = emptySet()
        }
        if (session == null || session.phase in setOf(
                CapturePhase.CONFIGURING,
                CapturePhase.WAITING_FOR_TARGET,
                CapturePhase.FAILED,
            )
        ) {
            currentIdentity = null
            latestUniqueSelectors = emptySet()
        }
    }

    fun onVerifiedTargetWindow(
        event: AccessibilityEvent,
        activityName: String,
        appVersion: AppVersion,
    ) {
        val state = stateProvider()
        val session = state.activeSession ?: return
        val packageName = event.packageName?.toString() ?: return
        if (packageName !in targetPackages || packageName != session.packageName) return
        if (session.phase !in setOf(CapturePhase.WAITING_FOR_TARGET, CapturePhase.OBSERVING_CLICK)) {
            return
        }
        if (appVersion != session.appVersion) {
            lockAndFail(session.id, CaptureFailure.APP_VERSION_CHANGED)
            return
        }
        val enteredAt = session.targetEnteredAtElapsedMs ?: coordinator.elapsedRealtimeMs()
        val identity = PageIdentity(
            packageName = packageName,
            activityName = activityName,
            windowId = event.windowId,
            appVersion = appVersion,
        )
        currentIdentity = identity
        refreshUniqueEvidence(state, identity)
        scope.launch {
            coordinator.observeTarget(
                sessionId = session.id,
                appVersion = appVersion,
                activityName = activityName,
                windowId = event.windowId,
                enteredAtElapsedMs = enteredAt,
            )
        }
    }

    fun onContentChanged(event: AccessibilityEvent) {
        val state = stateProvider()
        val session = state.activeSession ?: return
        if (session.phase != CapturePhase.OBSERVING_CLICK) return
        val packageName = event.packageName?.toString() ?: return
        val identity = currentIdentity?.takeIf {
            packageName in targetPackages &&
                packageName == session.packageName &&
                it.packageName == packageName &&
                it.windowId == event.windowId
        } ?: return
        refreshUniqueEvidence(state, identity)
    }

    fun onClicked(event: AccessibilityEvent) {
        val state = stateProvider()
        val session = state.activeSession ?: return
        if (session.phase != CapturePhase.OBSERVING_CLICK || lockedSessionId == session.id) return
        val packageName = event.packageName?.toString() ?: return
        // 包和窗口检查必须先于 event.source，确保非目标点击零节点读取。
        if (packageName !in targetPackages || packageName != session.packageName) return
        val identity = currentIdentity
        if (identity == null || identity.windowId != event.windowId) {
            lockAndFail(session.id, CaptureFailure.ACTIVITY_UNAVAILABLE)
            return
        }

        lockedSessionId = session.id
        val source = event.source
        if (source == null) {
            fail(session.id, CaptureFailure.CLICK_SOURCE_UNAVAILABLE)
            return
        }
        val nodes = copySemanticChain(source, packageName)
        val candidates = RuleCandidateAnalyzer.selectorCandidates(nodes)
        if (candidates.isEmpty()) {
            fail(session.id, CaptureFailure.CLICK_SEMANTICS_UNAVAILABLE)
            return
        }
        val enteredAt = session.targetEnteredAtElapsedMs
        val activityName = identity.activityName
        if (enteredAt == null || activityName.isNullOrBlank()) {
            fail(session.id, CaptureFailure.ACTIVITY_UNAVAILABLE)
            return
        }
        val sample = CaptureSample(
            id = session.id,
            targetAppId = session.targetAppId,
            appVersion = session.appVersion,
            activityName = activityName.take(MAX_ACTIVITY_LENGTH),
            clickedAfterTargetMs = (coordinator.elapsedRealtimeMs() - enteredAt).coerceAtLeast(0),
            capturedAtEpochMs = coordinator.currentTimeMillis(),
            nodes = nodes,
            preClickUniqueSelectors = candidates.filter { it in latestUniqueSelectors },
        )
        scope.launch { coordinator.recordCaptured(session.id, sample) }
    }

    private fun refreshUniqueEvidence(state: RuleCaptureState, identity: PageIdentity) {
        val session = state.activeSession ?: return
        val selectors = RuleCandidateAnalyzer.analyze(
            samples = state.samples,
            targetAppId = session.targetAppId,
            currentVersion = session.appVersion,
        ).probeSelectors
        latestUniqueSelectors = when (val result = probe.uniqueSelectors(identity, selectors)) {
            is AccessibilityCaptureProbe.ProbeResult.Success -> result.selectors
            AccessibilityCaptureProbe.ProbeResult.NoRoot,
            AccessibilityCaptureProbe.ProbeResult.PageChanged,
            AccessibilityCaptureProbe.ProbeResult.TraversalLimit,
            -> emptySet()
        }
    }

    @Suppress("DEPRECATION")
    private fun copySemanticChain(
        initialNode: AccessibilityNodeInfo,
        expectedPackage: String,
    ): List<CapturedNode> {
        val nodes = mutableListOf<CapturedNode>()
        var current: AccessibilityNodeInfo? = initialNode
        repeat(MAX_CAPTURE_DEPTH) {
            val node = current ?: return@repeat
            val parent = runCatching { node.parent }.getOrNull()
            try {
                if (node.packageName?.toString() == expectedPackage) {
                    val bounds = Rect().also(node::getBoundsInScreen)
                    val metrics = service.resources.displayMetrics
                    nodes += CapturedNode(
                        resourceId = sanitize(node.viewIdResourceName, MAX_RESOURCE_ID_LENGTH),
                        text = sanitize(node.text?.toString(), MAX_SEMANTIC_LENGTH),
                        contentDescription = sanitize(
                            node.contentDescription?.toString(),
                            MAX_SEMANTIC_LENGTH,
                        ),
                        clickable = node.isClickable,
                        supportsActionClick = node.actionList.any {
                            it.id == AccessibilityNodeInfo.ACTION_CLICK
                        },
                        visibleToUser = node.isVisibleToUser,
                        hasUsableBounds = bounds.width() > 0 && bounds.height() > 0 &&
                            bounds.centerX() in 0 until metrics.widthPixels &&
                            bounds.centerY() in 0 until metrics.heightPixels,
                    )
                }
            } finally {
                node.recycle()
            }
            current = parent
        }
        current?.recycle()
        return nodes
    }

    private fun lockAndFail(sessionId: Long, failure: CaptureFailure) {
        if (lockedSessionId == sessionId) return
        lockedSessionId = sessionId
        fail(sessionId, failure)
    }

    private fun fail(sessionId: Long, failure: CaptureFailure) {
        scope.launch { coordinator.fail(sessionId, failure) }
    }

    private fun sanitize(value: String?, maxLength: Int): String? = value
        ?.map { if (it.isISOControl()) ' ' else it }
        ?.joinToString("")
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.take(maxLength)

    private companion object {
        const val MAX_CAPTURE_DEPTH = 5
        const val MAX_ACTIVITY_LENGTH = 240
        const val MAX_RESOURCE_ID_LENGTH = 180
        const val MAX_SEMANTIC_LENGTH = 80
    }
}
