package com.panomc.plugins.market.service

import com.panomc.platform.error.PageNotFound
import com.panomc.platform.model.Error
import com.panomc.plugins.market.config.MarketConfig
import com.panomc.plugins.market.core.cart.CartLine
import com.panomc.plugins.market.db.MarketDaoITBase
import com.panomc.plugins.market.db.model.CreditSystemKey
import com.panomc.plugins.market.db.model.CreditTxType
import com.panomc.plugins.market.db.model.MarketOrder
import com.panomc.plugins.market.db.model.MarketProduct
import com.panomc.plugins.market.db.model.MarketSubscription
import com.panomc.plugins.market.db.model.SubscriptionStatus
import com.panomc.plugins.market.db.model.OrderEventType
import com.panomc.platform.error.BadRequest
import com.panomc.plugins.market.error.InvalidCart
import com.panomc.platform.error.NotLoggedIn
import com.panomc.plugins.market.routes.user.credit.BuyerCreditViews
import com.panomc.plugins.market.support.TestUser
import com.panomc.plugins.market.support.TestWiring
import com.panomc.plugins.market.util.OrderStatus
import com.panomc.plugins.market.util.Paging
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Credit top-up on a real MariaDB (MK-092; 07 sections 8.1 to 8.4, 13, 14.1 and 11.3, 06 test 65, 17 V-15, 07 E-4 to E-6): a credit pack and the free amount are
 * credited at O2 as `TOPUP` (`GIFT` for a gift-code order) to the recipient, a pack is never payable with credits, the free amount is validated (below the
 * minimum, above the maximum, a guest, items present, a gift), the limits are served by `checkout/config`, test-mode orders post, the `onlyAcceptCredits` cart
 * classification of section 13, `creditsEnabled = false` after checkout, and the buyer's views (`me/credits`, `me/summary`). The real services run on the
 * real ledger through the production composition ([CreditEffects]); I1 to I22 and the credit self-check run after every test.
 */
class TopUpIT : MarketDaoITBase() {
    private lateinit var w: TestWiring
    private lateinit var c: CreditHarness
    private val vertx: Vertx = Vertx.vertx()

    @AfterAll
    fun closeVertx() {
        vertx.close()
    }

    @BeforeEach
    fun freshState() {
        runBlocking { resetState() }
        w = TestWiring(pool)
        c = CreditHarness(w, vertx)
    }

    override suspend fun assertInvariants() {
        super.assertInvariants()

        val result = c.reconciler().run(full = true)

        assertTrue(result.ok, "the credit reconciler found ${result.problems}")
    }

    private val fx get() = w.fixtures
    private val h get() = c.h

    // ------------------------------------------------------------------------------------------------------ helpers

    private suspend fun user(name: String = "Alex", credit: Long = 0): Pair<TestUser, QuoteCaller> {
        val u = fx.user(name)

        h.emails[u.id] = "$name@example.com"

        if (credit > 0) fx.credit(u, credit)

        return u to QuoteCaller(u.id)
    }

    private suspend fun configure(free: Boolean = false, min: Double = 5.0, max: Double = 100.0, mixed: Boolean = false, onlyCredits: Boolean = false) {
        h.config = h.config.copy(
            creditTopUpEnabled = true, creditTopUpFreeAmount = free, creditTopUpMin = min, creditTopUpMax = max, creditName = "Gems",
            allowMixedCreditPayment = mixed, onlyAcceptCredits = onlyCredits
        )

        fx.paymentMethod("fake")
    }

    private suspend fun pack(credits: Long = 50_000, price: Long = 500, slug: String = "pack-${System.nanoTime()}"): MarketProduct =
        fx.product(slug = slug, price = price, columns = mapOf("kind" to "CREDIT_PACK", "creditAmount" to credits))

    private suspend fun expect(code: String, status: Int, block: suspend () -> Any?): JsonObject {
        val e = try {
            block()

            null
        } catch (e: Error) {
            e
        } ?: error("expected $code, nothing was thrown")
        val body = JsonObject(e.encode())

        assertEquals(code, body.getString("error"), "error code of the wire body ${e.encode()}")
        assertEquals(status, e.getStatusCode())

        return body
    }

    private suspend fun fails(block: suspend () -> Any?): Throwable = try {
        block()

        error("expected a failure, nothing was thrown")
    } catch (e: IllegalStateException) {
        throw e
    } catch (e: Throwable) {
        e
    }

    /** A gateway checkout as [caller]; the order stays `PENDING` until [paid]. */
    private suspend fun gateway(caller: QuoteCaller, vararg extra: Pair<String, Any?>, items: List<Map<String, Any?>>? = null): MarketOrder {
        val pairs = ArrayList<Pair<String, Any?>>()

        if (items != null) pairs += "items" to items

        pairs += "paymentMethodId" to "fake"
        pairs += extra

        return c.orderOf(c.checkout(h.body(*pairs.toTypedArray()), caller))
    }

    private suspend fun paid(order: MarketOrder): MarketOrder {
        c.succeed(order.id, c.attempts(order.id).single())

        return c.order(order.id)
    }

    private suspend fun txs(orderId: Long) = c.ledger(orderId)

    private suspend fun itemsOf(orderId: Long) = w.orderItems.getByOrderIds(listOf(orderId), pool).sortedBy { it.id }

    private suspend fun balance(u: TestUser) = fx.creditBalance(u)

    /** O2 stamps `order.testMode` from the paying attempt (06 section 11), so a test-mode order is one whose attempt is. */
    private suspend fun markAttemptsTestMode(orderId: Long) {
        sql("UPDATE `pano_market_order` SET `testMode` = 1 WHERE `id` = ?", orderId)
        sql("UPDATE `pano_market_payment` SET `testMode` = 1 WHERE `orderId` = ?", orderId)
    }

    private suspend fun setOrderColumn(orderId: Long, column: String, value: Any?) {
        sql("UPDATE `pano_market_order` SET `$column` = ? WHERE `id` = ?", value, orderId)
    }

    // ================================================================================== packs (07 section 8.1, 8.3)

    @Test
    fun `D-O10 a pack bought twice is credited at O2 as one TOPUP per item with its creditAmount and a replay posts nothing`(): Unit = runBlocking {
        configure()

        val (alex, caller) = user()
        val product = pack(credits = 50_000)
        val order = gateway(caller, items = listOf(h.line(product, 2)))
        val item = itemsOf(order.id).single()

        assertEquals(100_000, item.creditAmount, "creditAmount = product.creditAmount x quantity")
        assertEquals(OrderStatus.PENDING, order.status)
        assertEquals(0, balance(alex), "nothing is credited before the payment")
        assertTrue(txs(order.id).isEmpty())

        val done = paid(order)

        assertEquals(OrderStatus.COMPLETED, done.status)

        val tx = txs(done.id).single()

        assertEquals(CreditTxType.TOPUP, tx.type)
        assertEquals("orderitem:${item.id}:topup", tx.idempotencyKey)
        assertEquals(100_000, tx.amount)
        assertEquals(alex.id, tx.userId)
        assertEquals(done.id, tx.orderId)
        assertEquals(100_000, balance(alex))
        assertEquals(-100_000, c.system(CreditSystemKey.ISSUANCE))
        assertEquals(0, c.system(CreditSystemKey.SPENT), "a top-up is not a spend")

        // the same payment event again, and the effect itself run again: nothing new is posted
        c.succeed(done.id, c.attempts(done.id).single())

        val again = c.ph.db.tx { conn -> c.credits.creditOrderItems(w.orders.getById(done.id, conn)!!, itemsOf(done.id), conn) { true } }

        assertTrue(again.posted.isNotEmpty() && again.posted.all { it.replayed }, "a replayed O2 finds every transaction under its key")
        assertEquals(1, txs(done.id).size)
        assertEquals(100_000, balance(alex))
    }

    @Test
    fun `the other effects of O2 still run and only the credit effects are taken by the ledger`(): Unit = runBlocking {
        configure()

        val (_, caller) = user()
        val done = paid(gateway(caller, items = listOf(h.line(pack(), 1))))
        val effects = c.ph.effects.of(done.id)

        assertTrue("IssueInvoice" in effects || "QueueMail" in effects, "the next effect handler still gets what the credit handler does not take: $effects")
        assertFalse("GrantCashback" in effects)
        assertFalse("CreditGrantingLines" in effects)
    }

    @Test
    fun `a pack is credited to the recipient of a gift and the payer gets nothing`(): Unit = runBlocking {
        configure()

        val (alex, caller) = user("Alex")
        val bob = fx.user("Bob")
        val order = gateway(caller, "recipientUsername" to "bob", items = listOf(h.line(pack(30_000), 1)))

        assertTrue(order.isGift)
        assertEquals(bob.id, order.recipientUserId)

        val done = paid(order)
        val tx = txs(done.id).single()

        assertEquals(CreditTxType.TOPUP, tx.type)
        assertEquals(bob.id, tx.userId)
        assertEquals(30_000, balance(bob))
        assertEquals(0, balance(alex))
    }

    @Test
    fun `a pack is not payable with credits and blocks any credit use on its order`(): Unit = runBlocking {
        configure(mixed = true)

        val (alex, caller) = user("Alex", credit = 100_000)
        val product = pack()
        val items = listOf(h.line(product, 1))

        // the whole order paid with credits (F4): the pack line is refused, the cart is invalid
        val refused = expect("INVALID_CART", 400) { c.checkout(h.body("items" to items, "paymentMethodId" to "credits", "payWithCredits" to true), caller) }

        assertTrue(refused.getJsonObject("lineErrors").map.values.single().toString().contains("NOT_PAYABLE_WITH_CREDITS"), "$refused")

        // the quote says so too: not payable in credits, and nothing can be applied (M4)
        val quote = c.service.quote(QuoteInput(items = listOf(CartLine(product.id, 0, 1)), paymentMethodId = "fake"), caller, pool)

        assertFalse(quote.credits!!.payableInCredits)
        assertEquals(0, quote.credits!!.maxApplicable)

        // a mixed payment has nothing to spend on a pack order (M4): refused, nothing held, no order
        assertEquals(
            "MIXED_CREDIT_NOT_SUPPORTED",
            expect("PAYMENT_METHOD_UNAVAILABLE", 400) { gateway(caller, "useCredits" to 5, items = items) }.getString("reason")
        )
        assertEquals(0, count("market_order"))
        assertEquals(100_000, balance(alex))

        val order = gateway(caller, items = items)

        assertEquals(0, order.creditAmount, "no credit part on a pack order")
        assertTrue(txs(order.id).isEmpty(), "nothing was held")

        // the order cannot be switched to credits afterwards either
        assertEquals(
            "NOT_PAYABLE_WITH_CREDITS",
            expect("PAYMENT_METHOD_UNAVAILABLE", 400) { c.payments.pay(order, PayRequest("credits", null, null), PayCaller(), pool) }.getString("reason")
        )
        assertEquals(100_000, balance(alex))
    }

    @Test
    fun `a pack is a line error for a guest and a gift to nobody is refused`(): Unit = runBlocking {
        configure()

        val (_, caller) = user("Alex")
        val product = pack()

        assertEquals(listOf("LOGIN_REQUIRED"), c.service.quote(QuoteInput(items = listOf(CartLine(product.id, 0, 1))), QuoteCaller(null), pool).lines.single().errors)
        assertTrue(fails { c.checkout(h.body("items" to listOf(h.line(product, 1)), "paymentMethodId" to "fake"), QuoteCaller(null)) } is NotLoggedIn)

        // a recipient without a Pano account cannot receive credits
        expect("INVALID_RECIPIENT", 400) { c.checkout(h.body("items" to listOf(h.line(product, 1)), "paymentMethodId" to "fake", "recipientUsername" to "Never_Joined"), caller) }
    }

    /** The platform deletes a user: the user row goes and the orders are anonymised (`userId = NULL`; `recipientUserId` is left alone, 01 section 13). */
    private suspend fun deleteAccount(u: TestUser) {
        w.users.remove(u.id)
        w.orders.anonymizeByUserId(u.id, pool)
    }

    /** Either only the top-up or the whole credit system is switched off. */
    private fun switchOff(topUpOnly: Boolean) {
        h.config = if (topUpOnly) h.config.copy(creditTopUpEnabled = false) else h.config.copy(creditsEnabled = false)
    }

    private suspend fun assertGoneNote(orderId: Long) {
        val notes = w.orderEvents.getByOrderId(orderId, pool).filter { it.type == OrderEventType.NOTE }

        assertEquals(listOf(CREDIT_RECIPIENT_GONE_NOTE), notes.map { it.message })
    }

    @Test
    fun `a buyer who is deleted before O2 gets nothing, the order is still completed and the timeline says why`(): Unit = runBlocking {
        configure()

        val (alex, caller) = user("Alex")
        val order = gateway(caller, items = listOf(h.line(pack(), 1)))

        assertEquals(alex.id, order.recipientUserId, "a self purchase stores the payer as the recipient, the anonymisation does not clear it")

        deleteAccount(alex)

        assertNull(w.orders.getById(order.id, pool)!!.userId)
        assertEquals(alex.id, w.orders.getById(order.id, pool)!!.recipientUserId)

        val done = paid(order)

        assertEquals(OrderStatus.COMPLETED, done.status, "the payment is valid, the order is not diverted")
        assertTrue(txs(done.id).isEmpty(), "nothing is posted to the account of a deleted user")
        assertEquals(0, balance(alex))
        assertGoneNote(done.id)
    }

    @Test
    fun `a gift recipient who is deleted before O2 gets nothing, the payer is not credited, the note is written and the order is completed`(): Unit = runBlocking {
        configure()

        val (alex, caller) = user("Alex")
        val (bob, _) = user("Bob")
        val order = gateway(caller, "recipientUsername" to "Bob", items = listOf(h.line(pack(), 1)))

        assertEquals(bob.id, order.recipientUserId)
        assertEquals(alex.id, order.userId)

        deleteAccount(bob)

        val done = paid(order)

        assertEquals(OrderStatus.COMPLETED, done.status)
        assertTrue(txs(done.id).isEmpty(), "no TOPUP for the deleted recipient and none for the payer")
        assertEquals(0, balance(alex), "the payer is not credited instead")
        assertEquals(0, balance(bob))
        assertGoneNote(done.id)
    }

    @Test
    fun `a recipient who is alive at O2 is credited, the existence check does not get in the way of a gift`(): Unit = runBlocking {
        configure()

        val (alex, caller) = user("Alex")
        val (bob, _) = user("Bob")
        val done = paid(gateway(caller, "recipientUsername" to "Bob", items = listOf(h.line(pack(30_000), 1))))

        assertEquals(30_000, balance(bob))
        assertEquals(0, balance(alex))
        assertEquals(listOf(bob.id), txs(done.id).map { it.userId })
    }

    // ================================================================================== packs need top-up (07 section 8 intro, 14.1)

    @Test
    fun `a pack with top-up off or with credits off is a line error, cannot be checked out and writes no order`(): Unit = runBlocking {
        configure()

        val (_, caller) = user("Alex")
        val product = pack()
        val input = QuoteInput(items = listOf(CartLine(product.id, 0, 1)))

        // control: on
        assertTrue(c.service.quote(input, caller, pool).canCheckout)

        for (label in listOf("top-up off", "credits off")) {
            switchOff(label == "top-up off")

            val quote = c.service.quote(input, caller, pool)

            assertEquals(listOf("PRODUCT_UNAVAILABLE"), quote.lines.single().errors, label)
            assertFalse(quote.canCheckout, label)

            val before = count("market_order")

            expect("INVALID_CART", 400) { c.checkout(h.body("items" to listOf(h.line(product, 1)), "paymentMethodId" to "fake"), caller) }
            assertEquals(before, count("market_order"), "$label: no order row")

            configure()
        }
    }

    @Test
    fun `listings omit packs while they cannot be bought, the page of a pack says PRODUCT_UNAVAILABLE and a standard product is unaffected`(): Unit = runBlocking {
        configure()

        val store = StoreQueryService(
            { h.config.toConfig() }, w.clock, w.categories, w.products, w.variants, w.prices, w.fields, w.bundleItems, w.discounts,
            w.currencyRates, w.comparisons, w.orderItems, w.entitlements
        )
        val standard = fx.product(slug = "plain-${System.nanoTime()}", price = 1000)
        val product = pack(slug = "listed-pack")
        val viewer = StoreViewer(userId = fx.user("Viewer").id)

        fun slugs(json: JsonObject) = json.getJsonArray("products").map { (it as JsonObject).getString("slug") }.toSet()

        assertTrue(slugs(store.products(ProductListQuery(), viewer, pool)).containsAll(listOf(product.slug, standard.slug)))
        assertTrue(store.product(product.slug, null, viewer, pool).getJsonObject("purchasable").getBoolean("ok"))

        for (topUpOnly in listOf(true, false)) {
            switchOff(topUpOnly)

            val listed = slugs(store.products(ProductListQuery(), viewer, pool))

            assertFalse(product.slug in listed)
            assertTrue(standard.slug in listed)
            assertFalse(product.slug in store.store(null, viewer, pool).getJsonArray("products").map { (it as JsonObject).getString("slug") })

            val purchasable = store.product(product.slug, null, viewer, pool).getJsonObject("purchasable")

            assertFalse(purchasable.getBoolean("ok"))
            assertEquals("PRODUCT_UNAVAILABLE", purchasable.getString("reason"))

            configure()
        }
    }

    @Test
    fun `an order of a gift code posts GIFT under the gift key`(): Unit = runBlocking {
        configure()

        val (alex, caller) = user("Alex")
        val order = gateway(caller, items = listOf(h.line(pack(20_000), 1)))
        val item = itemsOf(order.id).single()

        setOrderColumn(order.id, "source", "GIFT_CODE")

        val done = paid(order)
        val tx = txs(done.id).single()

        assertEquals(CreditTxType.GIFT, tx.type)
        assertEquals("orderitem:${item.id}:gift", tx.idempotencyKey)
        assertEquals(20_000, balance(alex))
    }

    @Test
    fun `credits turned off after checkout do not stop the credit of a paid pack`(): Unit = runBlocking {
        configure()

        val (alex, caller) = user("Alex")
        val order = gateway(caller, items = listOf(h.line(pack(40_000), 1)))

        h.config = h.config.copy(creditsEnabled = false, creditTopUpEnabled = false)

        paid(order)

        assertEquals(40_000, balance(alex), "the payment is valid whatever the storefront switches say meanwhile (07 section 14.1)")
    }

    @Test
    fun `test-mode orders credit the top-up, only the cashback excludes them`(): Unit = runBlocking {
        configure()
        h.config = h.config.copy(cashbackPercent = 10.0)

        val (alex, caller) = user("Alex")
        val product = fx.product(price = 10_000)
        val order = gateway(caller, items = listOf(h.line(pack(25_000), 1), h.line(product, 1)))

        markAttemptsTestMode(order.id)

        val done = paid(order)

        assertEquals(listOf(CreditTxType.TOPUP), txs(done.id).map { it.type }, "the pack is credited, no cashback for the product")
        assertEquals(25_000, balance(alex))
    }

    // ================================================================================== free amount (07 section 8.2)

    @Test
    fun `V-15 a paid free-amount top-up is one CREDIT_TOPUP line credited to the buyer, the server cart is untouched`(): Unit = runBlocking {
        configure(free = true)

        val (alex, caller) = user("Alex")
        val inCart = fx.product(price = 1000)

        h.cart.addItem(alex.id, CartLine(inCart.id, 0, 1, emptyMap(), null))

        val order = gateway(caller, "creditTopUp" to 12.5)
        val item = itemsOf(order.id).single()

        assertEquals("CREDIT_TOPUP", item.kind.name)
        assertEquals(1_250, item.creditAmount)
        assertEquals(1_250, order.totalPrice, "1 credit is worth 1.00")
        assertEquals(1, w.cartItems.getByCartId(w.carts.getByUserId(alex.id, pool)!!.id, pool).size, "the server cart is untouched")
        assertEquals(0, balance(alex))

        val done = paid(order)
        val tx = txs(done.id).single()

        assertEquals(CreditTxType.TOPUP, tx.type)
        assertEquals("orderitem:${item.id}:topup", tx.idempotencyKey)
        assertEquals(1_250, tx.amount)
        assertEquals(1_250, balance(alex))
        assertEquals(1, w.cartItems.getByCartId(w.carts.getByUserId(alex.id, pool)!!.id, pool).size, "still untouched after the payment")
    }

    @Test
    fun `V-15 a free amount below the minimum, above the maximum, a guest, a gift to nobody, with items and when off are refused before any row is written`(): Unit = runBlocking {
        configure(free = true, min = 5.0, max = 100.0)

        val (_, caller) = user("Alex")
        val low = expect("INVALID_CREDIT_AMOUNT", 400) { c.checkout(h.body("creditTopUp" to 1.5, "paymentMethodId" to "fake"), caller) }

        assertEquals("BELOW_MINIMUM", low.getString("reason"))
        assertEquals(5.0, low.getDouble("min"))
        assertEquals(100.0, low.getDouble("max"))

        val high = expect("INVALID_CREDIT_AMOUNT", 400) { c.checkout(h.body("creditTopUp" to 500, "paymentMethodId" to "fake"), caller) }

        assertEquals("ABOVE_MAXIMUM", high.getString("reason"))
        assertEquals(5.0, high.getDouble("min"))
        assertEquals(100.0, high.getDouble("max"))

        // the bounds themselves are fine
        assertNotNull(gateway(caller, "creditTopUp" to 5))
        assertNotNull(gateway(caller, "creditTopUp" to 100))

        assertTrue(fails { c.checkout(h.body("creditTopUp" to 12.5, "paymentMethodId" to "fake"), QuoteCaller(null)) } is NotLoggedIn, "a guest is 401")
        assertTrue(
            fails { c.checkout(h.body("creditTopUp" to 12.5, "items" to listOf(h.line(fx.product(), 1)), "paymentMethodId" to "fake"), caller) } is BadRequest,
            "items next to a top-up are 400 BAD_REQUEST"
        )
        expect("INVALID_RECIPIENT", 400) { c.checkout(h.body("creditTopUp" to 12.5, "recipientUsername" to "Never_Joined", "paymentMethodId" to "fake"), caller) }

        h.config = h.config.copy(creditTopUpFreeAmount = false)

        assertEquals("TOPUP_DISABLED", expect("INVALID_CREDIT_AMOUNT", 400) { c.checkout(h.body("creditTopUp" to 12.5, "paymentMethodId" to "fake"), caller) }.getString("reason"))

        h.config = h.config.copy(creditTopUpFreeAmount = true, creditsEnabled = false)

        assertEquals("TOPUP_DISABLED", expect("INVALID_CREDIT_AMOUNT", 400) { c.checkout(h.body("creditTopUp" to 12.5, "paymentMethodId" to "fake"), caller) }.getString("reason"))
        assertEquals(2, count("market_order"), "only the two bound checkouts wrote an order")
    }

    @Test
    fun `V-15 a gift top-up credits the recipient`(): Unit = runBlocking {
        configure(free = true)

        val (alex, caller) = user("Alex")
        val bob = fx.user("Bob")
        val order = gateway(caller, "creditTopUp" to 20, "recipientUsername" to "bob")

        assertTrue(order.isGift)

        val done = paid(order)
        val tx = txs(done.id).single()

        assertEquals(bob.id, tx.userId)
        assertEquals(2_000, balance(bob))
        assertEquals(0, balance(alex))
    }

    @Test
    fun `the free-amount quote reports the invalid amount as an error message and cannot be checked out`(): Unit = runBlocking {
        configure(free = true, min = 5.0, max = 100.0)

        val (_, caller) = user("Alex")
        val ok = c.service.quote(QuoteInput(creditTopUp = TopUpRequest(2_500)), caller, pool)

        assertTrue(ok.canCheckout)
        assertEquals("CREDIT_TOPUP", ok.lines.single().kind)

        for (bad in listOf(TopUpRequest(null), TopUpRequest(0), TopUpRequest(499), TopUpRequest(10_001))) {
            val quote = c.service.quote(QuoteInput(creditTopUp = bad), caller, pool)

            assertFalse(quote.canCheckout, "${bad.credits}")
            assertEquals("error", quote.messages.single { it.code == "INVALID_CREDIT_AMOUNT" }.level)
        }
    }

    // ================================================================================== discovery (07 section 8.4)

    @Test
    fun `the limits of a free amount are served by checkout config`(): Unit = runBlocking {
        configure(free = true, min = 5.0, max = 250.0)

        val legal = LegalTextService(w.db, w.clock, w.legalTexts, { "en-US" })
        val service = CheckoutConfigService({ h.config.toConfig() }, legal, w.shippingZones)
        val topUp = service.get(null, pool).getJsonObject("creditTopUp")

        assertEquals(true, topUp.getBoolean("enabled"))
        assertEquals(true, topUp.getBoolean("freeAmount"))
        assertEquals(5.0, topUp.getDouble("min"))
        assertEquals(250.0, topUp.getDouble("max"))
        assertEquals(1.0, topUp.getDouble("creditValue"))
        assertEquals("EUR", topUp.getString("currency"))

        h.config = h.config.copy(creditTopUpFreeAmount = false)

        assertEquals(false, service.get(null, pool).getJsonObject("creditTopUp").getBoolean("freeAmount"))

        h.config = h.config.copy(creditsEnabled = false)

        val off = service.get(null, pool).getJsonObject("creditTopUp")

        assertEquals(false, off.getBoolean("enabled"))
        assertEquals(false, off.getBoolean("freeAmount"))
    }

    // ================================================================================== onlyAcceptCredits (07 section 13)

    @Test
    fun `onlyAcceptCredits classifies the cart, a credit-granting cart is unaffected, a product cart is credits only, a combined cart is refused`(): Unit = runBlocking {
        configure(free = true, onlyCredits = true)

        val (alex, caller) = user("Alex", credit = 50_000)
        val product = fx.product(price = 1_000, creditPrice = 1_000)
        val creditPack = pack()
        val free = fx.product(price = 0)

        // a credit-granting cart (pack, or the free amount): the normal gateway checkout
        val packOrder = gateway(caller, items = listOf(h.line(creditPack, 1)))

        assertEquals("fake", packOrder.paymentMethodId)
        assertEquals(0, packOrder.creditAmount)

        val topUpOrder = gateway(caller, "creditTopUp" to 10)

        assertEquals("fake", topUpOrder.paymentMethodId)

        // a product cart with a money total: the credits option only, and the checkout still has to ask for it
        val quote = c.service.quote(QuoteInput(items = listOf(CartLine(product.id, 0, 1))), caller, pool)

        assertTrue(quote.paymentMethods.none { it.id == "fake" }, "a gateway is not offered")
        assertTrue(quote.canCheckout)
        assertEquals(
            "CREDITS_REQUIRED",
            expect("PAYMENT_METHOD_UNAVAILABLE", 400) { c.checkout(h.body("items" to listOf(h.line(product, 1)), "paymentMethodId" to "fake"), caller) }.getString("reason")
        )

        val spent = c.orderOf(c.spend(product, caller))

        assertEquals(OrderStatus.COMPLETED, spent.status)
        assertEquals(1_000, spent.creditAmount)
        assertEquals(49_000, balance(alex))

        // a product with a zero money total: the free provider, as always
        assertEquals("free", c.orderOf(c.checkout(h.body("items" to listOf(h.line(free, 1)), "paymentMethodId" to "free"), caller)).paymentMethodId)

        // a combined cart: line errors, nothing to check out
        val combined = c.service.quote(QuoteInput(items = listOf(CartLine(product.id, 0, 1), CartLine(creditPack.id, 0, 1))), caller, pool)

        assertFalse(combined.canCheckout)
        assertTrue(combined.lines.single { it.productId == creditPack.id }.errors.contains("CREDIT_PACK_SEPARATE_ORDER"), "the pack line is refused: ${combined.lines.map { it.errors }}")
        assertTrue(combined.lines.single { it.productId == product.id }.errors.isNotEmpty(), "the product line is refused too")
        assertTrue(fails { c.checkout(h.body("items" to listOf(h.line(product, 1), h.line(creditPack, 1)), "paymentMethodId" to "fake"), caller) } is InvalidCart)
    }

    @Test
    fun `onlyAcceptCredits makes a product without a credit price unpurchasable`(): Unit = runBlocking {
        configure(onlyCredits = true)

        val (_, caller) = user("Alex", credit = 50_000)
        val noCreditPrice = fx.product(price = 1_000, creditPrice = 0)
        val quote = c.service.quote(QuoteInput(items = listOf(CartLine(noCreditPrice.id, 0, 1))), caller, pool)

        assertFalse(quote.canCheckout)
        assertEquals(listOf("NOT_PAYABLE_WITH_CREDITS"), quote.lines.single().errors)
    }

    // ================================================================================== buyer views (07 section 11.3, 04 section 4)

    private fun views(config: () -> MarketConfig = { h.config.toConfig() }) = BuyerCreditViews(config, c.credits, "pano_")

    @Test
    fun `me-credits lists the signed ledger newest first, the note only of grant and revoke, the order public id, and pages`(): Unit = runBlocking {
        configure()

        val (alex, caller) = user("Alex")
        val order = paid(gateway(caller, items = listOf(h.line(pack(10_000), 1))))

        c.ph.db.tx { conn ->
            c.credits.grant(alex.id, 5_000, "panel:grant-0000000000001", 77L, "welcome bonus", conn)
            c.credits.revoke(alex.id, 2_000, "panel:revoke-000000000001", 77L, "chargeback of a friend", conn)
        }

        sql("UPDATE `pano_market_credit_tx` SET `note` = 'internal' WHERE `type` = 'TOPUP'")

        val view = views().credits(alex.id, Paging.Window(1, 10), pool)

        assertEquals(130.0, view.getDouble("balance"), 1e-9)
        assertEquals("Gems", view.getString("creditName"))
        assertEquals(3L, view.getLong("entryCount"))
        assertEquals(1L, view.getLong("totalPage"))

        val entries = view.getJsonArray("entries").map { it as JsonObject }

        assertEquals(listOf("REVOKE", "GRANT", "TOPUP"), entries.map { it.getString("type") }, "newest first")
        assertEquals(listOf(-20.0, 50.0, 100.0), entries.map { it.getDouble("amount") }, "signed from the account's side")
        assertEquals(listOf(130.0, 150.0, 100.0), entries.map { it.getDouble("balanceAfter") })
        assertEquals(listOf("chargeback of a friend", "welcome bonus", null), entries.map { it.getString("note") }, "the reason of a TOPUP is never sent")
        assertNull(entries[1].getString("orderPublicId"))
        assertEquals(order.publicId, entries[2].getString("orderPublicId"))

        // paging: pages of two, a page beyond the last is 404
        val page2 = views().credits(alex.id, Paging.Window(2, 2), pool)

        assertEquals(3L, page2.getLong("entryCount"))
        assertEquals(2L, page2.getLong("totalPage"))
        assertEquals(listOf("TOPUP"), page2.getJsonArray("entries").map { (it as JsonObject).getString("type") })
        assertThrows(PageNotFound::class.java) { runBlocking { views().credits(alex.id, Paging.Window(3, 2), pool) } }

        // another user's ledger is not mixed in, and an account that does not exist is an empty ledger (reading creates nothing)
        val bob = fx.user("Bob")
        val bobView = views().credits(bob.id, Paging.Window(1, 10), pool)

        assertEquals(0L, bobView.getLong("entryCount"))
        assertTrue(bobView.getJsonArray("entries").isEmpty)

        val ghost = views().credits(987_654L, Paging.Window(1, 10), pool)

        assertEquals(0.0, ghost.getDouble("balance"))
        assertEquals(0L, ghost.getLong("totalPage"))
        assertEquals(0, sql("SELECT `id` FROM `pano_market_credit_account` WHERE `userId` = 987654").size)
    }

    @Test
    fun `me-credits and me-summary work while credits are disabled`(): Unit = runBlocking {
        configure()

        val (alex, caller) = user("Alex")

        paid(gateway(caller, items = listOf(h.line(pack(10_000), 1))))

        h.config = h.config.copy(creditsEnabled = false)

        val view = views().credits(alex.id, Paging.Window(1, 10), pool)

        assertEquals(100.0, view.getDouble("balance"))
        assertEquals(1L, view.getLong("entryCount"))
        assertEquals(false, views().summary(alex.id, pool).getBoolean("creditsEnabled"))
        assertEquals(100.0, views().summary(alex.id, pool).getDouble("creditBalance"))
    }

    @Test
    fun `me-summary carries the balance, the credit name, the cart quantity, the subscription counts and the creator flag`(): Unit = runBlocking {
        configure()

        val (alex, _) = user("Alex", credit = 7_500)
        val bob = fx.user("Bob")
        val a = fx.product(price = 1000)
        val b = fx.product(price = 2000)

        h.cart.addItem(alex.id, CartLine(a.id, 0, 2, emptyMap(), null))
        h.cart.addItem(alex.id, CartLine(b.id, 0, 3, emptyMap(), null))
        h.cart.addItem(bob.id, CartLine(a.id, 0, 9, emptyMap(), null))

        val empty = views().summary(alex.id, pool)

        assertEquals(true, empty.getBoolean("creditsEnabled"))
        assertEquals(75.0, empty.getDouble("creditBalance"))
        assertEquals("Gems", empty.getString("creditName"))
        assertEquals(5L, empty.getLong("cartItemCount"), "the sum of the quantities of the caller's own cart")
        assertEquals(0L, empty.getLong("activeSubscriptionCount"))
        assertEquals(0L, empty.getLong("subscriptionCount"))
        assertEquals(false, empty.getBoolean("isCreator"))

        // subscriptions of every status are counted, the live ones separately; a creator code of the user (not deleted) sets the flag
        val product = fx.product(price = 500)
        val now = w.clock.now()

        for ((index, status) in listOf(SubscriptionStatus.ACTIVE, SubscriptionStatus.PAST_DUE, SubscriptionStatus.PAUSED, SubscriptionStatus.EXPIRED, SubscriptionStatus.CANCELLED, SubscriptionStatus.PENDING).withIndex()) {
            w.subscriptions.add(
                MarketSubscription(
                    userId = alex.id, playerUsername = "Alex", ownerKey = "u:${alex.id}:$index", productId = product.id, productName = "p", providerId = "fake",
                    status = status, currency = "EUR", createdAt = now, updatedAt = now
                ),
                pool
            )
        }

        sql("INSERT INTO `pano_market_creator_code` (`creator`, `code`, `discount`, `creatorUserId`, `deletedAt`, `createdAt`, `updatedAt`) VALUES ('Alex', 'GONE', 0, ?, ?, ?, ?)", alex.id, now, now, now)

        val partial = views().summary(alex.id, pool)

        assertEquals(3L, partial.getLong("activeSubscriptionCount"))
        assertEquals(6L, partial.getLong("subscriptionCount"))
        assertEquals(false, partial.getBoolean("isCreator"), "a deleted code does not make a creator")

        sql("INSERT INTO `pano_market_creator_code` (`creator`, `code`, `discount`, `creatorUserId`, `createdAt`, `updatedAt`) VALUES ('Alex', 'ALEX', 0, ?, ?, ?)", alex.id, now, now)

        assertEquals(true, views().summary(alex.id, pool).getBoolean("isCreator"))
        assertEquals(false, views().summary(bob.id, pool).getBoolean("isCreator"))
        assertEquals(9L, views().summary(bob.id, pool).getLong("cartItemCount"))
    }
}
