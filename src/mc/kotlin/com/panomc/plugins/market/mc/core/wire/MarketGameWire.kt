package com.panomc.plugins.market.mc.core.wire

import com.panomc.plugins.pano.core.platform.PlatformMessageResponse
import java.util.UUID

// MARKET_PURCHASE (19 section 7.3), MARKET_ADMIN (7.4), MARKET_ECONOMY (7.5).

class MarketPurchaseRequest(
    componentVersion: String,
    protocol: Int = MarketWire.PROTOCOL,
    /** The idempotency key of the purchase: Pano keys the order `mc:<serverId>:<operationId>`. */
    val operationId: String,
    val player: PlayerRef,
    val productId: Long,
    val quantity: Int,
    val confirmLegalTextId: Long?,
    eventId: UUID = UUID.randomUUID()
) : MarketRequest(componentVersion, protocol, eventId)

/** `ok = true` carries the order, `ok = false` carries `code` (a code of 04 section 11) and optional `extras`. */
data class MarketPurchaseMessage(
    val accepted: Boolean = false,
    val reason: String? = null,
    val ok: Boolean? = null,
    val orderPublicId: String? = null,
    val creditTotal: Double? = null,
    val balance: Double? = null,
    val code: String? = null,
    val extras: Map<String, Any?>? = null
) : PlatformMessageResponse

data class AdminActor(
    val console: Boolean = false,
    val username: String? = null,
    val uuid: String? = null
)

data class AdminTarget(
    val username: String = ""
)

class MarketAdminRequest(
    componentVersion: String,
    protocol: Int = MarketWire.PROTOCOL,
    val operationId: String,
    val op: String,
    val actor: AdminActor,
    val target: AdminTarget,
    val amount: Double?,
    val productId: Long?,
    val quantity: Int?,
    val note: String?,
    eventId: UUID = UUID.randomUUID()
) : MarketRequest(componentVersion, protocol, eventId)

/** The result keys depend on `op`: credits ops answer `balance`, `GRANT_PRODUCT` `orderPublicId`, `PURCHASES` `orders`. */
data class MarketAdminMessage(
    val accepted: Boolean = false,
    val reason: String? = null,
    val ok: Boolean? = null,
    val code: String? = null,
    val balance: Double? = null,
    val orderPublicId: String? = null,
    val orders: List<QueryOrder>? = null
) : PlatformMessageResponse

class MarketEconomyRequest(
    componentVersion: String,
    protocol: Int = MarketWire.PROTOCOL,
    val operationId: String,
    val op: String,
    val player: PlayerRef,
    val amount: Double?,
    val reason: String?,
    eventId: UUID = UUID.randomUUID()
) : MarketRequest(componentVersion, protocol, eventId)

data class MarketEconomyMessage(
    val accepted: Boolean = false,
    val reason: String? = null,
    val ok: Boolean? = null,
    val code: String? = null,
    val balance: Double? = null
) : PlatformMessageResponse
