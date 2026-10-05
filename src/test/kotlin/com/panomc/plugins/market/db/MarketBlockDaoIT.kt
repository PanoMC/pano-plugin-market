package com.panomc.plugins.market.db

import com.panomc.plugins.market.db.impl.MarketBlockDaoImpl
import com.panomc.plugins.market.db.model.*
import com.panomc.plugins.market.support.Race
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** `market_block` (01 section 12). */
class MarketBlockDaoIT : MarketDaoITBase() {
    private val blocks = MarketBlockDaoImpl()

    private fun block(type: BlockType = BlockType.EMAIL, value: String = "a@b.c", expiresAt: Long? = null) = MarketBlock(
        type = type, value = value, reason = "chargeback", source = BlockSource.CHARGEBACK, orderId = 4, createdBy = 5, expiresAt = expiresAt,
        hitCount = 2, lastHitAt = 30, createdAt = 10, updatedAt = 20
    )

    @Test
    fun `a block round-trips every column`(): Unit = runBlocking {
        val id = blocks.add(block(expiresAt = 900), pool)!!
        val r = blocks.getById(id, pool)!!
        assertEquals(listOf<Any?>(BlockType.EMAIL, "a@b.c", "chargeback", BlockSource.CHARGEBACK, 4L, 5L, 900L, 2, 30L, 10L, 20L),
            listOf(r.type, r.value, r.reason, r.source, r.orderId, r.createdBy, r.expiresAt, r.hitCount, r.lastHitAt, r.createdAt, r.updatedAt))
        assertEquals(id, blocks.getByTypeAndValue(BlockType.EMAIL, "a@b.c", pool)!!.id)
        assertNull(blocks.getByTypeAndValue(BlockType.PLAYER, "a@b.c", pool))
    }

    @Test
    fun `type and value are unique`(): Unit = runBlocking {
        assertNotNull(blocks.add(block(), pool))
        assertNull(blocks.add(MarketBlock(type = BlockType.EMAIL, value = "a@b.c", reason = "other", source = BlockSource.MANUAL), pool))
        assertEquals("chargeback", blocks.getByTypeAndValue(BlockType.EMAIL, "a@b.c", pool)!!.reason, "first row intact")
        assertNotNull(blocks.add(block(type = BlockType.PLAYER), pool), "same value, other type")
        assertNotNull(blocks.add(block(value = "x@y.z"), pool), "same type, other value")
        val failure = runCatching {
            sql("INSERT INTO `pano_market_block` (`type`, `value`, `source`, `createdAt`, `updatedAt`) VALUES ('EMAIL', 'a@b.c', 'MANUAL', 1, 1)")
        }.exceptionOrNull()
        assertNotNull(failure)
        assertEquals(3L, count("market_block"))
        val ids = Race.run(4) { blocks.add(block(type = BlockType.IP, value = "10.0.0.0/8"), pool) }.map { it.getOrThrow() }
        assertEquals(1, ids.count { it != null })
    }

    @Test
    fun `active entries skip expired ones and recordHit is an atomic increment`(): Unit = runBlocking {
        val live = blocks.add(block(type = BlockType.IP, value = "1.2.3.4"), pool)!!
        val timed = blocks.add(block(type = BlockType.IP, value = "5.6.7.8", expiresAt = 1000), pool)!!
        val gone = blocks.add(block(type = BlockType.IP, value = "9.9.9.9", expiresAt = 500), pool)!!
        blocks.add(block(type = BlockType.USER, value = "7"), pool)
        assertEquals(listOf(live, timed), blocks.getActiveByType(BlockType.IP, 600, pool).map { it.id })
        assertEquals(listOf(live), blocks.getActiveByType(BlockType.IP, 1000, pool).map { it.id })
        assertEquals(4, blocks.getAll(pool).size)
        assertEquals(blocks.getAll(pool).map { it.id }, blocks.getAll(pool).map { it.id }.sortedDescending())

        val results = Race.run(10) { blocks.recordHit(gone, 77, pool) }
        assertTrue(results.all { it.getOrThrow() })
        val row = blocks.getById(gone, pool)!!
        assertEquals(2 + 10, row.hitCount)
        assertEquals(77L, row.lastHitAt)
        assertFalse(blocks.recordHit(9999, 1, pool))
    }

    @Test
    fun `delete and delete expired`(): Unit = runBlocking {
        val a = blocks.add(block(value = "1@x"), pool)!!
        blocks.add(block(value = "2@x", expiresAt = 100), pool)
        blocks.add(block(value = "3@x", expiresAt = 200), pool)
        assertEquals(2, blocks.deleteExpired(200, pool))
        assertEquals(1L, count("market_block"))
        assertTrue(blocks.delete(a, pool))
        assertFalse(blocks.delete(a, pool))
        assertEquals(0L, count("market_block"))
    }
}
