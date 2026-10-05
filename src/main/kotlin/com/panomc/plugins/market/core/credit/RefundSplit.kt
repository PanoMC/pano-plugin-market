package com.panomc.plugins.market.core.credit

import com.panomc.plugins.market.core.money.Rounding
import com.panomc.plugins.market.spi.payment.RefundSupport
import java.math.BigDecimal

/**
 * The split of one refund into its gateway part and its credit part (07 section 7.1). Pure.
 *
 * One refund row carries both parts: `amount = gatewayPart + creditValuePart`, and the credits that go back are
 * `creditPart` (credits x 100). A refund never turns gateway money into credits or the reverse ("refund as store credit"
 * is a manual grant), never returns more credits than were spent and never more gateway money than the gateway took.
 *
 * ```
 * remT  = T  - rT  - pT          rT = refundedTotal,            pT  = in flight (REQUESTED / PENDING refunds)
 * remG  = G  - rG  - pG          rG = refundedGatewayAmount,    pG  = in flight
 * remCV = CV - rCV - pCV         rCV = rT - rG                  pCV = pT - pG
 * remCA = CA - rCA - pCA         rCA = refundedCreditAmount     pCA = in flight
 *
 * PROPORTIONAL
 *   creditValuePart = remCV                                   when A == remT
 *                   = halfUp(A x CV / T) to the unit, clamped to [max(0, A - remG), min(remCV, A)]   otherwise
 *   gatewayPart     = A - creditValuePart
 *   creditPart      = remCA                                   when creditValuePart == remCV
 *                   = min(remCA, halfUp(CA x creditValuePart / CV))                                  otherwise (0 when CV == 0)
 * OVERRIDE: see [compute]
 * ```
 * `A` is the requested value (default `remT`). Every result satisfies `0 <= gatewayPart <= remG`,
 * `0 <= creditValuePart <= remCV`, `0 <= creditPart <= remCA` and `gatewayPart + creditValuePart = A > 0`, else the
 * answer is [Result.Invalid] (400 `INVALID_REFUND_AMOUNT {max, maxGateway, maxCredit}`). In a zero-decimal currency `A` and
 * both money parts are multiples of 100 ([Order.unit]).
 *
 * One deviation from `A > 0` (07 section 7.1), the credits that have no money value: an order paid with credits whose money
 * valuation is 0 (a credits-only product, 05 section 8.1 / 17 row 53: `T = 0`, `CV = 0`, `CA = 40.00`), or whose money is all
 * back while a rounding remainder of credits is left, has `remG = remCV = 0` and `remCA > 0`. Its credits are the buyer's and
 * can only come back as a credit-only refund of value 0: `Split(0, 0, 0, creditPart)`. That is accepted when `remG == 0`,
 * `remCV == 0` and `remCA > 0` (so never for an anonymised order, whose `remCA` is 0, and never for a damaged order that still
 * has a money remainder on one side), and only as PROPORTIONAL without an `amount` (all `remCA`) or as OVERRIDE with
 * `creditAmount` in `1..remCA`, no (or 0) `gatewayAmount` and no (or 0) `amount`. An explicit `amount` in PROPORTIONAL mode is a
 * request for money and stays refused: an item refund of lines that cost 0 money cannot say how many credits it means. The refund
 * row then has `amount = 0` and `creditAmount > 0` (the caller must accept that).
 *
 * Defensive reading of a damaged order: a gateway-originated refund that raced a panel refund can book `refundedTotal`
 * above `totalPrice` (07 section 7.2, alert `OVER_REFUND`), which makes a remainder negative. A negative remainder counts as 0
 * and `remT` never exceeds `remG + remCV`, so the answer can only be smaller, never a refund of money that is not there. For a
 * consistent order (`T = G + CV`, nothing over-refunded) this changes nothing.
 */
object RefundSplit {
    enum class Mode { PROPORTIONAL, OVERRIDE }

    /**
     * The order's figures (order currency x 100; credits x 100). The `inFlight*` amounts are the sums over the order's refunds in
     * `REQUESTED` / `PENDING`. [unit] is `CreditMath.unit(currency)`: 100 for a zero-decimal currency, else 1. [anonymised]
     * is `userId IS NULL`: with a credit part the credits cannot be returned (07 section 7.1 "anonymised order").
     */
    data class Order(
        val totalPrice: Long,
        val gatewayAmount: Long,
        val creditValue: Long,
        val creditAmount: Long,
        val refundedTotal: Long = 0,
        val refundedGatewayAmount: Long = 0,
        val refundedCreditAmount: Long = 0,
        val inFlightTotal: Long = 0,
        val inFlightGatewayAmount: Long = 0,
        val inFlightCreditAmount: Long = 0,
        val unit: Long = 1,
        val anonymised: Boolean = false
    ) {
        init {
            require(unit == 1L || unit == 100L) { "the unit is 1 or 100: $unit" }
            require(
                totalPrice >= 0 && gatewayAmount >= 0 && creditValue >= 0 && creditAmount >= 0 && refundedTotal >= 0 &&
                    refundedGatewayAmount >= 0 && refundedCreditAmount >= 0 && inFlightTotal >= 0 &&
                    inFlightGatewayAmount >= 0 && inFlightCreditAmount >= 0
            ) { "order figures are never negative" }
        }
    }

    /**
     * The request of `POST /orders/:id/refunds` and of the preview. [amount] is the value `A` (a sum of line amounts when the
     * refund has items); null means "all that remains" in PROPORTIONAL mode. [gatewayAmount] (money) and [creditAmount]
     * (credits) are the OVERRIDE fields and are only allowed with [Mode.OVERRIDE].
     */
    data class Request(
        val mode: Mode = Mode.PROPORTIONAL,
        val amount: Long? = null,
        val gatewayAmount: Long? = null,
        val creditAmount: Long? = null
    ) {
        init {
            require(mode == Mode.OVERRIDE || (gatewayAmount == null && creditAmount == null)) {
                "gatewayAmount and creditAmount are override fields"
            }
        }
    }

    /**
     * What the gateway behind the order's payment can refund ([support] is `PaymentCapabilities.refund`, 02 section 5) and the
     * request's [manual] flag. `manual` means the money was (or is) returned outside the gateway: no provider call is made, so no
     * capability applies and it lifts every refusal, not only [RefundSupport.NONE] but also a partial gateway part, or any gateway
     * part after an earlier gateway refund, at a [RefundSupport.FULL_ONLY] provider (21 section 3.2 step 3 and 3.6, 04 section 7
     * `REFUND_NOT_SUPPORTED (use manual)`; the panel dialog switches `manual` on and resubmits). The limits stay what the gateway took.
     */
    class GatewayRefund(val support: RefundSupport, val manual: Boolean = false)

    /** The split: `amount = gatewayPart + creditValuePart`. */
    data class Split(val amount: Long, val gatewayPart: Long, val creditValuePart: Long, val creditPart: Long)

    /** What is left to refund (`max`, `maxGateway`, `maxCredit` of the 400 body; the credit value is the middle one of the constraints). */
    data class Limits(val max: Long, val maxGateway: Long, val maxCreditValue: Long, val maxCredit: Long)

    enum class WarningCode { MIXED_PAYMENT_SPLIT, CREDIT_ONLY_REFUND, GATEWAY_PARTIAL_REFUND_NOT_SUPPORTED, CREDIT_ACCOUNT_CLOSED }

    /** A preview warning (07 section 7.3); the fields are the extras of the code. `creditName` is config and added by the caller. */
    data class Warning(
        val code: WarningCode,
        val gatewayAmount: Long? = null,
        val creditAmount: Long? = null,
        val refundSupport: RefundSupport? = null,
        val forfeitedCredits: Long? = null
    )

    /** Why a request is invalid; the HTTP answer is always 400 `INVALID_REFUND_AMOUNT` with the [Limits]. */
    enum class Problem {
        NOTHING_TO_REFUND, AMOUNT_OUT_OF_RANGE, NOT_A_MULTIPLE_OF_UNIT, GATEWAY_PART_OUT_OF_RANGE, CREDIT_PART_OUT_OF_RANGE,
        CREDIT_VALUE_PART_OUT_OF_RANGE, AMOUNT_MISMATCH
    }

    sealed class Result {
        /** The split is valid and the gateway can do it. */
        data class Ok(val split: Split, val limits: Limits, val warnings: List<Warning>) : Result()

        /** 400 `INVALID_REFUND_AMOUNT {max, maxGateway, maxCredit}`. */
        data class Invalid(val limits: Limits, val problem: Problem) : Result()

        /**
         * 400 `REFUND_NOT_SUPPORTED`: the split is valid but its gateway part is not refundable at this provider. The split
         * and the warnings are still there for the preview.
         */
        data class NotSupported(val split: Split, val limits: Limits, val refundSupport: RefundSupport, val warnings: List<Warning>) : Result()
    }

    private class Remaining(val t: Long, val g: Long, val cv: Long, val ca: Long) {
        fun limits() = Limits(t, g, cv, ca)
    }

    /**
     * Splits the refund of [order] asked for by [request]. [gateway] is the capability of the provider that took the
     * payment; null means "no gateway refund is possible" (a gateway part then answers [Result.NotSupported]).
     *
     * OVERRIDE (07 section 7.1 table), `gatewayAmount` only: `gatewayPart` as given, `creditValuePart = A - gatewayPart` when
     * `amount` was sent (else 0 and `A = gatewayPart`), `creditPart` by the proportional rule. `creditAmount` only: `creditPart`
     * as given, `creditValuePart = remCV` when it equals `remCA` (else `halfUp(CV x creditPart / CA)` to the unit),
     * `gatewayPart = A - creditValuePart` when `amount` was sent (else 0 and `A = creditValuePart`). Both: parts as given,
     * `creditValuePart` from the credit part, and `amount`, when sent, must equal `gatewayPart + creditValuePart`.
     *
     * Capability: a gateway part needs `gateway.support` to allow it, unless `gateway.manual` (then nothing is asked of the gateway);
     * a null [gateway] is "unknown" and refuses every gateway part. Credits that have no money value left are the one case of a
     * result with `amount == 0`, see the class comment.
     */
    fun compute(order: Order, request: Request, gateway: GatewayRefund?): Result {
        val rem = remaining(order)
        val limits = rem.limits()
        fun invalid(problem: Problem) = Result.Invalid(limits, problem)

        valueLessCredits(rem, request)?.let { return finish(order, rem, it, gateway, valueLess = true) }

        val split = when (request.mode) {
            Mode.PROPORTIONAL -> {
                val amount = request.amount ?: rem.t
                if (amount <= 0L || amount > rem.t) return invalid(Problem.AMOUNT_OUT_OF_RANGE)
                if (amount % order.unit != 0L) return invalid(Problem.NOT_A_MULTIPLE_OF_UNIT)
                proportional(order, rem, amount)
            }
            Mode.OVERRIDE -> override(order, rem, request) ?: return invalid(overrideProblem(order, rem, request))
        }
        return finish(order, rem, split, gateway)
    }

    // ---------------------------------------------------------------- credits without money value

    /**
     * The credit-only refund of value 0 for an order that has credits left and no money on either side (see the class comment),
     * or null when the state or the request is not that case (the ordinary path then answers, and with `remT == 0` it refuses).
     */
    private fun valueLessCredits(r: Remaining, req: Request): Split? {
        if (r.g != 0L || r.cv != 0L || r.ca <= 0L) return null
        return when (req.mode) {
            Mode.PROPORTIONAL -> if (req.amount == null) Split(0L, 0L, 0L, r.ca) else null
            Mode.OVERRIDE -> {
                val credits = req.creditAmount ?: return null
                if (credits < 1L || credits > r.ca) return null
                if ((req.gatewayAmount ?: 0L) != 0L || (req.amount ?: 0L) != 0L) return null
                Split(0L, 0L, 0L, credits)
            }
        }
    }

    // ---------------------------------------------------------------- proportional

    private fun proportional(o: Order, r: Remaining, amount: Long): Split {
        val creditValuePart = if (amount == r.t) {
            // the whole remainder takes the whole remaining credit value; `min` only matters for a damaged order (over-refunded at the gateway)
            minOf(r.cv, amount)
        } else {
            val raw = if (o.totalPrice == 0L) 0L else {
                Rounding.ratioQ(BigDecimal.valueOf(amount).multiply(BigDecimal.valueOf(o.creditValue)), BigDecimal.valueOf(o.totalPrice), o.unit)
            }
            // amount <= remT <= remG + remCV, so the lower bound never exceeds the upper one
            raw.coerceIn(maxOf(0L, amount - r.g), minOf(r.cv, amount))
        }
        return Split(amount, amount - creditValuePart, creditValuePart, creditOf(o, r, creditValuePart))
    }

    /** `remCA` when the credit value part is the whole remaining credit value, else the credit share of that value (capped by `remCA`). */
    private fun creditOf(o: Order, r: Remaining, creditValuePart: Long): Long {
        if (creditValuePart == r.cv) return r.ca
        if (o.creditValue == 0L) return 0L
        val share = Rounding.ratioQ(BigDecimal.valueOf(o.creditAmount).multiply(BigDecimal.valueOf(creditValuePart)), BigDecimal.valueOf(o.creditValue), 1L)
        return minOf(r.ca, share)
    }

    // ---------------------------------------------------------------- override

    /** The override split, or null when the request is malformed or out of range (the caller then names the problem). */
    private fun override(o: Order, r: Remaining, req: Request): Split? {
        val givenG = req.gatewayAmount
        val givenC = req.creditAmount
        val sent = req.amount
        if (givenG == null && givenC == null) return null
        if (givenG != null && (givenG < 0 || givenG > r.g)) return null
        if (givenC != null && (givenC < 0 || givenC > r.ca)) return null
        if (sent != null && sent <= 0) return null
        if ((givenG != null && givenG % o.unit != 0L) || (sent != null && sent % o.unit != 0L)) return null

        return if (givenC == null) {
            // gatewayAmount only
            val gatewayPart = givenG!!
            val creditValuePart: Long
            val amount: Long
            if (sent != null) {
                if (sent < gatewayPart) return null
                amount = sent
                creditValuePart = sent - gatewayPart
            } else {
                amount = gatewayPart
                creditValuePart = 0L
            }
            Split(amount, gatewayPart, creditValuePart, creditOf(o, r, creditValuePart))
        } else {
            val creditValuePart = valueOfCredits(o, r, givenC)
            val gatewayPart: Long
            val amount: Long
            if (givenG == null) {
                if (sent != null) {
                    if (sent < creditValuePart) return null
                    amount = sent
                    gatewayPart = sent - creditValuePart
                } else {
                    amount = creditValuePart
                    gatewayPart = 0L
                }
            } else {
                gatewayPart = givenG
                amount = Math.addExact(gatewayPart, creditValuePart)
                if (sent != null && sent != amount) return null
            }
            Split(amount, gatewayPart, creditValuePart, givenC)
        }
    }

    /** `remCV` when [creditPart] is all that remains, else `halfUp(CV x creditPart / CA)` to the unit; 0 for no credits. */
    private fun valueOfCredits(o: Order, r: Remaining, creditPart: Long): Long {
        if (creditPart == r.ca) return r.cv
        if (creditPart == 0L || o.creditAmount == 0L) return 0L
        return Rounding.ratioQ(
            BigDecimal.valueOf(o.creditValue).multiply(BigDecimal.valueOf(creditPart)),
            BigDecimal.valueOf(o.creditAmount),
            o.unit
        )
    }

    /** The reason an override request was refused, for the log and the tests (same checks as [override], in order). */
    private fun overrideProblem(o: Order, r: Remaining, req: Request): Problem {
        val givenG = req.gatewayAmount
        val givenC = req.creditAmount
        val sent = req.amount
        return when {
            givenG == null && givenC == null -> Problem.NOTHING_TO_REFUND
            givenG != null && (givenG < 0 || givenG > r.g) -> Problem.GATEWAY_PART_OUT_OF_RANGE
            givenC != null && (givenC < 0 || givenC > r.ca) -> Problem.CREDIT_PART_OUT_OF_RANGE
            sent != null && sent <= 0 -> Problem.AMOUNT_OUT_OF_RANGE
            (givenG != null && givenG % o.unit != 0L) || (sent != null && sent % o.unit != 0L) -> Problem.NOT_A_MULTIPLE_OF_UNIT
            else -> Problem.AMOUNT_MISMATCH
        }
    }

    // ---------------------------------------------------------------- constraints, capability, warnings

    private fun finish(o: Order, r: Remaining, s: Split, gateway: GatewayRefund?, valueLess: Boolean = false): Result {
        val limits = r.limits()
        // the constraints of 07 section 7.1, for every result; a refund of value 0 is only the credits that have no money value ([valueLessCredits])
        val problem = when {
            s.amount < 0L || (s.amount == 0L && !(valueLess && s.creditPart > 0L)) -> Problem.AMOUNT_OUT_OF_RANGE
            s.amount > r.t -> Problem.AMOUNT_OUT_OF_RANGE
            s.gatewayPart < 0L || s.gatewayPart > r.g -> Problem.GATEWAY_PART_OUT_OF_RANGE
            s.creditValuePart < 0L || s.creditValuePart > r.cv -> Problem.CREDIT_VALUE_PART_OUT_OF_RANGE
            s.creditPart < 0L || s.creditPart > r.ca -> Problem.CREDIT_PART_OUT_OF_RANGE
            Math.addExact(s.gatewayPart, s.creditValuePart) != s.amount -> Problem.AMOUNT_MISMATCH
            s.amount % o.unit != 0L || s.gatewayPart % o.unit != 0L || s.creditValuePart % o.unit != 0L -> Problem.NOT_A_MULTIPLE_OF_UNIT
            else -> null
        }
        if (problem != null) return Result.Invalid(limits, problem)

        val warnings = ArrayList<Warning>()
        if (o.anonymised && o.creditAmount > 0L) {
            val forfeited = maxOf(0L, o.creditAmount - o.refundedCreditAmount - o.inFlightCreditAmount)
            warnings += Warning(WarningCode.CREDIT_ACCOUNT_CLOSED, forfeitedCredits = forfeited)
        }
        if (s.gatewayPart > 0L && s.creditPart > 0L) {
            warnings += Warning(WarningCode.MIXED_PAYMENT_SPLIT, gatewayAmount = s.gatewayPart, creditAmount = s.creditPart)
        }
        if (s.gatewayPart == 0L && s.creditPart > 0L && o.gatewayAmount > 0L) warnings += Warning(WarningCode.CREDIT_ONLY_REFUND)

        // 07 section 7.1 "Gateway capability" (02 section 5); `manual` = no provider call, so it lifts every refusal (21 section 3.2 step 3, 3.6)
        if (s.gatewayPart > 0L) {
            val support = gateway?.support ?: RefundSupport.NONE
            val manual = gateway?.manual == true
            val refundable = manual || when (support) {
                RefundSupport.NONE -> false
                RefundSupport.FULL_ONLY -> s.gatewayPart == o.gatewayAmount && o.refundedGatewayAmount == 0L
                RefundSupport.PARTIAL, RefundSupport.PER_LINE -> true
            }
            if (!refundable) {
                warnings += Warning(WarningCode.GATEWAY_PARTIAL_REFUND_NOT_SUPPORTED, refundSupport = support)
                return Result.NotSupported(s, limits, support, warnings)
            }
        }
        return Result.Ok(s, limits, warnings)
    }

    private fun remaining(o: Order): Remaining {
        val g = maxOf(0L, o.gatewayAmount - o.refundedGatewayAmount - o.inFlightGatewayAmount)
        val refundedValue = maxOf(0L, o.refundedTotal - o.refundedGatewayAmount)
        val inFlightValue = maxOf(0L, o.inFlightTotal - o.inFlightGatewayAmount)
        var cv = maxOf(0L, o.creditValue - refundedValue - inFlightValue)
        var ca = maxOf(0L, o.creditAmount - o.refundedCreditAmount - o.inFlightCreditAmount)
        if (o.anonymised && o.creditAmount > 0L) {
            // the credits cannot be returned: the limit is what the gateway can still give back
            cv = 0L
            ca = 0L
        }
        val t = minOf(maxOf(0L, o.totalPrice - o.refundedTotal - o.inFlightTotal), g + cv)
        return Remaining(t, g, cv, ca)
    }
}
