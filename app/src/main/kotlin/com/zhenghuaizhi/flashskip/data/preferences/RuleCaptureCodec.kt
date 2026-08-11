package com.zhenghuaizhi.flashskip.data.preferences

import com.zhenghuaizhi.flashskip.domain.model.AppVersion
import com.zhenghuaizhi.flashskip.domain.model.CaptureFailure
import com.zhenghuaizhi.flashskip.domain.model.CapturePhase
import com.zhenghuaizhi.flashskip.domain.model.CaptureSample
import com.zhenghuaizhi.flashskip.domain.model.CaptureSession
import com.zhenghuaizhi.flashskip.domain.model.CapturedNode
import com.zhenghuaizhi.flashskip.domain.model.CapturedSelector
import com.zhenghuaizhi.flashskip.domain.model.RuleCaptureState
import com.zhenghuaizhi.flashskip.domain.model.TargetAppId
import org.json.JSONArray
import org.json.JSONObject

object RuleCaptureCodec {
    private const val VERSION = 1

    fun encode(state: RuleCaptureState): String = JSONObject()
        .put("version", VERSION)
        .put("samples", JSONArray().apply { state.samples.forEach { put(it.toJson()) } })
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
            )
        }.getOrDefault(RuleCaptureState())
    }

    private fun CaptureSession.toJson() = JSONObject()
        .put("id", id)
        .put("targetAppId", targetAppId.name)
        .put("packageName", packageName)
        .put("appVersion", appVersion.toJson())
        .put("phase", phase.name)
        .put("phaseStartedAtEpochMs", phaseStartedAtEpochMs)
        .put("deadlineAtEpochMs", deadlineAtEpochMs)
        .putNullable("activityName", activityName)
        .putNullable("windowId", windowId)
        .putNullable("targetEnteredAtElapsedMs", targetEnteredAtElapsedMs)
        .putNullable("pendingSample", pendingSample?.toJson())
        .putNullable("failure", failure?.name)

    private fun CaptureSample.toJson() = JSONObject()
        .put("id", id)
        .put("targetAppId", targetAppId.name)
        .put("appVersion", appVersion.toJson())
        .put("activityName", activityName)
        .put("clickedAfterTargetMs", clickedAfterTargetMs)
        .put("capturedAtEpochMs", capturedAtEpochMs)
        .put("nodes", JSONArray().apply { nodes.forEach { put(it.toJson()) } })
        .put(
            "preClickUniqueSelectors",
            JSONArray().apply { preClickUniqueSelectors.forEach { put(it.toJson()) } },
        )

    private fun AppVersion.toJson() = JSONObject()
        .putNullable("versionName", versionName)
        .put("versionCode", versionCode)

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

    private fun JSONObject.toSession() = CaptureSession(
        id = getLong("id"),
        targetAppId = TargetAppId.valueOf(getString("targetAppId")),
        packageName = getString("packageName"),
        appVersion = getJSONObject("appVersion").toAppVersion(),
        phase = CapturePhase.valueOf(getString("phase")),
        phaseStartedAtEpochMs = getLong("phaseStartedAtEpochMs"),
        deadlineAtEpochMs = getLong("deadlineAtEpochMs"),
        activityName = nullableString("activityName"),
        windowId = nullableInt("windowId"),
        targetEnteredAtElapsedMs = nullableLong("targetEnteredAtElapsedMs"),
        pendingSample = optJSONObject("pendingSample")?.toSample(),
        failure = nullableString("failure")?.let(CaptureFailure::valueOf),
    )

    private fun JSONObject.toSample() = CaptureSample(
        id = getLong("id"),
        targetAppId = TargetAppId.valueOf(getString("targetAppId")),
        appVersion = getJSONObject("appVersion").toAppVersion(),
        activityName = getString("activityName"),
        clickedAfterTargetMs = getLong("clickedAfterTargetMs"),
        capturedAtEpochMs = getLong("capturedAtEpochMs"),
        nodes = getJSONArray("nodes").mapObjects { it.toCapturedNode() },
        preClickUniqueSelectors = getJSONArray("preClickUniqueSelectors")
            .mapObjects { it.toCapturedSelector() },
    )

    private fun JSONObject.toAppVersion() = AppVersion(
        versionName = nullableString("versionName"),
        versionCode = getLong("versionCode"),
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
    )

    private fun JSONObject.putNullable(key: String, value: Any?): JSONObject =
        put(key, value ?: JSONObject.NULL)

    private fun JSONObject.nullableString(key: String): String? =
        if (isNull(key)) null else getString(key)

    private fun JSONObject.nullableLong(key: String): Long? =
        if (isNull(key)) null else getLong(key)

    private fun JSONObject.nullableInt(key: String): Int? =
        if (isNull(key)) null else getInt(key)

    private fun <T> JSONArray.mapObjects(mapper: (JSONObject) -> T): List<T> =
        List(length()) { index -> mapper(getJSONObject(index)) }
}
