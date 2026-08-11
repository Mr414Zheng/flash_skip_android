package com.zhenghuaizhi.flashskip.ui.home

import com.zhenghuaizhi.flashskip.domain.model.ActionMethod
import com.zhenghuaizhi.flashskip.domain.model.ActionOutcome
import com.zhenghuaizhi.flashskip.domain.model.ActionRecord
import com.zhenghuaizhi.flashskip.domain.model.ExecutionTiming
import com.zhenghuaizhi.flashskip.domain.model.TargetAppId
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordFormatterTest {
    @Test
    fun detailShowsActionAndConfirmationDurationsWhenTimingIsAvailable() {
        val record = ActionRecord(
            timestampEpochMs = 1_000,
            targetAppId = TargetAppId.QQ_MUSIC,
            ruleId = "local-rule",
            method = ActionMethod.NODE_CLICK,
            outcome = ActionOutcome.CLOSED,
            timing = ExecutionTiming(
                targetToFirstQueryMs = 120,
                targetToFirstMatchMs = 3_190,
                targetToActionMs = 3_202,
                targetToCompletedMs = 3_352,
                selectorQueryCount = 4,
            ),
        )

        val detail = record.detail(nowEpochMs = 1_000)

        assertTrue(detail.contains("查询 120毫秒"))
        assertTrue(detail.contains("匹配 3.190秒"))
        assertTrue(detail.contains("点击 3.202秒"))
        assertTrue(detail.contains("确认 3.352秒"))
    }
}
