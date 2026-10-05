package com.panomc.plugins.market.db

import com.panomc.plugins.market.db.impl.MarketProductProviderMetaDaoImpl
import com.panomc.plugins.market.db.model.MarketProductProviderMeta
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** `market_product_provider_meta` (01 section 2.7): round trip, upsert on the unique key, finders and deletes. */
class MarketProductProviderMetaDaoIT : MarketDaoITBase() {
    private val dao = MarketProductProviderMetaDaoImpl()

    @Test
    fun `upsert then get returns every column`(): Unit = runBlocking {
        val id = dao.upsert(
            MarketProductProviderMeta(
                productId = 4, variantId = 8, providerId = "tebex", meta = """{"packageId":"123","note":"ü"}""",
                createdAt = 1_700_000_000_000, updatedAt = 1_700_000_001_000
            ),
            pool
        )
        val read = dao.getById(id, pool)!!
        assertEquals(4L, read.productId)
        assertEquals(8L, read.variantId)
        assertEquals("tebex", read.providerId)
        assertEquals("""{"packageId":"123","note":"ü"}""", read.meta)
        assertEquals(1_700_000_000_000, read.createdAt)
        assertEquals(1_700_000_001_000, read.updatedAt)
        assertEquals(id, dao.get(4, 8, "tebex", pool)!!.id)
        assertNull(dao.get(4, 8, "paynow", pool))
        assertNull(dao.getById(99999, pool))
    }

    @Test
    fun `a second upsert of the same key overwrites meta and keeps id and createdAt`(): Unit = runBlocking {
        val id = dao.upsert(MarketProductProviderMeta(productId = 1, providerId = "tebex", meta = """{"a":1}""", createdAt = 10, updatedAt = 10), pool)
        val again = dao.upsert(MarketProductProviderMeta(productId = 1, providerId = "tebex", meta = """{"a":2}""", createdAt = 99, updatedAt = 20), pool)
        assertEquals(id, again)
        assertEquals(1L, count("market_product_provider_meta"))
        val read = dao.getById(id, pool)!!
        assertEquals("""{"a":2}""", read.meta)
        assertEquals(10L, read.createdAt)
        assertEquals(20L, read.updatedAt)
        assertEquals(0L, read.variantId)
    }

    @Test
    fun `provider variant and product make different rows`(): Unit = runBlocking {
        dao.upsert(MarketProductProviderMeta(productId = 1, variantId = 0, providerId = "tebex"), pool)
        dao.upsert(MarketProductProviderMeta(productId = 1, variantId = 3, providerId = "tebex"), pool)
        dao.upsert(MarketProductProviderMeta(productId = 1, variantId = 0, providerId = "paynow"), pool)
        dao.upsert(MarketProductProviderMeta(productId = 2, variantId = 0, providerId = "tebex"), pool)
        assertEquals(4L, count("market_product_provider_meta"))
    }

    @Test
    fun `finders by product and by provider, and deletes`(): Unit = runBlocking {
        dao.upsert(MarketProductProviderMeta(productId = 1, variantId = 3, providerId = "tebex", meta = "{}"), pool)
        dao.upsert(MarketProductProviderMeta(productId = 1, variantId = 0, providerId = "tebex", meta = "{}"), pool)
        dao.upsert(MarketProductProviderMeta(productId = 1, variantId = 0, providerId = "paynow", meta = "{}"), pool)
        dao.upsert(MarketProductProviderMeta(productId = 2, variantId = 0, providerId = "tebex", meta = "{}"), pool)

        assertEquals(
            listOf(0L to "paynow", 0L to "tebex", 3L to "tebex"),
            dao.getByProductId(1, pool).map { it.variantId to it.providerId }
        )
        assertEquals(listOf(1L to 0L, 1L to 3L, 2L to 0L), dao.getByProviderId("tebex", pool).map { it.productId to it.variantId }.sortedWith(compareBy({ it.first }, { it.second })))
        assertEquals(1, dao.delete(1, 3, "tebex", pool))
        assertEquals(0, dao.delete(1, 3, "tebex", pool))
        dao.deleteById(dao.get(2, 0, "tebex", pool)!!.id, pool)
        assertEquals(2, dao.deleteByProductId(1, pool))
        assertEquals(0L, count("market_product_provider_meta"))
    }
}
