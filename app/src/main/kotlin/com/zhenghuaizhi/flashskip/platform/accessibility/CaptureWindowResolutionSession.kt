package com.zhenghuaizhi.flashskip.platform.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.zhenghuaizhi.flashskip.domain.model.TargetWindowRecognitionOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 只跨重试保存窗口标量；事件、窗口和节点始终由同步 resolver 调用持有并释放。
 */
internal class CaptureWindowResolutionSession(
    private val scope: CoroutineScope,
    private val elapsedRealtimeMs: () -> Long,
    private val attemptResolver: CaptureWindowAttemptResolver,
    private val retryRequestProvider: (WindowRetryKey) -> AccessibilityCaptureWindowResolver.Request?,
    private val onRetryResolved: (
        request: AccessibilityCaptureWindowResolver.Request,
        root: AccessibilityCaptureWindowResolver.ResolvedRoot,
    ) -> Unit,
    private val recordResolution: (
        packageName: String,
        windowId: Int,
        outcome: TargetWindowRecognitionOutcome,
        isRetry: Boolean,
        occurredAtElapsedMs: Long,
    ) -> Unit,
) {
    constructor(
        service: AccessibilityService,
        scope: CoroutineScope,
        elapsedRealtimeMs: () -> Long,
        retryRequestProvider: (WindowRetryKey) -> AccessibilityCaptureWindowResolver.Request?,
        onRetryResolved: (
            request: AccessibilityCaptureWindowResolver.Request,
            root: AccessibilityCaptureWindowResolver.ResolvedRoot,
        ) -> Unit,
        recordResolution: (
            packageName: String,
            windowId: Int,
            outcome: TargetWindowRecognitionOutcome,
            isRetry: Boolean,
            occurredAtElapsedMs: Long,
        ) -> Unit,
    ) : this(
        scope = scope,
        elapsedRealtimeMs = elapsedRealtimeMs,
        attemptResolver = AccessibilityCaptureWindowResolver(service).let { resolver ->
            CaptureWindowAttemptResolver { request, event, deadline, block ->
                resolver.withResolvedRoot(
                    request = request,
                    event = event,
                    deadlineAtElapsedMs = deadline,
                    elapsedRealtimeMs = elapsedRealtimeMs,
                    block = block,
                )
            }
        },
        retryRequestProvider = retryRequestProvider,
        onRetryResolved = onRetryResolved,
        recordResolution = recordResolution,
    )

    private val pendingRetries = mutableMapOf<WindowRetryKey, PendingRetry>()

    fun resolve(
        request: AccessibilityCaptureWindowResolver.Request,
        event: AccessibilityEvent?,
        block: (AccessibilityCaptureWindowResolver.ResolvedRoot) -> Unit,
    ) {
        val result = attemptResolver.resolve(
            request = request,
            event = event,
            deadlineAtElapsedMs = null,
            block = block,
        )
        handleResult(request, result, isRetry = false)
    }

    /** 会话结束或宿主身份变化时取消全部键；普通窗口成功只能取消自己的键。 */
    fun cancel() {
        pendingRetries.values.forEach { it.job.cancel() }
        pendingRetries.clear()
    }

    private fun cancel(key: WindowRetryKey) {
        pendingRetries.remove(key)?.job?.cancel()
    }

    private fun handleResult(
        request: AccessibilityCaptureWindowResolver.Request,
        result: AccessibilityCaptureWindowResolver.Result<Unit>,
        isRetry: Boolean,
    ) {
        val key = request.toRetryKey()
        val now = elapsedRealtimeMs()
        when (result) {
            is AccessibilityCaptureWindowResolver.Result.Success -> {
                cancel(key)
                record(request, result.source.toRecognitionOutcome(), isRetry, now)
            }

            AccessibilityCaptureWindowResolver.Result.Retryable -> {
                record(
                    request,
                    if (pendingRetries[key]?.exhausted == true) {
                        TargetWindowRecognitionOutcome.RETRY_EXHAUSTED
                    } else {
                        TargetWindowRecognitionOutcome.CHILD_WINDOW_PENDING
                    },
                    isRetry,
                    now,
                )
                if (!isRetry) schedule(request)
            }

            is AccessibilityCaptureWindowResolver.Result.Rejected -> {
                cancel(key)
                record(request, result.reason.toRecognitionOutcome(), isRetry, now)
            }
        }
    }

    private fun schedule(request: AccessibilityCaptureWindowResolver.Request) {
        val key = request.toRetryKey()
        if (key in pendingRetries) return
        val gate = CaptureWindowRetryGate().apply {
            begin(key, elapsedRealtimeMs())
        }
        val deadlineAt = requireNotNull(gate.deadlineAtElapsedMs(key))
        val job = scope.launch {
            for (delayMs in RETRY_DELAYS_MS) {
                delay(delayMs)
                if (!gate.claimAttempt(key, elapsedRealtimeMs())) break
                val retryRequest = retryRequestProvider(key) ?: run {
                    cancel(key)
                    return@launch
                }
                val result = attemptResolver.resolve(
                    request = retryRequest,
                    event = null,
                    deadlineAtElapsedMs = deadlineAt,
                ) { root ->
                    onRetryResolved(retryRequest, root)
                }
                if (elapsedRealtimeMs() > deadlineAt) {
                    recordExhausted(request)
                    return@launch
                }
                handleResult(retryRequest, result, isRetry = true)
                if (result !is AccessibilityCaptureWindowResolver.Result.Retryable) {
                    return@launch
                }
            }
            if (key in pendingRetries && retryRequestProvider(key) != null) {
                recordExhausted(request)
            }
        }
        pendingRetries[key] = PendingRetry(job)
    }

    private fun recordExhausted(request: AccessibilityCaptureWindowResolver.Request) {
        pendingRetries[request.toRetryKey()]?.exhausted = true
        record(
            request,
            TargetWindowRecognitionOutcome.RETRY_EXHAUSTED,
            isRetry = true,
            occurredAt = elapsedRealtimeMs(),
        )
    }

    private fun record(
        request: AccessibilityCaptureWindowResolver.Request,
        outcome: TargetWindowRecognitionOutcome,
        isRetry: Boolean,
        occurredAt: Long,
    ) {
        recordResolution(
            request.eventPackageName,
            request.eventWindowId,
            outcome,
            isRetry,
            occurredAt,
        )
    }

    private data class PendingRetry(
        val job: Job,
        var exhausted: Boolean = false,
    )

    private companion object {
        val RETRY_DELAYS_MS = longArrayOf(40L, 60L, 80L)
    }
}

internal fun interface CaptureWindowAttemptResolver {
    fun resolve(
        request: AccessibilityCaptureWindowResolver.Request,
        event: AccessibilityEvent?,
        deadlineAtElapsedMs: Long?,
        block: (AccessibilityCaptureWindowResolver.ResolvedRoot) -> Unit,
    ): AccessibilityCaptureWindowResolver.Result<Unit>
}

internal fun AccessibilityCaptureWindowResolver.Request.toRetryKey() = WindowRetryKey(
    sessionId = sessionId,
    packageName = eventPackageName,
    windowId = eventWindowId,
    allowActiveHostRootFallback = allowActiveHostRootFallback,
)

private fun CaptureWindowResolutionSource.toRecognitionOutcome() = when (this) {
    CaptureWindowResolutionSource.WINDOW_LIST ->
        TargetWindowRecognitionOutcome.RESOLVED_FROM_WINDOW_LIST
    CaptureWindowResolutionSource.ACTIVE_ROOT ->
        TargetWindowRecognitionOutcome.RESOLVED_FROM_ACTIVE_ROOT
    CaptureWindowResolutionSource.EVENT_SOURCE ->
        TargetWindowRecognitionOutcome.RESOLVED_FROM_EVENT_SOURCE
}

private fun CaptureWindowBoundaryPolicy.RejectReason.toRecognitionOutcome() = when (this) {
    CaptureWindowBoundaryPolicy.RejectReason.EVENT_WINDOW_AMBIGUOUS ->
        TargetWindowRecognitionOutcome.EVENT_WINDOW_AMBIGUOUS
    CaptureWindowBoundaryPolicy.RejectReason.WINDOW_NOT_ACTIVE ->
        TargetWindowRecognitionOutcome.WINDOW_NOT_ACTIVE
    CaptureWindowBoundaryPolicy.RejectReason.NON_APPLICATION_WINDOW ->
        TargetWindowRecognitionOutcome.NON_APPLICATION_WINDOW
    CaptureWindowBoundaryPolicy.RejectReason.WINDOW_ROOT_MISMATCH ->
        TargetWindowRecognitionOutcome.WINDOW_ROOT_MISMATCH
    CaptureWindowBoundaryPolicy.RejectReason.ACTIVE_ROOT_MISMATCH ->
        TargetWindowRecognitionOutcome.ACTIVE_ROOT_MISMATCH
    CaptureWindowBoundaryPolicy.RejectReason.SOURCE_MISMATCH,
    CaptureWindowBoundaryPolicy.RejectReason.SOURCE_WINDOW_INVALID,
    -> TargetWindowRecognitionOutcome.SOURCE_MISMATCH
    CaptureWindowBoundaryPolicy.RejectReason.HOST_UNVERIFIED,
    CaptureWindowBoundaryPolicy.RejectReason.SESSION_MISMATCH,
    CaptureWindowBoundaryPolicy.RejectReason.PACKAGE_MISMATCH,
    -> TargetWindowRecognitionOutcome.ACTIVITY_UNVERIFIED
}
