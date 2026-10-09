package com.panomc.plugins.market.frontend

import com.github.jknack.handlebars.Handlebars
import com.panomc.platform.frontend.FallbackPage
import com.panomc.platform.frontend.FallbackPageRegistry
import com.panomc.platform.frontend.FrontendTargetsRefusal
import com.panomc.platform.frontend.FrontendUrlMap
import com.panomc.plugins.market.support.SiteLinksFixture
import com.panomc.plugins.market.util.MarketTargets
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.slf4j.LoggerFactory

/** The page `market.order` (doc 05 section 10.3): registered under the target of `frontend-targets.json`, rendered from its template and texts. */
class OrderFallbackPageTest {
    private val handlebars = Handlebars()

    private fun render(model: Map<String, Any?>): String {
        val source = OrderFallbackPageTest::class.java.classLoader.getResourceAsStream(OrderFallbackPage.TEMPLATE)!!.use { it.readBytes().toString(Charsets.UTF_8) }
        val data = LinkedHashMap(model)

        // the helper of the core shell, which only records the title
        handlebars.registerHelper("pageTitle", com.github.jknack.handlebars.Helper<Any?> { _, _ -> "" })

        return handlebars.compileInline(source).apply(data)
    }

    // ---- the contract with the target file and the registry

    private class StubPage(target: String) : FallbackPage(target, OrderFallbackPage.TEMPLATE)

    @Test
    fun `the page target and the target of the file make market order, and the template is in the jar`() {
        assertEquals(MarketTargets.ORDER, "${MarketTargets.NAMESPACE}.${OrderFallbackPage.TARGET}")
        assertNotNull(OrderFallbackPageTest::class.java.classLoader.getResourceAsStream(OrderFallbackPage.TEMPLATE), "the template resource")
    }

    @Test
    fun `a plugin load with the targets file needs the page, and the registry prefixes the namespace`() {
        val logger = LoggerFactory.getLogger("test")
        val map = FrontendUrlMap(SiteLinksFixture.Inputs("https://shop.example", "https://shop.example"), logger)
        val registry = FallbackPageRegistry(map, logger)

        // no page registered: the plugin load fails, naming the target
        val refusal = assertThrows<FrontendTargetsRefusal> { map.registerPlugin(MarketTargets.PLUGIN_ID, MarketTargets.NAMESPACE, MarketTargets.fileText()) }

        assertTrue(refusal.message!!.contains("market.order"), refusal.message)

        registry.registerPlugin(MarketTargets.PLUGIN_ID, MarketTargets.NAMESPACE, listOf(StubPage(OrderFallbackPage.TARGET)))
        map.registerPlugin(MarketTargets.PLUGIN_ID, MarketTargets.NAMESPACE, MarketTargets.fileText())

        assertTrue(registry.has(MarketTargets.ORDER))
        assertEquals(listOf(MarketTargets.ORDER), registry.ids())

        // unloading drops the page again
        registry.unregisterPlugin(MarketTargets.PLUGIN_ID)
        assertFalse(registry.has(MarketTargets.ORDER))
    }

    // ---- texts and the model

    @Test
    fun `every language has the same keys as English, and a regional tag falls back to its language`() {
        val english = OrderPageModel.texts("en-US")

        for (locale in listOf("tr", "ru")) {
            val own = OrderPageModel.texts(locale)

            assertEquals(flatKeys(english), flatKeys(own), locale)
            assertFalse(own["heading"] == null)
        }

        assertEquals(OrderPageModel.texts("tr")["back"], OrderPageModel.texts("tr-TR")["back"])
        assertEquals(english["back"], OrderPageModel.texts("xx")["back"], "a language without a file reads English")
        assertEquals(english["back"], OrderPageModel.texts("../etc")["back"], "a locale that is no language tag reads English")
    }

    private fun flatKeys(map: Map<String, Any?>, prefix: String = ""): Set<String> =
        map.flatMap { (k, v) -> if (v is Map<*, *>) flatKeys(v as Map<String, Any?>, "$prefix$k.") else setOf("$prefix$k") }.toSet()

    @Test
    fun `a paid order shows its status, its total and the way back`() {
        val html = render(OrderPageModel.found(OrderPageModel.texts("en-US"), "ABCDEFGHJKMNPQRSTVWX", "COMPLETED", "\$19.90", "success", "https://shop.example/store"))

        assertTrue(html.contains("Paid"), html)
        assertTrue(html.contains("Thank you, your payment is confirmed."), html)
        assertTrue(html.contains("ABCDEFGHJKMNPQRSTVWX"), html)
        assertTrue(html.contains("\$19.90"), html)
        assertTrue(html.contains("href=\"https://shop.example/store\""), html)
        assertTrue(html.contains("Back to the store"), html)
    }

    @Test
    fun `a pending order after a cancelled payment says so, and a stranger sees no total`() {
        val cancelled = render(OrderPageModel.found(OrderPageModel.texts("en-US"), "ABC", "PENDING", null, "cancel", "/"))

        assertTrue(cancelled.contains("The payment was cancelled."), cancelled)
        assertFalse(cancelled.contains("Total"), cancelled)

        val waiting = render(OrderPageModel.found(OrderPageModel.texts("tr"), "ABC", "PENDING", null, "success", "/"))

        assertTrue(waiting.contains("Ödeme bekleniyor"), waiting)
    }

    @Test
    fun `an unknown status is shown as it is, never as markup`() {
        val html = render(OrderPageModel.found(OrderPageModel.texts("en-US"), "ABC", "<script>alert(1)</script>", null, null, "/"))

        assertFalse(html.contains("<script>"), html)
    }

    @Test
    fun `an order that cannot be shown gets one sentence, the same for a missing order and a wrong token`() {
        val html = render(OrderPageModel.notFound(OrderPageModel.texts("en-US"), "/store"))

        assertTrue(html.contains("We could not find this order."), html)
        assertFalse(html.contains("Status"), html)
        assertTrue(html.contains("href=\"/store\""), html)

        val away = render(OrderPageModel.unavailable(OrderPageModel.texts("en-US"), "/store"))

        assertTrue(away.contains("The store is not available right now."), away)
    }
}
