package com.panomc.plugins.market.mc.spigot.gui

import com.panomc.plugins.market.mc.core.platform.McLog
import com.panomc.plugins.market.mc.spigot.MarketScheduler
import com.panomc.plugins.market.mc.spigot.SpigotMessages
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Marks the inventories of this menu; the listener only ever touches inventories whose holder is a [MenuHolder]. */
class MenuHolder(val owner: String) : InventoryHolder {
    lateinit var created: Inventory

    override fun getInventory(): Inventory = created
}

/** Which material answers to a name on the running server (the API of 1.8.8 and the current names differ for a few). */
object MenuMaterials {
    private val ALIASES = mapOf(
        "CLOCK" to listOf("WATCH"),
        "BARRIER" to listOf("BARRIER", "REDSTONE_BLOCK")
    )

    fun resolve(name: String, lookup: (String) -> Material? = { Material.matchMaterial(it) }): Material {
        for (candidate in listOf(name) + (ALIASES[name] ?: emptyList()) + "PAPER") {
            val m = try {
                lookup(candidate)
            } catch (_: Throwable) {
                null
            }
            if (m != null) return m
        }
        return Material.PAPER
    }
}

/**
 * The Bukkit side of the chest menu. Only the API of Spigot 1.8.8 is used (createInventory, ItemStack, ItemMeta, the
 * inventory click / close / drag events, `Player.openInventory`). Every inventory action runs through
 * [MarketScheduler] (global region to find the player, then the player's own scheduler on Folia), numbered so that an
 * older screen never overwrites a newer one even when the two hops of two screens interleave.
 *
 * The inventory title is constant, so a screen change rewrites the contents of the open inventory instead of reopening
 * one (no cursor jump, no close event). A close event of an inventory that is no longer the current one is ignored,
 * which is what makes "closing cancels the flow" safe against our own reopen.
 */
class BukkitMenuView(
    private val scheduler: MarketScheduler,
    private val log: McLog,
    private val title: () -> String,
    private val chat: (username: String, text: String, openUrl: String?) -> Unit
) : MenuView, Listener {
    private class Open(val holder: MenuHolder, val inventory: Inventory)

    private val open = ConcurrentHashMap<String, Open>()
    private val sequence = AtomicLong()
    private val applied = ConcurrentHashMap<String, Long>()

    /** Set by the plugin after the model exists (the model needs the view first). */
    @Volatile
    var model: StoreMenu? = null

    override fun show(username: String, screen: MenuScreen) {
        val seq = sequence.incrementAndGet()
        onPlayer(username) { player -> render(player, screen, seq) }
    }

    override fun close(username: String) {
        val seq = sequence.incrementAndGet()
        onPlayer(username) { player ->
            if (!fresh(username, seq)) return@onPlayer
            val current = open.remove(username.lowercase())
            if (current != null && player.openInventory?.topInventory === current.inventory) player.closeInventory()
        }
    }

    override fun message(username: String, text: String, openUrl: String?) = chat(username, text, openUrl)

    /** Closes every menu (plugin disable). */
    fun closeAll() {
        for (name in open.keys.toList()) close(name)
    }

    private fun fresh(username: String, seq: Long): Boolean {
        val key = username.lowercase()
        val previous = applied[key] ?: 0L
        if (seq < previous) return false
        applied[key] = seq
        return true
    }

    private fun onPlayer(username: String, task: (Player) -> Unit) {
        try {
            scheduler.runGlobal {
                val player = Bukkit.getPlayerExact(username) ?: return@runGlobal
                try {
                    scheduler.runForPlayer(player, {
                        try {
                            task(player)
                        } catch (t: Throwable) {
                            log.warn("The store menu of $username failed: ${t.message}")
                        }
                    })
                } catch (t: Throwable) {
                    log.warn("The store menu of $username could not be scheduled: ${t.message}")
                }
            }
        } catch (t: Throwable) {
            log.warn("The store menu of $username could not be scheduled: ${t.message}")
        }
    }

    private fun render(player: Player, screen: MenuScreen, seq: Long) {
        val key = player.name.lowercase()
        if (!fresh(key, seq)) return
        val current = open[key]
        val reusable = current != null && current.inventory.size == screen.size && player.openInventory?.topInventory === current.inventory
        val inventory: Inventory
        if (reusable) {
            inventory = current!!.inventory
            inventory.clear()
        } else {
            val holder = MenuHolder(player.name)
            inventory = Bukkit.createInventory(holder, screen.size, title().take(32))
            holder.created = inventory
            // Register the new inventory first: the close event of the old one then no longer matches.
            open[key] = Open(holder, inventory)
            fill(inventory, screen)
            player.openInventory(inventory)
            return
        }
        fill(inventory, screen)
    }

    private fun fill(inventory: Inventory, screen: MenuScreen) {
        for (item in screen.items) {
            if (item.slot < 0 || item.slot >= inventory.size) continue
            val stack = ItemStack(MenuMaterials.resolve(item.material))
            val meta = stack.itemMeta
            if (meta != null) {
                meta.displayName = item.title
                if (item.lore.isNotEmpty()) meta.lore = item.lore
                stack.itemMeta = meta
            }
            inventory.setItem(item.slot, stack)
        }
    }

    // ---- events ---------------------------------------------------------------------------------------------------

    private fun holderOf(inventory: Inventory?): MenuHolder? = inventory?.holder as? MenuHolder

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onClick(event: InventoryClickEvent) {
        val top = event.view.topInventory
        val holder = holderOf(top) ?: return
        // Nothing can be taken out of, put into or shift-clicked through the menu.
        event.isCancelled = true
        val player = event.whoClicked as? Player ?: return
        val current = open[player.name.lowercase()]
        if (current == null || current.inventory !== top || current.holder !== holder) return
        val raw = event.rawSlot
        if (raw < 0 || raw >= top.size) return
        model?.click(player.name, raw)
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onDrag(event: InventoryDragEvent) {
        if (holderOf(event.view.topInventory) != null) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onClose(event: InventoryCloseEvent) {
        val holder = holderOf(event.inventory) ?: return
        val key = holder.owner.lowercase()
        val current = open[key]
        if (current == null || current.inventory !== event.inventory) return
        open.remove(key, current)
        model?.closed(holder.owner)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        val key = event.player.name.lowercase()
        open.remove(key)
        applied.remove(key)
        model?.closed(event.player.name)
    }
}

/** Chat output of the menu: the same clickable-link line as the commands, sent on the server thread. */
fun menuChat(scheduler: MarketScheduler, log: McLog): (String, String, String?) -> Unit = { username, text, url ->
    try {
        scheduler.runGlobal {
            Bukkit.getPlayerExact(username)?.let { SpigotMessages.send(it, text, url) }
        }
    } catch (t: Throwable) {
        log.warn("A store menu message could not be scheduled: ${t.message}")
    }
}
