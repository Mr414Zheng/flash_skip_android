package com.zhenghuaizhi.flashskip.platform.accessibility

import android.app.Application
import android.view.accessibility.AccessibilityEvent
import com.zhenghuaizhi.flashskip.BuildConfig
import com.zhenghuaizhi.flashskip.application.rulecapture.*
import com.zhenghuaizhi.flashskip.domain.model.*
import com.zhenghuaizhi.flashskip.domain.ports.*
import com.zhenghuaizhi.flashskip.platform.diagnostics.CaptureDiagnosticSink
import java.io.IOException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class, shadows = [FaultableAccessibilityNodeShadow::class, CopyingAccessibilityServiceShadow::class])
class DiagnosticOutputContractTest {
    private val session = CaptureSession(1, phase = CapturePhase.OBSERVING_CLICK, phaseStartedAtEpochMs = 0, deadlineAtEpochMs = 10_000)
    @Before fun clearLogs() { ShadowLog.clear() }
    private fun logs() = ShadowLog.getLogs().joinToString("\n") { it.msg }
    @Test fun disabledLoggerDoesNotInvokeSuppliersOrSinks() {
        val logger = CaptureTraceLogger(false)
        logger.installSink(object : CaptureDiagnosticSink {
            override fun append(sessionId: Long?, message: String, essential: Boolean) { fail("disabled sink") }
            override fun beginSession(sessionId: Long) { fail("disabled registration") }
        })
        logger.beginSession(1); logger.state(session); logger.stage(1, "scan", "count" to 1)
        logger.lifecycle("service_started"); logger.boundary(1, "boundary"); logger.flush()
        logger.summary(session, CaptureTraceConclusion.FAILED, { error("disabled provider") }, { error("disabled provider") })
        assertEquals("", logs())
    }
    @Test fun metadataFilterNeverStringifiesArbitraryObjects() {
        val logger = CaptureTraceLogger(true)
        logger.stage(1, "scan", "text" to "secret", "throwable" to IOException("secret"),
            "object" to object { override fun toString(): String = error("unsafe stringify") },
            "decimal" to 2.5, "count" to 3, "flag" to true, "phase" to CapturePhase.OBSERVING_CLICK)
        val text = logs(); assertFalse(text.contains("secret")); assertFalse(text.contains("decimal="))
        assertTrue(text.contains("count=3")); assertTrue(text.contains("flag=true")); assertTrue(text.contains("phase=OBSERVING_CLICK"))
    }
    @Test fun accessibilityEventNeverOutputsTextDescriptionPackageOrClass() {
        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_CLICKED).apply {
            text.add("secret-text"); contentDescription = "secret-description"; packageName = "secret-package"; className = "secret-class"
        }
        try { CaptureTraceLogger(true).event(session, event) } finally { event.recycle() }
        assertTrue(logs().contains("accessibility_event")); assertFalse(logs().contains("secret-"))
    }
    @Test fun localSinkFailureDoesNotSuppressLogcatOrNextEvent() {
        val logger = CaptureTraceLogger(true)
        logger.installSink(object : CaptureDiagnosticSink {
            override fun append(sessionId: Long?, message: String, essential: Boolean) { throw IOException("secret-path") }
        })
        logger.boundary(1, "first"); logger.boundary(1, "second")
        assertTrue(logs().contains("stage=second")); assertFalse(logs().contains("secret-path"))
    }
    @Test fun sessionRegistrationFailureCannotEscape() {
        val logger = CaptureTraceLogger(true)
        logger.installSink(object : CaptureDiagnosticSink {
            override fun append(sessionId: Long?, message: String, essential: Boolean) = Unit
            override fun beginSession(sessionId: Long) { throw SecurityException("secret") }
        })
        logger.beginSession(1); logger.boundary(1, "after"); assertTrue(logs().contains("stage=after"))
    }
    @Test fun enabledDiagnosticSupplierFailureCannotEscapeBusinessBoundary() {
        CaptureTraceLogger(true).summary(session, CaptureTraceConclusion.FAILED, { throw IOException("secret") }, { null })
    }
    @Test fun enabledTargetWindowSupplierFailureCannotEscapeBusinessBoundary() {
        CaptureTraceLogger(true).summary(session, CaptureTraceConclusion.FAILED, { null }, { throw IllegalStateException("secret") })
    }
    @Test fun repeatedStagesFlushOnceAndPreserveMaximumDelay() {
        val logger = CaptureTraceLogger(true)
        listOf(4L, 20L, 2L).forEachIndexed { i, delay ->
            logger.stage(1, "scan", "count" to 1, "eventUptimeMs" to i.toLong(), "dispatchDelayMs" to delay)
        }
        assertEquals(1, ShadowLog.getLogs().size)
        logger.flush(); assertTrue(logs().contains("repeatCount=2")); assertTrue(logs().contains("maxDispatchDelayMs=20"))
        val count = ShadowLog.getLogs().size; logger.flush(); assertEquals(count, ShadowLog.getLogs().size)
    }
    @Test fun repeatedStageCapacityFlushesOldestAndRetainsBoundedSummaries() {
        val logger = CaptureTraceLogger(true)
        repeat(25) { logger.stage(1, "scan$it", "count" to 1); logger.stage(1, "scan$it", "count" to 1) }
        assertTrue(logs().contains("stage=scan0 count=1 repeatCount=1"))
        logger.flush(); assertEquals(25, ShadowLog.getLogs().count { "repeatCount=1" in it.msg })
    }
    @Test fun lateStageDoesNotResetCurrentSessionOrItsRepeatBuffer() {
        val logger = CaptureTraceLogger(true)
        logger.state(session); logger.state(session.copy(id = 2)); logger.stage(2, "scan", "count" to 1)
        logger.stage(1, "scan", "count" to 8); logger.stage(2, "scan", "count" to 1); logger.flush()
        assertTrue(ShadowLog.getLogs().any { "session=1 " in it.msg && "late=true" in it.msg })
        assertTrue(ShadowLog.getLogs().any { "session=2 " in it.msg && "repeatCount=1" in it.msg })
    }
    @Test fun errorClassificationDoesNotContainExceptionMessages() {
        assertEquals(CaptureTraceError.IO, CaptureTraceError.from(IOException("secret")))
        assertEquals(CaptureTraceError.SECURITY, CaptureTraceError.from(SecurityException("secret")))
        assertEquals(CaptureTraceError.CANCELLED, CaptureTraceError.from(CancellationException("secret")))
        assertEquals(CaptureTraceError.OTHER, CaptureTraceError.from(IllegalStateException("secret")))
    }
    @Test fun automationDisabledProducesNoLogs() {
        AutomationTraceLog(false).record(event()); assertEquals("", logs())
    }
    @Test fun automationRepeatedQueriesFlushAtTerminalEvent() {
        val logger = AutomationTraceLog(true)
        repeat(3) { logger.record(event().copy(stage = AutomationTraceStage.QUERY, elapsedMs = it.toLong())) }
        logger.record(event().copy(stage = AutomationTraceStage.FINISHED))
        assertTrue(logs().contains("repeatCount=2")); assertTrue(logs().contains("stage=FINISHED"))
    }
    @Test fun automationOutputMustNotContainStableContentHashes() {
        AutomationTraceLog(true).record(event())
        assertFalse("稳定内容散列不得写入诊断", logs().contains("targetHash=1234abcd"))
    }
    @Test fun scanOutputMustNotContainStableContentHashes() {
        val f = AccessibilityFixture()
        AccessibilityScanTraceLog(true, f.identity, f.selector, ScanOperation.QUERY).finish(AutomationTraceResult.UNIQUE)
        assertFalse("扫描目标不得输出稳定散列", logs().contains("targetHash=${f.identity.packageName.hashCode().toUInt().toString(16)}"))
    }
    @Test fun actionEvidenceOutputMustNotContainStableContentHashes() {
        val f = AccessibilityFixture(); val evidence = f.evidence.capture(f.target, f.identity, f.selector)
        f.evidence.observe(f.root, evidence, false)
        assertTrue(logs().contains("ACTION_EVIDENCE_READ"))
        assertFalse("动作证据不得输出稳定散列", logs().contains("targetHash=${f.identity.packageName.hashCode().toUInt().toString(16)}"))
    }
    @Test fun disabledScanDoesNotReadClockOrCreateDiagnosticCollectors() {
        val f = AccessibilityFixture()
        val logger = AccessibilityScanTraceLog(false, f.identity, f.selector, ScanOperation.QUERY, object : MonotonicClock {
            override fun elapsedRealtimeMs(): Long = error("disabled clock")
        })
        assertNull(logger.candidateSink); assertNull(logger.searchDiagnostics)
        logger.finish(AutomationTraceResult.UNIQUE); assertEquals("", logs())
    }
    @Test fun managementDisabledNeverConstructsDiagnosticEvent() {
        RuleManagementTraceLog(false).recordSafely { error("disabled event") }; assertEquals("", logs())
    }
    @Test fun managementSinkFailureIncludingCancellationCannotChangeCallerResult() {
        for (error in listOf(IOException("secret"), CancellationException("sink"))) {
            val sink = object : RuleManagementTraceSink {
                override val enabled = true
                override fun record(event: RuleManagementTraceEvent) { throw error }
            }
            sink.recordSafely { RuleManagementTraceEvent(RuleManagementOperation.PUBLISH, RuleManagementOutcome.SUCCESS) }
        }
    }
    @Test fun managementOutputMustNotContainStableSessionHash() {
        RuleManagementTraceLog(true).record(RuleManagementTraceEvent(RuleManagementOperation.CANCEL, RuleManagementOutcome.SUCCESS,
            sessionHash = 305441741, sessionId = 1))
        assertTrue(BuildConfig.DEBUG); assertFalse(logs().contains("sessionHash=305441741"))
    }
    @Test fun automationRepeatCapacityFlushesEvictedKeysAndTerminalRemainder() {
        val logger = AutomationTraceLog(true)
        repeat(65) { index ->
            val query = event().copy(stage = AutomationTraceStage.QUERY, ruleHash = index)
            logger.record(query); logger.record(query)
        }
        assertEquals(1, ShadowLog.getLogs().count { "repeatCount=1" in it.msg })
        logger.record(event().copy(stage = AutomationTraceStage.FINISHED))
        assertEquals(65, ShadowLog.getLogs().count { "repeatCount=1" in it.msg })
    }
    @Test fun managementRepeatedFailuresFlushEvery32AndBeforeDifferentEvent() {
        val logger = RuleManagementTraceLog(true)
        val event = RuleManagementTraceEvent(RuleManagementOperation.SERVICE_UPDATE,
            RuleManagementOutcome.STORAGE_FAILURE, sessionId = 1)
        repeat(34) { logger.record(event) }
        assertTrue(logs().contains("repeatCount=32"))
        logger.record(RuleManagementTraceEvent(RuleManagementOperation.PUBLISH, RuleManagementOutcome.SUCCESS))
        assertTrue(logs().contains("repeatCount=1")); assertTrue(logs().contains("operation=PUBLISH"))
    }
    @Test fun onlyTwoRecentSessionTimingOriginsAreRetained() {
        val logger = CaptureTraceLogger(true)
        (1L..3L).forEach { logger.state(session.copy(id = it)) }
        ShadowLog.clear(); logger.stage(1, "late_scan"); logger.stage(3, "current_scan")
        val old = ShadowLog.getLogs().single { "stage=late_scan" in it.msg }.msg
        val current = ShadowLog.getLogs().single { "stage=current_scan" in it.msg }.msg
        assertTrue(old.contains("late=true")); assertFalse(old.contains("relativeMs="))
        assertTrue(current.contains("relativeMs="))
    }

    private fun event() = AutomationTraceEvent(1, 0, AutomationTraceStage.SESSION_START, 0x1234abcd, 7,
        ruleHash = 0x1234abcd, selectorHash = 0x1234abcd, resourceIdHash = 0x1234abcd, ancestorIdHash = 0x1234abcd)
}
