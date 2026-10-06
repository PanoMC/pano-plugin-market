package com.panomc.plugins.market.component

import com.github.jknack.handlebars.Handlebars
import com.google.gson.Gson
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
import com.panomc.plugins.market.mail.MailContent
import com.panomc.plugins.market.mail.MailContentBuilder
import com.panomc.plugins.market.mail.MailInput
import com.panomc.plugins.market.mail.MailSite
import com.panomc.plugins.market.mail.MarketMail
import com.panomc.plugins.market.support.FakeClock
import com.panomc.plugins.market.util.OrderStatus
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The order mails of `MailContentBuilder` (MK-142; 12 sections 3.1 and 5): every kind renders in tr, en-US and ru against golden files
 * (subject, preheader, plain text and the HTML of the real `market-mail.hbs` after the Gson round trip `MailManager` does), with no
 * unresolved `{{ }}` or `{var}` and a translated subject. Pure: the real bundled locale files, no database, no host.
 *
 * Golden files live next to this class in `golden/mail`. A changed golden is a reviewed change: run with `GOLDEN_UPDATE=1` to rewrite them
 * and read the diff; a missing golden fails the run (it never writes silently).
 */
class MailTemplateTest {
    private val handlebars = Handlebars()
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

    /** What `MailManager.sendMail` hands to the template. */
    private fun html(content: MailContent): String {
        val map = JsonObject(Gson().toJson(MarketMail(content).parameters())).map.toMutableMap()
        map["websiteUrl"] = site.websiteUrl
        map["websiteLogo"] = "cid:logo"
        map["websiteName"] = site.websiteName

        return MarketMail(content).getTemplate(handlebars).apply(map)
    }

    private fun golden(content: MailContent): String =
        "=== subject\n${content.subject}\n=== preheader\n${content.preheader}\n=== text\n${content.toText().replace("\r\n", "\n")}\n=== html\n${html(content)}\n"

    private val goldenDir = File("src/test/kotlin/com/panomc/plugins/market/component/golden/mail")
    private val update = System.getenv("GOLDEN_UPDATE") == "1"

    private val unresolved = Regex("\\{\\{|\\}\\}|\\{[A-Za-z][A-Za-z0-9_]*\\}")

    // ----- tests -------------------------------------------------------------------------------------------------------

    @Test
    fun `every order mail renders in tr, en-US and ru against its golden file`(): Unit = runBlocking {
        val seen = HashSet<MailKind>()
        var compared = 0

        for (locale in locales) {
            for (s in scenarios(locale)) {
                seen += s.input.kind

                val content = builder().build(s.input)
                val text = content.toText()
                val rendered = html(content)

                for ((what, value) in listOf("subject" to content.subject, "preheader" to content.preheader, "heading" to content.heading, "text" to text, "html" to rendered)) {
                    assertFalse(unresolved.containsMatchIn(value), "${s.name} / $locale: unresolved placeholder in $what: ${unresolved.find(value)?.value}")
                    assertFalse(listOf("mail.common.", "mail.order-", "mail.gift-", "mail.bank-", "mail.expiry-", "mail.subscription-", "mail.shipment-", "mail.test.").any { value.contains(it) }, "${s.name} / $locale: a missing key leaked into $what")
                }

                val file = File(goldenDir, "${s.name}.$locale.golden")
                val actual = golden(content)

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

                builder().build(s.input).subject
            }

            assertEquals(3, subjects.toSet().size, "$kind: one subject per language: $subjects")
            subjects.forEach { assertFalse(it.contains("mail."), "$kind subject is a key: $it") }
        }
    }

    @Test
    fun `an order id is the order number and the payer name fills the subject of a gift`(): Unit = runBlocking {
        val gift = scenarios("en-US").first { it.name == "gift-received" }
        val confirmed = scenarios("en-US").first { it.name == "order-confirmation" }

        assertEquals("Steve sent you a gift on Blocky Network", builder().build(gift.input).subject)
        assertEquals("Your order #1042 is confirmed", builder().build(confirmed.input).subject)
    }

    @Test
    fun `confirmation totals follow 12 section 5 rows in order, the discount is negative and the VAT row says included`(): Unit = runBlocking {
        val content = builder().build(scenarios("en-US").first { it.name == "order-confirmation" }.input)

        assertEquals(listOf("Subtotal", "Discount", "VAT (included)", "Total"), content.totals.map { it.label })
        assertEquals(listOf("₺400.00", "-₺50.00", "₺58.33", "₺350.00"), content.totals.map { it.value })
        assertTrue(content.totals.last().strong)
        assertEquals(listOf("1", "2", "2"), content.items.map { it.quantity })
        assertEquals(listOf(false, false, true), content.items.map { it.child })
        assertEquals("", content.items[2].total, "a bundle child carries no price")
        assertEquals("₺150.00", content.items[0].total)
        assertEquals("30 days", content.items[0].variant)
        assertTrue(content.footerNote!!.contains("invoice is attached"))
        assertEquals("View order", content.buttonLabel)
        assertEquals("https://shop.example/store/order/K7M2Q9X4B8D6T3W5Z1RV", content.buttonUrl, "an order of a logged-in user never carries the token")
    }

    @Test
    fun `a guest order link carries the token, a test order is prefixed and bannered, a physical order names its address`(): Unit = runBlocking {
        val content = builder().build(scenarios("en-US").first { it.name == "order-confirmation-physical-test" }.input)

        assertTrue(content.buttonUrl!!.endsWith("?token=0f1e2d3c4b5a69788796a5b4c3d2e1f001122334"))
        assertTrue(content.subject.startsWith("[TEST] "))
        assertTrue(content.testMode)
        assertEquals("TEST ORDER - no real payment was made", content.testModeLabel)
        assertTrue(content.paragraphs.any { it.contains("12 Cube Street") && it.contains("Istanbul") }, "shipping address paragraph: ${content.paragraphs}")
        assertEquals(listOf("Subtotal", "Discount", "Shipping", "VAT", "Total", "Paid with credits"), content.totals.map { it.label })
        assertEquals("100.00 Coins", content.totals.last().value)
        assertFalse(content.footerNote!!.contains("attached"))
    }

    @Test
    fun `an order paid with credits only shows the total as credits`(): Unit = runBlocking {
        val content = builder().build(scenarios("en-US").first { it.name == "order-confirmation-credits" }.input)
        val total = content.totals.single { it.strong }

        assertEquals("350.00 Coins", total.value)
        assertFalse(content.totals.any { it.label == "Paid with credits" })
    }

    @Test
    fun `an order that is not priced by the market shows only its total`(): Unit = runBlocking {
        val input = MailInput(MailKind.ORDER_CONFIRMATION, "en-US", order(pricing = PricingMode.EXTERNAL), items)
        val content = builder().build(input)

        assertEquals(listOf("Total"), content.totals.map { it.label })
    }

    @Test
    fun `a gift mail shows no prices and no totals and quotes the message`(): Unit = runBlocking {
        val content = builder().build(scenarios("en-US").first { it.name == "gift-received" }.input)

        assertEquals("Have fun on the server!", content.quote)
        assertTrue(content.items.all { it.total.isEmpty() })
        assertTrue(content.totals.isEmpty())
        assertEquals("https://shop.example/store", content.buttonUrl, "the gift links to the store, never an order or a token")
        assertEquals("Visit store", content.buttonLabel)
        assertTrue(content.paragraphs.none { it.startsWith("Hi ") }, "no greeting by the payer's name to the recipient")
    }

    @Test
    fun `a refund mail lists the amount and where the money went, claims through a safe url only`(): Unit = runBlocking {
        val s = scenarios("en-US").first { it.name == "order-refunded" }
        val content = builder().build(s.input)

        assertEquals("Refund for order #1042", content.subject)
        assertEquals(
            listOf("Order number", "Refund amount", "Returned to your payment method", "Returned as credits", "Reason"), content.details.map { it.label }
        )
        assertEquals(listOf("#1042", "₺150.00", "₺100.00", "50.00 Coins", "Bought by mistake"), content.details.map { it.value })
        assertEquals("Claim refund", content.buttonLabel)
        assertEquals("https://pay.example/claim/abc?x=1&y=2", content.buttonUrl)
        assertEquals("View order", content.secondaryLabel)
        assertEquals(1, content.items.size)
        assertEquals("₺150.00", content.items[0].total)
        assertTrue(content.footerNote!!.contains("credit note is attached"))

        val unsafe = MailInput(MailKind.ORDER_REFUNDED, "en-US", order(), items, refund = MarketRefund(id = 9, orderId = 1042, amount = 500, gatewayAmount = 500, currency = "TRY", buyerActionUrl = "javascript:alert(1)"))
        val safe = builder().build(unsafe)

        assertEquals("View order", safe.buttonLabel, "a javascript: claim url is never a button")
        assertNull(safe.secondaryLabel)
        assertEquals(listOf("Order number", "Refund amount", "Returned to your payment method"), safe.details.map { it.label }, "no credits row without credits")
    }

    @Test
    fun `a credit only refund names no payment method row`(): Unit = runBlocking {
        val refund = MarketRefund(id = 9, orderId = 1042, amount = 2_000, gatewayAmount = 0, creditAmount = 2_000, currency = "TRY")
        val content = builder().build(MailInput(MailKind.ORDER_REFUNDED, "en-US", order(), items, refund = refund))

        assertEquals(listOf("Order number", "Refund amount", "Returned as credits"), content.details.map { it.label })
    }

    @Test
    fun `bank transfer instructions keep their fields, sanitise the html again and name the deadline`(): Unit = runBlocking {
        val content = builder().build(scenarios("en-US").first { it.name == "bank-transfer-instructions" }.input)

        assertEquals(listOf("IBAN", "Reference"), content.instructionFields.map { it.label })
        assertEquals("TR33 0006 1005 1978 6457 8413 26", content.instructionFields[0].value)
        assertFalse(content.instructionsHtml!!.contains("<script"), "the stored body is sanitised again: ${content.instructionsHtml}")
        assertTrue(content.instructionsHtml!!.contains("Use the reference below."))
        assertTrue(content.details.any { it.label == "Pay before" })
        assertTrue(content.paragraphs.any { it.contains("reserved until") })
        assertNull(content.quote)
    }

    @Test
    fun `an instruction without a deadline or a site url drops the deadline and the buttons`(): Unit = runBlocking {
        site = MailSite("Blocky Network", "")

        val params = JsonObject().put("instructions", JsonObject().put("body", "Pay by transfer").put("fields", JsonArray()))
        val content = builder().build(MailInput(MailKind.BANK_TRANSFER_INSTRUCTIONS, "en-US", order(status = OrderStatus.PENDING), items, params))

        assertTrue(content.details.none { it.label == "Pay before" })
        assertTrue(content.paragraphs.none { it.contains("reserved until") })
        assertNull(content.buttonUrl)
        assertNull(content.buttonLabel)
    }

    @Test
    fun `dynamic strings are cut to 255 characters and escaped by the template`(): Unit = runBlocking {
        val long = "x".repeat(400)
        val o = MarketOrder(id = 5, playerUsername = "<b>Steve</b>", totalPrice = 100, currency = "TRY", status = OrderStatus.COMPLETED, createdAt = createdAt, publicId = "P", paidAt = paidAt, email = "a@b.example", locale = "en-US")
        val content = builder().build(MailInput(MailKind.ORDER_CONFIRMATION, "en-US", o, listOf(item(1, long, 1, 100))))

        assertEquals(255, content.items[0].name.length)

        val rendered = html(content)

        assertFalse(rendered.contains("<b>Steve</b>"))
        assertTrue(rendered.contains("&lt;b&gt;Steve&lt;/b&gt;"))
    }

    @Test
    fun `an unknown locale falls back to the language and then to en-US`(): Unit = runBlocking {
        val input = scenarios("tr-TR").first { it.name == "order-delivered" }.input
        val content = builder().build(input)

        assertEquals("#1042 numaralı siparişiniz teslim edildi", content.subject)

        val other = builder().build(MailInput(MailKind.ORDER_DELIVERED, "de-DE", order(), items))

        assertEquals("Your order #1042 has been delivered", other.subject)
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
        val content = builder().build(s.input)

        assertEquals("Gold Rank expires on Jun 27, 2026", content.subject)
        assertEquals(listOf("Product", "Valid until"), content.details.map { it.label })
        assertEquals("Renew", content.buttonLabel)
        assertEquals("https://shop.example/store/gold-rank", content.buttonUrl)

        val gone = builder().build(MailInput(MailKind.EXPIRY_REMINDER, "en-US", order(), items, s.input.params, entitlement = s.input.entitlement, productSlug = null))

        assertNull(gone.buttonUrl, "a deleted or archived product leaves no renew button")
        assertNull(gone.buttonLabel)

        val bad = builder().build(MailInput(MailKind.EXPIRY_REMINDER, "en-US", order(), items, s.input.params, entitlement = s.input.entitlement, productSlug = "../admin"))

        assertNull(bad.buttonUrl, "a slug with a path in it is never linked")
        assertThrows(IllegalStateException::class.java) { runBlocking { builder().build(MailInput(MailKind.EXPIRY_REMINDER, "en-US", order(), items, JsonObject(), entitlement = MarketEntitlement(id = 1))) } }
    }

    @Test
    fun `a subscription reminder asks a manual subscriber to pay and tells an automatic one what will be charged`(): Unit = runBlocking {
        val auto = builder().build(scenarios("en-US").first { it.name == "subscription-reminder-gateway" }.input)
        val manual = builder().build(scenarios("en-US").first { it.name == "subscription-reminder-manual" }.input)

        assertEquals("Gold Rank renews on Jun 27, 2026", auto.subject)
        assertTrue(auto.paragraphs.any { it.contains("We will charge ₺150.00") })
        assertTrue(auto.paragraphs.any { it.contains("Visa **** 4242") }, "the stored method is named: ${auto.paragraphs}")
        assertNull(auto.buttonUrl, "an automatic renewal has nothing to pay")
        assertEquals("https://shop.example/profile", auto.secondaryUrl)
        assertEquals("Manage subscription", auto.secondaryLabel)

        assertTrue(manual.paragraphs.any { it.contains("Please pay ₺150.00") })
        assertEquals("Pay now", manual.buttonLabel)
        assertEquals("https://shop.example/store/order/PAYLINK0000000000000", manual.buttonUrl, "the renewal order's pay link, made absolute")

        val noLink = builder().build(MailInput(MailKind.SUBSCRIPTION_REMINDER, "en-US", order(), items, JsonObject().put("periodEnd", 1_780_000_000_000L), subscription = manual.let { scenarios("en-US").first { s -> s.name == "subscription-reminder-manual" }.input.subscription }, productSlug = "gold-rank"))

        assertEquals("https://shop.example/store/gold-rank", noLink.buttonUrl, "without a pay link the product page is the target")

        val evil = builder().build(MailInput(MailKind.SUBSCRIPTION_REMINDER, "en-US", order(), items, JsonObject().put("periodEnd", 1_780_000_000_000L).put("payUrl", "javascript:alert(1)"), subscription = scenarios("en-US").first { it.name == "subscription-reminder-manual" }.input.subscription))

        assertNull(evil.buttonUrl, "a stored link that is neither a site path nor http(s) is no button")
    }

    @Test
    fun `a failed payment shows the grace date and links the renewal order, else the profile`(): Unit = runBlocking {
        val failed = scenarios("en-US").first { it.name == "subscription-payment-failed" }.input
        val content = builder().build(failed)

        assertEquals("Payment failed for Gold Rank", content.subject)
        assertTrue(content.paragraphs.any { it.contains("until Jun 30, 2026") }, content.paragraphs.toString())
        assertEquals(listOf("Product", "Amount"), content.details.map { it.label })
        assertEquals("Update payment", content.buttonLabel)
        assertEquals("https://shop.example/profile", content.buttonUrl)

        val withOrder = builder().build(MailInput(MailKind.SUBSCRIPTION_PAYMENT_FAILED, "en-US", order(), items, JsonObject().put("payUrl", "/store/order/RENEWAL00000000000000"), subscription = failed.subscription))

        assertEquals("https://shop.example/store/order/RENEWAL00000000000000", withOrder.buttonUrl)

        val noGrace = builder().build(MailInput(MailKind.SUBSCRIPTION_PAYMENT_FAILED, "en-US", order(), items, JsonObject(), subscription = MarketSubscription(id = 1, productName = "Gold Rank", price = 100, currency = "TRY")))

        assertTrue(noGrace.paragraphs.none { it.contains("until") }, "no grace date, no 'until': ${noGrace.paragraphs}")
    }

    @Test
    fun `an ended subscription says why, an unknown reason falls back to the neutral sentence`(): Unit = runBlocking {
        val ended = scenarios("en-US").first { it.name == "subscription-ended" }.input
        val content = builder().build(ended)

        assertTrue(content.paragraphs.any { it.contains("payment could not be collected") })
        assertEquals("https://shop.example/store", content.buttonUrl)

        for (reason in listOf("SOMETHING_NEW", "bad reason!", "")) {
            val other = builder().build(MailInput(MailKind.SUBSCRIPTION_ENDED, "en-US", order(), items, JsonObject().put("endReason", reason), subscription = MarketSubscription(id = 1, productName = "Gold Rank")))

            assertTrue(other.paragraphs.any { it.contains("contact the store") }, "$reason: ${other.paragraphs}")
        }

        for (reason in listOf("BUYER_CANCEL", "ADMIN_CANCEL", "PAYMENT_FAILED", "GATEWAY_ENDED", "COMPLETED", "REFUND", "CHARGEBACK", "PROVIDER_UNAVAILABLE", "OTHER")) {
            for (locale in locales) assertTrue(i18n.has(locale, "mail.subscription-ended.reason.$reason"), "$reason in $locale")
        }
    }

    @Test
    fun `a cancelled subscription keeps access until the paid period ends`(): Unit = runBlocking {
        val content = builder().build(scenarios("en-US").first { it.name == "subscription-cancelled" }.input)

        assertEquals("Gold Rank will not renew", content.subject)
        assertTrue(content.paragraphs.any { it.contains("until Jun 27, 2026") })
        assertNull(content.buttonUrl)
        assertEquals("https://shop.example/profile", content.secondaryUrl)

        val open = builder().build(MailInput(MailKind.SUBSCRIPTION_CANCELLED, "en-US", order(), items, JsonObject(), subscription = MarketSubscription(id = 1, productName = "Gold Rank")))

        assertTrue(open.paragraphs.any { it.contains("end of the current period") })
    }

    @Test
    fun `a shipped mail names carrier, tracking number, estimate and parcel lines and tracks through a safe link only`(): Unit = runBlocking {
        val shipped = scenarios("en-US").first { it.name == "shipment-shipped" }.input
        val content = builder().build(shipped)

        assertEquals("Your order #1042 is on its way", content.subject)
        assertEquals(listOf("Order number", "Carrier", "Tracking number", "Estimated delivery", "Shipping to"), content.details.map { it.label })
        assertEquals("AR123456789", content.details[2].value)
        assertEquals("Steve Miner, 12 Cube Street, 34000 Istanbul, Turkey", content.details[4].value)
        assertEquals(listOf("Gold Rank", "Starter Bundle"), content.items.map { it.name })
        assertEquals(listOf("1", "2"), content.items.map { it.quantity })
        assertTrue(content.items.all { it.total.isEmpty() }, "a parcel shows no prices")
        assertTrue(content.paragraphs.any { it.contains("part of your order") }, "the partial note")
        assertEquals("Track shipment", content.buttonLabel)
        assertEquals("https://track.example/AR123456789?a=1&b=2", content.buttonUrl)
        assertEquals("View order", content.secondaryLabel)
        assertEquals("https://shop.example/store/order/K7M2Q9X4B8D6T3W5Z1RV?token=0f1e2d3c4b5a69788796a5b4c3d2e1f001122334", content.secondaryUrl, "a guest order's link carries the token")

        val unsafe = MarketShipment(id = 8, orderId = 1042, status = ShipmentStatus.IN_TRANSIT, carrierName = "X", trackingUrl = "javascript:alert(1)")
        val safe = builder().build(MailInput(MailKind.SHIPMENT_SHIPPED, "en-US", shipped.order, items, JsonObject(), shipment = unsafe))

        assertEquals("View order", safe.buttonLabel, "a javascript: tracking url is never a button")
        assertNull(safe.secondaryLabel)
        assertEquals(listOf("Order number", "Carrier"), safe.details.map { it.label }, "no tracking row without a number")
    }

    @Test
    fun `a shipped mail without a shipment row still renders from the params the shipping service queued`(): Unit = runBlocking {
        val params = JsonObject().put("carrierName", "Yurtici").put("trackingNumber", "YT1").put("trackingUrl", "https://t.example/YT1").put("estimatedDelivery", "Jun 29, 2026")
            .put("items", JsonArray().add(JsonObject().put("name", "Poster").put("variantName", "A2").put("quantity", 3)))
        val content = builder().build(MailInput(MailKind.SHIPMENT_SHIPPED, "en-US", order(), items, params))

        assertEquals(listOf("Poster"), content.items.map { it.name })
        assertEquals("A2", content.items[0].variant)
        assertEquals("3", content.items[0].quantity)
        assertEquals("Jun 29, 2026", content.details.single { it.label == "Estimated delivery" }.value)
        assertEquals("https://t.example/YT1", content.buttonUrl)
    }

    @Test
    fun `a delivered mail names carrier and delivery date and links the order`(): Unit = runBlocking {
        val content = builder().build(scenarios("en-US").first { it.name == "shipment-delivered" }.input)

        assertEquals("Your order #1042 was delivered", content.subject)
        assertEquals(listOf("Order number", "Carrier", "Delivered on"), content.details.map { it.label })
        assertEquals("View order", content.buttonLabel)
        assertEquals(2, content.items.size)
    }

    @Test
    fun `a test order marks the new kinds like the old ones and a missing row is no mail`(): Unit = runBlocking {
        val testOrder = order(testMode = true)
        val sub = MarketSubscription(id = 1, productName = "Gold Rank", price = 100, currency = "TRY", currentPeriodEnd = 1_780_000_000_000L)
        val content = builder().build(MailInput(MailKind.SUBSCRIPTION_REMINDER, "en-US", testOrder, items, JsonObject(), subscription = sub))

        assertTrue(content.subject.startsWith("[TEST] "))
        assertTrue(content.testMode)

        assertThrows(IllegalStateException::class.java) { runBlocking { builder().build(MailInput(MailKind.SUBSCRIPTION_ENDED, "en-US", order(), items)) } }
        assertThrows(IllegalStateException::class.java) { runBlocking { builder().build(MailInput(MailKind.SUBSCRIPTION_CANCELLED, "en-US", order(), items)) } }
    }

    @Test
    fun `the plain test mail is translated and mentions no order`(): Unit = runBlocking {
        val subjects = locales.map { builder().test(it).subject }

        assertEquals(3, subjects.toSet().size)
        assertEquals("Test e-mail from Blocky Store", builder().test("en-US").subject)
        assertTrue(builder().test("en-US").items.isEmpty())
    }

    @Test
    fun `the three languages say different things for every key of the kinds this slice owns`() {
        val bundles = MarketI18n.loadBundles(MailTemplateTest::class.java.classLoader)
        val keys = bundles.getValue("en-US").keys.filter { it.startsWith("mail.") }

        assertTrue(keys.size > 40, "the mail keys are bundled: ${keys.size}")

        for (key in keys) {
            val tr = bundles.getValue("tr")[key]
            val ru = bundles.getValue("ru")[key]
            val en = bundles.getValue("en-US").getValue(key)

            assertTrue(tr != null && ru != null, "$key exists in all three locales")
            assertFalse(en.contains(Regex("\\{[^A-Za-z}]")), "$key holds no ICU syntax: $en")
            assertEquals(placeholders(en), placeholders(tr!!), "$key: tr uses the same placeholders as en-US")
            assertEquals(placeholders(en), placeholders(ru!!), "$key: ru uses the same placeholders as en-US")
        }

        assertNotEquals(bundles.getValue("en-US")["mail.order-confirmation.subject"], bundles.getValue("tr")["mail.order-confirmation.subject"])
    }

    private fun placeholders(text: String): Set<String> = Regex("\\{([A-Za-z][A-Za-z0-9_]*)\\}").findAll(text).map { it.groupValues[1] }.toSet()
}
