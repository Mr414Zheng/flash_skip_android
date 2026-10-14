package com.zhenghuaizhi.flashskip.data.preferences

import com.zhenghuaizhi.flashskip.domain.model.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ActionRecordCodecContractTest {
    @Test fun completeRecordRoundTrips() {
        val record = actionRecord().copy(
            reason = ActionReason.CLICK_UNCONFIRMED,
            timing = ExecutionTiming(1, 2, 3, 4, 5),
            observation = ActionObservationStatus.TARGET_PRESENT,
        )
        assertEquals(listOf(record), ActionRecordCodec.decodeOrEmpty(ActionRecordCodec.encode(listOf(record))))
    }

    @Test fun historicalV2DefaultsAcceptedFromMethod() {
        // 固定历史 wire 样本，避免用当前编码器生成历史输入。
        val raw = """{"version":2,"records":[{"timestamp":9,"packageName":"com.example.target","appName":"old","rule":"r","method":"NODE_CLICK","outcome":"CLOSED"}]}"""
        val record = ActionRecordCodec.decodeOrEmpty(raw).single()
        assertTrue(record.actionAccepted)
        assertNull(record.observation)
        assertNull(record.timing)
    }

    @Test fun historicalV2WithoutMethodDoesNotClaimAcceptedAction() {
        val raw = """{"version":2,"records":[{"timestamp":9,"packageName":"com.example.target","appName":"old","rule":"r","method":null,"outcome":"FAILED"}]}"""
        assertFalse(ActionRecordCodec.decodeOrEmpty(raw).single().actionAccepted)
    }

    @Test fun unknownOptionalObservationDoesNotDiscardRecord() {
        val json = envelope()
        json.getJSONArray("records").getJSONObject(0).put("observation", "FUTURE")
        assertEquals(listOf(actionRecord()), ActionRecordCodec.decodeOrEmpty(json.toString()))
    }

    @Test fun damagedOptionalTimingDoesNotDiscardRecord() {
        val json = envelope()
        json.getJSONArray("records").getJSONObject(0).put("timing", JSONObject().put("completedMs", "bad"))
        assertEquals(listOf(actionRecord()), ActionRecordCodec.decodeOrEmpty(json.toString()))
    }

    @Test fun firstDamagedRecordIsIsolated() = damagedRecord(0)
    @Test fun middleDamagedRecordIsIsolated() = damagedRecord(1)
    @Test fun lastDamagedRecordIsIsolated() = damagedRecord(2)

    @Test fun badRecordAfterRetentionLimitDoesNotEraseFirstTwenty() {
        val expected = (1L..20).map(::actionRecord)
        val json = JSONObject(ActionRecordCodec.encode(expected))
        json.getJSONArray("records").put(JSONObject())
        assertEquals(expected, ActionRecordCodec.decodeOrEmpty(json.toString()))
    }

    @Test fun retentionKeepsWireOrderRatherThanTimestampOrder() {
        val records = (1L..25).map(::actionRecord)
        assertEquals(records.take(20), ActionRecordCodec.decodeOrEmpty(ActionRecordCodec.encode(records)))
    }

    @Test fun duplicateRecordsAreRetained() {
        val records = listOf(actionRecord(), actionRecord())
        assertEquals(records, ActionRecordCodec.decodeOrEmpty(ActionRecordCodec.encode(records)))
    }

    @Test fun absentAndBlankRecordsAreEmpty() {
        assertTrue(ActionRecordCodec.decodeOrEmpty(null).isEmpty())
        assertTrue(ActionRecordCodec.decodeOrEmpty(" ").isEmpty())
    }

    private fun envelope() = JSONObject(ActionRecordCodec.encode(listOf(actionRecord())))
    private fun damagedRecord(index: Int) {
        val records = (1L..3).map(::actionRecord)
        val json = JSONObject(ActionRecordCodec.encode(records))
        json.getJSONArray("records").getJSONObject(index).put("outcome", "UNKNOWN")
        assertEquals(records.filterIndexed { i, _ -> i != index }, ActionRecordCodec.decodeOrEmpty(json.toString()))
    }
}
