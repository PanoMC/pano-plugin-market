package com.panomc.plugins.market.service

import com.panomc.platform.error.NotFound
import com.panomc.plugins.market.core.order.OrderActor
import com.panomc.plugins.market.core.order.OrderTimings
import com.panomc.plugins.market.core.payment.PaymentAttemptEvent
import com.panomc.plugins.market.core.payment.ProviderMoneyPolicy
import com.panomc.plugins.market.core.time.Clock
import com.panomc.plugins.market.db.dao.MarketOrderDao
import com.panomc.plugins.market.db.dao.MarketOrderEventDao
import com.panomc.plugins.market.db.dao.MarketPaymentDao
import com.panomc.plugins.market.db.model.MarketOrder
import com.panomc.plugins.market.db.model.MarketOrderEvent
import com.panomc.plugins.market.db.model.MarketPayment
import com.panomc.plugins.market.db.model.OrderActorType
import com.panomc.plugins.market.db.model.OrderEventType
import com.panomc.plugins.market.db.model.PaymentStatus
import com.panomc.plugins.market.db.tx.Locks
import com.panomc.plugins.market.db.tx.MarketDb
import com.panomc.plugins.market.db.tx.OrderLockScope
import com.panomc.plugins.market.db.tx.txRestartingOnOrderChange
import com.panomc.plugins.market.error.OrderNotPayable
import com.panomc.plugins.market.util.OrderStatus
import io.vertx.core.json.JsonObject

/** `decision` of `POST /orders/:id/bank-transfer` (04 section 7). */
enum class BankTransferDecision { APPROVE, REJECT }

/** What [BankTransferService.decide] did to the order: the order and attempt as they are now, and whether it ended in a late-payment review (O9). */
class BankTransferOutcome(val order: MarketOrder, val attempt: MarketPayment, val review: Boolean)

/**
 * The bank transfer flow on top of the attempt and order machines (MK-094; 06 section 14.1, 02 section 12): the buyer's "I have paid" notice and the admin's
 * approval or rejection. Nothing is written outside [PaymentService.applyIn], so every rule of 00 section 7 (O2, O8, O9, the amount check, the duplicate
 * flag) applies as it does to a gateway event; the provider never sees either step.
 *
 * - [notify]: `Pending(AWAITING_BANK)` on the newest `bank-transfer` attempt: `PENDING` becomes `PROCESSING`, `expiresAt` of the attempt and of the order
 *   stay as they are (a notice never extends the reservation, never earns a grace: `OrderExpiryJob.graceOf`), timeline `BANK_TRANSFER_NOTIFIED`. A second
 *   notice is a no-op that answers 200.
 * - [decide]: `APPROVE` is `Succeeded(paid = attempt.amount)` with actor `ADMIN` (O2, or O9 when the order was released: the money arrived late),
 *   `REJECT` is `Failed(final)` with failure code `REJECTED` (the order stays `PENDING` until `expiresAt`, so the buyer can pay another way; O8 once the window is over).
 *
 * Each call is one transaction under `Locks.forOrder`, the state is read again under the lock, so two admins (or an admin and the expiry job) cannot both win.
 */
class BankTransferService(
    private val db: MarketDb,
    private val locks: Locks,
    private val clock: Clock,
    private val orders: MarketOrderDao,
    private val payments: MarketPaymentDao,
    private val events: MarketOrderEventDao,
    private val paymentService: PaymentService,
    private val cashback: () -> Boolean,
    /** `requireBuyerNotice` of the bank transfer settings: the admin may approve only after the buyer's notice. */
    private val requireNotice: suspend () -> Boolean = { false },
    private val readClient: suspend () -> io.vertx.sqlclient.SqlClient
) {
    /**
     * The buyer's notice (04 section 3). The caller has resolved the owner. 409 `ORDER_NOT_PAYABLE` unless the order is `PENDING` inside its window and its
     * newest attempt is a `bank-transfer` one in `PENDING` (or already `PROCESSING`, which answers without changing anything).
     */
    suspend fun notify(orderId: Long, senderName: String?, note: String?, buyerUserId: Long?): Boolean {
        val after = ArrayList<AfterCommit>()
        val changed = db.txRestartingOnOrderChange { conn ->
            after.clear()

            locks.forOrder(conn, orderId, OrderLockScope.PAYMENT) { locked ->
                locks.children(conn, orderId, com.panomc.plugins.market.db.tx.OrderChild.PAYMENT)

                val order = orders.getById(orderId, conn) ?: throw NotFound()
                val newest = payments.getByOrderId(orderId, conn).lastOrNull()

                if (order.status != OrderStatus.PENDING || newest == null || newest.providerId != OrderTimings.BANK_TRANSFER_PROVIDER) throw OrderNotPayable()

                if (newest.status == PaymentStatus.PROCESSING) return@forOrder false

                if (newest.status != PaymentStatus.PENDING) throw OrderNotPayable()

                // the window is over (the expiry job has not run yet): the notice cannot save the order, the admin can still decide
                if (order.expiresAt != null && clock.now() >= order.expiresAt) throw OrderNotPayable()

                val applied = paymentService.applyIn(conn, locked, newest.id, PaymentAttemptEvent.Pending, AttemptFacts.NONE, ProviderMoneyPolicy(), OrderActor.BUYER, after)

                if (!applied.changed) return@forOrder false

                val data = JsonObject().put("senderName", clean(senderName)).put("note", clean(note))
                val now = clock.now()

                events.add(
                    MarketOrderEvent(
                        orderId = orderId, type = OrderEventType.BANK_TRANSFER_NOTIFIED, actorType = OrderActorType.BUYER, actorUserId = buyerUserId,
                        data = data.encode(), createdAt = now, updatedAt = now
                    ),
                    conn
                )

                true
            }
        }

        if (after.isNotEmpty()) paymentService.runAfterCommit(after, readClient())

        return changed
    }

    /**
     * The admin's decision (04 section 7, 06 section 14.1 step 3). 404 when the order has no `bank-transfer` attempt, 409 `ORDER_NOT_PAYABLE` for a state the
     * decision does not apply to: `APPROVE` needs a `PENDING` order with a `PENDING` / `PROCESSING` attempt (a notice first when `requireBuyerNotice` is on), or
     * an `EXPIRED` / `CANCELLED` / `FAILED` order whose money arrived late (O9: the order goes to `REVIEW`, accepted through the review endpoint); `REJECT` needs a
     * `PENDING` order with a `PENDING` / `PROCESSING` attempt.
     */
    suspend fun decide(orderId: Long, decision: BankTransferDecision, note: String?, adminUserId: Long): BankTransferOutcome {
        val after = ArrayList<AfterCommit>()
        val message = clean(note)
        val mustHaveNotice = decision == BankTransferDecision.APPROVE && requireNotice()
        val outcome = db.txRestartingOnOrderChange { conn ->
            after.clear()

            val scope = if (decision == BankTransferDecision.APPROVE) OrderLockScope.COMMIT else OrderLockScope.RELEASE

            locks.forOrder(conn, orderId, scope, cashback = decision == BankTransferDecision.APPROVE && cashback()) { locked ->
                locks.children(conn, orderId, com.panomc.plugins.market.db.tx.OrderChild.PAYMENT)

                val order = orders.getById(orderId, conn) ?: throw NotFound()
                val attempt = payments.getByOrderId(orderId, conn).lastOrNull { it.providerId == OrderTimings.BANK_TRANSFER_PROVIDER } ?: throw NotFound()
                val open = attempt.status == PaymentStatus.PENDING || attempt.status == PaymentStatus.PROCESSING

                val event: PaymentAttemptEvent
                val facts: AttemptFacts

                when (decision) {
                    BankTransferDecision.APPROVE -> {
                        val usable = when (order.status) {
                            OrderStatus.PENDING -> open
                            OrderStatus.EXPIRED, OrderStatus.CANCELLED, OrderStatus.FAILED -> attempt.status != PaymentStatus.SUCCEEDED
                            else -> false
                        }

                        if (!usable) throw OrderNotPayable()

                        if (mustHaveNotice && order.status == OrderStatus.PENDING && attempt.status != PaymentStatus.PROCESSING) throw OrderNotPayable()

                        event = PaymentAttemptEvent.Succeeded(attempt.amount, attempt.currency, null)
                        facts = AttemptFacts(adminMessage = message)
                    }

                    BankTransferDecision.REJECT -> {
                        if (order.status != OrderStatus.PENDING || !open) throw OrderNotPayable()

                        event = PaymentAttemptEvent.Failed(final = true)
                        facts = AttemptFacts(failureCode = REJECTED, adminMessage = message)
                    }
                }

                val applied = paymentService.applyIn(conn, locked, attempt.id, event, facts, ProviderMoneyPolicy(), OrderActor.ADMIN, after)

                // an event the attempt machine ignored is a state that changed under the lock: nothing happened, so nothing is claimed
                if (!applied.changed) throw OrderNotPayable()

                val now = clock.now()

                if (message != null) {
                    events.add(
                        MarketOrderEvent(
                            orderId = orderId, type = OrderEventType.NOTE, actorType = OrderActorType.ADMIN, actorUserId = adminUserId, message = message,
                            data = JsonObject().put("bankTransfer", decision.name).put("paymentId", attempt.id).encode(), createdAt = now, updatedAt = now
                        ),
                        conn
                    )
                }

                val fresh = orders.getById(orderId, conn)!!

                BankTransferOutcome(fresh, payments.getById(attempt.id, conn)!!, review = fresh.status == OrderStatus.REVIEW)
            }
        }

        if (after.isNotEmpty()) paymentService.runAfterCommit(after, readClient())

        return outcome
    }

    companion object {
        const val REJECTED = "REJECTED"
        const val FIELD_MAX = 255

        /** 04 section 3: at most 255 characters, control characters removed, blank = absent. */
        fun clean(raw: String?): String? =
            raw?.filter { !it.isISOControl() }?.trim()?.takeIf { it.isNotEmpty() }?.let { if (it.length > FIELD_MAX) it.substring(0, FIELD_MAX).trimEnd() else it }
    }
}
