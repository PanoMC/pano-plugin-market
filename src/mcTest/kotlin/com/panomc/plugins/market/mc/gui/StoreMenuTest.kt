package com.panomc.plugins.market.mc.gui

import com.panomc.plugins.market.mc.core.wire.MarketPurchaseMessage
import com.panomc.plugins.market.mc.core.wire.MarketPurchaseRequest
import com.panomc.plugins.market.mc.core.wire.MarketQueryData
import com.panomc.plugins.market.mc.core.wire.MarketQueryMessage
import com.panomc.plugins.market.mc.core.wire.MarketQueryRequest
import com.panomc.plugins.market.mc.core.wire.MarketMcSettings
import com.panomc.plugins.market.mc.core.wire.QueryCategory
import com.panomc.plugins.market.mc.core.wire.QueryProduct
import com.panomc.plugins.market.mc.core.wire.QueryPurchasable
import com.panomc.plugins.market.mc.core.wire.QueryType
import com.panomc.plugins.market.mc.feature.FeatureRig
import com.panomc.plugins.market.mc.feature.ShippedResources
import com.panomc.plugins.market.mc.feature.panoConfig
import com.panomc.plugins.market.mc.core.wire.MarketRequest
import com.panomc.plugins.pano.core.platform.PlatformMessageResponse
import com.panomc.plugins.market.mc.spigot.gui.MenuAction
import com.panomc.plugins.market.mc.spigot.gui.MenuPlayer
import com.panomc.plugins.market.mc.spigot.gui.MenuScreen
import com.panomc.plugins.market.mc.spigot.gui.MenuView
import com.panomc.plugins.market.mc.spigot.gui.StoreMenu
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** The recording [MenuView]: what was drawn, what was said, what was closed. */
class RecordingView : MenuView {
    val screens = CopyOnWriteArrayList<MenuScreen>()
    val messages = CopyOnWriteArrayList<Pair<String, String?>>()
    val closes = AtomicInteger()

    override fun show(username: String, screen: MenuScreen) {
        // Layout invariants of every screen: slots inside the inventory and unique.
        val slots = screen.items.map { it.slot }
        assertTrue(slots.all { it in 0 until screen.size }, "slot outside the inventory: $slots (size ${screen.size})")
        assertEquals(slots.size, slots.toSet().size, "two items share a slot: $slots")
        screens.add(screen)
    }

    override fun close(username: String) {
        closes.incrementAndGet()
    }

    override fun message(username: String, text: String, openUrl: String?) {
        messages.add(text.replace(Regex("§."), "") to openUrl)
    }

    val last: MenuScreen get() = screens.last()
    fun said(fragment: String) = messages.any { it.first.contains(fragment) }
    fun slotOf(title: String) = last.items.first { it.title.replace(Regex("§."), "").contains(title) }.slot
    fun hasAction(action: MenuAction) = last.items.any { it.action == action }
    fun slotOfAction(action: MenuAction) = last.items.first { it.action == action }.slot
}

class StoreMenuTest {
    @TempDir
    lateinit var dir: Path

    private lateinit var rig: FeatureRig
    private lateinit var view: RecordingView
    private lateinit var menu: StoreMenu
    private val steve = MenuPlayer("Steve", "11111111-1111-1111-1111-111111111111", null)
    private val ids = AtomicInteger()

    private val diamonds = QueryProduct(10, "Diamonds", "Shiny", 50.0, 4.99, "USD", 7, "diamond", QueryPurchasable(true), false, slug = "diamonds")
    private val cape = QueryProduct(11, "Cape", null, null, 9.0, "USD", null, null, QueryPurchasable(false, "COOLDOWN_ACTIVE"), true, slug = "cape")
    private val limited = QueryProduct(12, "Sword", null, 20.0, null, null, 1, "DIAMOND_SWORD", QueryPurchasable(false, "PURCHASE_LIMIT_REACHED"), false)
    private val categories = listOf(QueryCategory(1, "Ranks", "crown"), QueryCategory(2, "Items"))

    private fun catalog(products: List<QueryProduct>, page: Int = 1, total: Int = 1, withCategories: Boolean = true) =
        MarketQueryMessage(true, null, MarketQueryData(categories = if (withCategories) categories else emptyList(), products = products, page = page, totalPage = total))

    private fun balance(value: Double?, registered: Boolean = true) =
        MarketQueryMessage(true, null, MarketQueryData(registered = registered, balance = value, creditName = "Credits"))

    private fun ok(total: Double = 50.0, balance: Double = 150.0) = MarketPurchaseMessage(true, null, true, "ORD-1", total, balance)
    private fun fail(code: String, extras: Map<String, Any?>? = null) = MarketPurchaseMessage(true, null, false, code = code, extras = extras)

    @BeforeEach
    fun setUp() {
        build()
    }

    private fun build(menuRows: Int = 6, settings: MarketMcSettings = MarketMcSettings()) {
        val text = ShippedResources.reader("mc/config.yml")!!.replace("menu-rows: 6", "menu-rows: $menuRows")
        rig = FeatureRig(dir.resolve("r${ids.incrementAndGet()}"), text)
        rig.loadConfig(panoConfig(settings = settings))
        view = RecordingView()
        menu = StoreMenu(rig.features.config, rig.features.messages, rig.link, view, { rig.features.runtime() }, { rig.uuids[it.lowercase()] }, "1.4.0", rig.clock, { "op-${ids.incrementAndGet()}" })
        rig.link.handler = { r ->
            when {
                r is MarketQueryRequest && r.type == QueryType.CATALOG -> catalog(listOf(diamonds, cape, limited))
                r is MarketQueryRequest && r.type == QueryType.BALANCE -> balance(150.0)
                r is MarketPurchaseRequest -> ok()
                else -> null
            }
        }
    }

    private fun opens() {
        rig.clock.now += 5_000
        menu.open(steve)
    }

    private fun purchases() = rig.link.of(MarketPurchaseRequest::class.java)
    private fun queries(type: String) = rig.link.of(MarketQueryRequest::class.java).filter { it.type == type }

    /** Open, pick "All products", the diamonds, and stand at the confirm screen. */
    private fun toConfirm() {
        opens()
        menu.click("Steve", view.slotOf("All products"))
        menu.click("Steve", view.slotOf("Diamonds"))
    }

    @Test
    fun `flow categories to products to confirm to purchase with credits`() {
        opens()
        assertEquals(1, queries(QueryType.CATALOG).size)
        assertEquals(1, queries(QueryType.CATALOG)[0].page)
        assertNull(queries(QueryType.CATALOG)[0].args, "the first catalog request carries no category")
        assertTrue(view.hasAction(MenuAction.OpenCategory(null)), "categories screen with the all entry")
        assertTrue(view.hasAction(MenuAction.OpenCategory(1)) && view.hasAction(MenuAction.OpenCategory(2)))

        menu.click("Steve", view.slotOfAction(MenuAction.OpenCategory(2)))
        assertEquals(2L, queries(QueryType.CATALOG).last().args?.categoryId, "the category is asked from Pano")
        assertTrue(view.hasAction(MenuAction.Product(10)))
        assertFalse(view.last.items.any { it.action == MenuAction.Product(11) }, "a web product has no buy button")
        assertFalse(view.last.items.any { it.action == MenuAction.Product(12) }, "an unpurchasable product has no buy button")

        menu.click("Steve", view.slotOfAction(MenuAction.Product(10)))
        assertEquals(1, queries(QueryType.BALANCE).size)
        val confirmLore = view.last.items.first { it.slot == 4 }.lore.joinToString("|").replace(Regex("§."), "")
        assertTrue(confirmLore.contains("50 Credits") && confirmLore.contains("150 Credits") && confirmLore.contains("100 Credits"), confirmLore)
        assertEquals(0, purchases().size, "nothing is bought before the confirm click")

        menu.click("Steve", view.slotOfAction(MenuAction.Confirm))
        assertEquals(1, purchases().size)
        val p = purchases()[0]
        assertEquals(10L, p.productId)
        assertEquals(1, p.quantity)
        assertEquals("Steve", p.player.username)
        assertTrue(p.operationId.startsWith("op-"))
        assertNull(p.confirmLegalTextId)
        assertTrue(view.said("You bought Diamonds for 50 Credits"), view.messages.toString())
        assertTrue(view.said("Your balance: 150 Credits"))
        assertEquals(1, rig.control.syncs, "the component syncs right after an ok purchase")
        assertTrue(menu.hasFlow("Steve"), "the menu goes back to the products")
        assertEquals(3, queries(QueryType.CATALOG).size, "fresh stock after the purchase")
    }

    @Test
    fun `one open flow per player and a late answer of the replaced flow is dropped`() {
        rig.link.defer = true
        opens()
        opens()
        assertEquals(2, queries(QueryType.CATALOG).size)
        val before = view.screens.size
        rig.link.defer = false
        rig.link.releaseDeferred()
        // Both answers arrive: only the newer flow may draw its categories screen.
        assertEquals(before + 1, view.screens.size, "exactly one of the two answers is drawn")
        assertTrue(menu.hasFlow("Steve"))
        assertTrue(view.hasAction(MenuAction.OpenCategory(null)))
    }

    @Test
    fun `opening is throttled and refused while the store is not connected or the menu is switched off`() {
        menu.open(steve)
        menu.open(steve)
        assertTrue(view.said("wait a moment"))
        assertEquals(1, queries(QueryType.CATALOG).size)

        rig.link.up = false
        opens()
        assertTrue(view.said("not connected"))

        build(settings = MarketMcSettings(mcStoreMenu = false))
        opens()
        assertTrue(view.said("switched off"))
        assertEquals(0, rig.link.requests.size)
        assertFalse(menu.hasFlow("Steve"))
    }

    @Test
    fun `double click on confirm sends one purchase`() {
        toConfirm()
        rig.link.defer = true
        val confirmSlot = view.slotOfAction(MenuAction.Confirm)
        menu.click("Steve", confirmSlot)
        menu.click("Steve", confirmSlot)
        menu.click("Steve", confirmSlot)
        assertEquals(1, purchases().size, "the processing screen has no confirm button")
        assertFalse(view.hasAction(MenuAction.Confirm), "the confirm button is gone while the answer is awaited")
        rig.link.defer = false
        rig.link.releaseDeferred()
        assertEquals(1, purchases().size)
    }

    @Test
    fun `a purchase of a closed menu still blocks a second purchase until it is answered`() {
        toConfirm()
        rig.link.defer = true
        menu.click("Steve", view.slotOfAction(MenuAction.Confirm))
        menu.closed("Steve")
        assertFalse(menu.hasFlow("Steve"))
        // Reopen at once and try the same product again while the first answer is outstanding.
        rig.link.defer = false
        opens()
        menu.click("Steve", view.slotOf("All products"))
        menu.click("Steve", view.slotOf("Diamonds"))
        menu.click("Steve", view.slotOfAction(MenuAction.Confirm))
        assertEquals(1, purchases().size, "no second order while the first is in flight")
        assertTrue(view.said("already being processed"))
        // The first answer arrives after the menu was closed: the player is told, nothing is drawn for the dead flow.
        rig.link.releaseDeferred()
        assertTrue(view.said("You bought Diamonds"))
        assertEquals(1, rig.control.syncs)
        // After the answer a new purchase is possible again (a new operation id).
        menu.click("Steve", view.slotOfAction(MenuAction.Confirm))
        assertEquals(2, purchases().size)
        assertNotEquals(purchases()[0].operationId, purchases()[1].operationId)
    }

    @Test
    fun `closing the inventory cancels the flow at every step`() {
        // while the catalog is awaited
        rig.link.defer = true
        opens()
        val drawn = view.screens.size
        menu.closed("Steve")
        rig.link.defer = false
        rig.link.releaseDeferred()
        assertEquals(drawn, view.screens.size, "a late catalog answer draws nothing")
        assertFalse(menu.hasFlow("Steve"))

        // at the confirm screen: later clicks do nothing
        toConfirm()
        val confirmSlot = view.slotOfAction(MenuAction.Confirm)
        menu.closed("Steve")
        menu.click("Steve", confirmSlot)
        assertEquals(0, purchases().size, "no purchase after closing")
        assertEquals(0, view.closes.get(), "a player-closed inventory is not closed again from our side")
    }

    @Test
    fun `the close button ends the flow and closes the view`() {
        opens()
        menu.click("Steve", view.slotOfAction(MenuAction.Close))
        assertFalse(menu.hasFlow("Steve"))
        assertEquals(1, view.closes.get())
        menu.click("Steve", 0)
        assertEquals(1, queries(QueryType.CATALOG).size)
    }

    @Test
    fun `an unknown outcome keeps the operation id so a retry cannot create a second order`() {
        toConfirm()
        val base: (MarketRequest) -> PlatformMessageResponse? = { r ->
            when {
                r is MarketQueryRequest && r.type == QueryType.CATALOG -> catalog(listOf(diamonds))
                r is MarketQueryRequest && r.type == QueryType.BALANCE -> balance(150.0)
                else -> null
            }
        }
        rig.link.handler = { r -> if (r is MarketPurchaseRequest) null else base(r) }
        menu.click("Steve", view.slotOfAction(MenuAction.Confirm))
        assertTrue(view.said("may or may not have gone through"))
        val first = purchases()[0].operationId

        // Back at the products: the same product again, same operation id; the closed-and-reopened menu too.
        menu.closed("Steve")
        opens()
        menu.click("Steve", view.slotOf("All products"))
        menu.click("Steve", view.slotOf("Diamonds"))
        rig.link.handler = { r -> if (r is MarketPurchaseRequest) ok() else base(r) }
        menu.click("Steve", view.slotOfAction(MenuAction.Confirm))
        assertEquals(2, purchases().size)
        assertEquals(first, purchases()[1].operationId, "the retry replays the same operation")

        // The answer was definite: the next purchase of it is a new operation.
        menu.click("Steve", view.slotOf("Diamonds"))
        menu.click("Steve", view.slotOfAction(MenuAction.Confirm))
        assertEquals(3, purchases().size)
        assertNotEquals(first, purchases()[2].operationId)
    }

    @Test
    fun `an unknown outcome is forgotten after ten minutes`() {
        toConfirm()
        val inner = rig.link.handler
        rig.link.handler = { r -> if (r is MarketPurchaseRequest) null else inner(r) }
        menu.click("Steve", view.slotOfAction(MenuAction.Confirm))
        val first = purchases()[0].operationId
        rig.clock.now += 11 * 60 * 1000
        rig.link.handler = inner
        menu.closed("Steve")
        opens()
        menu.click("Steve", view.slotOf("All products"))
        menu.click("Steve", view.slotOf("Diamonds"))
        menu.click("Steve", view.slotOfAction(MenuAction.Confirm))
        assertNotEquals(first, purchases()[1].operationId)
    }

    @Test
    fun `web only items show the link and never buy`() {
        opens()
        menu.click("Steve", view.slotOf("All products"))
        val capeItem = view.last.items.first { it.title.contains("Cape") }
        assertEquals(MenuAction.WebLink("https://shop.example.com/store/cape", "Cape"), capeItem.action)
        assertTrue(capeItem.lore.joinToString().contains("website"))
        menu.click("Steve", capeItem.slot)
        val sent = view.messages.last()
        assertTrue(sent.first.contains("Cape is sold on the website"), sent.first)
        assertEquals("https://shop.example.com/store/cape", sent.second)
        assertEquals(0, purchases().size)
        assertEquals(0, queries(QueryType.BALANCE).size)
    }

    @Test
    fun `a product without a credit price needs the web even when the flag is missing`() {
        val noPrice = QueryProduct(20, "Rank", null, null, 5.0, "USD", null, null, QueryPurchasable(true), false)
        val handler = rig.link.handler
        rig.link.handler = { r -> if (r is MarketQueryRequest && r.type == QueryType.CATALOG) catalog(listOf(noPrice)) else handler(r) }
        opens()
        menu.click("Steve", view.slotOf("All products"))
        val item = view.last.items.first { it.title.contains("Rank") }
        assertEquals(MenuAction.WebLink("https://shop.example.com/store", "Rank"), item.action)
    }

    @Test
    fun `an unpurchasable product shows its reason and does nothing on click`() {
        opens()
        menu.click("Steve", view.slotOf("All products"))
        val sword = view.last.items.first { it.title.contains("Sword") }
        assertNull(sword.action)
        assertTrue(sword.lore.joinToString().replace(Regex("§."), "").contains("purchase limit"), sword.lore.toString())
        val before = rig.link.requests.size
        menu.click("Steve", sword.slot)
        assertEquals(before, rig.link.requests.size)
    }

    @Test
    fun `not enough credits disables the confirm button`() {
        val handler = rig.link.handler
        rig.link.handler = { r -> if (r is MarketQueryRequest && r.type == QueryType.BALANCE) balance(10.0) else handler(r) }
        toConfirm()
        assertFalse(view.hasAction(MenuAction.Confirm))
        assertTrue(view.last.items.first { it.slot == 4 }.lore.joinToString().contains("not have enough"))
        assertTrue(view.hasAction(MenuAction.Cancel))
        menu.click("Steve", 1)
        assertEquals(0, purchases().size)
    }

    @Test
    fun `a player without a website account gets the register link`() {
        val handler = rig.link.handler
        rig.link.handler = { r -> if (r is MarketQueryRequest && r.type == QueryType.BALANCE) balance(null, registered = false) else handler(r) }
        toConfirm()
        assertEquals("https://shop.example.com/register", view.messages.last().second)
        assertFalse(menu.hasFlow("Steve"))
        assertEquals(1, view.closes.get())
    }

    @Test
    fun `refusal codes are shown to the player and the menu returns to the products`() {
        for ((code, text) in listOf(
            "OUT_OF_STOCK" to "out of stock", "PURCHASE_LIMIT_REACHED" to "purchase limit", "COOLDOWN_ACTIVE" to "wait before",
            "REQUIREMENT_NOT_MET" to "requirements", "NOT_PAYABLE_WITH_CREDITS" to "cannot be paid with credits",
            "INSUFFICIENT_CREDITS" to "not have enough credits", "PRODUCT_UNAVAILABLE" to "not available", "BUYER_BLOCKED" to "cannot buy",
            "CREDITS_DISABLED" to "switched off", "SOMETHING_NEW" to "SOMETHING_NEW"
        )) {
            build()
            val handler = rig.link.handler
            rig.link.handler = { r -> if (r is MarketPurchaseRequest) fail(code) else handler(r) }
            toConfirm()
            menu.click("Steve", view.slotOfAction(MenuAction.Confirm))
            assertTrue(view.said(text), "$code -> ${view.messages}")
            assertEquals(0, rig.control.syncs, "a refused purchase does not trigger a sync ($code)")
            assertTrue(menu.hasFlow("Steve"))
            assertTrue(view.hasAction(MenuAction.Product(10)), "back at the products ($code)")
        }
    }

    @Test
    fun `a refused request and a missing answer are told and end the catalog flow`() {
        val handler = rig.link.handler
        rig.link.handler = { r -> if (r is MarketQueryRequest && r.type == QueryType.CATALOG) MarketQueryMessage(false, "RATE_LIMITED") else handler(r) }
        opens()
        assertTrue(view.said("busy"))
        assertFalse(menu.hasFlow("Steve"))
        rig.link.handler = { null }
        opens()
        assertTrue(view.said("did not answer"))
        assertFalse(menu.hasFlow("Steve"))
    }

    @Test
    fun `a required legal text is accepted by confirming again and then travels with the purchase`() {
        val handler = rig.link.handler
        var legalSeen: Long? = null
        rig.link.handler = { r ->
            if (r is MarketPurchaseRequest) {
                if (r.confirmLegalTextId == null) fail("LEGAL_ACCEPTANCE_REQUIRED", mapOf("legalTextId" to 7.0))
                else {
                    legalSeen = r.confirmLegalTextId
                    ok()
                }
            } else handler(r)
        }
        toConfirm()
        menu.click("Steve", view.slotOfAction(MenuAction.Confirm))
        assertTrue(view.said("store terms must be accepted"))
        assertTrue(view.last.items.first { it.slot == 4 }.lore.joinToString().contains("accept the store terms"))
        assertTrue(view.hasAction(MenuAction.Confirm), "the confirm screen is back")
        menu.click("Steve", view.slotOfAction(MenuAction.Confirm))
        assertEquals(7L, legalSeen)
        assertEquals(2, purchases().size)
        assertNotEquals(purchases()[0].operationId, purchases()[1].operationId, "a refused attempt is not replayed")
        assertTrue(view.said("You bought"))
    }

    @Test
    fun `LOGIN_REQUIRED from Pano gives the register link and ends the flow`() {
        val handler = rig.link.handler
        rig.link.handler = { r -> if (r is MarketPurchaseRequest) fail("LOGIN_REQUIRED") else handler(r) }
        toConfirm()
        menu.click("Steve", view.slotOfAction(MenuAction.Confirm))
        assertEquals("https://shop.example.com/register", view.messages.last().second)
        assertFalse(menu.hasFlow("Steve"))
    }

    @Test
    fun `cancel returns to the products without buying`() {
        toConfirm()
        menu.click("Steve", view.slotOfAction(MenuAction.Cancel))
        assertTrue(view.hasAction(MenuAction.Product(10)))
        assertEquals(0, purchases().size)
    }

    @Test
    fun `without categories the menu opens on the products and back is absent`() {
        val handler = rig.link.handler
        rig.link.handler = { r -> if (r is MarketQueryRequest && r.type == QueryType.CATALOG) catalog(listOf(diamonds), withCategories = false) else handler(r) }
        opens()
        assertTrue(view.hasAction(MenuAction.Product(10)))
        assertFalse(view.hasAction(MenuAction.Back))
    }

    @Test
    fun `back from the products goes to the categories`() {
        opens()
        menu.click("Steve", view.slotOf("All products"))
        menu.click("Steve", view.slotOfAction(MenuAction.Back))
        assertTrue(view.hasAction(MenuAction.OpenCategory(1)))
    }

    @Test
    fun `server pages and local sub pages with a small chest`() {
        build(menuRows = 2) // 9 product slots per screen, the second row is navigation
        val many = (1L..12L).map { QueryProduct(it, "P$it", null, 1.0, null, null, null, null, QueryPurchasable(true), false) }
        val rest = (13L..14L).map { QueryProduct(it, "P$it", null, 1.0, null, null, null, null, QueryPurchasable(true), false) }
        rig.link.handler = { r ->
            when {
                r is MarketQueryRequest && r.type == QueryType.CATALOG -> if (r.page == 2) catalog(rest, 2, 2, false) else catalog(many, 1, 2, false)
                else -> null
            }
        }
        opens()
        assertEquals(9, view.last.items.count { it.action is MenuAction.Product })
        assertFalse(view.hasAction(MenuAction.PreviousPage))
        menu.click("Steve", view.slotOfAction(MenuAction.NextPage))
        assertEquals(3, view.last.items.count { it.action is MenuAction.Product }, "the rest of server page 1")
        assertEquals(1, queries(QueryType.CATALOG).size, "a local page needs no request")
        menu.click("Steve", view.slotOfAction(MenuAction.NextPage))
        assertEquals(2, queries(QueryType.CATALOG).last().page)
        assertEquals(2, view.last.items.count { it.action is MenuAction.Product })
        assertFalse(view.hasAction(MenuAction.NextPage), "last page")
        menu.click("Steve", view.slotOfAction(MenuAction.PreviousPage))
        assertEquals(1, queries(QueryType.CATALOG).last().page)
        assertEquals(3, view.last.items.count { it.action is MenuAction.Product }, "back on the last local page of server page 1")
        menu.click("Steve", view.slotOfAction(MenuAction.PreviousPage))
        assertEquals(9, view.last.items.count { it.action is MenuAction.Product })
    }

    @Test
    fun `clicks on empty slots and a click while loading do nothing`() {
        rig.link.defer = true
        opens()
        val requests = rig.link.requests.size
        menu.click("Steve", 3)
        menu.click("Steve", 53)
        assertEquals(requests, rig.link.requests.size)
        rig.link.defer = false
        rig.link.releaseDeferred()
        menu.click("Steve", 40)
        assertEquals(requests, rig.link.requests.size)
        menu.click("Nobody", 0)
    }

    @Test
    fun `texts of the menu follow the player locale and unsafe names cannot inject formatting`() {
        val evil = QueryProduct(30, "§4Evil&kName", "line\nbreak", 5.0, null, null, null, "not a material!", QueryPurchasable(true), false)
        val handler = rig.link.handler
        rig.link.handler = { r -> if (r is MarketQueryRequest && r.type == QueryType.CATALOG) catalog(listOf(evil), withCategories = false) else handler(r) }
        menu.open(MenuPlayer("Ayse", null, "tr_TR"))
        val item = view.last.items.first { it.action is MenuAction.Product }
        assertFalse(item.title.drop(2).contains('§'), "no colour symbol of the product name survives: ${item.title}")
        assertEquals("PAPER", item.material, "an invalid icon falls back to a plain item")
        assertTrue(item.lore.joinToString().contains("Fiyat"), "Turkish texts for a tr client: ${item.lore}")
    }
}
