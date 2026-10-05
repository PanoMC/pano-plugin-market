package com.panomc.plugins.market.db

import com.panomc.plugins.market.db.impl.MarketProductVariantDaoImpl
import com.panomc.plugins.market.db.model.MarketProductVariant
import com.panomc.plugins.market.util.MarketStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** `market_product_variant` (01 section 2.3): round trip of every column, finders, soft delete and the guarded stock counter. */
class MarketProductVariantDaoIT : MarketDaoITBase() {
    private val dao = MarketProductVariantDaoImpl()

    private fun full(productId: Long = 7, name: String = "L / Red", position: Int = 3) = MarketProductVariant(
        productId = productId, name = name, sku = "SKU-1",
        optionValues = """{"size":"l","color":"red"}""", attributes = """{"material":"cotton"}""",
        price = 12_50, creditPrice = 900, compareAtPrice = 15_00, stock = 5, weightGrams = 250, periodCount = 90,
        imageFileName = "variant.png", position = position, status = MarketStatus.INACTIVE, deletedAt = null,
        createdAt = 1_700_000_000_000, updatedAt = 1_700_000_001_000
    )

    @Test
    fun `insert then get returns every column`(): Unit = runBlocking {
        val id = dao.add(full(), pool)
        val read = dao.getById(id, pool)!!
        assertEquals(id, read.id)
        assertEquals(7L, read.productId)
        assertEquals("L / Red", read.name)
        assertEquals("SKU-1", read.sku)
        assertEquals("""{"size":"l","color":"red"}""", read.optionValues)
        assertEquals("""{"material":"cotton"}""", read.attributes)
        assertEquals(1250L, read.price)
        assertEquals(900L, read.creditPrice)
        assertEquals(1500L, read.compareAtPrice)
        assertEquals(5, read.stock)
        assertEquals(250, read.weightGrams)
        assertEquals(90, read.periodCount)
        assertEquals("variant.png", read.imageFileName)
        assertEquals(3, read.position)
        assertEquals(MarketStatus.INACTIVE, read.status)
        assertNull(read.deletedAt)
        assertEquals(1_700_000_000_000, read.createdAt)
        assertEquals(1_700_000_001_000, read.updatedAt)
    }

    @Test
    fun `nullable columns round trip as null and defaults apply`(): Unit = runBlocking {
        val id = dao.add(MarketProductVariant(productId = 1, name = "Plain"), pool)
        val read = dao.getById(id, pool)!!
        assertNull(read.sku); assertNull(read.price); assertNull(read.creditPrice); assertNull(read.compareAtPrice)
        assertNull(read.stock); assertNull(read.weightGrams); assertNull(read.periodCount); assertNull(read.optionValues)
        assertEquals(0, read.position)
        assertEquals(MarketStatus.ACTIVE, read.status)
        assertNull(dao.getById(id + 1000, pool))
    }

    @Test
    fun `update writes the descriptive columns and leaves stock, deletedAt and productId alone`(): Unit = runBlocking {
        val id = dao.add(full(), pool)
        dao.update(
            MarketProductVariant(
                id = id, productId = 999, name = "XL", sku = null, price = 2000, stock = 77, position = 1,
                status = MarketStatus.ACTIVE, deletedAt = 5, updatedAt = 1_800_000_000_000
            ),
            pool
        )
        val read = dao.getById(id, pool)!!
        assertEquals("XL", read.name)
        assertNull(read.sku)
        assertEquals(2000L, read.price)
        assertEquals(1, read.position)
        assertEquals(MarketStatus.ACTIVE, read.status)
        assertEquals(1_800_000_000_000, read.updatedAt)
        assertEquals(5, read.stock)
        assertNull(read.deletedAt)
        assertEquals(7L, read.productId)
        assertEquals(1_700_000_000_000, read.createdAt)
    }

    @Test
    fun `getByProductId orders by position then id and hides soft deleted rows`(): Unit = runBlocking {
        val b = dao.add(full(productId = 20, name = "B", position = 2), pool)
        val a = dao.add(full(productId = 20, name = "A", position = 1), pool)
        val c = dao.add(full(productId = 20, name = "C", position = 2), pool)
        dao.add(full(productId = 21, name = "other"), pool)

        assertEquals(listOf(a, b, c), dao.getByProductId(20, false, pool).map { it.id })
        assertEquals(3L, dao.countByProductId(20, false, pool))

        assertTrue(dao.markDeleted(b, 1_900_000_000_000, pool))
        assertFalse(dao.markDeleted(b, 1_950_000_000_000, pool), "second delete is a no-op")
        assertFalse(dao.markDeleted(b + 1000, 1L, pool))
        assertEquals(listOf(a, c), dao.getByProductId(20, false, pool).map { it.id })
        assertEquals(listOf(a, b, c), dao.getByProductId(20, true, pool).map { it.id })
        assertEquals(2L, dao.countByProductId(20, false, pool))
        assertEquals(3L, dao.countByProductId(20, true, pool))
        assertEquals(1_900_000_000_000, dao.getById(b, pool)!!.deletedAt)
    }

    @Test
    fun `getByIds finds the rows and an empty list is empty`(): Unit = runBlocking {
        val x = dao.add(full(name = "x"), pool)
        val y = dao.add(full(name = "y"), pool)
        dao.add(full(name = "z"), pool)
        assertEquals(listOf(x, y), dao.getByIds(listOf(y, x, 99999), pool).map { it.id })
        assertTrue(dao.getByIds(emptyList(), pool).isEmpty())
    }

    @Test
    fun `stock reservation is guarded and never goes below zero`(): Unit = runBlocking {
        val id = dao.add(MarketProductVariant(productId = 7, name = "S", stock = 3), pool)
        assertTrue(dao.reserveStock(id, 2, pool))
        assertEquals(1, dao.getById(id, pool)!!.stock)
        assertFalse(dao.reserveStock(id, 2, pool), "only 1 left")
        assertEquals(1, dao.getById(id, pool)!!.stock)
        assertTrue(dao.reserveStock(id, 1, pool))
        assertEquals(0, dao.getById(id, pool)!!.stock)
        assertFalse(dao.reserveStock(id, 1, pool))
        dao.releaseStock(id, 4, pool)
        assertEquals(4, dao.getById(id, pool)!!.stock)
        assertThrows<IllegalArgumentException> { runBlocking { dao.reserveStock(id, 0, pool) } }
    }

    @Test
    fun `an unlimited variant is never reserved and release keeps it unlimited`(): Unit = runBlocking {
        val id = dao.add(MarketProductVariant(productId = 7, name = "Unlimited"), pool)
        assertFalse(dao.reserveStock(id, 1, pool))
        dao.releaseStock(id, 5, pool)
        assertNull(dao.getById(id, pool)!!.stock)
        dao.setStock(id, 9, pool)
        assertEquals(9, dao.getById(id, pool)!!.stock)
        dao.setStock(id, null, pool)
        assertNull(dao.getById(id, pool)!!.stock)
    }

    @Test
    fun `concurrent reservations never oversell`(): Unit = runBlocking {
        val id = dao.add(MarketProductVariant(productId = 7, name = "Hot", stock = 10), pool)
        val results = (1..40).map { async { dao.reserveStock(id, 1, pool) } }.map { it.await() }
        assertEquals(10, results.count { it })
        assertEquals(0, dao.getById(id, pool)!!.stock)
    }

    @Test
    fun `deleteByProductId removes only that product`(): Unit = runBlocking {
        dao.add(full(productId = 30), pool); dao.add(full(productId = 30), pool); dao.add(full(productId = 31), pool)
        assertEquals(2, dao.deleteByProductId(30, pool))
        assertEquals(0, dao.deleteByProductId(30, pool))
        assertEquals(1L, dao.countByProductId(31, true, pool))
    }
}
