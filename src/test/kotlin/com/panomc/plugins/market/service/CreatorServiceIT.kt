package com.panomc.plugins.market.service

import com.panomc.platform.model.Error
import com.panomc.plugins.market.config.MarketConfig
import com.panomc.plugins.market.core.delivery.ActionParser
import com.panomc.plugins.market.core.delivery.DeliveryEvent
import com.panomc.plugins.market.core.delivery.ProductAction
import com.panomc.plugins.market.core.delivery.ResultStatus
import com.panomc.plugins.market.core.order.OrderEffect
import com.panomc.plugins.market.db.MarketDaoITBase
import com.panomc.plugins.market.db.model.CreatorEarningState
import com.panomc.plugins.market.db.model.CreatorPayoutMethod
import com.panomc.plugins.market.db.model.CreatorPayoutState
import com.panomc.plugins.market.db.model.CreditSystemKey
import com.panomc.plugins.market.db.model.CreditTxType
import com.panomc.plugins.market.db.model.DeliveryActionType
import com.panomc.plugins.market.db.model.DeliverySourceType
import com.panomc.plugins.market.db.model.DeliveryStatus
import com.panomc.plugins.market.db.model.MarketCreatorCode
import com.panomc.plugins.market.db.model.MarketOrder
import com.panomc.plugins.market.db.model.OrderSource
import com.panomc.plugins.market.db.tx.OrderLockScope
import com.panomc.plugins.market.db.tx.txRestartingOnOrderChange
import com.panomc.plugins.market.service.platform.DirectoryUser
import com.panomc.plugins.market.service.platform.UserDirectory
import com.panomc.plugins.market.support.Race
import com.panomc.plugins.market.support.MarketTestDb
import com.panomc.plugins.market.support.TestUser
import com.panomc.plugins.market.support.TestWiring
import com.panomc.plugins.market.util.OrderStatus
import io.vertx.core.Vertx
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
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
import kotlin.random.Random

/**
 * `CreatorService` on a real MariaDB (MK-114; 21 section 7, 07 section 12, 08 section 12, 17 I14, tests RD-D14 to RD-D17, V-11, R-26): the earning accrues at O2 as
 * `PENDING` for `creatorEarningHoldDays` (through the production [CreatorEffects]), is reversed in proportion at O10 and in full at O11 (the refund service and the
 * dispute effects call the same [CreatorReversal]), a payout is refused while the earning is pending and when a reversed paid earning made the balance negative,
 * a `CREDIT` payout needs an account and credits enabled, an `ACTION` payout plans `cp:<id>` rows and follows them, and the same payout request cannot pay twice
 * (same key, or two keys racing for the full amount). The creator views are read back with their documented shapes. I1 to I22 (I14 included) and the credit
 * self-check (P1: every `CREATOR_PAYOUT` tx belongs to a payout of that creator) run after every test.
 */
class CreatorServiceIT : MarketDaoITBase() {
    private lateinit var w: TestWiring
    private lateinit var r: RefundWorld
    private lateinit var ds: DeliveryService
    private lateinit var creators: CreatorService
    private lateinit var effects: CreatorEffects
    private val roster = FakeRoster()
    private val vertx: Vertx = Vertx.vertx()
    private val admin = 77L

    @AfterAll
    fun closeVertx() {
        vertx.close()
    }

    @BeforeEach
    fun wire() {
        w = TestWiring(pool)
        r = RefundWorld(w, vertx)
        config()

        val directory = object : UserDirectory {
            override suspend fun byUsername(username: String, sqlClient: SqlClient) = w.users.idOf(username)?.let { DirectoryUser(it, w.users.nameOf(it)!!) }

            override suspend fun usernameOf(userId: Long, sqlClient: SqlClient): String? = w.users.nameOf(userId) ?: if (userId == admin) "admin" else null

            override suspend fun emailOf(userId: Long, sqlClient: SqlClient): String? = null

            override suspend fun hasPermission(userId: Long, node: String) = false
        }
        // the payout settlement goes both ways: the delivery service tells the creator service, which plans through the delivery service
        val settlement = object : PayoutSettlement {
            override suspend fun lock(conn: SqlClient, payoutId: Long): CreatorPayoutState? = creators.lock(conn, payoutId)

            override suspend fun settle(conn: SqlClient, payoutId: Long) = creators.settle(conn, payoutId)
        }

        ds = DeliveryService(
            w.db, r.d.locks, w.clock, w.ids, { w.config }, w.orders, w.orderItems, w.orderEvents, w.deliveries, w.entitlements, w.creditAccounts, w.products, w.fields,
            roster, directory, FakePlayerAccounts(w.users), r.d.credits, r.d.permissionService, Random(7), payouts = settlement
        )
        creators = CreatorService(w.db, w.clock, { w.config }, w.creatorEarnings, w.creatorPayouts, r.d.credits, ds, directory, { MarketTestDb.TABLE_PREFIX }, { pool })
        effects = CreatorEffects({ creators }, w.orders, ForeignEffects { _, _, _ -> })
    }

    override suspend fun assertInvariants() {
        w.assertInvariants()

        val result = CreditReconciler(w.clock, MarketTestDb.TABLE_PREFIX, { pool }, recheckDelayMs = 0).run(full = true)

        assertTrue(result.ok, "the credit reconciler found ${result.problems}")
    }

    // ------------------------------------------------------------------------------------------------------ helpers

    private fun config(holdDays: Int = 14, creditValue: Double = 1.0, credits: Boolean = true) = w.configure {
        MarketConfig(
            currency = com.panomc.plugins.market.util.CurrencyType.EUR, vatPercent = 20.0, showVatInPrice = true, creditValue = creditValue, storeTimeZone = "UTC",
            creatorEarningHoldDays = holdDays, creditsEnabled = credits
        )
    }

    private val day = 86_400_000L

    private class Creator(val user: TestUser, val code: MarketCreatorCode)

    /** A creator with an account (`creatorUserId` set) and a code with the given commission (basis points). */
    private suspend fun creator(name: String = "Streamer", code: String = "STREAM", commission: Long = 1000, account: Boolean = true): Creator {
        val user = w.fixtures.user(name)
        val row = w.fixtures.creatorCode(code = code, creator = name, commissionPercent = commission)

        if (account) MarketTestDb.sql(pool, "UPDATE `${MarketTestDb.TABLE_PREFIX}market_creator_code` SET `creatorUserId` = ? WHERE `id` = ?", user.id, row.id)

        return Creator(user, row)
    }

    private var buyer = 0

    private suspend fun buyer(): TestUser = w.fixtures.user("Buyer${++buyer}")

    /** A paid order of [total] attributed to [creator]'s code; the order is not accrued yet. */
    private suspend fun order(creator: Creator, total: Long = 10_000, source: OrderSource = OrderSource.STOREFRONT, testMode: Boolean = false, attribute: Boolean = true): PaidOrder {
        val paid = r.place(buyer(), listOf(RefundLine(total)), testMode = testMode)

        if (attribute) MarketTestDb.sql(pool, "UPDATE `${MarketTestDb.TABLE_PREFIX}market_order` SET `creatorCodeId` = ?, `source` = ? WHERE `id` = ?", creator.code.id, source.name, paid.order.id)

        return paid
    }

    /** The `AccrueCreatorEarning` effect of O2 under the order lock, the way `OrderService.transition` runs it (the production [CreatorEffects]). */
    private suspend fun accrue(paid: PaidOrder) {
        w.db.txRestartingOnOrderChange { conn ->
            r.d.locks.forOrder(conn, paid.order.id, OrderLockScope.COMMIT) { locked -> effects.apply(conn, locked, OrderEffect.AccrueCreatorEarning) }
        }
    }

    private suspend fun earning(orderId: Long, code: Creator) = w.creatorEarnings.get(orderId, code.code.id, pool)

    private suspend fun codeColumns(code: Creator): Pair<Long, Long> {
        val row = MarketTestDb.sql(pool, "SELECT `earnings`, `paidOut` FROM `${MarketTestDb.TABLE_PREFIX}market_creator_code` WHERE `id` = ?", code.code.id).single()

        return row.getLong("earnings") to row.getLong("paidOut")
    }

    private suspend fun availableOf(code: Creator) = creators.balance(code.code.id, pool).available

    private suspend fun payout(code: Creator, amount: Long, method: CreatorPayoutMethod = CreatorPayoutMethod.MANUAL, key: String = r.key("payout"), note: String? = "paid by bank", actions: String? = null) =
        creators.requestPayout(code.code.id, CreatorPayoutInput(amount, method, actions, note), key, admin)

    private suspend fun expect(code: String, status: Int, block: suspend () -> Any?): JsonObject = r.expect(code, status, block)

    /** A creator whose one order earned [total] x commission and whose hold has passed. */
    private suspend fun available(creator: Creator, total: Long = 10_000): PaidOrder {
        val paid = order(creator, total)

        accrue(paid)
        w.clock.advance(15 * day)

        return paid
    }

    private fun command(id: String = "p1", server: Long = 7L) =
        ProductAction(id = id, type = DeliveryActionType.COMMAND, commands = listOf("pay {username} {payout.amount}"), targetServers = listOf(server))

    private fun actionsJson(vararg actions: ProductAction) = ActionParser.toJson(actions.toList()).encode()

    private suspend fun payoutRows(payoutId: Long) = MarketTestDb.sql(
        pool, "SELECT `id`, `status`, `idempotencyKey`, `orderId`, `sourceType` FROM `${MarketTestDb.TABLE_PREFIX}market_delivery` WHERE `sourceType` = 'CREATOR_PAYOUT' AND `sourceId` = ? ORDER BY `id`", payoutId
    )

    // ================================================================================== accrual (21 section 7.1)

    @Test
    fun `O2 accrues one PENDING earning for the hold days, the cached earnings follow, and a replay writes nothing (RD-D14, I14)`(): Unit = runBlocking {
        val streamer = creator()
        val paid = order(streamer, 10_000)

        accrue(paid)
        accrue(paid)

        val e = earning(paid.order.id, streamer)!!

        assertEquals(10_000, e.baseAmount, "excluding VAT, shipping and fee (the placed order carries no VAT)")
        assertEquals(1000, e.commissionPercent, "the commission of the code at payment time")
        assertEquals(1000, e.amount, "10 % of 100.00")
        assertEquals("EUR", e.currency)
        assertEquals(CreatorEarningState.PENDING, e.state)
        assertEquals(paid.order.paidAt!! + 14 * day, e.availableAt)
        assertEquals(0, e.reversedAmount)
        assertEquals(streamer.user.id, e.creatorUserId)
        assertEquals(1, w.creatorEarnings.getByCodeId(streamer.code.id, pool).size, "uq_order_code: a replay is harmless")
        assertEquals(1000L to 0L, codeColumns(streamer))

        val b = creators.balance(streamer.code.id, pool)

        assertEquals(1000, b.earned)
        assertEquals(1000, b.pending)
        assertEquals(0, b.available)
    }

    @Test
    fun `with no hold days the earning is AVAILABLE at once, the commission is the one of the code`(): Unit = runBlocking {
        config(holdDays = 0)

        val streamer = creator(commission = 2550)
        val paid = order(streamer, 10_000)

        accrue(paid)

        val e = earning(paid.order.id, streamer)!!

        assertEquals(CreatorEarningState.AVAILABLE, e.state)
        assertEquals(2550, e.commissionPercent)
        assertEquals(2550, e.amount)
        assertEquals(2550, availableOf(streamer))
    }

    @Test
    fun `renewals, manual, in-game and gift orders, test-mode orders, orders without a code and a zero commission earn nothing`(): Unit = runBlocking {
        val streamer = creator()
        val none = creator(name = "Nobody", code = "NOBODY", commission = 0)

        for (source in listOf(OrderSource.PANEL, OrderSource.INGAME, OrderSource.GIFT_CODE, OrderSource.RENEWAL, OrderSource.EXTERNAL)) {
            val paid = order(streamer, source = source)

            accrue(paid)

            assertNull(earning(paid.order.id, streamer), "a $source order earns nothing")
        }

        val test = order(streamer, testMode = true)
        val plain = order(streamer, attribute = false)
        val zero = order(none)

        accrue(test)
        accrue(plain)
        accrue(zero)

        assertNull(earning(test.order.id, streamer), "test mode")
        assertEquals(0, w.creatorEarnings.getByOrderId(plain.order.id, pool).size, "no creator code on the order")
        assertNull(earning(zero.order.id, none), "a zero commission writes no row")
        assertEquals(0L to 0L, codeColumns(streamer))
        assertEquals(0L to 0L, codeColumns(none))
    }

    @Test
    fun `an order paid entirely in credits earns on the money value of the credit lines (05 section 10)`(): Unit = runBlocking {
        val streamer = creator()
        val payer = buyer()

        // 80.00 credits at 1.0 per credit, paid on the credits method: base = halfUp(8000 x 100 / 100)
        val paid = r.place(payer, listOf(RefundLine(8_000)), creditValue = 8_000, credits = 8_000, provider = "credits")

        MarketTestDb.sql(pool, "UPDATE `${MarketTestDb.TABLE_PREFIX}market_order` SET `creatorCodeId` = ? WHERE `id` = ?", streamer.code.id, paid.order.id)
        accrue(paid)

        val e = earning(paid.order.id, streamer)!!

        assertEquals(8_000, e.baseAmount)
        assertEquals(800, e.amount)
    }

    @Test
    fun `an order priced by the checkout earns on lineTotal minus VAT, and the amount is pctQ of it (the real order rows)`(): Unit = runBlocking {
        val streamer = creator(commission = 1250)
        val payer = buyer()
        val paid = r.place(payer, listOf(RefundLine(12_345)), shipping = 500)

        // a VAT-inclusive line of 123.45: 20 % VAT is 20.58 (rounded), the earning is taken from 102.87
        MarketTestDb.sql(pool, "UPDATE `${MarketTestDb.TABLE_PREFIX}market_order_item` SET `vatPercent` = 2000, `vatAmount` = 2058 WHERE `orderId` = ?", paid.order.id)
        MarketTestDb.sql(pool, "UPDATE `${MarketTestDb.TABLE_PREFIX}market_order` SET `creatorCodeId` = ? WHERE `id` = ?", streamer.code.id, paid.order.id)
        accrue(paid)

        val e = earning(paid.order.id, streamer)!!

        assertEquals(10_287, e.baseAmount, "12345 - 2058, shipping is not part of it")
        assertEquals(1286, e.amount, "12.5 % of 102.87 = 12.85875, half up to the cent")
    }

    // ================================================================================== payout while pending (V-11, RD-D14)

    @Test
    fun `a payout is refused while the earning is PENDING and allowed once the hold has passed, without any background job (RD-D14)`(): Unit = runBlocking {
        val streamer = creator()
        val paid = order(streamer)

        accrue(paid)

        val body = expect("INVALID_PAYOUT_AMOUNT", 400) { payout(streamer, 100) }

        assertEquals(0.0, body.getDouble("available"))

        w.clock.advance(14 * day - 1)

        expect("INVALID_PAYOUT_AMOUNT", 400) { payout(streamer, 100) }
        assertEquals(CreatorEarningState.PENDING, earning(paid.order.id, streamer)!!.state)

        w.clock.advance(2)

        val done = payout(streamer, 1000)

        assertEquals(CreatorPayoutState.PAID, done.payout.state)
        assertEquals(CreatorEarningState.PAID, earning(paid.order.id, streamer)!!.state, "the covered row is marked paid")
        assertEquals(done.payout.id, earning(paid.order.id, streamer)!!.payoutId)
        assertEquals(1000L to 1000L, codeColumns(streamer))
        assertEquals(0, availableOf(streamer))
    }

    @Test
    fun `the amount must be positive and at most what is available, and nothing is written when it is refused`(): Unit = runBlocking {
        val streamer = creator()

        available(streamer)

        for (bad in listOf(0L, -5L, 1001L)) {
            val body = expect("INVALID_PAYOUT_AMOUNT", 400) { payout(streamer, bad) }

            assertEquals(10.0, body.getDouble("available"), "available $bad")
        }

        assertTrue(w.creatorPayouts.getByCodeId(streamer.code.id, pool).isEmpty())
        assertEquals(1000L to 0L, codeColumns(streamer))
    }

    @Test
    fun `a partial payout leaves the row AVAILABLE and the next one completes it, rows are marked paid oldest first and never split`(): Unit = runBlocking {
        val streamer = creator()
        val a = available(streamer, 10_000)
        val b = order(streamer, 20_000)

        accrue(b)
        w.clock.advance(15 * day)

        // 10.00 + 20.00 available; 4.00 covers nothing completely, 10.00 covers the first row
        payout(streamer, 400)

        assertEquals(CreatorEarningState.AVAILABLE, earning(a.order.id, streamer)!!.state, "not covered completely: stays AVAILABLE")

        payout(streamer, 600)

        assertEquals(CreatorEarningState.PAID, earning(a.order.id, streamer)!!.state, "what the first payout paid of it counts")
        assertEquals(CreatorEarningState.AVAILABLE, earning(b.order.id, streamer)!!.state)
        assertEquals(1000L + 2000L to 1000L, codeColumns(streamer))
        assertEquals(2000, availableOf(streamer))
    }

    // ================================================================================== reversal (O10, O11; 21 section 7.3)

    @Test
    fun `O10 reverses the earning in proportion to a partial refund and in full when the order is refunded (RF-08 twin)`(): Unit = runBlocking {
        val streamer = creator()
        val paid = order(streamer, 10_000)

        accrue(paid)

        r.service.request(paid.order.id, RefundInput(amount = 3_000), r.key(), null)

        var e = earning(paid.order.id, streamer)!!

        assertEquals(300, e.reversedAmount, "floor(1000 x 3000 / 10000)")
        assertEquals(CreatorEarningState.PENDING, e.state, "a partly reversed row keeps its state")
        assertEquals(700L to 0L, codeColumns(streamer))
        assertEquals(700, creators.balance(streamer.code.id, pool).pending)

        r.service.request(paid.order.id, RefundInput(amount = 3_333), r.key(), null)

        e = earning(paid.order.id, streamer)!!

        assertEquals(floorShare(1000, 6_333, 10_000), e.reversedAmount, "cumulative, never twice")

        r.service.request(paid.order.id, RefundInput(amount = 3_667), r.key(), null)

        e = earning(paid.order.id, streamer)!!

        assertEquals(1000, e.reversedAmount)
        assertEquals(CreatorEarningState.REVERSED, e.state)
        assertEquals(0L to 0L, codeColumns(streamer))
        assertEquals(1000, creators.balance(streamer.code.id, pool).reversed)
    }

    private fun floorShare(amount: Long, part: Long, total: Long) = amount * part / total

    @Test
    fun `O11 reverses the whole earning at once, a second reversal changes nothing, and an AVAILABLE row ends REVERSED`(): Unit = runBlocking {
        val streamer = creator()
        val paid = available(streamer)

        // what StandardDisputeEffects does inside O11: the refund effects' reversal with fully = true
        suspend fun chargeback() = w.db.txRestartingOnOrderChange { conn ->
            r.d.locks.forOrder(conn, paid.order.id, OrderLockScope.RELEASE) { locked -> r.effects.creatorReversal(conn, locked.order, locked.order.totalPrice, true) }
        }

        chargeback()
        chargeback()

        val e = earning(paid.order.id, streamer)!!

        assertEquals(1000, e.reversedAmount)
        assertEquals(CreatorEarningState.REVERSED, e.state)
        assertEquals(0L to 0L, codeColumns(streamer))
        assertEquals(0, availableOf(streamer))
    }

    @Test
    fun `V-11 and RD-D15 reversing a PAID earning keeps it PAID, makes available negative and blocks payouts until new earnings cover it`(): Unit = runBlocking {
        val streamer = creator()
        val first = available(streamer, 10_000)
        val paidOut = payout(streamer, 1000)

        assertEquals(CreatorEarningState.PAID, earning(first.order.id, streamer)!!.state)

        // the order is charged back after the creator was paid
        w.db.txRestartingOnOrderChange { conn ->
            r.d.locks.forOrder(conn, first.order.id, OrderLockScope.RELEASE) { locked -> r.effects.creatorReversal(conn, locked.order, locked.order.totalPrice, true) }
        }

        val e = earning(first.order.id, streamer)!!

        assertEquals(CreatorEarningState.PAID, e.state, "a paid row stays PAID")
        assertEquals(1000, e.reversedAmount)
        assertEquals(-1000, availableOf(streamer), "nothing is clawed back automatically, the balance shows it")
        assertEquals(CreatorPayoutState.PAID, w.creatorPayouts.getById(paidOut.payout.id, pool)!!.state)

        val refused = expect("INVALID_PAYOUT_AMOUNT", 400) { payout(streamer, 1) }

        assertEquals(-10.0, refused.getDouble("available"), "also when available is negative")

        // new earnings: 5.00 of them only bring the balance back to -5.00
        val second = order(streamer, 5_000)

        accrue(second)
        w.clock.advance(15 * day)

        assertEquals(-500, availableOf(streamer))
        expect("INVALID_PAYOUT_AMOUNT", 400) { payout(streamer, 1) }

        val third = order(streamer, 30_000)

        accrue(third)
        w.clock.advance(15 * day)

        assertEquals(2500, availableOf(streamer), "(5.00 + 30.00) earned since, 10.00 already paid out and reversed")
        expect("INVALID_PAYOUT_AMOUNT", 400) { payout(streamer, 2501) }
        assertEquals(CreatorPayoutState.PAID, payout(streamer, 2500).payout.state)
        assertEquals(0, availableOf(streamer))
    }

    // ================================================================================== CREDIT payout (07 section 12)

    @Test
    fun `a CREDIT payout needs an account and credits enabled, a creator without one is refused and nothing is written`(): Unit = runBlocking {
        val nobody = creator(name = "Anon", code = "ANON", account = false)

        available(nobody)

        expect("CREATOR_HAS_NO_ACCOUNT", 400) { payout(nobody, 500, CreatorPayoutMethod.CREDIT) }

        val streamer = creator()

        available(streamer)
        config(credits = false)

        expect("CREDITS_DISABLED", 409) { payout(streamer, 500, CreatorPayoutMethod.CREDIT) }

        assertTrue(w.creatorPayouts.getByCodeId(nobody.code.id, pool).isEmpty())
        assertTrue(w.creatorPayouts.getByCodeId(streamer.code.id, pool).isEmpty())
        assertEquals(0L, codeColumns(streamer).second)
        assertEquals(0L, codeColumns(nobody).second)
    }

    @Test
    fun `a CREDIT payout posts CREATOR_PAYOUT payout-id once at the rate of the payout time, is PAID at once and cannot be cancelled`(): Unit = runBlocking {
        val streamer = creator()
        val paid = available(streamer)

        // 1 credit = 2.00: 10.00 of earnings are 5.00 credits; 3.33 are 1.665 credits, half up 1.67
        config(creditValue = 2.0)

        val done = payout(streamer, 333, CreatorPayoutMethod.CREDIT, note = null)
        val row = w.creatorPayouts.getById(done.payout.id, pool)!!

        assertEquals(CreatorPayoutState.PAID, row.state)
        assertEquals(CreatorPayoutMethod.CREDIT, row.method)
        assertEquals(streamer.user.id, row.creatorUserId)
        assertEquals(admin, row.paidBy)
        assertNotNull(row.paidAt)

        val tx = w.creditTxs.getById(row.creditTxId!!, pool)!!

        assertEquals(CreditTxType.CREATOR_PAYOUT, tx.type)
        assertEquals("payout:${row.id}", tx.idempotencyKey)
        assertEquals(167, tx.amount)
        assertEquals(streamer.user.id, tx.userId)
        assertEquals(admin, tx.actorUserId)
        assertEquals(167, w.fixtures.creditBalance(streamer.user))
        assertEquals(-167, w.creditAccounts.getBySystemKey(CreditSystemKey.ISSUANCE, pool)!!.balance)
        assertEquals(333L, codeColumns(streamer).second)
        assertEquals(CreatorEarningState.AVAILABLE, earning(paid.order.id, streamer)!!.state, "a row that is not covered completely stays AVAILABLE")

        expect("INVALID_STATE", 409) { creators.cancelPayout(row.id) }
        assertEquals(CreatorPayoutState.PAID, w.creatorPayouts.getById(row.id, pool)!!.state)
        assertEquals(167, w.fixtures.creditBalance(streamer.user))
    }

    // ================================================================================== MANUAL payout and the idempotency rules

    @Test
    fun `a MANUAL payout needs a note and is PAID at once, the activity of the payout is on the row`(): Unit = runBlocking {
        val streamer = creator()

        available(streamer)

        val error = org.junit.jupiter.api.Assertions.assertThrows(com.panomc.plugins.market.error.RequestValueException::class.java) { runBlocking { payout(streamer, 500, note = " ") } }

        assertEquals("note", error.field)
        assertEquals("REQUIRED", error.reason)
        assertEquals(0, w.creatorPayouts.getByCodeId(streamer.code.id, pool).size)

        val done = payout(streamer, 500, note = "bank transfer 17")

        assertEquals(CreatorPayoutState.PAID, done.payout.state)
        assertEquals("bank transfer 17", done.payout.note)
        assertEquals("EUR", done.payout.currency)
        assertEquals(admin, done.payout.paidBy)
        assertNull(done.payout.creditTxId)
        assertEquals("STREAM", done.creatorCode)
    }

    @Test
    fun `RD-D16 one Idempotency-Key is one payout, a replay answers the stored row, another body is IDEMPOTENCY_CONFLICT, concurrent calls pay once`(): Unit = runBlocking {
        val streamer = creator()

        available(streamer)

        val first = payout(streamer, 400, key = "same-key-0123456789")
        val replay = payout(streamer, 400, key = "same-key-0123456789")

        assertFalse(first.replay)
        assertTrue(replay.replay)
        assertEquals(first.payout.id, replay.payout.id)
        assertEquals(400L, codeColumns(streamer).second, "paid once")
        expect("IDEMPOTENCY_CONFLICT", 409) { payout(streamer, 401, key = "same-key-0123456789") }
        expect("IDEMPOTENCY_CONFLICT", 409) { payout(streamer, 400, CreatorPayoutMethod.MANUAL, "same-key-0123456789", note = "another note") }

        val other = creator(name = "Other", code = "OTHER")

        available(other)
        expect("IDEMPOTENCY_CONFLICT", 409) { creators.requestPayout(other.code.id, CreatorPayoutInput(400, CreatorPayoutMethod.MANUAL, null, "paid by bank"), "same-key-0123456789", admin) }

        // the same request, eight callers at once
        val results = Race.run(8) { payout(streamer, 100, key = "race-key-0123456789ab") }
        val ok = results.mapNotNull { it.getOrNull() }

        assertEquals(8, ok.size, "every caller gets the one payout: ${results.map { it.exceptionOrNull() }}")
        assertEquals(1, ok.map { it.payout.id }.toSet().size)
        assertEquals(1, ok.count { !it.replay })
        assertEquals(500L, codeColumns(streamer).second)
        assertEquals(2, w.creatorPayouts.getByCodeId(streamer.code.id, pool).size)
    }

    @Test
    fun `R-26 the full available amount requested twice with different keys at once is one success and one INVALID_PAYOUT_AMOUNT (5 rounds)`(): Unit = runBlocking {
        repeat(Race.rounds) { round ->
            val streamer = creator(name = "Racer$round", code = "RACE$round")

            available(streamer)

            val results = Race.run(2) { i -> payout(streamer, 1000, CreatorPayoutMethod.MANUAL, "race-$round-key-$i-0123456789") }
            val failures = results.mapNotNull { it.exceptionOrNull() }

            assertEquals(1, results.count { it.isSuccess }, "round $round: ${failures.map { it.message }}")
            assertEquals(1, failures.size)
            assertEquals("INVALID_PAYOUT_AMOUNT", (failures.single() as Error).getErrorCode())

            val (earned, paidOut) = codeColumns(streamer)

            assertEquals(1000L, paidOut)
            assertTrue(paidOut <= earned, "paidOut never exceeds the earnings")
            assertEquals(1, w.creatorPayouts.getByCodeId(streamer.code.id, pool).size)
        }
    }

    @Test
    fun `a payout racing the reversal of the same earning, the creator is never paid more than the balance said at the time`(): Unit = runBlocking {
        repeat(Race.rounds) { round ->
            val streamer = creator(name = "Mixed$round", code = "MIX$round")
            val paid = available(streamer)

            val results = Race.run(2) { i ->
                if (i == 0) {
                    payout(streamer, 1000, key = "mix-$round-0123456789abcd").payout.id
                } else {
                    w.db.txRestartingOnOrderChange { conn ->
                        r.d.locks.forOrder(conn, paid.order.id, OrderLockScope.RELEASE) { locked -> r.effects.creatorReversal(conn, locked.order, locked.order.totalPrice, true) }
                    }

                    0L
                }
            }

            assertTrue(results.all { it.isSuccess || (it.exceptionOrNull() as? Error)?.getErrorCode() == "INVALID_PAYOUT_AMOUNT" }, "round $round: ${results.map { it.exceptionOrNull() }}")

            val e = earning(paid.order.id, streamer)!!
            val (earned, paidOut) = codeColumns(streamer)

            assertEquals(1000, e.reversedAmount)
            assertEquals(0, earned)
            // either the payout went first (the row is PAID and the balance negative) or the reversal did (nothing was payable)
            assertEquals(if (paidOut == 1000L) CreatorEarningState.PAID else CreatorEarningState.REVERSED, e.state)
            assertEquals(-paidOut, availableOf(streamer))
        }
    }

    // ================================================================================== ACTION payout (08 section 12)

    @Test
    fun `an ACTION payout plans cp-payout-id rows without an order, stays PENDING and is PAID when its rows are confirmed (R-26 twin for ACTION)`(): Unit = runBlocking {
        roster.granted = listOf(7L, 8L)

        val streamer = creator()

        available(streamer)

        val done = payout(streamer, 600, CreatorPayoutMethod.ACTION, note = null, actions = actionsJson(command("p1", 7), command("p2", 8)))
        val id = done.payout.id
        val rows = payoutRows(id)

        assertEquals(CreatorPayoutState.PENDING, done.payout.state)
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.getString("idempotencyKey").startsWith("cp:$id:") }, rows.map { it.getString("idempotencyKey") }.toString())
        assertTrue(rows.all { it.getLong("orderId") == null && it.getString("sourceType") == DeliverySourceType.CREATOR_PAYOUT.name })
        assertTrue(w.creatorPayouts.getById(id, pool)!!.actions!!.contains("pay {username} {payout.amount}"))
        assertEquals(600L, codeColumns(streamer).second, "the money is reserved while the actions run")

        val first = rows[0].getLong("id")
        val second = rows[1].getLong("id")

        send(first)
        confirm(first)

        assertEquals(CreatorPayoutState.PENDING, w.creatorPayouts.getById(id, pool)!!.state, "one row is still open")

        send(second)
        confirm(second)

        val paid = w.creatorPayouts.getById(id, pool)!!

        assertEquals(CreatorPayoutState.PAID, paid.state)
        assertNotNull(paid.paidAt)
        assertEquals(admin, paid.paidBy)
        assertEquals(DeliveryStatus.CONFIRMED, w.deliveries.getById(first, pool)!!.status)

        val body = w.deliveries.getById(first, pool)!!.payload

        assertTrue("Streamer" in body && "6" in body, "the command is rendered for the creator with the payout amount: $body")
    }

    private suspend fun send(deliveryId: Long) {
        w.db.tx { conn ->
            ds.apply(conn, deliveryId, DeliveryEvent.Promote)
            ds.apply(conn, deliveryId, DeliveryEvent.Offer)
        }
    }

    private suspend fun confirm(deliveryId: Long, status: ResultStatus = ResultStatus.DONE, code: String? = null) {
        w.db.tx { conn -> ds.apply(conn, deliveryId, DeliveryEvent.ServerResult(status, code)) }
    }

    @Test
    fun `an ACTION payout is FAILED when none of its rows is open and one failed, and cancelling it (nothing was delivered) gives the money back`(): Unit = runBlocking {
        roster.granted = listOf(7L, 8L)

        val streamer = creator()
        val paid = available(streamer)
        val done = payout(streamer, 1000, CreatorPayoutMethod.ACTION, note = null, actions = actionsJson(command("p1", 7), command("p2", 8)))
        val rows = payoutRows(done.payout.id).map { it.getLong("id") }

        assertEquals(CreatorEarningState.PAID, earning(paid.order.id, streamer)!!.state)

        send(rows[0])
        confirm(rows[0], ResultStatus.FAILED, "COMMAND_FAILED")
        send(rows[1])
        confirm(rows[1], ResultStatus.FAILED, "COMMAND_FAILED")

        assertEquals(CreatorPayoutState.FAILED, w.creatorPayouts.getById(done.payout.id, pool)!!.state)
        assertEquals(1000L, codeColumns(streamer).second)

        val cancelled = creators.cancelPayout(done.payout.id)

        assertEquals(CreatorPayoutState.CANCELLED, cancelled.payout.state)
        assertEquals(0L, codeColumns(streamer).second)
        assertEquals(CreatorEarningState.AVAILABLE, earning(paid.order.id, streamer)!!.state)
        assertNull(earning(paid.order.id, streamer)!!.payoutId)
        assertEquals(1000, availableOf(streamer))
        expect("INVALID_STATE", 409) { creators.cancelPayout(done.payout.id) }

        // the freed balance can be paid again
        assertEquals(CreatorPayoutState.PAID, payout(streamer, 1000).payout.state)
    }

    @Test
    fun `cancelling a PENDING ACTION payout cancels its open rows, one that was sent blocks it, and a manual payout cannot be cancelled`(): Unit = runBlocking {
        roster.granted = listOf(7L)

        val streamer = creator()

        available(streamer)

        val done = payout(streamer, 400, CreatorPayoutMethod.ACTION, note = null, actions = actionsJson(command("p1", 7)))
        val row = payoutRows(done.payout.id).single().getLong("id")

        send(row)
        expect("INVALID_STATE", 409) { creators.cancelPayout(done.payout.id) }
        assertEquals(CreatorPayoutState.PENDING, w.creatorPayouts.getById(done.payout.id, pool)!!.state)
        assertEquals(400L, codeColumns(streamer).second, "a sent command may still run: the payout stays")

        // a payout whose command was never sent
        val unsent = payout(streamer, 300, CreatorPayoutMethod.ACTION, note = null, actions = actionsJson(command("p1", 7)))
        val unsentRow = payoutRows(unsent.payout.id).single().getLong("id")

        assertEquals(CreatorPayoutState.CANCELLED, creators.cancelPayout(unsent.payout.id).payout.state)
        assertEquals(DeliveryStatus.CANCELLED, w.deliveries.getById(unsentRow, pool)!!.status)
        assertEquals(400L, codeColumns(streamer).second)

        val manual = payout(streamer, 100)

        expect("INVALID_STATE", 409) { creators.cancelPayout(manual.payout.id) }
        expect("NOT_FOUND", 404) { creators.cancelPayout(987_654) }
    }

    private suspend fun retry(deliveryId: Long) = w.db.tx { conn -> ds.apply(conn, deliveryId, DeliveryEvent.Retry) }

    private suspend fun payoutState(payoutId: Long) = w.creatorPayouts.getById(payoutId, pool)!!.state

    @Test
    fun `a FAILED payout follows its row when the admin retries it, so the confirmed retry is PAID and cannot be cancelled and paid again - review, retry`(): Unit = runBlocking {
        roster.granted = listOf(7L)

        val streamer = creator()

        available(streamer)

        val done = payout(streamer, 400, CreatorPayoutMethod.ACTION, note = null, actions = actionsJson(command("p1", 7)))
        val row = payoutRows(done.payout.id).single().getLong("id")

        send(row)
        confirm(row, ResultStatus.FAILED, "COMMAND_FAILED")

        assertEquals(CreatorPayoutState.FAILED, payoutState(done.payout.id))

        val retried = retry(row)

        assertTrue(retried.moved, "COMMAND_ERROR is retryable")
        assertEquals(CreatorPayoutState.PENDING, payoutState(done.payout.id), "the retried row is open again, the payout follows it")

        send(row)
        confirm(row)

        val paid = w.creatorPayouts.getById(done.payout.id, pool)!!

        assertEquals(CreatorPayoutState.PAID, paid.state)
        assertNotNull(paid.paidAt)
        expect("INVALID_STATE", 409) { creators.cancelPayout(done.payout.id) }
        assertEquals(400L, codeColumns(streamer).second, "the delivered amount stays paid out")
    }

    @Test
    fun `a late DONE after FAILED (ONLINE_WAIT_EXPIRED) settles the FAILED payout as PAID, and until then it cannot be cancelled because the command may have run - review, late result`(): Unit = runBlocking {
        roster.granted = listOf(7L)

        val streamer = creator()

        available(streamer)

        val done = payout(streamer, 400, CreatorPayoutMethod.ACTION, note = null, actions = actionsJson(command("p1", 7)))
        val row = payoutRows(done.payout.id).single().getLong("id")

        send(row)
        confirm(row, ResultStatus.EXPIRED)

        assertEquals(CreatorPayoutState.FAILED, payoutState(done.payout.id))
        assertEquals("ONLINE_WAIT_EXPIRED", w.deliveries.getById(row, pool)!!.lastErrorCode)

        val refused = expect("INVALID_STATE", 409) { creators.cancelPayout(done.payout.id) }

        assertEquals(DeliveryStatus.FAILED, w.deliveries.getById(row, pool)!!.status)
        assertEquals(400L, codeColumns(streamer).second, "the money stays reserved ($refused)")

        confirm(row)

        assertEquals(DeliveryStatus.CONFIRMED, w.deliveries.getById(row, pool)!!.status)
        assertEquals(CreatorPayoutState.PAID, payoutState(done.payout.id))
        assertNotNull(w.creatorPayouts.getById(done.payout.id, pool)!!.paidAt)
        expect("INVALID_STATE", 409) { creators.cancelPayout(done.payout.id) }
        assertEquals(400L, codeColumns(streamer).second)
    }

    @Test
    fun `a cancelled payout's failed row cannot be retried or confirmed any more, the cancel itself still passes - review, row of a cancelled payout`(): Unit = runBlocking {
        roster.granted = listOf(7L)

        val streamer = creator()

        available(streamer)

        val done = payout(streamer, 400, CreatorPayoutMethod.ACTION, note = null, actions = actionsJson(command("p1", 7)))
        val row = payoutRows(done.payout.id).single().getLong("id")

        send(row)
        confirm(row, ResultStatus.FAILED, "COMMAND_FAILED")

        assertEquals(CreatorPayoutState.CANCELLED, creators.cancelPayout(done.payout.id).payout.state)
        assertEquals(0L, codeColumns(streamer).second)

        val retried = retry(row)

        assertFalse(retried.moved, "the money went back: the command must not run for it")
        assertEquals(DeliveryStatus.FAILED, w.deliveries.getById(row, pool)!!.status)
        assertEquals(CreatorPayoutState.CANCELLED, payoutState(done.payout.id))
        assertEquals(0L, codeColumns(streamer).second)
    }

    @Test
    fun `a PENDING ACTION payout with a confirmed row cannot be cancelled, the delivered part would be paid again, and a FAILED one with a confirmed row neither - review, partial delivery`(): Unit = runBlocking {
        roster.granted = listOf(7L, 8L)

        val streamer = creator()

        val paid = available(streamer)

        val done = payout(streamer, 1000, CreatorPayoutMethod.ACTION, note = null, actions = actionsJson(command("p1", 7), command("p2", 8)))
        val rows = payoutRows(done.payout.id).map { it.getLong("id") }

        send(rows[0])
        confirm(rows[0])

        assertEquals(CreatorPayoutState.PENDING, payoutState(done.payout.id))

        expect("INVALID_STATE", 409) { creators.cancelPayout(done.payout.id) }
        assertEquals(1000L, codeColumns(streamer).second, "paidOut unchanged")
        assertEquals(CreatorPayoutState.PENDING, payoutState(done.payout.id))
        assertEquals(DeliveryStatus.PENDING, w.deliveries.getById(rows[1], pool)!!.status, "the open row is not cancelled")
        assertEquals(CreatorEarningState.PAID, earning(paid.order.id, streamer)!!.state, "the earnings stay marked paid")

        // the other row fails: FAILED with one delivered row is refused too
        send(rows[1])
        confirm(rows[1], ResultStatus.FAILED, "COMMAND_FAILED")

        assertEquals(CreatorPayoutState.FAILED, payoutState(done.payout.id))
        expect("INVALID_STATE", 409) { creators.cancelPayout(done.payout.id) }
        assertEquals(1000L, codeColumns(streamer).second)

        // retrying the failed row and confirming it ends the payout as PAID
        assertTrue(retry(rows[1]).moved)
        send(rows[1])
        confirm(rows[1])

        assertEquals(CreatorPayoutState.PAID, payoutState(done.payout.id))
    }

    @Test
    fun `an ACTION payout whose rows cannot be sent (no server is granted) is FAILED at once`(): Unit = runBlocking {
        roster.granted = emptyList()

        val streamer = creator()

        available(streamer)

        val done = payout(streamer, 400, CreatorPayoutMethod.ACTION, note = null, actions = actionsJson(command("p1", 7)))
        val rows = payoutRows(done.payout.id)

        assertEquals(1, rows.size)
        assertEquals("FAILED", rows.single().getString("status"))
        assertEquals(CreatorPayoutState.FAILED, w.creatorPayouts.getById(done.payout.id, pool)!!.state)
    }

    @Test
    fun `two rows of one payout confirmed at the same moment settle it once, neither misses the other (5 rounds)`(): Unit = runBlocking {
        roster.granted = listOf(7L, 8L)

        repeat(Race.rounds) { round ->
            val streamer = creator(name = "Pair$round", code = "PAIR$round")

            available(streamer)

            val done = payout(streamer, 1000, CreatorPayoutMethod.ACTION, note = null, actions = actionsJson(command("p1", 7), command("p2", 8)))
            val rows = payoutRows(done.payout.id).map { it.getLong("id") }

            rows.forEach { send(it) }

            val results = Race.run(2) { i -> confirm(rows[i]) }

            assertTrue(results.all { it.isSuccess }, "round $round: ${results.map { it.exceptionOrNull() }}")
            assertEquals(CreatorPayoutState.PAID, w.creatorPayouts.getById(done.payout.id, pool)!!.state, "round $round")
        }
    }

    // ================================================================================== views (21 section 7.5)

    @Test
    fun `the panel report, earnings and payouts views carry the documented fields and filter`(): Unit = runBlocking {
        val streamer = creator()
        val other = creator(name = "Idle", code = "IDLE")
        val a = available(streamer, 10_000)
        val b = order(streamer, 20_000)

        accrue(b)

        payout(streamer, 600)

        val report = creators.report(null, null)
        val row = report.getJsonArray("creators").map { it as JsonObject }.single { it.getString("code") == "STREAM" }

        assertEquals("EUR", report.getString("currency"))
        assertEquals(streamer.code.id, row.getLong("id"))
        assertEquals("Streamer", row.getString("creator"))
        assertEquals(2, row.getInteger("uses"))
        assertEquals(300.0, row.getDouble("revenue"))
        assertEquals(30.0, row.getDouble("earned"))
        assertEquals(20.0, row.getDouble("pending"), "the second order is still in its hold")
        assertEquals(0.0, row.getDouble("reversed"))
        assertEquals(6.0, row.getDouble("paidOut"))
        assertEquals(4.0, row.getDouble("available"))
        assertEquals(0.0, report.getJsonArray("creators").map { it as JsonObject }.single { it.getString("code") == "IDLE" }.getDouble("earned"), "a code without earnings is listed")
        assertEquals(other.code.id, report.getJsonArray("creators").map { it as JsonObject }.single { it.getString("code") == "IDLE" }.getLong("id"))

        val none = creators.report(w.clock.now() + day, null).getJsonArray("creators").map { it as JsonObject }.single { it.getString("code") == "STREAM" }

        assertEquals(0, none.getInteger("uses"), "the range bounds the earnings counted")
        assertEquals(4.0, none.getDouble("available"), "the balance is not a period figure")

        val earnings = creators.earningsOf(streamer.code.id, null, com.panomc.plugins.market.util.Paging.Window(1, 10))
        val first = earnings.getJsonArray("earnings").getJsonObject(0)

        assertEquals(2, earnings.getInteger("earningCount"))
        assertEquals(1, earnings.getInteger("totalPage"))
        assertEquals(b.order.id, first.getLong("orderId"), "newest first")
        assertEquals(200.0, first.getDouble("baseAmount"))
        assertEquals(10.0, first.getDouble("commissionPercent"))
        assertEquals(20.0, first.getDouble("amount"))
        assertEquals(0.0, first.getDouble("reversedAmount"))
        assertEquals("PENDING", first.getString("state"))
        assertNotNull(first.getLong("availableAt"))
        assertNotNull(first.getLong("createdAt"))
        assertEquals(1, creators.earningsOf(streamer.code.id, CreatorEarningState.PENDING, com.panomc.plugins.market.util.Paging.Window(1, 10)).getInteger("earningCount"))
        assertEquals(a.order.id, creators.earningsOf(streamer.code.id, null, com.panomc.plugins.market.util.Paging.Window(2, 1)).getJsonArray("earnings").getJsonObject(0).getLong("orderId"))

        val outOfRange = runCatching { creators.earningsOf(streamer.code.id, null, com.panomc.plugins.market.util.Paging.Window(3, 10)) }.exceptionOrNull()

        assertTrue(outOfRange is CreatorPageOutOfRange)
        expect("NOT_FOUND", 404) { creators.earningsOf(987_654, null, com.panomc.plugins.market.util.Paging.Window(1, 10)) }

        val payouts = creators.payoutsOf(streamer.code.id).getJsonArray("payouts")

        assertEquals(1, payouts.size())
        assertEquals(6.0, payouts.getJsonObject(0).getDouble("amount"))
        assertEquals("MANUAL", payouts.getJsonObject(0).getString("method"))
        assertEquals("PAID", payouts.getJsonObject(0).getString("state"))
        assertEquals("paid by bank", payouts.getJsonObject(0).getString("note"))
        assertEquals("admin", payouts.getJsonObject(0).getString("paidBy"))
        assertNotNull(payouts.getJsonObject(0).getLong("paidAt"))
        expect("NOT_FOUND", 404) { creators.payoutsOf(987_654) }
    }

    @Test
    fun `the creator sees their codes, totals, earnings by order number and payouts, no buyer data, and a user without a code gets 404`(): Unit = runBlocking {
        val streamer = creator()
        val paid = available(streamer, 10_000)
        val later = order(streamer, 20_000)

        accrue(later)

        payout(streamer, 600)

        val mine = creators.mine(streamer.user.id, com.panomc.plugins.market.util.Paging.Window(1, 10))
        val code = mine.getJsonArray("codes").getJsonObject(0)

        assertEquals("STREAM", code.getString("code"))
        assertEquals(5.0, code.getDouble("discount"))
        assertEquals("PERCENT", code.getString("unit"))
        assertEquals(10.0, code.getDouble("commissionPercent"))
        assertEquals("ACTIVE", code.getString("status"))
        assertNotNull(code.getValue("usedCount"))

        val totals = mine.getJsonObject("totals")

        assertEquals(30.0, totals.getDouble("earned"))
        assertEquals(20.0, totals.getDouble("pending"))
        assertEquals(6.0, totals.getDouble("paidOut"))
        assertEquals(4.0, totals.getDouble("available"))
        assertEquals("EUR", totals.getString("currency"))

        val earnings = mine.getJsonArray("earnings")

        assertEquals(2, mine.getInteger("earningCount"))
        assertEquals("#${later.order.id}", earnings.getJsonObject(0).getString("orderNumber"))
        assertEquals("#${paid.order.id}", earnings.getJsonObject(1).getString("orderNumber"))
        assertEquals(setOf("orderNumber", "amount", "state", "availableAt", "createdAt"), earnings.getJsonObject(0).fieldNames(), "no buyer data")
        assertEquals(1, mine.getJsonArray("payouts").size())
        assertEquals(setOf("amount", "method", "state", "paidAt", "createdAt"), mine.getJsonArray("payouts").getJsonObject(0).fieldNames())

        expect("NOT_FOUND", 404) { creators.mine(w.fixtures.user("NoCode").id, com.panomc.plugins.market.util.Paging.Window(1, 10)) }
        assertTrue(runCatching { creators.mine(streamer.user.id, com.panomc.plugins.market.util.Paging.Window(2, 10)) }.exceptionOrNull() is CreatorPageOutOfRange)

        // a soft-deleted code is not the creator's any more
        MarketTestDb.sql(pool, "UPDATE `${MarketTestDb.TABLE_PREFIX}market_creator_code` SET `deletedAt` = ? WHERE `id` = ?", w.clock.now(), streamer.code.id)
        expect("NOT_FOUND", 404) { creators.mine(streamer.user.id, com.panomc.plugins.market.util.Paging.Window(1, 10)) }
    }

    // ================================================================================== wiring

    @Test
    fun `the composition roots fill the creator seams, the source says so`() {
        val root = java.io.File("src/main/kotlin/com/panomc/plugins/market")
        val orders = root.resolve("routes/api/order/OrderRouteSupport.kt").readText()
        val refunds = root.resolve("service/RefundEffects.kt").readText()
        val delivery = root.resolve("service/DeliveryService.kt").readText()

        assertTrue("CreatorEffects({ creatorService(plugin) }" in orders, "O2 / O4 accrue the earning")
        assertTrue("payouts = object : PayoutSettlement" in orders, "an ACTION payout follows its delivery rows")
        assertTrue("CreatorReversal.reverse(" in refunds, "O10 and O11 reverse through the creator service's one implementation")
        assertTrue("payouts.settle(conn, row.sourceId)" in delivery && "payouts.lock(conn, row.sourceId)" in delivery, "the delivery service tells the payout")
    }

    @Test
    fun `the earning rows of the order are one per order and code, and the cached earnings of every code equal their net sum (I14 self-check)`(): Unit = runBlocking {
        val a = creator(name = "A", code = "AAA")
        val b = creator(name = "B", code = "BBB", commission = 500)

        for (i in 1..3) {
            val pa = order(a, 1_000L * i)
            val pb = order(b, 700L * i)

            accrue(pa)
            accrue(pb)
        }

        // a refund on one of them
        val refunded = order(a, 5_000)

        accrue(refunded)
        r.service.request(refunded.order.id, RefundInput(amount = 2_500), r.key(), null)

        w.assertInvariants()
        assertEquals(
            w.creatorEarnings.getByCodeId(a.code.id, pool).sumOf { it.amount - it.reversedAmount }, codeColumns(a).first
        )
        assertEquals(
            w.creatorEarnings.getByCodeId(b.code.id, pool).sumOf { it.amount - it.reversedAmount }, codeColumns(b).first
        )
    }
}
