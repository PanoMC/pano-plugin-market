package com.panomc.plugins.market.core.subscription

/**
 * Duplicate check of a `GATEWAY` `SubscriptionRenewed` event (09 section 8.2 step 2). The first matching rule wins
 * and the event is then a no-op. Pure: the three lookups are done by the service and passed in as [Facts].
 */
object RenewalDedupe {
    /** Which rule matched. */
    enum class Match {
        /** (a) the event's `gatewayTransactionId` is already a `market_payment` of this provider. */
        TRANSACTION_ID,

        /** (b) the event's `periodStart` is not after the current period, or a renewal row with it is `PAID`. */
        PERIOD_START,

        /** (c) the event names neither, and the newest paid order is younger than half a period. */
        RECENT_PAYMENT
    }

    /** What the event carries. A blank transaction id counts as absent. */
    data class Event(val gatewayTransactionId: String?, val periodStart: Long?)

    /**
     * What the service looked up inside the locked transaction.
     * @property paymentWithTransactionExists a `market_payment` with `(providerId, gatewayTransactionId)` exists.
     * @property paidRenewalWithPeriodStartExists a renewal row of this subscription with `periodStart` = the event's
     * and `status = PAID` exists.
     * @property currentPeriodStart / [currentPeriodEnd] of the subscription.
     * @property newestPaidOrderPaidAt `paidAt` of the newest paid order of this subscription, if any.
     */
    data class Facts(
        val paymentWithTransactionExists: Boolean = false,
        val paidRenewalWithPeriodStartExists: Boolean = false,
        val currentPeriodStart: Long? = null,
        val currentPeriodEnd: Long? = null,
        val newestPaidOrderPaidAt: Long? = null
    )

    /**
     * The rule that makes [event] a duplicate, or `null` when it is new. Rule (c) needs the period length; when the
     * subscription has no period yet it cannot match, so money that arrived is recorded rather than dropped.
     */
    fun match(event: Event, facts: Facts, now: Long): Match? {
        val transactionId = event.gatewayTransactionId?.takeIf { it.isNotBlank() }
        val periodStart = event.periodStart

        if (transactionId != null && facts.paymentWithTransactionExists) return Match.TRANSACTION_ID

        if (periodStart != null) {
            val current = facts.currentPeriodStart
            if ((current != null && periodStart <= current) || facts.paidRenewalWithPeriodStartExists) return Match.PERIOD_START
        }

        if (transactionId == null && periodStart == null) {
            val start = facts.currentPeriodStart
            val end = facts.currentPeriodEnd
            val paidAt = facts.newestPaidOrderPaidAt
            if (start != null && end != null && paidAt != null && paidAt > now - (end - start) / 2) return Match.RECENT_PAYMENT
        }
        return null
    }

    fun isDuplicate(event: Event, facts: Facts, now: Long): Boolean = match(event, facts, now) != null
}
