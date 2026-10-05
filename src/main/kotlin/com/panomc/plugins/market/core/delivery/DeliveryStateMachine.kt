package com.panomc.plugins.market.core.delivery

import com.panomc.plugins.market.core.time.Backoff
import com.panomc.plugins.market.db.model.DeliveryActionType
import com.panomc.plugins.market.db.model.DeliveryPhase
import com.panomc.plugins.market.db.model.DeliverySourceType
import com.panomc.plugins.market.db.model.DeliveryStatus
import com.panomc.plugins.market.db.model.DeliveryTransport
import kotlin.random.Random

/** `market_delivery.lastErrorCode` catalogue (08 section 18) as constants; the strings are the stored values. */
object DeliveryError {
    const val RENDER_ERROR = "RENDER_ERROR"
    const val NO_TARGET_SERVER = "NO_TARGET_SERVER"
    const val INVALID_PLAYER = "INVALID_PLAYER"
    const val NO_ACCOUNT = "NO_ACCOUNT"
    const val NEEDS_CONFIRMATION = "NEEDS_CONFIRMATION"
    const val DB_ERROR = "DB_ERROR"
    const val SERVER_OFFLINE = "SERVER_OFFLINE"
    const val COMPONENT_MISSING = "COMPONENT_MISSING"
    const val VERSION_MISMATCH = "VERSION_MISMATCH"
    const val SERVER_REMOVED = "SERVER_REMOVED"
    const val UNKNOWN_OUTCOME = "UNKNOWN_OUTCOME"
    const val ONLINE_WAIT_EXPIRED = "ONLINE_WAIT_EXPIRED"
    const val COMMAND_ERROR = "COMMAND_ERROR"
    const val LUCKPERMS_MISSING = "LUCKPERMS_MISSING"
    const val DISABLED_LOCALLY = "DISABLED_LOCALLY"
    const val INVALID_PAYLOAD = "INVALID_PAYLOAD"
    const val REJECTED = "REJECTED"
    const val WEBHOOK_DEAD = "WEBHOOK_DEAD"
    const val CANCELLED_BY_ADMIN = "CANCELLED_BY_ADMIN"
    const val ORDER_REVOKED = "ORDER_REVOKED"
    const val ENTITLEMENT_ENDED = "ENTITLEMENT_ENDED"
    const val NOTHING_TO_REVOKE = "NOTHING_TO_REVOKE"

    /** HTTP codes of the panel operations (08 section 18). */
    const val DELIVERY_NOT_RETRYABLE = "DELIVERY_NOT_RETRYABLE"
    const val DELIVERY_NOT_CANCELLABLE = "DELIVERY_NOT_CANCELLABLE"
}

/**
 * The columns of a `market_delivery` row that decisions depend on. One shape for the state machine, the fulfilment
 * calculator and the planner's "what was executed" checks, so a service reads the row once and hands it to all three.
 */
data class DeliveryRow(
    val sourceType: DeliverySourceType = DeliverySourceType.ORDER_ITEM,
    val orderItemId: Long? = null,
    val actionId: String = "",
    val actionType: DeliveryActionType = DeliveryActionType.COMMAND,
    val phase: DeliveryPhase = DeliveryPhase.GRANT,
    val serverId: Long = 0,
    val unitIndex: Int = 0,
    val attemptGroup: Int = 0,
    val transport: DeliveryTransport = DeliveryTransport.INLINE,
    val status: DeliveryStatus = DeliveryStatus.PENDING,
    val attempts: Int = 0,
    val runAfter: Long = 0,
    val nextAttemptAt: Long? = null,
    val cancelRequestedAt: Long? = null,
    val waitUntil: Long? = null,
    val claimedUntil: Long? = null,
    val sentAt: Long? = null,
    val lastErrorCode: String? = null
)

/** Settings of `MarketConfig` the transitions use: `deliveryMaxAttempts` and `deliveryAckTimeoutSeconds`. */
data class DeliveryRules(val maxAttempts: Int = 5, val ackTimeoutSeconds: Int = 30)

/** Result status of a `MARKET_SYNC` result entry (08 section 8.1). */
enum class ResultStatus { DONE, QUEUED, FAILED, EXPIRED, CANCELLED, UNKNOWN }

/** Everything that can happen to a delivery row (the "event" column of 08 section 6). */
sealed class DeliveryEvent {
    /** D1: the job found `runAfter <= now`. */
    data object Promote : DeliveryEvent()

    /** D2: `DeliveryJob` claims an inline row. */
    data object Claim : DeliveryEvent()

    /** D3: the inline executor succeeded; [result] is the JSON written to `result`. */
    data class InlineSucceeded(val result: String? = null) : DeliveryEvent()

    /** D4 / D5: the inline executor failed. A non-[retryable] error, or one after the last attempt, is final. */
    data class InlineFailed(val code: String, val message: String? = null, val retryable: Boolean = false) : DeliveryEvent()

    /** D6: the job found `claimedUntil < now` on a `SENDING` row. */
    data object ClaimExpired : DeliveryEvent()

    /** D7: classification found the target server not ready (`SERVER_OFFLINE`, `COMPONENT_MISSING`, `VERSION_MISMATCH`). */
    data class ServerNotReady(val code: String) : DeliveryEvent()

    /** D8 / D9 / D10: the row is selected for a `MARKET_SYNC` response (first offer or re-offer of the same key). */
    data object Offer : DeliveryEvent()

    /**
     * A result entry of the Minecraft component (D11 - D15). [code] / [message] are the component's; [result] is the
     * JSON for `result`.
     */
    data class ServerResult(
        val status: ResultStatus,
        val code: String? = null,
        val message: String? = null,
        val result: String? = null
    ) : DeliveryEvent()

    /** D14: the job found `waitUntil + 1 h < now` on a `SENT` / `QUEUED` row. */
    data object WaitExpired : DeliveryEvent()

    /** D16 / D17: [reason] is `CANCELLED_BY_ADMIN`, `ORDER_REVOKED` or `ENTITLEMENT_ENDED`. */
    data class Cancel(val reason: String = DeliveryError.CANCELLED_BY_ADMIN) : DeliveryEvent()

    /** D18 / D19: admin retry of the same row (same key). */
    data object Retry : DeliveryEvent()

    /** D20: the target server row no longer exists. */
    data object ServerRemoved : DeliveryEvent()

    /** D12 for an action webhook: the outbox row `SUCCEEDED` (first time or after a redeliver). */
    data object WebhookSucceeded : DeliveryEvent()

    /** D21: the outbox row of an action webhook is `DEAD`. */
    data object WebhookDead : DeliveryEvent()

    /**
     * D22: the predecessor gate (08 section 11.4) is open. [nothingDelivered] = no row of the item that the undo refers
     * to is `CONFIRMED` or `FAILED (UNKNOWN_OUTCOME)`; the service evaluates the SQL, the machine only applies the rule.
     */
    data class GateOpened(val nothingDelivered: Boolean) : DeliveryEvent()
}

/** What the service writes besides the new status, in the same transaction (value objects: decisions compare structurally). */
sealed interface DeliveryEffect {
    /** `claimToken` = a fresh UUID (chosen by the service), `claimedUntil = until`. */
    data class Claim(val until: Long) : DeliveryEffect

    /** `claimToken` / `claimedUntil` = NULL. */
    data object ClearClaim : DeliveryEffect

    data object IncrementAttempts : DeliveryEffect
    data object ResetAttempts : DeliveryEffect

    /** `sentAt = COALESCE(sentAt, at)`. */
    data class StampSent(val at: Long) : DeliveryEffect
    data class StampConfirmed(val at: Long) : DeliveryEffect

    /** `result` = this JSON text. */
    data class RecordResult(val result: String) : DeliveryEffect

    /** `nextAttemptAt`; `null` = waiting for an event. */
    data class SetNextAttemptAt(val at: Long?) : DeliveryEffect

    /** `lastErrorCode`, `lastError` (at most 512 characters). */
    data class SetError(val code: String, val message: String? = null) : DeliveryEffect

    /** `lastErrorCode` and `lastError` = NULL. */
    data object ClearError : DeliveryEffect

    /** `cancelRequestedAt = at` (the key goes into the next sync's `cancel` list). */
    data class RequestCancel(val at: Long) : DeliveryEffect

    /** Re-compute `market_order.fulfillmentStatus` under the order lock ([FulfillmentCalculator]). */
    data object RecomputeFulfillment : DeliveryEffect

    /** `market_order_event` `type=DELIVERY_FAILED`, `actorType=SYSTEM`, `data={deliveryId, code}`. */
    data class RecordDeliveryFailed(val code: String) : DeliveryEffect
}

sealed class DeliveryTransition {
    /** An admin operation that the row's state does not allow (409 [errorCode]). */
    data class Rejected(val errorCode: String) : DeliveryTransition()

    /** Replay, race lost or not applicable: nothing changes. The service never treats it as an error. */
    data object NoOp : DeliveryTransition()

    /**
     * Apply `UPDATE ... SET status = [to] ... WHERE id = ? AND status = :from`; zero rows means re-read and re-decide
     * once. [to] may equal the current status (re-offer, label refresh, cancel request). [rule] is the row of the table
     * (`D1` ... `D22`). Transitions that touch the order lock it first (08 section 6, last paragraph).
     */
    data class Move(val to: DeliveryStatus, val effects: List<DeliveryEffect>, val rule: String) : DeliveryTransition() {
        val touchesOrder: Boolean
            get() = effects.any { it is DeliveryEffect.RecomputeFulfillment || it is DeliveryEffect.RecordDeliveryFailed }
    }
}

/**
 * Delivery state machine (00 section 7.4, 08 section 6: D1 - D22), pure. `decide` is the only place that maps events
 * to transitions; the service applies the result with a conditional update.
 *
 * Invariants the table fixes and this object keeps:
 * - Server rows (`MARKET_MC`) are never `SENDING`: only inline rows are claimed.
 * - `SENT` is never final: a server row is re-offered with the same key until a result arrives (D9) and the budget
 *   ends in `FAILED (UNKNOWN_OUTCOME)` (D10), which a later positive result overrides (D12).
 * - `WAITING_PLAYER` is a legal value but is never entered; only a cancel (D16) or a server removal (D20) leaves it.
 * - A positive result always wins (D12): `DONE` / webhook success move `SENT`, `QUEUED` and the late-result failures
 *   (`UNKNOWN_OUTCOME`, `ONLINE_WAIT_EXPIRED`, `WEBHOOK_DEAD`) to `CONFIRMED`; any other `FAILED` stays.
 */
object DeliveryStateMachine {
    const val CLAIM_MS = 60_000L

    /** D7: a `PENDING` server row waits this long past `runAfter` before it is labelled `WAITING_SERVER`. */
    const val NOT_READY_MS = 15_000L

    /** D14: the grace on top of `waitUntil`. */
    const val WAIT_GRACE_MS = 3_600_000L

    /** D9 / D10: offers beyond `deliveryMaxAttempts` before the row gives up. */
    const val REOFFER_EXTRA = 5

    /** D10 / 14.2: the game server keeps a key for at least 30 days; older rows are not offered or retried again. */
    const val MAX_AGE_MS = 30L * 86_400_000L

    /** 08 section 7.2: a confirmed permission grant is verified again this long after confirmation. */
    const val REASSERT_MS = 60_000L

    const val MAX_ERROR_LENGTH = 512

    private val UNSENT = setOf(DeliveryStatus.PENDING, DeliveryStatus.SCHEDULED, DeliveryStatus.WAITING_SERVER, DeliveryStatus.WAITING_PLAYER)
    private val IN_FLIGHT = setOf(DeliveryStatus.SENT, DeliveryStatus.QUEUED)
    private val NON_TERMINAL = UNSENT + IN_FLIGHT + DeliveryStatus.SENDING
    private val LATE_POSITIVE = setOf(DeliveryError.UNKNOWN_OUTCOME, DeliveryError.ONLINE_WAIT_EXPIRED, DeliveryError.WEBHOOK_DEAD)

    /**
     * 08 section 14.2: these failures are not retried through D18. `WEBHOOK_DEAD` is added to the four of the table
     * (a retry would find the existing, dead outbox row and leave the delivery `SENT` for good; the way out is to redeliver
     * the webhook row, D12).
     */
    private val NOT_RETRYABLE = setOf(
        DeliveryError.RENDER_ERROR, DeliveryError.NO_TARGET_SERVER, DeliveryError.SERVER_REMOVED,
        DeliveryError.INVALID_PLAYER, DeliveryError.WEBHOOK_DEAD
    )

    private val COMPONENT_CODES = setOf(
        DeliveryError.COMMAND_ERROR, DeliveryError.LUCKPERMS_MISSING, DeliveryError.DISABLED_LOCALLY, DeliveryError.INVALID_PAYLOAD
    )
    private val CANCEL_REASONS = setOf(DeliveryError.CANCELLED_BY_ADMIN, DeliveryError.ORDER_REVOKED, DeliveryError.ENTITLEMENT_ENDED)
    private val READINESS_CODES = setOf(DeliveryError.SERVER_OFFLINE, DeliveryError.COMPONENT_MISSING, DeliveryError.VERSION_MISMATCH)

    /** `ackDelay(n) = min(1 h, deliveryAckTimeoutSeconds * 2^(n - 1))`, no jitter, `n` = attempts including this offer. */
    fun ackDelayMs(attempts: Int, rules: DeliveryRules = DeliveryRules()): Long =
        Backoff(baseMs = rules.ackTimeoutSeconds * 1000L, factor = 2.0, capMs = 3_600_000L, jitter = 0.0).baseDelayMs(attempts)

    /** `backoff(n) = min(1 h, 30 s * 2^(n - 1))` times a random factor in `[0.8, 1.2]` (D4). */
    fun retryDelayMs(attempts: Int, random: Random = Random.Default): Long = Backoff().delayMs(attempts, random)

    /** The code stored on a `FAILED` row for a component result (08 section 18): the four known codes, else `REJECTED`. */
    fun mapResultCode(code: String?): String = if (code in COMPONENT_CODES) code!! else DeliveryError.REJECTED

    /** 08 section 14.2: may the admin retry this row (D18 / D19)? `false` = 409 `DELIVERY_NOT_RETRYABLE`. */
    fun isRetryable(row: DeliveryRow, now: Long): Boolean = decideRetry(row, now) is DeliveryTransition.Move

    fun decide(
        row: DeliveryRow,
        event: DeliveryEvent,
        now: Long,
        rules: DeliveryRules = DeliveryRules(),
        random: Random = Random.Default
    ): DeliveryTransition = when (event) {
        DeliveryEvent.Promote -> promote(row, now)
        DeliveryEvent.Claim -> claim(row, now)
        is DeliveryEvent.InlineSucceeded -> inlineSucceeded(row, event, now)
        is DeliveryEvent.InlineFailed -> inlineFailed(row, event, now, rules, random)
        DeliveryEvent.ClaimExpired -> claimExpired(row, now)
        is DeliveryEvent.ServerNotReady -> serverNotReady(row, event, now)
        DeliveryEvent.Offer -> offer(row, now, rules)
        is DeliveryEvent.ServerResult -> serverResult(row, event, now)
        DeliveryEvent.WaitExpired -> waitExpired(row, now)
        is DeliveryEvent.Cancel -> cancel(row, event, now)
        DeliveryEvent.Retry -> decideRetry(row, now)
        DeliveryEvent.ServerRemoved -> serverRemoved(row)
        DeliveryEvent.WebhookSucceeded -> webhookSucceeded(row, now)
        DeliveryEvent.WebhookDead -> webhookDead(row)
        is DeliveryEvent.GateOpened -> gateOpened(row, event)
    }

    private fun move(to: DeliveryStatus, rule: String, vararg effects: DeliveryEffect) =
        DeliveryTransition.Move(to, effects.toList(), rule)

    private fun cut(text: String?): String? = text?.take(MAX_ERROR_LENGTH)

    private val DeliveryRow.isServerRow: Boolean get() = transport == DeliveryTransport.MARKET_MC
    private val DeliveryRow.isInlineRow: Boolean get() = transport == DeliveryTransport.INLINE

    /** D1 */
    private fun promote(row: DeliveryRow, now: Long): DeliveryTransition {
        if (row.status != DeliveryStatus.SCHEDULED || row.runAfter > now) return DeliveryTransition.NoOp

        return move(DeliveryStatus.PENDING, "D1", DeliveryEffect.SetNextAttemptAt(now))
    }

    /** D2: only inline rows are claimed; a server row never becomes `SENDING`. */
    private fun claim(row: DeliveryRow, now: Long): DeliveryTransition {
        if (row.status != DeliveryStatus.PENDING || !row.isInlineRow) return DeliveryTransition.NoOp

        val due = row.nextAttemptAt
        if (due == null || due > now) return DeliveryTransition.NoOp

        return move(DeliveryStatus.SENDING, "D2", DeliveryEffect.Claim(now + CLAIM_MS), DeliveryEffect.IncrementAttempts)
    }

    /** D3: `CREDIT` / `PERMISSION` are done (`CONFIRMED`); a `WEBHOOK` has only written its outbox row (`SENT`). */
    private fun inlineSucceeded(row: DeliveryRow, event: DeliveryEvent.InlineSucceeded, now: Long): DeliveryTransition {
        if (row.status != DeliveryStatus.SENDING || !row.isInlineRow) return DeliveryTransition.NoOp

        val effects = ArrayList<DeliveryEffect>()
        effects += DeliveryEffect.StampSent(now)

        val webhook = row.actionType == DeliveryActionType.WEBHOOK

        if (!webhook) effects += DeliveryEffect.StampConfirmed(now)

        event.result?.let { effects += DeliveryEffect.RecordResult(it) }
        effects += DeliveryEffect.ClearClaim

        // 08 section 7.2: a confirmed ADD / EXTEND of a permission is verified again after 60 s; the job then walks 5 min, 15 min, NULL.
        val reassert = !webhook && row.actionType == DeliveryActionType.PERMISSION &&
            (row.phase == DeliveryPhase.GRANT || row.phase == DeliveryPhase.RENEW)

        effects += DeliveryEffect.SetNextAttemptAt(if (reassert) now + REASSERT_MS else null)
        effects += DeliveryEffect.ClearError
        effects += DeliveryEffect.RecomputeFulfillment

        return DeliveryTransition.Move(if (webhook) DeliveryStatus.SENT else DeliveryStatus.CONFIRMED, effects, "D3")
    }

    /** D4 (retryable, attempts left) / D5 (non-retryable or exhausted). `attempts` already counts this try (D2). */
    private fun inlineFailed(
        row: DeliveryRow,
        event: DeliveryEvent.InlineFailed,
        now: Long,
        rules: DeliveryRules,
        random: Random
    ): DeliveryTransition {
        if (row.status != DeliveryStatus.SENDING || !row.isInlineRow) return DeliveryTransition.NoOp

        if (event.retryable && row.attempts < rules.maxAttempts) {
            // An end flow asked for the cancel while the row was claimed (D17 on SENDING): it must not run again later.
            if (row.cancelRequestedAt != null) return cancelledWhileSending(row, "D4")

            return move(
                DeliveryStatus.PENDING, "D4",
                DeliveryEffect.SetNextAttemptAt(now + retryDelayMs(row.attempts, random)),
                DeliveryEffect.SetError(event.code, cut(event.message)),
                DeliveryEffect.ClearClaim
            )
        }

        return move(
            DeliveryStatus.FAILED, "D5",
            DeliveryEffect.SetError(event.code, cut(event.message)),
            DeliveryEffect.SetNextAttemptAt(null),
            DeliveryEffect.ClearClaim,
            DeliveryEffect.RecordDeliveryFailed(event.code),
            DeliveryEffect.RecomputeFulfillment
        )
    }

    /** D6: a crashed worker; inline executors are idempotent, so the row simply goes back to `PENDING`. */
    private fun claimExpired(row: DeliveryRow, now: Long): DeliveryTransition {
        val until = row.claimedUntil

        if (row.status != DeliveryStatus.SENDING || until == null || until >= now) return DeliveryTransition.NoOp

        // A cancel was requested while the claim ran (D17 on SENDING): the grant never took effect and must not run again.
        if (row.cancelRequestedAt != null) return cancelledWhileSending(row, "D6")

        return move(DeliveryStatus.PENDING, "D6", DeliveryEffect.SetNextAttemptAt(now), DeliveryEffect.ClearClaim)
    }

    /**
     * D4 / D6 of a `SENDING` row whose cancel was requested by an end flow (D17): the attempt did not take effect, so the
     * row ends `CANCELLED` with the recorded reason instead of going back to `PENDING` (where it would run later, after
     * D22 already cancelled the inverse that was waiting for it).
     */
    private fun cancelledWhileSending(row: DeliveryRow, rule: String): DeliveryTransition {
        val effects = ArrayList<DeliveryEffect>()

        effects += DeliveryEffect.SetNextAttemptAt(null)
        effects += DeliveryEffect.ClearClaim
        if (row.lastErrorCode !in CANCEL_REASONS) effects += DeliveryEffect.SetError(DeliveryError.CANCELLED_BY_ADMIN)
        effects += DeliveryEffect.RecomputeFulfillment

        return DeliveryTransition.Move(DeliveryStatus.CANCELLED, effects, rule)
    }

    /** D7: after [NOT_READY_MS] of waiting; also refreshes the reason label of a row that is already `WAITING_SERVER`. */
    private fun serverNotReady(row: DeliveryRow, event: DeliveryEvent.ServerNotReady, now: Long): DeliveryTransition {
        if (!row.isServerRow) return DeliveryTransition.NoOp

        if (event.code !in READINESS_CODES) return DeliveryTransition.Rejected("INVALID_READINESS_CODE")

        return when (row.status) {
            DeliveryStatus.PENDING ->
                if (row.runAfter > now - NOT_READY_MS) {
                    DeliveryTransition.NoOp
                } else {
                    move(DeliveryStatus.WAITING_SERVER, "D7", DeliveryEffect.SetError(event.code), DeliveryEffect.SetNextAttemptAt(null))
                }

            DeliveryStatus.WAITING_SERVER ->
                if (row.lastErrorCode == event.code) {
                    DeliveryTransition.NoOp
                } else {
                    move(DeliveryStatus.WAITING_SERVER, "D7", DeliveryEffect.SetError(event.code))
                }

            else -> DeliveryTransition.NoOp
        }
    }

    /** D8 (first offer), D9 (re-offer, same key), D10 (re-offer budget exhausted). */
    private fun offer(row: DeliveryRow, now: Long, rules: DeliveryRules): DeliveryTransition {
        if (!row.isServerRow || row.cancelRequestedAt != null) return DeliveryTransition.NoOp

        fun offered(rule: String) = move(
            DeliveryStatus.SENT, rule,
            DeliveryEffect.IncrementAttempts,
            DeliveryEffect.StampSent(now),
            DeliveryEffect.SetNextAttemptAt(now + ackDelayMs(row.attempts + 1, rules)),
            DeliveryEffect.ClearError
        )

        return when (row.status) {
            DeliveryStatus.PENDING, DeliveryStatus.WAITING_SERVER ->
                if (row.runAfter > now) DeliveryTransition.NoOp else offered("D8")

            DeliveryStatus.SENT -> {
                val due = row.nextAttemptAt

                if (due == null || due > now) {
                    DeliveryTransition.NoOp
                } else if (row.attempts >= rules.maxAttempts + REOFFER_EXTRA || (row.sentAt != null && row.sentAt < now - MAX_AGE_MS)) {
                    move(
                        DeliveryStatus.FAILED, "D10",
                        DeliveryEffect.SetError(DeliveryError.UNKNOWN_OUTCOME),
                        DeliveryEffect.SetNextAttemptAt(null),
                        DeliveryEffect.RecordDeliveryFailed(DeliveryError.UNKNOWN_OUTCOME),
                        DeliveryEffect.RecomputeFulfillment
                    )
                } else {
                    offered("D9")
                }
            }

            else -> DeliveryTransition.NoOp
        }
    }

    /** D11 - D15: the Minecraft component answered. At-least-once: a result for a row already past it is a no-op. */
    private fun serverResult(row: DeliveryRow, event: DeliveryEvent.ServerResult, now: Long): DeliveryTransition {
        if (!row.isServerRow) return DeliveryTransition.NoOp

        val result = event.result?.let { DeliveryEffect.RecordResult(it) }

        fun withResult(vararg effects: DeliveryEffect) = if (result == null) effects.toList() else listOf(result) + effects

        return when (event.status) {
            // D11: ignored unless the row is SENT.
            ResultStatus.QUEUED ->
                if (row.status == DeliveryStatus.SENT) {
                    DeliveryTransition.Move(DeliveryStatus.QUEUED, withResult(DeliveryEffect.SetNextAttemptAt(null)), "D11")
                } else {
                    DeliveryTransition.NoOp
                }

            // D12: a positive result always wins, also over a failure that may not have been one.
            ResultStatus.DONE ->
                if (row.status in IN_FLIGHT || (row.status == DeliveryStatus.FAILED && row.lastErrorCode in LATE_POSITIVE)) {
                    confirmed(row, "D12", now, result)
                } else {
                    DeliveryTransition.NoOp
                }

            // D13
            ResultStatus.FAILED ->
                if (row.status in IN_FLIGHT) {
                    failedByComponent(event)
                } else {
                    DeliveryTransition.NoOp
                }

            // D14
            ResultStatus.EXPIRED ->
                if (row.status in IN_FLIGHT) {
                    DeliveryTransition.Move(
                        DeliveryStatus.FAILED,
                        withResult(
                            DeliveryEffect.SetError(DeliveryError.ONLINE_WAIT_EXPIRED, cut(event.message)),
                            DeliveryEffect.SetNextAttemptAt(null),
                            DeliveryEffect.RecordDeliveryFailed(DeliveryError.ONLINE_WAIT_EXPIRED),
                            DeliveryEffect.RecomputeFulfillment
                        ),
                        "D14"
                    )
                } else {
                    DeliveryTransition.NoOp
                }

            // D15 when a cancel was asked; a CANCELLED answer to a cancel nobody asked for is a failure (08 section 8.4).
            ResultStatus.CANCELLED ->
                if (row.status !in IN_FLIGHT) {
                    DeliveryTransition.NoOp
                } else if (row.cancelRequestedAt != null) {
                    cancelled(row)
                } else {
                    failedByComponent(ServerResultFailure(DeliveryError.REJECTED, "CANCELLED${event.message?.let { ": $it" } ?: ""}", event.result))
                }

            // D15 when a cancel was asked (the delivery never reached the server); otherwise ignored.
            ResultStatus.UNKNOWN ->
                if (row.status in IN_FLIGHT && row.cancelRequestedAt != null) cancelled(row) else DeliveryTransition.NoOp
        }
    }

    private class ServerResultFailure(val code: String, val message: String?, val result: String?)

    private fun failedByComponent(event: DeliveryEvent.ServerResult) =
        failedByComponent(
            ServerResultFailure(
                mapResultCode(event.code),
                // REJECTED keeps the original code in lastError (08 section 18).
                if (mapResultCode(event.code) == DeliveryError.REJECTED && event.code != null) "${event.code}${event.message?.let { ": $it" } ?: ""}" else event.message,
                event.result
            )
        )

    private fun failedByComponent(failure: ServerResultFailure): DeliveryTransition {
        val effects = ArrayList<DeliveryEffect>()

        failure.result?.let { effects += DeliveryEffect.RecordResult(it) }
        effects += DeliveryEffect.SetError(failure.code, cut(failure.message))
        effects += DeliveryEffect.SetNextAttemptAt(null)
        effects += DeliveryEffect.RecordDeliveryFailed(failure.code)
        effects += DeliveryEffect.RecomputeFulfillment

        return DeliveryTransition.Move(DeliveryStatus.FAILED, effects, "D13")
    }

    private fun confirmed(row: DeliveryRow, rule: String, now: Long, result: DeliveryEffect.RecordResult?): DeliveryTransition {
        val effects = ArrayList<DeliveryEffect>()

        effects += DeliveryEffect.StampConfirmed(now)
        if (result != null) effects += result
        effects += DeliveryEffect.ClearError
        effects += DeliveryEffect.SetNextAttemptAt(null)
        effects += DeliveryEffect.RecomputeFulfillment

        return DeliveryTransition.Move(DeliveryStatus.CONFIRMED, effects, rule)
    }

    /** D15: the reason recorded when the cancel was requested stays; a row without one is an admin cancel. */
    private fun cancelled(row: DeliveryRow): DeliveryTransition {
        val effects = ArrayList<DeliveryEffect>()

        effects += DeliveryEffect.SetNextAttemptAt(null)
        if (row.lastErrorCode !in CANCEL_REASONS) effects += DeliveryEffect.SetError(DeliveryError.CANCELLED_BY_ADMIN)
        effects += DeliveryEffect.RecomputeFulfillment

        return DeliveryTransition.Move(DeliveryStatus.CANCELLED, effects, "D15")
    }

    /** D14 (job). */
    private fun waitExpired(row: DeliveryRow, now: Long): DeliveryTransition {
        val until = row.waitUntil

        if (!row.isServerRow || row.status !in IN_FLIGHT || until == null || until + WAIT_GRACE_MS >= now) return DeliveryTransition.NoOp

        return move(
            DeliveryStatus.FAILED, "D14",
            DeliveryEffect.SetError(DeliveryError.ONLINE_WAIT_EXPIRED),
            DeliveryEffect.SetNextAttemptAt(null),
            DeliveryEffect.RecordDeliveryFailed(DeliveryError.ONLINE_WAIT_EXPIRED),
            DeliveryEffect.RecomputeFulfillment
        )
    }

    /**
     * D16 (not yet sent: cancelled at once), D17 (in flight: only a request, the row turns `CANCELLED` or `CONFIRMED`
     * when the server answers). The reason is stored as `lastErrorCode` on the in-flight row so that D15 can keep it;
     * a `DONE` answer clears it again. Terminal rows are `DELIVERY_NOT_CANCELLABLE` (08 section 14.3), and so is a
     * `SENDING` row for the admin reason; an end flow (`ORDER_REVOKED` / `ENTITLEMENT_ENDED`, 08 section 11.1) gets D17
     * on a claimed inline row (review fix: the claim-to-execute window would otherwise let a refunded grant still run).
     */
    private fun cancel(row: DeliveryRow, event: DeliveryEvent.Cancel, now: Long): DeliveryTransition {
        val reason = if (event.reason in CANCEL_REASONS) event.reason else DeliveryError.CANCELLED_BY_ADMIN

        return when {
            row.status in UNSENT ->
                move(
                    DeliveryStatus.CANCELLED, "D16",
                    DeliveryEffect.SetError(reason),
                    DeliveryEffect.SetNextAttemptAt(null),
                    DeliveryEffect.RecomputeFulfillment
                )

            // An end flow (08 section 11.1) reaches a claimed inline row too: only a request, which the executor, D4, D5 or D6
            // resolves. The admin cancel of 14.3 keeps rejecting SENDING.
            row.status in IN_FLIGHT || (row.status == DeliveryStatus.SENDING && row.isInlineRow && reason != DeliveryError.CANCELLED_BY_ADMIN) ->
                if (row.cancelRequestedAt != null) {
                    DeliveryTransition.NoOp
                } else {
                    move(row.status, "D17", DeliveryEffect.RequestCancel(now), DeliveryEffect.SetError(reason))
                }

            else -> DeliveryTransition.Rejected(DeliveryError.DELIVERY_NOT_CANCELLABLE)
        }
    }

    /** D18 / D19 and the rules of 08 section 14.2. */
    private fun decideRetry(row: DeliveryRow, now: Long): DeliveryTransition {
        val rejected = DeliveryTransition.Rejected(DeliveryError.DELIVERY_NOT_RETRYABLE)

        // A cancel is pending: the row is not offered any more, a retry would only hide that.
        if (row.cancelRequestedAt != null) return rejected

        return when (row.status) {
            DeliveryStatus.FAILED -> {
                val tooOld = row.sentAt != null && row.sentAt < now - MAX_AGE_MS

                if (row.lastErrorCode in NOT_RETRYABLE || tooOld) {
                    rejected
                } else {
                    move(
                        DeliveryStatus.PENDING, "D18",
                        DeliveryEffect.ResetAttempts,
                        DeliveryEffect.SetNextAttemptAt(now),
                        DeliveryEffect.ClearError,
                        DeliveryEffect.RecomputeFulfillment
                    )
                }
            }

            DeliveryStatus.WAITING_SERVER ->
                move(
                    DeliveryStatus.PENDING, "D18",
                    DeliveryEffect.ResetAttempts,
                    DeliveryEffect.SetNextAttemptAt(now),
                    DeliveryEffect.ClearError,
                    DeliveryEffect.RecomputeFulfillment
                )

            DeliveryStatus.SENT -> move(DeliveryStatus.SENT, "D19", DeliveryEffect.SetNextAttemptAt(now))

            else -> rejected
        }
    }

    /** D20 */
    private fun serverRemoved(row: DeliveryRow): DeliveryTransition {
        if (!row.isServerRow || row.status !in NON_TERMINAL) return DeliveryTransition.NoOp

        return move(
            DeliveryStatus.FAILED, "D20",
            DeliveryEffect.SetError(DeliveryError.SERVER_REMOVED),
            DeliveryEffect.SetNextAttemptAt(null),
            DeliveryEffect.ClearClaim,
            DeliveryEffect.RecordDeliveryFailed(DeliveryError.SERVER_REMOVED),
            DeliveryEffect.RecomputeFulfillment
        )
    }

    /** D12 for an action webhook (outbox row `SUCCEEDED`), also after a redeliver of a dead row. */
    private fun webhookSucceeded(row: DeliveryRow, now: Long): DeliveryTransition {
        if (!row.isInlineRow || row.actionType != DeliveryActionType.WEBHOOK) return DeliveryTransition.NoOp

        val ok = row.status == DeliveryStatus.SENT || (row.status == DeliveryStatus.FAILED && row.lastErrorCode == DeliveryError.WEBHOOK_DEAD)

        return if (ok) confirmed(row, "D12", now, null) else DeliveryTransition.NoOp
    }

    /** D21 */
    private fun webhookDead(row: DeliveryRow): DeliveryTransition {
        if (!row.isInlineRow || row.actionType != DeliveryActionType.WEBHOOK || row.status != DeliveryStatus.SENT) return DeliveryTransition.NoOp

        return move(
            DeliveryStatus.FAILED, "D21",
            DeliveryEffect.SetError(DeliveryError.WEBHOOK_DEAD),
            DeliveryEffect.SetNextAttemptAt(null),
            DeliveryEffect.RecordDeliveryFailed(DeliveryError.WEBHOOK_DEAD),
            DeliveryEffect.RecomputeFulfillment
        )
    }

    /**
     * D22: a held undo whose predecessor never took effect has nothing to undo. Applies to `PENDING` and, for server rows,
     * `WAITING_SERVER` (the table of 08 section 6 says `PENDING` only; the held row of an offline server is `WAITING_SERVER` after D7 and
     * would otherwise be offered at the next sync and run its revoke command for a grant that never executed).
     */
    private fun gateOpened(row: DeliveryRow, event: DeliveryEvent.GateOpened): DeliveryTransition {
        val undo = row.phase == DeliveryPhase.EXPIRE || row.phase == DeliveryPhase.REVOKE

        // A held server undo turns WAITING_SERVER by D7 when its server is not ready, so that state counts for server rows.
        val waiting = row.status == DeliveryStatus.PENDING || (row.status == DeliveryStatus.WAITING_SERVER && row.isServerRow)

        if (!waiting || !undo || !event.nothingDelivered) return DeliveryTransition.NoOp

        return move(
            DeliveryStatus.CANCELLED, "D22",
            DeliveryEffect.SetError(DeliveryError.NOTHING_TO_REVOKE),
            DeliveryEffect.SetNextAttemptAt(null),
            DeliveryEffect.RecomputeFulfillment
        )
    }
}
