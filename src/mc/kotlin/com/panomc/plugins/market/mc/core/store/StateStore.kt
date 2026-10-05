package com.panomc.plugins.market.mc.core.store

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.panomc.plugins.market.mc.core.platform.McClock
import com.panomc.plugins.market.mc.core.platform.McLog
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/** Append-only sink of the journal. `append` returns only after the bytes are durable (fsync). */
interface AppendSink : AutoCloseable {
    fun size(): Long
    fun append(bytes: ByteArray)
    fun truncate(size: Long)
    override fun close()
}

class FileAppendSink(path: Path) : AppendSink {
    private val channel: FileChannel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
    private var size: Long = channel.size()

    override fun size(): Long = size

    override fun append(bytes: ByteArray) {
        channel.position(size)
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) channel.write(buffer)
        channel.force(true)
        size += bytes.size
    }

    override fun truncate(size: Long) {
        channel.truncate(size)
        channel.position(size)
        channel.force(true)
        this.size = size
    }

    override fun close() {
        channel.close()
    }
}

data class StoreOptions(
    /** The journal is compacted into the snapshot when it grows past this size (19 section 5: 1 MB). */
    val maxJournalBytes: Long = 1_048_576L,
    val sinkFactory: (Path) -> AppendSink = { FileAppendSink(it) }
)

/** What is known about a lost store: the component is in recovery mode until an operator confirms (19 section 6.4). */
data class RecoveryInfo(
    val since: Long,
    /** Directory name the unreadable store was moved to, e.g. `state.corrupt-1790000000000`. */
    val quarantine: String?,
    val reason: String?,
    /** Keys without an acknowledged result that could still be read from the lost store (an upper bound of what may run twice). */
    val salvagedUnackedKeys: Int
)

/**
 * The local state of the component (19 section 5): `state/journal.log` (append-only JSON lines, fsync after each batch)
 * and `state/snapshot.json` (compaction, temp file + atomic rename).
 *
 * Every change is a [JournalOp] applied by one function, to memory and (in the same call) to the journal, so a replay of
 * snapshot + journal always yields the state that was in memory. A batch is validated against memory first, written
 * second, and rolled back in memory when the write fails; nothing is ever acted upon (dispatched, reported, acked)
 * before the write returned.
 *
 * Not thread-safe: confined to the engine thread (see `McScheduler`).
 */
class StateStore private constructor(
    private val baseDir: Path,
    private val clock: McClock,
    private val log: McLog,
    private val options: StoreOptions
) : AutoCloseable {
    private val stateDir: Path = baseDir.resolve(STATE_DIR)
    private val journalPath: Path = stateDir.resolve(JOURNAL)
    private val snapshotPath: Path = stateDir.resolve(SNAPSHOT)
    private val markerPath: Path = baseDir.resolve(RECOVERY_MARKER)

    private val records = LinkedHashMap<String, DeliveryRecord>()
    private val waitingKeys = LinkedHashSet<String>()
    private val runningKeys = LinkedHashSet<String>()
    private val reportKeys = LinkedHashSet<String>()

    private var sink: AppendSink? = null
    private var seq: Long = 0
    private var snapshotSeq: Long = 0
    private var broken = false

    /** `false` after a failed write until a write succeeds again (a rolled back batch leaves nothing half written). */
    var healthy: Boolean = true
        private set

    var recovery: RecoveryInfo? = null
        private set

    // ---- reads --------------------------------------------------------------------------------------------------

    fun get(key: String): DeliveryRecord? = records[key]

    val size: Int get() = records.size

    /** Every record, oldest first. */
    fun all(): List<DeliveryRecord> = records.values.sortedWith(ORDER)

    /** Waiting records (not started), oldest first. */
    fun waiting(): List<DeliveryRecord> = waitingKeys.mapNotNull { records[it] }.sortedWith(ORDER)

    /** Waiting records of one player (case-insensitive), ascending by delivery id. */
    fun waitingFor(username: String): List<DeliveryRecord> {
        val k = DeliveryRecord.playerKey(username)
        return waitingKeys.mapNotNull { records[it] }.filter { it.playerKey == k }.sortedWith(compareBy({ it.id }, { it.key }))
    }

    fun waitingCount(): Int = waitingKeys.size
    fun runningCount(): Int = runningKeys.size

    /** Records the next sync must report (finished and not acknowledged, or waiting and not acknowledged as queued). */
    fun reportable(): List<DeliveryRecord> = reportKeys.mapNotNull { records[it] }

    fun running(): List<DeliveryRecord> = runningKeys.mapNotNull { records[it] }

    /** Acknowledged finished records whose acknowledgement is at least [retentionMs] old. */
    fun purgeCandidates(now: Long, retentionMs: Long): List<String> =
        records.values.filter { it.state.finished && it.ackedAt != null && now - it.ackedAt >= retentionMs }.map { it.key }

    /** The whole state as one canonical document (tests compare it before and after a restart). */
    fun dump(): String {
        val arr = JsonArray()
        all().forEach { arr.add(RecordCodec.recordToJson(it)) }
        val o = JsonObject()
        o.add("records", arr)
        return RecordCodec.toText(o)
    }

    // ---- changes ------------------------------------------------------------------------------------------------

    /**
     * Applies [ops] in order as one durable batch. Throws [IllegalStateException] when an op does not fit the current
     * state (a bug of the caller; nothing is written) and [StoreWriteException] when the disk refused the batch
     * (memory is rolled back, nothing is written that could be read back half done).
     */
    fun commit(ops: List<JournalOp>) {
        if (ops.isEmpty()) return
        if (broken) throw StoreWriteException("the journal is broken after an unrecoverable write error; restart the server")
        val undo = LinkedHashMap<String, DeliveryRecord?>()
        try {
            ops.forEach { applyOp(it, undo, replay = false) }
        } catch (e: RuntimeException) {
            rollback(undo)
            throw e
        }
        val out = StringBuilder()
        var next = seq
        ops.forEach { op ->
            next += 1
            out.append(RecordCodec.opToLine(next, op)).append('\n')
        }
        val bytes = out.toString().toByteArray(StandardCharsets.UTF_8)
        val currentSink = try {
            sink ?: reopenSink()
        } catch (e: IOException) {
            rollback(undo)
            healthy = false
            throw StoreWriteException("journal cannot be opened: ${e.message}", e)
        }
        val before = currentSink.size()
        try {
            currentSink.append(bytes)
        } catch (e: IOException) {
            rollback(undo)
            healthy = false
            try {
                currentSink.truncate(before)
            } catch (t: IOException) {
                broken = true
                log.error("The Market journal write failed and the partial entry could not be removed; deliveries stay paused until the server restarts.", t)
            }
            throw StoreWriteException("journal write failed: ${e.message}", e)
        }
        seq = next
        healthy = true
        if (currentSink.size() > options.maxJournalBytes) {
            try {
                compact()
            } catch (e: Exception) {
                log.warn("Compacting the Market journal failed, it stays as it is: ${e.message}")
            }
        }
    }

    fun commit(op: JournalOp) = commit(listOf(op))

    /** Writes a probe entry: whether the disk accepts writes again after a failure. */
    fun probe(): Boolean = try {
        commit(JournalOp.Probe)
        true
    } catch (e: StoreWriteException) {
        false
    }

    /** Operator confirmed `/panomarket recover confirm`: leaves recovery mode. Returns whether it was on. */
    fun confirmRecovery(): Boolean {
        val was = recovery != null || Files.exists(markerPath)
        Files.deleteIfExists(markerPath)
        recovery = null
        return was
    }

    private fun rollback(undo: Map<String, DeliveryRecord?>) {
        undo.forEach { (key, previous) ->
            if (previous == null) records.remove(key) else records[key] = previous
            reindex(key)
        }
    }

    private fun applyOp(op: JournalOp, undo: MutableMap<String, DeliveryRecord?>?, replay: Boolean) {
        fun fail(message: String): Nothing = if (replay) throw StoreCorruptException(message) else throw IllegalStateException(message)
        fun touch(key: String) {
            if (undo != null && !undo.containsKey(key)) undo[key] = records[key]
        }
        fun existing(key: String): DeliveryRecord = records[key] ?: fail("unknown key '$key' in $op")
        fun put(r: DeliveryRecord) {
            touch(r.key)
            records[r.key] = r
            reindex(r.key)
        }

        when (op) {
            is JournalOp.Received -> {
                val r = op.record
                if (records.containsKey(r.key)) fail("key '${r.key}' received twice")
                if (r.state != RecordState.QUEUED || r.startedAt != null || r.result != null || r.finishedAt != null || r.ackedAt != null) {
                    fail("a received record must be a fresh QUEUED one: '${r.key}'")
                }
                put(r)
            }
            is JournalOp.Started -> {
                val r = existing(op.key)
                if (!r.waiting) fail("'${op.key}' cannot start in state ${r.state} (started=${r.startedAt})")
                put(r.copy(startedAt = op.at))
            }
            is JournalOp.Finished -> {
                val r = existing(op.key)
                if (r.state.finished) fail("'${op.key}' is already ${r.state}: a finished state never changes")
                if (!op.state.finished) fail("'${op.key}' cannot finish as QUEUED")
                put(
                    r.copy(
                        state = op.state, result = op.result, finishedAt = op.at,
                        commands = emptyList(), permission = null, issuer = null, queuedAcked = false
                    )
                )
            }
            is JournalOp.QueuedAcked -> {
                val r = existing(op.key)
                if (!r.waiting) fail("'${op.key}' is not waiting")
                put(r.copy(queuedAcked = true))
            }
            is JournalOp.Acked -> {
                val r = existing(op.key)
                if (!r.state.finished || r.ackedAt != null) fail("'${op.key}' cannot be acknowledged")
                put(r.copy(ackedAt = op.at))
            }
            is JournalOp.Unacked -> {
                val r = existing(op.key)
                val ok = (r.state.finished && r.ackedAt != null) || (r.waiting && r.queuedAcked)
                if (!ok) fail("'${op.key}' has no acknowledgement to take back")
                put(r.copy(ackedAt = null, queuedAcked = false))
            }
            is JournalOp.Purged -> {
                op.keys.forEach { key ->
                    val r = existing(key)
                    if (!r.state.finished || r.ackedAt == null) fail("'$key' is not an acknowledged finished record")
                }
                op.keys.forEach { key ->
                    touch(key)
                    records.remove(key)
                    reindex(key)
                }
            }
            JournalOp.Probe -> Unit
        }
    }

    private fun reindex(key: String) {
        val r = records[key]
        if (r == null) {
            waitingKeys.remove(key)
            runningKeys.remove(key)
            reportKeys.remove(key)
            return
        }
        if (r.waiting) waitingKeys.add(key) else waitingKeys.remove(key)
        if (r.running) runningKeys.add(key) else runningKeys.remove(key)
        val reportable = (r.state.finished && r.ackedAt == null) || (r.waiting && !r.queuedAcked)
        if (reportable) reportKeys.add(key) else reportKeys.remove(key)
    }

    // ---- snapshot / compaction ------------------------------------------------------------------------------------

    /**
     * Writes the snapshot (temp file, fsync, atomic rename) and starts an empty journal. A crash between the two steps
     * leaves the old journal in place; its entries are skipped on replay because their `seq` is not above the
     * snapshot's.
     */
    fun compact() {
        if (broken) return
        val arr = JsonArray()
        all().forEach { arr.add(RecordCodec.recordToJson(it)) }
        val doc = JsonObject()
        doc.addProperty("version", FORMAT_VERSION)
        doc.addProperty("seq", seq)
        doc.addProperty("createdAt", clock.now())
        doc.add("records", arr)

        val tmp = stateDir.resolve("$SNAPSHOT.tmp")
        writeDurably(tmp, RecordCodec.toText(doc).toByteArray(StandardCharsets.UTF_8))
        atomicMove(tmp, snapshotPath)
        snapshotSeq = seq
        fsyncDirectory(stateDir)

        val journalTmp = stateDir.resolve("$JOURNAL.tmp")
        writeDurably(journalTmp, ByteArray(0))
        sink?.close()
        sink = null
        atomicMove(journalTmp, journalPath)
        fsyncDirectory(stateDir)
        sink = options.sinkFactory(journalPath)
    }

    /** Clean shutdown: compacts the journal into the snapshot and closes the files. */
    override fun close() {
        try {
            if (!broken && healthy && sink != null) compact()
        } catch (e: Exception) {
            log.warn("Compacting the Market state on shutdown failed, the journal stays as it is: ${e.message}")
        } finally {
            try {
                sink?.close()
            } catch (_: IOException) {
            }
            sink = null
        }
    }

    private fun reopenSink(): AppendSink {
        val s = options.sinkFactory(journalPath)
        sink = s
        return s
    }

    // ---- opening --------------------------------------------------------------------------------------------------

    private fun load(): Boolean {
        Files.createDirectories(baseDir)
        if (!Files.exists(stateDir)) {
            Files.createDirectories(stateDir)
            reopenSink()
            return false
        }
        loadSnapshot()
        replayJournal()
        return true
    }

    private fun loadSnapshot() {
        if (!Files.exists(snapshotPath)) return
        val doc = try {
            RecordCodec.parseObject(String(Files.readAllBytes(snapshotPath), StandardCharsets.UTF_8))
        } catch (e: IOException) {
            throw StoreCorruptException("snapshot unreadable: ${e.message}", e)
        }
        val version = doc.get("version")
        if (version == null || !version.isJsonPrimitive || version.asInt != FORMAT_VERSION) throw StoreCorruptException("unsupported snapshot version")
        val seqEl = doc.get("seq")
        if (seqEl == null || !seqEl.isJsonPrimitive) throw StoreCorruptException("snapshot without seq")
        snapshotSeq = try {
            seqEl.asLong
        } catch (e: NumberFormatException) {
            throw StoreCorruptException("snapshot seq is not a number", e)
        }
        seq = snapshotSeq
        val recs = doc.get("records")
        if (recs == null || !recs.isJsonArray) throw StoreCorruptException("snapshot without records")
        for (e in recs.asJsonArray) {
            if (!e.isJsonObject) throw StoreCorruptException("snapshot record is not an object")
            val r = RecordCodec.recordFromJson(e.asJsonObject)
            if (records.containsKey(r.key)) throw StoreCorruptException("duplicate key '${r.key}' in the snapshot")
            records[r.key] = r
            reindex(r.key)
        }
    }

    private class Line(val text: String, val end: Long)

    private fun replayJournal() {
        if (!Files.exists(journalPath)) {
            reopenSink()
            return
        }
        val bytes = Files.readAllBytes(journalPath)
        val lines = ArrayList<Line>()
        var start = 0
        for (i in bytes.indices) {
            if (bytes[i] == '\n'.code.toByte()) {
                lines.add(Line(String(bytes, start, i - start, StandardCharsets.UTF_8), (i + 1).toLong()))
                start = i + 1
            }
        }
        if (start < bytes.size) lines.add(Line(String(bytes, start, bytes.size - start, StandardCharsets.UTF_8), bytes.size.toLong()))

        var lastNonBlank = -1
        lines.forEachIndexed { i, l -> if (l.text.isNotBlank()) lastNonBlank = i }

        var good = 0L
        var expected = snapshotSeq + 1
        var tornAt = -1L
        for ((i, line) in lines.withIndex()) {
            if (line.text.isBlank()) {
                good = line.end
                continue
            }
            val obj = try {
                RecordCodec.parseObject(line.text)
            } catch (e: StoreCorruptException) {
                if (i == lastNonBlank) {
                    tornAt = good
                    break
                }
                throw StoreCorruptException("journal line ${i + 1} is unreadable: ${e.message}", e)
            }
            val lineSeq = try {
                RecordCodec.seqOf(obj)
            } catch (e: StoreCorruptException) {
                if (i == lastNonBlank) {
                    tornAt = good
                    break
                }
                throw StoreCorruptException("journal line ${i + 1} has no sequence number", e)
            }
            if (lineSeq <= snapshotSeq) {
                good = line.end
                continue
            }
            if (lineSeq != expected) throw StoreCorruptException("journal sequence jumps from ${expected - 1} to $lineSeq at line ${i + 1}")
            val op = try {
                RecordCodec.opFromJson(obj)
            } catch (e: StoreCorruptException) {
                if (i == lastNonBlank) {
                    tornAt = good
                    break
                }
                throw StoreCorruptException("journal line ${i + 1}: ${e.message}", e)
            }
            applyOp(op, null, replay = true)
            seq = lineSeq
            expected = lineSeq + 1
            good = line.end
        }

        val s = reopenSink()
        if (tornAt >= 0) {
            log.warn("The Market journal ended in a partly written entry (a crash during a write); it was cut off.")
            s.truncate(tornAt)
        } else if (good > 0 && bytes.isNotEmpty() && bytes[bytes.size - 1] != '\n'.code.toByte()) {
            s.append("\n".toByteArray(StandardCharsets.UTF_8))
        }
    }

    /** Interrupted executions (started, never finished) become FAILED / INTERRUPTED (19 section 6.3). */
    private fun settleInterrupted(code: String, message: String) {
        val interrupted = running()
        if (interrupted.isEmpty()) return
        val now = clock.now()
        commit(interrupted.map { JournalOp.Finished(it.key, RecordState.FAILED, RecordResult(code, message, null, emptyList()), now) })
        log.warn("${interrupted.size} Market deliver${if (interrupted.size == 1) "y was" else "ies were"} interrupted by a restart while running and will not be run again automatically.")
    }

    companion object {
        const val STATE_DIR = "state"
        const val JOURNAL = "journal.log"
        const val SNAPSHOT = "snapshot.json"
        const val RECOVERY_MARKER = "recovery.pending"
        const val FORMAT_VERSION = 1
        const val INTERRUPTED_MESSAGE = "The server stopped while this delivery was running; it was not run again."

        private val ORDER: Comparator<DeliveryRecord> = compareBy<DeliveryRecord>({ it.receivedAt }, { it.id }, { it.key })

        /**
         * Opens (or creates) the store under [baseDir] (`plugins/PanoMarket`). A store that cannot be read back is
         * moved to `state.corrupt-<ts>`, a fresh one is started and recovery mode is switched on (persisted as
         * `recovery.pending` until the operator confirms).
         */
        fun open(baseDir: Path, clock: McClock, log: McLog, options: StoreOptions = StoreOptions()): StateStore {
            var store = StateStore(baseDir, clock, log, options)
            try {
                store.load()
            } catch (e: StoreCorruptException) {
                store.closeQuietly()
                store = StateStore(baseDir, clock, log, options)
                store.quarantine(e)
                return store
            }
            store.recovery = store.readMarker()
            store.settleInterrupted("INTERRUPTED", INTERRUPTED_MESSAGE)
            return store
        }
    }

    private fun closeQuietly() {
        try {
            sink?.close()
        } catch (_: IOException) {
        }
        sink = null
    }

    private fun quarantine(cause: StoreCorruptException) {
        val now = clock.now()
        var name = "$STATE_DIR.corrupt-$now"
        var n = 1
        while (Files.exists(baseDir.resolve(name))) name = "$STATE_DIR.corrupt-$now-${n++}"
        val salvaged = salvageUnacked(stateDir)
        log.error("The Market state store is unreadable (${cause.message}). It was moved to '$name'. No delivery runs until an operator confirms with /panomarket recover.", cause)

        // The marker first: a crash between the marker and the rename starts the same recovery on the next start,
        // a crash after the rename finds the marker and a fresh state directory.
        val info = RecoveryInfo(now, name, cause.message, salvaged)
        writeMarker(info)
        if (Files.exists(stateDir)) atomicMove(stateDir, baseDir.resolve(name))
        Files.createDirectories(stateDir)
        reopenSink()
        recovery = info
    }

    private fun writeMarker(info: RecoveryInfo) {
        val o = JsonObject()
        o.addProperty("since", info.since)
        info.quarantine?.let { o.addProperty("quarantine", it) }
        info.reason?.let { o.addProperty("reason", it) }
        o.addProperty("salvagedUnackedKeys", info.salvagedUnackedKeys)
        writeDurably(markerPath, RecordCodec.toText(o).toByteArray(StandardCharsets.UTF_8))
    }

    private fun readMarker(): RecoveryInfo? {
        if (!Files.exists(markerPath)) return null
        return try {
            val o = RecordCodec.parseObject(String(Files.readAllBytes(markerPath), StandardCharsets.UTF_8))
            fun s(name: String) = o.get(name)?.takeIf { it.isJsonPrimitive }?.asString
            RecoveryInfo(
                s("since")?.toLongOrNull() ?: clock.now(), s("quarantine"), s("reason"), s("salvagedUnackedKeys")?.toIntOrNull() ?: 0
            )
        } catch (e: Exception) {
            RecoveryInfo(clock.now(), null, "recovery marker unreadable", 0)
        }
    }

    /** Best effort over a store that failed to load: keys with no acknowledged result. Never throws. */
    private fun salvageUnacked(dir: Path): Int {
        val open = HashSet<String>()
        fun record(o: JsonObject) {
            try {
                val r = RecordCodec.recordFromJson(o)
                if (r.ackedAt == null) open.add(r.key) else open.remove(r.key)
            } catch (_: Exception) {
            }
        }
        try {
            val snap = dir.resolve(SNAPSHOT)
            if (Files.exists(snap)) {
                val doc = RecordCodec.parseObject(String(Files.readAllBytes(snap), StandardCharsets.UTF_8))
                doc.get("records")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { if (it.isJsonObject) record(it.asJsonObject) }
            }
        } catch (_: Exception) {
        }
        try {
            val journal = dir.resolve(JOURNAL)
            if (Files.exists(journal)) {
                String(Files.readAllBytes(journal), StandardCharsets.UTF_8).split('\n').forEach { text ->
                    if (text.isBlank()) return@forEach
                    try {
                        val o = RecordCodec.parseObject(text)
                        val key = o.get("key")?.takeIf { it.isJsonPrimitive }?.asString
                        when (o.get("op")?.takeIf { it.isJsonPrimitive }?.asString) {
                            "received" -> o.get("rec")?.takeIf { it.isJsonObject }?.let { record(it.asJsonObject) }
                            "acked" -> if (key != null) open.remove(key)
                            "unacked" -> if (key != null) open.add(key)
                            "purged" -> o.get("keys")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { open.remove(it.asString) }
                        }
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (_: Exception) {
        }
        return open.size
    }

    // ---- files ----------------------------------------------------------------------------------------------------

    private fun writeDurably(path: Path, bytes: ByteArray) {
        FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { ch ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) ch.write(buffer)
            ch.force(true)
        }
    }

    private fun atomicMove(from: Path, to: Path) {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun fsyncDirectory(dir: Path) {
        try {
            FileChannel.open(dir, StandardOpenOption.READ).use { it.force(true) }
        } catch (_: Exception) {
            // not supported on every platform (Windows); the rename itself is atomic
        }
    }
}
