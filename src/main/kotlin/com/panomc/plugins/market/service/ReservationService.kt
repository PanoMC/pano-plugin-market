package com.panomc.plugins.market.service

import com.panomc.plugins.market.core.time.Clock
import com.panomc.plugins.market.db.dao.MarketOrderDao
import com.panomc.plugins.market.db.model.OrderItemKind
import com.panomc.plugins.market.db.model.ReservationState
import com.panomc.plugins.market.db.tx.LockedOrder
import com.panomc.plugins.market.db.tx.LockedStock
import com.panomc.plugins.market.db.tx.Locks
import com.panomc.plugins.market.db.tx.OrderLockScope
import com.panomc.plugins.market.error.OutOfStock
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple

/**
 * One quantity of one stock subject a line asks for (06 section 7.1). The subject is the variant row when [variantId] is
 * given (the product has variants: its own stock is ignored), else the product row. A bundle line becomes one demand for
 * the bundle product (when it has stock) and one per child with `line quantity x child quantity`, all with the same
 * [lineKey]. [itemKey] names the order item the units belong to, so the result can say how many units each item holds.
 */
class StockDemand(
    val lineKey: String,
    val itemKey: String,
    val productId: Long,
    val variantId: Long?,
    val quantity: Int
)

/** What [ReservationService.reserve] took: the units deducted for each [StockDemand.itemKey] (0 for an unlimited subject), the value of `market_order_item.stockReserved`. */
class Reservation(val stockReserved: Map<String, Int>)

/**
 * Stock, code usage and their release for an order (06 section 7). The methods run on the connection of the caller's
 * `MarketDb.tx` and are idempotent through the order's `reservationState`: `HELD` at O1, `COMMITTED` at O2 / O4,
 * `RELEASED` at O5 to O8; a state change is a conditional update and a step runs only when one row changed.
 *
 * - [reserve] (B8 and B9 of the order transaction): stock by one conditional `UPDATE` per stock subject, then the
 *   limits and counters of codes and discounts ([RedemptionService.reserve]). It locks everything it touches in the
 *   global order itself (codes, discounts, products, variants), so it is safe on its own; checkout takes the same locks
 *   earlier (B2 to B4), which makes them no-ops here.
 * - [commit], [release], [reReserve]: the three reservation operations of an existing order. The order must have been
 *   locked by `Locks.forOrder`, whose scope guarantees the rows these steps touch are locked in order: [release] and
 *   [reReserve] need `RELEASE`, [commit] `COMMIT` or `RELEASE`.
 *
 * Not here: the credit hold, capture and release of 06 section 7.3 and the cancellation of a pending subscription
 * row (those services do not exist yet; their call sites are the `commit` / `release` / `reReserve` transitions) and
 * the per-player limit and cooldown counts of 06 section 6.4 (the quote and checkout slices own them; they run under
 * the same product locks).
 */
class ReservationService(
    private val clock: Clock,
    private val locks: Locks,
    private val redemptions: RedemptionService,
    private val orders: MarketOrderDao
) {
    private fun table(name: String) = "`${orders.prefix()}$name`"

    /** A stock subject: a variant or a product row. Products sort before variants, then by id: the lock order of level 3. */
    private data class Subject(val variant: Boolean, val id: Long) : Comparable<Subject> {
        override fun compareTo(other: Subject): Int = compareValuesBy(this, other, { it.variant }, { it.id })
    }

    // ----- reserve ---------------------------------------------------------------------------------------------------

    /**
     * Reserves [demands] and the use of [uses] by [customer] (B8, then B9). [force] is the manual order of 06 section 14.3:
     * the stock statement becomes `GREATEST(stock - ?, 0)` and a demand holds `min(requested, stock before)`.
     *
     * Throws `OutOfStock` with the line keys of **every** failing line (not only the first), then the code errors of
     * [RedemptionService.reserve]; the caller's transaction rolls everything back.
     */
    suspend fun reserve(
        conn: SqlConnection,
        demands: List<StockDemand>,
        uses: List<CodeUse> = emptyList(),
        customer: CustomerKeys? = null,
        force: Boolean = false
    ): Reservation {
        require(uses.isEmpty() || customer != null) { "the customer is needed to count the per-customer limit of a code" }

        // Lock order: codes and discounts (levels 1 and 2) before products and variants (level 3).
        redemptions.lockFor(conn, uses)

        val reservation = reserveStock(conn, demands, force)

        if (uses.isNotEmpty()) redemptions.reserve(conn, uses, customer!!)

        return reservation
    }

    private suspend fun reserveStock(conn: SqlConnection, demands: List<StockDemand>, force: Boolean): Reservation {
        if (demands.isEmpty()) return Reservation(emptyMap())

        require(demands.all { it.quantity > 0 }) { "a stock demand needs a positive quantity" }
        require(demands.map { it.itemKey }.toSet().size == demands.size) { "an order item is reserved once" }

        val products = locks.products(conn, demands.map { it.productId })
        val variants = locks.variants(conn, demands.mapNotNull { it.variantId })

        val bySubject = demands.groupBy { Subject(it.variantId != null, it.variantId ?: it.productId) }.toSortedMap()
        val reserved = LinkedHashMap<String, Int>()
        val failing = LinkedHashSet<String>()

        for ((subject, subjectDemands) in bySubject) {
            val row: LockedStock? = (if (subject.variant) variants else products)[subject.id]
            val total = subjectDemands.sumOf { it.quantity.toLong() }
            val tableName = if (subject.variant) "market_product_variant" else "market_product"

            if (row == null) {
                // A row that does not exist cannot be sold from.
                subjectDemands.forEach { failing += it.lineKey }

                continue
            }

            val stock = row.stock

            if (stock == null) {
                // Unlimited: no statement, nothing held.
                subjectDemands.forEach { reserved[it.itemKey] = 0 }

                continue
            }

            if (force) {
                conn.preparedQuery("UPDATE ${table(tableName)} SET `stock` = GREATEST(`stock` - ?, 0), `updatedAt` = ? WHERE `id` = ? AND `stock` IS NOT NULL")
                    .execute(Tuple.of(total, clock.now(), subject.id)).coAwait()

                var remaining = stock.toLong()

                for (demand in subjectDemands) {
                    val taken = minOf(demand.quantity.toLong(), remaining)
                    remaining -= taken
                    reserved[demand.itemKey] = taken.toInt()
                }
            } else {
                val changed = conn.preparedQuery(
                    "UPDATE ${table(tableName)} SET `stock` = `stock` - ?, `updatedAt` = ? WHERE `id` = ? AND `stock` IS NOT NULL AND `stock` >= ?"
                ).execute(Tuple.of(total, clock.now(), subject.id, total)).coAwait().rowCount()

                if (changed == 0) subjectDemands.forEach { failing += it.lineKey } else subjectDemands.forEach { reserved[it.itemKey] = it.quantity }
            }
        }

        if (failing.isNotEmpty()) throw OutOfStock(failing.toList())

        return Reservation(reserved)
    }

    // ----- commit ----------------------------------------------------------------------------------------------------

    /**
     * O2 / O4: `HELD` becomes `COMMITTED`; when one row changed, the redemptions become `APPLIED` and `soldCount` grows
     * by the units of every item (bundle children included) per product, ascending id, unless the order is a test order.
     * Stock is not touched: the units were deducted at [reserve]. Returns `false` when the order was not `HELD`.
     */
    suspend fun commit(conn: SqlConnection, locked: LockedOrder): Boolean {
        require(locked.scope == OrderLockScope.COMMIT || locked.scope == OrderLockScope.RELEASE) {
            "commit needs the products locked: lock the order with scope COMMIT or RELEASE"
        }

        if (!moveState(conn, locked.order.id, ReservationState.HELD, ReservationState.COMMITTED)) return false

        redemptions.commit(conn, locked.order.id)

        if (!locked.order.testMode) {
            val sold = sortedMapOf<Long, Long>()

            for (item in locked.items) {
                val productId = item.productId ?: continue

                if (item.kind == OrderItemKind.CREDIT_TOPUP) continue

                sold.merge(productId, item.quantity.toLong(), Long::plus)
            }

            for ((productId, quantity) in sold) {
                conn.preparedQuery("UPDATE ${table("market_product")} SET `soldCount` = `soldCount` + ? WHERE `id` = ?")
                    .execute(Tuple.of(quantity, productId)).coAwait()
            }
        }

        return true
    }

    // ----- release ---------------------------------------------------------------------------------------------------

    /**
     * O5 to O8: `HELD` becomes `RELEASED`; when one row changed, the stock of every item goes back to its subject
     * (`stock + stockReserved`, products before variants, ascending id; a subject that became unlimited stays unlimited),
     * `stockReserved` is zeroed, and the redemptions are released with their counters ([RedemptionService.release]).
     * Returns `false` when the order was not `HELD` (a second release changes nothing).
     */
    suspend fun release(conn: SqlConnection, locked: LockedOrder): Boolean {
        require(locked.scope == OrderLockScope.RELEASE) { "release needs the codes and stock rows locked: lock the order with scope RELEASE" }

        if (!moveState(conn, locked.order.id, ReservationState.HELD, ReservationState.RELEASED)) return false

        val back = sortedMapOf<Subject, Long>()

        for (item in locked.items) {
            if (item.stockReserved <= 0) continue

            val subject = Subject(item.variantId != null, item.variantId ?: item.productId ?: continue)
            back.merge(subject, item.stockReserved.toLong(), Long::plus)
        }

        for ((subject, units) in back) {
            val tableName = if (subject.variant) "market_product_variant" else "market_product"

            conn.preparedQuery("UPDATE ${table(tableName)} SET `stock` = `stock` + ?, `updatedAt` = ? WHERE `id` = ? AND `stock` IS NOT NULL")
                .execute(Tuple.of(units, clock.now(), subject.id)).coAwait()
        }

        conn.preparedQuery("UPDATE ${table("market_order_item")} SET `stockReserved` = 0, `updatedAt` = ? WHERE `orderId` = ? AND `stockReserved` > 0")
            .execute(Tuple.of(clock.now(), locked.order.id)).coAwait()

        redemptions.release(conn, locked.order.id)

        return true
    }

    // ----- re-reserve ------------------------------------------------------------------------------------------------

    /**
     * O4 on an order that came through O9 (`reservationState = RELEASED`): the way back to `HELD`. Strict by default:
     * every item's stock is taken again with the conditional statement (`OutOfStock` when one cannot be, the order stays
     * `RELEASED`), the released redemptions return to `HELD` and their counters grow without the limit condition
     * ([RedemptionService.reHold]; a vanished coupon, creator code or gift fails with `CODE_NOT_FOUND`). With [force]
     * (an admin decision, written to the timeline by the caller) codes and limits are not re-reserved and stock is
     * decremented with the forced statement, clamped at 0. The caller follows with [commit] in the same transaction.
     * Returns `false` when the order was not `RELEASED`.
     */
    suspend fun reReserve(conn: SqlConnection, locked: LockedOrder, force: Boolean = false): Boolean {
        require(locked.scope == OrderLockScope.RELEASE) { "reReserve needs the codes and stock rows locked: lock the order with scope RELEASE" }

        if (!moveState(conn, locked.order.id, ReservationState.RELEASED, ReservationState.HELD)) return false

        val demands = locked.items
            .filter { it.kind != OrderItemKind.CREDIT_TOPUP && it.productId != null && it.quantity > 0 }
            .map { StockDemand((it.parentItemId ?: it.id).toString(), it.id.toString(), it.productId!!, it.variantId, it.quantity) }

        val reservation = reserveStock(conn, demands, force)

        for ((itemId, units) in reservation.stockReserved) {
            if (units <= 0) continue

            conn.preparedQuery("UPDATE ${table("market_order_item")} SET `stockReserved` = ?, `updatedAt` = ? WHERE `id` = ?")
                .execute(Tuple.of(units, clock.now(), itemId.toLong())).coAwait()
        }

        if (!force) redemptions.reHold(conn, locked.order.id)

        return true
    }

    /** `UPDATE market_order SET reservationState = to WHERE id = ? AND reservationState = from`; the order's `updatedAt` grows strictly (06 section 13.2). */
    private suspend fun moveState(conn: SqlConnection, orderId: Long, from: ReservationState, to: ReservationState): Boolean =
        conn.preparedQuery(
            "UPDATE ${table("market_order")} SET `reservationState` = ?, `updatedAt` = GREATEST(?, `updatedAt` + 1) WHERE `id` = ? AND `reservationState` = ?"
        ).execute(Tuple.of(to.name, clock.now(), orderId, from.name)).coAwait().rowCount() == 1
}
