package com.zhenghuaizhi.flashskip.data.preferences

import com.zhenghuaizhi.flashskip.domain.model.ActionMethod
import com.zhenghuaizhi.flashskip.domain.model.ActionOutcome
import com.zhenghuaizhi.flashskip.domain.model.ActionReason
import com.zhenghuaizhi.flashskip.domain.model.ActionObservationStatus
import com.zhenghuaizhi.flashskip.domain.model.ActionRecord
import com.zhenghuaizhi.flashskip.domain.model.ExecutionTiming
import com.zhenghuaizhi.flashskip.domain.model.TargetAppId
import com.zhenghuaizhi.flashskip.domain.ports.StorageRead
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

object ActionRecordCodec {
    private const val VERSION = 2

    fun encode(records: List<ActionRecord>): String {
        val array = JSONArray()
        records.forEach { record ->
            array.put(
                JSONObject()
                    .put("timestamp", record.timestampEpochMs)
                    .put("packageName", record.targetAppId.packageName)
                    .put("appName", record.targetAppName)
                    .put("rule", record.ruleId)
                    .put("method", record.method?.name ?: JSONObject.NULL)
                    .put("outcome", record.outcome.name)
                    .put("reason", record.reason?.name ?: JSONObject.NULL)
                    .put("actionAccepted", record.actionAccepted)
                    .put("observation", record.observation?.name ?: JSONObject.NULL)
                    .put("timing", record.timing?.toJson() ?: JSONObject.NULL),
            )
        }
        return JSONObject()
            .put("version", VERSION)
            .put("records", array)
            .toString()
    }

    fun decodeOrEmpty(raw: String?): List<ActionRecord> = decode(raw).value.orEmpty()

    internal fun decode(raw: String?): StorageRead<List<ActionRecord>> {
        if (raw.isNullOrBlank()) return StorageRead(emptyList())
        return try {
            val envelope = JSONObject(raw)
            if (envelope.getInt("version") != VERSION) {
                return StorageRead(emptyList(), IOException("Unsupported record storage version"))
            }
            val array = envelope.getJSONArray("records")
            val records = buildList {
                repeat(array.length()) { index ->
                    // 单条记录损坏不能抹掉其他记录；容量在隔离后应用。
                    runCatching { array.getJSONObject(index).toRecord() }.getOrNull()?.let(::add)
                }
            }.take(MAX_RECORDS)
            StorageRead(records)
        } catch (_: Exception) {
            StorageRead(emptyList(), IOException("Unreadable record storage envelope"))
        }
    }

    private fun JSONObject.toRecord(): ActionRecord = ActionRecord(
        timestampEpochMs = getLong("timestamp"),
        targetAppId = TargetAppId(getString("packageName")),
        targetAppName = getString("appName"),
        ruleId = getString("rule"),
        method = optionalEnum("method", ActionMethod::valueOf),
        outcome = ActionOutcome.valueOf(getString("outcome")),
        reason = optionalEnum("reason", ActionReason::valueOf),
        timing = optJSONObject("timing")?.let { runCatching { it.toExecutionTiming() }.getOrNull() },
        actionAccepted = optBoolean("actionAccepted", !isNull("method")),
        observation = if (!has("observation") || isNull("observation")) null else
            runCatching { ActionObservationStatus.valueOf(getString("observation")) }.getOrNull(),
    )

    private fun ExecutionTiming.toJson(): JSONObject = JSONObject()
        .put("firstQueryMs", targetToFirstQueryMs ?: JSONObject.NULL)
        .put("firstMatchMs", targetToFirstMatchMs ?: JSONObject.NULL)
        .put("actionMs", targetToActionMs ?: JSONObject.NULL)
        .put("completedMs", targetToCompletedMs)
        .put("queryCount", selectorQueryCount)

    private fun JSONObject.toExecutionTiming(): ExecutionTiming = ExecutionTiming(
        targetToFirstQueryMs = optionalLong("firstQueryMs"),
        targetToFirstMatchMs = optionalLong("firstMatchMs"),
        targetToActionMs = optionalLong("actionMs"),
        targetToCompletedMs = getLong("completedMs"),
        selectorQueryCount = getInt("queryCount"),
    )

    private fun JSONObject.optionalLong(key: String): Long? =
        if (!has(key) || isNull(key)) null else getLong(key)

    private fun <T> JSONObject.optionalEnum(key: String, parse: (String) -> T): T? =
        if (isNull(key)) null else parse(getString(key))

    const val MAX_RECORDS = 20
}
