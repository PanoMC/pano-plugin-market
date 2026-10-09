package com.panomc.plugins.market.service

import com.panomc.plugins.market.db.MarketDaoITBase
import com.panomc.plugins.market.db.model.CreditSystemKey
import com.panomc.plugins.market.db.model.CreditTxType
import com.panomc.plugins.market.db.tx.MarketDb
import com.panomc.plugins.market.error.InsufficientCredits
import com.panomc.plugins.market.support.InvariantChecker
import com.panomc.plugins.market.support.TestUser
import com.panomc.plugins.market.support.TestWiring
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.mysqlclient.MySQLException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import com.panomc.plugins.market.support.ErrorBodies

/**
 * `CreditService.post` and the plain ledger operations on a real MariaDB (MK-091; 07 sections 3 and 19.7, D-L1 to D-L5, D-L11 to D-L13, D-X1): double-entry postings,
 * the key replay, the three policies, the database guard, the system accounts, the posting rules, and 1 000 seeded postings against a model. The invariants
 * I1 to I22 are checked after every test by the base class; [CreditReconciler] judges L1 to L7 explicitly (a clock-free run with no re-check delay).
 */
class CreditLedgerIT : MarketDaoITBase() {
    private lateinit var w: TestWiring
    private lateinit var credits: CreditService
    private lateinit var db: MarketDb
    private val sequence = AtomicLong()

    @BeforeEach
    fun freshState() {
        runBlocking { resetState() }
        w = TestWiring(pool)
        credits = CreditService(w.clock, w.creditAccounts, w.creditTxs, w.creditEntries)
        db = w.db
    }

    private val fx get() = w.fixtures

    // ------------------------------------------------------------------------------------------------------ helpers

    private fun key(prefix: String = "panel") = "$prefix:k${sequence.incrementAndGet()}"

    private suspend fun grant(user: TestUser, amount: Long, key: String = key()) = db.tx { credits.grant(user.id, amount, key, 1L, "test grant", it) }

    private suspend fun revoke(user: TestUser, amount: Long, key: String = key()) = db.tx { credits.revoke(user.id, amount, key, 1L, "test revoke", it) }

    private suspend fun balance(user: TestUser): Long = fx.creditBalance(user)

    private suspend fun system(key: CreditSystemKey): Long = w.creditAccounts.getBySystemKey(key, pool)!!.balance

    private suspend fun txCount(): Long = count("market_credit_tx")

    private suspend fun entryCount(): Long = count("market_credit_entry")

    private fun reconciler() = CreditReconciler(w.clock, "pano_", { pool }, recheckDelayMs = 0)

    private suspend fun assertLedgerSound() {
        val result = reconciler().run(full = true)

        assertTrue(result.ok, "the reconciler found ${result.problems}")
    }

    private fun post(type: CreditTxType, key: String, user: TestUser, amount: Long, from: AccountRef, to: AccountRef, policy: PostingPolicy = PostingPolicy.NONE, orderId: Long? = null) =
        Posting(type, key, user.id, amount, from, to, policy, orderId = orderId)

    private fun hold(key: String, user: TestUser, amount: Long, orderId: Long) =
        post(CreditTxType.HOLD, key, user, amount, AccountRef.User(user.id), AccountRef.System(CreditSystemKey.HOLD), PostingPolicy.FAIL, orderId)

    private fun release(key: String, user: TestUser, amount: Long, orderId: Long) =
        post(CreditTxType.RELEASE, key, user, amount, AccountRef.System(CreditSystemKey.HOLD), AccountRef.User(user.id), PostingPolicy.NONE, orderId)

    // ================================================================================================== D-L1 double entry

    @Test
    fun `D-L1 a grant and a revoke are double-entry postings with a balanceAfter chain`(): Unit = runBlocking {
        val alex = fx.user("Alex")

        val granted = grant(alex, 10_000)

        assertFalse(granted.replayed)
        assertEquals(CreditTxType.GRANT, granted.tx.type)
        assertEquals(10_000, granted.tx.amount)
        assertEquals(0, granted.tx.shortfall)
        assertEquals(alex.id, granted.tx.userId)
        assertEquals(1L, granted.tx.actorUserId)
        assertEquals("test grant", granted.tx.note)
        assertEquals(10_000, granted.userBalance)

        val grantLegs = w.creditEntries.getByTxId(granted.tx.id, pool)

        assertEquals(2, grantLegs.size)
        assertEquals(listOf(-10_000L, 10_000L), grantLegs.map { it.amount }, "ISSUANCE is debited, the user credited")
        assertEquals(system(CreditSystemKey.ISSUANCE).let { listOf(it, 10_000L) }, grantLegs.map { it.balanceAfter })
        assertEquals(0L, grantLegs.sumOf { it.amount })

        val revoked = revoke(alex, 4_000)

        assertEquals(CreditTxType.REVOKE, revoked.tx.type)
        assertEquals(4_000, revoked.tx.amount)
        assertEquals(6_000, revoked.userBalance)
        assertEquals(6_000, balance(alex))
        assertEquals(4_000, system(CreditSystemKey.REVOKED))
        assertEquals(-10_000, system(CreditSystemKey.ISSUANCE))

        val userChain = w.creditEntries.getByAccountId(alex.accountId, 10, pool).reversed()

        assertEquals(listOf(10_000L, -4_000L), userChain.map { it.amount })
        assertEquals(listOf(10_000L, 6_000L), userChain.map { it.balanceAfter }, "balanceAfter of consecutive entries chains")
        assertEquals(2L, w.creditEntries.getByTxId(revoked.tx.id, pool).size.toLong())

        assertLedgerSound()
        InvariantChecker.assertAll(pool)
    }

    @Test
    fun `reading a balance never creates an account while a posting creates it once`(): Unit = runBlocking {
        val stranger = fx.user("Stranger")

        w.users.create("Ghost").let { ghost ->
            assertEquals(0, credits.balance(ghost, pool))
            assertNull(w.creditAccounts.getByUserId(ghost, pool), "reading does not create the row")

            db.tx { c -> credits.grant(ghost, 500, key(), null, "first", c) }

            assertEquals(500, credits.balance(ghost, pool))
            assertEquals(1L, count("market_credit_account", "`userId` = ?", ghost))
        }

        assertEquals(0, credits.balance(stranger.id, pool))
    }

    // ================================================================================================== D-L2, D-L3 keys

    @Test
    fun `D-L2 the same key returns the first transaction and writes nothing`(): Unit = runBlocking {
        val alex = fx.user("Alex")
        val k = key()

        val first = grant(alex, 3_000, k)
        val txs = txCount()
        val entries = entryCount()
        val again = grant(alex, 3_000, k)

        assertTrue(again.replayed)
        assertEquals(first.tx.id, again.tx.id)
        assertEquals(3_000, again.userBalance)
        assertEquals(txs, txCount())
        assertEquals(entries, entryCount())
        assertEquals(3_000, balance(alex))

        // inside one transaction twice, too
        val k2 = key()
        val pair = db.tx { c -> credits.grant(alex.id, 100, k2, null, "n", c) to credits.grant(alex.id, 100, k2, null, "n", c) }

        assertFalse(pair.first.replayed)
        assertTrue(pair.second.replayed)
        assertEquals(3_100, balance(alex))

        assertLedgerSound()
    }

    @Test
    fun `D-L3 a key used for another type or another user is a programming error and rolls the transaction back`(): Unit = runBlocking {
        val alex = fx.user("Alex")
        val beth = fx.user("Beth")
        val k = key()

        grant(alex, 3_000, k)

        val txs = txCount()

        assertThrows(IllegalStateException::class.java) { runBlocking { revoke(alex, 100, k) } }
        assertThrows(IllegalStateException::class.java) { runBlocking { grant(beth, 3_000, k) } }

        // the whole transaction is gone, also what it posted before the conflict
        val fresh = key()

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                db.tx { c ->
                    credits.grant(beth.id, 700, fresh, null, "n", c)
                    credits.revoke(alex.id, 100, k, null, "n", c)
                }
            }
        }

        assertEquals(txs, txCount())
        assertEquals(0, balance(beth))
        assertEquals(3_000, balance(alex))
        assertLedgerSound()
    }

    // ================================================================================================== policies

    @Test
    fun `D-L4 a FAIL posting without the balance throws InsufficientCredits and leaves no row`(): Unit = runBlocking {
        val alex = fx.user("Alex")

        grant(alex, 5_000)

        val txs = txCount()
        val entries = entryCount()

        val failure = assertThrows(InsufficientCredits::class.java) {
            runBlocking {
                db.tx { c ->
                    credits.grant(alex.id, 1, key(), null, "inside the same transaction", c)
                    credits.post(hold(key("order"), alex, 5_002, 901), c)
                }
            }
        }

        assertEquals(400, failure.getStatusCode())
        assertEquals(50.01, ErrorBodies.details(failure).getDouble("balance"), "the balance of the locked row, with the grant of the same transaction")
        assertEquals(txs, txCount(), "the posting before it was rolled back too")
        assertEquals(entries, entryCount())
        assertEquals(5_000, balance(alex))
        assertEquals(0, system(CreditSystemKey.HOLD))
    }

    @Test
    fun `D-L5 TAKE_AVAILABLE moves what is there and records the shortfall`(): Unit = runBlocking {
        val alex = fx.user("Alex")

        grant(alex, 3_000)

        val partial = revoke(alex, 5_000)

        assertEquals(3_000, partial.tx.amount)
        assertEquals(2_000, partial.tx.shortfall)
        assertEquals(0, partial.userBalance)
        assertEquals(0, balance(alex))
        assertEquals(2, w.creditEntries.getByTxId(partial.tx.id, pool).size)

        val nothing = revoke(alex, 1_500)

        assertEquals(0, nothing.tx.amount)
        assertEquals(1_500, nothing.tx.shortfall)
        assertEquals(0, w.creditEntries.getByTxId(nothing.tx.id, pool).size, "a revoke that found nothing has no entries")
        assertEquals(0, balance(alex))

        // a replay of the empty one is a replay
        val key = key()
        val first = revoke(alex, 10, key)
        val replay = revoke(alex, 10, key)

        assertTrue(replay.replayed)
        assertEquals(first.tx.id, replay.tx.id)
        assertEquals(10, replay.tx.shortfall)

        assertLedgerSound()
    }

    @Test
    fun `D-L13 credits on hold are not revocable`(): Unit = runBlocking {
        val alex = fx.user("Alex")

        grant(alex, 10_000)

        db.tx { credits.post(hold("order:901:hold", alex, 6_000, 901), it) }

        assertEquals(4_000, balance(alex))
        assertEquals(6_000, system(CreditSystemKey.HOLD))

        val revoked = revoke(alex, 10_000)

        assertEquals(4_000, revoked.tx.amount)
        assertEquals(6_000, revoked.tx.shortfall)
        assertEquals(0, balance(alex))
        assertEquals(6_000, system(CreditSystemKey.HOLD), "the hold is untouched")

        db.tx { credits.post(release("order:901:release", alex, 6_000, 901), it) }

        assertEquals(6_000, balance(alex))
        assertEquals(0, system(CreditSystemKey.HOLD))
        assertLedgerSound()
    }

    @Test
    fun `ALLOW_DEBT of a dispute clawback takes the full amount and leaves a debt that blocks every FAIL posting until it is repaid`(): Unit = runBlocking {
        val alex = fx.user("Alex")

        grant(alex, 10_000)

        val clawback = db.tx { c ->
            credits.post(
                post(CreditTxType.REVOKE, "dispute:5:clawback:7", alex, 15_000, AccountRef.User(alex.id), AccountRef.System(CreditSystemKey.REVOKED), PostingPolicy.ALLOW_DEBT), c
            )
        }

        assertEquals(15_000, clawback.tx.amount)
        assertEquals(0, clawback.tx.shortfall)
        assertEquals(-5_000, clawback.userBalance)
        assertEquals(-5_000, balance(alex))

        // a debt covers nothing, not even one cent; the message never shows a negative balance
        val refused = assertThrows(InsufficientCredits::class.java) { runBlocking { db.tx { credits.post(hold(key("order"), alex, 1, 902), it) } } }

        assertEquals(400, refused.getStatusCode())

        // TAKE_AVAILABLE takes nothing from a debt
        assertEquals(0, revoke(alex, 100).tx.amount)

        // a grant that leaves it negative is accepted, the next one brings it back
        assertEquals(-2_000, grant(alex, 3_000).userBalance)
        assertEquals(1_000, grant(alex, 3_000).userBalance)

        assertLedgerSound()
    }

    // ================================================================================================== D-L11 the guard

    @Test
    fun `D-L11 a capture or a release without the hold behind it is refused by the database guard and rolls back`(): Unit = runBlocking {
        val alex = fx.user("Alex")

        grant(alex, 4_000)

        val txs = txCount()
        val entries = entryCount()

        assertThrows(LedgerGuardViolation::class.java) {
            runBlocking {
                db.tx { credits.post(post(CreditTxType.CAPTURE, "order:903:capture", alex, 1_000, AccountRef.System(CreditSystemKey.HOLD), AccountRef.System(CreditSystemKey.SPENT), orderId = 903), it) }
            }
        }

        assertThrows(LedgerGuardViolation::class.java) { runBlocking { db.tx { credits.post(release("order:903:release", alex, 1_000, 903), it) } } }

        assertEquals(txs, txCount())
        assertEquals(entries, entryCount())
        assertEquals(0, system(CreditSystemKey.HOLD))
        assertEquals(0, system(CreditSystemKey.SPENT))
        assertEquals(4_000, balance(alex))
    }

    // ================================================================================================== D-L12, D-X1

    @Test
    fun `D-L12 the five system accounts are seeded once and seeding again changes nothing`(): Unit = runBlocking {
        val before = w.creditAccounts.getSystemAccounts(pool)

        assertEquals(CreditSystemKey.entries.toSet(), before.map { it.systemKey }.toSet())
        assertEquals(0, w.creditAccounts.seedSystemAccounts(pool))
        assertEquals(0, w.creditAccounts.seedSystemAccounts(pool))
        assertEquals(before.map { it.id }, w.creditAccounts.getSystemAccounts(pool).map { it.id })
    }

    @Test
    fun `D-X1 EXTERNAL_OUT needs the balance, EXTERNAL_IN and EXTERNAL_OUT replay by their mc key and EXTERNAL may go negative while the ledger still sums to zero`(): Unit = runBlocking {
        val alex = fx.user("Alex")

        grant(alex, 2_000)

        fun out(k: String, amount: Long) =
            post(CreditTxType.EXTERNAL_OUT, k, alex, amount, AccountRef.User(alex.id), AccountRef.System(CreditSystemKey.EXTERNAL), PostingPolicy.FAIL)

        fun into(k: String, amount: Long) =
            post(CreditTxType.EXTERNAL_IN, k, alex, amount, AccountRef.System(CreditSystemKey.EXTERNAL), AccountRef.User(alex.id))

        assertThrows(InsufficientCredits::class.java) { runBlocking { db.tx { credits.post(out("mc:1:op-out-1", 3_000), it) } } }
        assertEquals(2_000, balance(alex))
        assertEquals(0, count("market_credit_tx", "`idempotencyKey` = ?", "mc:1:op-out-1"))

        val deposit = db.tx { credits.post(into("mc:1:op-in-1", 10_000), it) }
        val depositAgain = db.tx { credits.post(into("mc:1:op-in-1", 10_000), it) }

        assertFalse(deposit.replayed)
        assertTrue(depositAgain.replayed)
        assertEquals(deposit.tx.id, depositAgain.tx.id)
        assertEquals(12_000, balance(alex))
        assertEquals(-10_000, system(CreditSystemKey.EXTERNAL))

        val withdrawal = db.tx { credits.post(out("mc:1:op-out-2", 3_000), it) }
        val withdrawalAgain = db.tx { credits.post(out("mc:1:op-out-2", 3_000), it) }

        assertTrue(withdrawalAgain.replayed)
        assertEquals(withdrawal.tx.id, withdrawalAgain.tx.id)
        assertEquals(9_000, balance(alex))
        assertEquals(-7_000, system(CreditSystemKey.EXTERNAL), "EXTERNAL is the server economy: either sign")

        assertLedgerSound()
    }

    // ================================================================================================== rules of a posting, locks

    @Test
    fun `a posting refuses a zero amount, a long key, a long note and a move from an account to itself`() {
        val issuance = AccountRef.System(CreditSystemKey.ISSUANCE)
        val user = AccountRef.User(7)

        assertThrows(IllegalArgumentException::class.java) { Posting(CreditTxType.GRANT, "k", 7, 0, issuance, user) }
        assertThrows(IllegalArgumentException::class.java) { Posting(CreditTxType.GRANT, "k", 7, -5, issuance, user) }
        assertThrows(IllegalArgumentException::class.java) { Posting(CreditTxType.GRANT, "", 7, 5, issuance, user) }
        assertThrows(IllegalArgumentException::class.java) { Posting(CreditTxType.GRANT, "k".repeat(Posting.MAX_KEY_LENGTH + 1), 7, 5, issuance, user) }
        assertThrows(IllegalArgumentException::class.java) { Posting(CreditTxType.GRANT, "k", 7, 5, issuance, user, note = "n".repeat(Posting.MAX_NOTE_LENGTH + 1)) }
        assertThrows(IllegalArgumentException::class.java) { Posting(CreditTxType.GRANT, "k", 7, 5, issuance, issuance) }
        assertThrows(IllegalArgumentException::class.java) { Posting(CreditTxType.GRANT, "k", 7, 5, user, AccountRef.User(7)) }
        assertThrows(IllegalArgumentException::class.java) { Posting(CreditTxType.HOLD, "k", 7, 5, issuance, user, PostingPolicy.FAIL) }

        Posting(CreditTxType.GRANT, "k".repeat(Posting.MAX_KEY_LENGTH), 7, 5, issuance, user, note = "n".repeat(Posting.MAX_NOTE_LENGTH))
    }

    @Test
    fun `lockAccounts locks users and system accounts against everybody else and creates a missing user account`(): Unit = runBlocking {
        val a = fx.user("A")
        val b = fx.user("B")
        val conn = pool.connection.coAwait()
        val tx = conn.begin().coAwait()

        try {
            credits.lockAccounts(listOf(b.id, a.id, a.id), withSystem = true, conn)

            val accounts = listOf(a.accountId, b.accountId) +
                listOf(CreditSystemKey.ISSUANCE, CreditSystemKey.SPENT, CreditSystemKey.HOLD, CreditSystemKey.REVOKED).map { w.creditAccounts.getBySystemKey(it, pool)!!.id }

            for (id in accounts) assertTrue(lockedByOthers(id), "account $id is locked")

            assertFalse(lockedByOthers(w.creditAccounts.getBySystemKey(CreditSystemKey.EXTERNAL, pool)!!.id), "EXTERNAL is not part of an order's lock set")
        } finally {
            tx.rollback().coAwait()
            conn.close().coAwait()
        }

        // a user without an account row gets one, once, and without the system accounts nothing else is locked
        val ghost = w.users.create("Ghost")

        assertNull(w.creditAccounts.getByUserId(ghost, pool))

        db.tx { c -> credits.lockAccounts(listOf(ghost), withSystem = false, c) }
        db.tx { c -> credits.lockAccounts(listOf(ghost), withSystem = false, c) }

        assertEquals(1L, count("market_credit_account", "`userId` = ?", ghost))
        assertEquals(0, credits.balance(ghost, pool))
    }

    /** Whether another connection cannot take the row lock of credit account [id] right now (`FOR UPDATE NOWAIT`). */
    private suspend fun lockedByOthers(id: Long): Boolean {
        val conn = pool.connection.coAwait()
        val tx = conn.begin().coAwait()
        var errorCode: Int? = null
        var failure: Throwable? = null

        runCatching { conn.query("SELECT `id` FROM `pano_market_credit_account` WHERE `id` = $id FOR UPDATE NOWAIT").execute().coAwait() }
            .onFailure { if (it is MySQLException) errorCode = it.errorCode else failure = it }

        runCatching { tx.rollback().coAwait() }
        conn.close().coAwait()

        failure?.let { throw it }

        val code = errorCode ?: return false

        assertTrue(code == 1205 || code == 3572, "unexpected error $code")

        return true
    }

    // ================================================================================================== 1 000 seeded postings

    @Test
    fun `I1 to I3 hold after 1 000 seeded postings and every balance equals the model`(): Unit = runBlocking {
        val random = Random(20_261_005)
        val users = List(8) { fx.user("seed$it") }
        val model = LongArray(users.size)
        val held = ArrayList<Triple<Int, Long, Long>>()
        val used = ArrayList<Pair<Int, String>>()
        var external = 0L
        var replays = 0
        var refusals = 0
        var shortfalls = 0
        var debts = 0

        repeat(1_000) { n ->
            val i = random.nextInt(users.size)
            val user = users[i]
            val amount = random.nextLong(1, 5_001)

            when (random.nextInt(100)) {
                in 0..34 -> {
                    val k = "panel:seed-$n"

                    assertEquals(model[i] + amount, grant(user, amount, k).userBalance)

                    model[i] += amount
                    used += i to k
                }

                in 35..54 -> {
                    val result = revoke(user, amount)
                    val taken = minOf(amount, maxOf(model[i], 0))

                    assertEquals(taken, result.tx.amount)
                    assertEquals(amount - taken, result.tx.shortfall)

                    if (result.tx.shortfall > 0) shortfalls++

                    model[i] -= taken
                }

                in 55..64 -> {
                    db.tx { credits.post(post(CreditTxType.EXTERNAL_IN, "mc:1:seed-$n", user, amount, AccountRef.System(CreditSystemKey.EXTERNAL), AccountRef.User(user.id)), it) }

                    model[i] += amount
                    external -= amount
                }

                in 65..74 -> {
                    val request = post(CreditTxType.EXTERNAL_OUT, "mc:1:seed-$n", user, amount, AccountRef.User(user.id), AccountRef.System(CreditSystemKey.EXTERNAL), PostingPolicy.FAIL)

                    if (model[i] >= amount) {
                        db.tx { credits.post(request, it) }

                        model[i] -= amount
                        external += amount
                    } else {
                        assertThrows(InsufficientCredits::class.java) { runBlocking { db.tx { credits.post(request, it) } } }

                        refusals++
                    }
                }

                in 75..84 -> {
                    val orderId = 10_000L + n
                    val request = hold("order:$orderId:hold", user, amount, orderId)

                    if (model[i] >= amount) {
                        db.tx { credits.post(request, it) }

                        model[i] -= amount
                        held += Triple(i, orderId, amount)
                    } else {
                        assertThrows(InsufficientCredits::class.java) { runBlocking { db.tx { credits.post(request, it) } } }

                        refusals++
                    }
                }

                in 85..94 -> {
                    if (used.isNotEmpty()) {
                        val (j, k) = used[random.nextInt(used.size)]
                        val result = grant(users[j], 1, k)

                        assertTrue(result.replayed)
                        assertEquals(model[j], balance(users[j]), "a replay moves nothing")

                        replays++
                    }
                }

                else -> {
                    val result = db.tx { c ->
                        credits.post(
                            post(CreditTxType.REVOKE, "dispute:$n:clawback:$n", user, amount, AccountRef.User(user.id), AccountRef.System(CreditSystemKey.REVOKED), PostingPolicy.ALLOW_DEBT), c
                        )
                    }

                    assertEquals(amount, result.tx.amount)

                    model[i] -= amount

                    if (model[i] < 0) debts++
                }
            }
        }

        // everything on hold goes back: no order exists, so a hold left over would break I4
        for ((i, orderId, amount) in held) {
            db.tx { credits.post(release("order:$orderId:release", users[i], amount, orderId), it) }

            model[i] += amount
        }

        for ((i, user) in users.withIndex()) assertEquals(model[i], balance(user), "balance of ${user.username}")

        assertEquals(0, system(CreditSystemKey.HOLD))
        assertEquals(external, system(CreditSystemKey.EXTERNAL))
        assertEquals(0L, w.creditAccounts.getSystemAccounts(pool).sumOf { it.balance } + users.sumOf { balance(it) }, "L5: the ledger sums to zero")
        assertTrue(replays > 10 && refusals > 10 && shortfalls > 5 && debts > 0, "the seed exercised every path: $replays replays, $refusals refusals, $shortfalls shortfalls, $debts debts")
        assertTrue(txCount() > 600, "enough postings: ${txCount()}")

        assertLedgerSound()
        InvariantChecker.assertAll(pool)
    }

    @Test
    fun `a posting key differs between users and orders so two holds of one order generation never collide`() {
        assertEquals("order:7:hold", CreditService.holdKey(7, 0))
        assertEquals("order:7:hold:1", CreditService.holdKey(7, 1))
        assertEquals("order:7:hold:12", CreditService.holdKey(7, 12))
        assertEquals("order:7:release", CreditService.releaseKey(7, 0))
        assertEquals("order:7:release:3", CreditService.releaseKey(7, 3))
        assertNotEquals(CreditService.holdKey(7, 1), CreditService.holdKey(71, 0))
    }
}
