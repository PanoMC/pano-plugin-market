package com.panomc.plugins.market.core.credit

import com.panomc.plugins.market.core.credit.RefundSplit.GatewayRefund
import com.panomc.plugins.market.core.credit.RefundSplit.Limits
import com.panomc.plugins.market.core.credit.RefundSplit.Mode
import com.panomc.plugins.market.core.credit.RefundSplit.Order
import com.panomc.plugins.market.core.credit.RefundSplit.Problem
import com.panomc.plugins.market.core.credit.RefundSplit.Request
import com.panomc.plugins.market.core.credit.RefundSplit.Result
import com.panomc.plugins.market.core.credit.RefundSplit.Split
import com.panomc.plugins.market.core.credit.RefundSplit.Warning
import com.panomc.plugins.market.core.credit.RefundSplit.WarningCode
import com.panomc.plugins.market.spi.payment.RefundSupport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigInteger
import kotlin.random.Random

/**
 * 07 section 19.5 (U-R1 .. U-R15) plus the properties the money rests on: for every input the gateway part and the credit
 * value part add up to the amount, a proportional part is the half-up share (within one unit of the exact ratio unless the
 * remainders clamp it), both parts stay inside what remains on their side, and a sequence of refunds that ends with the
 * whole remainder returns exactly the gateway amount, the credit value and the credits that were spent, no more and no less.
 *
 * Figures are x 100: 10000 is 100.00. A mixed order of 100.00 is `G = 6000` paid by the gateway and `CV = 4000` paid with
 * `CA = 4000` credits (a credit value of 1.00).
 */
class RefundSplitTest {
    private val partial = GatewayRefund(RefundSupport.PARTIAL)

    private fun mixedOrder(
        t: Long = 10_000, g: Long = 6_000, cv: Long = 4_000, ca: Long = 4_000,
        rT: Long = 0, rG: Long = 0, rCA: Long = 0, pT: Long = 0, pG: Long = 0, pCA: Long = 0,
        unit: Long = 1, anonymised: Boolean = false
    ) = Order(t, g, cv, ca, rT, rG, rCA, pT, pG, pCA, unit, anonymised)

    private fun ok(result: Result): Result.Ok {
        assertTrue(result is Result.Ok, "expected Ok but was $result")
        return result as Result.Ok
    }

    private fun invalid(result: Result): Result.Invalid {
        assertTrue(result is Result.Invalid, "expected Invalid but was $result")
        return result as Result.Invalid
    }

    private fun split(order: Order, request: Request = Request(), gateway: GatewayRefund? = partial) =
        ok(RefundSplit.compute(order, request, gateway)).split

    // ---------------------------------------------------------------- 19.5

    @Test
    fun `U-R1 a full refund of a mixed order returns 60 00 to the gateway and 40 00 and 40 credits to the balance`() {
        assertEquals(Split(10_000, 6_000, 4_000, 4_000), split(mixedOrder()))
        assertEquals(Split(10_000, 6_000, 4_000, 4_000), split(mixedOrder(), Request(amount = 10_000)))
    }

    @Test
    fun `U-R2 a partial refund of 50 00 is split in proportion`() {
        assertEquals(Split(5_000, 3_000, 2_000, 2_000), split(mixedOrder(), Request(amount = 5_000)))
    }

    @Test
    fun `U-R3 two partial refunds that make a whole add up exactly to every side`() {
        val first = split(mixedOrder(), Request(amount = 3_333))
        assertEquals(Split(3_333, 2_000, 1_333, 1_333), first)
        val afterFirst = mixedOrder(rT = first.amount, rG = first.gatewayPart, rCA = first.creditPart)
        val rest = split(afterFirst) // the remainder, 66.67
        assertEquals(6_667L, rest.amount)
        assertEquals(Split(6_667, 4_000, 2_667, 2_667), rest)
        assertEquals(6_000L, first.gatewayPart + rest.gatewayPart)
        assertEquals(4_000L, first.creditValuePart + rest.creditValuePart)
        assertEquals(4_000L, first.creditPart + rest.creditPart)
    }

    @Test
    fun `U-R4 at a credit value of 0 10 a refund of 25 00 returns 10 00 of value and 100 credits`() {
        val order = mixedOrder(ca = 40_000) // 400.00 credits worth 40.00
        assertEquals(Split(2_500, 1_500, 1_000, 10_000), split(order, Request(amount = 2_500)))
        assertEquals(Split(10_000, 6_000, 4_000, 40_000), split(order))
    }

    @Test
    fun `U-R5 a full-credit order is refunded in credits only`() {
        val order = Order(totalPrice = 10_000, gatewayAmount = 0, creditValue = 10_000, creditAmount = 12_000) // 120.00 credits for 100.00
        assertEquals(Split(3_000, 0, 3_000, 3_600), split(order, Request(amount = 3_000), gateway = null), "no gateway is asked")
        assertEquals(Split(10_000, 0, 10_000, 12_000), split(order, gateway = null))
    }

    @Test
    fun `U-R6 a gateway-only order has no credit parts`() {
        val order = Order(totalPrice = 10_000, gatewayAmount = 10_000, creditValue = 0, creditAmount = 0)
        assertEquals(Split(4_000, 4_000, 0, 0), split(order, Request(amount = 4_000)))
        assertEquals(Split(10_000, 10_000, 0, 0), split(order))
    }

    @Test
    fun `U-R7 after the gateway part is gone the next proportional refund is all credit`() {
        // an earlier override refunded the whole 60.00 at the gateway
        val order = mixedOrder(rT = 6_000, rG = 6_000)
        assertEquals(Split(2_000, 0, 2_000, 2_000), split(order, Request(amount = 2_000)))
        assertEquals(Split(4_000, 0, 4_000, 4_000), split(order))
        // and the mirror: all the credit value is gone, so the rest is gateway
        val creditGone = mixedOrder(rT = 4_000, rG = 0, rCA = 4_000)
        assertEquals(Split(1_000, 1_000, 0, 0), split(creditGone, Request(amount = 1_000)))
    }

    @Test
    fun `U-R8 an override can name the gateway part the credit part or both`() {
        val order = mixedOrder()
        // gatewayAmount alone: a gateway-only refund of 20.00
        assertEquals(Split(2_000, 2_000, 0, 0), split(order, Request(Mode.OVERRIDE, gatewayAmount = 2_000)))
        // creditAmount alone: a credit-only refund of 10.00 credits, worth 10.00
        assertEquals(Split(1_000, 0, 1_000, 1_000), split(order, Request(Mode.OVERRIDE, creditAmount = 1_000)))
        // gatewayAmount 0 with an amount: the whole amount is credit value
        assertEquals(Split(1_000, 0, 1_000, 1_000), split(order, Request(Mode.OVERRIDE, amount = 1_000, gatewayAmount = 0)))
        // gatewayAmount with an amount: the rest is credit value, credits by the proportional rule
        assertEquals(Split(3_000, 2_000, 1_000, 1_000), split(order, Request(Mode.OVERRIDE, amount = 3_000, gatewayAmount = 2_000)))
        // creditAmount with an amount: the rest is gateway
        assertEquals(Split(3_000, 2_000, 1_000, 1_000), split(order, Request(Mode.OVERRIDE, amount = 3_000, creditAmount = 1_000)))
        // both: parts as given, the amount is their sum (and may be omitted)
        assertEquals(Split(3_000, 2_000, 1_000, 1_000), split(order, Request(Mode.OVERRIDE, gatewayAmount = 2_000, creditAmount = 1_000)))
        assertEquals(Split(3_000, 2_000, 1_000, 1_000), split(order, Request(Mode.OVERRIDE, amount = 3_000, gatewayAmount = 2_000, creditAmount = 1_000)))
    }

    @Test
    fun `an override that takes all remaining credits takes all the remaining credit value`() {
        val order = mixedOrder(ca = 40_000)
        assertEquals(Split(4_000, 0, 4_000, 40_000), split(order, Request(Mode.OVERRIDE, creditAmount = 40_000)))
        // one cent of credit less is almost all of the value
        assertEquals(Split(4_000, 0, 4_000, 39_999), split(order, Request(Mode.OVERRIDE, creditAmount = 39_999)))
        // after a partial refund the rest is the rest
        val first = split(order, Request(amount = 3_333))
        val afterFirst = mixedOrder(ca = 40_000, rT = 3_333, rG = first.gatewayPart, rCA = first.creditPart)
        val rest = split(afterFirst, Request(Mode.OVERRIDE, creditAmount = 40_000 - first.creditPart))
        assertEquals(4_000L - first.creditValuePart, rest.creditValuePart)
    }

    @Test
    fun `U-R9 an override beyond what is left on a side is refused with the limits`() {
        val order = mixedOrder(rT = 1_000, rG = 1_000, rCA = 0) // remaining: gateway 50.00, credit value 40.00, credits 40.00
        val g = invalid(RefundSplit.compute(order, Request(Mode.OVERRIDE, gatewayAmount = 5_001), partial))
        assertEquals(Problem.GATEWAY_PART_OUT_OF_RANGE, g.problem)
        assertEquals(Limits(max = 9_000, maxGateway = 5_000, maxCreditValue = 4_000, maxCredit = 4_000), g.limits)
        val c = invalid(RefundSplit.compute(order, Request(Mode.OVERRIDE, creditAmount = 4_001), partial))
        assertEquals(Problem.CREDIT_PART_OUT_OF_RANGE, c.problem)
        assertEquals(5_000L, c.limits.maxGateway)
        assertEquals(4_000L, c.limits.maxCredit)
        // exactly the limit is fine
        assertEquals(Split(5_000, 5_000, 0, 0), split(order, Request(Mode.OVERRIDE, gatewayAmount = 5_000)))
        assertEquals(Split(4_000, 0, 4_000, 4_000), split(order, Request(Mode.OVERRIDE, creditAmount = 4_000)))
    }

    @Test
    fun `U-R10 both overrides with an amount that is not their sum are refused`() {
        val r = invalid(RefundSplit.compute(mixedOrder(), Request(Mode.OVERRIDE, amount = 3_001, gatewayAmount = 2_000, creditAmount = 1_000), partial))
        assertEquals(Problem.AMOUNT_MISMATCH, r.problem)
        // an amount smaller than the part it must contain
        assertEquals(Problem.AMOUNT_MISMATCH, invalid(RefundSplit.compute(mixedOrder(), Request(Mode.OVERRIDE, amount = 1_500, gatewayAmount = 2_000), partial)).problem)
        assertEquals(Problem.AMOUNT_MISMATCH, invalid(RefundSplit.compute(mixedOrder(), Request(Mode.OVERRIDE, amount = 500, creditAmount = 1_000), partial)).problem)
    }

    @Test
    fun `U-R11 an amount above the remainder or not above zero is refused with max`() {
        val order = mixedOrder(rT = 2_500, rG = 1_500, rCA = 1_000)
        for (amount in listOf(7_501L, 10_000L, Long.MAX_VALUE, 0L, -1L)) {
            val r = invalid(RefundSplit.compute(order, Request(amount = amount), partial))
            assertEquals(Problem.AMOUNT_OUT_OF_RANGE, r.problem, "$amount")
            assertEquals(7_500L, r.limits.max, "$amount")
        }
        assertEquals(Split(7_500, 4_500, 3_000, 3_000), split(order, Request(amount = 7_500)))
        // an order with nothing left
        val done = mixedOrder(rT = 10_000, rG = 6_000, rCA = 4_000)
        assertEquals(0L, invalid(RefundSplit.compute(done, Request(), partial)).limits.max)
        // an override that names nothing
        assertEquals(Problem.NOTHING_TO_REFUND, invalid(RefundSplit.compute(mixedOrder(), Request(Mode.OVERRIDE), partial)).problem)
        assertEquals(Problem.AMOUNT_OUT_OF_RANGE, invalid(RefundSplit.compute(mixedOrder(), Request(Mode.OVERRIDE, amount = 0, gatewayAmount = 0), partial)).problem)
        assertEquals(Problem.AMOUNT_OUT_OF_RANGE, invalid(RefundSplit.compute(mixedOrder(), Request(Mode.OVERRIDE, gatewayAmount = 0, creditAmount = 0), partial)).problem)
    }

    @Test
    fun `U-R12 a provider that can only refund in full cannot do a partial gateway part`() {
        val fullOnly = GatewayRefund(RefundSupport.FULL_ONLY)
        // a proportional partial refund has a partial gateway part
        val r = RefundSplit.compute(mixedOrder(), Request(amount = 5_000), fullOnly)
        assertTrue(r is Result.NotSupported, "$r")
        r as Result.NotSupported
        assertEquals(RefundSupport.FULL_ONLY, r.refundSupport)
        assertEquals(Split(5_000, 3_000, 2_000, 2_000), r.split, "the preview still shows the split")
        assertTrue(r.warnings.any { it.code == WarningCode.GATEWAY_PARTIAL_REFUND_NOT_SUPPORTED && it.refundSupport == RefundSupport.FULL_ONLY })
        // a credit-only refund needs no gateway
        assertEquals(Split(1_000, 0, 1_000, 1_000), split(mixedOrder(), Request(Mode.OVERRIDE, creditAmount = 1_000), fullOnly))
        // the whole gateway amount in one go is the full refund
        assertEquals(Split(6_000, 6_000, 0, 0), split(mixedOrder(), Request(Mode.OVERRIDE, gatewayAmount = 6_000), fullOnly))
        assertEquals(Split(10_000, 6_000, 4_000, 4_000), split(mixedOrder(), Request(), fullOnly))
        // but not when part of it was refunded before, or only part of it is asked for
        assertTrue(RefundSplit.compute(mixedOrder(rT = 1_000, rG = 1_000), Request(Mode.OVERRIDE, gatewayAmount = 5_000), fullOnly) is Result.NotSupported)
        assertTrue(RefundSplit.compute(mixedOrder(), Request(Mode.OVERRIDE, gatewayAmount = 5_999), fullOnly) is Result.NotSupported)
    }

    @Test
    fun `a provider that cannot refund at all needs the manual flag`() {
        val none = GatewayRefund(RefundSupport.NONE)
        assertTrue(RefundSplit.compute(mixedOrder(), Request(amount = 5_000), none) is Result.NotSupported)
        assertTrue(RefundSplit.compute(mixedOrder(), Request(amount = 5_000), null) is Result.NotSupported, "no gateway known: fail closed")
        assertEquals(Split(5_000, 3_000, 2_000, 2_000), split(mixedOrder(), Request(amount = 5_000), GatewayRefund(RefundSupport.NONE, manual = true)))
        // credit-only never touches the gateway
        assertEquals(Split(1_000, 0, 1_000, 1_000), split(mixedOrder(), Request(Mode.OVERRIDE, creditAmount = 1_000), none))
        assertEquals(Split(1_000, 0, 1_000, 1_000), split(mixedOrder(), Request(Mode.OVERRIDE, creditAmount = 1_000), null))
        // per-line gateways take any amount
        assertEquals(Split(5_000, 3_000, 2_000, 2_000), split(mixedOrder(), Request(amount = 5_000), GatewayRefund(RefundSupport.PER_LINE)))
    }

    @Test
    fun `manual lifts every capability refusal because no provider call is made`() {
        // 21 section 3.2 step 3 and 3.6, 04 section 7: REFUND_NOT_SUPPORTED unless manual; the panel dialog turns manual on and resubmits
        val fullOnly = GatewayRefund(RefundSupport.FULL_ONLY)
        val fullOnlyManual = GatewayRefund(RefundSupport.FULL_ONLY, manual = true)
        // a partial proportional refund of an order paid at a full-refund-only gateway: refused, then recorded with manual
        assertTrue(RefundSplit.compute(mixedOrder(), Request(amount = 5_000), fullOnly) is Result.NotSupported)
        val partialManual = ok(RefundSplit.compute(mixedOrder(), Request(amount = 5_000), fullOnlyManual))
        assertEquals(Split(5_000, 3_000, 2_000, 2_000), partialManual.split)
        assertEquals(listOf(Warning(WarningCode.MIXED_PAYMENT_SPLIT, gatewayAmount = 3_000, creditAmount = 2_000)), partialManual.warnings,
            "no GATEWAY_PARTIAL_REFUND_NOT_SUPPORTED warning when the admin returns the money outside the gateway")
        // a gateway part after an earlier gateway refund (rG > 0): FULL_ONLY refuses it, manual records it
        val afterEarlier = mixedOrder(rT = 1_000, rG = 1_000)
        assertTrue(RefundSplit.compute(afterEarlier, Request(Mode.OVERRIDE, gatewayAmount = 5_000), fullOnly) is Result.NotSupported)
        val later = ok(RefundSplit.compute(afterEarlier, Request(Mode.OVERRIDE, gatewayAmount = 5_000), fullOnlyManual))
        assertEquals(Split(5_000, 5_000, 0, 0), later.split)
        assertTrue(later.warnings.isEmpty(), "${later.warnings}")
        // a partial gateway part of the whole gateway amount
        assertEquals(Split(5_999, 5_999, 0, 0), split(mixedOrder(), Request(Mode.OVERRIDE, gatewayAmount = 5_999), fullOnlyManual))
        // a per-item style amount at a full-refund-only gateway
        assertEquals(Split(3_333, 2_000, 1_333, 1_333), split(mixedOrder(), Request(amount = 3_333), fullOnlyManual))
        // the other capabilities do not need it but accept it
        for (support in listOf(RefundSupport.PARTIAL, RefundSupport.PER_LINE, RefundSupport.NONE)) {
            assertEquals(Split(5_000, 3_000, 2_000, 2_000), split(mixedOrder(), Request(amount = 5_000), GatewayRefund(support, manual = true)), "$support")
        }
        // manual does not widen the limits: it is still capped by what the gateway took
        val tooMuch = invalid(RefundSplit.compute(mixedOrder(), Request(Mode.OVERRIDE, gatewayAmount = 6_001), fullOnlyManual))
        assertEquals(Problem.GATEWAY_PART_OUT_OF_RANGE, tooMuch.problem)
        assertEquals(6_000L, tooMuch.limits.maxGateway)
        // and an unknown gateway is not manual: fail closed
        assertTrue(RefundSplit.compute(mixedOrder(), Request(amount = 5_000), null) is Result.NotSupported)
    }

    @Test
    fun `U-R13 an anonymised order cannot return credits so the limit is the gateway part`() {
        val order = mixedOrder(anonymised = true)
        val r = ok(RefundSplit.compute(order, Request(), partial))
        assertEquals(Split(6_000, 6_000, 0, 0), r.split, "the default is what the gateway can still give back")
        assertEquals(Limits(max = 6_000, maxGateway = 6_000, maxCreditValue = 0, maxCredit = 0), r.limits)
        assertTrue(r.warnings.contains(Warning(WarningCode.CREDIT_ACCOUNT_CLOSED, forfeitedCredits = 4_000)))
        // a partial refund is all gateway
        assertEquals(Split(2_500, 2_500, 0, 0), split(order, Request(amount = 2_500)))
        // more than the gateway part is refused
        assertEquals(6_000L, invalid(RefundSplit.compute(order, Request(amount = 6_001), partial)).limits.max)
        // a credit override has nothing to return
        assertEquals(Problem.CREDIT_PART_OUT_OF_RANGE, invalid(RefundSplit.compute(order, Request(Mode.OVERRIDE, creditAmount = 100), partial)).problem)
        // forfeited credits are those still unrefunded
        val half = mixedOrder(anonymised = true, rT = 2_000, rG = 0, rCA = 2_000)
        assertTrue(ok(RefundSplit.compute(half, Request(), partial)).warnings.contains(Warning(WarningCode.CREDIT_ACCOUNT_CLOSED, forfeitedCredits = 2_000)))
        // an anonymised order without a credit part is an ordinary order
        val plain = Order(10_000, 10_000, 0, 0, anonymised = true)
        assertTrue(ok(RefundSplit.compute(plain, Request(), partial)).warnings.isEmpty())
    }

    @Test
    fun `U-R14 in a zero-decimal currency every amount is a multiple of 100`() {
        val order = Order(totalPrice = 100_000, gatewayAmount = 60_000, creditValue = 40_000, creditAmount = 400_000, unit = 100) // 1000 JPY
        // 333 JPY of 1000: credit value share 133.2 JPY -> 133 JPY, never 133.20
        val s = split(order, Request(amount = 33_300))
        assertEquals(Split(33_300, 20_000, 13_300, 133_000), s)
        for (part in listOf(s.amount, s.gatewayPart, s.creditValuePart)) assertEquals(0L, part % 100)
        // an amount in the middle of a yen is refused
        assertEquals(Problem.NOT_A_MULTIPLE_OF_UNIT, invalid(RefundSplit.compute(order, Request(amount = 33_350), partial)).problem)
        assertEquals(Problem.NOT_A_MULTIPLE_OF_UNIT, invalid(RefundSplit.compute(order, Request(Mode.OVERRIDE, gatewayAmount = 150), partial)).problem)
        assertEquals(Problem.NOT_A_MULTIPLE_OF_UNIT, invalid(RefundSplit.compute(order, Request(Mode.OVERRIDE, amount = 150, creditAmount = 1_000), partial)).problem)
        // a credit override rounds the value to a whole yen, half up: 3330 credit cents = 3.33 yen -> 3, 1250 = 1.25 -> 1, 1500 = 1.5 -> 2
        val c = split(order, Request(Mode.OVERRIDE, creditAmount = 3_330))
        assertEquals(Split(300, 0, 300, 3_330), c)
        assertEquals(100L, split(order, Request(Mode.OVERRIDE, creditAmount = 1_250)).creditValuePart)
        assertEquals(200L, split(order, Request(Mode.OVERRIDE, creditAmount = 1_500)).creditValuePart)
        // a credit amount worth less than half a yen is no refund at all
        assertEquals(Problem.AMOUNT_OUT_OF_RANGE, invalid(RefundSplit.compute(order, Request(Mode.OVERRIDE, creditAmount = 333), partial)).problem)
    }

    @Test
    fun `U-R15 the preview warnings`() {
        // both parts: the mixed split warning with both amounts
        val mixed = ok(RefundSplit.compute(mixedOrder(), Request(amount = 5_000), partial))
        assertEquals(listOf(Warning(WarningCode.MIXED_PAYMENT_SPLIT, gatewayAmount = 3_000, creditAmount = 2_000)), mixed.warnings)
        // gateway only: no warning
        assertTrue(ok(RefundSplit.compute(mixedOrder(), Request(Mode.OVERRIDE, gatewayAmount = 2_000), partial)).warnings.isEmpty())
        // credit only on an order that has a gateway part: the credit-only warning
        val creditOnly = ok(RefundSplit.compute(mixedOrder(), Request(Mode.OVERRIDE, creditAmount = 1_000), partial))
        assertEquals(listOf(Warning(WarningCode.CREDIT_ONLY_REFUND)), creditOnly.warnings)
        // credit only on a full-credit order is the normal refund: no warning
        val full = Order(10_000, 0, 10_000, 10_000)
        assertTrue(ok(RefundSplit.compute(full, Request(amount = 3_000), null)).warnings.isEmpty())
        // a gateway-only order: no warning
        assertTrue(ok(RefundSplit.compute(Order(10_000, 10_000, 0, 0), Request(), partial)).warnings.isEmpty())
    }

    // ---------------------------------------------------------------- credits that have no money value left

    /** 05 section 8.1 / section 17 row 53: a credits-only product (money price 0, credit price 40.00) paid with credits: total 0, credit value 0, 40.00 credits. */
    private val creditsOnly = Order(totalPrice = 0, gatewayAmount = 0, creditValue = 0, creditAmount = 4_000)

    @Test
    fun `U-R16 a credits-only order is refunded in credits although its money value is 0`() {
        // the default is everything that remains: no money, no gateway, 40.00 credits
        val whole = ok(RefundSplit.compute(creditsOnly, Request(), null))
        assertEquals(Split(0, 0, 0, 4_000), whole.split)
        assertEquals(Limits(max = 0, maxGateway = 0, maxCreditValue = 0, maxCredit = 4_000), whole.limits)
        assertTrue(whole.warnings.isEmpty(), "${whole.warnings}")
        // an override names the credits; the amount may be omitted or 0, a gateway part 0 is the same as none
        assertEquals(Split(0, 0, 0, 1_500), split(creditsOnly, Request(Mode.OVERRIDE, creditAmount = 1_500), null))
        assertEquals(Split(0, 0, 0, 1_500), split(creditsOnly, Request(Mode.OVERRIDE, amount = 0, creditAmount = 1_500), null))
        assertEquals(Split(0, 0, 0, 1_500), split(creditsOnly, Request(Mode.OVERRIDE, gatewayAmount = 0, creditAmount = 1_500), null))
        assertEquals(Split(0, 0, 0, 1_500), split(creditsOnly, Request(Mode.OVERRIDE, amount = 0, gatewayAmount = 0, creditAmount = 1_500), null))
        assertEquals(Split(0, 0, 0, 4_000), split(creditsOnly, Request(Mode.OVERRIDE, creditAmount = 4_000), null))
        assertEquals(Split(0, 0, 0, 1), split(creditsOnly, Request(Mode.OVERRIDE, creditAmount = 1), null))
        // no gateway capability applies: there is no gateway part
        for (gateway in listOf(null, GatewayRefund(RefundSupport.NONE), GatewayRefund(RefundSupport.FULL_ONLY), partial)) {
            assertEquals(Split(0, 0, 0, 4_000), split(creditsOnly, Request(), gateway), "$gateway")
        }
        // in a zero-decimal currency too
        assertEquals(Split(0, 0, 0, 2_500), split(creditsOnly.copy(unit = 100), Request(Mode.OVERRIDE, creditAmount = 2_500), null))
    }

    @Test
    fun `a credits-only order refuses what it cannot return`() {
        val refused = listOf(
            // above what remains, below zero, zero
            Request(Mode.OVERRIDE, creditAmount = 4_001) to Problem.CREDIT_PART_OUT_OF_RANGE,
            Request(Mode.OVERRIDE, creditAmount = Long.MAX_VALUE) to Problem.CREDIT_PART_OUT_OF_RANGE,
            Request(Mode.OVERRIDE, creditAmount = -1) to Problem.CREDIT_PART_OUT_OF_RANGE,
            Request(Mode.OVERRIDE, creditAmount = 0) to Problem.AMOUNT_OUT_OF_RANGE,
            // money that is not there
            Request(Mode.OVERRIDE, gatewayAmount = 100) to Problem.GATEWAY_PART_OUT_OF_RANGE,
            Request(Mode.OVERRIDE, gatewayAmount = 100, creditAmount = 1_000) to Problem.GATEWAY_PART_OUT_OF_RANGE,
            Request(Mode.OVERRIDE, amount = 100, creditAmount = 1_000) to Problem.AMOUNT_OUT_OF_RANGE,
            Request(Mode.OVERRIDE, amount = -100, creditAmount = 1_000) to Problem.AMOUNT_OUT_OF_RANGE,
            // naming no credits
            Request(Mode.OVERRIDE) to Problem.NOTHING_TO_REFUND,
            Request(Mode.OVERRIDE, gatewayAmount = 0) to Problem.AMOUNT_OUT_OF_RANGE,
            Request(Mode.OVERRIDE, amount = 0) to Problem.NOTHING_TO_REFUND,
            // a proportional request with an amount is a request for money; an item refund of lines that cost 0 money cannot say how many credits
            Request(amount = 0) to Problem.AMOUNT_OUT_OF_RANGE,
            Request(amount = 100) to Problem.AMOUNT_OUT_OF_RANGE,
            Request(amount = -1) to Problem.AMOUNT_OUT_OF_RANGE
        )
        for ((request, problem) in refused) {
            val r = invalid(RefundSplit.compute(creditsOnly, request, partial))
            assertEquals(problem, r.problem, "$request")
            assertEquals(Limits(max = 0, maxGateway = 0, maxCreditValue = 0, maxCredit = 4_000), r.limits, "$request")
        }
        // an anonymised order cannot return credits at all: nothing to refund
        val anon = creditsOnly.copy(anonymised = true)
        assertEquals(Problem.AMOUNT_OUT_OF_RANGE, invalid(RefundSplit.compute(anon, Request(), null)).problem)
        assertEquals(Problem.CREDIT_PART_OUT_OF_RANGE, invalid(RefundSplit.compute(anon, Request(Mode.OVERRIDE, creditAmount = 1_500), null)).problem)
        assertEquals(Limits(0, 0, 0, 0), invalid(RefundSplit.compute(anon, Request(), null)).limits)
        // all credits back: the order is done
        val done = creditsOnly.copy(refundedCreditAmount = 4_000)
        assertEquals(Limits(0, 0, 0, 0), invalid(RefundSplit.compute(done, Request(), null)).limits)
        assertEquals(Problem.CREDIT_PART_OUT_OF_RANGE, invalid(RefundSplit.compute(done, Request(Mode.OVERRIDE, creditAmount = 1), null)).problem)
        // credits of a refund still in flight are not available again
        val inFlight = creditsOnly.copy(inFlightCreditAmount = 1_500)
        assertEquals(Split(0, 0, 0, 2_500), split(inFlight, Request(), null))
        assertEquals(Problem.CREDIT_PART_OUT_OF_RANGE, invalid(RefundSplit.compute(inFlight, Request(Mode.OVERRIDE, creditAmount = 2_501), null)).problem)
        assertEquals(Limits(0, 0, 0, 2_500), invalid(RefundSplit.compute(inFlight, Request(Mode.OVERRIDE, creditAmount = 2_501), null)).limits)
        assertEquals(Limits(0, 0, 0, 0), invalid(RefundSplit.compute(creditsOnly.copy(inFlightCreditAmount = 4_000), Request(), null)).limits)
    }

    @Test
    fun `credits-only refunds in parts return exactly the credits that were spent`() {
        var order = creditsOnly
        for (part in listOf(1_500L, 1L, 999L)) {
            val s = split(order, Request(Mode.OVERRIDE, creditAmount = part), null)
            assertEquals(Split(0, 0, 0, part), s)
            order = order.copy(refundedCreditAmount = order.refundedCreditAmount + s.creditPart)
        }
        // what is left: 4 000 - 2 500 = 1 500
        assertEquals(Split(0, 0, 0, 1_500), split(order, Request(), null))
        assertEquals(Problem.CREDIT_PART_OUT_OF_RANGE, invalid(RefundSplit.compute(order, Request(Mode.OVERRIDE, creditAmount = 1_501), null)).problem)
        order = order.copy(refundedCreditAmount = 4_000)
        assertEquals(0L, invalid(RefundSplit.compute(order, Request(), null)).limits.maxCredit)
    }

    @Test
    fun `a credit remainder that is worth nothing can be returned, but not when money is still there`() {
        // 40.00 credits worth 4.00 (a rate of 0.10), 60.00 at the gateway. A credit override of 399.99 of 400.00 rounds its value to the
        // whole 40.00 credit value, a gateway refund of the 60.00 follows: the order has no money left, 0.01 credits do
        val order0 = mixedOrder(ca = 40_000)
        val first = split(order0, Request(Mode.OVERRIDE, creditAmount = 39_999))
        assertEquals(Split(4_000, 0, 4_000, 39_999), first)
        val afterFirst = order0.copy(refundedTotal = 4_000, refundedCreditAmount = 39_999)
        // while the gateway part remains the remainder is an ordinary one (the final whole refund takes it with the gateway part)
        assertEquals(Split(6_000, 6_000, 0, 1), split(afterFirst, Request()))
        // the gateway part goes back first, as a gateway-only override that leaves the credit part alone
        val gatewayOnly = split(afterFirst, Request(Mode.OVERRIDE, gatewayAmount = 6_000))
        assertEquals(Split(6_000, 6_000, 0, 1), gatewayOnly, "the remainder rule takes the credit dust with it")
        // ... the other order of events: the gateway part first while credit value remained, then the credit override
        val g = split(order0, Request(Mode.OVERRIDE, gatewayAmount = 6_000))
        assertEquals(Split(6_000, 6_000, 0, 0), g)
        val afterGateway = order0.copy(refundedTotal = 6_000, refundedGatewayAmount = 6_000)
        val c = split(afterGateway, Request(Mode.OVERRIDE, creditAmount = 39_999))
        assertEquals(Split(4_000, 0, 4_000, 39_999), c)
        val dust = afterGateway.copy(refundedTotal = 10_000, refundedCreditAmount = 39_999)
        val r = ok(RefundSplit.compute(dust, Request(), null))
        assertEquals(Split(0, 0, 0, 1), r.split, "the last 0.01 credits are the buyer's")
        assertEquals(Limits(0, 0, 0, 1), r.limits)
        assertEquals(Split(0, 0, 0, 1), split(dust, Request(Mode.OVERRIDE, creditAmount = 1), GatewayRefund(RefundSupport.NONE)))
        assertEquals(Problem.CREDIT_PART_OUT_OF_RANGE, invalid(RefundSplit.compute(dust, Request(Mode.OVERRIDE, creditAmount = 2), null)).problem)

        // a damaged order is not a credit remainder: the books say the order is refunded in full (the gateway over-refunded),
        // 5.00 of credit value is still booked, so no credits are given on top of that
        val damaged = mixedOrder(rT = 10_000, rG = 6_500)
        assertEquals(Limits(max = 0, maxGateway = 0, maxCreditValue = 500, maxCredit = 4_000), invalid(RefundSplit.compute(damaged, Request(), partial)).limits)
        assertEquals(Problem.AMOUNT_OUT_OF_RANGE, invalid(RefundSplit.compute(damaged, Request(), partial)).problem)
        assertEquals(Problem.AMOUNT_OUT_OF_RANGE, invalid(RefundSplit.compute(damaged, Request(Mode.OVERRIDE, creditAmount = 4_000), partial)).problem)
        // nor is an order whose total is refunded while the gateway part is still there
        val odd = mixedOrder(rT = 10_000, rG = 0)
        assertEquals(Problem.AMOUNT_OUT_OF_RANGE, invalid(RefundSplit.compute(odd, Request(), partial)).problem)
        assertEquals(Problem.AMOUNT_OUT_OF_RANGE, invalid(RefundSplit.compute(odd, Request(Mode.OVERRIDE, creditAmount = 1_000), partial)).problem)
        // and a gateway-only override of 0 does not turn a credit remainder into a refund of nothing
        assertEquals(Problem.AMOUNT_OUT_OF_RANGE, invalid(RefundSplit.compute(dust, Request(Mode.OVERRIDE, gatewayAmount = 0), partial)).problem)
    }

    @Test
    fun `an item refund of a full-credit order is split by money value, not by the credit prices of the lines`() {
        // known limitation (evidence MK-090): vip 100.00 for 100.00 credits plus a credits-only perk of 40.00 credits: T 100.00, CA 140.00.
        // A refund of 50.00 (half of the vip) returns 70.00 credits, because the split follows the money record; the whole adds up exactly.
        val order = Order(totalPrice = 10_000, gatewayAmount = 0, creditValue = 10_000, creditAmount = 14_000)
        val first = split(order, Request(amount = 5_000), null)
        assertEquals(Split(5_000, 0, 5_000, 7_000), first)
        val rest = split(order.copy(refundedTotal = first.amount, refundedCreditAmount = first.creditPart), Request(), null)
        assertEquals(Split(5_000, 0, 5_000, 7_000), rest)
        assertEquals(14_000L, first.creditPart + rest.creditPart)
    }

    // ---------------------------------------------------------------- in flight, damaged orders

    @Test
    fun `D-R4 refunds in flight reduce what can be refunded`() {
        // refund #1 (100.00, gateway part 60.00, 40 credits) is PENDING at an async gateway; a credit-only refund of 40.00 is asked for
        val order = mixedOrder(pT = 10_000, pG = 6_000, pCA = 4_000)
        val r = invalid(RefundSplit.compute(order, Request(Mode.OVERRIDE, creditAmount = 4_000), partial))
        assertEquals(0L, r.limits.max)
        assertEquals(0L, r.limits.maxCredit)
        assertEquals(0L, invalid(RefundSplit.compute(order, Request(amount = 4_000), partial)).limits.max)
        // part in flight, part already refunded
        val mixed = mixedOrder(rT = 2_500, rG = 1_500, rCA = 1_000, pT = 2_500, pG = 1_500, pCA = 1_000)
        assertEquals(Split(5_000, 3_000, 2_000, 2_000), split(mixed))
        assertEquals(5_000L, invalid(RefundSplit.compute(mixed, Request(amount = 5_001), partial)).limits.max)
        // the gateway part in flight is not available again: 60.00 - 15.00 refunded - 15.00 in flight = 30.00 left
        val g = invalid(RefundSplit.compute(mixed, Request(Mode.OVERRIDE, gatewayAmount = 3_500), partial))
        assertEquals(Problem.GATEWAY_PART_OUT_OF_RANGE, g.problem)
        assertEquals(3_000L, g.limits.maxGateway)
        assertEquals(Split(3_000, 3_000, 0, 0), split(mixed, Request(Mode.OVERRIDE, gatewayAmount = 3_000)))
        // and the credits in flight are not available again either: 40.00 - 10.00 - 10.00 = 20.00
        assertEquals(Problem.CREDIT_PART_OUT_OF_RANGE, invalid(RefundSplit.compute(mixed, Request(Mode.OVERRIDE, creditAmount = 2_500), partial)).problem)
    }

    @Test
    fun `an order that was over-refunded at the gateway can only be refunded what is really left`() {
        // the gateway refunded 65.00 of 60.00 while the panel refund was pending: remG counts as 0, never negative
        val damaged = mixedOrder(rT = 6_500, rG = 6_500)
        val r = ok(RefundSplit.compute(damaged, Request(), partial))
        assertEquals(Limits(max = 3_500, maxGateway = 0, maxCreditValue = 4_000, maxCredit = 4_000), r.limits)
        assertEquals(0L, r.split.gatewayPart)
        assertEquals(3_500L, r.split.amount)
        assertEquals(3_500L, r.split.creditValuePart)
        assertEquals(3_500L, r.split.creditPart)
        // refunded total above the total price: nothing is left, nothing is refunded
        val over = mixedOrder(rT = 10_500, rG = 7_000, rCA = 4_000)
        assertEquals(0L, invalid(RefundSplit.compute(over, Request(), partial)).limits.max)
        // gateway refunded more than its share and no credit value left
        val noCredit = mixedOrder(rT = 10_000, rG = 6_500, rCA = 4_000)
        assertEquals(0L, invalid(RefundSplit.compute(noCredit, Request(), partial)).limits.max)
        // refunded gateway above refunded total (inconsistent) is read as no refunded credit value
        val odd = Order(10_000, 6_000, 4_000, 4_000, refundedTotal = 100, refundedGatewayAmount = 500)
        assertEquals(4_000L, ok(RefundSplit.compute(odd, Request(Mode.OVERRIDE, creditAmount = 4_000), partial)).split.creditValuePart)
    }

    @Test
    fun `figures that make no sense are programming errors and a free order refunds nothing`() {
        assertThrows(IllegalArgumentException::class.java) { Order(-1, 0, 0, 0) }
        assertThrows(IllegalArgumentException::class.java) { Order(1, 1, 0, 0, unit = 10) }
        assertThrows(IllegalArgumentException::class.java) { Request(Mode.PROPORTIONAL, gatewayAmount = 1) }
        assertThrows(IllegalArgumentException::class.java) { Request(Mode.PROPORTIONAL, creditAmount = 1) }
        val free = Order(0, 0, 0, 0)
        assertEquals(0L, invalid(RefundSplit.compute(free, Request(), partial)).limits.max)
        assertEquals(Problem.AMOUNT_OUT_OF_RANGE, invalid(RefundSplit.compute(free, Request(), partial)).problem)
    }

    // ---------------------------------------------------------------- properties

    private fun halfUpToUnit(num: BigInteger, den: BigInteger, unit: Long): Long {
        // round(num / den / unit) * unit with ties up, on integers
        val u = BigInteger.valueOf(unit)
        val q = num.multiply(BigInteger.TWO).add(den.multiply(u)).divide(den.multiply(u).multiply(BigInteger.TWO))
        return q.multiply(u).toLong()
    }

    private class Branches {
        private val seen = java.util.TreeMap<String, Int>()
        fun hit(name: String) { seen.merge(name, 1) { a, b -> a + b } }
        fun require(vararg names: String, atLeast: Int = 20) {
            for (n in names) assertTrue((seen[n] ?: 0) >= atLeast, "the loop must exercise '$n' at least $atLeast times: $seen")
        }
    }

    private fun randomOrder(rnd: Random): Order {
        val unit = if (rnd.nextInt(4) == 0) 100L else 1L
        val units = when (rnd.nextInt(4)) { 0 -> rnd.nextLong(1, 30); 1 -> rnd.nextLong(1, 1_000); 2 -> rnd.nextLong(1, 100_000); else -> rnd.nextLong(1, 100_000_000) }
        val t = units * unit
        val g = when (rnd.nextInt(5)) { 0 -> 0L; 1 -> t; else -> rnd.nextLong(0, units + 1) * unit }
        val cv = t - g
        val ca = if (cv == 0L) 0L else maxOf(1L, cv * 100 / rnd.nextLong(1, 5_000).let { if (rnd.nextBoolean()) it else 100 })
        return Order(t, g, cv, ca, unit = unit)
    }

    @Test
    fun `property loop 1 a sequence of proportional refunds returns exactly what was paid on every side`() {
        val rnd = Random(20261104)
        val b = Branches()
        repeat(4_000) { n ->
            val o0 = randomOrder(rnd)
            val unit = o0.unit
            var rT = 0L
            var rG = 0L
            var rCA = 0L
            var steps = 0
            while (rT < o0.totalPrice) {
                val order = o0.copy(refundedTotal = rT, refundedGatewayAmount = rG, refundedCreditAmount = rCA)
                val remT = o0.totalPrice - rT
                val remG = o0.gatewayAmount - rG
                val remCV = o0.creditValue - (rT - rG)
                val remCA = o0.creditAmount - rCA
                val whole = rnd.nextInt(4) == 0
                val amount = if (whole) remT else rnd.nextLong(1, remT / unit + 1) * unit
                val request = if (whole && rnd.nextBoolean()) Request() else Request(amount = amount)
                val where = "#$n step $steps $order amount=$amount"
                val s = ok(RefundSplit.compute(order, request, partial)).split

                // the three identities and the bounds of 07 section 7.1
                assertEquals(amount, s.amount, where)
                assertEquals(s.amount, s.gatewayPart + s.creditValuePart, "gateway + credit value == amount $where")
                assertTrue(s.gatewayPart in 0..remG, "gateway part within its remainder $where")
                assertTrue(s.creditValuePart in 0..remCV, "credit value part within its remainder $where")
                assertTrue(s.creditPart in 0..remCA, "credit part within its remainder $where")
                for (part in listOf(s.amount, s.gatewayPart, s.creditValuePart)) assertEquals(0L, part % unit, "on the unit $where")

                // an independent oracle of the formula
                val expectedCv: Long
                if (amount == remT) {
                    expectedCv = remCV
                } else {
                    val raw = if (o0.totalPrice == 0L) 0L else halfUpToUnit(BigInteger.valueOf(amount).multiply(BigInteger.valueOf(o0.creditValue)), BigInteger.valueOf(o0.totalPrice), unit)
                    val lo = maxOf(0L, amount - remG)
                    val hi = minOf(remCV, amount)
                    expectedCv = raw.coerceIn(lo, hi)
                    if (raw < lo) b.hit("clamped up by the gateway remainder")
                    if (raw > hi) b.hit("clamped down by the credit value remainder")
                    if (raw in lo..hi) {
                        // proportional within one unit of the exact ratio
                        val exactNum = BigInteger.valueOf(amount).multiply(BigInteger.valueOf(o0.creditValue))
                        val diff = exactNum.subtract(BigInteger.valueOf(s.creditValuePart).multiply(BigInteger.valueOf(o0.totalPrice))).abs()
                        assertTrue(diff <= BigInteger.valueOf(unit).multiply(BigInteger.valueOf(o0.totalPrice)), "within one unit of the exact share $where")
                        b.hit("unclamped partial")
                    }
                }
                assertEquals(expectedCv, s.creditValuePart, "credit value part $where")
                val expectedCredits = if (s.creditValuePart == remCV) remCA
                else if (o0.creditValue == 0L) 0L
                else minOf(remCA, halfUpToUnit(BigInteger.valueOf(o0.creditAmount).multiply(BigInteger.valueOf(s.creditValuePart)), BigInteger.valueOf(o0.creditValue), 1))
                assertEquals(expectedCredits, s.creditPart, "credit part $where")

                rT += s.amount
                rG += s.gatewayPart
                rCA += s.creditPart
                steps++
                if (whole) b.hit("whole remainder")
                if (unit == 100L) b.hit("zero-decimal")
            }
            // the remainder rule: everything paid comes back, exactly
            assertEquals(o0.totalPrice, rT, "#$n total")
            assertEquals(o0.gatewayAmount, rG, "#$n gateway ${o0}")
            assertEquals(o0.creditValue, rT - rG, "#$n credit value")
            assertEquals(o0.creditAmount, rCA, "#$n credits")
            b.hit(if (o0.creditAmount > 0 && o0.gatewayAmount > 0) "mixed order" else if (o0.creditAmount > 0) "full-credit order" else "gateway order")
            if (steps > 1) b.hit("several refunds")
        }
        b.require("unclamped partial", "whole remainder", "zero-decimal", "mixed order", "full-credit order", "gateway order", "several refunds", atLeast = 500)
        // the clamps are rare after proportional refunds only (rounding by one unit); U-R7 and the override loop reach them directly
        b.require("clamped up by the gateway remainder", "clamped down by the credit value remainder", atLeast = 3)
    }

    @Test
    fun `property loop 2 override requests keep every constraint and never create money`() {
        val rnd = Random(20261105)
        val b = Branches()
        repeat(6_000) { n ->
            val o0 = randomOrder(rnd)
            val unit = o0.unit
            // a random earlier history: one proportional or override refund that really happened
            var order = o0
            if (rnd.nextBoolean() && o0.totalPrice > unit) {
                val a = rnd.nextLong(1, o0.totalPrice / unit) * unit
                val r = RefundSplit.compute(o0, Request(amount = a), partial)
                if (r is Result.Ok) order = o0.copy(refundedTotal = r.split.amount, refundedGatewayAmount = r.split.gatewayPart, refundedCreditAmount = r.split.creditPart)
            }
            val remG = order.gatewayAmount - order.refundedGatewayAmount
            val remCA = order.creditAmount - order.refundedCreditAmount
            val remT = order.totalPrice - order.refundedTotal
            val request = when (rnd.nextInt(6)) {
                0 -> Request(Mode.OVERRIDE, gatewayAmount = rnd.nextLong(0, remG / unit + 2) * unit)
                1 -> Request(Mode.OVERRIDE, creditAmount = rnd.nextLong(0, remCA + 2))
                2 -> Request(Mode.OVERRIDE, gatewayAmount = rnd.nextLong(0, remG / unit + 2) * unit, creditAmount = rnd.nextLong(0, remCA + 2))
                3 -> Request(Mode.OVERRIDE, amount = rnd.nextLong(1, remT / unit + 2) * unit, gatewayAmount = rnd.nextLong(0, remG / unit + 2) * unit)
                4 -> Request(Mode.OVERRIDE, amount = rnd.nextLong(1, remT / unit + 2) * unit, creditAmount = rnd.nextLong(0, remCA + 2))
                else -> Request(Mode.PROPORTIONAL, amount = rnd.nextLong(-1, remT / unit + 3).let { if (it > 0) it * unit else it })
            }
            val gateways = listOf(
                partial, GatewayRefund(RefundSupport.FULL_ONLY), GatewayRefund(RefundSupport.NONE), GatewayRefund(RefundSupport.NONE, true),
                GatewayRefund(RefundSupport.FULL_ONLY, true), GatewayRefund(RefundSupport.PARTIAL, true), GatewayRefund(RefundSupport.PER_LINE), null
            )
            val gateway = gateways[rnd.nextInt(gateways.size)]
            val where = "#$n $order $request"
            val result = RefundSplit.compute(order, request, gateway)
            val split = when (result) {
                is Result.Ok -> { b.hit("ok"); result.split }
                is Result.NotSupported -> { b.hit("not supported"); result.split }
                is Result.Invalid -> { b.hit("invalid ${result.problem}"); null }
            }
            val limits = when (result) {
                is Result.Ok -> result.limits
                is Result.NotSupported -> result.limits
                is Result.Invalid -> result.limits
            }
            // the limits are what remains, never negative
            assertEquals(Limits(remT, remG, order.creditValue - (order.refundedTotal - order.refundedGatewayAmount), remCA), limits, "limits $where")
            // `manual` means no provider call: it lifts every capability refusal, whatever the gateway can do
            if (gateway?.manual == true) assertTrue(result !is Result.NotSupported, "a manual refund is never refused for the gateway $where")
            if (split != null) {
                // a refund is above zero; the one exception is the credits of an order that has no money left (amount 0, credits only)
                if (split.amount == 0L) {
                    assertTrue(split.creditPart > 0 && split.gatewayPart == 0L && split.creditValuePart == 0L, "a refund of value 0 is a credit-only refund $where")
                    assertEquals(0L, remT, "a refund of value 0 only when no money is left $where")
                    b.hit("credits without money")
                } else {
                    assertTrue(split.amount > 0, "a refund is above zero $where")
                }
                if (gateway?.manual == true && gateway.support != RefundSupport.NONE && split.gatewayPart > 0 && result is Result.Ok) b.hit("manual gateway part")
                if (gateway?.manual == true && gateway.support == RefundSupport.FULL_ONLY && split.gatewayPart > 0 && split.gatewayPart != order.gatewayAmount && result is Result.Ok) b.hit("manual partial gateway part at full-only")
                assertEquals(split.amount, split.gatewayPart + split.creditValuePart, "gateway + credit value == amount $where")
                assertTrue(split.gatewayPart in 0..remG, "gateway bound $where")
                assertTrue(split.creditValuePart in 0..limits.maxCreditValue, "credit value bound $where")
                assertTrue(split.creditPart in 0..remCA, "credit bound $where")
                assertTrue(split.amount <= remT, "amount bound $where")
                // the parts the admin named are the parts that come back
                request.gatewayAmount?.let { assertEquals(it, split.gatewayPart, "named gateway part $where") }
                request.creditAmount?.let { assertEquals(it, split.creditPart, "named credit part $where") }
                if (request.mode == Mode.OVERRIDE && request.amount != null) assertEquals(request.amount, split.amount, "named amount $where")
                // gateway money is never turned into credits: no gateway part means no gateway call is needed
                if (split.gatewayPart == 0L) assertTrue(result is Result.Ok, "credit-only needs no gateway $where")
                if (result is Result.NotSupported) assertTrue(split.gatewayPart > 0, "$where")
            }
            // a gateway-only override within the remainder is always accepted
            if (request.mode == Mode.OVERRIDE && request.creditAmount == null && request.amount == null) {
                val g = request.gatewayAmount!!
                if (g in 1..remG) assertTrue(result !is Result.Invalid, "a valid gateway-only override is not refused $where")
            }
        }
        b.require("ok", "not supported", atLeast = 200)
        b.require("manual gateway part", "manual partial gateway part at full-only", atLeast = 100)
        b.require("invalid AMOUNT_OUT_OF_RANGE", "invalid GATEWAY_PART_OUT_OF_RANGE", "invalid CREDIT_PART_OUT_OF_RANGE", "invalid AMOUNT_MISMATCH", atLeast = 10)
    }

    @Test
    fun `property loop 3 an anonymised or damaged order never refunds credits or more money than it has`() {
        val rnd = Random(20261106)
        var oks = 0
        repeat(4_000) { n ->
            val o0 = randomOrder(rnd)
            val rG = if (o0.gatewayAmount > 0) rnd.nextLong(0, o0.gatewayAmount / o0.unit + 2) * o0.unit else 0L // may exceed the gateway amount
            val rT = rG + if (o0.creditValue > 0) rnd.nextLong(0, o0.creditValue / o0.unit + 2) * o0.unit else 0L
            val rCA = if (o0.creditAmount > 0) rnd.nextLong(0, o0.creditAmount + 2) else 0L
            val order = o0.copy(refundedTotal = rT, refundedGatewayAmount = rG, refundedCreditAmount = rCA, anonymised = rnd.nextBoolean())
            val result = RefundSplit.compute(order, Request(), partial)
            val where = "#$n $order"
            if (result is Result.Ok) {
                oks++
                val s = result.split
                assertEquals(s.amount, s.gatewayPart + s.creditValuePart, where)
                assertTrue(s.gatewayPart <= maxOf(0L, order.gatewayAmount - rG), "no more gateway money than the gateway took $where")
                assertTrue(s.creditPart <= maxOf(0L, order.creditAmount - rCA), "no more credits than were spent $where")
                assertTrue(s.amount <= maxOf(0L, order.totalPrice - rT), "no more than the order total $where")
                if (order.anonymised && order.creditAmount > 0) {
                    assertEquals(0L, s.creditPart, "an anonymised order returns no credits $where")
                    assertEquals(0L, s.creditValuePart, "an anonymised order has no credit value part $where")
                }
            } else {
                assertTrue(result is Result.Invalid, where)
            }
        }
        assertTrue(oks > 500, "ok results: $oks")
    }

    @Test
    fun `property loop 4 a credits-only order gets back exactly the credits it spent, in any number of refunds`() {
        val rnd = Random(20261107)
        val b = Branches()
        repeat(3_000) { n ->
            val ca = when (rnd.nextInt(3)) { 0 -> rnd.nextLong(1, 100); 1 -> rnd.nextLong(1, 100_000); else -> rnd.nextLong(1, 1_000_000_000) }
            val o0 = Order(totalPrice = 0, gatewayAmount = 0, creditValue = 0, creditAmount = ca, unit = if (rnd.nextInt(4) == 0) 100L else 1L)
            var returned = 0L
            var steps = 0
            while (returned < ca) {
                check(steps++ < 2_000) { "#$n does not end: $o0 returned=$returned" }
                // a refund of another request may be in flight and takes credits off the limit (never all of them)
                val inFlight = if (rnd.nextInt(5) == 0) rnd.nextLong(0, ca - returned) else 0L
                val order = o0.copy(refundedCreditAmount = returned, inFlightCreditAmount = inFlight)
                val avail = ca - returned - inFlight
                val where = "#$n $order"
                val gateways = listOf(null, partial, GatewayRefund(RefundSupport.NONE), GatewayRefund(RefundSupport.FULL_ONLY), GatewayRefund(RefundSupport.PER_LINE, true))
                val gateway = gateways[rnd.nextInt(gateways.size)]
                val named = rnd.nextLong(1, avail + 1)
                val limits = Limits(max = 0, maxGateway = 0, maxCreditValue = 0, maxCredit = avail)
                when (rnd.nextInt(8)) {
                    0, 1 -> { // everything that remains
                        val r = ok(RefundSplit.compute(order, Request(), gateway))
                        assertEquals(Split(0, 0, 0, avail), r.split, where)
                        assertEquals(limits, r.limits, where)
                        assertTrue(r.warnings.isEmpty(), where)
                        returned += avail
                        b.hit("whole remainder")
                    }
                    2, 3 -> { // named credits
                        val r = ok(RefundSplit.compute(order, Request(Mode.OVERRIDE, creditAmount = named), gateway))
                        assertEquals(Split(0, 0, 0, named), r.split, where)
                        returned += named
                        b.hit("named credits")
                    }
                    4 -> { // named credits with the value fields spelled out as 0
                        val r = ok(RefundSplit.compute(order, Request(Mode.OVERRIDE, amount = 0, gatewayAmount = 0, creditAmount = named), gateway))
                        assertEquals(Split(0, 0, 0, named), r.split, where)
                        returned += named
                        b.hit("zeros spelled out")
                    }
                    5 -> { // more than remains, or money that is not there
                        val r = invalid(RefundSplit.compute(order, Request(Mode.OVERRIDE, creditAmount = avail + rnd.nextLong(1, 1_000)), gateway))
                        assertEquals(Problem.CREDIT_PART_OUT_OF_RANGE, r.problem, where)
                        assertEquals(limits, r.limits, where)
                        val g = invalid(RefundSplit.compute(order, Request(Mode.OVERRIDE, gatewayAmount = rnd.nextLong(1, 100) * o0.unit, creditAmount = named), gateway))
                        assertEquals(Problem.GATEWAY_PART_OUT_OF_RANGE, g.problem, where)
                        val a = invalid(RefundSplit.compute(order, Request(Mode.OVERRIDE, amount = rnd.nextLong(1, 100) * o0.unit, creditAmount = named), gateway))
                        assertEquals(Problem.AMOUNT_OUT_OF_RANGE, a.problem, where)
                        b.hit("refused")
                    }
                    6 -> { // a request for money names no credits and returns none
                        val amount = rnd.nextLong(-3, 1_000)
                        assertEquals(Problem.AMOUNT_OUT_OF_RANGE, invalid(RefundSplit.compute(order, Request(amount = amount), gateway)).problem, "$where amount=$amount")
                        assertEquals(Problem.AMOUNT_OUT_OF_RANGE, invalid(RefundSplit.compute(order, Request(Mode.OVERRIDE, gatewayAmount = 0), gateway)).problem, where)
                        assertEquals(Problem.AMOUNT_OUT_OF_RANGE, invalid(RefundSplit.compute(order, Request(Mode.OVERRIDE, creditAmount = 0), gateway)).problem, where)
                        b.hit("no credits named")
                    }
                    else -> { // the same order, anonymised, can return nothing
                        val anon = order.copy(anonymised = true)
                        assertEquals(Limits(0, 0, 0, 0), invalid(RefundSplit.compute(anon, Request(), gateway)).limits, where)
                        assertTrue(RefundSplit.compute(anon, Request(Mode.OVERRIDE, creditAmount = named), gateway) is Result.Invalid, where)
                        b.hit("anonymised")
                    }
                }
                if (inFlight > 0) b.hit("with a refund in flight")
            }
            assertEquals(ca, returned, "#$n returned exactly what was spent")
            val done = o0.copy(refundedCreditAmount = returned)
            assertEquals(Limits(0, 0, 0, 0), invalid(RefundSplit.compute(done, Request(), null)).limits, "#$n nothing is left")
            assertEquals(Problem.CREDIT_PART_OUT_OF_RANGE, invalid(RefundSplit.compute(done, Request(Mode.OVERRIDE, creditAmount = 1), null)).problem, "#$n")
            if (steps > 1) b.hit("several refunds")
        }
        b.require("whole remainder", "named credits", "zeros spelled out", "refused", "no credits named", "anonymised", "with a refund in flight", "several refunds", atLeast = 300)
    }
}
