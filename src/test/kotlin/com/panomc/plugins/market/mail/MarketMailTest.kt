package com.panomc.plugins.market.mail

import com.github.jknack.handlebars.Handlebars
import com.google.gson.Gson
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `market-mail.hbs` and `MarketMail` (MK-141; the T-MAIL-1 / T-MAIL-2 / T-MAIL-3 core, the per-kind goldens are MK-142): the
 * template is rendered through a real `Handlebars()` after the same Gson -> `JsonObject.map` round trip `MailManager` does.
 */
class MarketMailTest {
    private val handlebars = Handlebars()

    private fun full() = MailContent(
        subject = "Your order #A1B2",
        preheader = "Thank you for your order",
        heading = "Order confirmed",
        paragraphs = listOf("Hello Steve,", "We received your payment."),
        items = listOf(
            MailContent.Item("Gold Rank", "30 days", "1", "EUR 5.00", false),
            MailContent.Item("Starter Bundle", null, "2", "EUR 10.00", false),
            MailContent.Item("Diamond Sword", null, "2", "EUR 0.00", true)
        ),
        totals = listOf(MailContent.Row("Subtotal", "EUR 15.00"), MailContent.Row("Total", "EUR 15.00", strong = true)),
        details = listOf(MailContent.Row("Order no.", "A1B2"), MailContent.Row("Payment", "Card")),
        buttonLabel = "View order",
        buttonUrl = "https://shop.example/store/order/A1B2?token=abc&x=1",
        footerNote = "Questions? Reply to this mail."
    )

    /** What `MailManager.sendMail` hands to the template. */
    private fun render(content: MailContent): String {
        val map = JsonObject(Gson().toJson(MarketMail(content).parameters())).map.toMutableMap()
        map["websiteUrl"] = "https://shop.example"
        map["websiteLogo"] = "cid:logo"
        map["websiteName"] = "Shop & Co"
        return MarketMail(content).getTemplate(handlebars).apply(map)
    }

    @Test
    fun `the mail class names the template and carries no subject of its own`() {
        val mail = MarketMail(full())
        assertEquals("mail/market-mail.hbs", mail.templatePath)
        assertEquals("", mail.subject)
        assertTrue(mail.getTemplateContent().contains("{{heading}}"))
    }

    @Test
    fun `a full mail renders every block with no placeholder left`() {
        val html = render(full())
        for (text in listOf("Order confirmed", "Hello Steve,", "We received your payment.", "Gold Rank", "(30 days)", "Starter Bundle", "Diamond Sword", "EUR 15.00", "Subtotal", "Order no.", "A1B2", "Card", "View order", "Questions? Reply to this mail.", "Thank you for your order")) {
            assertTrue(html.contains(text), "missing: $text")
        }
        assertFalse(html.contains("{{"), "unresolved placeholder")
        assertFalse(html.contains("}}"))
        assertTrue(html.contains("src=\"cid:logo\""))
        assertTrue(html.contains("Shop &amp; Co"))
        assertTrue(html.contains("href=\"https://shop.example/store/order/A1B2?token&#x3D;abc&amp;x&#x3D;1\""))
        assertFalse(html.contains("TEST MODE"))
    }

    @Test
    fun `empty lists and missing optionals omit their blocks`() {
        val html = render(MailContent(subject = "s", preheader = "p", heading = "Only a heading"))
        assertTrue(html.contains("Only a heading"))
        assertFalse(html.contains("&times;"))
        assertFalse(html.contains("<a "), "no button without a url")
        assertFalse(html.contains("border-left-style"), "no quote block")
        assertFalse(html.contains("background-color:rgb(249,250,251)"), "no details block")
        assertFalse(html.contains("{{"))

        // a button needs both label and url
        assertFalse(render(MailContent(subject = "s", preheader = "p", heading = "h", buttonLabel = "Go")).contains("<a "))
        assertFalse(render(MailContent(subject = "s", preheader = "p", heading = "h", buttonUrl = "https://x.example")).contains("<a "))
    }

    @Test
    fun `the test banner shows its translated words only for a test order`() {
        val on = render(MailContent(subject = "s", preheader = "p", heading = "h", testMode = true, testModeLabel = "Test siparisi"))
        assertTrue(on.contains("Test siparisi"))
        val off = render(MailContent(subject = "s", preheader = "p", heading = "h", testMode = false, testModeLabel = "Test siparisi"))
        assertFalse(off.contains("Test siparisi"))
    }

    @Test
    fun `buyer controlled strings are escaped, only the instructions html is raw`() {
        val html = render(
            MailContent(
                subject = "s", preheader = "p", heading = "<b>head</b>",
                paragraphs = listOf("<script>alert(1)</script>"),
                quote = "<script>gift()</script>",
                items = listOf(MailContent.Item("\"><img src=x onerror=1>", "<i>v</i>", "1", "EUR 1.00", false)),
                details = listOf(MailContent.Row("<u>l</u>", "<a href=evil>v</a>")),
                instructionsHtml = "<p>Pay to <b>TR00 0000</b></p>",
                instructionFields = listOf(MailContent.Row("IBAN", "<script>x</script>"))
            )
        )
        assertFalse(html.contains("<script>"))
        assertFalse(html.contains("<img src=x"))
        assertFalse(html.contains("<b>head</b>"))
        assertFalse(html.contains("<a href=evil>"))
        assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt;"))
        assertTrue(html.contains("&lt;script&gt;gift()&lt;/script&gt;"))
        assertTrue(html.contains("&quot;&gt;&lt;img src&#x3D;x onerror&#x3D;1&gt;"))
        assertTrue(html.contains("<p>Pay to <b>TR00 0000</b></p>"))
    }

    @Test
    fun `quote and instruction fields render in their own blocks`() {
        val html = render(
            MailContent(
                subject = "s", preheader = "p", heading = "h", quote = "Have fun!",
                instructionFields = listOf(MailContent.Row("IBAN", "TR33 0006"), MailContent.Row("Reference", "A1B2")),
                secondaryLabel = "Store", secondaryUrl = "https://shop.example/store"
            )
        )
        assertTrue(html.contains("Have fun!"))
        assertTrue(html.contains("TR33 0006"))
        assertTrue(html.contains("Reference"))
        assertTrue(html.contains("href=\"https://shop.example/store\""))
    }

    @Test
    fun `toText is the plain alternative with CRLF line ends only`() {
        val text = full().toText()
        assertEquals(
            listOf(
                "Order confirmed",
                "",
                "Hello Steve,",
                "",
                "We received your payment.",
                "",
                "- 1 x Gold Rank (30 days) ... EUR 5.00",
                "- 2 x Starter Bundle ... EUR 10.00",
                "  - 2 x Diamond Sword ... EUR 0.00",
                "",
                "Subtotal: EUR 15.00",
                "Total: EUR 15.00",
                "",
                "Order no.: A1B2",
                "Payment: Card",
                "",
                "View order: https://shop.example/store/order/A1B2?token=abc&x=1",
                "",
                "Questions? Reply to this mail."
            ).joinToString("\r\n"),
            text
        )
        assertFalse(text.replace("\r\n", "").contains('\n'))
        assertFalse(text.replace("\r\n", "").contains('\r'))
    }

    @Test
    fun `toText converts the instructions html and the quote and marks a test order`() {
        val text = MailContent(
            subject = "s", preheader = "p", heading = "Pay now", testMode = true, testModeLabel = "TEST",
            quote = "line one\nline two",
            instructionsHtml = "<p>Send <b>EUR 10</b> &amp; keep the receipt.</p><p>Thanks<br>Team</p>",
            instructionFields = listOf(MailContent.Row("IBAN", "TR33"))
        ).toText()
        assertEquals(
            listOf(
                "[TEST]", "Pay now", "", "> line one", "> line two", "", "Send EUR 10 & keep the receipt.", "Thanks", "Team", "", "IBAN: TR33"
            ).joinToString("\r\n"),
            text
        )
    }
}
