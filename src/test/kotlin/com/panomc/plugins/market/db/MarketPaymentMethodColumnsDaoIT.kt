package com.panomc.plugins.market.db

import com.panomc.plugins.market.db.impl.MarketPaymentMethodDaoImpl
import com.panomc.plugins.market.db.model.PaymentFeeMode
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The 14 columns `market_payment_method` gained with scheme version 6 (01 section 6.1). */
class MarketPaymentMethodColumnsDaoIT : MarketDaoITBase() {
    private val dao = MarketPaymentMethodDaoImpl()

    @Test
    fun `a row written by the old upsert reads back with the new column defaults`(): Unit = runBlocking {
        dao.upsertByMethodId("stripe", true, "{\"a\":1}", pool)
        val row = dao.getByMethodId("stripe", pool)!!
        assertTrue(row.enabled)
        assertEquals("{\"a\":1}", row.settings)
        assertEquals(0, row.position)
        assertNull(row.customLabel)
        assertNull(row.customDescription)
        assertEquals(PaymentFeeMode.NONE, row.feeMode)
        assertEquals(0L, row.feePercent)
        assertEquals(0L, row.feeFixed)
        assertNull(row.minAmount)
        assertNull(row.maxAmount)
        assertNull(row.currencies)
        assertFalse(row.testMode)
        assertNull(row.lastInboundAt)
        assertNull(row.lastError)
        assertNull(row.lastErrorAt)
        assertNull(row.settingsUpdatedAt)
    }

    @Test
    fun `every new column round-trips through the entity`(): Unit = runBlocking {
        dao.upsertByMethodId("paypal", false, "{}", pool)
        sql(
            "UPDATE `pano_market_payment_method` SET `position` = 3, `customLabel` = 'Pay by card', `customDescription` = 'Visa, Mastercard', `feeMode` = 'BUYER', " +
                "`feePercent` = 290, `feeFixed` = 30, `minAmount` = 100, `maxAmount` = 500000, `currencies` = '[\"EUR\",\"USD\"]', `testMode` = 1, " +
                "`lastInboundAt` = 111, `lastError` = 'timeout', `lastErrorAt` = 222, `settingsUpdatedAt` = 333 WHERE `methodId` = 'paypal'"
        )
        val row = dao.getByMethodId("paypal", pool)!!
        assertEquals(3, row.position)
        assertEquals("Pay by card", row.customLabel)
        assertEquals("Visa, Mastercard", row.customDescription)
        assertEquals(PaymentFeeMode.BUYER, row.feeMode)
        assertEquals(290L, row.feePercent)
        assertEquals(30L, row.feeFixed)
        assertEquals(100L, row.minAmount)
        assertEquals(500000L, row.maxAmount)
        assertEquals("[\"EUR\",\"USD\"]", row.currencies)
        assertTrue(row.testMode)
        assertEquals(111L, row.lastInboundAt)
        assertEquals("timeout", row.lastError)
        assertEquals(222L, row.lastErrorAt)
        assertEquals(333L, row.settingsUpdatedAt)
    }

    @Test
    fun `the upsert keeps the new columns of an existing row`(): Unit = runBlocking {
        dao.upsertByMethodId("iyzico", false, "{}", pool)
        sql("UPDATE `pano_market_payment_method` SET `customLabel` = 'Kart', `feeMode` = 'BUYER', `feePercent` = 150 WHERE `methodId` = 'iyzico'")
        dao.upsertByMethodId("iyzico", true, "{\"k\":2}", pool)
        val row = dao.getByMethodId("iyzico", pool)!!
        assertTrue(row.enabled)
        assertEquals("Kart", row.customLabel)
        assertEquals(PaymentFeeMode.BUYER, row.feeMode)
        assertEquals(150L, row.feePercent)
        assertEquals(1, dao.getAll(pool).count { it.methodId == "iyzico" })
    }
}
