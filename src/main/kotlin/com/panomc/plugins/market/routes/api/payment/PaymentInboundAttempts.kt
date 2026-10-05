package com.panomc.plugins.market.routes.api.payment

import com.panomc.plugins.market.core.order.OrderActor
import com.panomc.plugins.market.core.payment.PaymentAttemptEvent
import com.panomc.plugins.market.core.payment.ProviderMoneyPolicy
import com.panomc.plugins.market.core.time.Clock
import com.panomc.plugins.market.db.dao.MarketOrderDao
import com.panomc.plugins.market.db.dao.MarketPaymentDao
import com.panomc.plugins.market.db.dao.isDuplicateKey
import com.panomc.plugins.market.db.model.MarketPayment
import com.panomc.plugins.market.db.tx.Locks
import com.panomc.plugins.market.db.tx.MarketDb
import com.panomc.plugins.market.db.tx.OrderChild
import com.panomc.plugins.market.db.tx.OrderLockScope
import com.panomc.plugins.market.db.tx.txRestartingOnOrderChange
import com.panomc.plugins.market.provider.SecretCipher
import com.panomc.plugins.market.service.AppliedEvent
import com.panomc.plugins.market.service.AttemptFacts
import com.panomc.plugins.market.service.PaymentService
import com.panomc.plugins.market.spi.common.Money
import com.panomc.plugins.market.spi.payment.PaymentAttemptView
import com.panomc.plugins.market.spi.payment.PaymentEvent
import com.panomc.plugins.market.spi.payment.PaymentLookup
import com.panomc.plugins.market.spi.payment.PaymentTarget
import com.panomc.plugins.market.spi.payment.SubscriptionView
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple

/** [MarketPayment] as the SPI shows it to a provider (02 section 7.1 `PaymentAttemptView`); [publicId] is the order's. */
internal fun attemptViewOf(a: MarketPayment, publicId: String, cipher: SecretCipher) = PaymentAttemptView(
    id = a.id, reference = a.reference, token = a.token, status = a.status.name, amount = Money(a.amount, a.currency), orderId = a.orderId, orderPublicId = publicId,
    gatewayTransactionId = a.gatewayTransactionId, gatewayRefs = a.gatewayRefs?.let { raw -> runCatching { JsonObject(raw).map.mapValues { it.value.toString() } }.getOrNull() } ?: emptyMap(),
    providerData = a.providerData?.let { cipher.decrypt(it) }?.let { runCatching { JsonObject(it) }.getOrNull() }, testMode = a.testMode, createdAt = a.createdAt,
    expiresAt = a.expiresAt, subscription = null, paidAmount = a.paidAmount?.let { Money(it, a.paidCurrency ?: a.currency) }, refundedAmount = Money(a.refundedAmount, a.currency),
    paidAt = a.paidAt
)

/**
 * [InboundAttempts] on the real service and tables: targets are resolved with the DAO finders of the attempt table (and one JSON lookup for a
 * gateway reference), an attempt event goes through [PaymentService.applyEvent] (its own transaction under the order locks of 06 section 13.2),
 * `ReferencesUpdated` is a locked merge of the ids.
 */
class PaymentInboundAttempts(
    private val payments: MarketPaymentDao,
    private val orders: MarketOrderDao,
    private val service: PaymentService,
    private val cipher: SecretCipher,
    private val db: MarketDb,
    private val locks: Locks,
    private val clock: Clock,
    private val client: suspend () -> SqlClient
) : InboundAttempts {
    private fun table(name: String) = "`${orders.prefix()}$name`"

    override suspend fun byToken(token: String): MarketPayment? = payments.getByToken(token, client())

    override suspend fun byId(id: Long): MarketPayment? = payments.getById(id, client())

    override suspend fun publicIdOf(orderId: Long): String? = orders.getById(orderId, client())?.publicId

    override suspend fun resolve(providerId: String, target: PaymentTarget): MarketPayment? = resolveAttemptTarget(payments, orders, providerId, target, client())

    override fun view(attempt: MarketPayment, publicId: String): PaymentAttemptView = attemptViewOf(attempt, publicId, cipher)

    override fun facts(event: PaymentEvent): AttemptFacts = AttemptFacts.of(event, cipher)

    override suspend fun apply(attempt: MarketPayment, event: PaymentAttemptEvent, facts: AttemptFacts, policy: ProviderMoneyPolicy): AppliedEvent =
        service.applyEvent(attempt.orderId, attempt.id, event, facts, OrderActor.GATEWAY, policy)

    override suspend fun attach(attempt: MarketPayment, event: PaymentEvent) {
        db.txRestartingOnOrderChange { conn ->
            locks.forOrder(conn, attempt.orderId, OrderLockScope.PAYMENT) {
                locks.children(conn, attempt.orderId, OrderChild.PAYMENT)

                val current = payments.getById(attempt.id, conn) ?: return@forOrder
                val sets = linkedMapOf<String, Any?>()

                event.gatewayTransactionId?.let { sets["gatewayTransactionId"] = it }

                if (event.gatewayRefs.isNotEmpty()) {
                    val merged = current.gatewayRefs?.let { runCatching { JsonObject(it) }.getOrNull() } ?: JsonObject()

                    event.gatewayRefs.forEach { (k, v) -> merged.put(k, v) }
                    sets["gatewayRefs"] = merged.encode()
                }

                event.providerData?.let { sets["providerData"] = cipher.encrypt(it.encode()) }

                if (sets.isEmpty()) return@forOrder

                try {
                    update(conn, current.id, sets)
                } catch (e: Exception) {
                    // the transaction id belongs to another attempt of this provider (uq_provider_txn): the rest is kept, the id is dropped
                    if (!e.isDuplicateKey() || !sets.containsKey("gatewayTransactionId")) throw e

                    sets.remove("gatewayTransactionId")

                    if (sets.isNotEmpty()) update(conn, current.id, sets)
                }
            }
        }
    }

    private suspend fun update(conn: io.vertx.sqlclient.SqlConnection, id: Long, sets: Map<String, Any?>) {
        val columns = sets.keys.joinToString(", ") { "`$it` = ?" }
        val values = ArrayList<Any?>(sets.values).also { it += clock.now(); it += id }

        conn.preparedQuery("UPDATE ${table("market_payment")} SET $columns, `updatedAt` = ? WHERE `id` = ?").execute(Tuple.from(values)).coAwait()
    }
}

/**
 * `ctx.payments` of a provider (02 section 5): read-only views of this provider's own attempts. An attempt of another provider answers `null`.
 * Subscriptions are the subscription slice's (MK-121): until it lands `subscriptionByGatewayId` answers `null`.
 */
class AttemptLookup(
    private val providerId: String,
    private val payments: MarketPaymentDao,
    private val orders: MarketOrderDao,
    private val cipher: SecretCipher,
    private val client: suspend () -> SqlClient
) : PaymentLookup {
    private suspend fun view(a: MarketPayment?): PaymentAttemptView? {
        if (a == null || a.providerId != providerId) return null

        return attemptViewOf(a, orders.getById(a.orderId, client())?.publicId.orEmpty(), cipher)
    }

    override suspend fun byId(attemptId: Long): PaymentAttemptView? = view(payments.getById(attemptId, client()))

    override suspend fun byReference(reference: String): PaymentAttemptView? = view(payments.getByReference(reference, client()))

    override suspend fun byGatewayTransactionId(id: String): PaymentAttemptView? = view(payments.getByProviderTransaction(providerId, id, client()))

    override suspend fun byGatewayRef(name: String, value: String): PaymentAttemptView? {
        val path = "$.\"" + name.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        val id = client().preparedQuery(
            "SELECT `id` FROM `${orders.prefix()}market_payment` WHERE `providerId` = ? AND JSON_UNQUOTE(JSON_EXTRACT(`gatewayRefs`, ?)) = ? ORDER BY `id` DESC LIMIT 1"
        ).execute(Tuple.of(providerId, path, value)).coAwait().firstOrNull()?.getLong("id") ?: return null

        return view(payments.getById(id, client()))
    }

    override suspend fun subscriptionByGatewayId(gatewaySubscriptionId: String): SubscriptionView? = null
}

/**
 * The attempt of provider [providerId] that [target] names (`null` for an unknown one, and for one of another provider: a provider only ever reaches its
 * own attempts; `PaymentTarget.Subscription` is never resolved here). The one rule of every channel: the inbound pipeline ([PaymentInboundAttempts]) and the
 * status / reconcile query of [PaymentService].
 */
suspend fun resolveAttemptTarget(payments: MarketPaymentDao, orders: MarketOrderDao, providerId: String, target: PaymentTarget, sql: SqlClient): MarketPayment? {
    val found = when (target) {
        is PaymentTarget.Attempt -> payments.getById(target.attemptId, sql)
        is PaymentTarget.Reference -> payments.getByReference(target.reference, sql)
        is PaymentTarget.GatewayTransaction -> payments.getByProviderTransaction(providerId, target.gatewayTransactionId, sql)
        is PaymentTarget.GatewayRef -> attemptByGatewayRef(payments, orders, providerId, target.name, target.value, sql)
        is PaymentTarget.Subscription -> null
    }

    return found?.takeIf { it.providerId == providerId }
}

private suspend fun attemptByGatewayRef(payments: MarketPaymentDao, orders: MarketOrderDao, providerId: String, name: String, value: String, sql: SqlClient): MarketPayment? {
    val path = "$.\"" + name.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    val id = sql.preparedQuery(
        "SELECT `id` FROM `${orders.prefix()}market_payment` WHERE `providerId` = ? AND JSON_UNQUOTE(JSON_EXTRACT(`gatewayRefs`, ?)) = ? ORDER BY `id` DESC LIMIT 1"
    ).execute(Tuple.of(providerId, path, value)).coAwait().firstOrNull()?.getLong("id") ?: return null

    return payments.getById(id, sql)
}
