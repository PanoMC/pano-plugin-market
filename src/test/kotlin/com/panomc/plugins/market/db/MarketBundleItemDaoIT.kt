package com.panomc.plugins.market.db

import com.panomc.plugins.market.db.impl.MarketBundleItemDaoImpl
import com.panomc.plugins.market.db.model.MarketBundleItem
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** `market_bundle_item` (01 section 2.6): round trip, the `uq_bundle_child` result and both finders. */
class MarketBundleItemDaoIT : MarketDaoITBase() {
    private val dao = MarketBundleItemDaoImpl()

    @Test
    fun `insert then get returns every column and defaults apply`(): Unit = runBlocking {
        val id = dao.add(
            MarketBundleItem(
                bundleProductId = 10, productId = 11, variantId = 12, quantity = 3, position = 2,
                createdAt = 1_700_000_000_000, updatedAt = 1_700_000_001_000
            ),
            pool
        )!!
        val read = dao.getById(id, pool)!!
        assertEquals(10L, read.bundleProductId)
        assertEquals(11L, read.productId)
        assertEquals(12L, read.variantId)
        assertEquals(3, read.quantity)
        assertEquals(2, read.position)
        assertEquals(1_700_000_000_000, read.createdAt)
        assertEquals(1_700_000_001_000, read.updatedAt)

        val plain = dao.getById(dao.add(MarketBundleItem(bundleProductId = 10, productId = 13), pool)!!, pool)!!
        assertEquals(0L, plain.variantId)
        assertEquals(1, plain.quantity)
        assertEquals(0, plain.position)
        assertNull(dao.getById(99999, pool))
    }

    @Test
    fun `a duplicate child is null and variantId 0 and a fixed variant are different children`(): Unit = runBlocking {
        val first = dao.add(MarketBundleItem(bundleProductId = 1, productId = 2, variantId = 0, quantity = 1), pool)!!
        assertNull(dao.add(MarketBundleItem(bundleProductId = 1, productId = 2, variantId = 0, quantity = 9), pool))
        assertEquals(1, dao.getById(first, pool)!!.quantity)
        assertNotNull(dao.add(MarketBundleItem(bundleProductId = 1, productId = 2, variantId = 5), pool))
        assertNotNull(dao.add(MarketBundleItem(bundleProductId = 3, productId = 2, variantId = 0), pool))
        assertEquals(3L, count("market_bundle_item"))
    }

    @Test
    fun `update writes quantity and position only`(): Unit = runBlocking {
        val id = dao.add(MarketBundleItem(bundleProductId = 1, productId = 2, createdAt = 1, updatedAt = 1), pool)!!
        dao.update(MarketBundleItem(id = id, bundleProductId = 9, productId = 9, variantId = 9, quantity = 4, position = 6, updatedAt = 7), pool)
        val read = dao.getById(id, pool)!!
        assertEquals(4, read.quantity)
        assertEquals(6, read.position)
        assertEquals(1L, read.bundleProductId)
        assertEquals(2L, read.productId)
        assertEquals(0L, read.variantId)
        assertEquals(7L, read.updatedAt)
    }

    @Test
    fun `finders by bundle and by child, and deletes`(): Unit = runBlocking {
        val c2 = dao.add(MarketBundleItem(bundleProductId = 1, productId = 20, position = 2), pool)!!
        val c1 = dao.add(MarketBundleItem(bundleProductId = 1, productId = 21, position = 1), pool)!!
        val c3 = dao.add(MarketBundleItem(bundleProductId = 1, productId = 22, position = 2), pool)!!
        val inOther = dao.add(MarketBundleItem(bundleProductId = 2, productId = 20), pool)!!

        assertEquals(listOf(c1, c2, c3), dao.getByBundleProductId(1, pool).map { it.id })
        assertEquals(listOf(c2, inOther), dao.getByChildProductId(20, pool).map { it.id })

        dao.deleteById(c1, pool)
        assertEquals(2, dao.deleteByBundleProductId(1, pool))
        assertEquals(0, dao.deleteByBundleProductId(1, pool))
        assertEquals(listOf(inOther), dao.getByChildProductId(20, pool).map { it.id })
    }
}
