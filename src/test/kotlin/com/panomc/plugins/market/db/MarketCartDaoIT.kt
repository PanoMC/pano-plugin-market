package com.panomc.plugins.market.db

import com.panomc.plugins.market.db.dao.isDuplicateKey
import com.panomc.plugins.market.db.impl.MarketCartDaoImpl
import com.panomc.plugins.market.db.impl.MarketCartItemDaoImpl
import com.panomc.plugins.market.db.model.MarketCart
import com.panomc.plugins.market.db.model.MarketCartItem
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** `market_cart` (01 section 4.1) and `market_cart_item` (01 section 4.2). */
class MarketCartDaoIT : MarketDaoITBase() {
    private val carts = MarketCartDaoImpl()
    private val items = MarketCartItemDaoImpl()

    private fun cart(user: Long = 5) = MarketCart(
        userId = user, currency = "EUR", couponCode = "SAVE10", creatorCode = "CREATOR", recipientUsername = "Alex",
        giftMessage = "enjoy", shippingAddressId = 8, shippingMethodId = 9, createdAt = 10, updatedAt = 20
    )

    private fun item(cart: Long, key: String = "a".repeat(40), product: Long = 3) = MarketCartItem(
        cartId = cart, productId = product, variantId = 2, quantity = 4, fieldValues = "{\"nick\":\"Steve\"}",
        targetServerId = 6, lineKey = key, createdAt = 10, updatedAt = 20
    )

    @Test
    fun `a cart round-trips every column`(): Unit = runBlocking {
        val written = cart()
        EntityRoundTrip.differsFromDefaults(written, MarketCart())
        val id = carts.add(written, pool)!!
        val read = carts.getById(id, pool)!!
        assertEquals(id, read.id)
        EntityRoundTrip.assertSame(written, read)
        assertEquals(id, carts.getByUserId(5, pool)!!.id)
        assertNull(carts.getByUserId(6, pool))
        assertNull(carts.getById(9999, pool))
    }

    @Test
    fun `a user has one cart, the duplicate is answered with null and rejected by uq_user`(): Unit = runBlocking {
        assertNotNull(carts.add(cart(), pool))
        assertNull(carts.add(cart(), pool))
        assertEquals(1L, count("market_cart"))
        val raw = runCatching {
            sql("INSERT INTO `pano_market_cart` (`userId`, `createdAt`, `updatedAt`) VALUES (5, 1, 1)")
        }.exceptionOrNull()
        assertTrue(raw != null && raw.isDuplicateKey())
        assertNotNull(carts.add(cart(user = 6), pool))
    }

    @Test
    fun `a cart item round-trips every column and identical lines collide`(): Unit = runBlocking {
        val cartId = carts.add(cart(), pool)!!
        val written = item(cartId)
        EntityRoundTrip.differsFromDefaults(written, MarketCartItem())
        val id = items.add(written, pool)!!
        val read = items.getById(id, pool)!!
        assertEquals(id, read.id)
        EntityRoundTrip.assertSame(written, read)
        // same cart and lineKey: merged by the caller, so the DAO answers null and the key rejects a raw insert
        assertNull(items.add(item(cartId, product = 99), pool))
        val raw = runCatching {
            sql("INSERT INTO `pano_market_cart_item` (`cartId`, `productId`, `lineKey`, `createdAt`, `updatedAt`) VALUES (?, 1, ?, 1, 1)", cartId, "a".repeat(40))
        }.exceptionOrNull()
        assertTrue(raw != null && raw.isDuplicateKey())
        // another line key in the same cart, and the same key in another cart, are other rows
        assertNotNull(items.add(item(cartId, key = "b".repeat(40)), pool))
        val other = carts.add(cart(user = 6), pool)!!
        assertNotNull(items.add(item(other), pool))
        assertNull(items.getById(9999, pool))
    }

    @Test
    fun `defaults apply and lines are listed per cart and deleted with it`(): Unit = runBlocking {
        val cartId = carts.add(MarketCart(userId = 5), pool)!!
        sql("INSERT INTO `pano_market_cart_item` (`cartId`, `productId`, `lineKey`, `createdAt`, `updatedAt`) VALUES (?, 1, ?, 1, 1)", cartId, "c".repeat(40))
        val line = items.getByCartId(cartId, pool).single()
        assertEquals(0L, line.variantId)
        assertEquals(1, line.quantity)
        assertNull(line.fieldValues)
        assertNull(line.targetServerId)
        items.add(item(cartId, key = "d".repeat(40)), pool)
        assertEquals(2, items.getByCartId(cartId, pool).size)
        assertEquals(emptyList<MarketCartItem>(), items.getByCartId(cartId + 1, pool))
        assertEquals(2, items.deleteByCartId(cartId, pool))
        assertTrue(carts.deleteById(cartId, pool))
        assertFalse(carts.deleteById(cartId, pool))
    }
}
