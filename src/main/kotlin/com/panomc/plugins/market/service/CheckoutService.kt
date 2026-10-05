package com.panomc.plugins.market.service

import com.panomc.plugins.market.core.abuse.AbuseLimits
import com.panomc.platform.error.BadRequest
import com.panomc.platform.error.NotLoggedIn
import com.panomc.platform.model.Error as PanoError
import com.panomc.plugins.market.config.MarketConfig
import com.panomc.plugins.market.core.cart.CartLimits
import com.panomc.plugins.market.core.cart.CartLine
import com.panomc.plugins.market.core.cart.CartLineKey
import com.panomc.plugins.market.core.cart.CartMerger
import com.panomc.plugins.market.core.abuse.IpRange
import com.panomc.plugins.market.core.order.BillingSnapshot
import com.panomc.plugins.market.core.order.ItemSnapshot
import com.panomc.plugins.market.core.order.LineCode
import com.panomc.plugins.market.core.order.LineRules
import com.panomc.plugins.market.core.order.LineVerdict
import com.panomc.plugins.market.core.order.OwnedEntitlement
import com.panomc.plugins.market.core.order.ProductUsage
import com.panomc.plugins.market.core.order.RecipientResolver
import com.panomc.plugins.market.core.order.RequiredBuyerFields
import com.panomc.plugins.market.core.order.RuleChild
import com.panomc.plugins.market.core.order.OrderTimings
import com.panomc.plugins.market.core.order.RuleContext
import com.panomc.plugins.market.core.order.RuleField
import com.panomc.plugins.market.core.order.RuleLine
import com.panomc.plugins.market.core.order.RuleProduct
import com.panomc.plugins.market.core.order.RuleResult
import com.panomc.plugins.market.core.order.SnapshotProduct
import com.panomc.plugins.market.core.order.TimingConfig
import com.panomc.plugins.market.core.order.RuleTier
import com.panomc.plugins.market.core.order.RuleVariant
import com.panomc.plugins.market.core.order.BuyerValidator
import com.panomc.plugins.market.core.credit.CreditPricing
import com.panomc.plugins.market.core.pricing.BuyerContext
import com.panomc.plugins.market.core.pricing.BundleChild
import com.panomc.plugins.market.core.pricing.CouponInput
import com.panomc.plugins.market.core.pricing.CreatorCodeInput
import com.panomc.plugins.market.core.pricing.CurrencyPriceResolver
import com.panomc.plugins.market.core.pricing.DiscountInput
import com.panomc.plugins.market.core.pricing.ItemsResult
import com.panomc.plugins.market.core.pricing.LineInput
import com.panomc.plugins.market.core.credit.CreditEligibility
import com.panomc.plugins.market.core.pricing.LineKind
import com.panomc.plugins.market.core.pricing.MethodEvaluation
import com.panomc.plugins.market.core.pricing.MethodInput
import com.panomc.plugins.market.core.pricing.OrderCurrencies
import com.panomc.plugins.market.core.pricing.OwnedTier
import com.panomc.plugins.market.core.pricing.PriceBreakdown
import com.panomc.plugins.market.core.pricing.PricedLine
import com.panomc.plugins.market.core.pricing.PricingEngine
import com.panomc.plugins.market.core.pricing.PricingCode
import com.panomc.plugins.market.core.pricing.PricingError
import com.panomc.plugins.market.core.pricing.PricingException
import com.panomc.plugins.market.core.pricing.PricingInput
import com.panomc.plugins.market.core.pricing.PricingMode
import com.panomc.plugins.market.core.pricing.PricingProfile
import com.panomc.plugins.market.core.pricing.ShippingCharge
import com.panomc.plugins.market.core.money.Conversions
import com.panomc.plugins.market.core.shipping.ShippableLine
import com.panomc.plugins.market.core.shipping.ShippableLines
import com.panomc.plugins.market.core.pricing.TenderInput
import com.panomc.plugins.market.core.pricing.TierInfo
import com.panomc.plugins.market.core.subscription.ModeOffer
import com.panomc.plugins.market.core.subscription.ModeResolver
import com.panomc.plugins.market.core.subscription.RecurringPlan
import com.panomc.plugins.market.core.time.Clock
import com.panomc.plugins.market.db.dao.MarketBundleItemDao
import com.panomc.plugins.market.db.dao.MarketCartDao
import com.panomc.plugins.market.db.dao.MarketCartItemDao
import com.panomc.plugins.market.db.dao.MarketCategoryDao
import com.panomc.plugins.market.db.dao.MarketCouponDao
import com.panomc.plugins.market.db.dao.MarketCreatorCodeDao
import com.panomc.plugins.market.db.dao.MarketCreditAccountDao
import com.panomc.plugins.market.db.dao.MarketCurrencyRateDao
import com.panomc.plugins.market.db.dao.MarketDiscountDao
import com.panomc.plugins.market.db.dao.MarketEntitlementDao
import com.panomc.plugins.market.db.dao.MarketOrderDao
import com.panomc.plugins.market.db.dao.MarketPaymentDao
import com.panomc.plugins.market.db.dao.MarketPaymentMethodDao
import com.panomc.plugins.market.db.dao.MarketProductDao
import com.panomc.plugins.market.db.dao.MarketProductFieldDao
import com.panomc.plugins.market.db.dao.MarketProductProviderMetaDao
import com.panomc.plugins.market.db.dao.MarketProductPriceDao
import com.panomc.plugins.market.db.dao.MarketProductVariantDao
import com.panomc.plugins.market.db.dao.MarketRedemptionDao
import com.panomc.plugins.market.db.dao.MarketSubscriptionDao
import com.panomc.plugins.market.db.model.BillingMode
import com.panomc.plugins.market.db.model.FulfillmentBy
import com.panomc.plugins.market.db.model.MarketCart
import com.panomc.plugins.market.db.model.MarketCategory
import com.panomc.plugins.market.db.model.MarketOrder
import com.panomc.plugins.market.db.model.MarketOrderItem
import com.panomc.plugins.market.db.model.MarketPayment
import com.panomc.plugins.market.db.model.MarketPaymentMethod
import com.panomc.plugins.market.db.model.MarketProduct
import com.panomc.plugins.market.db.model.MarketProductField
import com.panomc.plugins.market.db.model.MarketProductVariant
import com.panomc.plugins.market.db.model.OrderItemKind
import com.panomc.plugins.market.db.model.OrderSource
import com.panomc.plugins.market.db.model.PaymentStatus
import com.panomc.plugins.market.db.model.PeriodUnit
import com.panomc.plugins.market.db.model.PricingMode as DbPricingMode
import com.panomc.plugins.market.db.model.ProductKind
import com.panomc.plugins.market.db.model.RedemptionKind
import com.panomc.plugins.market.db.model.ReservationState
import com.panomc.plugins.market.db.model.ShippingStatus
import com.panomc.plugins.market.db.model.SubscriptionStatus
import com.panomc.plugins.market.db.tx.Locks
import com.panomc.plugins.market.db.tx.MarketDb
import com.panomc.plugins.market.error.BuyerBlocked
import com.panomc.plugins.market.error.BuyerInfoRequired
import com.panomc.plugins.market.error.CooldownActive
import com.panomc.plugins.market.error.EmptyCart
import com.panomc.plugins.market.error.IdempotencyConflict
import com.panomc.plugins.market.error.InsufficientCredits
import com.panomc.plugins.market.error.InvalidCart
import com.panomc.plugins.market.error.InvalidCoupon
import com.panomc.plugins.market.error.InvalidCreatorCode
import com.panomc.plugins.market.error.InvalidCreditAmount
import com.panomc.plugins.market.error.InvalidRecipient
import com.panomc.plugins.market.error.LegalAcceptanceRequired
import com.panomc.plugins.market.error.MinimumOrderAmountNotReached
import com.panomc.plugins.market.error.OutOfStock
import com.panomc.plugins.market.error.PaymentMethodUnavailable
import com.panomc.plugins.market.error.PaymentProviderError
import com.panomc.plugins.market.error.PriceChanged
import com.panomc.plugins.market.error.ProductRequirementNotMet
import com.panomc.plugins.market.error.PurchaseLimitReached
import com.panomc.plugins.market.error.ShippingAddressRequired
import com.panomc.plugins.market.error.ShippingUnavailable
import com.panomc.plugins.market.error.SubscriptionMustBeAlone
import com.panomc.plugins.market.error.TooManyRequests
import com.panomc.plugins.market.provider.ProviderLookup
import com.panomc.plugins.market.provider.SecretCipher
import com.panomc.plugins.market.provider.SettingsCodec
import com.panomc.plugins.market.service.platform.DirectoryUser
import com.panomc.plugins.market.service.platform.ServerDirectory
import com.panomc.plugins.market.service.platform.UserDirectory
import com.panomc.plugins.market.spi.common.Money
import com.panomc.plugins.market.spi.common.ProviderErrorCode
import com.panomc.plugins.market.spi.common.ProviderSettings
import com.panomc.plugins.market.spi.common.TestModeSupport
import com.panomc.plugins.market.spi.payment.BuyerInfo
import com.panomc.plugins.market.spi.payment.CheckoutSnapshot
import com.panomc.plugins.market.spi.payment.FulfillmentAuthority
import com.panomc.plugins.market.spi.payment.IntervalUnit
import com.panomc.plugins.market.spi.payment.OrderLine
import com.panomc.plugins.market.spi.payment.OrderSnapshot
import com.panomc.plugins.market.spi.payment.PaymentCapabilities
import com.panomc.plugins.market.spi.payment.PaymentProvider
import com.panomc.plugins.market.spi.payment.SubscriptionPlan
import com.panomc.plugins.market.util.CouponScope
import com.panomc.plugins.market.util.DiscountUnit
import com.panomc.plugins.market.util.MarketStatus
import com.panomc.plugins.market.util.OrderStatus
import io.vertx.core.json.JsonArray
import com.panomc.platform.util.RateLimiter
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.SqlClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.security.MessageDigest
import java.util.Locale

/**
 * What a shipping quote of the cart is (03, 10 section 5). The rate engine and the live carrier quotes are MK-132; until
 * then [NONE] answers "nothing to charge", and the quote of a physical cart says it needs an address / has no option.
 */
class ShippingQuote(
    /** The charge of the chosen option, `null` = none could be priced. */
    val charge: ShippingCharge?,
    /** `ShippingOption[]` of 04 section 2. */
    val options: List<JsonObject> = emptyList(),
    val methodId: Long? = null,
    val messages: List<QuoteMessage> = emptyList(),
    /** What checkout freezes on the order (06 section 5.2 A6, 01 section 5.1): the validated address, the method name, the quote snapshot and the parcel weight. */
    val address: JsonObject? = null,
    val methodName: String? = null,
    val snapshot: JsonObject? = null,
    val weightGrams: Int? = null,
    /** Why checkout refuses: the address paths of `SHIPPING_ADDRESS_REQUIRED` and the `reason` of `SHIPPING_UNAVAILABLE` (`NO_ZONE`, `NO_METHOD`, `METHOD_REQUIRED`, `METHOD_NOT_OFFERED`). */
    val fields: List<String> = emptyList(),
    val reason: String? = null
)

/** The shipping seam of the quote (MK-132 implements it with the rate engine). */
fun interface ShippingQuoter {
    suspend fun quote(request: ShippingRequest, sqlClient: SqlClient): ShippingQuote

    companion object {
        val NONE = ShippingQuoter { _, _ -> ShippingQuote(null) }
    }
}

/** What a shipping quoter needs: the shippable part of the priced cart and what the buyer chose. */
class ShippingRequest(
    val currency: String,
    /** `itemsBasisBase` and `physicalBasisBase` of the priced items (03 section 2.4), base currency. */
    val itemsBasisBase: Long,
    val physicalBasisBase: Long,
    val physicalLines: List<PricedLine>,
    val shippingAddress: JsonObject?,
    val shippingAddressId: Long?,
    val shippingMethodId: Long?,
    val userId: Long?,
    /** `physicalBasis` of the priced items: the physical lines after every discount, price basis, order currency (the free threshold compares with it). */
    val physicalBasis: Long = 0,
    val conversions: Conversions? = null,
    /** `showVatInPrice`: the admin typed the rates and thresholds with VAT included. */
    val pricesIncludeVat: Boolean = true,
    /** The store's VAT in basis points (what a method without its own rate uses). */
    val configVatBp: Long = 0,
    /** The order e-mail: the default `email` of a shipping address. */
    val orderEmail: String? = null,
    /** Checkout (never auto-selects a method, reuses the displayed carrier price, 10 section 6.2) instead of the quote. */
    val checkout: Boolean = false,
    /** The physical lines after bundle expansion (10 section 2.2); `null` when a physical product has no usable weight (nothing can be priced). */
    val shippable: List<ShippableLine>? = null
)

/** The block list seam (MK-151): is this buyer refused? `true` = blocked (the quote warns, checkout answers 403). */
fun interface BuyerBlocks {
    suspend fun blocked(payerUsername: String?, recipientUsername: String?, email: String?, clientIp: String?, userId: Long?, sqlClient: SqlClient): Boolean

    companion object {
        val NONE = BuyerBlocks { _, _, _, _, _, _ -> false }
    }
}

/** `POST /api/market/checkout` after parsing (04 section 3): the `CartInput`, the consent total and the legal acceptance. */
data class CheckoutRequest(
    val input: QuoteInput,
    /** `expectedTotal` x 100: the total of the quote the buyer confirmed; `null` = the buyer did not state one. */
    val expectedTotal: Long?,
    val acceptLegal: Boolean,
    val legalTextId: Long?,
    val hideFromBroadcast: Boolean,
    /** The `Idempotency-Key` header, already checked against `^[A-Za-z0-9_-]{16,64}$`. */
    val idempotencyKey: String,
    /** `RequestFingerprint.hash` of the whole body. */
    val bodyHash: String,
    /**
     * The locale of the order (06 section 5.4), resolved by the route: the body `locale` when it is an installed platform locale, else the
     * user's stored locale, else the first installed match of `Accept-Language`, else the site default. `null` (a caller that resolved
     * nothing) = [CheckoutService.DEFAULT_LOCALE]; the body's own `locale` is never stored.
     */
    val orderLocale: String? = null
)

/** The answer of a checkout: the order (owner view), its access token (returned only here) and the payment start. */
class CheckoutResult(val order: JsonObject, val orderToken: String, val payment: JsonObject?) {
    fun toMap(): Map<String, Any?> = mapOf("order" to order, "orderToken" to orderToken, "payment" to payment)
}

/** A payment start that failed; [code] is a `ProviderErrorCode` name (never the gateway's own text). */
class PaymentStartFailed(val code: String, cause: Throwable? = null) : RuntimeException(code, cause)

/**
 * Phase C of checkout (06 section 9.2): starts the `CREATED` attempt the order transaction wrote. `PaymentService`
 * (MK-076) implements it; [NONE] does nothing, so the order page takes over (`payment: null`) and a free or credits order
 * waits for the reconcile job. Never called inside a database transaction.
 */
interface PaymentStarter {
    /** The `PaymentStart` JSON for the buyer, `null` = none yet. Throws [PaymentStartFailed] when the provider failed. */
    suspend fun start(order: MarketOrder, attempt: MarketPayment, sqlClient: SqlClient): JsonObject?

    /** The stored start result of [attempt] as `PaymentStart` JSON (a replay of the request), `null` when it has none. */
    suspend fun served(attempt: MarketPayment, sqlClient: SqlClient): JsonObject?

    companion object {
        val NONE: PaymentStarter = object : PaymentStarter {
            override suspend fun start(order: MarketOrder, attempt: MarketPayment, sqlClient: SqlClient): JsonObject? = null

            override suspend fun served(attempt: MarketPayment, sqlClient: SqlClient): JsonObject? = null
        }
    }
}

/**
 * What `checkout` needs on top of what the quote needs, kept apart so a quote-only service is built without it. [replayWaitMs] /
 * [replayPollMs]: how long a replay waits for the first request to finish starting the payment (06 section 5.1: 5 s, polled every 500 ms).
 */
class CheckoutDeps(
    val db: MarketDb,
    val locks: Locks,
    val reservations: ReservationService,
    val redemptions: RedemptionService,
    val orders: OrderService,
    val payments: MarketPaymentDao,
    val providerMeta: MarketProductProviderMetaDao? = null,
    val starter: PaymentStarter = PaymentStarter.NONE,
    val replayWaitMs: Long = 5_000,
    val replayPollMs: Long = 500
)

/** The priced cart changed between phase A and the order transaction (06 section 5.3 B5): phase A and B run again once. Internal. */
class QuoteChanged(why: String) : RuntimeException(why)

/**
 * Phase A of checkout (06 sections 3 and 5.2), MK-072: validates a cart and prices it without taking a lock or writing a
 * row. [quote] never fails for a business reason: a missing product, a bad code, an unselected variant, a limit, a
 * cooldown, an unavailable payment method are line errors and messages with `canCheckout = false`; only a malformed
 * request (too many lines, a number that overflows) is an HTTP error. Limits and cooldown are unlocked reads here; the
 * authoritative check is the checkout transaction.
 *
 * The pieces: the line rules ([LineRules], pure), the recipient ([RecipientResolver]), the price ([PricingEngine]: items,
 * shipping, credits, fee, totals), and the payment method list (`02` section 5.1: enabled, configured, provider usable,
 * the amount checks of the engine, guests, recurring offer, `checkEligibility`, the test-mode rule).
 */
class CheckoutService(
    private val config: () -> MarketConfig,
    private val clock: Clock,
    private val categories: MarketCategoryDao,
    private val products: MarketProductDao,
    private val variants: MarketProductVariantDao,
    private val prices: MarketProductPriceDao,
    private val fields: MarketProductFieldDao,
    private val bundleItems: MarketBundleItemDao,
    private val discounts: MarketDiscountDao,
    private val coupons: MarketCouponDao,
    private val creatorCodes: MarketCreatorCodeDao,
    private val currencyRates: MarketCurrencyRateDao,
    private val redemptions: MarketRedemptionDao,
    private val orders: MarketOrderDao,
    private val entitlements: MarketEntitlementDao,
    private val subscriptions: MarketSubscriptionDao,
    private val creditAccounts: MarketCreditAccountDao,
    private val carts: MarketCartDao,
    private val cartItems: MarketCartItemDao,
    private val paymentMethods: MarketPaymentMethodDao,
    private val lookup: ProviderLookup,
    private val cipher: SecretCipher,
    private val contexts: PaymentContexts,
    private val legal: LegalTextService,
    private val users: UserDirectory,
    private val servers: ServerDirectory,
    private val blocks: BuyerBlocks = BuyerBlocks.NONE,
    private val shipping: ShippingQuoter = ShippingQuoter.NONE,
    /** The wiring of [checkout]; `null` for a service that only quotes. */
    private val checkout: CheckoutDeps? = null
) {
    // ------------------------------------------------------------------------------------------------------ quote

    /** `POST /api/market/checkout/quote` (04 section 3). */
    suspend fun quote(input: QuoteInput, caller: QuoteCaller, sqlClient: SqlClient): Quote =
        assess(input, caller, sqlClient, strict = false, frozen = null).quote

    /**
     * Phase A of 06 section 5.2 as a value: everything the quote says plus the internal facts checkout needs to write the
     * order (the catalog rows, the priced lines, the resolved recipient, the selected payment method). [strict] is checkout
     * (a credit amount above what can be applied is refused, 06 section 6.6) and [frozen] is phase B inside the order
     * transaction: the payment method and the shipping option of phase A are reused, no provider, carrier or block
     * list call is made while the locks are held (06 section 13.1 rule 1).
     */
    private suspend fun assess(input: QuoteInput, caller: QuoteCaller, sqlClient: SqlClient, strict: Boolean, frozen: Frozen?, orderLocale: String? = null): Assessment {
        val c = config()
        val now = clock.now()
        // checkout names the locale of the order itself (06 section 5.4, resolved by the route, never the raw body value); the quote takes what the client asked for
        val locale = (orderLocale ?: input.locale)?.trim()?.takeIf { it.isNotEmpty() }?.take(16) ?: DEFAULT_LOCALE
        val messages = ArrayList<QuoteMessage>()
        val loggedIn = caller.loggedIn

        // ---- the cart: request lines, or the server cart of a logged-in caller who sent none
        val topUp = input.creditTopUp

        if (topUp != null && !input.items.isNullOrEmpty()) throw BadRequest()

        val cart = if (topUp == null && input.items == null && caller.userId != null) carts.getByUserId(caller.userId, sqlClient) else null
        val serverLines = cart?.let { loadServerLines(it.id, sqlClient) }
        val rawLines = if (topUp != null) emptyList() else input.items ?: serverLines.orEmpty()
        val lines = CartMerger.sumByKey(rawLines.map { it.withQuantity(CartLimits.clampQuantity(it.quantity.toLong())) })

        if (lines.size > CartLimits.MAX_LINES) throw InvalidCart(mapOf("cart" to listOf(CART_FULL)))

        val currencyAsked = input.currency ?: cart?.currency
        val couponCode = if (topUp != null) null else CartLimits.normalizeCode(input.couponCode ?: cart?.couponCode)?.takeIf { CartLimits.codeFits(it) }
        val creatorCode = if (topUp != null) null else CartLimits.normalizeCode(input.creatorCode ?: cart?.creatorCode)?.takeIf { CartLimits.codeFits(it) }
        val recipientAsked = input.recipientUsername ?: cart?.recipientUsername
        val giftMessage = input.giftMessage ?: cart?.giftMessage

        // ---- the rows the cart points at
        val catalog = loadCatalog(lines, c, now, sqlClient)

        // ---- payer, recipient
        val payerName = if (caller.userId != null) users.usernameOf(caller.userId, sqlClient) else guestOf(input)?.username
        val accountEmail = if (caller.userId != null) users.emailOf(caller.userId, sqlClient) else null
        val orderEmail = if (caller.userId != null) BuyerValidator.orderEmailOfAccount(accountEmail, input.billingInfo?.getString("email")) else guestOf(input)?.email
        val payerKey = when {
            caller.userId != null -> "u:${caller.userId}"
            !payerName.isNullOrBlank() -> "g:${payerName.lowercase(Locale.ROOT)}"
            else -> ""
        }
        val hasCreditPack = lines.any { catalog.products[it.productId]?.kind == ProductKind.CREDIT_PACK }
        val recipient = resolveRecipient(caller, payerName, recipientAsked, giftMessage, c, hasCreditPack || topUp != null, messages, sqlClient)

        if (!loggedIn && !c.allowGuestCheckout) messages += QuoteMessage(LineCode.LOGIN_REQUIRED, LineRules.ERROR)

        if (topUp != null && !loggedIn) messages += QuoteMessage(LineCode.LOGIN_REQUIRED, LineRules.ERROR)

        // ---- the line rules
        val recipientKeys = recipientKeysOf(recipient)
        val facts = loadRecipientFacts(recipientKeys, catalog, now, sqlClient)
        val ruleLines = lines.map { RuleLine(it.lineKey, it.productId, it.variantId, it.quantity, it.fieldValues, it.targetServerId, catalog.rule(it.productId)) }
        val permissionNodes = ruleLines.mapNotNull { it.product?.requiredPermission?.takeIf { n -> n.isNotBlank() } }.toSet()
        val granted = HashSet<String>()

        if (recipient?.userId != null) permissionNodes.forEach { if (users.hasPermission(recipient.userId, it)) granted += it }

        val wantedServers = ruleLines.filter { it.product?.buyerChoiceServer == true }.mapNotNull { it.targetServerId }.toSet()
        val existingServers = if (wantedServers.isEmpty()) emptySet() else servers.existing(wantedServers, sqlClient)
        val ruleContext = RuleContext(
            now = now,
            loggedIn = loggedIn,
            isGift = recipient?.isGift == true,
            hasPermission = { it in granted },
            existingServerIds = existingServers,
            usage = facts.usage,
            owned = facts.owned,
            subscribedProductIds = facts.subscribed
        )
        val rules = LineRules.evaluate(ruleLines, ruleContext)

        for (m in rules.messages) messages += QuoteMessage(m.code, m.level, m.lineKey)

        // ---- the order lines to price
        val rates = currencyRates.getAll(sqlClient).filter { it.rate.signum() > 0 }.associate { it.currency to it.rate }
        val pricingConfig = marketPricingConfig(c, rates)
        val orderCurrency = OrderCurrencies.resolve(pricingConfig, currencyAsked).currency
        val priceable = ArrayList<LineInput>()
        val byKey = lines.associateBy { it.lineKey }

        for (verdict in rules.lines) {
            if (!verdict.priceable) continue

            val line = byKey.getValue(verdict.lineKey)

            priceable += lineInput(line, verdict, catalog, orderCurrency)
        }

        var topUpMessage: QuoteMessage? = null

        if (topUp != null) {
            val reason = topUpProblem(topUp, c)

            if (reason != null) {
                topUpMessage = QuoteMessage(INVALID_CREDIT_AMOUNT, LineRules.ERROR)
            } else {
                priceable += topUpLine(topUp.credits!!)
            }
        }

        if (topUpMessage != null) messages += topUpMessage

        val buyerKeyForUse = payerKey
        val buyer = BuyerContext(
            buyerKey = payerKey,
            userId = caller.userId,
            loggedIn = loggedIn,
            recipientUserId = recipient?.userId,
            email = orderEmail,
            creditBalance = if (caller.userId != null) creditAccounts.getByUserId(caller.userId, sqlClient)?.balance ?: 0L else 0L,
            recipientTiers = facts.tiers
        )

        // ---- payment methods and the selected one
        val candidates = if (frozen != null) listOfNotNull(frozen.selected) else loadCandidates(c, sqlClient)
        val selectedId = input.paymentMethodId?.trim()?.takeIf { it.isNotEmpty() }
        val payWithCredits = topUp == null && (input.payWithCredits || selectedId == MethodInput.CREDITS)
        val selected = if (payWithCredits || selectedId == null) null else candidates.firstOrNull { it.id == selectedId }

        if (selectedId != null && selectedId != MethodInput.CREDITS && selected == null) {
            messages += QuoteMessage(PAYMENT_METHOD_UNAVAILABLE, LineRules.WARNING)
        }

        val pricingMode = selected?.input?.pricingMode ?: PricingMode.MARKET
        val coupon = if (couponCode != null) couponInput(couponCode, buyerKeyForUse, orderEmail, recipientKeys, sqlClient) else null
        val creator = if (creatorCode != null) creatorInput(creatorCode, sqlClient) else null
        val automatic = discounts.getAutomatic(sqlClient).map { a ->
            val d = a.discount

            DiscountInput(
                id = d.id, value = d.value, unit = d.unit, scope = d.scope,
                productIds = d.productIds.orEmpty().toSet(), categoryIds = d.categoryIds.orEmpty().toSet(),
                minPaymentAmount = d.minPaymentAmount, startDate = d.startDate, expiryDate = d.expiryDate,
                usageLimit = d.usageLimit, usedCount = d.usedCount
            )
        }

        val items = try {
            PricingEngine.priceItems(
                PricingInput(
                    config = pricingConfig,
                    now = now,
                    profile = PricingProfile.STOREFRONT,
                    requestedCurrency = currencyAsked,
                    lines = priceable,
                    buyer = buyer,
                    discounts = automatic,
                    coupon = coupon,
                    creatorCode = creator,
                    pricingMode = pricingMode,
                    payWithCredits = payWithCredits,
                    priceOverride = null
                )
            )
        } catch (e: PricingException) {
            if (e.error == PricingError.AMOUNT_OVERFLOW) throw InvalidCart(mapOf("cart" to listOf(AMOUNT_OVERFLOW)))

            throw e
        }

        // ---- shipping
        var shippingQuote = ShippingQuote(null)

        if (items.requiresShipping) {
            val physical = items.lines.filter { l -> l.physicalLine(catalog) }

            shippingQuote = frozen?.shipping ?: shipping.quote(
                ShippingRequest(
                    items.currency, items.itemsBasisBase, items.physicalBasisBase, physical, input.shippingAddress,
                    input.shippingAddressId ?: cart?.shippingAddressId, input.shippingMethodId ?: cart?.shippingMethodId, caller.userId,
                    physicalBasis = items.physicalBasis, conversions = items.conversions, pricesIncludeVat = items.pricesIncludeVat,
                    configVatBp = items.terms.vatBp, orderEmail = orderEmail, checkout = strict, shippable = shippableLines(items.lines, catalog)
                ),
                sqlClient
            )

            messages += shippingQuote.messages

            if (shippingQuote.charge == null && shippingQuote.messages.isEmpty()) {
                val hasAddress = input.shippingAddress != null || (input.shippingAddressId ?: cart?.shippingAddressId) != null

                messages += QuoteMessage(if (hasAddress) SHIPPING_UNAVAILABLE else SHIPPING_ADDRESS_REQUIRED, LineRules.ERROR)
            }
        }

        // ---- tender: credits, selected method, fee, totals
        val tender = TenderInput(input.useCredits.takeIf { !payWithCredits && topUp == null }?.let { creditsOf(it) }, selected?.input, strict = strict)
        val breakdown = try {
            PricingEngine.finalize(items, shippingQuote.charge, tender)
        } catch (e: PricingException) {
            if (e.error == PricingError.AMOUNT_OVERFLOW) throw InvalidCart(mapOf("cart" to listOf(AMOUNT_OVERFLOW)))

            throw e
        }
        val evaluations = if (frozen != null || (topUp != null && topUpMessage != null)) {
            emptyList()
        } else {
            PricingEngine.evaluateMethods(items, shippingQuote.charge, tender, candidates.map { it.input })
        }.associateBy { it.methodId }

        // ---- the method list
        val subscriptionLine = lines.firstOrNull { catalog.products[it.productId]?.billingMode == BillingMode.SUBSCRIPTION }
        val eligibilityBase = EligibilityContext(
            c, caller, payerKey, payerName ?: "", orderEmail, recipient, locale, breakdown, items.lines, catalog, subscriptionLine, input
        )
        val options = ArrayList<PaymentMethodOption>()

        for (candidate in candidates) {
            val evaluation = evaluations[candidate.id] ?: continue

            options += option(candidate, evaluation, eligibilityBase, locale)
        }

        val chosen = options.firstOrNull { it.id == selected?.id }

        if (chosen != null && !chosen.available) messages += QuoteMessage(chosen.unavailableReason ?: PAYMENT_METHOD_UNAVAILABLE, LineRules.ERROR)

        // ---- blocked buyer (the quote stays usable, checkout refuses)
        if (frozen == null && blocks.blocked(payerName, recipient?.username, orderEmail, caller.clientIp, caller.userId, sqlClient)) {
            messages += QuoteMessage(BUYER_BLOCKED, LineRules.WARNING)
        }

        // the tender cannot be used as asked (a gateway refused the amount, credits required, ...): say so, as an error
        breakdown.tender.unavailable?.let { messages += QuoteMessage(it.name, LineRules.ERROR) }

        // ---- engine messages, once each
        for (m in breakdown.messages) messages += QuoteMessage(m.code.name, m.level.name.lowercase(Locale.ROOT), m.lineKey)

        // ---- the legal text
        val legalView = if (frozen == null) legal.activeFor(locale, sqlClient) else null
        val legalText = legalView?.let { QuoteLegal(c.legalTextRequired, it.id, it.version, it.title) }

        // ---- the lines of the answer
        val quoteLines = quoteLines(rules.lines, lines, breakdown, catalog, separateCreditOrders(c, breakdown))
        val distinct = messages.distinct()
        val requiredFields = RequiredBuyerFields.of(
            c.billingInfoMode, selected?.caps?.requiredBuyerFields.orEmpty(),
            BillingSnapshot.cleanText(input.billingInfo?.getValue("type")).equals("COMPANY", ignoreCase = true),
            BillingSnapshot.cleanText(input.billingInfo?.getValue("country")), items.requiresShipping
        )
        val canCheckout = quoteLines.isNotEmpty() &&
            quoteLines.all { it.errors.isEmpty() } &&
            distinct.none { it.level == LineRules.ERROR } &&
            breakdown.canCheckout &&
            (chosen == null || chosen.available)

        val quote = Quote(
            currency = items.currency,
            baseCurrency = items.baseCurrency,
            displayCurrency = items.display?.currency,
            lines = quoteLines,
            pricingMode = items.pricingMode.name,
            pricesIncludeVat = items.pricesIncludeVat,
            fxRate = items.fxRate,
            display = breakdown.display?.let { QuoteDisplay(it.currency, it.rate, it.subtotal, it.total, it.gatewayAmount) },
            minimumOrderAmount = items.conversions.toOrder(items.terms.minimumOrderAmount),
            subtotal = breakdown.subtotal,
            discountTotal = breakdown.discountTotal,
            couponDiscount = breakdown.couponDiscount,
            creatorDiscount = breakdown.creatorDiscount,
            upgradeDiscount = breakdown.upgradeDiscount,
            shippingTotal = breakdown.shippingTotal,
            paymentFee = breakdown.paymentFee,
            vatTotal = breakdown.vatTotal,
            total = breakdown.total,
            credits = breakdown.credits?.let { QuoteCredits(true, c.creditName, it.balance, it.payableInCredits, it.creditTotal, it.maxApplicable, it.applied, it.appliedValue) },
            gatewayAmount = breakdown.gatewayAmount,
            coupon = items.coupon?.let { QuoteCode(it.code, it.valid, it.reason?.name) },
            creatorCode = items.creatorCode?.let { QuoteCode(it.code, it.valid, it.reason?.name) },
            requiresShipping = items.requiresShipping,
            shippingOptions = shippingQuote.options,
            shippingMethodId = shippingQuote.methodId,
            paymentMethods = options,
            requiredBuyerFields = requiredFields,
            legal = legalText,
            messages = distinct,
            canCheckout = canCheckout
        )

        return Assessment(
            quote = quote, c = c, locale = locale, lines = lines, cart = cart, usedServerCart = topUp == null && input.items == null && caller.userId != null,
            catalog = catalog, rules = rules, recipient = recipient, payerName = payerName, payerKey = payerKey, orderEmail = orderEmail,
            items = items, breakdown = breakdown, selected = selected, chosen = chosen, selectedId = selectedId, shipping = shippingQuote,
            topUp = topUp, topUpReason = topUp?.let { topUpProblem(it, c) }, payWithCredits = payWithCredits, legal = legalView,
            requiredFields = requiredFields, messages = distinct, now = now, candidates = candidates
        )
    }

    // -------------------------------------------------------------------------------------------------- checkout

    private class Payer(val key: String, val name: String, val email: String?, val userId: Long?)

    /** What phase A verified once and phase B reuses: the validated billing snapshot and the legal text the buyer accepted. */
    private class Verified(val billing: JsonObject?, val legal: LegalTextService.LegalView?)

    /**
     * `POST /api/market/checkout` (06 sections 4 and 5, 04 section 3). The route has done steps 1 and 2 (store switch, schema,
     * header, CSRF); this is step 3 on: the payer, rate limit L1 (IP before the replay lookup, buyer after it), the idempotency
     * replay, the block list, phase A (validation and pricing without a lock), phase B (one `MarketDb.tx`: the locks of
     * 00 section 8.3, the price again under them, the reservation, the inserts) and phase C (the payment start, outside any
     * transaction).
     *
     * A price that moved between A and B runs both again once; a second change is `PRICE_CHANGED` with the fresh quote.
     */
    suspend fun checkout(request: CheckoutRequest, caller: QuoteCaller, sqlClient: SqlClient): CheckoutResult {
        val deps = checkout ?: throw IllegalStateException("this CheckoutService was built without the checkout wiring")
        val c = config()
        val input = request.input
        val locale = request.orderLocale ?: DEFAULT_LOCALE
        val payer = resolvePayer(input, caller, c, sqlClient)

        limitIp(caller, c)

        orders.getByBuyerAndIdempotencyKey(payer.key, request.idempotencyKey, sqlClient)?.let { return replay(it, request, deps, sqlClient) }

        limitBuyer(payer.key, c)

        if (blocks.blocked(payer.name, input.recipientUsername, payer.email, caller.clientIp, caller.userId, sqlClient)) throw BuyerBlocked()

        precheck(input, caller, c)

        var attempts = 0
        var placed: CreatedOrder? = null
        var duplicate: MarketOrder? = null

        while (placed == null && duplicate == null) {
            attempts++

            try {
                val plan = phaseA(request, caller, sqlClient)
                val verified = verify(plan, request, caller)

                try {
                    placed = phaseB(request, caller, payer, plan, verified, deps)
                } catch (e: IdempotentReplay) {
                    duplicate = e.order
                } catch (e: QuoteChanged) {
                    // the second change in a row is the buyer's to confirm: the fresh quote goes back with 409 PRICE_CHANGED
                    if (attempts >= 2) {
                        throw PriceChanged(assess(input, caller, sqlClient, strict = false, frozen = null, orderLocale = locale).quote.toJson())
                    }
                }
            } catch (e: PanoError) {
                // a business error of this request may be the first request's own doing (its cart emptied, its last unit taken, its limit or
                // cooldown, its coupon use): when that request has committed, this one is its replay, not a failure (06 section 5.1)
                duplicate = orders.getByBuyerAndIdempotencyKey(payer.key, request.idempotencyKey, sqlClient) ?: throw e
            }
        }

        duplicate?.let { return replay(it, request, deps, sqlClient) }

        return finish(checkNotNull(placed), deps, sqlClient)
    }

    /** Step 3: who pays. A logged-in caller's `guest` object is ignored (06 section 6.1); a guest needs the setting and a valid name and e-mail. */
    private suspend fun resolvePayer(input: QuoteInput, caller: QuoteCaller, c: MarketConfig, sqlClient: SqlClient): Payer {
        if (caller.userId != null) {
            val name = users.usernameOf(caller.userId, sqlClient) ?: throw NotLoggedIn()
            val email = BuyerValidator.orderEmailOfAccount(users.emailOf(caller.userId, sqlClient), input.billingInfo?.getString("email"))
                ?: throw BuyerInfoRequired(listOf(BuyerValidator.FIELD_ORDER_EMAIL))

            return Payer("u:${caller.userId}", name, email, caller.userId)
        }

        if (!c.allowGuestCheckout) throw NotLoggedIn()

        return when (val result = BuyerValidator.validateGuest(input.guest?.username, input.guest?.email)) {
            is BuyerValidator.GuestResult.Valid -> Payer(result.guest.buyerKey, result.guest.username, result.guest.email, null)
            is BuyerValidator.GuestResult.Invalid -> throw BuyerInfoRequired(result.fields)
        }
    }

    /** Step 7 before the cart is read: the credit inputs that contradict each other or need an account, and the free-amount top-up. */
    private fun precheck(input: QuoteInput, caller: QuoteCaller, c: MarketConfig) {
        val topUp = input.creditTopUp
        val method = input.paymentMethodId?.trim()?.takeIf { it.isNotEmpty() }
        val payAll = input.payWithCredits || method == MethodInput.CREDITS
        val mixed = (input.useCredits as? UseCredits.Amount)?.credits?.let { it > 0 } == true

        if (payAll && (mixed || (input.payWithCredits && method != null && method != MethodInput.CREDITS))) throw BadRequest()

        if (!caller.loggedIn && (payAll || mixed || topUp != null)) throw NotLoggedIn()

        if ((payAll || mixed) && !c.creditsEnabled) throw PaymentMethodUnavailable("CREDITS_DISABLED")

        if (topUp != null) {
            if (!input.items.isNullOrEmpty()) throw BadRequest()

            topUpProblem(topUp, c)?.let { throw InvalidCreditAmount(it, c.creditTopUpMin, c.creditTopUpMax) }
        }
    }

    // ----- phase A

    private suspend fun phaseA(request: CheckoutRequest, caller: QuoteCaller, sqlClient: SqlClient): Assessment {
        val a = assess(request.input, caller, sqlClient, strict = true, frozen = null, orderLocale = request.orderLocale ?: DEFAULT_LOCALE)

        if (a.topUp == null && a.lines.isEmpty()) throw EmptyCart()

        return a
    }

    /** The A1 to A12 table of 06 section 5.2 in its order; throws the first failure. Phase B runs it again on the locked rows. */
    private fun failOn(a: Assessment, request: CheckoutRequest, caller: QuoteCaller, frozen: Boolean) {
        val messages = a.messages

        // PT-5 (11 section 4.1): a currency the request itself names and the store does not offer is refused here; the quote only warns and
        // prices in the base currency. A fallback that comes from the stored server cart currency (gone stale after the admin changed the
        // currency settings) is not the request's currency: the order is priced in the base currency like the quote says, and expectedTotal
        // still guards the buyer's consent
        val currencyNamed = request.input.currency?.trim()?.takeIf { it.isNotEmpty() }

        if (currencyNamed != null && a.items.messages.any { it.code == PricingCode.CURRENCY_NOT_SUPPORTED }) {
            throw InvalidCart(mapOf("cart" to listOf(PricingCode.CURRENCY_NOT_SUPPORTED.name)))
        }

        // A1
        if (messages.any { it.code == INVALID_RECIPIENT }) throw InvalidRecipient()

        if (!frozen && messages.any { it.code == BUYER_BLOCKED }) throw BuyerBlocked()

        // A2, A3
        if (messages.any { it.code == LineCode.SUBSCRIPTION_MUST_BE_ALONE }) throw SubscriptionMustBeAlone()

        if (!caller.loggedIn && a.quote.lines.any { LineCode.LOGIN_REQUIRED in it.errors }) throw NotLoggedIn()

        // A4: line rules. A `MAX_QUANTITY` that is only about stock (the cap per order is not exceeded) or that comes with a limit code is the
        // 409 of 06 section 7.1 / 6.4, not an `INVALID_CART` entry
        val lineErrors = a.quote.lines.filter { l -> badRequestCodes(a, l).isNotEmpty() }.associate { it.lineKey to it.errors }

        if (lineErrors.isNotEmpty()) throw InvalidCart(lineErrors)

        // A5
        a.items.coupon?.let { if (!it.valid) throw InvalidCoupon((it.reason ?: PricingCode.CODE_NOT_FOUND).name) }
        a.items.creatorCode?.let { if (!it.valid) throw InvalidCreatorCode((it.reason ?: PricingCode.CODE_NOT_FOUND).name) }

        // A6
        val shipping = messages.firstOrNull { it.code in SHIPPING_CODES }

        if (a.items.requiresShipping && (shipping != null || a.shipping.charge == null)) {
            when (shipping?.code) {
                SHIPPING_UNAVAILABLE -> throw ShippingUnavailable(a.shipping.reason ?: "NO_METHOD")
                SHIPPING_METHOD_REQUIRED -> throw ShippingUnavailable("METHOD_REQUIRED")
                else -> throw ShippingAddressRequired(a.shipping.fields.ifEmpty { listOf("shippingAddress") })
            }
        }

        // A7
        val tender = a.breakdown.tender

        if (a.breakdown.messages.any { it.code == PricingCode.INSUFFICIENT_CREDITS }) {
            val credits = a.breakdown.credits

            throw InsufficientCredits(money(credits?.balance ?: 0L), if (a.payWithCredits) null else money(credits?.maxApplicable ?: 0L))
        }

        tender.unavailable?.let {
            if (it == PricingCode.LOGIN_REQUIRED) throw NotLoggedIn()

            if (it in CREDIT_UNAVAILABLE) throw PaymentMethodUnavailable(it.name)
        }

        // A8: skipped when the gateway has nothing to collect
        val methodId = a.breakdown.paymentMethodId

        if (methodId != MethodInput.FREE && methodId != MethodInput.CREDITS) {
            when {
                a.selectedId == null -> throw PaymentMethodUnavailable(METHOD_REQUIRED)
                a.selected == null -> throw PaymentMethodUnavailable(METHOD_NOT_OFFERED)
            }

            a.chosen?.let { if (!it.available) throw PaymentMethodUnavailable(it.unavailableReason ?: METHOD_NOT_OFFERED) }
            tender.unavailable?.let { throw PaymentMethodUnavailable(it.name) }
        }

        // A9
        if (a.breakdown.messages.any { it.code == PricingCode.MINIMUM_ORDER_AMOUNT_NOT_REACHED }) {
            throw MinimumOrderAmountNotReached(money(a.items.conversions.toOrder(a.items.terms.minimumOrderAmount)))
        }

        // A12 (advisory in phase A, authoritative in B7 and B8): the 409 codes
        for (verdict in a.rules.lines) {
            for (detail in verdict.details) {
                when (detail.code) {
                    LineCode.REQUIREMENT_NOT_MET -> throw ProductRequirementNotMet(detail.productId)
                    LineCode.PURCHASE_LIMIT_REACHED -> throw PurchaseLimitReached(detail.productId, detail.limit ?: 0)
                    LineCode.COOLDOWN_ACTIVE -> throw CooldownActive(detail.productId, detail.retryAfterSeconds ?: 0)
                }
            }
        }

        val outOfStock = a.rules.lines.filter { LineCode.OUT_OF_STOCK in it.errors || stockShort(a, it) }.map { it.lineKey }

        if (outOfStock.isNotEmpty()) throw OutOfStock(outOfStock)
    }

    /** The codes of a quote line that make checkout answer 400 `INVALID_CART` (everything but the 409 codes, the login code and a stock-only `MAX_QUANTITY`). */
    private fun badRequestCodes(a: Assessment, line: QuoteLine): List<String> {
        val verdict = a.rules.lines.firstOrNull { it.lineKey == line.lineKey }
        val conflict = line.errors.any { it in STOCK_AND_LIMIT_CODES }

        return line.errors.filter { code ->
            when {
                code in STOCK_AND_LIMIT_CODES || code == LineCode.LOGIN_REQUIRED -> false
                code == LineCode.MAX_QUANTITY -> !conflict && !(verdict != null && stockShort(a, verdict))
                else -> true
            }
        }
    }

    /** `MAX_QUANTITY` although the per-order cap of the line is not exceeded: the stock (or the allowance) is what is short. */
    private fun stockShort(a: Assessment, verdict: LineVerdict): Boolean {
        if (LineCode.MAX_QUANTITY !in verdict.errors) return false

        val line = a.lines.firstOrNull { it.lineKey == verdict.lineKey } ?: return false
        val product = a.catalog.products[line.productId] ?: return false
        val cap = when {
            product.billingMode == BillingMode.SUBSCRIPTION -> 1
            product.billingMode == BillingMode.TIMED || a.catalog.tierOf(product) != null -> minOf(product.maxQuantityPerOrder ?: Int.MAX_VALUE, 1)
            else -> product.maxQuantityPerOrder?.let { maxOf(0, it) } ?: Int.MAX_VALUE
        }

        return line.quantity <= cap
    }

    /**
     * A10 and A11, once, in phase A: the legal acceptance (06 section 8.1) and the billing info with the fields a provider requires
     * (06 section 8.2). Run after the checks that precede them in the table, so the first failing rule wins.
     */
    private fun verify(a: Assessment, request: CheckoutRequest, caller: QuoteCaller): Verified {
        failOn(a, request, caller, frozen = false)

        // A10: a required text that exists must be the one accepted; a required text that does not exist never stops a sale
        val legalView = if (a.c.legalTextRequired) a.legal else null

        if (legalView != null && (!request.acceptLegal || request.legalTextId != legalView.id)) throw LegalAcceptanceRequired(legalView.id)

        // A11
        val have = buildSet {
            if (a.orderEmail != null) add(BuyerValidator.FIELD_ORDER_EMAIL)
            if (a.items.requiresShipping && a.shipping.charge != null) add("shippingAddress")
        }
        val billing = when (val r = BillingSnapshot.check(request.input.billingInfo, a.c.billingInfoMode, a.requiredFields, have)) {
            is BillingSnapshot.Result.Invalid -> throw BuyerInfoRequired(r.fields)
            is BillingSnapshot.Result.Valid -> r.json
        }

        // PT-12: the buyer confirmed a total; a different one is theirs to confirm again
        request.expectedTotal?.let { if (it != a.breakdown.total) throw PriceChanged(a.quote.toJson()) }

        return Verified(billing, legalView)
    }

    // ----- phase B

    private fun usesOf(a: Assessment): List<CodeUse> {
        val currency = a.items.currency
        val out = ArrayList<CodeUse>()

        a.items.coupon?.takeIf { it.valid && it.id != null }?.let { out += CodeUse(RedemptionKind.COUPON, it.id!!, it.code, a.items.couponDiscount, currency) }
        a.items.creatorCode?.takeIf { it.valid && it.id != null }?.let { out += CodeUse(RedemptionKind.CREATOR_CODE, it.id!!, it.code, a.items.creatorDiscount, currency) }
        a.items.discountRedemptions.forEach { out += CodeUse(RedemptionKind.DISCOUNT, it.discountId, null, it.amount, currency) }

        return out
    }

    /** Cart products, bundle children, in ascending order (B4). The product rows are what serialises two checkouts of one product. */
    private fun productIdsOf(a: Assessment): List<Long> {
        val ids = sortedSetOf<Long>()

        a.items.lines.forEach { l -> l.productId?.let { ids += it } }

        return ids.toList()
    }

    private fun variantIdsOf(a: Assessment): List<Long> {
        val ids = sortedSetOf<Long>()

        a.items.lines.forEach { l -> if (l.variantId != 0L) ids += l.variantId }

        return ids.toList()
    }

    private suspend fun phaseB(request: CheckoutRequest, caller: QuoteCaller, payer: Payer, plan: Assessment, verified: Verified, deps: CheckoutDeps): CreatedOrder {
        val frozen = Frozen(plan.selected, plan.shipping)
        val planUses = usesOf(plan)
        val products = productIdsOf(plan)
        val variants = variantIdsOf(plan)

        return deps.db.tx { conn ->
            // B1 to B4: cart, codes, discounts, products, variants, in the global order
            if (plan.usedServerCart && caller.userId != null) deps.locks.cart(conn, caller.userId)

            deps.redemptions.lockFor(conn, planUses)
            deps.locks.products(conn, products)
            deps.locks.variants(conn, variants)

            // the first request of the same key may have committed while this one waited for the locks: that is a replay, not a conflict
            orders.getByBuyerAndIdempotencyKey(payer.key, request.idempotencyKey, conn)?.let { throw IdempotentReplay(it) }

            // B5 to B7: the whole price and every rule again, on rows nobody can change now
            val a = assess(request.input, caller, conn, strict = true, frozen = frozen, orderLocale = request.orderLocale ?: DEFAULT_LOCALE)

            if (a.topUp == null && a.lines.isEmpty()) throw QuoteChanged("the cart is empty")

            failOn(a, request, caller, frozen = true)
            ensureUnchanged(plan, a)

            // B8, B9: stock, then codes and discounts, by conditional statements
            val uses = usesOf(a)
            val built = itemsOf(a, deps, conn)
            val customer = CustomerKeys(caller.userId, payer.key, a.orderEmail, a.recipient?.key.orEmpty(), recipientKeysOf(a.recipient))
            val reservation = try {
                deps.reservations.reserve(conn, built.demands, uses, customer)
            } catch (e: DiscountUnavailable) {
                throw QuoteChanged("discount ${e.discountId} is exhausted")
            }

            // B10, B11
            deps.orders.create(conn, draftOf(a, request, caller, payer, verified, built.items, reservation, uses, customer))
        }
    }

    /**
     * B5: the price under the locks must be the price of phase A, line for line; anything else is a changed quote. A total that
     * equals the plan's also equals the `expectedTotal` the plan was verified against (PT-12), so the consent needs no check of its own here.
     */
    private fun ensureUnchanged(plan: Assessment, a: Assessment) {
        fun same(condition: Boolean, what: String) {
            if (!condition) throw QuoteChanged(what)
        }

        same(plan.lines.associate { it.lineKey to it.quantity } == a.lines.associate { it.lineKey to it.quantity }, "lines")
        same(plan.recipient?.key == a.recipient?.key, "recipient")
        same(plan.items.discountRedemptions.map { it.discountId }.toSet() == a.items.discountRedemptions.map { it.discountId }.toSet(), "discounts")
        same(usesOf(plan).map { it.kind to it.refId }.toSet() == usesOf(a).map { it.kind to it.refId }.toSet(), "codes")
        same(plan.breakdown.total == a.breakdown.total, "total")
        same(plan.breakdown.gatewayAmount == a.breakdown.gatewayAmount && plan.breakdown.creditAmount == a.breakdown.creditAmount, "tender")
        same(plan.breakdown.paymentMethodId == a.breakdown.paymentMethodId, "method")
    }

    private class BuiltItems(val items: List<DraftItem>, val demands: List<StockDemand>)

    /** The order items (bundle line first, its children after it) and the stock each of them needs (06 section 7.1). */
    private suspend fun itemsOf(a: Assessment, deps: CheckoutDeps, conn: SqlClient): BuiltItems {
        val meta = HashMap<Long, JsonObject>()

        deps.providerMeta?.let { dao ->
            for (id in productIdsOf(a)) {
                val rows = dao.getByProductId(id, conn)
                val product = JsonObject()

                // the product level first, then the variant level on top
                for (row in rows.sortedBy { it.variantId }) {
                    val obj = runCatching { JsonObject(row.meta) }.getOrNull() ?: continue
                    val existing = product.getJsonObject(row.providerId) ?: JsonObject().also { product.put(row.providerId, it) }

                    existing.mergeIn(obj)
                }

                meta[id] = product
            }
        }

        val items = ArrayList<DraftItem>()
        val demands = ArrayList<StockDemand>()
        val children = a.items.lines.filter { it.parentLineKey != null }.groupBy { it.parentLineKey!! }

        for (line in a.items.lines.filter { it.parentLineKey == null }) {
            val product = line.productId?.let { a.catalog.products[it] }

            items += draftItem(a, line, null, meta)

            if (product != null) {
                demands += StockDemand(line.lineKey, line.lineKey, product.id, line.variantId.takeIf { it != 0L }, line.quantity)
            }

            for (child in children[line.lineKey].orEmpty()) {
                items += draftItem(a, child, line.lineKey, meta)
                child.productId?.let { demands += StockDemand(line.lineKey, child.lineKey, it, child.variantId.takeIf { v -> v != 0L }, child.quantity) }
            }
        }

        return BuiltItems(items, demands)
    }

    private fun draftItem(a: Assessment, l: PricedLine, parentKey: String?, meta: Map<Long, JsonObject>): DraftItem {
        val product = l.productId?.let { a.catalog.products[it] }
        val variant = if (l.variantId != 0L) a.catalog.variants[l.variantId] else null
        val verdict = a.rules.lines.firstOrNull { it.lineKey == l.lineKey }
        val topUp = l.kind == OrderItemKind.CREDIT_TOPUP
        val granted = when {
            topUp -> l.creditAmount
            product?.kind == ProductKind.CREDIT_PACK -> product.creditAmount?.let { Math.multiplyExact(it, l.quantity.toLong()) }
            else -> null
        }
        val name = if (topUp) "${topUpAmount(l.creditAmount)} ${a.c.creditName.ifBlank { "credits" }}" else product?.name.orEmpty()
        val physical = product?.physical == true && l.kind != OrderItemKind.BUNDLE
        val snapshot = if (topUp || product == null) {
            ItemSnapshot.topUp()
        } else {
            ItemSnapshot.of(
                SnapshotProduct(
                    slug = product.slug, imageFileName = variant?.imageFileName ?: product.imageFileName, kind = product.kind.name,
                    billingMode = product.billingMode.name, periodUnit = product.periodUnit?.name, periodCount = variant?.periodCount ?: product.periodCount,
                    physical = physical, weightGrams = variant?.weightGrams ?: product.weightGrams, lengthMm = product.lengthMm, widthMm = product.widthMm,
                    heightMm = product.heightMm, hsCode = product.hsCode, originCountry = product.originCountry,
                    tierCategoryId = a.catalog.tierOf(product)?.categoryId, tierRank = a.catalog.tierOf(product)?.rank, actions = product.actions,
                    variantAttributes = variant?.attributes, providerMeta = meta[product.id] ?: JsonObject()
                )
            )
        }
        val fieldValues = verdict?.fieldValues?.takeIf { it.isNotEmpty() }?.let { JsonObject(LinkedHashMap(it)).encode() }
        val creditOrder = a.breakdown.paymentMethodId == MethodInput.CREDITS
        val creditUnitPrice = creditUnitPriceOf(l, creditOrder)
        // the credit run's line total net of the code shares, so that a later switch to credits charges the quoted amount (see CreditRunSnapshot)
        val creditLine = creditUnitPrice?.let { a.breakdown.items.credit?.lines?.firstOrNull { it.lineKey == l.lineKey } }
        val storedSnapshot = CreditRunSnapshot.with(snapshot, creditLine?.lineTotal).encode()
        val now = clock.now()

        return DraftItem(l.lineKey, parentKey, product?.billingMode == BillingMode.SUBSCRIPTION && l.kind != OrderItemKind.BUNDLE_CHILD) { orderId, parentItemId, stockReserved ->
            MarketOrderItem(
                orderId = orderId, productId = l.productId, productName = name, quantity = l.quantity, unitPrice = l.unitPrice,
                kind = l.kind, parentItemId = parentItemId, variantId = l.variantId.takeIf { it != 0L }, variantName = variant?.name,
                sku = variant?.sku ?: product?.sku, listUnitPrice = l.listUnitPrice, discountAmount = l.discountAmount, upgradeAmount = l.upgradeAmount,
                couponAmount = l.couponAmount, vatPercent = l.vatPercent, vatAmount = l.vatAmount, lineTotal = l.lineTotal,
                creditUnitPrice = creditUnitPrice, creditAmount = granted,
                fieldValues = fieldValues, targetServerId = verdict?.targetServerId, snapshot = storedSnapshot, physical = physical,
                stockReserved = stockReserved, upgradeFromEntitlementId = l.upgradeFromEntitlementId, createdAt = now, updatedAt = now
            )
        }
    }

    /**
     * The `creditUnitPrice` snapshot of an item (07 section 4): the effective credit unit price at purchase of every `PRODUCT` / `BUNDLE` line a buyer
     * could pay with credits, whatever tender the order used now, so that `POST .../pay` can switch a pending order to `credits` later (06 section 9.3, the
     * credit run of 05 section 8.1 needs it). A line that is not sold for credits (a priced line with credit price 0, a credit pack, a top-up) and a bundle
     * child carry `NULL`, which is what makes such an order not payable in credits. A full-credit order stores it for every line, as before.
     */
    private fun creditUnitPriceOf(l: PricedLine, creditOrder: Boolean): Long? = when {
        l.kind == OrderItemKind.BUNDLE_CHILD || l.kind == OrderItemKind.CREDIT_TOPUP -> null
        creditOrder || CreditPricing.sellableForCredits(l.creditUnitPrice, l.listUnitPrice) -> l.creditUnitPrice
        else -> null
    }

    private fun topUpAmount(credits: Long): String = BigDecimal.valueOf(credits).movePointLeft(2).stripTrailingZeros().toPlainString()

    private fun draftOf(
        a: Assessment,
        request: CheckoutRequest,
        caller: QuoteCaller,
        payer: Payer,
        verified: Verified,
        items: List<DraftItem>,
        reservation: Reservation,
        uses: List<CodeUse>,
        customer: CustomerKeys
    ): OrderDraft {
        val b = a.breakdown
        val methodId = checkNotNull(b.paymentMethodId) { "an order without a payment method" }
        val recipient = checkNotNull(a.recipient) { "an order without a recipient" }
        val now = clock.now()
        val timings = TimingConfig(a.c.orderExpiryMinutes, a.c.bankTransferExpiryHours)
        val windowMinutes = a.selected?.caps?.paymentWindowMinutes
        val window = OrderTimings.providerWindowMs(methodId, windowMinutes, timings)
        val label = labelOf(a, methodId)
        val testMode = a.c.testMode || a.selected?.testMode == true
        val coupon = a.items.coupon?.takeIf { it.valid }
        val creator = a.items.creatorCode?.takeIf { it.valid }
        // 02 section 5.1 / 01 section 5.1: a provider that delivers by itself (`fulfillment = GATEWAY`) is stored at O1, so no market delivery is planned next to it
        val chosen = a.selected?.takeIf { it.id == methodId }
        val fulfillmentBy = if (chosen?.caps?.fulfillment == FulfillmentAuthority.GATEWAY) FulfillmentBy.GATEWAY else FulfillmentBy.MARKET

        check(b.gatewayAmount + b.creditValue == b.total) { "gateway ${b.gatewayAmount} + credit value ${b.creditValue} != total ${b.total}" }

        val build = { publicId: String, accessToken: String ->
            MarketOrder(
                userId = caller.userId, playerUsername = payer.name, totalPrice = b.total, currency = a.items.currency, paymentMethodId = methodId,
                paymentLabel = label, status = OrderStatus.PENDING, createdAt = now, updatedAt = now, publicId = publicId, accessToken = accessToken,
                source = OrderSource.STOREFRONT, buyerKey = payer.key, idempotencyKey = request.idempotencyKey, idempotencyHash = request.bodyHash,
                email = a.orderEmail?.lowercase(Locale.ROOT), locale = a.locale, clientIp = caller.clientIp, userAgent = caller.userAgent?.take(255),
                recipientUsername = recipient.username, recipientUserId = recipient.userId, recipientKey = recipient.key, isGift = recipient.isGift,
                giftMessage = if (recipient.isGift) recipient.giftMessage else null, hideFromBroadcast = request.hideFromBroadcast,
                reservationState = ReservationState.HELD, fulfillmentBy = fulfillmentBy,
                expiresAt = OrderTimings.orderExpiresAtOnCreate(now, methodId, windowMinutes, timings),
                baseCurrency = a.items.baseCurrency, fxRate = a.items.fxRate, displayCurrency = a.items.display?.currency, displayRate = a.items.display?.rate,
                pricingMode = DbPricingMode.valueOf(a.items.pricingMode.name), pricesIncludeVat = a.items.pricesIncludeVat, subtotal = b.subtotal,
                discountTotal = b.discountTotal, couponDiscount = b.couponDiscount, creatorDiscount = b.creatorDiscount, upgradeDiscount = b.upgradeDiscount,
                shippingTotal = b.shippingTotal, shippingVatPercent = b.shippingVatPercent, shippingVatAmount = b.shippingVat, paymentFee = b.paymentFee,
                paymentFeeVatPercent = b.tender.paymentFeeVatPercent, paymentFeeVatAmount = b.tender.paymentFeeVatAmount, vatTotal = b.vatTotal,
                creditAmount = b.creditAmount, creditValue = b.creditValue, gatewayAmount = b.gatewayAmount, couponId = coupon?.id, creatorCodeId = creator?.id,
                couponCode = coupon?.code, creatorCode = creator?.code, testMode = testMode, requiresShipping = a.items.requiresShipping,
                shippingStatus = if (a.items.requiresShipping) ShippingStatus.PENDING else ShippingStatus.NOT_REQUIRED,
                shippingAddress = if (a.items.requiresShipping) (a.shipping.address ?: request.input.shippingAddress)?.encode() else null,
                shippingMethodId = if (a.items.requiresShipping) a.shipping.methodId else null,
                shippingMethodName = if (a.items.requiresShipping) a.shipping.methodName else null,
                shippingQuote = if (a.items.requiresShipping) a.shipping.snapshot?.encode() else null,
                shippingWeightGrams = if (a.items.requiresShipping) a.shipping.weightGrams else null,
                billingInfo = verified.billing?.encode(), legalTextId = verified.legal?.id, legalAcceptedAt = verified.legal?.let { now }
            )
        }

        return OrderDraft(
            order = build, items = items, reservation = reservation, uses = uses, customer = customer,
            attempt = AttemptDraft(
                providerId = methodId, methodLabel = label, expiresAt = OrderTimings.attemptExpiresAt(now, window, OrderTimings.hardCap(now, window)),
                testMode = testMode, clientIp = caller.clientIp, userAgent = caller.userAgent?.take(255)
            ),
            clearCartOfUser = if (a.usedServerCart) caller.userId else null,
            actorUserId = caller.userId
        )
    }

    /** `paymentLabel`: the method's own label, else the provider's name in the order locale (`credits` / `free` have none of their own). */
    private fun labelOf(a: Assessment, methodId: String): String {
        val selected = a.selected

        if (selected != null) return selected.row.customLabel?.takeIf { it.isNotBlank() } ?: selected.provider.descriptor.displayName.resolve(a.locale)

        return lookup.payment(methodId)?.provider?.descriptor?.displayName?.resolve(a.locale) ?: methodId
    }

    // ----- phase C and the answer

    private suspend fun finish(created: CreatedOrder, deps: CheckoutDeps, sqlClient: SqlClient): CheckoutResult {
        val token = checkNotNull(created.order.accessToken)
        val start = try {
            deps.starter.start(created.order, created.attempt, sqlClient)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val code = (e as? PaymentStartFailed)?.code ?: ProviderErrorCode.INTERNAL.name

            logger.warn("the payment of order {} could not be started: {}", created.order.id, code)

            throw PaymentProviderError(code, deps.orders.ownerView(orders.getById(created.order.id, sqlClient) ?: created.order, sqlClient), token)
        }
        val order = orders.getById(created.order.id, sqlClient) ?: created.order

        return CheckoutResult(deps.orders.ownerView(order, sqlClient, start), token, start ?: completedStart(order))
    }

    private fun completedStart(order: MarketOrder): JsonObject? = if (order.status == OrderStatus.COMPLETED) JsonObject().put("kind", "COMPLETED") else null

    /**
     * A request that was already placed (06 section 5.1): another body for the key is `IDEMPOTENCY_CONFLICT`; the same body gets the order
     * as it is now: the stored start of the newest attempt when it is `PENDING` / `PROCESSING`, a wait of up to
     * [CheckoutDeps.replayWaitMs] while it is still `CREATED` (the first request is talking to the gateway; then `payment: null`), a
     * 502 with the order when it `FAILED`, `{kind: COMPLETED}` for a completed order.
     */
    private suspend fun replay(existing: MarketOrder, request: CheckoutRequest, deps: CheckoutDeps, sqlClient: SqlClient): CheckoutResult {
        if (existing.idempotencyHash != request.bodyHash) throw IdempotencyConflict()

        var order = existing
        var attempt = deps.payments.getByOrderId(order.id, sqlClient).lastOrNull()
        var polls = 0L
        val maxPolls = if (deps.replayPollMs <= 0) 0 else deps.replayWaitMs / deps.replayPollMs

        while (attempt != null && attempt.status == PaymentStatus.CREATED && order.status == OrderStatus.PENDING && polls < maxPolls) {
            delay(deps.replayPollMs)

            polls++
            order = orders.getById(order.id, sqlClient) ?: order
            attempt = deps.payments.getByOrderId(order.id, sqlClient).lastOrNull()
        }

        val token = checkNotNull(order.accessToken)

        if (attempt != null && attempt.status == PaymentStatus.FAILED) {
            throw PaymentProviderError(attempt.failureCode ?: ProviderErrorCode.INTERNAL.name, deps.orders.ownerView(order, sqlClient), token)
        }

        val start = if (order.status == OrderStatus.COMPLETED) {
            completedStart(order)
        } else if (attempt != null && (attempt.status == PaymentStatus.PENDING || attempt.status == PaymentStatus.PROCESSING)) {
            deps.starter.served(attempt, sqlClient)
        } else {
            null
        }

        return CheckoutResult(deps.orders.ownerView(order, sqlClient, start), token, start)
    }

    // ----- rate limit L1 (11 section 11): IP before the replay lookup, buyer after it

    private class Limiters(val perMinute: Int, val ip: RateLimiter, val buyer: RateLimiter)

    @Volatile
    private var limiters: Limiters? = null

    private fun limitersFor(c: MarketConfig): Limiters? {
        val perMinute = c.checkoutRateLimitPerMinute

        if (perMinute <= 0) return null

        return limiters?.takeIf { it.perMinute == perMinute }
            ?: Limiters(perMinute, RateLimiter(perMinute, AbuseLimits.refillMs(perMinute)!!), RateLimiter(perMinute, AbuseLimits.refillMs(perMinute)!!)).also { limiters = it }
    }

    private fun limitIp(caller: QuoteCaller, c: MarketConfig) {
        val l = limitersFor(c) ?: return
        val bucket = IpRange.bucketKey(caller.clientIp) ?: return

        if (!l.ip.tryAcquire("ip:$bucket")) throw TooManyRequests(retryAfterSeconds(l.perMinute))
    }

    private fun limitBuyer(buyerKey: String, c: MarketConfig) {
        val l = limitersFor(c) ?: return

        if (!l.buyer.tryAcquire("b:$buyerKey")) throw TooManyRequests(retryAfterSeconds(l.perMinute))
    }

    private fun retryAfterSeconds(perMinute: Int): Long = maxOf(1L, Math.ceil(60.0 / perMinute).toLong())

    // ---------------------------------------------------------------------------------------------------- the cart

    private suspend fun loadServerLines(cartId: Long, sqlClient: SqlClient): List<CartLine> =
        cartItems.getByCartId(cartId, sqlClient).map {
            val values = CartLineKey.normalize(it.fieldValues?.takeIf { v -> v.isNotBlank() }?.let { v -> runCatching { JsonObject(v).map }.getOrNull() })

            CartLine(it.productId, it.variantId, it.quantity, values, it.targetServerId)
        }

    private fun guestOf(input: QuoteInput): BuyerValidator.Guest? {
        val guest = input.guest ?: return null

        return (BuyerValidator.validateGuest(guest.username, guest.email) as? BuyerValidator.GuestResult.Valid)?.guest
    }

    private suspend fun resolveRecipient(
        caller: QuoteCaller,
        payerName: String?,
        recipientAsked: String?,
        giftMessage: String?,
        c: MarketConfig,
        hasCreditPack: Boolean,
        messages: MutableList<QuoteMessage>,
        sqlClient: SqlClient
    ): RecipientResolver.Recipient? {
        // a guest who has not said who they are, buying for nobody in particular: per-player rules cannot be judged yet
        if (caller.userId == null && payerName.isNullOrBlank() && recipientAsked.isNullOrBlank()) return null

        val result = RecipientResolver.resolve(
            RecipientResolver.Payer(caller.userId, payerName.orEmpty()), recipientAsked, giftMessage, c.allowGiftPurchase, hasCreditPack
        ) { name -> users.byUsername(name, sqlClient)?.let { RecipientResolver.KnownPlayer(it.id, it.username) } }

        return when (result) {
            is RecipientResolver.Result.Resolved -> {
                result.recipient.messages.forEach { messages += QuoteMessage(it.code, it.level, it.lineKey) }

                result.recipient
            }

            is RecipientResolver.Result.Rejected -> {
                messages += QuoteMessage(INVALID_RECIPIENT, LineRules.ERROR)

                null
            }
        }
    }

    /** `K` of 06 section 6.2: the recipient's key and, for a registered player, the twin `g:<name>` of rows written before the rewrite job. */
    private fun recipientKeysOf(recipient: RecipientResolver.Recipient?): List<String> {
        if (recipient == null || recipient.username.isBlank()) return emptyList()

        val guestKey = "g:${recipient.username.lowercase(Locale.ROOT)}"

        return if (recipient.key == guestKey) listOf(guestKey) else listOf(recipient.key, guestKey)
    }

    // ------------------------------------------------------------------------------------------- catalog facts

    private class Catalog(
        val products: Map<Long, MarketProduct>,
        val variants: Map<Long, MarketProductVariant>,
        val children: Map<Long, List<ChildRow>>,
        val fields: Map<Long, List<MarketProductField>>,
        val categories: Map<Long, MarketCategory>,
        val live: Set<Long>,
        val prices: Map<Long, List<CurrencyPriceResolver.PriceRow>>,
        val now: Long,
        val c: MarketConfig
    ) {
        class ChildRow(val productId: Long, val variantId: Long, val quantity: Int)

        private val cache = HashMap<Long, RuleProduct?>()

        fun rule(productId: Long): RuleProduct? = cache.getOrPut(productId) { products[productId]?.let { build(it, withChildren = true) } }

        private fun categoryActive(product: MarketProduct): Boolean {
            var id = product.categoryId ?: return true
            val seen = HashSet<Long>()

            while (true) {
                val category = categories[id] ?: return false

                if (category.status != MarketStatus.ACTIVE || !seen.add(id)) return false

                id = category.parentId ?: return true
            }
        }

        fun categoryPath(categoryId: Long?): List<Long> {
            val out = ArrayList<Long>()
            var current = categoryId

            while (current != null && current !in out) {
                out += current
                current = categories[current]?.parentId
            }

            return out
        }

        fun tierOf(product: MarketProduct): RuleTier? {
            val category = product.categoryId?.let { categories[it] } ?: return null
            val rank = product.tierRank

            return if (category.tiered && rank != null && product.kind == ProductKind.STANDARD) RuleTier(category.id, rank) else null
        }

        private fun build(p: MarketProduct, withChildren: Boolean): RuleProduct {
            val childRules = if (withChildren && p.kind == ProductKind.BUNDLE) {
                children[p.id].orEmpty().map { row -> RuleChild(products[row.productId]?.let { build(it, withChildren = false) }, row.variantId, row.quantity) }
            } else {
                emptyList()
            }

            return RuleProduct(
                id = p.id,
                kind = p.kind,
                billingMode = p.billingMode,
                status = p.status,
                deleted = p.deletedAt != null,
                categoryActive = categoryActive(p),
                durationType = p.durationType,
                durationStart = p.durationStart,
                durationExpiry = p.durationExpiry,
                hasVariants = p.hasVariants,
                variants = variants.values.filter { it.productId == p.id }.associate {
                    it.id to RuleVariant(it.id, it.productId, it.status == MarketStatus.ACTIVE, it.deletedAt != null, it.stock)
                },
                stock = p.stock,
                maxQuantityPerOrder = p.maxQuantityPerOrder,
                limitPerPlayer = p.limitPerPlayer,
                cooldownSeconds = p.cooldownSeconds,
                requiredProducts = p.requiredProducts.filter { it in live },
                requireOnlyOne = p.requireOnlyOne,
                requiredPermission = p.requiredPermission,
                allowGift = p.allowGift,
                tier = tierOf(p),
                fields = fields[p.id].orEmpty().map { f ->
                    RuleField(f.fieldKey, f.type, f.required, optionValues(f.options), f.pattern, f.minLength, f.maxLength, f.minValue, f.maxValue)
                },
                serverChoices = longArray(p.serverChoices),
                buyerChoiceServer = buyerChoice(p.actions) || childrenBuyerChoice(p),
                children = childRules
            )
        }

        private fun childrenBuyerChoice(p: MarketProduct): Boolean =
            p.kind == ProductKind.BUNDLE && children[p.id].orEmpty().any { row -> products[row.productId]?.actions?.let { buyerChoice(it) } == true }
    }

    private suspend fun loadCatalog(lines: List<CartLine>, c: MarketConfig, now: Long, sqlClient: SqlClient): Catalog {
        val lineProducts = products.getByIds(lines.map { it.productId }.distinct().filter { it > 0 }, sqlClient)
        val byId = HashMap<Long, MarketProduct>()

        lineProducts.forEach { byId[it.id] = it }

        // bundle children, then the prerequisites of every product
        val children = HashMap<Long, List<Catalog.ChildRow>>()

        for (bundle in lineProducts.filter { it.kind == ProductKind.BUNDLE }) {
            children[bundle.id] = bundleItems.getByBundleProductId(bundle.id, sqlClient).map { Catalog.ChildRow(it.productId, it.variantId, it.quantity) }
        }

        val wanted = LinkedHashSet<Long>()

        children.values.forEach { rows -> rows.forEach { wanted += it.productId } }
        lineProducts.forEach { p -> wanted += p.requiredProducts }
        wanted.removeAll(byId.keys)

        if (wanted.isNotEmpty()) products.getByIds(wanted.toList(), sqlClient).forEach { byId[it.id] = it }

        val variantIds = LinkedHashSet<Long>()

        lines.forEach { if (it.variantId != 0L) variantIds += it.variantId }
        children.values.forEach { rows -> rows.forEach { if (it.variantId != 0L) variantIds += it.variantId } }

        val variantRows = if (variantIds.isEmpty()) emptyMap() else variants.getByIds(variantIds.toList(), sqlClient).associateBy { it.id }
        val fieldRows = fields.getByProductIds(lineProducts.map { it.id }, sqlClient).groupBy { it.productId }
        val priceRows = prices.getAll(sqlClient).groupBy({ it.productId }) { CurrencyPriceResolver.PriceRow(it.variantId, it.currency, it.price) }
        val live = byId.values.filter { it.deletedAt == null }.map { it.id }.toSet()

        return Catalog(byId, variantRows, children, fieldRows, categories.getAll(null, sqlClient).associateBy { it.id }, live, priceRows, now, c)
    }

    private class RecipientFacts(
        val usage: Map<Long, ProductUsage>,
        val owned: List<OwnedEntitlement>,
        val tiers: List<OwnedTier>,
        val subscribed: Set<Long>
    )

    private suspend fun loadRecipientFacts(keys: List<String>, catalog: Catalog, now: Long, sqlClient: SqlClient): RecipientFacts {
        if (keys.isEmpty()) return RecipientFacts(emptyMap(), emptyList(), emptyList(), emptySet())

        val productIds = LinkedHashSet<Long>()

        catalog.products.values.filter { it.deletedAt == null }.forEach { productIds += it.id }

        val usage = orders.usageByProduct(keys, productIds, sqlClient).mapValues { ProductUsage(it.value.used, it.value.lastOrderAt) }
        val owned = keys.flatMap { entitlements.getActiveByOwner(it, now, sqlClient) }.distinctBy { it.id }
        val subscribed = keys.flatMap { subscriptions.getByOwnerKey(it, sqlClient) }
            .filter { it.status in LIVE_SUBSCRIPTIONS }
            .map { it.productId }
            .toSet()

        return RecipientFacts(
            usage = usage,
            owned = owned.map { OwnedEntitlement(it.productId, it.tierCategoryId, it.tierRank) },
            tiers = owned.mapNotNull { e ->
                val category = e.tierCategoryId
                val rank = e.tierRank

                if (category == null || rank == null) null else OwnedTier(e.id, category, rank, e.pricePaid)
            },
            subscribed = subscribed
        )
    }

    // -------------------------------------------------------------------------------------------- pricing input

    private fun lineInput(line: CartLine, verdict: LineVerdict, catalog: Catalog, orderCurrency: String): LineInput {
        val product = catalog.products.getValue(line.productId)
        val variant = if (line.variantId != 0L) catalog.variants[line.variantId] else null
        val rows = catalog.prices[product.id].orEmpty()
        val explicit = CurrencyPriceResolver.resolve(orderCurrency, line.variantId, variant?.price != null, rows)
        val children = if (product.kind == ProductKind.BUNDLE) {
            catalog.children[product.id].orEmpty().map {
                BundleChild(it.productId, it.variantId, it.quantity, catalog.products[it.productId]?.physical == true)
            }
        } else {
            emptyList()
        }
        val tier = catalog.tierOf(product)

        return LineInput(
            lineKey = line.lineKey,
            productId = product.id,
            variantId = line.variantId,
            kind = when (product.kind) {
                ProductKind.STANDARD -> LineKind.PRODUCT
                ProductKind.BUNDLE -> LineKind.BUNDLE
                ProductKind.CREDIT_PACK -> LineKind.CREDIT_PACK
            },
            quantity = verdict.quantity,
            basePrice = variant?.price ?: product.price,
            currencyPrices = if (explicit != null) mapOf(orderCurrency to explicit) else emptyMap(),
            creditPrice = variant?.creditPrice ?: product.creditPrice,
            vatBp = product.vatPercent,
            categoryPath = catalog.categoryPath(product.categoryId),
            physical = product.physical,
            subscription = product.billingMode == BillingMode.SUBSCRIPTION,
            tier = tier?.let { t -> TierInfo(t.categoryId, t.rank, catalog.categories.getValue(t.categoryId).upgradeMode) },
            topUpCredits = null,
            children = children
        )
    }

    private fun topUpLine(credits: Long) = LineInput(
        lineKey = TOP_UP_LINE_KEY, productId = null, variantId = 0, kind = LineKind.CREDIT_TOPUP, quantity = 1, basePrice = 0, currencyPrices = emptyMap(),
        creditPrice = 0, vatBp = null, categoryPath = emptyList(), physical = false, subscription = false, tier = null, topUpCredits = credits, children = emptyList()
    )

    /** 07 section 8.2: `TOPUP_DISABLED`, `NOT_A_NUMBER`, `BELOW_MINIMUM`, `ABOVE_MAXIMUM`; `null` = fine. */
    private fun topUpProblem(request: TopUpRequest, c: MarketConfig): String? {
        val credits = request.credits

        return when {
            !c.creditsEnabled || !c.creditTopUpEnabled || !c.creditTopUpFreeAmount -> "TOPUP_DISABLED"
            credits == null || credits <= 0 -> "NOT_A_NUMBER"
            credits < (c.creditTopUpMin * 100).toLong() -> "BELOW_MINIMUM"
            credits > (c.creditTopUpMax * 100).toLong() -> "ABOVE_MAXIMUM"
            else -> null
        }
    }

    private fun creditsOf(use: UseCredits): Long = when (use) {
        UseCredits.Max -> com.panomc.plugins.market.core.pricing.MixedPayment.MAX
        is UseCredits.Amount -> use.credits
    }

    private suspend fun couponInput(code: String, buyerKey: String, email: String?, recipientKeys: List<String>, sqlClient: SqlClient): CouponInput {
        val row = coupons.getByCode(code, sqlClient)
            ?: return CouponInput(false, 0, code, false, 0, DiscountUnit.PERCENT, CouponScope.ALL, emptySet(), emptySet(), null, null, null, null, null, 0, 0)
        val uses = if (row.customerRedeemLimit != null && buyerKey.isNotEmpty()) {
            redemptions.countForCustomer(RedemptionKind.COUPON, row.id, buyerKey, email?.lowercase(Locale.ROOT), recipientKeys, sqlClient).toInt()
        } else {
            0
        }

        return CouponInput(
            found = true, id = row.id, code = row.code, active = row.status == MarketStatus.ACTIVE, discount = row.discount, unit = row.unit,
            scope = row.scope, productIds = row.productIds.orEmpty().toSet(), categoryIds = emptySet(), minPaymentAmount = row.minPaymentAmount,
            startDate = row.startDate, expiryDate = row.expiryDate, redeemLimit = row.redeemLimit, customerRedeemLimit = row.customerRedeemLimit,
            usedCount = row.usedCount, buyerUses = uses
        )
    }

    private suspend fun creatorInput(code: String, sqlClient: SqlClient): CreatorCodeInput {
        val row = creatorCodes.getByCode(code, sqlClient)
            ?: return CreatorCodeInput(false, 0, code, false, 0, DiscountUnit.PERCENT, 0, null, null, null, null, 0)
        val creator: DirectoryUser? = row.creator.takeIf { it.isNotBlank() }?.let { users.byUsername(it, sqlClient) }

        return CreatorCodeInput(
            found = true, id = row.id, code = row.code, active = row.status == MarketStatus.ACTIVE, discount = row.discount, unit = row.unit,
            commissionBp = row.commissionPercent, creatorUserId = creator?.id, startDate = row.startDate, expiryDate = row.expiryDate,
            redeemLimit = row.redeemLimit, usedCount = row.usedCount, creatorEmail = creator?.let { users.emailOf(it.id, sqlClient) }
        )
    }

    /**
     * The physical lines after bundle expansion (10 section 2.2): a physical product line as it is, a bundle as its physical children with
     * the bundle's `lineTotal` shared over their units. `null` when a physical product has no usable weight: such a cart is never priced
     * (the quote says there is no method), it is not shipped as if it weighed nothing.
     */
    private fun shippableLines(lines: List<PricedLine>, catalog: Catalog): List<ShippableLine>? {
        val out = ArrayList<ShippableLine>()
        val childrenOf = lines.filter { it.parentLineKey != null }.groupBy { it.parentLineKey!! }

        fun shippable(l: PricedLine, product: MarketProduct, value: Long): ShippableLine? {
            val variant = if (l.variantId != 0L) catalog.variants[l.variantId] else null
            val weight = variant?.weightGrams ?: product.weightGrams

            if (weight == null || weight <= 0 || l.quantity <= 0 || value < 0) return null

            return ShippableLine(
                orderItemId = null, productId = product.id, variantId = l.variantId, name = product.name, sku = variant?.sku ?: product.sku,
                quantity = l.quantity, unitWeightGrams = weight, lengthMm = product.lengthMm, widthMm = product.widthMm, heightMm = product.heightMm,
                lineValue = value, hsCode = product.hsCode, originCountry = product.originCountry
            )
        }

        for (l in lines) {
            if (l.excluded || l.parentLineKey != null) continue

            val product = l.productId?.let { catalog.products[it] } ?: continue

            when (l.kind) {
                OrderItemKind.PRODUCT -> if (product.physical) out += shippable(l, product, l.lineTotal) ?: return null

                OrderItemKind.BUNDLE -> {
                    val children = childrenOf[l.lineKey].orEmpty().filter { c -> c.productId?.let { catalog.products[it]?.physical } == true }

                    if (children.isNotEmpty()) {
                        val values = ShippableLines.splitBundleValue(l.lineTotal, children.map { it.quantity })

                        children.forEachIndexed { i, c -> out += shippable(c, catalog.products.getValue(c.productId!!), values[i]) ?: return null }
                    }
                }

                else -> Unit
            }
        }

        return out
    }

    private fun PricedLine.physicalLine(catalog: Catalog): Boolean {
        val id = productId ?: return false
        val product = catalog.products[id] ?: return false

        return if (product.kind == ProductKind.BUNDLE) catalog.children[id].orEmpty().any { catalog.products[it.productId]?.physical == true } else product.physical
    }

    // ------------------------------------------------------------------------------------------ answer lines

    /**
     * 07 section 13: in an `onlyAcceptCredits` store a cart that mixes credit purchases (packs, the free amount) with products is the combined cart; its
     * credit-purchase lines carry the line error `CREDIT_PACK_SEPARATE_ORDER` (the product lines already carry the engine's `CREDITS_ONLY`), so the quote cannot
     * be checked out and checkout answers 400 `INVALID_CART`.
     */
    private fun separateCreditOrders(c: MarketConfig, breakdown: PriceBreakdown): Boolean =
        c.creditsEnabled && c.onlyAcceptCredits &&
            CreditEligibility.classify(breakdown.lines.filter { it.parentLineKey == null }.map { it.lineKind }) == CreditEligibility.CartClass.COMBINED

    private fun quoteLines(verdicts: List<LineVerdict>, lines: List<CartLine>, breakdown: PriceBreakdown, catalog: Catalog, separateOrders: Boolean): List<QuoteLine> {
        val priced = breakdown.lines.associateBy { it.lineKey }
        val childrenOf = breakdown.lines.filter { it.parentLineKey != null }.groupBy { it.parentLineKey!! }
        val byKey = lines.associateBy { it.lineKey }
        val out = ArrayList<QuoteLine>()

        for (verdict in verdicts) {
            val line = byKey.getValue(verdict.lineKey)
            val product = catalog.products[line.productId]
            val variant = if (line.variantId != 0L) catalog.variants[line.variantId] else null
            val pricedLine = priced[verdict.lineKey]
            val errors = LinkedHashSet<String>(verdict.errors)

            pricedLine?.errors?.forEach { errors += it.name }

            if (separateOrders && pricedLine?.lineKind == LineKind.CREDIT_PACK) errors += CREDIT_PACK_SEPARATE_ORDER

            out += QuoteLine(
                lineKey = verdict.lineKey,
                productId = line.productId,
                variantId = line.variantId,
                name = product?.name.orEmpty(),
                variantName = variant?.name,
                slug = product?.slug,
                imageFileName = variant?.imageFileName ?: product?.imageFileName,
                quantity = pricedLine?.quantity ?: verdict.quantity,
                maxQuantity = verdict.maxQuantity,
                listUnitPrice = pricedLine?.listUnitPrice ?: 0,
                unitPrice = pricedLine?.unitPrice ?: 0,
                discountAmount = pricedLine?.discountAmount ?: 0,
                upgradeAmount = pricedLine?.upgradeAmount ?: 0,
                couponAmount = pricedLine?.couponAmount ?: 0,
                vatPercent = pricedLine?.vatPercent ?: 0,
                vatAmount = pricedLine?.vatAmount ?: 0,
                lineTotal = pricedLine?.lineTotal ?: 0,
                creditUnitPrice = pricedLine?.creditUnitPrice ?: 0,
                fieldValues = verdict.fieldValues,
                targetServerId = verdict.targetServerId,
                physical = product?.let { it.physical || (it.kind == ProductKind.BUNDLE && catalog.children[it.id].orEmpty().any { c -> catalog.products[c.productId]?.physical == true }) } ?: false,
                kind = pricedLine?.lineKind?.name ?: kindOf(product),
                parentLineKey = null,
                billingMode = (product?.billingMode ?: BillingMode.ONE_TIME).name,
                errors = errors.toList()
            )

            childrenOf[verdict.lineKey].orEmpty().forEach { child ->
                val childProduct = child.productId?.let { catalog.products[it] }

                out += QuoteLine(
                    lineKey = child.lineKey, productId = child.productId, variantId = child.variantId, name = childProduct?.name.orEmpty(),
                    variantName = if (child.variantId != 0L) catalog.variants[child.variantId]?.name else null, slug = childProduct?.slug,
                    imageFileName = childProduct?.imageFileName, quantity = child.quantity, maxQuantity = 0, listUnitPrice = 0, unitPrice = 0,
                    discountAmount = 0, upgradeAmount = 0, couponAmount = 0, vatPercent = 0, vatAmount = 0, lineTotal = 0, creditUnitPrice = 0,
                    fieldValues = emptyMap(), targetServerId = null, physical = childProduct?.physical == true, kind = "BUNDLE_CHILD",
                    parentLineKey = verdict.lineKey, billingMode = (childProduct?.billingMode ?: BillingMode.ONE_TIME).name, errors = emptyList()
                )
            }
        }

        // the synthetic top-up line has no cart line: it is priced, not judged
        breakdown.lines.firstOrNull { it.lineKey == TOP_UP_LINE_KEY }?.let { l ->
            out += QuoteLine(
                lineKey = l.lineKey, productId = null, variantId = 0, name = "", variantName = null, slug = null, imageFileName = null, quantity = 1,
                maxQuantity = 1, listUnitPrice = l.listUnitPrice, unitPrice = l.unitPrice, discountAmount = 0, upgradeAmount = 0, couponAmount = 0,
                vatPercent = l.vatPercent, vatAmount = l.vatAmount, lineTotal = l.lineTotal, creditUnitPrice = 0, fieldValues = emptyMap(),
                targetServerId = null, physical = false, kind = "CREDIT_TOPUP", parentLineKey = null, billingMode = BillingMode.ONE_TIME.name,
                errors = l.errors.map { it.name }
            )
        }

        return out
    }

    private fun kindOf(product: MarketProduct?): String = when (product?.kind) {
        ProductKind.BUNDLE -> "BUNDLE"
        ProductKind.CREDIT_PACK -> "CREDIT_PACK"
        else -> "PRODUCT"
    }

    // ----------------------------------------------------------------------------------------- payment methods

    /** What phase B keeps from phase A: no provider, carrier or block-list call happens inside the order transaction. */
    private class Frozen(val selected: Candidate?, val shipping: ShippingQuote)

    /** The quote and every internal fact checkout needs to write the order. */
    private class Assessment(
        val quote: Quote,
        val c: MarketConfig,
        val locale: String,
        val lines: List<CartLine>,
        val cart: MarketCart?,
        val usedServerCart: Boolean,
        val catalog: Catalog,
        val rules: RuleResult,
        val recipient: RecipientResolver.Recipient?,
        val payerName: String?,
        val payerKey: String,
        val orderEmail: String?,
        val items: ItemsResult,
        val breakdown: PriceBreakdown,
        val selected: Candidate?,
        val chosen: PaymentMethodOption?,
        val selectedId: String?,
        val shipping: ShippingQuote,
        val topUp: TopUpRequest?,
        val topUpReason: String?,
        val payWithCredits: Boolean,
        val legal: LegalTextService.LegalView?,
        val requiredFields: List<String>,
        val messages: List<QuoteMessage>,
        val now: Long,
        val candidates: List<Candidate>
    )

    private class Candidate(
        val id: String,
        val provider: PaymentProvider,
        val row: MarketPaymentMethod,
        val caps: PaymentCapabilities,
        val settings: ProviderSettings,
        val input: MethodInput,
        val testMode: Boolean
    )

    /** Enabled, configured, usable providers in panel order (`position`, then id). `credits` and `free` are tenders, never listed. */
    private suspend fun loadCandidates(c: MarketConfig, sqlClient: SqlClient): List<Candidate> {
        val out = ArrayList<Candidate>()

        for (row in paymentMethods.getAll(sqlClient).filter { it.enabled }.sortedWith(compareBy({ it.position }, { it.methodId }))) {
            if (row.methodId == MethodInput.CREDITS || row.methodId == MethodInput.FREE) continue

            val resolved = lookup.payment(row.methodId) ?: continue
            val provider = resolved.provider

            try {
                val codec = SettingsCodec(provider.settingsSchema(), cipher)
                val stored = runCatching { JsonObject(row.settings) }.getOrNull()

                if (codec.missingRequired(stored).isNotEmpty()) continue

                val settings = codec.decrypt(stored)
                val caps = provider.capabilities(settings)
                val testMode = when (caps.testMode) {
                    TestModeSupport.FLAG -> c.testMode || row.testMode
                    TestModeSupport.DERIVED -> caps.derivedTestMode == true
                    TestModeSupport.NONE -> false
                }
                val input = MethodInput(
                    id = row.methodId,
                    feeMode = row.feeMode,
                    feePercent = row.feePercent,
                    feeFixed = row.feeFixed,
                    minAmount = row.minAmount,
                    maxAmount = row.maxAmount,
                    adminCurrencies = row.currencies?.let { stringSet(it) },
                    providerCurrencies = caps.currencies?.map { it.uppercase(Locale.ROOT) }?.toSet(),
                    providerMin = caps.minAmount,
                    providerMax = caps.maxAmount,
                    mixedCredit = caps.mixedCredit,
                    priceAuthority = caps.priceAuthority,
                    physicalGoods = caps.physicalGoods
                )

                out += Candidate(row.methodId, provider, row, caps, settings, input, testMode)
            } catch (e: Exception) {
                // a provider that throws while it describes itself is not offered
                continue
            }
        }

        return out
    }

    private class EligibilityContext(
        val c: MarketConfig,
        val caller: QuoteCaller,
        val payerKey: String,
        val payerName: String,
        val email: String?,
        val recipient: RecipientResolver.Recipient?,
        val locale: String,
        val breakdown: PriceBreakdown,
        val priced: List<PricedLine>,
        val catalog: Catalog,
        val subscriptionLine: CartLine?,
        val input: QuoteInput
    )

    private fun option(candidate: Candidate, evaluation: MethodEvaluation, ctx: EligibilityContext, locale: String): PaymentMethodOption {
        val caps = candidate.caps
        val descriptor = candidate.provider.descriptor
        var reason: String? = evaluation.unavailableReason?.name
        var providerCode: String? = null
        var recurring: String? = null

        if (reason == null && !caps.guests && !ctx.caller.loggedIn) reason = GUESTS_NOT_SUPPORTED

        // 09 section 4.2: the offer table for a subscription, then the provider's own eligibility verdict
        var offer: ModeOffer? = null

        if (reason == null && ctx.subscriptionLine != null) {
            offer = ModeResolver.offer(caps, planOf(ctx), ctx.c.subscriptionManualFallback)

            if (offer is ModeOffer.Unavailable) reason = offer.reason
        }

        if (reason == null) {
            val snapshot = snapshotFor(candidate, evaluation, ctx, offer)
            val eligibility = try {
                candidate.provider.checkEligibility(contexts.create(candidate.provider, candidate.settings, candidate.testMode), snapshot)
            } catch (e: Exception) {
                null
            }

            when {
                eligibility == null -> {
                    reason = ModeResolver.PROVIDER_INELIGIBLE
                    providerCode = "PROVIDER_ERROR"
                }

                offer is ModeOffer.Auto -> {
                    offer = ModeResolver.applyEligibility(offer, eligibility, ctx.c.subscriptionManualFallback)

                    if (offer is ModeOffer.Unavailable) {
                        reason = offer.reason
                        providerCode = eligibility.code
                    }
                }

                !eligibility.eligible -> {
                    reason = ModeResolver.PROVIDER_INELIGIBLE
                    providerCode = eligibility.code
                }
            }
        }

        // 02 section 5.1: a test-mode method is for SET / PAY holders only
        if (reason == null && candidate.testMode && !ctx.caller.canUseTestMode) reason = TEST_MODE

        if (offer != null && offer !is ModeOffer.Unavailable) recurring = offer.recurring

        return PaymentMethodOption(
            id = candidate.id,
            label = candidate.row.customLabel?.takeIf { it.isNotBlank() } ?: descriptor.displayName.resolve(locale),
            description = candidate.row.customDescription?.takeIf { it.isNotBlank() } ?: descriptor.description.resolve(locale),
            hint = descriptor.checkoutHint?.resolve(locale),
            icon = descriptor.icon,
            logoUrl = if (descriptor.logo != null) "/api/market/payment-providers/${candidate.id}/logo" else null,
            color = descriptor.color,
            feeAmount = evaluation.feeAmount,
            available = reason == null,
            unavailableReason = reason,
            providerCode = providerCode,
            pricing = evaluation.pricing.name,
            recurring = recurring,
            testMode = candidate.testMode,
            notices = descriptor.storefrontNotices.map { it.label.resolve(locale) to it.url },
            requiredBuyerFields = caps.requiredBuyerFields.map { it.name }
        )
    }

    private fun planOf(ctx: EligibilityContext): RecurringPlan {
        val line = ctx.subscriptionLine!!
        val product = ctx.catalog.products.getValue(line.productId)
        val variant = if (line.variantId != 0L) ctx.catalog.variants[line.variantId] else null

        return RecurringPlan(
            currency = ctx.breakdown.currency,
            intervalUnit = intervalUnit(product.periodUnit),
            intervalCount = variant?.periodCount ?: product.periodCount ?: 1,
            maxCycles = product.subscriptionMaxCycles
        )
    }

    private fun intervalUnit(unit: PeriodUnit?): IntervalUnit = when (unit) {
        PeriodUnit.WEEK -> IntervalUnit.WEEK
        PeriodUnit.MONTH -> IntervalUnit.MONTH
        PeriodUnit.YEAR -> IntervalUnit.YEAR
        else -> IntervalUnit.DAY
    }

    private fun snapshotFor(candidate: Candidate, evaluation: MethodEvaluation, ctx: EligibilityContext, offer: ModeOffer?): CheckoutSnapshot {
        val b = ctx.breakdown
        val currency = b.currency
        val lines = ctx.priced.filter { it.kind != com.panomc.plugins.market.db.model.OrderItemKind.BUNDLE_CHILD }.map { l ->
            val product = l.productId?.let { ctx.catalog.products[it] }
            val variant = if (l.variantId != 0L) ctx.catalog.variants[l.variantId] else null

            OrderLine(
                orderItemId = 0, productId = l.productId, name = product?.name ?: "Credits", sku = variant?.sku ?: product?.sku, variantName = variant?.name,
                quantity = l.quantity, unitPrice = Money(l.unitPrice, currency), total = Money(l.lineTotal, currency), vatPercent = l.vatPercent,
                physical = product?.physical == true, categoryName = null, providerMeta = null
            )
        }
        val fee = evaluation.feeAmount
        val total = Math.addExact(b.tender.preFee, fee)
        val plan = if (offer is ModeOffer.Auto && ctx.subscriptionLine != null) {
            val product = ctx.catalog.products.getValue(ctx.subscriptionLine.productId)
            val recurring = planOf(ctx)
            val price = Money(lines.firstOrNull()?.total?.amount ?: 0L, currency)

            SubscriptionPlan(0, planKey(product.id, ctx.subscriptionLine.variantId, price.amount, currency, recurring), product.name, price, recurring.intervalUnit, recurring.intervalCount, recurring.maxCycles)
        } else {
            null
        }

        return CheckoutSnapshot(
            order = OrderSnapshot(
                id = 0, publicId = "", description = "Order", currency = currency, lines = lines, subtotal = Money(b.subtotal, currency),
                discount = Money(b.snapshotDiscount, currency), shipping = Money(b.shippingTotal, currency), fee = Money(fee, currency),
                vat = Money(b.vatTotal, currency), total = Money(total, currency), creditValue = Money(b.creditValue, currency),
                requiresShipping = b.items.requiresShipping, recipientUsername = ctx.recipient?.username ?: ctx.payerName,
                gift = ctx.recipient?.isGift == true, pricingMode = evaluation.pricing.name
            ),
            buyer = BuyerInfo(
                userId = ctx.caller.userId, guest = !ctx.caller.loggedIn, numericId = ctx.caller.userId ?: guestNumericId(ctx.payerName),
                stableId = ctx.payerKey.take(64), username = ctx.payerName, email = ctx.email, ip = ctx.caller.clientIp, userAgent = ctx.caller.userAgent,
                locale = ctx.locale, firstName = ctx.input.billingInfo?.getString("firstName"), lastName = ctx.input.billingInfo?.getString("lastName"),
                phone = ctx.input.billingInfo?.getString("phone"), country = ctx.input.billingInfo?.getString("country"),
                identityNumber = ctx.input.billingInfo?.getString("identityNumber"), registeredAt = null
            ),
            subscription = plan,
            hasPanoPriceModifiers = b.snapshotDiscount > 0 || b.creditValue > 0 || b.paymentFee > 0
        )
    }

    // -------------------------------------------------------------------------------------------------- helpers

    companion object {
        const val DEFAULT_LOCALE = "en-US"
        const val TOP_UP_LINE_KEY = "topup"
        const val CART_FULL = "CART_FULL"
        const val AMOUNT_OVERFLOW = "AMOUNT_OVERFLOW"
        const val INVALID_RECIPIENT = "INVALID_RECIPIENT"
        const val INVALID_CREDIT_AMOUNT = "INVALID_CREDIT_AMOUNT"
        const val CREDIT_PACK_SEPARATE_ORDER = "CREDIT_PACK_SEPARATE_ORDER"
        const val PAYMENT_METHOD_UNAVAILABLE = "PAYMENT_METHOD_UNAVAILABLE"
        const val GUESTS_NOT_SUPPORTED = "GUESTS_NOT_SUPPORTED"
        const val TEST_MODE = "TEST_MODE"
        const val BUYER_BLOCKED = "BUYER_BLOCKED"
        const val SHIPPING_ADDRESS_REQUIRED = "SHIPPING_ADDRESS_REQUIRED"
        const val SHIPPING_UNAVAILABLE = "SHIPPING_UNAVAILABLE"
        const val SHIPPING_ADDRESS_INVALID = "SHIPPING_ADDRESS_INVALID"
        const val SHIPPING_METHOD_REQUIRED = "SHIPPING_METHOD_REQUIRED"
        const val METHOD_REQUIRED = "METHOD_REQUIRED"
        const val METHOD_NOT_OFFERED = "METHOD_NOT_OFFERED"

        private val logger = LoggerFactory.getLogger(CheckoutService::class.java)

        /** Line codes that are 409 errors of their own at checkout (06 section 6.3, last row), not `INVALID_CART` entries. */
        private val STOCK_AND_LIMIT_CODES = setOf(LineCode.OUT_OF_STOCK, LineCode.PURCHASE_LIMIT_REACHED, LineCode.COOLDOWN_ACTIVE, LineCode.REQUIREMENT_NOT_MET)
        private val SHIPPING_CODES = setOf(SHIPPING_ADDRESS_REQUIRED, SHIPPING_ADDRESS_INVALID, SHIPPING_UNAVAILABLE, SHIPPING_METHOD_REQUIRED)

        /** A7: refusals of the credit tender (the others of `TenderBreakdown.unavailable` are the amount checks of the gateway, A8). */
        private val CREDIT_UNAVAILABLE = setOf(
            PricingCode.CREDITS_DISABLED, PricingCode.EXTERNAL_PRICING, PricingCode.NOT_PAYABLE_WITH_CREDITS,
            PricingCode.MIXED_CREDIT_NOT_SUPPORTED, PricingCode.CREDITS_REQUIRED
        )

        private val LIVE_SUBSCRIPTIONS = setOf(SubscriptionStatus.PENDING, SubscriptionStatus.ACTIVE, SubscriptionStatus.PAST_DUE, SubscriptionStatus.PAUSED)

        /** `[{value, label}]` of a `SELECT` (plain strings are accepted too). */
        fun optionValues(raw: String?): List<String> {
            val array = raw?.takeIf { it.isNotBlank() }?.let { runCatching { JsonArray(it) }.getOrNull() } ?: return emptyList()

            return array.mapNotNull { item ->
                when (item) {
                    is JsonObject -> item.getValue("value")?.toString()
                    null -> null
                    else -> item.toString()
                }
            }
        }

        fun longArray(raw: String?): List<Long> {
            val array = raw?.takeIf { it.isNotBlank() }?.let { runCatching { JsonArray(it) }.getOrNull() } ?: return emptyList()

            return array.mapNotNull { (it as? Number)?.toLong() }
        }

        /** True when an action of the `actions` JSON has `serverMode = BUYER_CHOICE`. */
        fun buyerChoice(actions: String?): Boolean {
            val array = actions?.takeIf { it.isNotBlank() }?.let { runCatching { JsonArray(it) }.getOrNull() } ?: return false

            return array.any { (it as? JsonObject)?.getString("serverMode") == "BUYER_CHOICE" }
        }

        private fun stringSet(raw: String): Set<String>? {
            val array = runCatching { JsonArray(raw) }.getOrNull() ?: return null

            return array.mapNotNull { (it as? String)?.trim()?.uppercase(Locale.ROOT) }.toSet()
        }

        /** 09 section 4.2: first 32 hex characters of SHA-256 over `productId|variantId|price|currency|unit|count|maxCycles`. */
        fun planKey(productId: Long, variantId: Long, price: Long, currency: String, plan: RecurringPlan): String {
            val text = "$productId|$variantId|$price|$currency|${plan.intervalUnit}|${plan.intervalCount}|${plan.maxCycles}"

            return MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)
        }

        /** A stable positive id for a guest: 2^62 and up, derived from the lower-cased username (02 section 6, `BuyerInfo.numericId`). */
        fun guestNumericId(username: String): Long {
            val digest = MessageDigest.getInstance("SHA-256").digest(username.lowercase(Locale.ROOT).toByteArray())
            var value = 0L

            for (i in 0 until 8) value = (value shl 8) or (digest[i].toLong() and 0xff)

            return (1L shl 62) or (value and ((1L shl 62) - 1))
        }
    }
}
