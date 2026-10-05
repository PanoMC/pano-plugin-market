package com.panomc.plugins.market.db

import com.panomc.plugins.market.db.impl.MarketCreditAccountDaoImpl
import com.panomc.plugins.market.db.impl.MarketCreditEntryDaoImpl
import com.panomc.plugins.market.db.impl.MarketCreditTxDaoImpl
import com.panomc.plugins.market.db.model.CreditAccountType
import com.panomc.plugins.market.db.model.CreditSystemKey
import com.panomc.plugins.market.db.model.CreditTxType
import com.panomc.plugins.market.db.model.MarketCreditAccount
import com.panomc.plugins.market.db.model.MarketCreditEntry
import com.panomc.plugins.market.db.model.MarketCreditTx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** `market_credit_account`, `market_credit_tx` and `market_credit_entry` (01 section 7). */
class MarketCreditDaoIT : MarketDaoITBase() {
    private val accounts = MarketCreditAccountDaoImpl()
    private val txs = MarketCreditTxDaoImpl()
    private val entries = MarketCreditEntryDaoImpl()

    private fun userAccount(userId: Long = 7, balance: Long = 0) =
        MarketCreditAccount(type = CreditAccountType.USER, userId = userId, balance = balance, createdAt = 10, updatedAt = 20)

    private fun tx(key: String = "panel:k1", type: CreditTxType = CreditTxType.TOPUP, user: Long? = 7) = MarketCreditTx(
        type = type, idempotencyKey = key, userId = user, amount = 1_500, shortfall = 250, orderId = 11, refundId = 12, deliveryId = 13,
        actorUserId = 14, note = "welcome gift", createdAt = 30, updatedAt = 40
    )

    private suspend fun balanceOf(id: Long): Long = accounts.getById(id, pool)!!.balance

    // --- system accounts -----------------------------------------------------------------------------------------

    @Test
    fun `the five system accounts exist after the schema install, ids 1 to 5 in order`(): Unit = runBlocking {
        val system = accounts.getSystemAccounts(pool)
        assertEquals(listOf("ISSUANCE", "SPENT", "HOLD", "REVOKED", "EXTERNAL"), system.map { it.systemKey!!.name })
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), system.map { it.id })
        assertTrue(system.all { it.type == CreditAccountType.SYSTEM && it.userId == null && it.balance == 0L })
        assertEquals(CreditSystemKey.HOLD, accounts.getBySystemKey(CreditSystemKey.HOLD, pool)!!.systemKey)
        assertEquals(5L, count("market_credit_account"))
    }

    @Test
    fun `seeding twice leaves five rows and reports what it inserted`(): Unit = runBlocking {
        assertEquals(0, accounts.seedSystemAccounts(pool))
        assertEquals(0, accounts.seedSystemAccounts(pool))
        assertEquals(5L, count("market_credit_account"))

        sql("DELETE FROM `pano_market_credit_account` WHERE `systemKey` IN ('SPENT', 'REVOKED')")
        assertEquals(2, accounts.seedSystemAccounts(pool))
        assertEquals(5L, count("market_credit_account"))

        // Dao.init seeds too, and is repeatable
        MarketCreditAccountDaoImpl().init(pool)
        MarketCreditAccountDaoImpl().init(pool)
        assertEquals(5L, count("market_credit_account"))
        assertEquals(5L, count("market_credit_account", "`type` = 'SYSTEM'"))
    }

    @Test
    fun `a sixth system row or a repeated key is refused by the unique key`(): Unit = runBlocking {
        assertNull(accounts.add(MarketCreditAccount(type = CreditAccountType.SYSTEM, systemKey = CreditSystemKey.ISSUANCE), pool))
        assertEquals(5L, count("market_credit_account"))
    }

    // --- user accounts ------------------------------------------------------------------------------------------

    @Test
    fun `an account round-trips every column`(): Unit = runBlocking {
        val written = userAccount(balance = 4_200)
        EntityRoundTrip.assertSame(written, accounts.getById(accounts.add(written, pool)!!, pool)!!)
        val system = MarketCreditAccount(type = CreditAccountType.SYSTEM, systemKey = CreditSystemKey.EXTERNAL, balance = -300)
        // a second EXTERNAL row is a duplicate; read the seeded one instead
        assertNull(accounts.add(system, pool))
        val read = accounts.getBySystemKey(CreditSystemKey.EXTERNAL, pool)!!
        assertEquals(CreditAccountType.SYSTEM, read.type)
        assertNull(read.userId)
        assertNull(accounts.getById(9999, pool))
        assertNull(accounts.getByUserId(9999, pool))
    }

    @Test
    fun `a user has one account`(): Unit = runBlocking {
        assertNotNull(accounts.add(userAccount(7), pool))
        assertNull(accounts.add(userAccount(7, balance = 5), pool))
        assertNotNull(accounts.add(userAccount(8), pool))
        assertEquals(0L, accounts.getByUserId(7, pool)!!.balance)
        assertEquals(2L, count("market_credit_account", "`type` = 'USER'"))
    }

    @Test
    fun `insertUserAccountIgnore creates the account once`(): Unit = runBlocking {
        assertTrue(accounts.insertUserAccountIgnore(21, pool))
        assertEquals(false, accounts.insertUserAccountIgnore(21, pool))
        val account = accounts.getByUserId(21, pool)!!
        assertEquals(CreditAccountType.USER, account.type)
        assertNull(account.systemKey)
        assertEquals(0L, account.balance)
        assertEquals(1L, count("market_credit_account", "`userId` = 21"))
    }

    // --- guarded balance update ----------------------------------------------------------------------------------

    @Test
    fun `the guarded update returns 0 rows when the balance would go below zero and leaves it alone`(): Unit = runBlocking {
        val id = accounts.add(userAccount(balance = 1_000), pool)!!

        assertEquals(1, accounts.addToBalance(id, -400, guarded = true, pool))
        assertEquals(600L, balanceOf(id))
        assertEquals(1, accounts.addToBalance(id, -600, guarded = true, pool)) // exactly to zero is allowed
        assertEquals(0L, balanceOf(id))

        assertEquals(0, accounts.addToBalance(id, -1, guarded = true, pool))
        assertEquals(0L, balanceOf(id))
        assertEquals(1, accounts.addToBalance(id, 250, guarded = true, pool))
        assertEquals(0, accounts.addToBalance(id, -251, guarded = true, pool))
        assertEquals(250L, balanceOf(id))
    }

    @Test
    fun `the guard refuses debits only, a guarded credit repays an account that is in debt`(): Unit = runBlocking {
        val debtor = accounts.add(userAccount(41, balance = 100), pool)!!
        assertEquals(1, accounts.addToBalance(debtor, -400, guarded = false, pool))
        assertEquals(-300L, balanceOf(debtor))

        // a credit smaller than the debt is accepted even though the result is still negative
        assertEquals(1, accounts.addToBalance(debtor, 100, guarded = true, pool))
        assertEquals(-200L, balanceOf(debtor))
        // any debit stays refused while in debt
        assertEquals(0, accounts.addToBalance(debtor, -1, guarded = true, pool))
        assertEquals(-200L, balanceOf(debtor))
        // a zero delta is a credit too
        assertEquals(1, accounts.addToBalance(debtor, 0, guarded = true, pool))
        assertEquals(-200L, balanceOf(debtor))
        // repaid past zero, debits work again
        assertEquals(1, accounts.addToBalance(debtor, 250, guarded = true, pool))
        assertEquals(50L, balanceOf(debtor))
        assertEquals(1, accounts.addToBalance(debtor, -50, guarded = true, pool))
        assertEquals(0L, balanceOf(debtor))
    }

    @Test
    fun `an unguarded update may go below zero, as ISSUANCE, EXTERNAL and an ALLOW_DEBT user account do`(): Unit = runBlocking {
        val issuance = accounts.getBySystemKey(CreditSystemKey.ISSUANCE, pool)!!.id
        assertEquals(1, accounts.addToBalance(issuance, -10_000, guarded = false, pool))
        assertEquals(-10_000L, balanceOf(issuance))
        // the same delta guarded is refused
        assertEquals(0, accounts.addToBalance(issuance, -1, guarded = true, pool))
        assertEquals(-10_000L, balanceOf(issuance))

        val debtor = accounts.add(userAccount(9, balance = 100), pool)!!
        assertEquals(1, accounts.addToBalance(debtor, -400, guarded = false, pool))
        assertEquals(-300L, balanceOf(debtor))
    }

    @Test
    fun `the guarded update on a missing account returns 0 rows`(): Unit = runBlocking {
        assertEquals(0, accounts.addToBalance(424242, 100, guarded = true, pool))
        assertEquals(0, accounts.addToBalance(424242, 100, guarded = false, pool))
    }

    @Test
    fun `concurrent guarded debits never take the balance below zero`(): Unit = runBlocking {
        val id = accounts.add(userAccount(balance = 1_000), pool)!!
        val results = (1..40).map { async(Dispatchers.Default) { accounts.addToBalance(id, -100, guarded = true, pool) } }
            .map { it.await() }
        assertEquals(10, results.sum())
        assertEquals(0L, balanceOf(id))
    }

    @Test
    fun `lockByIds answers the rows in ascending id order inside a transaction`(): Unit = runBlocking {
        val a = accounts.add(userAccount(31, balance = 5), pool)!!
        val b = accounts.add(userAccount(32, balance = 6), pool)!!
        val locked = marketDb().tx { conn -> accounts.lockByIds(listOf(b, 3, a, b), conn) }
        assertEquals(listOf(3L, a, b), locked.map { it.id })
        assertEquals(listOf(0L, 5L, 6L), locked.map { it.balance })
        assertEquals(emptyList<MarketCreditAccount>(), marketDb().tx { conn -> accounts.lockByIds(emptyList(), conn) })
    }

    // --- transactions -----------------------------------------------------------------------------------------

    @Test
    fun `a transaction round-trips every column`(): Unit = runBlocking {
        val written = tx()
        EntityRoundTrip.differsFromDefaults(written, MarketCreditTx())
        val id = txs.add(written, pool)!!
        val read = txs.getById(id, pool)!!
        assertEquals(id, read.id)
        EntityRoundTrip.assertSame(written, read)
        assertNull(txs.getById(9999, pool))
    }

    @Test
    fun `a transaction with only the required columns reads back with the defaults`(): Unit = runBlocking {
        val minimal = MarketCreditTx(type = CreditTxType.EXTERNAL_IN, idempotencyKey = "mc:1:2", amount = 5, createdAt = 1, updatedAt = 2)
        val read = txs.getById(txs.add(minimal, pool)!!, pool)!!
        EntityRoundTrip.assertSame(minimal, read)
        assertEquals(0L, read.shortfall)
        assertNull(read.userId)
        assertNull(read.note)
    }

    @Test
    fun `the idempotency key is unique and a replay finds the first transaction`(): Unit = runBlocking {
        val first = txs.add(tx("order:1:hold"), pool)!!
        assertNull(txs.add(tx("order:1:hold", type = CreditTxType.HOLD, user = 8), pool)) // differs in every other column
        assertEquals(1L, count("market_credit_tx"))
        assertEquals(first, txs.getByIdempotencyKey("order:1:hold", pool)!!.id)
        assertEquals(CreditTxType.TOPUP, txs.getByIdempotencyKey("order:1:hold", pool)!!.type)
        assertNull(txs.getByIdempotencyKey("order:1:capture", pool))
        assertNotNull(txs.add(tx("order:1:capture"), pool))
        assertEquals(2L, count("market_credit_tx"))
    }

    @Test
    fun `an idempotency key of 128 characters fits`(): Unit = runBlocking {
        val key = "mc:" + "s".repeat(60) + ":" + "o".repeat(64)
        assertEquals(128, key.length)
        assertNotNull(txs.add(tx(key), pool))
        assertEquals(key, txs.getByIdempotencyKey(key, pool)!!.idempotencyKey)
    }

    @Test
    fun `transactions of a user come newest first and of an order oldest first`(): Unit = runBlocking {
        val ids = (1..4).map { txs.add(tx("k$it", user = if (it % 2 == 0) 7 else 8).let { t -> MarketCreditTx(type = t.type, idempotencyKey = t.idempotencyKey, userId = t.userId, amount = 1, orderId = if (it <= 3) 99 else null) }, pool)!! }
        assertEquals(listOf(ids[3], ids[1]), txs.getByUserId(7, 10, pool).map { it.id })
        assertEquals(listOf(ids[3]), txs.getByUserId(7, 1, pool).map { it.id })
        assertEquals(listOf(ids[0], ids[1], ids[2]), txs.getByOrderId(99, pool).map { it.id })
        assertEquals(emptyList<MarketCreditTx>(), txs.getByOrderId(1, pool))
    }

    // --- entries ----------------------------------------------------------------------------------------------

    @Test
    fun `an entry round-trips and the sums follow the legs`(): Unit = runBlocking {
        val issuance = accounts.getBySystemKey(CreditSystemKey.ISSUANCE, pool)!!.id
        val user = accounts.add(userAccount(), pool)!!
        val txId = txs.add(tx(), pool)!!

        val written = MarketCreditEntry(txId = txId, accountId = user, amount = 1_500, balanceAfter = 1_500, createdAt = 5, updatedAt = 6)
        EntityRoundTrip.differsFromDefaults(written, MarketCreditEntry())
        val entryId = entries.add(written, pool)
        EntityRoundTrip.assertSame(written, entries.getById(entryId, pool)!!)
        entries.add(MarketCreditEntry(txId = txId, accountId = issuance, amount = -1_500, balanceAfter = -1_500), pool)

        assertEquals(0L, entries.sumByTxId(txId, pool))
        assertEquals(1_500L, entries.sumByAccountId(user, pool))
        assertEquals(-1_500L, entries.sumByAccountId(issuance, pool))
        assertEquals(0L, entries.sumByAccountId(4242, pool))
        assertEquals(0L, entries.sumByTxId(4242, pool))
        assertEquals(listOf(user, issuance), entries.getByTxId(txId, pool).map { it.accountId })
        assertNull(entries.getById(9999, pool))
    }

    @Test
    fun `entries of an account come newest first and honour the limit`(): Unit = runBlocking {
        val user = accounts.add(userAccount(), pool)!!
        val ids = (1..3).map { entries.add(MarketCreditEntry(txId = it.toLong(), accountId = user, amount = 10L * it, balanceAfter = 10L * it), pool) }
        assertEquals(listOf(ids[2], ids[1], ids[0]), entries.getByAccountId(user, 10, pool).map { it.id })
        assertEquals(listOf(ids[2]), entries.getByAccountId(user, 1, pool).map { it.id })
    }

    @Test
    fun `a posting inside one transaction commits all legs together and rolls back as a unit`(): Unit = runBlocking {
        val issuance = accounts.getBySystemKey(CreditSystemKey.ISSUANCE, pool)!!.id
        val user = accounts.add(userAccount(balance = 0), pool)!!

        marketDb().tx { conn ->
            val txId = txs.add(tx("panel:post-1"), conn)!!
            accounts.addToBalance(issuance, -1_000, guarded = false, conn)
            entries.add(MarketCreditEntry(txId = txId, accountId = issuance, amount = -1_000, balanceAfter = -1_000), conn)
            accounts.addToBalance(user, 1_000, guarded = true, conn)
            entries.add(MarketCreditEntry(txId = txId, accountId = user, amount = 1_000, balanceAfter = 1_000), conn)
        }
        assertEquals(1_000L, balanceOf(user))
        assertEquals(1_000L, entries.sumByAccountId(user, pool))

        val failure = runCatching {
            marketDb().tx { conn ->
                txs.add(tx("panel:post-2"), conn)
                check(accounts.addToBalance(user, -5_000, guarded = true, conn) == 1) { "guard" }
            }
        }
        assertEquals("guard", failure.exceptionOrNull()?.message)
        assertNull(txs.getByIdempotencyKey("panel:post-2", pool))
        assertEquals(1_000L, balanceOf(user))
    }
}
