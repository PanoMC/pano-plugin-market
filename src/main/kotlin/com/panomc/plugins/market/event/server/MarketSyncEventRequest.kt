package com.panomc.plugins.market.event.server

import com.panomc.platform.server.ServerEventRequest

/** One executed command of a COMMAND delivery (08 section 8.1, `results[].commands[]`). */
data class SyncCommandResult(
    val index: Int = 0,
    val ok: Boolean = false,
    val error: String? = null
)

/**
 * One delivery key the Minecraft component reports (08 section 8.1): re-sent on every sync until it appears in `acked`.
 * [status] is `DONE | QUEUED | FAILED | EXPIRED | CANCELLED | UNKNOWN`; a value outside that list is acknowledged and ignored.
 */
data class SyncResultEntry(
    val key: String = "",
    val status: String = "UNKNOWN",
    val code: String? = null,
    val message: String? = null,
    val executedAt: Long? = null,
    val commands: List<SyncCommandResult> = emptyList()
)

/**
 * `MARKET_SYNC` request (MC component -> Pano), field for field the JSON of 08 section 8.1 and of the shared wire fixtures
 * (`MarketSyncRequest*.json`). The platform decodes it with Gson (`ServerManager.onServerWrite`), so every parameter has a default: an absent
 * key keeps it, and a malformed or older component never produces a null in a non-null field.
 *
 * `event` is the wire name the frame carries; the platform has already used it to find [MarketSyncEvent], it is declared only so the
 * class re-encodes to the fixture.
 */
data class MarketSyncEventRequest(
    val event: String? = null,
    val componentVersion: String = "",
    val protocol: Int = 0,
    val platform: String? = null,
    val luckPerms: Boolean = false,
    val vault: Boolean = false,
    val placeholderApi: Boolean = false,
    val queued: Int = 0,
    val capacity: Int = 0,
    val configHash: String? = null,
    val results: List<SyncResultEntry> = emptyList()
) : ServerEventRequest()
