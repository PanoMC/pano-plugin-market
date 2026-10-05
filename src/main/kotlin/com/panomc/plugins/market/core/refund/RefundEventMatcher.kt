package com.panomc.plugins.market.core.refund

import com.panomc.plugins.market.db.model.RefundOrigin
import com.panomc.plugins.market.db.model.RefundStatus
import com.panomc.plugins.market.spi.payment.RefundState
import java.security.MessageDigest

/**
 * Which refund row an inbound `RefundUpdated` belongs to (21 section 4, 02 section 7.4). Pure; the service calls it under the
 * order lock and then moves the row (or inserts one) by `RefundStateMachine`.
 *
 * Rules, in order:
 * 1. `refundKey != null`: the row with `idempotencyKey = refundKey`.
 * 2. `gatewayRefundId != null`: the row with `(providerId, gatewayRefundId)`.
 * 3. No row found by 1 or 2, and the event carries **no `refundKey`** (an unknown key is a foreign refund, rule 4): the oldest
 *    refund of the attempt in `REQUESTED` / `PENDING` whose `gatewayAmount` equals `event.amount`; with `event.amount == null` the
 *    only open one (none or several: no match). When the event carries a gateway id that no row knows, only open rows that do not
 *    carry a gateway id themselves are candidates (a row with another id is another refund). This is the case "the confirming
 *    webhook arrives before tx2 stored `gatewayRefundId`" (also after a timed-out call) and the gateways whose notifications carry
 *    no id (Tebex, Paymentwall, NOWPayments, Sipay); without it the notification would be booked as a second, gateway-originated
 *    refund. **The service stores the event's `gatewayRefundId` on a row matched this way** (`uq_provider_refund`).
 * 4. No match:
 *    - `cumulativeRefunded != null` (snapshot gateways: Mollie, PayTR `returns[]`, Moka): at most what is already known
 *      (`market_payment.refundedAmount` plus the open refunds of the attempt) is a [Match.NoOp]; more inserts a
 *      gateway-originated row for the difference;
 *    - else a gateway-originated row for `event.amount` (`null`: the attempt's remaining collected amount) and **never beyond**
 *      `paidAmount - refundedAmount` (excess: clamped, [Match.Insert.clamped]; nothing left: [Match.NoOp] with the alert).
 *      An event with **neither id nor cumulative value** is applied **at most once per `(paymentId, amount)`** within 24 h: a
 *      refund of the attempt that is not failed / cancelled (of any origin: the notification of a panel refund must not be booked
 *      a second time when it is delivered again, 02 section 7.3 steps 2 and 5), with this amount and created or completed less
 *      than 24 h ago, makes the event a [Match.NoOp] without alert; with `event.amount == null` any refund of the attempt that
 *      reached `event.state` in that window does. An event that carries an id has its identity in that id: two gateway-side
 *      refunds of one amount are two refunds.
 *
 * The idempotency key of an inserted row is `"gw:" + x`, at most 64 characters (`market_refund.idempotencyKey` is `VARCHAR(64)`,
 * unique) and an identity of the fact, never of the request (a request hash is no de-duplication key, 01 section 6.3): see [gatewayKey].
 * A row found by rule 1 or 2 may be in any status; whether the event changes it is the state machine's decision. A key or id that
 * no row carries falls through to the next rule (a gateway can echo a key of another order or a refund made in its dashboard).
 * Amounts are in the order currency x100 (the attempt's currency), exactly as the rows hold them.
 */
object RefundEventMatcher {
    /** Why a row was chosen. */
    enum class Rule { REFUND_KEY, GATEWAY_REFUND_ID, OLDEST_OPEN_BY_AMOUNT, ONLY_OPEN, DUPLICATE_GATEWAY_KEY }

    enum class NoOpReason {
        /** A snapshot that repeats what is known. */
        SNAPSHOT_KNOWN,

        /** An id-less refund of this payment and amount was already applied within 24 h (a redelivery). */
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
        /** The provider's own event key (`InboundResult.eventKey`). */
        val eventKey: String? = null,
        /**
         * Hex SHA-256 of the raw request (`market_payment_event.requestHash`). Used for the `gw:` key only when the event has no
         * `eventKey`, no id and no cumulative value; then one of the two is required (an event of `queryPayment` / reconcile has
         * no request and must carry a synthetic `eventKey`).
         */
        val requestHash: String? = null
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
        val createdAt: Long,
        /** `completedAt` of a settled row: a row created earlier but settled by this very notification still counts for the 24 h rule. */
        val completedAt: Long? = null
    )

    sealed class Match {
        /** The event is about this row (move it by the state machine). */
        data class Existing(val refundId: Long, val rule: Rule) : Match()

        /** Insert an `origin = GATEWAY` row in `event.state` with [amount] as `gatewayAmount` and this key. [clamped]: cut back to the cap. */
        data class Insert(val amount: Long, val idempotencyKey: String, val clamped: Boolean = false) : Match()

        /** Nothing to write; [alert] asks the service for the panel alert (`OVER_REFUND`). */
        data class NoOp(val reason: NoOpReason, val alert: Boolean = false) : Match()
    }

    /** Two id-less refunds of one payment and amount inside this window are one (21 section 4 rule 4). */
    const val DEDUPE_WINDOW_MS = 24L * 60 * 60 * 1000

    /** `market_refund.idempotencyKey` is `VARCHAR(64)`. */
    const val MAX_KEY_LENGTH = 64

    private const val KEY_PREFIX = "gw:"
    private const val KEY_BODY = MAX_KEY_LENGTH - 3

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
        if (event.refundKey == null) {
            // An id that no row knows can still be the id of an open row that has not stored one yet (tx2 not done, call timed out);
            // a row that carries another id is another refund.
            val candidates = if (event.gatewayRefundId == null) open else open.filter { it.gatewayRefundId == null }
            val amount = event.amount
            if (amount != null) {
                candidates.filter { it.gatewayAmount == amount }
                    .minWithOrNull(compareBy<RefundRow>({ it.createdAt }, { it.id }))
                    ?.let { return Match.Existing(it.id, Rule.OLDEST_OPEN_BY_AMOUNT) }
            } else if (candidates.size == 1) {
                return Match.Existing(candidates[0].id, Rule.ONLY_OPEN)
            }
        }

        val key = gatewayKey(event, attempt)
        // The same fact again (its key is already a row): the state machine decides, nothing is inserted twice.
        refunds.firstOrNull { it.idempotencyKey == key }?.let { return Match.Existing(it.id, Rule.DUPLICATE_GATEWAY_KEY) }

        val cumulative = event.cumulativeRefunded
        if (cumulative != null) {
            val known = Math.addExact(attempt.refundedAmount, open.fold(0L) { acc, r -> Math.addExact(acc, r.gatewayAmount) })
            if (cumulative <= known) return Match.NoOp(NoOpReason.SNAPSHOT_KNOWN)
            return capped(cumulative - known, maxOf(0L, attempt.paidAmount - known), key)
        }

        val wanted = event.amount ?: maxOf(0L, attempt.paidAmount - attempt.refundedAmount)
        // Only an event with neither id nor cumulative value has no identity but (payment, amount): a gateway-side refund that
        // carries an id is found by rule 2 on its replay, and two of them with one amount are two refunds.
        if (event.gatewayRefundId == null && event.refundKey == null && ofAttempt.any { appliedAlready(it, event, wanted, now) }) {
            return Match.NoOp(NoOpReason.SAME_AMOUNT_WITHIN_24H)
        }
        return capped(wanted, attempt.paidAmount - attempt.refundedAmount, key)
    }

    /**
     * The `idempotencyKey` of a gateway-originated row inserted for [event]: `"gw:"` plus at most 61 characters, built from the
     * strongest identity the event has:
     * 1. `gatewayRefundId`: `sha256Hex(providerId + ":" + gatewayRefundId)`;
     * 2. `cumulativeRefunded`: `sha256Hex("<paymentId>:cum:<cumulativeRefunded>")` (the inserted amount is a function of it);
     * 3. an unknown `refundKey`: `sha256Hex(providerId + ":key:" + refundKey)`;
     * 4. the provider's `eventKey`, else the `requestHash`, as it is when it fits and as `sha256Hex(raw)` when it does not.
     *
     * Each hash is cut to 61 characters so the key fits `VARCHAR(64)`. Only case 4 can name something that is not a fact (a request),
     * which is why it is the last and why the 24 h rule backs it.
     */
    fun gatewayKey(event: Event, attempt: Attempt): String {
        event.gatewayRefundId?.let { return KEY_PREFIX + sha256Hex(attempt.providerId + ":" + it).take(KEY_BODY) }
        event.cumulativeRefunded?.let { return KEY_PREFIX + sha256Hex("${attempt.paymentId}:cum:$it").take(KEY_BODY) }
        event.refundKey?.let { return KEY_PREFIX + sha256Hex(attempt.providerId + ":key:" + it).take(KEY_BODY) }
        val raw = event.eventKey?.takeIf { it.isNotEmpty() } ?: event.requestHash?.takeIf { it.isNotEmpty() }
        require(raw != null) { "a RefundUpdated without id and cumulative value needs an eventKey or a requestHash" }
        return KEY_PREFIX + if (raw.length <= KEY_BODY) raw else sha256Hex(raw).take(KEY_BODY)
    }

    /**
     * Is [r] the refund that [event] (no id, no cumulative value) was already applied as?
     * - with an amount: a refund of the attempt of that amount that is not failed / cancelled, created or completed within 24 h, of any
     *   origin (the notification of a panel refund confirms it, it is no second refund); a failed / cancelled row counts only for
     *   an event that reports that same state (a replay of a recorded failure);
     * - without an amount: a refund of the attempt that reached the state of the event within 24 h.
     */
    private fun appliedAlready(r: RefundRow, event: Event, wanted: Long, now: Long): Boolean {
        if (now - (r.completedAt ?: r.createdAt) >= DEDUPE_WINDOW_MS) return false
        return when {
            event.amount == null -> reached(r.status, event.state)
            r.gatewayAmount != wanted -> false
            r.status != RefundStatus.FAILED && r.status != RefundStatus.CANCELLED -> true
            else -> reached(r.status, event.state)
        }
    }

    private fun reached(status: RefundStatus, state: RefundState): Boolean = when (state) {
        RefundState.SUCCEEDED -> status == RefundStatus.SUCCEEDED
        RefundState.PENDING -> status == RefundStatus.PENDING || status == RefundStatus.SUCCEEDED
        RefundState.FAILED -> status == RefundStatus.FAILED
        RefundState.CANCELLED -> status == RefundStatus.CANCELLED
    }

    /** Never beyond what the attempt can still give back: a cut is [Match.Insert.clamped], nothing left is [Match.NoOp] with the alert. */
    private fun capped(wanted: Long, cap: Long, key: String): Match = when {
        cap <= 0L -> Match.NoOp(NoOpReason.NOTHING_REFUNDABLE, alert = true)
        wanted <= 0L -> Match.NoOp(NoOpReason.NOTHING_REFUNDABLE)
        wanted > cap -> Match.Insert(cap, key, clamped = true)
        else -> Match.Insert(wanted, key)
    }

    private fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
