package com.panomc.plugins.market.service

import com.panomc.platform.error.PageNotFound
import com.panomc.platform.model.PageRequest
import com.panomc.platform.error.NotFound
import com.panomc.plugins.market.db.MarketDaoITBase
import com.panomc.plugins.market.db.model.CreditTxType
import com.panomc.plugins.market.error.IdempotencyConflict
import com.panomc.plugins.market.error.InvalidCreditAmount
import com.panomc.plugins.market.error.InvalidSettings
import com.panomc.plugins.market.error.RequestValueException
import com.panomc.plugins.market.routes.panel.credit.CreditAdminService
import com.panomc.plugins.market.routes.panel.credit.CreditMovement
import com.panomc.plugins.market.routes.panel.credit.applyCreditSettings
import com.panomc.plugins.market.routes.panel.credit.parseCreditAmount
import com.panomc.plugins.market.routes.panel.credit.parseCreditMove
import com.panomc.plugins.market.routes.panel.credit.parseCreditTxFilter
import com.panomc.plugins.market.support.InvariantChecker
import com.panomc.plugins.market.support.MarketTestDb
import com.panomc.plugins.market.support.Race
import com.panomc.plugins.market.support.TestUser
import com.panomc.plugins.market.support.TestWiring
import io.vertx.core.json.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import com.panomc.plugins.market.support.ErrorBodies

/**
 * The panel credit routes' logic on a real MariaDB (MK-093; 07 sections 11.1, 11.2, 14.2): grant / revoke with the `Idempotency-Key` rules, the note and
 * amount parsing, the shortfall of a revoke beyond the balance (CR-01 twin), ten concurrent grants under one key (R-21 twin), the totals row, the three
 * read models, and the credit settings validation. The platform `user` table is the stub of `MarketTestDb.createPlatformStubs`, with one row per fixture user.
 */
class CreditAdminIT : MarketDaoITBase() {
    private lateinit var w: TestWiring
    private lateinit var credits: CreditService
    private lateinit var admin: CreditAdminService
    private lateinit var boss: TestUser
    private val counter = java.util.concurrent.atomic.AtomicInteger()

    @BeforeEach
    fun freshState() {
        runBlocking {
            resetState()
            MarketTestDb.createPlatformStubs(pool)
            sql("DELETE FROM `${prefix}user`")
        }

        w = TestWiring(pool)
        credits = CreditService(w.clock, w.creditAccounts, w.creditTxs, w.creditEntries)
        admin = CreditAdminService(w.db, credits, w.creditAccounts, w.creditTxs, { prefix }, { pool })
        boss = runBlocking { user("Boss") }
    }

    private suspend fun user(name: String): TestUser {
        val created = w.fixtures.user(name)

        sql("INSERT INTO `${prefix}user` (`id`, `username`, `registerDate`) VALUES (?, ?, 0)", created.id, name)

        return created
    }

    private fun key() = "idem-key-%016d".format(counter.incrementAndGet())

    private fun body(amount: Any?, note: String? = "support gift") = JsonObject().put("amount", amount).also { if (note != null) it.put("note", note) }

    private suspend fun move(type: CreditTxType, target: TestUser, amount: Any?, key: String = key(), note: String? = "support gift"): CreditMovement =
        admin.move(parseCreditMove(type, key, body(amount, note)), target.id, boss.id)

    private suspend fun grant(target: TestUser, amount: Any?, key: String = key()) = move(CreditTxType.GRANT, target, amount, key)

    private suspend fun revoke(target: TestUser, amount: Any?, key: String = key()) = move(CreditTxType.REVOKE, target, amount, key)

    private val first = PageRequest(1, 50)

    private suspend fun hold(target: TestUser, credits: Long, order: Long) = w.db.tx { conn ->
        this.credits.lockAccounts(listOf(target.id), true, conn)
        this.credits.post(
            Posting(CreditTxType.HOLD, "order:$order:hold", target.id, credits, AccountRef.User(target.id), AccountRef.System(com.panomc.plugins.market.db.model.CreditSystemKey.HOLD), PostingPolicy.FAIL, orderId = order),
            conn
        )
    }

    /** Gives a test hold back, so that invariant I4 (HOLD = held orders) holds again: the holds here belong to no order row. */
    private suspend fun release(target: TestUser, credits: Long, order: Long) = w.db.tx { conn ->
        this.credits.lockAccounts(listOf(target.id), true, conn)
        this.credits.post(
            Posting(CreditTxType.RELEASE, "order:$order:release", target.id, credits, AccountRef.System(com.panomc.plugins.market.db.model.CreditSystemKey.HOLD), AccountRef.User(target.id), orderId = order),
            conn
        )
    }

    // ================================================================================================== request parsing

    @Test
    fun `grant and revoke need an Idempotency-Key header of the contract shape`() {
        for (header in listOf(null, "", "   ", "short", "has spaces in it 123456", "x".repeat(65), "bad/char/in/the/key/ab")) {
            assertThrows(RequestValueException::class.java, { parseCreditMove(CreditTxType.GRANT, header, body(5)) }, "header [$header]")
            assertThrows(RequestValueException::class.java, { parseCreditMove(CreditTxType.REVOKE, header, body(5)) }, "header [$header]")
        }

        assertEquals("0f8fad5b-d9cb-469f-a165-70867728950e", parseCreditMove(CreditTxType.GRANT, " 0f8fad5b-d9cb-469f-a165-70867728950e ", body(5)).idempotencyKey)
    }

    @Test
    fun `the note is trimmed and must have 3 to 255 characters`() {
        fun parse(note: String?) = parseCreditMove(CreditTxType.GRANT, key(), body(5, note))

        for (bad in listOf(null, "", "  ", "ab", "  ab  ", "x".repeat(256), " " + "x".repeat(256))) {
            assertThrows(RequestValueException::class.java, { parse(bad) }, "note [$bad]")
        }

        assertEquals("abc", parse("  abc ").note)
        assertEquals(255, parse("x".repeat(255)).note.length)
        assertThrows(RequestValueException::class.java) { parseCreditMove(CreditTxType.GRANT, key(), JsonObject().put("amount", 5).put("note", 12345)) }
    }

    @Test
    fun `the amount is a number from 0_01 to 1 000 000 with at most two decimals`() {
        assertEquals(1, parseCreditAmount(0.01))
        assertEquals(1050, parseCreditAmount(10.5))
        assertEquals(1025, parseCreditAmount(10.25))
        assertEquals(1000, parseCreditAmount(10))
        assertEquals(100_000_000, parseCreditAmount(1_000_000))
        assertEquals(1000, parseCreditAmount(10.000))

        for (bad in listOf<Any?>(0, 0.0, -1, -0.01, 1_000_000.01, 1_000_001, 1.005, 0.001, "10", null, Double.NaN, Double.POSITIVE_INFINITY, true)) {
            assertThrows(InvalidCreditAmount::class.java, { parseCreditAmount(bad) }, "amount [$bad]")
        }
    }

    @Test
    fun `the transaction filter accepts a csv of ledger types and refuses unknown ones`() {
        val filter = parseCreditTxFilter("GRANT, REVOKE,GRANT", "7", "9", "100", "200")

        assertEquals(listOf(CreditTxType.GRANT, CreditTxType.REVOKE), filter.types)
        assertEquals(7L, filter.userId)
        assertEquals(9L, filter.orderId)
        assertEquals(100L, filter.from)
        assertEquals(200L, filter.to)
        assertTrue(parseCreditTxFilter(null, null, null, null, null).types.isEmpty())

        assertThrows(RequestValueException::class.java) { parseCreditTxFilter("NOPE", null, null, null, null) }
        assertThrows(RequestValueException::class.java) { parseCreditTxFilter(null, "0", null, null, null) }
        assertThrows(RequestValueException::class.java) { parseCreditTxFilter(null, null, null, "yesterday", null) }
    }

    // ================================================================================================== grant and revoke

    @Test
    fun `a grant creates the account, credits it and stores the actor and the note`(): Unit = runBlocking {
        val alex = w.users.create("Alex").also { sql("INSERT INTO `${prefix}user` (`id`, `username`, `registerDate`) VALUES (?, 'Alex', 0)", it) }

        assertNull(w.creditAccounts.getByUserId(alex, pool))

        val result = admin.move(parseCreditMove(CreditTxType.GRANT, key(), body(12.5, "  welcome  ")), alex, boss.id)

        assertFalse(result.replayed)
        assertEquals(1250, result.balance)
        assertEquals(1250, result.moved)
        assertEquals(0, result.shortfall)
        assertEquals("Alex", result.username)
        assertEquals(12.5, result.toJson().getDouble("balance"))
        assertEquals(1250, w.creditAccounts.getByUserId(alex, pool)!!.balance)

        val stored = w.creditTxs.getByUserId(alex, 10, pool).single()

        assertEquals(CreditTxType.GRANT, stored.type)
        assertEquals(boss.id, stored.actorUserId)
        assertEquals("welcome", stored.note)
        assertTrue(stored.idempotencyKey.startsWith("panel:"))

        InvariantChecker.assertAll(pool)
    }

    @Test
    fun `a replay returns the first response with the current balance and writes nothing`(): Unit = runBlocking {
        val alex = user("Alex")
        val same = key()

        val firstTry = grant(alex, 20, same)

        grant(alex, 5)

        val again = grant(alex, 20, same)

        assertTrue(again.replayed)
        assertEquals(2000, again.moved)
        assertEquals(2500, again.balance, "the balance is the current one")
        assertEquals(2000, firstTry.balance)
        assertEquals(2L, count("market_credit_tx", "`type` = 'GRANT'"))
        assertEquals(2500, w.fixtures.creditBalance(alex))
        InvariantChecker.assertAll(pool)
    }

    @Test
    fun `a replay of a revoke returns the stored shortfall and the current balance`(): Unit = runBlocking {
        val alex = user("Alex")

        grant(alex, 30)

        val same = key()
        val firstTry = revoke(alex, 50, same)

        assertEquals(3000, firstTry.moved)
        assertEquals(2000, firstTry.shortfall)

        grant(alex, 7)

        val again = revoke(alex, 50, same)

        assertTrue(again.replayed)
        assertEquals(2000, again.shortfall, "the stored shortfall")
        assertEquals(700, again.balance, "the current balance")
        assertEquals(700, w.fixtures.creditBalance(alex), "nothing was taken a second time")
        assertEquals(1L, count("market_credit_tx", "`type` = 'REVOKE'"))
    }

    @Test
    fun `the same key with another amount, user or type is IDEMPOTENCY_CONFLICT and changes nothing`(): Unit = runBlocking {
        val alex = user("Alex")
        val bea = user("Bea")
        val same = key()

        grant(alex, 20, same)

        val before = count("market_credit_tx")

        assertThrows(IdempotencyConflict::class.java) { runBlocking { grant(alex, 21, same) } }
        assertThrows(IdempotencyConflict::class.java) { runBlocking { grant(bea, 20, same) } }
        assertThrows(IdempotencyConflict::class.java) { runBlocking { revoke(alex, 20, same) } }

        assertEquals(before, count("market_credit_tx"))
        assertEquals(2000, w.fixtures.creditBalance(alex))
        assertEquals(0, w.fixtures.creditBalance(bea))
        InvariantChecker.assertAll(pool)
    }

    @Test
    fun `a revoked key conflicts on the requested amount, not on the moved one`(): Unit = runBlocking {
        val alex = user("Alex")
        val same = key()

        grant(alex, 30)
        revoke(alex, 50, same)

        assertTrue(revoke(alex, 50, same).replayed, "30 moved + 20 short = 50 requested")
        assertThrows(IdempotencyConflict::class.java) { runBlocking { revoke(alex, 30, same) } }
    }

    @Test
    fun `a revoke beyond the balance takes what is there and records the shortfall (CR-01 twin)`(): Unit = runBlocking {
        val alex = user("Alex")

        grant(alex, 30)

        val result = revoke(alex, 50)

        assertEquals(3000, result.moved)
        assertEquals(2000, result.shortfall)
        assertEquals(0, result.balance)
        assertEquals(0, w.fixtures.creditBalance(alex), "never negative")
        assertEquals(20.0, result.toJson().getDouble("shortfall"))
        assertEquals(3000, w.creditTxs.getByUserId(alex.id, 10, pool).first { it.type == CreditTxType.REVOKE }.amount)
        InvariantChecker.assertAll(pool)
    }

    @Test
    fun `a revoke at balance 0 writes a transaction without entries and answers shortfall = requested`(): Unit = runBlocking {
        val alex = user("Alex")

        val result = revoke(alex, 12.34)

        assertEquals(0, result.moved)
        assertEquals(1234, result.shortfall)
        assertEquals(0, result.balance)

        val tx = w.creditTxs.getByUserId(alex.id, 10, pool).single()

        assertEquals(0, tx.amount)
        assertEquals(1234, tx.shortfall)
        assertEquals(0L, count("market_credit_entry", "`txId` = ?", tx.id))
        assertTrue(revoke(alex, 12.34, tx.idempotencyKey.removePrefix("panel:")).replayed)
        InvariantChecker.assertAll(pool)
    }

    @Test
    fun `credits held by pending orders are not revocable`(): Unit = runBlocking {
        val alex = user("Alex")

        grant(alex, 100)
        hold(alex, 8000, 900)

        val result = revoke(alex, 100)

        assertEquals(2000, result.moved)
        assertEquals(8000, result.shortfall)
        assertEquals(0, result.balance)
        assertEquals(8000, w.creditAccounts.getBySystemKey(com.panomc.plugins.market.db.model.CreditSystemKey.HOLD, pool)!!.balance, "the hold is untouched")

        release(alex, 8000, 900)
        InvariantChecker.assertAll(pool)
    }

    @Test
    fun `an unknown user is NOT_FOUND and nothing is created`(): Unit = runBlocking {
        assertThrows(NotFound::class.java) { runBlocking { admin.move(parseCreditMove(CreditTxType.GRANT, key(), body(5)), 9999, boss.id) } }
        assertThrows(NotFound::class.java) { runBlocking { admin.accountDetail(9999, first) } }
        assertEquals(0L, count("market_credit_tx"))
        assertEquals(0L, count("market_credit_account", "`userId` = ?", 9999))
    }

    @Test
    fun `ten concurrent grants with one key make one transaction (R-21 twin)`(): Unit = runBlocking {
        repeat(Race.rounds) { round ->
            val alex = user("Race$round")
            val same = key()

            val results = Race.run(10) { grant(alex, 7.5, same) }

            assertEquals(10, results.count { it.isSuccess }, "failures: ${results.mapNotNull { it.exceptionOrNull() }}")
            assertEquals(1, results.count { !it.getOrThrow().replayed }, "one writer, nine replays")
            assertEquals(1L, count("market_credit_tx", "`idempotencyKey` = ?", "panel:$same"))
            assertEquals(750, w.fixtures.creditBalance(alex))
            assertEquals(setOf(750L), results.map { it.getOrThrow().moved }.toSet())
        }

        InvariantChecker.assertAll(pool)
    }

    @Test
    fun `concurrent grants and revokes with different keys keep the ledger sound`(): Unit = runBlocking {
        val alex = user("Alex")

        grant(alex, 100)

        val results = Race.run(20) { i -> if (i % 2 == 0) grant(alex, 10) else revoke(alex, 10) }

        assertEquals(20, results.count { it.isSuccess }, "failures: ${results.mapNotNull { it.exceptionOrNull() }}")
        assertEquals(10_000, w.fixtures.creditBalance(alex))
        InvariantChecker.assertAll(pool)
    }

    // ================================================================================================== lists

    @Test
    fun `the totals row balances issued = outstanding + held + spent + revoked`(): Unit = runBlocking {
        val alex = user("Alex")
        val bea = user("Bea")

        grant(alex, 100)
        grant(bea, 40)
        revoke(alex, 10)
        hold(bea, 1500, 901)

        val totals = admin.accountList(null, first).getJsonObject("totals")

        assertEquals(140.0, totals.getDouble("issued"))
        assertEquals(10.0, totals.getDouble("revoked"))
        assertEquals(15.0, totals.getDouble("held"))
        assertEquals(0.0, totals.getDouble("spent"))
        assertEquals(0.0, totals.getDouble("external"))
        assertEquals(115.0, totals.getDouble("outstanding"))
        assertEquals(
            totals.getDouble("issued"),
            totals.getDouble("outstanding") + totals.getDouble("held") + totals.getDouble("spent") + totals.getDouble("revoked")
        )

        release(bea, 1500, 901)
    }

    @Test
    fun `the account list is ordered by balance, searched by username prefix and paged`(): Unit = runBlocking {
        val anna = user("Anna")
        val alex = user("Alex")
        val bob = user("Bob_x")
        val cem = user("Cem")
        w.users.create("NoAccount").also { sql("INSERT INTO `${prefix}user` (`id`, `username`, `registerDate`) VALUES (?, 'NoAccount', 0)", it) }

        grant(anna, 10)
        grant(alex, 30)
        grant(bob, 30)
        grant(cem, 5)

        sql("DELETE FROM `${prefix}market_credit_account` WHERE `userId` = ?", boss.id)

        val all = admin.accountList(null, first)

        assertEquals(listOf("Alex", "Bob_x", "Anna", "Cem"), all.getJsonArray("items").map { (it as JsonObject).getString("username") }, "balance DESC, userId ASC")
        assertEquals(4L, all.getJsonObject("page").getLong("totalItems"))
        assertEquals(1L, all.getJsonObject("page").getLong("totalPages"))

        assertEquals(listOf("Alex", "Anna"), admin.accountList("a", first).getJsonArray("items").map { (it as JsonObject).getString("username") }, "case-insensitive prefix")
        assertEquals(listOf("Bob_x"), admin.accountList("BOB_", first).getJsonArray("items").map { (it as JsonObject).getString("username") })
        assertEquals(0, admin.accountList("%", first).getJsonArray("items").size(), "a wildcard is literal")
        assertEquals(0, admin.accountList("lex", first).getJsonArray("items").size(), "prefix, not substring")

        val page2 = admin.accountList(null, PageRequest(2, 3))

        assertEquals(listOf("Cem"), page2.getJsonArray("items").map { (it as JsonObject).getString("username") })
        assertEquals(2L, page2.getJsonObject("page").getLong("totalPages"))
        assertThrows(PageNotFound::class.java) { runBlocking { admin.accountList(null, PageRequest(3, 3)) } }
        assertEquals(0L, admin.accountList("zzz", first).getJsonObject("page").getLong("totalItems"))
    }

    @Test
    fun `an account detail lists its entries newest first and works without an account row`(): Unit = runBlocking {
        val alex = user("Alex")
        val ghost = user("Ghost")

        grant(alex, 20)
        revoke(alex, 5)
        revoke(alex, 100)

        val detail = admin.accountDetail(alex.id, first)
        val entries = detail.getJsonArray("items").map { it as JsonObject }

        assertEquals(0.0, detail.getDouble("balance"))
        assertEquals(3L, detail.getJsonObject("page").getLong("totalItems"))
        assertEquals(listOf("REVOKE", "REVOKE", "GRANT"), entries.map { it.getString("type") })
        assertEquals(listOf(-15.0, -5.0, 20.0), entries.map { it.getDouble("amount") })
        assertEquals(listOf(0.0, 15.0, 20.0), entries.map { it.getDouble("balanceAfter") })
        assertEquals("Boss", entries[0].getString("actorUsername"))
        assertEquals("support gift", entries[0].getString("note"))
        assertEquals(85.0, entries[0].getDouble("shortfall"))
        assertEquals(0.0, entries[2].getDouble("shortfall"))
        assertNull(entries[0].getValue("orderId"))

        val none = admin.accountDetail(ghost.id, first)

        assertEquals(0.0, none.getDouble("balance"))
        assertEquals(0, none.getJsonArray("items").size())
        assertEquals(0L, none.getJsonObject("page").getLong("totalItems"))

        sql("DELETE FROM `${prefix}market_credit_account` WHERE `userId` = ?", ghost.id)

        assertEquals(0L, admin.accountDetail(ghost.id, first).getJsonObject("page").getLong("totalItems"))
        assertEquals(0L, count("market_credit_account", "`userId` = ?", ghost.id), "reading created no row")
    }

    @Test
    fun `the ledger lists zero-entry transactions and filters by type, user, order and time`(): Unit = runBlocking {
        val alex = user("Alex")
        val bea = user("Bea")

        w.clock.set(1_000)
        grant(alex, 20)
        w.clock.set(2_000)
        revoke(bea, 7)
        w.clock.set(3_000)
        revoke(alex, 5)

        val all = admin.transactions(parseCreditTxFilter(null, null, null, null, null), first)
        val rows = all.getJsonArray("items").map { it as JsonObject }

        assertEquals(3L, all.getJsonObject("page").getLong("totalItems"))
        assertEquals(listOf("REVOKE", "REVOKE", "GRANT"), rows.map { it.getString("type") }, "id DESC")
        assertEquals(listOf("Alex", "Bea", "Alex"), rows.map { it.getString("username") })
        assertEquals(7.0, rows[1].getDouble("shortfall"), "Bea had nothing: a zero-entry transaction is listed")
        assertEquals(0.0, rows[1].getDouble("amount"))
        assertEquals("Boss", rows[0].getString("actorUsername"))
        assertEquals(3000L, rows[0].getLong("createdAt"))
        assertNull(rows[0].getValue("orderId"))

        fun ids(filter: com.panomc.plugins.market.routes.panel.credit.CreditTxFilter) =
            runBlocking { admin.transactions(filter, first).getJsonArray("items").map { (it as JsonObject).getString("type") + (it.getLong("userId")) } }

        assertEquals(listOf("REVOKE${alex.id}", "GRANT${alex.id}"), ids(parseCreditTxFilter(null, alex.id.toString(), null, null, null)))
        assertEquals(listOf("REVOKE${alex.id}", "REVOKE${bea.id}"), ids(parseCreditTxFilter("REVOKE", null, null, null, null)))
        assertEquals(3, ids(parseCreditTxFilter("REVOKE,GRANT", null, null, null, null)).size)
        assertEquals(listOf("REVOKE${bea.id}"), ids(parseCreditTxFilter(null, null, null, "1500", "2500")))
        assertEquals(emptyList<String>(), ids(parseCreditTxFilter(null, null, "99", null, null)))
        assertEquals(1L, admin.transactions(parseCreditTxFilter(null, null, null, null, null), PageRequest(3, 1)).getJsonArray("items").size().toLong())
        assertThrows(PageNotFound::class.java) { runBlocking { admin.transactions(parseCreditTxFilter(null, null, null, null, null), PageRequest(4, 1)) } }
    }

    // ================================================================================================== settings

    private fun current() = JsonObject()
        .put("creditsEnabled", true).put("creditName", "").put("cashbackPercent", 0.0).put("onlyAcceptCredits", false).put("creditValue", 1.0)
        .put("allowMixedCreditPayment", false).put("creditTopUpEnabled", false).put("creditTopUpFreeAmount", false).put("creditTopUpMin", 1.0)
        .put("creditTopUpMax", 10000.0).put("storeName", "Market")

    private fun fieldErrors(body: JsonObject): JsonObject {
        val e = assertThrows(InvalidSettings::class.java) { applyCreditSettings(body, current()) }

        return ErrorBodies.details(e).getJsonObject("fieldErrors")
    }

    @Test
    fun `credit settings validation follows 07 section 14_2`() {
        val ok = applyCreditSettings(
            JsonObject().put("creditValue", 0.01).put("creditTopUpEnabled", true).put("creditTopUpFreeAmount", true).put("creditTopUpMin", 0.01).put("creditTopUpMax", 1_000_000)
                .put("cashbackPercent", 100).put("creditName", "x".repeat(32)),
            current()
        )

        assertEquals(0.01, ok.getDouble("creditValue"))
        assertEquals("Market", ok.getString("storeName"), "merged onto the current config")

        assertEquals(setOf("creditValue"), fieldErrors(JsonObject().put("creditValue", 0.009)).fieldNames())
        assertEquals(setOf("creditValue"), fieldErrors(JsonObject().put("creditValue", 0)).fieldNames())
        assertEquals(setOf("creditValue"), fieldErrors(JsonObject().put("creditValue", 1_000_000.01)).fieldNames())
        assertEquals(setOf("creditValue"), fieldErrors(JsonObject().put("creditValue", 1.005)).fieldNames(), "two decimals")
        assertEquals(setOf("cashbackPercent"), fieldErrors(JsonObject().put("cashbackPercent", 100.5)).fieldNames())
        assertEquals(setOf("cashbackPercent"), fieldErrors(JsonObject().put("cashbackPercent", -1)).fieldNames())
        assertEquals(setOf("cashbackPercent"), fieldErrors(JsonObject().put("cashbackPercent", 5.123)).fieldNames())
        assertEquals(setOf("creditTopUpMin"), fieldErrors(JsonObject().put("creditTopUpMin", 0)).fieldNames())
        assertEquals(setOf("creditTopUpMax"), fieldErrors(JsonObject().put("creditTopUpMin", 5).put("creditTopUpMax", 4)).fieldNames())
        assertEquals(setOf("creditTopUpMax"), fieldErrors(JsonObject().put("creditTopUpMax", 1_000_001)).fieldNames())
        assertEquals(setOf("creditName"), fieldErrors(JsonObject().put("creditName", "x".repeat(33))).fieldNames())
        assertEquals(setOf("creditTopUpFreeAmount"), fieldErrors(JsonObject().put("creditTopUpFreeAmount", true)).fieldNames(), "needs creditTopUpEnabled")
        assertEquals(setOf("onlyAcceptCredits"), fieldErrors(JsonObject().put("creditsEnabled", false).put("onlyAcceptCredits", true)).fieldNames())
        assertEquals(setOf("storeName"), fieldErrors(JsonObject().put("storeName", "x")).fieldNames(), "a general key is unknown here")
        assertEquals(setOf("creditValue", "creditTopUpMin"), fieldErrors(JsonObject().put("creditValue", 0).put("creditTopUpMin", 0)).fieldNames(), "every bad field is reported")
    }
}
