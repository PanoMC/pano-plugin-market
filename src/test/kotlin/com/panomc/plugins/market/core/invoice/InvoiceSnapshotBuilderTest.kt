package com.panomc.plugins.market.core.invoice

import com.panomc.plugins.market.db.model.InvoiceType
import com.panomc.plugins.market.db.model.MarketInvoice
import com.panomc.plugins.market.db.model.MarketOrder
import com.panomc.plugins.market.db.model.MarketOrderItem
import com.panomc.plugins.market.db.model.MarketRefund
import com.panomc.plugins.market.db.model.MarketRefundItem
import com.panomc.plugins.market.db.model.OrderItemKind
import com.panomc.plugins.market.db.model.OrderSource
import com.panomc.plugins.market.db.model.RefundStatus
import com.panomc.plugins.market.util.OrderStatus
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `InvoiceSnapshotBuilder` (12 sections 7.1 to 7.3; tests T-INV-1 to T-INV-3 of 12 section 12): invoice lines, VAT rows and totals,
 * the consistency failures, and the credit-note split with the largest remainder method. Pure: no database.
 */
class InvoiceSnapshotBuilderTest {
    private val config = InvoiceSnapshotConfig(
        sellerName = "Acme Ltd", sellerAddress = "1 Main St\nTown", sellerTaxOffice = "Kadikoy", sellerTaxNumber = "123456",
        footer = "Thank you", websiteName = "Acme Craft", websiteUrl = "https://acme.example", timeZone = "UTC"
    )

    private val issuedAt = 1_780_000_000_000L

    private var nextId = 0L

    private fun newOrder(
        total: Long,
        currency: String = "EUR",
        gateway: Long = total,
        includeVat: Boolean = true,
        columns: MarketOrder.() -> Unit = {},
        shipping: Long = 0,
        shippingVat: Long = 0,
        shippingPercent: Long = 0,
        shippingName: String? = null,
        fee: Long = 0,
        feeVat: Long = 0,
        feePercent: Long = 0,
        vatTotal: Long = 0,
        source: OrderSource = OrderSource.STOREFRONT,
        billing: String? = null,
        testMode: Boolean = false,
        email: String? = "buyer@example.com",
        paidAt: Long? = issuedAt - 5_000,
        subtotal: Long = total,
        discount: Long = 0,
        coupon: Long = 0,
        creator: Long = 0,
        upgrade: Long = 0,
        creditValue: Long = 0,
        creditAmount: Long = 0
    ): MarketOrder {
        val order = MarketOrder(
            id = 812, playerUsername = "Steve", totalPrice = total, currency = currency, paymentMethodId = "fake", paymentLabel = "Card",
            status = OrderStatus.COMPLETED, createdAt = issuedAt - 60_000, publicId = "ABCDEFGHJKMNPQRSTVWX", email = email, source = source,
            recipientUsername = "Steve", pricesIncludeVat = includeVat, subtotal = subtotal, discountTotal = discount, couponDiscount = coupon,
            creatorDiscount = creator, upgradeDiscount = upgrade, shippingTotal = shipping, shippingVatAmount = shippingVat,
            shippingVatPercent = shippingPercent, shippingMethodName = shippingName, paymentFee = fee, paymentFeeVatAmount = feeVat,
            paymentFeeVatPercent = feePercent, vatTotal = vatTotal, creditValue = creditValue, creditAmount = creditAmount,
            gatewayAmount = gateway, paidAt = paidAt, billingInfo = billing, testMode = testMode
        )

        order.columns()

        return order
    }

    private fun item(
        gross: Long,
        vat: Long,
        percent: Long = 2000,
        name: String = "Rank VIP",
        quantity: Int = 1,
        list: Long = gross,
        discount: Long = 0,
        upgrade: Long = 0,
        coupon: Long = 0,
        kind: OrderItemKind = OrderItemKind.PRODUCT,
        variant: String? = null,
        sku: String? = null,
        id: Long = ++nextId
    ) = MarketOrderItem(
        id = id, orderId = 812, productName = name, quantity = quantity, unitPrice = list, kind = kind, variantName = variant, sku = sku,
        listUnitPrice = list, discountAmount = discount, upgradeAmount = upgrade, couponAmount = coupon, vatPercent = percent,
        vatAmount = vat, lineTotal = gross
    )

    private fun build(order: MarketOrder, items: List<MarketOrderItem>, number: String = "INV-2026-000042", locale: String = "tr", logoHash: String? = null): InvoiceBuild =
        InvoiceSnapshotBuilder.build(order = order, items = items, config = config, sellerLogoHash = logoHash, issuedAt = issuedAt, number = number, locale = locale)

    private fun built(order: MarketOrder, items: List<MarketOrderItem>, logoHash: String? = null): InvoiceBuild.Built =
        build(order, items, logoHash = logoHash) as? InvoiceBuild.Built ?: error("expected a snapshot")

    private fun failed(result: InvoiceBuild): InvoiceBuild.Failed = result as? InvoiceBuild.Failed ?: error("expected a failure")

    private fun JsonObject.line(index: Int): JsonObject = getJsonArray("lines").getJsonObject(index)

    private fun JsonObject.rows(): List<JsonObject> = getJsonArray("vatRows").map { it as JsonObject }

    private fun JsonObject.row(rate: Long): JsonObject = rows().first { it.getLong("vatPercent") == rate }

    // ================================================================ T-INV-1: invoice

    @Test
    fun `a single line becomes one line, one VAT row and totals equal to the order`() {
        val order = newOrder(total = 1200, vatTotal = 200)
        val result = built(order, listOf(item(gross = 1200, vat = 200, variant = "Gold", sku = "VIP-G", quantity = 1)))
        val snapshot = result.snapshot

        assertEquals(1, snapshot.getInteger("v"))
        assertEquals("INVOICE", snapshot.getString("type"))
        assertEquals("INV-2026-000042", snapshot.getString("number"))
        assertEquals(issuedAt, snapshot.getLong("issuedAt"))
        assertEquals("tr", snapshot.getString("locale"))
        assertEquals("EUR", snapshot.getString("currency"))
        assertEquals("UTC", snapshot.getString("timeZone"))
        assertFalse(snapshot.getBoolean("testMode"))
        assertTrue(snapshot.getBoolean("pricesIncludeVat"))
        assertEquals("Thank you", snapshot.getString("footer"))
        assertNull(snapshot.getValue("ref"))
        assertTrue(snapshot.containsKey("ref"), "the key is present with a null value")

        val line = snapshot.line(0)

        assertEquals("PRODUCT", line.getString("kind"))
        assertEquals("Rank VIP", line.getString("name"))
        assertEquals("Gold", line.getString("variantName"))
        assertEquals("VIP-G", line.getString("sku"))
        assertEquals(1, line.getInteger("quantity"))
        assertEquals(1200L, line.getLong("unitPrice"))
        assertEquals(0L, line.getLong("discount"))
        assertEquals(2000L, line.getLong("vatPercent"))
        assertEquals(1000L, line.getLong("net"))
        assertEquals(200L, line.getLong("vat"))
        assertEquals(1200L, line.getLong("gross"))

        assertEquals(1, snapshot.rows().size)
        val row = snapshot.row(2000)

        assertEquals(listOf(1000L, 200L, 1200L), listOf(row.getLong("net"), row.getLong("vat"), row.getLong("gross")))

        val totals = snapshot.getJsonObject("totals")

        assertEquals(1200L, totals.getLong("subtotal"))
        assertEquals(0L, totals.getLong("discount"))
        assertEquals(0L, totals.getLong("shipping"))
        assertEquals(0L, totals.getLong("paymentFee"))
        assertEquals(1000L, totals.getLong("net"))
        assertEquals(200L, totals.getLong("vat"))
        assertEquals(1200L, totals.getLong("total"))
        assertEquals(1200L, totals.getLong("gatewayAmount"))
        assertEquals(0L, totals.getLong("creditValue"))
        assertEquals(0L, totals.getLong("creditAmount"))

        assertEquals(1200L, result.total)
        assertEquals(200L, result.vatTotal)
    }

    @Test
    fun `lines of several VAT rates are grouped by rate, ascending, and a rate without money is dropped`() {
        val items = listOf(
            item(gross = 1200, vat = 200, percent = 2000, name = "A"),
            item(gross = 1100, vat = 100, percent = 1000, name = "B"),
            item(gross = 500, vat = 0, percent = 0, name = "C"),
            item(gross = 2400, vat = 400, percent = 2000, name = "D"),
            // a fully discounted line: gross 0 at 8 % must not leave an empty row
            item(gross = 0, vat = 0, percent = 800, name = "Free", list = 300, discount = 300)
        )
        val snapshot = built(newOrder(total = 5200, vatTotal = 700), items).snapshot

        assertEquals(listOf(0L, 1000L, 2000L), snapshot.rows().map { it.getLong("vatPercent") })
        assertEquals(listOf(500L, 0L, 500L), snapshot.row(0).let { listOf(it.getLong("net"), it.getLong("vat"), it.getLong("gross")) })
        assertEquals(listOf(1000L, 100L, 1100L), snapshot.row(1000).let { listOf(it.getLong("net"), it.getLong("vat"), it.getLong("gross")) })
        assertEquals(listOf(3000L, 600L, 3600L), snapshot.row(2000).let { listOf(it.getLong("net"), it.getLong("vat"), it.getLong("gross")) })
        assertEquals(5, snapshot.getJsonArray("lines").size(), "the fully discounted line is still a line")
        assertEquals(700L, snapshot.getJsonObject("totals").getLong("vat"))
        assertEquals(4500L, snapshot.getJsonObject("totals").getLong("net"))
    }

    @Test
    fun `discounts are shown per line and summed over the four order discount columns`() {
        val order = newOrder(total = 1825, vatTotal = 304, subtotal = 2000, discount = 100, coupon = 25, creator = 10, upgrade = 50)
        val snapshot = built(order, listOf(item(gross = 1825, vat = 304, quantity = 2, list = 1000, discount = 100, upgrade = 50, coupon = 25))).snapshot
        val line = snapshot.line(0)

        assertEquals(1000L, line.getLong("unitPrice"), "the unit price is the list price")
        assertEquals(2, line.getInteger("quantity"))
        assertEquals(175L, line.getLong("discount"), "discount + upgrade + coupon of the line")
        assertEquals(1825L, line.getLong("gross"))

        val totals = snapshot.getJsonObject("totals")

        assertEquals(2000L, totals.getLong("subtotal"))
        assertEquals(185L, totals.getLong("discount"), "discountTotal + couponDiscount + creatorDiscount + upgradeDiscount")
        assertEquals(1825L, totals.getLong("total"))
    }

    @Test
    fun `a bundle keeps its price and its children are description lines with no amounts`() {
        val items = listOf(
            item(gross = 3000, vat = 500, name = "Starter bundle", kind = OrderItemKind.BUNDLE, id = 1),
            item(gross = 0, vat = 0, percent = 0, name = "Sword", quantity = 2, kind = OrderItemKind.BUNDLE_CHILD, id = 2),
            item(gross = 0, vat = 0, percent = 0, name = "Shield", kind = OrderItemKind.BUNDLE_CHILD, id = 3)
        )
        val snapshot = built(newOrder(total = 3000, vatTotal = 500), items).snapshot

        assertEquals(listOf("BUNDLE", "BUNDLE_CHILD", "BUNDLE_CHILD"), (0..2).map { snapshot.line(it).getString("kind") })
        assertEquals(listOf("Starter bundle", "Sword", "Shield"), (0..2).map { snapshot.line(it).getString("name") })
        assertEquals(2, snapshot.line(1).getInteger("quantity"))

        for (child in 1..2) {
            val line = snapshot.line(child)

            assertEquals(listOf(0L, 0L, 0L, 0L, 0L, 0L), listOf("unitPrice", "discount", "vatPercent", "net", "vat", "gross").map { line.getLong(it) })
        }

        assertEquals(listOf(2000L), snapshot.rows().map { it.getLong("vatPercent") }, "children add no VAT row")
    }

    @Test
    fun `lines come in item id order whatever the order they are passed in`() {
        val items = listOf(item(gross = 100, vat = 17, name = "second", id = 9), item(gross = 200, vat = 33, name = "first", id = 4))
        val snapshot = built(newOrder(total = 300, vatTotal = 50), items).snapshot

        assertEquals(listOf("first", "second"), (0..1).map { snapshot.line(it).getString("name") })
    }

    @Test
    fun `shipping and fee lines take their VAT from the order columns`() {
        val order = newOrder(
            total = 1920, vatTotal = 320, shipping = 600, shippingVat = 100, shippingPercent = 2000, shippingName = "Standard post",
            fee = 120, feeVat = 20, feePercent = 2000
        )
        val snapshot = built(order, listOf(item(gross = 1200, vat = 200))).snapshot

        assertEquals(3, snapshot.getJsonArray("lines").size())

        val shipping = snapshot.line(1)

        assertEquals("SHIPPING", shipping.getString("kind"))
        assertEquals("Standard post", shipping.getString("name"))
        assertEquals(listOf(1L, 600L, 2000L, 500L, 100L, 600L), listOf(shipping.getInteger("quantity").toLong(), shipping.getLong("unitPrice"), shipping.getLong("vatPercent"), shipping.getLong("net"), shipping.getLong("vat"), shipping.getLong("gross")))

        val fee = snapshot.line(2)

        assertEquals("PAYMENT_FEE", fee.getString("kind"))
        assertEquals("", fee.getString("name"), "the renderer labels a fee line by its kind")
        assertEquals(listOf(100L, 20L, 120L), listOf(fee.getLong("net"), fee.getLong("vat"), fee.getLong("gross")))

        val totals = snapshot.getJsonObject("totals")

        assertEquals(600L, totals.getLong("shipping"))
        assertEquals(120L, totals.getLong("paymentFee"))
        assertEquals(1920L, totals.getLong("total"))
        assertEquals(320L, totals.getLong("vat"))
        assertEquals(1600L, totals.getLong("net"))
        assertEquals(1, snapshot.rows().size)
    }

    @Test
    fun `a legacy order gets the residual VAT on the shipping line first, capped, the rest on the fee line`() {
        // items carry 200 of the 230 VAT: the residual 30 is the shipping VAT (net 150, so 20 %), the fee line gets none
        val simple = newOrder(total = 1500, vatTotal = 230, shipping = 180, fee = 120, source = OrderSource.LEGACY)
        val first = built(simple, listOf(item(gross = 1200, vat = 200))).snapshot

        assertEquals(listOf(150L, 30L, 180L, 2000L), listOf("net", "vat", "gross", "vatPercent").map { first.line(1).getLong(it) })
        assertEquals(listOf(120L, 0L, 120L, 0L), listOf("net", "vat", "gross", "vatPercent").map { first.line(2).getLong(it) })
        assertEquals(230L, first.getJsonObject("totals").getLong("vat"))

        // a residual larger than the shipping line: shipping is capped at its price (net 0, so rate 0), the fee takes the rest
        val capped = newOrder(total = 1380, vatTotal = 350, shipping = 60, fee = 120, source = OrderSource.LEGACY)
        val second = built(capped, listOf(item(gross = 1200, vat = 200))).snapshot

        assertEquals(listOf(0L, 60L, 60L, 0L), listOf("net", "vat", "gross", "vatPercent").map { second.line(1).getLong(it) }, "gross = VAT gives rate 0")
        assertEquals(listOf(30L, 90L, 120L, 30000L), listOf("net", "vat", "gross", "vatPercent").map { second.line(2).getLong(it) })
    }

    @Test
    fun `the residual rule is for legacy orders whose columns are 0 only`() {
        // LEGACY with the columns filled: they win
        val filled = newOrder(total = 1500, vatTotal = 230, shipping = 180, shippingVat = 30, shippingPercent = 2000, fee = 120, source = OrderSource.LEGACY)
        val first = built(filled, listOf(item(gross = 1200, vat = 200))).snapshot

        assertEquals(30L, first.line(1).getLong("vat"))
        assertEquals(0L, first.line(2).getLong("vat"))

        // a current order with zero columns: no residual is invented
        val current = newOrder(total = 1500, vatTotal = 230, shipping = 180, fee = 120)
        val second = built(current, listOf(item(gross = 1200, vat = 200))).snapshot

        assertEquals(0L, second.line(1).getLong("vat"))
        assertEquals(0L, second.line(2).getLong("vat"))
        assertEquals(200L, second.getJsonObject("totals").getLong("vat"))
    }

    @Test
    fun `pricesIncludeVat is carried both ways and does not change a line or a total`() {
        val items = listOf(item(gross = 1200, vat = 200))
        val inclusive = built(newOrder(total = 1200, vatTotal = 200, includeVat = true), items).snapshot
        val exclusive = built(newOrder(total = 1200, vatTotal = 200, includeVat = false), items).snapshot

        assertTrue(inclusive.getBoolean("pricesIncludeVat"))
        assertFalse(exclusive.getBoolean("pricesIncludeVat"))
        assertEquals(inclusive.getJsonArray("lines"), exclusive.getJsonArray("lines"))
        assertEquals(inclusive.getJsonObject("totals"), exclusive.getJsonObject("totals"))
        assertEquals(inclusive.getJsonArray("vatRows"), exclusive.getJsonArray("vatRows"))
    }

    @Test
    fun `a mixed payment keeps the credit and gateway parts of the order`() {
        val order = newOrder(total = 1200, vatTotal = 200, gateway = 700, creditValue = 500, creditAmount = 500)
        val totals = built(order, listOf(item(gross = 1200, vat = 200))).snapshot.getJsonObject("totals")

        assertEquals(700L, totals.getLong("gatewayAmount"))
        assertEquals(500L, totals.getLong("creditValue"))
        assertEquals(500L, totals.getLong("creditAmount"))
    }

    @Test
    fun `the seller block is frozen from the config and shows the website name when no seller name is set`() {
        val snapshot = built(newOrder(total = 1200, vatTotal = 200), listOf(item(gross = 1200, vat = 200))).snapshot.getJsonObject("seller")

        assertEquals("Acme Ltd", snapshot.getString("name"))
        assertEquals("1 Main St\nTown", snapshot.getString("address"))
        assertEquals("Kadikoy", snapshot.getString("taxOffice"))
        assertEquals("123456", snapshot.getString("taxNumber"))
        assertEquals("Acme Craft", snapshot.getString("websiteName"))
        assertEquals("https://acme.example", snapshot.getString("websiteUrl"))
        assertFalse(snapshot.containsKey("logoHash"))

        val unnamed = InvoiceSnapshotConfig("", "", "", "", "", "Acme Craft", "https://acme.example", "UTC")
        val result = InvoiceSnapshotBuilder.build(
            order = newOrder(total = 1200, vatTotal = 200), items = listOf(item(gross = 1200, vat = 200)), config = unnamed,
            sellerLogoHash = "abc123", issuedAt = issuedAt, number = "INV-2026-000001", locale = "en-US"
        ) as InvoiceBuild.Built
        val seller = result.snapshot.getJsonObject("seller")

        assertEquals("Acme Craft", seller.getString("name"))
        assertEquals("abc123", seller.getString("logoHash"))
    }

    @Test
    fun `the buyer comes from the billing info with a masked identity number`() {
        val billing = JsonObject()
            .put("type", "COMPANY").put("firstName", "Ayse").put("lastName", "Yilmaz").put("company", "Yilmaz AS")
            .put("taxOffice", "Besiktas").put("taxNumber", "9876543210").put("identityNumber", "12345678901")
            .put("country", "TR").put("state", "Istanbul").put("city", "Besiktas").put("district", "Levent").put("neighborhood", "Gayrettepe Mah.")
            .put("line1", "Sok. 5").put("line2", "Kat 3").put("postalCode", "34340").put("email", "ayse@example.com").put("phone", "+905551112233")
            .encode()
        val buyer = built(newOrder(total = 1200, vatTotal = 200, billing = billing), listOf(item(gross = 1200, vat = 200))).snapshot.getJsonObject("buyer")

        assertEquals("Steve", buyer.getString("username"))
        assertEquals("COMPANY", buyer.getString("type"))
        assertEquals("Ayse Yilmaz", buyer.getString("name"))
        assertEquals("Yilmaz AS", buyer.getString("company"))
        assertEquals("Besiktas", buyer.getString("taxOffice"))
        assertEquals("9876543210", buyer.getString("taxNumber"))
        assertEquals("*******8901", buyer.getString("identityNumber"), "only the last 4 characters stay readable")
        assertEquals(listOf("Sok. 5", "Kat 3", "Gayrettepe Mah. Levent", "34340 Besiktas Istanbul"), buyer.getJsonArray("addressLines").map { it as String })
        assertEquals("TR", buyer.getString("country"))
        assertEquals("ayse@example.com", buyer.getString("email"))
        assertEquals("+905551112233", buyer.getString("phone"))
    }

    @Test
    fun `without billing info the buyer is the username and the order e-mail`() {
        val buyer = built(newOrder(total = 1200, vatTotal = 200, billing = null, email = "guest@example.com"), listOf(item(gross = 1200, vat = 200))).snapshot.getJsonObject("buyer")

        assertEquals("Steve", buyer.getString("username"))
        assertEquals("guest@example.com", buyer.getString("email"))
        assertNull(buyer.getValue("type"))
        assertEquals("", buyer.getString("name"))
        assertEquals(0, buyer.getJsonArray("addressLines").size())

        // a billing e-mail wins, a billing object without one falls back to the order e-mail, garbage is no billing info
        val partial = built(newOrder(total = 1200, vatTotal = 200, billing = """{"firstName":"A","lastName":"B"}""", email = "order@example.com"), listOf(item(gross = 1200, vat = 200))).snapshot.getJsonObject("buyer")

        assertEquals("order@example.com", partial.getString("email"))
        assertEquals("A B", partial.getString("name"))

        val garbage = built(newOrder(total = 1200, vatTotal = 200, billing = "not json", email = null), listOf(item(gross = 1200, vat = 200))).snapshot.getJsonObject("buyer")

        assertEquals("", garbage.getString("email"))
        assertEquals("Steve", garbage.getString("username"))
    }

    @Test
    fun `an identity number is masked down to its last 4 characters and a short one completely`() {
        assertEquals("*******8901", InvoiceSnapshotBuilder.maskIdentity("12345678901"))
        assertEquals("*5678", InvoiceSnapshotBuilder.maskIdentity("A5678"))
        assertEquals("****", InvoiceSnapshotBuilder.maskIdentity("1234"))
        assertEquals("**", InvoiceSnapshotBuilder.maskIdentity("12"))
        assertEquals("", InvoiceSnapshotBuilder.maskIdentity(""))
    }

    @Test
    fun `the order block and the test mode are frozen`() {
        val unpaid = newOrder(total = 1200, vatTotal = 200, testMode = true, paidAt = null)
        val snapshot = built(unpaid, listOf(item(gross = 1200, vat = 200))).snapshot
        val block = snapshot.getJsonObject("order")

        assertTrue(snapshot.getBoolean("testMode"))
        assertEquals(812L, block.getLong("id"))
        assertEquals("ABCDEFGHJKMNPQRSTVWX", block.getString("publicId"))
        assertEquals("Card", block.getString("paymentLabel"))
        assertEquals(0L, block.getLong("paidAt"), "an order without paidAt shows 0")
        assertFalse(block.getBoolean("isGift"))
        assertEquals("Steve", block.getString("recipientUsername"))
    }

    @Test
    fun `the same input builds the same snapshot text`() {
        val order = newOrder(total = 1920, vatTotal = 320, shipping = 600, shippingVat = 100, shippingPercent = 2000, shippingName = "Post", fee = 120, feeVat = 20, feePercent = 2000)
        val items = listOf(item(gross = 1200, vat = 200, id = 1))

        assertEquals(built(order, items).snapshot.encode(), built(order, items).snapshot.encode())
    }

    // ================================================================ T-INV-2: consistency failures

    @Test
    fun `lines that do not add up to the order total are TOTAL_MISMATCH`() {
        val result = failed(build(newOrder(total = 1300, vatTotal = 200), listOf(item(gross = 1200, vat = 200))))

        assertEquals(InvoiceBuildFailure.TOTAL_MISMATCH, result.reason)

        // the shipping line is part of the sum: a total that forgot it is refused too
        val noShipping = failed(build(newOrder(total = 1200, vatTotal = 200, shipping = 100, shippingVat = 16, shippingPercent = 2000), listOf(item(gross = 1200, vat = 200))))

        assertEquals(InvoiceBuildFailure.TOTAL_MISMATCH, noShipping.reason)

        // a child that carries money is forced to 0, so the sum falls short of the order total
        val child = failed(build(newOrder(total = 3100, vatTotal = 500), listOf(item(gross = 3000, vat = 500, kind = OrderItemKind.BUNDLE), item(gross = 100, vat = 0, percent = 0, kind = OrderItemKind.BUNDLE_CHILD))))

        assertEquals(InvoiceBuildFailure.TOTAL_MISMATCH, child.reason)
    }

    @Test
    fun `a negative net or VAT is NEGATIVE_AMOUNT`() {
        // VAT above the line total makes the net negative
        val negativeNet = failed(build(newOrder(total = 1000, vatTotal = 1200), listOf(item(gross = 1000, vat = 1200))))

        assertEquals(InvoiceBuildFailure.NEGATIVE_AMOUNT, negativeNet.reason)

        val negativeVat = failed(build(newOrder(total = 1000, vatTotal = -5), listOf(item(gross = 1000, vat = -5))))

        assertEquals(InvoiceBuildFailure.NEGATIVE_AMOUNT, negativeVat.reason)

        val negativeGross = failed(build(newOrder(total = -100), listOf(item(gross = -100, vat = 0, percent = 0))))

        assertEquals(InvoiceBuildFailure.NEGATIVE_AMOUNT, negativeGross.reason)

        // a legacy residual that cannot fit its line is the same failure: the fee VAT exceeds the fee
        val legacy = failed(build(newOrder(total = 1500, vatTotal = 900, shipping = 100, fee = 100, source = OrderSource.LEGACY), listOf(item(gross = 1300, vat = 0, percent = 0))))

        assertEquals(InvoiceBuildFailure.NEGATIVE_AMOUNT, legacy.reason)
    }

    @Test
    fun `a currency the plugin does not know is UNKNOWN_CURRENCY`() {
        val result = failed(build(newOrder(total = 1200, currency = "XXX", vatTotal = 200), listOf(item(gross = 1200, vat = 200))))

        assertEquals(InvoiceBuildFailure.UNKNOWN_CURRENCY, result.reason)
        assertTrue(result.detail.contains("XXX"))
    }

    @Test
    fun `an amount that overflows is a failure, not an exception`() {
        val big = Long.MAX_VALUE / 2 + 10
        val result = build(newOrder(total = 5), listOf(item(gross = big, vat = 0, percent = 0, name = "x"), item(gross = big, vat = 0, percent = 0, name = "y")))

        assertEquals(InvoiceBuildFailure.TOTAL_MISMATCH, failed(result).reason)
    }

    // ================================================================ T-INV-3: credit notes

    /** An invoice of two VAT rates: 10 % over a gross of 1000 and 20 % over a gross of 2000, plus what the tests add. */
    private fun invoiceOf(order: MarketOrder, items: List<MarketOrderItem>, number: String = "INV-2026-000007"): MarketInvoice {
        val snapshot = built(order, items).snapshot

        return MarketInvoice(
            id = 77, orderId = order.id, type = InvoiceType.INVOICE, series = "INV", sequence = 7, number = number, locale = "tr", currency = order.currency,
            total = snapshot.getJsonObject("totals").getLong("total"), vatTotal = snapshot.getJsonObject("totals").getLong("vat"), snapshot = snapshot.encode(), issuedAt = issuedAt - 1_000
        )
    }

    private fun refund(amount: Long, id: Long = 5, gateway: Long = amount, creditValue: Long = 0, creditAmount: Long = 0, reason: String? = "defective") = MarketRefund(
        id = id, orderId = 812, status = RefundStatus.SUCCEEDED, amount = amount, gatewayAmount = gateway, creditValue = creditValue, creditAmount = creditAmount,
        currency = "EUR", reason = reason
    )

    private fun refundRow(item: MarketOrderItem, amount: Long, quantity: Int = 1, id: Long = ++nextId) =
        MarketRefundItem(id = id, refundId = 5, orderItemId = item.id, quantity = quantity, amount = amount)

    private fun creditNote(
        order: MarketOrder,
        items: List<MarketOrderItem>,
        invoice: MarketInvoice?,
        refund: MarketRefund,
        rows: List<MarketRefundItem> = emptyList()
    ): InvoiceBuild = InvoiceSnapshotBuilder.build(
        order = order, items = items, refund = refund, refundItems = rows, invoice = invoice, config = config, issuedAt = issuedAt + 10_000,
        number = "CN-2026-000003", locale = "tr"
    )

    private fun twoRates(): Triple<MarketOrder, List<MarketOrderItem>, MarketInvoice> {
        val items = listOf(item(gross = 1100, vat = 100, percent = 1000, name = "Ten", id = 1), item(gross = 2400, vat = 400, percent = 2000, name = "Twenty", quantity = 2, id = 2))
        val order = newOrder(total = 3500, vatTotal = 500)

        return Triple(order, items, invoiceOf(order, items))
    }

    @Test
    fun `an itemised refund becomes one line per refund row with the VAT inside the refunded amount`() {
        val (order, items, invoice) = twoRates()
        val refund = refund(amount = 1200, creditValue = 200, gateway = 1000, creditAmount = 200)
        val result = creditNote(order, items, invoice, refund, listOf(refundRow(items[1], amount = 1200, quantity = 1))) as InvoiceBuild.Built
        val snapshot = result.snapshot

        assertEquals("CREDIT_NOTE", snapshot.getString("type"))
        assertEquals("CN-2026-000003", snapshot.getString("number"))
        assertEquals(1, snapshot.getJsonArray("lines").size())

        val line = snapshot.line(0)

        assertEquals("PRODUCT", line.getString("kind"))
        assertEquals("Twenty", line.getString("name"))
        assertEquals(1, line.getInteger("quantity"))
        assertEquals(1200L, line.getLong("unitPrice"))
        assertEquals(2000L, line.getLong("vatPercent"))
        assertEquals(listOf(1000L, 200L, 1200L), listOf(line.getLong("net"), line.getLong("vat"), line.getLong("gross")))

        val ref = snapshot.getJsonObject("ref")

        assertEquals("INV-2026-000007", ref.getString("number"))
        assertEquals(invoice.issuedAt, ref.getLong("issuedAt"))
        assertEquals(5L, ref.getLong("refundId"))
        assertEquals("defective", ref.getString("reason"))

        val totals = snapshot.getJsonObject("totals")

        assertEquals(1200L, totals.getLong("total"))
        assertEquals(200L, totals.getLong("vat"))
        assertEquals(1000L, totals.getLong("net"))
        assertEquals(200L, totals.getLong("creditValue"))
        assertEquals(200L, totals.getLong("creditAmount"))
        assertEquals(1000L, totals.getLong("gatewayAmount"))
        assertEquals(1200L, result.total)
        assertEquals(200L, result.vatTotal)
    }

    @Test
    fun `the quantity of a refund row sets its unit price and a row without quantity shows its amount`() {
        val (order, items, invoice) = twoRates()
        val rows = listOf(refundRow(items[1], amount = 2000, quantity = 2, id = 1), refundRow(items[0], amount = 300, quantity = 0, id = 2))
        val snapshot = (creditNote(order, items, invoice, refund(2300), rows) as InvoiceBuild.Built).snapshot

        assertEquals(1000L, snapshot.line(0).getLong("unitPrice"))
        assertEquals(2, snapshot.line(0).getInteger("quantity"))
        assertEquals(300L, snapshot.line(1).getLong("unitPrice"))
        assertEquals("Ten", snapshot.line(1).getString("name"))
    }

    @Test
    fun `an amount-only refund is split over the VAT rows of the invoice by the largest remainder method`() {
        val (order, items, invoice) = twoRates()
        val snapshot = (creditNote(order, items, invoice, refund(100)) as InvoiceBuild.Built).snapshot
        val lines = (0 until snapshot.getJsonArray("lines").size()).map { snapshot.line(it) }

        assertEquals(listOf("REFUND", "REFUND"), lines.map { it.getString("kind") })
        assertEquals(listOf(1000L, 2000L), lines.map { it.getLong("vatPercent") })
        // 100 * 1100 / 3500 = 31.43, 100 * 2400 / 3500 = 68.57: floors 31 + 68 = 99, the unit goes to the larger remainder (0.57)
        assertEquals(listOf(31L, 69L), lines.map { it.getLong("gross") })
        assertEquals(100L, lines.sumOf { it.getLong("gross") })

        for (line in lines) {
            assertEquals(line.getLong("gross"), line.getLong("net") + line.getLong("vat"))
            assertEquals(1, line.getInteger("quantity"))
            assertEquals(line.getLong("gross"), line.getLong("unitPrice"))
        }

        // VAT inside the share: 31 at 10 % -> 3 (2.82), 69 at 20 % -> 12 (69 * 2000 / 12000 = 11.5, half up)
        assertEquals(3L, lines[0].getLong("vat"))
        assertEquals(12L, lines[1].getLong("vat"))
        assertEquals(100L, snapshot.getJsonObject("totals").getLong("total"))
    }

    @Test
    fun `equal remainders go to the lower VAT rate first and a rate with no share gets no line`() {
        val items = listOf(
            item(gross = 1100, vat = 100, percent = 1000, id = 1),
            item(gross = 1100, vat = 100, percent = 1000, id = 2, name = "same rate"),
            item(gross = 1200, vat = 200, percent = 2000, id = 3),
            item(gross = 1000, vat = 0, percent = 0, id = 4)
        )
        val order = newOrder(total = 4400, vatTotal = 400)
        // an invoice with three rows of equal weight, built by hand
        val equal = invoiceWithRows(order, listOf(0L to 1000L, 1000L to 1000L, 2000L to 1000L))

        val one = (creditNote(order, items, equal, refund(1)) as InvoiceBuild.Built).snapshot

        assertEquals(1, one.getJsonArray("lines").size(), "a single unit goes to one row only")
        assertEquals(0L, one.line(0).getLong("vatPercent"), "the tie goes to the lowest rate")

        val two = (creditNote(order, items, equal, refund(2)) as InvoiceBuild.Built).snapshot

        assertEquals(listOf(0L, 1000L), (0 until two.getJsonArray("lines").size()).map { two.line(it).getLong("vatPercent") })
        assertEquals(listOf(1L, 1L), (0 until 2).map { two.line(it).getLong("gross") })

        val three = (creditNote(order, items, equal, refund(3)) as InvoiceBuild.Built).snapshot

        assertEquals(listOf(0L, 1000L, 2000L), (0 until three.getJsonArray("lines").size()).map { three.line(it).getLong("vatPercent") })
        assertEquals(3L, three.getJsonObject("totals").getLong("total"))
    }

    /** An invoice whose snapshot carries exactly the given `(vatPercent, gross)` VAT rows. */
    private fun invoiceWithRows(order: MarketOrder, rows: List<Pair<Long, Long>>): MarketInvoice {
        val snapshot = JsonObject().put("vatRows", JsonArray(rows.map { JsonObject().put("vatPercent", it.first).put("net", 0L).put("vat", 0L).put("gross", it.second) }))

        return MarketInvoice(id = 78, orderId = order.id, number = "INV-2026-000008", snapshot = snapshot.encode(), issuedAt = issuedAt - 1_000)
    }

    @Test
    fun `the amount-only split always adds up to the refund to the minor unit`() {
        val random = java.util.Random(20_261_005L)

        repeat(300) {
            val rows = listOf(0L, 800L, 1000L, 2000L).filter { random.nextBoolean() }.ifEmpty { listOf(2000L) }.map { it to (100L + random.nextInt(50_000)) }
            val total = rows.sumOf { it.second }
            val amount = 1L + random.nextInt(total.toInt())
            val order = newOrder(total = total)
            val invoice = invoiceWithRows(order, rows)
            val snapshot = (creditNote(order, emptyList(), invoice, refund(amount)) as InvoiceBuild.Built).snapshot
            val lines = (0 until snapshot.getJsonArray("lines").size()).map { snapshot.line(it) }

            assertEquals(amount, lines.sumOf { it.getLong("gross") }, "rows $rows amount $amount")
            assertTrue(lines.all { it.getLong("gross") > 0 && it.getLong("net") >= 0 && it.getLong("vat") >= 0 })
            assertEquals(amount, snapshot.getJsonArray("vatRows").sumOf { (it as JsonObject).getLong("gross") })
        }
    }

    @Test
    fun `an itemised refund with a remainder keeps its rows and splits the rest`() {
        val (order, items, invoice) = twoRates()
        val rows = listOf(refundRow(items[0], amount = 550, quantity = 1))
        // 550 itemised (10 %), 450 amount-only: split over 1100 : 2400
        val snapshot = (creditNote(order, items, invoice, refund(1000), rows) as InvoiceBuild.Built).snapshot
        val lines = (0 until snapshot.getJsonArray("lines").size()).map { snapshot.line(it) }

        assertEquals(listOf("PRODUCT", "REFUND", "REFUND"), lines.map { it.getString("kind") })
        assertEquals(550L, lines[0].getLong("gross"))
        assertEquals(50L, lines[0].getLong("vat"), "550 at 10 % contains 50")
        // 450 * 1100 / 3500 = 141.43, 450 * 2400 / 3500 = 308.57 -> 141 + 308 = 449, the unit goes to 20 %
        assertEquals(listOf(141L, 309L), lines.drop(1).map { it.getLong("gross") })
        assertEquals(1000L, lines.sumOf { it.getLong("gross") })
        assertEquals(1000L, snapshot.getJsonObject("totals").getLong("total"))
        assertEquals(lines.sumOf { it.getLong("vat") }, snapshot.getJsonObject("totals").getLong("vat"))
    }

    @Test
    fun `refund rows above the refund amount, a refund above the invoice and a refund that cannot be split are refused`() {
        val (order, items, invoice) = twoRates()

        val rowsTooHigh = failed(creditNote(order, items, invoice, refund(500), listOf(refundRow(items[0], amount = 600))))

        assertEquals(InvoiceBuildFailure.TOTAL_MISMATCH, rowsTooHigh.reason)

        val aboveInvoice = failed(creditNote(order, items, invoice, refund(3600)))

        assertEquals(InvoiceBuildFailure.TOTAL_MISMATCH, aboveInvoice.reason)
    }

    @Test
    fun `a credit note needs a readable invoice`() {
        val (order, items, invoice) = twoRates()

        assertEquals(InvoiceBuildFailure.INVOICE_UNREADABLE, failed(creditNote(order, items, null, refund(100))).reason)

        val garbage = MarketInvoice(number = "INV-2026-000001", snapshot = "not json")

        assertEquals(InvoiceBuildFailure.INVOICE_UNREADABLE, failed(creditNote(order, items, garbage, refund(100))).reason)

        val noRows = MarketInvoice(number = "INV-2026-000001", snapshot = """{"vatRows":[]}""")

        assertEquals(InvoiceBuildFailure.INVOICE_UNREADABLE, failed(creditNote(order, items, noRows, refund(100))).reason)

        val missing = MarketInvoice(number = "INV-2026-000001", snapshot = "{}")

        assertEquals(InvoiceBuildFailure.INVOICE_UNREADABLE, failed(creditNote(order, items, missing, refund(100))).reason)

        // a fully itemised refund needs no split, but its credit note still refers to an invoice
        assertEquals(InvoiceBuildFailure.INVOICE_UNREADABLE, failed(creditNote(order, items, null, refund(550), listOf(refundRow(items[0], amount = 550)))).reason)
        assertNotNull((creditNote(order, items, invoice, refund(550), listOf(refundRow(items[0], amount = 550))) as? InvoiceBuild.Built))
    }

    @Test
    fun `a refund row of a line the order does not have, a negative row and a broken VAT rate are refused`() {
        val (order, items, invoice) = twoRates()
        val stranger = MarketRefundItem(id = 1, refundId = 5, orderItemId = 999, quantity = 1, amount = 100)

        assertEquals(InvoiceBuildFailure.UNKNOWN_ITEM, failed(creditNote(order, items, invoice, refund(100), listOf(stranger))).reason)

        assertEquals(InvoiceBuildFailure.NEGATIVE_AMOUNT, failed(creditNote(order, items, invoice, refund(100), listOf(refundRow(items[0], amount = -100)))).reason)

        val broken = listOf(item(gross = 1200, vat = 200, percent = 20_000, id = 1))
        val brokenOrder = newOrder(total = 1200, vatTotal = 200)

        assertEquals(InvoiceBuildFailure.INVALID_VAT_RATE, failed(creditNote(brokenOrder, broken, invoiceOf(brokenOrder, broken), refund(600), listOf(refundRow(broken[0], amount = 600)))).reason)
    }

    @Test
    fun `the credit note document is frozen like an invoice, test mode included`() {
        val items = listOf(item(gross = 1200, vat = 200, id = 1))
        val order = newOrder(total = 1200, vatTotal = 200, testMode = true)
        val invoice = invoiceOf(order, items)
        val snapshot = (creditNote(order, items, invoice, refund(1200), listOf(refundRow(items[0], amount = 1200))) as InvoiceBuild.Built).snapshot

        assertTrue(snapshot.getBoolean("testMode"))
        assertEquals("Acme Ltd", snapshot.getJsonObject("seller").getString("name"))
        assertEquals("Steve", snapshot.getJsonObject("buyer").getString("username"))
        assertEquals(812L, snapshot.getJsonObject("order").getLong("id"))
        assertEquals("Thank you", snapshot.getString("footer"))
        assertEquals(issuedAt + 10_000, snapshot.getLong("issuedAt"))
    }

    @Test
    fun `a credit note of a refund without a reason has a null reason`() {
        val items = listOf(item(gross = 1200, vat = 200, id = 1))
        val order = newOrder(total = 1200, vatTotal = 200)
        val snapshot = (creditNote(order, items, invoiceOf(order, items), refund(1200, reason = null), listOf(refundRow(items[0], amount = 1200))) as InvoiceBuild.Built).snapshot

        assertTrue(snapshot.getJsonObject("ref").containsKey("reason"))
        assertNull(snapshot.getJsonObject("ref").getValue("reason"))
    }

    @Test
    fun `a zero-decimal currency keeps every amount and VAT on whole units`() {
        // JPY: amounts are x100 with a quantum of 100 (one yen)
        val items = listOf(item(gross = 110_000, vat = 10_000, percent = 1000, id = 1), item(gross = 120_000, vat = 20_000, percent = 2000, id = 2))
        val order = newOrder(total = 230_000, currency = "JPY", vatTotal = 30_000)
        val invoice = invoiceOf(order, items)
        val snapshot = (creditNote(order, items, invoice, refund(100_000, gateway = 100_000)) as InvoiceBuild.Built).snapshot
        val lines = (0 until snapshot.getJsonArray("lines").size()).map { snapshot.line(it) }

        assertEquals(100_000L, lines.sumOf { it.getLong("gross") })

        for (line in lines) {
            assertEquals(0L, line.getLong("gross") % 100, "whole yen: ${line.getLong("gross")}")
            assertEquals(0L, line.getLong("vat") % 100, "whole yen of VAT: ${line.getLong("vat")}")
            assertEquals(line.getLong("gross"), line.getLong("net") + line.getLong("vat"))
        }

        // 100000 over 110000 : 120000 -> 47826.09 / 52173.91 yen-units of 100: 478 + 521 = 999, the last unit goes to the larger remainder
        assertEquals(listOf(47_800L, 52_200L), lines.map { it.getLong("gross") })
    }
}
