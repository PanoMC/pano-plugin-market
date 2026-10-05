package com.panomc.plugins.market.core.cart

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** `CartMerger` (06 section 2.2 PUT, section 2.3 merge on login). */
class CartMergerTest {
    private fun line(productId: Long, quantity: Int = 1, vararg values: Pair<String, Any>, variantId: Long = 0, target: Long? = null) =
        CartLine(productId, variantId, quantity, mapOf(*values), target)

    @Test
    fun `identical lines are summed and keep the first position`() {
        val merged = CartMerger.sumByKey(listOf(line(1, 2), line(2, 1), line(1, 3), line(2, 4, "a" to "x")))

        assertEquals(listOf(1L to 5, 2L to 1, 2L to 4), merged.map { it.productId to it.quantity })
    }

    @Test
    fun `sums are capped at 999`() {
        assertEquals(999, CartMerger.sumByKey(listOf(line(1, 600), line(1, 600))).single().quantity)
        assertEquals(999, CartMerger.sumByKey(listOf(line(1, Int.MAX_VALUE), line(1, Int.MAX_VALUE))).single().quantity)
        assertEquals(1, CartMerger.sumByKey(listOf(line(1, 0))).single().quantity)
    }

    @Test
    fun `field order does not split a line`() {
        val a = CartLine(1, 0, 1, linkedMapOf("a" to "1", "b" to "2"))
        val b = CartLine(1, 0, 2, linkedMapOf("b" to "2", "a" to "1"))

        assertEquals(3, CartMerger.sumByKey(listOf(a, b)).single().quantity)
    }

    @Test
    fun `browser normalisation keeps at most 50 lines`() {
        val lines = (1L..60L).map { line(it) }

        assertEquals((1L..50L).toList(), CartMerger.normalizeBrowser(lines).map { it.productId })
        // equal lines count once
        assertEquals(1, CartMerger.normalizeBrowser(List(80) { line(9) }).size)
    }

    @Test
    fun `an equal server line takes the larger quantity, not the sum`() {
        val server = mapOf(line(1, 3).lineKey to 3, line(2, 5).lineKey to 5)
        val plan = CartMerger.planLogin(server, listOf(line(1, 2), line(2, 9)))

        assertEquals(mapOf(line(2).lineKey to 9), plan.updates)
        assertTrue(plan.inserts.isEmpty())
        assertTrue(plan.overflow.isEmpty())
    }

    @Test
    fun `the merge is idempotent`() {
        val server = mapOf(line(1, 3).lineKey to 3)
        val first = CartMerger.planLogin(server, listOf(line(1, 3), line(2, 1)))
        val after = server + (line(2).lineKey to 1)
        val second = CartMerger.planLogin(after, listOf(line(1, 3), line(2, 1)))

        assertEquals(listOf(2L), first.inserts.map { it.productId })
        assertTrue(second.updates.isEmpty())
        assertTrue(second.inserts.isEmpty())
    }

    @Test
    fun `new lines are inserted while the cart has fewer than 50 lines and the rest overflows`() {
        val server = (1L..48L).associate { line(it).lineKey to 1 }
        val plan = CartMerger.planLogin(server, listOf(line(100), line(101), line(102), line(1, 4)))

        assertEquals(listOf(100L, 101L), plan.inserts.map { it.productId })
        assertEquals(listOf(102L), plan.overflow.map { it.productId })
        // an existing line still updates when the cart is full
        assertEquals(mapOf(line(1).lineKey to 4), plan.updates)
        assertEquals(listOf(CartMessage("MAX_QUANTITY", "warning", line(102).lineKey)), plan.messages)
    }

    @Test
    fun `a full cart takes no new line`() {
        val server = (1L..50L).associate { line(it).lineKey to 1 }
        val plan = CartMerger.planLogin(server, listOf(line(77)))

        assertTrue(plan.inserts.isEmpty())
        assertEquals(1, plan.overflow.size)
    }

    @Test
    fun `an empty browser cart changes nothing`() {
        val plan = CartMerger.planLogin(mapOf(line(1).lineKey to 2), emptyList())

        assertTrue(plan.updates.isEmpty() && plan.inserts.isEmpty() && plan.overflow.isEmpty() && plan.messages.isEmpty())
    }
}
