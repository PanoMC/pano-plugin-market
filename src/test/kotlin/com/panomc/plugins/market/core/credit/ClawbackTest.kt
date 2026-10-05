package com.panomc.plugins.market.core.credit

import com.panomc.plugins.market.core.credit.Clawback.Basis
import com.panomc.plugins.market.core.credit.Clawback.Item
import com.panomc.plugins.market.core.credit.Clawback.RefundItem
import com.panomc.plugins.market.core.credit.Clawback.Request
import com.panomc.plugins.market.core.credit.CreditPolicy.Taken
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * 07 section 19.6, clawback half (U-K1 .. U-K3), the rules of 07 section 8.5, and the posting policies of section 3.1
 * (`CreditPolicy`). Figures are credits x 100: 30000 is 300.00 credits.
 */
class ClawbackTest {
    private fun item(id: Long, granted: Long, quantity: Int = 1, lineTotal: Long = 10_000, already: Long = 0) =
        Item(id, granted, already, quantity, lineTotal)

    private fun items(vararg refundItems: RefundItem) = Basis.Items(refundItems.toList())

    // ---------------------------------------------------------------- 19.6 clawback

    @Test
    fun `U-K1 refunding one unit of a pack of three takes back a third of the credits`() {
        val pack = item(1, granted = 30_000, quantity = 3, lineTotal = 30_000)
        assertEquals(listOf(Request(1, 10_000)), Clawback.compute(listOf(pack), items(RefundItem(1, quantity = 1, amount = 10_000))))
        assertEquals(listOf(Request(1, 20_000)), Clawback.compute(listOf(pack), items(RefundItem(1, quantity = 2, amount = 20_000))))
        // the quantity decides, not the amount
        assertEquals(listOf(Request(1, 10_000)), Clawback.compute(listOf(pack), items(RefundItem(1, quantity = 1, amount = 1))))
        // a quantity above the item's is capped at the whole grant
        assertEquals(listOf(Request(1, 30_000)), Clawback.compute(listOf(pack), items(RefundItem(1, quantity = 9, amount = 0))))
        // floor: 100 credits over 3 units is 33.33 per unit
        assertEquals(listOf(Request(1, 3_333)), Clawback.compute(listOf(item(1, 10_000, quantity = 3)), items(RefundItem(1, 1, 0))))
    }

    @Test
    fun `U-K2 a refund by amount takes the share of the line a refund without items the share of the order and a full refund the remainder`() {
        // a top-up line of 100.00 that granted 110.00 credits
        val topUp = item(7, granted = 11_000, quantity = 1, lineTotal = 10_000)
        // refund item with quantity 0 and an amount of 50.00: half of the line
        assertEquals(listOf(Request(7, 5_500)), Clawback.compute(listOf(topUp), items(RefundItem(7, quantity = 0, amount = 5_000))))
        // a refund without items: its amount against the order total
        assertEquals(listOf(Request(7, 2_750)), Clawback.compute(listOf(topUp), Basis.Amount(refundAmount = 2_500, orderTotal = 10_000)))
        // the order becomes REFUNDED: the rest of the grant, whatever the earlier refunds took
        val after = item(7, granted = 11_000, already = 5_500)
        assertEquals(listOf(Request(7, 5_500)), Clawback.compute(listOf(after), Basis.Everything))
        // a chargeback is the same rule
        assertEquals(listOf(Request(7, 11_000)), Clawback.compute(listOf(topUp), Basis.Everything))
    }

    @Test
    fun `U-K3 repeated refunds never request more than was granted`() {
        val pack = item(1, granted = 30_000, quantity = 3, lineTotal = 30_000)
        var already = 0L
        val requests = ArrayList<Long>()
        repeat(6) { // six one-unit refund items against a pack of three (the refund guards prevent it, the arithmetic must not rely on that)
            val r = Clawback.compute(listOf(item(1, 30_000, 3, 30_000, already)), items(RefundItem(1, 1, 10_000)))
            val amount = r.singleOrNull()?.amount ?: 0L
            requests += amount
            already += amount
        }
        assertEquals(listOf(10_000L, 10_000L, 10_000L, 0L, 0L, 0L), requests)
        assertEquals(30_000L, already)
        // by amount: three 40.00 refunds of a 100.00 line, the third is capped
        var taken = 0L
        val byAmount = ArrayList<Long>()
        repeat(3) {
            val r = Clawback.compute(listOf(item(7, 11_000, 1, 10_000, taken)), items(RefundItem(7, 0, 4_000)))
            val amount = r.singleOrNull()?.amount ?: 0L
            byAmount += amount
            taken += amount
        }
        assertEquals(listOf(4_400L, 4_400L, 2_200L), byAmount)
        assertEquals(11_000L, taken)
    }

    // ---------------------------------------------------------------- the rest of 07 section 8.5

    @Test
    fun `only the credit-granting items a refund names are clawed back`() {
        val a = item(1, granted = 10_000, quantity = 2, lineTotal = 20_000)
        val b = item(2, granted = 5_000, quantity = 1, lineTotal = 5_000)
        // the refund names item 2 only
        assertEquals(listOf(Request(2, 5_000)), Clawback.compute(listOf(a, b), items(RefundItem(2, 1, 5_000))))
        // it names an order item that grants nothing: no clawback at all
        assertEquals(emptyList<Request>(), Clawback.compute(listOf(a, b), items(RefundItem(99, 1, 5_000))))
        // two refund rows of one item add up
        assertEquals(listOf(Request(1, 10_000)), Clawback.compute(listOf(a), items(RefundItem(1, 1, 0), RefundItem(1, 1, 0))))
        // the amount of a refund without items applies to every credit-granting item
        assertEquals(listOf(Request(1, 5_000), Request(2, 2_500)), Clawback.compute(listOf(a, b), Basis.Amount(12_500, 25_000)))
    }

    @Test
    fun `an item with nothing granted or nothing left is skipped and zero lines never divide`() {
        assertEquals(emptyList<Request>(), Clawback.compute(listOf(item(1, 0)), Basis.Everything))
        assertEquals(emptyList<Request>(), Clawback.compute(listOf(item(1, 5_000, already = 5_000)), Basis.Everything))
        assertEquals(emptyList<Request>(), Clawback.compute(listOf(item(1, 5_000, already = 9_000)), Basis.Everything), "more taken than granted (a bug elsewhere) is nothing, not negative")
        assertEquals(emptyList<Request>(), Clawback.compute(emptyList(), Basis.Everything))
        // a free line (line total 0) cannot be measured by an amount; a free order has no amount to measure by
        assertEquals(emptyList<Request>(), Clawback.compute(listOf(item(1, 5_000, lineTotal = 0)), items(RefundItem(1, 0, 100))))
        assertEquals(emptyList<Request>(), Clawback.compute(listOf(item(1, 5_000)), Basis.Amount(100, 0)))
        // a refund of more than the line or order is the whole grant
        assertEquals(listOf(Request(1, 5_000)), Clawback.compute(listOf(item(1, 5_000)), items(RefundItem(1, 0, 99_999))))
        assertEquals(listOf(Request(1, 5_000)), Clawback.compute(listOf(item(1, 5_000)), Basis.Amount(99_999, 10_000)))
        assertThrows(IllegalArgumentException::class.java) { Item(1, -1, 0, 1, 0) }
        assertThrows(IllegalArgumentException::class.java) { Item(1, 1, 0, 0, 0) }
        assertThrows(IllegalArgumentException::class.java) { RefundItem(1, -1, 0) }
        assertThrows(IllegalArgumentException::class.java) { Basis.Amount(-1, 10) }
    }

    @Test
    fun `no overflow with the largest grants`() {
        val big = item(1, granted = 9_000_000_000_000_000_000L, quantity = 100_000, lineTotal = Long.MAX_VALUE)
        assertEquals(listOf(Request(1, 90_000_000_000_000_000L)), Clawback.compute(listOf(big), items(RefundItem(1, 1_000, 0))))
        assertEquals(
            listOf(Request(1, 5_000_000_000_000_000_000L)),
            Clawback.compute(listOf(big), Basis.Amount(refundAmount = 5_000_000_000_000_000_000L, orderTotal = 9_000_000_000_000_000_000L))
        )
    }

    @Test
    fun `a random history of refunds never takes back more than was granted and the last one takes the rest`() {
        val rnd = Random(20261109)
        repeat(4_000) { n ->
            val count = rnd.nextInt(1, 4)
            val granted = LongArray(count) { rnd.nextLong(1, 1_000_000_000) }
            val quantity = IntArray(count) { rnd.nextInt(1, 20) }
            val lineTotal = LongArray(count) { rnd.nextLong(0, 100_000_000) }
            val already = LongArray(count)
            val refundedQuantity = IntArray(count)
            val steps = rnd.nextInt(1, 8)
            repeat(steps) { step ->
                val list = (0 until count).map { Item(it.toLong(), granted[it], already[it], quantity[it], lineTotal[it]) }
                val basis: Basis = when (rnd.nextInt(3)) {
                    0 -> Basis.Items((0 until count).filter { rnd.nextBoolean() }.map {
                        val q = rnd.nextInt(0, quantity[it] - refundedQuantity[it] + 1)
                        refundedQuantity[it] += q
                        RefundItem(it.toLong(), q, if (q == 0) rnd.nextLong(0, lineTotal[it] + 1) else 0)
                    })
                    1 -> Basis.Amount(rnd.nextLong(0, 200_000_000), rnd.nextLong(1, 200_000_000))
                    else -> Basis.Everything
                }
                val where = "#$n step $step"
                val requests = Clawback.compute(list, basis)
                assertEquals(requests.map { it.itemId }.distinct(), requests.map { it.itemId }, "one request per item $where")
                for (r in requests) {
                    val i = r.itemId.toInt()
                    assertTrue(r.amount > 0, where)
                    assertTrue(r.amount <= granted[i] - already[i], "never more than is untaken: $where")
                    already[i] += r.amount // a shortfall counts as taken: amount + shortfall is the request
                    assertTrue(already[i] <= granted[i], "invariant O8 $where")
                }
                if (basis is Basis.Everything) {
                    for (i in 0 until count) assertEquals(granted[i], already[i], "the order is refunded: everything is taken back $where")
                }
            }
            // whatever happened before, the final rule takes the rest
            val rest = Clawback.compute((0 until count).map { Item(it.toLong(), granted[it], already[it], quantity[it], lineTotal[it]) }, Basis.Everything)
            for (r in rest) already[r.itemId.toInt()] += r.amount
            for (i in 0 until count) assertEquals(granted[i], already[i], "#$n item $i")
        }
    }

    // ---------------------------------------------------------------- the posting policies of 07 section 3.1

    @Test
    fun `TAKE_AVAILABLE takes what the balance has and records the rest`() {
        assertEquals(Taken(30, 70), CreditPolicy.takeAvailable(100, 30))
        assertEquals(Taken(100, 0), CreditPolicy.takeAvailable(100, 100))
        assertEquals(Taken(100, 0), CreditPolicy.takeAvailable(100, 5_000))
        assertEquals(Taken(0, 100), CreditPolicy.takeAvailable(100, 0))
        assertEquals(Taken(0, 100), CreditPolicy.takeAvailable(100, -40), "a debt gives nothing and never deepens")
        assertEquals(Taken(0, 0), CreditPolicy.takeAvailable(0, 50))
        assertThrows(IllegalArgumentException::class.java) { CreditPolicy.takeAvailable(-1, 5) }
        // the sum is always the request
        val rnd = Random(20261110)
        repeat(2_000) {
            val requested = rnd.nextLong(0, 1_000_000)
            val balance = rnd.nextLong(-1_000_000, 1_000_000)
            val t = CreditPolicy.takeAvailable(requested, balance)
            assertEquals(requested, t.taken + t.shortfall)
            assertTrue(t.taken in 0..maxOf(0L, balance).coerceAtLeast(0))
            assertTrue(balance - t.taken >= minOf(balance, 0L), "the balance does not go below zero through it")
        }
    }

    @Test
    fun `ALLOW_DEBT takes the whole amount and FAIL needs the balance`() {
        assertEquals(Taken(100, 0), CreditPolicy.allowDebt(100))
        assertThrows(IllegalArgumentException::class.java) { CreditPolicy.allowDebt(-1) }
        assertTrue(CreditPolicy.covers(100, 100))
        assertFalse(CreditPolicy.covers(100, 99))
        assertTrue(CreditPolicy.covers(0, 0))
        assertFalse(CreditPolicy.covers(1, -5), "a debt covers nothing")
        assertFalse(CreditPolicy.covers(0, -5), "a negative balance does not even cover nothing")
    }
}
