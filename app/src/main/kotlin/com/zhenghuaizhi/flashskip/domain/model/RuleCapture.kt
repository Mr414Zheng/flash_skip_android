package com.zhenghuaizhi.flashskip.domain.model

import java.util.Collections
import java.util.ArrayList
import java.util.EnumMap

enum class CapturePhase {
    CONFIGURING,
    WAITING_FOR_TARGET,
    OBSERVING_CLICK,
    AWAITING_CONFIRMATION,
    CAPTURED,
    FAILED,
}

enum class CaptureEvidence {
    ACCESSIBILITY_CLICK,
    USER_CONFIRMED_UNREPORTED_CLICK,
}

enum class CaptureFailure {
    SERVICE_UNAVAILABLE,
    CONFIGURATION_TIMEOUT,
    TARGET_TIMEOUT,
    CLICK_TIMEOUT,
    COMPLETION_TIMEOUT,
    RETURNED_BEFORE_TARGET,
    DISMISS_NOT_CAPTURED,
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
    val explicitDismissNodeCount: Int = 0,
    val selectorCandidateNodeCount: Int = 0,
    val countdownSemanticNodeCount: Int = 0,
    val countdownActionChainCount: Int = 0,
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

enum class SemanticDiffOutcome {
    BASELINE,
    TRACKING,
    INSUFFICIENT_COVERAGE,
    AMBIGUOUS,
    CANDIDATE,
}

data class SemanticDiffDiagnostics(
    val outcome: SemanticDiffOutcome,
    val observationCount: Int = 0,
    val currentCandidateCount: Int = 0,
    val stableCandidateCount: Int = 0,
    val disappearedCandidateCount: Int = 0,
    val visitedNodeCount: Int = 0,
    val transitionSignal: Boolean = false,
)

enum class CaptureDiagnosticRecordKind {
    SCAN,
    SEMANTIC_DIFF,
    WINDOW_RESOLUTION,
    HOST_WINDOW_UPDATE,
}

/** 区分常规预点击扫描与缺少点击源时的即时恢复扫描。 */
enum class CaptureDiagnosticScanPurpose {
    PRE_CLICK_REFRESH,
    CLICK_RECOVERY,
}

enum class CaptureDiagnosticWindowSource {
    WINDOW_LIST,
    ACTIVE_ROOT,
    EVENT_SOURCE,
}

enum class CaptureDiagnosticClearReason {
    HOST_WINDOW_CHANGED,
    HOST_IDENTITY_CHANGED,
}

/** 单条采集轨迹只保存相对时间、窗口数字、计数和固定枚举，不携带页面内容。 */
data class CaptureDiagnosticRecord(
    val elapsedMs: Long,
    val kind: CaptureDiagnosticRecordKind,
    val eventId: Int? = null,
    val eventWindowId: Int? = null,
    val hostWindowId: Int? = null,
    val rootWindowId: Int? = null,
    val rootSource: CaptureDiagnosticWindowSource? = null,
    val ownedChild: Boolean? = null,
    val fallbackAllowed: Boolean? = null,
    val retry: Boolean? = null,
    val resolutionOutcome: TargetWindowRecognitionOutcome? = null,
    val scanOutcome: ClickRecoveryOutcome? = null,
    val scanPurpose: CaptureDiagnosticScanPurpose? = null,
    val closeSemanticCount: Int? = null,
    val effectiveCandidateCount: Int? = null,
    val countdownSemanticCount: Int? = null,
    val visitedNodeCount: Int? = null,
    val scanCompleted: Boolean? = null,
    val traversalLimitReason: TraversalLimitReason? = null,
    val semanticDiffOutcome: SemanticDiffOutcome? = null,
    val diffObservationCount: Int? = null,
    val diffCurrentCandidateCount: Int? = null,
    val diffStableCandidateCount: Int? = null,
    val diffDisappearedCandidateCount: Int? = null,
    val transitionSignal: Boolean? = null,
    val previousHostWindowId: Int? = null,
    val currentHostWindowId: Int? = null,
    val differenceCleared: Boolean? = null,
    val clearReason: CaptureDiagnosticClearReason? = null,
)

/** 汇总覆盖整个会话，确保超出近期记录上限的扫描结果仍可回溯。 */
data class CaptureDiagnosticSummary(
    val totalRecordCount: Int = 0,
    val scanCount: Int = 0,
    val completedScanCount: Int = 0,
    val incompleteScanCount: Int = 0,
    val traversalLimitScanCount: Int = 0,
    val scansWithCloseSemantics: Int = 0,
    val maxCloseSemanticCount: Int = 0,
    val maxCountdownSemanticCount: Int = 0,
    val maxEffectiveCandidateCount: Int = 0,
    val semanticDiffCount: Int = 0,
    val transitionSignalCount: Int = 0,
    val maxDiffCurrentCandidateCount: Int = 0,
    val maxDiffStableCandidateCount: Int = 0,
    val maxDiffDisappearedCandidateCount: Int = 0,
    val differenceClearCount: Int = 0,
    val windowResolutionCount: Int = 0,
    val windowResolutionRetryCount: Int = 0,
    val windowResolutionOutcomeCounts: Map<TargetWindowRecognitionOutcome, Int> = emptyMap(),
    val hostWindowUpdateCount: Int = 0,
    val droppedRecordCount: Int = 0,
)

/** 记录列表在创建时复制并设为只读，避免快照暴露采集器的可变缓冲区。 */
class CaptureDiagnosticTrace private constructor(
    val summary: CaptureDiagnosticSummary,
    records: List<CaptureDiagnosticRecord>,
) {
    val records: List<CaptureDiagnosticRecord> =
        Collections.unmodifiableList(ArrayList(records))

    override fun equals(other: Any?): Boolean =
        other is CaptureDiagnosticTrace && summary == other.summary && records == other.records

    override fun hashCode(): Int = 31 * summary.hashCode() + records.hashCode()

    override fun toString(): String = "CaptureDiagnosticTrace(summary=$summary, records=$records)"

    companion object {
        fun snapshot(
            summary: CaptureDiagnosticSummary,
            records: List<CaptureDiagnosticRecord>,
        ): CaptureDiagnosticTrace {
            val boundedRecords = records.takeLast(MAX_RECORDS)
            val totalRecordCount = maxOf(summary.totalRecordCount, records.size)
            val normalizedSummary = summary.copy(
                totalRecordCount = totalRecordCount,
                droppedRecordCount = maxOf(
                    summary.droppedRecordCount,
                    (totalRecordCount - boundedRecords.size).coerceAtLeast(0),
                ).coerceAtMost(totalRecordCount),
                windowResolutionOutcomeCounts = Collections.unmodifiableMap(
                    EnumMap<TargetWindowRecognitionOutcome, Int>(
                        TargetWindowRecognitionOutcome::class.java,
                    ).apply { putAll(summary.windowResolutionOutcomeCounts) },
                ),
            )
            return CaptureDiagnosticTrace(normalizedSummary, boundedRecords)
        }

        private const val MAX_RECORDS = 48
    }
}

data class CaptureFailureDiagnostics(
    val sourceAvailable: Boolean,
    val eventTextCount: Int,
    val eventHasContentDescription: Boolean,
    val copiedNodeCount: Int = 0,
    val selectorCandidateCount: Int = 0,
    val immediateRecovery: ClickRecoveryDiagnostics? = null,
    val preClickProbe: ClickRecoveryDiagnostics? = null,
    val snapshotRecovery: SnapshotRecoveryDiagnostics? = null,
    val semanticDiff: SemanticDiffDiagnostics? = null,
    val lastWindowVerifiedAgeMs: Long? = null,
    val lastContentChangedAgeMs: Long? = null,
    val clickDiagnosticApplicable: Boolean = true,
    val sessionTrace: CaptureDiagnosticTrace? = null,
)

enum class TargetWindowRecognitionOutcome {
    NO_WINDOW_STATE_EVENT,
    WINDOW_METADATA_UNAVAILABLE,
    EVENT_WINDOW_NOT_FOUND,
    EVENT_WINDOW_AMBIGUOUS,
    WINDOW_NOT_ACTIVE,
    NON_APPLICATION_WINDOW,
    ACTIVITY_UNVERIFIED,
    OWNED_CHILD_WINDOW,
    VERIFIED,
    CHILD_WINDOW_PENDING,
    RESOLVED_FROM_WINDOW_LIST,
    RESOLVED_FROM_ACTIVE_ROOT,
    RESOLVED_FROM_EVENT_SOURCE,
    WINDOW_ROOT_MISMATCH,
    ACTIVE_ROOT_MISMATCH,
    SOURCE_MISMATCH,
    RETRY_EXHAUSTED,
}

data class TargetWindowDiagnostics(
    val windowStateEventCount: Int = 0,
    val contentChangedEventCount: Int = 0,
    val viewClickedEventCount: Int = 0,
    val ignoredPackageEventCount: Int = 0,
    val missingPackageEventCount: Int = 0,
    val windowResolutionAttemptCount: Int = 0,
    val windowRetryAttemptCount: Int = 0,
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
    val actionAncestorDistance: Int? = null,
) {
    val conditionCount: Int
        get() = listOf(resourceId, text, contentDescription, ancestorResourceId).count { it != null }

    fun toNodeSelector(): NodeSelector = NodeSelector(
        resourceId = resourceId,
        text = text?.let(TextCondition::Exact),
        contentDescription = contentDescription,
        ancestorResourceId = ancestorResourceId,
        ancestorDistance = ancestorDistance,
        actionAncestorDistance = actionAncestorDistance,
    )

    fun stableKey(): String {
        val legacyFields = listOf(
            resourceId.orEmpty(),
            text.orEmpty(),
            contentDescription.orEmpty(),
            ancestorResourceId.orEmpty(),
            ancestorDistance?.toString().orEmpty(),
        )
        return if (actionAncestorDistance == null) {
            legacyFields.joinToString("\u001f")
        } else {
            (legacyFields + actionAncestorDistance.toString()).joinToString("\u001f")
        }
    }
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
    val evidence: CaptureEvidence = CaptureEvidence.ACCESSIBILITY_CLICK,
)

data class ObservedDismissCandidate(
    val id: Long,
    val targetAppId: TargetAppId,
    val targetAppName: String,
    val activityName: String,
    val observedAfterTargetMs: Long,
    val observedAtEpochMs: Long,
    val nodes: List<CapturedNode>,
    val preClickUniqueSelectors: List<CapturedSelector>,
)

data class SemanticDiffEvidence(
    val targetAppId: TargetAppId,
    val activityName: String,
    val selectors: List<CapturedSelector>,
    val capturedAtEpochMs: Long,
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
    /** 仅记录本次采集因用户离开 FlashSkip 而等待返回，用于区分初始恢复与真实返回。 */
    val leftFlashSkipAtEpochMs: Long? = null,
    /** 候选待确认期间是否仍监听匹配该候选的真实点击；旧持久化数据默认关闭。 */
    val candidateClickListeningEnabled: Boolean = false,
    val pendingSample: CaptureSample? = null,
    val pendingObservedCandidate: ObservedDismissCandidate? = null,
    val failure: CaptureFailure? = null,
    val failureDiagnostics: CaptureFailureDiagnostics? = null,
    val targetWindowDiagnostics: TargetWindowDiagnostics? = null,
) {
    val blocksAutomation: Boolean
        get() = phase != CapturePhase.FAILED

    val listensForCandidateClick: Boolean
        get() = candidateClickListeningEnabled &&
            phase == CapturePhase.AWAITING_CONFIRMATION &&
            pendingObservedCandidate != null
}

data class RuleCaptureState(
    val samples: List<CaptureSample> = emptyList(),
    val activeSession: CaptureSession? = null,
    val semanticDiffEvidence: List<SemanticDiffEvidence> = emptyList(),
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
