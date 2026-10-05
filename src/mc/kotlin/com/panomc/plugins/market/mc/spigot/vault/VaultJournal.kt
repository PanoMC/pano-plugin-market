package com.panomc.plugins.market.mc.spigot.vault

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.panomc.plugins.market.mc.core.platform.McLog
import com.panomc.plugins.market.mc.core.store.AppendSink
import com.panomc.plugins.market.mc.core.store.FileAppendSink
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

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
 * `vault/journal.log` (next to, not inside, the delivery store: a lost delivery store is moved away as a whole and must
 * not take the record of money that may be owed with it): append-only JSON lines, each the full latest state of one
 * entry, durable (fsync) before [write] returns. The last line of an id wins. A line torn by a crash at the very end is
 * dropped; a damaged line anywhere else moves the file to `journal.corrupt-<ts>` (kept for the admin) and starts a new
 * one, loudly. Closed entries are kept only until the next compaction (at start, and when they pile up).
 *
 * Thread safe; every method may be called from any thread. Nothing here blocks on the network.
 */
class VaultJournal(
    private val dir: Path,
    private val log: McLog,
    private val now: () -> Long = System::currentTimeMillis,
    private val sinkFactory: (Path) -> AppendSink = { FileAppendSink(it) },
    private val compactAfterClosed: Int = 200
) {
    private val file: Path = dir.resolve(FILE)
    private val open = LinkedHashMap<String, VaultEntry>()
    private var sink: AppendSink? = null
    private var closedSinceCompaction = 0
    private var closed = false

    /** Reads the file and returns the entries that are not closed. Never throws. */
    @Synchronized
    fun load(): List<VaultEntry> {
        open.clear()
        closeSink()
        closed = false
        try {
            Files.createDirectories(dir)
            if (Files.exists(file)) {
                val parsed = parse(String(Files.readAllBytes(file), StandardCharsets.UTF_8))
                if (parsed == null) {
                    val moved = dir.resolve("journal.corrupt-${now()}")
                    Files.move(file, moved)
                    log.error("The Vault journal ${file.fileName} is damaged and was moved to ${moved.fileName}. Money operations that were still unresolved in it are NOT retried: check the credit ledger of the Pano admin panel for operations of this server.")
                } else {
                    parsed.values.filter { it.phase != OpPhase.CLOSED }.forEach { open[it.id] = it }
                }
            }
            compact()
        } catch (e: Exception) {
            log.error("The Vault journal could not be read: ${e.message}. Unresolved money operations may be lost.", e)
        }
        return open.values.toList()
    }

    /** Appends the entry. Returns `false` when it could not be made durable (the disk refuses writes); nothing is kept then. */
    @Synchronized
    fun write(entry: VaultEntry): Boolean {
        if (closed) return false // a late answer after the component stopped must not reopen the file
        return try {
            val out = ensureSink()
            out.append(encode(entry))
            if (entry.phase == OpPhase.CLOSED) {
                open.remove(entry.id)
                if (++closedSinceCompaction >= compactAfterClosed) compact()
            } else {
                open[entry.id] = entry
            }
            true
        } catch (e: Exception) {
            log.error("The Vault journal could not be written: ${e.message}")
            // A torn line may be at the end now: restart the file from the known state on the next write.
            try {
                closeSink()
                compact()
            } catch (_: Exception) {
            }
            false
        }
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

    /** Rewrites the file with the open entries only (temp file, fsync, atomic rename). */
    private fun compact() {
        closeSink()
        val tmp = dir.resolve("$FILE.tmp")
        Files.createDirectories(dir)
        val bytes = open.values.map { encode(it) }.fold(ByteArray(0)) { a, b -> a + b }
        Files.write(tmp, bytes)
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
        }
        closedSinceCompaction = 0
    }

    /** `null` = damaged. A torn last line (no newline at the end, not parseable) is ignored. */
    private fun parse(text: String): Map<String, VaultEntry>? {
        val out = LinkedHashMap<String, VaultEntry>()
        val endsClean = text.isEmpty() || text.endsWith("\n")
        val lines = text.split('\n').let { if (it.isNotEmpty() && it.last().isEmpty()) it.dropLast(1) else it }
        for ((i, line) in lines.withIndex()) {
            if (line.isBlank()) continue
            val entry = try {
                decode(line)
            } catch (e: Exception) {
                if (i == lines.lastIndex && !endsClean) {
                    log.warn("The last line of the Vault journal was cut off by a crash and is ignored.")
                    continue
                }
                return null
            }
            out[entry.id] = entry
        }
        return out
    }

    companion object {
        const val FILE = "journal.log"
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
