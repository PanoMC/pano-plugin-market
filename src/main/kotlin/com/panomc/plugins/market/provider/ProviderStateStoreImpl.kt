package com.panomc.plugins.market.provider

import com.panomc.plugins.market.core.time.Clock
import com.panomc.plugins.market.db.dao.MarketProviderStateDao
import com.panomc.plugins.market.db.model.MarketProviderState
import com.panomc.plugins.market.db.model.ProviderStateKind
import com.panomc.plugins.market.db.tx.MarketDb
import com.panomc.plugins.market.spi.common.ProviderErrorCode
import com.panomc.plugins.market.spi.common.ProviderException
import com.panomc.plugins.market.spi.common.ProviderStateStore
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple

/**
 * The key/value store of one provider (`market_provider_state`, 01 section 6.6), scoped by (kind, provider id).
 * Values are encrypted with [SecretCipher] (the `ENC` column holds the `v1:` text). Every operation is one
 * [MarketDb.tx], so it is atomic across market instances sharing the database; time comes from the [Clock] only.
 *
 * - A row whose `expiresAt` has passed reads as absent everywhere (`get`, `compareAndSet`), whether or not the
 *   housekeeping purge ran yet.
 * - [compareAndSet] locks the row (`SELECT ... FOR UPDATE`) and compares the *decrypted* value (the stored text
 *   differs on every encryption because of the random IV). `expected = null` means "absent or expired"; two callers
 *   racing to create the same key cannot both win (the unique key decides).
 * - A stored value that cannot be decrypted reads as absent from [get] and never equals any `expected` in
 *   [compareAndSet] (the row is left alone; [put] overwrites it).
 */
class ProviderStateStoreImpl(
    private val kind: ProviderStateKind,
    private val providerId: String,
    private val dao: MarketProviderStateDao,
    private val db: MarketDb,
    private val cipher: SecretCipher,
    private val clock: Clock
) : ProviderStateStore {

    override suspend fun get(key: String): String? {
        checkKey(key)
        return db.tx { conn ->
            val row = select(conn, key, forUpdate = false) ?: return@tx null
            if (row.expired(clock.now())) null else cipher.decrypt(row.value)
        }
    }

    override suspend fun put(key: String, value: String, ttlSeconds: Long?) {
        checkKey(key)
        checkValue(value)
        val expiresAt = expiry(ttlSeconds)
        db.tx { conn ->
            val now = clock.now()
            conn.preparedQuery(
                "INSERT INTO `${table()}` (`kind`, `providerId`, `stateKey`, `value`, `expiresAt`, `createdAt`, `updatedAt`) VALUES (?, ?, ?, ?, ?, ?, ?) " +
                    "ON DUPLICATE KEY UPDATE `value` = VALUES(`value`), `expiresAt` = VALUES(`expiresAt`), `updatedAt` = VALUES(`updatedAt`)"
            ).execute(
                Tuple.tuple().addValue(kind.name).addValue(providerId).addValue(key).addValue(cipher.encrypt(value))
                    .addValue(expiresAt).addValue(now).addValue(now)
            ).coAwait()
        }
    }

    override suspend fun remove(key: String) {
        checkKey(key)
        db.tx { conn ->
            conn.preparedQuery("DELETE FROM `${table()}` WHERE `kind` = ? AND `providerId` = ? AND `stateKey` = ?")
                .execute(Tuple.of(kind.name, providerId, key)).coAwait()
        }
    }

    override suspend fun compareAndSet(key: String, expected: String?, value: String, ttlSeconds: Long?): Boolean {
        checkKey(key)
        checkValue(value)
        val expiresAt = expiry(ttlSeconds)
        return db.tx { conn ->
            val now = clock.now()
            val row = select(conn, key, forUpdate = true)
            val live = row != null && !row.expired(now)
            if (!live) {
                if (expected != null) return@tx false
                if (row == null) {
                    // Unique key: when another caller inserted the row since the SELECT, we lose.
                    dao.add(
                        MarketProviderState(
                            kind = kind, providerId = providerId, stateKey = key, value = cipher.encrypt(value),
                            expiresAt = expiresAt, createdAt = now, updatedAt = now
                        ),
                        conn
                    ) != null
                } else {
                    update(conn, row.id, value, expiresAt, now)
                    true
                }
            } else {
                val current = cipher.decrypt(row!!.value)
                if (current == null || current != expected) return@tx false
                update(conn, row.id, value, expiresAt, now)
                true
            }
        }
    }

    /**
     * The live (not expired), readable values of this provider, for its [com.panomc.plugins.market.core.abuse.Redactor]
     * (tokens and webhook secrets kept in the state must never reach a log either).
     */
    suspend fun values(): Set<String> = db.tx { conn ->
        val now = clock.now()
        conn.preparedQuery("SELECT `value`, `expiresAt` FROM `${table()}` WHERE `kind` = ? AND `providerId` = ?")
            .execute(Tuple.of(kind.name, providerId)).coAwait()
            .filter { row -> row.getLong("expiresAt").let { it == null || it > now } }
            .mapNotNull { cipher.decrypt(it.getString("value")) }
            .toSet()
    }

    private suspend fun update(conn: SqlConnection, id: Long, value: String, expiresAt: Long?, now: Long) {
        conn.preparedQuery("UPDATE `${table()}` SET `value` = ?, `expiresAt` = ?, `updatedAt` = ? WHERE `id` = ?")
            .execute(Tuple.of(cipher.encrypt(value), expiresAt, now, id)).coAwait()
    }

    private suspend fun select(conn: SqlConnection, key: String, forUpdate: Boolean): StateRow? {
        val rows = conn.preparedQuery(
            "SELECT `id`, `value`, `expiresAt` FROM `${table()}` WHERE `kind` = ? AND `providerId` = ? AND `stateKey` = ?" +
                if (forUpdate) " FOR UPDATE" else ""
        ).execute(Tuple.of(kind.name, providerId, key)).coAwait()
        val row = rows.firstOrNull() ?: return null
        return StateRow(row.getLong("id"), row.getString("value"), row.getLong("expiresAt"))
    }

    private fun table(): String = dao.prefix() + TABLE

    private fun expiry(ttlSeconds: Long?): Long? {
        if (ttlSeconds == null) return null
        if (ttlSeconds <= 0) throw ProviderException(ProviderErrorCode.INVALID_REQUEST, "ttlSeconds must be positive")
        return clock.now() + minOf(ttlSeconds, MAX_TTL_SECONDS) * 1000
    }

    private class StateRow(val id: Long, val value: String, val expiresAt: Long?) {
        fun expired(now: Long): Boolean = expiresAt != null && expiresAt <= now
    }

    companion object {
        const val TABLE = "market_provider_state"
        const val MAX_KEY_LENGTH = 191
        const val MAX_VALUE_BYTES = 40_000
        private const val MAX_TTL_SECONDS = 10L * 365 * 24 * 3600

        internal fun checkKey(key: String) {
            if (key.isEmpty() || key.length > MAX_KEY_LENGTH) {
                throw ProviderException(ProviderErrorCode.INVALID_REQUEST, "A state key is 1 to $MAX_KEY_LENGTH characters")
            }
        }

        internal fun checkValue(value: String) {
            if (value.toByteArray(Charsets.UTF_8).size > MAX_VALUE_BYTES) {
                throw ProviderException(ProviderErrorCode.INVALID_REQUEST, "A state value is at most $MAX_VALUE_BYTES bytes")
            }
        }
    }
}

/** Housekeeping on `market_provider_state` for all providers (daily purge, deletion of a player's keys). */
class ProviderStateMaintenance(private val dao: MarketProviderStateDao, private val db: MarketDb, private val clock: Clock) {
    /** Deletes the expired rows; returns how many. */
    suspend fun purgeExpired(): Int = db.tx { conn ->
        conn.preparedQuery("DELETE FROM `${dao.prefix() + ProviderStateStoreImpl.TABLE}` WHERE `expiresAt` IS NOT NULL AND `expiresAt` <= ?")
            .execute(Tuple.of(clock.now())).coAwait().rowCount()
    }

    /** Deletes the keys `user:<userId>:*` of every provider (a deleted player, 01 section 13); returns how many. */
    suspend fun deleteUserKeys(userId: Long): Int = db.tx { conn ->
        conn.preparedQuery("DELETE FROM `${dao.prefix() + ProviderStateStoreImpl.TABLE}` WHERE `stateKey` LIKE ?")
            .execute(Tuple.of("user:$userId:%")).coAwait().rowCount()
    }
}
