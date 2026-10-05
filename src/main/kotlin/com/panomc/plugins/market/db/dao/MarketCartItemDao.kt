package com.panomc.plugins.market.db.dao

import com.panomc.plugins.market.db.MarketDao
import com.panomc.plugins.market.db.model.MarketCartItem
import io.vertx.sqlclient.SqlClient

/** The lines of a cart (01 section 4.2). */
abstract class MarketCartItemDao : MarketDao<MarketCartItem>(MarketCartItem::class.java) {
    /** The new id, or `null` when the cart already has a line with this `lineKey` (`uq_cart_line`). */
    abstract suspend fun add(item: MarketCartItem, sqlClient: SqlClient): Long?

    abstract suspend fun getById(id: Long, sqlClient: SqlClient): MarketCartItem?

    abstract suspend fun getByCartId(cartId: Long, sqlClient: SqlClient): List<MarketCartItem>

    abstract suspend fun deleteByCartId(cartId: Long, sqlClient: SqlClient): Int
}
