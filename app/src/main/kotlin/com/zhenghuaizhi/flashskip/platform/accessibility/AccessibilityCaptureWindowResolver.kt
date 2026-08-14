package com.zhenghuaizhi.flashskip.platform.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.zhenghuaizhi.flashskip.platform.accessibility.CaptureWindowBoundaryPolicy.Decision
import com.zhenghuaizhi.flashskip.platform.accessibility.CaptureWindowBoundaryPolicy.RejectReason

internal class AccessibilityCaptureWindowResolver(
    private val service: AccessibilityService,
) {
    @Suppress("DEPRECATION")
    fun <T> withResolvedRoot(
        request: Request,
        event: AccessibilityEvent?,
        deadlineAtElapsedMs: Long? = null,
        elapsedRealtimeMs: () -> Long = AndroidMonotonicClock::elapsedRealtimeMs,
        block: (ResolvedRoot) -> T,
    ): Result<T> {
        if (!request.hasValidHostBoundary()) {
            return Result.Rejected(RejectReason.HOST_UNVERIFIED)
        }
        fun deadlineReached(): Boolean = deadlineAtElapsedMs != null &&
            elapsedRealtimeMs() >= deadlineAtElapsedMs
        if (deadlineReached()) return Result.Retryable

        var matchingRoot: AccessibilityNodeInfo? = null
        var activeRoot: AccessibilityNodeInfo? = null
        var sourceRoot: AccessibilityNodeInfo? = null
        var sourceWindow: AccessibilityWindowInfo? = null
        var ownedActiveRootWindow: AccessibilityWindowInfo? = null
        var sourcePackageName: String? = null
        var sourceWindowId: Int? = null
        val currentWindows = runCatching { service.windows }.getOrNull()
        try {
            val matchingWindows = currentWindows
                ?.filter { it.id == request.eventWindowId }
                .orEmpty()
            val matchingWindow = matchingWindows.singleOrNull()
            matchingRoot = matchingWindow
                ?.takeIf {
                    it.isActive && it.type == AccessibilityWindowInfo.TYPE_APPLICATION
                }
                ?.let { runCatching { it.root }.getOrNull() }

            if (matchingWindows.size != 1 || matchingRoot == null) {
                if (deadlineReached()) return Result.Retryable
                activeRoot = runCatching { service.rootInActiveWindow }.getOrNull()
            }
            if (deadlineReached()) return Result.Retryable
            val activeRootWindows = activeRoot?.let { root ->
                currentWindows?.filter { it.id == root.windowId }.orEmpty()
            }.orEmpty()
            val activeRootWindow = activeRootWindows.singleOrNull() ?: activeRoot?.let { root ->
                if (activeRootWindows.isEmpty()) {
                    runCatching { root.window }.getOrNull()?.also {
                        ownedActiveRootWindow = it
                    }
                } else {
                    null
                }
            }
            val activeRootProvesTargetForeground = activeRoot?.let { root ->
                root.packageName?.toString() == request.host.packageName &&
                    root.windowId in setOf(request.host.windowId, request.eventWindowId) &&
                    activeRootWindow?.id == root.windowId &&
                    activeRootWindow.isActive &&
                    activeRootWindow.type == AccessibilityWindowInfo.TYPE_APPLICATION
            } == true
            val matchingWindowAllowsSourceFallback = matchingWindows.isEmpty() ||
                (matchingWindows.size == 1 &&
                    matchingWindow?.isActive == true &&
                    matchingWindow.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                    matchingRoot == null)
            if (matchingWindowAllowsSourceFallback && activeRootProvesTargetForeground &&
                activeRoot.windowId != request.eventWindowId
            ) {
                if (deadlineReached()) return Result.Retryable
                event?.source?.let { source ->
                    sourcePackageName = source.packageName?.toString()
                    sourceWindowId = source.windowId
                    sourceRoot = ascendWithinEventWindow(
                        source,
                        request.host.packageName,
                        request.eventWindowId,
                    )
                }
                sourceWindow = sourceRoot?.let { runCatching { it.window }.getOrNull() }
            }

            val evidence = CaptureWindowBoundaryEvidence(
                sessionId = request.sessionId,
                eventPackageName = request.eventPackageName,
                eventWindowId = request.eventWindowId,
                matchingWindowCount = matchingWindows.size,
                matchingWindowType = matchingWindow?.type,
                matchingWindowActive = matchingWindow?.isActive,
                matchingRootAvailable = matchingRoot != null,
                matchingRootPackageName = matchingRoot?.packageName?.toString(),
                matchingRootWindowId = matchingRoot?.windowId,
                activeRootPackageName = activeRoot?.packageName?.toString(),
                activeRootWindowId = activeRoot?.windowId,
                activeRootWindowAvailable = activeRootWindow != null,
                activeRootWindowObjectId = activeRootWindow?.id,
                activeRootWindowType = activeRootWindow?.type,
                activeRootWindowActive = activeRootWindow?.isActive,
                sourcePackageName = sourcePackageName,
                sourceWindowId = sourceWindowId,
                sourceWindowAvailable = sourceWindow != null,
                sourceWindowObjectId = sourceWindow?.id,
                sourceWindowType = sourceWindow?.type,
                sourceWindowActive = sourceWindow?.isActive,
            )
            return when (val decision = CaptureWindowBoundaryPolicy.decide(request.host, evidence)) {
                is Decision.AcceptPrimary -> {
                    val root = rootFor(decision.source, matchingRoot, activeRoot, sourceRoot)
                        ?: return Result.Retryable
                    if (deadlineReached()) return Result.Retryable
                    Result.Success(
                        value = block(
                            ResolvedRoot(
                                node = root,
                                source = decision.source,
                                ownedChild = false,
                                deadlineAtElapsedMs = deadlineAtElapsedMs,
                            ),
                        ),
                        source = decision.source,
                        ownedChild = false,
                    )
                }

                is Decision.AcceptOwnedChild -> {
                    val root = rootFor(decision.source, matchingRoot, activeRoot, sourceRoot)
                        ?: return Result.Retryable
                    if (deadlineReached()) return Result.Retryable
                    Result.Success(
                        value = block(
                            ResolvedRoot(
                                node = root,
                                source = decision.source,
                                ownedChild = true,
                                deadlineAtElapsedMs = deadlineAtElapsedMs,
                            ),
                        ),
                        source = decision.source,
                        ownedChild = true,
                    )
                }

                is Decision.Retryable -> Result.Retryable
                is Decision.Reject -> Result.Rejected(decision.reason)
            }
        } finally {
            matchingRoot?.recycle()
            activeRoot?.recycle()
            sourceRoot?.recycle()
            sourceWindow?.recycle()
            ownedActiveRootWindow?.recycle()
            currentWindows?.forEach(AccessibilityWindowInfo::recycle)
        }
    }

    private fun Request.hasValidHostBoundary(): Boolean =
        sessionId == host.sessionId &&
            eventPackageName == host.packageName &&
            host.activityName.isNotBlank()

    @Suppress("DEPRECATION")
    private fun ascendWithinEventWindow(
        initialNode: AccessibilityNodeInfo,
        expectedPackageName: String,
        expectedWindowId: Int,
    ): AccessibilityNodeInfo? {
        if (initialNode.packageName?.toString() != expectedPackageName ||
            initialNode.windowId != expectedWindowId
        ) {
            initialNode.recycle()
            return null
        }
        var current = initialNode
        var depth = 0
        while (depth < MAX_SOURCE_PARENT_DEPTH) {
            val parent = runCatching { current.parent }.getOrNull() ?: break
            if (parent.packageName?.toString() != expectedPackageName ||
                parent.windowId != expectedWindowId
            ) {
                parent.recycle()
                break
            }
            current.recycle()
            current = parent
            depth++
        }
        return current
    }

    private fun rootFor(
        source: CaptureWindowResolutionSource,
        matchingRoot: AccessibilityNodeInfo?,
        activeRoot: AccessibilityNodeInfo?,
        sourceRoot: AccessibilityNodeInfo?,
    ): AccessibilityNodeInfo? = when (source) {
        CaptureWindowResolutionSource.WINDOW_LIST -> matchingRoot
        CaptureWindowResolutionSource.ACTIVE_ROOT -> activeRoot
        CaptureWindowResolutionSource.EVENT_SOURCE -> sourceRoot
    }

    data class Request(
        val sessionId: Long,
        val eventPackageName: String,
        val eventWindowId: Int,
        val host: VerifiedCaptureHost,
    )

    data class ResolvedRoot(
        val node: AccessibilityNodeInfo,
        val source: CaptureWindowResolutionSource,
        val ownedChild: Boolean,
        val deadlineAtElapsedMs: Long? = null,
    )

    sealed interface Result<out T> {
        data class Success<T>(
            val value: T,
            val source: CaptureWindowResolutionSource,
            val ownedChild: Boolean,
        ) : Result<T>

        data object Retryable : Result<Nothing>
        data class Rejected(val reason: RejectReason) : Result<Nothing>
    }

    private companion object {
        const val MAX_SOURCE_PARENT_DEPTH = 40
    }
}
