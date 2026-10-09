package com.panomc.plugins.market.routes.panel.credit

import com.panomc.platform.model.PageRequest
import com.panomc.platform.model.Paging
import com.panomc.platform.error.NotFound
import com.panomc.plugins.market.db.dao.MarketCreditAccountDao
import com.panomc.plugins.market.db.dao.MarketCreditTxDao
import com.panomc.plugins.market.db.model.CreditSystemKey
import com.panomc.plugins.market.db.model.CreditTxType
import com.panomc.plugins.market.db.tx.MarketDb
import com.panomc.plugins.market.error.IdempotencyConflict
import com.panomc.plugins.market.routes.base.pageJson
import com.panomc.plugins.market.service.CreditService
import com.panomc.plugins.market.service.PostResult
import com.panomc.plugins.market.util.MoneyUtil
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple

/** What a grant or a revoke did (07 section 11.1): [replayed] is true when the key had been used before, so the route writes no second activity log. */
class CreditMovement(val userId: Long, val username: String, val type: CreditTxType, val moved: Long, val shortfall: Long, val balance: Long, val replayed: Boolean) {
    fun toJson(): JsonObject = JsonObject().put("balance", MoneyUtil.toDecimal(balance)).put("shortfall", MoneyUtil.toDecimal(shortfall))
}

/**
 * What the panel credit routes do (07 sections 11.1 and 11.2; 04 section 7 `credits` routes), as a service so the rules run in a database test without the host:
 * manual grant / revoke with the `panel:<Idempotency-Key>` replay rules, and the three read models (accounts with the totals row, one account's entries,
 * the global ledger). Amounts leave as decimal credits (the ledger holds credits x 100). The routes only parse, call this and write the activity log.
 * [prefix] is the table prefix; users are read from the platform `user` table.
 */
class CreditAdminService(
    private val db: MarketDb,
    private val credits: CreditService,
    private val accounts: MarketCreditAccountDao,
    private val txs: MarketCreditTxDao,
    private val prefix: () -> String,
    private val client: suspend () -> SqlClient
) {
    private fun t(name: String) = "`${prefix()}$name`"

    // ----- grant and revoke (07 section 11.1) ----------------------------------------------------------------------------

    /**
     * `GRANT` or `REVOKE` of [request] for the user, all in one transaction: the accounts are locked first, then the key `panel:<Idempotency-Key>` is looked
     * up. A tx under that key with another type, user or requested amount (`amount + shortfall`) is `IDEMPOTENCY_CONFLICT`; the same request replays the stored
     * tx (its shortfall, the current balance, nothing written). 404 when the user does not exist. Works while credits are disabled (07 section 14.1).
     */
    suspend fun move(request: CreditMoveRequest, userId: Long, actor: Long): CreditMovement {
        val username = usernameOf(userId, client()) ?: throw NotFound()
        val key = "panel:${request.idempotencyKey}"

        val result: PostResult = db.tx { conn ->
            credits.lockAccounts(listOf(userId), true, conn)

            txs.getByIdempotencyKey(key, conn)?.let { existing ->
                if (existing.type != request.type || existing.userId != userId || existing.amount + existing.shortfall != request.credits) throw IdempotencyConflict()
            }

            if (request.type == CreditTxType.GRANT) credits.grant(userId, request.credits, key, actor, request.note, conn)
            else credits.revoke(userId, request.credits, key, actor, request.note, conn)
        }

        val balance = if (result.replayed) credits.balance(userId, client()) else result.userBalance

        return CreditMovement(userId, username, request.type, result.tx.amount, result.tx.shortfall, balance, result.replayed)
    }

    // ----- lists (07 section 11.2) ---------------------------------------------------------------------------------------

    /** `GET /credits/accounts`: users with an account row, `balance DESC, userId ASC`, the totals row of the system accounts. */
    suspend fun accountList(search: String?, window: PageRequest): JsonObject {
        val c = client()
        val like = search?.trim()?.takeIf { it.isNotEmpty() }?.let { escapeLike(it.lowercase()) + "%" }
        val filter = if (like == null) "" else " AND LOWER(u.`username`) LIKE ? ESCAPE '\\\\'"
        val args = listOfNotNull(like)
        val from = "FROM ${t("market_credit_account")} a JOIN ${t("user")} u ON u.`id` = a.`userId` WHERE a.`systemKey` IS NULL$filter"

        val count = one(c, "SELECT COUNT(*) $from", args).getLong(0)

        Paging.requireInRange(window, count)

        val rows = many(
            c, "SELECT a.`userId`, u.`username`, a.`balance` $from ORDER BY a.`balance` DESC, a.`userId` ASC LIMIT ? OFFSET ?",
            args + listOf(window.size.toLong(), window.offset)
        )

        return pageJson(
            rows.map { JsonObject().put("userId", it.getLong("userId")).put("username", it.getString("username")).put("balance", credits(it.getLong("balance"))) },
            count, window, mapOf("totals" to totals(c))
        )
    }

    /** `issued = -ISSUANCE`, `spent`, `held`, `revoked`, `external`, `outstanding = sum of the user balances` (so `issued = outstanding + held + spent + revoked`). */
    suspend fun totals(c: SqlClient): JsonObject {
        val system = accounts.getSystemAccounts(c).associate { it.systemKey!! to it.balance }
        val outstanding = one(c, "SELECT COALESCE(SUM(`balance`), 0) FROM ${t("market_credit_account")} WHERE `systemKey` IS NULL", emptyList()).getLong(0)

        return JsonObject()
            .put("issued", credits(-(system[CreditSystemKey.ISSUANCE] ?: 0L)))
            .put("spent", credits(system[CreditSystemKey.SPENT] ?: 0L))
            .put("held", credits(system[CreditSystemKey.HOLD] ?: 0L))
            .put("revoked", credits(system[CreditSystemKey.REVOKED] ?: 0L))
            .put("external", credits(system[CreditSystemKey.EXTERNAL] ?: 0L))
            .put("outstanding", credits(outstanding))
    }

    /** `GET /credits/accounts/:userId`: works without an account row (balance 0, no entries, nothing created); 404 only for an unknown user. */
    suspend fun accountDetail(userId: Long, window: PageRequest): JsonObject {
        val c = client()

        usernameOf(userId, c) ?: throw NotFound()

        val account = accounts.getByUserId(userId, c)
        val count = if (account == null) 0L else one(c, "SELECT COUNT(*) FROM ${t("market_credit_entry")} WHERE `accountId` = ?", listOf(account.id)).getLong(0)

        Paging.requireInRange(window, count)

        val rows = if (account == null) emptyList() else many(
            c,
            "SELECT e.`id`, x.`type`, e.`amount`, e.`balanceAfter`, x.`shortfall`, x.`note`, x.`orderId`, x.`refundId`, x.`deliveryId`, au.`username` AS actorUsername, e.`createdAt` " +
                "FROM ${t("market_credit_entry")} e JOIN ${t("market_credit_tx")} x ON x.`id` = e.`txId` LEFT JOIN ${t("user")} au ON au.`id` = x.`actorUserId` " +
                "WHERE e.`accountId` = ? ORDER BY e.`id` DESC LIMIT ? OFFSET ?",
            listOf(account.id, window.size.toLong(), window.offset)
        )

        return pageJson(rows.map { entry(it) }, count, window, mapOf("balance" to credits(account?.balance ?: 0L)))
    }

    /** `GET /credits/transactions`: the global ledger, zero-entry transactions included. */
    suspend fun transactions(filter: CreditTxFilter, window: PageRequest): JsonObject {
        val c = client()
        val where = ArrayList<String>()
        val args = ArrayList<Any?>()

        if (filter.types.isNotEmpty()) {
            where += "x.`type` IN (${filter.types.joinToString(",") { "?" }})"
            args.addAll(filter.types.map { it.name })
        }

        filter.userId?.let { where += "x.`userId` = ?"; args += it }
        filter.orderId?.let { where += "x.`orderId` = ?"; args += it }
        filter.from?.let { where += "x.`createdAt` >= ?"; args += it }
        filter.to?.let { where += "x.`createdAt` <= ?"; args += it }

        val clause = if (where.isEmpty()) "" else " WHERE " + where.joinToString(" AND ")
        val count = one(c, "SELECT COUNT(*) FROM ${t("market_credit_tx")} x$clause", args).getLong(0)

        Paging.requireInRange(window, count)

        val rows = many(
            c,
            "SELECT x.`id`, x.`type`, x.`userId`, u.`username`, x.`amount`, x.`shortfall`, x.`orderId`, x.`refundId`, x.`deliveryId`, au.`username` AS actorUsername, x.`note`, x.`createdAt` " +
                "FROM ${t("market_credit_tx")} x LEFT JOIN ${t("user")} u ON u.`id` = x.`userId` LEFT JOIN ${t("user")} au ON au.`id` = x.`actorUserId`$clause " +
                "ORDER BY x.`id` DESC LIMIT ? OFFSET ?",
            args + listOf(window.size.toLong(), window.offset)
        )

        return pageJson(
            rows.map { row ->
                JsonObject().put("id", row.getLong("id")).put("type", row.getString("type")).put("userId", row.getLong("userId")).put("username", row.getString("username"))
                    .put("amount", credits(row.getLong("amount"))).put("shortfall", credits(row.getLong("shortfall"))).put("orderId", row.getLong("orderId"))
                    .put("refundId", row.getLong("refundId")).put("deliveryId", row.getLong("deliveryId")).put("actorUsername", row.getString("actorUsername"))
                    .put("note", row.getString("note")).put("createdAt", row.getLong("createdAt"))
            },
            count, window
        )
    }

    // ----- helpers -------------------------------------------------------------------------------------------------------

    private fun entry(row: Row) = JsonObject().put("id", row.getLong("id")).put("type", row.getString("type")).put("amount", credits(row.getLong("amount")))
        .put("balanceAfter", credits(row.getLong("balanceAfter"))).put("shortfall", credits(row.getLong("shortfall"))).put("note", row.getString("note"))
        .put("orderId", row.getLong("orderId")).put("refundId", row.getLong("refundId")).put("deliveryId", row.getLong("deliveryId"))
        .put("actorUsername", row.getString("actorUsername")).put("createdAt", row.getLong("createdAt"))

    private fun credits(value: Long): Double = MoneyUtil.toDecimal(value)

    private suspend fun usernameOf(userId: Long, c: SqlClient): String? =
        many(c, "SELECT `username` FROM ${t("user")} WHERE `id` = ?", listOf(userId)).firstOrNull()?.getString("username")

    private suspend fun many(c: SqlClient, sql: String, args: List<Any?>): List<Row> =
        c.preparedQuery(sql).execute(Tuple.from(args)).coAwait().toList()

    private suspend fun one(c: SqlClient, sql: String, args: List<Any?>): Row = many(c, sql, args).first()

    private fun escapeLike(text: String) = text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
}
