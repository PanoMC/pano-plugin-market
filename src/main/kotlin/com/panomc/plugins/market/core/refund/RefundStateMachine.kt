package com.panomc.plugins.market.core.refund

import com.panomc.plugins.market.db.model.RefundOrigin
import com.panomc.plugins.market.db.model.RefundStatus
import com.panomc.plugins.market.spi.payment.RefundState

/**
 * What the machine needs to know about a refund row: its status, and which kind of `REQUESTED` row it is. A `REQUESTED` row is one of
 * three things, and the machine cannot tell them apart from the status alone:
 * - **in flight** (neither flag): tx1 is done and the provider call is running right now (up to the 30 s deadline, 21 section 3.3);
 *   it may still answer `Succeeded` or `Pending`, so it can be neither re-sent (a gateway without idempotency keys would refund
 *   twice) nor cancelled (the reservation would be released while money may be on its way);
 * - **waiting** ([revokeFirstWaiting]): nothing was sent;
 * - **outcome unknown** ([outcomeUnknown]): the call timed out or the process died after tx1 (stale).
 */
data class RefundRowState(
    val status: RefundStatus,
    /**
     * `revokeFirst = 1` and the gateway call has not been released yet (21 section 3.5): the row waits in `REQUESTED`, nothing
     * was sent. Such a row is not an "unknown outcome" (retry makes no sense) and is the only one the 24 h timeout cancels.
     */
    val revokeFirstWaiting: Boolean = false,
    /**
     * A `REQUESTED` row that is not [revokeFirstWaiting] and is stale (21 section 3.3: older than 60 s, `nextQueryAt` due, no
     * call running): the outcome at the gateway is unknown, the panel shows "outcome unknown, retry or cancel". The service computes
     * it. Ignored for every other status; [revokeFirstWaiting] wins if both are set.
     */
    val outcomeUnknown: Boolean = false
)

/** A `REQUESTED` row nothing is running for: it waits for its undo, or it is stale. Only such a row may be cancelled by an admin or by O11. */
private val RefundRowState.idle: Boolean get() = revokeFirstWaiting || outcomeUnknown

/** Every trigger of the table in 21 section 3.7 (plus the O11 side effect on unsent refunds, 21 section 5.2 step 4). */
sealed class RefundEvent {
    /**
     * What the outside world says about the refund: the provider's answer to `refund` (`Succeeded` / `Pending` / `Failed`), an
     * inbound `RefundUpdated`, a `queryRefund` result, or the market's own settlement of a credit-only or manual refund
     * ([RefundState.SUCCEEDED] in tx1). [failureCode] / [failureMessage] describe a [RefundState.FAILED].
     */
    data class Reported(
        val state: RefundState,
        val failureCode: String? = null,
        val failureMessage: String? = null
    ) : RefundEvent()

    /** `POST /refunds/:id/retry`. */
    data object AdminRetry : RefundEvent()

    /** `POST /refunds/:id/cancel`. */
    data object AdminCancel : RefundEvent()

    /** `RefundReconcileJob`: a `revokeFirst` refund is still open 24 h after the request (21 section 3.5). */
    data object RevokeTimeout : RefundEvent()

    /** O11: a dispute opened on the order; refunds that have not been executed are cancelled (21 section 5.2 step 4). */
    data object ChargebackOpened : RefundEvent()
}

/** What the service writes besides the new status, in the same transaction (value objects: decisions compare structurally). */
sealed interface RefundEffect {
    /** O10 (21 section 3.4): books, lines, order status, revoke, restock, codes, earnings, mail, webhook. Idempotent by its own keys. */
    data object RunOrderEffects : RefundEffect

    /** `completedAt = now`. */
    data object StampCompleted : RefundEffect

    /** `nextQueryAt = now + afterMs`. */
    data class ScheduleQuery(val afterMs: Long) : RefundEffect

    /** `nextQueryAt = NULL`: the reconcile job has nothing to ask any more. */
    data object ClearQuery : RefundEffect

    /** `failureCode` / `failureMessage` (a failed gateway answer, or [RefundStateMachine.REVOKE_TIMEOUT] / [RefundStateMachine.CHARGEBACK]). */
    data class RecordFailure(val code: String?, val message: String?) : RefundEffect

    /** `failureCode` / `failureMessage = NULL`. */
    data object ClearFailure : RefundEffect

    /**
     * Send the gateway call again with the **same** `idempotencyKey` and the same amounts, re-validated against the remainder
     * without this row (21 section 3.3): a gateway that already executed the refund does not refund twice.
     */
    data object ResendToGateway : RefundEffect

    /** A panel alert; [code] is `REVOKE_TIMEOUT`. */
    data class PanelAlert(val code: String) : RefundEffect
}

sealed class RefundTransition {
    /** An admin action that the state does not allow: 409 `INVALID_STATE {state}` (04 section 7). */
    data class Rejected(val errorCode: String, val state: RefundStatus) : RefundTransition()

    /** A replay, an out-of-order event, or a combination the table does not list: nothing changes. */
    data object NoOp : RefundTransition()

    /** One conditional `UPDATE ... SET status = :to WHERE id = :id AND status = :from`, then the effects. A retry keeps `REQUESTED`. */
    data class Move(val to: RefundStatus, val effects: List<RefundEffect>) : RefundTransition()
}

/**
 * Refund state machine (21 section 3.7, 00 section 7.3), pure.
 *
 * | From | Trigger | To |
 * |---|---|---|
 * | - | panel / system request | `REQUESTED`; a gateway-originated row is inserted in the reported state ([insert]) |
 * | `REQUESTED` | `Reported(SUCCEEDED)` (provider, credit-only, manual, event) | `SUCCEEDED` and O10 |
 * | `REQUESTED` | `Reported(PENDING)` | `PENDING` |
 * | `REQUESTED`, `PENDING` | `Reported(FAILED)` | `FAILED` |
 * | `PENDING` | `Reported(SUCCEEDED)` | `SUCCEEDED` and O10 |
 * | `PENDING` | `Reported(CANCELLED)` | `CANCELLED` |
 * | `FAILED` | retry | `REQUESTED` |
 * | `REQUESTED` (outcome unknown) | retry | `REQUESTED`, same key sent again |
 * | `REQUESTED` (unsent / waiting, i.e. not in flight), `FAILED` | cancel, `revokeFirst` timeout (waiting rows), O11 | `CANCELLED` |
 * | `FAILED`, `CANCELLED` | `Reported(SUCCEEDED)` (late truth) | `SUCCEEDED` and O10: the gateway's word about money that left wins |
 * | `SUCCEEDED` | anything | unchanged |
 *
 * Everything the table does not list is a [RefundTransition.NoOp] for an event of the outside world (a gateway may repeat or
 * reorder notifications) and [RefundTransition.Rejected] (`INVALID_STATE`) for an admin action, so the panel can answer 409.
 * A refund the gateway confirmed is never moved to `FAILED` or `CANCELLED`: `SUCCEEDED` ignores every event.
 *
 * A `REQUESTED` row is in flight (the call runs, neither [RefundRowState] flag), waiting (`revokeFirst`) or of unknown outcome (stale:
 * timeout, or a crash between tx1 and the call). Retry (21 section 3.3: "from `FAILED` or unknown-outcome `REQUESTED`") needs
 * an unknown outcome and re-sends the same key; cancel (21 section 3.3, 3.7: "not yet sent, or `revokeFirst`-waiting") and O11
 * (21 section 5.2 step 4: "`REQUESTED` (unsent)") need a waiting or unknown-outcome row, because a later `Reported(SUCCEEDED)`
 * still wins over a cancel of a row nothing is running for. A row in flight is rejected for the admin (`INVALID_STATE`) and a
 * no-op for O11: its call is about to settle it.
 * Not in the table, decided here: a gateway `CANCELLED` for a `REQUESTED` row is ignored (the table lists it for `PENDING` only,
 * and the row stays visible for the admin), and so is a late `PENDING` or `FAILED` for a row that already failed or was cancelled.
 */
object RefundStateMachine {
    const val INVALID_STATE = "INVALID_STATE"

    /** `failureCode` of a `revokeFirst` refund that waited 24 h (21 section 3.5). */
    const val REVOKE_TIMEOUT = "REVOKE_TIMEOUT"

    /** `failureCode` of a refund cancelled because a dispute opened on the order (21 section 5.2 step 4). */
    const val CHARGEBACK = "CHARGEBACK"

    /** A `PENDING` refund is asked about again after 5 minutes (21 section 3.3). */
    const val PENDING_FIRST_QUERY_MS = 5L * 60 * 1000

    /** `revokeFirst` gives up after 24 h (21 section 3.5). */
    const val REVOKE_FIRST_TIMEOUT_MS = 24L * 60 * 60 * 1000

    /** A new row. A gateway-originated refund is inserted directly in the state the gateway reported; every other origin starts `REQUESTED`. */
    fun insert(origin: RefundOrigin, reported: RefundState? = null): RefundTransition.Move {
        if (origin != RefundOrigin.GATEWAY) return RefundTransition.Move(RefundStatus.REQUESTED, emptyList())
        require(reported != null) { "a gateway-originated refund is inserted in the state the gateway reported" }
        return when (reported) {
            RefundState.SUCCEEDED -> RefundTransition.Move(RefundStatus.SUCCEEDED, succeededEffects(fromFailed = false))
            RefundState.PENDING -> RefundTransition.Move(RefundStatus.PENDING, listOf(RefundEffect.ScheduleQuery(PENDING_FIRST_QUERY_MS)))
            RefundState.FAILED -> RefundTransition.Move(RefundStatus.FAILED, failedEffects(null, null))
            RefundState.CANCELLED -> RefundTransition.Move(RefundStatus.CANCELLED, listOf(RefundEffect.ClearQuery))
        }
    }

    fun decide(row: RefundRowState, event: RefundEvent): RefundTransition {
        val status = row.status
        return when (event) {
            is RefundEvent.Reported -> reported(status, event)

            RefundEvent.AdminRetry -> when {
                status == RefundStatus.FAILED ->
                    RefundTransition.Move(RefundStatus.REQUESTED, listOf(RefundEffect.ClearFailure, RefundEffect.ResendToGateway))
                status == RefundStatus.REQUESTED && row.outcomeUnknown && !row.revokeFirstWaiting ->
                    RefundTransition.Move(RefundStatus.REQUESTED, listOf(RefundEffect.ResendToGateway))
                else -> RefundTransition.Rejected(INVALID_STATE, status)
            }

            RefundEvent.AdminCancel ->
                if (status == RefundStatus.FAILED || (status == RefundStatus.REQUESTED && row.idle))
                    RefundTransition.Move(RefundStatus.CANCELLED, listOf(RefundEffect.ClearQuery))
                else RefundTransition.Rejected(INVALID_STATE, status)

            RefundEvent.RevokeTimeout ->
                if (status == RefundStatus.REQUESTED && row.revokeFirstWaiting)
                    RefundTransition.Move(
                        RefundStatus.CANCELLED,
                        listOf(RefundEffect.RecordFailure(REVOKE_TIMEOUT, null), RefundEffect.ClearQuery, RefundEffect.PanelAlert(REVOKE_TIMEOUT))
                    )
                else RefundTransition.NoOp

            RefundEvent.ChargebackOpened ->
                if (status == RefundStatus.REQUESTED && row.idle)
                    RefundTransition.Move(RefundStatus.CANCELLED, listOf(RefundEffect.RecordFailure(CHARGEBACK, null), RefundEffect.ClearQuery))
                else RefundTransition.NoOp
        }
    }

    private fun reported(status: RefundStatus, event: RefundEvent.Reported): RefundTransition = when (event.state) {
        RefundState.SUCCEEDED -> when (status) {
            RefundStatus.REQUESTED, RefundStatus.PENDING ->
                RefundTransition.Move(RefundStatus.SUCCEEDED, succeededEffects(fromFailed = false))
            // The gateway's word about money that left wins over a failure or a cancel recorded here.
            RefundStatus.FAILED, RefundStatus.CANCELLED ->
                RefundTransition.Move(RefundStatus.SUCCEEDED, succeededEffects(fromFailed = true))
            RefundStatus.SUCCEEDED -> RefundTransition.NoOp
        }

        RefundState.PENDING ->
            if (status == RefundStatus.REQUESTED)
                RefundTransition.Move(RefundStatus.PENDING, listOf(RefundEffect.ScheduleQuery(PENDING_FIRST_QUERY_MS)))
            else RefundTransition.NoOp

        RefundState.FAILED ->
            if (status == RefundStatus.REQUESTED || status == RefundStatus.PENDING)
                RefundTransition.Move(RefundStatus.FAILED, failedEffects(event.failureCode, event.failureMessage))
            else RefundTransition.NoOp

        RefundState.CANCELLED ->
            if (status == RefundStatus.PENDING) RefundTransition.Move(RefundStatus.CANCELLED, listOf(RefundEffect.ClearQuery))
            else RefundTransition.NoOp
    }

    private fun succeededEffects(fromFailed: Boolean): List<RefundEffect> = buildList {
        if (fromFailed) add(RefundEffect.ClearFailure)
        add(RefundEffect.StampCompleted)
        add(RefundEffect.ClearQuery)
        add(RefundEffect.RunOrderEffects)
    }

    private fun failedEffects(code: String?, message: String?): List<RefundEffect> =
        listOf(RefundEffect.RecordFailure(code, message), RefundEffect.ClearQuery)
}
