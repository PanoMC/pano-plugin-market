package com.panomc.plugins.market.core.credit

import com.panomc.plugins.market.core.pricing.MixedPayment
import java.math.BigDecimal

/**
 * Credit amounts that arrive as text or as a JSON number: `useCredits` (07 section 6.2), a free top-up amount, a grant or a
 * revoke (07 sections 8.2 and 11.1). A valid amount is a number `>= 0` with **at most two decimals**; the result is credits
 * x 100. Anything else is `null` and the endpoint answers 400 `BAD_REQUEST` / `INVALID_CREDIT_AMOUNT`.
 */
object CreditAmounts {
    /** The request word that means "everything applicable"; valid on the quote only (07 section 6.2). */
    const val MAX_WORD = "MAX"

    /** Integer digits that still fit credits x 100 into a `Long` (a bound that stops absurd exponents before any big expansion). */
    private const val MAX_INTEGER_DIGITS = 17

    private val PLAIN = Regex("[0-9]+(\\.[0-9]{1,2})?")

    /** Credits x 100 of [value], or `null` when it is negative, has more than two decimals or does not fit. */
    fun fromDecimal(value: BigDecimal): Long? {
        if (value.signum() < 0) return null
        if (value.signum() == 0) return 0L
        val stripped = value.stripTrailingZeros()
        if (stripped.scale() > 2) return null
        if (stripped.precision() - stripped.scale() > MAX_INTEGER_DIGITS) return null
        val cents = try {
            stripped.movePointRight(2).setScale(0).longValueExact()
        } catch (e: ArithmeticException) {
            return null
        }
        return if (cents == MixedPayment.MAX) null else cents
    }

    /** Credits x 100 of a plain decimal text (`"12"`, `"12.5"`, `"12.50"`); no sign, no exponent, no spaces. */
    fun parse(text: String): Long? {
        if (!PLAIN.matches(text)) return null
        return fromDecimal(BigDecimal(text))
    }

    /**
     * The `useCredits` request field: absent or blank is `0` (no credits are ever applied on their own), [MAX_WORD] is
     * [MixedPayment.MAX] on the quote and invalid when [strict] (checkout and `/pay`), a number is its credits x 100.
     * `null` is an invalid value (400 `BAD_REQUEST`).
     */
    fun parseUseCredits(text: String?, strict: Boolean): Long? {
        if (text == null || text.isBlank()) return 0L
        if (text == MAX_WORD) return if (strict) null else MixedPayment.MAX
        return parse(text)
    }
}
