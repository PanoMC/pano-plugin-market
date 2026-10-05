package com.panomc.plugins.market.db.dao

import com.panomc.plugins.market.db.MarketDao
import com.panomc.plugins.market.db.model.MarketCart
import io.vertx.sqlclient.SqlClient

/** The cart header, one per user (01 section 4.1). */
abstract class MarketCartDao : MarketDao<MarketCart>(MarketCart::class.java) {
    /** The new id, or `null` when the user already has a cart (`uq_user`). */
    abstract suspend fun add(cart: MarketCart, sqlClient: SqlClient): Long?

    abstract suspend fun getById(id: Long, sqlClient: SqlClient): MarketCart?

    abstract suspend fun getByUserId(userId: Long, sqlClient: SqlClient): MarketCart?

    abstract suspend fun deleteById(id: Long, sqlClient: SqlClient): Boolean

    /**
     * Get-or-create (06 section 2.2): `INSERT ... ON DUPLICATE KEY UPDATE id = LAST_INSERT_ID(id)` on `uq_user`; the id of
     * the user's cart whether it existed or not. One statement, safe under concurrency.
     */
    abstract suspend fun ensure(userId: Long, now: Long, sqlClient: SqlClient): Long

    /** The cart row with its lock (`FOR UPDATE`): every writer of the cart's lines takes it first. */
    abstract suspend fun getByIdForUpdate(id: Long, sqlClient: SqlClient): MarketCart?

    /**
     * Writes the cart-level columns named in [changes] (a subset of `currency`, `couponCode`, `creatorCode`,
     * `recipientUsername`, `giftMessage`, `shippingAddressId`, `shippingMethodId`; `null` clears) and `updatedAt`.
     * Any other key is refused with `IllegalArgumentException`.
     */
    abstract suspend fun updateFields(id: Long, changes: Map<String, Any?>, now: Long, sqlClient: SqlClient)

    /** DELETE `/me/cart` and the order transaction: clears codes, recipient, gift message and shipping selection (not the currency). */
    abstract suspend fun clearFields(id: Long, now: Long, sqlClient: SqlClient)
}
