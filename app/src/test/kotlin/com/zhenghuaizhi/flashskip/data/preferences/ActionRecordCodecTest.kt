package com.zhenghuaizhi.flashskip.data.preferences

import com.zhenghuaizhi.flashskip.domain.model.ActionMethod
import com.zhenghuaizhi.flashskip.domain.model.ActionOutcome
import com.zhenghuaizhi.flashskip.domain.model.ActionReason
import com.zhenghuaizhi.flashskip.domain.model.ActionRecord
import com.zhenghuaizhi.flashskip.domain.model.ExecutionTiming
import com.zhenghuaizhi.flashskip.domain.model.TargetAppId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionRecordCodecTest {
    @Test
    fun roundTripPreservesOnlyRecordFields() {
        val record = record(1).copy(
            timing = ExecutionTiming(
                targetToFirstQueryMs = 120,
                targetToFirstMatchMs = 1_640,
                targetToActionMs = 1_644,
                targetToCompletedMs = 1_794,
                selectorQueryCount = 3,
            ),
        )

        assertEquals(listOf(record), ActionRecordCodec.decodeOrEmpty(ActionRecordCodec.encode(listOf(record))))
    }

    @Test
    fun malformedOrUnknownVersionRecoversAsEmpty() {
        assertTrue(ActionRecordCodec.decodeOrEmpty("not-json").isEmpty())
        assertTrue(
            ActionRecordCodec.decodeOrEmpty("{\"version\":999,\"records\":[]}").isEmpty(),
        )
    }

    @Test
    fun legacyVersionOneRecordWithoutTimingRemainsReadable() {
        val raw = """{"version":1,"records":[{"timestamp":7,"app":"TAOBAO","rule":"legacy","method":"NODE_CLICK","outcome":"CLOSED","reason":null}]}"""

        assertEquals(
            ActionRecord(
                timestampEpochMs = 7,
                targetAppId = TargetAppId.TAOBAO,
                ruleId = "legacy",
                method = ActionMethod.NODE_CLICK,
                outcome = ActionOutcome.CLOSED,
            ),
            ActionRecordCodec.decodeOrEmpty(raw).single(),
        )
    }

    @Test
    fun roundTripPreservesUnconfirmedClickFailure() {
        val record = record(1).copy(
            method = null,
            outcome = ActionOutcome.FAILED,
            reason = ActionReason.CLICK_UNCONFIRMED,
        )

        assertEquals(
            listOf(record),
            ActionRecordCodec.decodeOrEmpty(ActionRecordCodec.encode(listOf(record))),
        )
    }

    @Test
    fun decodeCapsUnexpectedOversizedPayloadAtTwenty() {
        val decoded = ActionRecordCodec.decodeOrEmpty(
            ActionRecordCodec.encode((1L..25L).map(::record)),
        )

        assertEquals(20, decoded.size)
        assertEquals(1, decoded.first().timestampEpochMs)
        assertEquals(20, decoded.last().timestampEpochMs)
    }

    private fun record(timestamp: Long) = ActionRecord(
        timestampEpochMs = timestamp,
        targetAppId = TargetAppId.TAOBAO,
        ruleId = "test-rule-$timestamp",
        method = ActionMethod.NODE_CLICK,
        outcome = ActionOutcome.CLOSED,
    )
}
