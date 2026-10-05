package com.panomc.plugins.market.routes.api

import com.panomc.platform.error.NotFound
import com.panomc.platform.util.RateLimiter
import com.panomc.plugins.market.core.abuse.IpRange
import com.panomc.plugins.market.core.time.Clock
import com.panomc.plugins.market.db.dao.MarketOrderDao
import com.panomc.plugins.market.db.model.MarketOrder
import com.panomc.plugins.market.error.TooManyRequests
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.SqlClient
import java.security.MessageDigest

/** What a caller is to one order (11 section 5.1). */
enum class OrderRole { OWNER, RECIPIENT, LIMITED }

/** The facts access depends on, so the rules run without the entity (and without the database). */
class OrderAccessFacts(
    val userId: Long?,
    val recipientUserId: Long?,
    val accessToken: String?,
    val createdAt: Long
) {
    companion object {
        fun of(order: MarketOrder) = OrderAccessFacts(order.userId, order.recipientUserId, order.accessToken, order.createdAt)
    }
}

/**
 * The pure rules of `OrderAccess` (11 section 5.1, 06 section 10.3): the shape of a public id, who the token makes an owner,
 * the role of a caller. No clock, no database, no session lookup: the caller passes what it knows.
 */
object OrderAccessRules {
    /** The order token is valid for this long after the order was created. */
    const val TOKEN_TTL_DAYS = 90L
    const val TOKEN_TTL_MS = TOKEN_TTL_DAYS * 24 * 60 * 60 * 1000

    private val PUBLIC_ID = Regex("^[0-9A-HJKMNP-TV-Z]{20}$")

    /** Step 1: 20 Crockford base32 characters; anything else is a 404 without a query. */
    fun isPublicId(raw: String?): Boolean = raw != null && PUBLIC_ID.matches(raw)

    /**
     * The token a request presents: the header `X-Order-Token`; the query `token` only on a `GET` (mail links), never on a mutating
     * call. A blank value is no token.
     */
    fun presentedToken(isGet: Boolean, header: String?, query: String?): String? =
        header?.takeIf { it.isNotEmpty() } ?: if (isGet) query?.takeIf { it.isNotEmpty() } else null

    /**
     * Step 4: the order has a token, it is not older than [TOKEN_TTL_DAYS] at [now] (the day it expires is already over), and
     * [presented] equals it in constant time (`MessageDigest.isEqual`).
     */
    fun tokenValid(facts: OrderAccessFacts, presented: String?, now: Long): Boolean {
        val stored = facts.accessToken?.takeIf { it.isNotEmpty() } ?: return false

        if (presented.isNullOrEmpty()) return false
        if (now >= facts.createdAt + TOKEN_TTL_MS) return false

        return MessageDigest.isEqual(presented.toByteArray(Charsets.UTF_8), stored.toByteArray(Charsets.UTF_8))
    }

    /**
     * Steps 3 and 5: the session user who is the payer is the OWNER; a valid token is the OWNER; the session user who is the recipient
     * is the RECIPIENT; everyone else is LIMITED. A panel session is no owner here.
     */
    fun roleOf(facts: OrderAccessFacts, sessionUserId: Long?, tokenOk: Boolean): OrderRole = when {
        sessionUserId != null && facts.userId == sessionUserId -> OrderRole.OWNER
        tokenOk -> OrderRole.OWNER
        sessionUserId != null && facts.recipientUserId == sessionUserId -> OrderRole.RECIPIENT
        else -> OrderRole.LIMITED
    }
}

/** An order with the role the caller has on it. */
class OrderAccessResult(val order: MarketOrder, val role: OrderRole) {
    /**
     * Owner-only endpoints answer 404 `NOT_FOUND` for everyone else: a 403 would reveal that the order exists and is somebody
     * else's (11 section 5.1).
     */
    fun requireOwner(): MarketOrder {
        if (role != OrderRole.OWNER) throw NotFound()

        return order
    }
}

/**
 * `OrderAccess.resolve` (11 section 5.1): the order behind a public id and the caller's role. A token that is presented but does not
 * match consumes one token of limiter L6 (30 a minute per IP address bucket); an exhausted limiter answers 429.
 */
class OrderAccess(
    private val orders: MarketOrderDao,
    private val clock: Clock,
    private val tokenFailures: RateLimiter = RateLimiter(L6_BURST, L6_REFILL_MS)
) {
    suspend fun resolve(
        publicId: String?,
        sessionUserId: Long?,
        headerToken: String?,
        queryToken: String?,
        isGet: Boolean,
        clientIp: String?,
        sqlClient: SqlClient
    ): OrderAccessResult {
        if (!OrderAccessRules.isPublicId(publicId)) throw NotFound()

        val order = orders.getByPublicId(publicId!!, sqlClient) ?: throw NotFound()
        val facts = OrderAccessFacts.of(order)
        val presented = OrderAccessRules.presentedToken(isGet, headerToken, queryToken)
        val tokenOk = OrderAccessRules.tokenValid(facts, presented, clock.now())

        if (presented != null && !tokenOk) {
            // an address that cannot be parsed has no bucket of its own: its failures share one
            val key = "ip:" + (IpRange.bucketKey(clientIp) ?: "unknown")

            if (!tokenFailures.tryAcquire(key)) throw TooManyRequests(maxOf(1, tokenFailures.retryAfterSeconds(key)))
        }

        return OrderAccessResult(order, OrderAccessRules.roleOf(facts, sessionUserId, tokenOk))
    }

    companion object {
        /** L6: 30 non-matching tokens a minute per IP. */
        const val L6_BURST = 30
        const val L6_REFILL_MS = 2_000L
    }
}

/**
 * `OrderView` per role (11 section 5.2): the owner view is the full one; the recipient and the limited view are cut from it by
 * the allow-list of the table, never by removing a few secret keys.
 */
object OrderViews {
    private val ALWAYS = listOf("publicId", "status", "fulfillmentStatus", "shippingStatus", "createdAt", "paidAt", "currency", "testMode", "isGift")

    fun forRole(owner: JsonObject, role: OrderRole): JsonObject {
        if (role == OrderRole.OWNER) return owner.copy().put("limited", false)

        val out = JsonObject()

        for (key in ALWAYS) out.put(key, owner.getValue(key))

        out.put("limited", true)
        out.put("number", null as Long?)
        out.put("totals", if (role == OrderRole.RECIPIENT) null else JsonObject().put("total", owner.getJsonObject("totals")?.getValue("total")))
        out.put("recipientUsername", if (role == OrderRole.RECIPIENT) owner.getValue("recipientUsername") else null)
        out.put("items", JsonArray((owner.getJsonArray("items") ?: JsonArray()).map { item(it as JsonObject, role) }))
        out.put("shipments", JsonArray((owner.getJsonArray("shipments") ?: JsonArray()).map { shipment(it as JsonObject) }))

        return out
    }

    private fun item(owner: JsonObject, role: OrderRole): JsonObject {
        val out = JsonObject()

        for (key in listOf("name", "variantName", "imageFileName", "quantity", "delivery")) out.put(key, owner.getValue(key))

        if (role == OrderRole.RECIPIENT) {
            for (key in listOf("id", "productId", "fieldValues", "targetServerName", "expiresAt")) out.put(key, owner.getValue(key))
        }

        return out
    }

    /** Status and the two dates; the tracking number and URL are the owner's. */
    private fun shipment(owner: JsonObject): JsonObject {
        val out = JsonObject()

        for (key in listOf("status", "shippedAt", "deliveredAt")) out.put(key, owner.getValue(key))

        return out
    }
}
