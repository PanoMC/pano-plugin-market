package com.panomc.plugins.market.db.dao

import com.panomc.plugins.market.db.MarketDao
import com.panomc.plugins.market.db.model.*
import io.vertx.sqlclient.SqlClient

/** Renewal periods of a subscription (01 section 10.2). */
abstract class MarketSubscriptionRenewalDao : MarketDao<MarketSubscriptionRenewal>(MarketSubscriptionRenewal::class.java) {
    /** The new id, or `null` when that period of the subscription exists already (`uq_sub_period`). */
    abstract suspend fun add(renewal: MarketSubscriptionRenewal, sqlClient: SqlClient): Long?

    abstract suspend fun getById(id: Long, sqlClient: SqlClient): MarketSubscriptionRenewal?

    abstract suspend fun getByPeriod(subscriptionId: Long, periodIndex: Int, sqlClient: SqlClient): MarketSubscriptionRenewal?

    /** Every renewal of a subscription, by period index. */
    abstract suspend fun getBySubscriptionId(subscriptionId: Long, sqlClient: SqlClient): List<MarketSubscriptionRenewal>

    /** Renewals in [status] whose `nextAttemptAt` is due (`idx_due`), oldest first. */
    abstract suspend fun getDue(status: RenewalStatus, now: Long, limit: Int, sqlClient: SqlClient): List<MarketSubscriptionRenewal>

    /** Compare-and-set of the status; `true` when the renewal was in [from]. */
    abstract suspend fun transition(id: Long, from: RenewalStatus, to: RenewalStatus, now: Long, sqlClient: SqlClient): Boolean

    /** Links the renewal order / payment (each only when given). */
    abstract suspend fun attach(id: Long, orderId: Long?, paymentId: Long?, now: Long, sqlClient: SqlClient): Boolean

    /** Atomic `attempts + 1` with the next attempt time and the last error. */
    abstract suspend fun recordAttempt(id: Long, nextAttemptAt: Long?, lastError: String?, now: Long, sqlClient: SqlClient): Boolean
}
