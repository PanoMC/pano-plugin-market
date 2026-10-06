package com.panomc.plugins.market.component

import com.github.jknack.handlebars.Handlebars
import com.google.gson.Gson
import com.panomc.plugins.market.config.MarketConfig
import com.panomc.plugins.market.db.model.MailKind
import com.panomc.plugins.market.db.model.MarketOrder
import com.panomc.plugins.market.db.model.MarketOrderItem
import com.panomc.plugins.market.db.model.MarketRefund
import com.panomc.plugins.market.db.model.MarketRefundItem
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

        return listOf(
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
                    assertFalse(value.contains("mail.common.") || value.contains("mail.order-") || value.contains("mail.gift-") || value.contains("mail.bank-"), "${s.name} / $locale: a missing key leaked into $what")
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
    fun `the kinds of the other mail slice have no composer yet and say so`(): Unit = runBlocking {
        val open = MailKind.entries - MailContentBuilder.KINDS
        val expected = setOf(
            MailKind.SUBSCRIPTION_REMINDER, MailKind.SUBSCRIPTION_PAYMENT_FAILED, MailKind.SUBSCRIPTION_CANCELLED, MailKind.SUBSCRIPTION_ENDED, MailKind.EXPIRY_REMINDER,
            MailKind.SHIPMENT_SHIPPED, MailKind.SHIPMENT_DELIVERED
        )

        assertEquals(expected, open.toSet())

        for (kind in open) {
            assertFalse(builder().supports(kind))
            assertThrows(IllegalArgumentException::class.java) { runBlocking { builder().build(MailInput(kind, "en-US", order(), items)) } }
        }
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
