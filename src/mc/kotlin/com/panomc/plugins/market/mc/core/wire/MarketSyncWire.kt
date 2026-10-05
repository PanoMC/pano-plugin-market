package com.panomc.plugins.market.mc.core.wire

import com.panomc.plugins.pano.core.platform.PlatformMessageResponse
import java.util.UUID

// MARKET_SYNC (08 sections 8.1 and 8.2, 19 section 6).

/** One executed command of a COMMAND delivery. */
data class CommandResult(
    val index: Int = 0,
    val ok: Boolean = false,
    val error: String? = null
)

/** One finished (or queued) delivery key, re-reported until it appears in `acked`. */
data class SyncResult(
    val key: String = "",
    val status: String = ResultStatus.UNKNOWN,
    val code: String? = null,
    val message: String? = null,
    val executedAt: Long? = null,
    val commands: List<CommandResult> = emptyList()
)

class MarketSyncRequest(
    componentVersion: String,
    protocol: Int = MarketWire.PROTOCOL,
    val platform: String,
    val luckPerms: Boolean,
    val vault: Boolean,
    val placeholderApi: Boolean,
    val queued: Int,
    val capacity: Int,
    val configHash: String?,
    val results: List<SyncResult>,
    eventId: UUID = UUID.randomUUID()
) : MarketRequest(componentVersion, protocol, eventId)

data class DeliveryPlayer(
    val username: String = "",
    val uuid: String? = null
)

/** `ADD` upserts the nodes with the given expiry (`null` = permanent), `REMOVE` removes them (08 section 6, 19 section 6.3). */
data class DeliveryPermission(
    val op: String = "ADD",
    val nodes: List<String> = emptyList(),
    val expiresAt: Long? = null
)

data class DeliveryDisplay(
    val productName: String? = null,
    val orderPublicId: String? = null,
    val gift: Boolean = false,
    val from: String? = null
)

data class SyncDelivery(
    val key: String = "",
    val id: Long = 0,
    val kind: String = DeliveryKind.COMMAND,
    val phase: String = "GRANT",
    val player: DeliveryPlayer = DeliveryPlayer(),
    val requiresOnline: Boolean = false,
    val expiresAt: Long? = null,
    val issuer: String? = null,
    val commands: List<String> = emptyList(),
    val permission: DeliveryPermission? = null,
    val display: DeliveryDisplay? = null
)

data class SyncBroadcast(
    val id: Long = 0,
    val text: String = ""
)

data class MarketSyncMessage(
    val accepted: Boolean = false,
    val reason: String? = null,
    val marketVersion: String? = null,
    val acked: List<String> = emptyList(),
    val deliveries: List<SyncDelivery> = emptyList(),
    val cancel: List<String> = emptyList(),
    val broadcasts: List<SyncBroadcast> = emptyList(),
    val configHash: String? = null,
    val pollAfterMs: Long = 5000
) : PlatformMessageResponse
