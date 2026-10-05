package com.panomc.plugins.market.core.credit

import java.math.BigInteger

/**
 * Top-up clawback (07 section 8.5): the credits granted by credit-granting order items (`TOPUP` / `GIFT` txs) that are taken
 * back when a refund with `revoke = 1` succeeds (O10) or a dispute opens with `revokeOnChargeback` (O11). Pure; the posting
 * is `CreditService.clawback` (keys `refund:<refundId>:clawback:<itemId>` / `dispute:<disputeId>:clawback:<itemId>`,
 * `TAKE_AVAILABLE` for a refund, `ALLOW_DEBT` for a chargeback).
 *
 * ```
 * per credit-granting item i:
 *   granted_i = amount of its TOPUP / GIFT tx
 *   already_i = sum(amount + shortfall) of the earlier clawback txs for item i
 *   target_i  = granted_i                                          the order becomes REFUNDED, or a chargeback
 *             = floor(granted_i x ri.quantity / item.quantity)      a refund item with quantity > 0
 *             = floor(granted_i x ri.amount / item.lineTotal)       a refund item with quantity = 0
 *             = floor(granted_i x refund.amount / order.totalPrice) a refund without items
 *   request_i = min(target_i, granted_i - already_i)
 * ```
 * Reading of the spec's "(cumulative for the first and last rule)": the first rule's target is the whole grant, so its request
 * is `granted_i - already_i` (the same formula). The last rule's `refund.amount` is the amount of **this** refund (a refund
 * without items has nothing else to be measured by); its request is capped by what is still untaken, not reduced by
 * earlier clawbacks: subtracting them from a per-refund target would take back too little after an item refund, and using
 * the order's cumulative refunded total would count the refund of another product against the pack. In both readings
 * the sum of the requests never exceeds `granted_i` (invariant O8), shortfalls counted.
 */
object Clawback {
    /**
     * A credit-granting order item. [granted] is the amount of its `TOPUP` / `GIFT` tx, [already] the sum of `amount + shortfall`
     * of its earlier clawbacks, [quantity] and [lineTotal] those of the item (a credit top-up line has quantity 1).
     */
    class Item(val itemId: Long, val granted: Long, val already: Long, val quantity: Int, val lineTotal: Long) {
        init {
            require(granted >= 0 && already >= 0 && lineTotal >= 0) { "credit and money figures are never negative" }
            require(quantity >= 1) { "an item has at least one unit: $quantity" }
        }
    }

    /** One `market_refund_item`: [quantity] units, or an [amount] (money) when the quantity is 0. */
    class RefundItem(val itemId: Long, val quantity: Int, val amount: Long) {
        init {
            require(quantity >= 0 && amount >= 0) { "refund item figures are never negative" }
        }
    }

    sealed class Basis {
        /** The order becomes `REFUNDED`, or a chargeback: everything not taken back yet. */
        data object Everything : Basis()

        /** A refund with items: only the credit-granting items it names are clawed back. */
        class Items(val refundItems: List<RefundItem>) : Basis()

        /** A refund without items: [refundAmount] of this refund against [orderTotal] (`order.totalPrice`). */
        class Amount(val refundAmount: Long, val orderTotal: Long) : Basis() {
            init {
                require(refundAmount >= 0 && orderTotal >= 0) { "figures are never negative" }
            }
        }
    }

    /** One clawback posting to make: [amount] credits x 100 for item [itemId]. */
    data class Request(val itemId: Long, val amount: Long)

    /** The clawbacks to post for [items] under [basis]: only the positive ones, in the order of [items]. */
    fun compute(items: List<Item>, basis: Basis): List<Request> {
        val byItem: Map<Long, Pair<Long, Long>> = when (basis) {
            is Basis.Items -> {
                val sums = LinkedHashMap<Long, Pair<Long, Long>>() // itemId -> (quantity, amount)
                for (ri in basis.refundItems) {
                    val old = sums[ri.itemId] ?: (0L to 0L)
                    sums[ri.itemId] = Math.addExact(old.first, ri.quantity.toLong()) to Math.addExact(old.second, ri.amount)
                }
                sums
            }
            else -> emptyMap()
        }
        val out = ArrayList<Request>()
        for (item in items) {
            val untaken = maxOf(0L, item.granted - item.already)
            if (untaken == 0L) continue
            val target = when (basis) {
                Basis.Everything -> item.granted
                is Basis.Items -> {
                    val sum = byItem[item.itemId]
                    when {
                        sum == null -> 0L
                        sum.first > 0L -> share(item.granted, minOf(sum.first, item.quantity.toLong()), item.quantity.toLong())
                        else -> if (item.lineTotal == 0L) 0L else share(item.granted, minOf(sum.second, item.lineTotal), item.lineTotal)
                    }
                }
                is Basis.Amount -> if (basis.orderTotal == 0L) 0L else share(item.granted, minOf(basis.refundAmount, basis.orderTotal), basis.orderTotal)
            }
            val request = minOf(target, untaken)
            if (request > 0L) out += Request(item.itemId, request)
        }
        return out
    }

    /** `floor(granted x part / whole)`, exact. */
    private fun share(granted: Long, part: Long, whole: Long): Long =
        BigInteger.valueOf(granted).multiply(BigInteger.valueOf(part)).divide(BigInteger.valueOf(whole)).longValueExact()
}
