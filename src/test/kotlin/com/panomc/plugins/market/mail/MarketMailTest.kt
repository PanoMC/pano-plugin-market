package com.panomc.plugins.market.mail

import com.panomc.plugins.market.db.model.MailKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [MarketMailMessage] and the fixed HTML partials of [MailBlocks]: which `html` variables a message hands to the template, that
 * an empty block is an empty string, and that every value is escaped except the sanitised bank-transfer instructions.
 */
class MarketMailTest {
    private fun full() = MarketMailMessage(
        variant = "order-confirmation",
        values = mapOf("orderNumber" to "#1"),
        items = listOf(
            MailItem("Gold Rank", "30 days", "1", "EUR 5.00", false),
            MailItem("Starter Bundle", null, "2", "EUR 10.00", false),
            MailItem("Diamond Sword", null, "2", "", true)
        ),
        totals = listOf(MailRow("Subtotal", "EUR 15.00"), MailRow("Total", "EUR 15.00", strong = true)),
        details = listOf(MailRow("Order no.", "A1B2"), MailRow("Payment", "Card"))
    )

    @Test
    fun `the variant of a kind is its kebab-case name`() {
        assertEquals("order-received", MarketMailMessage.variantOf(MailKind.ORDER_RECEIVED))
        assertEquals("subscription-payment-failed", MarketMailMessage.variantOf(MailKind.SUBSCRIPTION_PAYMENT_FAILED))
        assertEquals("pano-plugin-market", MarketTemplateMail.OWNER)
        assertEquals("order", MarketTemplateMail(full()).key)
        assertEquals("order-confirmation", MarketTemplateMail(full()).variant)
    }

    @Test
    fun `the variables are the values plus the flag and every html block`() {
        val v = full().variables()

        assertEquals("#1", v["orderNumber"])
        assertEquals(false, v["testMode"])
        assertEquals("", v["testBanner"])
        assertEquals("", v["bankInstructions"], "no instructions, no block")

        val items = v["itemsTable"].toString()

        for (text in listOf("1 &#215; Gold Rank", "(30 days)", "Starter Bundle", "Diamond Sword", "EUR 5.00", ";padding-left:20px")) {
            assertTrue(items.contains(text), "items block misses $text: $items")
        }

        val totals = v["totalsTable"].toString()

        assertTrue(totals.contains("Subtotal") && totals.contains("font-weight:700"), totals)
        assertTrue(v["detailsTable"].toString().contains("A1B2"))
    }

    @Test
    fun `empty lists give empty blocks`() {
        val v = MarketMailMessage("order-delivered").variables()

        for (name in listOf("itemsTable", "totalsTable", "detailsTable", "bankInstructions", "testBanner")) assertEquals("", v[name], name)
    }

    @Test
    fun `the test banner shows its translated words only for a test order`() {
        assertTrue(MarketMailMessage("order-delivered", testMode = true, testBannerLabel = "Test siparisi").variables()["testBanner"].toString().contains("Test siparisi"))
        assertEquals("", MarketMailMessage("order-delivered", testMode = false, testBannerLabel = "Test siparisi").variables()["testBanner"])
        assertEquals(true, MarketMailMessage("order-delivered", testMode = true).variables()["testMode"])
    }

    @Test
    fun `buyer controlled strings are escaped, only the instructions html is raw`() {
        val v = MarketMailMessage(
            "bank-transfer-instructions",
            testMode = true,
            testBannerLabel = "<b>TEST</b>",
            items = listOf(MailItem("\"><img src=x onerror=1>", "<i>v</i>", "1", "EUR 1.00", false)),
            details = listOf(MailRow("<u>l</u>", "<a href=evil>v</a>")),
            instructionsHtml = "<p>Pay to <b>TR00 0000</b></p>",
            instructionFields = listOf(MailRow("IBAN", "<script>x</script>"))
        ).variables()
        val html = v.values.joinToString("") { it.toString() }

        assertFalse(html.contains("<script>"))
        assertFalse(html.contains("<img src=x"))
        assertFalse(html.contains("<a href=evil>"))
        assertFalse(html.contains("<b>TEST</b>"))
        assertTrue(html.contains("&quot;&gt;&lt;img src&#x3D;x onerror&#x3D;1&gt;"))
        assertTrue(html.contains("&lt;i&gt;v&lt;/i&gt;"))
        assertTrue(html.contains("&lt;script&gt;x&lt;/script&gt;"))
        assertTrue(v["bankInstructions"].toString().contains("<p>Pay to <b>TR00 0000</b></p>"))
    }

    @Test
    fun `instruction fields render without a body and the body without fields`() {
        val fieldsOnly = MailBlocks.instructions(null, listOf(MailRow("IBAN", "TR33 0006"), MailRow("Reference", "A1B2")))

        assertTrue(fieldsOnly.contains("TR33 0006") && fieldsOnly.contains("Reference"))
        assertFalse(fieldsOnly.contains("<div"))
        assertEquals(
            """<div style="font-size:15px;line-height:22px;color:rgb(54,65,83);margin-top:16px;margin-bottom:16px">Pay</div>""",
            MailBlocks.instructions("Pay", emptyList())
        )
        assertEquals("", MailBlocks.instructions("  ", emptyList()))
    }

    @Test
    fun `escape covers the html specials`() {
        assertEquals("&amp;&lt;&gt;&quot;&#x27;&#x60;&#x3D;a", MailBlocks.escape("&<>\"'`=a"))
    }
}
