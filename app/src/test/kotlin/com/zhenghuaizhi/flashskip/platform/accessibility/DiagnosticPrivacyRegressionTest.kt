package com.zhenghuaizhi.flashskip.platform.accessibility

import android.app.Application
import com.zhenghuaizhi.flashskip.domain.model.CapturePhase
import com.zhenghuaizhi.flashskip.domain.model.CaptureSession
import com.zhenghuaizhi.flashskip.domain.ports.*
import com.zhenghuaizhi.flashskip.platform.diagnostics.CaptureDiagnosticSink
import com.zhenghuaizhi.flashskip.platform.diagnostics.ProcessDiagnosticIdentifiers
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class, shadows = [FaultableAccessibilityNodeShadow::class, CopyingAccessibilityServiceShadow::class])
class DiagnosticPrivacyRegressionTest {
    @Before fun clearLogs() { ShadowLog.clear() }
    private fun logs() = ShadowLog.getLogs().joinToString("\n") { it.msg }
    private fun event() = AutomationTraceEvent(987654321, 0, AutomationTraceStage.SESSION_START, 0x1234abcd, 7,
        ruleHash = 0x1234abcd, selectorHash = 0x1234abcd, resourceIdHash = 0x1234abcd, ancestorIdHash = 0x1234abcd)
    private fun session(id: Long = 987654321) = CaptureSession(id, phase = CapturePhase.CAPTURED, phaseStartedAtEpochMs = 0, deadlineAtEpochMs = 10000)

    @Test fun allAutomationContentFieldsUseScopedTokens() {
        AutomationTraceLog(true).record(event())
        assertFalse(logs().contains("Hash=")); assertFalse(logs().contains("1234abcd")); assertFalse(logs().contains("987654321"))
        for (name in listOf("target", "rule", "selector", "resourceId", "ancestorId", "session")) {
            assertTrue(Regex("${name}Token=[a-f0-9]{32}").containsMatchIn(logs()))
        }
    }
    @Test fun independentProcessSecretsProduceDifferentLoggerTokens() {
        val first = mutableListOf<String>(); val second = mutableListOf<String>()
        AutomationTraceLog(true, ProcessDiagnosticIdentifiers(ByteArray(32) { 1 })) { first += it }.record(event())
        AutomationTraceLog(true, ProcessDiagnosticIdentifiers(ByteArray(32) { 2 })) { second += it }.record(event())
        val token = Regex("targetToken=([a-f0-9]+)")
        assertNotEquals(token.find(first.single())!!.value, token.find(second.single())!!.value)
    }
    @Test fun scanningAndAutomationCorrelateTargetOnlyWithinSameProcess() {
        val f = AccessibilityFixture()
        AutomationTraceLog(true).record(event().copy(targetHash = f.identity.packageName.hashCode()))
        AccessibilityScanTraceLog(true, f.identity, f.selector, ScanOperation.QUERY).finish(AutomationTraceResult.UNIQUE)
        val tokens = Regex("targetToken=([a-f0-9]+)").findAll(logs()).map { it.groupValues[1] }.toList()
        assertEquals(2, tokens.size); assertEquals(tokens[0], tokens[1])
        assertFalse(logs().contains(f.identity.packageName))
    }
    @Test fun candidateResourceAndClassValuesAreNeverWrittenAsStableHashes() {
        val f = AccessibilityFixture()
        val scan = AccessibilityScanTraceLog(true, f.identity, f.selector, ScanOperation.QUERY)
        scan.candidateSink!!(CandidateCheck(false, true, CandidateMatchResult.MATCH, false, 0x1234abcd, 0x1234abcd, true, true))
        assertTrue(logs().contains("resourceIdToken=")); assertTrue(logs().contains("classToken="))
        assertFalse(logs().contains("Hash=")); assertFalse(logs().contains("1234abcd"))
    }
    @Test fun actionRevalidationAndGestureCallbackUseScopedTokens() = runTest {
        val f = AccessibilityFixture()
        f.gateway.submitClick(f.identity, f.selector)
        f.gateway.submitGesture(f.identity, f.selector, 100)
        val dispatch = f.serviceShadow.gesturesDispatched.single()
        dispatch.callback()!!.onCompleted(dispatch.description())
        assertTrue(logs().contains("ACTION_REVALIDATED")); assertTrue(logs().contains("GESTURE_CALLBACK"))
        assertFalse(logs().contains("Hash=")); assertFalse(logs().contains(f.identity.packageName))
    }
    @Test fun persistedSessionIdIsNeitherPrintedNorPassedToJournal() {
        val ids = mutableListOf<Long?>(); val messages = mutableListOf<String>(); val starts = mutableListOf<Long>()
        val logger = CaptureTraceLogger(true)
        logger.installSink(object : CaptureDiagnosticSink {
            override fun append(sessionId: Long?, message: String, essential: Boolean) { ids += sessionId; messages += message }
            override fun beginSession(sessionId: Long) { starts += sessionId }
        })
        logger.beginSession(987654321); logger.state(session()); logger.boundary(987654321, "after")
        assertTrue(ids.isNotEmpty()); assertTrue(starts.isNotEmpty())
        assertTrue(ids.all { it != 987654321L }); assertTrue(starts.all { it == ids.first() })
        assertFalse(messages.any { "987654321" in it }); assertTrue(messages.all { "session=1 " in it })
    }
    @Test fun captureLogcatFailureDoesNotPreventFileSinkOrFutureRecords() {
        val messages = mutableListOf<String>()
        val logger = CaptureTraceLogger(true, logOutput = { _, _, _ -> throw IOException("backend-secret") })
        logger.installSink(object : CaptureDiagnosticSink {
            override fun append(sessionId: Long?, message: String, essential: Boolean) { messages += message }
        })
        logger.boundary(1, "first"); logger.boundary(1, "second")
        assertEquals(2, messages.size); assertFalse(messages.any { "backend-secret" in it })
    }
    @Test fun automationBackendFailureDoesNotEscapeOrPreventNextRecord() {
        var calls = 0
        val logger = AutomationTraceLog(true, output = { calls++; throw IOException("backend-secret") })
        logger.record(event()); logger.record(event().copy(stage = AutomationTraceStage.FINISHED))
        assertEquals(2, calls)
    }
    @Test fun providerFailuresAreClassifiedAndOtherSummaryProviderStillRuns() {
        var secondCalled = false
        val logger = CaptureTraceLogger(true)
        logger.summary(session(), CaptureTraceConclusion.FAILED, { throw IOException("supplier-secret") }, { secondCalled = true; null })
        logger.boundary(987654321, "after")
        assertTrue(secondCalled); assertTrue(logs().contains("error=IO")); assertTrue(logs().contains("stage=after"))
        assertFalse(logs().contains("supplier-secret"))
    }
    @Test fun disabledLoggerDoesNotCallInjectedLogBackend() {
        CaptureTraceLogger(false, logOutput = { _, _, _ -> fail("disabled backend") }).lifecycle("ignored")
        AutomationTraceLog(false, output = { fail("disabled backend") }).record(event())
        assertEquals("", logs())
    }
}
