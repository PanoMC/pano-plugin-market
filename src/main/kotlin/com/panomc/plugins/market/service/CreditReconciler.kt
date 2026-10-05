package com.panomc.plugins.market.service

import com.panomc.plugins.market.core.time.Clock
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory

/** One violated invariant of the credit ledger (07 section 16.2): [invariant] is `L1` ... `P1`, [entity] the row kind, [id] its id, [expected] / [actual] a short text. */
class CreditProblem(val invariant: String, val entity: String, val id: Long, val expected: String, val actual: String) {
    internal val key: String get() = "$invariant:$entity:$id"

    override fun toString(): String = "$invariant $entity#$id expected $expected, found $actual"
}

/** The result of one self-check (07 section 16.2): [problems] holds at most [CreditReconciler.MAX_PROBLEMS] entries, [ok] is `true` when none was found. */
class CreditCheckResult(val ok: Boolean, val checkedAt: Long, val problems: List<CreditProblem>, val truncated: Boolean = false)

/**
 * The self-check of the credit ledger (07 section 16): L1 to L7 on the transactions, entries and accounts, O1 to O8 on the orders and P1 on the creator
 * payouts. Read-only, no locks: every statement is its own consistent read, so a check that fails is evaluated once more after [recheckDelayMs] and only a
 * problem that fails twice is reported (a transaction in flight cannot raise a false alarm). There is no repair and no shutdown of credit spending: a
 * failure is a bug and the rows are the evidence; the fix is a reviewed grant or revoke, never an edit of ledger rows.
 *
 * Scope (07 section 16.2): `L5`, `L6`, `O1` on every run; `L1` to `L4` and `L7` for the transactions with an id above the last verified one (all of them
 * on the first run, in id batches of [batchSize]); `O2` to `O8` and `P1` for the orders and payouts updated since the previous run, everything again once per
 * 24 hours. The cursor of the transaction checks only moves past a run that found nothing, so a persistent problem stays visible.
 *
 * `HousekeepingJob` calls [run] 60 s after the plugin starts and every 6 h, `GET /api/panel/market/health?recheck=credits` calls it on demand with
 * `full = true`; the last result is [last] (`health.credits`).
 */
class CreditReconciler(
    private val clock: Clock,
    private val prefix: String,
    private val client: suspend () -> SqlClient,
    private val recheckDelayMs: Long = RECHECK_DELAY_MS,
    private val batchSize: Int = BATCH_SIZE
) {
    private val mutex = Mutex()

    @Volatile
    var last: CreditCheckResult? = null
        private set

    private var verifiedTxId = 0L
    private var lastOrderScanAt = 0L
    private var lastFullScanAt = 0L

    private fun t(name: String) = "`$prefix$name`"

    /** Runs the checks; [full] forces the complete scan of every check. Concurrent calls are serialised. */
    suspend fun run(full: Boolean = false): CreditCheckResult = mutex.withLock {
        val startedAt = clock.now()
        val everything = full || lastFullScanAt == 0L || startedAt - lastFullScanAt >= FULL_SCAN_MS
        val sinceTx = if (everything) 0L else verifiedTxId
        val sinceOrders = if (everything) 0L else maxOf(0L, lastOrderScanAt - ORDER_SCAN_MARGIN_MS)
        val first = scan(sinceTx, sinceOrders)
        var problems = first.problems

        if (problems.isNotEmpty() && recheckDelayMs >= 0) {
            if (recheckDelayMs > 0) delay(recheckDelayMs)

            val again = scan(sinceTx, sinceOrders).problems.map { it.key }.toSet()

            problems = problems.filter { it.key in again }
        }

        if (problems.isEmpty()) verifiedTxId = maxOf(verifiedTxId, first.maxTxId)

        lastOrderScanAt = startedAt

        if (everything) lastFullScanAt = startedAt

        val shown = problems.take(MAX_PROBLEMS)
        val result = CreditCheckResult(problems.isEmpty(), clock.now(), shown, problems.size > shown.size)

        for (problem in shown) logger.error("credit ledger self-check: {}", problem)

        last = result

        result
    }

    private class Scan(val problems: List<CreditProblem>, val maxTxId: Long)

    private suspend fun scan(sinceTx: Long, sinceOrders: Long): Scan {
        val sql = client()
        val found = ArrayList<CreditProblem>()
        val maxTxId = sql.query("SELECT COALESCE(MAX(`id`), 0) FROM ${t("market_credit_tx")}").execute().coAwait().first().getLong(0)

        // every run
        accountsAndHolds(sql, found)

        // transactions, entries and accounts above the last verified id, in id batches
        var from = sinceTx

        while (from < maxTxId) {
            val to = minOf(maxTxId, from + batchSize)

            entriesOfTransactions(sql, from, to, found)
            accountsOfTransactions(sql, from, to, found)

            from = to
        }

        // orders and payouts updated since the previous run
        ordersOf(sql, sinceOrders, found)
        payoutsOf(sql, sinceOrders, found)

        return Scan(found.distinctBy { it.key }, maxTxId)
    }

    private suspend fun rows(sql: SqlClient, statement: String, vararg args: Any?): List<Row> =
        sql.preparedQuery(statement).execute(Tuple.from(args.toList())).coAwait().toList()

    // ----- L5, L6, O1 -------------------------------------------------------------------------------------------------

    private suspend fun accountsAndHolds(sql: SqlClient, found: MutableList<CreditProblem>) {
        // L5: the ledger sums to zero
        val total = rows(sql, "SELECT COALESCE(SUM(`balance`), 0) AS s FROM ${t("market_credit_account")}").first().getLong("s")

        if (total != 0L) found += CreditProblem("L5", "ledger", 0, "0", total.toString())

        // L6: HOLD, SPENT, REVOKED >= 0; ISSUANCE <= 0; a user balance >= 0 unless a dispute clawback allows a debt of at most that much
        val suspicious = rows(
            sql,
            "SELECT `id`, `type`, `systemKey`, `userId`, `balance` FROM ${t("market_credit_account")} " +
                "WHERE `balance` < 0 OR (`systemKey` = 'ISSUANCE' AND `balance` > 0)"
        )

        for (row in suspicious) {
            val id = row.getLong("id")
            val balance = row.getLong("balance")
            val key = row.getString("systemKey")

            when {
                key == "ISSUANCE" -> if (balance > 0) found += CreditProblem("L6", "account", id, "<= 0 (ISSUANCE)", balance.toString())

                key == "EXTERNAL" -> Unit

                key != null -> if (balance < 0) found += CreditProblem("L6", "account", id, ">= 0 ($key)", balance.toString())

                else -> {
                    val allowed = rows(
                        sql,
                        "SELECT COALESCE(SUM(`amount`), 0) AS s FROM ${t("market_credit_tx")} WHERE `userId` = ? AND `idempotencyKey` LIKE 'dispute:%'",
                        row.getLong("userId")
                    ).first().getLong("s")

                    if (balance < -allowed) found += CreditProblem("L6", "account", id, ">= ${-allowed} (user ${row.getLong("userId")})", balance.toString())
                }
            }
        }

        // O1: HOLD = what the open orders hold
        val hold = rows(sql, "SELECT `id`, `balance` FROM ${t("market_credit_account")} WHERE `systemKey` = 'HOLD'").firstOrNull()

        if (hold != null) {
            val expected = rows(sql, "SELECT COALESCE(SUM(`creditAmount`), 0) AS s FROM ${t("market_order")} WHERE `reservationState` = 'HELD'").first().getLong("s")

            if (hold.getLong("balance") != expected) found += CreditProblem("O1", "account", hold.getLong("id"), expected.toString(), hold.getLong("balance").toString())
        }
    }

    // ----- L1, L2, L4, L7 ---------------------------------------------------------------------------------------------

    private suspend fun entriesOfTransactions(sql: SqlClient, from: Long, to: Long, found: MutableList<CreditProblem>) {
        // L1: two entries that sum to zero with -amount / +amount, or no entry and amount 0
        for (row in rows(
            sql,
            "SELECT t.`id`, t.`amount`, COUNT(e.`id`) AS n, COALESCE(SUM(e.`amount`), 0) AS s, COALESCE(MAX(e.`amount`), 0) AS mx, COALESCE(MIN(e.`amount`), 0) AS mn " +
                "FROM ${t("market_credit_tx")} t LEFT JOIN ${t("market_credit_entry")} e ON e.`txId` = t.`id` " +
                "WHERE t.`id` > ? AND t.`id` <= ? GROUP BY t.`id`, t.`amount`",
            from, to
        )) {
            val amount = row.getLong("amount")
            val n = row.getLong("n")
            val ok = (n == 2L && row.getLong("s") == 0L && row.getLong("mx") == amount && row.getLong("mn") == -amount && amount > 0) || (n == 0L && amount == 0L)

            if (!ok) {
                found += CreditProblem(
                    "L1", "tx", row.getLong("id"), "2 entries of -$amount / +$amount, or none for 0",
                    "$n entries, sum ${row.getLong("s")}, min ${row.getLong("mn")}, max ${row.getLong("mx")}"
                )
            }
        }

        // L2: amount and shortfall are never negative, a shortfall exists only on the types that take what is there
        for (row in rows(
            sql,
            "SELECT `id`, `type`, `amount`, `shortfall` FROM ${t("market_credit_tx")} WHERE `id` > ? AND `id` <= ? AND " +
                "(`amount` < 0 OR `shortfall` < 0 OR (`shortfall` > 0 AND `type` NOT IN ('REVOKE', 'CASHBACK_REVERSAL', 'ACTION_REVERSAL')))",
            from, to
        )) {
            found += CreditProblem("L2", "tx", row.getLong("id"), "amount >= 0, shortfall >= 0 only on a reversal", "${row.getString("type")} amount ${row.getLong("amount")} shortfall ${row.getLong("shortfall")}")
        }

        // L4: the balanceAfter of an account's entries chains
        for (row in rows(
            sql,
            "SELECT x.`id`, x.`accountId`, x.`amount`, x.`balanceAfter`, x.`prev` FROM (" +
                "SELECT e.`id`, e.`accountId`, e.`amount`, e.`balanceAfter`, COALESCE((SELECT p.`balanceAfter` FROM ${t("market_credit_entry")} p " +
                "WHERE p.`accountId` = e.`accountId` AND p.`id` < e.`id` ORDER BY p.`id` DESC LIMIT 1), 0) AS prev " +
                "FROM ${t("market_credit_entry")} e WHERE e.`txId` > ? AND e.`txId` <= ?) x WHERE x.`balanceAfter` <> x.`prev` + x.`amount`",
            from, to
        )) {
            found += CreditProblem(
                "L4", "entry", row.getLong("id"), "${row.getLong("prev") + row.getLong("amount")} after ${row.getLong("amount")} on ${row.getLong("prev")}",
                row.getLong("balanceAfter").toString()
            )
        }

        // L7: the accounts of a transaction match the catalogue of its type (07 section 3.1)
        for (row in rows(
            sql,
            "SELECT t.`id`, t.`type`, t.`userId`, fa.`type` AS fType, fa.`systemKey` AS fKey, fa.`userId` AS fUser, " +
                "ta.`type` AS tType, ta.`systemKey` AS tKey, ta.`userId` AS tUser FROM ${t("market_credit_tx")} t " +
                "JOIN ${t("market_credit_entry")} fe ON fe.`txId` = t.`id` AND fe.`amount` < 0 JOIN ${t("market_credit_account")} fa ON fa.`id` = fe.`accountId` " +
                "JOIN ${t("market_credit_entry")} te ON te.`txId` = t.`id` AND te.`amount` > 0 JOIN ${t("market_credit_account")} ta ON ta.`id` = te.`accountId` " +
                "WHERE t.`id` > ? AND t.`id` <= ?",
            from, to
        )) {
            val type = row.getString("type")
            val expected = CATALOGUE[type] ?: continue
            val userId = row.getLong("userId")
            val fromOk = side(expected.first, row.getString("fType"), row.getString("fKey"), row.getLong("fUser"), userId)
            val toOk = side(expected.second, row.getString("tType"), row.getString("tKey"), row.getLong("tUser"), userId)

            if (!fromOk || !toOk) {
                found += CreditProblem(
                    "L7", "tx", row.getLong("id"), "$type ${expected.first} -> ${expected.second}",
                    "${describe(row.getString("fType"), row.getString("fKey"), row.getLong("fUser"))} -> ${describe(row.getString("tType"), row.getString("tKey"), row.getLong("tUser"))}"
                )
            }
        }
    }

    private fun side(expected: String, type: String, systemKey: String?, accountUser: Long?, txUser: Long?): Boolean =
        if (expected == USER) type == "USER" && accountUser != null && accountUser == txUser else type == "SYSTEM" && systemKey == expected

    private fun describe(type: String, systemKey: String?, userId: Long?): String = if (type == "USER") "user $userId" else systemKey ?: "?"

    // ----- L3 ---------------------------------------------------------------------------------------------------------

    private suspend fun accountsOfTransactions(sql: SqlClient, from: Long, to: Long, found: MutableList<CreditProblem>) {
        // L3: the cached balance is the sum of the entries and the newest entry's balanceAfter, for every account the batch touched
        for (row in rows(
            sql,
            "SELECT a.`id`, a.`balance`, COALESCE((SELECT SUM(e.`amount`) FROM ${t("market_credit_entry")} e WHERE e.`accountId` = a.`id`), 0) AS s, " +
                "(SELECT e.`balanceAfter` FROM ${t("market_credit_entry")} e WHERE e.`accountId` = a.`id` ORDER BY e.`id` DESC LIMIT 1) AS newest " +
                "FROM ${t("market_credit_account")} a WHERE a.`id` IN (SELECT DISTINCT e2.`accountId` FROM ${t("market_credit_entry")} e2 WHERE e2.`txId` > ? AND e2.`txId` <= ?)",
            from, to
        )) {
            val balance = row.getLong("balance")
            val sum = row.getLong("s")
            val newest = row.getLong("newest")

            if (balance != sum || (newest != null && newest != balance)) {
                found += CreditProblem("L3", "account", row.getLong("id"), "balance = sum of entries = newest balanceAfter", "balance $balance, sum $sum, newest ${newest ?: "none"}")
            }
        }
    }

    // ----- O2 ... O8 --------------------------------------------------------------------------------------------------

    private suspend fun ordersOf(sql: SqlClient, since: Long, found: MutableList<CreditProblem>) {
        val tx = t("market_credit_tx")
        val orders = t("market_order")

        // O2 (captured): a COMMITTED order with a credit part has exactly one CAPTURE of exactly that amount
        for (row in rows(
            sql,
            "SELECT x.`id`, x.`creditAmount`, x.`n`, x.`s` FROM (SELECT o.`id`, o.`creditAmount`, " +
                "(SELECT COUNT(*) FROM $tx c WHERE c.`orderId` = o.`id` AND c.`type` = 'CAPTURE') AS n, " +
                "(SELECT COALESCE(SUM(c.`amount`), 0) FROM $tx c WHERE c.`orderId` = o.`id` AND c.`type` = 'CAPTURE') AS s " +
                "FROM $orders o WHERE o.`reservationState` = 'COMMITTED' AND o.`creditAmount` > 0 AND o.`updatedAt` >= ?) x WHERE x.`n` <> 1 OR x.`s` <> x.`creditAmount`",
            since
        )) {
            found += CreditProblem("O2", "order", row.getLong("id"), "one CAPTURE of ${row.getLong("creditAmount")}", "${row.getLong("n")} CAPTURE, sum ${row.getLong("s")}")
        }

        // O2 (no credit part): nothing was captured
        for (row in rows(
            sql,
            "SELECT o.`id` FROM $orders o WHERE o.`creditAmount` = 0 AND o.`updatedAt` >= ? AND EXISTS (SELECT 1 FROM $tx c WHERE c.`orderId` = o.`id` AND c.`type` = 'CAPTURE')",
            since
        )) {
            found += CreditProblem("O2", "order", row.getLong("id"), "no CAPTURE without a credit part", "a CAPTURE exists")
        }

        // O3 and O3b: #HOLD - #RELEASE is 1 for a HELD or COMMITTED order with a credit part, 0 otherwise; a HELD order holds exactly its credit part
        for (row in rows(
            sql,
            "SELECT o.`id`, o.`reservationState` AS st, o.`creditAmount` AS ca, o.`source` AS src, " +
                "(SELECT COUNT(*) FROM $tx c WHERE c.`orderId` = o.`id` AND c.`type` = 'HOLD') AS nh, " +
                "(SELECT COUNT(*) FROM $tx c WHERE c.`orderId` = o.`id` AND c.`type` = 'RELEASE') AS nr, " +
                "(SELECT COALESCE(SUM(c.`amount`), 0) FROM $tx c WHERE c.`orderId` = o.`id` AND c.`type` = 'HOLD') AS h, " +
                "(SELECT COALESCE(SUM(c.`amount`), 0) FROM $tx c WHERE c.`orderId` = o.`id` AND c.`type` = 'RELEASE') AS r " +
                "FROM $orders o WHERE o.`updatedAt` >= ? AND (o.`creditAmount` > 0 OR EXISTS (SELECT 1 FROM $tx c WHERE c.`orderId` = o.`id` AND c.`type` IN ('HOLD', 'RELEASE')))",
            since
        )) {
            val id = row.getLong("id")
            val state = row.getString("st")
            val credits = row.getLong("ca")
            val holds = row.getLong("nh")
            val releases = row.getLong("nr")
            val open = state == "HELD" || state == "COMMITTED"
            val expectedOpen = if (open && credits > 0) 1L else 0L

            if (holds - releases != expectedOpen) {
                found += CreditProblem("O3", "order", id, "#HOLD - #RELEASE = $expectedOpen ($state, credit part $credits)", "$holds HOLD, $releases RELEASE")
            }

            if (state == "HELD" && credits > 0 && row.getLong("h") - row.getLong("r") != credits) {
                found += CreditProblem("O3b", "order", id, "an unreleased HOLD of $credits", "${row.getLong("h") - row.getLong("r")}")
            }

            if (credits > 0 && holds == 0L && row.getString("src") != "LEGACY") {
                found += CreditProblem("O3b", "order", id, "a HOLD for the credit part $credits", "no HOLD")
            }
        }

        // O4: refundedCreditAmount = the REFUND transactions = the SUCCEEDED refunds' credit parts, never above the credit part
        for (row in rows(
            sql,
            "SELECT x.`id`, x.`rc`, x.`ca`, x.`txs`, x.`rs` FROM (SELECT o.`id`, o.`refundedCreditAmount` AS rc, o.`creditAmount` AS ca, " +
                "(SELECT COALESCE(SUM(c.`amount`), 0) FROM $tx c WHERE c.`orderId` = o.`id` AND c.`type` = 'REFUND') AS txs, " +
                "(SELECT COALESCE(SUM(r.`creditAmount`), 0) FROM ${t("market_refund")} r WHERE r.`orderId` = o.`id` AND r.`status` = 'SUCCEEDED') AS rs " +
                "FROM $orders o WHERE o.`updatedAt` >= ?) x WHERE x.`rc` <> x.`txs` OR x.`txs` <> x.`rs` OR x.`rc` > x.`ca`",
            since
        )) {
            found += CreditProblem(
                "O4", "order", row.getLong("id"), "refundedCreditAmount = REFUND txs = succeeded refunds <= ${row.getLong("ca")}",
                "refunded ${row.getLong("rc")}, REFUND txs ${row.getLong("txs")}, refunds ${row.getLong("rs")}"
            )
        }

        // O5: gatewayAmount + creditValue = totalPrice, and a credit part exists exactly when it has a value
        for (row in rows(
            sql,
            "SELECT o.`id`, o.`totalPrice`, o.`gatewayAmount`, o.`creditValue`, o.`creditAmount` FROM $orders o WHERE o.`pricingMode` = 'MARKET' AND o.`source` <> 'LEGACY' " +
                "AND o.`updatedAt` >= ? AND (o.`gatewayAmount` + o.`creditValue` <> o.`totalPrice` OR (o.`creditAmount` > 0) <> (o.`creditValue` > 0))",
            since
        )) {
            found += CreditProblem(
                "O5", "order", row.getLong("id"), "gateway + credit value = total, credit part <=> credit value",
                "gateway ${row.getLong("gatewayAmount")} + credit value ${row.getLong("creditValue")} vs total ${row.getLong("totalPrice")}, credit part ${row.getLong("creditAmount")}"
            )
        }

        // O6: a paid item that grants credits to an existing user has exactly one TOPUP / GIFT of that amount
        for (row in rows(
            sql,
            "SELECT x.`id`, x.`oid`, x.`ca`, x.`n`, x.`s` FROM (SELECT i.`id`, o.`id` AS oid, i.`creditAmount` AS ca, " +
                "(SELECT COUNT(*) FROM $tx c WHERE c.`idempotencyKey` IN (CONCAT('orderitem:', i.`id`, ':topup'), CONCAT('orderitem:', i.`id`, ':gift'))) AS n, " +
                "(SELECT COALESCE(SUM(c.`amount`), 0) FROM $tx c WHERE c.`idempotencyKey` IN (CONCAT('orderitem:', i.`id`, ':topup'), CONCAT('orderitem:', i.`id`, ':gift'))) AS s " +
                "FROM ${t("market_order_item")} i JOIN $orders o ON o.`id` = i.`orderId` WHERE i.`creditAmount` > 0 AND o.`updatedAt` >= ? " +
                "AND o.`status` IN ('COMPLETED', 'PARTIALLY_REFUNDED', 'REFUNDED', 'CHARGEBACK') AND COALESCE(o.`recipientUserId`, o.`userId`) IS NOT NULL) x " +
                "WHERE x.`n` <> 1 OR x.`s` <> x.`ca`",
            since
        )) {
            found += CreditProblem("O6", "orderItem", row.getLong("id"), "one TOPUP / GIFT of ${row.getLong("ca")} (order ${row.getLong("oid")})", "${row.getLong("n")} tx, sum ${row.getLong("s")}")
        }

        // O7: at most one CASHBACK per order, its reversals (amount + shortfall) never above it
        for (row in rows(
            sql,
            "SELECT x.`id`, x.`n`, x.`cb`, x.`rev` FROM (SELECT o.`id`, " +
                "(SELECT COUNT(*) FROM $tx c WHERE c.`orderId` = o.`id` AND c.`type` = 'CASHBACK') AS n, " +
                "(SELECT COALESCE(SUM(c.`amount`), 0) FROM $tx c WHERE c.`orderId` = o.`id` AND c.`type` = 'CASHBACK') AS cb, " +
                "(SELECT COALESCE(SUM(c.`amount` + c.`shortfall`), 0) FROM $tx c WHERE c.`orderId` = o.`id` AND c.`type` = 'CASHBACK_REVERSAL') AS rev " +
                "FROM $orders o WHERE o.`updatedAt` >= ? AND EXISTS (SELECT 1 FROM $tx c WHERE c.`orderId` = o.`id` AND c.`type` IN ('CASHBACK', 'CASHBACK_REVERSAL'))) x " +
                "WHERE x.`n` > 1 OR x.`rev` > x.`cb`",
            since
        )) {
            found += CreditProblem("O7", "order", row.getLong("id"), "at most one CASHBACK, reversals <= cashback", "${row.getLong("n")} CASHBACK of ${row.getLong("cb")}, reversed ${row.getLong("rev")}")
        }

        // O8: the clawbacks of one credit-granting item (amount + shortfall) never exceed what it granted
        for (row in rows(
            sql,
            "SELECT x.`id`, x.`ca`, x.`taken` FROM (SELECT i.`id`, i.`creditAmount` AS ca, " +
                "(SELECT COALESCE(SUM(c.`amount` + c.`shortfall`), 0) FROM $tx c WHERE c.`type` = 'REVOKE' AND c.`idempotencyKey` LIKE CONCAT('%:clawback:', i.`id`)) AS taken " +
                "FROM ${t("market_order_item")} i JOIN $orders o ON o.`id` = i.`orderId` WHERE i.`creditAmount` > 0 AND o.`updatedAt` >= ?) x WHERE x.`taken` > x.`ca`",
            since
        )) {
            found += CreditProblem("O8", "orderItem", row.getLong("id"), "clawbacks <= ${row.getLong("ca")}", row.getLong("taken").toString())
        }
    }

    // ----- P1 ---------------------------------------------------------------------------------------------------------

    private suspend fun payoutsOf(sql: SqlClient, since: Long, found: MutableList<CreditProblem>) {
        for (row in rows(
            sql,
            "SELECT p.`id`, p.`creditTxId` FROM ${t("market_creator_payout")} p LEFT JOIN ${t("market_credit_tx")} c ON c.`id` = p.`creditTxId` " +
                "WHERE p.`method` = 'CREDIT' AND p.`state` = 'PAID' AND p.`updatedAt` >= ? AND " +
                "(c.`id` IS NULL OR c.`type` <> 'CREATOR_PAYOUT' OR p.`creatorUserId` IS NULL OR c.`userId` <> p.`creatorUserId`)",
            since
        )) {
            found += CreditProblem("P1", "payout", row.getLong("id"), "creditTxId of a CREATOR_PAYOUT for the creator", "creditTxId ${row.getLong("creditTxId") ?: "none"}")
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(CreditReconciler::class.java)

        /** A failing check is evaluated again after this long and reported only when it fails twice (07 section 16.2). */
        const val RECHECK_DELAY_MS = 5_000L

        /** Transactions per id batch of the full scan. */
        const val BATCH_SIZE = 5_000

        const val MAX_PROBLEMS = 100

        /** Every order is checked again once per day. */
        const val FULL_SCAN_MS = 24L * 60 * 60 * 1000

        /** The orders updated since the previous run, minus a margin for transactions that were still open then. */
        private const val ORDER_SCAN_MARGIN_MS = 60_000L

        private const val USER = "USER"

        /** The posting catalogue of 07 section 3.1: from and to of every type, `USER` or the key of a system account. */
        private val CATALOGUE: Map<String, Pair<String, String>> = mapOf(
            "TOPUP" to ("ISSUANCE" to USER), "GIFT" to ("ISSUANCE" to USER), "GRANT" to ("ISSUANCE" to USER),
            "REVOKE" to (USER to "REVOKED"), "HOLD" to (USER to "HOLD"), "CAPTURE" to ("HOLD" to "SPENT"), "RELEASE" to ("HOLD" to USER),
            "REFUND" to ("ISSUANCE" to USER), "CASHBACK" to ("ISSUANCE" to USER), "CASHBACK_REVERSAL" to (USER to "REVOKED"),
            "ACTION" to ("ISSUANCE" to USER), "ACTION_REVERSAL" to (USER to "REVOKED"), "CREATOR_PAYOUT" to ("ISSUANCE" to USER),
            "EXTERNAL_IN" to ("EXTERNAL" to USER), "EXTERNAL_OUT" to (USER to "EXTERNAL")
        )
    }
}
