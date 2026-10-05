package com.panomc.plugins.market.core.order

import com.panomc.plugins.market.db.model.PaymentStatus
import com.panomc.plugins.market.spi.payment.ReviewReason
import com.panomc.plugins.market.util.OrderStatus

/**
 * What the service must do in the same transaction when [OrderStateMachine] returns a `Move` (00 section 7.1,
 * 06 section 11). A closed set of value objects: no lambdas, so two decisions compare structurally (17 section 4 S13).
 * The machine only names the effects and fixes their order; the services own the SQL. Effects that only apply to
 * some orders (a physical line, a subscription, a credit part) are always listed and the service skips them when the
 * order has nothing to apply them to.
 */
sealed interface OrderEffect {
    // O1 (create)
    /** Stock, coupon / discount / gift usage and per-player limits move to `HELD`. */
    data object ReserveStockAndLimits : OrderEffect

    /** The credit part is held in the ledger. */
    data object HoldCredits : OrderEffect

    /** `expiresAt` is set from `OrderTimings`. */
    data object SetExpiresAt : OrderEffect

    // O2 / O4 (paid)
    data object CommitReservation : OrderEffect

    /** Capture of the order's outstanding `HOLD` in the ledger (07 section 5). */
    data object CaptureCreditHold : OrderEffect
    data object ApplyRedemptions : OrderEffect

    /**
     * `paymentId`, `paidAt = now`, `paidAmount`, `testMode` of the attempt, `exchangeRate` frozen. [keepPaidAmount]
     * (O4): the amount stays as received. [attemptId] is `null` for admin "mark paid".
     */
    data class StampPaid(val attemptId: Long?, val keepPaidAmount: Boolean = false) : OrderEffect

    /** `expiresAt = NULL`. */
    data object ClearExpiry : OrderEffect

    /** Other open attempts of the order are cancelled in the same transaction. */
    data object CancelOpenAttempts : OrderEffect
    data object AccrueCreatorEarning : OrderEffect
    data object GrantCashback : OrderEffect

    /** `TOPUP` / `GIFT` lines credit the ledger. */
    data object CreditGrantingLines : OrderEffect
    data object GrantEntitlements : OrderEffect

    /** `GRANT` deliveries; none when `fulfillmentBy = GATEWAY`. */
    data object QueueGrantDeliveries : OrderEffect
    data object SubscriptionOnOrderPaid : OrderEffect
    data object IssueInvoice : OrderEffect

    /** `market_mail_outbox` row of the given `MailKind` name (12 section 3). */
    data class QueueMail(val kind: String) : OrderEffect

    /** Store webhook, e.g. `order.paid`. */
    data class QueueWebhook(val event: String) : OrderEffect
    data object AdvanceGoalProgress : OrderEffect

    /** `shippingStatus = PENDING` when the order has a physical line. */
    data object StartShipping : OrderEffect

    // O3 / O9 (review)
    data class SetReviewReason(val reason: ReviewReason) : OrderEffect

    /** `paymentId` and `paidAmount` of the attempt that brought the money. */
    data class RecordPayment(val attemptId: Long?) : OrderEffect
    data object PanelAlert : OrderEffect

    // O4 (review accepted)
    /** Re-reserve a released hold; [force] skips limits / cooldown / requirements and clamps stock at 0 (06 section 7.4). */
    data class ReReserve(val force: Boolean) : OrderEffect

    /** `AMOUNT_MISMATCH`: the order's tender becomes the paid attempt's snapshot, credits re-held (06 section 9.4). */
    data object RewriteTender : OrderEffect

    /** `force: true` completed a released order without re-reserving codes and limits: written to the timeline. */
    data object RecordForceOverride : OrderEffect

    // O5 - O8 (closed unpaid)
    /** Reservation `HELD` to `RELEASED`: stock, counters, redemptions and the credit hold go back (never "refunded"). */
    data object ReleaseReservation : OrderEffect

    /** Open attempts are set to [to] in the same transaction. */
    data class CloseOpenAttempts(val to: PaymentStatus) : OrderEffect

    /** `cancelPayment` after commit, best effort, failures ignored. */
    data object CancelAtGatewayAfterCommit : OrderEffect

    /** For an order with `subscriptionId`; a no-op otherwise. */
    data object SubscriptionOnClosedUnpaid : OrderEffect

    /** `RefundService.create(origin = SYSTEM)` for `paidAmount`. */
    data object CreateRefundForPaidAmount : OrderEffect

    // O10 - O12 (refunds, disputes; 21 sections 3 and 5)
    /** `REVOKE` deliveries (for a dispute: following upgrade successors). The service applies `revokeOnRefund` / `revokeOnChargeback`. */
    data class RevokeDeliveries(val onDispute: Boolean) : OrderEffect
    data object RestockItems : OrderEffect
    data object ReverseCreatorEarning : OrderEffect
    data object ReverseCashback : OrderEffect

    /** Clawback of credits granted by credit-granting lines (a dispute clawback may leave a credit debt). */
    data object ClawbackGrantedCredits : OrderEffect

    /** The credit part of the refund goes back to the ledger. */
    data object ReturnCreditsToLedger : OrderEffect
    data object IssueCreditNote : OrderEffect
    data object SubscriptionOnOrderRefunded : OrderEffect
    data object SubscriptionOnOrderChargeback : OrderEffect

    /** `statusBeforeDispute` is saved so that O12 can return to it. */
    data class SaveStatusBeforeDispute(val status: OrderStatus) : OrderEffect

    /** Block-list entry (recipient + e-mail) when `autoBlockOnChargeback`. */
    data object BlockBuyer : OrderEffect

    /** Configured chargeback actions (21 section 5). */
    data object RunChargebackActions : OrderEffect
}
