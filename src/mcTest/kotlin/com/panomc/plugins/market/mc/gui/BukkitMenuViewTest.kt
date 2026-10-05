package com.panomc.plugins.market.mc.gui

import com.panomc.plugins.market.mc.core.feature.FeatureHost
import com.panomc.plugins.market.mc.core.wire.MarketQueryData
import com.panomc.plugins.market.mc.core.wire.MarketQueryMessage
import com.panomc.plugins.market.mc.core.wire.MarketQueryRequest
import com.panomc.plugins.market.mc.core.wire.QueryProduct
import com.panomc.plugins.market.mc.core.wire.QueryPurchasable
import com.panomc.plugins.market.mc.core.wire.QueryType
import com.panomc.plugins.market.mc.feature.CollectingLog
import com.panomc.plugins.market.mc.feature.FeatureRig
import com.panomc.plugins.market.mc.feature.panoConfig
import com.panomc.plugins.market.mc.link.proxyOf
import com.panomc.plugins.market.mc.spigot.FakeBukkit
import com.panomc.plugins.market.mc.spigot.MarketScheduler
import com.panomc.plugins.market.mc.spigot.gui.BukkitMenuView
import com.panomc.plugins.market.mc.spigot.gui.MenuAction
import com.panomc.plugins.market.mc.spigot.gui.MenuHolder
import com.panomc.plugins.market.mc.spigot.gui.MenuItem
import com.panomc.plugins.market.mc.spigot.gui.MenuMaterials
import com.panomc.plugins.market.mc.spigot.gui.MenuPlayer
import com.panomc.plugins.market.mc.spigot.gui.MenuScreen
import com.panomc.plugins.market.mc.spigot.gui.StoreMenu
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.InventoryView
import org.bukkit.inventory.ItemFactory
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

/** The Bukkit side of the menu against the fake server: contents, events, close handling. 1.8.8 API only (it compiles against it). */
class BukkitMenuViewTest {
    @TempDir
    lateinit var dir: Path

    class FakeMeta(var name: String? = null, var lore: List<String>? = null) {
        val proxy: ItemMeta = proxyOf(ItemMeta::class.java) { m, a ->
            when (m.name) {
                "setDisplayName" -> { name = a[0] as String?; null }
                "getDisplayName" -> name
                "hasDisplayName" -> name != null
                "setLore" -> { @Suppress("UNCHECKED_CAST") run { lore = a[0] as List<String>? }; null }
                "getLore" -> lore
                "hasLore" -> lore != null
                "clone" -> FakeMeta(name, lore).proxy
                else -> throw UnsupportedOperationException("ItemMeta.${m.name}")
            }
        }
    }

    class FakeInventory(val size: Int, holder: InventoryHolder?, val title: String) {
        val items = arrayOfNulls<ItemStack>(size)
        val inventory: Inventory = proxyOf(Inventory::class.java) { m, a ->
            when (m.name) {
                "getSize" -> size
                "getHolder" -> holder
                "getTitle" -> title
                "setItem" -> { items[a[0] as Int] = a[1] as ItemStack?; null }
                "getItem" -> items[a[0] as Int]
                "clear" -> { items.fill(null); null }
                else -> throw UnsupportedOperationException("Inventory.${m.name}")
            }
        }
    }

    class FakePlayer(val name: String) {
        var top: Inventory? = null
        val opened = CopyOnWriteArrayList<Inventory>()
        var closedCalls = 0
        lateinit var view: InventoryView
        val player: Player = proxyOf(Player::class.java) { m, a ->
            when (m.name) {
                "getName" -> name
                "openInventory" -> { top = a[0] as Inventory; opened.add(top!!); view }
                "getOpenInventory" -> view
                "closeInventory" -> { closedCalls++; top = null; null }
                "getGameMode" -> org.bukkit.GameMode.SURVIVAL
                else -> throw UnsupportedOperationException("Player.${m.name}")
            }
        }

        init {
            view = object : InventoryView() {
                override fun getTopInventory(): Inventory? = top
                override fun getBottomInventory(): Inventory? = null
                override fun getPlayer(): org.bukkit.entity.HumanEntity = this@FakePlayer.player
                override fun getType(): InventoryType = InventoryType.CHEST
            }
        }
    }

    private val inventories = CopyOnWriteArrayList<FakeInventory>()
    private val chat = CopyOnWriteArrayList<Triple<String, String, String?>>()
    private val players = HashMap<String, FakePlayer>()
    private lateinit var view: BukkitMenuView
    private lateinit var scheduler: MarketScheduler

    private fun steve() = players.getOrPut("Steve") { FakePlayer("Steve") }

    @BeforeEach
    fun setUp() {
        FakeBukkit.install()
        val plugin = FakeBukkit.plugin("PanoMarket")
        scheduler = MarketScheduler(plugin, false)
        FakeBukkit.extraServer["getPlayerExact"] = { a -> players[a[0] as String]?.player }
        FakeBukkit.extraServer["createInventory"] = { a ->
            val fi = FakeInventory(a[1] as Int, a[0] as InventoryHolder?, a[2] as String)
            inventories.add(fi)
            fi.inventory
        }
        FakeBukkit.extraServer["getItemFactory"] = { _ ->
            proxyOf(ItemFactory::class.java) { m, a ->
                when (m.name) {
                    "getItemMeta" -> FakeMeta().proxy
                    "isApplicable" -> true
                    "asMetaFor" -> a[0]
                    else -> throw UnsupportedOperationException("ItemFactory.${m.name}")
                }
            }
        }
        view = BukkitMenuView(scheduler, CollectingLog(), { "Store" }, { u, t, url -> chat.add(Triple(u, t, url)) })
    }

    private fun await(what: String, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5000
        while (!check() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue(check(), what)
    }

    private fun screen(vararg items: MenuItem, rows: Int = 2) = MenuScreen(rows, items.toList())

    @Test
    fun `a screen opens an inventory with items, names, lore and fallback materials`() {
        val p = steve()
        view.show("Steve", screen(MenuItem(0, "DIAMOND", "§fShiny", listOf("§7a", "§7b")), MenuItem(1, "NOT_A_MATERIAL", "x"), MenuItem(40, "PAPER", "outside")))
        await("inventory opened") { p.opened.size == 1 }
        val inv = inventories.single()
        assertEquals(18, inv.size)
        assertEquals("Store", inv.title)
        assertTrue(inv.inventory.holder is MenuHolder)
        assertEquals(Material.DIAMOND, inv.items[0]!!.type)
        assertEquals("§fShiny", inv.items[0]!!.itemMeta.displayName)
        assertEquals(listOf("§7a", "§7b"), inv.items[0]!!.itemMeta.lore)
        assertEquals(Material.PAPER, inv.items[1]!!.type, "an unknown material is a plain item")
        assertEquals(2, inv.items.count { it != null }, "a slot outside the inventory is skipped")
    }

    @Test
    fun `a following screen rewrites the open inventory without reopening`() {
        val p = steve()
        view.show("Steve", screen(MenuItem(0, "DIAMOND", "one")))
        await("open") { p.opened.size == 1 }
        view.show("Steve", screen(MenuItem(3, "EMERALD", "two")))
        await("rewritten") { inventories.single().items[3] != null }
        val inv = inventories.single()
        assertNull(inv.items[0], "the old contents are gone")
        assertEquals(1, p.opened.size, "no second inventory for the same size")
    }

    @Test
    fun `a different size opens a new inventory and the close event of the old one is ignored`() {
        val p = steve()
        val closes = CopyOnWriteArrayList<String>()
        view.model = recordingModel(closes)
        view.show("Steve", screen(MenuItem(0, "DIAMOND", "one"), rows = 2))
        await("open") { p.opened.size == 1 }
        val oldInv = p.opened[0]
        view.show("Steve", screen(MenuItem(0, "DIAMOND", "one"), rows = 3))
        await("reopened") { p.opened.size == 2 }
        view.onClose(InventoryCloseEvent(p.view.also { p.top = oldInv }))
        assertEquals(emptyList<String>(), closes, "our own reopen is not a cancel")
        p.top = p.opened[1]
        view.onClose(InventoryCloseEvent(p.view))
        assertEquals(listOf("Steve"), closes.toList(), "the player closing the current inventory cancels the flow")
        view.onClose(InventoryCloseEvent(p.view))
        assertEquals(1, closes.size, "a second close event of the same inventory does nothing")
    }

    @Test
    fun `clicks are always cancelled in the menu, only slots of the menu are forwarded, other inventories are untouched`() {
        val p = steve()
        val clicks = CopyOnWriteArrayList<Pair<String, Int>>()
        val model = recordingModel(CopyOnWriteArrayList(), clicks)
        view.model = model
        view.show("Steve", screen(MenuItem(0, "DIAMOND", "one")))
        await("open") { p.opened.size == 1 }
        p.top = p.opened[0]

        fun click(slot: Int): InventoryClickEvent {
            val e = InventoryClickEvent(p.view, InventoryType.SlotType.CONTAINER, slot, ClickType.LEFT, InventoryAction.PICKUP_ALL)
            view.onClick(e)
            return e
        }
        assertTrue(click(0).isCancelled)
        assertTrue(click(17).isCancelled)
        assertTrue(click(25).isCancelled, "a click in the player's own inventory below is cancelled too (no shift-click through)")
        assertTrue(click(-999).isCancelled, "a click outside")
        assertEquals(listOf("Steve" to 0, "Steve" to 17), clicks.toList(), "only slots inside the menu are forwarded")

        // Somebody else's inventory: not ours, never cancelled or forwarded.
        val chest = FakeInventory(27, null, "Chest")
        p.top = chest.inventory
        assertFalse(click(3).isCancelled)
        assertEquals(2, clicks.size)
    }

    @Test
    fun `a click on a stale menu inventory is cancelled but not forwarded`() {
        val p = steve()
        val clicks = CopyOnWriteArrayList<Pair<String, Int>>()
        view.model = recordingModel(CopyOnWriteArrayList(), clicks)
        view.show("Steve", screen(MenuItem(0, "DIAMOND", "one"), rows = 2))
        await("open") { p.opened.size == 1 }
        val stale = p.opened[0]
        view.show("Steve", screen(MenuItem(0, "DIAMOND", "one"), rows = 3))
        await("reopened") { p.opened.size == 2 }
        p.top = stale
        val e = InventoryClickEvent(p.view, InventoryType.SlotType.CONTAINER, 0, ClickType.LEFT, InventoryAction.PICKUP_ALL)
        view.onClick(e)
        assertTrue(e.isCancelled)
        assertEquals(0, clicks.size)
    }

    @Test
    fun `close closes the inventory, quit forgets the player`() {
        val p = steve()
        val closes = CopyOnWriteArrayList<String>()
        view.model = recordingModel(closes)
        view.show("Steve", screen(MenuItem(0, "DIAMOND", "one")))
        await("open") { p.opened.size == 1 }
        view.close("Steve")
        await("closed") { p.closedCalls == 1 }
        // The close event our own closeInventory raises finds no open menu.
        view.onClose(InventoryCloseEvent(p.view.also { p.top = p.opened[0] }))
        assertEquals(0, closes.size)

        view.show("Steve", screen(MenuItem(0, "DIAMOND", "one")))
        await("open again") { p.opened.size == 2 }
        view.onQuit(PlayerQuitEvent(p.player, "bye"))
        assertEquals(listOf("Steve"), closes.toList())
    }

    @Test
    fun `an older screen never overwrites a newer one`() {
        val p = steve()
        // Two shows queued before the main thread runs: they run in order, and the newest wins.
        view.show("Steve", screen(MenuItem(0, "DIAMOND", "first")))
        view.show("Steve", screen(MenuItem(1, "EMERALD", "second")))
        await("both rendered") { inventories.isNotEmpty() && inventories.last().items[1] != null }
        assertNull(inventories.last().items[0])
    }

    @Test
    fun `an offline player or a refused task does not throw`() {
        view.show("Nobody", screen(MenuItem(0, "DIAMOND", "one")))
        view.close("Nobody")
        FakeBukkit.refuseTasks = IllegalStateException("disabled")
        view.show("Steve", screen(MenuItem(0, "DIAMOND", "one")))
        view.message("Steve", "hello", null)
        assertEquals(listOf(Triple("Steve", "hello", null)), chat.toList())
    }

    @Test
    fun `material names resolve with the 1_8_8 aliases and fall back to paper`() {
        val names = setOf("WATCH", "PAPER", "REDSTONE_BLOCK")
        val lookup: (String) -> Material? = { n -> if (n in names) Material.valueOf(n) else null }
        assertEquals(Material.getMaterial("WATCH"), MenuMaterials.resolve("CLOCK", lookup), "CLOCK is WATCH on 1.8")
        assertEquals(Material.REDSTONE_BLOCK, MenuMaterials.resolve("BARRIER", lookup))
        assertEquals(Material.PAPER, MenuMaterials.resolve("CROWN", lookup))
        assertEquals(Material.PAPER, MenuMaterials.resolve("X", { throw IllegalStateException("boom") }))
        assertEquals(Material.DIAMOND, MenuMaterials.resolve("DIAMOND"))
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    /** A real [StoreMenu] whose click / closed calls are only recorded (the model itself is covered by StoreMenuTest). */
    private fun recordingModel(closes: MutableList<String>, clicks: MutableList<Pair<String, Int>> = CopyOnWriteArrayList()): StoreMenu {
        val rig = FeatureRig(dir.resolve("m${System.nanoTime()}"))
        return object : StoreMenu(rig.features.config, rig.features.messages, rig.link, RecordingView(), { null }, { null }, "1.4.0") {
            override fun click(username: String, slot: Int) {
                clicks.add(username to slot)
            }

            override fun closed(username: String) {
                closes.add(username)
            }
        }
    }
}
