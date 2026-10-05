package com.panomc.plugins.market.event.server

import com.panomc.platform.server.ServerEventResponse

/** The player a delivery is for: the platform-reported username and, as a hint only, the UUID (19 section 3). */
data class SyncPlayer(
    val username: String = "",
    val uuid: String? = null
)

/** `kind = PERMISSION` payload: `ADD` upserts [nodes] with the given expiry (`null` = permanent), `REMOVE` removes them (08 section 7.2, 19 section 6.3). */
data class SyncPermission(
    val op: String = "ADD",
    val nodes: List<String> = emptyList(),
    val expiresAt: Long? = null
)

/** What the component shows the player when the delivery ran (19 section 9): product, order, gift marker and sender. */
data class SyncDisplay(
    val productName: String? = null,
    val orderPublicId: String? = null,
    val gift: Boolean = false,
    val from: String? = null
)

/** One delivery offered to the component. [key] is the idempotency key the component de-duplicates on for at least 30 days. */
data class SyncDeliveryOffer(
    val key: String = "",
    val id: Long = 0,
    val kind: String = "COMMAND",
    val phase: String = "GRANT",
    val player: SyncPlayer = SyncPlayer(),
    val requiresOnline: Boolean = false,
    val expiresAt: Long? = null,
    val issuer: String? = null,
    val commands: List<String> = emptyList(),
    val permission: SyncPermission? = null,
    val display: SyncDisplay? = null
)

/** A rendered purchase announcement, offered in exactly one sync response per server (best effort, 08 section 8.1). */
data class SyncBroadcastOffer(
    val id: Long = 0,
    val text: String = ""
)

/**
 * `MARKET_SYNC` response (Pano -> MC component), field for field the JSON of 08 section 8.1 and of the shared wire fixtures
 * (`MarketSyncMessage*.json`). The wire name is derived from the class name (`ServerEventResponse.getResponseName`): `MARKET_SYNC`.
 * `eventId` is set by the platform from the request.
 *
 * A refusal is `accepted = false` with a [reason]: `MARKET_NOT_READY`, `PROTOCOL_UNSUPPORTED`, `VERSION_MISMATCH`.
 */
data class MarketSyncEventResponse(
    val accepted: Boolean = false,
    val reason: String? = null,
    val marketVersion: String? = null,
    val acked: List<String> = emptyList(),
    val deliveries: List<SyncDeliveryOffer> = emptyList(),
    val cancel: List<String> = emptyList(),
    val broadcasts: List<SyncBroadcastOffer> = emptyList(),
    val configHash: String? = null,
    val pollAfterMs: Long = 5000
) : ServerEventResponse()
