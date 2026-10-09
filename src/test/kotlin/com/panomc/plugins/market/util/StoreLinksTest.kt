package com.panomc.plugins.market.util

import com.panomc.platform.frontend.FrontendTarget
import com.panomc.platform.frontend.FrontendTargetsRefusal
import com.panomc.platform.frontend.FrontendUrlMap
import com.panomc.platform.frontend.UrlSource
import com.panomc.platform.ui.FrontendMode
import com.panomc.plugins.market.support.SiteLinksFixture
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.slf4j.LoggerFactory

/** The market links of doc 05 section 10.2 over the real front-end URL map (steps 1 to 4) and the `frontend-targets.json` of the plugin. */
class StoreLinksTest {
    private val site = SiteLinksFixture()

    // ---- the file the platform reads

    @Test
    fun `the targets file declares the five targets of the market and only order has a fallback page`() {
        val file = JsonObject(MarketTargets.fileText())

        assertEquals(setOf("store", "product", "checkout", "order", "subscriptions"), file.fieldNames())
        assertEquals("/store", file.getString("store"))
        assertEquals("/store/{slug}", file.getString("product"))
        assertEquals("/store/checkout", file.getString("checkout"))
        assertEquals("/profile/subscriptions", file.getString("subscriptions"))
        assertEquals("/store/order/{id}", file.getJsonObject("order").getString("path"))
        assertTrue(file.getJsonObject("order").getBoolean("fallback"))
    }

    @Test
    fun `the platform registers the file under the namespace market`() {
        val targets = site.map.targets().filter { it.owner == MarketTargets.PLUGIN_ID }.associateBy { it.id }

        assertEquals(
            setOf(MarketTargets.STORE, MarketTargets.PRODUCT, MarketTargets.CHECKOUT, MarketTargets.ORDER, MarketTargets.SUBSCRIPTIONS), targets.keys
        )
        assertTrue(targets.getValue(MarketTargets.ORDER).fallback)
        assertFalse(targets.getValue(MarketTargets.STORE).fallback)
        assertTrue(site.map.target(MarketTargets.ORDER) is FrontendTarget)
    }

    @Test
    fun `an order target with fallback true is refused while no page is registered for it`() {
        val map = FrontendUrlMap(SiteLinksFixture.Inputs("https://shop.example", "https://shop.example"), LoggerFactory.getLogger("test"))

        map.fallbackPageCheck = { false }

        assertThrows<FrontendTargetsRefusal> { map.registerPlugin(MarketTargets.PLUGIN_ID, MarketTargets.NAMESPACE, MarketTargets.fileText()) }

        map.fallbackPageCheck = { it == MarketTargets.ORDER }
        map.registerPlugin(MarketTargets.PLUGIN_ID, MarketTargets.NAMESPACE, MarketTargets.fileText())

        assertEquals("https://shop.example/store/order/AB", map.url(MarketTargets.ORDER, mapOf("id" to "AB")))
    }

    // ---- a site that changed nothing

    @Test
    fun `a default site links the paths the store always had`() {
        val links = site.links

        assertEquals("https://shop.example/store", links.store())
        assertEquals("https://shop.example/store/gold-rank", links.product("gold-rank"))
        assertEquals("https://shop.example/store/checkout", links.checkout())
        assertEquals("https://shop.example/store/order/ABC", links.order("ABC"))
        assertEquals("https://shop.example/store/order/ABC?token=tok&return=success", links.order("ABC", linkedMapOf("token" to "tok", "return" to "success")))
        assertEquals("https://shop.example/profile/subscriptions", links.subscriptions())
        assertEquals("https://shop.example/profile", links.profile())
        assertEquals("https://shop.example/register", links.register())
        assertEquals("https://shop.example/store/{slug}", links.productTemplate())
    }

    @Test
    fun `ofBase gives the same links without a platform and nothing for an empty base`() {
        val links = StoreLinks.ofBase(" https://shop.example/// ")

        assertEquals("https://shop.example/store/order/ABC", links.order("ABC"))
        assertEquals("https://shop.example/store/{slug}", links.productTemplate())
        assertEquals("https://shop.example/register", links.register())

        for (empty in listOf("", "   ")) {
            val none = StoreLinks.ofBase(empty)

            assertNull(none.store())
            assertNull(none.order("ABC"))
            assertNull(none.productTemplate())
        }
    }

    @Test
    fun `a slug and an id are encoded, never trusted as path`() {
        assertEquals("https://shop.example/store/a%2Fb%20c", site.links.product("a/b c"))
        assertEquals("https://shop.example/store/order/a%3Fb", site.links.order("a?b"))
    }

    // ---- the four steps

    @Test
    fun `a theme that renamed the order route moves the order link and nothing else`() {
        site.renameOrder()

        assertEquals("https://shop.example/shop/purchase/ABC", site.links.order("ABC"))
        assertEquals("https://shop.example/shop/purchase/ABC?token=tok", site.links.order("ABC", mapOf("token" to "tok")))
        assertEquals("https://shop.example/store", site.links.store())
        assertEquals(UrlSource.THEME, site.map.targets().first { it.id == MarketTargets.ORDER }.source)
    }

    @Test
    fun `an admin override wins and may point to another address`() {
        site.inputs.overrides = mapOf(MarketTargets.ORDER to "/orders/{id}", MarketTargets.STORE to "https://store.example.com/")

        assertEquals("https://shop.example/orders/ABC", site.links.order("ABC"))
        assertEquals("https://store.example.com/", site.links.store())
    }

    @Test
    fun `a front-end that serves the site from another address is followed by every link`() {
        val split = SiteLinksFixture(site = "https://play.example.com", website = "https://api.example.com")
        split.inputs.frontend = mapOf(MarketTargets.PRODUCT to "/p/{slug}")

        assertEquals("https://play.example.com/store", split.links.store())
        assertEquals("https://play.example.com/p/gold", split.links.product("gold"))
        assertEquals("https://play.example.com/p/{slug}", split.links.productTemplate())
        assertEquals("https://play.example.com/register", split.links.register())
    }

    @Test
    fun `a disabled page has no link, the order falls back to the page of Pano`() {
        val split = SiteLinksFixture(site = "https://play.example.com", website = "https://api.example.com")
        split.disable("/store/order/[id]", "/store", "/register", "/profile")

        assertNull(split.links.store())
        assertNull(split.links.register())
        assertNull(split.links.profile())
        assertEquals("https://api.example.com/_pano/market.order?id=ABC&return=cancel", split.links.order("ABC", mapOf("return" to "cancel")))
        assertEquals("https://play.example.com/store/checkout", split.links.checkout())

        // what a gateway is given when a page is gone: the next best page, else the site
        assertEquals("https://play.example.com/store/checkout", split.links.checkoutPage("https://api.example.com"))
        assertEquals("https://api.example.com/_pano/market.order?id=ABC", split.links.orderPage("ABC", "https://api.example.com"))
        assertEquals("https://api.example.com", StoreLinks.NONE.orderPage("ABC", "https://api.example.com"))
        assertEquals("https://api.example.com", StoreLinks.NONE.checkoutPage("https://api.example.com"))
    }

    @Test
    fun `a front-end that says false for a page has no such page`() {
        site.inputs.frontend = mapOf(MarketTargets.SUBSCRIPTIONS to false, MarketTargets.STORE to false)

        assertNull(site.links.subscriptions())
        assertNull(site.links.store())
        assertEquals("https://shop.example/store/checkout", site.links.checkout())
    }

    @Test
    fun `without a theme a plain front-end has only the fallback page of the order`() {
        site.inputs.mode = FrontendMode.NONE

        assertNull(site.links.store())
        assertNull(site.links.product("gold"))
        assertNull(site.links.productTemplate())
        assertEquals("https://shop.example/_pano/market.order?id=ABC", site.links.order("ABC"))
    }

    @Test
    fun `a link that is not an absolute address is no link, and a failing map never breaks the caller`() {
        val relative = UrlMapLinks({ _, _ -> "/store" }, { "/store/{slug}" })

        assertNull(relative.store())
        assertNull(relative.productTemplate())

        val failing = UrlMapLinks({ _, _ -> throw IllegalArgumentException("boom") }, { throw IllegalStateException("boom") })

        assertNull(failing.order("ABC"))
        assertNull(failing.productTemplate())
    }
}
