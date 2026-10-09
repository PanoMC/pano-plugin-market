package com.panomc.plugins.market.service

import com.panomc.platform.model.Error
import com.panomc.plugins.market.db.MarketDaoITBase
import com.panomc.plugins.market.db.model.CreditSystemKey
import com.panomc.plugins.market.db.model.CreditTxType
import com.panomc.plugins.market.db.model.ReservationState
import com.panomc.plugins.market.error.InsufficientCredits
import com.panomc.plugins.market.support.Race
import com.panomc.plugins.market.support.TestUser
import com.panomc.plugins.market.support.TestWiring
import com.panomc.plugins.market.util.OrderStatus
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicLong

/**
 * Concurrent spending of credits on a real MariaDB (MK-091; 07 sections 14.3 and 19.7, 17 section 9.4): the account lock serialises postings and checkouts, so
 * credits are never spent twice (R-08, R-09), a manual revoke never takes credits that are held and never leaves a negative balance (R-22), a payment racing the
 * expiry ends in exactly one of capture and release (D-O8), and the ledger itself loses no update, creates one account per user and takes a key once
 * (D-L6 to D-L10). Every race runs [Race.rounds] times on fresh fixtures; the invariants I1 to I22 and the self-check of the ledger run after every test.
 */
class CreditRaceIT : MarketDaoITBase() {
    private lateinit var w: TestWiring
    private lateinit var c: CreditHarness
    private val vertx: Vertx = Vertx.vertx()
    private val sequence = AtomicLong()

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
    private val credits get() = c.credits

    // ------------------------------------------------------------------------------------------------------ helpers

    private suspend fun user(name: String, credit: Long = 0): Pair<TestUser, QuoteCaller> {
        val u = fx.user(name)

        h.emails[u.id] = "$name@example.com"

        if (credit > 0) fx.credit(u, credit)

        return u to QuoteCaller(u.id)
    }

    private fun key() = "panel:race-${sequence.incrementAndGet()}"

    private fun codeOf(failure: Throwable): String? = (failure as? Error)?.let { JsonObject(it.encode()).getJsonObject("error").getString("code") }

    private suspend fun spentCount() = count("market_credit_tx", "`type` = 'CAPTURE'")

    // ================================================================================== the ledger itself

    @Test
    fun `D-L6 twenty first postings for one user create one account`(): Unit = runBlocking {
        repeat(Race.rounds) { round ->
            val ghost = w.users.create("ghost$round")

            val results = Race.run(20) { i -> w.db.tx { conn -> credits.grant(ghost, 100, key(), null, "first $i", conn) } }

            assertEquals(20, results.count { it.isSuccess }, "failures: ${results.mapNotNull { it.exceptionOrNull() }}")
            assertEquals(1L, count("market_credit_account", "`userId` = ?", ghost))
            assertEquals(2_000, credits.balance(ghost, pool))
        }
    }

    @Test
    fun `D-L7 a hundred concurrent grants of one credit to one user lose no update`(): Unit = runBlocking {
        val (alex, _) = user("Alex")

        val results = Race.run(100) { i -> w.db.tx { conn -> credits.grant(alex.id, 100, key(), 1L, "grant $i", conn) } }

        assertEquals(100, results.count { it.isSuccess }, "failures: ${results.mapNotNull { it.exceptionOrNull() }}")
        assertEquals(10_000, fx.creditBalance(alex))
        assertEquals(-10_000, w.creditAccounts.getBySystemKey(CreditSystemKey.ISSUANCE, pool)!!.balance)
        assertEquals(100L, count("market_credit_tx", "`userId` = ?", alex.id))

        // L3 and L4: the chain of balanceAfter has no gap and no duplicate
        val chain = w.creditEntries.getByAccountId(alex.accountId, 200, pool).reversed()

        assertEquals((1..100).map { it * 100L }, chain.map { it.balanceAfter })
    }

    @Test
    fun `D-L8 two concurrent holds of 80 on a balance of 100 let exactly one through`(): Unit = runBlocking {
        repeat(Race.rounds) { round ->
            val (alex, _) = user("Alex$round", credit = 10_000)
            val orders = listOf(9_000L + round * 10, 9_001L + round * 10)

            val results = Race.run(2) { i ->
                w.db.tx { conn ->
                    credits.post(
                        Posting(
                            CreditTxType.HOLD, "order:${orders[i]}:hold", alex.id, 8_000, AccountRef.User(alex.id), AccountRef.System(CreditSystemKey.HOLD), PostingPolicy.FAIL, orderId = orders[i]
                        ),
                        conn
                    )
                }
            }

            assertEquals(1, results.count { it.isSuccess })
            assertEquals(1, results.count { it.exceptionOrNull() is InsufficientCredits }, "the other one: ${results.mapNotNull { it.exceptionOrNull() }}")
            assertEquals(2_000, fx.creditBalance(alex))
            assertEquals(8_000, w.creditAccounts.getBySystemKey(CreditSystemKey.HOLD, pool)!!.balance)

            // no order owns these credits: put them back so that the book of open orders stays in line
            val winner = orders[results.indexOfFirst { it.isSuccess }]

            w.db.tx { conn ->
                credits.post(
                    Posting(CreditTxType.RELEASE, "order:$winner:release", alex.id, 8_000, AccountRef.System(CreditSystemKey.HOLD), AccountRef.User(alex.id), orderId = winner), conn
                )
            }

            assertEquals(10_000, fx.creditBalance(alex))
        }
    }

    @Test
    fun `D-L9 concurrent posts with the same key make one transaction with one pair of entries`(): Unit = runBlocking {
        repeat(Race.rounds) {
            val (alex, _) = user("Alex${sequence.incrementAndGet()}")
            val same = key()

            val results = Race.run(10) { w.db.tx { conn -> credits.grant(alex.id, 700, same, 1L, "same", conn) } }

            assertEquals(10, results.count { it.isSuccess }, "failures: ${results.mapNotNull { it.exceptionOrNull() }}")
            assertEquals(1, results.count { !it.getOrThrow().replayed }, "one writer, nine replays")
            assertEquals(1, results.map { it.getOrThrow().tx.id }.toSet().size)
            assertEquals(1L, count("market_credit_tx", "`idempotencyKey` = ?", same))
            assertEquals(2L, count("market_credit_entry", "`txId` = ?", results.first().getOrThrow().tx.id))
            assertEquals(700, fx.creditBalance(alex))
        }
    }

    @Test
    fun `D-L10 transactions that lock the same accounts in opposite orders complete with the right balances`(): Unit = runBlocking {
        val (a, _) = user("Aa")
        val (b, _) = user("Bb")

        val results = Race.run(40) { i ->
            w.db.tx { conn ->
                // the order a caller names the users in is not the order the rows are locked in
                credits.lockAccounts(if (i % 2 == 0) listOf(a.id, b.id) else listOf(b.id, a.id), withSystem = true, conn)
                credits.grant(if (i % 2 == 0) a.id else b.id, 100, key(), null, "first", conn)
                credits.grant(if (i % 2 == 0) b.id else a.id, 100, key(), null, "second", conn)
            }
        }

        assertEquals(40, results.count { it.isSuccess }, "failures: ${results.mapNotNull { it.exceptionOrNull() }}")
        assertEquals(4_000, fx.creditBalance(a))
        assertEquals(4_000, fx.creditBalance(b))
    }

    // ================================================================================== R-08, R-09, R-22: orders

    @Test
    fun `R-08 two concurrent credits-only checkouts of 80 on a balance of 100 let one through and refuse the other`(): Unit = runBlocking {
        h.config = h.config.copy(allowMixedCreditPayment = true)
        fx.paymentMethod("fake")

        repeat(Race.rounds) { round ->
            val (alex, caller) = user("Alex$round", credit = 10_000)
            val product = fx.product(price = 10_000, creditPrice = 8_000, stock = 5)
            val spentBefore = w.creditAccounts.getBySystemKey(CreditSystemKey.SPENT, pool)!!.balance

            val results = Race.run(2) { c.spend(product, caller) }

            assertEquals(1, results.count { it.isSuccess }, "$round: ${results.map { it.exceptionOrNull()?.let { e -> (e as? Error)?.encode() ?: e.toString() } }}")

            val refused = results.single { it.isFailure }.exceptionOrNull()

            assertEquals("INSUFFICIENT_CREDITS", codeOf(refused!!), "$refused")
            assertEquals(400, (refused as Error).getStatusCode())

            assertEquals(2_000, fx.creditBalance(alex))
            assertEquals(0, w.creditAccounts.getBySystemKey(CreditSystemKey.HOLD, pool)!!.balance, "nothing stays on hold")
            assertEquals(spentBefore + 8_000, w.creditAccounts.getBySystemKey(CreditSystemKey.SPENT, pool)!!.balance)
            assertEquals(1L, count("market_order", "`userId` = ?", alex.id), "the refused checkout created no order")
            assertEquals(4, w.products.getById(product.id, pool)!!.stock, "and kept no stock")
        }
    }

    @Test
    fun `R-09 five concurrent mixed checkouts of 60 on a balance of 100 let exactly one hold, and the expiry gives it back`(): Unit = runBlocking {
        h.config = h.config.copy(allowMixedCreditPayment = true)
        fx.paymentMethod("fake")

        repeat(Race.rounds) { round ->
            val (alex, caller) = user("Alex$round", credit = 10_000)
            val product = fx.product(price = 10_000, stock = 10)

            val results = Race.run(5) { c.mixed(product, caller, useCredits = 60) }

            assertEquals(1, results.count { it.isSuccess }, "$round: ${results.map { it.exceptionOrNull()?.let { e -> (e as? Error)?.encode() ?: e.toString() } }}")

            for (failure in results.filter { it.isFailure }) {
                assertEquals("INSUFFICIENT_CREDITS", codeOf(failure.exceptionOrNull()!!), "${failure.exceptionOrNull()}")
            }

            assertEquals(4_000, fx.creditBalance(alex))
            assertEquals(6_000, w.creditAccounts.getBySystemKey(CreditSystemKey.HOLD, pool)!!.balance)
            assertEquals(1L, count("market_order", "`userId` = ?", alex.id))
            assertEquals(9, w.products.getById(product.id, pool)!!.stock)

            w.clock.advance(61 * 60_000L)
            c.expiry.runOnce()

            assertEquals(0, w.creditAccounts.getBySystemKey(CreditSystemKey.HOLD, pool)!!.balance)
            assertEquals(10_000, fx.creditBalance(alex))
            assertEquals(10, w.products.getById(product.id, pool)!!.stock)
        }
    }

    @Test
    fun `R-22 a revoke of 100 racing a credits-only purchase of 80 never takes held credits and never goes negative`(): Unit = runBlocking {
        fx.paymentMethod("fake")

        var purchaseWon = 0
        var revokeWon = 0

        repeat(Race.rounds * 2) { round ->
            val (alex, caller) = user("Alex$round", credit = 10_000)
            val product = fx.product(price = 8_000, creditPrice = 8_000, stock = 5)
            val spentBefore = w.creditAccounts.getBySystemKey(CreditSystemKey.SPENT, pool)!!.balance
            val revokedBefore = w.creditAccounts.getBySystemKey(CreditSystemKey.REVOKED, pool)!!.balance
            val revokeKey = key()

            val results = Race.run<Any>(2) { i ->
                if (i == 0) w.db.tx { conn -> credits.revoke(alex.id, 10_000, revokeKey, 1L, "race", conn) } else c.spend(product, caller)
            }

            val revoke = (results[0].getOrThrow() as PostResult).tx
            val purchase = results[1]
            val balance = fx.creditBalance(alex)

            assertEquals(10_000, revoke.amount + revoke.shortfall, "the revoke accounts for the whole request")
            assertTrue(balance >= 0, "never negative: $balance")
            assertEquals(0, w.creditAccounts.getBySystemKey(CreditSystemKey.HOLD, pool)!!.balance)

            if (purchase.isSuccess) {
                purchaseWon++

                assertEquals(2_000, revoke.amount, "the revoke found only what the purchase left")
                assertEquals(8_000, revoke.shortfall, "and says so")
                assertEquals(spentBefore + 8_000, w.creditAccounts.getBySystemKey(CreditSystemKey.SPENT, pool)!!.balance)
            } else {
                revokeWon++

                assertEquals("INSUFFICIENT_CREDITS", codeOf(purchase.exceptionOrNull()!!), "${purchase.exceptionOrNull()}")
                assertEquals(10_000, revoke.amount)
                assertEquals(0, revoke.shortfall)
                assertEquals(spentBefore, w.creditAccounts.getBySystemKey(CreditSystemKey.SPENT, pool)!!.balance)
            }

            assertEquals(0, balance, "every credit is either spent or revoked")
            assertEquals(revokedBefore + revoke.amount, w.creditAccounts.getBySystemKey(CreditSystemKey.REVOKED, pool)!!.balance)
        }

        assertEquals(Race.rounds * 2, purchaseWon + revokeWon)
    }

    @Test
    fun `D-O8 a payment racing the expiry of a mixed order ends in exactly one of capture and release`(): Unit = runBlocking {
        h.config = h.config.copy(allowMixedCreditPayment = true)
        fx.paymentMethod("fake")

        var captured = 0
        var released = 0

        repeat(Race.rounds * 2) { round ->
            val (alex, caller) = user("Alex$round", credit = 8_000)
            val product = fx.product(price = 10_000, stock = 5)
            val order = c.orderOf(c.mixed(product, caller, useCredits = 30))
            val attempt = c.attempts(order.id).single()

            w.clock.advance(61 * 60_000L)

            val results = Race.run<Any>(2) { i -> if (i == 0) c.succeed(order.id, attempt) else c.expiry.runOnce() }

            assertTrue(results.all { it.isSuccess }, "$round: ${results.mapNotNull { it.exceptionOrNull() }}")

            val ledger = c.ledger(order.id)
            val after = c.order(order.id)
            val captures = ledger.count { it.type == CreditTxType.CAPTURE }
            val releases = ledger.count { it.type == CreditTxType.RELEASE }

            assertEquals(1, captures + releases, "exactly one of capture and release: ${ledger.map { it.type }}, order ${after.status}")
            assertEquals(1, ledger.count { it.type == CreditTxType.HOLD })

            if (captures == 1) {
                captured++

                assertEquals(OrderStatus.COMPLETED, after.status)
                assertEquals(ReservationState.COMMITTED, after.reservationState)
                assertEquals(5_000, fx.creditBalance(alex))
            } else {
                released++

                assertTrue(after.status != OrderStatus.COMPLETED, "released but completed: ${after.status}")
                assertEquals(ReservationState.RELEASED, after.reservationState)
                assertEquals(8_000, fx.creditBalance(alex))
            }

            assertEquals(0, w.creditAccounts.getBySystemKey(CreditSystemKey.HOLD, pool)!!.balance)
        }

        assertEquals(Race.rounds * 2, captured + released)
    }

    @Test
    fun `two concurrent re-tenders of one pending order leave one consistent hold`(): Unit = runBlocking {
        h.config = h.config.copy(allowMixedCreditPayment = true)
        fx.paymentMethod("fake")

        val (alex, caller) = user("Alex", credit = 10_000)
        val order = c.orderOf(c.mixed(fx.product(price = 10_000, stock = 5), caller, useCredits = 30))

        val results = Race.run(2) { i -> c.payments.pay(c.order(order.id), PayRequest("fake", if (i == 0) 1_000 else 2_000, null), PayCaller(), pool) }

        val after = c.order(order.id)
        val held = c.credits.outstanding(order.id, pool)

        assertTrue(results.count { it.isSuccess } >= 1, "${results.mapNotNull { it.exceptionOrNull() }}")
        assertEquals(after.creditAmount, held, "the ledger holds exactly the credit part the order ended with")
        assertEquals(10_000 - held, fx.creditBalance(alex))
        assertEquals(held, w.creditAccounts.getBySystemKey(CreditSystemKey.HOLD, pool)!!.balance)

        c.payments.cancel(after, pool)

        assertEquals(10_000, fx.creditBalance(alex))
    }
}
