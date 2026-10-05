package com.panomc.plugins.market.service

import com.panomc.platform.error.NotLoggedIn
import com.panomc.platform.model.Error
import com.panomc.plugins.market.db.MarketDaoITBase
import com.panomc.plugins.market.db.model.CreditTxType
import com.panomc.plugins.market.db.model.MarketOrder
import com.panomc.plugins.market.db.model.MarketProduct
import com.panomc.plugins.market.db.model.OrderItemKind
import com.panomc.plugins.market.db.model.OrderSource
import com.panomc.plugins.market.db.model.PaymentStatus
import com.panomc.plugins.market.db.model.RedemptionKind
import com.panomc.plugins.market.db.model.RedemptionState
import com.panomc.plugins.market.db.model.ReservationState
import com.panomc.plugins.market.routes.user.gift.GiftCodeGuard
import com.panomc.plugins.market.routes.user.gift.GiftRedeemService
import com.panomc.plugins.market.routes.user.gift.parseRedeemRequest
import com.panomc.plugins.market.support.Race
import com.panomc.plugins.market.support.TestUser
import com.panomc.plugins.market.support.TestWiring
import com.panomc.plugins.market.util.GiftType
import com.panomc.plugins.market.util.OrderStatus
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
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Gift-code redemption on a real MariaDB (MK-113; 21 section 6, 07 section 10, 04 section 4 `POST /me/gifts/redeem`): the zero-total order of
 * `source = GIFT_CODE` that the free provider completes at O2, a credit gift's `CREDIT_TOPUP` item, every refusal reason of the endpoint with nothing
 * written, the per-customer and the global limit (two users on a limit-1 code: one order, R-20 twin), the random pick, the code guard seam and the
 * redemption lists. The global invariants (I1 to I22, I6 and I7 among them) are checked after every test by the base class.
 *
 * The orders run on the production composition of the credit slices ([CreditHarness]: the real ledger and `CreditEffects`), so the `GIFT` posting of O2
 * (07 section 10: key `orderitem:<itemId>:gift`, to the redeemer) is asserted on the ledger itself, for a credit gift and for a product gift whose product is
 * a credit pack. O2 runs in the transaction of the redemption (21 section 6 step 4): a failure in it leaves the code unused.
 */
class RedemptionServiceIT : MarketDaoITBase() {
    private lateinit var w: TestWiring
    private lateinit var c: CreditHarness
    private lateinit var ph: PaymentHarness
    private lateinit var redeemer: GiftRedeemService
    private lateinit var guard: ScriptedGuard
    private val vertx: Vertx = Vertx.vertx()

    @AfterAll
    fun closeVertx() {
        vertx.close()
    }

    @BeforeEach
    fun freshState() {
        runBlocking { resetState() }
        w = TestWiring(pool)
        // the production composition of the order transitions: the real credit ledger and CreditEffects (the GIFT posting of O2), the recorder only behind them
        c = CreditHarness(w, vertx)
        ph = c.ph
        guard = ScriptedGuard()

        val redemptions = RedemptionService(w.clock, ph.locks, w.redemptions)

        redeemer = GiftRedeemService(c.service, redemptions, { w.pool }, w.clock, { guard })
    }

    /** The credit ledger is self-consistent after every scenario, as in the credit tests (D-O19). */
    override suspend fun assertInvariants() {
        super.assertInvariants()

        val result = c.reconciler().run(full = true)

        assertTrue(result.ok, "the credit reconciler found ${result.problems}")
    }

    private val fx get() = w.fixtures
    private val h get() = ph.h

    private class ScriptedGuard : GiftCodeGuard {
        @Volatile
        var lockedUntil: Long? = null
        val unknown = CopyOnWriteArrayList<Pair<List<String>, String>>()
        val checked = CopyOnWriteArrayList<List<String>>()

        override suspend fun lockedUntil(subjects: List<String>): Long? {
            checked += subjects

            return lockedUntil
        }

        override suspend fun recordUnknown(subjects: List<String>, code: String) {
            unknown += subjects to code
        }
    }

    // ------------------------------------------------------------------------------------------------------ helpers

    private suspend fun user(name: String = "Alex"): Pair<TestUser, QuoteCaller> {
        val u = fx.user(name)

        h.emails[u.id] = "$name@example.com"

        return u to QuoteCaller(u.id, clientIp = "203.0.113.7")
    }

    private suspend fun redeem(code: String, caller: QuoteCaller, server: Long? = null, values: Map<String, Any?> = emptyMap()): CheckoutResult =
        redeemer.redeem(code, server, values, caller, "en-US")

    /** The error a redemption throws, its code and status checked; the test fails when nothing is thrown. */
    private suspend fun refused(code: String, status: Int, block: suspend () -> Any?): JsonObject {
        val e = try {
            block()

            null
        } catch (e: Error) {
            e
        } ?: error("expected $code, the redemption succeeded")

        assertEquals(code, e.getErrorCode(), "error code, body ${e.encode()}")
        assertEquals(status, e.getStatusCode())

        return JsonObject(e.encode())
    }

    private suspend fun invalid(reason: String, block: suspend () -> Any?) {
        val body = refused("INVALID_GIFT_CODE", 400, block)

        assertEquals(reason, body.getString("reason"), "reason, body $body")
    }

    private suspend fun orderOf(result: CheckoutResult): MarketOrder = w.orders.getByPublicId(result.order.getString("publicId"), pool)!!

    private suspend fun usedCount(giftId: Long): Int = sql("SELECT `usedCount` FROM `pano_market_gift` WHERE `id` = ?", giftId).single().getInteger("usedCount")

    private suspend fun stockOf(p: MarketProduct): Int? = sql("SELECT `stock` FROM `pano_market_product` WHERE `id` = ?", p.id).single().getInteger("stock")

    private suspend fun nothingWritten(giftId: Long) {
        assertEquals(0, usedCount(giftId), "the counter went back with the transaction")
        assertEquals(0, count("market_order"), "orders")
        assertEquals(0, count("market_order_item"), "items")
        assertEquals(0, count("market_payment"), "attempts")
        assertEquals(0, count("market_redemption"), "redemptions")
    }

    private suspend fun shirt(): MarketProduct = fx.product("shirt-${System.nanoTime()}", price = 2500, columns = mapOf("physical" to true, "weightGrams" to 300))

    // ======================================================================================== the happy paths

    @Test
    fun `a product gift is a zero-total GIFT_CODE order, completed by the free provider, with the redemption applied`(): Unit = runBlocking {
        val vip = fx.product("vip", price = 1000, stock = 5)
        val gift = fx.gift("WELCOME-VIP", GiftType.PRODUCT, productId = vip.id, redeemLimit = 3)
        val (alex, caller) = user("Alex")

        val result = redeem("welcome-vip", caller)
        val order = orderOf(result)

        assertEquals(order.publicId, result.order.getString("publicId"))
        assertEquals(OrderSource.GIFT_CODE, order.source)
        assertEquals(gift.id, order.giftId)
        assertEquals(OrderStatus.COMPLETED, order.status)
        assertEquals(ReservationState.COMMITTED, order.reservationState)
        assertEquals(alex.id, order.userId)
        assertEquals("Alex", order.recipientUsername)
        assertEquals(alex.id, order.recipientUserId)
        assertFalse(order.isGift, "redeeming for oneself is not a gift purchase")
        assertEquals(0, order.totalPrice)
        assertEquals(0, order.gatewayAmount)
        assertEquals(0, order.creditAmount)
        assertEquals(0, order.creditValue)
        assertEquals(0, order.vatTotal)
        assertEquals(1000, order.subtotal)
        assertEquals(1000, order.discountTotal)
        assertEquals("free", order.paymentMethodId)
        assertNotNull(order.paidAt)
        assertNull(order.idempotencyKey, "a redemption carries no idempotency key: the per-customer limit is the guard")

        val item = w.orderItems.getByOrderIds(listOf(order.id), pool).single()

        assertEquals(OrderItemKind.PRODUCT, item.kind)
        assertEquals(vip.id, item.productId)
        assertEquals(1, item.quantity)
        assertEquals(1000, item.listUnitPrice)
        assertEquals(0, item.lineTotal)
        assertEquals(0, item.vatAmount)

        val attempt = w.payments.getByOrderId(order.id, pool).single()

        assertEquals("free", attempt.providerId)
        assertEquals(PaymentStatus.SUCCEEDED, attempt.status)

        val redemption = w.redemptions.getByOrderId(order.id, pool).single()

        assertEquals(RedemptionKind.GIFT, redemption.kind)
        assertEquals(gift.id, redemption.refId)
        assertEquals("WELCOME-VIP", redemption.code)
        assertEquals(RedemptionState.APPLIED, redemption.state)
        assertEquals(1000, redemption.amount, "the discount a gift gives is the whole subtotal (05 section 12)")
        assertEquals("u:${alex.id}", redemption.buyerKey)

        assertEquals(1, usedCount(gift.id))
        assertEquals(4, stockOf(vip), "the unit left the stock")
        assertTrue("GrantEntitlements" in ph.effects.of(order.id), "O2 grants what the gift hands out: ${ph.effects.of(order.id)}")

        val created = sql("SELECT `data` FROM `pano_market_order_event` WHERE `orderId` = ? AND `type` = 'CREATED'", order.id).single().getString("data")

        assertEquals("GIFT_CODE", JsonObject(created).getString("source"), "the timeline names the real source")
    }

    @Test
    fun `a credit gift is one CREDIT_TOPUP item and O2 posts exactly one GIFT transaction to the redeemer, a replayed O2 posts nothing more`(): Unit = runBlocking {
        val gift = fx.gift("CREDITS-50", GiftType.CREDIT, creditAmount = 5000, redeemLimit = 2)
        val (alex, caller) = user("Alex")

        val order = orderOf(redeem("CREDITS-50", caller))
        val item = w.orderItems.getByOrderIds(listOf(order.id), pool).single()

        assertEquals(OrderSource.GIFT_CODE, order.source)
        assertEquals(gift.id, order.giftId)
        assertEquals(OrderStatus.COMPLETED, order.status)
        assertEquals(0, order.totalPrice)
        assertEquals(0, order.creditAmount, "nothing is spent: the credits are granted, not paid with")
        assertEquals("free", order.paymentMethodId)
        assertEquals(OrderItemKind.CREDIT_TOPUP, item.kind)
        assertNull(item.productId)
        assertEquals(5000, item.creditAmount)
        assertEquals(0, item.lineTotal)
        assertEquals(1, usedCount(gift.id))
        assertEquals(RedemptionState.APPLIED, w.redemptions.getByOrderId(order.id, pool).single().state)
        assertEquals(alex.id, order.recipientUserId)
        assertEquals(0, count("market_credit_tx", "`type` = 'HOLD'"), "no credit hold")

        assertGiftPosted(order, item.id, 5000, alex.id)

        // a replayed O2 (the effect run again on the paid order) finds the transaction under its key and writes nothing
        val items = w.orderItems.getByOrderIds(listOf(order.id), pool)

        ph.db.tx { conn -> c.credits.creditOrderItems(w.orders.getById(order.id, conn)!!, items, conn) { true } }

        assertGiftPosted(order, item.id, 5000, alex.id)
    }

    /** 07 section 10: one `GIFT` transaction of [amount] for the item, key `orderitem:<itemId>:gift`, and the redeemer's balance is exactly that amount (starting from 0). */
    private suspend fun assertGiftPosted(order: MarketOrder, itemId: Long, amount: Long, userId: Long) {
        val txs = c.ledger(order.id)
        val gifts = txs.filter { it.type == CreditTxType.GIFT }

        assertEquals(1, gifts.size, "exactly one GIFT transaction: ${txs.map { it.type to it.idempotencyKey }}")
        assertEquals("orderitem:$itemId:gift", gifts.single().idempotencyKey)
        assertEquals(amount, gifts.single().amount)
        assertEquals(userId, gifts.single().userId)
        assertEquals(1, txs.size, "nothing else was posted for the order: ${txs.map { it.type }}")
        assertEquals(amount, w.creditAccounts.getByUserId(userId, pool)!!.balance, "the balance of the redeemer rose by the gift")
    }

    @Test
    fun `a product gift whose product is a credit pack posts the GIFT of the pack to the redeemer`(): Unit = runBlocking {
        val pack = fx.product("pack-gift", price = 500, columns = mapOf("kind" to "CREDIT_PACK", "creditAmount" to 25000))
        val gift = fx.gift("PACK-GIFT", GiftType.PRODUCT, productId = pack.id, redeemLimit = 1)
        val (alex, caller) = user("Alex")

        val order = orderOf(redeem("PACK-GIFT", caller))
        val item = w.orderItems.getByOrderIds(listOf(order.id), pool).single()

        assertEquals(OrderStatus.COMPLETED, order.status)
        assertEquals(OrderSource.GIFT_CODE, order.source)
        assertEquals(0, order.totalPrice)
        assertEquals(25000, item.creditAmount)
        assertEquals(1, usedCount(gift.id))
        assertGiftPosted(order, item.id, 25000, alex.id)

        ph.db.tx { conn -> c.credits.creditOrderItems(w.orders.getById(order.id, conn)!!, listOf(item), conn) { true } }

        assertGiftPosted(order, item.id, 25000, alex.id)
    }

    @Test
    fun `a failure in O2 rolls the order, the redemption and the counter back, and the code can be redeemed right away`(): Unit = runBlocking {
        val gift = fx.gift("FAILS-ONCE", GiftType.CREDIT, creditAmount = 5000, redeemLimit = 1)
        val vip = fx.product("vip-fail", price = 1000, stock = 3)
        val product = fx.gift("FAILS-PRODUCT", GiftType.PRODUCT, productId = vip.id, redeemLimit = 1)
        val (alex, caller) = user("Alex")

        c.zeroCompletionFault = IllegalStateException("O2 failed")

        for (code in listOf("FAILS-ONCE", "FAILS-PRODUCT")) {
            assertThrows(IllegalStateException::class.java) { runBlocking { redeem(code, caller) } }
        }

        assertEquals(0, usedCount(gift.id) + usedCount(product.id), "the counters went back with the transaction")
        assertEquals(0, count("market_order"), "no pending order is left behind")
        assertEquals(0, count("market_redemption"), "no HELD redemption is left behind")
        assertEquals(0, count("market_payment"), "no attempt")
        assertEquals(0, count("market_credit_tx"), "nothing credited")
        assertEquals(3, stockOf(vip), "the stock reservation went back")

        c.zeroCompletionFault = null

        // the retry of the same account succeeds at once: a limit-1 code was not consumed by the failed attempt
        val credit = orderOf(redeem("FAILS-ONCE", caller))
        val item = w.orderItems.getByOrderIds(listOf(credit.id), pool).single()

        assertEquals(OrderStatus.COMPLETED, credit.status)
        assertEquals(1, usedCount(gift.id))
        assertGiftPosted(credit, item.id, 5000, alex.id)
        assertEquals(OrderStatus.COMPLETED, orderOf(redeem("FAILS-PRODUCT", caller)).status)
        assertEquals(1, usedCount(product.id))
        assertEquals(2, stockOf(vip))
    }

    @Test
    fun `a credit gift answers CREDITS_DISABLED while credits are off and is not consumed`(): Unit = runBlocking {
        val gift = fx.gift("CREDITS-OFF", GiftType.CREDIT, creditAmount = 5000)
        val (_, caller) = user()

        h.config = h.config.copy(creditsEnabled = false)

        invalid("CREDITS_DISABLED") { redeem("CREDITS-OFF", caller) }
        nothingWritten(gift.id)

        h.config = h.config.copy(creditsEnabled = true)

        redeem("CREDITS-OFF", caller)

        assertEquals(1, usedCount(gift.id), "the code was not used up by the refusal")
    }

    @Test
    fun `a bundle gift hands out the bundle and its children, all at 0`(): Unit = runBlocking {
        val a = fx.product("a", price = 500, stock = 3)
        val b = fx.product("b", price = 700, stock = 3)
        val bundle = fx.bundle(a to 1, b to 2, price = 1500)
        val gift = fx.gift("BUNDLE-GIFT", GiftType.PRODUCT, productId = bundle.id, redeemLimit = 1)
        val (_, caller) = user()

        val order = orderOf(redeem("BUNDLE-GIFT", caller))
        val items = w.orderItems.getByOrderIds(listOf(order.id), pool)

        assertEquals(3, items.size)
        assertTrue(items.all { it.lineTotal == 0L })
        assertEquals(0, order.totalPrice)
        assertEquals(1, usedCount(gift.id))
        assertEquals(2, stockOf(a), "child a: one unit")
        assertEquals(1, stockOf(b), "child b: two units")
    }

    @Test
    fun `a random gift hands out exactly one product of its pool`(): Unit = runBlocking {
        val pool3 = (1..3).map { fx.product("p$it", price = 100L * it, stock = 10) }
        val gift = fx.gift("RANDOM-BOX", GiftType.RANDOM, redeemLimit = null)

        com.panomc.plugins.market.support.Fixtures.setColumns(
            pool, "market_gift", gift.id, mapOf("productIds" to pool3.map { it.id }.joinToString(prefix = "[", postfix = "]"), "customerRedeemLimit" to null)
        )

        val seen = HashSet<Long>()

        for (i in 1..12) {
            val (_, caller) = user("Player$i")
            val order = orderOf(redeem("RANDOM-BOX", caller))
            val items = w.orderItems.getByOrderIds(listOf(order.id), pool)

            assertEquals(1, items.size, "one product per redemption")
            assertTrue(items.single().productId in pool3.map { it.id })

            seen += items.single().productId!!
        }

        assertEquals(12, usedCount(gift.id))
        assertTrue(seen.size >= 2, "twelve draws out of three products met at least two of them: $seen")
        assertEquals(30 - 12, pool3.sumOf { stockOf(it)!! })
    }

    // ============================================================================================ refusals

    @Test
    fun `a physical product is refused with PHYSICAL_NOT_SUPPORTED and nothing is written`(): Unit = runBlocking {
        val gift = fx.gift("SHIRT-GIFT", GiftType.PRODUCT, productId = shirt().id)
        val (_, caller) = user()

        invalid("PHYSICAL_NOT_SUPPORTED") { redeem("SHIRT-GIFT", caller) }
        nothingWritten(gift.id)
    }

    @Test
    fun `a bundle with a physical child is refused as well`(): Unit = runBlocking {
        val bundle = fx.bundle(fx.product("digital", price = 100) to 1, shirt() to 1)
        val gift = fx.gift("BUNDLE-SHIRT", GiftType.PRODUCT, productId = bundle.id)
        val (_, caller) = user()

        invalid("PHYSICAL_NOT_SUPPORTED") { redeem("BUNDLE-SHIRT", caller) }
        nothingWritten(gift.id)
    }

    @Test
    fun `an unknown, an inactive, a soft-deleted and a malformed code are all CODE_NOT_FOUND, counted by the guard`(): Unit = runBlocking {
        val vip = fx.product("vip", price = 1000)
        val inactive = fx.gift("INACTIVE-ONE", GiftType.PRODUCT, productId = vip.id)
        val deleted = fx.gift("DELETED-ONE", GiftType.PRODUCT, productId = vip.id)
        val (_, caller) = user()

        com.panomc.plugins.market.support.Fixtures.setColumns(pool, "market_gift", inactive.id, mapOf("status" to "INACTIVE"))
        com.panomc.plugins.market.support.Fixtures.setColumns(pool, "market_gift", deleted.id, mapOf("deletedAt" to 1L))

        for (code in listOf("NO-SUCH-CODE", "INACTIVE-ONE", "DELETED-ONE", "has space", "", "<script>")) {
            invalid("CODE_NOT_FOUND") { redeem(code, caller) }
        }

        // the inactive code is looked up (it exists), so the guard only counts the codes that are truly unknown
        assertEquals(listOf("NO-SUCH-CODE", "DELETED-ONE", "has space", "", "<script>"), guard.unknown.map { it.second })
        assertEquals(listOf("ip:203.0.113.7", "b:u:${caller.userId}"), guard.unknown.first().first)
        assertEquals(0, count("market_order"))
    }

    @Test
    fun `the window is judged, not started, expired, and the code works inside it`(): Unit = runBlocking {
        val vip = fx.product("vip", price = 1000)
        val now = w.clock.now()
        val future = fx.gift("FUTURE-ONE", GiftType.PRODUCT, productId = vip.id)
        val past = fx.gift("PAST-ONE", GiftType.PRODUCT, productId = vip.id)
        val open = fx.gift("OPEN-ONE", GiftType.PRODUCT, productId = vip.id)
        val (_, caller) = user()

        com.panomc.plugins.market.support.Fixtures.setColumns(pool, "market_gift", future.id, mapOf("startDate" to now + 60_000))
        com.panomc.plugins.market.support.Fixtures.setColumns(pool, "market_gift", past.id, mapOf("expiryDate" to now - 1))
        com.panomc.plugins.market.support.Fixtures.setColumns(pool, "market_gift", open.id, mapOf("startDate" to now - 1000, "expiryDate" to now + 1000))

        invalid("CODE_NOT_STARTED") { redeem("FUTURE-ONE", caller) }
        invalid("CODE_EXPIRED") { redeem("PAST-ONE", caller) }

        assertEquals(0, usedCount(future.id) + usedCount(past.id))
        assertEquals(OrderStatus.COMPLETED, orderOf(redeem("OPEN-ONE", caller)).status)
    }

    @Test
    fun `a required field and a buyer-chosen server are asked for before anything is written`(): Unit = runBlocking {
        val withField = fx.product("with-field", price = 1000)

        fx.field(withField, "nick", required = true)

        val actions = """[{"id":"a1","type":"COMMAND","phase":"GRANT","via":"SERVER","serverMode":"BUYER_CHOICE","commands":["give {player} diamond"]}]"""
        val withServer = fx.product("with-server", price = 1000, actions = actions)
        val fieldGift = fx.gift("FIELD-GIFT", GiftType.PRODUCT, productId = withField.id)
        val serverGift = fx.gift("SERVER-GIFT", GiftType.PRODUCT, productId = withServer.id)
        val (_, caller) = user()

        invalid("FIELD_REQUIRED") { redeem("FIELD-GIFT", caller) }
        invalid("SERVER_REQUIRED") { redeem("SERVER-GIFT", caller) }

        assertEquals(0, usedCount(fieldGift.id) + usedCount(serverGift.id))
        assertEquals(0, count("market_order"))

        val order = orderOf(redeem("FIELD-GIFT", caller, values = mapOf("nick" to "Steve_1")))

        assertEquals(OrderStatus.COMPLETED, order.status)
        assertEquals("Steve_1", JsonObject(w.orderItems.getByOrderIds(listOf(order.id), pool).single().fieldValues!!).getString("nick"))
    }

    @Test
    fun `a gift never oversells, out of stock is PRODUCT_UNAVAILABLE and the counter goes back`(): Unit = runBlocking {
        val last = fx.product("last-one", price = 1000, stock = 1)
        val soldOut = fx.product("sold-out", price = 1000, stock = 0)
        val inactive = fx.product("inactive", price = 1000, status = com.panomc.plugins.market.util.MarketStatus.INACTIVE)
        val lastGift = fx.gift("LAST-GIFT", GiftType.PRODUCT, productId = last.id, redeemLimit = null)
        val soldGift = fx.gift("SOLD-GIFT", GiftType.PRODUCT, productId = soldOut.id)
        val inactiveGift = fx.gift("INACTIVE-PRODUCT", GiftType.PRODUCT, productId = inactive.id)
        val (_, first) = user("First")
        val (_, second) = user("Second")

        invalid("PRODUCT_UNAVAILABLE") { redeem("SOLD-GIFT", first) }
        invalid("PRODUCT_UNAVAILABLE") { redeem("INACTIVE-PRODUCT", first) }
        assertEquals(0, usedCount(soldGift.id) + usedCount(inactiveGift.id))

        redeem("LAST-GIFT", first)
        invalid("PRODUCT_UNAVAILABLE") { redeem("LAST-GIFT", second) }

        assertEquals(1, usedCount(lastGift.id), "only the first redemption used the code")
        assertEquals(0, stockOf(last))
        assertEquals(1, count("market_order"))
    }

    @Test
    fun `a subscription product cannot be redeemed`(): Unit = runBlocking {
        val sub = fx.product("sub", price = 1000, columns = mapOf("billingMode" to "SUBSCRIPTION", "periodUnit" to "MONTH", "periodCount" to 1))
        val gift = fx.gift("SUB-GIFT", GiftType.PRODUCT, productId = sub.id)
        val (_, caller) = user()

        invalid("PRODUCT_UNAVAILABLE") { redeem("SUB-GIFT", caller) }
        nothingWritten(gift.id)
    }

    @Test
    fun `a guest cannot redeem and a blocked buyer is refused`(): Unit = runBlocking {
        val vip = fx.product("vip", price = 1000)
        val gift = fx.gift("NEEDS-LOGIN", GiftType.PRODUCT, productId = vip.id)
        val (_, caller) = user("Blocked")

        assertThrows(NotLoggedIn::class.java) { runBlocking { redeem("NEEDS-LOGIN", QuoteCaller.GUEST) } }

        h.blocked = { payer, _, _, _, _ -> payer == "Blocked" }

        refused("BUYER_BLOCKED", 403) { redeem("NEEDS-LOGIN", caller) }
        nothingWritten(gift.id)
    }

    // ============================================================================================== limits

    @Test
    fun `the per-customer limit stops a second redemption by the same account`(): Unit = runBlocking {
        val vip = fx.product("vip", price = 1000, stock = 10)
        val gift = fx.gift("ONE-EACH", GiftType.PRODUCT, productId = vip.id, redeemLimit = null)
        val (_, alice) = user("Alice")
        val (_, bob) = user("Bob")

        redeem("ONE-EACH", alice)
        invalid("CODE_LIMIT_REACHED") { redeem("ONE-EACH", alice) }
        redeem("ONE-EACH", bob)

        assertEquals(2, usedCount(gift.id))
        assertEquals(2, count("market_order"))
    }

    @Test
    fun `a limit of 2 per customer lets the account redeem twice, and NULL means unlimited`(): Unit = runBlocking {
        val vip = fx.product("vip", price = 1000, stock = 10)
        val twice = fx.gift("TWICE-ONE", GiftType.PRODUCT, productId = vip.id, redeemLimit = null)
        val unlimited = fx.gift("UNLIMITED-ONE", GiftType.PRODUCT, productId = vip.id, redeemLimit = null)
        val (_, alice) = user("Alice")

        com.panomc.plugins.market.support.Fixtures.setColumns(pool, "market_gift", twice.id, mapOf("customerRedeemLimit" to 2))
        com.panomc.plugins.market.support.Fixtures.setColumns(pool, "market_gift", unlimited.id, mapOf("customerRedeemLimit" to null))

        redeem("TWICE-ONE", alice)
        redeem("TWICE-ONE", alice)
        invalid("CODE_LIMIT_REACHED") { redeem("TWICE-ONE", alice) }

        for (i in 1..3) redeem("UNLIMITED-ONE", alice)

        assertEquals(2, usedCount(twice.id))
        assertEquals(3, usedCount(unlimited.id))
    }

    @Test
    fun `the global limit is exhausted by the redeemLimit`(): Unit = runBlocking {
        val vip = fx.product("vip", price = 1000, stock = 10)
        val gift = fx.gift("THREE-USES", GiftType.PRODUCT, productId = vip.id, redeemLimit = 3)
        val callers = (1..4).map { user("Player$it").second }

        callers.take(3).forEach { redeem("THREE-USES", it) }
        invalid("CODE_LIMIT_REACHED") { redeem("THREE-USES", callers[3]) }

        assertEquals(3, usedCount(gift.id))
    }

    @Test
    fun `R-20 twin - two users redeeming a limit-1 code at once make one order`(): Unit = runBlocking {
        repeat(Race.rounds) { round ->
            val vip = fx.product("vip-$round", price = 1000, stock = 10)
            val gift = fx.gift("RACE-ONE-$round", GiftType.PRODUCT, productId = vip.id, redeemLimit = 1)
            val callers = (1..2).map { user("Racer$round-$it").second }
            val results = Race.run(2) { i -> redeem(gift.code, callers[i]) }
            val ok = results.count { it.isSuccess }
            val failures = results.mapNotNull { it.exceptionOrNull() }

            assertEquals(1, ok, "round $round: ${results.map { it.exceptionOrNull() }}")
            assertEquals(1, failures.size)
            assertEquals("INVALID_GIFT_CODE", (failures.single() as Error).getErrorCode())
            assertEquals("CODE_LIMIT_REACHED", JsonObject((failures.single() as Error).encode()).getString("reason"))
            assertEquals(1, usedCount(gift.id))
            assertEquals(1, count("market_redemption", "`refId` = ? AND `kind` = 'GIFT'", gift.id))
            assertEquals(9, stockOf(vip), "the loser kept nothing")
        }
    }

    @Test
    fun `twenty accounts on a limit-5 code make exactly five orders`(): Unit = runBlocking {
        val vip = fx.product("vip", price = 1000, stock = 100)
        val gift = fx.gift("RACE-FIVE", GiftType.PRODUCT, productId = vip.id, redeemLimit = 5)
        val callers = (1..20).map { user("Many$it").second }
        val results = Race.run(20) { i -> redeem(gift.code, callers[i]) }

        assertEquals(5, results.count { it.isSuccess }, "${results.mapNotNull { it.exceptionOrNull() }.map { it.message }.distinct()}")
        assertTrue(results.mapNotNull { it.exceptionOrNull() }.all { it is Error && it.getErrorCode() == "INVALID_GIFT_CODE" })
        assertEquals(5, usedCount(gift.id))
        assertEquals(95, stockOf(vip))
    }

    @Test
    fun `one account redeeming a single-use-per-customer code in parallel gets one order`(): Unit = runBlocking {
        val vip = fx.product("vip", price = 1000, stock = 10)
        val gift = fx.gift("SAME-USER", GiftType.PRODUCT, productId = vip.id, redeemLimit = null)
        val (_, caller) = user("Solo")
        val results = Race.run(6) { redeem(gift.code, caller) }

        assertEquals(1, results.count { it.isSuccess }, "${results.mapNotNull { it.exceptionOrNull() }.map { it.message }}")
        assertEquals(1, usedCount(gift.id))
        assertEquals(9, stockOf(vip))
    }

    // ============================================================================================= the guard

    @Test
    fun `a locked subject is refused before the lookup, even with a valid code`(): Unit = runBlocking {
        val vip = fx.product("vip", price = 1000)
        val gift = fx.gift("VALID-BUT-LOCKED", GiftType.PRODUCT, productId = vip.id)
        val (_, caller) = user()

        guard.lockedUntil = w.clock.now() + 90_500

        val body = refused("CODE_ATTEMPTS_LOCKED", 429) { redeem("VALID-BUT-LOCKED", caller) }

        assertEquals(91, body.getInteger("retryAfter"), "the seconds to the end of the lock, rounded up")
        assertTrue(guard.unknown.isEmpty(), "a locked request is not counted again")
        nothingWritten(gift.id)

        guard.lockedUntil = null

        assertEquals(OrderStatus.COMPLETED, orderOf(redeem("VALID-BUT-LOCKED", caller)).status)
    }

    @Test
    fun `an anonymous request is judged under the anon subject and still needs a login`(): Unit = runBlocking {
        val vip = fx.product("vip", price = 1000)
        val gift = fx.gift("ANON-TRY-1", GiftType.PRODUCT, productId = vip.id)

        assertThrows(NotLoggedIn::class.java) { runBlocking { redeem("ANON-TRY-1", QuoteCaller(null)) } }
        assertEquals(listOf(listOf("anon")), guard.checked, "no trusted IP and no session: the single anon subject")
        nothingWritten(gift.id)
    }

    // ============================================================================================= the body

    @Test
    fun `the redeem body is parsed strictly`() {
        val ok = parseRedeemRequest(JsonObject("""{"code":"abc-123","targetServerId":4,"fieldValues":{"nick":"Steve","n":3,"b":true}}"""))

        assertEquals("abc-123", ok.code)
        assertEquals(4L, ok.targetServerId)
        assertEquals(setOf("nick", "n", "b"), ok.fieldValues.keys)

        for (bad in listOf(
            """{}""", """{"code":5}""", """{"code":"x","extra":1}""", """{"code":"x","targetServerId":"4"}""", """{"code":"x","targetServerId":0}""",
            """{"code":"x","fieldValues":"no"}""", """{"code":"x","fieldValues":{"a":{"b":1}}}"""
        )) {
            assertThrows(com.panomc.plugins.market.error.RequestValueException::class.java, { parseRedeemRequest(JsonObject(bad)) }, bad)
        }
    }

    // ===================================================================================== listings and rows

    @Test
    fun `the redemption list names the order's payer, the amount, the state and is newest first`(): Unit = runBlocking {
        val vip = fx.product("vip", price = 1000, stock = 10)
        val gift = fx.gift("LISTED-ONE", GiftType.PRODUCT, productId = vip.id, redeemLimit = 3)
        val redemptions = RedemptionService(w.clock, ph.locks, w.redemptions)

        for (name in listOf("Alice", "Bob", "Carol")) {
            redeem(gift.code, user(name).second)
            w.clock.advance(1000)
        }

        val page = redemptions.listFor(pool, RedemptionKind.GIFT, gift.id, 1, 2)

        assertEquals(3, page.total)
        assertEquals(listOf("Carol", "Bob"), page.rows.map { it.playerUsername })
        assertTrue(page.rows.all { it.state == RedemptionState.APPLIED && it.amount == 1000L && it.currency == "EUR" })
        assertEquals(listOf("Alice"), redemptions.listFor(pool, RedemptionKind.GIFT, gift.id, 2, 2).rows.map { it.playerUsername })
        assertTrue(redemptions.hasRedemptions(pool, RedemptionKind.GIFT, gift.id))
        assertFalse(redemptions.hasRedemptions(pool, RedemptionKind.COUPON, gift.id))
    }
}
