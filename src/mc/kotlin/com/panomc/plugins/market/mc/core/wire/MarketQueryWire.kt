package com.panomc.plugins.market.mc.core.wire

import com.panomc.plugins.pano.core.platform.PlatformMessageResponse
import java.util.UUID

// MARKET_QUERY (19 section 7.2). `data` is one object whose populated keys depend on `type`; the others stay null.

/** `usernames` (at most 100, online players only) for `PLACEHOLDERS`. */
data class QueryArgs(
    val usernames: List<String>? = null
)

class MarketQueryRequest(
    componentVersion: String,
    protocol: Int = MarketWire.PROTOCOL,
    val type: String,
    val player: PlayerRef?,
    val page: Int?,
    val args: QueryArgs?,
    eventId: UUID = UUID.randomUUID()
) : MarketRequest(componentVersion, protocol, eventId)

data class QueryOrder(
    val publicId: String = "",
    val status: String = "",
    val total: Double = 0.0,
    val currency: String = "",
    val itemNames: List<String> = emptyList(),
    val createdAt: Long = 0
)

data class QueryCategory(
    val id: Long = 0,
    val name: String = "",
    val icon: String? = null
)

data class QueryPurchasable(
    val ok: Boolean = false,
    val reason: String? = null
)

data class QueryProduct(
    val id: Long = 0,
    val name: String = "",
    val shortDescription: String? = null,
    val creditPrice: Double? = null,
    val price: Double? = null,
    val currency: String? = null,
    val stockLeft: Int? = null,
    val icon: String? = null,
    val purchasable: QueryPurchasable = QueryPurchasable(),
    val needsWeb: Boolean = false
)

data class QueryGift(
    val from: String? = null,
    val productName: String = "",
    val orderPublicId: String = ""
)

data class QueryPlayerBalance(
    val balance: Double = 0.0
)

data class MarketQueryData(
    // BALANCE
    val registered: Boolean? = null,
    val balance: Double? = null,
    val creditName: String? = null,
    // PURCHASES
    val orders: List<QueryOrder>? = null,
    // CATALOG
    val categories: List<QueryCategory>? = null,
    val products: List<QueryProduct>? = null,
    val page: Int? = null,
    val totalPage: Int? = null,
    // PLACEHOLDERS
    val lastBuyer: String? = null,
    val topSupporter: String? = null,
    val goalName: String? = null,
    val goalPercent: Double? = null,
    val goalProgress: Double? = null,
    val goalTarget: Double? = null,
    val players: Map<String, QueryPlayerBalance>? = null,
    // PENDING
    val deliveriesQueued: Int? = null,
    val gifts: List<QueryGift>? = null
)

data class MarketQueryMessage(
    val accepted: Boolean = false,
    val reason: String? = null,
    val data: MarketQueryData? = null
) : PlatformMessageResponse
