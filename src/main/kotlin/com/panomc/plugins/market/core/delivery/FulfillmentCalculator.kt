package com.panomc.plugins.market.core.delivery

import com.panomc.plugins.market.db.model.DeliveryPhase
import com.panomc.plugins.market.db.model.DeliverySourceType
import com.panomc.plugins.market.db.model.DeliveryStatus
import com.panomc.plugins.market.db.model.EntitlementStatus
import com.panomc.plugins.market.db.model.FulfillmentBy
import com.panomc.plugins.market.db.model.FulfillmentStatus

/**
 * `market_order.fulfillmentStatus` from delivery rows (08 section 13), pure. The same function over the rows of one
 * item gives `OrderView.items[].delivery` ([perItem]).
 *
 * An order is never reported as `REVOKED` while the buyer may still have the goods: an undo (`REVOKE` / `EXPIRE` row)
 * that has not happened yet, or failed, makes it `PARTIAL` first (invariant I21), and the panel shows
 * [Result.revokePending] / [Result.revokeFailed].
 */
object FulfillmentCalculator {
    /**
     * [revokePending]: effective `REVOKE` / `EXPIRE` rows that are still open (not `CONFIRMED`, `FAILED` or `CANCELLED`),
     * [revokeFailed]: effective ones that are `FAILED`.
     */
    data class Result(val status: FulfillmentStatus, val revokePending: Int, val revokeFailed: Int)

    private val DONE_OR_GONE = setOf(DeliveryStatus.CONFIRMED, DeliveryStatus.FAILED, DeliveryStatus.CANCELLED)

    /** A logical delivery whose effective row is not `CONFIRMED` / `FAILED` / `CANCELLED` is still in progress. */
    fun isOpen(status: DeliveryStatus): Boolean = status !in DONE_OR_GONE

    /**
     * The effective row per `(orderItemId, actionId, serverId, unitIndex, phase)`: the one with the highest
     * `attemptGroup` (a re-run supersedes the row it repeats). Only `ORDER_ITEM` rows take part. First seen wins a tie.
     */
    fun effective(rows: List<DeliveryRow>): List<DeliveryRow> {
        val best = LinkedHashMap<List<Any?>, DeliveryRow>()

        for (row in rows) {
            if (row.sourceType != DeliverySourceType.ORDER_ITEM) continue

            val key = listOf(row.orderItemId, row.actionId, row.serverId, row.unitIndex, row.phase)
            val seen = best[key]

            if (seen == null || row.attemptGroup > seen.attemptGroup) best[key] = row
        }

        return best.values.toList()
    }

    /**
     * [rows] are the `ORDER_ITEM` rows of the order (or of one item) in any phase, [entitlements] the statuses of its
     * entitlements. An order with `fulfillmentBy = GATEWAY` has no rows and stays `NONE`.
     */
    fun calculate(
        rows: List<DeliveryRow>,
        entitlements: List<EntitlementStatus> = emptyList(),
        fulfillmentBy: FulfillmentBy = FulfillmentBy.MARKET
    ): Result {
        if (fulfillmentBy == FulfillmentBy.GATEWAY) return Result(FulfillmentStatus.NONE, 0, 0)

        val effective = effective(rows).filter { it.status != DeliveryStatus.CANCELLED }

        val undo = effective.filter { it.phase == DeliveryPhase.REVOKE || it.phase == DeliveryPhase.EXPIRE }
        val revokePending = undo.count { isOpen(it.status) }
        val revokeFailed = undo.count { it.status == DeliveryStatus.FAILED }

        val delivered = effective.filter { it.phase == DeliveryPhase.GRANT || it.phase == DeliveryPhase.RENEW }
        val c = delivered.count { it.status == DeliveryStatus.CONFIRMED }
        val f = delivered.count { it.status == DeliveryStatus.FAILED }
        val o = delivered.size - c - f

        val status = when {
            // The undo has not happened yet (or failed): the goods may still be with the buyer.
            undo.any { it.status != DeliveryStatus.CONFIRMED } -> FulfillmentStatus.PARTIAL
            entitlements.isNotEmpty() && entitlements.all { it == EntitlementStatus.REVOKED } -> FulfillmentStatus.REVOKED
            delivered.isEmpty() -> FulfillmentStatus.NONE
            f == 0 && o == 0 -> FulfillmentStatus.FULFILLED
            f == 0 && c == 0 -> FulfillmentStatus.PENDING
            f > 0 && c == 0 && o == 0 -> FulfillmentStatus.FAILED
            else -> FulfillmentStatus.PARTIAL
        }

        return Result(status, revokePending, revokeFailed)
    }

    fun status(
        rows: List<DeliveryRow>,
        entitlements: List<EntitlementStatus> = emptyList(),
        fulfillmentBy: FulfillmentBy = FulfillmentBy.MARKET
    ): FulfillmentStatus = calculate(rows, entitlements, fulfillmentBy).status

    /**
     * Value per order item (`OrderView.items[].delivery`): [rows] grouped by `orderItemId`, each item's entitlements
     * from [entitlementsByItem]. Items that have neither rows nor entitlements are not in the result.
     */
    fun perItem(
        rows: List<DeliveryRow>,
        entitlementsByItem: Map<Long, List<EntitlementStatus>> = emptyMap(),
        fulfillmentBy: FulfillmentBy = FulfillmentBy.MARKET
    ): Map<Long, FulfillmentStatus> {
        val itemRows = rows.filter { it.orderItemId != null }.groupBy { it.orderItemId!! }
        val ids = (itemRows.keys + entitlementsByItem.keys).sorted()

        return ids.associateWith { id -> status(itemRows[id].orEmpty(), entitlementsByItem[id].orEmpty(), fulfillmentBy) }
    }
}
