package com.panomc.plugins.market.spi.payment

import com.panomc.plugins.market.spi.common.TestModeSupport
import com.panomc.plugins.market.spi.common.WebhookSetup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PaymentCapabilitiesTest {
    @Test
    fun `a fresh capabilities object is the conservative default of 02 section 5_1`() {
        val c = PaymentCapabilities()
        assertNull(c.currencies)
        assertEquals(RefundSupport.NONE, c.refund)
        assertEquals(RecurringSupport.NONE, c.recurring)
        assertNull(c.recurringCurrencies)
        assertNull(c.recurringIntervals)
        assertNull(c.recurringMaxCycles)
        assertNull(c.recurringMinIntervalDays)
        assertNull(c.recurringMaxIntervalDays)
        assertFalse(c.recurringResume)
        assertFalse(c.recurringRetry)
        assertFalse(c.recurringPortal)
        assertFalse(c.statusQuery)
        assertFalse(c.cancelPending)
        assertFalse(c.disputeEvents)
        assertFalse(c.buyerMayPayMore)
        assertEquals(PriceAuthority.MARKET, c.priceAuthority)
        assertEquals(FulfillmentAuthority.MARKET, c.fulfillment)
        assertEquals(TestModeSupport.FLAG, c.testMode)
        assertNull(c.derivedTestMode)
        assertEquals(emptySet<BuyerField>(), c.requiredBuyerFields)
        assertNull(c.minAmount)
        assertNull(c.maxAmount)
        assertTrue(c.physicalGoods)
        assertTrue(c.guests)
        assertTrue(c.mixedCredit)
        assertNull(c.paymentWindowMinutes)
        assertFalse(c.longPending)
        assertFalse(c.fulfillmentUpdates)
        assertEquals(WebhookSetup.PER_PAYMENT, c.webhookSetup)
        assertFalse(c.needsPublicUrl)
    }

    @Test
    fun `every optional capability is a settable var so a new one never changes the constructor`() {
        val c = PaymentCapabilities().apply {
            currencies = setOf("EUR", "TRY")
            refund = RefundSupport.PER_LINE
            recurring = RecurringSupport.GATEWAY_MANAGED
            recurringCurrencies = setOf("EUR")
            recurringIntervals = setOf(IntervalUnit.MONTH, IntervalUnit.YEAR)
            recurringMaxCycles = 121
            recurringMinIntervalDays = 3
            recurringMaxIntervalDays = 365
            recurringResume = true
            recurringRetry = true
            recurringPortal = true
            statusQuery = true
            cancelPending = true
            disputeEvents = true
            buyerMayPayMore = true
            priceAuthority = PriceAuthority.GATEWAY_CATALOG
            fulfillment = FulfillmentAuthority.GATEWAY
            testMode = TestModeSupport.DERIVED
            derivedTestMode = true
            requiredBuyerFields = setOf(BuyerField.EMAIL, BuyerField.IDENTITY_NUMBER)
            minAmount = PaymentTestData.eur(100)
            maxAmount = PaymentTestData.eur(100_000)
            physicalGoods = false
            guests = false
            mixedCredit = false
            paymentWindowMinutes = 30
            longPending = true
            fulfillmentUpdates = true
            webhookSetup = WebhookSetup.AUTO_REGISTER
            needsPublicUrl = true
        }
        assertEquals(setOf("EUR", "TRY"), c.currencies)
        assertEquals(RefundSupport.PER_LINE, c.refund)
        assertEquals(121, c.recurringMaxCycles)
        assertEquals(TestModeSupport.DERIVED, c.testMode)
        assertEquals(WebhookSetup.AUTO_REGISTER, c.webhookSetup)
        assertEquals(PaymentTestData.eur(100_000), c.maxAmount)
    }

    @Test
    fun `enums keep the documented values in order so a stored name never moves`() {
        assertEquals(listOf("NONE", "FULL_ONLY", "PARTIAL", "PER_LINE"), RefundSupport.values().map { it.name })
        assertEquals(listOf("NONE", "GATEWAY_MANAGED", "MERCHANT_INITIATED"), RecurringSupport.values().map { it.name })
        assertEquals(listOf("MARKET", "GATEWAY_ADDS_TAX", "GATEWAY_CATALOG"), PriceAuthority.values().map { it.name })
        assertEquals(listOf("MARKET", "GATEWAY"), FulfillmentAuthority.values().map { it.name })
        assertEquals(listOf("RETURN_PAGE", "RECONCILE", "PANEL"), QueryReason.values().map { it.name })
        assertEquals(listOf("DAY", "WEEK", "MONTH", "YEAR"), IntervalUnit.values().map { it.name })
        assertEquals(
            listOf("EMAIL", "FIRST_NAME", "LAST_NAME", "PHONE", "COUNTRY", "BILLING_ADDRESS", "SHIPPING_ADDRESS", "IDENTITY_NUMBER"),
            BuyerField.values().map { it.name }
        )
        assertEquals(listOf("FLAG", "DERIVED", "NONE"), TestModeSupport.values().map { it.name })
        assertEquals(listOf("PER_PAYMENT", "MANUAL_URL", "AUTO_REGISTER", "NONE"), WebhookSetup.values().map { it.name })
    }
}
