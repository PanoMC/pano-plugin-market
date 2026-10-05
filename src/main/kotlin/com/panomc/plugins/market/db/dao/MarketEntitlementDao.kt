package com.panomc.plugins.market.db.dao

import com.panomc.plugins.market.db.MarketDao
import com.panomc.plugins.market.db.model.EntitlementStatus
import com.panomc.plugins.market.db.model.MarketEntitlement
import io.vertx.sqlclient.SqlClient

/** What a player owns (01 section 5.5). */
abstract class MarketEntitlementDao : MarketDao<MarketEntitlement>(MarketEntitlement::class.java) {
    abstract suspend fun add(entitlement: MarketEntitlement, sqlClient: SqlClient): Long

    abstract suspend fun getById(id: Long, sqlClient: SqlClient): MarketEntitlement?

    /** Every entitlement of one owner and product (`idx_owner_product`), oldest first. */
    abstract suspend fun getByOwnerAndProduct(ownerKey: String, productId: Long, sqlClient: SqlClient): List<MarketEntitlement>

    abstract suspend fun getByOrderItemId(orderItemId: Long, sqlClient: SqlClient): List<MarketEntitlement>

    /** Ends an entitlement: `true` when the row exists. */
    abstract suspend fun end(id: Long, status: EntitlementStatus, endReason: String, endedAt: Long, sqlClient: SqlClient): Boolean
}
