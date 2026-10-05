package com.panomc.plugins.market.service

import com.panomc.plugins.market.db.MarketDaoITBase
import com.panomc.plugins.market.db.model.CreditSystemKey
import com.panomc.plugins.market.db.model.CreditTxType
import com.panomc.plugins.market.db.model.MarketCreditTx
import com.panomc.plugins.market.db.model.MarketOrder
import com.panomc.plugins.market.db.model.MarketProduct
import com.panomc.plugins.market.support.TestUser
import com.panomc.plugins.market.support.TestWiring
import com.panomc.plugins.market.util.OrderStatus
import io.vertx.core.Vertx
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
 * Cashback on a real MariaDB (MK-092; 07 sections 9.1 to 9.3, 05 section 10, 17 CR-02, 07 D-O12 and the reversal half of D-O13 / D-O16): `CASHBACK` is posted once
 * at O2 / O4 to the payer from the gateway share of the eligible lines, floored, with the settings of the payment time; nothing is posted for a test-mode
 * order, a guest, a full-credit purchase, a credit pack, a manual order, an external-priced order or when credits are off. The reversal (`CASHBACK_REVERSAL`) is
 * proportional to a partial refund, complete on the full refund, idempotent per refund, `TAKE_AVAILABLE` for a refund and `ALLOW_DEBT` for a chargeback.
 * The real services run on the real ledger through the production composition ([CreditEffects]); I1 to I22 and the credit self-check run after every test.
 */
class CashbackIT : MarketDaoITBase() {
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
    private val admin = 77L

    // ------------------------------------------------------------------------------------------------------ helpers

    private suspend fun user(name: String = "Alex", credit: Long = 0): Pair<TestUser, QuoteCaller> {
        val u = fx.user(name)

        h.emails[u.id] = "$name@example.com"

        if (credit > 0) fx.credit(u, credit)

        return u to QuoteCaller(u.id)
    }

    private suspend fun configure(percent: Double = 10.0, mixed: Boolean = false) {
        h.config = h.config.copy(cashbackPercent = percent, allowMixedCreditPayment = mixed, creditTopUpEnabled = true)

        fx.paymentMethod("fake")
    }

    private suspend fun pack(credits: Long = 50_000, price: Long = 500): MarketProduct =
        fx.product(slug = "pack-${System.nanoTime()}", price = price, columns = mapOf("kind" to "CREDIT_PACK", "creditAmount" to credits))

    private suspend fun gateway(caller: QuoteCaller, vararg extra: Pair<String, Any?>, items: List<Map<String, Any?>>): MarketOrder =
        c.orderOf(c.checkout(h.body("items" to items, "paymentMethodId" to "fake", *extra), caller))

    private suspend fun paid(order: MarketOrder): MarketOrder {
        c.succeed(order.id, c.attempts(order.id).single())

        return c.order(order.id)
    }

    private suspend fun cashbacks(orderId: Long) = c.ledger(orderId).filter { it.type == CreditTxType.CASHBACK }

    private suspend fun reversals(orderId: Long) = c.ledger(orderId).filter { it.type == CreditTxType.CASHBACK_REVERSAL }

    private suspend fun balance(u: TestUser) = fx.creditBalance(u)

    /** O2 stamps `order.testMode` from the paying attempt (06 section 11), so a test-mode order is one whose attempt is. */
    private suspend fun markAttemptsTestMode(orderId: Long) {
        sql("UPDATE `pano_market_order` SET `testMode` = 1 WHERE `id` = ?", orderId)
        sql("UPDATE `pano_market_payment` SET `testMode` = 1 WHERE `orderId` = ?", orderId)
    }

    private suspend fun setOrderColumn(orderId: Long, column: String, value: Any?) {
        sql("UPDATE `pano_market_order` SET `$column` = ? WHERE `id` = ?", value, orderId)
    }

    /** `refund` / `dispute` of the ledger's reversal on the order's own transaction (the refund slices call exactly this inside their transition). */
    private suspend fun reverse(orderId: Long, refundId: Long? = null, disputeId: Long? = null, refundedTotalAfter: Long? = null) = c.ph.db.tx { conn ->
        val order = w.orders.getById(orderId, conn)!!

        c.credits.reverseCashback(order, refundId, disputeId, refundedTotalAfter ?: order.refundedTotal, conn)
    }

    // ================================================================================== posting (07 sections 9.1, 9.2)

    @Test
    fun `CR-02 and D-O12 cashback is posted once at O2 to the payer, floored from the gateway share, and a replay does not double it`(): Unit = runBlocking {
        configure(10.0)

        val (alex, caller) = user("Alex")
        val order = gateway(caller, items = listOf(h.line(fx.product(price = 10_000), 1)))

        assertTrue(cashbacks(order.id).isEmpty(), "nothing before the payment")

        val done = paid(order)
        val tx = cashbacks(done.id).single()

        assertEquals(OrderStatus.COMPLETED, done.status)
        assertEquals("order:${done.id}:cashback", tx.idempotencyKey)
        assertEquals(1_000, tx.amount, "10 % of 100.00 at 1 credit = 1.00")
        assertEquals(alex.id, tx.userId)
        assertEquals(done.id, tx.orderId)
        assertEquals(1_000, balance(alex))
        assertEquals(-1_000, c.system(CreditSystemKey.ISSUANCE))
        assertEquals(listOf(CreditTxType.CASHBACK), c.ledger(done.id).map { it.type })

        // the same payment event again, and the effect itself run again
        c.succeed(done.id, c.attempts(done.id).single())

        val again = c.ph.db.tx { conn ->
            c.credits.cashback(w.orders.getById(done.id, conn)!!, w.orderItems.getByOrderIds(listOf(done.id), conn), CreditEffects.cashbackSettings(h.config.toConfig()), conn)
        }

        assertTrue(again!!.replayed)
        assertEquals(1, cashbacks(done.id).size)
        assertEquals(1_000, balance(alex))
        assertFalse("GrantCashback" in c.ph.effects.of(done.id), "the credit handler takes the effect, the recorder never sees it")
    }

    @Test
    fun `cashback rounds down, one rounding for the whole formula`(): Unit = runBlocking {
        configure(10.0)

        val (alex, caller) = user("Alex")
        val a = paid(gateway(caller, items = listOf(h.line(fx.product(price = 999), 1))))

        assertEquals(99, cashbacks(a.id).single().amount, "10 % of 9.99 = 0.999 credits -> 0.99")

        configure(1.0)

        val b = paid(gateway(caller, items = listOf(h.line(fx.product(price = 150), 1))))

        assertEquals(1, cashbacks(b.id).single().amount, "1 % of 1.50 = 0.015 credits -> 0.01")

        configure(1.0)

        val tiny = paid(gateway(caller, items = listOf(h.line(fx.product(price = 99), 1))))

        assertTrue(cashbacks(tiny.id).isEmpty(), "0.0099 credits floors to nothing: no transaction")
        assertEquals(100, balance(alex))
    }

    @Test
    fun `only the gateway share earns, a mixed order earns on what the gateway collected`(): Unit = runBlocking {
        configure(10.0, mixed = true)

        val (alex, caller) = user("Alex", credit = 5_000)
        val order = c.orderOf(c.mixed(fx.product(price = 10_000, stock = 5), caller, useCredits = 30))

        assertEquals(3_000, order.creditAmount)

        val done = paid(order)

        assertEquals(OrderStatus.COMPLETED, done.status)
        assertEquals(700, cashbacks(done.id).single().amount, "10 % of the 70.00 the gateway collected")
        assertEquals(2_000 + 700, balance(alex))
    }

    @Test
    fun `a full-credit purchase earns nothing`(): Unit = runBlocking {
        configure(10.0)

        val (alex, caller) = user("Alex", credit = 20_000)
        val done = c.orderOf(c.spend(fx.product(price = 10_000, creditPrice = 5_000, stock = 5), caller))

        assertEquals(OrderStatus.COMPLETED, done.status)
        assertTrue(cashbacks(done.id).isEmpty())
        assertEquals(15_000, balance(alex))
    }

    @Test
    fun `a credit pack never earns, in a combined cart only the product earns`(): Unit = runBlocking {
        configure(10.0)

        val (alex, caller) = user("Alex")
        val onlyPack = paid(gateway(caller, items = listOf(h.line(pack(50_000, price = 5_000), 1))))

        assertTrue(cashbacks(onlyPack.id).isEmpty())
        assertEquals(50_000, balance(alex))

        val combined = paid(gateway(caller, items = listOf(h.line(pack(20_000, price = 5_000), 1), h.line(fx.product(price = 10_000), 1))))

        assertEquals(setOf(CreditTxType.TOPUP, CreditTxType.CASHBACK), c.ledger(combined.id).map { it.type }.toSet())
        assertEquals(1_000, cashbacks(combined.id).single().amount, "10 % of the 100.00 product, not of the 150.00 total")
        assertEquals(50_000 + 20_000 + 1_000, balance(alex))
    }

    @Test
    fun `a gift purchase earns the payer, not the recipient`(): Unit = runBlocking {
        configure(10.0)

        val (alex, caller) = user("Alex")
        val bob = fx.user("Bob")
        val done = paid(gateway(caller, "recipientUsername" to "bob", items = listOf(h.line(fx.product(price = 10_000), 1))))

        assertTrue(done.isGift)
        assertEquals(alex.id, cashbacks(done.id).single().userId)
        assertEquals(1_000, balance(alex))
        assertEquals(0, balance(bob))
    }

    @Test
    fun `nothing is posted for a test-mode order, a guest, a manual order, an external-priced order, credits off or a rate of zero`(): Unit = runBlocking {
        configure(10.0)

        val (alex, caller) = user("Alex")
        val product = fx.product(price = 10_000)

        // test mode
        val testMode = gateway(caller, items = listOf(h.line(product, 1)))

        markAttemptsTestMode(testMode.id)
        assertTrue(cashbacks(paid(testMode).id).isEmpty(), "test-mode orders are excluded from cashback")

        // a guest
        val guest = gateway(caller, items = listOf(h.line(product, 1)))

        setOrderColumn(guest.id, "userId", null)
        assertTrue(cashbacks(paid(guest).id).isEmpty())

        // the order sources that are not a purchase of the buyer: PANEL (manual) and GIFT_CODE
        for (source in listOf("PANEL", "GIFT_CODE")) {
            val order = gateway(caller, items = listOf(h.line(product, 1)))

            setOrderColumn(order.id, "source", source)
            assertTrue(cashbacks(paid(order).id).isEmpty(), source)
        }

        // an order priced by something else than the market
        val external = gateway(caller, items = listOf(h.line(product, 1)))

        setOrderColumn(external.id, "pricingMode", "EXTERNAL")
        assertTrue(cashbacks(paid(external).id).isEmpty())

        // credits off at payment time
        val creditsOff = gateway(caller, items = listOf(h.line(product, 1)))

        h.config = h.config.copy(creditsEnabled = false)
        assertTrue(cashbacks(paid(creditsOff).id).isEmpty())

        // a rate of zero at payment time
        h.config = h.config.copy(creditsEnabled = true, cashbackPercent = 0.0)

        assertTrue(cashbacks(paid(gateway(caller, items = listOf(h.line(product, 1)))).id).isEmpty())
        assertEquals(0, balance(alex))
        assertEquals(0, count("market_credit_tx", "`type` = 'CASHBACK'"))
    }

    @Test
    fun `a renewal order earns, the settings are those of the payment time`(): Unit = runBlocking {
        configure(10.0)

        val (alex, caller) = user("Alex")
        val order = gateway(caller, items = listOf(h.line(fx.product(price = 10_000), 1)))

        setOrderColumn(order.id, "source", "RENEWAL")

        // the rate changes between checkout and payment: the payment wins
        h.config = h.config.copy(cashbackPercent = 5.0)

        val done = paid(order)

        assertEquals(500, cashbacks(done.id).single().amount, "5 % at payment time, not the 10 % of checkout")
        assertEquals(500, balance(alex))
    }

    @Test
    fun `an accepted late payment (O4) earns the cashback once`(): Unit = runBlocking {
        configure(10.0)

        val (alex, caller) = user("Alex")
        val order = gateway(caller, items = listOf(h.line(fx.product(price = 10_000, stock = 5), 1)))
        val attempt = c.attempts(order.id).single()

        c.payments.cancel(order, pool)
        c.succeed(order.id, attempt)

        assertEquals(OrderStatus.REVIEW, c.order(order.id).status)
        assertTrue(cashbacks(order.id).isEmpty(), "a late payment pays nothing before the review")

        c.review.review(order.id, ReviewDecision.ACCEPT, refund = false, force = false, note = null, adminUserId = admin)

        assertEquals(OrderStatus.COMPLETED, c.order(order.id).status)
        assertEquals(1_000, cashbacks(order.id).single().amount)
        assertEquals(1_000, balance(alex))
    }

    // ================================================================================== reversal (07 section 9.3)

    private suspend fun cashbackOrder(percent: Double = 10.0, price: Long = 10_000): Triple<TestUser, MarketOrder, MarketCreditTx> {
        configure(percent)

        val (alex, caller) = user("Alex")
        val done = paid(gateway(caller, items = listOf(h.line(fx.product(price = price), 1))))

        return Triple(alex, done, cashbacks(done.id).single())
    }

    @Test
    fun `CR-02 a full refund reverses the whole cashback once and a replay of the same refund posts nothing`(): Unit = runBlocking {
        val (alex, order, earned) = cashbackOrder()

        assertEquals(1_000, earned.amount)

        val result = reverse(order.id, refundId = 41, refundedTotalAfter = order.totalPrice)!!

        assertFalse(result.replayed)
        assertEquals(CreditTxType.CASHBACK_REVERSAL, result.tx.type)
        assertEquals("refund:41:cashback", result.tx.idempotencyKey)
        assertEquals(1_000, result.tx.amount)
        assertEquals(0, result.tx.shortfall)
        assertEquals(41L, result.tx.refundId)
        assertEquals(order.id, result.tx.orderId)
        assertEquals(alex.id, result.tx.userId)
        assertEquals(0, balance(alex))
        assertEquals(1_000, c.system(CreditSystemKey.REVOKED))

        val replay = reverse(order.id, refundId = 41, refundedTotalAfter = order.totalPrice)

        assertTrue(replay == null || replay.replayed, "nothing is left to take for the same refund")
        assertEquals(1, reversals(order.id).size)
        assertEquals(0, balance(alex))
    }

    @Test
    fun `partial refunds reverse the refunded share and the sum never exceeds the cashback`(): Unit = runBlocking {
        val (alex, order, _) = cashbackOrder()
        val total = order.totalPrice

        val first = reverse(order.id, refundId = 1, refundedTotalAfter = total / 4)!!

        assertEquals(250, first.tx.amount, "floor(1000 x 25 %)")

        val second = reverse(order.id, refundId = 2, refundedTotalAfter = total / 2)!!

        assertEquals(250, second.tx.amount, "the share counted so far is 500, 250 are already taken")

        // the refund that completes the order takes the rest, whatever the floors left
        val last = reverse(order.id, refundId = 3, refundedTotalAfter = total)!!

        assertEquals(500, last.tx.amount)
        assertEquals(1_000, reversals(order.id).sumOf { it.amount + it.shortfall })
        assertEquals(0, balance(alex))

        // nothing left: a fourth refund (or a replay with another id) posts nothing
        assertNull(reverse(order.id, refundId = 4, refundedTotalAfter = total))
        assertEquals(3, reversals(order.id).size)
    }

    @Test
    fun `a refund takes what is available and records the shortfall of cashback already spent, no debt`(): Unit = runBlocking {
        val (alex, order, _) = cashbackOrder()

        // the buyer spends 6.00 of the 10.00 before the refund
        c.spend(fx.product(price = 600, creditPrice = 600, stock = 5), QuoteCaller(alex.id))

        assertEquals(400, balance(alex))

        val result = reverse(order.id, refundId = 7, refundedTotalAfter = order.totalPrice)!!

        assertEquals(400, result.tx.amount, "TAKE_AVAILABLE")
        assertEquals(600, result.tx.shortfall)
        assertEquals(0, balance(alex), "never negative through a refund")
    }

    @Test
    fun `a chargeback takes the whole cashback not reversed yet and may leave a debt, a second one posts nothing`(): Unit = runBlocking {
        val (alex, order, _) = cashbackOrder()

        c.spend(fx.product(price = 600, creditPrice = 600, stock = 5), QuoteCaller(alex.id))
        reverse(order.id, refundId = 9, refundedTotalAfter = order.totalPrice / 10)

        val partial = reversals(order.id).single()

        assertEquals(100, partial.amount)
        assertEquals(300, balance(alex))

        val result = reverse(order.id, disputeId = 5)!!

        assertEquals("dispute:5:cashback", result.tx.idempotencyKey)
        assertEquals(900, result.tx.amount, "everything not reversed yet, ALLOW_DEBT")
        assertEquals(0, result.tx.shortfall)
        assertEquals(-600, balance(alex), "a debt: top-up / spend / chargeback must not leave the goods and the money")
        assertEquals(1_000, reversals(order.id).sumOf { it.amount + it.shortfall })

        val again = reverse(order.id, disputeId = 5)

        assertTrue(again == null || again.replayed)
        assertEquals(2, reversals(order.id).size)
    }

    @Test
    fun `an order without a cashback has nothing to reverse and a reversal belongs to exactly one of refund and dispute`(): Unit = runBlocking {
        configure(0.0)

        val (_, caller) = user("Alex")
        val done = paid(gateway(caller, items = listOf(h.line(fx.product(price = 10_000), 1))))

        assertNull(reverse(done.id, refundId = 1, refundedTotalAfter = done.totalPrice))
        assertNull(reverse(done.id, disputeId = 1))
        assertThrows(IllegalArgumentException::class.java) { runBlocking { reverse(done.id, refundId = 1, disputeId = 1) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { reverse(done.id) } }
        assertNotNull(done.paidAt)
    }
}
