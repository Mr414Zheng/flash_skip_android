package com.zhenghuaizhi.flashskip.platform.accessibility

import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.zhenghuaizhi.flashskip.BuildConfig
import com.zhenghuaizhi.flashskip.domain.model.CaptureSession
import com.zhenghuaizhi.flashskip.domain.model.ClickRecoveryDiagnostics
import java.util.concurrent.atomic.AtomicLong

/** 采集诊断仅限 Debug 构建，且只输出状态、计数与耗时，不读取事件源节点。 */
internal object CaptureTraceLog : CaptureTraceLogger(enabled = BuildConfig.DEBUG)

internal open class CaptureTraceLogger(private val enabled: Boolean) {
    private val sequence = AtomicLong(0)

    @Volatile
    private var currentSessionId: Long? = null

    fun state(session: CaptureSession?) {
        if (!enabled) return
        val previousSessionId = currentSessionId
        if (session == null && previousSessionId == null) return
        stage(
            sessionId = session?.id,
            name = if (session == null) "capture_session_cleared" else "capture_state",
            "phase" to session?.phase,
            "failure" to session?.failure,
            "hasPendingSample" to (session?.pendingSample != null),
            "hasObservedCandidate" to (session?.pendingObservedCandidate != null),
        )
    }

    fun event(session: CaptureSession, event: AccessibilityEvent) {
        if (!enabled) return
        // 诊断不需要页面语义；即使已验证目标，也不读取 text、description 或 source。
        stage(
            sessionId = session.id,
            name = "accessibility_event",
            "phase" to session.phase,
            "type" to event.eventType,
            "action" to event.action,
            "contentChanges" to event.contentChangeTypes,
            "recordCount" to event.recordCount,
        )
    }

    fun windowBoundary(
        request: AccessibilityCaptureWindowResolver.Request,
        evidence: CaptureWindowBoundaryEvidence,
        decision: CaptureWindowBoundaryPolicy.Decision,
        deadlineAtElapsedMs: Long?,
    ) {
        if (!enabled) return
        stage(
            request.sessionId,
            "window_boundary",
            "activeHostFallback" to request.allowActiveHostRootFallback,
            "windowCount" to evidence.matchingWindowCount,
            "windowType" to evidence.matchingWindowType,
            "windowActive" to evidence.matchingWindowActive,
            "windowRoot" to evidence.matchingRootAvailable,
            "activeRootType" to evidence.activeRootWindowType,
            "activeRootActive" to evidence.activeRootWindowActive,
            "sourceWindowType" to evidence.sourceWindowType,
            "sourceWindowActive" to evidence.sourceWindowActive,
            "accepted" to (decision is CaptureWindowBoundaryPolicy.Decision.AcceptPrimary ||
                decision is CaptureWindowBoundaryPolicy.Decision.AcceptOwnedChild),
            "resolution" to when (decision) {
                is CaptureWindowBoundaryPolicy.Decision.AcceptPrimary -> decision.source
                is CaptureWindowBoundaryPolicy.Decision.AcceptOwnedChild -> decision.source
                is CaptureWindowBoundaryPolicy.Decision.Retryable -> decision.reason
                is CaptureWindowBoundaryPolicy.Decision.Reject -> decision.reason
            },
            "remainingMs" to deadlineAtElapsedMs?.let {
                (it - SystemClock.elapsedRealtime()).coerceAtLeast(0)
            },
        )
    }

    fun probe(
        sessionId: Long,
        allowBareNumericCountdown: Boolean,
        diagnostics: ClickRecoveryDiagnostics,
    ) {
        if (!enabled) return
        stage(
            sessionId,
            "pre_click_probe",
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

    /** 阶段与字段名必须使用代码常量；值仅接受状态枚举、布尔与整数。 */
    fun stage(sessionId: Long?, name: String, vararg fields: Pair<String, Any?>) {
        if (!enabled) return
        if (sessionId != currentSessionId) {
            sequence.set(0)
            currentSessionId = sessionId
        }
        // 自由文本、集合与对象一律丢弃，防止新增调用点意外把页面数据写入日志。
        val details = fields.mapNotNull { (key, value) ->
            when (value) {
                is Boolean, is Int, is Long -> "$key=$value"
                is Enum<*> -> "$key=${value.name}"
                else -> null
            }
        }.joinToString(separator = " ")
        Log.d(
            TAG,
            "seq=${sequence.incrementAndGet()} elapsed=${SystemClock.elapsedRealtime()} " +
                "stage=$name $details",
        )
    }

    companion object {
        const val TAG = "FlashSkipCapture"
    }
}

internal enum class CaptureTraceReason {
    MISSING_PACKAGE,
    SYSTEM_UI_WITHOUT_VERIFIED_HOST,
    LOCKED_PACKAGE_MISMATCH,
    PACKAGE_IGNORED_BEFORE_TARGET,
    HOST_NOT_VERIFIED,
    SESSION_LOCKED,
    WINDOW_POLICY,
    NO_FRESH_SNAPSHOT,
    NO_SELECTOR_CANDIDATE,
}
