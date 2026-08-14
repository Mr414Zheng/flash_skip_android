package com.zhenghuaizhi.flashskip.platform.accessibility

import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.zhenghuaizhi.flashskip.domain.model.CaptureSession
import com.zhenghuaizhi.flashskip.domain.model.ClickRecoveryDiagnostics
import java.util.concurrent.atomic.AtomicLong

/**
 * 采集诊断只写入 Logcat，不持久化事件或节点对象。目标包未锁定前仅记录事件外壳，
 * 避免把 Home 或其他应用的语义内容写入日志。
 */
internal object CaptureTraceLog {
    const val TAG = "FlashSkipCapture"

    private val sequence = AtomicLong(0)

    @Volatile
    private var currentSessionId: Long? = null

    fun state(session: CaptureSession?) {
        val previousSessionId = currentSessionId
        if (session == null && previousSessionId == null) return
        if (session?.id != previousSessionId) {
            sequence.set(0)
            currentSessionId = session?.id
        }
        stage(
            sessionId = session?.id ?: previousSessionId,
            name = if (session == null) "capture_session_cleared" else "capture_state",
            "phase" to session?.phase,
            "package" to session?.packageName,
            "target" to session?.targetAppName,
            "activity" to session?.activityName,
            "windowId" to session?.windowId,
            "failure" to session?.failure,
            "hasPendingSample" to (session?.pendingSample != null),
            "hasObservedCandidate" to (session?.pendingObservedCandidate != null),
        )
    }

    @Suppress("DEPRECATION")
    fun event(
        session: CaptureSession,
        event: AccessibilityEvent,
        verifiedHostPackage: String?,
    ) {
        val reportedPackage = event.packageName?.toString()
        val targetPackage = session.packageName ?: verifiedHostPackage
        val mayInspectSource = targetPackage != null && reportedPackage == targetPackage
        val eventText = if (mayInspectSource) {
            event.text.take(MAX_EVENT_TEXT_ITEMS).map { sanitize(it?.toString(), MAX_SEMANTIC_LENGTH) }
        } else {
            emptyList()
        }
        val description = if (mayInspectSource) {
            sanitize(event.contentDescription?.toString(), MAX_SEMANTIC_LENGTH)
        } else {
            null
        }
        val source = if (mayInspectSource) {
            runCatching { event.source }.getOrNull()
        } else {
            null
        }
        try {
            stage(
                sessionId = session.id,
                name = "accessibility_event",
                "phase" to session.phase,
                "type" to eventTypeName(event.eventType),
                "typeValue" to event.eventType,
                "eventTime" to event.eventTime,
                "package" to reportedPackage,
                "class" to sanitize(event.className?.toString(), MAX_CLASS_LENGTH),
                "windowId" to event.windowId,
                "action" to event.action,
                "contentChanges" to event.contentChangeTypes,
                "recordCount" to event.recordCount,
                "text" to eventText.takeIf { it.isNotEmpty() },
                "description" to description,
                "sourcePolicy" to if (mayInspectSource) "target" else "redacted",
                "source" to source?.summary(),
            )
        } finally {
            source?.recycle()
        }
    }

    fun windowBoundary(
        request: AccessibilityCaptureWindowResolver.Request,
        evidence: CaptureWindowBoundaryEvidence,
        decision: CaptureWindowBoundaryPolicy.Decision,
        deadlineAtElapsedMs: Long?,
    ) {
        stage(
            request.sessionId,
            "window_boundary",
            "eventPackage" to request.eventPackageName,
            "eventWindowId" to request.eventWindowId,
            "hostWindowId" to request.host.windowId,
            "activeHostFallback" to request.allowActiveHostRootFallback,
            "windowCount" to evidence.matchingWindowCount,
            "windowType" to evidence.matchingWindowType,
            "windowActive" to evidence.matchingWindowActive,
            "windowRoot" to evidence.matchingRootAvailable,
            "activeRootPackage" to evidence.activeRootPackageName,
            "activeRootWindowId" to evidence.activeRootWindowId,
            "activeRootType" to evidence.activeRootWindowType,
            "activeRootActive" to evidence.activeRootWindowActive,
            "sourcePackage" to evidence.sourcePackageName,
            "sourceWindowId" to evidence.sourceWindowId,
            "sourceWindowType" to evidence.sourceWindowType,
            "sourceWindowActive" to evidence.sourceWindowActive,
            "decision" to decision,
            "deadline" to deadlineAtElapsedMs,
        )
    }

    fun probe(
        sessionId: Long,
        windowId: Int,
        allowBareNumericCountdown: Boolean,
        diagnostics: ClickRecoveryDiagnostics,
    ) {
        stage(
            sessionId,
            "pre_click_probe",
            "windowId" to windowId,
            "allowBareNumeric" to allowBareNumericCountdown,
            "outcome" to diagnostics.outcome,
            "visited" to diagnostics.visitedNodeCount,
            "depth" to diagnostics.maxDepth,
            "elapsedMs" to diagnostics.elapsedMs,
            "semantic" to diagnostics.semanticNodeCount,
            "matched" to diagnostics.matchedNodeCount,
            "knownDismiss" to diagnostics.knownDismissNodeCount,
            "selectorCandidates" to diagnostics.selectorCandidateNodeCount,
            "countdownSemantic" to diagnostics.countdownSemanticNodeCount,
            "countdownActionChains" to diagnostics.countdownActionChainCount,
            "limit" to diagnostics.traversalLimitReason,
        )
    }

    fun stage(
        sessionId: Long?,
        name: String,
        vararg fields: Pair<String, Any?>,
    ) {
        val details = fields.joinToString(separator = " ") { (key, value) ->
            "$key=${format(value)}"
        }
        Log.d(
            TAG,
            "seq=${sequence.incrementAndGet()} elapsed=${SystemClock.elapsedRealtime()} " +
                "session=${format(sessionId)} stage=$name $details",
        )
    }

    private fun AccessibilityNodeInfo.summary(): String {
        val resourceId = runCatching { viewIdResourceName }.getOrNull()
        return listOf(
            "pkg=${sanitize(packageName?.toString(), MAX_PACKAGE_LENGTH)}",
            "class=${sanitize(className?.toString(), MAX_CLASS_LENGTH)}",
            "window=$windowId",
            "id=${sanitize(resourceId, MAX_RESOURCE_ID_LENGTH)}",
            "text=${sanitize(text?.toString(), MAX_SEMANTIC_LENGTH)}",
            "desc=${sanitize(contentDescription?.toString(), MAX_SEMANTIC_LENGTH)}",
            "clickable=$isClickable",
            "visible=$isVisibleToUser",
            "enabled=$isEnabled",
            "actions=$actions",
            "children=$childCount",
        ).joinToString(prefix = "{", postfix = "}", separator = ",")
    }

    private fun eventTypeName(type: Int): String =
        runCatching { AccessibilityEvent.eventTypeToString(type) }.getOrNull()
            ?.takeIf(String::isNotBlank)
            ?: "UNKNOWN"

    private fun format(value: Any?): String = when (value) {
        null -> "null"
        is String -> "\"${escape(value)}\""
        is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]") { format(it) }
        else -> escape(value.toString())
    }

    private fun sanitize(value: String?, maxLength: Int): String? = value
        ?.map { if (it.isISOControl()) ' ' else it }
        ?.joinToString("")
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.take(maxLength)

    private fun escape(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")

    private const val MAX_EVENT_TEXT_ITEMS = 4
    private const val MAX_SEMANTIC_LENGTH = 80
    private const val MAX_RESOURCE_ID_LENGTH = 180
    private const val MAX_PACKAGE_LENGTH = 180
    private const val MAX_CLASS_LENGTH = 240
}
