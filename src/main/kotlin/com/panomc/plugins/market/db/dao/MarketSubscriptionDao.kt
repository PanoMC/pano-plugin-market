package com.panomc.plugins.market.db.dao

import com.panomc.plugins.market.db.MarketDao
import com.panomc.plugins.market.db.model.*
import io.vertx.sqlclient.SqlClient

/** Subscriptions (01 section 10.1). The columns of the initial order and the product snapshot are immutable after insert. */
abstract class MarketSubscriptionDao : MarketDao<MarketSubscription>(MarketSubscription::class.java) {
    /** The new id, or `null` when (providerId, gatewaySubscriptionId) exists already (`uq_provider_sub`). */
    abstract suspend fun add(subscription: MarketSubscription, sqlClient: SqlClient): Long?

    abstract suspend fun getById(id: Long, sqlClient: SqlClient): MarketSubscription?

    abstract suspend fun getByGatewaySubscription(providerId: String, gatewaySubscriptionId: String, sqlClient: SqlClient): MarketSubscription?

    abstract suspend fun getByOwnerKey(ownerKey: String, sqlClient: SqlClient): List<MarketSubscription>

    abstract suspend fun getByUserId(userId: Long, sqlClient: SqlClient): List<MarketSubscription>

    /** Subscriptions in [status] whose `nextChargeAt` is due (`idx_charge`), oldest first. */
    abstract suspend fun getDueForCharge(status: SubscriptionStatus, now: Long, limit: Int, sqlClient: SqlClient): List<MarketSubscription>

    /** Subscriptions in [status] whose `currentPeriodEnd` has passed (`idx_period`), oldest first. */
    abstract suspend fun getPeriodEnded(status: SubscriptionStatus, now: Long, limit: Int, sqlClient: SqlClient): List<MarketSubscription>

    /** Subscriptions whose gateway status poll or remote-cancel retry is due (`idx_query`), oldest first. */
    abstract suspend fun getDueForQuery(now: Long, limit: Int, sqlClient: SqlClient): List<MarketSubscription>

    /** Compare-and-set of the status: `true` when the row was in [from]. */
    abstract suspend fun transition(id: Long, from: SubscriptionStatus, to: SubscriptionStatus, now: Long, sqlClient: SqlClient): Boolean

    /**
     * Books one paid period atomically: `cycleCount = cycleCount + 1` and the new period, guarded by the cycle count
     * the caller saw. `false` when another writer booked that period first.
     */
    abstract suspend fun recordPaidPeriod(
        id: Long, expectedCycleCount: Int, periodStart: Long, periodEnd: Long, nextChargeAt: Long?, now: Long, sqlClient: SqlClient
    ): Boolean

    /** Sets the `cancelAtPeriodEnd` flag (and `cancelRequestedAt` when set). */
    abstract suspend fun setCancelAtPeriodEnd(id: Long, value: Boolean, now: Long, sqlClient: SqlClient): Boolean

    /** Compare-and-set of the remote-cancel queue state, with the attempt counter and the next retry time. */
    abstract suspend fun updateRemoteCancel(
        id: Long, from: RemoteCancelState, to: RemoteCancelState, attempts: Int, nextQueryAt: Long?, now: Long, sqlClient: SqlClient
    ): Boolean

    /** Writes every mutable column of [subscription] (everything except the immutable origin columns and `createdAt`). */
    abstract suspend fun update(subscription: MarketSubscription, sqlClient: SqlClient): Boolean
}
