package com.panomc.plugins.market.spi.payment

import com.panomc.plugins.market.spi.MarketSpi
import com.panomc.plugins.market.spi.common.Currencies
import com.panomc.plugins.market.spi.common.Money
import com.panomc.plugins.market.spi.common.ProviderContext
import io.vertx.core.json.JsonObject
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

/** What a payment provider sees on top of [ProviderContext] (02 section 5). */
interface PaymentContext : ProviderContext {
    val urls: PaymentUrls
    val payments: PaymentLookup

    /**
     * Serialises work on one attempt inside this JVM (market already holds it for NOTIFY / RETURN, query, refund,
     * cancel).
     */
    suspend fun <T> withAttemptLock(attemptId: Long, block: suspend () -> T): T
}

interface PaymentUrls {
    /** `{base}/api/market/payments/{providerId}/webhook` plus `/{channel}` for a non-default channel. */
    fun webhook(channel: String = MarketSpi.DEFAULT_CHANNEL): String

    /** `{base}/store/checkout`. */
    fun checkoutPage(): String

    /** Per-attempt URLs outside start / continue (refund status URLs, provider-level callbacks). */
    fun forAttempt(attempt: PaymentAttemptView): AttemptUrls
}

/** Read-only views, this provider's attempts only. */
interface PaymentLookup {
    suspend fun byId(attemptId: Long): PaymentAttemptView?

    suspend fun byReference(reference: String): PaymentAttemptView?

    suspend fun byGatewayTransactionId(id: String): PaymentAttemptView?

    /** For example `("session", "cs_...")`. */
    suspend fun byGatewayRef(name: String, value: String): PaymentAttemptView?

    suspend fun subscriptionByGatewayId(gatewaySubscriptionId: String): SubscriptionView?
}

/** `id`: numeric reference; `reference`: `[A-Z0-9]{20}`; `token`: URL capability. */
class AttemptRef(val id: Long, val reference: String, val token: String)

/** The URLs of one attempt. Market constructs it, the provider only reads it. */
class AttemptUrls(
    /** `{base}/api/market/payments/{providerId}/return/{token}/success` (no query string). */
    val success: String,
    /** `.../return/{token}/cancel`. */
    val cancel: String,
    /** `.../return/{token}/pending`. */
    val pending: String,
    /** `.../return/{token}/result`: outcome unknown, for gateways with a single callback URL. */
    val result: String,
    /** `{base}/api/market/payments/{providerId}/notify/{token}`: per-attempt server-to-server URL. */
    val notify: String,
    /** `{base}/store/order/{publicId}`. */
    val orderPage: String
) {
    /** `.../notify/{token}/{channel}`, channel `[a-z0-9-]{1,32}`. */
    fun notify(channel: String): String {
        require(NAME.matches(channel)) { "Invalid notify channel '$channel'" }
        return "$notify/$channel"
    }

    /**
     * `.../return/{token}/step/{name}`, name `[a-z0-9-]{1,32}`: an intermediate browser hop (Tebex basket auth);
     * the provider must answer it with a redirect.
     */
    fun step(name: String): String {
        require(NAME.matches(name)) { "Invalid step name '$name'" }
        require(success.endsWith("/success")) { "success URL does not end with /success" }
        return success.removeSuffix("/success") + "/step/" + name
    }

    private companion object {
        val NAME = Regex("^[a-z0-9-]{1,32}$")
    }
}

/** `vatPercent` is in basis points. */
class OrderLine(
    val orderItemId: Long,
    val productId: Long?,
    val name: String,
    val sku: String?,
    val variantName: String?,
    val quantity: Int,
    val unitPrice: Money,
    val total: Money,
    val vatPercent: Long,
    val physical: Boolean,
    val categoryName: String?,
    val providerMeta: JsonObject?
) {
    companion object {
        /** `orderItemId` of the synthetic shipping line of balancedLines. */
        const val SHIPPING_LINE_ID = -1L

        /** `orderItemId` of the synthetic fee line. */
        const val FEE_LINE_ID = -2L

        /** `orderItemId` of the single line of [OrderSnapshot.singleLine]. */
        const val SINGLE_LINE_ID = 0L
    }
}

class OrderSnapshot(
    val id: Long,
    val publicId: String,
    val description: String,
    val currency: String,
    val lines: List<OrderLine>,
    val subtotal: Money,
    val discount: Money,
    val shipping: Money,
    val fee: Money,
    val vat: Money,
    val total: Money,
    val creditValue: Money,
    val requiresShipping: Boolean,
    val recipientUsername: String,
    val gift: Boolean,
    val pricingMode: String
) {
    /**
     * Lines adjusted so that the sum of their totals equals [amount] (discount spread, shipping and fee as lines,
     * credit part removed). Deterministic for (order, amount): a non-zero shipping and fee become synthetic lines with
     * the reserved ids [OrderLine.SHIPPING_LINE_ID] / [OrderLine.FEE_LINE_ID] (appended in that order); the
     * reduction (discounts, credit part) is spread over the real lines proportionally to their totals with half-up
     * rounding to the currency quantum, and the last real line absorbs the remainder.
     *
     * Edge cases: when the real lines sum to zero the whole target goes on the last real line; when [amount] is
     * smaller than shipping plus fee the reduction is spread over every line including the synthetic ones, so no
     * line total is ever negative; an order without real lines needs `amount == shipping + fee`.
     *
     * The unit price of an adjusted line is its new total divided by the quantity (half-up to the quantum); only the
     * totals are an invariant.
     */
    fun balancedLines(amount: Money): List<OrderLine> {
        require(amount.currency == currency) { "Amount currency ${amount.currency} differs from the order currency $currency" }
        require(amount.amount >= 0) { "Amount must not be negative" }
        val quantum = quantum(currency)
        val real = lines
        val shippingAmount = shipping.amount
        val feeAmount = fee.amount
        require(shippingAmount >= 0 && feeAmount >= 0) { "Shipping and fee must not be negative" }
        val syntheticTotal = Math.addExact(shippingAmount, feeAmount)

        val shippingLine = if (shippingAmount != 0L) synthetic(OrderLine.SHIPPING_LINE_ID, "Shipping", shippingAmount) else null
        val feeLine = if (feeAmount != 0L) synthetic(OrderLine.FEE_LINE_ID, "Fee", feeAmount) else null

        if (real.isEmpty()) {
            require(amount.amount == syntheticTotal) { "An order without lines can only be balanced to shipping plus fee" }
            return listOfNotNull(shippingLine, feeLine)
        }

        val realTotal = real.fold(0L) { acc, line -> Math.addExact(acc, line.total.amount) }
        val remaining = amount.amount - syntheticTotal
        if (remaining >= 0) {
            val totals = spread(real.map { it.total.amount }, realTotal, remaining, quantum)
            return real.mapIndexed { i, line -> adjusted(line, totals[i], quantum) } + listOfNotNull(shippingLine, feeLine)
        }

        // The amount does not even cover shipping and fee: spread over every line, synthetic ones included.
        val everything = real + listOfNotNull(shippingLine, feeLine)
        val sum = everything.fold(0L) { acc, line -> Math.addExact(acc, line.total.amount) }
        val totals = spread(everything.map { it.total.amount }, sum, amount.amount, quantum)
        return everything.mapIndexed { i, line -> adjusted(line, totals[i], quantum) }
    }

    /** One line carrying the whole [amount] (gateways that take a single description and price). */
    fun singleLine(amount: Money): OrderLine {
        require(amount.currency == currency) { "Amount currency ${amount.currency} differs from the order currency $currency" }
        val vats = lines.map { it.vatPercent }.distinct()
        return OrderLine(
            orderItemId = OrderLine.SINGLE_LINE_ID, productId = null, name = description, sku = null, variantName = null,
            quantity = 1, unitPrice = amount, total = amount, vatPercent = vats.singleOrNull() ?: 0L,
            physical = requiresShipping, categoryName = null, providerMeta = null
        )
    }

    private fun synthetic(id: Long, name: String, amount: Long): OrderLine {
        val money = Money(amount, currency)
        return OrderLine(id, null, name, null, null, 1, money, money, 0L, false, null, null)
    }

    private fun adjusted(line: OrderLine, newTotal: Long, quantum: Long): OrderLine {
        if (newTotal == line.total.amount) return line
        val total = Money(newTotal, currency)
        val unit = if (line.quantity <= 0) total else Money(
            divideHalfUp(BigInteger.valueOf(newTotal), BigInteger.valueOf(line.quantity.toLong() * quantum)).multiply(BigInteger.valueOf(quantum)).longValueExact(),
            currency
        )
        return OrderLine(
            line.orderItemId, line.productId, line.name, line.sku, line.variantName, line.quantity, unit, total,
            line.vatPercent, line.physical, line.categoryName, line.providerMeta
        )
    }

    private companion object {
        /** Smallest step an amount of [currency] moves in: whole units (100) for a zero-decimal currency, else 1. */
        fun quantum(currency: String): Long = if (Currencies.exponent(currency) == 0) 100L else 1L

        /** Half-up (away from zero on ties) integer division; the denominator is positive. */
        fun divideHalfUp(numerator: BigInteger, denominator: BigInteger): BigInteger =
            BigDecimal(numerator).divide(BigDecimal(denominator), 0, RoundingMode.HALF_UP).toBigIntegerExact()

        /**
         * Distributes [target] over [weights] proportionally (each share is rounded half-up to [quantum]); the last
         * entry absorbs the remainder so the shares add up to [target] exactly.
         */
        fun spread(weights: List<Long>, weightSum: Long, target: Long, quantum: Long): List<Long> {
            if (weightSum == 0L) return weights.indices.map { if (it == weights.lastIndex) target else 0L }
            val out = ArrayList<Long>(weights.size)
            var used = 0L
            for (i in 0 until weights.lastIndex) {
                val rounded = divideHalfUp(
                    BigInteger.valueOf(weights[i]).multiply(BigInteger.valueOf(target)),
                    BigInteger.valueOf(weightSum).multiply(BigInteger.valueOf(quantum))
                ).multiply(BigInteger.valueOf(quantum)).longValueExact()
                // Rounding up every share can overshoot the target by a few quanta: never hand out more than is left,
                // so the last line (which absorbs the remainder) can not go negative.
                val share = minOf(rounded, target - used)
                out.add(share)
                used = Math.addExact(used, share)
            }
            out.add(target - used)
            return out
        }
    }
}

class BuyerInfo(
    val userId: Long?,
    val guest: Boolean,
    /** `userId`, or a stable positive synthetic id for a guest (2^62 and up, derived from the username). */
    val numericId: Long,
    /** The buyer key, at most 64 chars. */
    val stableId: String,
    val username: String,
    val email: String?,
    /** Null for merchant-initiated charges (no buyer present); a provider that must send one uses the server address from `ctx.site`. */
    val ip: String?,
    val userAgent: String?,
    val locale: String,
    val firstName: String?,
    val lastName: String?,
    val phone: String?,
    val country: String?,
    val identityNumber: String?,
    val registeredAt: Long?
)

class SubscriptionPlan(
    val subscriptionId: Long,
    /** Stable hash of product, variant, price, currency, interval: cache key for remote plans. */
    val planKey: String,
    val productName: String,
    val price: Money,
    val intervalUnit: IntervalUnit,
    val intervalCount: Int,
    val maxCycles: Int?
)

/** One attempt as market stores it. Market constructs it, the provider only reads it. */
class PaymentAttemptView(
    val id: Long,
    val reference: String,
    val token: String,
    val status: String,
    val amount: Money,
    val orderId: Long,
    val orderPublicId: String,
    val gatewayTransactionId: String?,
    val gatewayRefs: Map<String, String>,
    val providerData: JsonObject?,
    val testMode: Boolean,
    val createdAt: Long,
    val expiresAt: Long?,
    val subscription: SubscriptionView?,
    /** What the gateway collected (may exceed `amount`: instalment surcharge). */
    val paidAmount: Money?,
    /** Gateway money already returned for this attempt. */
    val refundedAmount: Money,
    val paidAt: Long?
)

class SubscriptionView(
    val id: Long,
    val status: String,
    val gatewaySubscriptionId: String?,
    val gatewayCustomerId: String?,
    val price: Money,
    val intervalUnit: IntervalUnit,
    val intervalCount: Int,
    val currentPeriodEnd: Long?,
    val providerData: JsonObject?,
    val testMode: Boolean
)

/** Used by [PaymentProvider.checkEligibility] (02 section 8). */
class CheckoutSnapshot(
    val order: OrderSnapshot,
    val buyer: BuyerInfo,
    val subscription: SubscriptionPlan?,
    val hasPanoPriceModifiers: Boolean
)
