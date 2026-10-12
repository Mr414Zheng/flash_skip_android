package com.zhenghuaizhi.flashskip.platform.diagnostics

import java.io.File
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.util.ArrayDeque
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureDiagnosticJournalTest {
    @Test
    fun appendDefersAllFileOperationsAndSchedulesOnlyOneWriter() = withDirectory { directory ->
        val executor = ControlledExecutor()
        val target = File(directory, "not-created")
        val journal = journal(target, executor)

        journal.append(1, "event=started")
        journal.append(1, "event=ended", essential = true)

        assertFalse(target.exists())
        assertEquals(1, executor.tasks.size)
        executor.runAll()
        assertTrue(File(target, "session-1.log").readText().contains("event=ended"))
    }

    @Test
    fun queueIsBoundedAndEssentialReplacesOrdinaryBeforeBeingDropped() = withDirectory { directory ->
        val executor = ControlledExecutor()
        val journal = journal(directory, executor, queueCapacity = 3)
        journal.append(1, "event=ordinary_old")
        journal.append(1, "event=ordinary_kept")
        journal.append(1, "event=essential_first", true)
        journal.append(1, "event=essential_second", true)
        journal.append(1, "event=essential_third", true)
        journal.append(1, "event=essential_dropped", true)
        journal.append(1, "event=ordinary_dropped")

        executor.runAll()

        val session = File(directory, "session-1.log").readText()
        assertFalse(session.contains("ordinary_old"))
        assertFalse(session.contains("ordinary_kept"))
        assertFalse(session.contains("essential_dropped"))
        assertFalse(session.contains("ordinary_dropped"))
        assertTrue(session.contains("essential_first"))
        assertTrue(session.contains("essential_second"))
        assertTrue(session.contains("essential_third"))
        val recovery = File(directory, "runtime.log").readText()
        assertTrue(recovery.contains("journal_queue_dropped ordinary=3 essential=1"))
        journal.append(1, "event=after_recovery")
        executor.runAll()
        assertEquals(1, File(directory, "runtime.log").readLines().size)
    }

    @Test
    fun ordinaryBudgetRetainsSpaceForTerminalAndEveryLineIsBoundedUtf8() = withDirectory { directory ->
        val executor = ControlledExecutor()
        val journal = journal(directory, executor, maxSessionBytes = 8192)
        repeat(12) { journal.append(7, "event=scan id=$it " + "界🙂".repeat(5000)) }
        executor.runAll()
        val file = File(directory, "session-7.log")
        assertTrue(file.length() <= 8192 * 3 / 4)
        assertTrue(file.readText().contains("journal_session_truncated scope=ordinary"))
        assertTrue(file.readText().contains("messageTruncated=true"))

        journal.append(7, "event=capture_failed failure=NO_CANDIDATE", true)
        executor.runAll()

        assertTrue(file.length() <= 8192)
        assertTrue(file.readText().contains("event=capture_failed"))
        file.readLines().forEach { assertTrue((it + "\n").toByteArray(Charsets.UTF_8).size <= 4096) }
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(file.readBytes()))
    }

    @Test
    fun oneOversizedEssentialCannotExceedSessionLimit() = withDirectory { directory ->
        val executor = ControlledExecutor()
        val journal = journal(directory, executor, maxSessionBytes = 1024)
        journal.append(1, "event=terminal " + "x".repeat(10000), true)
        executor.runAll()
        val file = File(directory, "session-1.log")
        assertTrue(file.length() <= 1024)
        assertTrue(file.readText().contains("messageTruncated=true"))
        assertTrue(file.readText().contains("journal_session_truncated scope=all"))
        val bytes = file.length()
        journal.append(1, "event=more", true)
        executor.runAll()
        assertEquals(bytes, file.length())
    }

    @Test
    fun ordinaryLimitIsVisibleWhenEssentialEventsHaveAlreadyConsumedOrdinarySpace() = withDirectory { directory ->
        val executor = ControlledExecutor()
        val journal = journal(directory, executor, maxSessionBytes = 1024)
        journal.append(1, "event=important " + "x".repeat(670), true)
        executor.runAll()
        journal.append(1, "event=ordinary")
        executor.runAll()

        val file = File(directory, "session-1.log")
        assertTrue(file.length() <= 1024)
        assertTrue(file.readText().contains("journal_session_truncated scope=ordinary"))
    }

    @Test
    fun retentionUsesSessionIdAndLateOldEventsDoNotDisplaceNewSessions() = withDirectory { directory ->
        val executor = ControlledExecutor()
        val journal = journal(directory, executor)
        (1L..12L).forEach { journal.append(it, "event=started") }
        executor.runAll()
        assertEquals((3L..12L).toSet(), sessionIds(directory))

        journal.append(3, "event=late_retained", true)
        journal.append(1, "event=late_evicted", true)
        journal.append(13, "event=started")
        executor.runAll()
        assertEquals((4L..13L).toSet(), sessionIds(directory))
        assertFalse(File(directory, "session-1.log").exists())
        assertFalse(File(directory, "session-3.log").exists())
        assertTrue(File(directory, "runtime.log").readText().contains("journal_unknown_session_rejected count=1"))
    }

    @Test
    fun explicitLowIdAfterRestartIsRetainedByStartOrderAndLateOldIdIsRejected() = withDirectory { directory ->
        val executor = ControlledExecutor()
        val first = journal(directory, executor)
        (100L..109L).forEach { first.beginSession(it) }
        executor.runAll()
        val restarted = journal(directory, executor)
        restarted.beginSession(10)
        restarted.append(10, "event=new_scan")
        restarted.append(10, "event=terminal", true)
        restarted.append(100, "event=late_old", true)
        executor.runAll()

        assertEquals((101L..109L).toSet() + 10L, sessionIds(directory))
        val lowSession = File(directory, "session-10.log").readText()
        assertTrue(lowSession.contains("stage=journal_session_started"))
        assertTrue(lowSession.contains("event=new_scan"))
        assertTrue(lowSession.contains("event=terminal"))
        assertEquals((101L..109L).toList() + 10L, ledgerIds(directory))
        assertTrue(File(directory, "runtime.log").readText().contains("journal_unknown_session_rejected count=1"))

        journal(directory, executor).append(10, "event=after_second_restart", true)
        executor.runAll()
        assertTrue(File(directory, "session-10.log").readText().contains("after_second_restart"))
        assertEquals((101L..109L).toList() + 10L, ledgerIds(directory))
    }

    @Test
    fun fullEssentialQueueCanDropStartLineWithoutLosingLowIdIdentity() = withDirectory { directory ->
        val executor = ControlledExecutor()
        val first = journal(directory, executor, queueCapacity = 1)
        first.beginSession(100)
        executor.runAll()
        val restarted = journal(directory, executor, queueCapacity = 1)
        restarted.append(null, "event=runtime_filler", true)
        restarted.beginSession(1)
        executor.runAll()
        restarted.append(1, "event=terminal", true)
        executor.runAll()

        assertEquals(listOf(100L, 1L), ledgerIds(directory))
        assertTrue(File(directory, "session-1.log").readText().contains("event=terminal"))
        assertTrue(File(directory, "runtime.log").readText().contains("journal_queue_dropped ordinary=0 essential=1"))
        assertFalse(File(directory, "runtime.log").readText().contains("journal_unknown_session_rejected"))
    }

    @Test
    fun laterPendingBeginDuringSnapshotCommitIsNotCleared() = withDirectory { directory ->
        val executor = ControlledExecutor()
        val first = journal(directory, executor)
        first.beginSession(100)
        executor.runAll()
        lateinit var concurrent: CaptureDiagnosticJournal
        var inject = true
        val target = object : File(directory.absolutePath) {
            override fun listFiles(): Array<File>? {
                if (inject && File(directory, "sessions.index").exists() && 1L in ledgerIds(directory)) {
                    inject = false
                    val producer = Thread {
                        concurrent.append(null, "event=concurrent_filler", true)
                        concurrent.beginSession(2)
                    }
                    producer.start()
                    producer.join(5000)
                    assertFalse(producer.isAlive)
                }
                return super.listFiles()
            }
        }
        concurrent = CaptureDiagnosticJournal(target, executor, { 123L }, maxSessions = 2, queueCapacity = 1)
        concurrent.append(null, "event=initial_filler", true)
        concurrent.beginSession(1)
        executor.runAll()
        concurrent.append(2, "event=second_terminal", true)
        executor.runAll()

        assertFalse(inject)
        assertEquals(listOf(1L, 2L), ledgerIds(directory))
        assertTrue(File(directory, "session-2.log").readText().contains("event=second_terminal"))
        assertFalse(File(directory, "runtime.log").readText().contains("journal_unknown_session_rejected"))
    }

    @Test
    fun rejectedStartTaskKeepsIdentityForNextSuccessfulAppend() = withDirectory { directory ->
        val controlled = ControlledExecutor()
        journal(directory, controlled).beginSession(100)
        controlled.runAll()
        var reject = true
        val executor = Executor { task ->
            if (reject) throw RejectedExecutionException("rejected")
            controlled.execute(task)
        }
        val restarted = journal(directory, executor, queueCapacity = 1)
        restarted.beginSession(1)
        reject = false
        restarted.append(1, "event=recovered_terminal", true)
        controlled.runAll()

        assertEquals(listOf(100L, 1L), ledgerIds(directory))
        assertTrue(File(directory, "session-1.log").readText().contains("event=recovered_terminal"))
        assertTrue(File(directory, "runtime.log").readText().contains("journal_write_failures io=0 security=0 other=1"))
    }

    @Test
    fun duplicateBeginDoesNotRefreshCreationOrderAndLedgerStaysBounded() = withDirectory { directory ->
        val executor = ControlledExecutor()
        val journal = journal(directory, executor)
        (100L..109L).forEach { journal.beginSession(it) }
        executor.runAll()
        File(directory, "session-100.log").setLastModified(Long.MAX_VALUE / 2)
        val restarted = journal(directory, executor)
        restarted.beginSession(100)
        executor.runAll()
        assertEquals(2, File(directory, "session-100.log").readLines().size)
        assertEquals((100L..109L).toList(), ledgerIds(directory))
        restarted.beginSession(1)
        executor.runAll()
        assertFalse(File(directory, "session-100.log").exists())
        assertEquals((101L..109L).toList() + 1L, ledgerIds(directory))
        (2L..40L).forEach { restarted.beginSession(it) }
        executor.runAll()
        assertEquals((31L..40L).toList(), ledgerIds(directory))
        assertEquals(10, sessionIds(directory).size)
        assertTrue(File(directory, "sessions.index").length() < 512)
        assertFalse(File(directory, "sessions.index.tmp").exists())
    }

    @Test
    fun failedExplicitRegistrationIsRetriedByLaterAppendAfterLedgerRecovers() = withDirectory { directory ->
        val executor = ControlledExecutor()
        val journal = journal(directory, executor)
        journal.beginSession(100)
        executor.runAll()
        val blockedTemporary = File(directory, "sessions.index.tmp")
        blockedTemporary.mkdir()
        File(blockedTemporary, "blocker").writeText("blocker")
        journal.beginSession(1)
        executor.runAll()
        assertEquals(listOf(100L), ledgerIds(directory))

        blockedTemporary.deleteRecursively()
        journal.append(1, "event=recovered_scan")
        journal.append(1, "event=terminal", true)
        executor.runAll()

        assertEquals(listOf(100L, 1L), ledgerIds(directory))
        assertTrue(File(directory, "session-1.log").readText().contains("event=terminal"))
        assertTrue(File(directory, "runtime.log").readText().contains("journal_write_failures io=1"))
    }

    @Test
    fun persistedRegistrationSurvivesLogCreationFailureAndRestart() = withDirectory { directory ->
        val executor = ControlledExecutor()
        val first = journal(directory, executor)
        first.beginSession(100)
        executor.runAll()
        val blockedLog = File(directory, "session-1.log")
        blockedLog.mkdir()
        File(blockedLog, "blocker").writeText("blocker")
        // 已登记而暂时缺失的日志仍是有效会话，恢复时不能按高 ID 水位拒绝它。
        File(directory, "sessions.index").writeText("schema=1 highWatermark=100\n100\n1\n")
        val failing = journal(directory, executor)
        failing.append(1, "event=lost")
        executor.runAll()
        blockedLog.deleteRecursively()
        journal(directory, executor).append(1, "event=recovered_terminal", true)
        executor.runAll()
        assertTrue(File(directory, "session-1.log").readText().contains("event=recovered_terminal"))
        assertEquals(listOf(100L, 1L), ledgerIds(directory))
    }

    @Test
    fun oversizedLedgerFailsBoundedlyAndPendingBeginRecoversAfterRepair() = withDirectory { directory ->
        val executor = ControlledExecutor()
        File(directory, "sessions.index").writeText("x".repeat(10000))
        val failures = mutableListOf<CaptureJournalFailure>()
        val journal = CaptureDiagnosticJournal(directory, executor, { 123L }, { failures.add(it) })
        journal.beginSession(1)
        executor.runAll()
        assertEquals(listOf(CaptureJournalFailure.IO), failures)
        File(directory, "sessions.index").writeText("schema=1 highWatermark=100\n100\n")
        journal.append(1, "event=recovered_terminal", true)
        executor.runAll()
        assertTrue(File(directory, "session-1.log").readText().contains("event=recovered_terminal"))
        assertEquals(listOf(100L, 1L), ledgerIds(directory))
    }

    @Test
    fun deletionFailureDoesNotLoseCommittedLowIdOnRestart() = withDirectory { directory ->
        val executor = ControlledExecutor()
        val first = journal(directory, executor)
        (100L..109L).forEach { first.beginSession(it) }
        executor.runAll()
        var denyDelete = true
        val target = object : File(directory.absolutePath) {
            override fun listFiles(): Array<File>? = super.listFiles()?.map { file ->
                if (file.name != "session-100.log") file else object : File(file.absolutePath) {
                    override fun delete(): Boolean = if (denyDelete) false else super.delete()
                }
            }?.toTypedArray()
        }
        val failing = journal(target, executor)
        failing.beginSession(1)
        executor.runAll()
        assertEquals((101L..109L).toList() + 1L, ledgerIds(directory))
        assertTrue(File(directory, "session-100.log").exists())
        denyDelete = false
        journal(target, executor).append(1, "event=recovered_terminal", true)
        executor.runAll()
        assertEquals((101L..109L).toSet() + 1L, sessionIds(directory))
        assertTrue(File(directory, "session-1.log").readText().contains("event=recovered_terminal"))
    }

    @Test
    fun legacyFilesAreMigratedOnceThenLedgerOrderWins() = withDirectory { directory ->
        val executor = ControlledExecutor()
        (100L..111L).forEach { File(directory, "session-$it.log").writeText("legacy\n") }
        val journal = journal(directory, executor)
        journal.beginSession(1)
        executor.runAll()
        assertEquals((103L..111L).toList() + 1L, ledgerIds(directory))
        assertEquals((103L..111L).toSet() + 1L, sessionIds(directory))
        journal(directory, executor).append(1, "event=legacy_migrated_terminal", true)
        executor.runAll()
        assertEquals((103L..111L).toList() + 1L, ledgerIds(directory))
    }

    @Test
    fun restartAppendsExistingSessionAndKeepsRuntimeHistory() = withDirectory { directory ->
        val executor = ControlledExecutor()
        journal(directory, executor).apply {
            append(5, "event=before_restart")
            append(null, "event=runtime_before_restart")
        }
        executor.runAll()
        journal(directory, executor).apply {
            append(5, "event=after_restart", true)
            append(null, "event=runtime_after_restart")
        }
        executor.runAll()

        val session = File(directory, "session-5.log").readText()
        assertTrue(session.contains("before_restart"))
        assertTrue(session.contains("after_restart"))
        val runtime = File(directory, "runtime.log").readText()
        assertTrue(runtime.contains("runtime_before_restart"))
        assertTrue(runtime.contains("runtime_after_restart"))
        assertTrue(session.lines().filter { it.isNotEmpty() }.all { it.startsWith("schema=1 epochMs=123 ") })
    }

    @Test
    fun restartDoesNotResumeOrdinaryWritingAfterTruncation() = withDirectory { directory ->
        val executor = ControlledExecutor()
        journal(directory, executor, maxSessionBytes = 1024).append(1, "x".repeat(10000))
        executor.runAll()
        val file = File(directory, "session-1.log")
        val before = file.length()
        val restarted = journal(directory, executor, maxSessionBytes = 1024)
        restarted.append(1, "event=ignored")
        executor.runAll()
        assertEquals(before, file.length())
        restarted.append(1, "event=terminal", true)
        executor.runAll()
        assertTrue(file.readText().contains("event=terminal"))
        assertTrue(file.length() <= 1024)
    }

    @Test
    fun runtimeRollsWithinTwoSegmentsAndPreservesRestartedHistory() = withDirectory { directory ->
        val executor = ControlledExecutor()
        val journal = journal(directory, executor, maxRuntimeBytes = 512)
        repeat(12) { journal.append(null, "event=runtime id=$it " + "x".repeat(150)) }
        executor.runAll()
        assertTrue(File(directory, "runtime.log").length() <= 512)
        assertTrue(File(directory, "runtime-1.log").length() <= 512)
        val old = File(directory, "runtime-1.log").readText()
        val current = File(directory, "runtime.log").readText()
        assertTrue(current.contains("id=11"))
        journal(directory, executor, maxRuntimeBytes = 512).append(null, "event=restarted")
        executor.runAll()
        val retained = File(directory, "runtime-1.log").readText() + File(directory, "runtime.log").readText()
        assertTrue(retained.contains("event=restarted"))
        assertTrue(retained.contains("id=11"))
        assertTrue(old.isNotEmpty())
        assertEquals(2, directory.listFiles()!!.size)
    }

    @Test
    fun diskFailureDoesNotThrowToProducerAndRecoveryReportsLostWrites() = withDirectory { directory ->
        val executor = ControlledExecutor()
        val target = File(directory, "blocked")
        target.writeText("file_instead_of_directory")
        val failures = mutableListOf<CaptureJournalFailure>()
        val journal = CaptureDiagnosticJournal(target, executor, { 123L }, { failures.add(it) })
        journal.append(1, "event=lost")
        journal.append(1, "event=also_lost", true)
        assertTrue(failures.isEmpty())
        executor.runAll()
        assertEquals(listOf(CaptureJournalFailure.IO), failures)

        assertTrue(target.delete())
        journal.append(1, "event=recovered", true)
        executor.runAll()
        assertTrue(File(target, "session-1.log").readText().contains("event=recovered"))
        assertTrue(File(target, "runtime.log").readText().contains("journal_write_failures io=2 security=0 other=0"))
        target.deleteRecursively()
        target.writeText("blocked_again")
        journal.append(1, "event=lost_again")
        executor.runAll()
        assertEquals(listOf(CaptureJournalFailure.IO, CaptureJournalFailure.IO), failures)
    }

    @Test
    fun categorizesSecurityAndUnexpectedFailureWithoutRecursiveCallback() = withDirectory { directory ->
        val executor = ControlledExecutor()
        var cause: Exception? = SecurityException("denied")
        val target = object : File(directory.absolutePath) {
            override fun isDirectory(): Boolean {
                cause?.let { throw it }
                return super.isDirectory()
            }
        }
        val failures = mutableListOf<CaptureJournalFailure>()
        val journal = CaptureDiagnosticJournal(target, executor, { 123L }, {
            failures.add(it)
            throw IllegalStateException("callback_failure")
        })
        journal.append(null, "event=denied")
        executor.runAll()
        cause = IllegalStateException("unexpected")
        journal.append(null, "event=unexpected")
        executor.runAll()
        cause = null
        journal.append(null, "event=recovered")
        executor.runAll()
        assertEquals(listOf(CaptureJournalFailure.SECURITY), failures)
        assertTrue(File(directory, "runtime.log").readText().contains("io=0 security=1 other=1"))
    }

    @Test
    fun rejectedExecutorDoesNotThrowAndNextAppendRetries() = withDirectory { directory ->
        val controlled = ControlledExecutor()
        var reject = true
        val executor = Executor { task ->
            if (reject) throw RejectedExecutionException("rejected")
            controlled.execute(task)
        }
        val journal = journal(directory, executor)
        journal.append(1, "event=lost")
        assertFalse(File(directory, "session-1.log").exists())
        reject = false
        journal.append(1, "event=retried", true)
        assertEquals(1, controlled.tasks.size)
        controlled.runAll()

        val runtime = File(directory, "runtime.log").readText()
        assertTrue(runtime.contains("journal_queue_dropped ordinary=1 essential=0"))
        assertTrue(runtime.contains("journal_write_failures io=0 security=0 other=1"))
        val session = File(directory, "session-1.log").readText()
        assertTrue(session.contains("event=retried"))
        assertFalse(session.contains("journal_queue_dropped"))
        assertFalse(session.contains("journal_write_failures"))
    }

    private fun journal(
        directory: File,
        executor: Executor,
        maxSessionBytes: Int = 1024 * 1024,
        maxRuntimeBytes: Int = 128 * 1024,
        queueCapacity: Int = 256,
    ) = CaptureDiagnosticJournal(directory, executor, { 123L },
        maxSessionBytes = maxSessionBytes, maxRuntimeBytes = maxRuntimeBytes, queueCapacity = queueCapacity)

    private fun sessionIds(directory: File): Set<Long> = directory.listFiles()!!
        .mapNotNull { Regex("session-([0-9]+)\\.log").matchEntire(it.name)?.groupValues?.get(1)?.toLong() }
        .toSet()

    private fun ledgerIds(directory: File): List<Long> = File(directory, "sessions.index").readLines()
        .drop(1).map { it.toLong() }

    private fun withDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("capture-journal-test").toFile()
        try { block(directory) } finally { directory.deleteRecursively() }
    }

    private class ControlledExecutor : Executor {
        val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.addLast(command) }
        fun runAll() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }
}
