package com.panomc.plugins.market.core.shipping

import java.security.MessageDigest

/** The pure rules for storing tracking events (10 section 7.2 step 4). */
object TrackingEventRules {
    const val MAX_EVENTS_PER_UPDATE = 200
    const val MAX_EVENTS_PER_SHIPMENT = 500
    const val MAX_EVENT_ID = 128
    const val MAX_RAW_STATUS = 128
    const val MAX_DESCRIPTION = 512
    const val MAX_LOCATION = 255
    private const val FUTURE_TOLERANCE_MS = 24 * 3_600_000L

    /** An event dated more than 24 h in the future is dated [now]. */
    fun clampOccurredAt(occurredAt: Long, now: Long): Long = if (occurredAt > now + FUTURE_TOLERANCE_MS) now else occurredAt

    /** The provider's `eventId` (first 128 characters), else hex SHA-1 of `status|occurredAt|location|rawStatus`. */
    fun dedupeKey(eventId: String?, status: String, occurredAt: Long, location: String?, rawStatus: String?): String {
        if (!eventId.isNullOrEmpty()) return eventId.take(MAX_EVENT_ID)

        val preimage = "$status|$occurredAt|${location ?: ""}|${rawStatus ?: ""}"

        return MessageDigest.getInstance("SHA-1").digest(preimage.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    fun truncate(value: String?, max: Int): String? = value?.take(max)

    /** How many of [incoming] events may be stored when [stored] already exist (at most 200 per update, 500 per shipment). */
    fun room(incoming: Int, stored: Int): Int = minOf(incoming, MAX_EVENTS_PER_UPDATE, maxOf(0, MAX_EVENTS_PER_SHIPMENT - stored))
}
