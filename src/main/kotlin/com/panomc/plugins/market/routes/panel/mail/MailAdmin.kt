package com.panomc.plugins.market.routes.panel.mail

import com.panomc.platform.error.NotFound
import com.panomc.plugins.market.config.MarketConfig
import com.panomc.plugins.market.core.abuse.PiiMask
import com.panomc.plugins.market.core.time.Clock
import com.panomc.plugins.market.db.dao.MarketEntitlementDao
import com.panomc.plugins.market.db.dao.MarketMailOutboxDao
import com.panomc.plugins.market.db.dao.MarketOrderDao
import com.panomc.plugins.market.db.dao.MarketOrderItemDao
import com.panomc.plugins.market.db.dao.MarketRefundDao
import com.panomc.plugins.market.db.dao.MarketShipmentDao
import com.panomc.plugins.market.db.dao.MarketSubscriptionDao
import com.panomc.plugins.market.db.model.EntitlementStatus
import com.panomc.plugins.market.db.model.MailKind
import com.panomc.plugins.market.db.model.MailRefType
import com.panomc.plugins.market.db.model.MailStatus
import com.panomc.plugins.market.db.model.MarketMailOutbox
import com.panomc.plugins.market.db.model.MarketOrder
import com.panomc.plugins.market.db.model.RefundStatus
import com.panomc.plugins.market.db.model.ShipmentStatus
import com.panomc.plugins.market.db.model.SubscriptionStatus
import com.panomc.plugins.market.db.tx.MarketDb
import com.panomc.plugins.market.error.InvalidMailKind
import com.panomc.plugins.market.error.InvalidState
import com.panomc.plugins.market.error.MailDisabled
import com.panomc.plugins.market.error.MailNotApplicable
import com.panomc.plugins.market.error.MailRecipientRequired
import com.panomc.plugins.market.error.MailSendFailed
import com.panomc.plugins.market.error.RequestValueException
import com.panomc.plugins.market.job.MailOutboxJob
import com.panomc.plugins.market.mail.MailContentBuilder
import com.panomc.plugins.market.mail.MailGateway
import com.panomc.plugins.market.mail.MailSendResult
import com.panomc.plugins.market.mail.OutboundMail
import com.panomc.plugins.market.service.MailOutboxService
import com.panomc.plugins.market.service.platform.UserDirectory
import com.panomc.plugins.market.util.Paging
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout

/** One page of `GET /mails`: the wire rows (recipients masked unless the caller may see them) and the total. */
class MailPage(val rows: List<JsonObject>, val total: Long)

/**
 * What the mail panel routes do (MK-146; 04 sections 7 and 8, 12 sections 4.5 and 10), as a service so the rules run in a database test without the host:
 * the resend of one mail of an order, the retry of a `FAILED` / `SKIPPED` row (sent right away), the masked list and the test mail. The routes only parse,
 * call this and write the activity log. Nothing here sends through the SMTP path except [retry] (via the job) and [sendTest] (via the gateway).
 */
class MailAdmin(
    private val db: MarketDb,
    private val config: () -> MarketConfig,
    private val clock: Clock,
    private val orders: MarketOrderDao,
    private val orderItems: MarketOrderItemDao,
    private val mailOutbox: MarketMailOutboxDao,
    private val outbox: MailOutboxService,
    private val job: MailOutboxJob,
    private val refunds: MarketRefundDao,
    private val shipments: MarketShipmentDao,
    private val subscriptions: MarketSubscriptionDao,
    private val entitlements: MarketEntitlementDao,
    private val users: UserDirectory,
    private val builder: MailContentBuilder,
    private val gateway: MailGateway,
    private val mailEnabled: () -> Boolean,
    private val mailOptionsAvailable: () -> Boolean,
    private val defaultLocale: () -> String
) {
    /** What a resend queues: the reference the unique row is keyed on, the params that cannot be re-read, and the default recipient. */
    private class Target(val refType: MailRefType, val refId: Long, val refKey: String, val params: JsonObject, val recipient: String?, val userId: Long?)

    private fun table() = "`${mailOutbox.prefix()}market_mail_outbox`"

    // ----- resend (12 section 4.5) --------------------------------------------------------------------------------------

    /**
     * `POST /orders/:id/mails/resend`: queues (or resets) the mail of [rawKind] for the order and returns its outbox id.
     * 400 `INVALID_MAIL_KIND`, 400 `BAD_REQUEST` for a malformed [rawRecipient], 400 `MAIL_RECIPIENT_REQUIRED` when there is no address at all,
     * 409 `MAIL_DISABLED` (platform switch), 409 `MAIL_NOT_APPLICABLE` (the reference is missing or does not fit the state, or the row is being sent), 404 for an unknown order.
     * The kind switches and `sendEmailAfterPurchase` are bypassed (`force`), the platform switch is not.
     */
    suspend fun resend(orderId: Long, rawKind: String?, rawRecipient: String?, refId: Long?, client: SqlClient): Long {
        val kind = parseKind(rawKind) ?: throw InvalidMailKind()

        if (!mailEnabled() || !mailOptionsAvailable()) throw MailDisabled()

        val order = orders.getById(orderId, client) ?: throw NotFound()
        val explicit = parseRecipient(rawRecipient)
        val target = resolve(kind, order, refId, client)
        val recipient = explicit ?: target.recipient?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: throw MailRecipientRequired()

        if (!RECIPIENT.matches(recipient)) throw MailRecipientRequired()

        val locale = order.locale?.takeIf { it.isNotBlank() } ?: defaultLocale()

        return db.tx { conn ->
            outbox.enqueue(
                conn, kind, target.refType, target.refId, target.refKey, order.id, target.userId ?: order.userId, recipient, locale, target.params, force = true
            )
        } ?: throw MailNotApplicable()
    }

    private suspend fun resolve(kind: MailKind, order: MarketOrder, refId: Long?, c: SqlClient): Target {
        val payer = payerEmail(order, c)

        return when (kind) {
            MailKind.ORDER_CONFIRMATION, MailKind.ORDER_DELIVERED -> {
                requirePaid(order)

                Target(MailRefType.ORDER, order.id, "", JsonObject(), payer, order.userId)
            }
            MailKind.GIFT_RECEIVED -> {
                requirePaid(order)

                if (!order.isGift) throw MailNotApplicable()

                val recipientId = order.recipientUserId

                Target(MailRefType.ORDER, order.id, "", JsonObject(), recipientId?.let { users.emailOf(it, c) }, recipientId)
            }
            MailKind.ORDER_RECEIVED -> {
                requireOpen(order)

                Target(MailRefType.ORDER, order.id, "", JsonObject(), payer, order.userId)
            }
            MailKind.BANK_TRANSFER_INSTRUCTIONS -> {
                requireOpen(order)

                // the instructions were cleared from the attempt when it closed: only a row that was queued once still carries them
                val row = mailOutbox.getByOrderId(order.id, c).filter { it.kind == kind && it.refType == MailRefType.PAYMENT && (refId == null || it.refId == refId) }
                    .maxByOrNull { it.id } ?: throw MailNotApplicable()

                Target(MailRefType.PAYMENT, row.refId, row.refKey, paramsOf(row), payer, order.userId)
            }
            MailKind.ORDER_REFUNDED -> {
                val refund = refunds.getByOrderId(order.id, c).filter { it.status == RefundStatus.SUCCEEDED && (refId == null || it.id == refId) }.maxByOrNull { it.id }
                    ?: throw MailNotApplicable()

                Target(MailRefType.REFUND, refund.id, "", JsonObject(), payer, order.userId)
            }
            MailKind.SHIPMENT_SHIPPED, MailKind.SHIPMENT_DELIVERED -> {
                val shipment = shipments.getByOrderId(order.id, c).filter { s ->
                    (refId == null || s.id == refId) && when (kind) {
                        MailKind.SHIPMENT_DELIVERED -> s.status == ShipmentStatus.DELIVERED
                        else -> s.status != ShipmentStatus.CREATED && s.status != ShipmentStatus.CANCELLED
                    }
                }.maxByOrNull { it.id } ?: throw MailNotApplicable()

                Target(MailRefType.SHIPMENT, shipment.id, "", JsonObject(), payer, order.userId)
            }
            MailKind.EXPIRY_REMINDER -> {
                val now = clock.now()
                val found = orderItems.getByOrderIds(listOf(order.id), c).flatMap { entitlements.getByOrderItemId(it.id, c) }
                    .filter { it.status == EntitlementStatus.ACTIVE && (it.expiresAt ?: 0L) > now && (refId == null || it.id == refId) }
                    .maxByOrNull { it.id } ?: throw MailNotApplicable()
                val end = checkNotNull(found.expiresAt)

                Target(
                    MailRefType.ENTITLEMENT, found.id, end.toString(), JsonObject().put("expiresAt", end),
                    found.userId?.let { users.emailOf(it, c) } ?: payer, found.userId ?: order.userId
                )
            }
            MailKind.SUBSCRIPTION_REMINDER, MailKind.SUBSCRIPTION_PAYMENT_FAILED, MailKind.SUBSCRIPTION_CANCELLED, MailKind.SUBSCRIPTION_ENDED -> {
                val sub = (order.subscriptionId ?: throw MailNotApplicable()).let { subscriptions.getById(it, c) } ?: throw MailNotApplicable()
                val cycle = sub.cycleCount.coerceAtLeast(1).toString()
                val params = JsonObject()
                val key = when (kind) {
                    MailKind.SUBSCRIPTION_REMINDER -> {
                        if (sub.status != SubscriptionStatus.ACTIVE) throw MailNotApplicable()

                        params.put("periodEnd", sub.currentPeriodEnd ?: throw MailNotApplicable())
                        cycle
                    }
                    MailKind.SUBSCRIPTION_PAYMENT_FAILED -> {
                        if (sub.status != SubscriptionStatus.PAST_DUE) throw MailNotApplicable()

                        sub.graceEndsAt?.let { params.put("graceEndsAt", it) }
                        cycle
                    }
                    MailKind.SUBSCRIPTION_CANCELLED -> {
                        val requested = sub.cancelRequestedAt ?: throw MailNotApplicable()

                        sub.currentPeriodEnd?.let { params.put("accessUntil", it) }
                        requested.toString()
                    }
                    else -> {
                        if (sub.status != SubscriptionStatus.EXPIRED && sub.status != SubscriptionStatus.CANCELLED && sub.status != SubscriptionStatus.COMPLETED) throw MailNotApplicable()

                        sub.endReason?.let { params.put("endReason", it) }
                        ""
                    }
                }
                val owner = sub.email?.takeIf { it.isNotBlank() } ?: sub.userId?.let { users.emailOf(it, c) } ?: payer

                Target(MailRefType.SUBSCRIPTION, sub.id, key, params, owner, sub.userId ?: order.userId)
            }
        }
    }

    private fun requirePaid(order: MarketOrder) {
        if (order.paidAt == null) throw MailNotApplicable()
    }

    private fun requireOpen(order: MarketOrder) {
        if (order.status != com.panomc.plugins.market.util.OrderStatus.PENDING && order.status != com.panomc.plugins.market.util.OrderStatus.REVIEW) throw MailNotApplicable()
    }

    /** "Payer" recipient (12 section 4.1): the order e-mail, else the e-mail of the order's user. */
    private suspend fun payerEmail(order: MarketOrder, c: SqlClient): String? =
        order.email?.takeIf { it.isNotBlank() } ?: order.userId?.let { users.emailOf(it, c) }

    private fun paramsOf(row: MarketMailOutbox): JsonObject = runCatching { JsonObject(row.params.ifBlank { "{}" }) }.getOrDefault(JsonObject())

    // ----- list ---------------------------------------------------------------------------------------------------------

    /**
     * `GET /mails`: newest first; [status] / [kind] / [orderId] narrow it. Recipients are masked (`j***@e***.com`) unless [seeRecipients] (OM or PAY), and
     * the masked address is also removed from `lastError`, which a mail server may fill with the address it refused.
     */
    suspend fun list(status: MailStatus?, kind: MailKind?, orderId: Long?, window: Paging.Window, seeRecipients: Boolean, client: SqlClient): MailPage {
        val where = ArrayList<String>()
        val values = Tuple.tuple()

        if (status != null) { where += "`status` = ?"; values.addValue(status.name) }
        if (kind != null) { where += "`kind` = ?"; values.addValue(kind.name) }
        if (orderId != null) { where += "`orderId` = ?"; values.addValue(orderId) }

        val clause = if (where.isEmpty()) "" else " WHERE " + where.joinToString(" AND ")
        val total = client.preparedQuery("SELECT COUNT(*) AS c FROM ${table()}$clause").execute(values).coAwait().first().getLong("c")
        val page = Tuple.tuple().also { t -> (0 until values.size()).forEach { t.addValue(values.getValue(it)) } }.addValue(window.pageSize).addValue(window.offset)
        val rows = client.preparedQuery(
            "SELECT `id`, `kind`, `orderId`, `recipient`, `status`, `attempts`, `lastError`, `createdAt`, `sentAt` FROM ${table()}$clause ORDER BY `id` DESC LIMIT ? OFFSET ?"
        ).execute(page).coAwait().map { r ->
            val recipient = r.getString("recipient").orEmpty()
            val error = r.getString("lastError")

            JsonObject()
                .put("id", r.getLong("id"))
                .put("kind", r.getString("kind"))
                .put("orderId", r.getLong("orderId"))
                .put("recipient", if (seeRecipients) recipient else PiiMask.email(recipient))
                .put("status", r.getString("status"))
                .put("attempts", r.getInteger("attempts"))
                .put("lastError", if (seeRecipients || error == null) error else error.replace(recipient, PiiMask.email(recipient) ?: "***", ignoreCase = true))
                .put("createdAt", r.getLong("createdAt"))
                .put("sentAt", r.getLong("sentAt"))
        }

        return MailPage(rows, total)
    }

    // ----- retry --------------------------------------------------------------------------------------------------------

    /**
     * `POST /mails/:id/retry`: a `FAILED` or `SKIPPED` row goes back to `PENDING` (attempts 0, forced, so neither the relevance check nor the age limit
     * ends it) and is sent right away. 404 unknown row, 409 `INVALID_STATE {state}` for any other status, 409 `MAIL_DISABLED`, 502 `MAIL_SEND_FAILED` when the
     * send did not succeed (the row then keeps its error and, for a retryable failure, the job's backoff).
     */
    suspend fun retry(id: Long, client: SqlClient): MarketMailOutbox {
        val row = mailOutbox.getById(id, client) ?: throw NotFound()

        if (row.status != MailStatus.FAILED && row.status != MailStatus.SKIPPED) throw InvalidState(row.status.name)
        if (!mailEnabled() || !mailOptionsAvailable()) throw MailDisabled()

        val now = clock.now()
        val params = paramsOf(row).put("forced", true)
        val reset = client.preparedQuery(
            "UPDATE ${table()} SET `status` = 'PENDING', `attempts` = 0, `nextAttemptAt` = ?, `claimedUntil` = NULL, `lastError` = NULL, `params` = ?, `updatedAt` = ? " +
                "WHERE `id` = ? AND `status` IN ('FAILED', 'SKIPPED')"
        ).execute(Tuple.of(now, params.encode(), now, id)).coAwait().rowCount() > 0

        if (!reset) throw InvalidState((mailOutbox.getById(id, client) ?: throw NotFound()).status.name)

        val fresh = mailOutbox.getById(id, client) ?: throw NotFound()

        // false = the job's tick claimed it first: it sends the row, nothing is left to report here
        if (!job.runNow(fresh)) return fresh

        val after = mailOutbox.getById(id, client) ?: throw NotFound()

        when (after.status) {
            MailStatus.SENT -> Unit
            MailStatus.SKIPPED -> throw MailDisabled()
            else -> throw MailSendFailed()
        }

        return after
    }

    // ----- test mail ----------------------------------------------------------------------------------------------------

    /**
     * `POST /settings/mail/test`: one mail straight through the gateway (no outbox row). Without [rawKind] a plain test mail, with one a sample of that kind
     * (a sample order marked as a test order, so the subject carries `[TEST] `). The recipient is [rawRecipient], else [adminEmail]. 400 `INVALID_MAIL_KIND`,
     * 400 `MAIL_RECIPIENT_REQUIRED`, 409 `MAIL_DISABLED`, 502 `MAIL_SEND_FAILED` (never the mail server's own text).
     */
    suspend fun sendTest(rawKind: String?, rawRecipient: String?, adminEmail: String?) {
        val kind = rawKind?.trim()?.takeIf { it.isNotEmpty() }?.let { parseKind(it) ?: throw InvalidMailKind() }
        val to = (parseRecipient(rawRecipient) ?: adminEmail?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }) ?: throw MailRecipientRequired()

        if (!RECIPIENT.matches(to)) throw MailRecipientRequired()
        if (!mailEnabled() || !mailOptionsAvailable()) throw MailDisabled()

        val locale = defaultLocale()
        val content = if (kind == null) builder.test(locale) else builder.build(MailSamples.input(kind, locale, config(), clock.now()))
        val message = OutboundMail(recipient = to, locale = locale, content = content, replyTo = config().mailReplyTo.takeIf { it.isNotBlank() })
        val result = try {
            withTimeout(SEND_TIMEOUT_MS) { gateway.send(message) }
        } catch (e: CancellationException) {
            if (e is kotlinx.coroutines.TimeoutCancellationException) throw MailSendFailed()

            throw e
        } catch (t: Throwable) {
            throw MailSendFailed()
        }

        if (result == MailSendResult.DISABLED) throw MailDisabled()
    }

    // ----- parsing ------------------------------------------------------------------------------------------------------

    private fun parseKind(raw: String?): MailKind? = MailKind.entries.firstOrNull { it.name == raw?.trim() }

    /** A given recipient: trimmed, lower-cased, the regex of 12 section 4.2 step 1 (no whitespace, so no CR / LF); blank = not given. */
    private fun parseRecipient(raw: String?): String? {
        val value = raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null

        if (!RECIPIENT.matches(value)) throw RequestValueException("recipient", "INVALID")

        return value
    }

    companion object {
        const val SEND_TIMEOUT_MS = 60_000L

        private val RECIPIENT = Regex("^[^@\\s]{1,64}@[^@\\s]{1,190}$")
    }
}
