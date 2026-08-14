package com.zhenghuaizhi.flashskip.data.preferences

import com.zhenghuaizhi.flashskip.domain.model.CaptureFailure
import com.zhenghuaizhi.flashskip.domain.model.CaptureFailureDiagnostics
import com.zhenghuaizhi.flashskip.domain.model.CaptureEvidence
import com.zhenghuaizhi.flashskip.domain.model.CapturePhase
import com.zhenghuaizhi.flashskip.domain.model.CaptureSample
import com.zhenghuaizhi.flashskip.domain.model.CaptureSession
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
import com.zhenghuaizhi.flashskip.domain.model.TargetWindowRecognitionOutcome
import com.zhenghuaizhi.flashskip.domain.model.TraversalLimitReason
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

    fun decodeOrEmpty(raw: String?): RuleCaptureState {
        if (raw.isNullOrBlank()) return RuleCaptureState()
        return runCatching {
            val json = JSONObject(raw)
            require(json.getInt("version") == VERSION)
            RuleCaptureState(
                samples = json.getJSONArray("samples").mapObjects { it.toSample() },
                activeSession = json.optJSONObject("activeSession")?.toSession(),
                semanticDiffEvidence = json.optJSONArray("semanticDiffEvidence")
                    ?.mapObjects { it.toSemanticDiffEvidence() }
                    .orEmpty(),
            )
        }.getOrDefault(RuleCaptureState())
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
        .putNullable("lastWindowVerifiedAgeMs", lastWindowVerifiedAgeMs)
        .putNullable("lastContentChangedAgeMs", lastContentChangedAgeMs)

    private fun ClickRecoveryDiagnostics.toJson() = JSONObject()
        .put("outcome", outcome.name)
        .put("visitedNodeCount", visitedNodeCount)
        .put("maxDepth", maxDepth)
        .put("elapsedMs", elapsedMs)
        .put("semanticNodeCount", semanticNodeCount)
        .put("matchedNodeCount", matchedNodeCount)
        .put("knownDismissNodeCount", knownDismissNodeCount)
        .put("selectorCandidateNodeCount", selectorCandidateNodeCount)
        .put("countdownSemanticNodeCount", countdownSemanticNodeCount)
        .put("countdownActionChainCount", countdownActionChainCount)
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
        pendingSample = optJSONObject("pendingSample")?.toSample(),
        pendingObservedCandidate = optJSONObject("pendingObservedCandidate")
            ?.toObservedDismissCandidate(),
        failure = nullableString("failure")?.let(CaptureFailure::valueOf),
        failureDiagnostics = optJSONObject("failureDiagnostics")?.toFailureDiagnostics(),
        targetWindowDiagnostics = optJSONObject("targetWindowDiagnostics")
            ?.toTargetWindowDiagnostics(),
    )

    private fun JSONObject.toFailureDiagnostics() = CaptureFailureDiagnostics(
        sourceAvailable = getBoolean("sourceAvailable"),
        eventTextCount = getInt("eventTextCount"),
        eventHasContentDescription = getBoolean("eventHasContentDescription"),
        copiedNodeCount = getInt("copiedNodeCount"),
        selectorCandidateCount = getInt("selectorCandidateCount"),
        immediateRecovery = optJSONObject("immediateRecovery")?.toRecoveryDiagnostics(),
        preClickProbe = optJSONObject("preClickProbe")?.toRecoveryDiagnostics(),
        snapshotRecovery = optJSONObject("snapshotRecovery")?.toSnapshotDiagnostics(),
        semanticDiff = optJSONObject("semanticDiff")?.toSemanticDiffDiagnostics(),
        lastWindowVerifiedAgeMs = nullableLong("lastWindowVerifiedAgeMs"),
        lastContentChangedAgeMs = nullableLong("lastContentChangedAgeMs"),
    )

    private fun JSONObject.toRecoveryDiagnostics() = ClickRecoveryDiagnostics(
        outcome = ClickRecoveryOutcome.valueOf(getString("outcome")),
        visitedNodeCount = getInt("visitedNodeCount"),
        maxDepth = getInt("maxDepth"),
        elapsedMs = getLong("elapsedMs"),
        semanticNodeCount = getInt("semanticNodeCount"),
        matchedNodeCount = getInt("matchedNodeCount"),
        knownDismissNodeCount = getInt("knownDismissNodeCount"),
        selectorCandidateNodeCount = getInt("selectorCandidateNodeCount"),
        countdownSemanticNodeCount = optInt("countdownSemanticNodeCount", 0),
        countdownActionChainCount = optInt("countdownActionChainCount", 0),
        traversalLimitReason = nullableString("traversalLimitReason")
            ?.let(TraversalLimitReason::valueOf),
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
