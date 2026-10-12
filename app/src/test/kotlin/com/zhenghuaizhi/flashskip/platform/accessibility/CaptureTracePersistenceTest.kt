package com.zhenghuaizhi.flashskip.platform.accessibility

import com.zhenghuaizhi.flashskip.domain.model.CaptureFailure
import com.zhenghuaizhi.flashskip.domain.model.CaptureFailureDiagnostics
import com.zhenghuaizhi.flashskip.domain.model.CapturePhase
import com.zhenghuaizhi.flashskip.domain.model.CaptureSession
import com.zhenghuaizhi.flashskip.domain.model.RuleCaptureState
import com.zhenghuaizhi.flashskip.platform.diagnostics.CaptureDiagnosticSink
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class CaptureTracePersistenceTest {
    @Test
    fun startupAndEmptyStateAreRecordedWithoutServiceOrSession() {
        val entries = mutableListOf<Entry>()
        val logger = logger(entries)
        logger.lifecycle("capture_diagnostics_ready", "debug" to true)
        logger.persistedState(null, RuleCaptureState())
        assertEquals(2, entries.size)
        assertTrue(entries.all { it.id == null && it.essential })
        assertTrue(entries[1].message.contains("hasSession=false"))
        assertTrue(entries[1].message.contains("firstRead=true"))
    }

    @Test
    fun failedStatePersistsDiagnosticSummaryAndClearingKeepsSessionAssociation() {
        val entries = mutableListOf<Entry>()
        val logger = logger(entries)
        val observing = RuleCaptureState(activeSession = session())
        val failed = observing.copy(activeSession = session().copy(
            phase = CapturePhase.FAILED, failure = CaptureFailure.DISMISS_NOT_CAPTURED,
            failureDiagnostics = CaptureFailureDiagnostics(false, 0, false),
        ))
        logger.persistedState(null, observing)
        logger.persistedState(observing, failed)
        logger.persistedState(failed, RuleCaptureState())
        assertTrue(entries.any { it.id == 7L && it.message.contains("stage=capture_diagnostics") && it.essential })
        assertTrue(entries.any { it.message.contains("failure=DISMISS_NOT_CAPTURED") })
        assertTrue(entries.any { it.id == 7L && it.message.contains("stage=persisted_session_ended") })
    }

    @Test
    fun applicationObserverDoesNotResetServiceMergeContext() {
        val entries = mutableListOf<Entry>()
        val logger = logger(entries)
        logger.state(session())
        logger.stage(7, "scan", "count" to 1)
        logger.persistedState(null, RuleCaptureState(activeSession = session().copy(id = 8)))
        logger.stage(7, "scan", "count" to 1)
        logger.flush()
        assertEquals(2, entries.count { it.message.contains("stage=scan") })
        assertTrue(entries.any { it.id == 7L && it.message.contains("repeatCount=1") })
        assertFalse(entries.any { it.message.contains("late=true") })
    }

    @Test
    fun bothOutputsReceiveOnlySafeMetadataAndSinkFailureDoesNotBreakLogcat() {
        ShadowLog.clear()
        val entries = mutableListOf<Entry>()
        val logger = logger(entries)
        logger.boundary(7, "configuration_failed", "error" to CaptureTraceError.IO,
            "private" to "private-page", "nodes" to listOf("private-node"))
        assertTrue(entries.single().message.contains("error=IO"))
        assertFalse(entries.single().message.contains("private"))
        logger.installSink(object : CaptureDiagnosticSink {
            override fun append(sessionId: Long?, message: String, essential: Boolean) {
                throw IllegalStateException("private-error")
            }
        })
        logger.lifecycle("after_sink_failure")
        assertTrue(ShadowLog.getLogsForTag(CaptureTraceLogger.TAG).any { it.msg.contains("after_sink_failure") })
    }

    @Test
    fun disabledLoggerDoesNotWriteOrReadLazyDiagnostics() {
        val entries = mutableListOf<Entry>()
        val logger = logger(entries, enabled = false)
        logger.lifecycle("startup")
        logger.persistedState(null, RuleCaptureState(activeSession = session()))
        logger.summary(session(), CaptureTraceConclusion.FAILED,
            diagnostics = { error("must not read diagnostics") },
            targetWindow = { error("must not read windows") })
        assertTrue(entries.isEmpty())
    }

    private fun logger(entries: MutableList<Entry>, enabled: Boolean = true) =
        CaptureTraceLogger(enabled).apply {
            installSink(object : CaptureDiagnosticSink {
                override fun append(sessionId: Long?, message: String, essential: Boolean) {
                    entries.add(Entry(sessionId, message, essential))
                }
            })
        }

    private fun session() = CaptureSession(id = 7, phase = CapturePhase.OBSERVING_CLICK,
        phaseStartedAtEpochMs = 0, deadlineAtEpochMs = 15_000)

    private data class Entry(val id: Long?, val message: String, val essential: Boolean)
}
