package com.zhenghuaizhi.flashskip.data.preferences

import com.zhenghuaizhi.flashskip.domain.model.ActionMethod
import com.zhenghuaizhi.flashskip.domain.model.ActionOutcome
import com.zhenghuaizhi.flashskip.domain.model.ActionReason
import com.zhenghuaizhi.flashskip.domain.model.ActionRecord
import com.zhenghuaizhi.flashskip.domain.model.TargetAppId
import org.json.JSONArray
import org.json.JSONObject

object ActionRecordCodec {
    private const val VERSION = 1

    fun encode(records: List<ActionRecord>): String {
        val array = JSONArray()
        records.forEach { record ->
            array.put(
                JSONObject()
                    .put("timestamp", record.timestampEpochMs)
                    .put("app", record.targetAppId.name)
                    .put("rule", record.ruleId)
                    .put("method", record.method?.name ?: JSONObject.NULL)
                    .put("outcome", record.outcome.name)
                    .put("reason", record.reason?.name ?: JSONObject.NULL),
            )
        }
        return JSONObject()
            .put("version", VERSION)
            .put("records", array)
            .toString()
    }

    fun decodeOrEmpty(raw: String?): List<ActionRecord> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val envelope = JSONObject(raw)
            if (envelope.getInt("version") != VERSION) return emptyList()
            val array = envelope.getJSONArray("records")
            buildList {
                repeat(array.length()) { index -> add(array.getJSONObject(index).toRecord()) }
            }.take(MAX_RECORDS)
        }.getOrDefault(emptyList())
    }

    private fun JSONObject.toRecord(): ActionRecord = ActionRecord(
        timestampEpochMs = getLong("timestamp"),
        targetAppId = TargetAppId.valueOf(getString("app")),
        ruleId = getString("rule"),
        method = optionalEnum("method", ActionMethod::valueOf),
        outcome = ActionOutcome.valueOf(getString("outcome")),
        reason = optionalEnum("reason", ActionReason::valueOf),
    )

    private fun <T> JSONObject.optionalEnum(key: String, parse: (String) -> T): T? =
        if (isNull(key)) null else parse(getString(key))

    const val MAX_RECORDS = 20
}
