package com.panomc.plugins.market.db.dao

import com.panomc.plugins.market.db.MarketDao
import com.panomc.plugins.market.db.model.*
import io.vertx.sqlclient.SqlClient

/** Shipping provider configuration, one row per provider id (01 section 11.4). `settings` is stored encrypted by the caller. */
abstract class MarketShippingCarrierDao : MarketDao<MarketShippingCarrier>(MarketShippingCarrier::class.java) {
    /** The new id, or `null` when `uq_provider` (providerId) exists already. */
    abstract suspend fun add(carrier: MarketShippingCarrier, sqlClient: SqlClient): Long?

    abstract suspend fun getById(id: Long, sqlClient: SqlClient): MarketShippingCarrier?

    /** Writes every mutable column of [carrier] (not the origin columns and `createdAt`); `true` when the row exists. */
    abstract suspend fun update(carrier: MarketShippingCarrier, sqlClient: SqlClient): Boolean

    abstract suspend fun getByProviderId(providerId: String, sqlClient: SqlClient): MarketShippingCarrier?

    /** The carrier whose inbound path carries [webhookToken]. */
    abstract suspend fun getByWebhookToken(webhookToken: String, sqlClient: SqlClient): MarketShippingCarrier?

    abstract suspend fun getAll(sqlClient: SqlClient): List<MarketShippingCarrier>

    abstract suspend fun getEnabled(sqlClient: SqlClient): List<MarketShippingCarrier>

    /** Stamps `lastInboundAt` of a provider; `false` when no row exists. */
    abstract suspend fun recordInbound(providerId: String, now: Long, sqlClient: SqlClient): Boolean

    /** Stores the last error (and its time); a `null` [error] clears both. */
    abstract suspend fun recordError(id: Long, error: String?, now: Long, sqlClient: SqlClient): Boolean
}
