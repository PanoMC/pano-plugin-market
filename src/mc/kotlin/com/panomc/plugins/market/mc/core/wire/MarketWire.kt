package com.panomc.plugins.market.mc.core.wire

import com.panomc.plugins.pano.core.platform.PlatformRequest
import java.util.UUID

/**
 * Wire contract between the Minecraft component and Pano (19 sections 4, 6, 7; 08 sections 8.1 and 8.2).
 *
 * These classes are plain data carriers, duplicated on purpose from the Pano-side request / response classes of
 * `event.server`: `mc.**` must not reference the platform or any other market package. A contract test
 * (WireParityTest, MC-U7) compares their JSON with the fixtures in `src/mcTest/resources/wire`, which the Pano-side
 * test reads as well.
 *
 * Encoding rules that come from `pano-mc-plugin` Core and must not be fought:
 * - a request is encoded by `PlatformRequest.encode()` (Vert.x / Jackson `mapFrom`: every public property, null
 *   included) and the wire event name is the simple class name minus `Request`, upper snake case;
 * - a response is decoded by Gson (`Pano.gson`) and the event name check is the simple class name minus `Message`,
 *   upper snake case. Every constructor parameter therefore has a default, so Gson (which uses the generated no-arg
 *   constructor) leaves an absent JSON key at its default.
 *
 * Money and credits are decimal numbers (04 section 1). Timestamps are epoch milliseconds. Enumerations are strings;
 * their values are the constants below.
 */
object MarketWire {
    /** `protocol` of every request (08 section 8.1); a Pano that does not speak it answers PROTOCOL_UNSUPPORTED. */
    const val PROTOCOL = 1

    const val EVENT_SYNC = "MARKET_SYNC"
    const val EVENT_CONFIG = "MARKET_CONFIG"
    const val EVENT_QUERY = "MARKET_QUERY"
    const val EVENT_PURCHASE = "MARKET_PURCHASE"
    const val EVENT_ADMIN = "MARKET_ADMIN"
    const val EVENT_ECONOMY = "MARKET_ECONOMY"
}

/** `reason` of a refused request (`accepted = false`). */
object RefusalReason {
    const val MARKET_NOT_READY = "MARKET_NOT_READY"
    const val PROTOCOL_UNSUPPORTED = "PROTOCOL_UNSUPPORTED"
    const val VERSION_MISMATCH = "VERSION_MISMATCH"
    const val RATE_LIMITED = "RATE_LIMITED"
}

/** `platform` of `MARKET_SYNC` (08 section 8.1). */
object McPlatformName {
    const val SPIGOT = "SPIGOT"
    const val PAPER = "PAPER"
    const val FOLIA = "FOLIA"
    const val BUNGEECORD = "BUNGEECORD"
    const val VELOCITY = "VELOCITY"
    const val FABRIC = "FABRIC"
}

/** `status` of one reported result (08 section 8.1, 19 section 6.4). */
object ResultStatus {
    const val DONE = "DONE"
    const val QUEUED = "QUEUED"
    const val FAILED = "FAILED"
    const val EXPIRED = "EXPIRED"
    const val CANCELLED = "CANCELLED"
    const val UNKNOWN = "UNKNOWN"
}

/** `code` of a `FAILED` result that the component itself produces (08 section 8.2, 19 section 6). */
object ResultCode {
    const val COMMAND_ERROR = "COMMAND_ERROR"
    const val DISABLED_LOCALLY = "DISABLED_LOCALLY"
    const val LUCKPERMS_MISSING = "LUCKPERMS_MISSING"
    const val INTERRUPTED = "INTERRUPTED"
}

/** `kind` of a delivery. */
object DeliveryKind {
    const val COMMAND = "COMMAND"
    const val PERMISSION = "PERMISSION"
}

/** `type` of `MARKET_QUERY` (19 section 7.2). */
object QueryType {
    const val BALANCE = "BALANCE"
    const val PURCHASES = "PURCHASES"
    const val CATALOG = "CATALOG"
    const val PLACEHOLDERS = "PLACEHOLDERS"
    const val PENDING = "PENDING"
}

/** `op` of `MARKET_ADMIN` (19 section 7.4). */
object AdminOp {
    const val GIVE_CREDITS = "GIVE_CREDITS"
    const val TAKE_CREDITS = "TAKE_CREDITS"
    const val SET_CREDITS = "SET_CREDITS"
    const val GRANT_PRODUCT = "GRANT_PRODUCT"
    const val PURCHASES = "PURCHASES"
}

/** `op` of `MARKET_ECONOMY` (19 section 7.5). */
object EconomyOp {
    const val DEPOSIT = "DEPOSIT"
    const val WITHDRAW = "WITHDRAW"
    const val BALANCE = "BALANCE"
}

/**
 * Base of the six requests: the fields every request carries (19 section 4).
 *
 * The concrete subclass name decides the wire event, so subclasses are named `Market<Event>Request` exactly.
 */
abstract class MarketRequest(
    val componentVersion: String,
    val protocol: Int,
    eventId: UUID
) : PlatformRequest(eventId)

/** A player as the component knows it: the platform-reported username and, as a hint only, the UUID (19 section 3). */
data class PlayerRef(
    val username: String = "",
    val uuid: String? = null
)
