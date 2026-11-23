package com.panomc.plugins.market.service

import com.panomc.plugins.market.spi.common.Money
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RefundLineSharesTest {
    @Test
    fun `the gateway part of a mixed JPY refund is spread over its lines in whole yen, so every line is a valid amount`() {
        // three 1000 JPY lines, 1000 JPY of the refund went back as credits: 2000 JPY over three lines
        val shares = PaymentService.refundLineShares(200_000, listOf(100_000, 100_000, 100_000), "JPY")

        assertEquals(200_000, shares.sum())
        shares.forEach { assertEquals(0L, it % 100, "$it is not a whole yen") }
        shares.forEach { Money(it, "JPY") }
    }

    @Test
    fun `a JPY amount that is not a whole yen is sent as an amount only`() {
        assertEquals(listOf(0L, 0L), PaymentService.refundLineShares(150_050, listOf(100_000, 100_000), "JPY"))
    }

    @Test
    fun `a two-decimal currency still splits by the cent`() {
        val shares = PaymentService.refundLineShares(200, listOf(100, 100, 100), "EUR")

        assertEquals(listOf(67L, 67L, 66L), shares)
    }
}
