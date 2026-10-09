package com.panomc.plugins.market.event.server

import com.panomc.platform.db.model.Server
import com.panomc.platform.server.ServerEvent
import com.panomc.plugins.market.runtime.MarketRuntime
import com.panomc.plugins.market.service.McGameService

/*
 * The five request / response events of the Minecraft component besides MARKET_SYNC (19 section 7, 04 section 3.1): MARKET_CONFIG, MARKET_QUERY, MARKET_PURCHASE,
 * MARKET_ADMIN and MARKET_ECONOMY. Each one is a thin adapter: the platform derives the wire name from the class name (`ServerEvent.getEventName`: the simple name
 * without "Event", upper snake case) and the name of the response (`ServerEventResponse.getResponseName`: without "EventResponse"), so renaming a class silences
 * the component. The frame arrives over the server's authenticated, AES-256-GCM encrypted socket, so `server` is a trusted identity.
 *
 * Every adapter registers before the store is READY, when the beans the service needs may not exist yet: while the runtime gate is closed (00 section 8.9) it answers
 * `MARKET_NOT_READY` itself and never looks the service up. All business rules live in [McGameService].
 */

/** The reason of a refusal that is not about the request itself: the store is not READY. */
private const val NOT_READY = McGameService.REASON_NOT_READY

// ---- MARKET_CONFIG (19 section 7.1) ---------------------------------------------------------------------------------------------------

/** `{have}`: the hash the component holds, `null` when it holds none. `event` is declared only so the class re-encodes to the shared wire fixture. */
data class MarketConfigEventRequest(
    val event: String? = null,
    val componentVersion: String = "",
    val protocol: Int = 0,
    val have: String? = null
) : com.panomc.platform.server.ServerEventRequest()

/** The `mc*` settings of 00 section 12 as the component reads them: panel defaults merged with the per-server override. */
data class McSettingsView(
    val mcStoreCommand: Boolean = true,
    val mcCreditsCommand: Boolean = true,
    val mcJoinNotifications: Boolean = true,
    val mcStoreMenu: Boolean = true,
    val mcAdminCommands: Boolean = true,
    val mcPlaceholders: Boolean = true,
    val mcLuckPerms: Boolean = true,
    val mcBroadcast: Boolean = false,
    val mcBroadcastTemplate: String? = null,
    val mcDisabledAdminCommands: List<String> = emptyList(),
    val mcVaultMode: String = "OFF",
    val mcVaultRate: Double = 1.0,
    val mcVaultDirection: String = "BOTH"
)

/**
 * `MARKET_CONFIG` response. An answer whose `configHash` equals the request's `have` carries no `settings` (and no `texts`): the component keeps what it holds
 * (MC-05: "an answer without settings means unchanged").
 */
data class MarketConfigEventResponse(
    val accepted: Boolean = false,
    val reason: String? = null,
    val configHash: String? = null,
    val settings: McSettingsView? = null,
    /** `locale -> key -> text`, at most three locales. */
    val texts: Map<String, Map<String, String>> = emptyMap(),
    val storeUrl: String? = null,
    val creditName: String? = null,
    val currency: String? = null,
    val serverId: Long? = null,
    /** `market.product` of the front-end URL map with `{slug}` left in (doc 05 section 10.2); a component that knows it uses it instead of `storeUrl/store/<slug>`. */
    val productUrlTemplate: String? = null,
    /** `auth.register` of the front-end URL map; a component that knows it uses it instead of `storeUrl/register`. */
    val registerUrl: String? = null
) : com.panomc.platform.server.ServerEventResponse()

class MarketConfigEvent(
    private val service: () -> McGameService,
    private val ready: () -> Boolean = { MarketRuntime.isReady }
) : ServerEvent<MarketConfigEventRequest, MarketConfigEventResponse>() {
    override suspend fun handle(request: MarketConfigEventRequest, server: Server): MarketConfigEventResponse =
        if (ready()) service().config(request, server) else MarketConfigEventResponse(accepted = false, reason = NOT_READY)
}

// ---- MARKET_QUERY (19 section 7.2) ----------------------------------------------------------------------------------------------------

/** The player a request is about, as the component knows them (19 section 3). */
data class GamePlayer(
    val username: String = "",
    val uuid: String? = null
)

/** `usernames` (at most 100, online players only) for `PLACEHOLDERS`; `categoryId` narrows `CATALOG`. */
data class GameQueryArgs(
    val usernames: List<String>? = null,
    val categoryId: Long? = null
)

data class MarketQueryEventRequest(
    val event: String? = null,
    val componentVersion: String = "",
    val protocol: Int = 0,
    val type: String = "",
    val player: GamePlayer? = null,
    val page: Int? = null,
    val args: GameQueryArgs? = null
) : com.panomc.platform.server.ServerEventRequest()

data class GameOrder(
    val publicId: String = "",
    val status: String = "",
    val total: Double = 0.0,
    val currency: String = "",
    val itemNames: List<String> = emptyList(),
    val createdAt: Long = 0
)

data class GameCategory(
    val id: Long = 0,
    val name: String = "",
    val icon: String? = null
)

data class GamePurchasable(
    val ok: Boolean = false,
    val reason: String? = null
)

data class GameProduct(
    val id: Long = 0,
    val name: String = "",
    val shortDescription: String? = null,
    val creditPrice: Double? = null,
    val price: Double? = null,
    val currency: String? = null,
    val stockLeft: Int? = null,
    val icon: String? = null,
    val purchasable: GamePurchasable = GamePurchasable(),
    val needsWeb: Boolean = false,
    val slug: String? = null
)

data class GameGift(
    val from: String? = null,
    val productName: String = "",
    val orderPublicId: String = ""
)

data class GamePlayerBalance(
    val balance: Double = 0.0
)

/** One object whose populated keys depend on the request `type`; the others stay `null` (and are omitted by the component's decoder). */
data class MarketQueryData(
    // BALANCE
    val registered: Boolean? = null,
    val balance: Double? = null,
    val creditName: String? = null,
    // PURCHASES
    val orders: List<GameOrder>? = null,
    // CATALOG
    val categories: List<GameCategory>? = null,
    val products: List<GameProduct>? = null,
    val page: Int? = null,
    val totalPage: Int? = null,
    // PLACEHOLDERS
    val lastBuyer: String? = null,
    val topSupporter: String? = null,
    val goalName: String? = null,
    val goalPercent: Double? = null,
    val goalProgress: Double? = null,
    val goalTarget: Double? = null,
    val players: Map<String, GamePlayerBalance>? = null,
    // PENDING
    val deliveriesQueued: Int? = null,
    val gifts: List<GameGift>? = null
)

data class MarketQueryEventResponse(
    val accepted: Boolean = false,
    val reason: String? = null,
    val data: MarketQueryData? = null
) : com.panomc.platform.server.ServerEventResponse()

class MarketQueryEvent(
    private val service: () -> McGameService,
    private val ready: () -> Boolean = { MarketRuntime.isReady }
) : ServerEvent<MarketQueryEventRequest, MarketQueryEventResponse>() {
    override suspend fun handle(request: MarketQueryEventRequest, server: Server): MarketQueryEventResponse =
        if (ready()) service().query(request, server) else MarketQueryEventResponse(accepted = false, reason = NOT_READY)
}

// ---- MARKET_PURCHASE (19 section 7.3) -------------------------------------------------------------------------------------------------

data class MarketPurchaseEventRequest(
    val event: String? = null,
    val componentVersion: String = "",
    val protocol: Int = 0,
    /** The idempotency key of the purchase: the order is keyed `mc:<serverId>:<operationId>`. */
    val operationId: String = "",
    val player: GamePlayer = GamePlayer(),
    val productId: Long = 0,
    val quantity: Int = 1,
    val confirmLegalTextId: Long? = null
) : com.panomc.platform.server.ServerEventRequest()

/** `ok = true` carries the order, `ok = false` carries `code` (a code of 04 section 11) and optional `extras`; `accepted = false` is a refusal of the whole request. */
data class MarketPurchaseEventResponse(
    val accepted: Boolean = false,
    val reason: String? = null,
    val ok: Boolean? = null,
    val orderPublicId: String? = null,
    val creditTotal: Double? = null,
    val balance: Double? = null,
    val code: String? = null,
    val extras: Map<String, Any?>? = null
) : com.panomc.platform.server.ServerEventResponse()

class MarketPurchaseEvent(
    private val service: () -> McGameService,
    private val ready: () -> Boolean = { MarketRuntime.isReady }
) : ServerEvent<MarketPurchaseEventRequest, MarketPurchaseEventResponse>() {
    override suspend fun handle(request: MarketPurchaseEventRequest, server: Server): MarketPurchaseEventResponse =
        if (ready()) service().purchase(request, server) else MarketPurchaseEventResponse(accepted = false, reason = NOT_READY)
}

// ---- MARKET_ADMIN (19 section 7.4) ----------------------------------------------------------------------------------------------------

data class GameActor(
    val console: Boolean = false,
    val username: String? = null,
    val uuid: String? = null
)

data class GameTarget(
    val username: String = ""
)

data class MarketAdminEventRequest(
    val event: String? = null,
    val componentVersion: String = "",
    val protocol: Int = 0,
    val operationId: String = "",
    val op: String = "",
    val actor: GameActor = GameActor(),
    val target: GameTarget = GameTarget(),
    val amount: Double? = null,
    val productId: Long? = null,
    val quantity: Int? = null,
    val note: String? = null
) : com.panomc.platform.server.ServerEventRequest()

/** The result keys depend on `op`: the credit operations answer `balance`, `GRANT_PRODUCT` `orderPublicId`, `PURCHASES` `orders`. */
data class MarketAdminEventResponse(
    val accepted: Boolean = false,
    val reason: String? = null,
    val ok: Boolean? = null,
    val code: String? = null,
    val balance: Double? = null,
    val orderPublicId: String? = null,
    val orders: List<GameOrder>? = null
) : com.panomc.platform.server.ServerEventResponse()

class MarketAdminEvent(
    private val service: () -> McGameService,
    private val ready: () -> Boolean = { MarketRuntime.isReady }
) : ServerEvent<MarketAdminEventRequest, MarketAdminEventResponse>() {
    override suspend fun handle(request: MarketAdminEventRequest, server: Server): MarketAdminEventResponse =
        if (ready()) service().admin(request, server) else MarketAdminEventResponse(accepted = false, reason = NOT_READY)
}

// ---- MARKET_ECONOMY (19 section 7.5) --------------------------------------------------------------------------------------------------

data class MarketEconomyEventRequest(
    val event: String? = null,
    val componentVersion: String = "",
    val protocol: Int = 0,
    val operationId: String = "",
    val op: String = "",
    val player: GamePlayer = GamePlayer(),
    val amount: Double? = null,
    val reason: String? = null
) : com.panomc.platform.server.ServerEventRequest()

data class MarketEconomyEventResponse(
    val accepted: Boolean = false,
    val reason: String? = null,
    val ok: Boolean? = null,
    val code: String? = null,
    val balance: Double? = null
) : com.panomc.platform.server.ServerEventResponse()

class MarketEconomyEvent(
    private val service: () -> McGameService,
    private val ready: () -> Boolean = { MarketRuntime.isReady }
) : ServerEvent<MarketEconomyEventRequest, MarketEconomyEventResponse>() {
    override suspend fun handle(request: MarketEconomyEventRequest, server: Server): MarketEconomyEventResponse =
        if (ready()) service().economy(request, server) else MarketEconomyEventResponse(accepted = false, reason = NOT_READY)
}
