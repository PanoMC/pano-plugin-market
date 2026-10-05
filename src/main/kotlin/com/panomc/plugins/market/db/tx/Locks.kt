package com.panomc.plugins.market.db.tx

import com.panomc.plugins.market.db.dao.MarketCreditAccountDao
import com.panomc.plugins.market.db.dao.MarketOrderDao
import com.panomc.plugins.market.db.dao.MarketOrderItemDao
import com.panomc.plugins.market.db.dao.MarketRedemptionDao
import com.panomc.plugins.market.db.model.CreditSystemKey
import com.panomc.plugins.market.db.model.MarketOrder
import com.panomc.plugins.market.db.model.MarketOrderItem
import com.panomc.plugins.market.db.model.MarketRedemption
import com.panomc.plugins.market.db.model.RedemptionKind
import com.panomc.plugins.market.error.MarketBusyException
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple
import java.util.TreeSet

/**
 * What a transition on an existing order needs locked before the order row (06 section 13.2). The rows of every scope
 * are taken in the global order of 00 section 8.3.
 */
enum class OrderLockScope {
    /** Attempt bookkeeping that cannot move the order (start result, `Pending`, attempt expiry, status query): no row before the order. */
    PAYMENT,

    /** `POST .../pay`: the payer's credit account and the `HOLD` account, then the subscription row of the order. */
    CREDIT,

    /** Anything that may end in O2 / O3 / O9: creator code, products, credit accounts, subscription. */
    COMMIT,

    /**
     * O5 to O8, O4 with a re-reserve, refunds: codes, discounts, products, variants, credit accounts (a superset of
     * [COMMIT]'s, plus `REVOKED` when credits are granted or a cashback can apply), subscription.
     */
    RELEASE
}

/** The child tables of an order, declared in the order their rows are locked (00 section 8.3 level 7). */
enum class OrderChild(internal val table: String) {
    PAYMENT("market_payment"),
    REFUND("market_refund"),
    DELIVERY("market_delivery"),
    SHIPMENT("market_shipment")
}

/**
 * The order row changed between the unlocked read and its lock (06 section 13.2 step 4): the caller's transaction rolls
 * back and the whole use case restarts on the new state (see [txRestartingOnOrderChange]).
 */
class OrderChangedException(val orderId: Long, val detail: String) :
    RuntimeException("order $orderId changed between the unlocked read and its lock: $detail")

/**
 * The order after [Locks.forOrder] locked it: [order], its [items] and all its [redemptions] as read under the lock
 * (so they are the rows the lock set was computed from, verified against `status`, `reservationState`, `updatedAt`).
 * [scope] tells which rows were locked before the order; the reservation steps check it so a step never runs without
 * the locks it relies on.
 */
class LockedOrder(
    val order: MarketOrder,
    val items: List<MarketOrderItem>,
    val redemptions: List<MarketRedemption>,
    val scope: OrderLockScope
)

/** A locked `market_product` / `market_product_variant` row: `stock` is `null` for unlimited. */
class LockedStock(val id: Long, val stock: Int?)

/** A reference to a code or discount row; the enum order of [RedemptionKind] is the lock order. */
data class CodeRef(val kind: RedemptionKind, val id: Long) : Comparable<CodeRef> {
    override fun compareTo(other: CodeRef): Int = compareValuesBy(this, other, { it.kind.ordinal }, { it.id })
}

/** A locked coupon, creator code, gift or discount row with the columns the reservation reads. */
class LockedCode(
    val ref: CodeRef,
    /** `redeemLimit` (coupon, creator code, gift) or `usageLimit` (discount); `null` = unlimited. */
    val limit: Int?,
    /** `customerRedeemLimit` (coupon, gift); always `null` for creator codes and discounts, which have none. */
    val customerLimit: Int?,
    val usedCount: Int,
    val deletedAt: Long?
)

/**
 * The lock order of 00 section 8.3 as code. Every row lock of the market plugin is taken here, inside the caller's
 * `MarketDb.tx`, and always in this order:
 *
 * 0. `market_cart`, 1. code rows (coupon, creator code, gift), 2. `market_discount`, 3. `market_product` then
 *    `market_product_variant` (ascending id), 4. `market_credit_account` (ascending id), 5. `market_subscription`,
 *    6. `market_order`, 7. child rows of the order, 8. `market_sequence` (not here: taken by the order transitions).
 *
 * [forOrder] is the one entry point of every mutation of an existing order: unlocked read, lock of the prerequisite rows
 * the scope needs, lock of the order, check that nothing changed, then the caller's block. Locking the order first and
 * the products or codes afterwards would invert the order and deadlock against a re-reserve.
 *
 * The lock statements are plain `SELECT ... FOR UPDATE` on the table prefix of [orders]; an [id list][lockIds] is
 * sorted before it is sent and the statement orders by `id`, so two transactions never meet in opposite directions.
 */
class Locks(
    private val orders: MarketOrderDao,
    private val orderItems: MarketOrderItemDao,
    private val redemptions: MarketRedemptionDao,
    private val creditAccounts: MarketCreditAccountDao
) {
    private fun table(name: String) = "`${orders.prefix()}$name`"

    private suspend fun lockIds(conn: SqlClient, table: String, ids: Collection<Long>, columns: String): RowSet<Row> {
        val sorted = ids.toSortedSet().toList()
        return conn.preparedQuery(
            "SELECT $columns FROM ${table(table)} WHERE `id` IN (${sorted.joinToString(",") { "?" }}) ORDER BY `id` FOR UPDATE"
        ).execute(Tuple.from(sorted)).coAwait()
    }

    // ----- level 0 -------------------------------------------------------------------------------------------------

    /** Level 0: the cart row of [userId] (`SELECT id FROM market_cart WHERE userId = ? FOR UPDATE`); its id, or `null` when there is none. */
    suspend fun cart(conn: SqlClient, userId: Long): Long? =
        conn.preparedQuery("SELECT `id` FROM ${table("market_cart")} WHERE `userId` = ? FOR UPDATE").execute(Tuple.of(userId)).coAwait()
            .firstOrNull()?.getLong("id")

    // ----- levels 1 and 2 ------------------------------------------------------------------------------------------

    /**
     * Levels 1 and 2: the rows named by [refs], coupons first, then creator codes, then gifts (each ascending id), then
     * discounts. A row that does not exist is simply absent from the result.
     */
    suspend fun codes(conn: SqlClient, refs: Collection<CodeRef>): Map<CodeRef, LockedCode> {
        val found = LinkedHashMap<CodeRef, LockedCode>()

        for (kind in RedemptionKind.values()) {
            val ids = refs.filter { it.kind == kind }.map { it.id }

            if (ids.isEmpty()) continue

            val limit = if (kind == RedemptionKind.DISCOUNT) "usageLimit" else "redeemLimit"
            val customerLimit = if (kind == RedemptionKind.COUPON || kind == RedemptionKind.GIFT) "`customerRedeemLimit`" else "NULL"

            lockIds(conn, tableOf(kind), ids, "`id`, `$limit` AS `lim`, $customerLimit AS `clim`, `usedCount`, `deletedAt`").forEach { row ->
                val ref = CodeRef(kind, row.getLong("id"))
                found[ref] = LockedCode(ref, row.getInteger("lim"), row.getInteger("clim"), row.getInteger("usedCount"), row.getLong("deletedAt"))
            }
        }

        return found
    }

    // ----- level 3 -------------------------------------------------------------------------------------------------

    /** Level 3: product rows, ascending id; the stock of every row that exists. */
    suspend fun products(conn: SqlClient, ids: Collection<Long>): Map<Long, LockedStock> = stockRows(conn, "market_product", ids)

    /** Level 3, after the products: variant rows, ascending id. */
    suspend fun variants(conn: SqlClient, ids: Collection<Long>): Map<Long, LockedStock> = stockRows(conn, "market_product_variant", ids)

    private suspend fun stockRows(conn: SqlClient, table: String, ids: Collection<Long>): Map<Long, LockedStock> {
        if (ids.isEmpty()) return emptyMap()

        val found = LinkedHashMap<Long, LockedStock>()

        lockIds(conn, table, ids, "`id`, `stock`").forEach { row -> found[row.getLong("id")] = LockedStock(row.getLong("id"), row.getInteger("stock")) }

        return found
    }

    // ----- levels 4 and 5 ------------------------------------------------------------------------------------------

    /** Level 4: credit accounts in ascending id order (one statement); the ids that exist. */
    suspend fun creditAccounts(conn: SqlClient, ids: Collection<Long>): List<Long> =
        if (ids.isEmpty()) emptyList() else creditAccounts.lockByIds(ids.toSortedSet(), conn).map { it.id }

    /** Level 5: the subscription row `FOR UPDATE`; `false` when it does not exist. */
    suspend fun subscription(conn: SqlClient, subscriptionId: Long): Boolean =
        conn.preparedQuery("SELECT `id` FROM ${table("market_subscription")} WHERE `id` = ? FOR UPDATE").execute(Tuple.of(subscriptionId)).coAwait()
            .iterator().hasNext()

    // ----- levels 6 and 7 ------------------------------------------------------------------------------------------

    /**
     * Level 7: the child rows of [orderId] the use case needs, in the order of [OrderChild] and ascending id inside a
     * table. Call it after [forOrder] locked the order row (the block of [forOrder] is the place).
     */
    suspend fun children(conn: SqlClient, orderId: Long, vararg wanted: OrderChild): Map<OrderChild, List<Long>> {
        val ids = LinkedHashMap<OrderChild, List<Long>>()

        for (child in OrderChild.values().filter { it in wanted }) {
            ids[child] = conn.preparedQuery("SELECT `id` FROM ${table(child.table)} WHERE `orderId` = ? ORDER BY `id` FOR UPDATE")
                .execute(Tuple.of(orderId)).coAwait().map { it.getLong("id") }
        }

        return ids
    }

    /**
     * The order lock of every transition on an existing order (06 section 13.2): unlocked read, the rows [scope] needs in
     * the global order, `SELECT ... FOR UPDATE` of the order row, verification, then [block] with the locked order.
     *
     * [cashback] adds the payer's account and the `ISSUANCE` account to a `COMMIT` or `RELEASE` lock (and `REVOKED` to a
     * `RELEASE` lock): the caller passes it when a ledger posting beyond the order's own credit hold can happen, that is
     * a cashback of O2 / O4, or in a refund transition the `REFUND` of a credit part and the `CASHBACK_REVERSAL`. They
     * are locked only then, because locking them for every payment would serialise all of them on one row. Credit-granting
     * items (`TOPUP` / `GIFT`, payer and recipient) are found from the order itself and need no flag. A scope other than
     * `PAYMENT` locks the subscription row when the order has one.
     *
     * `RELEASE` locks every credit account `COMMIT` does (an accept of a released order re-reserves and then commits in
     * one transaction), so a posting made under either scope never takes a level 4 row after the order row.
     *
     * Throws [OrderChangedException] when `status`, `reservationState`, `updatedAt`, the payer, the recipient, the
     * subscription or a code of the order differs from the unlocked read; the caller's transaction rolls back and the
     * use case restarts ([txRestartingOnOrderChange]). A missing order is a [NoSuchElementException].
     */
    suspend fun <T> forOrder(
        conn: SqlConnection,
        orderId: Long,
        scope: OrderLockScope,
        cashback: Boolean = false,
        block: suspend (LockedOrder) -> T
    ): T = lockOrder(conn, orderId, scope, cashback, withSubscription = scope != OrderLockScope.PAYMENT, block)

    /**
     * Locks an order that carries a subscription the way 09 section 2 demands: the credit account (when the order holds
     * credits), the subscription row, the order row, nothing else. The only way such an order is locked outside
     * [forOrder].
     */
    suspend fun <T> orderWithSubscription(conn: SqlConnection, orderId: Long, block: suspend (LockedOrder) -> T): T =
        lockOrder(conn, orderId, OrderLockScope.PAYMENT, cashback = false, withSubscription = true, block)

    private suspend fun <T> lockOrder(
        conn: SqlConnection,
        orderId: Long,
        scope: OrderLockScope,
        cashback: Boolean,
        withSubscription: Boolean,
        block: suspend (LockedOrder) -> T
    ): T {
        // 1. unlocked read
        val first = orders.getById(orderId, conn) ?: throw NoSuchElementException("order $orderId does not exist")
        val firstItems = orderItems.getByOrderIds(listOf(orderId), conn)
        val firstRedemptions = redemptions.getByOrderId(orderId, conn)

        // 2. the rows the scope needs, in the global order
        when (scope) {
            OrderLockScope.PAYMENT -> Unit

            OrderLockScope.CREDIT -> Unit

            OrderLockScope.COMMIT -> first.creatorCodeId?.let { codes(conn, listOf(CodeRef(RedemptionKind.CREATOR_CODE, it))) }

            OrderLockScope.RELEASE -> {
                // Every redemption row of the order, not only the HELD ones: a re-reserve brings RELEASED rows back.
                val refs = firstRedemptions.map { CodeRef(it.kind, it.refId) }.toMutableSet()
                first.couponId?.let { refs += CodeRef(RedemptionKind.COUPON, it) }
                first.creatorCodeId?.let { refs += CodeRef(RedemptionKind.CREATOR_CODE, it) }
                codes(conn, refs)
            }
        }

        if (scope == OrderLockScope.COMMIT || scope == OrderLockScope.RELEASE) {
            products(conn, firstItems.mapNotNull { it.productId })
        }

        if (scope == OrderLockScope.RELEASE) variants(conn, firstItems.mapNotNull { it.variantId })

        creditAccounts(conn, creditAccountIds(conn, first, firstItems, scope, cashback, withSubscription))

        if (withSubscription) first.subscriptionId?.let { subscription(conn, it) }

        // 3. the order row, 4. verification
        val row = conn.preparedQuery(
            "SELECT `status`, `reservationState`, `updatedAt`, `userId`, `recipientUserId`, `subscriptionId`, `couponId`, `creatorCodeId` " +
                "FROM ${table("market_order")} WHERE `id` = ? FOR UPDATE"
        ).execute(Tuple.of(orderId)).coAwait().firstOrNull() ?: throw OrderChangedException(orderId, "the row is gone")

        val differences = buildList {
            if (row.getString("status") != first.status.name) add("status")
            if (row.getString("reservationState") != first.reservationState.name) add("reservationState")
            if (row.getLong("updatedAt") != first.updatedAt) add("updatedAt")
            if (row.getLong("userId") != first.userId) add("userId")
            if (row.getLong("recipientUserId") != first.recipientUserId) add("recipientUserId")
            if (row.getLong("subscriptionId") != first.subscriptionId) add("subscriptionId")
            if (row.getLong("couponId") != first.couponId) add("couponId")
            if (row.getLong("creatorCodeId") != first.creatorCodeId) add("creatorCodeId")
        }

        if (differences.isNotEmpty()) throw OrderChangedException(orderId, differences.joinToString(", "))

        // The order is ours now: what is read from here on cannot change under us.
        val order = orders.getById(orderId, conn) ?: throw OrderChangedException(orderId, "the row is gone")

        return block(
            LockedOrder(
                order,
                orderItems.getByOrderIds(listOf(orderId), conn),
                redemptions.getByOrderId(orderId, conn),
                scope
            )
        )
    }

    /** The credit accounts (level 4) [scope] locks for [order], per 06 section 13.2 and 07 section 3.3; ids of accounts that exist. */
    private suspend fun creditAccountIds(
        conn: SqlClient,
        order: MarketOrder,
        items: List<MarketOrderItem>,
        scope: OrderLockScope,
        cashback: Boolean,
        withSubscription: Boolean
    ): Set<Long> {
        val ids = TreeSet<Long>()
        val holds = order.creditAmount > 0
        val grants = items.any { (it.creditAmount ?: 0) > 0 }

        suspend fun user(userId: Long?) {
            if (userId != null) creditAccounts.getByUserId(userId, conn)?.let { ids += it.id }
        }

        suspend fun system(key: CreditSystemKey) {
            creditAccounts.getBySystemKey(key, conn)?.let { ids += it.id }
        }

        when (scope) {
            OrderLockScope.PAYMENT -> if (withSubscription && holds) {
                user(order.userId)
                system(CreditSystemKey.HOLD)
            }

            OrderLockScope.CREDIT -> {
                user(order.userId)
                system(CreditSystemKey.HOLD)
            }

            // RELEASE is the scope of O4 after O9 (re-reserve, then everything of O2: capture, TOPUP / GIFT, CASHBACK) and of the
            // refund transitions (21 section 3.2 and 3.4), so it locks at least what COMMIT locks. REVOKED is added for the
            // postings that only a RELEASE transition makes (clawback and cashback reversal: user -> REVOKED).
            OrderLockScope.COMMIT, OrderLockScope.RELEASE -> {
                if (holds || grants || cashback) user(order.userId)

                if (holds) {
                    system(CreditSystemKey.HOLD)
                    system(CreditSystemKey.SPENT)
                }

                if (grants || cashback) {
                    system(CreditSystemKey.ISSUANCE)

                    if (scope == OrderLockScope.RELEASE) system(CreditSystemKey.REVOKED)
                }

                // A credit pack bought as a gift grants to the recipient (07 section 3.3); a clawback takes it back from there.
                if (grants && order.recipientUserId != null) user(order.recipientUserId)
            }
        }

        return ids
    }

    companion object {
        /** Restarts of a use case after [OrderChangedException] before it gives up (06 section 13.2 step 4). */
        const val MAX_RESTARTS = 3

        internal fun tableOf(kind: RedemptionKind): String = when (kind) {
            RedemptionKind.COUPON -> "market_coupon"
            RedemptionKind.CREATOR_CODE -> "market_creator_code"
            RedemptionKind.GIFT -> "market_gift"
            RedemptionKind.DISCOUNT -> "market_discount"
        }
    }
}

/**
 * [MarketDb.tx] for a use case whose block calls [Locks.forOrder]: when the order changed between the unlocked read and
 * its lock the transaction rolls back and the whole block runs again on the new state, [Locks.MAX_RESTARTS] times in all.
 * After the last one [MarketBusyException] is thrown (503 `STORE_BUSY` on buyer and panel routes, a retried event on
 * inbound routes). The block must be re-runnable, as for every [MarketDb.tx] block.
 */
suspend fun <T> MarketDb.txRestartingOnOrderChange(block: suspend (SqlConnection) -> T): T {
    var last: OrderChangedException? = null

    repeat(Locks.MAX_RESTARTS) {
        try {
            return tx(block)
        } catch (e: OrderChangedException) {
            last = e
        }
    }

    throw MarketBusyException(Locks.MAX_RESTARTS, last)
}
