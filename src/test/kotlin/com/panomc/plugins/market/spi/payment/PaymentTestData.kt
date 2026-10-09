package com.panomc.plugins.market.spi.payment

import com.panomc.plugins.market.spi.common.Money
import com.panomc.plugins.market.util.MarketPaths

/** Fixtures shared by the spi.payment tests. */
internal object PaymentTestData {
    fun eur(minor: Long) = Money(minor, "EUR")

    fun line(id: Long, totalMinor: Long, quantity: Int = 1, currency: String = "EUR", vat: Long = 2000L) = OrderLine(
        orderItemId = id, productId = id + 100, name = "Item $id", sku = "SKU$id", variantName = null, quantity = quantity,
        unitPrice = Money(totalMinor / quantity, currency), total = Money(totalMinor, currency), vatPercent = vat,
        physical = false, categoryName = null, providerMeta = null
    )

    fun order(
        lines: List<OrderLine>, shipping: Long = 0, fee: Long = 0, currency: String = "EUR", description: String = "Order #1"
    ): OrderSnapshot {
        val sub = lines.sumOf { it.total.amount }
        return OrderSnapshot(
            id = 1, publicId = "ABCDEFGHJKMNPQRSTVWX", description = description, currency = currency, lines = lines,
            subtotal = Money(sub, currency), discount = Money(0, currency), shipping = Money(shipping, currency),
            fee = Money(fee, currency), vat = Money(0, currency), total = Money(sub + shipping + fee, currency),
            creditValue = Money(0, currency), requiresShipping = shipping > 0, recipientUsername = "steve", gift = false,
            pricingMode = "MARKET"
        )
    }

    fun buyer() = BuyerInfo(
        userId = 7, guest = false, numericId = 7, stableId = "u7", username = "steve", email = "steve@example.com", ip = "203.0.113.5",
        userAgent = "test", locale = "en-US", firstName = null, lastName = null, phone = null, country = "TR", identityNumber = null,
        registeredAt = 1L
    )

    fun urls() = AttemptUrls(
        success = "https://shop.example${MarketPaths.SITE_ROOT}/payments/fake/return/tok/success",
        cancel = "https://shop.example${MarketPaths.SITE_ROOT}/payments/fake/return/tok/cancel",
        pending = "https://shop.example${MarketPaths.SITE_ROOT}/payments/fake/return/tok/pending",
        result = "https://shop.example${MarketPaths.SITE_ROOT}/payments/fake/return/tok/result",
        notify = "https://shop.example${MarketPaths.SITE_ROOT}/payments/fake/notify/tok",
        orderPage = "https://shop.example/store/order/ABCDEFGHJKMNPQRSTVWX"
    )

    fun attemptView(amount: Money = eur(1000)) = PaymentAttemptView(
        id = 5, reference = "ABCDEFGHJKMNPQRSTVWX", token = "tok", status = "PENDING", amount = amount, orderId = 1,
        orderPublicId = "ABCDEFGHJKMNPQRSTVWX", gatewayTransactionId = null, gatewayRefs = emptyMap(), providerData = null,
        testMode = false, createdAt = 1L, expiresAt = null, subscription = null, paidAmount = null, refundedAmount = Money(0, amount.currency), paidAt = null
    )

    fun subscriptionView() = SubscriptionView(
        id = 3, status = "ACTIVE", gatewaySubscriptionId = "sub_1", gatewayCustomerId = null, price = eur(500),
        intervalUnit = IntervalUnit.MONTH, intervalCount = 1, currentPeriodEnd = null, providerData = null, testMode = false
    )
}
