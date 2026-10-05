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

    /**
     * One-statement insert that merges into an identical line (06 section 2.2):
     * `ON DUPLICATE KEY UPDATE quantity = LEAST(quantity + VALUES(quantity), 999)`. Returns the id of the row (new or merged).
     */
    abstract suspend fun upsertAdd(item: MarketCartItem, sqlClient: SqlClient): Long

    abstract suspend fun getByIdInCart(id: Long, cartId: Long, sqlClient: SqlClient): MarketCartItem?

    abstract suspend fun getByCartIdAndLineKey(cartId: Long, lineKey: String, sqlClient: SqlClient): MarketCartItem?

    abstract suspend fun countByCartId(cartId: Long, sqlClient: SqlClient): Long

    /** Sets the quantity of a line of that cart; `false` when no such row. */
    abstract suspend fun setQuantity(id: Long, cartId: Long, quantity: Int, now: Long, sqlClient: SqlClient): Boolean

    /** Idempotent: a missing row is success; `true` when a row was removed. */
    abstract suspend fun deleteByIdInCart(id: Long, cartId: Long, sqlClient: SqlClient): Boolean
}
