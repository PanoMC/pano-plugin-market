package com.panomc.plugins.market.mc.spigot.placeholder

import com.panomc.plugins.market.mc.core.platform.McLog
import me.clip.placeholderapi.expansion.PlaceholderExpansion
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer

/**
 * `%panomarket_<identifier>%`. Loaded only after PlaceholderAPI was found enabled (the class extends a PlaceholderAPI
 * class; `PlaceholderHook` catches every linkage problem). Values come from [PlaceholderCache]: no network, no waiting.
 */
class MarketExpansion(private val cache: PlaceholderCache, private val version: String) : PlaceholderExpansion() {
    override fun getIdentifier(): String = "panomarket"

    override fun getAuthor(): String = "Pano"

    override fun getVersion(): String = version

    /** Stays registered over `/papi reload`. */
    override fun persist(): Boolean = true

    override fun onRequest(player: OfflinePlayer?, params: String): String? = cache.resolve(params, player?.name)
}

/**
 * Registers the expansion when PlaceholderAPI is there; [registered] feeds `McPlatform.placeholderApiAvailable()`. [register]
 * returns the function that removes the expansion again, or `null` when PlaceholderAPI refused it.
 */
class PlaceholderHook(
    private val cache: PlaceholderCache,
    private val version: String,
    private val log: McLog,
    private val enabled: () -> Boolean = { Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI") },
    private val register: (PlaceholderCache, String) -> (() -> Unit)? = { c, v ->
        val expansion = MarketExpansion(c, v)
        if (expansion.register()) ({ expansion.unregister() }) else null
    }
) {
    @Volatile
    var registered = false
        private set

    private var remove: (() -> Unit)? = null

    fun install() {
        try {
            if (!enabled()) return
            remove = register(cache, version)
            registered = remove != null
            if (!registered) log.warn("PlaceholderAPI did not accept the panomarket expansion.")
        } catch (t: Throwable) {
            registered = false
            log.warn("PlaceholderAPI is installed but the panomarket expansion could not be registered: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    fun uninstall() {
        registered = false
        val r = remove ?: return
        remove = null
        try {
            r()
        } catch (_: Throwable) {
        }
    }
}
