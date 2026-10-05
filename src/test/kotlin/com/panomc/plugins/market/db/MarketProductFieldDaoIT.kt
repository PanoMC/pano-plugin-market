package com.panomc.plugins.market.db

import com.panomc.plugins.market.db.impl.MarketProductFieldDaoImpl
import com.panomc.plugins.market.db.model.MarketProductField
import com.panomc.plugins.market.db.model.ProductFieldType
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** `market_product_field` (01 section 2.5): round trip of every column, the `uq_product_key` results and the finders. */
class MarketProductFieldDaoIT : MarketDaoITBase() {
    private val dao = MarketProductFieldDaoImpl()

    @Test
    fun `insert then get returns every column`(): Unit = runBlocking {
        val id = dao.add(
            MarketProductField(
                productId = 5, fieldKey = "server_nick", label = "Nick üж", helpText = "Your in-game name",
                type = ProductFieldType.SELECT, required = true, options = """[{"value":"a","label":"A"}]""",
                pattern = "[a-z]{3,16}", minLength = 3, maxLength = 16, minValue = -5, maxValue = 99_000_000_000,
                placeholder = "steve", defaultValue = "alex", usableInCommands = false, position = 4,
                createdAt = 1_700_000_000_000, updatedAt = 1_700_000_001_000
            ),
            pool
        )!!
        val read = dao.getById(id, pool)!!
        assertEquals(5L, read.productId)
        assertEquals("server_nick", read.fieldKey)
        assertEquals("Nick üж", read.label)
        assertEquals("Your in-game name", read.helpText)
        assertEquals(ProductFieldType.SELECT, read.type)
        assertTrue(read.required)
        assertEquals("""[{"value":"a","label":"A"}]""", read.options)
        assertEquals("[a-z]{3,16}", read.pattern)
        assertEquals(3, read.minLength)
        assertEquals(16, read.maxLength)
        assertEquals(-5L, read.minValue)
        assertEquals(99_000_000_000, read.maxValue)
        assertEquals("steve", read.placeholder)
        assertEquals("alex", read.defaultValue)
        assertFalse(read.usableInCommands)
        assertEquals(4, read.position)
        assertEquals(1_700_000_000_000, read.createdAt)
        assertEquals(1_700_000_001_000, read.updatedAt)
    }

    @Test
    fun `defaults and nulls round trip`(): Unit = runBlocking {
        val read = dao.getById(dao.add(MarketProductField(productId = 1, fieldKey = "a", label = "A"), pool)!!, pool)!!
        assertEquals(ProductFieldType.TEXT, read.type)
        assertFalse(read.required)
        assertTrue(read.usableInCommands)
        assertNull(read.helpText); assertNull(read.options); assertNull(read.pattern); assertNull(read.minLength)
        assertNull(read.maxLength); assertNull(read.minValue); assertNull(read.maxValue); assertNull(read.placeholder)
        assertNull(read.defaultValue)
        assertEquals(0, read.position)
    }

    @Test
    fun `every field type is stored by name`(): Unit = runBlocking {
        for ((index, type) in ProductFieldType.entries.withIndex()) {
            val id = dao.add(MarketProductField(productId = 2, fieldKey = "f$index", label = "L", type = type), pool)!!
            assertEquals(type, dao.getById(id, pool)!!.type)
            assertEquals(type.name, sql("SELECT `type` FROM `pano_market_product_field` WHERE `id` = ?", id).single().getString("type"))
        }
    }

    @Test
    fun `a duplicate key on the same product is null, another product may reuse the key`(): Unit = runBlocking {
        val first = dao.add(MarketProductField(productId = 1, fieldKey = "nick", label = "One"), pool)!!
        assertNull(dao.add(MarketProductField(productId = 1, fieldKey = "nick", label = "Two"), pool))
        assertEquals("One", dao.getById(first, pool)!!.label)
        assertNotNull(dao.add(MarketProductField(productId = 2, fieldKey = "nick", label = "Three"), pool))
        assertEquals(2L, count("market_product_field"))
    }

    @Test
    fun `update rewrites the columns and reports a key collision with false`(): Unit = runBlocking {
        val a = dao.add(MarketProductField(productId = 1, fieldKey = "a", label = "A", createdAt = 1, updatedAt = 1), pool)!!
        val b = dao.add(MarketProductField(productId = 1, fieldKey = "b", label = "B"), pool)!!

        assertTrue(
            dao.update(
                MarketProductField(
                    id = a, productId = 999, fieldKey = "a2", label = "A2", type = ProductFieldType.NUMBER, required = true,
                    minValue = 1, maxValue = 10, position = 8, updatedAt = 50
                ),
                pool
            )
        )
        val read = dao.getById(a, pool)!!
        assertEquals("a2", read.fieldKey)
        assertEquals(ProductFieldType.NUMBER, read.type)
        assertTrue(read.required)
        assertEquals(8, read.position)
        assertEquals(1L, read.productId)
        assertEquals(1L, read.createdAt)
        assertEquals(50L, read.updatedAt)

        assertFalse(dao.update(MarketProductField(id = b, productId = 1, fieldKey = "a2", label = "B"), pool))
        assertEquals("b", dao.getById(b, pool)!!.fieldKey)
    }

    @Test
    fun `finders order by position and id and cover several products`(): Unit = runBlocking {
        val c = dao.add(MarketProductField(productId = 1, fieldKey = "c", label = "C", position = 2), pool)!!
        val a = dao.add(MarketProductField(productId = 1, fieldKey = "a", label = "A", position = 1), pool)!!
        val b = dao.add(MarketProductField(productId = 1, fieldKey = "b", label = "B", position = 2), pool)!!
        val other = dao.add(MarketProductField(productId = 2, fieldKey = "x", label = "X"), pool)!!
        dao.add(MarketProductField(productId = 3, fieldKey = "y", label = "Y"), pool)

        assertEquals(listOf(a, c, b), dao.getByProductId(1, pool).map { it.id })
        assertEquals(listOf(a, c, b, other), dao.getByProductIds(listOf(2, 1), pool).map { it.id })
        assertTrue(dao.getByProductIds(emptyList(), pool).isEmpty())
        assertEquals(b, dao.getByProductIdAndKey(1, "b", pool)!!.id)
        assertNull(dao.getByProductIdAndKey(1, "zzz", pool))

        dao.deleteById(a, pool)
        assertEquals(2, dao.deleteByProductId(1, pool))
        assertEquals(2L, count("market_product_field"))
    }
}
