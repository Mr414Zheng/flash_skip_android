package com.zhenghuaizhi.flashskip.platform.accessibility

import com.zhenghuaizhi.flashskip.domain.model.ActionMethod
import com.zhenghuaizhi.flashskip.domain.model.ActionOutcome
import com.zhenghuaizhi.flashskip.domain.model.ActionReason
import com.zhenghuaizhi.flashskip.domain.model.ActionObservationStatus
import com.zhenghuaizhi.flashskip.domain.ports.AbsenceProof
import com.zhenghuaizhi.flashskip.domain.ports.AutomationTraceEvent
import com.zhenghuaizhi.flashskip.domain.ports.AutomationTraceResult
import com.zhenghuaizhi.flashskip.domain.ports.AutomationTraceStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
class AutomationTraceLogTest {
    @Before
    fun resetLogs() = ShadowLog.clear()

    @Test
    fun repeatedQueriesAggregateButEveryClickAndVerificationRemainVisible() {
        val logger = AutomationTraceLog(enabled = true)
        repeat(100) { index -> logger.record(event().copy(elapsedMs = index.toLong(), queryCount = index + 1)) }
        assertEquals(1, logs().size)
        logger.record(event(AutomationTraceStage.NODE_CLICK).copy(result = AutomationTraceResult.CLICKED, attempt = 1))
        logger.record(event(AutomationTraceStage.VERIFY_QUERY).copy(result = AutomationTraceResult.UNIQUE, attempt = 1))
        logger.record(event(AutomationTraceStage.VERIFY_CLICK).copy(result = AutomationTraceResult.CLICKED, attempt = 2))
        logger.record(event(AutomationTraceStage.VERIFY_QUERY).copy(result = AutomationTraceResult.UNIQUE, attempt = 2))
        logger.record(event(AutomationTraceStage.FINISHED).copy(outcome = ActionOutcome.FAILED, reason = ActionReason.CLICK_UNCONFIRMED))
        val logs = logs()
        assertEquals(2, logs.count { it.contains("stage=QUERY ") })
        assertTrue(logs.any { it.contains("repeatCount=99") && it.contains("queryCount=100") })
        assertEquals(2, logs.count { it.contains("stage=VERIFY_QUERY ") })
        assertTrue(logs.last().contains("reason=CLICK_UNCONFIRMED"))
        assertTrue(logs.last().contains("outcome=FAILED"))
    }

    @Test
    fun traversalLimitedVerificationLogsTheClickAndUnconfirmedConclusionSeparately() {
        val logger = AutomationTraceLog(enabled = true)
        logger.record(event(AutomationTraceStage.NODE_CLICK).copy(
            result = AutomationTraceResult.CLICKED, attempt = 1,
        ))
        logger.record(event(AutomationTraceStage.VERIFY_QUERY).copy(
            result = AutomationTraceResult.TRAVERSAL_LIMIT, attempt = 1,
        ))
        logger.record(event(AutomationTraceStage.VERIFY_RESULT).copy(
            result = AutomationTraceResult.ABANDONED, attempt = 1,
            method = ActionMethod.NODE_CLICK, reason = ActionReason.TRAVERSAL_LIMIT,
            verificationIndex = 6, queryOnly = true,
        ))
        logger.record(event(AutomationTraceStage.FINISHED).copy(
            result = null, outcome = ActionOutcome.ABANDONED, reason = ActionReason.TRAVERSAL_LIMIT,
        ))

        val logs = logs()
        assertEquals(4, logs.size)
        assertTrue(logs[0].contains("stage=NODE_CLICK") && logs[0].contains("result=CLICKED"))
        assertTrue(logs[1].contains("stage=VERIFY_QUERY") && logs[1].contains("result=TRAVERSAL_LIMIT"))
        assertTrue(logs[2].contains("stage=VERIFY_RESULT") && logs[2].contains("result=ABANDONED"))
        assertTrue(logs[2].contains("method=NODE_CLICK") && logs[2].contains("reason=TRAVERSAL_LIMIT"))
        assertTrue(logs[2].contains("verificationIndex=6") && logs[2].contains("queryOnly=true"))
        assertTrue(logs[3].contains("stage=FINISHED") && logs[3].contains("outcome=ABANDONED"))
        assertTrue(logs[3].contains("reason=TRAVERSAL_LIMIT"))
        assertTrue(logs.none { it.contains("result=CONFIRMED") || it.contains("outcome=CLOSED") })
    }

    @Test
    fun resultChangeAndNewSessionAreNeverSuppressed() {
        val logger = AutomationTraceLog(enabled = true)
        logger.record(event())
        logger.record(event().copy(result = AutomationTraceResult.UNIQUE))
        logger.record(event().copy(sessionId = 2))
        assertEquals(3, logs().size)
        assertTrue(logs()[1].contains("result=UNIQUE"))
        assertTrue(logs()[2].contains("session=2"))
    }

    @Test
    fun disabledLoggerEmitsNoExecutionData() {
        val logger = AutomationTraceLog(enabled = false)
        for (stage in AutomationTraceStage.entries) logger.record(event(stage))
        assertTrue(logs().isEmpty())
    }

    @Test
    fun acceptedActionEvidenceRetryAndConclusionAreSeparatelyTraceable() {
        val logger = AutomationTraceLog(enabled = true)
        logger.record(event(AutomationTraceStage.ACTION_ACCEPTED).copy(result = AutomationTraceResult.ACCEPTED))
        logger.record(event(AutomationTraceStage.TARGET_OBSERVATION).copy(
            result = AutomationTraceResult.TARGET_ABSENT, proof = AbsenceProof.LOCAL_CONTAINER_REMOVED,
            eventRevision = 4, fallback = false,
        ))
        logger.record(event(AutomationTraceStage.RETRY_DECISION).copy(retryAllowed = false))
        logger.record(event(AutomationTraceStage.FINISHED).copy(
            outcome = ActionOutcome.CLOSED, observation = ActionObservationStatus.CONFIRMED,
        ))
        assertEquals(4, logs().size)
        assertTrue(logs()[0].contains("result=ACCEPTED"))
        assertTrue(logs()[1].contains("proof=LOCAL_CONTAINER_REMOVED"))
        assertTrue(logs()[1].contains("eventRevision=4") && logs()[1].contains("fallbackAllowed=false"))
        assertTrue(logs()[2].contains("retryAllowed=false"))
        assertTrue(logs()[3].contains("observation=CONFIRMED"))
    }

    private fun logs() = ShadowLog.getLogsForTag(AutomationTraceLog.TAG).map { it.msg }

    private fun event(stage: AutomationTraceStage = AutomationTraceStage.QUERY) = AutomationTraceEvent(
        sessionId = 1, elapsedMs = 0, stage = stage, targetHash = 123, windowId = 1,
        ruleHash = 456, selectorIndex = 0, result = AutomationTraceResult.NONE,
    )
}
