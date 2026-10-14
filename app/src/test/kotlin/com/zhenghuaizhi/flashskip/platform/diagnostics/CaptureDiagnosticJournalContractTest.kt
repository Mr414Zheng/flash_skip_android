package com.zhenghuaizhi.flashskip.platform.diagnostics

import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CaptureDiagnosticJournalContractTest {
    @get:Rule val temporary = TemporaryFolder()
    private val executor = ManualExecutor()
    private val failures = mutableListOf<CaptureJournalFailure>()
    private fun journal(dir: File, sessions: Int = 10, capacity: Int = 256, sessionBytes: Int = 1024 * 1024, runtimeBytes: Int = 128 * 1024) =
        CaptureDiagnosticJournal(dir, executor, { 100L }, { failures += it }, sessionBytes, runtimeBytes, sessions, capacity)
    private fun File.log(name: String = "runtime.log") = File(this, name).takeIf { it.exists() }?.readText().orEmpty()

    @Test fun appendIsAsynchronousAndUsesOneDrainTask() {
        val dir = temporary.newFolder(); val j = journal(dir)
        j.append(null, "stage=first"); j.append(null, "stage=second")
        assertEquals(1, executor.pending.size); assertFalse(File(dir, "runtime.log").exists())
        executor.drain(); assertTrue(dir.log().indexOf("first") < dir.log().indexOf("second"))
    }
    @Test fun defaultQueueRetains256AndReportsOrdinaryOverflow() {
        val dir = temporary.newFolder(); val j = journal(dir)
        repeat(257) { j.append(null, "eventId=$it") }; executor.drain()
        assertEquals(256, dir.log().lines().count { "eventId=" in it })
        assertTrue(dir.log().contains("ordinary=1 essential=0")); assertFalse(dir.log().contains("eventId=256"))
    }
    @Test fun importantEventEvictsOldestOrdinaryAtCapacity() {
        val dir = temporary.newFolder(); val j = journal(dir, capacity = 2)
        j.append(null, "firstOrdinary"); j.append(null, "secondOrdinary"); j.append(null, "important", true)
        executor.drain(); assertFalse(dir.log().contains("firstOrdinary"))
        assertTrue(dir.log().contains("secondOrdinary")); assertTrue(dir.log().contains("important"))
        assertTrue(dir.log().contains("ordinary=1 essential=0"))
    }
    @Test fun allImportantQueueMayDropAndCountsImportantLoss() {
        val dir = temporary.newFolder(); val j = journal(dir, capacity = 2)
        repeat(3) { j.append(null, "important=$it", true) }; executor.drain()
        assertTrue(dir.log().contains("ordinary=0 essential=1")); assertFalse(dir.log().contains("important=2"))
    }
    @Test fun ordinaryCannotEvictImportantAtCapacity() {
        val dir = temporary.newFolder(); val j = journal(dir, capacity = 1)
        j.append(null, "important", true); j.append(null, "discardedOrdinary"); executor.drain()
        assertTrue(dir.log().contains("important")); assertFalse(dir.log().contains("discardedOrdinary"))
    }
    @Test fun rejectedExecutorDoesNotThrowAndNextEventRecoversCounters() {
        val dir = temporary.newFolder(); val j = journal(dir)
        executor.failure = RejectedExecutionException("sensitive-path")
        j.append(null, "lost"); assertEquals(listOf(CaptureJournalFailure.OTHER), failures)
        executor.failure = null; j.append(null, "recovered"); executor.drain()
        assertTrue(dir.log().contains("ordinary=1")); assertTrue(dir.log().contains("other=1"))
        assertFalse(dir.log().contains("sensitive-path")); assertFalse(dir.log().contains(" lost"))
    }
    @Test fun failureCallbackExceptionCannotEscapeOrPreventRecovery() {
        val dir = temporary.newFile(); val j = CaptureDiagnosticJournal(dir, executor, { 100 }, { throw IllegalStateException("callback") })
        j.append(null, "failed"); executor.drain(); assertTrue(dir.delete()); assertTrue(dir.mkdir())
        j.append(null, "recovered"); executor.drain(); assertTrue(dir.log().contains("recovered"))
    }
    @Test fun directoryFailureIsReportedOncePerFailurePeriod() {
        val dir = temporary.newFile(); val j = journal(dir)
        repeat(3) { j.append(null, "failed") }; executor.drain()
        assertEquals(listOf(CaptureJournalFailure.IO), failures)
        assertTrue(dir.delete()); assertTrue(dir.mkdir()); j.append(null, "ok"); executor.drain()
        assertTrue(dir.log().contains("io=3"))
        dir.deleteRecursively(); dir.writeText("collision"); j.append(null, "failedAgain"); executor.drain()
        assertEquals(listOf(CaptureJournalFailure.IO, CaptureJournalFailure.IO), failures)
    }
    @Test fun securityExecutorFailureOnlyReportsClassification() {
        val dir = temporary.newFolder(); val j = journal(dir)
        executor.failure = SecurityException("secret"); j.append(null, "drop")
        assertEquals(listOf(CaptureJournalFailure.SECURITY), failures)
        executor.failure = null; j.append(null, "ok"); executor.drain()
        assertTrue(dir.log().contains("security=1")); assertFalse(dir.log().contains("secret"))
    }
    @Test fun unicodeRecordIsValidUtf8AndAtMost4096Bytes() {
        val dir = temporary.newFolder(); val j = journal(dir)
        j.append(null, "界😀".repeat(2000)); executor.drain()
        val bytes = File(dir, "runtime.log").readBytes()
        val decoded = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        assertTrue(bytes.size <= 4096); assertTrue(decoded.endsWith(" messageTruncated=true\n"))
        assertFalse(decoded.contains('\uFFFD'))
    }
    @Test fun newlineAndCarriageReturnCannotForgeRecords() {
        val dir = temporary.newFolder(); val j = journal(dir)
        j.append(null, "stage=one\r\nstage=forged"); executor.drain()
        assertEquals(1, dir.log().lines().count { it.isNotEmpty() }); assertFalse(dir.log().contains('\r'))
    }
    @Test fun runtimeRotationKeepsOnlyCurrentAndPreviousWithinBudget() {
        val dir = temporary.newFolder(); val j = journal(dir, runtimeBytes = 512)
        repeat(20) { j.append(null, "event=$it " + "x".repeat(150)); executor.drain() }
        assertTrue(File(dir, "runtime.log").length() <= 512)
        assertTrue(File(dir, "runtime-1.log").length() in 1..512)
        assertEquals(setOf("runtime.log", "runtime-1.log"), dir.list()!!.toSet())
        assertTrue(dir.log().contains("event=19")); assertFalse((dir.log() + dir.log("runtime-1.log")).contains("event=0 "))
    }
    @Test fun ordinaryBudgetReservesRoomForImportantTerminalEvidence() {
        val dir = temporary.newFolder(); val j = journal(dir, sessionBytes = 1024)
        j.beginSession(1); repeat(20) { j.append(1, "ordinary=$it " + "x".repeat(80)) }; executor.drain()
        assertTrue(dir.log("session-1.log").contains("scope=ordinary"))
        j.append(1, "terminal", true); executor.drain()
        assertTrue(dir.log("session-1.log").contains("terminal")); assertTrue(File(dir, "session-1.log").length() <= 1024)
    }
    @Test fun fullSessionBudgetIsBoundedAndDoesNotGrowAfterTruncation() {
        val dir = temporary.newFolder(); val j = journal(dir, sessionBytes = 512)
        repeat(20) { j.append(1, "important=$it " + "x".repeat(100), true) }; executor.drain()
        val size = File(dir, "session-1.log").length()
        assertTrue(size <= 512); assertTrue(dir.log("session-1.log").contains("scope=all"))
        j.append(1, "later", true); executor.drain(); assertEquals(size, File(dir, "session-1.log").length())
    }
    @Test fun reopeningRetainsSessionTruncationBoundary() {
        val dir = temporary.newFolder(); val first = journal(dir, sessionBytes = 512)
        first.append(1, "x".repeat(1000), true); executor.drain()
        val before = File(dir, "session-1.log").readBytes()
        journal(dir, sessionBytes = 512).append(1, "afterReopen", true); executor.drain()
        assertArrayEquals(before, File(dir, "session-1.log").readBytes())
    }
    @Test fun tenSessionDefaultIsBoundedInRegistrationOrder() {
        val dir = temporary.newFolder(); val j = journal(dir)
        (20L downTo 10L).forEach { j.beginSession(it); executor.drain() }
        assertFalse(File(dir, "session-20.log").exists()); assertTrue(File(dir, "session-10.log").exists())
        assertEquals(10, dir.listFiles()!!.count { it.name.startsWith("session-") })
        assertEquals((19L downTo 10L).map { "$it" }, File(dir, "sessions.index").readLines().drop(1))
    }
    @Test fun evictedLateSessionCannotBeRecreatedAfterReopen() {
        val dir = temporary.newFolder(); val j = journal(dir, sessions = 1)
        j.beginSession(100); executor.drain(); j.beginSession(50); executor.drain()
        journal(dir, sessions = 1).append(100, "late", true); executor.drain()
        assertFalse(File(dir, "session-100.log").exists()); assertTrue(dir.log().contains("journal_unknown_session_rejected count=1"))
    }
    @Test fun explicitNewSessionAllowsClockRollbackIdentity() {
        val dir = temporary.newFolder(); val j = journal(dir)
        j.beginSession(100); executor.drain(); j.beginSession(5); j.append(5, "new", true); executor.drain()
        assertTrue(dir.log("session-5.log").contains("new"))
    }
    @Test fun duplicateRegistrationDoesNotDiscardExistingEvidence() {
        val dir = temporary.newFolder(); val j = journal(dir)
        j.beginSession(1); j.append(1, "kept"); executor.drain(); j.beginSession(1); executor.drain()
        assertTrue(dir.log("session-1.log").contains("kept")); assertEquals(listOf("1"), File(dir, "sessions.index").readLines().drop(1))
    }
    @Test fun negativeSessionIsIgnoredWithoutScheduling() {
        val dir = temporary.newFolder(); val j = journal(dir)
        j.beginSession(-1); j.append(-1, "invalid", true)
        assertTrue(executor.pending.isEmpty()); assertTrue(dir.list()!!.isEmpty())
    }
    @Test fun sessionRegistrationSurvivesDroppedStartRecord() {
        val dir = temporary.newFolder(); val j = journal(dir, capacity = 1)
        j.append(null, "occupied", true); j.beginSession(2); executor.drain()
        j.append(2, "accepted", true); executor.drain()
        assertTrue(dir.log("session-2.log").contains("accepted")); assertTrue(dir.log().contains("essential=1"))
    }
    @Test fun corruptIndexPreservesIndexAndExistingEvidence() {
        val dir = temporary.newFolder(); val index = File(dir, "sessions.index").apply { writeText("broken-secret") }
        val old = File(dir, "session-1.log").apply { writeText("old") }; val j = journal(dir)
        j.beginSession(2); j.append(2, "new"); executor.drain()
        assertEquals("broken-secret", index.readText()); assertEquals("old", old.readText())
        assertFalse(File(dir, "session-2.log").exists()); assertEquals(listOf(CaptureJournalFailure.IO), failures)
    }
    @Test fun repairedIndexAllowsLaterEventsWithoutReplayingFailedEvents() {
        val dir = temporary.newFolder(); val index = File(dir, "sessions.index").apply { writeText("bad") }; val j = journal(dir)
        j.append(null, "failedEvent"); executor.drain()
        index.writeText("schema=1 highWatermark=-1\n")
        j.append(null, "recoveredEvent"); executor.drain()
        assertTrue(dir.log().contains("recoveredEvent")); assertFalse(dir.log().contains("failedEvent"))
        assertTrue(dir.log().contains("io=1"))
    }
    @Test fun invalidLedgerVariantsAreNeverOverwritten() {
        listOf("schema=2 highWatermark=1\n1\n", "schema=1 highWatermark=1\n1\n1\n",
            "schema=1 highWatermark=1\n2\n", "schema=1 highWatermark=1\n-1\n", "x".repeat(1000)).forEach { raw ->
            val dir = temporary.newFolder(); val index = File(dir, "sessions.index").apply { writeText(raw) }
            journal(dir).append(null, "new"); executor.drain(); assertEquals(raw, index.readText())
        }
        assertEquals(5, failures.size)
    }
    @Test fun unrelatedFilesArePreservedByRetention() {
        val dir = temporary.newFolder(); val external = File(dir, "unrelated.txt").apply { writeText("keep") }
        val j = journal(dir, sessions = 1); j.beginSession(1); executor.drain(); j.beginSession(2); executor.drain()
        assertEquals("keep", external.readText())
    }

    @Test fun defaultSessionLimitIsOneMiBIncludingTruncationMarkers() {
        val dir = temporary.newFolder(); val j = journal(dir)
        j.beginSession(1); executor.drain()
        repeat(300) { j.append(1, "x".repeat(4096)); executor.drain() }
        assertTrue(dir.log("session-1.log").contains("scope=ordinary"))
        repeat(100) { j.append(1, "y".repeat(4096), true); executor.drain() }
        assertTrue(File(dir, "session-1.log").length() in (1024L * 1024 - 4096)..(1024L * 1024))
        assertTrue(dir.log("session-1.log").contains("scope=all"))
    }
    @Test fun defaultRuntimeLimitIs128KiBPerRetainedFile() {
        val dir = temporary.newFolder(); val j = journal(dir)
        repeat(100) { j.append(null, "x".repeat(4096)); executor.drain() }
        assertTrue(File(dir, "runtime.log").length() in 1..(128L * 1024))
        assertEquals(128L * 1024, File(dir, "runtime-1.log").length())
        assertEquals(setOf("runtime.log", "runtime-1.log"), dir.list()!!.toSet())
    }
    @Test fun failedIndexReplacementKeepsPreviousLedgerAndSession() {
        val dir = temporary.newFolder(); val j = journal(dir, sessions = 1)
        j.beginSession(1); executor.drain()
        val ledger = File(dir, "sessions.index").readBytes()
        val first = File(dir, "session-1.log").readBytes()
        assertTrue(File(dir, "sessions.index.tmp").mkdir())
        j.beginSession(2); executor.drain()
        assertArrayEquals(ledger, File(dir, "sessions.index").readBytes())
        assertArrayEquals(first, File(dir, "session-1.log").readBytes())
        assertEquals(listOf(CaptureJournalFailure.IO), failures)
        File(dir, "sessions.index.tmp").delete()
        j.append(2, "afterRepair", true); executor.drain()
        assertTrue(dir.log("session-2.log").contains("afterRepair")); assertFalse(File(dir, "session-1.log").exists())
    }

    private class ManualExecutor : Executor {
        val pending = ArrayDeque<Runnable>()
        var failure: RuntimeException? = null
        override fun execute(command: Runnable) { failure?.let { throw it }; pending.addLast(command) }
        fun drain() { while (pending.isNotEmpty()) pending.removeFirst().run() }
    }
}
