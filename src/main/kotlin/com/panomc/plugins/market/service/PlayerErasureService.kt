package com.panomc.plugins.market.service

import com.panomc.platform.error.NotFound
import com.panomc.plugins.market.core.order.OrderEvent
import com.panomc.plugins.market.core.order.OrderActor
import com.panomc.plugins.market.core.time.Clock
import com.panomc.plugins.market.db.tx.Locks
import com.panomc.plugins.market.db.tx.MarketDb
import com.panomc.plugins.market.db.tx.OrderChild
import com.panomc.plugins.market.db.tx.OrderLockScope
import com.panomc.plugins.market.db.tx.txRestartingOnOrderChange
import com.panomc.plugins.market.error.InvalidState
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * What [PlayerErasureService.erase] did: the [failed] steps (their names; the rest ran), and whether the closing of the credit account and the `userId`
 * of orders that still hold credits is [deferred] until those orders settle (the next run, by `HousekeepingJob`, completes it).
 */
class ErasureReport(val userId: Long, val failed: List<String>, val deferred: Boolean) {
    /** `true` when every step ran and nothing waits: the user's PII is gone. */
    val complete: Boolean get() = failed.isEmpty() && !deferred
}

/**
 * PII on player deletion (11 section 16) and the panel action "anonymise this order" (04 section 7, `POST /orders/:id/anonymize`).
 *
 * `PlayerEventHandler.onDelete` calls [erase]. It never throws (a failure would abort the platform's delete): every step of the table of 11 section 16
 * runs on its own, in its own transaction, and a failing step is logged and recorded in the [ErasureReport]; the remaining steps still run. Every step is
 * idempotent, so [erase] can run any number of times. The orders of the user are found by `userId = ? OR buyerKey = 'u:<id>'` (the buyer key is kept
 * by design, 11 section 16 step 5), so a second run still finds the orders whose `userId` the first one blanked.
 *
 * Decisions beyond the table:
 * - The run registers `erasure-pending:<userId>` in `market_sequence` and removes it only when every step ran and nothing waits; `HousekeepingJob` runs
 *   [erase] again for the markers that are left (a step that failed once is retried, an erasure that had to wait is finished).
 * - An order that still holds credits (`reservationState = HELD`, `creditAmount > 0`, e.g. an attempt in `PROCESSING`) keeps its `userId`, and the credit
 *   account keeps its `userId`, until the order settles: the release posts to the account of `order.userId`, and blanking it first would leave the credits
 *   on hold forever or mint a second account. Everything else of the order is blanked at once. The remaining credits of the account are revoked at once
 *   (spendable balance only; held credits go back to the account when the order settles and are revoked by the next run).
 * - Credits: the key of the closing `REVOKE` is `user-delete:<id>` (07 section 15 catalogue; 11 section 16 says `erase:<id>`), a later balance uses
 *   `user-delete:<id>:sweep:<lastEntryId>` (07 section 14.4).
 * - The marker of an erased order is its `PII_ERASED` timeline event: it drives the deferred blanking of `HousekeepingJob` (shipping address, shipment
 *   addresses, webhook bodies, payment event bodies) once the order is finished.
 * - [labelsDir]: label files of the shipments whose address is blanked are deleted (01 section 13).
 */
class PlayerErasureService(
    private val clock: Clock,
    private val db: MarketDb,
    private val locks: Locks,
    private val orderService: OrderService,
    private val credits: CreditService,
    private val prefix: () -> String,
    private val client: suspend () -> SqlClient,
    /** Ends the subscriptions of the user and clears their personal data (`SubscriptionService.onUserDeleted`). */
    private val endSubscriptions: suspend (userId: Long) -> Unit,
    private val afterCommit: suspend (List<AfterCommit>) -> Unit,
    private val labelsDir: Path? = null
) {
    private fun t(name: String) = "`${prefix()}$name`"

    /** The set of orders a statement works on: the SQL condition on `market_order` and its arguments. */
    private class Scope(private val condition: (alias: String) -> String, val args: List<Any>) {
        /** The condition on `market_order`, its columns prefixed with [alias] (`"o."`) when the statement names the table. */
        fun where(alias: String = "") = condition(alias)

        fun orderIds(table: String) = "SELECT `id` FROM $table WHERE ${where()}"
    }

    private fun userScope(userId: Long) = Scope({ a -> "(${a}`userId` = ? OR ${a}`buyerKey` = ?)" }, listOf(userId, "u:$userId"))

    private suspend fun exec(c: SqlClient, statement: String, args: List<Any?> = emptyList()): Int =
        c.preparedQuery(statement).execute(if (args.isEmpty()) Tuple.tuple() else Tuple.from(args)).coAwait().rowCount()

    private suspend fun rows(c: SqlClient, statement: String, args: List<Any?> = emptyList()) =
        c.preparedQuery(statement).execute(if (args.isEmpty()) Tuple.tuple() else Tuple.from(args)).coAwait().toList()

    // =============================================================================================== erase a user

    /** Steps 1 to 20 of 11 section 16 (step 19, the invoices, is kept as it is, step 21 is a reference only). Never throws except for cancellation. */
    suspend fun erase(userId: Long): ErasureReport {
        val failed = ArrayList<String>()
        val scope = userScope(userId)

        suspend fun step(name: String, block: suspend () -> Unit) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                failed += name
                logger.error("erasure of user {}: step {} failed, the other steps go on: {}", userId, name, t.toString())
            }
        }

        step("marker") { marker(userId) }
        step("cancel-orders") { cancelPendingOrders(userId) }
        step("subscriptions") { subscriptions(userId) }

        // the orders that still hold credits are known only after step 1 ran
        var deferred = false

        step("hold-check") { deferred = holdsCredits(userId) }
        step("credits") { closeCredits(userId, deferred) }
        step("cart-address") { db.tx { c -> cartAndAddresses(c, userId) } }
        step("orders") { db.tx { c -> orders(c, scope) } }
        step("recipient") { db.tx { c -> exec(c, "UPDATE ${t("market_order")} SET `recipientUserId` = NULL WHERE `recipientUserId` = ?", listOf(userId)) } }
        step("order-items") { db.tx { c -> orderItems(c, scope) } }
        step("payments") { db.tx { c -> payments(c, scope) } }
        step("payment-events") { db.tx { c -> paymentEvents(c, scope) } }
        step("redemptions") {
            db.tx { c -> exec(c, "UPDATE ${t("market_redemption")} SET `userId` = NULL, `email` = NULL WHERE `userId` = ?", listOf(userId)) }
        }
        step("entitlements") { db.tx { c -> exec(c, "UPDATE ${t("market_entitlement")} SET `userId` = NULL WHERE `userId` = ?", listOf(userId)) } }
        step("mail-outbox") { mailOutbox(userId) }
        step("shipments") { shipments(scope) }
        step("webhook-deliveries") { db.tx { c -> webhookDeliveries(c, scope) } }
        step("creator") { db.tx { c -> creator(c, userId) } }
        step("provider-state") { db.tx { c -> exec(c, "DELETE FROM ${t("market_provider_state")} WHERE `stateKey` LIKE ?", listOf("user:$userId:%")) } }
        step("blocks") { db.tx { c -> exec(c, "DELETE FROM ${t("market_block")} WHERE `type` = 'USER' AND `value` = ?", listOf(userId.toString())) } }
        step("throttle") { db.tx { c -> exec(c, "DELETE FROM ${t("market_throttle")} WHERE `subject` = ?", listOf("b:u:$userId")) } }
        step("events") { db.tx { c -> erasedEvents(c, scope) } }

        if (failed.isEmpty() && !deferred) step("unmark") { db.tx { c -> exec(c, "DELETE FROM ${t("market_sequence")} WHERE `name` = ?", listOf(markerName(userId))) } }

        return ErasureReport(userId, failed, deferred)
    }

    /** `erasure-pending:<userId>` in `market_sequence`: the user was deleted and something may still be left to do. */
    private suspend fun marker(userId: Long) {
        val now = clock.now()

        db.tx { c ->
            exec(
                c, "INSERT INTO ${t("market_sequence")} (`name`, `value`, `createdAt`, `updatedAt`) VALUES (?, ?, ?, ?) ON DUPLICATE KEY UPDATE `updatedAt` = VALUES(`updatedAt`)",
                listOf(markerName(userId), userId, now, now)
            )
        }
    }

    /** The user ids that `erase` has not finished (the markers), oldest first. */
    suspend fun pending(limit: Int): List<Long> =
        rows(client(), "SELECT `value` FROM ${t("market_sequence")} WHERE `name` LIKE ? ORDER BY `id` LIMIT $limit", listOf("$MARKER_PREFIX%")).map { it.getLong("value") }

    // ---- 1: orders that never got paid are cancelled through the state machine (O7)

    private suspend fun cancelPendingOrders(userId: Long) {
        // the user scope (not `userId` alone): a retry still finds an order whose `userId` the `orders` step has blanked in the meantime
        val ids = rows(
            client(), "SELECT `id` FROM ${t("market_order")} WHERE (`userId` = ? OR `buyerKey` = ?) AND `status` = 'PENDING' ORDER BY `id`", listOf(userId, "u:$userId")
        ).map { it.getLong("id") }
        var firstError: Throwable? = null

        for (id in ids) {
            val after = ArrayList<AfterCommit>()

            try {
                db.txRestartingOnOrderChange { conn ->
                    after.clear()

                    locks.forOrder(conn, id, OrderLockScope.RELEASE) { locked ->
                        locks.children(conn, id, OrderChild.PAYMENT)

                        val busy = rows(conn, "SELECT 1 FROM ${t("market_payment")} WHERE `orderId` = ? AND `status` = 'PROCESSING' LIMIT 1", listOf(id)).isNotEmpty()

                        if (!busy) after += orderService.transition(conn, locked, OrderEvent.Cancel(OrderActor.SYSTEM), message = REASON_ACCOUNT_DELETED).after
                    }
                }

                afterCommit(after)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                // one order that cannot be cancelled (a provider failure after the commit, a restart limit) does not keep the others from being erased
                logger.warn("erasure of user {}: order {} could not be cancelled: {}", userId, id, t.toString())

                firstError = firstError ?: t
            }
        }

        // the step is still reported as failed (the marker stays, the housekeeping job tries again), but only after every order had its turn
        firstError?.let { throw it }
    }

    // ---- 2

    private suspend fun subscriptions(userId: Long) {
        val ids = rows(client(), "SELECT `id` FROM ${t("market_subscription")} WHERE `userId` = ?", listOf(userId)).map { it.getLong("id") }

        endSubscriptions(userId)

        if (ids.isEmpty()) return

        db.tx { c ->
            val marks = ids.joinToString(",") { "?" }

            // a gateway cancel that is still queued needs the customer and the provider data: HousekeepingJob clears them when it is done
            exec(
                c,
                "UPDATE ${t("market_subscription")} SET `userId` = NULL, `email` = NULL, `storedMethod` = NULL, `storedMethodLabel` = NULL, `fieldValues` = NULL, " +
                    "`gatewayCustomerId` = CASE WHEN `remoteCancelState` = 'PENDING' THEN `gatewayCustomerId` ELSE NULL END, " +
                    "`providerData` = CASE WHEN `remoteCancelState` = 'PENDING' THEN `providerData` ELSE NULL END WHERE `id` IN ($marks)",
                ids
            )
        }
    }

    // ---- 3: the credit account

    /** `true` when an order of the user still holds credits (the account and the order keep their `userId` until it settles). */
    private suspend fun holdsCredits(userId: Long): Boolean =
        rows(
            client(), "SELECT 1 FROM ${t("market_order")} WHERE `userId` = ? AND `reservationState` = 'HELD' AND `creditAmount` > 0 LIMIT 1", listOf(userId)
        ).isNotEmpty()

    private suspend fun closeCredits(userId: Long, pending: Boolean) {
        db.tx { c ->
            val account = rows(c, "SELECT `id` FROM ${t("market_credit_account")} WHERE `type` = 'USER' AND `userId` = ?", listOf(userId)).firstOrNull()?.getLong("id")

            if (account != null) {
                credits.lockAccounts(listOf(userId), true, c)

                val balance = credits.balance(userId, c)

                if (balance > 0) {
                    val first = "user-delete:$userId"
                    val used = rows(c, "SELECT 1 FROM ${t("market_credit_tx")} WHERE `idempotencyKey` = ?", listOf(first)).isNotEmpty()
                    val key = if (!used) first else {
                        val last = rows(c, "SELECT COALESCE(MAX(`id`), 0) AS m FROM ${t("market_credit_entry")} WHERE `accountId` = ?", listOf(account)).first().getLong("m")

                        "$first:sweep:$last"
                    }

                    credits.revoke(userId, balance, key, null, REASON_ACCOUNT_DELETED, c)
                }

                if (!pending) {
                    exec(c, "UPDATE ${t("market_credit_account")} SET `userId` = NULL WHERE `id` = ? AND `userId` = ?", listOf(account, userId))
                    exec(c, "UPDATE ${t("market_credit_tx")} SET `userId` = NULL WHERE `userId` = ?", listOf(userId))
                }
            }

            // the admin who granted or took credits is an id too; the ledger rows keep their amounts
            if (!pending) exec(c, "UPDATE ${t("market_credit_tx")} SET `actorUserId` = NULL WHERE `actorUserId` = ?", listOf(userId))
        }
    }

    // ---- 4

    private suspend fun cartAndAddresses(c: SqlClient, userId: Long) {
        exec(c, "DELETE FROM ${t("market_cart_item")} WHERE `cartId` IN (SELECT `id` FROM ${t("market_cart")} WHERE `userId` = ?)", listOf(userId))
        exec(c, "DELETE FROM ${t("market_cart")} WHERE `userId` = ?", listOf(userId))
        exec(c, "DELETE FROM ${t("market_address")} WHERE `userId` = ?", listOf(userId))
    }

    // ---- 5 and its children (also the order-level action)

    /**
     * Step 5: personal columns of the orders in [scope]. `shippingAddress` goes with the rule of 11 section 16 (nothing left to ship, or the order is over),
     * `userId` unless the order still holds credits.
     */
    private suspend fun orders(c: SqlClient, scope: Scope) {
        exec(
            c,
            "UPDATE ${t("market_order")} SET `email` = NULL, `clientIp` = NULL, `userAgent` = NULL, `billingInfo` = NULL, `giftMessage` = NULL, `accessToken` = NULL, " +
                "`shippingAddress` = CASE WHEN `shippingStatus` IN ('NOT_REQUIRED', 'DELIVERED', 'RETURNED') OR `status` IN ('REFUNDED', 'CANCELLED', 'EXPIRED', 'FAILED') " +
                "THEN NULL ELSE `shippingAddress` END, " +
                "`userId` = CASE WHEN `reservationState` = 'HELD' AND `creditAmount` > 0 THEN `userId` ELSE NULL END WHERE ${scope.where()}",
            scope.args
        )
    }

    private suspend fun orderItems(c: SqlClient, scope: Scope) {
        exec(c, "UPDATE ${t("market_order_item")} SET `fieldValues` = NULL WHERE `orderId` IN (${scope.orderIds(t("market_order"))})", scope.args)
    }

    private suspend fun payments(c: SqlClient, scope: Scope) {
        exec(c, "UPDATE ${t("market_payment")} SET `clientIp` = NULL, `userAgent` = NULL WHERE `orderId` IN (${scope.orderIds(t("market_order"))})", scope.args)
    }

    private suspend fun paymentEvents(c: SqlClient, scope: Scope) {
        exec(
            c,
            "UPDATE ${t("market_payment_event")} SET `body` = NULL, `headers` = NULL WHERE `orderId` IN (${scope.orderIds(t("market_order"))}) " +
                "AND `status` NOT IN ('RECEIVED', 'DEFERRED', 'FAILED')",
            scope.args
        )
    }

    /** Step 13: the address of a shipment that is over; the label files of those shipments are deleted after the commit. */
    private suspend fun shipments(scope: Scope) {
        val blanked = db.tx { c ->
            val ids = rows(
                c,
                "SELECT `id` FROM ${t("market_shipment")} WHERE `orderId` IN (${scope.orderIds(t("market_order"))}) AND `status` IN ('DELIVERED', 'RETURNED', 'CANCELLED', 'LOST') " +
                    "AND (`toAddress` <> '{}' OR `labelFile` IS NOT NULL)",
                scope.args
            ).map { it.getLong("id") }

            if (ids.isNotEmpty()) {
                exec(c, "UPDATE ${t("market_shipment")} SET $BLANK_SHIPMENT WHERE `id` IN (${ids.joinToString(",") { "?" }})", ids)
            }

            ids
        }

        deleteLabels(blanked)
    }

    /** `<labelsDir>/<shipmentId>-*`: the label and the documents of a shipment whose address was blanked (01 section 13). */
    internal fun deleteLabels(shipmentIds: Collection<Long>) {
        val dir = labelsDir ?: return

        if (shipmentIds.isEmpty() || !Files.isDirectory(dir)) return

        for (id in shipmentIds) {
            try {
                Files.newDirectoryStream(dir, "$id-*").use { found -> found.forEach { Files.deleteIfExists(it) } }
            } catch (e: Exception) {
                logger.warn("the label files of shipment {} could not be deleted: {}", id, e.toString())
            }
        }
    }

    private suspend fun webhookDeliveries(c: SqlClient, scope: Scope) {
        exec(
            c, "UPDATE ${t("market_webhook_delivery")} SET `body` = '{}' WHERE `orderId` IN (${scope.orderIds(t("market_order"))}) AND `status` IN ('SUCCEEDED', 'DEAD') AND `body` <> '{}'",
            scope.args
        )
    }

    /** Step 20: one `PII_ERASED` event per affected order (once). */
    private suspend fun erasedEvents(c: SqlClient, scope: Scope) {
        val now = clock.now()

        exec(
            c,
            "INSERT INTO ${t("market_order_event")} (`orderId`, `type`, `actorType`, `createdAt`, `updatedAt`) " +
                "SELECT o.`id`, 'PII_ERASED', 'SYSTEM', ?, ? FROM ${t("market_order")} o WHERE ${scope.where("o.")} " +
                "AND NOT EXISTS (SELECT 1 FROM ${t("market_order_event")} e WHERE e.`orderId` = o.`id` AND e.`type` = 'PII_ERASED')",
            listOf(now, now) + scope.args
        )
    }

    // ---- 12

    private suspend fun mailOutbox(userId: Long) {
        val whole = "UPDATE ${t("market_mail_outbox")} SET `status` = CASE WHEN `status` IN ('PENDING', 'FAILED') THEN 'SKIPPED' ELSE `status` END, `recipient` = '', `params` = '{}' WHERE `userId` = ?"

        try {
            db.tx { c -> exec(c, whole, listOf(userId)) }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // two rows that differ only in their recipient would meet in the unique key: each gets a placeholder of its own
            logger.info("erasure of user {}: the mail rows are blanked one by one ({})", userId, t.toString())

            db.tx { c ->
                exec(
                    c,
                    "UPDATE ${t("market_mail_outbox")} SET `status` = CASE WHEN `status` IN ('PENDING', 'FAILED') THEN 'SKIPPED' ELSE `status` END, " +
                        "`recipient` = CONCAT('erased-', `id`), `params` = '{}' WHERE `userId` = ?",
                    listOf(userId)
                )
            }
        }
    }

    // ---- 15

    private suspend fun creator(c: SqlClient, userId: Long) {
        for (table in listOf("market_creator_code", "market_creator_earning", "market_creator_payout")) {
            exec(c, "UPDATE ${t(table)} SET `creatorUserId` = NULL WHERE `creatorUserId` = ?", listOf(userId))
        }
    }

    // =============================================================================================== one order

    /**
     * `POST /orders/:id/anonymize` (11 section 16, last paragraph): the order-level steps (5, 7, 8, 9, 13, 14, 20) for one order, in one transaction. An
     * order that is still open (`PENDING`, `REVIEW`) is refused with [InvalidState] (its mails and its payment still need the e-mail address); an unknown
     * id is [NotFound]. Idempotent. Returns `true` when the order changed.
     */
    suspend fun anonymizeOrder(orderId: Long): Boolean {
        val scope = Scope({ a -> "${a}`id` = ?" }, listOf(orderId))
        val labels = ArrayList<Long>()

        val changed = db.tx { c ->
            labels.clear()

            val order = rows(
                c, "SELECT `status`, `email`, `userId`, `clientIp`, `userAgent`, `billingInfo`, `giftMessage`, `accessToken`, `shippingAddress` FROM ${t("market_order")} WHERE `id` = ? FOR UPDATE",
                listOf(orderId)
            ).firstOrNull() ?: throw NotFound()
            val status = order.getString("status")

            if (status == "PENDING" || status == "REVIEW") throw InvalidState(status)

            val before = listOf("email", "userId", "clientIp", "userAgent", "billingInfo", "giftMessage", "accessToken").any { order.getValue(it) != null }

            orders(c, scope)
            orderItems(c, scope)
            payments(c, scope)
            paymentEvents(c, scope)

            labels += rows(
                c,
                "SELECT `id` FROM ${t("market_shipment")} WHERE `orderId` = ? AND `status` IN ('DELIVERED', 'RETURNED', 'CANCELLED', 'LOST') AND (`toAddress` <> '{}' OR `labelFile` IS NOT NULL)",
                listOf(orderId)
            ).map { it.getLong("id") }

            if (labels.isNotEmpty()) exec(c, "UPDATE ${t("market_shipment")} SET $BLANK_SHIPMENT WHERE `id` IN (${labels.joinToString(",") { "?" }})", labels.toList())

            // the e-mail address of a guest order is also in the mail rows and the redemptions that are keyed by the order (the user-keyed steps 10 and 12 never reach them)
            exec(c, "UPDATE ${t("market_redemption")} SET `email` = NULL WHERE `orderId` = ?", listOf(orderId))
            exec(
                c,
                "UPDATE ${t("market_mail_outbox")} SET `status` = CASE WHEN `status` IN ('PENDING', 'FAILED') THEN 'SKIPPED' ELSE `status` END, `recipient` = CONCAT('erased-', `id`), `params` = '{}' WHERE `orderId` = ?",
                listOf(orderId)
            )

            webhookDeliveries(c, scope)

            val firstTime = rows(c, "SELECT 1 FROM ${t("market_order_event")} WHERE `orderId` = ? AND `type` = 'PII_ERASED' LIMIT 1", listOf(orderId)).isEmpty()

            erasedEvents(c, scope)

            before || firstTime
        }

        deleteLabels(labels)

        return changed
    }

    companion object {
        private val logger = LoggerFactory.getLogger(PlayerErasureService::class.java)

        /** The `message` of the cancelling transition and the `note` of the closing `REVOKE`. */
        const val REASON_ACCOUNT_DELETED = "ACCOUNT_DELETED"

        const val MARKER_PREFIX = "erasure-pending:"

        /**
         * `SET` clause of a finished shipment whose address was blanked: the pointers to the label files go with the address (the files are deleted), so a
         * handled row no longer matches the selection `toAddress <> '{}' OR labelFile IS NOT NULL` and the deferred job terminates.
         */
        const val BLANK_SHIPMENT = "`toAddress` = '{}', `labelFile` = NULL, `labelFormat` = NULL, `documents` = NULL"

        fun markerName(userId: Long) = "$MARKER_PREFIX$userId"
    }
}
