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
    APP_VERSION_CHANGED,
    ACTIVITY_UNAVAILABLE,
    CLICK_SOURCE_UNAVAILABLE,
    CLICK_SEMANTICS_UNAVAILABLE,
}

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
) {
    val conditionCount: Int
        get() = listOf(resourceId, text, contentDescription).count { it != null }

    fun toNodeSelector(): NodeSelector = NodeSelector(
        resourceId = resourceId,
        text = text?.let(TextCondition::Exact),
        contentDescription = contentDescription,
    )

    fun stableKey(): String = listOf(
        resourceId.orEmpty(),
        text.orEmpty(),
        contentDescription.orEmpty(),
    ).joinToString("\u001f")
}

data class CaptureSample(
    val id: Long,
    val targetAppId: TargetAppId,
    val appVersion: AppVersion,
    val activityName: String,
    val clickedAfterTargetMs: Long,
    val capturedAtEpochMs: Long,
    val nodes: List<CapturedNode>,
    val preClickUniqueSelectors: List<CapturedSelector>,
)

data class CaptureSession(
    val id: Long,
    val targetAppId: TargetAppId,
    val packageName: String,
    val appVersion: AppVersion,
    val phase: CapturePhase,
    val phaseStartedAtEpochMs: Long,
    val deadlineAtEpochMs: Long,
    val activityName: String? = null,
    val windowId: Int? = null,
    val targetEnteredAtElapsedMs: Long? = null,
    val pendingSample: CaptureSample? = null,
    val failure: CaptureFailure? = null,
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
    STALE,
}

data class RuleCandidateAnalysis(
    val targetAppId: TargetAppId,
    val appVersion: AppVersion?,
    val status: RuleCardStatus,
    val currentVersionSampleCount: Int,
    val stableSampleCount: Int,
    val uniqueEvidenceCount: Int,
    val primarySelector: CapturedSelector?,
    val probeSelectors: List<CapturedSelector>,
    val allowedActivities: Set<String>,
    val minimumClickDelayMs: Long?,
    val rule: AdRule?,
)
