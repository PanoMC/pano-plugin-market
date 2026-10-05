package com.panomc.plugins.market.mc.core.store

/** Local state of one delivery key (19 section 5). A finished state never changes (19 section 6.4). */
enum class RecordState {
    QUEUED, DONE, FAILED, EXPIRED, CANCELLED;

    val finished: Boolean get() = this != QUEUED
}

data class PlayerIdentity(val username: String, val uuid: String? = null)

data class PermissionSpec(val op: String, val nodes: List<String>, val expiresAt: Long?)

data class DisplayInfo(val productName: String?, val orderPublicId: String?, val gift: Boolean, val from: String?)

data class CommandOutcome(val index: Int, val ok: Boolean, val error: String? = null)

/** The result of a finished record (the status itself is [DeliveryRecord.state]). */
data class RecordResult(
    val code: String? = null,
    val message: String? = null,
    val executedAt: Long? = null,
    val commands: List<CommandOutcome> = emptyList()
)

/**
 * One delivery key as the component stores it (the record of 19 section 5). Immutable: a change is a new value made by
 * [StateStore] from a [JournalOp].
 *
 * `QUEUED` with `startedAt == null` is *waiting* (for the player, or behind an earlier delivery of that player);
 * `QUEUED` with `startedAt != null` is *running* (the "started" journal entry is written before dispatch). A running
 * record found at start-up is an interrupted execution and becomes `FAILED / INTERRUPTED`.
 *
 * A finished record drops `commands`, `permission` and `issuer` (19 section 5: compaction keeps only live fields); the
 * de-duplication only needs the key, the result and the times.
 */
data class DeliveryRecord(
    val key: String,
    val id: Long,
    val kind: String,
    val phase: String,
    val player: PlayerIdentity,
    val requiresOnline: Boolean,
    val expiresAt: Long?,
    val issuer: String?,
    val commands: List<String>,
    val permission: PermissionSpec?,
    val display: DisplayInfo?,
    val state: RecordState,
    val result: RecordResult?,
    val receivedAt: Long,
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    /** When Pano acknowledged the FINAL result (finished records only). */
    val ackedAt: Long? = null,
    /** Pano acknowledged the `QUEUED` report of a waiting record (so it is not reported again until it finishes). */
    val queuedAcked: Boolean = false
) {
    val waiting: Boolean get() = state == RecordState.QUEUED && startedAt == null
    val running: Boolean get() = state == RecordState.QUEUED && startedAt != null

    /** Lower-case, locale independent: the key of every per-player lookup. */
    val playerKey: String get() = playerKey(player.username)

    companion object {
        fun playerKey(username: String): String = username.lowercase()
    }
}

/**
 * The only ways a record changes. The live store and the replay of the journal apply the very same function, so
 * "journal replay equals state" holds by construction (and is tested).
 */
sealed class JournalOp {
    /** A new key, always `QUEUED` and not started. */
    data class Received(val record: DeliveryRecord) : JournalOp()

    /** Written (and flushed) BEFORE the first command is dispatched. */
    data class Started(val key: String, val at: Long) : JournalOp()

    /** Terminal transition of a waiting or running record. */
    data class Finished(val key: String, val state: RecordState, val result: RecordResult, val at: Long) : JournalOp()

    /** Pano acknowledged the `QUEUED` report of a waiting record. */
    data class QueuedAcked(val key: String) : JournalOp()

    /** Pano acknowledged the final result. */
    data class Acked(val key: String, val at: Long) : JournalOp()

    /** Report the stored result (or the `QUEUED` status) again: Pano asked for the key once more. */
    data class Unacked(val key: String) : JournalOp()

    /** Retention purge of acknowledged finished records. */
    data class Purged(val keys: List<String>) : JournalOp()

    /** A no-op used to find out whether the disk accepts writes again. */
    object Probe : JournalOp()
}

/** The journal or the snapshot cannot be read back: the store is quarantined (19 section 4 step 2). */
class StoreCorruptException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** A journal write failed; the in-memory state was rolled back and nothing was executed on the strength of it. */
class StoreWriteException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
