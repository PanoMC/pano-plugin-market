package com.panomc.plugins.market.core.refund

import com.panomc.plugins.market.core.credit.RefundSplit
import com.panomc.plugins.market.core.money.Rounding
import com.panomc.plugins.market.db.model.RefundStatus
import java.math.BigDecimal

/**
 * The remaining amounts of an order and the per-line bookkeeping of a refund (21 section 2, 3.1, 3.4). Pure.
 *
 * ```
 * succeeded : rT = refundedTotal, rG = refundedGatewayAmount, rCA = refundedCreditAmount, rCV = rT - rG     (the order's books)
 * in flight : rows of market_refund WHERE orderId = ? AND status IN ('REQUESTED','PENDING')
 *             pT = sum amount, pG = sum gatewayAmount, pCA = sum creditAmount, pCV = pT - pG
 * remT = T - rT - pT     remG = G - rG - pG     remCV = CV - rCV - pCV     remCA = CA - rCA - pCA
 * ```
 * Succeeded refunds are read from the order's books (they are what O10 wrote), in-flight ones from their rows; a `FAILED` or
 * `CANCELLED` row reserves nothing. This is the one definition: [RefundSplit] reads the same figures through [splitOrder], and
 * `RefundMathTest` checks that its own limits equal [RefundSplit]'s on thousands of random orders (damaged ones included).
 *
 * Money is x100 in the order currency, credits are x100 credit units; nothing here uses floating point.
 */
object RefundMath {
    /** `market_order` figures the remainders need (the books of what already came back, 07 section 7). */
    data class OrderAmounts(
        val totalPrice: Long,
        val gatewayAmount: Long,
        val creditValue: Long,
        val creditAmount: Long,
        val refundedTotal: Long = 0,
        val refundedGatewayAmount: Long = 0,
        val refundedCreditAmount: Long = 0,
        /** `CreditMath.unit(currency)`: 100 in a zero-decimal currency, else 1. */
        val unit: Long = 1,
        /** `userId IS NULL`: with a credit part the credits cannot be returned (07 section 7.1). */
        val anonymised: Boolean = false
    )

    /** The amounts of one `market_refund` row; only [status] decides whether it is in flight. */
    data class RefundAmounts(
        val id: Long,
        val status: RefundStatus,
        val amount: Long,
        val gatewayAmount: Long,
        val creditAmount: Long
    )

    /** Sums over the refunds in `REQUESTED` / `PENDING` (`pT`, `pG`, `pCA`; `pCV = pT - pG`). */
    data class InFlight(val total: Long, val gateway: Long, val credit: Long) {
        val creditValue: Long get() = total - gateway
    }

    /** `remT`, `remG`, `remCV`, `remCA`: what can still be refunded (the `max`, `maxGateway`, `maxCredit` of the 400 body). */
    data class Remaining(val total: Long, val gateway: Long, val creditValue: Long, val credit: Long)

    private val IN_FLIGHT = setOf(RefundStatus.REQUESTED, RefundStatus.PENDING)

    /**
     * The refunds that reserve part of the remainder. [excludingRefundId] leaves one row out: a retry is re-validated against
     * the remainder without its own reservation (21 section 3.3).
     */
    fun inFlight(refunds: List<RefundAmounts>, excludingRefundId: Long? = null): InFlight {
        var total = 0L
        var gateway = 0L
        var credit = 0L
        for (r in refunds) {
            if (r.status !in IN_FLIGHT || r.id == excludingRefundId) continue
            total = Math.addExact(total, r.amount)
            gateway = Math.addExact(gateway, r.gatewayAmount)
            credit = Math.addExact(credit, r.creditAmount)
        }
        return InFlight(total, gateway, credit)
    }

    /**
     * `remT / remG / remCV / remCA` of 21 section 2. Reads of a damaged order stay safe: a negative remainder counts as 0
     * (a gateway-side refund can book `refundedTotal` above `totalPrice`, 07 section 7.2), `remT` never exceeds `remG + remCV`,
     * and an anonymised order with credits returns none (`remCV = remCA = 0`, so the limit is what the gateway can still give back).
     * For a consistent order none of this changes a figure.
     */
    fun remaining(order: OrderAmounts, refunds: List<RefundAmounts>, excludingRefundId: Long? = null): Remaining =
        remaining(order, inFlight(refunds, excludingRefundId))

    fun remaining(order: OrderAmounts, inFlight: InFlight): Remaining {
        val g = maxOf(0L, order.gatewayAmount - order.refundedGatewayAmount - inFlight.gateway)
        val refundedValue = maxOf(0L, order.refundedTotal - order.refundedGatewayAmount)
        var cv = maxOf(0L, order.creditValue - refundedValue - maxOf(0L, inFlight.creditValue))
        var ca = maxOf(0L, order.creditAmount - order.refundedCreditAmount - inFlight.credit)
        if (order.anonymised && order.creditAmount > 0L) {
            cv = 0L
            ca = 0L
        }
        val t = minOf(maxOf(0L, order.totalPrice - order.refundedTotal - inFlight.total), g + cv)
        return Remaining(t, g, cv, ca)
    }

    /** The order as [RefundSplit] wants it, with the in-flight sums taken from [refunds] (same definition as [remaining]). */
    fun splitOrder(order: OrderAmounts, refunds: List<RefundAmounts>, excludingRefundId: Long? = null): RefundSplit.Order {
        val p = inFlight(refunds, excludingRefundId)
        return RefundSplit.Order(
            totalPrice = order.totalPrice,
            gatewayAmount = order.gatewayAmount,
            creditValue = order.creditValue,
            creditAmount = order.creditAmount,
            refundedTotal = order.refundedTotal,
            refundedGatewayAmount = order.refundedGatewayAmount,
            refundedCreditAmount = order.refundedCreditAmount,
            inFlightTotal = p.total,
            inFlightGatewayAmount = p.gateway,
            inFlightCreditAmount = p.credit,
            unit = order.unit,
            anonymised = order.anonymised
        )
    }

    /**
     * `RefundRequest.full` (21 section 3.3): the gateway part is the whole captured amount, nothing was refunded at the gateway
     * before, and no other refund of the order is in flight there. [inFlightGatewayWithThis] is `pG` including the row being sent.
     */
    fun isFullGatewayRefund(order: OrderAmounts, gatewayPart: Long, inFlightGatewayWithThis: Long): Boolean =
        gatewayPart == order.gatewayAmount && order.refundedGatewayAmount == 0L && inFlightGatewayWithThis == gatewayPart

    // ---------------------------------------------------------------- booking of a confirmed refund (O10 step 1)

    /** What O10 adds to the order's books for a refund that reached `SUCCEEDED` (07 section 7.2). */
    data class Booking(
        /** Added to `refundedTotal`: `gatewayPart + creditValuePart`. */
        val amount: Long,
        /** Added to `refundedGatewayAmount`; always the full gateway part. */
        val gatewayPart: Long,
        /** `amount - gatewayPart`. */
        val creditValuePart: Long,
        /** Credits that go back to the ledger and are added to `refundedCreditAmount`. */
        val creditPart: Long,
        /** The credit side was cut back to what is left (a gateway-side refund raced a panel refund). */
        val clamped: Boolean,
        /** `refundedTotal` would exceed `totalPrice`: booked anyway, the service raises the `OVER_REFUND` alert. */
        val overRefund: Boolean
    )

    /**
     * Books a refund the gateway confirmed (07 section 7.2 step 2): the credit part is clamped to what the books still hold
     * (`creditPart = min(creditPart, CA - rCA)`, the value part likewise against `CV - rCV`), the gateway part is **always**
     * booked in full (the money really left the account), and when `refundedTotal` ends above `totalPrice` it is booked anyway
     * and [Booking.overRefund] is set. The row is never failed because of this.
     */
    fun book(order: OrderAmounts, gatewayPart: Long, creditValuePart: Long, creditPart: Long): Booking {
        require(gatewayPart >= 0 && creditValuePart >= 0 && creditPart >= 0) { "refund parts are never negative" }
        val valueLeft = maxOf(0L, order.creditValue - maxOf(0L, order.refundedTotal - order.refundedGatewayAmount))
        val creditLeft = maxOf(0L, order.creditAmount - order.refundedCreditAmount)
        val value = minOf(creditValuePart, valueLeft)
        val credit = minOf(creditPart, creditLeft)
        val amount = Math.addExact(gatewayPart, value)
        return Booking(
            amount = amount,
            gatewayPart = gatewayPart,
            creditValuePart = value,
            creditPart = credit,
            clamped = value != creditValuePart || credit != creditPart,
            overRefund = Math.addExact(order.refundedTotal, amount) > order.totalPrice
        )
    }

    // ---------------------------------------------------------------- lines

    /** The part of an order line a refund works on (`market_order_item`). */
    data class Line(
        val itemId: Long,
        val quantity: Int,
        val lineTotal: Long,
        val refundedQuantity: Int = 0,
        val refundedAmount: Long = 0
    ) {
        /** What the line can still give back; never negative. */
        val remainingAmount: Long get() = maxOf(0L, lineTotal - refundedAmount)
        val remainingQuantity: Int get() = maxOf(0, quantity - refundedQuantity)
    }

    /** One requested `{orderItemId, quantity}` of a refund with items. */
    data class ItemRequest(val itemId: Long, val quantity: Int)

    /** One `market_refund_item` row. */
    data class ItemAmount(val itemId: Long, val quantity: Int, val amount: Long)

    enum class ItemsProblem { EMPTY, UNKNOWN_ITEM, DUPLICATE_ITEM, QUANTITY_OUT_OF_RANGE }

    sealed class ItemsResult {
        /** [total] is the refund's amount `A` (sum of the line amounts). */
        data class Ok(val items: List<ItemAmount>, val total: Long) : ItemsResult()
        data class Invalid(val problem: ItemsProblem, val itemId: Long? = null) : ItemsResult()
    }

    /**
     * The amount of [quantity] units of [line] (21 section 3.1): `lineTotal x quantity / line.quantity` rounded half up to
     * [unit], except that the **last unit takes the remainder** (refunding every unit still left gives `lineTotal - refundedAmount`),
     * so a line refunded in pieces adds up to its total. The result never exceeds what the line can still give back.
     * `quantity` must lie in `1..line.remainingQuantity`.
     */
    fun lineAmount(line: Line, quantity: Int, unit: Long = 1): Long {
        require(quantity in 1..line.remainingQuantity) { "quantity ${quantity} is outside 1..${line.remainingQuantity}" }
        require(line.quantity > 0) { "a line has at least one unit" }
        if (quantity == line.remainingQuantity) return line.remainingAmount
        val share = Rounding.ratioQ(
            BigDecimal.valueOf(line.lineTotal).multiply(BigDecimal.valueOf(quantity.toLong())),
            BigDecimal.valueOf(line.quantity.toLong()),
            unit
        )
        return minOf(share, line.remainingAmount)
    }

    /**
     * The rows and the amount `A` of a refund with `items[{orderItemId, quantity}]`. Answers [ItemsResult.Invalid] for an empty list,
     * an unknown or repeated item, or a quantity outside `1..remaining units`. A line that costs nothing (a bundle child) is
     * allowed and adds 0; the split then refuses an `A` of 0.
     */
    fun itemsAmount(lines: List<Line>, request: List<ItemRequest>, unit: Long = 1): ItemsResult {
        if (request.isEmpty()) return ItemsResult.Invalid(ItemsProblem.EMPTY)
        val byId = lines.associateBy { it.itemId }
        val seen = HashSet<Long>()
        val rows = ArrayList<ItemAmount>(request.size)
        var total = 0L
        for (r in request) {
            val line = byId[r.itemId] ?: return ItemsResult.Invalid(ItemsProblem.UNKNOWN_ITEM, r.itemId)
            if (!seen.add(r.itemId)) return ItemsResult.Invalid(ItemsProblem.DUPLICATE_ITEM, r.itemId)
            if (r.quantity < 1 || r.quantity > line.remainingQuantity) return ItemsResult.Invalid(ItemsProblem.QUANTITY_OUT_OF_RANGE, r.itemId)
            val amount = lineAmount(line, r.quantity, unit)
            rows += ItemAmount(r.itemId, r.quantity, amount)
            total = Math.addExact(total, amount)
        }
        return ItemsResult.Ok(rows, total)
    }

    /** One line's share of an amount-only refund. [quantity] is the units that count as refunded by it (see [spread]). */
    data class LineShare(val itemId: Long, val amount: Long, val quantity: Int)

    /**
     * [shares] in the order of the lines given; [unallocated] is the part of the amount that no line holds (shipping and the
     * payment fee are part of `totalPrice` but are not lines, 01 section 5.1).
     */
    data class Spread(val shares: List<LineShare>, val unallocated: Long)

    /**
     * The per-line `refundedAmount` of a refund without items (21 section 3.4 step 2): [amount] is spread over the lines in
     * proportion to `lineTotal - refundedAmount` by largest remainder (`Rounding.allocate`, 05 section 6.2), ties to the first
     * line, so the shares add up exactly and no line goes above its total. Quantities change **only** when the order becomes
     * fully refunded ([orderFullyRefunded]): then every line counts all its units as refunded ([LineShare.quantity] is the units
     * still open); otherwise the quantity is 0.
     *
     * Only the lines' remainders can take money: an [amount] above their sum (the rest is shipping or fee) leaves the difference
     * in [Spread.unallocated], and a line that cost nothing (a bundle child) gets nothing. [amount] must be a multiple of [unit].
     */
    fun spread(amount: Long, lines: List<Line>, orderFullyRefunded: Boolean, unit: Long = 1): Spread {
        require(amount >= 0) { "a refund amount is never negative: $amount" }
        require(unit == 1L || unit == 100L) { "the unit is 1 or 100: $unit" }
        require(amount % unit == 0L) { "the amount must be a multiple of the unit: $amount % $unit" }
        val weights = lines.map { it.remainingAmount }
        // A damaged line (a weight off the unit grid) falls back to the finest grid instead of failing a refund.
        val q = if (weights.all { it % unit == 0L }) unit else 1L
        val capacity = weights.fold(0L) { acc, w -> Math.addExact(acc, w) }
        val spreadAmount = minOf(amount, capacity - capacity % q)
        val shares = Rounding.allocate(spreadAmount, weights, q)
        val rows = lines.mapIndexed { i, line ->
            LineShare(line.itemId, shares[i], if (orderFullyRefunded) line.remainingQuantity else 0)
        }
        return Spread(rows, amount - spreadAmount)
    }
}
