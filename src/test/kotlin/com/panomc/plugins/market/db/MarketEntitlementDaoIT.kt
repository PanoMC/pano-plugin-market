package com.panomc.plugins.market.db

import com.panomc.plugins.market.db.impl.MarketEntitlementDaoImpl
import com.panomc.plugins.market.db.model.EntitlementStatus
import com.panomc.plugins.market.db.model.MarketEntitlement
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** `market_entitlement` (01 section 5.5). */
class MarketEntitlementDaoIT : MarketDaoITBase() {
    private val dao = MarketEntitlementDaoImpl()

    private fun entitlement(owner: String = "u:7", product: Long = 3, orderItem: Long = 11) = MarketEntitlement(
        userId = 7, playerUsername = "Steve", ownerKey = owner, productId = product, variantId = 2, orderId = 10,
        orderItemId = orderItem, subscriptionId = 4, quantity = 3, status = EntitlementStatus.UPGRADED, startsAt = 100,
        expiresAt = 200, tierCategoryId = 6, tierRank = 2, pricePaid = 12_345, replacedById = 99, endReason = "UPGRADE",
        reminderSentAt = 150, endedAt = 160, createdAt = 10, updatedAt = 20
    )

    @Test
    fun `an entitlement round-trips every column`(): Unit = runBlocking {
        val written = entitlement()
        EntityRoundTrip.differsFromDefaults(written, MarketEntitlement())
        val id = dao.add(written, pool)
        val read = dao.getById(id, pool)!!
        assertEquals(id, read.id)
        EntityRoundTrip.assertSame(written, read)
        assertNull(dao.getById(9999, pool))
    }

    @Test
    fun `column defaults and nullable columns hold when only the required fields are written`(): Unit = runBlocking {
        sql("INSERT INTO `pano_market_entitlement` (`playerUsername`, `ownerKey`, `productId`, `orderId`, `orderItemId`, `startsAt`, `createdAt`, `updatedAt`) VALUES ('Alex', 'g:alex', 1, 2, 3, 5, 1, 1)")
        val read = dao.getByOwnerAndProduct("g:alex", 1, pool).single()
        assertEquals(0L, read.variantId)
        assertEquals(1, read.quantity)
        assertEquals(EntitlementStatus.ACTIVE, read.status)
        assertEquals(0L, read.pricePaid)
        assertNull(read.userId)
        assertNull(read.expiresAt)
        assertNull(read.subscriptionId)
    }

    @Test
    fun `lookups by owner and product and by order item return only their rows, oldest first`(): Unit = runBlocking {
        val a = dao.add(entitlement(), pool)
        dao.add(entitlement(owner = "u:8", orderItem = 14), pool)
        dao.add(entitlement(product = 4, orderItem = 12), pool)
        val c = dao.add(entitlement(orderItem = 13), pool)
        assertEquals(listOf(a, c), dao.getByOwnerAndProduct("u:7", 3, pool).map { it.id })
        assertEquals(listOf(a), dao.getByOrderItemId(11, pool).map { it.id })
        assertEquals(emptyList<MarketEntitlement>(), dao.getByOwnerAndProduct("u:7", 99, pool))
    }

    @Test
    fun `end records status, reason and time and reports a missing row`(): Unit = runBlocking {
        val id = dao.add(MarketEntitlement(playerUsername = "Steve", ownerKey = "u:7", productId = 3, orderId = 1, orderItemId = 1, startsAt = 1), pool)
        assertTrue(dao.end(id, EntitlementStatus.REVOKED, "REFUND", 777, pool))
        val read = dao.getById(id, pool)!!
        assertEquals(EntitlementStatus.REVOKED, read.status)
        assertEquals("REFUND", read.endReason)
        assertEquals(777L, read.endedAt)
        assertFalse(dao.end(9999, EntitlementStatus.EXPIRED, "EXPIRED", 1, pool))
    }

    @Test
    fun `the declared indexes exist`(): Unit = runBlocking {
        val names = sql(
            "SELECT DISTINCT INDEX_NAME FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pano_market_entitlement'"
        ).map { it.getString("INDEX_NAME") }.toSet()
        assertEquals(setOf("PRIMARY", "idx_owner_product", "idx_expiry", "idx_orderItem", "idx_subscription", "idx_tier"), names)
    }
}
