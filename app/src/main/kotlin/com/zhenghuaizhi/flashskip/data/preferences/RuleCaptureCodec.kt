package com.zhenghuaizhi.flashskip.data.preferences

import com.zhenghuaizhi.flashskip.domain.model.CaptureFailure
import com.zhenghuaizhi.flashskip.domain.model.CandidateRecheckDiagnostics
import com.zhenghuaizhi.flashskip.domain.model.CaptureScanStrategy
import com.zhenghuaizhi.flashskip.domain.model.CaptureFailureDiagnostics
import com.zhenghuaizhi.flashskip.domain.model.CaptureEvidence
import com.zhenghuaizhi.flashskip.domain.model.CapturePhase
import com.zhenghuaizhi.flashskip.domain.model.CaptureSample
import com.zhenghuaizhi.flashskip.domain.model.CaptureSession
import com.zhenghuaizhi.flashskip.domain.model.CaptureDiagnosticClearReason
import com.zhenghuaizhi.flashskip.domain.model.CaptureDiagnosticRecord
import com.zhenghuaizhi.flashskip.domain.model.CaptureDiagnosticRecordKind
import com.zhenghuaizhi.flashskip.domain.model.CaptureDiagnosticScanPurpose
import com.zhenghuaizhi.flashskip.domain.model.CaptureDiagnosticSummary
import com.zhenghuaizhi.flashskip.domain.model.CaptureDiagnosticTrace
import com.zhenghuaizhi.flashskip.domain.model.CaptureDiagnosticWindowSource
import com.zhenghuaizhi.flashskip.domain.model.CapturedNode
import com.zhenghuaizhi.flashskip.domain.model.CapturedSelector
import com.zhenghuaizhi.flashskip.domain.model.ClickRecoveryDiagnostics
import com.zhenghuaizhi.flashskip.domain.model.ClickRecoveryOutcome
import com.zhenghuaizhi.flashskip.domain.model.ObservedDismissCandidate
import com.zhenghuaizhi.flashskip.domain.model.RuleCaptureState
import com.zhenghuaizhi.flashskip.domain.model.SemanticDiffDiagnostics
import com.zhenghuaizhi.flashskip.domain.model.SemanticDiffEvidence
import com.zhenghuaizhi.flashskip.domain.model.SemanticDiffOutcome
import com.zhenghuaizhi.flashskip.domain.model.SnapshotRecoveryDiagnostics
import com.zhenghuaizhi.flashskip.domain.model.SnapshotRecoveryOutcome
import com.zhenghuaizhi.flashskip.domain.model.TargetAppId
import com.zhenghuaizhi.flashskip.domain.model.TargetWindowDiagnostics
import com.zhenghuaizhi.flashskip.domain.model.CaptureStartupDiagnostics
import com.zhenghuaizhi.flashskip.domain.model.TargetWindowRecognitionOutcome
import com.zhenghuaizhi.flashskip.domain.model.TraversalLimitReason
import com.zhenghuaizhi.flashskip.domain.model.CaptureCandidateRejection
import com.zhenghuaizhi.flashskip.domain.model.CaptureScanUnavailableReason
import com.zhenghuaizhi.flashskip.domain.model.CaptureEventClockStatus
import com.zhenghuaizhi.flashskip.domain.model.CaptureEventTiming
import com.zhenghuaizhi.flashskip.domain.model.CaptureTimingDiagnostics
import com.zhenghuaizhi.flashskip.domain.ports.StorageRead
import java.io.IOException
import java.util.EnumMap
import org.json.JSONArray
import org.json.JSONObject

object RuleCaptureCodec {
    private const val VERSION = 2

    fun encode(state: RuleCaptureState): String = JSONObject()
        .put("version", VERSION)
        .put("samples", JSONArray().apply { state.samples.forEach { put(it.toJson()) } })
        .put(
            "semanticDiffEvidence",
            JSONArray().apply { state.semanticDiffEvidence.forEach { put(it.toJson()) } },
        )
        .putNullable("activeSession", state.activeSession?.toJson())
        .toString()

    fun decodeOrEmpty(raw: String?, onInvalid: () -> Unit = {}): RuleCaptureState =
        decode(raw, onInvalid).value ?: RuleCaptureState()

    internal fun decode(raw: String?, onInvalid: () -> Unit = {}): StorageRead<RuleCaptureState> {
        if (raw.isNullOrBlank()) return StorageRead(RuleCaptureState())
        var rejected = false
        val result = try {
            val json = JSONObject(raw)
            require(json.getInt("version") == VERSION)
            fun <T> JSONArray.recoverEntries(decode: (JSONObject) -> T): List<T> = buildList {
                repeat(length()) { index ->
                    val entry = runCatching { decode(getJSONObject(index)) }
                    if (entry.isFailure) rejected = true
                    entry.getOrNull()?.let(::add)
                }
            }
            val samples = json.getJSONArray("samples").recoverEntries { it.toSample() }
            val evidence = if (!json.has("semanticDiffEvidence") || json.isNull("semanticDiffEvidence")) {
                emptyList()
            } else json.getJSONArray("semanticDiffEvidence").recoverEntries { it.toSemanticDiffEvidence() }
            val session = runCatching {
                if (json.isNull("activeSession")) null else json.getJSONObject("activeSession").toSession()
            }
            // 坏会话只隔离其载荷；合法样本可展示，但必须保留不可用标记以阻止执行与覆盖。
            StorageRead(
                RuleCaptureState(samples, session.getOrNull(), evidence),
                if (session.isFailure) IOException("Unreadable capture session") else null,
            )
        } catch (_: Exception) {
            StorageRead(RuleCaptureState(), IOException("Unsupported or unreadable capture storage envelope"))
        }
        if (rejected || !result.available) {
            // 不把原始内容放入诊断。
            try { onInvalid() } catch (_: Exception) { }
        }
        return result
    }

    private fun CaptureSession.toJson() = JSONObject()
        .put("id", id)
        .putNullable("targetPackageName", targetAppId?.packageName)
        .putNullable("targetAppName", targetAppName)
        .putNullable("packageName", packageName)
        .put("phase", phase.name)
        .put("phaseStartedAtEpochMs", phaseStartedAtEpochMs)
        .put("deadlineAtEpochMs", deadlineAtEpochMs)
        .putNullable("activityName", activityName)
        .putNullable("windowId", windowId)
        .putNullable("targetEnteredAtElapsedMs", targetEnteredAtElapsedMs)
        .putNullable("leftFlashSkipAtEpochMs", leftFlashSkipAtEpochMs)
        .put("candidateClickListeningEnabled", candidateClickListeningEnabled)
        .putNullable("pendingSample", pendingSample?.toJson())
        .putNullable("pendingObservedCandidate", pendingObservedCandidate?.toJson())
        .putNullable("failure", failure?.name)
        .putNullable("failureDiagnostics", failureDiagnostics?.toJson())
        .putNullable("targetWindowDiagnostics", targetWindowDiagnostics?.toJson())

    private fun CaptureFailureDiagnostics.toJson() = JSONObject()
        .put("sourceAvailable", sourceAvailable)
        .put("eventTextCount", eventTextCount)
        .put("eventHasContentDescription", eventHasContentDescription)
        .put("copiedNodeCount", copiedNodeCount)
        .put("selectorCandidateCount", selectorCandidateCount)
        .putNullable("immediateRecovery", immediateRecovery?.toJson())
        .putNullable("preClickProbe", preClickProbe?.toJson())
        .putNullable("snapshotRecovery", snapshotRecovery?.toJson())
        .putNullable("semanticDiff", semanticDiff?.toJson())
        .putNullable("candidateRecheck", candidateRecheck?.toJson())
        .putNullable("lastWindowVerifiedAgeMs", lastWindowVerifiedAgeMs)
        .putNullable("lastContentChangedAgeMs", lastContentChangedAgeMs)
        .put("clickDiagnosticApplicable", clickDiagnosticApplicable)
        .putNullable("sessionTrace", sessionTrace?.toJson())

    private fun CaptureDiagnosticTrace.toJson() = JSONObject()
        .put("summary", summary.toJson())
        .put("records", JSONArray().apply { records.forEach { put(it.toJson()) } })

    private fun CaptureDiagnosticSummary.toJson() = JSONObject()
        .put("totalRecordCount", totalRecordCount)
        .put("scanCount", scanCount)
        .put("completedScanCount", completedScanCount)
        .put("incompleteScanCount", incompleteScanCount)
        .put("traversalLimitScanCount", traversalLimitScanCount)
        .put("scansWithCloseSemantics", scansWithCloseSemantics)
        .put("maxCloseSemanticCount", maxCloseSemanticCount)
        .put("maxCountdownSemanticCount", maxCountdownSemanticCount)
        .put("maxEffectiveCandidateCount", maxEffectiveCandidateCount)
        .put("semanticDiffCount", semanticDiffCount)
        .put("transitionSignalCount", transitionSignalCount)
        .put("maxDiffCurrentCandidateCount", maxDiffCurrentCandidateCount)
        .put("maxDiffStableCandidateCount", maxDiffStableCandidateCount)
        .put("maxDiffDisappearedCandidateCount", maxDiffDisappearedCandidateCount)
        .put("differenceClearCount", differenceClearCount)
        .put("windowResolutionCount", windowResolutionCount)
        .put("windowResolutionRetryCount", windowResolutionRetryCount)
        .put(
            "windowResolutionOutcomeCounts",
            JSONObject().apply {
                windowResolutionOutcomeCounts.toSortedMap().forEach { (outcome, count) ->
                    put(outcome.name, count)
                }
            },
        )
        .put("hostWindowUpdateCount", hostWindowUpdateCount)
        .put("droppedRecordCount", droppedRecordCount)
        .put("continuedScanCount", continuedScanCount)
        .put("candidateRecheckCount", candidateRecheckCount)
        .put("directedQueryCount", directedQueryCount)
        .put("directedPresentCount", directedPresentCount)
        .put("directedAbsentCount", directedAbsentCount)
        .put("directedUnknownCount", directedUnknownCount)
        .put("directedAmbiguousCount", directedAmbiguousCount)
        .put("completedAnchorScanCount", completedAnchorScanCount)
        .put("rejectedCandidateCounts", rejectedCandidateCounts.toRejectionCountsJson())

    private fun CaptureDiagnosticRecord.toJson() = JSONObject()
        .put("elapsedMs", elapsedMs)
        .put("kind", kind.name)
        .putNullable("eventId", eventId)
        .putNullable("eventWindowId", eventWindowId)
        .putNullable("hostWindowId", hostWindowId)
        .putNullable("rootWindowId", rootWindowId)
        .putNullable("rootSource", rootSource?.name)
        .putNullable("ownedChild", ownedChild)
        .putNullable("fallbackAllowed", fallbackAllowed)
        .putNullable("retry", retry)
        .putNullable("resolutionOutcome", resolutionOutcome?.name)
        .putNullable("scanOutcome", scanOutcome?.name)
        .putNullable("scanPurpose", scanPurpose?.name)
        .putNullable("closeSemanticCount", closeSemanticCount)
        .putNullable("effectiveCandidateCount", effectiveCandidateCount)
        .putNullable("countdownSemanticCount", countdownSemanticCount)
        .putNullable("visitedNodeCount", visitedNodeCount)
        .putNullable("scanCompleted", scanCompleted)
        .putNullable("traversalLimitReason", traversalLimitReason?.name)
        .putNullable("semanticDiffOutcome", semanticDiffOutcome?.name)
        .putNullable("diffObservationCount", diffObservationCount)
        .putNullable("diffCurrentCandidateCount", diffCurrentCandidateCount)
        .putNullable("diffStableCandidateCount", diffStableCandidateCount)
        .putNullable("diffDisappearedCandidateCount", diffDisappearedCandidateCount)
        .putNullable("transitionSignal", transitionSignal)
        .putNullable("previousHostWindowId", previousHostWindowId)
        .putNullable("currentHostWindowId", currentHostWindowId)
        .putNullable("differenceCleared", differenceCleared)
        .putNullable("clearReason", clearReason?.name)
        .putNullable("scanStrategy", scanStrategy?.name)
        .putNullable("coverageComplete", coverageComplete)
        .putNullable("continuationDepth", continuationDepth)
        .putNullable("budgetNodeReads", budgetNodeReads)
        .putNullable("directedQueryCount", directedQueryCount)
        .putNullable("directedPresentCount", directedPresentCount)
        .putNullable("directedAbsentCount", directedAbsentCount)
        .putNullable("directedUnknownCount", directedUnknownCount)
        .putNullable("directedAmbiguousCount", directedAmbiguousCount)
        .putNullable("directedAnchorCompleteCount", directedAnchorCompleteCount)
        .put("rejectedCandidateCounts", rejectedCandidateCounts.toRejectionCountsJson())
        .putNullable("unavailableReason", unavailableReason?.name)

    private fun ClickRecoveryDiagnostics.toJson() = JSONObject()
        .put("outcome", outcome.name)
        .put("visitedNodeCount", visitedNodeCount)
        .put("maxDepth", maxDepth)
        .put("elapsedMs", elapsedMs)
        .put("semanticNodeCount", semanticNodeCount)
        .put("matchedNodeCount", matchedNodeCount)
        .put("knownDismissNodeCount", knownDismissNodeCount)
        .put("explicitDismissNodeCount", explicitDismissNodeCount)
        .put("selectorCandidateNodeCount", selectorCandidateNodeCount)
        .put("countdownSemanticNodeCount", countdownSemanticNodeCount)
        .put("countdownActionChainCount", countdownActionChainCount)
        .putNullable("traversalLimitReason", traversalLimitReason?.name)
        .put("scanStrategy", scanStrategy.name)
        .put("coverageComplete", coverageComplete)
        .put("continuationDepth", continuationDepth)
        .put("pendingBranchCount", pendingBranchCount)
        .put("cursorReset", cursorReset)
        .put("budgetNodeReads", budgetNodeReads)
        .put("rejectedCandidateCounts", rejectedCandidateCounts.toRejectionCountsJson())
        .putNullable("unavailableReason", unavailableReason?.name)

    private fun CandidateRecheckDiagnostics.toJson() = JSONObject()
        .put("queryCount", queryCount)
        .put("anchorQueryCount", anchorQueryCount)
        .put("presentCount", presentCount)
        .put("absentCount", absentCount)
        .put("ambiguousCount", ambiguousCount)
        .put("unknownCount", unknownCount)
        .put("emptyQueryCount", emptyQueryCount)
        .put("anchorScanCount", anchorScanCount)
        .put("completedAnchorScanCount", completedAnchorScanCount)
        .put("unreadableNodeCount", unreadableNodeCount)
        .put("budgetNodeReads", budgetNodeReads)
        .put("elapsedMs", elapsedMs)
        .putNullable("traversalLimitReason", traversalLimitReason?.name)

    private fun SnapshotRecoveryDiagnostics.toJson() = JSONObject()
        .put("outcome", outcome.name)
        .putNullable("ageMs", ageMs)

    private fun SemanticDiffDiagnostics.toJson() = JSONObject()
        .put("outcome", outcome.name)
        .put("observationCount", observationCount)
        .put("currentCandidateCount", currentCandidateCount)
        .put("stableCandidateCount", stableCandidateCount)
        .put("disappearedCandidateCount", disappearedCandidateCount)
        .put("visitedNodeCount", visitedNodeCount)
        .put("transitionSignal", transitionSignal)
        .put("directedPresentCount", directedPresentCount)
        .put("directedAbsentCount", directedAbsentCount)
        .put("directedUnknownCount", directedUnknownCount)
        .put("directedAmbiguousCount", directedAmbiguousCount)
        .put("unresolvedDismissCount", unresolvedDismissCount)

    private fun SemanticDiffEvidence.toJson() = JSONObject()
        .put("targetPackageName", targetAppId.packageName)
        .put("activityName", activityName)
        .put("capturedAtEpochMs", capturedAtEpochMs)
        .put(
            "selectors",
            JSONArray().apply { selectors.forEach { put(it.toJson()) } },
        )

    private fun TargetWindowDiagnostics.toJson() = JSONObject()
        .put("windowStateEventCount", windowStateEventCount)
        .put("contentChangedEventCount", contentChangedEventCount)
        .put("viewClickedEventCount", viewClickedEventCount)
        .put("ignoredPackageEventCount", ignoredPackageEventCount)
        .put("missingPackageEventCount", missingPackageEventCount)
        .put("windowResolutionAttemptCount", windowResolutionAttemptCount)
        .put("windowRetryAttemptCount", windowRetryAttemptCount)
        .putNullable("lastPackageName", lastPackageName)
        .putNullable("lastClassName", lastClassName)
        .putNullable("lastEventWindowId", lastEventWindowId)
        .putNullable("lastWindowCount", lastWindowCount)
        .putNullable("lastMatchingWindowCount", lastMatchingWindowCount)
        .putNullable("lastMatchingWindowType", lastMatchingWindowType)
        .putNullable("lastMatchingWindowActive", lastMatchingWindowActive)
        .put("lastOutcome", lastOutcome.name)
        .putNullable("lastEventAgeMs", lastEventAgeMs)
        .putNullable("captureTiming", captureTiming?.toJson())
        .putNullable("startupObservation", startupObservation?.toJson())

    private fun CaptureStartupDiagnostics.toJson() = JSONObject()
        .put("targetConfirmed", targetConfirmed)
        .put("pageObservationCount", pageObservationCount)
        .put("provisionalObservationCount", provisionalObservationCount)
        .putNullable("firstObservedAtElapsedMs", firstObservedAtElapsedMs)
        .putNullable("firstObservedAtEpochMs", firstObservedAtEpochMs)
        .putNullable("firstDismissActionAtEpochMs", firstDismissActionAtEpochMs)
        .put("maxVisibleNodeCount", maxVisibleNodeCount)
        .put("maxActionableNodeCount", maxActionableNodeCount)
        .put("maxActionClickNodeCount", maxActionClickNodeCount)
        .put("maxDismissHintNodeCount", maxDismissHintNodeCount)
        .put("maxDismissActionChainCount", maxDismissActionChainCount)
        .put("maxStableDismissCandidateCount", maxStableDismissCandidateCount)
        .put("completeScanCount", completeScanCount)
        .putNullable("observedBeforeFirstTargetEventReceived", observedBeforeFirstTargetEventReceived)
        .putNullable("observedBeforeVerification", observedBeforeVerification)
        .put("pendingBoundaryEvictionCount", pendingBoundaryEvictionCount)

    private fun Map<CaptureCandidateRejection, Int>.toRejectionCountsJson() = JSONObject().apply {
        this@toRejectionCountsJson.toSortedMap().forEach { (reason, count) -> put(reason.name, count) }
    }

    private fun JSONObject.toRejectionCounts(): Map<CaptureCandidateRejection, Int> =
        EnumMap<CaptureCandidateRejection, Int>(CaptureCandidateRejection::class.java).apply {
            CaptureCandidateRejection.entries.forEach { reason ->
                val count = optLong(reason.name, -1)
                if (count in 0..Int.MAX_VALUE.toLong()) put(reason, count.toInt())
            }
        }

    private fun CaptureEventTiming.toJson() = JSONObject()
        .put("eventUptimeMs", eventUptimeMs)
        .put("receivedUptimeMs", receivedUptimeMs)
        .put("receivedElapsedMs", receivedElapsedMs)
        .put("receivedEpochMs", receivedEpochMs)
        .putNullable("dispatchDelayMs", dispatchDelayMs)
        .put("clockStatus", clockStatus.name)

    private fun CaptureTimingDiagnostics.toJson() = JSONObject()
        .put("receivedEventCount", receivedEventCount)
        .put("targetEventCount", targetEventCount)
        .put("preVerificationTargetClickCount", preVerificationTargetClickCount)
        .put("invalidTimestampEventCount", invalidTimestampEventCount)
        .put("pendingPackageEvictionCount", pendingPackageEvictionCount)
        .putNullable("firstTargetEvent", firstTargetEvent?.toJson())
        .putNullable("lastTargetEvent", lastTargetEvent?.toJson())
        .putNullable("maxTargetDispatchDelayMs", maxTargetDispatchDelayMs)
        .putNullable("firstVerifiedAtElapsedMs", firstVerifiedAtElapsedMs)
        .putNullable("firstVerifiedAtUptimeMs", firstVerifiedAtUptimeMs)
        .putNullable("firstScanAtElapsedMs", firstScanAtElapsedMs)
        .putNullable("targetEventToVerifiedUptimeMs", targetEventToVerifiedUptimeMs)
        .putNullable("targetReceivedToVerifiedMs", targetReceivedToVerifiedMs)
        .putNullable("verifiedToFirstScanMs", verifiedToFirstScanMs)

    private fun JSONObject.toEventTiming() = CaptureEventTiming(
        eventUptimeMs = getLong("eventUptimeMs"),
        receivedUptimeMs = getLong("receivedUptimeMs"),
        receivedElapsedMs = getLong("receivedElapsedMs"),
        receivedEpochMs = getLong("receivedEpochMs"),
        dispatchDelayMs = nullableLong("dispatchDelayMs"),
        clockStatus = runCatching { CaptureEventClockStatus.valueOf(getString("clockStatus")) }
            .getOrDefault(CaptureEventClockStatus.INVALID_CLOCK),
    )

    private fun JSONObject.toCaptureTimingDiagnostics() = CaptureTimingDiagnostics(
        receivedEventCount = optInt("receivedEventCount", 0),
        targetEventCount = optInt("targetEventCount", 0),
        preVerificationTargetClickCount = optInt("preVerificationTargetClickCount", 0),
        invalidTimestampEventCount = optInt("invalidTimestampEventCount", 0),
        pendingPackageEvictionCount = optInt("pendingPackageEvictionCount", 0),
        firstTargetEvent = optJSONObject("firstTargetEvent")?.toEventTiming(),
        lastTargetEvent = optJSONObject("lastTargetEvent")?.toEventTiming(),
        maxTargetDispatchDelayMs = nullableLong("maxTargetDispatchDelayMs"),
        firstVerifiedAtElapsedMs = nullableLong("firstVerifiedAtElapsedMs"),
        firstVerifiedAtUptimeMs = nullableLong("firstVerifiedAtUptimeMs"),
        firstScanAtElapsedMs = nullableLong("firstScanAtElapsedMs"),
        targetEventToVerifiedUptimeMs = nullableLong("targetEventToVerifiedUptimeMs"),
        targetReceivedToVerifiedMs = nullableLong("targetReceivedToVerifiedMs"),
        verifiedToFirstScanMs = nullableLong("verifiedToFirstScanMs"),
    )

    private fun CaptureSample.toJson() = JSONObject()
        .put("id", id)
        .put("targetPackageName", targetAppId.packageName)
        .put("targetAppName", targetAppName)
        .put("activityName", activityName)
        .put("clickedAfterTargetMs", clickedAfterTargetMs)
        .put("capturedAtEpochMs", capturedAtEpochMs)
        .put("evidence", evidence.name)
        .put("nodes", JSONArray().apply { nodes.forEach { put(it.toJson()) } })
        .put(
            "preClickUniqueSelectors",
            JSONArray().apply { preClickUniqueSelectors.forEach { put(it.toJson()) } },
        )

    private fun ObservedDismissCandidate.toJson() = JSONObject()
        .put("id", id)
        .put("targetPackageName", targetAppId.packageName)
        .put("targetAppName", targetAppName)
        .put("activityName", activityName)
        .put("observedAfterTargetMs", observedAfterTargetMs)
        .put("observedAtEpochMs", observedAtEpochMs)
        .put("nodes", JSONArray().apply { nodes.forEach { put(it.toJson()) } })
        .put(
            "preClickUniqueSelectors",
            JSONArray().apply { preClickUniqueSelectors.forEach { put(it.toJson()) } },
        )

    private fun CapturedNode.toJson() = JSONObject()
        .putNullable("resourceId", resourceId)
        .putNullable("text", text)
        .putNullable("contentDescription", contentDescription)
        .put("clickable", clickable)
        .put("supportsActionClick", supportsActionClick)
        .put("visibleToUser", visibleToUser)
        .put("hasUsableBounds", hasUsableBounds)

    private fun CapturedSelector.toJson() = JSONObject()
        .putNullable("resourceId", resourceId)
        .putNullable("text", text)
        .putNullable("contentDescription", contentDescription)
        .putNullable("ancestorResourceId", ancestorResourceId)
        .putNullable("ancestorDistance", ancestorDistance)
        .putNullable("actionAncestorDistance", actionAncestorDistance)

    /** 新增或损坏的可选诊断不应让业务样本和会话一起回退为空。 */
    private fun <T> JSONObject.optionalDiagnostic(name: String, decode: (JSONObject) -> T): T? =
        optJSONObject(name)?.let { runCatching { decode(it) }.getOrNull() }

    private fun JSONObject.toSession() = CaptureSession(
        id = getLong("id"),
        targetAppId = nullableString("targetPackageName")?.let(::TargetAppId),
        targetAppName = nullableString("targetAppName"),
        packageName = nullableString("packageName"),
        phase = CapturePhase.valueOf(getString("phase")),
        phaseStartedAtEpochMs = getLong("phaseStartedAtEpochMs"),
        deadlineAtEpochMs = getLong("deadlineAtEpochMs"),
        activityName = nullableString("activityName"),
        windowId = nullableInt("windowId"),
        targetEnteredAtElapsedMs = nullableLong("targetEnteredAtElapsedMs"),
        leftFlashSkipAtEpochMs = nullableLong("leftFlashSkipAtEpochMs"),
        candidateClickListeningEnabled = optBoolean("candidateClickListeningEnabled", false),
        pendingSample = if (isNull("pendingSample")) null else getJSONObject("pendingSample").toSample(),
        pendingObservedCandidate = if (isNull("pendingObservedCandidate")) null else
            getJSONObject("pendingObservedCandidate").toObservedDismissCandidate(),
        failure = nullableString("failure")?.let(CaptureFailure::valueOf),
        failureDiagnostics = optionalDiagnostic("failureDiagnostics") { it.toFailureDiagnostics() },
        targetWindowDiagnostics = optionalDiagnostic("targetWindowDiagnostics") { it.toTargetWindowDiagnostics() },
    ).also { session ->
        // 完成阶段缺少其必需载荷是坏会话，不能在服务恢复时转成无阻塞状态。
        require(session.phase != CapturePhase.CAPTURED || session.pendingSample != null)
        require(session.phase != CapturePhase.AWAITING_CONFIRMATION || session.pendingObservedCandidate != null)
    }

    private fun JSONObject.toFailureDiagnostics() = CaptureFailureDiagnostics(
        sourceAvailable = getBoolean("sourceAvailable"),
        eventTextCount = getInt("eventTextCount"),
        eventHasContentDescription = getBoolean("eventHasContentDescription"),
        copiedNodeCount = getInt("copiedNodeCount"),
        selectorCandidateCount = getInt("selectorCandidateCount"),
        immediateRecovery = optionalDiagnostic("immediateRecovery") { it.toRecoveryDiagnostics() },
        preClickProbe = optionalDiagnostic("preClickProbe") { it.toRecoveryDiagnostics() },
        snapshotRecovery = optionalDiagnostic("snapshotRecovery") { it.toSnapshotDiagnostics() },
        semanticDiff = optionalDiagnostic("semanticDiff") { it.toSemanticDiffDiagnostics() },
        candidateRecheck = optionalDiagnostic("candidateRecheck") { it.toCandidateRecheckDiagnostics() },
        lastWindowVerifiedAgeMs = nullableLong("lastWindowVerifiedAgeMs"),
        lastContentChangedAgeMs = nullableLong("lastContentChangedAgeMs"),
        clickDiagnosticApplicable = optBoolean("clickDiagnosticApplicable", true),
        sessionTrace = optJSONObject("sessionTrace")?.let { trace ->
            runCatching { trace.toSessionTrace() }.getOrNull()
        },
    )

    private fun JSONObject.toSessionTrace() = CaptureDiagnosticTrace.snapshot(
        summary = getJSONObject("summary").toDiagnosticSummary(),
        records = getJSONArray("records").mapObjects { it.toDiagnosticRecord() },
    )

    private fun JSONObject.toDiagnosticSummary() = CaptureDiagnosticSummary(
        totalRecordCount = optInt("totalRecordCount", 0),
        scanCount = optInt("scanCount", 0),
        completedScanCount = optInt("completedScanCount", 0),
        incompleteScanCount = optInt("incompleteScanCount", 0),
        traversalLimitScanCount = optInt("traversalLimitScanCount", 0),
        scansWithCloseSemantics = optInt("scansWithCloseSemantics", 0),
        maxCloseSemanticCount = optInt("maxCloseSemanticCount", 0),
        maxCountdownSemanticCount = optInt("maxCountdownSemanticCount", 0),
        maxEffectiveCandidateCount = optInt("maxEffectiveCandidateCount", 0),
        semanticDiffCount = optInt("semanticDiffCount", 0),
        transitionSignalCount = optInt("transitionSignalCount", 0),
        maxDiffCurrentCandidateCount = optInt("maxDiffCurrentCandidateCount", 0),
        maxDiffStableCandidateCount = optInt("maxDiffStableCandidateCount", 0),
        maxDiffDisappearedCandidateCount = optInt("maxDiffDisappearedCandidateCount", 0),
        differenceClearCount = optInt("differenceClearCount", 0),
        windowResolutionCount = optInt("windowResolutionCount", 0),
        windowResolutionRetryCount = optInt("windowResolutionRetryCount", 0),
        windowResolutionOutcomeCounts = optJSONObject("windowResolutionOutcomeCounts")
            ?.toWindowResolutionOutcomeCounts()
            ?: emptyMap(),
        hostWindowUpdateCount = optInt("hostWindowUpdateCount", 0),
        droppedRecordCount = optInt("droppedRecordCount", 0),
        continuedScanCount = optInt("continuedScanCount", 0),
        candidateRecheckCount = optInt("candidateRecheckCount", 0),
        directedQueryCount = optInt("directedQueryCount", 0),
        directedPresentCount = optInt("directedPresentCount", 0),
        directedAbsentCount = optInt("directedAbsentCount", 0),
        directedUnknownCount = optInt("directedUnknownCount", 0),
        directedAmbiguousCount = optInt("directedAmbiguousCount", 0),
        completedAnchorScanCount = optInt("completedAnchorScanCount", 0),
        rejectedCandidateCounts = optJSONObject("rejectedCandidateCounts")?.toRejectionCounts().orEmpty(),
    )

    private fun JSONObject.toWindowResolutionOutcomeCounts(): Map<TargetWindowRecognitionOutcome, Int> =
        EnumMap<TargetWindowRecognitionOutcome, Int>(TargetWindowRecognitionOutcome::class.java).apply {
            val keys = keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val count = getInt(key)
                require(count >= 0)
                put(TargetWindowRecognitionOutcome.valueOf(key), count)
            }
        }

    private fun JSONObject.toDiagnosticRecord() = CaptureDiagnosticRecord(
        elapsedMs = getLong("elapsedMs"),
        kind = CaptureDiagnosticRecordKind.valueOf(getString("kind")),
        eventId = nullableInt("eventId"),
        eventWindowId = nullableInt("eventWindowId"),
        hostWindowId = nullableInt("hostWindowId"),
        rootWindowId = nullableInt("rootWindowId"),
        rootSource = nullableString("rootSource")?.let(CaptureDiagnosticWindowSource::valueOf),
        ownedChild = nullableBoolean("ownedChild"),
        fallbackAllowed = nullableBoolean("fallbackAllowed"),
        retry = nullableBoolean("retry"),
        resolutionOutcome = nullableString("resolutionOutcome")
            ?.let(TargetWindowRecognitionOutcome::valueOf),
        scanOutcome = nullableString("scanOutcome")?.let(ClickRecoveryOutcome::valueOf),
        scanPurpose = nullableString("scanPurpose")?.let(CaptureDiagnosticScanPurpose::valueOf),
        closeSemanticCount = nullableInt("closeSemanticCount"),
        effectiveCandidateCount = nullableInt("effectiveCandidateCount"),
        countdownSemanticCount = nullableInt("countdownSemanticCount"),
        visitedNodeCount = nullableInt("visitedNodeCount"),
        scanCompleted = nullableBoolean("scanCompleted"),
        traversalLimitReason = nullableString("traversalLimitReason")?.let(TraversalLimitReason::valueOf),
        semanticDiffOutcome = nullableString("semanticDiffOutcome")?.let(SemanticDiffOutcome::valueOf),
        diffObservationCount = nullableInt("diffObservationCount"),
        diffCurrentCandidateCount = nullableInt("diffCurrentCandidateCount"),
        diffStableCandidateCount = nullableInt("diffStableCandidateCount"),
        diffDisappearedCandidateCount = nullableInt("diffDisappearedCandidateCount"),
        transitionSignal = nullableBoolean("transitionSignal"),
        previousHostWindowId = nullableInt("previousHostWindowId"),
        currentHostWindowId = nullableInt("currentHostWindowId"),
        differenceCleared = nullableBoolean("differenceCleared"),
        clearReason = nullableString("clearReason")?.let(CaptureDiagnosticClearReason::valueOf),
        scanStrategy = nullableString("scanStrategy")?.let(CaptureScanStrategy::valueOf),
        coverageComplete = nullableBoolean("coverageComplete"),
        continuationDepth = nullableInt("continuationDepth"),
        budgetNodeReads = nullableInt("budgetNodeReads"),
        directedQueryCount = nullableInt("directedQueryCount"),
        directedPresentCount = nullableInt("directedPresentCount"),
        directedAbsentCount = nullableInt("directedAbsentCount"),
        directedUnknownCount = nullableInt("directedUnknownCount"),
        directedAmbiguousCount = nullableInt("directedAmbiguousCount"),
        directedAnchorCompleteCount = nullableInt("directedAnchorCompleteCount"),
        rejectedCandidateCounts = optJSONObject("rejectedCandidateCounts")?.toRejectionCounts().orEmpty(),
        unavailableReason = nullableString("unavailableReason")?.let {
            runCatching { CaptureScanUnavailableReason.valueOf(it) }.getOrNull()
        },
    )

    private fun JSONObject.toRecoveryDiagnostics() = ClickRecoveryDiagnostics(
        outcome = ClickRecoveryOutcome.valueOf(getString("outcome")),
        visitedNodeCount = getInt("visitedNodeCount"),
        maxDepth = getInt("maxDepth"),
        elapsedMs = getLong("elapsedMs"),
        semanticNodeCount = getInt("semanticNodeCount"),
        matchedNodeCount = getInt("matchedNodeCount"),
        knownDismissNodeCount = getInt("knownDismissNodeCount"),
        explicitDismissNodeCount = optInt("explicitDismissNodeCount", 0),
        selectorCandidateNodeCount = getInt("selectorCandidateNodeCount"),
        countdownSemanticNodeCount = optInt("countdownSemanticNodeCount", 0),
        countdownActionChainCount = optInt("countdownActionChainCount", 0),
        traversalLimitReason = nullableString("traversalLimitReason")
            ?.let(TraversalLimitReason::valueOf),
        scanStrategy = nullableString("scanStrategy")?.let(CaptureScanStrategy::valueOf) ?: CaptureScanStrategy.FULL_ROOT,
        coverageComplete = optBoolean("coverageComplete", true),
        continuationDepth = optInt("continuationDepth", 0),
        pendingBranchCount = optInt("pendingBranchCount", 0),
        cursorReset = optBoolean("cursorReset", false),
        budgetNodeReads = optInt("budgetNodeReads", 0),
        rejectedCandidateCounts = optJSONObject("rejectedCandidateCounts")?.toRejectionCounts().orEmpty(),
        unavailableReason = nullableString("unavailableReason")?.let {
            runCatching { CaptureScanUnavailableReason.valueOf(it) }.getOrNull()
        },
    )

    private fun JSONObject.toCandidateRecheckDiagnostics() = CandidateRecheckDiagnostics(
        queryCount = optInt("queryCount", 0),
        anchorQueryCount = optInt("anchorQueryCount", 0),
        presentCount = optInt("presentCount", 0),
        absentCount = optInt("absentCount", 0),
        ambiguousCount = optInt("ambiguousCount", 0),
        unknownCount = optInt("unknownCount", 0),
        emptyQueryCount = optInt("emptyQueryCount", 0),
        anchorScanCount = optInt("anchorScanCount", 0),
        completedAnchorScanCount = optInt("completedAnchorScanCount", 0),
        unreadableNodeCount = optInt("unreadableNodeCount", 0),
        budgetNodeReads = optInt("budgetNodeReads", 0),
        elapsedMs = optLong("elapsedMs", 0),
        traversalLimitReason = nullableString("traversalLimitReason")?.let(TraversalLimitReason::valueOf),
    )

    private fun JSONObject.toSnapshotDiagnostics() = SnapshotRecoveryDiagnostics(
        outcome = SnapshotRecoveryOutcome.valueOf(getString("outcome")),
        ageMs = nullableLong("ageMs"),
    )

    private fun JSONObject.toSemanticDiffDiagnostics() = SemanticDiffDiagnostics(
        outcome = SemanticDiffOutcome.valueOf(getString("outcome")),
        observationCount = optInt("observationCount", 0),
        currentCandidateCount = optInt("currentCandidateCount", 0),
        stableCandidateCount = optInt("stableCandidateCount", 0),
        disappearedCandidateCount = optInt("disappearedCandidateCount", 0),
        visitedNodeCount = optInt("visitedNodeCount", 0),
        transitionSignal = optBoolean("transitionSignal", false),
        directedPresentCount = optInt("directedPresentCount", 0),
        directedAbsentCount = optInt("directedAbsentCount", 0),
        directedUnknownCount = optInt("directedUnknownCount", 0),
        directedAmbiguousCount = optInt("directedAmbiguousCount", 0),
        unresolvedDismissCount = optInt("unresolvedDismissCount", 0),
    )

    private fun JSONObject.toSemanticDiffEvidence() = SemanticDiffEvidence(
        targetAppId = TargetAppId(getString("targetPackageName")),
        activityName = getString("activityName"),
        selectors = getJSONArray("selectors").mapObjects { it.toCapturedSelector() },
        capturedAtEpochMs = getLong("capturedAtEpochMs"),
    )

    private fun JSONObject.toTargetWindowDiagnostics() = TargetWindowDiagnostics(
        windowStateEventCount = getInt("windowStateEventCount"),
        contentChangedEventCount = getInt("contentChangedEventCount"),
        viewClickedEventCount = getInt("viewClickedEventCount"),
        ignoredPackageEventCount = getInt("ignoredPackageEventCount"),
        missingPackageEventCount = getInt("missingPackageEventCount"),
        windowResolutionAttemptCount = optInt("windowResolutionAttemptCount", 0),
        windowRetryAttemptCount = optInt("windowRetryAttemptCount", 0),
        lastPackageName = nullableString("lastPackageName"),
        lastClassName = nullableString("lastClassName"),
        lastEventWindowId = nullableInt("lastEventWindowId"),
        lastWindowCount = nullableInt("lastWindowCount"),
        lastMatchingWindowCount = nullableInt("lastMatchingWindowCount"),
        lastMatchingWindowType = nullableInt("lastMatchingWindowType"),
        lastMatchingWindowActive = nullableBoolean("lastMatchingWindowActive"),
        lastOutcome = TargetWindowRecognitionOutcome.valueOf(getString("lastOutcome")),
        lastEventAgeMs = nullableLong("lastEventAgeMs"),
        captureTiming = optJSONObject("captureTiming")?.let {
            runCatching { it.toCaptureTimingDiagnostics() }.getOrNull()
        },
        startupObservation = optionalDiagnostic("startupObservation") { it.toStartupObservation() },
    )

    private fun JSONObject.toStartupObservation() = CaptureStartupDiagnostics(
        targetConfirmed = optBoolean("targetConfirmed", false),
        pageObservationCount = optInt("pageObservationCount", 0).coerceAtLeast(0),
        provisionalObservationCount = optInt("provisionalObservationCount", 0).coerceAtLeast(0),
        firstObservedAtElapsedMs = nullableLong("firstObservedAtElapsedMs"),
        firstObservedAtEpochMs = nullableLong("firstObservedAtEpochMs"),
        firstDismissActionAtEpochMs = nullableLong("firstDismissActionAtEpochMs"),
        maxVisibleNodeCount = optInt("maxVisibleNodeCount", 0).coerceAtLeast(0),
        maxActionableNodeCount = optInt("maxActionableNodeCount", 0).coerceAtLeast(0),
        maxActionClickNodeCount = optInt("maxActionClickNodeCount", 0).coerceAtLeast(0),
        maxDismissHintNodeCount = optInt("maxDismissHintNodeCount", 0).coerceAtLeast(0),
        maxDismissActionChainCount = optInt("maxDismissActionChainCount", 0).coerceAtLeast(0),
        maxStableDismissCandidateCount = optInt("maxStableDismissCandidateCount", 0).coerceAtLeast(0),
        completeScanCount = optInt("completeScanCount", 0).coerceAtLeast(0),
        observedBeforeFirstTargetEventReceived = nullableBoolean("observedBeforeFirstTargetEventReceived"),
        observedBeforeVerification = nullableBoolean("observedBeforeVerification"),
        pendingBoundaryEvictionCount = optInt("pendingBoundaryEvictionCount", 0).coerceAtLeast(0),
    )

    private fun JSONObject.toSample() = CaptureSample(
        id = getLong("id"),
        targetAppId = TargetAppId(getString("targetPackageName")),
        targetAppName = getString("targetAppName"),
        activityName = getString("activityName"),
        clickedAfterTargetMs = getLong("clickedAfterTargetMs"),
        capturedAtEpochMs = getLong("capturedAtEpochMs"),
        nodes = getJSONArray("nodes").mapObjects { it.toCapturedNode() },
        preClickUniqueSelectors = getJSONArray("preClickUniqueSelectors")
            .mapObjects { it.toCapturedSelector() },
        evidence = nullableString("evidence")?.let(CaptureEvidence::valueOf)
            ?: CaptureEvidence.ACCESSIBILITY_CLICK,
    )

    private fun JSONObject.toObservedDismissCandidate() = ObservedDismissCandidate(
        id = getLong("id"),
        targetAppId = TargetAppId(getString("targetPackageName")),
        targetAppName = getString("targetAppName"),
        activityName = getString("activityName"),
        observedAfterTargetMs = getLong("observedAfterTargetMs"),
        observedAtEpochMs = getLong("observedAtEpochMs"),
        nodes = getJSONArray("nodes").mapObjects { it.toCapturedNode() },
        preClickUniqueSelectors = getJSONArray("preClickUniqueSelectors")
            .mapObjects { it.toCapturedSelector() },
    )

    private fun JSONObject.toCapturedNode() = CapturedNode(
        resourceId = nullableString("resourceId"),
        text = nullableString("text"),
        contentDescription = nullableString("contentDescription"),
        clickable = getBoolean("clickable"),
        supportsActionClick = getBoolean("supportsActionClick"),
        visibleToUser = getBoolean("visibleToUser"),
        hasUsableBounds = getBoolean("hasUsableBounds"),
    )

    private fun JSONObject.toCapturedSelector() = CapturedSelector(
        resourceId = nullableString("resourceId"),
        text = nullableString("text"),
        contentDescription = nullableString("contentDescription"),
        ancestorResourceId = nullableString("ancestorResourceId"),
        ancestorDistance = nullableInt("ancestorDistance"),
        actionAncestorDistance = nullableInt("actionAncestorDistance"),
    )

    private fun JSONObject.putNullable(key: String, value: Any?): JSONObject =
        put(key, value ?: JSONObject.NULL)

    private fun JSONObject.nullableString(key: String): String? =
        if (isNull(key)) null else getString(key)

    private fun JSONObject.nullableLong(key: String): Long? =
        if (isNull(key)) null else getLong(key)

    private fun JSONObject.nullableInt(key: String): Int? =
        if (isNull(key)) null else getInt(key)

    private fun JSONObject.nullableBoolean(key: String): Boolean? =
        if (isNull(key)) null else getBoolean(key)

    private fun <T> JSONArray.mapObjects(mapper: (JSONObject) -> T): List<T> =
        List(length()) { index -> mapper(getJSONObject(index)) }
}
