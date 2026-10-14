package com.panomc.plugins.market.mail

import com.panomc.plugins.market.db.model.MailKind
import com.panomc.plugins.market.support.MailRendering
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [MarketMailMessage]: the variables a message hands to the template (values, the test flag, the `items` and `instructionFields`
 * lists as plain rows with no null value, the sanitised `instructions`), and that the templates escape every value except the
 * bank-transfer instructions.
 */
class MarketMailTest {
    private fun full() = MarketMailMessage(
        variant = "order-confirmation",
        values = mapOf("orderNumber" to "#1", "subtotal" to "EUR 15.00", "total" to "EUR 15.00"),
        items = listOf(
            MailItem("Gold Rank", "30 days", "1", "EUR 5.00", false),
            MailItem("Starter Bundle", null, "2", "EUR 10.00", false),
            MailItem("Diamond Sword", null, "2", "", true)
        )
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
    fun `the variables are the values, the flag and the rows as plain maps without nulls`() {
        val v = full().variables()

        assertEquals("#1", v["orderNumber"])
        assertEquals(false, v["testMode"])
        assertEquals("", v["instructions"])
        assertEquals(emptyList<Any>(), v["instructionFields"])
        assertEquals(
            listOf(
                mapOf("name" to "Gold Rank", "variant" to "30 days", "quantity" to "1", "total" to "EUR 5.00", "child" to false),
                mapOf("name" to "Starter Bundle", "variant" to "", "quantity" to "2", "total" to "EUR 10.00", "child" to false),
                mapOf("name" to "Diamond Sword", "variant" to "", "quantity" to "2", "total" to "", "child" to true)
            ),
            v["items"]
        )
        assertEquals(true, MarketMailMessage("order-delivered", testMode = true).variables()["testMode"])
    }

    @Test
    fun `the template renders the rows, indents a bundle part and leaves a missing variant and an empty total out`() {
        val html = MailRendering.render(full(), "en-US").html

        for (text in listOf("1 &#215; Gold Rank", "(30 days)", "2 &#215; Starter Bundle", "EUR 5.00", ";padding-left:20px\">2 &#215; Diamond Sword</td>")) {
            assertTrue(html.contains(text), "items table misses $text")
        }

        assertFalse(html.contains("Starter Bundle <span"), "no variant, no brackets")
        assertFalse(html.contains("TEST ORDER"))
    }

    @Test
    fun `buyer controlled strings are escaped, only the instructions html is raw`() {
        val message = MarketMailMessage(
            "bank-transfer-instructions",
            values = mapOf("orderNumber" to "<a href=evil>v</a>", "total" to "EUR 1.00"),
            testMode = true,
            items = listOf(MailItem("\"><img src=x onerror=1>", "<i>v</i>", "1", "EUR 1.00", false)),
            instructionsHtml = "<p>Pay to <b>TR00 0000</b></p>",
            instructionFields = listOf(MailRow("IBAN", "<script>x</script>"))
        )
        val html = MailRendering.render(message, "en-US").html

        assertFalse(html.contains("<script>"))
        assertFalse(html.contains("<img src=x"))
        assertFalse(html.contains("<a href=evil>"))
        assertTrue(html.contains("&quot;&gt;&lt;img src&#x3D;x onerror&#x3D;1&gt;"))
        assertTrue(html.contains("&lt;i&gt;v&lt;/i&gt;"))
        assertTrue(html.contains("&lt;script&gt;x&lt;/script&gt;"))
        assertTrue(html.contains("<p>Pay to <b>TR00 0000</b></p>"))
        assertTrue(html.contains("TEST ORDER"))
    }

    @Test
    fun `instruction fields render without a body and the body without fields`() {
        fun bank(html: String?, fields: List<MailRow>) =
            MailRendering.render(MarketMailMessage("bank-transfer-instructions", mapOf("total" to "EUR 1.00"), instructionsHtml = html, instructionFields = fields), "en-US").html

        val fieldsOnly = bank(null, listOf(MailRow("IBAN", "TR33 0006"), MailRow("Reference", "A1B2")))

        assertTrue(fieldsOnly.contains("TR33 0006") && fieldsOnly.contains("Reference"))
        assertFalse(fieldsOnly.contains("margin-top:16px;margin-bottom:16px\">"), "no instructions, no text block")

        val bodyOnly = bank("Pay", emptyList())

        assertTrue(bodyOnly.contains("""<div style="font-size:15px;line-height:22px;color:rgb(54,65,83);margin-top:16px;margin-bottom:16px">Pay</div>"""))
        assertFalse(bodyOnly.contains("rgb(240,253,244)"), "no fields, no fields table")
    }
}
