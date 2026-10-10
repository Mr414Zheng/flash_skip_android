package com.zhenghuaizhi.flashskip.ui.home

import com.zhenghuaizhi.flashskip.domain.model.ActionOutcome
import com.zhenghuaizhi.flashskip.domain.model.ActionMethod
import com.zhenghuaizhi.flashskip.domain.model.ExecutionTiming
import com.zhenghuaizhi.flashskip.domain.model.ActionReason
import com.zhenghuaizhi.flashskip.domain.model.ActionRecord
import com.zhenghuaizhi.flashskip.domain.model.ActionObservationStatus
import com.zhenghuaizhi.flashskip.domain.model.TargetAppId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordFormatterTest {
    @Test
    fun bothAcceptedActionMethodsUseSameUnconfirmedResultAndExplainPresentTarget() {
        for (method in ActionMethod.entries) {
            val record = ActionRecord(
                timestampEpochMs = 0, targetAppId = TargetAppId("test.app"), ruleId = "skip-test",
                method = method, outcome = ActionOutcome.ABANDONED, reason = ActionReason.CLICK_UNCONFIRMED,
                actionAccepted = true, observation = ActionObservationStatus.TARGET_PRESENT,
            )
            assertEquals("已点击，关闭未确认", record.title())
            assertTrue(record.detail(0).contains("原关闭目标仍存在"))
            assertEquals("已关闭", record.copy(outcome = ActionOutcome.CLOSED, observation = ActionObservationStatus.CONFIRMED).title())
        }
    }
    @Test
    fun issuedClickWithIncompleteVerificationShowsUnknownClosureAndKeepsReason() {
        val record = ActionRecord(
            timestampEpochMs = 0, targetAppId = TargetAppId("test.app"), ruleId = "skip-test",
            method = ActionMethod.NODE_CLICK, outcome = ActionOutcome.ABANDONED,
            reason = ActionReason.TRAVERSAL_LIMIT, timing = ExecutionTiming(1, 2, 3, 5, 7),
        )
        assertEquals("已点击，关闭未确认", record.title())
        val detail = record.detail(0)
        assertTrue(detail.startsWith("节点点击 · 页面结构超出安全范围 · "))
        assertTrue(detail.contains("结束 5毫秒"))
    }

    @Test
    fun olderUnconfirmedClickRecordUsesIssuedActionTimingWithoutClaimingAdRemains() {
        val record = ActionRecord(
            timestampEpochMs = 0, targetAppId = TargetAppId("test.app"), ruleId = "skip-test",
            method = null, outcome = ActionOutcome.FAILED, reason = ActionReason.CLICK_UNCONFIRMED,
            timing = ExecutionTiming(1, 2, 3, 5, 7),
        )
        assertEquals("已点击，关闭未确认", record.title())
        assertTrue(record.detail(0).startsWith("未能确认关闭 · "))
        assertEquals("已放弃", record.copy(
            timing = ExecutionTiming(1, 2, null, 5, 7), outcome = ActionOutcome.ABANDONED,
        ).title())
    }

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
