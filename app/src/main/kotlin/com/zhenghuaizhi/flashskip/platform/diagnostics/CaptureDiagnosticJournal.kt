package com.zhenghuaizhi.flashskip.platform.diagnostics

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.ArrayDeque
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** 消息须由调用方过滤为安全元数据；这里仅接收字符串，不读取或格式化业务对象。 */
internal interface CaptureDiagnosticSink {
    fun append(sessionId: Long?, message: String, essential: Boolean = false)
    /** 显式登记新会话；保留顺序不依赖可能回拨的会话 ID。 */
    fun beginSession(sessionId: Long) = Unit
}

internal enum class CaptureJournalFailure { IO, SECURITY, OTHER }

/**
 * 文件操作均交给单写入者执行器；注入的执行器也必须异步执行任务。
 * essential 会在后台同步文件描述符，但进程退出前尚未出队的尾部事件仍可能丢失。
 */
internal class CaptureDiagnosticJournal(
    private val directory: File,
    private val executor: Executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "capture-diagnostic-journal").apply { isDaemon = true }
    },
    private val wallTimeMillis: () -> Long = System::currentTimeMillis,
    private val onFailure: (CaptureJournalFailure) -> Unit = {},
    private val maxSessionBytes: Int = 1024 * 1024,
    private val maxRuntimeBytes: Int = 128 * 1024,
    private val maxSessions: Int = 10,
    private val queueCapacity: Int = 256,
) : CaptureDiagnosticSink {
    private val lock = Any()
    private val queue = ArrayDeque<Event>()
    private var draining = false
    private var droppedOrdinary = 0L
    private var droppedEssential = 0L
    private var rejectedUnknownSessions = 0L
    private val failures = LongArray(CaptureJournalFailure.entries.size)
    private var failurePeriodReported = false
    private val sessionIds = linkedSetOf<Long>()
    private val pendingStarts = linkedMapOf<Long, Any>()
    private var observedStartHighWatermark = -1L
    private var highWatermark = -1L
    private val inspectedSessions = mutableSetOf<Long>()
    private val ordinaryBlocked = mutableSetOf<Long>()
    private val fullyBlocked = mutableSetOf<Long>()
    private var initialized = false

    init {
        require(maxSessionBytes >= 512)
        require(maxRuntimeBytes >= 512)
        require(maxSessions > 0)
        require(queueCapacity > 0)
    }

    override fun append(sessionId: Long?, message: String, essential: Boolean) {
        if (sessionId != null && sessionId < 0) return
        enqueue(Event(
            sessionId, message.take(MAX_RECORD_BYTES), essential,
            wallTimeMillis(), message.length > MAX_RECORD_BYTES,
        ))
    }

    override fun beginSession(sessionId: Long) {
        if (sessionId < 0) return
        synchronized(lock) {
            // 身份登记独立于可丢的开始日志；只保留最后一批有界身份，并用令牌确认快照。
            pendingStarts[sessionId] = Any()
            observedStartHighWatermark = maxOf(observedStartHighWatermark, sessionId)
            if (pendingStarts.size > maxSessions) {
                pendingStarts.remove(pendingStarts.keys.first())
                droppedEssential = increment(droppedEssential)
            }
        }
        enqueue(Event(sessionId, "stage=journal_session_started", true, wallTimeMillis(), startsSession = true))
    }

    private fun enqueue(event: Event) {
        val schedule = synchronized(lock) {
            if (queue.size == queueCapacity) {
                val ordinary = if (event.essential) queue.firstOrNull { !it.essential } else null
                if (ordinary != null) {
                    queue.remove(ordinary)
                    droppedOrdinary = increment(droppedOrdinary)
                } else {
                    if (event.essential) droppedEssential = increment(droppedEssential)
                    else droppedOrdinary = increment(droppedOrdinary)
                    return
                }
            }
            queue.addLast(event)
            if (draining) false else {
                draining = true
                true
            }
        }
        if (schedule) {
            try {
                executor.execute(::drain)
            } catch (failure: Exception) {
                synchronized(lock) {
                    queue.forEach {
                        if (it.essential) droppedEssential = increment(droppedEssential)
                        else droppedOrdinary = increment(droppedOrdinary)
                    }
                    queue.clear()
                    draining = false
                }
                reportFailure(failure)
            }
        }
    }

    private fun drain() {
        while (true) {
            val event = synchronized(lock) {
                if (queue.isEmpty()) {
                    draining = false
                    return
                }
                queue.removeFirst()
            }
            try {
                initialize()
                registerPendingStarts()
                recordRecovery(event.epochMs)
                if (event.sessionId == null) writeRuntime(line(event), event.essential)
                else writeSession(event)
                recordRecovery(event.epochMs)
                synchronized(lock) { failurePeriodReported = false }
            } catch (failure: Exception) {
                // 失败事件不重入队；只保留定长计数，避免磁盘故障导致内存增长或递归日志。
                initialized = false
                reportFailure(failure)
            }
        }
    }

    private fun initialize() {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("journal_directory")
        if (initialized) return
        val files = directory.listFiles() ?: throw IOException("journal_inventory")
        sessionIds.clear()
        inspectedSessions.clear()
        ordinaryBlocked.clear()
        fullyBlocked.clear()
        val existingIds = files.mapNotNull { file ->
            SESSION_FILE.matchEntire(file.name)?.groupValues?.get(1)?.toLongOrNull()?.let {
                if (file.isFile) it else null
            }
        }
        val ledger = File(directory, LEDGER_FILE)
        if (ledger.exists()) {
            // 自有索引只含定长数值；先限制字节数，避免损坏文件被整份读入内存。
            if (ledger.length() > maxOf(maxSessions, 10).toLong() * 21 + 96) {
                throw IOException("journal_ledger_size")
            }
            val records = ledger.readLines()
            val watermark = LEDGER_HEADER.matchEntire(records.firstOrNull().orEmpty())
                ?.groupValues?.get(1)?.toLongOrNull() ?: throw IOException("journal_ledger_header")
            val ids = records.drop(1).map { record ->
                record.toLongOrNull()?.takeIf { it >= 0 } ?: throw IOException("journal_ledger_session")
            }
            if (ids.distinct().size != ids.size || watermark < -1 || ids.any { it > watermark }) {
                throw IOException("journal_ledger_invalid")
            }
            highWatermark = watermark
            sessionIds.addAll(ids.takeLast(maxSessions))
            if (ids.size > maxSessions) persistLedger(sessionIds, highWatermark)
        } else {
            // 旧版本没有开始顺序；只在首次迁移时按旧 ID 顺序建立登记，此后以登记为准。
            highWatermark = existingIds.maxOrNull() ?: -1L
            sessionIds.addAll(existingIds.sorted().takeLast(maxSessions))
            if (sessionIds.isNotEmpty()) persistLedger(sessionIds, highWatermark)
        }
        pruneSessionFiles()
        initialized = true
    }

    private fun registerPendingStarts() {
        val (snapshot, observedWatermark) = synchronized(lock) {
            LinkedHashMap(pendingStarts) to observedStartHighWatermark
        }
        if (snapshot.isEmpty()) return
        val next = LinkedHashSet(sessionIds)
        snapshot.keys.forEach { next.add(it) }
        while (next.size > maxSessions) next.remove(next.first())
        val watermark = maxOf(highWatermark, observedWatermark)
        if (next.toList() == sessionIds.toList() && watermark == highWatermark) {
            acknowledgeStarts(snapshot)
            return
        }
        // 原子登记先于建档和淘汰；后续写入失败也不会把已接纳的新会话误判成迟到旧事件。
        persistLedger(next, watermark)
        sessionIds.clear()
        sessionIds.addAll(next)
        highWatermark = watermark
        pruneSessionFiles()
        acknowledgeStarts(snapshot)
    }

    private fun acknowledgeStarts(snapshot: Map<Long, Any>) {
        synchronized(lock) {
            snapshot.forEach { (id, token) ->
                // 写索引期间发生的新 begin（包括同 ID）必须留给下一次快照。
                if (pendingStarts[id] === token) pendingStarts.remove(id)
            }
        }
    }

    private fun registerImplicitSession(id: Long) {
        val next = LinkedHashSet(sessionIds)
        next.add(id)
        while (next.size > maxSessions) next.remove(next.first())
        persistLedger(next, maxOf(highWatermark, id))
        sessionIds.clear()
        sessionIds.addAll(next)
        highWatermark = maxOf(highWatermark, id)
        pruneSessionFiles()
    }

    private fun persistLedger(ids: Collection<Long>, watermark: Long) {
        val temporary = File(directory, "$LEDGER_FILE.tmp")
        val contents = "schema=1 highWatermark=$watermark\n" + ids.joinToString("") { "$it\n" }
        FileOutputStream(temporary, false).use { output ->
            output.write(contents.toByteArray(Charsets.UTF_8))
            output.flush()
            output.fd.sync()
        }
        Files.move(temporary.toPath(), File(directory, LEDGER_FILE).toPath(),
            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun recordRecovery(epochMs: Long) {
        val dropped = synchronized(lock) { droppedOrdinary to droppedEssential }
        if (dropped.first != 0L || dropped.second != 0L) {
            writeRuntime(line(Event(null,
                "event=journal_queue_dropped ordinary=${dropped.first} essential=${dropped.second}",
                true, epochMs,
            )), true)
            synchronized(lock) {
                droppedOrdinary -= dropped.first
                droppedEssential -= dropped.second
            }
        }
        val snapshot = synchronized(lock) { failures.copyOf() }
        if (snapshot.any { it != 0L }) {
            writeRuntime(line(Event(null,
                "event=journal_write_failures io=${snapshot[0]} security=${snapshot[1]} other=${snapshot[2]}",
                true, epochMs,
            )), true)
            synchronized(lock) { snapshot.indices.forEach { failures[it] -= snapshot[it] } }
        }
        if (rejectedUnknownSessions != 0L) {
            writeRuntime(line(Event(null,
                "event=journal_unknown_session_rejected count=$rejectedUnknownSessions", true, epochMs,
            )), true)
            rejectedUnknownSessions = 0L
        }
    }

    private fun writeSession(event: Event) {
        val id = checkNotNull(event.sessionId)
        if (id !in sessionIds) {
            if (event.startsSession || id <= highWatermark) {
                rejectedUnknownSessions = increment(rejectedUnknownSessions)
                return
            }
            registerImplicitSession(id)
        }
        val file = File(directory, "session-$id.log")
        if (id !in inspectedSessions && file.exists()) {
            file.bufferedReader().use { reader ->
                reader.forEachLine { record ->
                    if (record.contains("event=journal_session_truncated scope=ordinary")) ordinaryBlocked.add(id)
                    if (record.contains("event=journal_session_truncated scope=all")) fullyBlocked.add(id)
                }
            }
            inspectedSessions.add(id)
        }
        if (id in fullyBlocked || (!event.essential && id in ordinaryBlocked)) return
        val budget = if (event.essential) maxSessionBytes else maxSessionBytes * 3L / 4
        val marker = line(Event(id,
            "event=journal_session_truncated scope=${if (event.essential) "all" else "ordinary"}",
            true, event.epochMs,
        ))
        val available = (budget.toLong() - file.length()).coerceAtLeast(0)
        val record = line(event)
        if (record.size <= available - marker.size) {
            appendFile(file, record, event.essential)
        } else {
            val partial = line(event.copy(messageTruncated = true),
                (available - marker.size).coerceIn(0, MAX_RECORD_BYTES.toLong()).toInt())
            if (partial.isNotEmpty()) appendFile(file, partial, event.essential)
            if (marker.size <= budget.toLong() - file.length()) {
                appendFile(file, marker, true)
            } else if (!event.essential) {
                // essential 可能先占用了普通预算；仍应留下限额说明，并为总预算说明留位。
                val fullMarker = line(Event(id,
                    "event=journal_session_truncated scope=all", true, event.epochMs,
                ))
                val remaining = maxSessionBytes - file.length()
                if (marker.size + fullMarker.size <= remaining) appendFile(file, marker, true)
                else if (fullMarker.size <= remaining) {
                    appendFile(file, fullMarker, true)
                    fullyBlocked.add(id)
                }
            }
            if (event.essential) fullyBlocked.add(id) else ordinaryBlocked.add(id)
        }
        if (file.exists()) inspectedSessions.add(id)
    }

    private fun pruneSessionFiles() {
        val files = directory.listFiles() ?: throw IOException("journal_inventory")
        files.forEach { file ->
            val id = SESSION_FILE.matchEntire(file.name)?.groupValues?.get(1)?.toLongOrNull()
            if (id != null && id !in sessionIds && !file.delete()) throw IOException("journal_retention")
        }
        inspectedSessions.retainAll(sessionIds)
        ordinaryBlocked.retainAll(sessionIds)
        fullyBlocked.retainAll(sessionIds)
    }

    private fun writeRuntime(record: ByteArray, essential: Boolean) {
        val current = File(directory, "runtime.log")
        if (current.length() + record.size > maxRuntimeBytes) {
            val previous = File(directory, "runtime-1.log")
            if (previous.exists() && !previous.delete()) throw IOException("journal_rotation_delete")
            if (current.exists() && !current.renameTo(previous)) throw IOException("journal_rotation_move")
        }
        appendFile(current, record, essential)
    }

    private fun appendFile(file: File, record: ByteArray, essential: Boolean) {
        FileOutputStream(file, true).use { output ->
            output.write(record)
            output.flush()
            if (essential) output.fd.sync()
        }
    }

    private fun line(event: Event, maxBytes: Int = minOf(MAX_RECORD_BYTES, maxRuntimeBytes)): ByteArray {
        val prefix = "schema=1 epochMs=${event.epochMs} session=${event.sessionId ?: "runtime"} essential=${event.essential} "
        val message = event.message.replace('\r', ' ').replace('\n', ' ')
        val prefixBytes = prefix.toByteArray(Charsets.UTF_8).size
        val fullBytes = message.toByteArray(Charsets.UTF_8).size
        val truncated = event.messageTruncated || prefixBytes + fullBytes + 1 > maxBytes
        val suffix = if (truncated) " messageTruncated=true\n" else "\n"
        val available = maxBytes - prefixBytes - suffix.toByteArray(Charsets.UTF_8).size
        if (available < 0) return byteArrayOf()
        var end = 0
        var bytes = 0
        while (end < message.length) {
            val codePoint = message.codePointAt(end)
            val size = when {
                codePoint <= 0x7f -> 1
                codePoint <= 0x7ff -> 2
                codePoint <= 0xffff -> 3
                else -> 4
            }
            if (bytes + size > available) break
            bytes += size
            end += Character.charCount(codePoint)
        }
        return (prefix + message.substring(0, end) + suffix).toByteArray(Charsets.UTF_8)
    }

    private fun reportFailure(failure: Exception) {
        val category = when (failure) {
            is SecurityException -> CaptureJournalFailure.SECURITY
            is IOException -> CaptureJournalFailure.IO
            else -> CaptureJournalFailure.OTHER
        }
        val notify = synchronized(lock) {
            failures[category.ordinal] = increment(failures[category.ordinal])
            if (failurePeriodReported) false else {
                failurePeriodReported = true
                true
            }
        }
        if (!notify) return
        try {
            onFailure(category)
        } catch (_: Exception) {
            // 回调自身的失败不能中止写入者，也不能再次触发回调。
        }
    }

    private data class Event(
        val sessionId: Long?,
        val message: String,
        val essential: Boolean,
        val epochMs: Long,
        val messageTruncated: Boolean = false,
        val startsSession: Boolean = false,
    )

    private companion object {
        const val MAX_RECORD_BYTES = 4096
        val SESSION_FILE = Regex("session-([0-9]+)\\.log")
        const val LEDGER_FILE = "sessions.index"
        val LEDGER_HEADER = Regex("schema=1 highWatermark=(-?[0-9]+)")
        fun increment(value: Long): Long = if (value == Long.MAX_VALUE) value else value + 1
    }
}
