package com.panomc.plugins.market.core.refund

import com.panomc.plugins.market.core.credit.RefundSplit
import com.panomc.plugins.market.core.refund.RefundMath.Line
import com.panomc.plugins.market.core.refund.RefundMath.OrderAmounts
import com.panomc.plugins.market.core.refund.RefundMath.RefundAmounts
import com.panomc.plugins.market.core.refund.RefundMath.Remaining
import com.panomc.plugins.market.db.model.RefundStatus
import com.panomc.plugins.market.spi.payment.RefundSupport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Random

/** `RefundMath` (21 sections 2, 3.1, 3.4; 07 section 7.2): remaining amounts, booking, item amounts and the per-line spread (RD-U1). */
class RefundMathTest {
    private fun row(id: Long, status: RefundStatus, amount: Long, gateway: Long, credit: Long) =
        RefundAmounts(id, status, amount, gateway, credit)

    /** T = 100.00 (G = 60.00 + CV = 40.00) paid with 40.00 credits (CA = 40.00 credits x 100 = 4000). */
    private val mixed = OrderAmounts(totalPrice = 10000, gatewayAmount = 6000, creditValue = 4000, creditAmount = 4000)

    private fun limitsOf(r: RefundSplit.Result): RefundSplit.Limits = when (r) {
        is RefundSplit.Result.Ok -> r.limits
        is RefundSplit.Result.Invalid -> r.limits
        is RefundSplit.Result.NotSupported -> r.limits
    }

    private fun asLimits(r: Remaining) = RefundSplit.Limits(r.total, r.gateway, r.creditValue, r.credit)

    // ---------------------------------------------------------------- RD-U1: the remainder

    @Test
    fun `a fresh order can refund everything`() {
        assertEquals(Remaining(10000, 6000, 4000, 4000), RefundMath.remaining(mixed, emptyList()))
    }

    @Test
    fun `remainder subtracts SUCCEEDED from the books and REQUESTED and PENDING from their rows`() {
        // succeeded: 10.00 (6.00 gateway + 4.00 credit value, 4.00 credits) is in the books
        val order = mixed.copy(refundedTotal = 1000, refundedGatewayAmount = 600, refundedCreditAmount = 400)
        val rows = listOf(
            row(1, RefundStatus.SUCCEEDED, 1000, 600, 400), // already in the books: never counted twice
            row(2, RefundStatus.PENDING, 2000, 1200, 800),
            row(3, RefundStatus.REQUESTED, 500, 300, 200)
        )
        val rem = RefundMath.remaining(order, rows)
        assertEquals(10000L - 1000 - 2000 - 500, rem.total)
        assertEquals(6000L - 600 - 1200 - 300, rem.gateway)
        assertEquals(4000L - 400 - 800 - 200, rem.creditValue)
        assertEquals(4000L - 400 - 800 - 200, rem.credit)
        // the spec's raw formula agrees for a consistent order
        assertEquals(rem.total, rem.gateway + rem.creditValue)
        assertEquals(RefundMath.InFlight(2500, 1500, 1000), RefundMath.inFlight(rows))
        assertEquals(1000L, RefundMath.inFlight(rows).creditValue)
    }

    @Test
    fun `FAILED and CANCELLED rows reserve nothing`() {
        val rows = listOf(
            row(1, RefundStatus.FAILED, 3000, 1800, 1200),
            row(2, RefundStatus.CANCELLED, 4000, 2400, 1600)
        )
        assertEquals(Remaining(10000, 6000, 4000, 4000), RefundMath.remaining(mixed, rows))
        assertEquals(RefundMath.InFlight(0, 0, 0), RefundMath.inFlight(rows))
    }

    @Test
    fun `a retry is validated without its own reservation`() {
        val rows = listOf(row(7, RefundStatus.REQUESTED, 6000, 3600, 2400), row(8, RefundStatus.PENDING, 1000, 600, 400))
        assertEquals(Remaining(3000, 1800, 1200, 1200), RefundMath.remaining(mixed, rows))
        // without the retried row 7 only row 8 reserves; without row 8 only row 7 does
        assertEquals(Remaining(9000, 5400, 3600, 3600), RefundMath.remaining(mixed, rows, excludingRefundId = 7))
        assertEquals(Remaining(4000, 2400, 1600, 1600), RefundMath.remaining(mixed, rows, excludingRefundId = 8))
    }

    @Test
    fun `an override beyond the gateway remainder is rejected`() {
        val order = mixed.copy(refundedTotal = 1000, refundedGatewayAmount = 600, refundedCreditAmount = 400)
        val rows = listOf(row(2, RefundStatus.PENDING, 2000, 1200, 800))
        val rem = RefundMath.remaining(order, rows)
        assertEquals(4200L, rem.gateway)
        val split = RefundMath.splitOrder(order, rows)
        val gw = RefundSplit.GatewayRefund(RefundSupport.PARTIAL)
        val over = RefundSplit.compute(split, RefundSplit.Request(RefundSplit.Mode.OVERRIDE, gatewayAmount = rem.gateway + 1), gw)
        assertTrue(over is RefundSplit.Result.Invalid && over.problem == RefundSplit.Problem.GATEWAY_PART_OUT_OF_RANGE, over.toString())
        val exact = RefundSplit.compute(split, RefundSplit.Request(RefundSplit.Mode.OVERRIDE, gatewayAmount = rem.gateway), gw)
        assertTrue(exact is RefundSplit.Result.Ok, exact.toString())
        assertEquals(asLimits(rem), limitsOf(over))
    }

    @Test
    fun `RD-D1 an async refund of everything leaves nothing for a second credit-only refund`() {
        val rows = listOf(row(1, RefundStatus.PENDING, 10000, 6000, 4000))
        val rem = RefundMath.remaining(mixed, rows)
        assertEquals(Remaining(0, 0, 0, 0), rem)
        val second = RefundSplit.compute(
            RefundMath.splitOrder(mixed, rows),
            RefundSplit.Request(RefundSplit.Mode.OVERRIDE, creditAmount = 4000),
            RefundSplit.GatewayRefund(RefundSupport.PARTIAL)
        )
        assertTrue(second is RefundSplit.Result.Invalid, second.toString())
        assertEquals(0L, limitsOf(second).max)
        // when #1 succeeds the books carry it and nothing is left either
        val booked = mixed.copy(refundedTotal = 10000, refundedGatewayAmount = 6000, refundedCreditAmount = 4000)
        assertEquals(Remaining(0, 0, 0, 0), RefundMath.remaining(booked, emptyList()))
    }

    @Test
    fun `a damaged order never shows a negative remainder or more than gateway plus credit value`() {
        // a gateway-side refund raced a panel refund and booked more than the order cost (07 section 7.2)
        val damaged = mixed.copy(refundedTotal = 10500, refundedGatewayAmount = 6500, refundedCreditAmount = 4000)
        assertEquals(Remaining(0, 0, 0, 0), RefundMath.remaining(damaged, emptyList()))
        // in-flight above what is left
        val rows = listOf(row(1, RefundStatus.PENDING, 20000, 12000, 8000))
        assertEquals(Remaining(0, 0, 0, 0), RefundMath.remaining(mixed, rows))
        // an inconsistent order (T above G + CV): the total is cut to what the two sides can give back
        val odd = OrderAmounts(totalPrice = 10000, gatewayAmount = 5000, creditValue = 3000, creditAmount = 3000)
        assertEquals(8000L, RefundMath.remaining(odd, emptyList()).total)
    }

    @Test
    fun `an anonymised order with credits returns none of them`() {
        val anon = mixed.copy(anonymised = true)
        assertEquals(Remaining(6000, 6000, 0, 0), RefundMath.remaining(anon, emptyList()))
        // without credits anonymising changes nothing
        val plain = OrderAmounts(totalPrice = 5000, gatewayAmount = 5000, creditValue = 0, creditAmount = 0, anonymised = true)
        assertEquals(Remaining(5000, 5000, 0, 0), RefundMath.remaining(plain, emptyList()))
    }

    @Test
    fun `RefundMath and RefundSplit agree on the remainder for thousands of random orders`() {
        val rnd = Random(110L)
        val statuses = RefundStatus.values()
        var consistent = 0
        var damaged = 0
        repeat(6000) {
            val unit = if (rnd.nextInt(4) == 0) 100L else 1L
            val cv = if (rnd.nextInt(3) == 0) 0L else rnd.nextInt(500) * unit
            val g = rnd.nextInt(500) * unit
            val total = g + cv
            val ca = if (cv == 0L) (if (rnd.nextInt(5) == 0) rnd.nextInt(300).toLong() else 0L) else cv * (1 + rnd.nextInt(3)) / (1 + rnd.nextInt(2))
            val anonymised = rnd.nextInt(8) == 0
            val rows = ArrayList<RefundAmounts>()
            var remG = g
            var remCv = cv
            var remCa = ca
            var rG = 0L
            var rCv = 0L
            var rCa = 0L
            repeat(rnd.nextInt(5)) { i ->
                val pg = if (remG == 0L) 0L else (rnd.nextInt((remG / unit).toInt() + 1) * unit)
                val pcv = if (remCv == 0L) 0L else (rnd.nextInt((remCv / unit).toInt() + 1) * unit)
                val pca = if (remCa == 0L) 0L else rnd.nextInt(remCa.toInt() + 1).toLong()
                val status = statuses[rnd.nextInt(statuses.size)]
                rows += row(i + 1L, status, pg + pcv, pg, pca)
                if (status == RefundStatus.SUCCEEDED || status == RefundStatus.REQUESTED || status == RefundStatus.PENDING) {
                    remG -= pg; remCv -= pcv; remCa -= pca
                }
                if (status == RefundStatus.SUCCEEDED) { rG += pg; rCv += pcv; rCa += pca }
            }
            // the books carry exactly the SUCCEEDED rows; the damaged variant adds a stray gateway refund
            val stray = if (rnd.nextInt(10) == 0) (1 + rnd.nextInt(50)) * unit else 0L
            if (stray > 0) damaged++ else consistent++
            val books = OrderAmounts(total, g, cv, ca, rG + rCv + stray, rG + stray, rCa, unit, anonymised)
            val withoutSucceeded = rows.filter { it.status != RefundStatus.SUCCEEDED }

            val rem = RefundMath.remaining(books, withoutSucceeded)
            val split = RefundMath.splitOrder(books, withoutSucceeded)
            val ref = RefundSplit.compute(split, RefundSplit.Request(), RefundSplit.GatewayRefund(RefundSupport.PARTIAL))
            assertEquals(limitsOf(ref), asLimits(rem), "order $books rows $withoutSucceeded")
            assertTrue(rem.total >= 0 && rem.gateway >= 0 && rem.creditValue >= 0 && rem.credit >= 0)
            assertTrue(rem.total <= rem.gateway + rem.creditValue)

            if (stray == 0L && !(anonymised && ca > 0)) {
                // the raw formula of 21 section 2 on a consistent order
                val open = withoutSucceeded.filter { it.status == RefundStatus.REQUESTED || it.status == RefundStatus.PENDING }
                val pT = open.sumOf { it.amount }
                val pG = open.sumOf { it.gatewayAmount }
                val pCa = open.sumOf { it.creditAmount }
                assertEquals(total - books.refundedTotal - pT, rem.total, "remT of $books")
                assertEquals(g - books.refundedGatewayAmount - pG, rem.gateway, "remG of $books")
                assertEquals(cv - (books.refundedTotal - books.refundedGatewayAmount) - (pT - pG), rem.creditValue, "remCV of $books")
                assertEquals(ca - books.refundedCreditAmount - pCa, rem.credit, "remCA of $books")
            }
        }
        assertTrue(consistent > 4000 && damaged > 300, "mix: $consistent consistent, $damaged damaged")
    }

    // ---------------------------------------------------------------- RefundRequest.full

    @Test
    fun `full means the whole captured amount, no earlier gateway refund and nothing else in flight there`() {
        val o = OrderAmounts(10000, 6000, 4000, 4000)
        assertTrue(RefundMath.isFullGatewayRefund(o, 6000, 6000))
        assertFalse(RefundMath.isFullGatewayRefund(o, 5000, 5000))
        assertFalse(RefundMath.isFullGatewayRefund(o, 6000, 7000), "another refund is in flight at the gateway")
        assertFalse(RefundMath.isFullGatewayRefund(o.copy(refundedGatewayAmount = 100), 6000, 6000), "a refund already went through")
        assertFalse(RefundMath.isFullGatewayRefund(o, 0, 0), "a credit-only row is not a gateway refund")
    }

    // ---------------------------------------------------------------- O10 step 1: booking

    @Test
    fun `an ordinary refund is booked as it is`() {
        val b = RefundMath.book(mixed, gatewayPart = 6000, creditValuePart = 4000, creditPart = 4000)
        assertEquals(RefundMath.Booking(10000, 6000, 4000, 4000, clamped = false, overRefund = false), b)
        val partial = RefundMath.book(mixed, 600, 400, 400)
        assertEquals(RefundMath.Booking(1000, 600, 400, 400, clamped = false, overRefund = false), partial)
    }

    @Test
    fun `RD-D3 a gateway refund that raced a panel refund is booked, the credit side is cut back and the alert is asked for`() {
        // the panel refund of 6000 gateway + 4000 credit value (4000 credits) is booked first
        val afterPanel = mixed.copy(refundedTotal = 10000, refundedGatewayAmount = 6000, refundedCreditAmount = 4000)
        // then the dashboard refund of 2500 arrives; it knows no credits, but a stray credit part must still be cut back
        val gateway = RefundMath.book(afterPanel, gatewayPart = 2500, creditValuePart = 0, creditPart = 0)
        assertEquals(RefundMath.Booking(2500, 2500, 0, 0, clamped = false, overRefund = true), gateway)
        // a panel refund with a credit part confirmed after the credits already came back: credit side clamped, gateway side in full
        val raced = RefundMath.book(afterPanel, gatewayPart = 1000, creditValuePart = 800, creditPart = 800)
        assertEquals(1000L, raced.gatewayPart)
        assertEquals(0L, raced.creditValuePart)
        assertEquals(0L, raced.creditPart)
        assertEquals(1000L, raced.amount)
        assertTrue(raced.clamped)
        assertTrue(raced.overRefund)
    }

    @Test
    fun `the credit part is clamped to what the books still hold, partly`() {
        // 3000 of the 4000 credit value (3000 of 4000 credits) is back already
        val o = mixed.copy(refundedTotal = 3000, refundedGatewayAmount = 0, refundedCreditAmount = 3000)
        val b = RefundMath.book(o, gatewayPart = 0, creditValuePart = 2000, creditPart = 2000)
        assertEquals(1000L, b.creditValuePart)
        assertEquals(1000L, b.creditPart)
        assertEquals(1000L, b.amount)
        assertTrue(b.clamped)
        assertFalse(b.overRefund)
    }

    @Test
    fun `booking never lets the credit side or the gateway side go negative and rejects negative input`() {
        assertThrows(IllegalArgumentException::class.java) { RefundMath.book(mixed, -1, 0, 0) }
        assertThrows(IllegalArgumentException::class.java) { RefundMath.book(mixed, 0, -1, 0) }
        assertThrows(IllegalArgumentException::class.java) { RefundMath.book(mixed, 0, 0, -1) }
        val damaged = mixed.copy(refundedTotal = 20000, refundedGatewayAmount = 6000, refundedCreditAmount = 9000)
        val b = RefundMath.book(damaged, 100, 100, 100)
        assertEquals(100L, b.amount)
        assertEquals(0L, b.creditPart)
        assertTrue(b.overRefund)
    }

    @Test
    fun `booking random refunds in any order keeps the books consistent and never drops gateway money`() {
        val rnd = Random(111L)
        repeat(3000) {
            var o = mixed
            var gatewayTotal = 0L
            repeat(1 + rnd.nextInt(6)) {
                val g = rnd.nextInt(3000).toLong()
                val cv = rnd.nextInt(2500).toLong()
                val ca = rnd.nextInt(2500).toLong()
                val b = RefundMath.book(o, g, cv, ca)
                assertEquals(g, b.gatewayPart)
                assertEquals(b.gatewayPart + b.creditValuePart, b.amount)
                assertTrue(b.creditPart <= ca && b.creditValuePart <= cv)
                assertTrue(o.refundedCreditAmount + b.creditPart <= o.creditAmount, "credits returned above spent")
                assertTrue(b.creditValuePart + (o.refundedTotal - o.refundedGatewayAmount) <= o.creditValue, "value above CV")
                assertEquals(o.refundedTotal + b.amount > o.totalPrice, b.overRefund)
                gatewayTotal += g
                o = o.copy(
                    refundedTotal = o.refundedTotal + b.amount,
                    refundedGatewayAmount = o.refundedGatewayAmount + b.gatewayPart,
                    refundedCreditAmount = o.refundedCreditAmount + b.creditPart
                )
            }
            assertEquals(gatewayTotal, o.refundedGatewayAmount)
        }
    }

    // ---------------------------------------------------------------- lines of a refund with items

    @Test
    fun `the last unit takes the remainder of the line`() {
        var line = Line(itemId = 1, quantity = 3, lineTotal = 1000)
        val first = RefundMath.lineAmount(line, 1)
        assertEquals(333L, first)
        line = line.copy(refundedQuantity = 1, refundedAmount = first)
        val second = RefundMath.lineAmount(line, 1)
        assertEquals(333L, second)
        line = line.copy(refundedQuantity = 2, refundedAmount = first + second)
        assertEquals(334L, RefundMath.lineAmount(line, 1))
        // two units at once leave the remainder for the last
        assertEquals(667L, RefundMath.lineAmount(Line(1, 3, 1000), 2))
        // all units at once = the whole line
        assertEquals(1000L, RefundMath.lineAmount(Line(1, 3, 1000), 3))
    }

    @Test
    fun `a zero-decimal line rounds to the unit`() {
        val line = Line(itemId = 1, quantity = 3, lineTotal = 1000)
        assertEquals(300L, RefundMath.lineAmount(line, 1, unit = 100))
        assertEquals(700L, RefundMath.lineAmount(line.copy(refundedQuantity = 1, refundedAmount = 300).copy(refundedQuantity = 1), 2, unit = 100))
    }

    @Test
    fun `a line amount never exceeds what the line can still give back`() {
        // an amount-only refund already took most of the line
        val line = Line(itemId = 1, quantity = 2, lineTotal = 1000, refundedQuantity = 0, refundedAmount = 800)
        assertEquals(200L, RefundMath.lineAmount(line, 1), "half of 1000 would be 500, only 200 is left")
        assertEquals(200L, RefundMath.lineAmount(line, 2))
        // a fully refunded amount leaves nothing
        assertEquals(0L, RefundMath.lineAmount(Line(1, 2, 1000, 0, 1000), 1))
    }

    @Test
    fun `lineAmount refuses a quantity outside the open units`() {
        val line = Line(1, 3, 900, refundedQuantity = 1, refundedAmount = 300)
        assertThrows(IllegalArgumentException::class.java) { RefundMath.lineAmount(line, 0) }
        assertThrows(IllegalArgumentException::class.java) { RefundMath.lineAmount(line, 3) }
        assertEquals(600L, RefundMath.lineAmount(line, 2))
    }

    @Test
    fun `a line refunded piece by piece adds up to its total in every split`() {
        val rnd = Random(112L)
        repeat(2000) {
            val unit = if (rnd.nextBoolean()) 1L else 100L
            val quantity = 1 + rnd.nextInt(9)
            val total = (1 + rnd.nextInt(5000)) * unit
            var line = Line(1, quantity, total)
            var sum = 0L
            while (line.remainingQuantity > 0) {
                val q = 1 + rnd.nextInt(line.remainingQuantity)
                val a = RefundMath.lineAmount(line, q, unit)
                assertTrue(a in 0..line.remainingAmount)
                sum += a
                line = line.copy(refundedQuantity = line.refundedQuantity + q, refundedAmount = line.refundedAmount + a)
            }
            assertEquals(total, sum)
        }
    }

    @Test
    fun `itemsAmount sums the lines and names the problem of a bad request`() {
        val lines = listOf(Line(1, 2, 6000), Line(2, 1, 4000), Line(3, 4, 0))
        val ok = RefundMath.itemsAmount(lines, listOf(RefundMath.ItemRequest(1, 1), RefundMath.ItemRequest(2, 1)))
        assertEquals(
            RefundMath.ItemsResult.Ok(listOf(RefundMath.ItemAmount(1, 1, 3000), RefundMath.ItemAmount(2, 1, 4000)), 7000),
            ok
        )
        // a bundle child costs nothing and adds nothing
        val free = RefundMath.itemsAmount(lines, listOf(RefundMath.ItemRequest(3, 2)))
        assertEquals(RefundMath.ItemsResult.Ok(listOf(RefundMath.ItemAmount(3, 2, 0)), 0), free)

        fun problem(request: List<RefundMath.ItemRequest>) = (RefundMath.itemsAmount(lines, request) as RefundMath.ItemsResult.Invalid)
        assertEquals(RefundMath.ItemsProblem.EMPTY, problem(emptyList()).problem)
        assertEquals(RefundMath.ItemsResult.Invalid(RefundMath.ItemsProblem.UNKNOWN_ITEM, 9), problem(listOf(RefundMath.ItemRequest(9, 1))))
        assertEquals(
            RefundMath.ItemsResult.Invalid(RefundMath.ItemsProblem.DUPLICATE_ITEM, 1),
            problem(listOf(RefundMath.ItemRequest(1, 1), RefundMath.ItemRequest(1, 1)))
        )
        assertEquals(RefundMath.ItemsResult.Invalid(RefundMath.ItemsProblem.QUANTITY_OUT_OF_RANGE, 1), problem(listOf(RefundMath.ItemRequest(1, 3))))
        assertEquals(RefundMath.ItemsResult.Invalid(RefundMath.ItemsProblem.QUANTITY_OUT_OF_RANGE, 2), problem(listOf(RefundMath.ItemRequest(2, 0))))
        // a line that is already fully refunded has no open unit
        val done = listOf(Line(1, 1, 500, refundedQuantity = 1, refundedAmount = 500))
        assertEquals(
            RefundMath.ItemsResult.Invalid(RefundMath.ItemsProblem.QUANTITY_OUT_OF_RANGE, 1),
            RefundMath.itemsAmount(done, listOf(RefundMath.ItemRequest(1, 1)))
        )
    }

    // ---------------------------------------------------------------- RD-D8: amount-only refunds over the lines

    @Test
    fun `RD-D8 an amount-only refund of 30 over lines of 60 and 40 books 18 and 12`() {
        val lines = listOf(Line(1, 1, 6000), Line(2, 2, 4000))
        val s = RefundMath.spread(3000, lines, orderFullyRefunded = false)
        assertEquals(listOf(1800L, 1200L), s.shares.map { it.amount })
        assertEquals(0L, s.unallocated)
        // quantities change only when the order becomes fully refunded
        assertEquals(listOf(0, 0), s.shares.map { it.quantity })
        assertEquals(listOf(1L, 2L), s.shares.map { it.itemId })
    }

    @Test
    fun `the next refund spreads over what each line has left, and the full one settles the quantities`() {
        val after = listOf(
            Line(1, 1, 6000, refundedQuantity = 0, refundedAmount = 1800),
            Line(2, 2, 4000, refundedQuantity = 0, refundedAmount = 1200)
        )
        val s = RefundMath.spread(7000, after, orderFullyRefunded = true)
        assertEquals(listOf(4200L, 2800L), s.shares.map { it.amount })
        assertEquals(listOf(1, 2), s.shares.map { it.quantity })
        // a unit that an item refund took earlier does not count again
        val partlyShipped = listOf(Line(2, 3, 3000, refundedQuantity = 1, refundedAmount = 1000))
        assertEquals(2, RefundMath.spread(2000, partlyShipped, orderFullyRefunded = true).shares[0].quantity)
    }

    @Test
    fun `shipping and fee are not lines, so an amount above the lines leaves a difference`() {
        val lines = listOf(Line(1, 1, 6000), Line(2, 1, 4000))
        val s = RefundMath.spread(11000, lines, orderFullyRefunded = true)
        assertEquals(listOf(6000L, 4000L), s.shares.map { it.amount })
        assertEquals(1000L, s.unallocated)
        // a refund smaller than the lines is spread entirely
        assertEquals(0L, RefundMath.spread(999, lines, orderFullyRefunded = false).unallocated)
    }

    @Test
    fun `largest remainder ties go to the first line and a free line gets nothing`() {
        val lines = listOf(Line(1, 1, 1), Line(2, 1, 1), Line(3, 1, 1), Line(4, 1, 0))
        assertEquals(listOf(1L, 1L, 0L, 0L), RefundMath.spread(2, lines, false).shares.map { it.amount })
        // 7.00 over 10.00 and 5.00 in a zero-decimal currency stays on whole units
        val zero = listOf(Line(1, 1, 1000), Line(2, 1, 500))
        val s = RefundMath.spread(700, zero, false, unit = 100)
        assertEquals(700L, s.shares.sumOf { it.amount })
        assertTrue(s.shares.all { it.amount % 100 == 0L })
        assertEquals(listOf(500L, 200L), s.shares.map { it.amount })
    }

    @Test
    fun `spread refuses a negative amount, a bad unit and an amount off the unit`() {
        val lines = listOf(Line(1, 1, 1000))
        assertThrows(IllegalArgumentException::class.java) { RefundMath.spread(-1, lines, false) }
        assertThrows(IllegalArgumentException::class.java) { RefundMath.spread(100, lines, false, unit = 10) }
        assertThrows(IllegalArgumentException::class.java) { RefundMath.spread(150, lines, false, unit = 100) }
        assertEquals(emptyList<RefundMath.LineShare>(), RefundMath.spread(0, emptyList(), false).shares)
        assertEquals(500L, RefundMath.spread(500, emptyList(), false).unallocated)
    }

    @Test
    fun `a damaged line off the unit grid falls back to the finest grid instead of failing`() {
        val lines = listOf(Line(1, 1, 1050), Line(2, 1, 1000)) // 1050 is not a whole unit of 100
        val s = RefundMath.spread(700, lines, false, unit = 100)
        assertEquals(700L, s.shares.sumOf { it.amount })
        assertEquals(0L, s.unallocated)
    }

    @Test
    fun `spreading random refunds over random lines is exact, bounded and repeatable`() {
        val rnd = Random(113L)
        repeat(4000) {
            val unit = if (rnd.nextInt(3) == 0) 100L else 1L
            val lines = (1..(1 + rnd.nextInt(6))).map {
                val total = if (rnd.nextInt(6) == 0) 0L else rnd.nextInt(900) * unit
                val done = if (total == 0L) 0L else rnd.nextInt((total / unit).toInt() + 1) * unit
                Line(it.toLong(), 1 + rnd.nextInt(4), total, 0, done)
            }
            val capacity = lines.sumOf { it.remainingAmount }
            val amount = (rnd.nextInt(((capacity + 500) / unit).toInt() + 1)) * unit
            val full = rnd.nextBoolean()
            val s = RefundMath.spread(amount, lines, full, unit)
            assertEquals(s, RefundMath.spread(amount, lines, full, unit), "deterministic")
            assertEquals(amount, s.shares.sumOf { it.amount } + s.unallocated)
            assertEquals(minOf(amount, capacity), s.shares.sumOf { it.amount })
            for ((i, share) in s.shares.withIndex()) {
                assertTrue(share.amount in 0..lines[i].remainingAmount, "share ${share.amount} of ${lines[i]}")
                assertEquals(0L, share.amount % unit)
                assertEquals(if (full) lines[i].remainingQuantity else 0, share.quantity)
                // within one unit of the exact proportional share
                if (capacity > 0 && amount <= capacity) {
                    val exact = amount.toDouble() * lines[i].remainingAmount / capacity
                    assertTrue(Math.abs(share.amount - exact) <= unit.toDouble() + 1e-6, "share ${share.amount} vs exact $exact")
                }
            }
        }
    }

    // ---------------------------------------------------------------- no floating point in production code

    @Test
    fun `core refund production code contains no floating point`() {
        val root = File("src/main/kotlin/com/panomc/plugins/market/core/refund")
        assertTrue(root.isDirectory, "missing ${root.path}")
        val forbidden = Regex("""\b(Double|Float)\b|\.toDouble\(|\.toFloat\(|roundTo(Long|Int)\(|Math\.(round|floor|ceil)\(|\b\d+\.\d+\b""")
        var scanned = 0
        for (file in root.walkTopDown().filter { it.isFile && it.extension == "kt" }) {
            scanned++
            val code = stripCommentsAndStrings(file.readText())
            val hit = forbidden.find(code)
            assertTrue(hit == null, "${file.name}: floating point '${hit?.value}'")
        }
        assertTrue(scanned >= 4, "scanned only $scanned files")
    }

    private fun stripCommentsAndStrings(source: String): String {
        val out = StringBuilder()
        var i = 0
        val n = source.length
        while (i < n) {
            val c = source[i]
            when {
                source.startsWith("/*", i) -> {
                    var depth = 1
                    i += 2
                    while (i < n && depth > 0) {
                        if (source.startsWith("/*", i)) { depth++; i += 2 }
                        else if (source.startsWith("*/", i)) { depth--; i += 2 }
                        else i++
                    }
                }
                source.startsWith("//", i) -> while (i < n && source[i] != '\n') i++
                c == '"' -> {
                    i++
                    while (i < n && source[i] != '"') { if (source[i] == '\\') i++; i++ }
                    i++
                }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }
}
