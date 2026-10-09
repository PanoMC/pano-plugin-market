package com.panomc.plugins.market.routes.panel.discount

import com.panomc.platform.model.PageRequest
import com.panomc.platform.error.BadRequest
import com.panomc.platform.error.NotFound
import com.panomc.plugins.market.core.time.Clock
import com.panomc.plugins.market.db.dao.MarketBundleItemDao
import com.panomc.plugins.market.db.dao.MarketCategoryDao
import com.panomc.plugins.market.db.dao.MarketProductDao
import com.panomc.plugins.market.db.model.MarketProduct
import com.panomc.plugins.market.db.model.ProductKind
import com.panomc.plugins.market.db.tx.MarketDb
import com.panomc.plugins.market.error.CodeAlreadyExists
import com.panomc.plugins.market.error.InvalidCreditAmount
import com.panomc.plugins.market.error.MarketBusyException
import com.panomc.plugins.market.service.RedemptionPage
import com.panomc.plugins.market.service.RedemptionService
import com.panomc.plugins.market.service.ShippingService
import com.panomc.plugins.market.service.platform.UserDirectory
import com.panomc.plugins.market.util.GiftType
import com.panomc.plugins.market.util.MoneyUtil
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** The page of a promotion list: rows as the JSON of the route, the number of rows behind them. */
class PromotionPage(val rows: List<Map<String, Any?>>, val total: Long, val extras: Map<String, Any?> = emptyMap())

/** A created or changed promotion: its id and the name the activity log shows (the code for a code, the name for a discount). */
class PromotionResult(val id: Long, val label: String)

/**
 * The discounts panel (04 section 6, MK-113): list, create, partial update, delete and the redemption lists of discounts, coupons, creator codes and
 * gifts, written with plain statements so that no counter can be touched by a form (`usedCount`, `earnings` and `paidOut` appear in no `UPDATE` of this
 * class; the guarded statements of `RedemptionService` and `CreatorService` are their only writers).
 *
 * Soft delete (01 section 13): a row with any redemption keeps existing with `deletedAt` set (its redemption rows, orders and counters stay readable, a
 * `HELD` redemption still releases its counter); a row without one is removed. Every read of a live row skips `deletedAt IS NOT NULL`.
 *
 * Code uniqueness (01 section 3.4): coupon, creator code and gift share one code space. The check-and-write of a code runs under
 * `GET_LOCK('<prefix>market_code', 5)` on one connection and the lock is released only after the statement has committed (autocommit), so two
 * creates of the same code, in the same or in different tables, cannot both pass the check; the unique index of each table stays as the last guard.
 * A soft-deleted code keeps its place in the space (its redemptions name it); an admin picks another one.
 */
class PromotionAdminService(
    private val db: MarketDb,
    private val pool: suspend () -> Pool,
    private val client: suspend () -> SqlClient,
    private val prefix: () -> String,
    private val clock: Clock,
    private val redemptions: RedemptionService,
    private val users: UserDirectory,
    private val products: MarketProductDao,
    private val bundleItems: MarketBundleItemDao,
    private val categories: MarketCategoryDao
) {
    private fun table(promotion: Promotion) = "`${prefix()}${promotion.table}`"

    // ----------------------------------------------------------------------------------------------------- lists

    /** `GET /<plural>`: live rows, newest first; [search] matches the code (or the name), [status] the status. */
    suspend fun list(promotion: Promotion, window: PageRequest, search: String?, status: String?): PromotionPage {
        val sql = client()
        val columns = PromotionRules.columns(promotion)
        val where = StringBuilder("`deletedAt` IS NULL")
        val params = Tuple.tuple()

        if (!status.isNullOrBlank()) {
            where.append(" AND `status` = ?")
            params.addString(status)
        }

        if (!search.isNullOrBlank()) {
            val like = "%" + search.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"

            if (promotion.codeColumn) {
                where.append(" AND (`code` LIKE ? OR `${if (promotion == Promotion.CREATOR_CODE) "creator" else "name"}` LIKE ?)")
                params.addString(like).addString(like)
            } else {
                where.append(" AND `name` LIKE ?")
                params.addString(like)
            }
        }

        val total = sql.preparedQuery("SELECT COUNT(*) AS `n` FROM ${table(promotion)} WHERE $where").execute(params).coAwait().first().getLong("n")
        val rows = sql.preparedQuery(
            "SELECT ${columns.joinToString(", ") { "`${it.name}`" }} FROM ${table(promotion)} WHERE $where ORDER BY `id` DESC LIMIT ? OFFSET ?"
        ).execute(copy(params).addLong(window.size.toLong()).addLong(window.offset)).coAwait().map { row -> json(columns, row) }

        return PromotionPage(decorate(promotion, rows, sql), total)
    }

    private fun copy(t: Tuple): Tuple = Tuple.tuple().also { n -> for (i in 0 until t.size()) n.addValue(t.getValue(i)) }

    /** Resolved names next to the id lists, for the list tables (the raw ids stay for the form prefill). */
    private suspend fun decorate(promotion: Promotion, rows: List<Map<String, Any?>>, sql: SqlClient): List<Map<String, Any?>> {
        if (promotion == Promotion.DISCOUNT) {
            val productIds = rows.filter { it["scope"] == "PRODUCTS" }.flatMap { ids(it["productIds"]) }.distinct()
            val categoryIds = rows.filter { it["scope"] == "CATEGORIES" }.flatMap { ids(it["categoryIds"]) }.distinct()
            val productNames = if (productIds.isEmpty()) emptyMap() else products.getByIds(productIds, sql).associate { it.id to it.name }
            val categoryNames = if (categoryIds.isEmpty()) emptyMap() else categories.getNamesByIds(categoryIds, sql)

            return rows.map { row ->
                row + ("products" to when (row["scope"]) {
                    "PRODUCTS" -> ids(row["productIds"]).mapNotNull { productNames[it] }
                    "CATEGORIES" -> ids(row["categoryIds"]).mapNotNull { categoryNames[it] }
                    else -> listOf("all")
                })
            }
        }

        if (promotion == Promotion.GIFT) {
            val all = rows.flatMap { ids(it["productIds"]) + listOfNotNull(it["productId"] as? Long) }.distinct()
            val names = if (all.isEmpty()) emptyMap() else products.getByIds(all, sql).associate { it.id to it.name }

            return rows.map { row ->
                row + mapOf(
                    "productName" to (row["productId"] as? Long)?.let { names[it] },
                    "productNames" to ids(row["productIds"]).mapNotNull { names[it] }.takeIf { row["productIds"] != null }
                )
            }
        }

        return rows
    }

    @Suppress("UNCHECKED_CAST")
    private fun ids(value: Any?): List<Long> = (value as? List<Long>).orEmpty()

    private fun json(columns: List<Column>, row: Row): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()

        for (column in columns) {
            out[column.name] = when (column.type) {
                ColumnType.TEXT -> row.getString(column.name)
                ColumnType.LONG -> row.getLong(column.name)
                ColumnType.INT -> row.getInteger(column.name)
                ColumnType.MONEY -> row.getLong(column.name)?.let { MoneyUtil.toDecimal(it) }
                ColumnType.IDS -> row.getString(column.name)?.let { raw -> runCatching { JsonArray(raw).map { (it as Number).toLong() } }.getOrNull() }
                ColumnType.BOOL -> row.getValue(column.name)?.let { if (it is Boolean) it else (it as Number).toInt() != 0 }
            }
        }

        return out
    }

    /** The stored row as the raw values the rules compare against (money stays x100). */
    private suspend fun stored(promotion: Promotion, id: Long, sql: SqlClient, forUpdate: Boolean = false): Map<String, Any?>? {
        val rows = sql.preparedQuery("SELECT * FROM ${table(promotion)} WHERE `id` = ? AND `deletedAt` IS NULL${if (forUpdate) " FOR UPDATE" else ""}").execute(Tuple.of(id)).coAwait()
        val row = rows.firstOrNull() ?: return null

        return (0 until row.size()).associate { row.getColumnName(it) to row.getValue(it) }
    }

    // --------------------------------------------------------------------------------------------- create / update

    /** `POST`: validates [body], resolves the creator and the gift product, writes the row under the code lock. Returns the new id and the log label. */
    suspend fun create(promotion: Promotion, body: JsonObject): PromotionResult {
        val sql = client()
        val errors = linkedMapOf<String, String>()
        val write = PromotionRules.parse(promotion, body, null, errors)

        checks(promotion, body, null, write, errors, sql)

        if (errors.isNotEmpty()) throw fieldErrors(errors)

        val now = clock.now()
        val values = LinkedHashMap(write.values)

        values["createdAt"] = now
        values["updatedAt"] = now

        val insert = "INSERT INTO ${table(promotion)} (${values.keys.joinToString(", ") { "`$it`" }}) VALUES (${values.keys.joinToString(", ") { "?" }})"
        val id = if (promotion.codeColumn) {
            underCodeLock(sql) { conn ->
                assertCodeFree(conn, write.code!!, promotion, null)

                insertRow(conn, insert, values)
            }
        } else {
            insertRow(sql, insert, values)
        }

        return PromotionResult(id, label(promotion, write, values))
    }

    /** `PUT`: only the keys that are present change; a missing or soft-deleted row is 404. Counters are never in the statement. */
    suspend fun update(promotion: Promotion, id: Long, body: JsonObject): PromotionResult {
        val sql = client()
        val current = stored(promotion, id, sql) ?: throw NotFound()
        val errors = linkedMapOf<String, String>()
        val write = PromotionRules.parse(promotion, body, current, errors)

        checks(promotion, body, current, write, errors, sql)

        if (errors.isNotEmpty()) throw fieldErrors(errors)

        val values = LinkedHashMap(write.values)

        values["updatedAt"] = clock.now()

        val update = "UPDATE ${table(promotion)} SET ${values.keys.joinToString(", ") { "`$it` = ?" }} WHERE `id` = ? AND `deletedAt` IS NULL"
        val parameters = values.values.toMutableList<Any?>().also { it += id }

        val changed = if (promotion.codeColumn && write.code != null) {
            underCodeLock(sql) { conn ->
                assertCodeFree(conn, write.code, promotion, id)

                updateRow(conn, update, parameters)
            }
        } else {
            updateRow(sql, update, parameters)
        }

        if (changed == 0) throw NotFound()

        val merged = current.toMutableMap().also { m -> values.forEach { (k, v) -> m[k] = v } }

        return PromotionResult(id, labelOf(promotion, merged))
    }

    /** The checks that need the database: the gift's products, the creator code's owner. */
    private suspend fun checks(promotion: Promotion, body: JsonObject, stored: Map<String, Any?>?, write: PromotionWrite, errors: MutableMap<String, String>, sql: SqlClient) {
        if (promotion == Promotion.CREATOR_CODE) {
            val owner = ownerOf(write, errors, sql)

            if (owner != null) {
                write.values["creatorUserId"] = owner.first

                if (!write.values.containsKey("creator")) write.values["creator"] = owner.second
            } else if (write.creatorUserIdGiven || write.values.containsKey("creator")) {
                write.values["creatorUserId"] = null
            }
        }

        if (promotion != Promotion.GIFT) return

        val type = write.giftType ?: return

        if (type == GiftType.CREDIT) {
            // a CREDIT gift: 04 section 6 / 07 section 10, the amount is the whole point of the code
            if (PromotionRules.creditAmountInvalid(body, stored)) throw InvalidCreditAmount("RANGE", 0.01, MoneyUtil.toDecimal(PromotionRules.MAX_CREDIT_MINOR))

            return
        }

        if (write.productIds.isEmpty() || errors.containsKey("productId") || errors.containsKey("productIds")) return

        val found = products.getByIds(write.productIds, sql).filter { it.deletedAt == null }
        val missing = write.productIds.filter { id -> found.none { it.id == id } }

        if (missing.isNotEmpty()) {
            errors[if (type == GiftType.PRODUCT) "productId" else "productIds"] = "NOT_FOUND"

            return
        }

        // 10 section 6.3: a code redeems with no address, so a physical product (a bundle's children included) is refused here as well
        val handedOut = ArrayList<MarketProduct>(found)

        for (bundle in found.filter { it.kind == ProductKind.BUNDLE }) {
            val children = bundleItems.getByBundleProductId(bundle.id, sql).map { it.productId }

            if (children.isNotEmpty()) handedOut += products.getByIds(children, sql)
        }

        ShippingService.giftCodeRefusal(handedOut)?.let { throw it }
    }

    /** The creator code's owner: an explicit `creatorUserId` (must exist), else the user named by `creator` when there is one; `null` for a name nobody owns. */
    private suspend fun ownerOf(write: PromotionWrite, errors: MutableMap<String, String>, sql: SqlClient): Pair<Long, String>? {
        if (write.creatorUserIdGiven) {
            val id = write.creatorUserId ?: return null
            val name = users.usernameOf(id, sql)

            if (name == null) errors["creatorUserId"] = "NOT_FOUND"

            return name?.let { id to it }
        }

        val creator = write.creator ?: return null
        val known = users.byUsername(creator, sql) ?: return null

        return known.id to known.username
    }

    private fun label(promotion: Promotion, write: PromotionWrite, values: Map<String, Any?>): String = when (promotion) {
        Promotion.CREATOR_CODE, Promotion.COUPON, Promotion.GIFT -> (values["code"] as? String) ?: write.code.orEmpty()
        Promotion.DISCOUNT -> (values["name"] as? String).orEmpty()
    }

    private fun labelOf(promotion: Promotion, row: Map<String, Any?>): String =
        if (promotion == Promotion.DISCOUNT) (row["name"] as? String).orEmpty() else (row["code"] as? String).orEmpty()

    private fun fieldErrors(errors: Map<String, String>) = BadRequest(extras = mapOf("fieldErrors" to errors))

    private suspend fun insertRow(conn: SqlClient, sql: String, values: Map<String, Any?>): Long {
        val result = conn.preparedQuery(sql).execute(Tuple.from(values.values.toList())).coAwait()

        return result.property(io.vertx.mysqlclient.MySQLClient.LAST_INSERTED_ID)
    }

    private suspend fun updateRow(conn: SqlClient, sql: String, parameters: List<Any?>): Int =
        conn.preparedQuery(sql).execute(Tuple.from(parameters)).coAwait().rowCount()

    // ------------------------------------------------------------------------------------------------ code space

    /**
     * Runs [block] on one connection that holds `GET_LOCK` for the code space; the block's statements are autocommit, so they are committed when it
     * returns and the lock is released only after that. A lock that cannot be had in 5 seconds is [MarketBusyException] (503 `STORE_BUSY`).
     */
    private suspend fun <T> underCodeLock(@Suppress("UNUSED_PARAMETER") sql: SqlClient, block: suspend (SqlConnection) -> T): T {
        val conn = pool().connection.coAwait()
        var locked = false
        val lockName = "${prefix()}market_code".take(64)

        try {
            val got = conn.preparedQuery("SELECT GET_LOCK(?, ?) AS `got`").execute(Tuple.of(lockName, LOCK_WAIT_SECONDS)).coAwait().first().getValue("got") as? Number

            if (got?.toInt() != 1) throw MarketBusyException(1, null)

            locked = true

            return block(conn)
        } finally {
            withContext(NonCancellable) {
                if (locked) runCatching { conn.preparedQuery("SELECT RELEASE_LOCK(?)").execute(Tuple.of(lockName)).coAwait() }

                runCatching { conn.close().coAwait() }
            }
        }
    }

    /** `CodeAlreadyExists` (409) when another row, soft-deleted ones included, of the three coded tables has [code]; [self] is the row being changed. */
    private suspend fun assertCodeFree(conn: SqlConnection, code: String, promotion: Promotion, self: Long?) {
        for (other in Promotion.CODED) {
            val sql = "SELECT `id` FROM ${table(other)} WHERE `code` = ?${if (other == promotion && self != null) " AND `id` <> ?" else ""} LIMIT 1"
            val params = if (other == promotion && self != null) Tuple.of(code, self) else Tuple.of(code)

            if (conn.preparedQuery(sql).execute(params).coAwait().iterator().hasNext()) throw CodeAlreadyExists()
        }
    }

    // ---------------------------------------------------------------------------------------------------- delete

    /** `DELETE`: soft when any redemption names the row, hard otherwise; a missing or already deleted row is 404. Returns the label for the log. */
    suspend fun delete(promotion: Promotion, id: Long): String = db.tx { conn ->
        val row = stored(promotion, id, conn, forUpdate = true) ?: throw NotFound()
        val label = labelOf(promotion, row)

        if (redemptions.hasRedemptions(conn, promotion.kind, id)) {
            conn.preparedQuery("UPDATE ${table(promotion)} SET `deletedAt` = ?, `updatedAt` = ? WHERE `id` = ?").execute(Tuple.of(clock.now(), clock.now(), id)).coAwait()
        } else {
            conn.preparedQuery("DELETE FROM ${table(promotion)} WHERE `id` = ?").execute(Tuple.of(id)).coAwait()
        }

        label
    }

    // ---------------------------------------------------------------------------------------------- redemptions

    /** `GET /<plural>/:id/redemptions`: 404 for a row that never existed; a soft-deleted row keeps its history readable. */
    suspend fun redemptionList(promotion: Promotion, id: Long, window: PageRequest): RedemptionPage {
        val sql = client()
        val exists = sql.preparedQuery("SELECT 1 FROM ${table(promotion)} WHERE `id` = ?").execute(Tuple.of(id)).coAwait().iterator().hasNext()

        if (!exists) throw NotFound()

        return redemptions.listFor(sql, promotion.kind, id, window.number.toLong(), window.size.toLong())
    }

    private companion object {
        const val LOCK_WAIT_SECONDS = 5
    }
}
