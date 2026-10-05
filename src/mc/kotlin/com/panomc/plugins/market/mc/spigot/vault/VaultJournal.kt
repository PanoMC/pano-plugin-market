package com.panomc.plugins.market.mc.spigot.vault

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.panomc.plugins.market.mc.core.platform.McLog
import com.panomc.plugins.market.mc.core.store.AppendSink
import com.panomc.plugins.market.mc.core.store.FileAppendSink
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/** What a Vault bridge operation is (19 section 10). The ledger side of each is a `MARKET_ECONOMY` call. */
enum class OpKind(
    /** `WITHDRAW` or `DEPOSIT`: the `MARKET_ECONOMY` operation of the original ledger request. */
    val ledgerOp: String
) {
    PROVIDER_WITHDRAW("WITHDRAW"),
    PROVIDER_DEPOSIT("DEPOSIT"),
    CONVERT_TO_SERVER("WITHDRAW"),
    CONVERT_TO_CREDITS("DEPOSIT");

    /** The player's server money has already been taken when the ledger request is sent (server -> credits). */
    val serverMoneyTaken: Boolean get() = this == CONVERT_TO_CREDITS
}

enum class OpPhase {
    /** The ledger request was (or is being) sent and its outcome is not known yet. */
    LEDGER_PENDING,

    /** The ledger applied the original request, but whoever asked was told it failed: the opposite request (`<id>:undo`) is owed. */
    UNDO_PENDING,

    /** The ledger refused a server -> credits deposit: the player's server money is owed back. */
    REFUND_PENDING,

    /**
     * The server economy is being paid (credits -> money payout) right now: written durably immediately before
     * `deposit`. An entry found in this phase after a crash has an UNKNOWN outcome (paid or not) and is never
     * undone or retried automatically, only reported and closed as `INTERRUPTED`.
     */
    PAYOUT_STARTED,

    /** Same as [PAYOUT_STARTED] for the refund of a refused server -> credits deposit. */
    REFUND_STARTED,

    CLOSED
}

/**
 * One operation that may have an unknown outcome. [id] is the `operationId` of the original ledger request (Pano keys
 * the ledger transaction `mc:<serverId>:<id>`, so a repeat with the same id is answered from the first transaction and
 * posts nothing again, 07 section 3.1); its compensation is `<id>:undo`.
 */
data class VaultEntry(
    val id: String,
    val kind: OpKind,
    val username: String,
    val uuid: String?,
    /** Credits on the ledger side. */
    val credits: Double,
    /** Server-economy units (the CONVERT modes), `null` for the PROVIDER operations. */
    val money: Double?,
    val phase: OpPhase,
    val createdAt: Long,
    val updatedAt: Long,
    /** How often the resolver already asked Pano about it. */
    val attempts: Int = 0,
    /** Why the entry was closed (`APPLIED`, `REFUSED`, `NOT_APPLIED`, `UNDONE`, `UNDO_REFUSED`, `REFUNDED`, ...). */
    val closedReason: String? = null,
    val lastCode: String? = null
) {
    val undoId: String get() = id + UNDO_SUFFIX

    companion object {
        const val UNDO_SUFFIX = ":undo"
    }
}

/**
 * The file operations of the journal's compaction, apart so a test can watch their order: the new file's data is forced to
 * disk before the rename, the directory after it (the same steps as `StateStore`).
 */
interface JournalFs {
    /** A channel on [path], created or emptied. */
    fun create(path: Path): FileChannel

    /** Replaces [to] with [from], atomically where the file system can. */
    fun move(from: Path, to: Path)

    /** Makes a rename inside [dir] durable (not supported on every platform: best effort, the rename itself is atomic). */
    fun syncDirectory(dir: Path)
}

object RealJournalFs : JournalFs {
    override fun create(path: Path): FileChannel =
        FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)

    override fun move(from: Path, to: Path) {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    override fun syncDirectory(dir: Path) {
        try {
            FileChannel.open(dir, StandardOpenOption.READ).use { it.force(true) }
        } catch (_: Exception) {
        }
    }
}

/**
 * `vault/journal.log` (next to, not inside, the delivery store: a lost delivery store is moved away as a whole and must
 * not take the record of money that may be owed with it): append-only JSON lines, each the full latest state of one
 * entry, durable (fsync) before [write] returns. The last line of an id wins.
 *
 * Damage never costs the valid entries: a line torn by a crash at the very end is dropped; an unreadable line anywhere
 * else is skipped, loudly, and a copy of the whole damaged file is kept as `journal.corrupt-<ts>` for the admin. A write
 * that fails halfway is cut off again (truncated to the size before it) so the next entry is never glued onto a
 * fragment; when even that fails nothing is appended until the file was rewritten clean. Closed entries are kept only
 * until the next compaction (at start when there is something to drop, and when they pile up); a compaction writes a
 * temp file, forces it to disk, renames it over the journal and forces the directory.
 *
 * Thread safe; every method may be called from any thread. Nothing here blocks on the network.
 */
class VaultJournal(
    private val dir: Path,
    private val log: McLog,
    private val now: () -> Long = System::currentTimeMillis,
    private val sinkFactory: (Path) -> AppendSink = { FileAppendSink(it) },
    private val compactAfterClosed: Int = 200,
    private val fs: JournalFs = RealJournalFs
) {
    private val file: Path = dir.resolve(FILE)
    private val open = LinkedHashMap<String, VaultEntry>()
    private var sink: AppendSink? = null
    private var closedSinceCompaction = 0
    private var closed = false

    /** The file may end in a torn fragment that could not be cut off: nothing is appended until a rewrite repaired it. */
    private var tailSuspect = false

    /** Reads the file and returns the entries that are not closed. Never throws. */
    @Synchronized
    fun load(): List<VaultEntry> {
        open.clear()
        closeSink()
        closed = false
        tailSuspect = false
        try {
            Files.createDirectories(dir)
            if (Files.exists(file)) {
                val bytes = Files.readAllBytes(file)
                val parsed = parse(String(bytes, StandardCharsets.UTF_8))
                if (parsed.badLines.isNotEmpty()) keepCopy(bytes, parsed)
                parsed.entries.values.filter { it.phase != OpPhase.CLOSED }.forEach { open[it.id] = it }
                // Rewritten only when there is something to drop: a power loss during a needless rewrite is a risk for nothing.
                if (parsed.dirty) compact()
            } else {
                Files.write(file, ByteArray(0), StandardOpenOption.CREATE, StandardOpenOption.APPEND)
            }
        } catch (e: Exception) {
            log.error("The Vault journal could not be read: ${e.message}. Unresolved money operations may be lost.", e)
        }
        return open.values.toList()
    }

    /** Appends the entry. Returns `false` when it could not be made durable (the disk refuses writes); nothing is kept then. */
    @Synchronized
    fun write(entry: VaultEntry): Boolean {
        if (closed) return false // a late answer after the component stopped must not reopen the file
        if (tailSuspect && !repairTail()) return false
        var out: AppendSink? = null
        var before = 0L
        try {
            out = ensureSink()
            before = out.size()
            out.append(encode(entry))
        } catch (e: Exception) {
            log.error("The Vault journal could not be written: ${e.message}")
            cutOff(out, before)
            return false
        }
        if (entry.phase == OpPhase.CLOSED) {
            open.remove(entry.id)
            if (++closedSinceCompaction >= compactAfterClosed) {
                // The entry is on disk already: a compaction that fails must not turn this write into a failure.
                try {
                    compact()
                } catch (e: Exception) {
                    log.warn("The Vault journal could not be compacted (${e.message}); it is tried again with a later write.")
                }
            }
        } else {
            open[entry.id] = entry
        }
        return true
    }

    @Synchronized
    fun get(id: String): VaultEntry? = open[id]

    @Synchronized
    fun openEntries(): List<VaultEntry> = open.values.toList()

    @Synchronized
    fun close() {
        closed = true
        closeSink()
    }

    private fun ensureSink(): AppendSink {
        sink?.let { return it }
        Files.createDirectories(dir)
        return sinkFactory(file).also { sink = it }
    }

    private fun closeSink() {
        try {
            sink?.close()
        } catch (_: Exception) {
        }
        sink = null
    }

    /**
     * An append failed, possibly after part of the line reached the file (a disk that filled up inside a block): cut the
     * fragment off on the same sink. When that fails too, the file is rewritten from the known state; when that fails as
     * well the journal refuses further appends until a rewrite worked (it never writes behind a fragment).
     */
    private fun cutOff(out: AppendSink?, sizeBefore: Long) {
        if (out == null) return // the file could not even be opened: nothing was written
        try {
            out.truncate(sizeBefore)
            return
        } catch (e: Exception) {
            log.warn("The Vault journal could not cut a partly written entry off again (${e.message}); the file is rewritten instead.")
        }
        tailSuspect = true
        repairTail()
    }

    private fun repairTail(): Boolean =
        try {
            compact()
            tailSuspect = false
            true
        } catch (e: Exception) {
            log.error("The Vault journal cannot be repaired yet (${e.message}); money operations that need a record are not run until it can.")
            false
        }

    /** Rewrites the file with the open entries only: temp file, data forced to disk, atomic rename, directory forced. */
    private fun compact() {
        val tmp = dir.resolve("$FILE.tmp")
        Files.createDirectories(dir)
        val bytes = open.values.map { encode(it) }.fold(ByteArray(0)) { a, b -> a + b }
        try {
            fs.create(tmp).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
        } catch (e: Exception) {
            try {
                Files.deleteIfExists(tmp)
            } catch (_: Exception) {
            }
            throw e
        }
        closeSink() // the old file is replaced: the next append opens the new one
        fs.move(tmp, file)
        fs.syncDirectory(dir)
        closedSinceCompaction = 0
    }

    private fun keepCopy(bytes: ByteArray, parsed: Parsed) {
        val name = "journal.corrupt-${now()}"
        val lines = parsed.badLines
        val where = if (lines.size == 1) "line ${lines.single()}" else "lines ${lines.joinToString(", ")}"
        val ids = if (parsed.badIds.isEmpty()) "" else " (operation ids seen in them: ${parsed.badIds.joinToString(", ")})"
        try {
            Files.write(dir.resolve(name), bytes)
            log.error("The Vault journal ${file.fileName} has ${lines.size} unreadable line(s) ($where)$ids. Every valid entry was kept; a copy of the damaged file is in $name. Money operations that were only recorded in those lines are NOT retried: check the credit ledger of the Pano admin panel for operations of this server.")
        } catch (e: Exception) {
            log.error("The Vault journal ${file.fileName} has ${lines.size} unreadable line(s) ($where)$ids and a copy of it could not be kept (${e.message}). Every valid entry was kept; check the credit ledger of the Pano admin panel.")
        }
    }

    private class Parsed(
        val entries: Map<String, VaultEntry>,
        /** 1 based numbers of the lines that could not be read (not counting a torn last line). */
        val badLines: List<Int>,
        val badIds: List<String>,
        /** The file holds more than the open entries (closed or superseded lines, a torn tail, ...) and wants a rewrite. */
        val dirty: Boolean
    )

    /** A torn last line (no newline at the end, not parseable) is ignored; any other unreadable line is skipped and reported. */
    private fun parse(text: String): Parsed {
        val out = LinkedHashMap<String, VaultEntry>()
        val endsClean = text.isEmpty() || text.endsWith("\n")
        val lines = text.split('\n').let { if (it.isNotEmpty() && it.last().isEmpty()) it.dropLast(1) else it }
        val badLines = ArrayList<Int>()
        val badIds = ArrayList<String>()
        var decoded = 0
        var other = false
        for ((i, line) in lines.withIndex()) {
            if (line.isBlank()) {
                other = true
                continue
            }
            val entry = try {
                decode(line)
            } catch (e: Exception) {
                if (i == lines.lastIndex && !endsClean) {
                    log.warn("The last line of the Vault journal was cut off by a crash and is ignored.")
                    other = true
                } else {
                    badLines.add(i + 1)
                    ID_IN_LINE.find(line)?.groupValues?.get(1)?.take(64)?.let { badIds.add(it) }
                }
                continue
            }
            decoded++
            out[entry.id] = entry
        }
        val openCount = out.values.count { it.phase != OpPhase.CLOSED }
        return Parsed(out, badLines, badIds, dirty = other || badLines.isNotEmpty() || !endsClean || decoded != openCount)
    }

    companion object {
        const val FILE = "journal.log"
        private val ID_IN_LINE = Regex("\"id\"\\s*:\\s*\"([^\"]{1,64})\"")
        private val gson: Gson = GsonBuilder().disableHtmlEscaping().create()

        internal fun encode(e: VaultEntry): ByteArray {
            val o = JsonObject()
            o.addProperty("id", e.id)
            o.addProperty("kind", e.kind.name)
            o.addProperty("username", e.username)
            e.uuid?.let { o.addProperty("uuid", it) }
            o.addProperty("credits", e.credits)
            e.money?.let { o.addProperty("money", it) }
            o.addProperty("phase", e.phase.name)
            o.addProperty("createdAt", e.createdAt)
            o.addProperty("updatedAt", e.updatedAt)
            o.addProperty("attempts", e.attempts)
            e.closedReason?.let { o.addProperty("closedReason", it) }
            e.lastCode?.let { o.addProperty("lastCode", it) }
            return (gson.toJson(o) + "\n").toByteArray(StandardCharsets.UTF_8)
        }

        internal fun decode(line: String): VaultEntry {
            val o = gson.fromJson(line, JsonObject::class.java) ?: throw IOException("empty line")
            fun str(k: String): String = o.get(k)?.takeIf { !it.isJsonNull }?.asString ?: throw IOException("missing $k")
            fun optStr(k: String): String? = o.get(k)?.takeIf { !it.isJsonNull }?.asString
            fun dbl(k: String): Double = o.get(k)?.takeIf { !it.isJsonNull }?.asDouble ?: throw IOException("missing $k")
            fun lng(k: String): Long = o.get(k)?.takeIf { !it.isJsonNull }?.asLong ?: throw IOException("missing $k")
            return VaultEntry(
                id = str("id"),
                kind = OpKind.valueOf(str("kind")),
                username = str("username"),
                uuid = optStr("uuid"),
                credits = dbl("credits"),
                money = o.get("money")?.takeIf { !it.isJsonNull }?.asDouble,
                phase = OpPhase.valueOf(str("phase")),
                createdAt = lng("createdAt"),
                updatedAt = lng("updatedAt"),
                attempts = o.get("attempts")?.takeIf { !it.isJsonNull }?.asInt ?: 0,
                closedReason = optStr("closedReason"),
                lastCode = optStr("lastCode")
            )
        }
    }
}
