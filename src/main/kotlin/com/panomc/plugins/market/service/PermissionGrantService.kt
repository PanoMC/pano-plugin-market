package com.panomc.plugins.market.service

import com.panomc.plugins.market.core.time.Clock
import com.panomc.plugins.market.service.platform.PermissionWriter
import com.panomc.plugins.market.service.platform.StoredPermissionNode
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.SqlClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * `PERMISSION via=PANO` (08 section 7.2): writes Pano's own `permission_node` rows through the [PermissionWriter] seam on the
 * connection of the caller's market transaction. A node is identified by the tuple `(USER, userId, node, context)`, never by row id.
 *
 * The three operations are state based, so a replay changes nothing:
 * - [grant] `ADD` / `EXTEND`: no row for the tuple => insert; a row with another expiry => the later expiry wins (`null` = permanent
 *   wins over any date), the new row is added before the old one is deleted so the node is never missing; `EXTEND` may also lower
 *   an expiry (the coverage correction of 08 section 11.1 step 5) unless another active grant still holds the node.
 * - [remove] deletes the rows of the recorded tuples, except tuples another active market grant of the same user also holds. A row the market did not
 *   write (an admin's, kept by the grant: [Recorded.preexisting]) is never deleted, and a dated row of someone else that a grant replaced with a later expiry
 *   gets that expiry back ([Recorded.restoreExpiresAt]) instead of being deleted.
 * - [verify] re-adds recorded tuples that went missing (the lost-grant race, 08 section 7.2 "re-assertion").
 *
 * Nothing here commits, and nothing refreshes the platform cache: the caller runs [PermissionWriter.publish] after the commit.
 * Concurrent writers for one player are serialised in-process by [serialized]: the tuple check and the insert are not atomic in the
 * database, so two ranks that share a node would otherwise both insert it.
 */
class PermissionGrantService(private val writer: PermissionWriter, private val clock: Clock) {
    /** A node and the context it is written with (the server scope, plus `"pano": false` for a raw node). */
    class Tuple(val node: String, val context: JsonObject) {
        internal val identity: String = Canon.of(node, context)
    }

    /**
     * What is stored in `result.nodes` of a confirmed row: enough to find the tuple again after a snapshot rewrote the ids. [preexisting]: the grant kept a row
     * it did not write (an admin's), so an undo must leave it alone. [restoreExpiresAt]: the grant replaced a dated row it did not write, an undo puts that
     * expiry back.
     */
    class Recorded(
        val id: Long,
        val node: String,
        val context: JsonObject,
        val expiresAt: Long?,
        val preexisting: Boolean = false,
        val restoreExpiresAt: Long? = null
    ) {
        fun tuple() = Tuple(node, context)

        fun toJson(): JsonObject = JsonObject().put("node", node).put("context", context).put("expiresAt", expiresAt).put("id", id).also {
            if (preexisting) it.put("preexisting", true)
            if (restoreExpiresAt != null) it.put("restoreExpiresAt", restoreExpiresAt)
        }

        companion object {
            fun of(json: JsonObject) = Recorded(
                json.getLong("id", 0L), json.getString("node"), json.getJsonObject("context") ?: JsonObject(), json.getLong("expiresAt"),
                json.getBoolean("preexisting", false), json.getLong("restoreExpiresAt")
            )
        }
    }

    class Granted(val nodes: List<Recorded>, val changed: Boolean)

    class Removed(val removed: List<Recorded>, val kept: List<Recorded>) {
        val changed: Boolean get() = removed.isNotEmpty()
    }

    class Verified(val nodes: List<Recorded>, val readded: Int)

    private val stripes = Array(STRIPES) { Mutex() }

    /**
     * Runs [block] while no other [serialized] call for the same player is running (striped locks: bounded memory, a stripe may be shared).
     * [username] is the name on the delivery row, so the lock exists before the user does.
     */
    suspend fun <T> serialized(username: String, block: suspend () -> T): T = stripes[Math.floorMod(username.lowercase().hashCode(), STRIPES)].withLock { block() }

    /**
     * `ADD` (and `EXTEND`, [extend]) of [tuples] for [userId] with [expiresAt]. [heldElsewhere] answers whether another active market grant
     * of the user holds the tuple (only asked when an `EXTEND` would lower an expiry). [marketRecord] answers the record of the market grant that wrote a
     * stored row, `null` when no market grant wrote it (an admin's row, or one older than the market): such a row is recorded as [Recorded.preexisting].
     */
    suspend fun grant(
        userId: Long,
        tuples: List<Tuple>,
        expiresAt: Long?,
        extend: Boolean,
        heldElsewhere: suspend (Tuple) -> Boolean,
        sqlClient: SqlClient,
        marketRecord: suspend (StoredPermissionNode) -> Recorded? = { null }
    ): Granted {
        val rows = writer.userNodes(userId, sqlClient)
        val recorded = ArrayList<Recorded>()
        var changed = false

        for (tuple in tuples) {
            val existing = rows.filter { Canon.of(it.node, it.context) == tuple.identity }

            if (existing.isEmpty()) {
                recorded += Recorded(writer.add(userId, tuple.node, tuple.context, expiresAt, sqlClient), tuple.node, tuple.context, expiresAt)
                changed = true

                continue
            }

            val current = existing.maxWith(compareBy<StoredPermissionNode> { it.expiresAt ?: Long.MAX_VALUE }.thenBy { it.id })
            val target = when {
                expiresAt == current.expiresAt -> current.expiresAt

                // the new expiry is later than the stored one, or permanent: the later one wins
                laterOf(current.expiresAt, expiresAt) == expiresAt -> expiresAt

                // a permanent node is never lowered (it may be an admin's)
                current.expiresAt == null -> null

                // the new expiry is earlier than the stored one: ADD keeps the later one, EXTEND corrects it unless another grant needs it
                extend && !heldElsewhere(tuple) -> expiresAt

                else -> current.expiresAt
            }

            val own = marketRecord(current)

            if (target == current.expiresAt) {
                recorded += Recorded(current.id, current.node, current.context, current.expiresAt, preexisting = own == null, restoreExpiresAt = own?.restoreExpiresAt)

                continue
            }

            // the new row first, then the old ones: there is never a moment without the node
            val id = writer.add(userId, tuple.node, tuple.context, target, sqlClient)

            writer.deleteByIds(existing.map { it.id }, sqlClient)
            // a replaced row no market grant wrote gets its expiry back on the undo
            recorded += Recorded(id, tuple.node, tuple.context, target, restoreExpiresAt = if (own == null) current.expiresAt else own.restoreExpiresAt)
            changed = true
        }

        return Granted(recorded, changed)
    }

    /**
     * Deletes the rows of [tuples] that [heldElsewhere] does not protect. A [Recorded.preexisting] row is kept; a row with a [Recorded.restoreExpiresAt] still
     * in the future gets that expiry back instead of being deleted.
     */
    suspend fun remove(userId: Long, tuples: List<Recorded>, heldElsewhere: suspend (Tuple) -> Boolean, sqlClient: SqlClient): Removed {
        val rows = writer.userNodes(userId, sqlClient)
        val removed = ArrayList<Recorded>()
        val kept = ArrayList<Recorded>()

        for (recorded in tuples) {
            val tuple = recorded.tuple()

            if (recorded.preexisting || heldElsewhere(tuple)) {
                kept += recorded

                continue
            }

            val matches = rows.filter { Canon.of(it.node, it.context) == tuple.identity }
            val restore = recorded.restoreExpiresAt?.takeIf { it > clock.now() }

            // the restored row first, then the market's: there is never a moment without the node
            if (restore != null) writer.add(userId, tuple.node, tuple.context, restore, sqlClient)

            if (matches.isNotEmpty()) writer.deleteByIds(matches.map { it.id }, sqlClient)

            removed += recorded
        }

        return Removed(removed, kept)
    }

    /**
     * One re-assertion pass: a recorded tuple with no row any more is added again, with its recorded expiry, unless that expiry has passed.
     * The answered ids are the current ones (a snapshot rewrite changes them).
     */
    suspend fun verify(userId: Long, recorded: List<Recorded>, sqlClient: SqlClient): Verified {
        val rows = writer.userNodes(userId, sqlClient)
        val now = clock.now()
        val out = ArrayList<Recorded>()
        var readded = 0

        for (entry in recorded) {
            val tuple = entry.tuple()
            val present = rows.filter { Canon.of(it.node, it.context) == tuple.identity }.maxByOrNull { it.id }

            when {
                present != null -> out += Recorded(present.id, present.node, present.context, present.expiresAt, entry.preexisting, entry.restoreExpiresAt)

                // a row the market did not write is not the market's to put back
                entry.preexisting -> out += entry

                entry.expiresAt != null && entry.expiresAt <= now -> out += entry

                else -> {
                    out += Recorded(
                        writer.add(userId, entry.node, entry.context, entry.expiresAt, sqlClient), entry.node, entry.context, entry.expiresAt,
                        restoreExpiresAt = entry.restoreExpiresAt
                    )
                    readded++
                }
            }
        }

        return Verified(out, readded)
    }

    /** After the commit: refresh the platform cache and broadcast. Never throws. */
    suspend fun publish() {
        try {
            writer.publish()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            org.slf4j.LoggerFactory.getLogger(PermissionGrantService::class.java).warn("the permission refresh after a market grant failed: {}", t.toString())
        }
    }

    companion object {
        private const val STRIPES = 64

        /** The later of two expiries, `null` (permanent) being later than any date. */
        internal fun laterOf(a: Long?, b: Long?): Long? = if (a == null || b == null) null else maxOf(a, b)
    }
}

/** A canonical text for a node and its context: key order and integer width do not matter (a row read back is not the object that was written). */
internal object Canon {
    fun of(node: String, context: JsonObject): String = node + "|" + value(context)

    private fun value(v: Any?): String = when (v) {
        null -> "null"
        is JsonObject -> v.map.entries.sortedBy { it.key }.joinToString(",", "{", "}") { "\"${it.key}\":${value(it.value)}" }
        is Map<*, *> -> v.entries.sortedBy { it.key.toString() }.joinToString(",", "{", "}") { "\"${it.key}\":${value(it.value)}" }
        is JsonArray -> v.list.joinToString(",", "[", "]") { value(it) }
        is List<*> -> v.joinToString(",", "[", "]") { value(it) }
        is Number -> if (v.toDouble() == v.toLong().toDouble()) v.toLong().toString() else v.toString()
        is String -> "\"$v\""
        else -> v.toString()
    }
}
