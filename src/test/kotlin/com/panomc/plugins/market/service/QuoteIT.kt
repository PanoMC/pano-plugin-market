package com.panomc.plugins.market.service

import com.panomc.platform.error.BadRequest
import com.panomc.plugins.market.config.BillingInfoMode
import com.panomc.plugins.market.config.CurrencyMode
import com.panomc.plugins.market.config.MarketConfig
import com.panomc.plugins.market.config.MultiCurrencyFallback
import com.panomc.plugins.market.core.cart.CartLine
import com.panomc.plugins.market.core.pricing.ShippingCharge
import com.panomc.plugins.market.db.MarketDaoITBase
import com.panomc.plugins.market.db.model.CurrencyRateMode
import com.panomc.plugins.market.db.model.MarketCurrencyRate
import com.panomc.plugins.market.db.model.MarketEntitlement
import com.panomc.plugins.market.db.model.MarketOrder
import com.panomc.plugins.market.db.model.MarketOrderItem
import com.panomc.plugins.market.db.model.MarketProduct
import com.panomc.plugins.market.db.model.MarketSubscription
import com.panomc.plugins.market.db.model.OrderItemKind
import com.panomc.plugins.market.db.model.PaymentFeeMode
import com.panomc.plugins.market.db.model.ProductFieldType
import com.panomc.plugins.market.db.model.RedemptionKind
import com.panomc.plugins.market.db.model.RedemptionState
import com.panomc.plugins.market.db.model.ReservationState
import com.panomc.plugins.market.db.model.SubscriptionStatus
import com.panomc.plugins.market.error.InvalidCart
import com.panomc.plugins.market.provider.SecretCipher
import com.panomc.plugins.market.service.platform.DirectoryUser
import com.panomc.plugins.market.service.platform.ServerDirectory
import com.panomc.plugins.market.service.platform.UserDirectory
import com.panomc.plugins.market.spi.common.LocalizedText
import com.panomc.plugins.market.spi.common.Money
import com.panomc.plugins.market.spi.common.TestModeSupport
import com.panomc.plugins.market.spi.payment.BuyerField
import com.panomc.plugins.market.spi.payment.CheckoutSnapshot
import com.panomc.plugins.market.spi.payment.Eligibility
import com.panomc.plugins.market.spi.payment.IntervalUnit
import com.panomc.plugins.market.spi.payment.PaymentContext
import com.panomc.plugins.market.spi.payment.PaymentProvider
import com.panomc.plugins.market.spi.payment.PriceAuthority
import com.panomc.plugins.market.spi.payment.RecurringSupport
import com.panomc.plugins.market.spi.testkit.TestContexts
import com.panomc.plugins.market.support.FakePaymentProvider
import com.panomc.plugins.market.support.Fixtures
import com.panomc.plugins.market.support.StaticProviderLookup
import com.panomc.plugins.market.support.TestUser
import com.panomc.plugins.market.support.TestWiring
import com.panomc.plugins.market.util.CurrencyType
import com.panomc.plugins.market.util.DiscountUnit
import com.panomc.plugins.market.util.MarketStatus
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * `CheckoutService.quote` on a real MariaDB (MK-072, 06 sections 3 and 6, 04 section 3): the quote never fails for a
 * business reason, every line / message code of 04 section 11 that a quote can carry has a case, the per-player limit and
 * the cooldown are judged on the recipient, prerequisites are satisfied by an entitlement or by the same cart, the
 * minimum order amount, one-subscription-alone, `useCredits: "MAX"`, and the payment method list with its
 * `available` / `unavailableReason`.
 *
 * Rows that a later slice writes through checkout (orders, entitlements, subscriptions) are inserted raw here, so the
 * order / counter invariants are not checked after each test; instead [`a quote writes nothing`] proves the service only reads.
 */
class QuoteIT : MarketDaoITBase() {
    // a fresh wiring per test: the in-memory user directory of TestWiring has no reset
    private lateinit var w: TestWiring
    private lateinit var fx: Fixtures
    private val vertx: Vertx = Vertx.vertx()
    private val cipher = SecretCipher(ByteArray(32) { (it + 3).toByte() })

    private val fake = FakePaymentProvider()
    private var picky = Picky(fake)
    private var lookup = StaticProviderLookup(listOf(fake))

    @Volatile
    private var config: MarketConfig = base()

    private val emails = HashMap<Long, String>()
    private val granted = HashMap<Long, Set<String>>()
    private var servers: Set<Long> = emptySet()
    private var blocked = false
    private var shippingResult: ShippingQuote? = null
    private var shippingRequest: ShippingRequest? = null

    private val directory = object : UserDirectory {
        override suspend fun byUsername(username: String, sqlClient: SqlClient): DirectoryUser? =
            w.users.idOf(username)?.let { DirectoryUser(it, w.users.nameOf(it)!!) }

        override suspend fun usernameOf(userId: Long, sqlClient: SqlClient): String? = w.users.nameOf(userId)

        override suspend fun emailOf(userId: Long, sqlClient: SqlClient): String? = emails[userId]

        override suspend fun hasPermission(userId: Long, node: String): Boolean = node in granted[userId].orEmpty()
    }

    private lateinit var legal: LegalTextService
    private lateinit var service: CheckoutService

    private fun buildService() =
        CheckoutService(
            config = { config }, clock = w.clock, categories = w.categories, products = w.products, variants = w.variants, prices = w.prices,
            fields = w.fields, bundleItems = w.bundleItems, discounts = w.discounts, coupons = w.coupons, creatorCodes = w.creatorCodes,
            currencyRates = w.currencyRates, redemptions = w.redemptions, orders = w.orders, entitlements = w.entitlements,
            subscriptions = w.subscriptions, creditAccounts = w.creditAccounts, carts = w.carts, cartItems = w.cartItems,
            paymentMethods = w.paymentMethods, lookup = SwitchingLookup { lookup }, cipher = cipher,
            contexts = PaymentContexts { provider, settings, testMode -> TestContexts.payment(provider.id, settings, vertx, testMode) },
            legal = legal, users = directory, servers = ServerDirectory { ids, _ -> ids.filter { it in servers }.toSet() },
            blocks = BuyerBlocks { _, _, _, _, _, _ -> blocked },
            shipping = ShippingQuoter { request, _ ->
                shippingRequest = request

                shippingResult ?: ShippingQuote(null)
            }
        )

    /** The service reads the provider list at call time, so a test swaps `lookup` freely. */
    private class SwitchingLookup(private val current: () -> com.panomc.plugins.market.provider.ProviderLookup) : com.panomc.plugins.market.provider.ProviderLookup {
        override fun allPayment() = current().allPayment()

        override fun allShipping() = current().allShipping()

        override fun state(kind: com.panomc.plugins.market.provider.ProviderKind, id: String) = current().state(kind, id)

        override fun listing(kind: com.panomc.plugins.market.provider.ProviderKind) = current().listing(kind)
    }

    /** A provider that records the snapshot it was asked about and answers a scripted eligibility. */
    private class Picky(private val base: FakePaymentProvider) : PaymentProvider by base {
        var verdict: Eligibility = Eligibility.eligible()
        var seen: CheckoutSnapshot? = null
        var throwing = false

        override fun checkEligibility(ctx: PaymentContext, checkout: CheckoutSnapshot): Eligibility {
            seen = checkout

            if (throwing) throw IllegalStateException("boom")

            return verdict
        }
    }

    @AfterAll
    fun closeVertx() {
        vertx.close()
    }

    override suspend fun assertInvariants() {}

    @BeforeEach
    fun freshState() {
        runBlocking { resetState() }
        w = TestWiring(pool)
        fx = Fixtures(w)
        legal = LegalTextService(w.db, w.clock, w.legalTexts, { "en-US" })
        service = buildService()
        fake.caps = com.panomc.plugins.market.spi.payment.PaymentCapabilities()
        picky = Picky(fake)
        lookup = StaticProviderLookup(listOf(fake))
        config = base()
        emails.clear()
        granted.clear()
        servers = emptySet()
        blocked = false
        shippingResult = null
        shippingRequest = null
    }

    private fun base(
        guest: Boolean = true,
        gifts: Boolean = true,
        minimum: Double = 0.0,
        credits: Boolean = true,
        mixed: Boolean = false,
        onlyCredits: Boolean = false,
        testMode: Boolean = false,
        currencyMode: CurrencyMode = CurrencyMode.SINGLE,
        additional: List<String> = emptyList(),
        fallback: MultiCurrencyFallback = MultiCurrencyFallback.CONVERT,
        billing: BillingInfoMode = BillingInfoMode.OPTIONAL,
        manualFallback: Boolean = true,
        legalRequired: Boolean = false,
        topUp: Boolean = false,
        topUpFree: Boolean = false,
        removeCents: Boolean = false,
        combine: Boolean = true
    ) = MarketConfig(
        currency = CurrencyType.EUR, vatPercent = 20.0, showVatInPrice = true, creditValue = 1.0, storeTimeZone = "UTC",
        allowGuestCheckout = guest, allowGiftPurchase = gifts, minimumOrderAmount = minimum, creditsEnabled = credits,
        allowMixedCreditPayment = mixed, onlyAcceptCredits = onlyCredits, testMode = testMode, currencyMode = currencyMode,
        additionalCurrencies = additional, multiCurrencyFallback = fallback, billingInfoMode = billing, subscriptionManualFallback = manualFallback,
        legalTextRequired = legalRequired, creditTopUpEnabled = topUp, creditTopUpFreeAmount = topUpFree, removeCents = removeCents,
        combineDiscountsAndCoupons = combine
    )

    private fun line(product: MarketProduct, quantity: Int = 1, variant: Long = 0, values: Map<String, Any> = emptyMap(), server: Long? = null) =
        CartLine(product.id, variant, quantity, values, server)

    private suspend fun quote(
        vararg lines: CartLine,
        caller: QuoteCaller = QuoteCaller.GUEST,
        guest: GuestInput? = GuestInput("Steve", "steve@example.com"),
        code: String? = null,
        creator: String? = null,
        recipient: String? = null,
        method: String? = null,
        credits: UseCredits? = null,
        payWithCredits: Boolean = false,
        currency: String? = null,
        topUp: TopUpRequest? = null,
        items: List<CartLine>? = lines.toList(),
        shippingAddress: JsonObject? = null,
        billing: JsonObject? = null,
        locale: String? = null
    ): Quote = service.quote(
        QuoteInput(
            items = if (topUp != null) null else items, currency = currency, couponCode = code, creatorCode = creator, recipientUsername = recipient,
            creditTopUp = topUp, guest = if (caller.loggedIn) null else guest, useCredits = credits, payWithCredits = payWithCredits,
            paymentMethodId = method, shippingAddress = shippingAddress, billingInfo = billing, locale = locale
        ),
        caller, pool
    )

    private suspend fun user(name: String = "Alex", email: String? = "$name@example.com", credit: Long = 0): Pair<TestUser, QuoteCaller> {
        val u = fx.user(name)

        if (email != null) emails[u.id] = email
        if (credit > 0) fx.credit(u, credit)

        return u to QuoteCaller(u.id)
    }

    private fun codes(l: QuoteLine) = l.errors

    private fun Quote.messageCodes() = messages.map { it.code }

    private fun Quote.message(code: String) = messages.firstOrNull { it.code == code }

    private suspend fun method(enabled: Boolean = true, feeMode: PaymentFeeMode = PaymentFeeMode.NONE, feePercent: Long = 0, feeFixed: Long = 0, min: Long? = null, max: Long? = null) =
        fx.paymentMethod("fake", enabled, feeMode, feePercent, feeFixed, min, max)

    private suspend fun order(
        recipientKey: String, productId: Long, quantity: Int = 1, state: ReservationState = ReservationState.COMMITTED, buyerKey: String = recipientKey,
        createdAt: Long = w.clock.now(), refunded: Int = 0
    ): Long {
        val id = w.orders.add(MarketOrder(buyerKey = buyerKey, recipientKey = recipientKey, reservationState = state, createdAt = createdAt, currency = "EUR"), pool)

        w.orderItems.add(MarketOrderItem(orderId = id, productId = productId, quantity = quantity, kind = OrderItemKind.PRODUCT, refundedQuantity = refunded), pool)

        return id
    }

    private suspend fun entitlement(userId: Long, product: MarketProduct, categoryId: Long? = null, rank: Int? = null, pricePaid: Long = 0) =
        w.entitlements.add(
            MarketEntitlement(
                userId = userId, playerUsername = "u$userId", ownerKey = "u:$userId", productId = product.id, orderId = 1, orderItemId = 1,
                tierCategoryId = categoryId, tierRank = rank, pricePaid = pricePaid, startsAt = w.clock.now() - 1000
            ),
            pool
        )

    private suspend fun raw(product: MarketProduct, vararg values: Pair<String, Any?>) = Fixtures.setColumns(pool, "market_product", product.id, mapOf(*values))

    private suspend fun rawRow(table: String, id: Long, vararg values: Pair<String, Any?>) = Fixtures.setColumns(pool, table, id, mapOf(*values))

    private suspend fun count(table: String): Long =
        pool.query("SELECT COUNT(*) AS c FROM `pano_$table`").execute().coAwait().first().getLong("c")

    // =====================================================================================================  shape

    @Test
    fun `an empty cart is a quote that cannot be checked out`(): Unit = runBlocking {
        val q = quote()

        assertTrue(q.lines.isEmpty())
        assertFalse(q.canCheckout)
        assertEquals(0, q.total)
        assertEquals("EUR", q.currency)
        assertNull(q.legal)
        assertTrue(q.paymentMethods.isEmpty())
    }

    @Test
    fun `a single product is priced and the answer has exactly the keys of 04 section 2`(): Unit = runBlocking {
        method()
        val p = fx.product(slug = "vip", name = "VIP", price = 1000)
        val q = quote(line(p, 2))
        val l = q.lines.single()

        assertTrue(q.canCheckout)
        assertEquals(p.id, l.productId)
        assertEquals("VIP", l.name)
        assertEquals("vip", l.slug)
        assertEquals(2, l.quantity)
        assertEquals(1000, l.listUnitPrice)
        assertEquals(1000, l.unitPrice)
        assertEquals(2000, l.lineTotal)
        assertEquals(2000L, q.total)
        assertEquals(2000L, q.gatewayAmount)
        assertEquals(2000L, q.subtotal)
        assertEquals(2000L - 333, l.lineTotal - l.vatAmount)
        assertEquals("PRODUCT", l.kind)
        assertEquals("ONE_TIME", l.billingMode)
        assertEquals(2000, l.vatPercent * 1)
        assertTrue(l.errors.isEmpty())
        assertNull(l.parentLineKey)

        val json = q.toJson()

        assertEquals(
            setOf(
                "currency", "baseCurrency", "lines", "pricingMode", "pricesIncludeVat", "fxRate", "display", "minimumOrderAmount", "subtotal", "discountTotal",
                "couponDiscount", "creatorDiscount", "upgradeDiscount", "shippingTotal", "paymentFee", "vatTotal", "total", "credits", "gatewayAmount",
                "coupon", "creatorCode", "requiresShipping", "shippingOptions", "shippingMethodId", "paymentMethods", "requiredBuyerFields", "legal",
                "messages", "canCheckout"
            ),
            json.fieldNames()
        )
        assertEquals(
            setOf(
                "lineKey", "productId", "variantId", "name", "variantName", "slug", "imageFileName", "quantity", "maxQuantity", "listUnitPrice", "unitPrice",
                "discountAmount", "upgradeAmount", "couponAmount", "vatPercent", "vatAmount", "lineTotal", "creditUnitPrice", "fieldValues", "targetServerId",
                "physical", "kind", "parentLineKey", "billingMode", "errors"
            ),
            json.getJsonArray("lines").getJsonObject(0).fieldNames()
        )
        assertEquals(20.0, json.getJsonArray("lines").getJsonObject(0).getDouble("lineTotal"))
        assertEquals(20.0, json.getDouble("total"))
        assertEquals(
            setOf("id", "label", "description", "hint", "icon", "logoUrl", "color", "feeAmount", "available", "unavailableReason", "pricing", "recurring", "testMode", "notices", "requiredBuyerFields"),
            json.getJsonArray("paymentMethods").getJsonObject(0).fieldNames()
        )
    }

    @Test
    fun `a quote never fails for a business reason`(): Unit = runBlocking {
        val soldOut = fx.product(stock = 0)
        val archived = fx.product(status = MarketStatus.ARCHIVED)
        val variants = fx.product().also { fx.variant(it) }
        val ok = fx.product()

        val q = quote(
            line(soldOut), line(archived), line(variants), CartLine(987654, 0, 1), line(ok, 5000.coerceAtMost(999)), code = "NOPE", creator = "NOPE2", recipient = "n", method = "ghost"
        )

        assertFalse(q.canCheckout)
        assertEquals(5, q.lines.size)
        assertTrue(q.lines.all { it.errors.isNotEmpty() || it.productId == ok.id })
        assertEquals("CODE_NOT_FOUND", q.coupon!!.reason)
        assertFalse(q.coupon!!.valid)
        assertEquals("CODE_NOT_FOUND", q.creatorCode!!.reason)
        assertNotNull(q.message("INVALID_RECIPIENT"), "a name that is not a valid username")
        assertEquals("warning", q.message("PAYMENT_METHOD_UNAVAILABLE")!!.level)
    }

    @Test
    fun `a quote writes nothing`(): Unit = runBlocking {
        method(feeMode = PaymentFeeMode.BUYER, feePercent = 500)
        val a = fx.product(price = 5000)
        val coupon = fx.coupon("SAVE10")
        val (alex, caller) = user(credit = 10_000)
        val tables = listOf("market_order", "market_order_item", "market_order_event", "market_redemption", "market_cart", "market_cart_item", "market_credit_tx", "market_credit_entry", "market_payment")
        val before = tables.associateWith { count(it) }

        quote(line(a, 2), caller = caller, code = coupon.code, method = "fake", credits = UseCredits.Max)
        quote(line(a), caller = QuoteCaller.GUEST, creator = "X")

        assertEquals(before, tables.associateWith { count(it) })
        assertEquals(10_000, fx.creditBalance(alex))
    }

    // ===============================================================================================  line codes

    @Test
    fun `PRODUCT_UNAVAILABLE for a missing archived inactive deleted product or a product in an inactive category`(): Unit = runBlocking {
        val inactiveCategory = fx.category()
        w.categories.update(com.panomc.plugins.market.db.model.MarketCategory(id = inactiveCategory.id, name = inactiveCategory.name, status = MarketStatus.INACTIVE), pool)

        val cases = listOf(
            fx.product(status = MarketStatus.ARCHIVED),
            fx.product(status = MarketStatus.INACTIVE),
            fx.product(status = MarketStatus.HIDDEN),
            fx.product().also { raw(it, "deletedAt" to w.clock.now()) },
            fx.product(categoryId = inactiveCategory.id)
        )

        for (p in cases) assertEquals(listOf("PRODUCT_UNAVAILABLE"), quote(line(p)).lines.single().errors, p.slug)

        val q = quote(CartLine(424242, 0, 1))

        assertEquals(listOf("PRODUCT_UNAVAILABLE"), q.lines.single().errors)
        assertEquals(0, q.lines.single().maxQuantity)
        assertEquals(0, q.total)
    }

    @Test
    fun `a product outside its TEMPORARY sale window is unavailable`(): Unit = runBlocking {
        val now = w.clock.now()
        val early = fx.product().also { raw(it, "durationType" to "TEMPORARY", "durationStart" to now + 10_000) }
        val late = fx.product().also { raw(it, "durationType" to "TEMPORARY", "durationExpiry" to now) }
        val live = fx.product().also { raw(it, "durationType" to "TEMPORARY", "durationStart" to now, "durationExpiry" to now + 10_000) }

        assertEquals(listOf("PRODUCT_UNAVAILABLE"), quote(line(early)).lines.single().errors)
        assertEquals(listOf("PRODUCT_UNAVAILABLE"), quote(line(late)).lines.single().errors)
        assertTrue(quote(line(live)).lines.single().errors.isEmpty())
    }

    @Test
    fun `variants VARIANT_REQUIRED VARIANT_UNAVAILABLE and a priced variant`(): Unit = runBlocking {
        val p = fx.product(price = 1000)
        val small = fx.variant(p, "S", price = 800)
        val inactive = fx.variant(p, "XL")
        Fixtures.setColumns(pool, "market_product_variant", inactive.id, mapOf("status" to "INACTIVE"))
        val other = fx.variant(fx.product(), "Other")

        assertEquals(listOf("VARIANT_REQUIRED"), quote(line(p)).lines.single().errors)
        assertEquals(listOf("VARIANT_UNAVAILABLE"), quote(line(p, variant = inactive.id)).lines.single().errors)
        assertEquals(listOf("VARIANT_UNAVAILABLE"), quote(line(p, variant = other.id)).lines.single().errors)
        assertEquals(listOf("VARIANT_UNAVAILABLE"), quote(line(p, variant = 99999)).lines.single().errors)
        assertEquals(listOf("VARIANT_UNAVAILABLE"), quote(line(fx.product(), variant = small.id)).lines.single().errors, "a variant for a product without variants")

        val ok = quote(line(p, 2, variant = small.id)).lines.single()

        assertTrue(ok.errors.isEmpty())
        assertEquals("S", ok.variantName)
        assertEquals(800, ok.unitPrice)
        assertEquals(1600, ok.lineTotal)
    }

    @Test
    fun `stock OUT_OF_STOCK and MAX_QUANTITY with maxQuantity`(): Unit = runBlocking {
        val none = fx.product(stock = 0)
        val few = fx.product(stock = 3)
        val capped = fx.product().also { raw(it, "maxQuantityPerOrder" to 2) }

        assertEquals(listOf("OUT_OF_STOCK"), quote(line(none)).lines.single().errors)
        assertEquals(0, quote(line(none)).lines.single().maxQuantity)

        val partial = quote(line(few, 5)).lines.single()

        assertEquals(listOf("MAX_QUANTITY"), partial.errors)
        assertEquals(3, partial.maxQuantity)
        assertEquals(5, partial.quantity, "the quote prices what was asked and does not reduce it silently")
        assertEquals(5000, partial.lineTotal)

        assertTrue(quote(line(few, 3)).lines.single().errors.isEmpty())
        assertEquals(listOf("MAX_QUANTITY"), quote(line(capped, 3)).lines.single().errors)
        assertEquals(2, quote(line(capped, 3)).lines.single().maxQuantity)
    }

    @Test
    fun `lines of the same product share its stock`(): Unit = runBlocking {
        val p = fx.product(stock = 4)
        val f = fx.field(p, "nick", ProductFieldType.TEXT, required = false)
        val q = quote(line(p, 3), line(p, 3, values = mapOf(f.fieldKey to "x")))

        assertTrue(q.lines[0].errors.isEmpty())
        assertEquals(listOf("MAX_QUANTITY"), q.lines[1].errors)
        assertEquals(1, q.lines[1].maxQuantity)
    }

    @Test
    fun `custom fields FIELD_REQUIRED FIELD_INVALID and unknown keys dropped`(): Unit = runBlocking {
        val p = fx.product()
        fx.field(p, "nick", ProductFieldType.USERNAME, required = true)
        fx.field(p, "level", ProductFieldType.NUMBER)

        assertEquals(listOf("FIELD_REQUIRED"), quote(line(p)).lines.single().errors)
        assertEquals(listOf("FIELD_INVALID"), quote(line(p, values = mapOf("nick" to "a b"))).lines.single().errors)
        assertEquals(listOf("FIELD_INVALID"), quote(line(p, values = mapOf("nick" to "Steve", "level" to 2.5))).lines.single().errors)

        val ok = quote(line(p, values = mapOf("nick" to "Steve", "level" to 3, "junk" to "x"))).lines.single()

        assertTrue(ok.errors.isEmpty())
        assertEquals(setOf("nick", "level"), ok.fieldValues.keys)
    }

    @Test
    fun `buyer chosen server SERVER_REQUIRED and SERVER_UNAVAILABLE`(): Unit = runBlocking {
        val actions = """[{"id":"a1","type":"COMMAND","phase":"GRANT","via":"SERVER","serverMode":"BUYER_CHOICE","commands":["give {player} diamond"]}]"""
        val p = fx.product(actions = actions)
        raw(p, "serverChoices" to "[11,12]")
        servers = setOf(11)

        assertEquals(listOf("SERVER_REQUIRED"), quote(line(p)).lines.single().errors)
        assertEquals(listOf("SERVER_UNAVAILABLE"), quote(line(p, server = 99)).lines.single().errors, "not a choice of the product")
        assertEquals(listOf("SERVER_UNAVAILABLE"), quote(line(p, server = 12)).lines.single().errors, "a choice that no longer exists")

        val ok = quote(line(p, server = 11)).lines.single()

        assertTrue(ok.errors.isEmpty())
        assertEquals(11L, ok.targetServerId)

        // no buyer choice: the target is dropped
        assertNull(quote(line(fx.product(), server = 11)).lines.single().targetServerId)
    }

    @Test
    fun `PERMISSION_REQUIRED is judged on the recipient`(): Unit = runBlocking {
        val p = fx.product().also { raw(it, "requiredPermission" to "vip.buy") }
        val (alex, caller) = user("Alex")
        val (bob, _) = user("Bob")

        assertEquals(listOf("PERMISSION_REQUIRED"), quote(line(p), caller = caller).lines.single().errors)

        granted[alex.id] = setOf("vip.buy")

        assertTrue(quote(line(p), caller = caller).lines.single().errors.isEmpty())
        // a gift: the recipient (Bob) is who must hold the node, not the payer
        assertEquals(listOf("PERMISSION_REQUIRED"), quote(line(p), caller = caller, recipient = "Bob").lines.single().errors)

        granted[bob.id] = setOf("vip.buy")

        assertTrue(quote(line(p), caller = caller, recipient = "Bob").lines.single().errors.isEmpty())
        // a recipient without a Pano user never has it
        granted[alex.id] = setOf("vip.buy")
        assertEquals(listOf("PERMISSION_REQUIRED"), quote(line(p), caller = caller, recipient = "Nobody_Yet").lines.single().errors)
    }

    @Test
    fun `ALREADY_OWNED for a tier the recipient has and for a running subscription`(): Unit = runBlocking {
        val ladder = fx.category(tiered = true)
        val gold = fx.product(price = 5000, categoryId = ladder.id).also { raw(it, "tierRank" to 2) }
        val silver = fx.product(price = 2000, categoryId = ladder.id).also { raw(it, "tierRank" to 1) }
        val (alex, caller) = user("Alex")

        entitlement(alex.id, silver, ladder.id, 1, 2000)

        assertEquals(listOf("ALREADY_OWNED"), quote(line(silver), caller = caller).lines.single().errors)
        assertTrue(quote(line(gold), caller = caller).lines.single().errors.isEmpty(), "a higher tier is an upgrade")

        entitlement(alex.id, gold, ladder.id, 2, 5000)

        assertEquals(listOf("ALREADY_OWNED"), quote(line(gold), caller = caller).lines.single().errors)
        assertEquals(listOf("ALREADY_OWNED"), quote(line(silver), caller = caller).lines.single().errors)
        assertEquals(listOf("MAX_QUANTITY"), quote(line(fx.product(categoryId = ladder.id).also { raw(it, "tierRank" to 3) }, 2), caller = caller).lines.single().errors, "tiered = one at most")

        val sub = fx.product(price = 900).also { raw(it, "billingMode" to "SUBSCRIPTION", "periodUnit" to "MONTH", "periodCount" to 1) }

        w.subscriptions.add(
            MarketSubscription(ownerKey = "u:${alex.id}", userId = alex.id, productId = sub.id, status = SubscriptionStatus.ACTIVE, providerId = "fake", price = 900, currency = "EUR"),
            pool
        )

        assertEquals(listOf("ALREADY_OWNED"), quote(line(sub), caller = caller).lines.single().errors)
    }

    @Test
    fun `gift rules GIFT_NOT_ALLOWED RECIPIENT_UNKNOWN and INVALID_RECIPIENT`(): Unit = runBlocking {
        val noGift = fx.product().also { raw(it, "allowGift" to false) }
        val normal = fx.product()
        val (_, caller) = user("Alex")
        user("Bob")

        assertEquals(listOf("GIFT_NOT_ALLOWED"), quote(line(noGift), caller = caller, recipient = "Bob").lines.single().errors)
        assertTrue(quote(line(noGift), caller = caller).lines.single().errors.isEmpty(), "buying for oneself is not a gift")
        assertTrue(quote(line(noGift), caller = caller, recipient = "alex").lines.single().errors.isEmpty(), "the own name, any case")
        assertTrue(quote(line(normal), caller = caller, recipient = "Bob").canCheckout)

        val unknown = quote(line(normal), caller = caller, recipient = "Never_Joined")

        assertEquals("warning", unknown.message("RECIPIENT_UNKNOWN")!!.level)
        assertTrue(unknown.canCheckout, "a gift to somebody who never joined is not blocking")

        config = base(gifts = false)

        val off = quote(line(normal), caller = caller, recipient = "Bob")

        assertEquals("error", off.message("INVALID_RECIPIENT")!!.level)
        assertFalse(off.canCheckout)

        config = base()

        val pack = fx.product(price = 500).also { raw(it, "kind" to "CREDIT_PACK", "creditAmount" to 5000) }

        assertNotNull(quote(line(pack), caller = caller, recipient = "Never_Joined").message("INVALID_RECIPIENT"), "credits need an account")
        assertNull(quote(line(pack), caller = caller, recipient = "Bob").message("INVALID_RECIPIENT"))
    }

    @Test
    fun `a gift credit top-up needs a registered recipient`(): Unit = runBlocking {
        method()
        config = base(topUp = true, topUpFree = true)
        val (_, caller) = user("Alex")
        user("Bob")

        val ok = quote(topUp = TopUpRequest(2500), caller = caller, recipient = "Bob")

        assertTrue(ok.canCheckout, "a gift top-up to a registered player is checkoutable")
        assertNull(ok.message("INVALID_RECIPIENT"))
        assertNull(ok.message("RECIPIENT_UNKNOWN"))

        val unknown = quote(topUp = TopUpRequest(2500), caller = caller, recipient = "Never_Joined")

        assertEquals("error", unknown.message("INVALID_RECIPIENT")!!.level, "credits need an account")
        assertFalse(unknown.canCheckout)

        assertTrue(quote(topUp = TopUpRequest(2500), caller = caller, recipient = "alex").canCheckout, "the own name is no gift")
    }

    @Test
    fun `LOGIN_REQUIRED for a guest and a credit pack or a subscription and for a store that refuses guests`(): Unit = runBlocking {
        config = base(topUp = true) // a credit pack is a line error PRODUCT_UNAVAILABLE while the top-up is off (07 section 8)
        val pack = fx.product(price = 500).also { raw(it, "kind" to "CREDIT_PACK", "creditAmount" to 5000) }
        val sub = fx.product(price = 500).also { raw(it, "billingMode" to "SUBSCRIPTION", "periodUnit" to "MONTH", "periodCount" to 1) }
        val plain = fx.product()

        assertEquals(listOf("LOGIN_REQUIRED"), quote(line(pack)).lines.single().errors)
        assertEquals(listOf("LOGIN_REQUIRED"), quote(line(sub)).lines.single().errors)
        assertTrue(quote(line(plain)).canCheckout)

        config = base(guest = false)

        val q = quote(line(plain))

        assertEquals("error", q.message("LOGIN_REQUIRED")!!.level)
        assertFalse(q.canCheckout)
        assertTrue(quote(line(plain), caller = user("Alex").second).canCheckout)
    }

    // ======================================================================================== limits / cooldown

    @Test
    fun `the per player limit counts held and committed orders of the recipient and frees released ones`(): Unit = runBlocking {
        val p = fx.product().also { raw(it, "limitPerPlayer" to 3) }
        val (alex, caller) = user("Alex")
        val key = "u:${alex.id}"

        assertTrue(quote(line(p, 3), caller = caller).canCheckout)

        order(key, p.id, 2, ReservationState.COMMITTED)

        assertTrue(quote(line(p, 1), caller = caller).canCheckout)

        val over = quote(line(p, 2), caller = caller).lines.single()

        assertEquals(listOf("PURCHASE_LIMIT_REACHED"), over.errors)
        assertEquals(1, over.maxQuantity)

        order(key, p.id, 1, ReservationState.HELD)

        assertEquals(listOf("PURCHASE_LIMIT_REACHED"), quote(line(p, 1), caller = caller).lines.single().errors)
        assertEquals(0, quote(line(p, 1), caller = caller).lines.single().maxQuantity)

        // released orders and refunded units free the allowance
        order(key, p.id, 5, ReservationState.RELEASED)
        assertEquals(listOf("PURCHASE_LIMIT_REACHED"), quote(line(p, 1), caller = caller).lines.single().errors)

        val refunded = fx.product().also { raw(it, "limitPerPlayer" to 2) }

        order(key, refunded.id, 2, ReservationState.COMMITTED, refunded = 1)

        assertTrue(quote(line(refunded, 1), caller = caller).canCheckout, "one of two units was refunded")
        assertEquals(listOf("PURCHASE_LIMIT_REACHED"), quote(line(refunded, 2), caller = caller).lines.single().errors)
    }

    @Test
    fun `the limit and the cooldown belong to the recipient and an unpaid gift of a stranger does not count`(): Unit = runBlocking {
        val p = fx.product().also { raw(it, "limitPerPlayer" to 1, "cooldownSeconds" to 3600) }
        val (_, alexCaller) = user("Alex")
        val (bob, bobCaller) = user("Bob")
        val (carol, _) = user("Carol")
        val bobKey = "u:${bob.id}"

        // Carol has an unpaid (HELD) gift order for Bob: neither the limit nor the cooldown is used up for Bob
        val held = order(bobKey, p.id, 1, ReservationState.HELD, buyerKey = "u:${carol.id}")

        assertTrue(quote(line(p), caller = bobCaller).canCheckout)
        assertTrue(quote(line(p), caller = alexCaller, recipient = "Bob").canCheckout)

        // once it is paid (COMMITTED) it counts for Bob, whoever bought it
        Fixtures.setColumns(pool, "market_order", held, mapOf("reservationState" to "COMMITTED"))

        assertEquals(listOf("PURCHASE_LIMIT_REACHED", "COOLDOWN_ACTIVE"), quote(line(p), caller = bobCaller).lines.single().errors)
        assertEquals(listOf("PURCHASE_LIMIT_REACHED", "COOLDOWN_ACTIVE"), quote(line(p), caller = alexCaller, recipient = "Bob").lines.single().errors, "the gift is judged on Bob")
        // Alex himself is clean
        assertTrue(quote(line(p), caller = alexCaller).canCheckout)
    }

    @Test
    fun `rows written under the guest key still count for the registered player`(): Unit = runBlocking {
        val p = fx.product().also { raw(it, "limitPerPlayer" to 1) }
        val (_, caller) = user("Alex")

        order("g:alex", p.id, 1, ReservationState.COMMITTED)

        assertEquals(listOf("PURCHASE_LIMIT_REACHED"), quote(line(p), caller = caller).lines.single().errors)
    }

    @Test
    fun `COOLDOWN_ACTIVE with retryAfter in the message of a pending order and free after the cooldown`(): Unit = runBlocking {
        val p = fx.product().also { raw(it, "cooldownSeconds" to 60) }
        val (alex, caller) = user("Alex")

        w.clock.set(1_800_000_000_000)
        order("u:${alex.id}", p.id, 1, ReservationState.HELD, createdAt = w.clock.now() - 10_000)

        val l = quote(line(p), caller = caller).lines.single()

        assertEquals(listOf("COOLDOWN_ACTIVE"), l.errors, "a pending order starts the cooldown")
        assertEquals(0, l.maxQuantity)

        w.clock.advance(49_999)
        assertEquals(listOf("COOLDOWN_ACTIVE"), quote(line(p), caller = caller).lines.single().errors)
        w.clock.advance(1)
        assertTrue(quote(line(p), caller = caller).canCheckout, "exactly cooldownSeconds after the order")
    }

    @Test
    fun `required products are satisfied by an entitlement or by the same cart`(): Unit = runBlocking {
        val base = fx.product(slug = "base")
        val other = fx.product(slug = "other")
        val dependent = fx.product(slug = "dependent").also { raw(it, "requiredProducts" to "[${base.id},${other.id}]") }
        val either = fx.product(slug = "either").also { raw(it, "requiredProducts" to "[${base.id},${other.id}]", "requireOnlyOne" to true) }
        val (alex, caller) = user("Alex")

        assertEquals(listOf("REQUIREMENT_NOT_MET"), quote(line(dependent), caller = caller).lines.single().errors)
        assertEquals(listOf("REQUIREMENT_NOT_MET"), quote(line(either), caller = caller).lines.single().errors)

        entitlement(alex.id, base)

        assertEquals(listOf("REQUIREMENT_NOT_MET"), quote(line(dependent), caller = caller).lines.single().errors, "all of them")
        assertTrue(quote(line(either), caller = caller).lines.single().errors.isEmpty(), "one of them")

        // the prerequisite in the same cart counts
        assertTrue(quote(line(dependent), line(other), caller = caller).lines.all { it.errors.isEmpty() })

        // a deleted prerequisite is ignored
        val orphan = fx.product(slug = "orphan").also { raw(it, "requiredProducts" to "[${fx.product(slug = "gone").also { g -> raw(g, "deletedAt" to 1L) }.id}]") }

        assertTrue(quote(line(orphan), caller = caller).lines.single().errors.isEmpty())
    }

    // ============================================================================================ subscriptions

    @Test
    fun `a subscription is alone quantity one and carries QUANTITY_REDUCED`(): Unit = runBlocking {
        method()
        val sub = fx.product(price = 900).also { raw(it, "billingMode" to "SUBSCRIPTION", "periodUnit" to "MONTH", "periodCount" to 1) }
        val plain = fx.product()
        val (_, caller) = user("Alex")

        val alone = quote(line(sub, 3), caller = caller)

        assertTrue(alone.canCheckout)
        assertEquals(1, alone.lines.single().quantity)
        assertEquals("warning", alone.message("QUANTITY_REDUCED")!!.level)
        assertEquals(alone.lines.single().lineKey, alone.message("QUANTITY_REDUCED")!!.lineKey)
        assertEquals("SUBSCRIPTION", alone.lines.single().billingMode)

        val mixed = quote(line(sub), line(plain), caller = caller)

        assertEquals("error", mixed.message("SUBSCRIPTION_MUST_BE_ALONE")!!.level)
        assertFalse(mixed.canCheckout)
    }

    @Test
    fun `a coupon on a subscription is not applicable and useCredits is ignored with a warning`(): Unit = runBlocking {
        val sub = fx.product(price = 1000).also { raw(it, "billingMode" to "SUBSCRIPTION", "periodUnit" to "MONTH", "periodCount" to 1) }
        val coupon = fx.coupon("SUB10")
        config = base(mixed = true)
        val (_, caller) = user("Alex", credit = 5000)

        val q = quote(line(sub), caller = caller, code = coupon.code, credits = UseCredits.Amount(500))

        assertFalse(q.coupon!!.valid)
        assertEquals("COUPON_NOT_APPLICABLE", q.coupon!!.reason)
        assertEquals(0L, q.couponDiscount)
        assertEquals("warning", q.message("MIXED_CREDIT_NOT_SUPPORTED")!!.level)
        assertEquals(0L, q.credits!!.applied)
    }

    // ============================================================================================ money rules

    @Test
    fun `the minimum order amount is judged on the goods after discounts and skips free and credit covered orders`(): Unit = runBlocking {
        method()
        config = base(minimum = 50.0)
        val cheap = fx.product(price = 3000)
        val free = fx.product(price = 0)
        val big = fx.product(price = 6000)

        val low = quote(line(cheap))

        assertEquals("error", low.message("MINIMUM_ORDER_AMOUNT_NOT_REACHED")!!.level)
        assertFalse(low.canCheckout)
        assertEquals(50.0, low.toJson().getDouble("minimumOrderAmount"))
        assertTrue(quote(line(big)).canCheckout)
        assertTrue(quote(line(free)).canCheckout, "free orders stay obtainable")
        assertNull(quote(line(free)).message("MINIMUM_ORDER_AMOUNT_NOT_REACHED"))

        // a coupon that takes the goods below the minimum
        val half = fx.coupon("HALF", discount = 5000)
        val discounted = quote(line(big), code = half.code)

        assertEquals(3000L, discounted.total)
        assertNotNull(discounted.message("MINIMUM_ORDER_AMOUNT_NOT_REACHED"))

        // fully covered by credits: exempt
        val rich = fx.product(price = 3000, creditPrice = 3000)
        val (_, caller) = user("Alex", credit = 10_000)
        val paid = quote(line(rich), caller = caller, payWithCredits = true)

        assertNull(paid.message("MINIMUM_ORDER_AMOUNT_NOT_REACHED"))
        assertTrue(paid.canCheckout)
        assertEquals(0L, paid.gatewayAmount)
    }

    @Test
    fun `coupons and creator codes report valid or the reason and move the total`(): Unit = runBlocking {
        val p = fx.product(price = 10_000)
        val coupon = fx.coupon("TEN", discount = 1000)
        val creator = fx.creatorCode("STREAM", creator = "Streamer", discount = 500)
        val used = fx.coupon("ONCE", discount = 1000, customerRedeemLimit = 1)
        val (alex, caller) = user("Alex")
        user("Streamer")

        val ok = quote(line(p), code = coupon.code, creator = creator.code)

        assertTrue(ok.coupon!!.valid)
        assertNull(ok.coupon!!.reason)
        assertEquals(1000L, ok.couponDiscount)
        assertTrue(ok.creatorCode!!.valid)
        assertTrue(ok.creatorDiscount > 0)
        assertEquals(10_000 - ok.couponDiscount - ok.creatorDiscount, ok.total)

        val bad = quote(line(p), code = "missing")

        assertFalse(bad.coupon!!.valid)
        assertEquals("CODE_NOT_FOUND", bad.coupon!!.reason)
        assertNotNull(bad.message("CODE_NOT_FOUND"))
        assertFalse(bad.canCheckout)

        // per customer limit: the redemption of this buyer is counted
        w.redemptions.add(
            com.panomc.plugins.market.db.model.MarketRedemption(
                kind = RedemptionKind.COUPON, refId = used.id, code = used.code, orderId = 1, userId = alex.id, buyerKey = "u:${alex.id}",
                recipientKey = "u:${alex.id}", state = RedemptionState.APPLIED, currency = "EUR"
            ),
            pool
        )

        assertEquals("CODE_LIMIT_REACHED", quote(line(p), caller = caller, code = used.code).coupon!!.reason)
        assertTrue(quote(line(p), code = used.code).coupon!!.valid, "another buyer is not limited")
    }

    @Test
    fun `CODE_EXPIRED and CODE_NOT_STARTED come from the dates of the code rows and deduct nothing`(): Unit = runBlocking {
        val p = fx.product(price = 10_000)
        val now = w.clock.now()
        val expired = fx.coupon("OLD", discount = 1000).also { rawRow("market_coupon", it.id, "expiryDate" to now - 1000) }
        val atExpiry = fx.coupon("EDGE", discount = 1000).also { rawRow("market_coupon", it.id, "expiryDate" to now) }
        val future = fx.coupon("SOON", discount = 1000).also { rawRow("market_coupon", it.id, "startDate" to now + 60_000) }
        val running = fx.coupon("LIVE", discount = 1000).also { rawRow("market_coupon", it.id, "startDate" to now - 1000, "expiryDate" to now + 60_000) }
        user("Streamer")
        val oldCreator = fx.creatorCode("OLDSTREAM", creator = "Streamer", discount = 500).also { rawRow("market_creator_code", it.id, "expiryDate" to now - 1000) }
        val newCreator = fx.creatorCode("NEWSTREAM", creator = "Streamer", discount = 500).also { rawRow("market_creator_code", it.id, "startDate" to now + 60_000) }

        val e = quote(line(p), code = expired.code)

        assertFalse(e.coupon!!.valid)
        assertEquals("CODE_EXPIRED", e.coupon!!.reason)
        assertEquals("error", e.message("CODE_EXPIRED")!!.level)
        assertEquals(0L, e.couponDiscount)
        assertEquals(10_000L, e.total)
        assertFalse(e.canCheckout)

        assertEquals("CODE_EXPIRED", quote(line(p), code = atExpiry.code).coupon!!.reason, "the expiry instant itself is already over")

        val f = quote(line(p), code = future.code)

        assertFalse(f.coupon!!.valid)
        assertEquals("CODE_NOT_STARTED", f.coupon!!.reason)
        assertEquals("error", f.message("CODE_NOT_STARTED")!!.level)
        assertEquals(0L, f.couponDiscount)
        assertEquals(10_000L, f.total)
        assertFalse(f.canCheckout)

        val live = quote(line(p), code = running.code)

        assertTrue(live.coupon!!.valid)
        assertEquals(1000L, live.couponDiscount)

        val oc = quote(line(p), creator = oldCreator.code)

        assertFalse(oc.creatorCode!!.valid)
        assertEquals("CODE_EXPIRED", oc.creatorCode!!.reason)
        assertNotNull(oc.message("CODE_EXPIRED"))
        assertEquals(0L, oc.creatorDiscount)
        assertEquals(10_000L, oc.total)

        val nc = quote(line(p), creator = newCreator.code)

        assertFalse(nc.creatorCode!!.valid)
        assertEquals("CODE_NOT_STARTED", nc.creatorCode!!.reason)
        assertNotNull(nc.message("CODE_NOT_STARTED"))
        assertEquals(0L, nc.creatorDiscount)
        assertEquals(10_000L, nc.total)
    }

    @Test
    fun `CODE_MIN_AMOUNT compares the goods with minPaymentAmount of the coupon row`(): Unit = runBlocking {
        val p = fx.product(price = 10_000)
        val coupon = fx.coupon("BIGSPENDER", discount = 1000, minPaymentAmount = 15_000)

        val low = quote(line(p), code = coupon.code)

        assertFalse(low.coupon!!.valid)
        assertEquals("CODE_MIN_AMOUNT", low.coupon!!.reason)
        assertEquals("error", low.message("CODE_MIN_AMOUNT")!!.level)
        assertEquals(0L, low.couponDiscount)
        assertEquals(10_000L, low.total)
        assertFalse(low.canCheckout)

        val enough = quote(line(p, 2), code = coupon.code)

        assertTrue(enough.coupon!!.valid)
        assertNull(enough.message("CODE_MIN_AMOUNT"))
        assertEquals(2000L, enough.couponDiscount)
        assertEquals(18_000L, enough.total)
    }

    @Test
    fun `CODE_NOT_COMBINABLE when combineDiscountsAndCoupons is off and an automatic discount applies`(): Unit = runBlocking {
        val p = fx.product(price = 10_000)
        fx.discount(value = 2000, unit = DiscountUnit.PERCENT)
        val coupon = fx.coupon("TENP", discount = 1000)
        user("Streamer")
        val creator = fx.creatorCode("STREAMER5", creator = "Streamer", discount = 500)

        // combining allowed: discount first, the coupon on what is left
        val combined = quote(line(p), code = coupon.code)

        assertTrue(combined.coupon!!.valid)
        assertEquals(800L, combined.couponDiscount, "10 % of the 8000 that the automatic discount left")
        assertEquals(7200L, combined.total)
        assertNull(combined.message("CODE_NOT_COMBINABLE"))

        config = base(combine = false)
        val automaticOnly = quote(line(p)).total

        val q = quote(line(p), code = coupon.code)

        assertFalse(q.coupon!!.valid)
        assertEquals("CODE_NOT_COMBINABLE", q.coupon!!.reason)
        assertEquals("error", q.message("CODE_NOT_COMBINABLE")!!.level)
        assertEquals(0L, q.couponDiscount)
        assertEquals(automaticOnly, q.total, "nothing of the refused coupon is deducted")
        assertFalse(q.canCheckout)

        // a creator code stays an attribution but discounts nothing
        val c = quote(line(p), creator = creator.code)

        assertTrue(c.creatorCode!!.valid)
        assertEquals("CODE_NOT_COMBINABLE", c.creatorCode!!.reason)
        assertEquals(0L, c.creatorDiscount)
        assertEquals(automaticOnly, c.total)
    }

    @Test
    fun `CREDITS_ONLY for a product priced in credits only in a money quote`(): Unit = runBlocking {
        method()
        val creditsOnly = fx.product(price = 0, creditPrice = 500)
        val free = fx.product(price = 0)
        val (_, caller) = user("Alex", credit = 10_000)

        val q = quote(line(creditsOnly), caller = caller)

        assertEquals(listOf("CREDITS_ONLY"), q.lines.single().errors)
        assertFalse(q.canCheckout, "it must never become free")

        assertFalse(quote(line(creditsOnly)).canCheckout, "also for a guest")
        assertTrue(quote(line(free), caller = caller).lines.single().errors.isEmpty(), "a plain free product stays free")

        val paid = quote(line(creditsOnly), caller = caller, payWithCredits = true)

        assertFalse(paid.lines.single().errors.contains("CREDITS_ONLY"), "paid with credits it is a normal credit order")
        assertTrue(paid.canCheckout)
    }

    @Test
    fun `automatic discounts and a lower case code work like the storefront card`(): Unit = runBlocking {
        val p = fx.product(price = 10_000)
        fx.discount(value = 2000, unit = DiscountUnit.PERCENT)
        val coupon = fx.coupon("MIXED", discount = 1000)

        assertEquals(8000L, quote(line(p)).total)
        assertEquals(8000L, quote(line(p), code = "nonsense").total)
        assertTrue(quote(line(p), code = " mixed ").coupon!!.valid, "codes are trimmed and compared upper case")
        assertEquals(coupon.code, quote(line(p), code = "mixed").coupon!!.code)
    }

    // ======================================================================================================= credits

    @Test
    fun `useCredits MAX is applied up to what the balance and the total allow and a number is clamped with a warning`(): Unit = runBlocking {
        method()
        config = base(mixed = true)
        val p = fx.product(price = 2000, creditPrice = 2000)
        val (_, caller) = user("Alex", credit = 700)

        val max = quote(line(p), caller = caller, credits = UseCredits.Max, method = "fake")

        assertEquals(700L, max.credits!!.maxApplicable)
        assertEquals(700L, max.credits!!.applied)
        assertEquals(700L, max.credits!!.appliedValue)
        assertEquals(1300L, max.gatewayAmount)
        assertEquals(2000L, max.total)
        assertTrue(max.canCheckout)

        val clamped = quote(line(p), caller = caller, credits = UseCredits.Amount(9_000), method = "fake")

        assertEquals(700L, clamped.credits!!.applied)
        assertEquals("warning", clamped.message("CREDITS_REDUCED")!!.level)

        val exact = quote(line(p), caller = caller, credits = UseCredits.Amount(300), method = "fake")

        assertEquals(300L, exact.credits!!.applied)
        assertNull(exact.message("CREDITS_REDUCED"))
        assertEquals(1700L, exact.gatewayAmount)

        // never automatic: no useCredits, no credits
        val none = quote(line(p), caller = caller, method = "fake")

        assertEquals(0L, none.credits!!.applied)
        assertEquals(2000L, none.gatewayAmount)
        assertEquals(700L, none.credits!!.balance)
    }

    @Test
    fun `payWithCredits and the credits flag of a guest`(): Unit = runBlocking {
        val p = fx.product(price = 2000, creditPrice = 1500)
        val (_, caller) = user("Alex", credit = 1500)

        val paid = quote(line(p), caller = caller, payWithCredits = true)

        assertTrue(paid.canCheckout)
        assertEquals(0L, paid.gatewayAmount)
        assertEquals(1500L, paid.credits!!.creditTotal)
        assertEquals(1500L, paid.credits!!.applied)
        assertTrue(paid.credits!!.payableInCredits)
        assertEquals(1500L, paid.lines.single().creditUnitPrice)

        // the equivalent form
        assertEquals(1500L, quote(line(p), caller = caller, method = "credits").credits!!.applied)

        // not enough credits
        val (_, poor) = user("Poor", credit = 100)
        val short = quote(line(p), caller = poor, payWithCredits = true)

        assertEquals("error", short.message("INSUFFICIENT_CREDITS")!!.level)
        assertFalse(short.canCheckout)

        // a guest cannot
        val guest = quote(line(p), payWithCredits = true)

        assertNotNull(guest.message("LOGIN_REQUIRED"))
        assertFalse(guest.canCheckout)

        // a product without a credit price is not payable with credits
        val money = fx.product(price = 500)
        val cart = quote(line(money), caller = caller, payWithCredits = true)

        assertEquals(listOf("NOT_PAYABLE_WITH_CREDITS"), cart.lines.single().errors)
        assertFalse(cart.canCheckout)
    }

    @Test
    fun `onlyAcceptCredits empties the method list of a product cart and refuses a gateway choice`(): Unit = runBlocking {
        method()
        config = base(onlyCredits = true)
        val p = fx.product(price = 1000, creditPrice = 1000)
        val (_, caller) = user("Alex", credit = 5000)

        val q = quote(line(p), caller = caller, method = "fake")

        assertTrue(q.paymentMethods.isEmpty())
        assertEquals("CREDITS_REQUIRED", q.message("CREDITS_REQUIRED")?.code)
        assertFalse(q.canCheckout)
        assertTrue(quote(line(p), caller = caller).canCheckout, "the whole order is paid with credits")
    }

    // ================================================================================================ top-up

    @Test
    fun `a free amount credit top-up is priced and an invalid amount is a message`(): Unit = runBlocking {
        method()
        config = base(topUp = true, topUpFree = true)
        val (_, caller) = user("Alex")

        val q = quote(topUp = TopUpRequest(2500), caller = caller)

        assertTrue(q.canCheckout)
        assertEquals("CREDIT_TOPUP", q.lines.single().kind)
        assertNull(q.lines.single().productId)
        assertEquals(2500L, q.total)
        assertNull(q.message("MINIMUM_ORDER_AMOUNT_NOT_REACHED"))

        for (bad in listOf(TopUpRequest(null), TopUpRequest(0), TopUpRequest(50), TopUpRequest(2_000_000))) {
            val r = quote(topUp = bad, caller = caller)

            assertEquals("error", r.message("INVALID_CREDIT_AMOUNT")!!.level, "${bad.credits}")
            assertFalse(r.canCheckout)
        }

        assertNotNull(quote(topUp = TopUpRequest(2500)).message("LOGIN_REQUIRED"), "a guest cannot top up")

        config = base(topUp = false)

        assertNotNull(quote(topUp = TopUpRequest(2500), caller = caller).message("INVALID_CREDIT_AMOUNT"))

        config = base(topUp = true, topUpFree = true)

        assertThrows<BadRequest> {
            service.quote(QuoteInput(items = listOf(CartLine(1, 0, 1)), creditTopUp = TopUpRequest(2500)), caller, pool)
        }
    }

    // ===================================================================================================== bundles

    @Test
    fun `a bundle lists its children and is judged on them`(): Unit = runBlocking {
        val a = fx.product(slug = "a", stock = 3)
        val b = fx.product(slug = "b")
        val bundle = fx.bundle(a to 2, b to 1, price = 1500)

        val q = quote(line(bundle))

        assertTrue(q.canCheckout)
        assertEquals(3, q.lines.size)
        assertEquals("BUNDLE", q.lines[0].kind)
        assertEquals(1500, q.lines[0].lineTotal)
        assertEquals(listOf("BUNDLE_CHILD", "BUNDLE_CHILD"), q.lines.drop(1).map { it.kind })
        assertEquals(q.lines[0].lineKey, q.lines[1].parentLineKey)
        assertEquals(2, q.lines[1].quantity)
        assertEquals(0, q.lines[1].lineTotal)
        assertEquals("a", q.lines[1].slug)

        // 2 bundles = 4 of a, stock 3
        val over = quote(line(bundle, 2))

        assertEquals(listOf("MAX_QUANTITY"), over.lines[0].errors)
        assertEquals(1, over.lines[0].maxQuantity)
        assertFalse(over.canCheckout)

        raw(b, "deletedAt" to w.clock.now())

        val gone = quote(line(bundle))

        assertEquals(listOf("PRODUCT_UNAVAILABLE"), gone.lines.single().errors)
    }

    // =================================================================================================== currency

    @Test
    fun `a foreign currency in SINGLE mode is a warning and MULTI HIDE makes NOT_IN_CURRENCY`(): Unit = runBlocking {
        val p = fx.product(price = 1000)

        val single = quote(line(p), currency = "USD")

        assertEquals("EUR", single.currency)
        assertEquals("warning", single.message("CURRENCY_NOT_SUPPORTED")!!.level)
        assertTrue(single.canCheckout)

        w.currencyRates.upsert(MarketCurrencyRate(currency = "USD", rate = java.math.BigDecimal("1.1"), mode = CurrencyRateMode.MANUAL, fetchedAt = w.clock.now()), pool)
        config = base(currencyMode = CurrencyMode.MULTI, additional = listOf("USD"), fallback = MultiCurrencyFallback.HIDE)

        val hidden = quote(line(p), currency = "USD")

        assertEquals("USD", hidden.currency)
        assertEquals(listOf("NOT_IN_CURRENCY"), hidden.lines.single().errors)
        assertFalse(hidden.canCheckout)

        config = base(currencyMode = CurrencyMode.MULTI, additional = listOf("USD"), fallback = MultiCurrencyFallback.CONVERT)

        val converted = quote(line(p), currency = "USD")

        assertTrue(converted.canCheckout)
        assertEquals(1100, converted.lines.single().unitPrice)
        assertEquals("USD", converted.currency)
    }

    // ===================================================================================================== cart

    @Test
    fun `a logged in caller without items uses the server cart and its cart level fields`(): Unit = runBlocking {
        val p = fx.product(price = 1000)
        val coupon = fx.coupon("CARTCODE", discount = 1000)
        val (alex, caller) = user("Alex")
        val cartId = w.carts.ensure(alex.id, w.clock.now(), pool)

        w.cartItems.upsertAdd(
            com.panomc.plugins.market.db.model.MarketCartItem(cartId = cartId, productId = p.id, quantity = 2, lineKey = CartLine(p.id, 0, 2).lineKey, createdAt = 1, updatedAt = 1), pool
        )
        w.carts.updateFields(cartId, mapOf("couponCode" to coupon.code), w.clock.now(), pool)

        val q = quote(caller = caller, items = null)

        assertEquals(1, q.lines.size)
        assertEquals(2, q.lines.single().quantity)
        assertTrue(q.coupon!!.valid)
        assertEquals(200L, q.couponDiscount)

        // request lines win over the server cart
        assertEquals(1, quote(line(p, 1), caller = caller).lines.single().quantity)
        assertNull(quote(line(p, 1), caller = caller).coupon, "the cart level code does not leak into an explicit request")

        // a guest has no server cart
        assertTrue(quote(items = null).lines.isEmpty())
    }

    @Test
    fun `equal lines are summed quantities are clamped and more than 50 lines are refused`(): Unit = runBlocking {
        val p = fx.product(price = 100)

        val summed = quote(line(p, 2), line(p, 3))

        assertEquals(1, summed.lines.size)
        assertEquals(5, summed.lines.single().quantity)
        assertEquals(999, quote(line(p, 700), line(p, 700)).lines.single().quantity)

        val many = (1..51).map { fx.product(slug = "p$it") }

        assertThrows<InvalidCart> { quote(*many.map { line(it) }.toTypedArray()) }
        assertTrue(quote(*many.take(50).map { line(it) }.toTypedArray()).lines.size == 50)
    }

    // ================================================================================================== legal

    @Test
    fun `the legal block follows the active text and legalTextRequired`(): Unit = runBlocking {
        val p = fx.product()

        assertNull(quote(line(p)).legal)

        legal.publish("en-US", "Terms", "<p>Be nice</p>", null)
        config = base(legalRequired = true)

        val q = quote(line(p))

        assertTrue(q.legal!!.required)
        assertEquals("Terms", q.legal!!.title)
        assertEquals(1, q.legal!!.version)
        assertTrue(q.canCheckout, "the unticked box is checked by checkout, not by the quote")
        assertEquals("Terms", q.toJson().getJsonObject("legal").getString("title"))

        config = base(legalRequired = false)

        assertFalse(quote(line(p)).legal!!.required)
    }

    // ========================================================================================== buyer, shipping

    @Test
    fun `a blocked buyer is a warning and the quote stays usable`(): Unit = runBlocking {
        val p = fx.product()

        blocked = true

        val q = quote(line(p))

        assertEquals("warning", q.message("BUYER_BLOCKED")!!.level)
        assertTrue(q.canCheckout)
    }

    @Test
    fun `a physical cart needs an address and a shipping option`(): Unit = runBlocking {
        val p = fx.product(price = 2000).also { raw(it, "physical" to true, "weightGrams" to 500) }

        val none = quote(line(p))

        assertTrue(none.requiresShipping)
        assertEquals("error", none.message("SHIPPING_ADDRESS_REQUIRED")!!.level)
        assertFalse(none.canCheckout)

        val addressed = quote(line(p), shippingAddress = JsonObject().put("country", "DE"))

        assertEquals("error", addressed.message("SHIPPING_UNAVAILABLE")!!.level)
        assertFalse(addressed.canCheckout)
        assertEquals(1, shippingRequest!!.physicalLines.size)
        assertEquals(2000L, shippingRequest!!.physicalBasisBase)

        shippingResult = ShippingQuote(ShippingCharge(600, null), listOf(JsonObject().put("methodId", 4).put("name", "Standard")), 4)

        val priced = quote(line(p), shippingAddress = JsonObject().put("country", "DE"))

        assertTrue(priced.canCheckout)
        assertEquals(600L, priced.shippingTotal)
        assertEquals(2600L, priced.total)
        assertEquals(4L, priced.shippingMethodId)
        assertEquals("Standard", priced.shippingOptions.single().getString("name"))
    }

    // ====================================================================================== payment methods

    @Test
    fun `the list holds enabled configured methods in panel order and never credits or free`(): Unit = runBlocking {
        val other = FakePaymentProvider("fake-two")
        lookup.add(other)
        method()
        fx.paymentMethod("fake-two", true)
        Fixtures.setColumns(pool, "market_payment_method", w.paymentMethods.getByMethodId("fake-two", pool)!!.id, mapOf("position" to 1, "customLabel" to "Second gate"))
        Fixtures.setColumns(pool, "market_payment_method", w.paymentMethods.getByMethodId("fake", pool)!!.id, mapOf("position" to 5))
        fx.paymentMethod("credits", true)
        fx.paymentMethod("free", true)
        fx.paymentMethod("disabled-one", false)
        fx.paymentMethod("ghost", true)

        val q = quote(line(fx.product()))

        assertEquals(listOf("fake-two", "fake"), q.paymentMethods.map { it.id })
        assertEquals("Second gate", q.paymentMethods[0].label)
        assertEquals("Fake payment", q.paymentMethods[1].label)
        assertEquals("Scriptable test provider", q.paymentMethods[1].description)
        assertTrue(q.paymentMethods.all { it.available && it.unavailableReason == null && it.pricing == "MARKET" })

        // a provider that disappears is not offered
        lookup.remove("fake-two")

        assertEquals(listOf("fake"), quote(line(fx.product())).paymentMethods.map { it.id })
    }

    @Test
    fun `currency and amount windows give CURRENCY_NOT_SUPPORTED AMOUNT_BELOW_MINIMUM and AMOUNT_ABOVE_MAXIMUM`(): Unit = runBlocking {
        val p = fx.product(price = 5000)

        method(min = 6000)
        assertEquals("AMOUNT_BELOW_MINIMUM", quote(line(p)).paymentMethods.single().unavailableReason)
        assertTrue(quote(line(p, 2)).paymentMethods.single().available)

        method(max = 4000)
        assertEquals("AMOUNT_ABOVE_MAXIMUM", quote(line(p)).paymentMethods.single().unavailableReason)

        // the window is measured before the fee and before credits
        method(min = 4000, max = 5000, feeMode = PaymentFeeMode.BUYER, feePercent = 2000)
        assertTrue(quote(line(p)).paymentMethods.single().available)

        method()
        fake.caps = com.panomc.plugins.market.spi.payment.PaymentCapabilities().apply { currencies = setOf("USD") }
        assertEquals("CURRENCY_NOT_SUPPORTED", quote(line(p)).paymentMethods.single().unavailableReason)

        fake.caps = com.panomc.plugins.market.spi.payment.PaymentCapabilities().apply { minAmount = Money(6000, "EUR") }
        assertEquals("AMOUNT_BELOW_MINIMUM", quote(line(p)).paymentMethods.single().unavailableReason)

        fake.caps = com.panomc.plugins.market.spi.payment.PaymentCapabilities().apply { maxAmount = Money(4000, "EUR") }
        assertEquals("AMOUNT_ABOVE_MAXIMUM", quote(line(p)).paymentMethods.single().unavailableReason)

        // the admin currency list
        fake.caps = com.panomc.plugins.market.spi.payment.PaymentCapabilities()
        Fixtures.setColumns(pool, "market_payment_method", w.paymentMethods.getByMethodId("fake", pool)!!.id, mapOf("currencies" to "[\"USD\"]"))
        assertEquals("CURRENCY_NOT_SUPPORTED", quote(line(p)).paymentMethods.single().unavailableReason)
    }

    @Test
    fun `GUESTS_NOT_SUPPORTED and PHYSICAL_NOT_SUPPORTED`(): Unit = runBlocking {
        method()
        val p = fx.product()
        val physical = fx.product().also { raw(it, "physical" to true) }
        val (_, caller) = user("Alex")

        fake.caps = com.panomc.plugins.market.spi.payment.PaymentCapabilities().apply { guests = false }

        assertEquals("GUESTS_NOT_SUPPORTED", quote(line(p)).paymentMethods.single().unavailableReason)
        assertTrue(quote(line(p), caller = caller).paymentMethods.single().available)

        fake.caps = com.panomc.plugins.market.spi.payment.PaymentCapabilities().apply { physicalGoods = false }

        assertEquals("PHYSICAL_NOT_SUPPORTED", quote(line(physical)).paymentMethods.single().unavailableReason)
        assertTrue(quote(line(p)).paymentMethods.single().available)
    }

    @Test
    fun `a test mode method is available only to the holder of SET PAY or the umbrella node`(): Unit = runBlocking {
        method()
        val p = fx.product()
        val (_, caller) = user("Alex")

        // the store switch (FLAG support)
        config = base(testMode = true)

        val guest = quote(line(p)).paymentMethods.single()

        assertTrue(guest.testMode)
        assertFalse(guest.available)
        assertEquals("TEST_MODE", guest.unavailableReason)
        assertEquals("TEST_MODE", quote(line(p), caller = caller).paymentMethods.single().unavailableReason)
        assertTrue(quote(line(p), caller = QuoteCaller(caller.userId, canUseTestMode = true)).paymentMethods.single().available)

        // selecting it makes the quote unusable and says why
        val chosen = quote(line(p), method = "fake")

        assertFalse(chosen.canCheckout)
        assertEquals("error", chosen.message("TEST_MODE")!!.level)

        // the method's own flag
        config = base()
        Fixtures.setColumns(pool, "market_payment_method", w.paymentMethods.getByMethodId("fake", pool)!!.id, mapOf("testMode" to true))
        assertEquals("TEST_MODE", quote(line(p)).paymentMethods.single().unavailableReason)

        // DERIVED from the keys, NONE never
        fake.caps = com.panomc.plugins.market.spi.payment.PaymentCapabilities().apply { testMode = TestModeSupport.DERIVED; derivedTestMode = false }
        assertTrue(quote(line(p)).paymentMethods.single().available)
        fake.caps = com.panomc.plugins.market.spi.payment.PaymentCapabilities().apply { testMode = TestModeSupport.DERIVED; derivedTestMode = true }
        assertEquals("TEST_MODE", quote(line(p)).paymentMethods.single().unavailableReason)
        fake.caps = com.panomc.plugins.market.spi.payment.PaymentCapabilities().apply { testMode = TestModeSupport.NONE }
        assertTrue(quote(line(p)).paymentMethods.single().available)
    }

    @Test
    fun `recurring offers for a subscription AUTO MANUAL and RECURRING_NOT_SUPPORTED`(): Unit = runBlocking {
        method()
        val sub = fx.product(price = 900).also { raw(it, "billingMode" to "SUBSCRIPTION", "periodUnit" to "MONTH", "periodCount" to 1) }
        val (_, caller) = user("Alex")

        // no recurring support: a one-off payment with a reminder (the default fallback)
        val manual = quote(line(sub), caller = caller).paymentMethods.single()

        assertTrue(manual.available)
        assertEquals("MANUAL", manual.recurring)

        // the fallback switched off
        config = base(manualFallback = false)

        val refused = quote(line(sub), caller = caller).paymentMethods.single()

        assertFalse(refused.available)
        assertEquals("RECURRING_NOT_SUPPORTED", refused.unavailableReason)
        assertNull(refused.recurring)

        // a gateway that bills monthly
        config = base()
        fake.caps = com.panomc.plugins.market.spi.payment.PaymentCapabilities().apply {
            recurring = RecurringSupport.GATEWAY_MANAGED
            recurringIntervals = setOf(IntervalUnit.MONTH)
        }

        assertEquals("AUTO", quote(line(sub), caller = caller).paymentMethods.single().recurring)

        // an interval it cannot do: manual again
        fake.caps = com.panomc.plugins.market.spi.payment.PaymentCapabilities().apply {
            recurring = RecurringSupport.GATEWAY_MANAGED
            recurringIntervals = setOf(IntervalUnit.YEAR)
        }

        assertEquals("MANUAL", quote(line(sub), caller = caller).paymentMethods.single().recurring)

        // a plain product has no recurring field
        assertNull(quote(line(fx.product()), caller = caller).paymentMethods.single().recurring)
    }

    @Test
    fun `checkEligibility can refuse a method downgrade a subscription and sees the snapshot`(): Unit = runBlocking {
        lookup = StaticProviderLookup(listOf(picky))
        method()
        val p = fx.product(price = 2500, name = "Rank")
        val (_, caller) = user("Alex")

        picky.verdict = Eligibility.ineligible("TOO_SMALL", LocalizedText.of("no"))

        val refused = quote(line(p), caller = caller).paymentMethods.single()

        assertFalse(refused.available)
        assertEquals("PROVIDER_INELIGIBLE", refused.unavailableReason)
        assertEquals("TOO_SMALL", refused.toJson().getString("providerCode"))

        val snapshot = picky.seen!!

        assertEquals("EUR", snapshot.order.currency)
        assertEquals(2500L, snapshot.order.total.amount)
        assertEquals("Rank", snapshot.order.lines.single().name)
        assertEquals(1, snapshot.order.lines.single().quantity)
        assertEquals(caller.userId, snapshot.buyer.userId)
        assertFalse(snapshot.buyer.guest)
        assertEquals("alex@example.com", snapshot.buyer.email)
        assertNull(snapshot.subscription)
        assertFalse(snapshot.hasPanoPriceModifiers)

        // a provider that throws is not offered
        picky.throwing = true

        assertEquals("PROVIDER_INELIGIBLE", quote(line(p), caller = caller).paymentMethods.single().unavailableReason)
        picky.throwing = false

        // a guest snapshot
        picky.verdict = Eligibility.eligible()
        quote(line(p))
        assertTrue(picky.seen!!.buyer.guest)
        assertTrue(picky.seen!!.buyer.numericId >= (1L shl 62))
        assertEquals("g:steve", picky.seen!!.buyer.stableId)
        assertEquals("steve@example.com", picky.seen!!.buyer.email)

        // subscriptions: oneOffOnly downgrades an AUTO offer to MANUAL, ineligible removes it
        val sub = fx.product(price = 900).also { raw(it, "billingMode" to "SUBSCRIPTION", "periodUnit" to "MONTH", "periodCount" to 1) }

        fake.caps = com.panomc.plugins.market.spi.payment.PaymentCapabilities().apply { recurring = RecurringSupport.GATEWAY_MANAGED }

        assertEquals("AUTO", quote(line(sub), caller = caller).paymentMethods.single().recurring)
        assertNotNull(picky.seen!!.subscription, "the plan is only passed for an AUTO offer")
        assertEquals(IntervalUnit.MONTH, picky.seen!!.subscription!!.intervalUnit)
        assertEquals(32, picky.seen!!.subscription!!.planKey.length)

        picky.verdict = Eligibility.oneOffOnly("NO_PLAN")
        assertEquals("MANUAL", quote(line(sub), caller = caller).paymentMethods.single().recurring)

        picky.verdict = Eligibility.ineligible("BAD", LocalizedText.of("no"))
        assertEquals("PROVIDER_INELIGIBLE", quote(line(sub), caller = caller).paymentMethods.single().unavailableReason)
    }

    @Test
    fun `the fee of a method is in the list and in the total only for the selected one`(): Unit = runBlocking {
        method(feeMode = PaymentFeeMode.BUYER, feePercent = 1000, feeFixed = 100)
        val p = fx.product(price = 10_000)

        val unselected = quote(line(p))

        assertEquals(10_100L, 10_100L)
        assertEquals(1100L, unselected.paymentMethods.single().feeAmount)
        assertEquals(0L, unselected.paymentFee)
        assertEquals(10_000L, unselected.total)

        val selected = quote(line(p), method = "fake")

        assertEquals(1100L, selected.paymentFee)
        assertEquals(11_100L, selected.total)
        assertEquals(11_100L, selected.gatewayAmount)
        assertTrue(selected.canCheckout)
        assertEquals(1100L, selected.paymentMethods.single().feeAmount)
    }

    @Test
    fun `mixed credit capability decides whether a method can take a credit part`(): Unit = runBlocking {
        method()
        config = base(mixed = true)
        val p = fx.product(price = 5000)
        val (_, caller) = user("Alex", credit = 1000)

        fake.caps = com.panomc.plugins.market.spi.payment.PaymentCapabilities().apply { mixedCredit = false }

        val q = quote(line(p), caller = caller, credits = UseCredits.Amount(500))

        assertEquals("MIXED_CREDIT_NOT_SUPPORTED", q.paymentMethods.single().unavailableReason)

        fake.caps = com.panomc.plugins.market.spi.payment.PaymentCapabilities().apply { mixedCredit = true }

        assertTrue(quote(line(p), caller = caller, credits = UseCredits.Amount(500)).paymentMethods.single().available)
    }

    @Test
    fun `a gateway that sets the price lists its pricing and the quote re-prices when it is selected`(): Unit = runBlocking {
        method()
        fx.discount(value = 1000)
        val p = fx.product(price = 10_000)

        fake.caps = com.panomc.plugins.market.spi.payment.PaymentCapabilities().apply { priceAuthority = PriceAuthority.GATEWAY_ADDS_TAX }

        assertEquals("EXTERNAL_TAX", quote(line(p)).paymentMethods.single().pricing)

        val external = quote(line(p), method = "fake")

        assertEquals("EXTERNAL_TAX", external.pricingMode)

        fake.caps = com.panomc.plugins.market.spi.payment.PaymentCapabilities().apply { priceAuthority = PriceAuthority.GATEWAY_CATALOG }

        val catalog = quote(line(p), method = "fake")

        assertEquals("EXTERNAL", catalog.pricingMode)
        assertNotNull(catalog.message("EXTERNAL_PRICING"))
        assertEquals(10_000L, catalog.lines.single().unitPrice, "Pano discounts do not apply")
    }

    @Test
    fun `a selected method that is unavailable makes the quote unusable and an unselected one does not`(): Unit = runBlocking {
        method(min = 90_000)
        val p = fx.product(price = 5000)

        val unselected = quote(line(p))

        assertTrue(unselected.canCheckout)
        assertEquals("AMOUNT_BELOW_MINIMUM", unselected.paymentMethods.single().unavailableReason)

        val selected = quote(line(p), method = "fake")

        assertFalse(selected.canCheckout)
        assertNotNull(selected.message("AMOUNT_BELOW_MINIMUM"))
        assertEquals(1, selected.messages.count { it.code == "AMOUNT_BELOW_MINIMUM" }, "reported once")
    }

    @Test
    fun `requiredBuyerFields is the union of the billing mode and the selected method`(): Unit = runBlocking {
        method()
        fake.caps = com.panomc.plugins.market.spi.payment.PaymentCapabilities().apply { requiredBuyerFields = setOf(BuyerField.PHONE, BuyerField.EMAIL) }
        val p = fx.product()

        assertTrue(quote(line(p)).requiredBuyerFields.isEmpty(), "a method that is not selected requires nothing yet")
        assertEquals(listOf("billingInfo.phone", "email").sorted(), quote(line(p), method = "fake").requiredBuyerFields.sorted())
        assertEquals(listOf("email", "billingInfo.phone"), quote(line(p), method = "fake").requiredBuyerFields)
        assertEquals(listOf("PHONE", "EMAIL").sorted(), quote(line(p)).paymentMethods.single().requiredBuyerFields.sorted())

        config = base(billing = BillingInfoMode.REQUIRED)

        val both = quote(line(p), method = "fake", billing = JsonObject().put("type", "COMPANY")).requiredBuyerFields

        assertTrue("billingInfo.taxNumber" in both)
        assertTrue("billingInfo.line1" in both)
        assertTrue("email" in both)
        assertEquals(both.size, both.toSet().size)
    }

    @Test
    fun `locale picks the label and the line prices follow the removeCents setting`(): Unit = runBlocking {
        method()
        val p = fx.product(price = 1049)

        assertEquals("Fake payment", quote(line(p), locale = "tr").paymentMethods.single().label)

        config = base(removeCents = true)

        assertEquals(1000L, quote(line(p)).lines.single().unitPrice)
    }
}
