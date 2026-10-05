package com.panomc.plugins.market.service

import com.panomc.platform.util.RateLimiter
import com.panomc.plugins.market.config.MarketConfig
import com.panomc.plugins.market.core.abuse.AbuseLimits
import com.panomc.plugins.market.core.abuse.IpRange
import com.panomc.plugins.market.core.time.Clock
import com.panomc.plugins.market.db.dao.MarketOrderDao
import com.panomc.plugins.market.db.dao.OpenOrderColumn
import com.panomc.plugins.market.error.TooManyRequests
import io.vertx.sqlclient.SqlClient

/**
 * The in-memory limiters of 11 section 11 (L1, L2, L6 to L9, L11), one instance per plugin. The limits that an admin can change
 * (`checkoutRateLimitPerMinute` for L1, `quoteRateLimitPerMinute` for L2) are read from [config] at every call and the limiter is
 * rebuilt when the value differs from the one it was built with (`RateLimiter` fixes its parameters at construction), so a change in
 * the settings takes effect on the next request with no restart; [reconfigure] drops them at once. A limit of 0 switches L1 off.
 *
 * Every check that carries an address takes the IP as the route resolved it (`QuoteCaller.clientIp`: `null` when the address is not
 * trusted, 11 section 2 rule 2) and skips the IP dimension for `null`; IPv6 addresses share a bucket per `/64` ([IpRange.bucketKey]).
 * A refusal is `TooManyRequests(retryAfter)` (429, `retryAfter` at least 1 second).
 */
class MarketRateLimits(private val config: () -> MarketConfig) {
    private class Pair(val perMinute: Int, val ip: RateLimiter, val buyer: RateLimiter)

    @Volatile
    private var l1: Pair? = null

    @Volatile
    private var l2: Pair? = null

    private val l6 = RateLimiter(AbuseLimits.ORDER_TOKEN_MISS_PER_MINUTE, 60_000L / AbuseLimits.ORDER_TOKEN_MISS_PER_MINUTE)
    private val l7 = RateLimiter(AbuseLimits.ORDER_STATUS_PER_MINUTE, 60_000L / AbuseLimits.ORDER_STATUS_PER_MINUTE)
    private val l8 = RateLimiter(AbuseLimits.PANEL_ACTION_PER_MINUTE, 60_000L / AbuseLimits.PANEL_ACTION_PER_MINUTE)
    private val l9 = RateLimiter(AbuseLimits.INBOUND_REJECTED_PER_MINUTE, 60_000L / AbuseLimits.INBOUND_REJECTED_PER_MINUTE)
    private val l11 = RateLimiter(AbuseLimits.EXPORT_PER_MINUTE, 60_000L / AbuseLimits.EXPORT_PER_MINUTE)

    /** Drops the limiters built from a setting; the next call builds them from the current value (`POST /settings` calls it). */
    fun reconfigure() {
        l1 = null
        l2 = null
    }

    // ---- L1: checkout, pay, continue, bank-transfer notice, gift redeem (IP first, buyer after the replay lookup)

    /** L1, the IP bucket. Called before the idempotency replay lookup; a replay then consumes nothing more. */
    fun checkoutIp(clientIp: String?) {
        val limiters = checkoutLimiters() ?: return
        val bucket = IpRange.bucketKey(clientIp) ?: return

        if (!limiters.ip.tryAcquire("ip:$bucket")) throw TooManyRequests(retryAfter(limiters.perMinute))
    }

    /** L1, the buyer's bucket (`u:<id>` for a session, `g:<lower username>` for a guest). Called after the replay lookup. */
    fun checkoutBuyer(buyerKey: String) {
        val limiters = checkoutLimiters() ?: return

        if (!limiters.buyer.tryAcquire("b:$buyerKey")) throw TooManyRequests(retryAfter(limiters.perMinute))
    }

    /** Both L1 buckets, for the routes that have no replay lookup in between (`/pay`, `/payment/continue`, `/bank-transfer/notify`, gift redeem). */
    fun checkout(clientIp: String?, buyerKey: String) {
        checkoutIp(clientIp)
        checkoutBuyer(buyerKey)
    }

    private fun checkoutLimiters(): Pair? {
        val perMinute = config().checkoutRateLimitPerMinute

        if (perMinute <= 0) {
            l1 = null

            return null
        }

        return l1?.takeIf { it.perMinute == perMinute } ?: build(perMinute).also { l1 = it }
    }

    // ---- L2: quote and cart

    /** L2: `quoteRateLimitPerMinute` a minute per IP and, for a session, per account. */
    fun quote(clientIp: String?, userId: Long?) {
        val perMinute = config().quoteRateLimitPerMinute

        if (perMinute <= 0) {
            l2 = null

            return
        }

        val limiters = l2?.takeIf { it.perMinute == perMinute } ?: build(perMinute).also { l2 = it }
        val bucket = IpRange.bucketKey(clientIp)

        if (bucket != null && !limiters.ip.tryAcquire("ip:$bucket")) throw TooManyRequests(retryAfter(perMinute))
        if (userId != null && !limiters.buyer.tryAcquire("b:u:$userId")) throw TooManyRequests(retryAfter(perMinute))
    }

    // ---- fixed limits

    /** L6: an order endpoint with a token that does not match, 30 a minute per IP. Call it only for a miss. */
    fun orderTokenMiss(clientIp: String?) = ipOnly(l6, clientIp)

    /** L7: `GET /orders/:publicId/status`, 120 a minute per IP. */
    fun orderStatus(clientIp: String?) = ipOnly(l7, clientIp)

    /** L8: `POST /webhooks/:id/test`, provider `actions/:actionId`, `POST /payments/:paymentId/query`, 10 a minute per panel user. */
    fun panelAction(userId: Long) {
        if (!l8.tryAcquire("u:$userId")) throw TooManyRequests(maxOf(1, l8.retryAfterSeconds("u:$userId")))
    }

    /** L9: an inbound provider request that was rejected, 60 a minute per IP and provider. An authentic request is never counted. */
    fun inboundRejected(clientIp: String?, providerId: String) {
        val bucket = IpRange.bucketKey(clientIp) ?: return
        val key = "ip:$bucket|$providerId"

        if (!l9.tryAcquire(key)) throw TooManyRequests(maxOf(1, l9.retryAfterSeconds(key)))
    }

    /** L11: `GET /orders/export`, 6 a minute per panel user. */
    fun export(userId: Long) {
        if (!l11.tryAcquire("u:$userId")) throw TooManyRequests(maxOf(1, l11.retryAfterSeconds("u:$userId")))
    }

    private fun ipOnly(limiter: RateLimiter, clientIp: String?) {
        val bucket = IpRange.bucketKey(clientIp) ?: return
        val key = "ip:$bucket"

        if (!limiter.tryAcquire(key)) throw TooManyRequests(maxOf(1, limiter.retryAfterSeconds(key)))
    }

    private fun build(perMinute: Int): Pair {
        val refill = AbuseLimits.refillMs(perMinute)!!

        return Pair(perMinute, RateLimiter(perMinute, refill), RateLimiter(perMinute, refill))
    }

    private fun retryAfter(perMinute: Int): Long = maxOf(1L, Math.ceil(60.0 / perMinute).toLong())
}

/**
 * L4 of 11 section 11: at most [AbuseLimits.MAX_OPEN_ORDERS] storefront orders that are `PENDING` with `reservationState = 'HELD'` per subject, counted
 * separately for the buyer key, the recipient key, the order e-mail and the IP bucket. A guest's buyer key is any name they type, so the key alone caps
 * nothing; the e-mail and the address are the dimensions a hoarder cannot vary for free. [check] runs before the order transaction (SQL counts) and
 * answers 429 with `retryAfter` = seconds until the oldest of the orders of the first full subject expires (at least 1).
 *
 * Recipient: counted only for a gift to somebody else (the buyer key is not the recipient key); for a purchase for oneself the recipient is the buyer and
 * the buyer dimension already counts it, so another payer's unpaid gift can never lock the recipient out of their own checkout (06 section 6.4).
 */
class OpenOrderLimit(private val orders: MarketOrderDao, private val clock: Clock) {
    suspend fun check(buyerKey: String, recipientKey: String, email: String?, clientIp: String?, sqlClient: SqlClient) {
        val dimensions = ArrayList<List<Long?>>(4)

        if (buyerKey.isNotEmpty()) dimensions += orders.openHeldExpiries(OpenOrderColumn.BUYER, buyerKey, sqlClient)
        if (recipientKey.isNotEmpty() && recipientKey != buyerKey) dimensions += orders.openHeldExpiries(OpenOrderColumn.RECIPIENT, recipientKey, sqlClient)
        if (!email.isNullOrBlank()) dimensions += orders.openHeldExpiries(OpenOrderColumn.EMAIL, email.trim(), sqlClient)
        ipExpiries(clientIp, sqlClient)?.let { dimensions += it }

        val full = dimensions.filter { it.size >= AbuseLimits.MAX_OPEN_ORDERS }

        if (full.isEmpty()) return

        // the order that frees a place first; an order without an expiry is not counted down (it never frees by itself), the others decide
        val now = clock.now()
        val seconds = full.map { held ->
            val oldest = held.filterNotNull().minOrNull()

            if (oldest == null) 60L else maxOf(1L, Math.ceil((oldest - now) / 1000.0).toLong())
        }.minOrNull() ?: 60L

        throw TooManyRequests(seconds)
    }

    private suspend fun ipExpiries(clientIp: String?, sqlClient: SqlClient): List<Long?>? {
        val bucket = IpRange.bucketKey(clientIp) ?: return null

        // an IPv4 address is one subject (plus its IPv4-mapped spellings, which are stored as text with colons); an IPv6 address is the whole /64
        val exact = if (bucket.contains(':')) emptyList() else orders.openHeldExpiries(OpenOrderColumn.IP, bucket, sqlClient)
        val sameBucket = orders.openHeldIpv6(sqlClient).filter { IpRange.bucketKey(it.first) == bucket }.map { it.second }

        return (exact + sameBucket).sortedWith(compareBy(nullsLast()) { it })
    }
}
