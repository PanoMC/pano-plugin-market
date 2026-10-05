package com.panomc.plugins.market.db

import com.panomc.plugins.market.db.impl.MarketProductPriceDaoImpl
import com.panomc.plugins.market.db.model.MarketProductPrice
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** `market_product_price` (01 section 2.4): round trip, the `uq_product_variant_currency` result and the finders. */
class MarketProductPriceDaoIT : MarketDaoITBase() {
    private val dao = MarketProductPriceDaoImpl()

    @Test
    fun `insert then get returns every column`(): Unit = runBlocking {
        val id = dao.add(
            MarketProductPrice(
                productId = 4, variantId = 9, currency = "USD", price = 1999, compareAtPrice = 2499,
                createdAt = 1_700_000_000_000, updatedAt = 1_700_000_001_000
            ),
            pool
        )!!
        val read = dao.getById(id, pool)!!
        assertEquals(4L, read.productId)
        assertEquals(9L, read.variantId)
        assertEquals("USD", read.currency)
        assertEquals(1999L, read.price)
        assertEquals(2499L, read.compareAtPrice)
        assertEquals(1_700_000_000_000, read.createdAt)
        assertEquals(1_700_000_001_000, read.updatedAt)

        val plain = dao.getById(dao.add(MarketProductPrice(productId = 4, currency = "EUR", price = 5), pool)!!, pool)!!
        assertEquals(0L, plain.variantId)
        assertNull(plain.compareAtPrice)
        assertNull(dao.getById(99999, pool))
    }

    @Test
    fun `a duplicate of productId variantId currency is answered with null and the row is untouched`(): Unit = runBlocking {
        val first = dao.add(MarketProductPrice(productId = 1, variantId = 0, currency = "USD", price = 100), pool)!!
        assertNull(dao.add(MarketProductPrice(productId = 1, variantId = 0, currency = "USD", price = 999), pool))
        assertEquals(100L, dao.getById(first, pool)!!.price)
        assertEquals(1L, count("market_product_price"))
        // the same currency on another variant or product is a different row
        assertNotNull(dao.add(MarketProductPrice(productId = 1, variantId = 5, currency = "USD", price = 1), pool))
        assertNotNull(dao.add(MarketProductPrice(productId = 2, variantId = 0, currency = "USD", price = 1), pool))
        assertEquals(3L, count("market_product_price"))
    }

    @Test
    fun `upsert inserts once and then overwrites price and compareAtPrice and returns the same id`(): Unit = runBlocking {
        val id = dao.upsert(MarketProductPrice(productId = 3, currency = "GBP", price = 100, compareAtPrice = 150, createdAt = 10, updatedAt = 10), pool)
        val again = dao.upsert(MarketProductPrice(productId = 3, currency = "GBP", price = 200, compareAtPrice = null, createdAt = 99, updatedAt = 20), pool)
        assertEquals(id, again)
        assertEquals(1L, count("market_product_price"))
        val read = dao.getById(id, pool)!!
        assertEquals(200L, read.price)
        assertNull(read.compareAtPrice)
        assertEquals(10L, read.createdAt)
        assertEquals(20L, read.updatedAt)
    }

    @Test
    fun `update writes price and compareAtPrice only`(): Unit = runBlocking {
        val id = dao.add(MarketProductPrice(productId = 1, currency = "USD", price = 100, createdAt = 1, updatedAt = 1), pool)!!
        dao.update(MarketProductPrice(id = id, productId = 77, currency = "XXX", price = 300, compareAtPrice = 400, updatedAt = 5), pool)
        val read = dao.getById(id, pool)!!
        assertEquals(300L, read.price)
        assertEquals(400L, read.compareAtPrice)
        assertEquals("USD", read.currency)
        assertEquals(1L, read.productId)
        assertEquals(5L, read.updatedAt)
    }

    @Test
    fun `finders and deletes work per product and per variant`(): Unit = runBlocking {
        dao.add(MarketProductPrice(productId = 1, variantId = 2, currency = "USD", price = 1), pool)
        dao.add(MarketProductPrice(productId = 1, variantId = 0, currency = "USD", price = 2), pool)
        dao.add(MarketProductPrice(productId = 1, variantId = 0, currency = "EUR", price = 3), pool)
        dao.add(MarketProductPrice(productId = 2, variantId = 0, currency = "EUR", price = 4), pool)

        assertEquals(
            listOf(0L to "EUR", 0L to "USD", 2L to "USD"),
            dao.getByProductId(1, pool).map { it.variantId to it.currency }
        )
        assertEquals(listOf("EUR", "USD"), dao.getByProductAndVariant(1, 0, pool).map { it.currency })
        assertEquals(2L, dao.get(1, 0, "USD", pool)!!.price)
        assertNull(dao.get(1, 0, "GBP", pool))

        assertEquals(1, dao.deleteByVariantId(2, pool))
        assertEquals(2, dao.deleteByProductId(1, pool))
        assertEquals(1L, count("market_product_price"))
        dao.deleteById(dao.get(2, 0, "EUR", pool)!!.id, pool)
        assertEquals(0L, count("market_product_price"))
    }
}
