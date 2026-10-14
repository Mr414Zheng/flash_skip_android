package com.zhenghuaizhi.flashskip.platform.accessibility

import org.junit.Assert.*
import org.junit.Test

class CaptureDiagnosticTrailContractTest {
    private fun update(trail: CaptureDiagnosticTrail, id: Long = 1, time: Long = 110) =
        trail.recordHostWindowUpdate(id, time, 1, 7, null, 7, false, null)
    @Test fun defaultTrailKeeps48LatestRecordsAndReportsLoss() {
        val trail = CaptureDiagnosticTrail(); trail.beginSession(1, 100)
        repeat(50) { update(trail, time = 100L + it) }
        val trace = trail.snapshot(1)!!
        assertEquals(48, trace.records.size); assertEquals(50, trace.summary.totalRecordCount)
        assertEquals(2, trace.summary.droppedRecordCount)
        assertEquals(2L, trace.records.first().elapsedMs); assertEquals(49L, trace.records.last().elapsedMs)
    }
    @Test fun callbackFailureDoesNotLoseStoredRecordOrStopNextUpdate() {
        val trail = CaptureDiagnosticTrail(onRecord = { _, _, _ -> throw IllegalStateException("sink") })
        trail.beginSession(1, 100); update(trail); update(trail)
        assertEquals(2, trail.snapshot(1)!!.records.size)
    }
    @Test fun lateSessionCannotWriteIntoCurrentTrail() {
        val trail = CaptureDiagnosticTrail(); trail.beginSession(1, 100); update(trail)
        trail.beginSession(2, 200); update(trail, id = 1)
        assertNull(trail.snapshot(1)); assertTrue(trail.snapshot(2)!!.records.isEmpty())
    }
    @Test fun relativeTimesNeverBecomeNegative() {
        val trail = CaptureDiagnosticTrail(); trail.beginSession(1, 100); update(trail, time = 50)
        assertEquals(0L, trail.snapshot(1)!!.records.single().elapsedMs)
    }
    @Test fun resetClearsAllSessionEvidence() {
        val trail = CaptureDiagnosticTrail(); trail.beginSession(1, 100); update(trail); trail.reset()
        assertNull(trail.snapshot(1)); trail.beginSession(2, 200)
        assertEquals(0, trail.snapshot(2)!!.summary.totalRecordCount)
    }
}
