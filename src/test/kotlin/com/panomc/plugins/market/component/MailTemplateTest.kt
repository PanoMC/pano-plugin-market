package com.panomc.plugins.market.component

import com.panomc.platform.mail.template.MailTemplateContent
import com.panomc.platform.mail.template.MailTemplateService
import com.panomc.plugins.market.config.MarketConfig
import com.panomc.plugins.market.db.model.MailKind
import com.panomc.plugins.market.db.model.MarketOrder
import com.panomc.plugins.market.db.model.MarketOrderItem
import com.panomc.plugins.market.db.model.MarketRefund
import com.panomc.plugins.market.db.model.MarketEntitlement
import com.panomc.plugins.market.db.model.MarketRefundItem
import com.panomc.plugins.market.db.model.MarketShipment
import com.panomc.plugins.market.db.model.MarketShipmentItem
import com.panomc.plugins.market.db.model.MarketSubscription
import com.panomc.plugins.market.db.model.ShipmentStatus
import com.panomc.plugins.market.db.model.SubscriptionMode
import com.panomc.plugins.market.db.model.SubscriptionStatus
import com.panomc.plugins.market.db.model.OrderItemKind
import com.panomc.plugins.market.db.model.PricingMode
import com.panomc.plugins.market.i18n.MarketFormat
import com.panomc.plugins.market.i18n.MarketI18n
import com.panomc.plugins.market.mail.MailContentBuilder
import com.panomc.plugins.market.mail.MailInput
import com.panomc.plugins.market.mail.MailSite
import com.panomc.plugins.market.mail.MarketMailMessage
import com.panomc.plugins.market.support.FakeClock
import com.panomc.plugins.market.support.MailRendering
import com.panomc.plugins.market.util.OrderStatus
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import com.panomc.plugins.market.support.SiteLinksFixture
import com.panomc.plugins.market.util.MarketTargets
import java.io.File

/**
 * The order mails (MK-142; 12 sections 3.1 and 5) on the platform's unified mail templates: `MailContentBuilder` builds the variant and
 * the variables of every kind, the plugin's `mail-templates/order/<variant>/<locale>.hbs` hold the sentences, and the host's real
 * `MailTemplateService` renders them into the shared layout (see [MailRendering]). Every kind renders in tr, en-US and ru against golden
 * files (subject, plain text, HTML) with no unresolved `{{ }}` or `{var}`. Pure: no database, no host.
 *
 * Golden files live next to this class in `golden/mail`. A changed golden is a reviewed change: run with `GOLDEN_UPDATE=1` to rewrite them
 * and read the diff; a missing golden fails the run (it never writes silently).
 */
class MailTemplateTest {
    private val locales = listOf("tr", "en-US", "ru")
    private val clock = FakeClock()
    private val i18n = MarketI18n(MarketI18n.loadBundles(MailTemplateTest::class.java.classLoader), { emptyMap() }, clock)
    private var config = MarketConfig(storeName = "Blocky Store", creditName = "Coins")
    private val format = MarketFormat(i18n, zone = { "Europe/Istanbul" }, creditName = { config.creditName })
    private var site = MailSite("Blocky Network", "https://shop.example")

    private fun builder() = MailContentBuilder(i18n, format, { config }, { site })

    // ----- fixtures ----------------------------------------------------------------------------------------------------

    private val createdAt = 1_780_000_000_000L
    private val paidAt = 1_780_000_300_000L

    private fun item(
        id: Long, name: String, quantity: Int, lineTotal: Long, variant: String? = null, kind: OrderItemKind = OrderItemKind.PRODUCT, parent: Long? = null,
        physical: Boolean = false
    ) = MarketOrderItem(
        id = id, orderId = 1042, productName = name, quantity = quantity, lineTotal = lineTotal, variantName = variant, kind = kind, parentItemId = parent, physical = physical
    )

    private val items = listOf(
        item(1, "Gold Rank", 1, 15_000, variant = "30 days"),
        item(2, "Starter Bundle", 2, 20_000, kind = OrderItemKind.BUNDLE),
        item(3, "Diamond Sword", 2, 0, kind = OrderItemKind.BUNDLE_CHILD, parent = 2)
    )

    private fun order(
        status: OrderStatus = OrderStatus.COMPLETED, testMode: Boolean = false, userId: Long? = 7, accessToken: String? = null, gift: Boolean = false,
        credit: Long = 0, gateway: Long = 35_000, total: Long = 35_000, pricing: PricingMode = PricingMode.MARKET
    ) = MarketOrder(
        id = 1042, userId = userId, playerUsername = "Steve", totalPrice = total, currency = "TRY", paymentLabel = "Credit card", status = status, createdAt = createdAt,
        publicId = "K7M2Q9X4B8D6T3W5Z1RV", accessToken = accessToken, email = "steve@example.com", locale = "en-US", recipientUsername = if (gift) "Alex" else "",
        isGift = gift, giftMessage = if (gift) "Have fun on the server!" else null, pricingMode = pricing, pricesIncludeVat = true, subtotal = 40_000,
        discountTotal = 3_000, couponDiscount = 2_000, shippingTotal = 0, paymentFee = 0, vatTotal = 5_833, creditAmount = credit, gatewayAmount = gateway, paidAt = paidAt,
        testMode = testMode
    )

    private class Scenario(val name: String, val input: MailInput)

    private fun scenarios(locale: String): List<Scenario> {
        val digital = order()
        val physical = order(testMode = true, userId = null, accessToken = "0f1e2d3c4b5a69788796a5b4c3d2e1f001122334").let {
            MarketOrder(
                id = it.id, userId = it.userId, playerUsername = it.playerUsername, totalPrice = 37_500, currency = "TRY", paymentLabel = "Credit card", status = OrderStatus.COMPLETED,
                createdAt = createdAt, publicId = it.publicId, accessToken = it.accessToken, email = it.email, locale = it.locale, pricingMode = PricingMode.MARKET, pricesIncludeVat = false,
                subtotal = 40_000, discountTotal = 5_000, shippingTotal = 2_500, paymentFee = 0, vatTotal = 6_250, creditAmount = 10_000, gatewayAmount = 27_500, paidAt = paidAt,
                testMode = true, requiresShipping = true,
                shippingAddress = """{"firstName":"Steve","lastName":"Miner","line1":"12 Cube Street","city":"Istanbul","postalCode":"34000","country":"TR"}"""
            )
        }
        val credits = order(credit = 35_000, gateway = 0)
        val refund = MarketRefund(
            id = 9, orderId = 1042, amount = 15_000, gatewayAmount = 10_000, creditAmount = 5_000, currency = "TRY", reason = "Bought by mistake",
            buyerActionUrl = "https://pay.example/claim/abc?x=1&y=2"
        )
        val refundLines = listOf(MarketRefundItem(id = 1, refundId = 9, orderItemId = 1, quantity = 1, amount = 15_000))
        val bank = JsonObject()
            .put(
                "instructions",
                JsonObject().put("body", "Transfer to our account.<br>Use the reference below.<script>alert(1)</script>")
                    .put("fields", JsonArray().add(JsonObject().put("label", "IBAN").put("value", "TR33 0006 1005 1978 6457 8413 26")).add(JsonObject().put("label", "Reference").put("value", "ORDER-1042")))
            )
            .put("expiresAt", createdAt + 3 * 86_400_000L)

        fun input(kind: MailKind, o: MarketOrder = digital, params: JsonObject = JsonObject(), r: MarketRefund? = null, rl: List<MarketRefundItem> = emptyList(), attached: Boolean = false) =
            MailInput(kind, locale, o, items, params, r, rl, attached)

        val day = 86_400_000L
        val sub = MarketSubscription(
            id = 5, productName = "Gold Rank", initialOrderId = 1042, mode = SubscriptionMode.GATEWAY, status = SubscriptionStatus.ACTIVE, price = 15_000, currency = "TRY",
            cycleCount = 2, currentPeriodEnd = paidAt + 30 * day, graceEndsAt = paidAt + 33 * day, storedMethodLabel = "Visa **** 4242", endReason = "BUYER_CANCEL"
        )
        val manual = MarketSubscription(
            id = 6, productName = "Gold Rank", initialOrderId = 1042, mode = SubscriptionMode.MANUAL, status = SubscriptionStatus.ACTIVE, price = 15_000, currency = "TRY",
            cycleCount = 2, currentPeriodEnd = paidAt + 30 * day
        )
        val entitlement = MarketEntitlement(id = 3, orderId = 1042, orderItemId = 1, expiresAt = paidAt + 30 * day)
        val shippedOrder = MarketOrder(
            id = 1042, userId = null, playerUsername = "Steve", totalPrice = 37_500, currency = "TRY", status = OrderStatus.COMPLETED, createdAt = createdAt, publicId = "K7M2Q9X4B8D6T3W5Z1RV",
            accessToken = "0f1e2d3c4b5a69788796a5b4c3d2e1f001122334", email = "steve@example.com", locale = "en-US", paidAt = paidAt, requiresShipping = true
        )
        val parcel = MarketShipment(
            id = 8, orderId = 1042, status = ShipmentStatus.IN_TRANSIT, carrierName = "Aras Kargo", trackingNumber = "AR123456789", trackingUrl = "https://track.example/AR123456789?a=1&b=2",
            estimatedDeliveryAt = paidAt + 3 * day, deliveredAt = paidAt + 2 * day
        )
        val parcelLines = listOf(MarketShipmentItem(id = 1, shipmentId = 8, orderItemId = 1, quantity = 1), MarketShipmentItem(id = 2, shipmentId = 8, orderItemId = 2, quantity = 2))
        val shipParams = JsonObject().put("isPartial", true).put("addressLines", JsonArray().add("Steve Miner").add("12 Cube Street").add("34000 Istanbul").add("Turkey"))

        return listOf(
            Scenario("expiry-reminder", MailInput(MailKind.EXPIRY_REMINDER, locale, digital, items, JsonObject().put("expiresAt", paidAt + 30 * day), entitlement = entitlement, productSlug = "gold-rank")),
            Scenario("subscription-reminder-gateway", MailInput(MailKind.SUBSCRIPTION_REMINDER, locale, digital, items, JsonObject().put("periodEnd", paidAt + 30 * day), subscription = sub, productSlug = "gold-rank")),
            Scenario(
                "subscription-reminder-manual",
                MailInput(MailKind.SUBSCRIPTION_REMINDER, locale, digital, items, JsonObject().put("periodEnd", paidAt + 30 * day).put("payUrl", "/store/order/PAYLINK0000000000000"), subscription = manual, productSlug = "gold-rank")
            ),
            Scenario("subscription-payment-failed", MailInput(MailKind.SUBSCRIPTION_PAYMENT_FAILED, locale, digital, items, JsonObject().put("graceEndsAt", paidAt + 33 * day), subscription = sub)),
            Scenario("subscription-cancelled", MailInput(MailKind.SUBSCRIPTION_CANCELLED, locale, digital, items, JsonObject().put("accessUntil", paidAt + 30 * day), subscription = sub)),
            Scenario("subscription-ended", MailInput(MailKind.SUBSCRIPTION_ENDED, locale, digital, items, JsonObject().put("endReason", "PAYMENT_FAILED"), subscription = sub)),
            Scenario("shipment-shipped", MailInput(MailKind.SHIPMENT_SHIPPED, locale, shippedOrder, items, shipParams, shipment = parcel, shipmentItems = parcelLines)),
            Scenario("shipment-delivered", MailInput(MailKind.SHIPMENT_DELIVERED, locale, shippedOrder, items, JsonObject(), shipment = parcel, shipmentItems = parcelLines)),
            Scenario("order-received", input(MailKind.ORDER_RECEIVED, order(status = OrderStatus.PENDING))),
            Scenario("bank-transfer-instructions", input(MailKind.BANK_TRANSFER_INSTRUCTIONS, order(status = OrderStatus.PENDING), bank)),
            Scenario("order-confirmation", input(MailKind.ORDER_CONFIRMATION, attached = true)),
            Scenario("order-confirmation-physical-test", input(MailKind.ORDER_CONFIRMATION, physical)),
            Scenario("order-confirmation-credits", input(MailKind.ORDER_CONFIRMATION, credits)),
            Scenario("gift-received", input(MailKind.GIFT_RECEIVED, order(gift = true))),
            Scenario("order-delivered", input(MailKind.ORDER_DELIVERED)),
            Scenario("order-refunded", input(MailKind.ORDER_REFUNDED, r = refund, rl = refundLines, attached = true))
        )
    }


    // ----- rendering ---------------------------------------------------------------------------------------------------

    private fun render(message: MarketMailMessage, locale: String): MailTemplateService.RenderedMail =
        MailRendering.render(message, locale, site.websiteName, site.websiteUrl)

    private suspend fun build(name: String, locale: String = "en-US"): MarketMailMessage = builder().build(scenarios(locale).first { it.name == name }.input)

    private suspend fun rendered(name: String, locale: String = "en-US"): MailTemplateService.RenderedMail = render(build(name, locale), locale)

    private fun golden(r: MailTemplateService.RenderedMail): String = "=== subject\n${r.subject}\n=== text\n${r.text}\n=== html\n${r.html}\n"

    private val goldenDir = File("src/test/kotlin/com/panomc/plugins/market/component/golden/mail")
    private val update = System.getenv("GOLDEN_UPDATE") == "1"

    private val unresolved = Regex("\\{\\{|\\}\\}|\\{[A-Za-z][A-Za-z0-9_]*\\}")

    /** The main button of a rendered mail as the plain-text alternative names it (`label: url`), or null. */
    private fun button(r: MailTemplateService.RenderedMail, label: String): String? =
        r.text.lines().firstOrNull { it.startsWith("$label: ") }?.removePrefix("$label: ")

    private fun hasButton(r: MailTemplateService.RenderedMail): Boolean = r.html.contains("background-color:rgb(85,197,90)")

    // ----- tests -------------------------------------------------------------------------------------------------------

    @Test
    fun `every order mail renders in tr, en-US and ru against its golden file`(): Unit = runBlocking {
        val seen = HashSet<MailKind>()
        var compared = 0

        for (locale in locales) {
            for (s in scenarios(locale)) {
                seen += s.input.kind

                val r = render(builder().build(s.input), locale)

                for ((what, value) in listOf("subject" to r.subject, "text" to r.text, "html" to r.html)) {
                    assertFalse(unresolved.containsMatchIn(value), "${s.name} / $locale: unresolved placeholder in $what: ${unresolved.find(value)?.value}")
                    assertFalse(value.contains("mail.blocks."), "${s.name} / $locale: a missing key leaked into $what")
                }

                val file = File(goldenDir, "${s.name}.$locale.golden")
                val actual = golden(r)

                if (update) {
                    goldenDir.mkdirs()
                    file.writeText(actual)
                } else {
                    assertTrue(file.isFile, "missing golden file ${file.path} (run once with GOLDEN_UPDATE=1 and review it)")
                    assertEquals(file.readText(), actual, "${s.name} / $locale differs from ${file.path}")
                }

                compared++
            }
        }

        assertEquals(MailContentBuilder.KINDS, seen, "every kind the builder supports has a golden scenario")
        assertEquals(locales.size * scenarios("en-US").size, compared)
    }

    @Test
    fun `the subject is translated and differs per language`(): Unit = runBlocking {
        for (kind in MailContentBuilder.KINDS) {
            val subjects = locales.map { locale ->
                val s = scenarios(locale).first { it.input.kind == kind }

                render(builder().build(s.input), locale).subject
            }

            assertEquals(3, subjects.toSet().size, "$kind: one subject per language: $subjects")
        }
    }

    @Test
    fun `an order id is the order number and the payer name fills the subject of a gift`(): Unit = runBlocking {
        assertEquals("Steve sent you a gift on Blocky Network", rendered("gift-received").subject)
        assertEquals("Your order #1042 is confirmed", rendered("order-confirmation").subject)
    }

    @Test
    fun `confirmation totals follow 12 section 5 rows in order, the discount is negative and the VAT row says included`(): Unit = runBlocking {
        val message = build("order-confirmation")

        assertEquals("order-confirmation", message.variant)
        assertEquals(listOf("Subtotal", "Discount", "VAT (included)", "Total"), message.totals.map { it.label })
        assertEquals(listOf("₺400.00", "-₺50.00", "₺58.33", "₺350.00"), message.totals.map { it.value })
        assertTrue(message.totals.last().strong)
        assertEquals(listOf("1", "2", "2"), message.items.map { it.quantity })
        assertEquals(listOf(false, false, true), message.items.map { it.child })
        assertEquals("", message.items[2].total, "a bundle child carries no price")
        assertEquals("₺150.00", message.items[0].total)
        assertEquals("30 days", message.items[0].variant)
        assertEquals(true, message.values["invoiceAttached"])

        val r = render(message, "en-US")

        assertTrue(r.text.contains("invoice is attached"))
        assertEquals("https://shop.example/store/order/K7M2Q9X4B8D6T3W5Z1RV", button(r, "View order"), "an order of a logged-in user never carries the token")
    }

    @Test
    fun `a guest order link carries the token, a test order is prefixed and bannered, a physical order names its address`(): Unit = runBlocking {
        val message = build("order-confirmation-physical-test")
        val r = render(message, "en-US")

        assertTrue(message.values["orderUrl"].toString().endsWith("?token=0f1e2d3c4b5a69788796a5b4c3d2e1f001122334"))
        assertTrue(r.subject.startsWith("[TEST] "))
        assertTrue(message.testMode)
        assertTrue(r.html.contains("TEST ORDER - no real payment was made"))
        assertTrue(r.text.contains("We will ship your order to: Steve Miner, 12 Cube Street, 34000 Istanbul, TR"), r.text)
        assertEquals(listOf("Subtotal", "Discount", "Shipping", "VAT", "Total", "Paid with credits"), message.totals.map { it.label })
        assertEquals("100.00 Coins", message.totals.last().value)
        assertFalse(r.text.contains("attached"))

        val plain = rendered("order-confirmation")

        assertFalse(plain.subject.startsWith("[TEST]"))
        assertFalse(plain.html.contains("TEST ORDER"))
        assertFalse(plain.text.contains("We will ship"), "no address sentence for a digital order")
    }

    @Test
    fun `an order paid with credits only shows the total as credits`(): Unit = runBlocking {
        val message = build("order-confirmation-credits")
        val total = message.totals.single { it.strong }

        assertEquals("350.00 Coins", total.value)
        assertFalse(message.totals.any { it.label == "Paid with credits" })
    }

    @Test
    fun `an order that is not priced by the market shows only its total`(): Unit = runBlocking {
        val message = builder().build(MailInput(MailKind.ORDER_CONFIRMATION, "en-US", order(pricing = PricingMode.EXTERNAL), items))

        assertEquals(listOf("Total"), message.totals.map { it.label })
    }

    @Test
    fun `a gift mail shows no prices and no totals and quotes the message`(): Unit = runBlocking {
        val message = build("gift-received")
        val r = render(message, "en-US")

        assertEquals("Have fun on the server!", message.values["giftMessage"])
        assertTrue(r.html.contains("<blockquote style=\"font-style:italic\">Have fun on the server!</blockquote>"))
        assertTrue(message.items.all { it.total.isEmpty() })
        assertTrue(message.totals.isEmpty())
        assertEquals("https://shop.example/store", button(r, "Visit store"), "the gift links to the store, never an order or a token")
        assertFalse(r.text.contains("Hi Steve"), "no greeting by the payer's name to the recipient")
    }

    @Test
    fun `a refund mail lists the amount and where the money went, claims through a safe url only`(): Unit = runBlocking {
        val message = build("order-refunded")
        val r = render(message, "en-US")

        assertEquals("Refund for order #1042", r.subject)
        assertEquals(listOf("Order number", "Refund amount", "Returned to your payment method", "Returned as credits", "Reason"), message.details.map { it.label })
        assertEquals(listOf("#1042", "₺150.00", "₺100.00", "50.00 Coins", "Bought by mistake"), message.details.map { it.value })
        assertEquals("https://pay.example/claim/abc?x=1&y=2", button(r, "Claim refund"))
        assertTrue(r.html.contains(">View order</a>"), "the order is the second link")
        assertEquals(1, message.items.size)
        assertEquals("₺150.00", message.items[0].total)
        assertTrue(r.text.contains("credit note is attached"))

        val unsafe = MailInput(MailKind.ORDER_REFUNDED, "en-US", order(), items, refund = MarketRefund(id = 9, orderId = 1042, amount = 500, gatewayAmount = 500, currency = "TRY", buyerActionUrl = "javascript:alert(1)"))
        val safe = builder().build(unsafe)
        val safeR = render(safe, "en-US")

        assertEquals("", safe.values["claimUrl"])
        assertEquals("https://shop.example/store/order/K7M2Q9X4B8D6T3W5Z1RV", button(safeR, "View order"), "a javascript: claim url is never a button")
        assertFalse(safeR.html.contains("javascript:"))
        assertEquals(listOf("Order number", "Refund amount", "Returned to your payment method"), safe.details.map { it.label }, "no credits row without credits")
    }

    @Test
    fun `a credit only refund names no payment method row`(): Unit = runBlocking {
        val refund = MarketRefund(id = 9, orderId = 1042, amount = 2_000, gatewayAmount = 0, creditAmount = 2_000, currency = "TRY")
        val message = builder().build(MailInput(MailKind.ORDER_REFUNDED, "en-US", order(), items, refund = refund))

        assertEquals(listOf("Order number", "Refund amount", "Returned as credits"), message.details.map { it.label })
    }

    @Test
    fun `bank transfer instructions keep their fields, sanitise the html again and name the deadline`(): Unit = runBlocking {
        val message = build("bank-transfer-instructions")
        val r = render(message, "en-US")

        assertEquals(listOf("IBAN", "Reference"), message.instructionFields.map { it.label })
        assertEquals("TR33 0006 1005 1978 6457 8413 26", message.instructionFields[0].value)
        assertFalse(message.instructionsHtml!!.contains("<script"), "the stored body is sanitised again: ${message.instructionsHtml}")
        assertTrue(message.instructionsHtml!!.contains("Use the reference below."))
        assertFalse(r.html.contains("<script"))
        assertTrue(message.details.any { it.label == "Pay before" })
        assertTrue(r.text.contains("reserved until"))
    }

    @Test
    fun `an instruction without a deadline or a site url drops the deadline and the buttons`(): Unit = runBlocking {
        site = MailSite("Blocky Network", "")

        val params = JsonObject().put("instructions", JsonObject().put("body", "Pay by transfer").put("fields", JsonArray()))
        val message = builder().build(MailInput(MailKind.BANK_TRANSFER_INSTRUCTIONS, "en-US", order(status = OrderStatus.PENDING), items, params))
        val r = render(message, "en-US")

        assertTrue(message.details.none { it.label == "Pay before" })
        assertFalse(r.text.contains("reserved until"))
        assertEquals("", message.values["orderUrl"])
        assertFalse(hasButton(r))
    }

    @Test
    fun `dynamic strings are cut to 255 characters and escaped by the template`(): Unit = runBlocking {
        val long = "x".repeat(400)
        val o = MarketOrder(id = 5, playerUsername = "<b>Steve</b>", totalPrice = 100, currency = "TRY", status = OrderStatus.COMPLETED, createdAt = createdAt, publicId = "P", paidAt = paidAt, email = "a@b.example", locale = "en-US")
        val message = builder().build(MailInput(MailKind.ORDER_CONFIRMATION, "en-US", o, listOf(item(1, long, 1, 100))))

        assertEquals(255, message.items[0].name.length)

        val html = render(message, "en-US").html

        assertFalse(html.contains("<b>Steve</b>"))
        assertTrue(html.contains("&lt;b&gt;Steve&lt;/b&gt;"))
    }

    @Test
    fun `an unknown locale falls back to the language and then to en-US`(): Unit = runBlocking {
        assertEquals("#1042 numaralı siparişiniz teslim edildi", rendered("order-delivered", "tr-TR").subject)
        assertEquals("Your order #1042 has been delivered", render(builder().build(MailInput(MailKind.ORDER_DELIVERED, "de-DE", order(), items)), "de-DE").subject)
    }

    @Test
    fun `every kind of 12 section 4_1 has a composer and a golden scenario`(): Unit = runBlocking {
        assertEquals(MailKind.entries.toSet(), MailContentBuilder.KINDS, "no kind is left without a composer")

        val seen = scenarios("en-US").map { it.input.kind }.toSet()

        assertEquals(MailKind.entries.toSet(), seen, "every kind has a scenario, so the golden files cover it in tr, en-US and ru")

        for (kind in MailKind.entries) for (locale in locales) {
            val names = scenarios(locale).filter { it.input.kind == kind }.map { it.name }

            assertTrue(names.isNotEmpty())
            names.forEach { name -> assertTrue(File(goldenDir, "$name.$locale.golden").isFile, "golden file of $name in $locale") }
        }
    }

    @Test
    fun `an expiry reminder names the product and the end, and renews through the product page only while the product exists`(): Unit = runBlocking {
        val s = scenarios("en-US").first { it.name == "expiry-reminder" }
        val message = builder().build(s.input)
        val r = render(message, "en-US")

        assertEquals("Gold Rank expires on Jun 27, 2026", r.subject)
        assertEquals(listOf("Product", "Valid until"), message.details.map { it.label })
        assertEquals("https://shop.example/store/gold-rank", button(r, "Renew"))

        val gone = builder().build(MailInput(MailKind.EXPIRY_REMINDER, "en-US", order(), items, s.input.params, entitlement = s.input.entitlement, productSlug = null))

        assertEquals("", gone.values["renewUrl"])
        assertFalse(hasButton(render(gone, "en-US")), "a deleted or archived product leaves no renew button")

        val bad = builder().build(MailInput(MailKind.EXPIRY_REMINDER, "en-US", order(), items, s.input.params, entitlement = s.input.entitlement, productSlug = "../admin"))

        assertEquals("", bad.values["renewUrl"], "a slug with a path in it is never linked")
        assertThrows(IllegalStateException::class.java) { runBlocking { builder().build(MailInput(MailKind.EXPIRY_REMINDER, "en-US", order(), items, JsonObject(), entitlement = MarketEntitlement(id = 1))) } }
    }

    @Test
    fun `a subscription reminder asks a manual subscriber to pay and tells an automatic one what will be charged`(): Unit = runBlocking {
        val auto = rendered("subscription-reminder-gateway")
        val manual = rendered("subscription-reminder-manual")

        assertEquals("Gold Rank renews on Jun 27, 2026", auto.subject)
        assertTrue(auto.text.contains("We will charge ₺150.00"))
        assertTrue(auto.text.contains("It will be charged to: Visa **** 4242."), "the stored method is named: ${auto.text}")
        assertFalse(hasButton(auto), "an automatic renewal has nothing to pay")
        assertTrue(auto.html.contains("href=\"https://shop.example/profile\""))
        assertTrue(auto.html.contains(">Manage subscription</a>"))

        assertTrue(manual.text.contains("Please pay ₺150.00"))
        assertFalse(manual.text.contains("It will be charged to"))
        assertEquals("https://shop.example/store/order/PAYLINK0000000000000", button(manual, "Pay now"), "the renewal order's pay link, made absolute")

        val manualSub = scenarios("en-US").first { s -> s.name == "subscription-reminder-manual" }.input.subscription
        val noLink = builder().build(MailInput(MailKind.SUBSCRIPTION_REMINDER, "en-US", order(), items, JsonObject().put("periodEnd", 1_780_000_000_000L), subscription = manualSub, productSlug = "gold-rank"))

        assertEquals("https://shop.example/store/gold-rank", noLink.values["payUrl"], "without a pay link the product page is the target")

        val evil = builder().build(MailInput(MailKind.SUBSCRIPTION_REMINDER, "en-US", order(), items, JsonObject().put("periodEnd", 1_780_000_000_000L).put("payUrl", "javascript:alert(1)"), subscription = manualSub))

        assertEquals("", evil.values["payUrl"], "a stored link that is neither a site path nor http(s) is no button")
    }

    @Test
    fun `a failed payment shows the grace date and links the renewal order, else the profile`(): Unit = runBlocking {
        val failed = scenarios("en-US").first { it.name == "subscription-payment-failed" }.input
        val message = builder().build(failed)
        val r = render(message, "en-US")

        assertEquals("Payment failed for Gold Rank", r.subject)
        assertTrue(r.text.contains("until Jun 30, 2026"), r.text)
        assertEquals(listOf("Product", "Amount"), message.details.map { it.label })
        assertEquals("https://shop.example/profile", button(r, "Update payment"))

        val withOrder = builder().build(MailInput(MailKind.SUBSCRIPTION_PAYMENT_FAILED, "en-US", order(), items, JsonObject().put("payUrl", "/store/order/RENEWAL00000000000000"), subscription = failed.subscription))

        assertEquals("https://shop.example/store/order/RENEWAL00000000000000", withOrder.values["payUrl"])

        val noGrace = builder().build(MailInput(MailKind.SUBSCRIPTION_PAYMENT_FAILED, "en-US", order(), items, JsonObject(), subscription = MarketSubscription(id = 1, productName = "Gold Rank", price = 100, currency = "TRY")))
        val noGraceText = render(noGrace, "en-US").text

        assertFalse(noGraceText.contains("continues until"), "no grace date, no 'until': $noGraceText")
    }

    @Test
    fun `an ended subscription says why, an unknown reason falls back to the neutral sentence`(): Unit = runBlocking {
        val r = rendered("subscription-ended")

        assertTrue(r.text.contains("payment could not be collected"))
        assertFalse(r.text.contains("contact the store"), "one reason sentence only")
        assertEquals("https://shop.example/store", button(r, "Visit store"))

        for (reason in listOf("SOMETHING_NEW", "bad reason!", "")) {
            val other = builder().build(MailInput(MailKind.SUBSCRIPTION_ENDED, "en-US", order(), items, JsonObject().put("endReason", reason), subscription = MarketSubscription(id = 1, productName = "Gold Rank")))

            assertEquals(true, other.values["reasonOther"], reason)
            assertTrue(render(other, "en-US").text.contains("contact the store"), reason)
        }

        for (reason in MailContentBuilder.END_REASONS) {
            val message = builder().build(MailInput(MailKind.SUBSCRIPTION_ENDED, "en-US", order(), items, JsonObject().put("endReason", reason), subscription = MarketSubscription(id = 1, productName = "Gold Rank")))

            assertEquals(1, MailContentBuilder.END_REASONS.count { message.values[MailContentBuilder.reasonFlag(it)] == true }, reason)

            for (locale in locales) {
                val text = render(message, locale).text
                val base = render(MarketMailMessage("subscription-ended", message.values.filterKeys { !it.startsWith("reason") }), locale).text

                assertTrue(text.length > base.length, "$reason in $locale has a sentence of its own")
            }
        }
    }

    @Test
    fun `a cancelled subscription keeps access until the paid period ends`(): Unit = runBlocking {
        val r = rendered("subscription-cancelled")

        assertEquals("Gold Rank will not renew", r.subject)
        assertTrue(r.text.contains("until Jun 27, 2026"))
        assertFalse(hasButton(r))
        assertTrue(r.html.contains("href=\"https://shop.example/profile\""))

        val open = builder().build(MailInput(MailKind.SUBSCRIPTION_CANCELLED, "en-US", order(), items, JsonObject(), subscription = MarketSubscription(id = 1, productName = "Gold Rank")))

        assertTrue(render(open, "en-US").text.contains("end of the current period"))
    }

    @Test
    fun `a shipped mail names carrier, tracking number, estimate and parcel lines and tracks through a safe link only`(): Unit = runBlocking {
        val shipped = scenarios("en-US").first { it.name == "shipment-shipped" }.input
        val message = builder().build(shipped)
        val r = render(message, "en-US")

        assertEquals("Your order #1042 is on its way", r.subject)
        assertEquals(listOf("Order number", "Carrier", "Tracking number", "Estimated delivery", "Shipping to"), message.details.map { it.label })
        assertEquals("AR123456789", message.details[2].value)
        assertEquals("Steve Miner, 12 Cube Street, 34000 Istanbul, Turkey", message.details[4].value)
        assertEquals(listOf("Gold Rank", "Starter Bundle"), message.items.map { it.name })
        assertEquals(listOf("1", "2"), message.items.map { it.quantity })
        assertTrue(message.items.all { it.total.isEmpty() }, "a parcel shows no prices")
        assertTrue(r.text.contains("part of your order"), "the partial note")
        assertEquals("https://track.example/AR123456789?a=1&b=2", button(r, "Track shipment"))
        assertTrue(r.html.contains("href=\"https://shop.example/store/order/K7M2Q9X4B8D6T3W5Z1RV?token&#x3D;0f1e2d3c4b5a69788796a5b4c3d2e1f001122334\""), "a guest order's link carries the token")

        val unsafe = MarketShipment(id = 8, orderId = 1042, status = ShipmentStatus.IN_TRANSIT, carrierName = "X", trackingUrl = "javascript:alert(1)")
        val safe = builder().build(MailInput(MailKind.SHIPMENT_SHIPPED, "en-US", shipped.order, items, JsonObject(), shipment = unsafe))
        val safeR = render(safe, "en-US")

        assertTrue(button(safeR, "View order")!!.startsWith("https://shop.example/store/order/"), "a javascript: tracking url is never a button")
        assertFalse(safeR.html.contains("javascript:"))
        assertFalse(safeR.text.contains("part of your order"))
        assertEquals(listOf("Order number", "Carrier"), safe.details.map { it.label }, "no tracking row without a number")
    }

    @Test
    fun `a shipped mail without a shipment row still renders from the params the shipping service queued`(): Unit = runBlocking {
        val params = JsonObject().put("carrierName", "Yurtici").put("trackingNumber", "YT1").put("trackingUrl", "https://t.example/YT1").put("estimatedDelivery", "Jun 29, 2026")
            .put("items", JsonArray().add(JsonObject().put("name", "Poster").put("variantName", "A2").put("quantity", 3)))
        val message = builder().build(MailInput(MailKind.SHIPMENT_SHIPPED, "en-US", order(), items, params))

        assertEquals(listOf("Poster"), message.items.map { it.name })
        assertEquals("A2", message.items[0].variant)
        assertEquals("3", message.items[0].quantity)
        assertEquals("Jun 29, 2026", message.details.single { it.label == "Estimated delivery" }.value)
        assertEquals("https://t.example/YT1", message.values["trackingUrl"])
    }

    @Test
    fun `a delivered mail names carrier and delivery date and links the order`(): Unit = runBlocking {
        val message = build("shipment-delivered")
        val r = render(message, "en-US")

        assertEquals("Your order #1042 was delivered", r.subject)
        assertEquals(listOf("Order number", "Carrier", "Delivered on"), message.details.map { it.label })
        assertTrue(button(r, "View order")!!.startsWith("https://shop.example/store/order/"))
        assertEquals(2, message.items.size)
    }

    @Test
    fun `a test order marks the new kinds like the old ones and a missing row is no mail`(): Unit = runBlocking {
        val testOrder = order(testMode = true)
        val sub = MarketSubscription(id = 1, productName = "Gold Rank", price = 100, currency = "TRY", currentPeriodEnd = 1_780_000_000_000L)
        val message = builder().build(MailInput(MailKind.SUBSCRIPTION_REMINDER, "en-US", testOrder, items, JsonObject(), subscription = sub))

        assertTrue(render(message, "en-US").subject.startsWith("[TEST] "))
        assertTrue(message.testMode)

        assertThrows(IllegalStateException::class.java) { runBlocking { builder().build(MailInput(MailKind.SUBSCRIPTION_ENDED, "en-US", order(), items)) } }
        assertThrows(IllegalStateException::class.java) { runBlocking { builder().build(MailInput(MailKind.SUBSCRIPTION_CANCELLED, "en-US", order(), items)) } }
    }

    @Test
    fun `the plain test mail is translated and mentions no order`(): Unit = runBlocking {
        val subjects = locales.map { render(builder().test(it), it).subject }

        assertEquals(3, subjects.toSet().size)
        assertEquals("Test e-mail from Blocky Store", render(builder().test("en-US"), "en-US").subject)
        assertEquals(MarketMailMessage.TEST_VARIANT, builder().test("en-US").variant)
        assertTrue(builder().test("en-US").items.isEmpty())
        assertFalse(render(builder().test("en-US"), "en-US").text.contains("#"))
    }

    // ----- the bundled templates ---------------------------------------------------------------------------------------

    @Test
    fun `index json declares one variant per kind plus the test mail, each with a template in every language`() {
        val mail = MailRendering.index.mails.single()

        assertEquals(MarketMailMessage.KEY, mail.key)
        assertEquals(
            (MailKind.entries.map { MarketMailMessage.variantOf(it) } + MarketMailMessage.TEST_VARIANT).toSet(), mail.variants.map { it.key }.toSet(),
            "every MailKind has a variant and nothing else is declared"
        )

        for (variant in mail.variants) for (locale in locales) {
            val file = File("src/main/resources/mail-templates/${mail.key}/${variant.key}/$locale.hbs")

            assertTrue(file.isFile, "missing ${file.path}")

            val content = MailTemplateContent.parse(file.readText())

            assertTrue(content.subject.isNotBlank(), "${file.path}: subject")
            assertTrue(content.preheader.isNotBlank(), "${file.path}: preheader")
            assertTrue(content.footer.isNotBlank(), "${file.path}: footer")
        }
    }

    @Test
    fun `every variant renders with its declared samples and passes the panel's validation in every language`() {
        val mail = MailRendering.index.mails.single()

        for (variant in mail.variants) for (locale in locales) {
            val content = MailTemplateContent.parse(File("src/main/resources/mail-templates/${mail.key}/${variant.key}/$locale.hbs").readText())
            val system = MailRendering.system(locale)
            val validation = MailRendering.service.validate(content, variant.sampleVariables(), system)

            assertTrue(validation.valid, "${variant.key} / $locale: ${validation.fields} ${validation.message}")

            val r = MailRendering.service.render(content, MailTemplateService.Layout(MailRendering.service.defaultLayout(), false, "test"), variant.sampleVariables(), system)

            assertTrue(r.subject.isNotBlank())
            assertFalse(unresolved.containsMatchIn(r.subject + r.text + r.html), "${variant.key} / $locale: ${unresolved.find(r.subject + r.text + r.html)?.value}")
        }
    }

    @Test
    fun `every declared variable is one the builder sends, and the html ones are declared as html`(): Unit = runBlocking {
        val mail = MailRendering.index.mails.single()
        val html = setOf("testBanner", "itemsTable", "totalsTable", "detailsTable", "bankInstructions")

        for (s in scenarios("en-US")) {
            val message = builder().build(s.input)
            val variant = mail.variant(message.variant)!!
            val sent = message.variables().keys

            for (v in variant.variables) {
                assertTrue(v.name in sent, "${s.name}: ${v.name} is declared but never sent")
                assertEquals(v.name in html, v.type.id == "html", "${s.name}: ${v.name} has type ${v.type.id}")
            }
        }

        val test = mail.variant(MarketMailMessage.TEST_VARIANT)!!

        assertTrue(test.variables.all { it.name in builder().test("en-US").variables().keys })
    }

    // ----- the front-end URL map (doc 05 section 10.2) -----------------------------------------------------------------

    /** A site whose theme renamed the order route and whose front-end serves no store page, as the mail builder gets it from the platform. */
    private fun movedSite(): SiteLinksFixture = SiteLinksFixture(site = "https://play.example.com", website = "https://api.example.com")

    @Test
    fun `an order mail follows a renamed market order, for a logged-in buyer and a guest`(): Unit = runBlocking {
        val moved = movedSite().renameOrder()

        site = MailSite("Blocky Network", "https://api.example.com", moved.links)

        assertEquals("https://play.example.com/shop/purchase/K7M2Q9X4B8D6T3W5Z1RV", build("order-confirmation").values["orderUrl"])
        assertEquals(
            "https://play.example.com/shop/purchase/K7M2Q9X4B8D6T3W5Z1RV?token=0f1e2d3c4b5a69788796a5b4c3d2e1f001122334", build("shipment-shipped").values["orderUrl"],
            "the token of a guest order is added to the renamed page"
        )
    }

    @Test
    fun `a mail whose page the front-end does not serve falls back to the page of Pano or leaves the button out`(): Unit = runBlocking {
        val moved = movedSite().disable("/store/order/[id]", "/store", "/store/[slug]", "/profile")

        site = MailSite("Blocky Network", "https://api.example.com", moved.links)

        // the order page has a fallback page of Pano on the website address
        assertEquals("https://api.example.com/_pano/market.order?id=K7M2Q9X4B8D6T3W5Z1RV", build("order-confirmation").values["orderUrl"])

        // no store page: the gift mail has no button, as a mail without a site address has none
        val gift = build("gift-received")

        assertEquals("", gift.values["storeUrl"])
        assertFalse(hasButton(render(gift, "en-US")))

        // no product page: the renew button is left out
        assertEquals("", build("expiry-reminder").values["renewUrl"])
    }

    @Test
    fun `the product, store and profile buttons follow the URL map`(): Unit = runBlocking {
        val moved = movedSite()
        moved.inputs.overrides = mapOf(MarketTargets.PRODUCT to "/shop/item/{slug}", MarketTargets.STORE to "/shop", "user.profile" to "/me")
        site = MailSite("Blocky Network", "https://api.example.com", moved.links)

        assertEquals("https://play.example.com/shop/item/gold-rank", build("expiry-reminder").values["renewUrl"])
        assertEquals("https://play.example.com/shop", build("gift-received").values["storeUrl"])
    }

    @Test
    fun `a pay link that older mail rows kept as a path is still made absolute`(): Unit = runBlocking {
        site = MailSite("Blocky Network", "https://shop.example")

        assertEquals("https://shop.example/store/order/PAYLINK0000000000000", build("subscription-reminder-manual").values["payUrl"])
    }

    @Test
    fun `the three languages hold every label of the mail blocks`() {
        val bundles = MarketI18n.loadBundles(MailTemplateTest::class.java.classLoader)
        val keys = bundles.getValue("en-US").keys.filter { it.startsWith("mail.") }

        assertTrue(keys.size > 20, "the block labels are bundled: ${keys.size}")
        assertTrue(keys.all { it.startsWith("mail.blocks.") }, "only block labels are left in the locale files: ${keys.filterNot { it.startsWith("mail.blocks.") }}")

        for (key in keys) {
            val tr = bundles.getValue("tr")[key]
            val ru = bundles.getValue("ru")[key]
            val en = bundles.getValue("en-US").getValue(key)

            assertTrue(tr != null && ru != null, "$key exists in all three locales")
            assertFalse(en.contains("{"), "$key holds no placeholder: $en")
        }

        assertNotEquals(bundles.getValue("en-US")["mail.blocks.total"], bundles.getValue("tr")["mail.blocks.total"])
    }
}
