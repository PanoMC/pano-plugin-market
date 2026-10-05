package com.panomc.plugins.market.core.refund

import com.panomc.plugins.market.db.model.RefundOrigin
import com.panomc.plugins.market.db.model.RefundStatus
import com.panomc.plugins.market.spi.payment.RefundState

/**
 * Which refund row an inbound `RefundUpdated` belongs to (21 section 4, 02 section 7.4). Pure; the service calls it under the
 * order lock and then moves the row (or inserts one) by `RefundStateMachine`.
 *
 * Rules, in order:
 * 1. `refundKey != null`: the row with `idempotencyKey = refundKey`.
 * 2. `gatewayRefundId != null`: the row with `(providerId, gatewayRefundId)`.
 * 3. An event that carries **no id at all**: the oldest refund of the attempt in `REQUESTED` / `PENDING` whose `gatewayAmount`
 *    equals `event.amount`; with `event.amount == null` the only open one (none or several: no match). This is the case "the
 *    confirming webhook arrives before tx2 stored `gatewayRefundId`" and the gateways whose notifications carry no id (Tebex,
 *    Paymentwall, NOWPayments, Sipay); without it the notification would be booked as a second, gateway-originated refund.
 * 4. No match:
 *    - `cumulativeRefunded != null` (snapshot gateways: Mollie, PayTR `returns[]`, Moka): at most what is already known
 *      (`market_payment.refundedAmount` plus the open refunds of the attempt) is a [Match.NoOp]; more inserts a
 *      gateway-originated row for the difference;
 *    - else a gateway-originated row for `event.amount` (`null`: the attempt's remaining collected amount) keyed
 *      `"gw:" + (eventKey ?: requestHash)`, applied **at most once per `(paymentId, amount)`** within 24 h and **never beyond**
 *      `paidAmount - refundedAmount` (excess: clamped, [Match.Insert.clamped]; nothing left: [Match.NoOp] with the alert).
 *
 * A row found by rule 1 or 2 may be in any status; whether the event changes it is the state machine's decision. A key or id that
 * no row carries falls through to the next rule (a gateway can echo a key of another order or a refund made in its dashboard).
 * Amounts are in the order currency x100 (the attempt's currency), exactly as the rows hold them.
 *
 * Note for the caller (deliberate, not changed here): rule 3 is for events without any id. A webhook that carries a gateway id
 * before tx2 stored it on the row finds no row by rule 2 and is inserted as a gateway-originated refund by rule 4 unless it also
 * echoes the `refundKey`; tx2 must therefore look for a gateway-originated row with the same `gatewayRefundId` before booking.
 */
object RefundEventMatcher {
    /** Why a row was chosen. */
    enum class Rule { REFUND_KEY, GATEWAY_REFUND_ID, OLDEST_OPEN_BY_AMOUNT, ONLY_OPEN, DUPLICATE_GATEWAY_KEY }

    enum class NoOpReason {
        /** A snapshot that repeats what is known. */
        SNAPSHOT_KNOWN,

        /** A gateway-originated refund of this payment and amount was already applied within 24 h. */
        SAME_AMOUNT_WITHIN_24H,

        /** The attempt has nothing left that a refund could take (`paidAmount - refundedAmount <= 0`): the service raises the alert. */
        NOTHING_REFUNDABLE
    }

    /** What the gateway reported. All amounts are in the attempt's currency x100. */
    data class Event(
        val state: RefundState,
        /** `RefundUpdated.amount`; `null` when the gateway reports none. */
        val amount: Long? = null,
        val refundKey: String? = null,
        val gatewayRefundId: String? = null,
        /** `RefundUpdated.cumulativeRefunded` of snapshot gateways. */
        val cumulativeRefunded: Long? = null,
        /** The provider's own event key (`InboundResult.eventKey`), else the request hash is used for the `gw:` key. */
        val eventKey: String? = null,
        val requestHash: String = ""
    )

    /** The payment attempt the event is about (`market_payment`). */
    data class Attempt(
        val paymentId: Long,
        val providerId: String,
        val paidAmount: Long,
        /** `market_payment.refundedAmount`: refunds of this attempt that reached `SUCCEEDED`. */
        val refundedAmount: Long = 0
    )

    /** One `market_refund` row of the order. */
    data class RefundRow(
        val id: Long,
        /** `null` for a credit-only refund. */
        val paymentId: Long?,
        val providerId: String?,
        val origin: RefundOrigin,
        val status: RefundStatus,
        val idempotencyKey: String,
        val gatewayRefundId: String?,
        val gatewayAmount: Long,
        val createdAt: Long
    )

    sealed class Match {
        /** The event is about this row (move it by the state machine). */
        data class Existing(val refundId: Long, val rule: Rule) : Match()

        /** Insert an `origin = GATEWAY` row in `event.state` with [amount] as `gatewayAmount` and this key. [clamped]: cut back to the cap. */
        data class Insert(val amount: Long, val idempotencyKey: String, val clamped: Boolean = false) : Match()

        /** Nothing to write; [alert] asks the service for the panel alert (`OVER_REFUND`). */
        data class NoOp(val reason: NoOpReason, val alert: Boolean = false) : Match()
    }

    /** Two gateway-originated refunds of one payment and amount inside this window are one (21 section 4 rule 4). */
    const val DEDUPE_WINDOW_MS = 24L * 60 * 60 * 1000

    private val OPEN = setOf(RefundStatus.REQUESTED, RefundStatus.PENDING)

    /** `refunds` are all refunds of the order; [now] is the clock for the 24 h rule. */
    fun match(event: Event, attempt: Attempt, refunds: List<RefundRow>, now: Long): Match {
        event.refundKey?.let { key ->
            refunds.firstOrNull { it.idempotencyKey == key }?.let { return Match.Existing(it.id, Rule.REFUND_KEY) }
        }
        event.gatewayRefundId?.let { gid ->
            refunds.firstOrNull { it.providerId == attempt.providerId && it.gatewayRefundId == gid }
                ?.let { return Match.Existing(it.id, Rule.GATEWAY_REFUND_ID) }
        }

        val ofAttempt = refunds.filter { it.paymentId == attempt.paymentId }
        val open = ofAttempt.filter { it.status in OPEN }
        if (event.refundKey == null && event.gatewayRefundId == null) {
            val amount = event.amount
            if (amount != null) {
                open.filter { it.gatewayAmount == amount }
                    .minWithOrNull(compareBy<RefundRow>({ it.createdAt }, { it.id }))
                    ?.let { return Match.Existing(it.id, Rule.OLDEST_OPEN_BY_AMOUNT) }
            } else if (open.size == 1) {
                return Match.Existing(open[0].id, Rule.ONLY_OPEN)
            }
        }

        val key = "gw:" + (event.eventKey ?: event.requestHash)
        // The same event again (its key is already a row): the state machine decides, nothing is inserted twice.
        refunds.firstOrNull { it.idempotencyKey == key }?.let { return Match.Existing(it.id, Rule.DUPLICATE_GATEWAY_KEY) }

        val cumulative = event.cumulativeRefunded
        if (cumulative != null) {
            val known = Math.addExact(attempt.refundedAmount, open.fold(0L) { acc, r -> Math.addExact(acc, r.gatewayAmount) })
            if (cumulative <= known) return Match.NoOp(NoOpReason.SNAPSHOT_KNOWN)
            return capped(cumulative - known, maxOf(0L, attempt.paidAmount - known), key)
        }

        val wanted = event.amount ?: maxOf(0L, attempt.paidAmount - attempt.refundedAmount)
        val applied = ofAttempt.any {
            it.origin == RefundOrigin.GATEWAY && it.status != RefundStatus.FAILED && it.status != RefundStatus.CANCELLED &&
                it.gatewayAmount == wanted && now - it.createdAt < DEDUPE_WINDOW_MS
        }
        if (applied) return Match.NoOp(NoOpReason.SAME_AMOUNT_WITHIN_24H)
        return capped(wanted, attempt.paidAmount - attempt.refundedAmount, key)
    }

    /** Never beyond what the attempt can still give back: a cut is [Match.Insert.clamped], nothing left is [Match.NoOp] with the alert. */
    private fun capped(wanted: Long, cap: Long, key: String): Match = when {
        cap <= 0L -> Match.NoOp(NoOpReason.NOTHING_REFUNDABLE, alert = true)
        wanted <= 0L -> Match.NoOp(NoOpReason.NOTHING_REFUNDABLE)
        wanted > cap -> Match.Insert(cap, key, clamped = true)
        else -> Match.Insert(wanted, key)
    }
}
