package com.panomc.plugins.market.routes.api.payment

import com.panomc.plugins.market.spi.common.InboundKind
import java.security.MessageDigest

/**
 * The key rules of `market_payment_event.eventKey` for inbound rows (02 section 7.3 steps 2 and 5, 01 section 6.3). Pure: the JDK and the
 * SPI enum only.
 *
 * - a request is stored under `r:<random uuid>` before any provider code runs, so a forged request can never occupy the key of a genuine delivery;
 * - when the provider returns a delivery id, the row is re-keyed to `e:<id>` and `uq_event(providerId, direction, eventKey)` de-duplicates on it;
 * - a row that a redelivery took over keeps its key with `:<row id>` appended ([superseded]);
 * - the request hash is for diagnostics and panel grouping only, never a de-duplication key (Mollie posts the identical body `id=tr_x` for every
 *   state change, Paymentwall pingbacks are GETs with an empty body).
 */
object InboundEventKey {
    const val RECEIVED_PREFIX = "r:"
    const val PROVIDER_PREFIX = "e:"

    /** `VARCHAR(128)` of the column. */
    const val COLUMN_LENGTH = 128

    /** A provider key longer than this (or one that looks like a hashed one) is stored as its SHA-256 so that `:<id>` always fits the column. */
    const val MAX_PROVIDER_KEY = 96

    internal const val HASHED_PREFIX = "sha256:"

    /** The key of a request that was just stored. */
    fun received(uuid: String): String = RECEIVED_PREFIX + uuid

    /**
     * The key of a verified delivery. A short key is stored as it is (`e:evt_123`); a key longer than [MAX_PROVIDER_KEY], or one that starts like
     * the hashed form, is stored as `e:sha256:<hex>` (the same key always maps to the same stored key, and no literal key can produce the hashed form).
     */
    fun provider(key: String): String {
        require(key.isNotBlank()) { "a provider event key must not be blank" }

        return if (key.length > MAX_PROVIDER_KEY || key.startsWith(HASHED_PREFIX)) PROVIDER_PREFIX + HASHED_PREFIX + sha256Hex(key.toByteArray(Charsets.UTF_8))
        else PROVIDER_PREFIX + key
    }

    /** The key a row keeps after a redelivery took its key over (`UPDATE ... SET eventKey = CONCAT(eventKey, ':', id)`). */
    fun superseded(key: String, id: Long): String = "$key:$id"

    fun isReceivedKey(key: String): Boolean = key.startsWith(RECEIVED_PREFIX)

    fun isProviderKey(key: String): Boolean = key.startsWith(PROVIDER_PREFIX)

    /**
     * Hex SHA-256 of `kind 0x00 channel 0x00 attemptToken-or-empty 0x00 method 0x00 rawQuery 0x00 body` (01 section 6.3): `kind` is the enum name,
     * `rawQuery` the undecoded query string without the `?` (empty when there is none), `body` the exact bytes.
     */
    fun requestHash(kind: InboundKind, channel: String, attemptToken: String?, method: String, rawQuery: String?, body: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")

        fun text(value: String) {
            digest.update(value.toByteArray(Charsets.UTF_8))
            digest.update(0)
        }

        text(kind.name)
        text(channel)
        text(attemptToken.orEmpty())
        text(method)
        text(rawQuery.orEmpty())
        digest.update(body)

        return hex(digest.digest())
    }

    internal fun sha256Hex(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun hex(raw: ByteArray): String {
        val out = CharArray(raw.size * 2)

        for (i in raw.indices) {
            val v = raw[i].toInt() and 0xff

            out[i * 2] = HEX[v ushr 4]
            out[i * 2 + 1] = HEX[v and 0x0f]
        }

        return String(out)
    }

    private const val HEX = "0123456789abcdef"
}
