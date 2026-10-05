package com.panomc.plugins.market.service

import com.panomc.platform.error.BadRequest
import com.panomc.plugins.market.core.order.BillingSnapshot
import com.panomc.plugins.market.core.order.OrderActor
import com.panomc.plugins.market.core.order.OrderEvent
import com.panomc.plugins.market.core.order.OrderTimings
import com.panomc.plugins.market.core.order.RequiredBuyerFields
import com.panomc.plugins.market.core.order.TimingConfig
import com.panomc.plugins.market.core.payment.AttemptState
import com.panomc.plugins.market.core.payment.OrderTender
import com.panomc.plugins.market.core.payment.PaymentAttemptEvent
import com.panomc.plugins.market.core.payment.PaymentEffect
import com.panomc.plugins.market.core.payment.PaymentStateMachine
import com.panomc.plugins.market.core.payment.PaymentTransition
import com.panomc.plugins.market.core.payment.ProviderMoneyPolicy
import com.panomc.plugins.market.core.pricing.FrozenOrder
import com.panomc.plugins.market.core.pricing.MethodInput
import com.panomc.plugins.market.core.pricing.PricingCode
import com.panomc.plugins.market.core.pricing.PricingEngine
import com.panomc.plugins.market.core.pricing.PricingException
import com.panomc.plugins.market.core.pricing.PricingProfile
import com.panomc.plugins.market.core.pricing.TenderInput
import com.panomc.plugins.market.core.time.Clock
import com.panomc.plugins.market.core.time.Ids
import com.panomc.plugins.market.config.MarketConfig
import com.panomc.plugins.market.core.money.Conversions
import com.panomc.plugins.market.db.dao.MarketCreditAccountDao
import com.panomc.plugins.market.db.dao.MarketCurrencyRateDao
import com.panomc.plugins.market.db.dao.MarketOrderDao
import com.panomc.plugins.market.db.dao.MarketOrderEventDao
import com.panomc.plugins.market.db.dao.MarketOrderItemDao
import com.panomc.plugins.market.db.dao.MarketPaymentDao
import com.panomc.plugins.market.db.dao.MarketPaymentMethodDao
import com.panomc.plugins.market.db.dao.isDuplicateKey
import com.panomc.plugins.market.db.model.MarketOrder
import com.panomc.plugins.market.db.model.MarketOrderEvent
import com.panomc.plugins.market.db.model.MarketOrderItem
import com.panomc.plugins.market.db.model.MarketPayment
import com.panomc.plugins.market.db.model.MarketPaymentMethod
import com.panomc.plugins.market.db.model.OrderActorType
import com.panomc.plugins.market.db.model.OrderEventType
import com.panomc.plugins.market.db.model.OrderItemKind
import com.panomc.plugins.market.db.model.OrderSource
import com.panomc.plugins.market.db.model.PaymentStatus
import com.panomc.plugins.market.db.model.PricingMode
import com.panomc.plugins.market.db.model.ReservationState
import com.panomc.plugins.market.db.tx.LockedOrder
import com.panomc.plugins.market.db.tx.Locks
import com.panomc.plugins.market.db.tx.MarketDb
import com.panomc.plugins.market.db.tx.OrderChild
import com.panomc.plugins.market.db.tx.OrderLockScope
import com.panomc.plugins.market.db.tx.txRestartingOnOrderChange
import com.panomc.plugins.market.error.BuyerInfoRequired
import com.panomc.plugins.market.error.InsufficientCredits
import com.panomc.plugins.market.error.OrderNotCancellable
import com.panomc.plugins.market.error.OrderNotPayable
import com.panomc.plugins.market.error.PaymentMethodUnavailable
import com.panomc.plugins.market.error.PaymentProviderError
import com.panomc.plugins.market.provider.ProviderLookup
import com.panomc.plugins.market.provider.SecretCipher
import com.panomc.plugins.market.provider.SettingsCodec
import com.panomc.plugins.market.spi.common.Address
import com.panomc.plugins.market.spi.common.Money
import com.panomc.plugins.market.spi.common.ProviderException
import com.panomc.plugins.market.spi.common.ProviderErrorCode
import com.panomc.plugins.market.spi.common.ProviderSettings
import com.panomc.plugins.market.spi.common.SiteInfo
import com.panomc.plugins.market.spi.payment.AttemptRef
import com.panomc.plugins.market.spi.payment.AttemptUrls
import com.panomc.plugins.market.spi.payment.BuyerInfo
import com.panomc.plugins.market.spi.payment.CancelPaymentRequest
import com.panomc.plugins.market.spi.payment.CheckoutSnapshot
import com.panomc.plugins.market.spi.payment.ContinuePaymentRequest
import com.panomc.plugins.market.spi.payment.OrderLine
import com.panomc.plugins.market.spi.payment.PaymentAttemptView
import com.panomc.plugins.market.spi.payment.PaymentCapabilities
import com.panomc.plugins.market.spi.payment.PaymentEvent
import com.panomc.plugins.market.spi.payment.PaymentProvider
import com.panomc.plugins.market.spi.payment.QueryPaymentRequest
import com.panomc.plugins.market.spi.payment.QueryReason
import com.panomc.plugins.market.spi.payment.StartPaymentRequest
import com.panomc.plugins.market.spi.payment.StartPaymentResult
import com.panomc.plugins.market.spi.common.TestModeSupport
import com.panomc.plugins.market.spi.payment.OrderSnapshot as SpiOrderSnapshot
import com.panomc.plugins.market.util.HtmlSanitizer
import com.panomc.plugins.market.util.OrderStatus
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap

/** A panel alert for an order that waits for a human (06 section 11, O3 / O9). The notification itself is the platform's; this is the seam. */
fun interface PanelAlerts {
    suspend fun reviewOpened(orderId: Long, reason: String?)

    companion object {
        private val logger = LoggerFactory.getLogger(PanelAlerts::class.java)

        val LOG_ONLY = PanelAlerts { orderId, reason -> logger.warn("order {} waits for review ({})", orderId, reason) }
    }
}

/** The body of `POST /api/market/orders/:publicId/pay` after parsing (04 section 3). */
class PayRequest(val paymentMethodId: String?, val useCredits: Long?, val billingInfo: JsonObject?)

/** Who pays: [canUseTestMode] = the session holds `SET`, `PAY` or the umbrella node (02 section 5.1 test-mode rule); resolved by the route. */
class PayCaller(val canUseTestMode: Boolean = false, val clientIp: String? = null, val userAgent: String? = null)

/**
 * What an event carries on top of its kind, for the attempt row (06 section 9.4): provider ids, the money detail of a success, the stored
 * start. Everything is optional. [startKind] / [startPayload] (already encrypted) / [startedAt] / [nextQueryAt] are written when the attempt
 * moves; the failure fields when a start failed.
 */
class AttemptFacts(
    val gatewayTransactionId: String? = null,
    val gatewayRefs: Map<String, String> = emptyMap(),
    /** Already encrypted (`SecretCipher`), stored verbatim. */
    val providerData: String? = null,
    val gatewayFee: Long? = null,
    val net: Long? = null,
    val settlementCurrency: String? = null,
    val settlementAmount: String? = null,
    val installments: Int? = null,
    val methodDetail: String? = null,
    val startKind: String? = null,
    val startPayload: String? = null,
    val startedAt: Long? = null,
    val nextQueryAt: Long? = null,
    val expiresAt: Long? = null,
    val failureCode: String? = null,
    val failureMessage: String? = null,
    val adminMessage: String? = null
) {
    companion object {
        val NONE = AttemptFacts()

        /** The facts of a provider event (the encrypted `providerData` needs [cipher]). */
        fun of(event: PaymentEvent, cipher: SecretCipher): AttemptFacts {
            val succeeded = event as? PaymentEvent.Succeeded

            return AttemptFacts(
                gatewayTransactionId = event.gatewayTransactionId, gatewayRefs = event.gatewayRefs,
                providerData = event.providerData?.let { cipher.encrypt(it.encode()) },
                gatewayFee = succeeded?.gatewayFee?.amount, net = succeeded?.net?.amount, settlementCurrency = succeeded?.settlementCurrency,
                settlementAmount = succeeded?.settlementAmount, installments = succeeded?.installments, methodDetail = succeeded?.methodDetail,
                adminMessage = event.note
            )
        }
    }
}

/** What [PaymentService.applyEvent] did. */
class AppliedEvent(val attemptStatus: PaymentStatus, val orderStatus: OrderStatus, val changed: Boolean, val duplicate: Boolean)

/** `PaymentEvent` of the SPI as the attempt state machine reads it; refund, dispute and subscription events are other slices'. */
object PaymentEventMapper {
    fun attemptEvent(event: PaymentEvent): PaymentAttemptEvent? = when (event) {
        is PaymentEvent.Succeeded -> PaymentAttemptEvent.Succeeded(event.paid.amount, event.paid.currency, event.testMode)
        is PaymentEvent.Pending -> PaymentAttemptEvent.Pending
        is PaymentEvent.Failed -> PaymentAttemptEvent.Failed(event.final)
        is PaymentEvent.Cancelled -> PaymentAttemptEvent.Cancelled
        is PaymentEvent.Expired -> PaymentAttemptEvent.Expired
        is PaymentEvent.NeedsReview -> PaymentAttemptEvent.NeedsReview(event.reason, event.testMode)
        else -> null
    }
}

/**
 * Payment attempts (06 sections 9, 10; 02 sections 6 and 8; 00 sections 6.9 and 7.2; MK-076).
 *
 * - [start] is phase C of checkout and the second half of `/pay`: after the transaction that wrote the `CREATED` attempt committed, the
 *   provider is called (30 s, never inside a transaction) and a second transaction stores the result under the order lock: `PENDING` with
 *   the encrypted start for a buyer-facing result, the success event for `Completed` (O2 in the same transaction), `FAILED` for a provider
 *   error, or nothing for a deadline (the attempt stays `CREATED` with `failureCode = TIMEOUT`).
 * - [pay] re-tenders a pending order (credit part, method, fee) and starts a new attempt; [continuePayment] is the second step of an
 *   embedded form; [cancel] is O7 by the buyer; [status] is the poll of the order page with the on-demand status query.
 * - [applyEvent] is the one place a payment event reaches an attempt and its order (06 section 9.4): the attempt machine decides, the
 *   attempt row is written, then the order is told ([OrderService.transition]) in the same transaction, under the locks of 06 section 13.2.
 *   The inbound pipeline, the status query, the reconcile job and the bank transfer decisions call it.
 *
 * Provider calls never run in a transaction. Everything a call needs from the database is read before it.
 */
class PaymentService(
    private val db: MarketDb,
    private val locks: Locks,
    private val clock: Clock,
    private val ids: Ids,
    private val config: () -> MarketConfig,
    private val orders: MarketOrderDao,
    private val orderItems: MarketOrderItemDao,
    private val orderEvents: MarketOrderEventDao,
    private val payments: MarketPaymentDao,
    private val methods: MarketPaymentMethodDao,
    private val creditAccounts: MarketCreditAccountDao,
    private val currencyRates: MarketCurrencyRateDao,
    private val lookup: ProviderLookup,
    private val cipher: SecretCipher,
    private val contexts: PaymentContexts,
    private val orderService: OrderService,
    private val site: () -> SiteInfo,
    /** Reads outside a transaction (provider resolution after a commit, the view of an order). */
    private val readClient: suspend () -> SqlClient,
    private val alerts: PanelAlerts = PanelAlerts.LOG_ONLY,
    private val startTimeoutMs: Long = START_TIMEOUT_MS,
    private val cancelTimeoutMs: Long = CANCEL_TIMEOUT_MS,
    private val statusWaitMs: Long = STATUS_WAIT_MS,
    private val sanitizeHtml: (String) -> String = { HtmlSanitizer.sanitize(it) }
) : PaymentStarter {

    private fun table(name: String) = "`${orders.prefix()}$name`"

    private fun timing(): TimingConfig = config().let { TimingConfig(it.orderExpiryMinutes, it.bankTransferExpiryHours) }

    // ===================================================================================================== providers

    private class Resolved(
        val provider: PaymentProvider,
        val row: MarketPaymentMethod?,
        val settings: ProviderSettings,
        val caps: PaymentCapabilities,
        val testMode: Boolean,
        val configured: Boolean
    ) {
        val policy get() = ProviderMoneyPolicy(caps.buyerMayPayMore, caps.priceAuthority)
    }

    /** The provider behind [providerId] with its decrypted settings and capabilities; `null` when it is not registered or throws while it describes itself. */
    private suspend fun resolve(providerId: String, sqlClient: SqlClient): Resolved? {
        val provider = lookup.payment(providerId)?.provider ?: return null

        return try {
            val row = methods.getByMethodId(providerId, sqlClient)
            val codec = SettingsCodec(provider.settingsSchema(), cipher)
            val stored = row?.settings?.let { runCatching { JsonObject(it) }.getOrNull() }
            val settings = codec.decrypt(stored)
            val caps = provider.capabilities(settings)
            val c = config()
            val testMode = when (caps.testMode) {
                TestModeSupport.FLAG -> c.testMode || row?.testMode == true
                TestModeSupport.DERIVED -> caps.derivedTestMode == true
                TestModeSupport.NONE -> false
            }

            Resolved(provider, row, settings, caps, testMode, codec.missingRequired(stored).isEmpty())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("provider {} threw while it described itself: {}", providerId, e.javaClass.simpleName)

            null
        }
    }

    // ============================================================================================ phase C (start)

    override suspend fun start(order: MarketOrder, attempt: MarketPayment, sqlClient: SqlClient): JsonObject? =
        startAttempt(order.id, attempt.id, emptyList(), sqlClient)

    /** The stored start of [attempt] as `PaymentStart` JSON (a replay of the request, the order page), `null` when it has none. */
    override suspend fun served(attempt: MarketPayment, sqlClient: SqlClient): JsonObject? = servedStart(attempt)

    private fun servedStart(attempt: MarketPayment): JsonObject? {
        val stored = attempt.startPayload ?: return null
        val plain = cipher.decrypt(stored) ?: return null

        return runCatching { JsonObject(plain).getJsonObject("start") }.getOrNull()
    }

    /**
     * Starts the `CREATED` attempt [attemptId] (06 section 9.2). [cancelled] are the attempts this request's transaction cancelled (only
     * `/pay`): the gateway is told first (best effort), the first of them becomes `replaces`. Returns the `PaymentStart` for the buyer,
     * `{kind: COMPLETED}` when the order is paid, `null` when the attempt was taken over by an event or another `/pay` meanwhile.
     * Throws [PaymentStartFailed] when the provider failed or the deadline passed.
     */
    suspend fun startAttempt(orderId: Long, attemptId: Long, cancelled: List<MarketPayment>, sqlClient: SqlClient): JsonObject? {
        val order = orders.getById(orderId, sqlClient) ?: throw NoSuchElementException("order $orderId does not exist")
        val attempt = payments.getById(attemptId, sqlClient) ?: throw NoSuchElementException("attempt $attemptId does not exist")

        // 1. the superseded attempts are cancelled at the gateway before the new one is started (as the database has them now: CANCELLED)
        val superseded = cancelled.mapNotNull { payments.getById(it.id, sqlClient) }

        for (previous in superseded) cancelAtGateway(previous, order.publicId ?: "", sqlClient)

        val resolved = resolve(attempt.providerId, sqlClient)

        if (resolved == null) {
            failStart(attempt, ProviderErrorCode.CONFIGURATION, "the provider ${attempt.providerId} is not available")

            throw PaymentStartFailed(ProviderErrorCode.CONFIGURATION.name)
        }

        val items = orderItems.getByOrderIds(listOf(orderId), sqlClient)
        val request = requestFor(order, items, attempt, superseded.firstOrNull(), resolved)
        val ctx = contexts.create(resolved.provider, resolved.settings, attempt.testMode)

        // 2. the provider call, never inside a transaction
        val result = try {
            withTimeout(startTimeoutMs) { resolved.provider.startPayment(ctx, request) }
        } catch (e: TimeoutCancellationException) {
            // 5. outcome unknown: the attempt stays CREATED (a later event can still complete it, a new /pay cancels it)
            markTimeout(attempt, sqlClient)

            throw PaymentStartFailed(ProviderErrorCode.GATEWAY_UNREACHABLE.name, e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProviderException) {
            failStart(attempt, e.code, e.adminMessage ?: e.message)

            throw PaymentStartFailed(e.code.name, e)
        } catch (e: Throwable) {
            logger.warn("the provider {} failed to start attempt {}: {}", attempt.providerId, attempt.id, e.javaClass.name)
            failStart(attempt, ProviderErrorCode.INTERNAL, e.javaClass.simpleName)

            throw PaymentStartFailed(ProviderErrorCode.INTERNAL.name, e)
        }

        return storeResult(order, attempt, resolved, result, continued = false)
    }

    /** 4. tx2: `FAILED`, the code, the admin text, `lastError` of the method, a timeline row; the order stays `PENDING`. */
    private suspend fun failStart(attempt: MarketPayment, code: ProviderErrorCode, adminMessage: String?) {
        val after = ArrayList<AfterCommit>()

        db.txRestartingOnOrderChange { conn ->
            after.clear()

            locks.forOrder(conn, attempt.orderId, OrderLockScope.PAYMENT) { locked ->
                applyIn(
                    conn, locked, attempt.id, PaymentAttemptEvent.StartFailed,
                    AttemptFacts(failureCode = code.name, failureMessage = START_FAILED_TEXT, adminMessage = adminMessage?.take(ADMIN_MESSAGE_MAX)),
                    ProviderMoneyPolicy(), OrderActor.SYSTEM, after
                )

                methods.setLastError(attempt.providerId, code.name, clock.now(), conn)
            }
        }
    }

    private suspend fun markTimeout(attempt: MarketPayment, sqlClient: SqlClient) {
        sqlClient.preparedQuery("UPDATE ${table("market_payment")} SET `failureCode` = 'TIMEOUT', `updatedAt` = ? WHERE `id` = ? AND `status` = 'CREATED'")
            .execute(Tuple.of(clock.now(), attempt.id)).coAwait()
    }

    /**
     * 3. tx2: stores a start result. The attempt must still be `CREATED` (a start) or `PENDING` ([continued]: the next step of an embedded
     * form); then a buyer-facing result is stored encrypted and the attempt becomes `PENDING`, a `Completed` applies its success event.
     * An attempt that moved on (an event won the race, `/pay` cancelled it) only merges the provider ids it lacks.
     */
    private suspend fun storeResult(order: MarketOrder, attempt: MarketPayment, resolved: Resolved, result: StartPaymentResult, continued: Boolean): JsonObject? {
        val completed = result as? StartPaymentResult.Completed
        val scope = if (completed != null) OrderLockScope.COMMIT else OrderLockScope.PAYMENT
        val expect = if (continued) PaymentStatus.PENDING else PaymentStatus.CREATED
        val after = ArrayList<AfterCommit>()

        val start = db.txRestartingOnOrderChange { conn ->
            after.clear()

            locks.forOrder(conn, order.id, scope, cashback = completed != null && config().cashbackPercent > 0) { locked ->
                locks.children(conn, order.id, OrderChild.PAYMENT)

                val current = payments.getById(attempt.id, conn) ?: throw NoSuchElementException("attempt ${attempt.id} does not exist")
                val now = clock.now()

                if (current.status != expect) {
                    mergeRefs(conn, current, result.gatewayTransactionId, result.gatewayRefs, onlyIfEmpty = true)

                    return@forOrder null
                }

                if (completed != null) {
                    val facts = AttemptFacts.of(completed.event, cipher).let { f ->
                        AttemptFacts(
                            f.gatewayTransactionId ?: result.gatewayTransactionId, f.gatewayRefs + result.gatewayRefs, f.providerData, f.gatewayFee, f.net,
                            f.settlementCurrency, f.settlementAmount, f.installments, f.methodDetail, startKind = "COMPLETED", startedAt = now,
                            adminMessage = f.adminMessage
                        )
                    }
                    val applied = applyIn(
                        conn, locked, attempt.id, PaymentEventMapper.attemptEvent(completed.event)!!, facts, resolved.policy,
                        if (resolved.provider.id == OrderTimings.FREE_PROVIDER || resolved.provider.id == OrderTimings.CREDITS_PROVIDER) OrderActor.SYSTEM else OrderActor.GATEWAY,
                        after
                    )

                    return@forOrder if (applied.orderStatus == OrderStatus.COMPLETED) JsonObject().put("kind", "COMPLETED") else null
                }

                val expiresAt = result.expiresAt?.let { OrderTimings.attemptExpiresAt(now, 0, 0, it) } ?: current.expiresAt
                val json = result.toPaymentStartJson(order.locale ?: site().defaultLocale, attemptPageOf(current), expiresAt, sanitizeHtml)
                val facts = AttemptFacts(
                    gatewayTransactionId = result.gatewayTransactionId, gatewayRefs = result.gatewayRefs,
                    providerData = result.providerData?.let { cipher.encrypt(it.encode()) }, startKind = json.getString("kind"),
                    startPayload = cipher.encrypt(storedStart(json, result).encode()), startedAt = now,
                    nextQueryAt = if (resolved.caps.statusQuery) now + STATUS_QUERY_FIRST_MS else null, expiresAt = expiresAt
                )

                if (continued) {
                    rewriteStart(conn, current, facts)
                } else {
                    applyIn(conn, locked, attempt.id, PaymentAttemptEvent.Started, facts, resolved.policy, OrderActor.GATEWAY, after)
                }

                // order `expiresAt` after a new attempt: max(order.expiresAt, attempt.expiresAt)
                val fresh = orders.getById(order.id, conn)!!

                if (fresh.status == OrderStatus.PENDING && fresh.expiresAt != null && expiresAt != null) {
                    val raised = OrderTimings.orderExpiresAtAfterAttempt(fresh.expiresAt, expiresAt)

                    if (raised != fresh.expiresAt) orderService.updateOrder(conn, order.id, linkedMapOf("expiresAt" to raised))
                }

                json
            }
        }

        runAfter(after, readClient())

        return start
    }

    /** `{start: PaymentStart, formPost?: {...}, html?: {...}}`: what the attempt page route needs besides the `PaymentStart` (06 section 9.2 step 3). */
    private fun storedStart(json: JsonObject, result: StartPaymentResult): JsonObject {
        val out = JsonObject().put("start", json)

        when (result) {
            is StartPaymentResult.FormPost -> out.put(
                "formPost",
                JsonObject().put("actionUrl", result.actionUrl).put("fields", JsonObject(LinkedHashMap<String, Any?>(result.fields))).put("acceptCharset", result.acceptCharset)
            )

            is StartPaymentResult.Html -> out.put(
                "html",
                JsonObject().put("document", result.document).put("scriptOrigins", JsonArray(result.scriptOrigins)).put("frameOrigins", JsonArray(result.frameOrigins))
                    .put("connectOrigins", JsonArray(result.connectOrigins)).put("formActionOrigins", JsonArray(result.formActionOrigins)).put("inlineScript", result.inlineScript)
            )

            else -> Unit
        }

        return out
    }

    private fun attemptPageOf(attempt: MarketPayment): String = "/api/market/payments/attempts/${attempt.token}/page"

    /** A `continuePayment` result replaces the stored start of a `PENDING` attempt. */
    private suspend fun rewriteStart(conn: SqlConnection, attempt: MarketPayment, facts: AttemptFacts) {
        conn.preparedQuery(
            "UPDATE ${table("market_payment")} SET `startKind` = ?, `startPayload` = ?, `providerData` = COALESCE(?, `providerData`), `expiresAt` = COALESCE(?, `expiresAt`), `updatedAt` = ? " +
                "WHERE `id` = ? AND `status` = 'PENDING'"
        ).execute(Tuple.of(facts.startKind, facts.startPayload, facts.providerData, facts.expiresAt, clock.now(), attempt.id)).coAwait()

        mergeRefs(conn, attempt, facts.gatewayTransactionId, facts.gatewayRefs, onlyIfEmpty = false)
    }

    /** Merges provider ids into the attempt; a transaction id already held by another attempt of the provider (`uq_provider_txn`) is skipped, never an error. */
    private suspend fun mergeRefs(conn: SqlConnection, attempt: MarketPayment, transactionId: String?, refs: Map<String, String>, onlyIfEmpty: Boolean) {
        val sets = linkedMapOf<String, Any?>()

        if (transactionId != null && (!onlyIfEmpty || attempt.gatewayTransactionId == null)) sets["gatewayTransactionId"] = transactionId

        if (refs.isNotEmpty()) {
            val existing = attempt.gatewayRefs?.let { runCatching { JsonObject(it) }.getOrNull() } ?: JsonObject()
            val merged = existing.copy()

            for ((k, v) in refs) if (!onlyIfEmpty || !merged.containsKey(k)) merged.put(k, v)

            sets["gatewayRefs"] = merged.encode()
        }

        if (sets.isEmpty()) return

        updateAttempt(conn, attempt.id, sets, whereStatus = null)
    }

    private suspend fun updateAttempt(conn: SqlConnection, id: Long, sets: LinkedHashMap<String, Any?>, whereStatus: PaymentStatus?): Boolean {
        fun run(columns: Map<String, Any?>): suspend () -> Int = {
            val keys = columns.keys.joinToString(", ") { "`$it` = ?" }
            val values = ArrayList<Any?>(columns.values)

            values += clock.now()
            values += id

            val statusClause = if (whereStatus != null) " AND `status` = ?" else ""

            if (whereStatus != null) values += whereStatus.name

            conn.preparedQuery("UPDATE ${table("market_payment")} SET $keys, `updatedAt` = ? WHERE `id` = ?$statusClause").execute(Tuple.from(values)).coAwait().rowCount()
        }

        return try {
            run(sets)() == 1
        } catch (e: Exception) {
            // the transaction id belongs to another attempt of this provider: keep the rest, drop the id
            if (!e.isDuplicateKey() || !sets.containsKey("gatewayTransactionId")) throw e

            logger.warn("attempt {}: the gateway transaction id is already held by another attempt, not stored", id)

            run(LinkedHashMap(sets).also { it.remove("gatewayTransactionId") })() == 1
        }
    }

    // ======================================================================================== the provider request

    private fun spiSnapshot(order: MarketOrder, items: List<MarketOrderItem>): SpiOrderSnapshot {
        val currency = order.currency
        val lines = items.filter { it.kind != OrderItemKind.BUNDLE_CHILD }.map { item ->
            val snapshot = item.snapshot?.let { runCatching { JsonObject(it) }.getOrNull() }

            OrderLine(
                orderItemId = item.id, productId = item.productId, name = item.productName, sku = item.sku, variantName = item.variantName,
                quantity = item.quantity, unitPrice = Money(item.unitPrice, currency), total = Money(item.lineTotal, currency), vatPercent = item.vatPercent,
                physical = item.physical, categoryName = snapshot?.getString("categoryName"), providerMeta = snapshot?.getJsonObject("providerMeta")
            )
        }

        return SpiOrderSnapshot(
            id = order.id, publicId = order.publicId ?: "", description = "Order #${order.id}", currency = currency, lines = lines,
            subtotal = Money(order.subtotal, currency),
            discount = Money(order.discountTotal + order.couponDiscount + order.creatorDiscount + order.upgradeDiscount, currency),
            shipping = Money(order.shippingTotal, currency), fee = Money(order.paymentFee, currency), vat = Money(order.vatTotal, currency),
            total = Money(order.totalPrice, currency), creditValue = Money(order.creditValue, currency), requiresShipping = order.requiresShipping,
            recipientUsername = order.recipientUsername, gift = order.isGift, pricingMode = order.pricingMode.name
        )
    }

    private fun requestFor(order: MarketOrder, items: List<MarketOrderItem>, attempt: MarketPayment, replaces: MarketPayment?, resolved: Resolved): StartPaymentRequest {
        val snapshot = spiSnapshot(order, items)
        val billing = order.billingInfo?.let { runCatching { JsonObject(it) }.getOrNull() }
        val locale = order.locale ?: site().defaultLocale

        return StartPaymentRequest(
            attempt = AttemptRef(attempt.id, attempt.reference, attempt.token), amount = Money(attempt.amount, attempt.currency), order = snapshot,
            buyer = buyerOf(order, attempt.clientIp, attempt.userAgent, billing, locale), billing = addressOf(billing),
            shipping = addressOf(order.shippingAddress?.let { runCatching { JsonObject(it) }.getOrNull() }),
            subscription = null, urls = urlsFor(attempt, order.publicId ?: "", resolved.provider.id), idempotencyKey = "pay:" + attempt.reference, locale = locale,
            expiresAt = attempt.expiresAt ?: (clock.now() + OrderTimings.MIN_ATTEMPT_MS), replaces = replaces?.let { attemptView(it, order.publicId ?: "") }
        )
    }

    private fun buyerOf(order: MarketOrder, ip: String?, userAgent: String?, billing: JsonObject?, locale: String) = BuyerInfo(
        userId = order.userId, guest = order.userId == null, numericId = order.userId ?: CheckoutService.guestNumericId(order.playerUsername),
        stableId = order.buyerKey.take(64), username = order.playerUsername, email = order.email, ip = ip, userAgent = userAgent, locale = locale,
        firstName = billing?.getString("firstName"), lastName = billing?.getString("lastName"), phone = billing?.getString("phone"),
        country = billing?.getString("country"), identityNumber = billing?.getString("identityNumber"), registeredAt = null
    )

    private fun addressOf(json: JsonObject?): Address? = json?.let {
        Address(
            it.getString("firstName"), it.getString("lastName"), it.getString("company"), it.getString("phone"), it.getString("email"), it.getString("country"),
            it.getString("state"), it.getString("city"), it.getString("district"), it.getString("neighborhood"), it.getString("line1"), it.getString("line2"),
            it.getString("postalCode"), it.getString("taxOffice"), it.getString("taxNumber"), it.getString("identityNumber")
        )
    }

    private fun urlsFor(attempt: MarketPayment, publicId: String, providerId: String): AttemptUrls {
        val base = site().baseUrl.trimEnd('/')
        val root = "$base/api/market/payments/$providerId"

        return AttemptUrls(
            success = "$root/return/${attempt.token}/success", cancel = "$root/return/${attempt.token}/cancel", pending = "$root/return/${attempt.token}/pending",
            result = "$root/return/${attempt.token}/result", notify = "$root/notify/${attempt.token}", orderPage = "$base/store/order/$publicId"
        )
    }

    private fun attemptView(a: MarketPayment, publicId: String) = PaymentAttemptView(
        id = a.id, reference = a.reference, token = a.token, status = a.status.name, amount = Money(a.amount, a.currency), orderId = a.orderId, orderPublicId = publicId,
        gatewayTransactionId = a.gatewayTransactionId, gatewayRefs = a.gatewayRefs?.let { raw -> runCatching { JsonObject(raw).map.mapValues { it.value.toString() } }.getOrNull() } ?: emptyMap(),
        providerData = a.providerData?.let { cipher.decrypt(it) }?.let { runCatching { JsonObject(it) }.getOrNull() }, testMode = a.testMode, createdAt = a.createdAt,
        expiresAt = a.expiresAt, subscription = null, paidAmount = a.paidAmount?.let { Money(it, a.paidCurrency ?: a.currency) }, refundedAmount = Money(a.refundedAmount, a.currency),
        paidAt = a.paidAt
    )

    // ===================================================================================== best-effort gateway cancel

    private suspend fun cancelAtGateway(attempt: MarketPayment, publicId: String, sqlClient: SqlClient) {
        val resolved = resolve(attempt.providerId, sqlClient) ?: return

        if (!resolved.caps.cancelPending) return

        try {
            val ctx = contexts.create(resolved.provider, resolved.settings, attempt.testMode)

            withTimeout(cancelTimeoutMs) { resolved.provider.cancelPayment(ctx, CancelPaymentRequest(attemptView(attempt, publicId))) }
        } catch (e: TimeoutCancellationException) {
            logger.warn("cancelPayment of attempt {} timed out, ignored", attempt.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.warn("cancelPayment of attempt {} failed, ignored: {}", attempt.id, e.javaClass.simpleName)
        }
    }

    private suspend fun runAfter(after: List<AfterCommit>, sqlClient: SqlClient) {
        for (item in after) {
            try {
                when (item) {
                    is AfterCommit.CancelAtGateway -> {
                        val publicId = item.attempts.firstOrNull()?.let { orders.getById(it.orderId, sqlClient)?.publicId } ?: ""

                        for (attempt in item.attempts) cancelAtGateway(attempt, publicId, sqlClient)
                    }

                    is AfterCommit.PanelAlert -> alerts.reviewOpened(item.orderId, item.reason)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logger.warn("an after-commit step failed, ignored: {}", e.javaClass.simpleName)
            }
        }
    }

    // ============================================================================== applying events (06 section 9.4)

    /**
     * Applies [event] to attempt [attemptId] of order [orderId] in one transaction (06 section 9.4) and runs the after-commit steps.
     * `Succeeded` and `NeedsReview` take the `COMMIT` lock set, a final `Failed` the `RELEASE` set (it can end the order, O8), the rest
     * the `PAYMENT` set. [policy] defaults to what the attempt's provider declares.
     */
    suspend fun applyEvent(
        orderId: Long,
        attemptId: Long,
        event: PaymentAttemptEvent,
        facts: AttemptFacts = AttemptFacts.NONE,
        actor: OrderActor = OrderActor.GATEWAY,
        policy: ProviderMoneyPolicy? = null
    ): AppliedEvent {
        val after = ArrayList<AfterCommit>()
        val applied = db.txRestartingOnOrderChange { conn ->
            after.clear()

            locks.forOrder(conn, orderId, scopeFor(event), cashback = config().cashbackPercent > 0) { locked ->
                val attempt = payments.getById(attemptId, conn) ?: throw NoSuchElementException("attempt $attemptId does not exist")

                applyIn(conn, locked, attemptId, event, facts, policy ?: resolve(attempt.providerId, conn)?.policy ?: ProviderMoneyPolicy(), actor, after)
            }
        }

        runAfter(after, readClient())

        return applied
    }

    /** The lock set an event needs (06 section 13.2). */
    fun scopeFor(event: PaymentAttemptEvent): OrderLockScope = when (event) {
        is PaymentAttemptEvent.Succeeded, is PaymentAttemptEvent.NeedsReview -> OrderLockScope.COMMIT
        is PaymentAttemptEvent.Failed -> if (event.final) OrderLockScope.RELEASE else OrderLockScope.PAYMENT
        else -> OrderLockScope.PAYMENT
    }

    /**
     * The body of [applyEvent] on the caller's transaction, which holds `Locks.forOrder` of [locked]'s scope: locks the payment rows of the
     * order, lets the attempt machine decide, writes the attempt row, then does what the machine names (cancel the other open attempts,
     * record the payment on a `REVIEW` order, tell the order machine). Follow-up work for after the commit is added to [after].
     */
    suspend fun applyIn(
        conn: SqlConnection,
        locked: LockedOrder,
        attemptId: Long,
        event: PaymentAttemptEvent,
        facts: AttemptFacts,
        policy: ProviderMoneyPolicy,
        actor: OrderActor,
        after: MutableList<AfterCommit>
    ): AppliedEvent {
        val orderId = locked.order.id

        locks.children(conn, orderId, OrderChild.PAYMENT)

        val order = orders.getById(orderId, conn)!!
        val attempt = payments.getById(attemptId, conn) ?: throw NoSuchElementException("attempt $attemptId does not exist")
        val now = clock.now()
        val state = AttemptState(attempt.id, attempt.status, attempt.amount, attempt.currency, attempt.creditAmount, attempt.testMode)
        val tender = OrderTender(order.status, order.gatewayAmount, order.creditAmount, order.expiresAt != null && now >= order.expiresAt, order.paymentId)

        val decision = PaymentStateMachine.decide(state, tender, event, policy)

        if (decision !is PaymentTransition.Move) {
            // an event that does not apply still brings ids (06 section 9.2: only merged, never a status)
            if (decision is PaymentTransition.NoOp) mergeRefs(conn, attempt, facts.gatewayTransactionId, facts.gatewayRefs, onlyIfEmpty = attempt.status != PaymentStatus.CREATED)

            return AppliedEvent(attempt.status, order.status, changed = false, duplicate = attempt.duplicate)
        }

        // ---- the attempt row: status plus what the machine's effects and the facts say, one conditional update
        val sets = linkedMapOf<String, Any?>("status" to decision.to.name)
        val paid = event as? PaymentAttemptEvent.Succeeded

        facts.gatewayTransactionId?.let { sets["gatewayTransactionId"] = it }

        if (facts.gatewayRefs.isNotEmpty()) {
            val merged = attempt.gatewayRefs?.let { runCatching { JsonObject(it) }.getOrNull() } ?: JsonObject()

            facts.gatewayRefs.forEach { (k, v) -> merged.put(k, v) }
            sets["gatewayRefs"] = merged.encode()
        }

        facts.providerData?.let { sets["providerData"] = it }
        facts.startKind?.let { sets["startKind"] = it }
        facts.startPayload?.let { sets["startPayload"] = it }
        facts.startedAt?.let { sets["startedAt"] = it }
        facts.nextQueryAt?.let { sets["nextQueryAt"] = it }
        facts.expiresAt?.let { sets["expiresAt"] = it }
        facts.failureCode?.let { sets["failureCode"] = it }
        facts.failureMessage?.let { sets["failureMessage"] = it }
        facts.adminMessage?.let { sets["adminMessage"] = it }

        var duplicate = attempt.duplicate

        for (effect in decision.effects) {
            when (effect) {
                is PaymentEffect.RecordPaid -> {
                    sets["paidAmount"] = paid?.paidAmount
                    sets["paidCurrency"] = paid?.paidCurrency
                    sets["paidAt"] = now
                    facts.gatewayFee?.let { sets["gatewayFee"] = it }
                    facts.net?.let { sets["netAmount"] = it }
                    facts.settlementCurrency?.let { sets["settlementCurrency"] = it }
                    facts.settlementAmount?.let { sets["settlementAmount"] = it }
                    facts.installments?.let { sets["installments"] = it }
                    facts.methodDetail?.let { sets["methodDetail"] = it }
                }

                is PaymentEffect.ClearStartPayload -> sets["startPayload"] = null

                is PaymentEffect.StampClosed -> sets["closedAt"] = now

                is PaymentEffect.FlagDuplicate -> {
                    duplicate = true
                    sets["duplicate"] = true
                }

                is PaymentEffect.RecordReviewReason -> sets["adminMessage"] = (facts.adminMessage?.let { "$it; " } ?: "") + "REVIEW " + effect.reason.name

                else -> Unit
            }
        }

        if (!updateAttempt(conn, attemptId, sets, whereStatus = attempt.status)) throw com.panomc.plugins.market.db.tx.OrderChangedException(orderId, "attempt $attemptId moved under the lock")

        timeline(conn, orderId, attempt, decision.to, event, actor, paid, decision.effects.any { it is PaymentEffect.NotifyOrder }, duplicate)

        // ---- what the machine names besides the attempt row, in its order
        var orderStatus = order.status

        for (effect in decision.effects) {
            when (effect) {
                is PaymentEffect.CancelOtherOpenAttempts -> {
                    val closed = orderService.closeOpenAttempts(conn, orderId, PaymentStatus.CANCELLED, exceptAttemptId = attemptId)

                    if (closed.isNotEmpty()) after += AfterCommit.CancelAtGateway(closed)
                }

                is PaymentEffect.RecordPaymentOnOrder -> recordPaymentOnOrder(conn, order, effect.attemptId)

                is PaymentEffect.PanelAlert -> after += AfterCommit.PanelAlert(orderId, order.reviewReason)

                is PaymentEffect.NotifyOrder -> {
                    val moved = orderService.transition(conn, locked, effect.event, message = null)

                    after += moved.after

                    if (moved.moved) orderStatus = moved.to
                }

                else -> Unit
            }
        }

        return AppliedEvent(decision.to, orderStatus, changed = true, duplicate = duplicate)
    }

    private suspend fun timeline(
        conn: SqlConnection, orderId: Long, before: MarketPayment, to: PaymentStatus, event: PaymentAttemptEvent, actor: OrderActor,
        paid: PaymentAttemptEvent.Succeeded?, orderTold: Boolean, duplicate: Boolean
    ) {
        val type = when (to) {
            PaymentStatus.SUCCEEDED -> OrderEventType.PAYMENT_SUCCEEDED
            PaymentStatus.FAILED, PaymentStatus.EXPIRED -> OrderEventType.PAYMENT_FAILED
            PaymentStatus.CANCELLED -> OrderEventType.PAYMENT_CANCELLED
            PaymentStatus.REVIEW -> if (orderTold) null else OrderEventType.REVIEW_OPENED
            else -> null
        } ?: return

        val data = JsonObject().put("paymentId", before.id).put("providerId", before.providerId).put("from", before.status.name).put("to", to.name)

        if (paid != null) data.put("paidAmount", paid.paidAmount).put("paidCurrency", paid.paidCurrency).put("duplicate", duplicate)

        orderEvents.add(
            MarketOrderEvent(
                orderId = orderId, type = type, actorType = OrderActorType.valueOf(actor.name), data = data.encode(),
                createdAt = clock.now(), updatedAt = clock.now()
            ),
            conn
        )
    }

    /**
     * The order already waits for a human and money arrived on [attemptId] (06 section 9.4): when the order carries no payment, or the
     * order's own attempt was corrected, `paymentId` / `paidAmount` come from this attempt; otherwise what arrived is added to the
     * received total, so that a rejection refunds all of it.
     */
    private suspend fun recordPaymentOnOrder(conn: SqlConnection, order: MarketOrder, attemptId: Long) {
        val attempt = payments.getById(attemptId, conn) ?: return
        val received = attempt.paidAmount ?: 0L
        val fresh = orders.getById(order.id, conn)!!

        if (fresh.paymentId == null || fresh.paymentId == attemptId) {
            orderService.updateOrder(conn, order.id, linkedMapOf("paymentId" to attemptId, "paidAmount" to received))
        } else {
            orderService.updateOrder(conn, order.id, linkedMapOf("paidAmount" to fresh.paidAmount + received))
        }
    }

    // ================================================================================================ /pay (9.3)

    private class PayPlan(val attemptId: Long, val cancelled: List<MarketPayment>)

    /**
     * `POST /api/market/orders/:publicId/pay` (06 section 9.3, 07 section 6.4): a new attempt for a `PENDING` order, with the fee and the
     * credit part re-priced for the new method (items, discounts and shipping never change). [order] is the owner's order as
     * `OrderAccess` resolved it; everything is read again under the order lock. Returns the `PaymentStart`, or `null` when another request took
     * the attempt over before the provider answered.
     */
    suspend fun pay(order: MarketOrder, request: PayRequest, caller: PayCaller, sqlClient: SqlClient): JsonObject? {
        val after = ArrayList<AfterCommit>()
        val plan = db.txRestartingOnOrderChange { conn ->
            after.clear()

            locks.forOrder(conn, order.id, OrderLockScope.CREDIT) { locked -> payTx(conn, locked, request, caller) }
        }

        runAfter(after, sqlClient)

        return startAttempt(order.id, plan.attemptId, plan.cancelled, sqlClient)
    }

    private suspend fun payTx(conn: SqlConnection, locked: LockedOrder, request: PayRequest, caller: PayCaller): PayPlan {
        locks.children(conn, locked.order.id, OrderChild.PAYMENT)

        val order = orders.getById(locked.order.id, conn)!!
        val attempts = payments.getByOrderId(order.id, conn)
        val now = clock.now()

        // 1. the order can be paid again
        val blocked = attempts.any { it.status == PaymentStatus.PROCESSING || it.status == PaymentStatus.REVIEW || it.status == PaymentStatus.SUCCEEDED }

        if (order.status != OrderStatus.PENDING || blocked) throw OrderNotPayable()
        if (order.expiresAt != null && now >= order.expiresAt) throw OrderNotPayable()
        if (!OrderTimings.retryAllowed(now, hardCapOf(order, attempts, conn))) throw OrderNotPayable()

        val methodId = request.paymentMethodId?.trim()?.takeIf { it.isNotEmpty() } ?: throw BadRequest()
        val fullCredit = methodId == MethodInput.CREDITS

        // 2. orders priced by a gateway, subscription orders and full-credit orders keep their provider
        if (methodId == MethodInput.FREE) throw PaymentMethodUnavailable(METHOD_NOT_OFFERED)
        if ((order.pricingMode != PricingMode.MARKET || order.subscriptionId != null) && methodId != order.paymentMethodId) throw PaymentMethodUnavailable(METHOD_LOCKED)

        // the method: enabled, configured, registered, in test mode only for SET / PAY holders (PP-7), guests only where it takes them
        val target: Resolved? = if (fullCredit) null else resolve(methodId, conn)?.takeIf { it.configured && it.row?.enabled == true }

        if (!fullCredit && target == null) throw PaymentMethodUnavailable(METHOD_NOT_OFFERED)

        if (target != null) {
            if (target.testMode && !caller.canUseTestMode) throw PaymentMethodUnavailable(TEST_MODE)
            if (!target.caps.guests && order.userId == null) throw PaymentMethodUnavailable(GUESTS_NOT_SUPPORTED)
        }

        // 3 and 4. the credit part and the new fee, by the pure re-tender
        val items = orderItems.getByOrderIds(listOf(order.id), conn)
        val frozen = frozenOf(order, items, conn)
        val input = if (fullCredit) creditsInput() else methodInputOf(target!!)
        val tender = try {
            PricingEngine.retender(frozen, TenderInput(request.useCredits, input, strict = true))
        } catch (e: PricingException) {
            throw BadRequest()
        }

        if (tender.messages.any { it.code == PricingCode.INSUFFICIENT_CREDITS }) {
            throw InsufficientCredits(money(frozen.creditBalance), if (fullCredit) null else money(tender.credits?.maxApplicable ?: 0L))
        }

        tender.unavailable?.let { throw PaymentMethodUnavailable(it.name) }

        val newMethodId = tender.paymentMethodId ?: throw PaymentMethodUnavailable(METHOD_NOT_OFFERED)
        val chosen: Resolved? = if (newMethodId == methodId) target else resolve(newMethodId, conn)

        // eligibility is a pure question of the provider (02 section 5)
        if (target != null && tender.gatewayAmount > 0) {
            val eligibility = try {
                target.provider.checkEligibility(contexts.create(target.provider, target.settings, target.testMode), snapshotOf(order, items, tender))
            } catch (e: Exception) {
                null
            }

            if (eligibility == null || !eligibility.eligible) throw PaymentMethodUnavailable(PROVIDER_INELIGIBLE)
        }

        // 5. billing info and the buyer fields the provider requires
        val c = config()
        val raw = request.billingInfo ?: order.billingInfo?.let { runCatching { JsonObject(it) }.getOrNull() }
        val required = RequiredBuyerFields.of(
            c.billingInfoMode, chosen?.caps?.requiredBuyerFields.orEmpty(),
            BillingSnapshot.cleanText(raw?.getValue("type")).equals("COMPANY", ignoreCase = true), BillingSnapshot.cleanText(raw?.getValue("country")), order.requiresShipping
        )
        val have = buildSet {
            if (order.email != null) add("email")
            if (order.shippingAddress != null) add("shippingAddress")
        }
        val billing = when (val r = BillingSnapshot.check(raw, c.billingInfoMode, required, have)) {
            is BillingSnapshot.Result.Invalid -> throw BuyerInfoRequired(r.fields)
            is BillingSnapshot.Result.Valid -> r.json
        }

        // the credit part moves first: a ledger that cannot post stops the whole retender
        val creditChanged = tender.creditAmount != order.creditAmount

        if (creditChanged) orderService.retenderCredits(conn, order, tender.creditAmount)

        // 4. fee, VAT, total, credit value, gateway amount, method, label (five money columns plus the method)
        val label = labelOf(newMethodId, chosen, order)

        orderService.updateOrder(
            conn, order.id,
            linkedMapOf(
                "paymentFee" to tender.paymentFee, "paymentFeeVatPercent" to tender.paymentFeeVatPercent, "paymentFeeVatAmount" to tender.paymentFeeVatAmount,
                "vatTotal" to tender.vatTotal, "totalPrice" to tender.total, "creditAmount" to tender.creditAmount, "creditValue" to tender.creditValue,
                "gatewayAmount" to tender.gatewayAmount, "paymentMethodId" to newMethodId, "paymentLabel" to label, "billingInfo" to billing?.encode()
            )
        )

        // 6. the open attempts are cancelled before the new one exists (the gateway is told after the commit, before the new start)
        val cancelled = orderService.closeOpenAttempts(conn, order.id, PaymentStatus.CANCELLED)

        // 7. the new attempt carries the new tender
        val fresh = orders.getById(order.id, conn)!!
        val window = OrderTimings.providerWindowMs(newMethodId, chosen?.caps?.paymentWindowMinutes, timing())
        val hardCap = hardCapOf(order, attempts, conn)
        val expiresAt = OrderTimings.attemptExpiresAt(now, window, hardCap)
        val testMode = chosen?.testMode ?: c.testMode
        val created = orderService.addAttempt(
            conn, fresh, AttemptDraft(newMethodId, label, expiresAt, testMode, caller.clientIp, caller.userAgent?.take(255)),
            JsonObject().put("retender", true).put("previousCreditAmount", order.creditAmount).put("creditAmount", tender.creditAmount)
                .put("previousTotal", order.totalPrice).put("total", tender.total)
        )

        orderService.updateOrder(conn, order.id, linkedMapOf("expiresAt" to OrderTimings.orderExpiresAtAfterAttempt(fresh.expiresAt, expiresAt)))

        return PayPlan(created.id, cancelled)
    }

    private fun labelOf(methodId: String, resolved: Resolved?, order: MarketOrder): String {
        val locale = order.locale ?: site().defaultLocale

        if (resolved == null) return if (methodId == MethodInput.CREDITS) "credits" else methodId

        return resolved.row?.customLabel?.takeIf { it.isNotBlank() } ?: resolved.provider.descriptor.displayName.resolve(locale)
    }

    private fun money(amount: Long): Double = amount / 100.0

    private fun creditsInput() = MethodInput(
        id = MethodInput.CREDITS, feeMode = com.panomc.plugins.market.db.model.PaymentFeeMode.NONE, feePercent = 0, feeFixed = 0, minAmount = null, maxAmount = null,
        adminCurrencies = null, providerCurrencies = null, providerMin = null, providerMax = null, mixedCredit = true,
        priceAuthority = com.panomc.plugins.market.spi.payment.PriceAuthority.MARKET, physicalGoods = true
    )

    private fun methodInputOf(r: Resolved): MethodInput {
        val row = r.row

        return MethodInput(
            id = r.provider.id, feeMode = row?.feeMode ?: com.panomc.plugins.market.db.model.PaymentFeeMode.NONE, feePercent = row?.feePercent ?: 0, feeFixed = row?.feeFixed ?: 0,
            minAmount = row?.minAmount, maxAmount = row?.maxAmount, adminCurrencies = row?.currencies?.let { stringSet(it) },
            providerCurrencies = r.caps.currencies?.map { it.uppercase() }?.toSet(), providerMin = r.caps.minAmount, providerMax = r.caps.maxAmount,
            mixedCredit = r.caps.mixedCredit, priceAuthority = r.caps.priceAuthority, physicalGoods = r.caps.physicalGoods
        )
    }

    private fun stringSet(raw: String): Set<String>? {
        val array = runCatching { JsonArray(raw) }.getOrNull() ?: return null

        return array.mapNotNull { (it as? String)?.trim()?.uppercase() }.toSet()
    }

    private fun snapshotOf(order: MarketOrder, items: List<MarketOrderItem>, tender: com.panomc.plugins.market.core.pricing.TenderBreakdown): CheckoutSnapshot {
        val base = spiSnapshot(order, items)
        val currency = order.currency

        return CheckoutSnapshot(
            order = SpiOrderSnapshot(
                base.id, base.publicId, base.description, base.currency, base.lines, base.subtotal, base.discount, base.shipping, Money(tender.paymentFee, currency),
                Money(tender.vatTotal, currency), Money(tender.total, currency), Money(tender.creditValue, currency), base.requiresShipping, base.recipientUsername, base.gift, base.pricingMode
            ),
            buyer = buyerOf(order, null, null, order.billingInfo?.let { runCatching { JsonObject(it) }.getOrNull() }, order.locale ?: site().defaultLocale),
            subscription = null, hasPanoPriceModifiers = order.discountTotal > 0 || tender.creditValue > 0 || tender.paymentFee > 0
        )
    }

    /** The pending order as the pure re-tender sees it (05 section 9.6): the figures frozen at O1, what the buyer can spend now, the store's credit rules. */
    private suspend fun frozenOf(order: MarketOrder, items: List<MarketOrderItem>, sqlClient: SqlClient): FrozenOrder {
        val c = config()
        val rates = currencyRates.getAll(sqlClient).filter { it.rate.signum() > 0 }.associate { it.currency to it.rate }
        val pricing = marketPricingConfig(c, rates)
        val held = if (order.reservationState == ReservationState.HELD) order.creditAmount else 0L
        val balance = order.userId?.let { creditAccounts.getByUserId(it, sqlClient)?.balance } ?: 0L
        val roots = items.filter { it.kind != OrderItemKind.BUNDLE_CHILD }
        val creditable = roots.isNotEmpty() && roots.all { it.creditUnitPrice != null }
        val creditItems = if (creditable) roots.sumOf { Math.multiplyExact(it.creditUnitPrice!!, it.quantity.toLong()) } else null
        val profile = when (order.source) {
            OrderSource.PANEL -> PricingProfile.PANEL
            OrderSource.RENEWAL -> PricingProfile.RENEWAL
            OrderSource.GIFT_CODE -> PricingProfile.GIFT_CODE
            OrderSource.INGAME -> PricingProfile.INGAME
            else -> PricingProfile.STOREFRONT
        }

        return FrozenOrder(
            conversions = Conversions(order.baseCurrency, order.currency, order.fxRate, pricing.creditValue, pricing.removeCents, order.displayCurrency, order.displayRate),
            pricingMode = com.panomc.plugins.market.core.pricing.PricingMode.valueOf(order.pricingMode.name), profile = profile,
            itemsTotal = order.totalPrice - order.shippingTotal - order.paymentFee, itemsVat = order.vatTotal - order.shippingVatAmount - order.paymentFeeVatAmount,
            shippingTotal = order.shippingTotal, shippingVat = order.shippingVatAmount, requiresShipping = order.requiresShipping, vatBp = pricing.vatBp,
            currentMethodId = order.paymentMethodId, creditAmount = order.creditAmount, creditValue = order.creditValue, creditBalance = balance + held,
            loggedIn = order.userId != null, creditsEnabled = pricing.creditsEnabled, allowMixedCreditPayment = pricing.allowMixedCreditPayment,
            onlyAcceptCredits = pricing.onlyAcceptCredits, mixedCreditCart = items.none { it.kind == OrderItemKind.CREDIT_TOPUP || (it.creditAmount ?: 0) > 0 } && order.subscriptionId == null,
            creditItemsTotal = creditItems, renewalFee = if (order.source == OrderSource.RENEWAL) order.paymentFee else null, rates = rates
        )
    }

    /** `H = createdAt + max(24 h, W(first provider))`: retries cannot keep stock reserved beyond it (06 section 9.1). */
    private suspend fun hardCapOf(order: MarketOrder, attempts: List<MarketPayment>, sqlClient: SqlClient): Long {
        val first = attempts.firstOrNull()
        val minutes = first?.let { resolve(it.providerId, sqlClient)?.caps?.paymentWindowMinutes }

        return OrderTimings.hardCap(order.createdAt, OrderTimings.providerWindowMs(first?.providerId ?: OrderTimings.BANK_TRANSFER_PROVIDER, minutes, timing()))
    }

    // ================================================================================================= continue

    private val attemptLocks = ConcurrentHashMap<Long, Mutex>()

    /**
     * `POST /orders/:publicId/payment/continue` (06 section 9.3): the second step of an embedded form. The newest attempt must be `PENDING`
     * with `startKind = EMBEDDED` (else 409 `ORDER_NOT_PAYABLE`); the provider is called under the attempt lock (30 s) and the result is
     * stored like a start. A provider error answers 502 and leaves the attempt as it is, so the buyer can enter the step again.
     */
    suspend fun continuePayment(order: MarketOrder, values: JsonObject, caller: PayCaller, sqlClient: SqlClient): JsonObject? {
        val attempt = payments.getByOrderId(order.id, sqlClient).lastOrNull()

        if (order.status != OrderStatus.PENDING || attempt == null || attempt.status != PaymentStatus.PENDING || attempt.startKind != "EMBEDDED") throw OrderNotPayable()

        val resolved = resolve(attempt.providerId, sqlClient) ?: throw OrderNotPayable()
        val publicId = order.publicId ?: ""
        val ctx = contexts.create(resolved.provider, resolved.settings, attempt.testMode)
        val request = ContinuePaymentRequest(
            attemptView(attempt, publicId), values, buyerOf(order, attempt.clientIp, attempt.userAgent, order.billingInfo?.let { runCatching { JsonObject(it) }.getOrNull() }, order.locale ?: site().defaultLocale),
            urlsFor(attempt, publicId, resolved.provider.id)
        )
        val mutex = attemptLocks.computeIfAbsent(attempt.id) { Mutex() }

        val result = mutex.withLock {
            try {
                withTimeout(startTimeoutMs) { resolved.provider.continuePayment(ctx, request) }
            } catch (e: TimeoutCancellationException) {
                throw PaymentProviderError(ProviderErrorCode.GATEWAY_UNREACHABLE.name, orderService.ownerView(order, sqlClient), order.accessToken)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ProviderException) {
                throw PaymentProviderError(e.code.name, orderService.ownerView(order, sqlClient), order.accessToken)
            } catch (e: Throwable) {
                throw PaymentProviderError(ProviderErrorCode.INTERNAL.name, orderService.ownerView(order, sqlClient), order.accessToken)
            }
        }

        return storeResult(order, attempt, resolved, result, continued = true)
    }

    // ================================================================================================= cancel (O7)

    /**
     * `POST /orders/:publicId/cancel` by the owner (O7): `PENDING` without an attempt in `PROCESSING` (else 409 `ORDER_NOT_CANCELLABLE`);
     * a renewal order is cancelled by cancelling its subscription, never here; an already `CANCELLED` order answers 200. The stock, the codes
     * and the credit hold go back, the open attempts become `CANCELLED`, the gateway is told after the commit.
     */
    suspend fun cancel(order: MarketOrder, sqlClient: SqlClient): OrderStatus {
        if (order.source == OrderSource.RENEWAL && order.status != OrderStatus.CANCELLED) throw OrderNotCancellable()

        val after = ArrayList<AfterCommit>()
        val moved = db.txRestartingOnOrderChange { conn ->
            after.clear()

            locks.forOrder(conn, order.id, OrderLockScope.RELEASE) { locked ->
                locks.children(conn, order.id, OrderChild.PAYMENT)

                val result = orderService.transition(conn, locked, OrderEvent.Cancel(OrderActor.BUYER), actorUserId = order.userId)

                after += result.after

                when (val decision = result.decision) {
                    is com.panomc.plugins.market.core.order.OrderTransition.Rejected -> throw OrderNotCancellable()
                    else -> result.to
                }
            }
        }

        runAfter(after, sqlClient)

        return moved
    }

    // ============================================================================================ status and view

    /**
     * `GET /orders/:publicId/status` (06 section 10.4): the state the order page polls. For the owner of a `PENDING` order whose newest attempt is
     * open and whose provider has `statusQuery`, one provider query is claimed (at most one every 10 s per attempt); the response waits at most
     * [statusWaitMs] for it and then answers with the current state. `unknown()` and `unsupported()` change nothing.
     */
    suspend fun status(order: MarketOrder, owner: Boolean, sqlClient: SqlClient): JsonObject {
        if (owner && order.status == OrderStatus.PENDING) queryOnce(order, sqlClient)

        val current = orders.getById(order.id, sqlClient) ?: order
        val newest = payments.getByOrderId(order.id, sqlClient).lastOrNull()

        return JsonObject()
            .put("status", current.status.name).put("paymentStatus", newest?.status?.name).put("fulfillmentStatus", current.fulfillmentStatus.name)
            .put("shippingStatus", current.shippingStatus.name).put("updatedAt", current.updatedAt)
    }

    private suspend fun queryOnce(order: MarketOrder, sqlClient: SqlClient) {
        val attempt = payments.getByOrderId(order.id, sqlClient).lastOrNull() ?: return

        if (attempt.status != PaymentStatus.CREATED && attempt.status != PaymentStatus.PENDING && attempt.status != PaymentStatus.PROCESSING) return

        val resolved = resolve(attempt.providerId, sqlClient) ?: return

        if (!resolved.caps.statusQuery) return

        val now = clock.now()
        val claimed = sqlClient.preparedQuery(
            "UPDATE ${table("market_payment")} SET `lastQueriedAt` = ?, `queryCount` = `queryCount` + 1 WHERE `id` = ? AND (`lastQueriedAt` IS NULL OR `lastQueriedAt` <= ?)"
        ).execute(Tuple.of(now, attempt.id, now - STATUS_QUERY_MIN_GAP_MS)).coAwait().rowCount()

        if (claimed != 1) return

        val ctx = contexts.create(resolved.provider, resolved.settings, attempt.testMode)
        val events = try {
            withTimeoutOrNull(statusWaitMs) {
                resolved.provider.queryPayment(ctx, QueryPaymentRequest(attemptView(attempt, order.publicId ?: ""), QueryReason.RETURN_PAGE)).events
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.warn("status query of attempt {} failed: {}", attempt.id, e.javaClass.simpleName)

            null
        } ?: return

        for (event in events) {
            val mapped = PaymentEventMapper.attemptEvent(event) ?: continue

            applyEvent(order.id, attempt.id, mapped, AttemptFacts.of(event, cipher), OrderActor.GATEWAY, resolved.policy)
        }
    }

    /** The `OrderView` of [order] for [role]: the owner view with the stored start and the retry data, cut to the role's allow-list (11 section 5.2). */
    suspend fun viewFor(order: MarketOrder, role: com.panomc.plugins.market.routes.api.OrderRole, caller: PayCaller, sqlClient: SqlClient): JsonObject {
        val attempts = payments.getByOrderId(order.id, sqlClient)
        val newest = attempts.lastOrNull()
        val start = newest?.takeIf { it.status == PaymentStatus.PENDING || it.status == PaymentStatus.PROCESSING }?.let { servedStart(it) }
        val owner = if (role == com.panomc.plugins.market.routes.api.OrderRole.OWNER) {
            orderService.ownerView(order, sqlClient, start, retryView(order, attempts, caller, sqlClient))
        } else {
            orderService.ownerView(order, sqlClient, start)
        }

        return com.panomc.plugins.market.routes.api.OrderViews.forRole(owner, role)
    }

    private suspend fun retryView(order: MarketOrder, attempts: List<MarketPayment>, caller: PayCaller, sqlClient: SqlClient): OrderService.RetryView {
        val blocked = attempts.any { it.status == PaymentStatus.PROCESSING || it.status == PaymentStatus.REVIEW || it.status == PaymentStatus.SUCCEEDED }
        val now = clock.now()
        val canRetry = order.status == OrderStatus.PENDING && !blocked && (order.expiresAt == null || now < order.expiresAt) &&
            OrderTimings.retryAllowed(now, hardCapOf(order, attempts, sqlClient))

        if (!canRetry) return OrderService.RetryView(false)

        val items = orderItems.getByOrderIds(listOf(order.id), sqlClient)
        val frozen = frozenOf(order, items, sqlClient)
        val locale = order.locale ?: site().defaultLocale
        val options = JsonArray()
        var maxApplicable = 0L

        for (row in methods.getAll(sqlClient).filter { it.enabled }.sortedWith(compareBy({ it.position }, { it.methodId }))) {
            if (row.methodId == MethodInput.CREDITS || row.methodId == MethodInput.FREE) continue

            val resolved = resolve(row.methodId, sqlClient)?.takeIf { it.configured } ?: continue
            val tender = try {
                PricingEngine.retender(frozen, TenderInput(null, methodInputOf(resolved)))
            } catch (e: PricingException) {
                null
            }
            var reason = tender?.unavailable?.name

            if (reason == null && resolved.testMode && !caller.canUseTestMode) reason = TEST_MODE
            if (reason == null && !resolved.caps.guests && order.userId == null) reason = GUESTS_NOT_SUPPORTED

            maxApplicable = maxOf(maxApplicable, tender?.credits?.maxApplicable ?: 0L)

            val descriptor = resolved.provider.descriptor

            options.add(
                PaymentMethodOption(
                    id = row.methodId, label = row.customLabel?.takeIf { it.isNotBlank() } ?: descriptor.displayName.resolve(locale),
                    description = row.customDescription?.takeIf { it.isNotBlank() } ?: descriptor.description.resolve(locale), hint = descriptor.checkoutHint?.resolve(locale),
                    icon = descriptor.icon, logoUrl = if (descriptor.logo != null) "/api/market/payment-providers/${row.methodId}/logo" else null, color = descriptor.color,
                    feeAmount = tender?.paymentFee ?: 0L, available = reason == null, unavailableReason = reason, providerCode = null,
                    pricing = resolved.caps.priceAuthority.let { if (it == com.panomc.plugins.market.spi.payment.PriceAuthority.MARKET) "MARKET" else it.name },
                    recurring = null, testMode = resolved.testMode, notices = descriptor.storefrontNotices.map { it.label.resolve(locale) to it.url },
                    requiredBuyerFields = resolved.caps.requiredBuyerFields.map { it.name }
                ).toJson()
            )
        }

        val credits = if (config().creditsEnabled && order.userId != null) {
            JsonObject().put("enabled", true).put("name", config().creditName.ifBlank { "credits" }).put("balance", money(maxOf(0L, frozen.creditBalance))).put("maxApplicable", money(maxApplicable))
        } else {
            null
        }

        return OrderService.RetryView(true, options, credits)
    }

    companion object {
        const val START_TIMEOUT_MS = 30_000L
        const val CANCEL_TIMEOUT_MS = 10_000L
        const val STATUS_WAIT_MS = 8_000L
        const val STATUS_QUERY_FIRST_MS = 60_000L
        const val STATUS_QUERY_MIN_GAP_MS = 10_000L
        const val ADMIN_MESSAGE_MAX = 1000

        /** The generic text key a failed start stores for the buyer (the gateway's own text is the admin's, 02 section 6). */
        const val START_FAILED_TEXT = "payment.start-failed"

        const val METHOD_NOT_OFFERED = "METHOD_NOT_OFFERED"
        const val METHOD_LOCKED = "METHOD_LOCKED"
        const val TEST_MODE = "TEST_MODE"
        const val GUESTS_NOT_SUPPORTED = "GUESTS_NOT_SUPPORTED"
        const val PROVIDER_INELIGIBLE = "PROVIDER_INELIGIBLE"

        private val logger = LoggerFactory.getLogger(PaymentService::class.java)
    }
}
