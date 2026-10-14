package com.zhenghuaizhi.flashskip.platform.accessibility

internal object CaptureWindowBoundaryPolicy {
    fun decide(
        host: VerifiedCaptureHost?,
        evidence: CaptureWindowBoundaryEvidence,
        allowActiveHostRootFallback: Boolean = false,
    ): Decision {
        if (host == null || host.activityName.isBlank()) {
            return Decision.Reject(RejectReason.HOST_UNVERIFIED)
        }
        if (host.sessionId != evidence.sessionId) {
            return Decision.Reject(RejectReason.SESSION_MISMATCH)
        }
        if (host.packageName != evidence.eventPackageName) {
            return Decision.Reject(RejectReason.PACKAGE_MISMATCH)
        }

        val ownedChild = evidence.eventWindowId != host.windowId
        fun accept(source: CaptureWindowResolutionSource): Decision =
            if (ownedChild) {
                Decision.AcceptOwnedChild(source)
            } else {
                Decision.AcceptPrimary(source)
            }

        if (evidence.matchingWindowCount > 1) {
            return Decision.Reject(RejectReason.EVENT_WINDOW_AMBIGUOUS)
        }
        if (evidence.matchingWindowCount == 1 &&
            evidence.matchingWindowActive == true &&
            evidence.matchingWindowType == APPLICATION_WINDOW_TYPE &&
            evidence.matchingRootAvailable
        ) {
            if (evidence.matchingRootPackageName != host.packageName ||
                evidence.matchingRootWindowId != evidence.eventWindowId
            ) {
                return Decision.Reject(RejectReason.WINDOW_ROOT_MISMATCH)
            }
            return accept(CaptureWindowResolutionSource.WINDOW_LIST)
        }

        val activeRootProvesVerifiedHost =
            evidence.activeRootPackageName == host.packageName &&
                evidence.activeRootWindowId == host.windowId &&
                evidence.activeRootWindowAvailable &&
                evidence.activeRootWindowObjectId == evidence.activeRootWindowId &&
                evidence.activeRootWindowActive == true &&
                evidence.activeRootWindowType == APPLICATION_WINDOW_TYPE
        // 内容变化可能来自已销毁的广告子窗口。仅回退到仍处于前台的已验证宿主根节点，
        // 点击事件不会开启此门禁，避免把失效窗口中的点击归到其他节点。
        if (allowActiveHostRootFallback && activeRootProvesVerifiedHost) {
            return Decision.AcceptPrimary(CaptureWindowResolutionSource.ACTIVE_ROOT)
        }

        if (evidence.matchingWindowCount == 1) {
            if (evidence.matchingWindowActive != true) {
                return Decision.Reject(RejectReason.WINDOW_NOT_ACTIVE)
            }
            if (evidence.matchingWindowType != APPLICATION_WINDOW_TYPE) {
                return Decision.Reject(RejectReason.NON_APPLICATION_WINDOW)
            }
        }

        val activeRootProvesHostForeground =
            evidence.activeRootPackageName == host.packageName &&
                evidence.activeRootWindowId in setOf(host.windowId, evidence.eventWindowId)
        if (!activeRootProvesHostForeground) {
            return if (evidence.activeRootPackageName == null &&
                evidence.activeRootWindowId == null
            ) {
                Decision.Retryable(RetryReason.ACTIVE_ROOT_UNAVAILABLE)
            } else {
                Decision.Reject(RejectReason.ACTIVE_ROOT_MISMATCH)
            }
        }
        if (!evidence.activeRootWindowAvailable) {
            return Decision.Retryable(RetryReason.ACTIVE_ROOT_WINDOW_UNAVAILABLE)
        }
        if (evidence.activeRootWindowObjectId != evidence.activeRootWindowId ||
            evidence.activeRootWindowActive != true
        ) {
            return Decision.Reject(RejectReason.ACTIVE_ROOT_MISMATCH)
        }
        if (evidence.activeRootWindowType != APPLICATION_WINDOW_TYPE) {
            return Decision.Reject(RejectReason.NON_APPLICATION_WINDOW)
        }
        if (evidence.activeRootWindowId == evidence.eventWindowId) {
            return accept(CaptureWindowResolutionSource.ACTIVE_ROOT)
        }

        if (evidence.sourcePackageName == null && evidence.sourceWindowId == null) {
            return Decision.Retryable(RetryReason.EVENT_SOURCE_UNAVAILABLE)
        }
        if (evidence.sourcePackageName != host.packageName ||
            evidence.sourceWindowId != evidence.eventWindowId
        ) {
            return Decision.Reject(RejectReason.SOURCE_MISMATCH)
        }
        // 节点包名和窗口 ID 不能替代窗口活动状态；元数据缺失仅允许有限重试。
        if (!evidence.sourceWindowAvailable) {
            return Decision.Retryable(RetryReason.EVENT_SOURCE_UNAVAILABLE)
        }
        if (evidence.sourceWindowObjectId != evidence.eventWindowId ||
            evidence.sourceWindowActive != true ||
            evidence.sourceWindowType != APPLICATION_WINDOW_TYPE
        ) {
            return Decision.Reject(RejectReason.SOURCE_WINDOW_INVALID)
        }
        return accept(CaptureWindowResolutionSource.EVENT_SOURCE)
    }

    data class VerifiedCaptureHost(
        val sessionId: Long,
        val packageName: String,
        val activityName: String,
        val windowId: Int,
    )

    data class CaptureWindowBoundaryEvidence(
        val sessionId: Long,
        val eventPackageName: String,
        val eventWindowId: Int,
        val matchingWindowCount: Int = 0,
        val matchingWindowType: Int? = null,
        val matchingWindowActive: Boolean? = null,
        val matchingRootAvailable: Boolean = false,
        val matchingRootPackageName: String? = null,
        val matchingRootWindowId: Int? = null,
        val activeRootPackageName: String? = null,
        val activeRootWindowId: Int? = null,
        val activeRootWindowAvailable: Boolean = false,
        val activeRootWindowObjectId: Int? = null,
        val activeRootWindowType: Int? = null,
        val activeRootWindowActive: Boolean? = null,
        val sourcePackageName: String? = null,
        val sourceWindowId: Int? = null,
        val sourceWindowAvailable: Boolean = false,
        val sourceWindowObjectId: Int? = null,
        val sourceWindowType: Int? = null,
        val sourceWindowActive: Boolean? = null,
    )

    enum class CaptureWindowResolutionSource {
        WINDOW_LIST,
        ACTIVE_ROOT,
        EVENT_SOURCE,
    }

    enum class RejectReason {
        HOST_UNVERIFIED,
        SESSION_MISMATCH,
        PACKAGE_MISMATCH,
        EVENT_WINDOW_AMBIGUOUS,
        WINDOW_NOT_ACTIVE,
        NON_APPLICATION_WINDOW,
        WINDOW_ROOT_MISMATCH,
        ACTIVE_ROOT_MISMATCH,
        SOURCE_MISMATCH,
        SOURCE_WINDOW_INVALID,
    }

    enum class RetryReason {
        ACTIVE_ROOT_UNAVAILABLE,
        ACTIVE_ROOT_WINDOW_UNAVAILABLE,
        EVENT_SOURCE_UNAVAILABLE,
    }

    sealed interface Decision {
        data class AcceptPrimary(val source: CaptureWindowResolutionSource) : Decision
        data class AcceptOwnedChild(val source: CaptureWindowResolutionSource) : Decision
        data class Retryable(val reason: RetryReason) : Decision
        data class Reject(val reason: RejectReason) : Decision
    }

    private const val APPLICATION_WINDOW_TYPE = 1
}

internal typealias VerifiedCaptureHost =
    CaptureWindowBoundaryPolicy.VerifiedCaptureHost
internal typealias CaptureWindowBoundaryEvidence =
    CaptureWindowBoundaryPolicy.CaptureWindowBoundaryEvidence
internal typealias CaptureWindowResolutionSource =
    CaptureWindowBoundaryPolicy.CaptureWindowResolutionSource
