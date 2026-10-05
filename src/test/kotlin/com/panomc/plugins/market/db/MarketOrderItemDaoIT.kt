package com.panomc.plugins.market.db

import com.panomc.plugins.market.db.impl.MarketOrderItemDaoImpl
import com.panomc.plugins.market.db.model.MarketOrderItem
import com.panomc.plugins.market.db.model.OrderItemKind
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** `market_order_item` (01 section 5.2): the 25 columns of scheme version 5 round-trip and default as declared. */
class MarketOrderItemDaoIT : MarketDaoITBase() {
    private val dao = MarketOrderItemDaoImpl()

    private fun fullItem(orderId: Long = 1) = MarketOrderItem(
        orderId = orderId, productId = 9, productName = "VIP Rank", quantity = 3, unitPrice = 1000, createdAt = 10, updatedAt = 20,
        kind = OrderItemKind.BUNDLE_CHILD, parentItemId = 4, variantId = 5, variantName = "30 days", sku = "VIP-30",
        listUnitPrice = 1200, discountAmount = 100, upgradeAmount = 200, couponAmount = 300, vatPercent = 1800,
        vatAmount = 450, lineTotal = 2750, creditUnitPrice = 1000, creditAmount = 500, fieldValues = "{\"nick\":\"Steve\"}",
        targetServerId = 6, snapshot = "{\"slug\":\"vip\"}", physical = true, stockReserved = 3, refundedQuantity = 1,
        refundedAmount = 900, shippedQuantity = 2, gatewayItemRef = "pt-123", gatewayLineAmount = 2700, upgradeFromEntitlementId = 7
    )

    @Test
    fun `a full item round-trips every column`(): Unit = runBlocking {
        val written = fullItem()
        EntityRoundTrip.differsFromDefaults(written, MarketOrderItem())
        val id = dao.add(written, pool)
        val read = dao.getById(id, pool)!!
        assertEquals(id, read.id)
        EntityRoundTrip.assertSame(written, read)
        assertNull(dao.getById(9999, pool))
    }

    @Test
    fun `an item written with the version 2 columns only carries the defaults of the new columns`(): Unit = runBlocking {
        sql("INSERT INTO `pano_market_order_item` (`orderId`, `productId`, `productName`, `quantity`, `unitPrice`, `createdAt`, `updatedAt`) VALUES (1, NULL, 'Retired', 2, 750, 1, 2)")
        val read = dao.getById(sql("SELECT `id` FROM `pano_market_order_item`").single().getLong("id"), pool)!!
        EntityRoundTrip.assertSame(
            MarketOrderItem(orderId = 1, productId = null, productName = "Retired", quantity = 2, unitPrice = 750, createdAt = 1, updatedAt = 2),
            read
        )
        assertEquals(OrderItemKind.PRODUCT, read.kind)
        assertEquals(0L, read.lineTotal)
        assertFalse(read.physical)
    }

    @Test
    fun `items are found by order id and the stats queries still run`(): Unit = runBlocking {
        dao.add(fullItem(orderId = 1), pool)
        dao.add(fullItem(orderId = 2), pool)
        dao.add(fullItem(orderId = 3), pool)
        assertEquals(listOf(1L, 3L), dao.getByOrderIds(listOf(1, 3), pool).map { it.orderId })
        assertEquals(emptyList<MarketOrderItem>(), dao.getByOrderIds(emptyList(), pool))
        assertEquals(emptyList<Long>(), dao.topProductIds(5, pool)) // no COMPLETED order
    }
}
