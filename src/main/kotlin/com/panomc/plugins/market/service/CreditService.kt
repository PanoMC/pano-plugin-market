package com.panomc.plugins.market.service

import com.panomc.plugins.market.core.credit.CreditPolicy
import com.panomc.plugins.market.core.time.Clock
import com.panomc.plugins.market.db.dao.MarketCreditAccountDao
import com.panomc.plugins.market.db.dao.MarketCreditEntryDao
import com.panomc.plugins.market.db.dao.MarketCreditTxDao
import com.panomc.plugins.market.db.model.CreditSystemKey
import com.panomc.plugins.market.db.model.CreditTxType
import com.panomc.plugins.market.db.model.MarketCreditAccount
import com.panomc.plugins.market.db.model.MarketCreditEntry
import com.panomc.plugins.market.db.model.MarketCreditTx
import com.panomc.plugins.market.db.model.MarketOrder
import com.panomc.plugins.market.db.tx.LockedOrder
import com.panomc.plugins.market.error.InsufficientCredits
import com.panomc.plugins.market.spi.payment.ReviewReason
import com.panomc.plugins.market.util.MoneyUtil
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.SqlConnection
import org.slf4j.LoggerFactory

/** A ledger account named by a posting: the account of a user (created on first use) or one of the five system accounts (07 section 3). */
sealed class AccountRef {
    class User(val userId: Long) : AccountRef()

    class System(val key: CreditSystemKey) : AccountRef()
}

/**
 * What a posting does when the balance of its `from` user account cannot pay the requested amount (07 section 3.1): [NONE] nothing is
 * checked here (the database guard `balance >= 0` of the system accounts still is), [FAIL] `InsufficientCredits`, [TAKE_AVAILABLE] the
 * transaction moves what is there and records the `shortfall`, [ALLOW_DEBT] the full amount is taken and the balance may go negative
 * (dispute clawbacks only). The arithmetic is [CreditPolicy].
 */
enum class PostingPolicy { NONE, FAIL, TAKE_AVAILABLE, ALLOW_DEBT }

/**
 * One transaction of the ledger (07 section 3.2): two entries, `-amount` on [from] and `+amount` on [to]. [amount] is the amount
 * requested (> 0); the transaction stores what was moved. [userId] is the affected user, [idempotencyKey] the catalogue key of 07 section 3.1.
 */
class Posting(
    val type: CreditTxType,
    val idempotencyKey: String,
    val userId: Long,
    val amount: Long,
    val from: AccountRef,
    val to: AccountRef,
    val policy: PostingPolicy = PostingPolicy.NONE,
    val orderId: Long? = null,
    val refundId: Long? = null,
    val deliveryId: Long? = null,
    val actorUserId: Long? = null,
    val note: String? = null
) {
    init {
        require(amount > 0) { "a posting requests a positive amount: $amount" }
        require(idempotencyKey.length in 1..MAX_KEY_LENGTH) { "an idempotency key has 1..$MAX_KEY_LENGTH characters" }
        require(note == null || note.length <= MAX_NOTE_LENGTH) { "a note has at most $MAX_NOTE_LENGTH characters" }
        require(!(from is AccountRef.User && to is AccountRef.User && from.userId == to.userId)) { "a posting moves credits between two accounts" }
        require(from !is AccountRef.System || to !is AccountRef.System || from.key != to.key) { "a posting moves credits between two accounts" }
        require(policy == PostingPolicy.NONE || from is AccountRef.User) { "policy $policy applies to the balance of a user account on the from side" }
    }

    companion object {
        /** `market_credit_tx.idempotencyKey` is `VARCHAR(128)`, `note` is `VARCHAR(255)`. */
        const val MAX_KEY_LENGTH = 128
        const val MAX_NOTE_LENGTH = 255
    }
}

/** What [CreditService.post] answers: the transaction (the first one when [replayed]), and the balance of the user account involved after it. */
class PostResult(val tx: MarketCreditTx, val replayed: Boolean, val userBalance: Long)

/**
 * The database guard of 07 section 3.2 step 6 refused a debit: an account would have gone below zero although the policy let the posting through
 * (a capture or release without the hold behind it, a `HOLD` that is not backed). The transaction rolls back; this is a bug, not a user error.
 */
class LedgerGuardViolation(message: String) : IllegalStateException(message)

/**
 * The ledger and the order disagree (07 section 5): an order is captured, released or re-held while the holds the ledger carries for it are not what
 * its `creditAmount` says. Nothing is posted, the surrounding transaction rolls back, and the reconciler reports the order (O3b).
 */
class CreditLedgerMismatch(message: String) : IllegalStateException(message)

/** What the ledger holds for one order (07 section 5): the sums of its `HOLD` and `RELEASE` transactions and whether a `CAPTURE` exists. */
class OrderHolds(val held: Long, val released: Long, val releases: Int, val captured: Boolean) {
    /** `Σ HOLD - Σ RELEASE`: the credits that are on hold for the order right now (and the amount a capture takes). */
    val outstanding: Long get() = held - released

    /** The generation of the next `HOLD`: the number of `RELEASE` transactions so far (07 section 5, hold generation `k`). */
    val generation: Int get() = releases
}

/**
 * The credit ledger (07 sections 3, 5, 6.4 and 15): the only writer of `market_credit_account`, `market_credit_tx` and `market_credit_entry`.
 * Every method runs on the connection of the caller's `MarketDb.tx` and never opens a transaction; rows of the ledger are inserted, never
 * updated or deleted, and a mistake is corrected by a new posting.
 *
 * - [post] is the single posting algorithm: account rows locked in ascending id order in one statement, the idempotency key looked up after
 *   the lock (a replay returns the first transaction and writes nothing), the policy applied to the locked balance, two entries written, the
 *   balances moved with the `balance >= 0` guard of every account that may not go negative.
 * - The order integration is [CreditHolds] ([holds], O1) and [CreditSettlement] (capture at O2 / O4, release at O5 to O8, re-tender at `/pay`,
 *   re-hold at O9 accept). Capture and release read the order's outstanding hold from the ledger under the account lock and never trust
 *   `market_order.creditAmount` alone: `HOLD` is one pooled system account, so a posting from an order column that no hold backs would move
 *   other buyers' held credits (capture) or mint credits (release).
 *
 * Lock order (00 section 8.3): level 4. Callers that hold other rows lock them first; an order transition takes its accounts through
 * `Locks.forOrder` (payer, `HOLD`, `SPENT` ... in ascending id) and every statement here locks ascending ids again, which is a no-op for rows
 * that are already locked.
 */
class CreditService(
    private val clock: Clock,
    private val accounts: MarketCreditAccountDao,
    private val txs: MarketCreditTxDao,
    private val entries: MarketCreditEntryDao
) : CreditSettlement {
    // ----- balance and locks ---------------------------------------------------------------------------------------------

    /** The spendable balance of [userId] (credits x 100), `0` when the user has no account row: reading never creates one (07 section 3.4). */
    suspend fun balance(userId: Long, c: SqlClient): Long = accounts.getByUserId(userId, c)?.balance ?: 0L

    /**
     * Locks, in one `SELECT ... FOR UPDATE` in ascending id order, the accounts of [userIds] (a missing user account is created first, so the lock
     * covers it) and, with [withSystem], the system accounts `ISSUANCE`, `SPENT`, `HOLD` and `REVOKED` (07 section 3.3; `EXTERNAL` only belongs to
     * economy postings). A use case that posts several transactions in one `MarketDb.tx` calls this before its first posting, so that no
     * posting takes a lock below one it already holds.
     */
    suspend fun lockAccounts(userIds: Collection<Long>, withSystem: Boolean, c: SqlConnection) {
        val ids = LinkedHashSet<Long>()

        for (userId in userIds.toSortedSet()) ids += resolve(AccountRef.User(userId), c)

        if (withSystem) {
            for (key in LOCKED_SYSTEM) ids += resolve(AccountRef.System(key), c)
        }

        accounts.lockByIds(ids, c)
    }

    // ----- the posting ---------------------------------------------------------------------------------------------------

    /**
     * Writes [posting] (07 section 3.2). Throws [InsufficientCredits] for policy `FAIL` when the locked balance does not cover the amount,
     * [LedgerGuardViolation] when the database guard refuses a debit, `IllegalStateException` when the key was used for another type or user.
     * The result's [PostResult.tx] has `amount` = what was moved (less than requested only for `TAKE_AVAILABLE`, `0` writes no entries).
     */
    suspend fun post(posting: Posting, c: SqlConnection): PostResult {
        val fromId = resolve(posting.from, c)
        val toId = resolve(posting.to, c)
        val locked = accounts.lockByIds(listOf(fromId, toId), c).associateBy { it.id }
        val from = locked[fromId] ?: throw IllegalStateException("credit account $fromId disappeared under its lock")
        val to = locked[toId] ?: throw IllegalStateException("credit account $toId disappeared under its lock")

        // after the lock: whoever posted this key first has committed or is gone
        txs.getByIdempotencyKey(posting.idempotencyKey, c)?.let { return replay(it, posting, from, to) }

        val taken = when (posting.policy) {
            PostingPolicy.NONE -> CreditPolicy.Taken(posting.amount, 0L)

            PostingPolicy.FAIL -> {
                if (!CreditPolicy.covers(posting.amount, from.balance)) throw InsufficientCredits(MoneyUtil.toDecimal(maxOf(from.balance, 0L)))

                CreditPolicy.Taken(posting.amount, 0L)
            }

            PostingPolicy.TAKE_AVAILABLE -> CreditPolicy.takeAvailable(posting.amount, from.balance)

            PostingPolicy.ALLOW_DEBT -> CreditPolicy.allowDebt(posting.amount)
        }

        val now = clock.now()
        val txId = txs.add(
            MarketCreditTx(
                type = posting.type, idempotencyKey = posting.idempotencyKey, userId = posting.userId, amount = taken.taken, shortfall = taken.shortfall,
                orderId = posting.orderId, refundId = posting.refundId, deliveryId = posting.deliveryId, actorUserId = posting.actorUserId, note = posting.note,
                createdAt = now, updatedAt = now
            ),
            c
        )

        // 1062 on `uq_idem`: a caller that skipped the lock raced another poster of the same key; the winner's row is the answer
        if (txId == null) {
            val first = txs.getByIdempotencyKey(posting.idempotencyKey, c) ?: throw IllegalStateException("key ${posting.idempotencyKey} is taken but its row is not visible")

            return replay(first, posting, from, to)
        }

        var fromBalance = from.balance
        var toBalance = to.balance

        if (taken.taken > 0) {
            fromBalance = move(from, -taken.taken, guarded = guardedDebit(from, posting, isFrom = true), txId = txId, now = now, c = c)
            toBalance = move(to, taken.taken, guarded = guardedDebit(to, posting, isFrom = false), txId = txId, now = now, c = c)
        }

        val stored = txs.getById(txId, c) ?: throw IllegalStateException("credit tx $txId was just inserted")

        return PostResult(stored, false, userBalanceOf(posting, fromBalance, toBalance, c))
    }

    private suspend fun replay(existing: MarketCreditTx, posting: Posting, from: MarketCreditAccount, to: MarketCreditAccount): PostResult {
        check(existing.type == posting.type && existing.userId == posting.userId) {
            "idempotency key ${posting.idempotencyKey} belongs to a ${existing.type} of user ${existing.userId}, not to a ${posting.type} of user ${posting.userId}"
        }

        return PostResult(existing, true, if (posting.from is AccountRef.User) from.balance else if (posting.to is AccountRef.User) to.balance else 0L)
    }

    /** `ISSUANCE` and `EXTERNAL` may go either way, the user account of an `ALLOW_DEBT` posting may go negative; every other debit is guarded. */
    private fun guardedDebit(account: MarketCreditAccount, posting: Posting, isFrom: Boolean): Boolean = when {
        account.systemKey == CreditSystemKey.ISSUANCE || account.systemKey == CreditSystemKey.EXTERNAL -> false
        isFrom && posting.policy == PostingPolicy.ALLOW_DEBT -> false
        else -> true
    }

    private suspend fun move(account: MarketCreditAccount, delta: Long, guarded: Boolean, txId: Long, now: Long, c: SqlConnection): Long {
        if (accounts.addToBalance(account.id, delta, guarded, c) == 0) {
            logger.error("credit ledger guard: account {} ({}) refused {} on tx {}", account.id, account.systemKey ?: "USER ${account.userId}", delta, txId)

            throw LedgerGuardViolation("credit account ${account.id} would go below zero by $delta (balance ${account.balance})")
        }

        val after = account.balance + delta

        entries.add(MarketCreditEntry(txId = txId, accountId = account.id, amount = delta, balanceAfter = after, createdAt = now, updatedAt = now), c)

        return after
    }

    private suspend fun userBalanceOf(posting: Posting, fromBalance: Long, toBalance: Long, c: SqlClient): Long = when {
        posting.from is AccountRef.User -> fromBalance
        posting.to is AccountRef.User -> toBalance
        else -> balance(posting.userId, c)
    }

    private suspend fun resolve(ref: AccountRef, c: SqlClient): Long = when (ref) {
        is AccountRef.User -> {
            accounts.insertUserAccountIgnore(ref.userId, c)

            (accounts.getByUserId(ref.userId, c) ?: throw IllegalStateException("the credit account of user ${ref.userId} was not created")).id
        }

        is AccountRef.System -> (accounts.getBySystemKey(ref.key, c) ?: throw IllegalStateException("system credit account ${ref.key} is missing")).id
    }

    // ----- grant and revoke ----------------------------------------------------------------------------------------------

    /** `GRANT` `ISSUANCE` -> the user (07 section 3.1); [key] is the full idempotency key (`panel:<Idempotency-Key>`, `mc:<serverId>:<operationId>`). */
    suspend fun grant(userId: Long, amount: Long, key: String, actor: Long?, note: String, c: SqlConnection): PostResult =
        post(
            Posting(CreditTxType.GRANT, key, userId, amount, AccountRef.System(CreditSystemKey.ISSUANCE), AccountRef.User(userId), actorUserId = actor, note = note),
            c
        )

    /**
     * `REVOKE` the user -> `REVOKED`, policy `TAKE_AVAILABLE`: takes what is spendable (credits on hold are never touched) and records the rest
     * as the transaction's `shortfall`; nothing to take writes a transaction with no entries.
     */
    suspend fun revoke(userId: Long, amount: Long, key: String, actor: Long?, note: String, c: SqlConnection): PostResult =
        post(
            Posting(
                CreditTxType.REVOKE, key, userId, amount, AccountRef.User(userId), AccountRef.System(CreditSystemKey.REVOKED), PostingPolicy.TAKE_AVAILABLE,
                actorUserId = actor, note = note
            ),
            c
        )

    // ----- the holds of an order -----------------------------------------------------------------------------------------

    /** What the ledger carries for [orderId] now (07 section 5): read it under the payer's and the `HOLD` account lock. */
    suspend fun holdsOf(orderId: Long, c: SqlClient): OrderHolds {
        var held = 0L
        var released = 0L
        var releases = 0
        var captured = false

        for (tx in txs.getByOrderId(orderId, c)) {
            when (tx.type) {
                CreditTxType.HOLD -> held += tx.amount

                CreditTxType.RELEASE -> {
                    released += tx.amount
                    releases++
                }

                CreditTxType.CAPTURE -> captured = true

                else -> Unit
            }
        }

        return OrderHolds(held, released, releases, captured)
    }

    /** The credits on hold for [orderId], `Σ HOLD - Σ RELEASE` (07 section 5). */
    suspend fun outstanding(orderId: Long, c: SqlClient): Long = holdsOf(orderId, c).outstanding

    /** `HOLD(order.creditAmount)` of hold generation [generation] (key `order:<id>:hold`, `order:<id>:hold:<k>` for `k >= 1`), policy `FAIL`; 07 section 5 C1, C2, C5. */
    suspend fun hold(order: MarketOrder, generation: Int, c: SqlConnection): PostResult =
        postHold(order.id, checkNotNull(order.userId) { "a credit part needs a logged-in payer" }, order.creditAmount, holdKey(order.id, generation), c)

    /** `CAPTURE` of the outstanding hold of [order] (07 section 5 C3); the outstanding hold must be exactly `order.creditAmount`, else [CreditLedgerMismatch]. */
    suspend fun capture(order: MarketOrder, c: SqlConnection): PostResult {
        val userId = checkNotNull(order.userId) { "a credit part needs a logged-in payer" }

        lockOrderAccounts(userId, c, spent = true)

        val holds = holdsOf(order.id, c)

        // a replay (the same transition run twice) finds the first capture
        if (holds.captured) {
            return post(captureOf(order, userId, holds.outstanding), c)
        }

        if (order.creditAmount <= 0 || holds.outstanding != order.creditAmount) {
            logger.error(
                "order {}: cannot capture, the ledger holds {} for it but its credit part is {} (07 section 5 C3)", order.id, holds.outstanding, order.creditAmount
            )

            throw CreditLedgerMismatch("order ${order.id}: outstanding hold ${holds.outstanding} != credit part ${order.creditAmount}")
        }

        return post(captureOf(order, userId, holds.outstanding), c)
    }

    private fun captureOf(order: MarketOrder, userId: Long, amount: Long) = Posting(
        CreditTxType.CAPTURE, "order:${order.id}:capture", userId, amount, AccountRef.System(CreditSystemKey.HOLD), AccountRef.System(CreditSystemKey.SPENT), orderId = order.id
    )

    /**
     * `RELEASE` of the outstanding hold of [order] under generation [generation] (key `order:<id>:release`, `order:<id>:release:<k>` for `k >= 1`; 07
     * section 5 C4). The buyer's own held credits go back even when they differ from `order.creditAmount` (ERROR log; the reconciler reports it);
     * `null` when nothing is on hold (a release that already ran, or an order that never held); a captured hold is never released
     * ([CreditLedgerMismatch]): the credits are in `SPENT`.
     */
    suspend fun release(order: MarketOrder, generation: Int, c: SqlConnection): PostResult? {
        val userId = checkNotNull(order.userId) { "a credit part needs a logged-in payer" }

        lockOrderAccounts(userId, c, spent = false)

        return postRelease(order, userId, holdsOf(order.id, c), generation, c)
    }

    private suspend fun postRelease(order: MarketOrder, userId: Long, holds: OrderHolds, generation: Int, c: SqlConnection): PostResult? {
        if (holds.captured) {
            logger.error("order {}: its hold was captured, it cannot be released", order.id)

            throw CreditLedgerMismatch("order ${order.id}: the hold is captured, it cannot be released")
        }

        // a closing transition is never blocked by an order whose columns and ledger disagree: what is on hold goes back to the buyer
        val replay = holds.outstanding == 0L && holds.releases > 0

        if (holds.outstanding != order.creditAmount && !replay) {
            logger.error(
                "order {}: releasing {} although its credit part is {} (07 section 5 C4, the reconciler reports it)", order.id, holds.outstanding, order.creditAmount
            )
        }

        if (holds.outstanding <= 0) return null

        val key = releaseKey(order.id, generation)
        val result = post(
            Posting(CreditTxType.RELEASE, key, userId, holds.outstanding, AccountRef.System(CreditSystemKey.HOLD), AccountRef.User(userId), orderId = order.id), c
        )

        check(result.tx.amount == holds.outstanding) { "order ${order.id}: release key $key replayed an amount of ${result.tx.amount}, not ${holds.outstanding}" }

        return result
    }

    private suspend fun postHold(orderId: Long, userId: Long, credits: Long, key: String, c: SqlConnection): PostResult {
        require(credits > 0) { "a hold is for a positive amount" }

        val result = post(
            Posting(CreditTxType.HOLD, key, userId, credits, AccountRef.User(userId), AccountRef.System(CreditSystemKey.HOLD), PostingPolicy.FAIL, orderId = orderId), c
        )

        // a replayed key with another amount would leave the order without the hold its columns promise
        check(result.tx.amount == credits) { "order $orderId: hold key $key replayed an amount of ${result.tx.amount}, not $credits" }

        return result
    }

    private suspend fun lockOrderAccounts(userId: Long, c: SqlConnection, spent: Boolean) {
        val ids = sortedSetOf(resolve(AccountRef.User(userId), c), resolve(AccountRef.System(CreditSystemKey.HOLD), c))

        if (spent) ids += resolve(AccountRef.System(CreditSystemKey.SPENT), c)

        accounts.lockByIds(ids, c)
    }

    // ----- CreditHolds (O1) ----------------------------------------------------------------------------------------------

    /** The hold of checkout (06 section 5.3 B10, 07 section 5 C1): `HOLD` under `order:<id>:hold`, inside the order transaction, `InsufficientCredits` when the balance is short. */
    val checkoutHolds: CreditHolds = CreditHolds { conn, userId, credits, orderId, key -> postHold(orderId, userId, credits, key, conn) }

    // ----- CreditSettlement (O2, O4, O5 to O8, /pay, O9 accept) -----------------------------------------------------------

    override suspend fun capture(conn: SqlConnection, order: MarketOrder) {
        capture(order, conn)
    }

    override suspend fun release(conn: SqlConnection, order: MarketOrder) {
        val userId = checkNotNull(order.userId) { "a credit part needs a logged-in payer" }

        lockOrderAccounts(userId, conn, spent = false)

        val state = holdsOf(order.id, conn)

        postRelease(order, userId, state, state.generation, conn)
    }

    /**
     * `/pay` (07 section 6.4, C2): the credit part becomes [newCredits]. The old part is released first when it was above 0 (generation `k`), then the
     * new part is held under the generation the releases leave (`k + 1`, or `k` when nothing was released). `InsufficientCredits` rolls the whole
     * re-tender back; [order] still carries the old part.
     */
    override suspend fun retender(conn: SqlConnection, order: MarketOrder, newCredits: Long) {
        val userId = checkNotNull(order.userId) { "a credit part needs a logged-in payer" }

        lockOrderAccounts(userId, conn, spent = false)

        var state = holdsOf(order.id, conn)

        if (order.creditAmount > 0) {
            postRelease(order, userId, state, state.generation, conn)

            state = holdsOf(order.id, conn)
        }

        if (state.outstanding != 0L || state.captured) {
            logger.error("order {}: cannot re-tender, {} is still on hold (captured: {})", order.id, state.outstanding, state.captured)

            throw CreditLedgerMismatch("order ${order.id}: ${state.outstanding} is still on hold after the release (captured: ${state.captured}), it cannot be re-tendered")
        }

        if (newCredits > 0) postHold(order.id, userId, newCredits, holdKey(order.id, state.generation), conn)
    }

    /** O4 after O9 (07 section 5 C5): [credits] are held again under the next generation before they are captured; `InsufficientCredits` when the balance is short. */
    override suspend fun rehold(conn: SqlConnection, order: MarketOrder, credits: Long) {
        val userId = checkNotNull(order.userId) { "a credit part needs a logged-in payer" }

        lockOrderAccounts(userId, conn, spent = true)

        val state = holdsOf(order.id, conn)

        if (state.outstanding != 0L || state.captured) {
            logger.error("order {}: cannot hold again, {} is on hold already (captured: {})", order.id, state.outstanding, state.captured)

            throw CreditLedgerMismatch("order ${order.id}: ${state.outstanding} is on hold already (captured: ${state.captured}), it cannot be held again")
        }

        postHold(order.id, userId, credits, holdKey(order.id, state.generation), conn)
    }

    companion object {
        private val logger = LoggerFactory.getLogger(CreditService::class.java)

        /** The system accounts an order transition or a multi-posting use case locks (07 section 3.3). */
        private val LOCKED_SYSTEM = listOf(CreditSystemKey.ISSUANCE, CreditSystemKey.SPENT, CreditSystemKey.HOLD, CreditSystemKey.REVOKED)

        /** `order:<id>:hold` for generation 0, `order:<id>:hold:<k>` for `k >= 1` (07 section 3.1). */
        fun holdKey(orderId: Long, generation: Int): String = if (generation <= 0) "order:$orderId:hold" else "order:$orderId:hold:$generation"

        /** `order:<id>:release` for generation 0, `order:<id>:release:<k>` for `k >= 1` (07 section 3.1). */
        fun releaseKey(orderId: Long, generation: Int): String = if (generation <= 0) "order:$orderId:release" else "order:$orderId:release:$generation"
    }
}

/**
 * The first half of C3 (07 section 5, 06 section 7.3): an order whose credit part is not backed by exactly that much hold in the ledger is not
 * completed by a payment. It runs under the `COMMIT` locks right before O2; the order goes to `REVIEW (OTHER)` with the money recorded on it,
 * nothing is captured (no other buyer's hold is touched), and the ERROR log and the reconciler (O3b) name the order. A completed order without its
 * capture can therefore not exist; the accept of the review would fail the same way in [CreditService.capture] until a human repairs the ledger.
 */
class CreditHoldGuard(private val credits: CreditService) : PaidGuard {
    override suspend fun divert(conn: SqlConnection, locked: LockedOrder, order: MarketOrder): PaidDiversion? {
        if (order.creditAmount <= 0) return null

        val outstanding = credits.outstanding(order.id, conn)

        if (outstanding == order.creditAmount) return null

        LoggerFactory.getLogger(CreditHoldGuard::class.java)
            .error("order {}: its credit part is {} but the ledger holds {} for it, the payment goes to review (07 section 5 C3)", order.id, order.creditAmount, outstanding)

        return PaidDiversion(ReviewReason.OTHER, NOTE)
    }

    companion object {
        const val NOTE = "credit hold mismatch"
    }
}
