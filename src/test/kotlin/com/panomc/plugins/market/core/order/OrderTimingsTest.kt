package com.panomc.plugins.market.core.order

import com.panomc.plugins.market.core.order.OrderTimings.DAY_MS
import com.panomc.plugins.market.core.order.OrderTimings.HOUR_MS
import com.panomc.plugins.market.core.order.OrderTimings.MINUTE_MS
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** `OrderTimings` (06 section 9.1): windows, hard cap, attempt expiry, retry rule, processing grace. */
class OrderTimingsTest {
    private val cfg = TimingConfig(orderExpiryMinutes = 60, bankTransferExpiryHours = 72)
    private val t0 = 1_700_000_000_000L

    @Test
    fun `provider window per provider kind`() {
        assertEquals(72 * HOUR_MS, OrderTimings.providerWindowMs("bank-transfer", null, cfg))
        assertEquals(72 * HOUR_MS, OrderTimings.providerWindowMs("bank-transfer", 15, cfg), "the store setting wins for bank transfer")
        assertEquals(60 * MINUTE_MS, OrderTimings.providerWindowMs("free", null, cfg))
        assertEquals(60 * MINUTE_MS, OrderTimings.providerWindowMs("credits", 15, cfg), "built-ins use the store window")
        assertEquals(15 * MINUTE_MS, OrderTimings.providerWindowMs("stripe", 15, cfg))
        assertEquals(60 * MINUTE_MS, OrderTimings.providerWindowMs("stripe", null, cfg))
    }

    @Test
    fun `order expiry at creation uses the first provider, a manual pending order the bank transfer window`() {
        assertEquals(t0 + 15 * MINUTE_MS, OrderTimings.orderExpiresAtOnCreate(t0, "stripe", 15, cfg))
        assertEquals(t0 + 60 * MINUTE_MS, OrderTimings.orderExpiresAtOnCreate(t0, "credits", null, cfg))
        assertEquals(t0 + 72 * HOUR_MS, OrderTimings.orderExpiresAtOnCreate(t0, "stripe", 15, cfg, manualPending = true))
    }

    @Test
    fun `hard cap is created plus the larger of 24 hours and the provider window`() {
        assertEquals(t0 + DAY_MS, OrderTimings.hardCap(t0, 15 * MINUTE_MS))
        assertEquals(t0 + DAY_MS, OrderTimings.hardCap(t0, DAY_MS))
        assertEquals(t0 + 72 * HOUR_MS, OrderTimings.hardCap(t0, 72 * HOUR_MS))
    }

    @Test
    fun `attempt expiry is the window capped by the hard cap`() {
        val h = OrderTimings.hardCap(t0, 15 * MINUTE_MS)
        assertEquals(t0 + 15 * MINUTE_MS, OrderTimings.attemptExpiresAt(t0, 15 * MINUTE_MS, h))
        // 23 h into the order: only one hour of the cap is left.
        val late = t0 + 23 * HOUR_MS
        assertEquals(h, OrderTimings.attemptExpiresAt(late, 2 * HOUR_MS, h))
        assertEquals(late + 30 * MINUTE_MS, OrderTimings.attemptExpiresAt(late, 30 * MINUTE_MS, h))
    }

    @Test
    fun `a provider given expiry replaces the window and is clamped to one minute through thirty days`() {
        val h = OrderTimings.hardCap(t0, 15 * MINUTE_MS)
        assertEquals(t0 + 2 * HOUR_MS, OrderTimings.attemptExpiresAt(t0, 15 * MINUTE_MS, h, t0 + 2 * HOUR_MS))
        assertEquals(t0 + MINUTE_MS, OrderTimings.attemptExpiresAt(t0, 15 * MINUTE_MS, h, t0 - 5000))
        assertEquals(t0 + MINUTE_MS, OrderTimings.attemptExpiresAt(t0, 15 * MINUTE_MS, h, t0 + 10_000))
        assertEquals(t0 + 30 * DAY_MS, OrderTimings.attemptExpiresAt(t0, 15 * MINUTE_MS, h, t0 + 90 * DAY_MS))
    }

    @Test
    fun `order expiry after a new attempt only ever grows`() {
        assertEquals(t0 + 100, OrderTimings.orderExpiresAtAfterAttempt(t0 + 100, t0 + 50))
        assertEquals(t0 + 200, OrderTimings.orderExpiresAtAfterAttempt(t0 + 100, t0 + 200))
        assertEquals(t0 + 200, OrderTimings.orderExpiresAtAfterAttempt(null, t0 + 200))
    }

    @Test
    fun `a retry is refused when fewer than five minutes remain before the hard cap`() {
        val h = t0 + DAY_MS
        assertTrue(OrderTimings.retryAllowed(h - 5 * MINUTE_MS, h))
        assertFalse(OrderTimings.retryAllowed(h - 5 * MINUTE_MS + 1, h))
        assertFalse(OrderTimings.retryAllowed(h, h))
        assertFalse(OrderTimings.retryAllowed(h + 1, h))
        assertTrue(OrderTimings.retryAllowed(t0, h))
    }

    @Test
    fun `processing grace is 24 hours, 14 days for longPending and none for a buyer notice`() {
        assertEquals(DAY_MS, OrderTimings.processingGraceMs(longPending = false, buyerNotice = false))
        assertEquals(14 * DAY_MS, OrderTimings.processingGraceMs(longPending = true, buyerNotice = false))
        assertEquals(0L, OrderTimings.processingGraceMs(longPending = false, buyerNotice = true))
        assertEquals(0L, OrderTimings.processingGraceMs(longPending = true, buyerNotice = true))
    }

    @Test
    fun `a PROCESSING attempt expires once expiresAt plus its grace has passed`() {
        val exp = t0
        assertFalse(OrderTimings.processingExpired(exp + DAY_MS - 1, exp, longPending = false, buyerNotice = false))
        assertTrue(OrderTimings.processingExpired(exp + DAY_MS, exp, longPending = false, buyerNotice = false))
        assertFalse(OrderTimings.processingExpired(exp + 14 * DAY_MS - 1, exp, longPending = true, buyerNotice = false))
        assertTrue(OrderTimings.processingExpired(exp + 14 * DAY_MS, exp, longPending = true, buyerNotice = false))
        assertTrue(OrderTimings.processingExpired(exp, exp, longPending = true, buyerNotice = true))
        assertFalse(OrderTimings.processingExpired(exp - 1, exp, longPending = true, buyerNotice = true))
    }

    @Test
    fun `a CREATED attempt younger than two minutes may still have its start call in flight`() {
        assertFalse(OrderTimings.createdAttemptSettled(t0 + 2 * MINUTE_MS - 1, t0))
        assertTrue(OrderTimings.createdAttemptSettled(t0 + 2 * MINUTE_MS, t0))
    }
}
