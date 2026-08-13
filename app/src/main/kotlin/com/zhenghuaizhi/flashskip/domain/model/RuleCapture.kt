package com.zhenghuaizhi.flashskip.domain.model

enum class CapturePhase {
    CONFIGURING,
    WAITING_FOR_TARGET,
    OBSERVING_CLICK,
    CAPTURED,
    FAILED,
}

enum class CaptureFailure {
    SERVICE_UNAVAILABLE,
    CONFIGURATION_TIMEOUT,
    TARGET_TIMEOUT,
    CLICK_TIMEOUT,
    COMPLETION_TIMEOUT,
    ACTIVITY_UNAVAILABLE,
    CLICK_SOURCE_UNAVAILABLE,
    CLICK_SEMANTICS_UNAVAILABLE,
}

enum class ClickRecoveryOutcome {
    SUCCESS,
    OUTSIDE_TARGETS,
    IDENTITY_CHANGED,
    NO_ROOT,
    ROOT_MISMATCH,
    TRAVERSAL_LIMIT,
    NO_MATCH,
    AMBIGUOUS,
}

enum class TraversalLimitReason {
    NODE_COUNT,
    DEPTH,
    TIME,
}

data class ClickRecoveryDiagnostics(
    val outcome: ClickRecoveryOutcome,
    val visitedNodeCount: Int = 0,
    val maxDepth: Int = 0,
    val elapsedMs: Long = 0,
    val semanticNodeCount: Int = 0,
    val matchedNodeCount: Int = 0,
    val knownDismissNodeCount: Int = 0,
    val selectorCandidateNodeCount: Int = 0,
    val traversalLimitReason: TraversalLimitReason? = null,
)

enum class SnapshotRecoveryOutcome {
    MATCHED_EVENT,
    MATCHED_DISMISS,
    NOT_AVAILABLE,
    IDENTITY_MISMATCH,
    STALE,
    EMPTY,
    NO_MATCH,
}

data class SnapshotRecoveryDiagnostics(
    val outcome: SnapshotRecoveryOutcome,
    val ageMs: Long? = null,
)

data class CaptureFailureDiagnostics(
    val sourceAvailable: Boolean,
    val eventTextCount: Int,
    val eventHasContentDescription: Boolean,
    val copiedNodeCount: Int = 0,
    val selectorCandidateCount: Int = 0,
    val immediateRecovery: ClickRecoveryDiagnostics? = null,
    val preClickProbe: ClickRecoveryDiagnostics? = null,
    val snapshotRecovery: SnapshotRecoveryDiagnostics? = null,
    val lastWindowVerifiedAgeMs: Long? = null,
    val lastContentChangedAgeMs: Long? = null,
)

enum class TargetWindowRecognitionOutcome {
    NO_WINDOW_STATE_EVENT,
    WINDOW_METADATA_UNAVAILABLE,
    EVENT_WINDOW_NOT_FOUND,
    EVENT_WINDOW_AMBIGUOUS,
    WINDOW_NOT_ACTIVE,
    NON_APPLICATION_WINDOW,
    ACTIVITY_UNVERIFIED,
    VERIFIED,
}

data class TargetWindowDiagnostics(
    val windowStateEventCount: Int = 0,
    val contentChangedEventCount: Int = 0,
    val viewClickedEventCount: Int = 0,
    val ignoredPackageEventCount: Int = 0,
    val missingPackageEventCount: Int = 0,
    val lastPackageName: String? = null,
    val lastClassName: String? = null,
    val lastEventWindowId: Int? = null,
    val lastWindowCount: Int? = null,
    val lastMatchingWindowCount: Int? = null,
    val lastMatchingWindowType: Int? = null,
    val lastMatchingWindowActive: Boolean? = null,
    val lastOutcome: TargetWindowRecognitionOutcome =
        TargetWindowRecognitionOutcome.NO_WINDOW_STATE_EVENT,
    val lastEventAgeMs: Long? = null,
)

data class CapturedNode(
    val resourceId: String? = null,
    val text: String? = null,
    val contentDescription: String? = null,
    val clickable: Boolean = false,
    val supportsActionClick: Boolean = false,
    val visibleToUser: Boolean = false,
    val hasUsableBounds: Boolean = false,
)

data class CapturedSelector(
    val resourceId: String? = null,
    val text: String? = null,
    val contentDescription: String? = null,
    val ancestorResourceId: String? = null,
    val ancestorDistance: Int? = null,
) {
    val conditionCount: Int
        get() = listOf(resourceId, text, contentDescription, ancestorResourceId).count { it != null }

    fun toNodeSelector(): NodeSelector = NodeSelector(
        resourceId = resourceId,
        text = text?.let(TextCondition::Exact),
        contentDescription = contentDescription,
        ancestorResourceId = ancestorResourceId,
        ancestorDistance = ancestorDistance,
    )

    fun stableKey(): String = listOf(
        resourceId.orEmpty(),
        text.orEmpty(),
        contentDescription.orEmpty(),
        ancestorResourceId.orEmpty(),
        ancestorDistance?.toString().orEmpty(),
    ).joinToString("\u001f")
}

data class CaptureSample(
    val id: Long,
    val targetAppId: TargetAppId,
    val targetAppName: String = targetAppId.packageName,
    val activityName: String,
    val clickedAfterTargetMs: Long,
    val capturedAtEpochMs: Long,
    val nodes: List<CapturedNode>,
    val preClickUniqueSelectors: List<CapturedSelector>,
)

data class CaptureSession(
    val id: Long,
    val targetAppId: TargetAppId? = null,
    val targetAppName: String? = null,
    val packageName: String? = null,
    val phase: CapturePhase,
    val phaseStartedAtEpochMs: Long,
    val deadlineAtEpochMs: Long,
    val activityName: String? = null,
    val windowId: Int? = null,
    val targetEnteredAtElapsedMs: Long? = null,
    val pendingSample: CaptureSample? = null,
    val failure: CaptureFailure? = null,
    val failureDiagnostics: CaptureFailureDiagnostics? = null,
    val targetWindowDiagnostics: TargetWindowDiagnostics? = null,
) {
    val blocksAutomation: Boolean
        get() = phase != CapturePhase.FAILED
}

data class RuleCaptureState(
    val samples: List<CaptureSample> = emptyList(),
    val activeSession: CaptureSession? = null,
)

enum class RuleCardStatus {
    CANDIDATE,
    READY,
}

data class RuleCandidateAnalysis(
    val targetAppId: TargetAppId,
    val status: RuleCardStatus,
    val sampleCount: Int,
    val stableSampleCount: Int,
    val uniqueEvidenceCount: Int,
    val primarySelector: CapturedSelector?,
    val probeSelectors: List<CapturedSelector>,
    val allowedActivities: Set<String>,
    val minimumClickDelayMs: Long?,
    val rules: List<AdRule>,
) {
    val rule: AdRule?
        get() = rules.firstOrNull()
}
