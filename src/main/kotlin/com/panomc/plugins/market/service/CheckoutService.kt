package com.panomc.plugins.market.service

import com.panomc.platform.error.BadRequest
import com.panomc.plugins.market.config.MarketConfig
import com.panomc.plugins.market.core.cart.CartLimits
import com.panomc.plugins.market.core.cart.CartLine
import com.panomc.plugins.market.core.cart.CartLineKey
import com.panomc.plugins.market.core.cart.CartMerger
import com.panomc.plugins.market.core.order.LineCode
import com.panomc.plugins.market.core.order.LineRules
import com.panomc.plugins.market.core.order.LineVerdict
import com.panomc.plugins.market.core.order.OwnedEntitlement
import com.panomc.plugins.market.core.order.ProductUsage
import com.panomc.plugins.market.core.order.RecipientResolver
import com.panomc.plugins.market.core.order.RequiredBuyerFields
import com.panomc.plugins.market.core.order.RuleChild
import com.panomc.plugins.market.core.order.RuleContext
import com.panomc.plugins.market.core.order.RuleField
import com.panomc.plugins.market.core.order.RuleLine
import com.panomc.plugins.market.core.order.RuleProduct
import com.panomc.plugins.market.core.order.RuleTier
import com.panomc.plugins.market.core.order.RuleVariant
import com.panomc.plugins.market.core.order.BuyerValidator
import com.panomc.plugins.market.core.pricing.BuyerContext
import com.panomc.plugins.market.core.pricing.BundleChild
import com.panomc.plugins.market.core.pricing.CouponInput
import com.panomc.plugins.market.core.pricing.CreatorCodeInput
import com.panomc.plugins.market.core.pricing.CurrencyPriceResolver
import com.panomc.plugins.market.core.pricing.DiscountInput
import com.panomc.plugins.market.core.pricing.LineInput
import com.panomc.plugins.market.core.pricing.LineKind
import com.panomc.plugins.market.core.pricing.MethodEvaluation
import com.panomc.plugins.market.core.pricing.MethodInput
import com.panomc.plugins.market.core.pricing.OrderCurrencies
import com.panomc.plugins.market.core.pricing.OwnedTier
import com.panomc.plugins.market.core.pricing.PriceBreakdown
import com.panomc.plugins.market.core.pricing.PricedLine
import com.panomc.plugins.market.core.pricing.PricingEngine
import com.panomc.plugins.market.core.pricing.PricingError
import com.panomc.plugins.market.core.pricing.PricingException
import com.panomc.plugins.market.core.pricing.PricingInput
import com.panomc.plugins.market.core.pricing.PricingMode
import com.panomc.plugins.market.core.pricing.PricingProfile
import com.panomc.plugins.market.core.pricing.ShippingCharge
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
import com.panomc.plugins.market.db.dao.MarketPaymentMethodDao
import com.panomc.plugins.market.db.dao.MarketProductDao
import com.panomc.plugins.market.db.dao.MarketProductFieldDao
import com.panomc.plugins.market.db.dao.MarketProductPriceDao
import com.panomc.plugins.market.db.dao.MarketProductVariantDao
import com.panomc.plugins.market.db.dao.MarketRedemptionDao
import com.panomc.plugins.market.db.dao.MarketSubscriptionDao
import com.panomc.plugins.market.db.model.BillingMode
import com.panomc.plugins.market.db.model.MarketCategory
import com.panomc.plugins.market.db.model.MarketPaymentMethod
import com.panomc.plugins.market.db.model.MarketProduct
import com.panomc.plugins.market.db.model.MarketProductField
import com.panomc.plugins.market.db.model.MarketProductVariant
import com.panomc.plugins.market.db.model.PeriodUnit
import com.panomc.plugins.market.db.model.ProductKind
import com.panomc.plugins.market.db.model.RedemptionKind
import com.panomc.plugins.market.db.model.SubscriptionStatus
import com.panomc.plugins.market.error.InvalidCart
import com.panomc.plugins.market.provider.ProviderLookup
import com.panomc.plugins.market.provider.SecretCipher
import com.panomc.plugins.market.provider.SettingsCodec
import com.panomc.plugins.market.service.platform.DirectoryUser
import com.panomc.plugins.market.service.platform.ServerDirectory
import com.panomc.plugins.market.service.platform.UserDirectory
import com.panomc.plugins.market.spi.common.Money
import com.panomc.plugins.market.spi.common.ProviderSettings
import com.panomc.plugins.market.spi.common.TestModeSupport
import com.panomc.plugins.market.spi.payment.BuyerInfo
import com.panomc.plugins.market.spi.payment.CheckoutSnapshot
import com.panomc.plugins.market.spi.payment.IntervalUnit
import com.panomc.plugins.market.spi.payment.OrderLine
import com.panomc.plugins.market.spi.payment.OrderSnapshot
import com.panomc.plugins.market.spi.payment.PaymentCapabilities
import com.panomc.plugins.market.spi.payment.PaymentProvider
import com.panomc.plugins.market.spi.payment.SubscriptionPlan
import com.panomc.plugins.market.util.CouponScope
import com.panomc.plugins.market.util.DiscountUnit
import com.panomc.plugins.market.util.MarketStatus
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.SqlClient
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
    val messages: List<QuoteMessage> = emptyList()
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
    val userId: Long?
)

/** The block list seam (MK-151): is this buyer refused? `true` = blocked (the quote warns, checkout answers 403). */
fun interface BuyerBlocks {
    suspend fun blocked(payerUsername: String?, recipientUsername: String?, email: String?, clientIp: String?, userId: Long?, sqlClient: SqlClient): Boolean

    companion object {
        val NONE = BuyerBlocks { _, _, _, _, _, _ -> false }
    }
}

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
    private val shipping: ShippingQuoter = ShippingQuoter.NONE
) {
    // ------------------------------------------------------------------------------------------------------ quote

    /** `POST /api/market/checkout/quote` (04 section 3). */
    suspend fun quote(input: QuoteInput, caller: QuoteCaller, sqlClient: SqlClient): Quote {
        val c = config()
        val now = clock.now()
        val locale = input.locale?.trim()?.takeIf { it.isNotEmpty() }?.take(16) ?: DEFAULT_LOCALE
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
        val candidates = loadCandidates(c, sqlClient)
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

            shippingQuote = shipping.quote(
                ShippingRequest(
                    items.currency, items.itemsBasisBase, items.physicalBasisBase, physical, input.shippingAddress,
                    input.shippingAddressId ?: cart?.shippingAddressId, input.shippingMethodId ?: cart?.shippingMethodId, caller.userId
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
        val tender = TenderInput(input.useCredits.takeIf { !payWithCredits && topUp == null }?.let { creditsOf(it) }, selected?.input, strict = false)
        val breakdown = try {
            PricingEngine.finalize(items, shippingQuote.charge, tender)
        } catch (e: PricingException) {
            if (e.error == PricingError.AMOUNT_OVERFLOW) throw InvalidCart(mapOf("cart" to listOf(AMOUNT_OVERFLOW)))

            throw e
        }
        val evaluations = if (topUp != null && topUpMessage != null) {
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
        if (blocks.blocked(payerName, recipient?.username, orderEmail, caller.clientIp, caller.userId, sqlClient)) {
            messages += QuoteMessage(BUYER_BLOCKED, LineRules.WARNING)
        }

        // the tender cannot be used as asked (a gateway refused the amount, credits required, ...): say so, as an error
        breakdown.tender.unavailable?.let { messages += QuoteMessage(it.name, LineRules.ERROR) }

        // ---- engine messages, once each
        for (m in breakdown.messages) messages += QuoteMessage(m.code.name, m.level.name.lowercase(Locale.ROOT), m.lineKey)

        // ---- the legal text
        val legalText = legal.activeFor(locale, sqlClient)?.let { QuoteLegal(c.legalTextRequired, it.id, it.version, it.title) }

        // ---- the lines of the answer
        val quoteLines = quoteLines(rules.lines, lines, breakdown, catalog)
        val distinct = messages.distinct()
        val requiredFields = RequiredBuyerFields.of(
            c.billingInfoMode, selected?.caps?.requiredBuyerFields.orEmpty(),
            input.billingInfo?.getString("type").equals("COMPANY", ignoreCase = true), input.billingInfo?.getString("country"), items.requiresShipping
        )
        val canCheckout = quoteLines.isNotEmpty() &&
            quoteLines.all { it.errors.isEmpty() } &&
            distinct.none { it.level == LineRules.ERROR } &&
            breakdown.canCheckout &&
            (chosen == null || chosen.available)

        return Quote(
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
    }

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

    private fun PricedLine.physicalLine(catalog: Catalog): Boolean {
        val id = productId ?: return false
        val product = catalog.products[id] ?: return false

        return if (product.kind == ProductKind.BUNDLE) catalog.children[id].orEmpty().any { catalog.products[it.productId]?.physical == true } else product.physical
    }

    // ------------------------------------------------------------------------------------------ answer lines

    private fun quoteLines(verdicts: List<LineVerdict>, lines: List<CartLine>, breakdown: PriceBreakdown, catalog: Catalog): List<QuoteLine> {
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
        const val PAYMENT_METHOD_UNAVAILABLE = "PAYMENT_METHOD_UNAVAILABLE"
        const val GUESTS_NOT_SUPPORTED = "GUESTS_NOT_SUPPORTED"
        const val TEST_MODE = "TEST_MODE"
        const val BUYER_BLOCKED = "BUYER_BLOCKED"
        const val SHIPPING_ADDRESS_REQUIRED = "SHIPPING_ADDRESS_REQUIRED"
        const val SHIPPING_UNAVAILABLE = "SHIPPING_UNAVAILABLE"

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
