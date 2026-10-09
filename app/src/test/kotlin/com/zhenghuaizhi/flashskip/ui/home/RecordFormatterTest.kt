package com.zhenghuaizhi.flashskip.ui.home

import com.zhenghuaizhi.flashskip.domain.model.ActionOutcome
import com.zhenghuaizhi.flashskip.domain.model.ActionReason
import com.zhenghuaizhi.flashskip.domain.model.ActionRecord
import com.zhenghuaizhi.flashskip.domain.model.TargetAppId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordFormatterTest {
    @Test
    fun gestureTimeoutIsDisplayedAsFailedWithActionableReason() {
        val record = ActionRecord(
            timestampEpochMs = 0,
            targetAppId = TargetAppId("test.app"),
            ruleId = "skip-test",
            method = null,
            outcome = ActionOutcome.FAILED,
            reason = ActionReason.GESTURE_TIMED_OUT,
        )

        assertEquals("执行失败", record.title())
        assertTrue(record.detail(nowEpochMs = 0).startsWith("手势等待超时 · "))
    }
}
