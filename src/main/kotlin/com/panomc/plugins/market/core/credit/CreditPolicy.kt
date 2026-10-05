package com.panomc.plugins.market.core.credit

/**
 * The posting policies of the ledger (07 section 3.1) as pure arithmetic, so the preview of a refund (the `shortfall` it
 * warns about) and `CreditService.post` use one definition. All amounts are credits x 100.
 */
object CreditPolicy {
    /** What a posting moves: [taken] is the tx amount, [shortfall] what could not be taken (`requested - taken`). */
    data class Taken(val taken: Long, val shortfall: Long)

    /**
     * `TAKE_AVAILABLE`: `taken = min(requested, max(balance, 0))`. The balance never goes negative through it; a user in debt
     * (negative balance) yields nothing.
     */
    fun takeAvailable(requested: Long, balance: Long): Taken {
        require(requested >= 0) { "a posting amount is never negative: $requested" }
        val taken = minOf(requested, maxOf(balance, 0L))
        return Taken(taken, requested - taken)
    }

    /** `ALLOW_DEBT` (dispute clawbacks only): the full amount is taken, the balance may become negative, no shortfall. */
    fun allowDebt(requested: Long): Taken {
        require(requested >= 0) { "a posting amount is never negative: $requested" }
        return Taken(requested, 0L)
    }

    /** `FAIL`: the balance must cover the amount, else `InsufficientCredits(balance)`. A debt covers nothing but 0. */
    fun covers(requested: Long, balance: Long): Boolean {
        require(requested >= 0) { "a posting amount is never negative: $requested" }
        return balance >= requested
    }
}
