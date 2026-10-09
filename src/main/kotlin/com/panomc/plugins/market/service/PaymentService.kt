package com.panomc.plugins.market.service

import com.panomc.plugins.market.util.StoreLinks
import com.panomc.platform.error.BadRequest
import com.panomc.plugins.market.core.order.BillingSnapshot
import com.panomc.plugins.market.core.order.OrderActor
import com.panomc.plugins.market.routes.api.payment.AttemptLocks
import com.panomc.plugins.market.routes.api.payment.PaymentEventApplier
import com.panomc.plugins.market.routes.api.payment.resolveAttemptTarget
import com.panomc.plugins.market.routes.api.payment.withReceived
import com.panomc.plugins.market.core.order.OrderEvent
import com.panomc.plugins.market.core.subscription.ModeOffer
import com.panomc.plugins.market.core.subscription.ModeResolver
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
import com.panomc.plugins.market.db.dao.MarketEntitlementDao
import com.panomc.plugins.market.db.dao.MarketOrderDao
import com.panomc.plugins.market.db.dao.MarketOrderEventDao
import com.panomc.plugins.market.db.dao.MarketOrderItemDao
import com.panomc.plugins.market.db.dao.MarketPaymentDao
import com.panomc.plugins.market.db.dao.MarketPaymentMethodDao
import com.panomc.plugins.market.db.dao.MarketProductDao
import com.panomc.plugins.market.db.dao.isDuplicateKey
import com.panomc.plugins.market.db.model.BillingMode
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
import com.panomc.plugins.market.spi.payment.CancelSubscriptionRequest
import com.panomc.plugins.market.spi.payment.CancelSubscriptionResult
import com.panomc.plugins.market.spi.payment.CheckoutSnapshot
import com.panomc.plugins.market.spi.payment.ContinuePaymentRequest
import com.panomc.plugins.market.spi.payment.GatewaySubscriptionState
import com.panomc.plugins.market.spi.payment.StoredPaymentMethod
import com.panomc.plugins.market.spi.payment.SubscriptionPlan
import com.panomc.plugins.market.spi.payment.OrderLine
import com.panomc.plugins.market.spi.payment.PaymentAttemptView
import com.panomc.plugins.market.spi.payment.PaymentCapabilities
import com.panomc.plugins.market.spi.payment.PaymentEvent
import com.panomc.plugins.market.spi.payment.PaymentTarget
import com.panomc.plugins.market.spi.payment.PaymentContext
import com.panomc.plugins.market.spi.payment.PaymentProvider
import com.panomc.plugins.market.spi.payment.QueryPaymentRequest
import com.panomc.plugins.market.spi.payment.QueryReason
import com.panomc.plugins.market.spi.payment.RefundRequest
import com.panomc.plugins.market.spi.payment.RefundResult
import com.panomc.plugins.market.spi.payment.RefundSupport
import com.panomc.plugins.market.spi.payment.ReviewReason
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
import com.panomc.plugins.market.util.MarketPaths

/** A panel alert for an order that waits for a human (06 section 11, O3 / O9). The notification itself is the platform's; this is the seam. */
fun interface PanelAlerts {
    suspend fun reviewOpened(orderId: Long, reason: String?)

    companion object {
        private val logger = LoggerFactory.getLogger(PanelAlerts::class.java)

        val LOG_ONLY = PanelAlerts { orderId, reason -> logger.warn("order {} waits for review ({})", orderId, reason) }
    }
}

/**
 * Why a verified payment does not complete the order (06 section 9.4, first row): O2 is diverted to O3. [reason] is the order's `reviewReason`,
 * [note] the text of the `STATUS_CHANGED` row of the timeline (06 section 6.4: "recipient limit"). [refundAtOnce]: money the store may not keep (09 section 8.5,
 * the renewal of a closed subscription) is refunded in the same transaction when `autoRefundDuplicatePayments` is on and the provider can refund (O3, then O5
 * with a `SYSTEM` refund); otherwise the order waits in `REVIEW` for the admin like any other diversion.
 */
class PaidDiversion(val reason: ReviewReason, val note: String, val refundAtOnce: Boolean = false)

/**
 * A check that runs under the `COMMIT` locks right before an order that is `PENDING` is told `Paid` (O2) by a payment event; the first guard
 * that answers a [PaidDiversion] turns O2 into O3 (`REVIEW`): nothing is captured, committed or delivered, the money is recorded on the order.
 * `locked` holds the order, its items, and the product / credit rows of 06 section 13.2 (scope `COMMIT` or wider), so a guard may count
 * against them without taking more locks. [PaymentService] always runs [RecipientLimitGuard] first, then the guards it was given.
 *
 * Open seams: MK-151 adds the blocked-buyer check (06 section 6.8, `reviewReason = BLOCKED_BUYER`; the SPI `ReviewReason` has no such value
 * yet, see the MK-076 evidence) and MK-121 the late renewal of a terminal subscription (09 section 8.5, `LATE`).
 */
fun interface PaidGuard {
    suspend fun divert(conn: SqlConnection, locked: LockedOrder, order: MarketOrder): PaidDiversion?
}

/**
 * The recipient limit of a gift (06 section 6.4, review-log M-6). An order whose payer is not its recipient is left out of the recipient's
 * `limitPerPlayer` and cooldown while it is pending (`MarketOrderDao.usageByProduct`), so nobody can use up a victim's allowance with unpaid
 * gifts. That is safe only because this check runs when the gift is paid: under the product locks it counts what the recipient holds or has
 * on hold, adds this order's own units, and sends an order that would exceed the limit, or that falls inside the cooldown, to
 * `REVIEW (OTHER)` with the note "recipient limit". An order the buyer places for themselves was judged at checkout under the same locks and
 * is not judged again. A `TIMED` product the recipient already owns is an extension, never a second holding: the limit does not apply to it,
 * the cooldown does.
 */
internal class RecipientLimitGuard(
    private val orders: MarketOrderDao,
    private val products: MarketProductDao,
    private val entitlements: MarketEntitlementDao,
    private val clock: Clock
) : PaidGuard {
    override suspend fun divert(conn: SqlConnection, locked: LockedOrder, order: MarketOrder): PaidDiversion? {
        if (order.buyerKey == order.recipientKey) return null

        // every unit of the order, bundle children included, per product: the units `usageByProduct` counts for a recipient
        val units = HashMap<Long, Long>()

        for (item in locked.items) {
            val productId = item.productId ?: continue

            units.merge(productId, (item.quantity - item.refundedQuantity).toLong(), Long::plus)
        }

        if (units.isEmpty()) return null

        val rules = products.getByIds(units.keys.toList(), conn).filter { it.limitPerPlayer != null || (it.cooldownSeconds ?: 0L) > 0L }

        if (rules.isEmpty()) return null

        val keys = recipientKeys(order)
        val usage = orders.usageByProduct(keys, rules.map { it.id }, conn)
        val now = clock.now()
        var owned: Set<Long>? = null

        suspend fun ownsActive(productId: Long): Boolean {
            val ids = owned ?: keys.flatMap { entitlements.getActiveByOwner(it, now, conn) }.map { it.productId }.toSet().also { owned = it }

            return productId in ids
        }

        for (product in rules.sortedBy { it.id }) {
            val limit = product.limitPerPlayer

            if (limit != null) {
                val used = usage[product.id]?.used ?: 0L
                val extension = product.billingMode == BillingMode.TIMED && ownsActive(product.id)

                if (!extension && used + (units[product.id] ?: 0L) > limit) return PaidDiversion(ReviewReason.OTHER, NOTE)
            }

            val seconds = product.cooldownSeconds ?: 0L
            val last = usage[product.id]?.lastOrderAt

            if (seconds > 0 && last != null && now < last + seconds * 1000) return PaidDiversion(ReviewReason.OTHER, NOTE)
        }

        return null
    }

    companion object {
        const val NOTE = "recipient limit"

        /** `K` of 06 section 6.2: the recipient's key and, for a registered player, the `g:<name>` twin of rows written before the rewrite job. */
        fun recipientKeys(order: MarketOrder): List<String> {
            val guestKey = "g:${order.recipientUsername.lowercase(java.util.Locale.ROOT)}"

            return if (order.recipientUsername.isBlank() || order.recipientKey == guestKey) listOf(order.recipientKey) else listOf(order.recipientKey, guestKey)
        }
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
    val adminMessage: String? = null,
    /**
     * What a `NeedsReview` event says the gateway received (`PaymentEvent.NeedsReview.received`: a partial, an excess, a late or a wrong-asset
     * payment). It becomes the attempt's `paidAmount` / `paidCurrency` when the attempt moves to `REVIEW`, so that the order records it and a
     * rejection with a refund returns it (06 section 9.4). `null` when the event states no amount.
     */
    val receivedAmount: Long? = null,
    val receivedCurrency: String? = null,
    /**
     * What a `Succeeded` carries for a subscription (09 section 4.4): the gateway's subscription (`GATEWAY_MANAGED`) and the stored payment method
     * (`MERCHANT_INITIATED`). Not an attempt column: [PaymentSubscriptions.onPaid] writes it onto the pending subscription row in the same
     * transaction, where the activation of O2 / O4 finds it even when an admin accepts the payment later.
     */
    val subscription: GatewaySubscriptionState? = null,
    val storedMethod: StoredPaymentMethod? = null,
    /**
     * How `SubscriptionJob` classifies a failed merchant-initiated charge when the provider call itself failed (09 section 8.3): [RECURRING_TECHNICAL] (the
     * charge never reached a decision: configuration, authentication, unreachable, rate limited, invalid request) or [RECURRING_UNSUPPORTED] (the provider
     * cannot charge a stored method). `null` for everything a gateway reported, a decline included.
     */
    val recurringOutcome: String? = null
) {
    companion object {
        const val RECURRING_TECHNICAL = "TECHNICAL"
        const val RECURRING_UNSUPPORTED = "UNSUPPORTED"

        val NONE = AttemptFacts()

        /** The facts of a provider event (the encrypted `providerData` needs [cipher]). */
        fun of(event: PaymentEvent, cipher: SecretCipher): AttemptFacts {
            val succeeded = event as? PaymentEvent.Succeeded
            val received = (event as? PaymentEvent.NeedsReview)?.received
            val failed = event as? PaymentEvent.Failed

            return AttemptFacts(
                gatewayTransactionId = event.gatewayTransactionId, gatewayRefs = event.gatewayRefs,
                providerData = event.providerData?.let { cipher.encrypt(it.encode()) },
                gatewayFee = succeeded?.gatewayFee?.amount, net = succeeded?.net?.amount, settlementCurrency = succeeded?.settlementCurrency,
                settlementAmount = succeeded?.settlementAmount, installments = succeeded?.installments, methodDetail = succeeded?.methodDetail?.take(PaymentService.METHOD_DETAIL_MAX),
                // a gateway failure keeps its code (17 section 5.5: `card_declined` surfaces as `failureCode`); its text is the admin's, the buyer gets market's generic key
                failureCode = failed?.code?.take(PaymentService.FAILURE_CODE_MAX), failureMessage = failed?.let { PaymentService.PAYMENT_FAILED_TEXT },
                adminMessage = listOfNotNull(event.note, failed?.message).filter { it.isNotBlank() }.joinToString("; ").takeIf { it.isNotEmpty() }?.take(PaymentService.ADMIN_MESSAGE_MAX),
                receivedAmount = received?.amount, receivedCurrency = received?.currency,
                subscription = succeeded?.subscription, storedMethod = succeeded?.storedMethod
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
    /** The limits and cooldown of the products and the entitlements of a recipient, for the check of a paid gift ([RecipientLimitGuard]). */
    products: MarketProductDao,
    entitlements: MarketEntitlementDao,
    private val alerts: PanelAlerts = PanelAlerts.LOG_ONLY,
    private val startTimeoutMs: Long = START_TIMEOUT_MS,
    private val cancelTimeoutMs: Long = CANCEL_TIMEOUT_MS,
    private val statusWaitMs: Long = STATUS_WAIT_MS,
    private val queryTimeoutMs: Long = QUERY_TIMEOUT_MS,
    private val refundTimeoutMs: Long = REFUND_TIMEOUT_MS,
    private val sanitizeHtml: (String) -> String = { HtmlSanitizer.sanitize(it) },
    /** Checks run after [RecipientLimitGuard] before an O2 (MK-151: blocked buyer, MK-121: late renewal). */
    extraPaidGuards: List<PaidGuard> = emptyList(),
    /**
     * The attempt locks of the plugin instance (MK-077): the provider calls of the status query, the reconcile query and `continue` run under the lock
     * of the attempt they are about, the same lock the inbound pipeline and `ctx.payments.withAttemptLock` take, so a `queryPayment` and a
     * `handleInbound` of one attempt never run side by side. Production passes the registry the pipeline uses; the default is a private one.
     */
    private val attemptLocks: AttemptLocks = AttemptLocks(),
    /** The subscription side of a payment (MK-121, 09 section 2): the plan a start carries, the gateway data of a success, the offer a method change makes. */
    private val subscriptionHooks: PaymentSubscriptions = PaymentSubscriptions.NONE,
    /** The mails of an attempt's transitions (MK-142, 12 section 4.1): bank transfer instructions and "order received", queued in the transition's transaction. */
    private val mails: PaymentMails = PaymentMails.NONE,
    /** The in-game purchase announcement of a paid order (08 section 8.1, 19 section 9): runs after the commit of the transition that stamped the order paid. */
    private val announcer: PaidOrderAnnouncer = PaidOrderAnnouncer.NONE,
    /** The pages of the front-end a gateway returns the buyer to (the URL map, doc 05 section 10.2); `null` = the default paths under the site address. */
    private val links: StoreLinks? = null
) : PaymentStarter {

    private val paidGuards: List<PaidGuard> = listOf(RecipientLimitGuard(orders, products, entitlements, clock)) + extraPaidGuards

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

    /** The capabilities [providerId] answers right now (settings decrypted), `null` for a provider that is not registered or threw while it described itself. */
    suspend fun capabilitiesOf(providerId: String, sqlClient: SqlClient): PaymentCapabilities? = resolve(providerId, sqlClient)?.caps

    /** The two answers that decide whether a duplicate payment on [providerId] is refunded automatically (`autoRefundDuplicatePayments`, refund support of the provider). */
    suspend fun duplicateRefundRule(sqlClient: SqlClient, providerId: String): DuplicateRefundRule =
        DuplicateRefundRule(config().autoRefundDuplicatePayments, resolve(providerId, sqlClient)?.caps?.refund.let { it != null && it != RefundSupport.NONE })

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

    /**
     * O2 of an order whose first attempt settles without a gateway (`free`, MK-113 gift redemption: 21 section 6 step 4) on the caller's transaction: the
     * attempt moves `CREATED` -> `SUCCEEDED` and the order machine runs O2 (entitlements, deliveries, credit grant) under the `COMMIT` lock set, so a failure rolls
     * the caller's rows back with it. No provider call is made (the built-in answers `Completed(Succeeded(0))` by definition, 02 section 12); anything else
     * returns `null` and the caller starts the attempt after its commit as every checkout does. The returned step runs after the caller's commit.
     */
    override suspend fun completeZeroIn(conn: SqlConnection, order: MarketOrder, attempt: MarketPayment): (suspend (SqlClient) -> Unit)? {
        if (attempt.providerId != OrderTimings.FREE_PROVIDER || attempt.amount != 0L || attempt.creditAmount != 0L) return null

        val resolved = resolve(attempt.providerId, conn) ?: return null
        val event = PaymentEvent.Succeeded(PaymentTarget.Attempt(attempt.id), Money(0, attempt.currency))
        val after = ArrayList<AfterCommit>()

        locks.forOrder(conn, order.id, OrderLockScope.COMMIT, cashback = config().cashbackPercent > 0) {
            locks.children(conn, order.id, OrderChild.PAYMENT)

            val now = clock.now()
            val facts = AttemptFacts.of(event, cipher).let { f -> AttemptFacts(f.gatewayTransactionId, f.gatewayRefs, f.providerData, startKind = "COMPLETED", startedAt = now) }
            val applied = applyIn(conn, it, attempt.id, PaymentEventMapper.attemptEvent(event)!!, facts, resolved.policy, OrderActor.SYSTEM, after)

            check(applied.orderStatus == OrderStatus.COMPLETED) { "the free order ${order.id} did not complete: ${applied.orderStatus}" }
        }

        return { client -> runAfter(after, client) }
    }

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
        val request = requestFor(order, items, attempt, superseded.firstOrNull(), resolved, subscriptionHooks.planFor(order, sqlClient))
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
     * A buyer-facing result for an attempt that moved on (an event won the race, `/pay` cancelled it) only merges the provider ids it lacks.
     *
     * A `Completed` is money the provider already collected, so it is never dropped: it is applied as a `Succeeded` event whatever state the
     * attempt is in now (06 section 9.2 step 3, section 9.4): a late success on a `CANCELLED` / `FAILED` / `EXPIRED` attempt is O2 when its tender
     * still equals the order's and `REVIEW (AMOUNT_MISMATCH)` when the buyer changed it, a `PROCESSING` attempt completes, a `SUCCEEDED` one only
     * merges ids. The built-ins `free` and `credits` are the exception: no real money moved, so the result of an attempt that is no longer
     * current is dropped like a buyer-facing one (the order was re-tendered, the credits are not captured for a superseded attempt).
     */
    private suspend fun storeResult(order: MarketOrder, attempt: MarketPayment, resolved: Resolved, result: StartPaymentResult, continued: Boolean): JsonObject? {
        val completed = result as? StartPaymentResult.Completed
        val scope = if (completed != null) OrderLockScope.COMMIT else OrderLockScope.PAYMENT
        val expect = if (continued) PaymentStatus.PENDING else PaymentStatus.CREATED
        val builtIn = resolved.provider.id == OrderTimings.FREE_PROVIDER || resolved.provider.id == OrderTimings.CREDITS_PROVIDER
        val after = ArrayList<AfterCommit>()

        val start = db.txRestartingOnOrderChange { conn ->
            after.clear()

            locks.forOrder(conn, order.id, scope, cashback = completed != null && config().cashbackPercent > 0) { locked ->
                locks.children(conn, order.id, OrderChild.PAYMENT)

                val current = payments.getById(attempt.id, conn) ?: throw NoSuchElementException("attempt ${attempt.id} does not exist")
                val now = clock.now()
                val awaited = current.status == expect

                if (completed != null && (awaited || !builtIn)) {
                    val facts = AttemptFacts.of(completed.event, cipher).let { f ->
                        AttemptFacts(
                            f.gatewayTransactionId ?: result.gatewayTransactionId, f.gatewayRefs + result.gatewayRefs, f.providerData, f.gatewayFee, f.net,
                            f.settlementCurrency, f.settlementAmount, f.installments, f.methodDetail, startKind = if (awaited) "COMPLETED" else null,
                            startedAt = if (awaited) now else null, adminMessage = f.adminMessage,
                            // a synchronous success carries the subscription / stored method of 09 section 4.4 exactly like an event does
                            subscription = f.subscription, storedMethod = f.storedMethod
                        )
                    }
                    val applied = applyIn(
                        conn, locked, attempt.id, PaymentEventMapper.attemptEvent(completed.event)!!, facts, resolved.policy,
                        if (builtIn) OrderActor.SYSTEM else OrderActor.GATEWAY, after
                    )

                    return@forOrder if (applied.orderStatus == OrderStatus.COMPLETED) JsonObject().put("kind", "COMPLETED") else null
                }

                if (!awaited) {
                    mergeRefs(conn, current, result.gatewayTransactionId, result.gatewayRefs, onlyIfEmpty = true)

                    return@forOrder null
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

                // 12 section 4.1: an attempt that starts with INSTRUCTIONS mails them to the payer, in this transaction (the stored payload is cleared when the attempt closes)
                if (json.getString("kind") == "INSTRUCTIONS") {
                    orders.getById(order.id, conn)?.let { fresh -> if (fresh.status == OrderStatus.PENDING) mails.instructions(conn, fresh, attempt, json) }
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

    private fun attemptPageOf(attempt: MarketPayment): String = MarketPaths.site("/payments/attempts/${attempt.token}/page")

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

    private fun requestFor(
        order: MarketOrder, items: List<MarketOrderItem>, attempt: MarketPayment, replaces: MarketPayment?, resolved: Resolved, plan: SubscriptionPlan?
    ): StartPaymentRequest {
        val snapshot = spiSnapshot(order, items)
        val billing = order.billingInfo?.let { runCatching { JsonObject(it) }.getOrNull() }
        val locale = order.locale ?: site().defaultLocale

        return StartPaymentRequest(
            attempt = AttemptRef(attempt.id, attempt.reference, attempt.token), amount = Money(attempt.amount, attempt.currency), order = snapshot,
            buyer = buyerOf(order, attempt.clientIp, attempt.userAgent, billing, locale), billing = addressOf(billing),
            shipping = addressOf(order.shippingAddress?.let { runCatching { JsonObject(it) }.getOrNull() }),
            subscription = plan, urls = urlsFor(attempt, order.publicId ?: "", resolved.provider.id), idempotencyKey = "pay:" + attempt.reference, locale = locale,
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
        val root = "$base${MarketPaths.site("/payments/$providerId")}"
        val pages = links ?: StoreLinks.ofBase(base)

        return AttemptUrls(
            success = "$root/return/${attempt.token}/success", cancel = "$root/return/${attempt.token}/cancel", pending = "$root/return/${attempt.token}/pending",
            result = "$root/return/${attempt.token}/result", notify = "$root/notify/${attempt.token}", orderPage = pages.orderPage(publicId, base)
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
            // 02 section 10 guarantee 3: a cancel is one of the calls serialised per attempt; the lock is reentrant, so a cancel that runs after a commit made
            // under this attempt's lock (a query, an inbound event) does not wait for itself. The attempt is read again inside it: the view carries the ids
            // a call that held the lock before this one attached.
            attemptLocks.with(attempt.id) {
                val fresh = payments.getById(attempt.id, sqlClient) ?: attempt
                val ctx = contexts.create(resolved.provider, resolved.settings, fresh.testMode)

                withTimeout(cancelTimeoutMs) { resolved.provider.cancelPayment(ctx, CancelPaymentRequest(attemptView(fresh, publicId))) }
            }
        } catch (e: TimeoutCancellationException) {
            logger.warn("cancelPayment of attempt {} timed out, ignored", attempt.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.warn("cancelPayment of attempt {} failed, ignored: {}", attempt.id, e.javaClass.simpleName)
        }
    }

    /**
     * 09 section 4.4, last paragraph: the gateway subscription a payment carried but the subscription row does not keep is cancelled now (immediately), with
     * the view built from the event. Anything but a cancel or a scheduled end is written to the order timeline, because the gateway keeps billing the buyer.
     */
    private suspend fun cancelSurplusSubscription(item: CancelSurplusSubscription, sqlClient: SqlClient) {
        val failure: String? = try {
            val resolved = resolve(item.providerId, sqlClient)

            if (resolved == null) {
                "the provider ${item.providerId} is not available"
            } else {
                val ctx = contexts.create(resolved.provider, resolved.settings, item.view.testMode)
                val request = CancelSubscriptionRequest(item.view, atPeriodEnd = false, reason = CancelSurplusSubscription.REASON, storedMethod = null)

                when (val result = withTimeout(cancelTimeoutMs) { resolved.provider.cancelSubscription(ctx, request) }) {
                    is CancelSubscriptionResult.Cancelled, is CancelSubscriptionResult.Scheduled -> null
                    is CancelSubscriptionResult.LocalOnly -> "the provider cancelled nothing at the gateway"
                    is CancelSubscriptionResult.BuyerActionRequired -> "only the buyer can cancel it at the gateway"
                    is CancelSubscriptionResult.Failed -> result.message
                }
            }
        } catch (e: TimeoutCancellationException) {
            "the cancel call timed out"
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProviderException) {
            e.adminMessage ?: e.code.name
        } catch (e: Throwable) {
            e.javaClass.simpleName
        }

        if (failure == null) {
            logger.info("the surplus gateway subscription {} of order {} was cancelled at {}", item.view.gatewaySubscriptionId, item.orderId, item.providerId)

            return
        }

        logger.warn("the surplus gateway subscription {} of order {} could not be cancelled at {}: {}", item.view.gatewaySubscriptionId, item.orderId, item.providerId, failure)

        val now = clock.now()

        orderEvents.add(
            MarketOrderEvent(
                orderId = item.orderId, type = OrderEventType.NOTE, actorType = OrderActorType.SYSTEM, message = CancelSurplusSubscription.FAILED_NOTE,
                data = JsonObject().put("subscriptionId", item.subscriptionId).put("providerId", item.providerId)
                    .put("gatewaySubscriptionId", item.view.gatewaySubscriptionId).put("error", failure.take(ADMIN_MESSAGE_MAX)).encode(),
                createdAt = now, updatedAt = now
            ),
            sqlClient
        )
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

                    is AfterCommit.OrderPaid -> announcer.paid(item.orderId, sqlClient)

                    is CancelSurplusSubscription -> cancelSurplusSubscription(item, sqlClient)
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

            val body: suspend (LockedOrder) -> AppliedEvent = { locked ->
                val attempt = payments.getById(attemptId, conn) ?: throw NoSuchElementException("attempt $attemptId does not exist")

                applyIn(conn, locked, attemptId, event, facts, policy ?: resolve(attempt.providerId, conn)?.policy ?: ProviderMoneyPolicy(), actor, after)
            }
            val scope = scopeFor(event)

            // a failure of an attempt of a renewal order reaches the subscription (09 section 9.1): its row is locked before the order, as in every scope but PAYMENT
            if (scope == OrderLockScope.PAYMENT && (event is PaymentAttemptEvent.Failed || event is PaymentAttemptEvent.Expired) &&
                orders.getById(orderId, conn)?.let { it.source == OrderSource.RENEWAL && it.subscriptionId != null } == true
            ) {
                locks.orderWithSubscription(conn, orderId, body)
            } else {
                locks.forOrder(conn, orderId, scope, cashback = config().cashbackPercent > 0, block = body)
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
        facts.failureCode?.let { sets["failureCode"] = it.take(FAILURE_CODE_MAX) }
        facts.failureMessage?.let { sets["failureMessage"] = it }
        facts.adminMessage?.let { sets["adminMessage"] = it.take(ADMIN_MESSAGE_MAX) }

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

                is PaymentEffect.RecordReviewReason -> {
                    val reviewNote = "REVIEW " + effect.reason.name

                    // the column holds ADMIN_MESSAGE_MAX characters: the gateway's text gives way to the reason
                    sets["adminMessage"] = (facts.adminMessage?.take(ADMIN_MESSAGE_MAX - reviewNote.length - 2)?.let { "$it; " } ?: "") + reviewNote
                }

                else -> Unit
            }
        }

        // a NeedsReview that says what the gateway received (a partial, an excess, a late or a wrong-asset payment): the money is recorded on the
        // attempt, so that the order carries it (O3 / O9 record `paymentId` / `paidAmount`) and a rejection with a refund returns it (06 section 9.4)
        val received = facts.receivedAmount?.takeIf { decision.to == PaymentStatus.REVIEW && event is PaymentAttemptEvent.NeedsReview }

        if (received != null) {
            sets["paidAmount"] = received
            sets["paidCurrency"] = facts.receivedCurrency ?: attempt.currency
            sets["paidAt"] = now
        }

        if (!updateAttempt(conn, attemptId, sets, whereStatus = attempt.status)) throw com.panomc.plugins.market.db.tx.OrderChangedException(orderId, "attempt $attemptId moved under the lock")

        timeline(conn, orderId, attempt, decision.to, event, actor, paid, decision.effects.any { it is PaymentEffect.NotifyOrder }, duplicate)

        // 12 section 4.1 `ORDER_RECEIVED`: the attempt reached PROCESSING (the gateway has the money in flight) while the order still waits
        if (decision.to == PaymentStatus.PROCESSING && attempt.status != PaymentStatus.PROCESSING && order.status == OrderStatus.PENDING) mails.processing(conn, order, attempt)

        // 09 section 4.4: what a success says about the subscription (gateway subscription, stored method) goes onto the pending row now, so the activation
        // of O2 / O4 (also an admin's accept long after) reads it from there; the subscription row is locked by every scope that can reach a Succeeded
        // (a success on a renewal order is handed over too: the stored method of a payment by hand replaces the card, 09 section 8.4 step 4)
        if (paid != null && order.subscriptionId != null) subscriptionHooks.onPaid(conn, order, attempt, facts)?.let { after += it }

        // 09 section 9.1: a closed attempt of a renewal order is a failed charge of the subscription (or only a note on the renewal when it was the buyer's own attempt)
        if (order.subscriptionId != null && order.source == OrderSource.RENEWAL && (decision.to == PaymentStatus.FAILED || decision.to == PaymentStatus.EXPIRED)) {
            subscriptionHooks.onAttemptFailed(conn, order, attempt, event is PaymentAttemptEvent.Failed && event.final, facts)
        }

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

                // a second paid attempt of a paid order: the automatic refund of exactly that money, or an alert saying why not (00 section 7.2)
                is PaymentEffect.FlagDuplicate -> duplicateRefundRule(conn, attempt.providerId).let { rule ->
                    orderService.onDuplicatePayment(conn, orderId, attemptId, rule.autoRefund, rule.providerCanRefund, after)
                }

                is PaymentEffect.NotifyOrder -> {
                    // an admin's decision on the attempt (the bank transfer approval, 06 section 14.1) is the admin's decision on the order too: O2 / O3 / O8 carry the actor
                    var orderEvent = if (actor != OrderActor.ADMIN) effect.event else when (val e = effect.event) {
                        is OrderEvent.Paid -> e.copy(actor = actor)
                        is OrderEvent.NeedsReview -> e.copy(actor = actor)
                        is OrderEvent.Fail -> e.copy(actor = actor)
                        else -> e
                    }
                    var note: String? = null
                    var refundLocks: LockedOrder? = null
                    val paidEvent = orderEvent as? OrderEvent.Paid

                    // O2 or O3 (06 section 9.4): a guard may divert a payment that would complete a PENDING order into a review
                    if (paidEvent != null && order.status == OrderStatus.PENDING) {
                        val diversion = divertPaid(conn, locked, order)

                        if (diversion != null) {
                            logger.warn("order {} is paid but waits for review: {}", orderId, diversion.note)

                            orderEvent = OrderEvent.NeedsReview(diversion.reason, paidEvent.attemptId, paidEvent.actor)
                            note = diversion.note

                            // 09 section 8.5: money the store may not keep goes back at once when the switch is on and the provider can refund it
                            if (diversion.refundAtOnce) {
                                val rule = duplicateRefundRule(conn, attempt.providerId)

                                if (rule.autoRefund && rule.providerCanRefund) refundLocks = releaseLocksOf(locked, order)
                            }
                        }
                    }

                    val moved = orderService.transition(conn, locked, orderEvent, message = note)

                    // the panel is alerted about a review that waits for a human; one the system rejects in the same transaction needs nobody
                    after += if (refundLocks != null) moved.after.filterNot { it is AfterCommit.PanelAlert } else moved.after

                    if (moved.moved) orderStatus = moved.to

                    if (refundLocks != null && moved.moved && moved.to == OrderStatus.REVIEW) {
                        val rejected = orderService.transition(conn, refundLocks, OrderEvent.ReviewRejected(refund = true, system = true), message = LATE_REFUND_NOTE)

                        after += rejected.after

                        if (rejected.moved) orderStatus = rejected.to
                    }
                }

                else -> Unit
            }
        }

        // the order already waits for a human and the gateway reports money it cannot use: the order records it and the panel is told
        if (received != null && order.status == OrderStatus.REVIEW && decision.effects.none { it is PaymentEffect.RecordPaymentOnOrder }) {
            recordPaymentOnOrder(conn, order, attemptId)

            after += AfterCommit.PanelAlert(orderId, order.reviewReason)
        }

        return AppliedEvent(decision.to, orderStatus, changed = true, duplicate = duplicate)
    }

    /**
     * The lock set O5 needs for [order], a renewal order that a gateway paid (09 section 8.5). O5's release step asks for the `RELEASE` scope, which differs from the
     * `COMMIT` set the caller holds by the code, variant and `REVOKED` rows; a renewal order reserves nothing (no stock, no redemption, no credit hold: I22), so there
     * is no such row to protect. `null` for any order that is not of that kind: it is left in `REVIEW` for the admin.
     */
    private fun releaseLocksOf(locked: LockedOrder, order: MarketOrder): LockedOrder? =
        if (order.source == OrderSource.RENEWAL && order.creditAmount == 0L && locked.redemptions.isEmpty() && locked.items.all { it.stockReserved == 0 }) {
            LockedOrder(locked.order, locked.items, locked.redemptions, OrderLockScope.RELEASE)
        } else {
            null
        }

    /** The first [PaidGuard] that diverts O2, `null` when the payment may complete the order. The caller holds the `COMMIT` locks (or wider). */
    private suspend fun divertPaid(conn: SqlConnection, locked: LockedOrder, order: MarketOrder): PaidDiversion? {
        check(locked.scope == OrderLockScope.COMMIT || locked.scope == OrderLockScope.RELEASE) { "O2 runs under the COMMIT locks, not ${locked.scope}" }

        for (guard in paidGuards) guard.divert(conn, locked, order)?.let { return it }

        return null
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
     * the attempt over before the provider answered. A provider that fails, is unavailable or misses the 30 s deadline answers 502
     * `PAYMENT_PROVIDER_ERROR {code, order, orderToken}` (06 section 9.2 steps 4 and 5; `GATEWAY_UNREACHABLE` for the deadline), as in checkout.
     */
    suspend fun pay(order: MarketOrder, request: PayRequest, caller: PayCaller, sqlClient: SqlClient): JsonObject? {
        val after = ArrayList<AfterCommit>()
        val plan = db.txRestartingOnOrderChange { conn ->
            after.clear()

            locks.forOrder(conn, order.id, OrderLockScope.CREDIT) { locked -> payTx(conn, locked, request, caller) }
        }

        runAfter(after, sqlClient)

        return try {
            startAttempt(order.id, plan.attemptId, plan.cancelled, sqlClient)
        } catch (e: PaymentStartFailed) {
            logger.warn("the payment of order {} could not be started: {}", order.id, e.code)

            throw PaymentProviderError(e.code, orderService.ownerView(orders.getById(order.id, sqlClient) ?: order, sqlClient), order.accessToken)
        }
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

        // 2. orders priced by a gateway, full-credit orders and the orders of a running subscription keep their provider. The initial order of a subscription whose
        // row is still PENDING may switch to another gateway (09 section 2, section 4.3, test 22: the row follows); a credit-paid subscription keeps `credits`
        val pending = subscriptionHooks.pendingPlan(order, conn)
        // a renewal order is paid with any offered method (09 section 8.1 and 8.4: a buyer pays it by hand, with credits too); only the gateway-priced rule still holds
        val subscriptionLocked = order.subscriptionId != null && order.source != OrderSource.RENEWAL && (pending == null || fullCredit || order.paymentMethodId == MethodInput.CREDITS)

        if (methodId == MethodInput.FREE) throw PaymentMethodUnavailable(METHOD_NOT_OFFERED)
        if ((order.pricingMode != PricingMode.MARKET || subscriptionLocked) && methodId != order.paymentMethodId) throw PaymentMethodUnavailable(METHOD_LOCKED)

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

        // eligibility is a pure question of the provider (02 section 5). A subscription goes through the offer table of 09 section 4.2 first: the verdict
        // (AUTO / MANUAL) decides the mode of the PENDING row, an AUTO offer asks the provider about the plan, `oneOffOnly` downgrades it to MANUAL
        var recurring: String? = null

        if (target != null && tender.gatewayAmount > 0) {
            val fallback = config().subscriptionManualFallback
            var offer: ModeOffer? = pending?.let { ModeResolver.offer(target.caps, it.recurring, fallback) }

            if (offer is ModeOffer.Unavailable) throw PaymentMethodUnavailable(offer.reason)

            val plan = if (offer is ModeOffer.Auto) pending?.at(tender.total) else null
            val eligibility = try {
                target.provider.checkEligibility(contexts.create(target.provider, target.settings, target.testMode), snapshotOf(order, items, tender, plan))
            } catch (e: Exception) {
                null
            }

            if (eligibility == null || !eligibility.eligible) throw PaymentMethodUnavailable(PROVIDER_INELIGIBLE)

            if (offer is ModeOffer.Auto) offer = ModeResolver.applyEligibility(offer, eligibility, fallback)

            if (offer is ModeOffer.Unavailable) throw PaymentMethodUnavailable(offer.reason)

            recurring = offer?.recurring
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

        // 09 section 4.3: the PENDING row of a subscription follows the re-tendered order (provider, mode, per-period price), so the plan the new start
        // carries is the amount the new attempt asks for
        if (pending != null) subscriptionHooks.onMethodChanged(conn, orders.getById(order.id, conn)!!, newMethodId, recurring)

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

    private fun snapshotOf(
        order: MarketOrder, items: List<MarketOrderItem>, tender: com.panomc.plugins.market.core.pricing.TenderBreakdown, plan: SubscriptionPlan? = null
    ): CheckoutSnapshot {
        val base = spiSnapshot(order, items)
        val currency = order.currency

        return CheckoutSnapshot(
            order = SpiOrderSnapshot(
                base.id, base.publicId, base.description, base.currency, base.lines, base.subtotal, base.discount, base.shipping, Money(tender.paymentFee, currency),
                Money(tender.vatTotal, currency), Money(tender.total, currency), Money(tender.creditValue, currency), base.requiresShipping, base.recipientUsername, base.gift, base.pricingMode
            ),
            buyer = buyerOf(order, null, null, order.billingInfo?.let { runCatching { JsonObject(it) }.getOrNull() }, order.locale ?: site().defaultLocale),
            subscription = plan, hasPanoPriceModifiers = order.discountTotal > 0 || tender.creditValue > 0 || tender.paymentFee > 0
        )
    }

    /** The pending order as the pure re-tender sees it (05 section 9.6): the figures frozen at O1, what the buyer can spend now, the store's credit rules. */
    private suspend fun frozenOf(order: MarketOrder, items: List<MarketOrderItem>, sqlClient: SqlClient): FrozenOrder {
        val c = config()
        val rates = currencyRates.getAll(sqlClient).filter { it.rate.signum() > 0 }.associate { it.currency to it.rate }
        val pricing = marketPricingConfig(c, rates)
        val held = if (order.reservationState == ReservationState.HELD) order.creditAmount else 0L
        val balance = order.userId?.let { creditAccounts.getByUserId(it, sqlClient)?.balance } ?: 0L
        val conversions = Conversions(order.baseCurrency, order.currency, order.fxRate, pricing.creditValue, pricing.removeCents, order.displayCurrency, order.displayRate)
        val profile = when (order.source) {
            OrderSource.PANEL -> PricingProfile.PANEL
            OrderSource.RENEWAL -> PricingProfile.RENEWAL
            OrderSource.GIFT_CODE -> PricingProfile.GIFT_CODE
            OrderSource.INGAME -> PricingProfile.INGAME
            else -> PricingProfile.STOREFRONT
        }

        return FrozenOrder(
            conversions = conversions,
            pricingMode = com.panomc.plugins.market.core.pricing.PricingMode.valueOf(order.pricingMode.name), profile = profile,
            itemsTotal = order.totalPrice - order.shippingTotal - order.paymentFee, itemsVat = order.vatTotal - order.shippingVatAmount - order.paymentFeeVatAmount,
            shippingTotal = order.shippingTotal, shippingVat = order.shippingVatAmount, requiresShipping = order.requiresShipping, vatBp = pricing.vatBp,
            currentMethodId = order.paymentMethodId, creditAmount = order.creditAmount, creditValue = order.creditValue, creditBalance = balance + held,
            loggedIn = order.userId != null, creditsEnabled = pricing.creditsEnabled, allowMixedCreditPayment = pricing.allowMixedCreditPayment,
            onlyAcceptCredits = pricing.onlyAcceptCredits, mixedCreditCart = items.none { it.kind == OrderItemKind.CREDIT_TOPUP || (it.creditAmount ?: 0) > 0 } && order.subscriptionId == null,
            creditItemsTotal = CreditRunSnapshot.itemsTotal(order, items, conversions), renewalFee = if (order.source == OrderSource.RENEWAL) order.paymentFee else null, rates = rates
        )
    }

    /** `H = createdAt + max(24 h, W(first provider))`: retries cannot keep stock reserved beyond it (06 section 9.1). */
    private suspend fun hardCapOf(order: MarketOrder, attempts: List<MarketPayment>, sqlClient: SqlClient): Long {
        // a renewal order reserves nothing, so the cap that keeps stock from being held for ever does not apply: its own window (`expiresAt`) is its limit, and a
        // manual renewal is prepared days before the period ends (09 section 8.6)
        if (order.source == OrderSource.RENEWAL) order.expiresAt?.let { return it }

        val first = attempts.firstOrNull()
        val minutes = first?.let { resolve(it.providerId, sqlClient)?.caps?.paymentWindowMinutes }

        return OrderTimings.hardCap(order.createdAt, OrderTimings.providerWindowMs(first?.providerId ?: OrderTimings.BANK_TRANSFER_PROVIDER, minutes, timing()))
    }

    // ================================================================================================= continue

    /** The in-JVM lock of the embedded form of one order, counted so that it exists only while a call holds or waits for it. */
    private class AttemptLock {
        val mutex = Mutex()
        var users = 0
    }

    private val continueLocks = HashMap<Long, AttemptLock>()

    /** How many attempt locks a running call holds or awaits right now; `0` when no `continue` is in flight (a hook for the tests). */
    internal fun attemptLocksInUse(): Int = synchronized(continueLocks) { continueLocks.size }

    /**
     * Runs [block] holding the lock of the open attempt of [orderId]. An order has at most one open attempt (I10) and `continue` only ever
     * addresses the newest one, so the order is the key; the attempt itself is read inside the lock, never before. The entry is removed when the
     * last call that holds or waits for it leaves, so the map holds only the calls that are running right now.
     */
    private suspend fun <T> underAttemptLock(orderId: Long, block: suspend () -> T): T {
        val entry = synchronized(continueLocks) { continueLocks.getOrPut(orderId) { AttemptLock() }.also { it.users++ } }

        try {
            return entry.mutex.withLock { block() }
        } finally {
            synchronized(continueLocks) { if (--entry.users == 0) continueLocks.remove(orderId) }
        }
    }

    /**
     * `POST /orders/:publicId/payment/continue` (06 section 9.3): the second step of an embedded form. Under the attempt lock the order and
     * its newest attempt are read again and must be `PENDING` with `startKind = EMBEDDED` (else 409 `ORDER_NOT_PAYABLE`); the provider is
     * called with that fresh row (30 s) and the result is stored like a start before the lock is released, so the next waiter sees the stored
     * step (or `COMPLETED`) and answers 409 instead of calling the provider a second time. A provider error answers 502 and leaves the attempt
     * as it is, so the buyer can enter the step again.
     */
    suspend fun continuePayment(order: MarketOrder, values: JsonObject, caller: PayCaller, sqlClient: SqlClient): JsonObject? = underAttemptLock(order.id) {
        // the attempt lock of the inbound pipeline (lock order: this order's continue lock, then the attempt lock; the pipeline never takes the first)
        val addressed = payments.getByOrderId(order.id, sqlClient).lastOrNull() ?: throw OrderNotPayable()

        attemptLocks.with(addressed.id) { continueLocked(order, addressed.id, values, sqlClient) }
    }

    private suspend fun continueLocked(order: MarketOrder, addressedAttemptId: Long, values: JsonObject, sqlClient: SqlClient): JsonObject? {
        val current = orders.getById(order.id, sqlClient) ?: throw OrderNotPayable()
        val attempt = payments.getByOrderId(order.id, sqlClient).lastOrNull()

        // a newer attempt appeared while this call waited for the lock: it is not the one the buyer's form belongs to
        if (attempt != null && attempt.id != addressedAttemptId) throw OrderNotPayable()

        if (current.status != OrderStatus.PENDING || attempt == null || attempt.status != PaymentStatus.PENDING || attempt.startKind != "EMBEDDED") throw OrderNotPayable()

        val resolved = resolve(attempt.providerId, sqlClient) ?: throw OrderNotPayable()
        val publicId = current.publicId ?: ""
        val ctx = contexts.create(resolved.provider, resolved.settings, attempt.testMode)
        val request = ContinuePaymentRequest(
            attemptView(attempt, publicId), values, buyerOf(current, attempt.clientIp, attempt.userAgent, current.billingInfo?.let { runCatching { JsonObject(it) }.getOrNull() }, current.locale ?: site().defaultLocale),
            urlsFor(attempt, publicId, resolved.provider.id)
        )
        val result = try {
            withTimeout(startTimeoutMs) { resolved.provider.continuePayment(ctx, request) }
        } catch (e: TimeoutCancellationException) {
            throw PaymentProviderError(ProviderErrorCode.GATEWAY_UNREACHABLE.name, orderService.ownerView(current, sqlClient), current.accessToken)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProviderException) {
            throw PaymentProviderError(e.code.name, orderService.ownerView(current, sqlClient), current.accessToken)
        } catch (e: Throwable) {
            throw PaymentProviderError(ProviderErrorCode.INTERNAL.name, orderService.ownerView(current, sqlClient), current.accessToken)
        }

        return storeResult(current, attempt, resolved, result, continued = true)
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

        if (!isOpen(attempt)) return

        val resolved = resolve(attempt.providerId, sqlClient) ?: return

        if (!resolved.caps.statusQuery) return

        val startedNs = System.nanoTime()

        // the provider query and what it reports are one unit under the attempt lock of the inbound pipeline (a `handleInbound` of this attempt waits).
        // The wait is part of the poll's budget (06 section 10.4): a lock that stays held past it changes nothing and the caller answers the current state;
        // the per-attempt claim is taken inside the lock, so a wait that timed out does not use up the 10 s slot.
        attemptLocks.withOrNull(attempt.id, statusWaitMs) {
            // what the call that held the lock before this one did (an event applied, ids attached) is read now, never from the snapshot taken before the wait
            val fresh = payments.getById(attempt.id, sqlClient) ?: return@withOrNull

            if (!isOpen(fresh)) return@withOrNull

            val now = clock.now()
            val claimed = sqlClient.preparedQuery(
                "UPDATE ${table("market_payment")} SET `lastQueriedAt` = ?, `queryCount` = `queryCount` + 1 WHERE `id` = ? AND (`lastQueriedAt` IS NULL OR `lastQueriedAt` <= ?)"
            ).execute(Tuple.of(now, fresh.id, now - STATUS_QUERY_MIN_GAP_MS)).coAwait().rowCount()

            if (claimed != 1) return@withOrNull

            val remainingMs = statusWaitMs - (System.nanoTime() - startedNs) / 1_000_000
            val ctx = contexts.create(resolved.provider, resolved.settings, fresh.testMode)
            val events = try {
                withTimeoutOrNull(remainingMs.coerceAtLeast(1)) {
                    resolved.provider.queryPayment(ctx, QueryPaymentRequest(attemptView(fresh, order.publicId ?: ""), QueryReason.RETURN_PAGE)).events
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logger.warn("status query of attempt {} failed: {}", fresh.id, e.javaClass.simpleName)

                null
            }

            if (events != null) applyQueried(order, fresh, events, resolved.policy)
        }
    }

    private fun isOpen(attempt: MarketPayment) = attempt.status == PaymentStatus.CREATED || attempt.status == PaymentStatus.PENDING || attempt.status == PaymentStatus.PROCESSING

    /**
     * Whether [target] names [attempt], resolved the way the inbound pipeline resolves it ([resolveAttemptTarget]: provider-scoped, a subscription target
     * resolves to nothing). The attempt's own id and reference need no lookup; any other target must resolve to the very row.
     */
    private suspend fun namesAttempt(attempt: MarketPayment, target: PaymentTarget): Boolean = when {
        target is PaymentTarget.Attempt -> target.attemptId == attempt.id
        target is PaymentTarget.Reference && target.reference == attempt.reference -> true
        target is PaymentTarget.Subscription -> false
        else -> resolveAttemptTarget(payments, orders, attempt.providerId, target, readClient())?.id == attempt.id
    }

    /**
     * The events a provider reported on a query ([QueryReason.RETURN_PAGE] / [QueryReason.RECONCILE]), judged like the inbound pipeline judges them
     * (`PaymentEventApplier`, 02 section 7.3 step 6): an event whose target does not resolve to this very attempt (any [PaymentTarget] kind, scoped to the
     * provider; a subscription target never does) is skipped, an event of another environment than the attempt's is
     * skipped unless it is a `Succeeded` (which the attempt machine turns into a review, with the note "environment mismatch" and the money it names);
     * everything else is applied through [applyEvent]. Returns how many events were applied.
     */
    private suspend fun applyQueried(order: MarketOrder, attempt: MarketPayment, events: List<PaymentEvent>, policy: ProviderMoneyPolicy): Int {
        var applied = 0

        for (event in events) {
            val target = event.target

            if (!namesAttempt(attempt, target)) {
                logger.warn("a query of attempt {} answered a {} for a target that is not this attempt, skipped", attempt.id, event.javaClass.simpleName)

                continue
            }

            if (PaymentEventApplier.skippedForEnvironment(event, attempt.testMode)) {
                logger.warn("a query of attempt {} answered a {} of another environment (test mode {}), skipped", attempt.id, event.javaClass.simpleName, event.testMode)

                continue
            }

            val mapped = PaymentEventMapper.attemptEvent(event) ?: continue
            var facts = AttemptFacts.of(event, cipher)

            if (event is PaymentEvent.Succeeded && event.testMode != null && event.testMode != attempt.testMode) {
                facts = facts.withReceived(event.paid.amount, event.paid.currency, PaymentEventApplier.ENVIRONMENT_MISMATCH)
            }

            applyEvent(order.id, attempt.id, mapped, facts, OrderActor.GATEWAY, policy)
            applied++
        }

        return applied
    }

    // ============================================================================== what the background jobs need (MK-078)

    /** What `OrderExpiryJob` and `PaymentReconcileJob` need to know about a provider: `statusQuery` and `longPending` (02 section 8, 06 section 9.1). */
    class ProviderTraits(val statusQuery: Boolean, val longPending: Boolean)

    /** The traits of [providerId]; `null` when the provider is not registered (plugin stopped, license lapsed) or throws while it describes itself. */
    suspend fun traitsOf(providerId: String, sqlClient: SqlClient): ProviderTraits? =
        resolve(providerId, sqlClient)?.let { ProviderTraits(it.caps.statusQuery, it.caps.longPending) }

    /** The result of one reconcile query (02 section 8). Nothing here ever fails an attempt: only an applied event moves it. */
    sealed class ReconcileQuery {
        /** The gateway answered with [events] events (all applied); [pollAgainAfterSeconds] is its own hint. */
        class Applied(val events: Int, val pollAgainAfterSeconds: Long?) : ReconcileQuery()

        /** `PaymentQueryResult.unknown()` or an empty answer: still pending. */
        class Unknown(val pollAgainAfterSeconds: Long?) : ReconcileQuery()

        /** The provider has no `statusQuery`, or answered `unsupported()`. */
        data object Unsupported : ReconcileQuery()

        /** No provider, a timeout or a throwing provider: the outcome is unknown, handled like [Unknown] by the caller. */
        class Unavailable(val reason: String) : ReconcileQuery()
    }

    /**
     * Asks the provider about [attempt] (`QueryReason.RECONCILE`, 30 s deadline) and applies what it reports through [applyEvent]. A provider
     * failure, a missing provider and a timeout are [ReconcileQuery.Unavailable]: they change nothing. A failure while applying an event
     * propagates (the transaction rolled back; the caller logs it and the next slot of the schedule retries).
     */
    suspend fun reconcileQuery(order: MarketOrder, attempt: MarketPayment, sqlClient: SqlClient): ReconcileQuery {
        val resolved = resolve(attempt.providerId, sqlClient) ?: return ReconcileQuery.Unavailable("PROVIDER_UNAVAILABLE")

        if (!resolved.caps.statusQuery) return ReconcileQuery.Unsupported

        return attemptLocks.with(attempt.id) {
            // the row as the call that held the lock before this one left it: settled meanwhile = nothing to ask, new ids attached = they reach the provider
            val fresh = payments.getById(attempt.id, sqlClient)

            if (fresh == null || !isOpen(fresh)) ReconcileQuery.Unknown(null) else reconcileLocked(order, fresh, resolved)
        }
    }

    private suspend fun reconcileLocked(order: MarketOrder, attempt: MarketPayment, resolved: Resolved): ReconcileQuery {
        val result = try {
            val ctx = contexts.create(resolved.provider, resolved.settings, attempt.testMode)

            withTimeout(queryTimeoutMs) { resolved.provider.queryPayment(ctx, QueryPaymentRequest(attemptView(attempt, order.publicId ?: ""), QueryReason.RECONCILE)) }
        } catch (e: TimeoutCancellationException) {
            logger.warn("reconcile query of attempt {} timed out", attempt.id)

            return ReconcileQuery.Unavailable("TIMEOUT")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // a LinkageError of an older host included: the attempt is not touched, the next slot asks again
            logger.warn("reconcile query of attempt {} failed: {}", attempt.id, e.javaClass.simpleName)

            return ReconcileQuery.Unavailable(e.javaClass.simpleName)
        }

        if (result.unsupported) return ReconcileQuery.Unsupported

        val applied = applyQueried(order, attempt, result.events, resolved.policy)

        return if (applied == 0) ReconcileQuery.Unknown(result.pollAgainAfterSeconds) else ReconcileQuery.Applied(applied, result.pollAgainAfterSeconds)
    }

    /**
     * A provider as the subscription job calls it (09 sections 8.3 and 11, MK-122): the provider itself, its capabilities, whether its context is in test mode
     * and a context for the call. [testMode] is the environment a call for [attemptTestMode] runs in (`null` = the provider's own).
     */
    class ProviderHandle(val provider: PaymentProvider, val caps: PaymentCapabilities, val ctx: PaymentContext, val testMode: Boolean)

    /** The handle of [providerId]; `null` when it is not registered, incompatible or throws while it describes itself (`UNAVAILABLE` / `INCOMPATIBLE`, 02 section 11). A disabled method is still returned: it keeps charging its subscriptions. */
    suspend fun providerHandle(providerId: String, attemptTestMode: Boolean?, sqlClient: SqlClient): ProviderHandle? {
        val resolved = resolve(providerId, sqlClient) ?: return null

        return ProviderHandle(resolved.provider, resolved.caps, contexts.create(resolved.provider, resolved.settings, attemptTestMode ?: resolved.testMode), resolved.testMode)
    }

    /** The request of `chargeRecurring` for the renewal [order] and its [attempt] (09 section 8.3): the stored method of [subscription] is the instrument to charge. */
    suspend fun recurringChargeRequest(
        order: MarketOrder, attempt: MarketPayment, subscription: com.panomc.plugins.market.spi.payment.SubscriptionView, stored: StoredPaymentMethod,
        idempotencyKey: String, sqlClient: SqlClient
    ): com.panomc.plugins.market.spi.payment.RecurringChargeRequest {
        val items = orderItems.getByOrderIds(listOf(order.id), sqlClient)
        val billing = order.billingInfo?.let { runCatching { JsonObject(it) }.getOrNull() }
        val locale = order.locale ?: site().defaultLocale

        return com.panomc.plugins.market.spi.payment.RecurringChargeRequest(
            attempt = AttemptRef(attempt.id, attempt.reference, attempt.token), amount = Money(attempt.amount, attempt.currency), subscription = subscription,
            storedMethod = stored, order = spiSnapshot(order, items), buyer = buyerOf(order, null, null, billing, locale), idempotencyKey = idempotencyKey,
            notifyUrl = urlsFor(attempt, order.publicId ?: "", attempt.providerId).notify
        )
    }

    /** Runs the follow-up work a transaction of a job collected ([TransitionResult.after]): gateway cancels (failures ignored) and panel alerts. */
    suspend fun runAfterCommit(after: List<AfterCommit>, sqlClient: SqlClient) = runAfter(after, sqlClient)

    /** [runAfterCommit] on the plugin's own read client (the subscription job and the inbound sink have none of their own). */
    suspend fun runAfterCommit(after: List<AfterCommit>) = runAfter(after, readClient())

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
                    icon = descriptor.icon, logoUrl = if (descriptor.logo != null) MarketPaths.site("/payment-providers/${row.methodId}/logo") else null, color = descriptor.color,
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

    // ============================================================================================== refunds (MK-111)

    /** What a provider can refund (02 section 5), or `null` when no usable provider is registered under [providerId]. */
    suspend fun refundSupportOf(providerId: String, sqlClient: SqlClient): RefundSupport? = resolve(providerId, sqlClient)?.caps?.refund

    /** The provider call of a refund did not answer within its deadline: the outcome at the gateway is unknown (21 section 3.3). */
    class RefundCallTimeout : RuntimeException("the refund call did not answer in time")

    /**
     * `provider.refund` for [refund] (21 section 3.3), outside any transaction and under the lock of the attempt (02 section 10 guarantee 3). The key sent
     * is the row's `idempotencyKey`, the amount is the gateway part; a retry sends the same key. Throws [ProviderException] for a provider that refused or
     * is not available, [RefundCallTimeout] when the deadline passes; every other throwable is an unknown outcome for the caller.
     */
    suspend fun callRefund(
        order: MarketOrder, items: List<MarketOrderItem>, attempt: MarketPayment, refund: com.panomc.plugins.market.db.model.MarketRefund,
        lines: List<com.panomc.plugins.market.db.model.MarketRefundItem>, full: Boolean, sqlClient: SqlClient
    ): RefundResult {
        val resolved = resolve(attempt.providerId, sqlClient) ?: throw ProviderException(ProviderErrorCode.CONFIGURATION, "provider ${attempt.providerId} is not available")

        return attemptLocks.with(attempt.id) {
            val fresh = payments.getById(attempt.id, sqlClient) ?: attempt
            val ctx = contexts.create(resolved.provider, resolved.settings, fresh.testMode)
            val byItem = items.associateBy { it.id }
            val parts = com.panomc.plugins.market.core.money.Rounding.allocate(refund.gatewayAmount.coerceAtMost(lines.sumOf { it.amount }), lines.map { it.amount }, 1L)
            val refundLines = if (lines.isEmpty() || parts.sum() == 0L) emptyList() else lines.mapIndexed { i, line ->
                com.panomc.plugins.market.spi.payment.RefundLine(line.orderItemId, byItem[line.orderItemId]?.gatewayItemRef, line.quantity, Money(parts[i], order.currency))
            }
            val request = RefundRequest(
                refundId = refund.id, idempotencyKey = refund.idempotencyKey, attempt = attemptView(fresh, order.publicId ?: ""), order = spiSnapshot(order, items),
                amount = Money(refund.gatewayAmount, order.currency), full = full, lines = refundLines, reason = refund.reason, paidAt = fresh.paidAt ?: order.paidAt ?: clock.now()
            )

            try {
                withTimeout(refundTimeoutMs) { resolved.provider.refund(ctx, request) }
            } catch (e: TimeoutCancellationException) {
                throw RefundCallTimeout()
            }
        }
    }

    /** `provider.queryRefund` for [refund] (21 section 3.3, reconcile): the answer is applied like the answer of the call itself; `Unknown` changes nothing. */
    suspend fun callQueryRefund(order: MarketOrder, attempt: MarketPayment, refund: com.panomc.plugins.market.db.model.MarketRefund, sqlClient: SqlClient): RefundResult {
        val resolved = resolve(attempt.providerId, sqlClient) ?: throw ProviderException(ProviderErrorCode.CONFIGURATION, "provider ${attempt.providerId} is not available")

        return attemptLocks.with(attempt.id) {
            val fresh = payments.getById(attempt.id, sqlClient) ?: attempt
            val ctx = contexts.create(resolved.provider, resolved.settings, fresh.testMode)
            val request = com.panomc.plugins.market.spi.payment.QueryRefundRequest(
                refund.id, refund.idempotencyKey, refund.gatewayRefundId, attemptView(fresh, order.publicId ?: ""), Money(refund.gatewayAmount, order.currency)
            )

            try {
                withTimeout(refundTimeoutMs) { resolved.provider.queryRefund(ctx, request) }
            } catch (e: TimeoutCancellationException) {
                throw RefundCallTimeout()
            }
        }
    }

    companion object {
        const val REFUND_TIMEOUT_MS = 30_000L
        const val START_TIMEOUT_MS = 30_000L
        const val CANCEL_TIMEOUT_MS = 10_000L
        const val STATUS_WAIT_MS = 8_000L
        const val QUERY_TIMEOUT_MS = 30_000L
        const val STATUS_QUERY_FIRST_MS = 60_000L
        const val STATUS_QUERY_MIN_GAP_MS = 10_000L

        /** `market_payment.adminMessage` is `VARCHAR(512)`; `failureCode` is `VARCHAR(64)`. */
        const val ADMIN_MESSAGE_MAX = 512
        const val FAILURE_CODE_MAX = 64

        /** The width of `market_payment.methodDetail`. */
        const val METHOD_DETAIL_MAX = 128

        /** The generic text key a failed start stores for the buyer (the gateway's own text is the admin's, 02 section 6). */
        const val START_FAILED_TEXT = "payment.start-failed"

        /** The generic text key of a payment the gateway reported as `Failed` (06 section 9.4: `failureMessage` is buyer-safe). */
        const val PAYMENT_FAILED_TEXT = "payment.failed"

        /** The `STATUS_CHANGED` note of the O5 the system applies to the late payment of a closed subscription's renewal (09 section 8.5). */
        const val LATE_REFUND_NOTE = "subscription closed, refunded"

        const val METHOD_NOT_OFFERED = "METHOD_NOT_OFFERED"
        const val METHOD_LOCKED = "METHOD_LOCKED"
        const val TEST_MODE = "TEST_MODE"
        const val GUESTS_NOT_SUPPORTED = "GUESTS_NOT_SUPPORTED"
        const val PROVIDER_INELIGIBLE = "PROVIDER_INELIGIBLE"

        private val logger = LoggerFactory.getLogger(PaymentService::class.java)
    }
}
