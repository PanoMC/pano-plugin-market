package com.panomc.plugins.market.db

import com.panomc.plugins.market.db.dao.isDuplicateKey
import com.panomc.plugins.market.db.impl.MarketOrderDaoImpl
import com.panomc.plugins.market.db.model.DisputeStatus
import com.panomc.plugins.market.db.model.FulfillmentBy
import com.panomc.plugins.market.db.model.FulfillmentStatus
import com.panomc.plugins.market.db.model.MarketOrder
import com.panomc.plugins.market.db.model.OrderSource
import com.panomc.plugins.market.db.model.PricingMode
import com.panomc.plugins.market.db.model.ReservationState
import com.panomc.plugins.market.db.model.ShippingStatus
import com.panomc.plugins.market.util.OrderStatus
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * `market_order` (01 section 5.1): every column of scheme version 5 round-trips, a row written the version 2 way
 * carries the column defaults, `uq_buyer_idem` and `uq_publicId` are proven by duplicate inserts, and the widened
 * status column holds the nine statuses of 00 section 7.1.
 */
class MarketOrderDaoIT : MarketDaoITBase() {
    private val dao = MarketOrderDaoImpl()

    private fun order(
        publicId: String? = "ABCDEFGHJKMNPQRSTVWX", buyerKey: String = "u:7", idempotencyKey: String? = null,
        status: OrderStatus = OrderStatus.PENDING
    ) = MarketOrder(
        playerUsername = "Steve", totalPrice = 1999, status = status, publicId = publicId, buyerKey = buyerKey,
        idempotencyKey = idempotencyKey, createdAt = 10, updatedAt = 20
    )

    private fun fullOrder() = MarketOrder(
        userId = 7, playerUsername = "Steve", totalPrice = 12345, currency = "EUR", paymentMethodId = "stripe",
        paymentLabel = "Card", status = OrderStatus.REVIEW, createdAt = 111, updatedAt = 222, exchangeRate = 32.5,
        publicId = "0123456789ABCDEFGHJK", accessToken = "a".repeat(40), source = OrderSource.GIFT_CODE, buyerKey = "u:7",
        idempotencyKey = "idem-1", idempotencyHash = "b".repeat(64), email = "steve@example.com", locale = "tr",
        clientIp = "203.0.113.9", userAgent = "JUnit", recipientUsername = "Alex", recipientUserId = 8,
        recipientKey = "u:8", isGift = true, giftMessage = "enjoy", hideFromBroadcast = true,
        reservationState = ReservationState.HELD, expiresAt = 333, baseCurrency = "USD", fxRate = BigDecimal("0.9212345678"),
        displayCurrency = "GBP", displayRate = BigDecimal("0.7812345678"), pricingMode = PricingMode.EXTERNAL_TAX,
        pricesIncludeVat = false, subtotal = 15000, discountTotal = 100, couponDiscount = 200, creatorDiscount = 300,
        upgradeDiscount = 400, shippingTotal = 500, shippingVatPercent = 1800, shippingVatAmount = 76, paymentFee = 60,
        paymentFeeVatPercent = 2000, paymentFeeVatAmount = 10, vatTotal = 1900, creditAmount = 700, creditValue = 650,
        gatewayAmount = 11695, paidAmount = 11700, refundedTotal = 800, refundedGatewayAmount = 750, refundedCreditAmount = 50,
        couponId = 11, creatorCodeId = 12, giftId = 13, couponCode = "SAVE10", creatorCode = "STEVE", paymentId = 14,
        paidAt = 444, testMode = true, statusBeforeDispute = OrderStatus.PARTIALLY_REFUNDED, disputeStatus = DisputeStatus.OPEN,
        reviewReason = "UNDERPAID", fulfillmentStatus = FulfillmentStatus.PARTIAL, fulfillmentBy = FulfillmentBy.GATEWAY,
        requiresShipping = true, shippingStatus = ShippingStatus.SHIPPED, shippingAddress = "{\"city\":\"Izmir\"}",
        shippingMethodId = 15, shippingMethodName = "Express", shippingQuote = "{\"price\":500}", shippingWeightGrams = 1250,
        billingInfo = "{\"type\":\"COMPANY\"}", legalTextId = 16, legalAcceptedAt = 555, subscriptionId = 17, invoiceId = 18,
        note = "admin note", createdBy = 19
    )

    @Test
    fun `a full order round-trips every column`(): Unit = runBlocking {
        val written = fullOrder()
        EntityRoundTrip.differsFromDefaults(written, MarketOrder())
        val id = dao.add(written, pool)
        val read = dao.getById(id, pool)!!
        assertEquals(id, read.id)
        EntityRoundTrip.assertSame(written, read)
    }

    @Test
    fun `an order written with the version 2 columns only carries the defaults of the new columns`(): Unit = runBlocking {
        sql(
            "INSERT INTO `pano_market_order` (`userId`, `playerUsername`, `totalPrice`, `currency`, `paymentMethodId`, `paymentLabel`, `status`, `createdAt`, `updatedAt`) " +
                "VALUES (5, 'Old', 500, 'TRY', 'paytr', 'PayTR', 'COMPLETED', 1, 2)"
        )
        val read = dao.getById(sql("SELECT `id` FROM `pano_market_order`").single().getLong("id"), pool)!!
        EntityRoundTrip.assertSame(
            MarketOrder(userId = 5, playerUsername = "Old", totalPrice = 500, paymentMethodId = "paytr", paymentLabel = "PayTR", status = OrderStatus.COMPLETED, createdAt = 1, updatedAt = 2),
            read
        )
        assertNull(read.publicId)
        assertEquals(OrderSource.STOREFRONT, read.source)
        assertEquals("", read.buyerKey)
        assertEquals(0, BigDecimal.ONE.compareTo(read.fxRate))
        assertTrue(read.pricesIncludeVat)
    }

    @Test
    fun `a duplicate of buyerKey and idempotencyKey is answered with null and rejected by the unique key`(): Unit = runBlocking {
        val first = dao.tryAdd(order(publicId = "A".repeat(20), idempotencyKey = "k1"), pool)!!
        assertNull(dao.tryAdd(order(publicId = "B".repeat(20), idempotencyKey = "k1"), pool))
        assertEquals(1L, count("market_order"))
        assertEquals(first, dao.getByBuyerAndIdempotencyKey("u:7", "k1", pool)!!.id)

        val failure = runCatching { dao.add(order(publicId = "C".repeat(20), idempotencyKey = "k1"), pool) }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure!!.isDuplicateKey())
        val raw = runCatching {
            sql("INSERT INTO `pano_market_order` (`playerUsername`, `totalPrice`, `buyerKey`, `idempotencyKey`, `createdAt`, `updatedAt`) VALUES ('x', 1, 'u:7', 'k1', 1, 1)")
        }.exceptionOrNull()
        assertTrue(raw != null && raw.isDuplicateKey())

        // another buyer with the same key, or the same buyer with another key, is another order
        assertNotNull(dao.tryAdd(order(publicId = "D".repeat(20), buyerKey = "u:8", idempotencyKey = "k1"), pool))
        assertNotNull(dao.tryAdd(order(publicId = "E".repeat(20), idempotencyKey = "k2"), pool))
        assertEquals(3L, count("market_order"))
        assertNull(dao.getByBuyerAndIdempotencyKey("u:7", "missing", pool))
    }

    @Test
    fun `orders without an idempotency key never collide`(): Unit = runBlocking {
        assertNotNull(dao.tryAdd(order(publicId = "A".repeat(20), idempotencyKey = null), pool))
        assertNotNull(dao.tryAdd(order(publicId = "B".repeat(20), idempotencyKey = null), pool))
        // the legacy shape: no public id and an empty buyer key, several rows
        assertNotNull(dao.tryAdd(order(publicId = null, buyerKey = ""), pool))
        assertNotNull(dao.tryAdd(order(publicId = null, buyerKey = ""), pool))
        assertEquals(4L, count("market_order"))
    }

    @Test
    fun `a duplicate public id is answered with null and found by getByPublicId`(): Unit = runBlocking {
        val first = dao.tryAdd(order(publicId = "Z".repeat(20), buyerKey = "u:1", idempotencyKey = "a"), pool)!!
        assertNull(dao.tryAdd(order(publicId = "Z".repeat(20), buyerKey = "u:2", idempotencyKey = "b"), pool))
        assertEquals(first, dao.getByPublicId("Z".repeat(20), pool)!!.id)
        assertNull(dao.getByPublicId("Y".repeat(20), pool))
        val raw = runCatching {
            sql("INSERT INTO `pano_market_order` (`playerUsername`, `totalPrice`, `publicId`, `createdAt`, `updatedAt`) VALUES ('x', 1, ?, 1, 1)", "Z".repeat(20))
        }.exceptionOrNull()
        assertTrue(raw != null && raw.isDuplicateKey())
    }

    @Test
    fun `the status column is wide enough for every status of the state machine`(): Unit = runBlocking {
        assertEquals(24L, sql(
            "SELECT CHARACTER_MAXIMUM_LENGTH AS l FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pano_market_order' AND COLUMN_NAME = 'status'"
        ).single().getLong("l"))
        assertEquals(9, OrderStatus.entries.size)
        for ((index, status) in OrderStatus.entries.withIndex()) {
            val id = dao.add(order(publicId = "P${index.toString().padStart(19, '0')}", idempotencyKey = "s$index", status = status), pool)
            assertEquals(status, dao.getById(id, pool)!!.status)
        }
        assertEquals(1L, dao.count(null, OrderStatus.PARTIALLY_REFUNDED, pool))
        assertEquals(1L, dao.count(null, OrderStatus.CHARGEBACK, pool))
        assertEquals(1, dao.getAllPaged(1, null, OrderStatus.REVIEW, pool).size)
        val id = dao.getAllPaged(1, null, OrderStatus.PENDING, pool).single().id
        dao.updateStatus(id, OrderStatus.EXPIRED, pool)
        assertEquals(OrderStatus.EXPIRED, dao.getById(id, pool)!!.status)
        assertFalse(dao.getAllPaged(1, null, OrderStatus.PENDING, pool).any { it.id == id })
    }
}
