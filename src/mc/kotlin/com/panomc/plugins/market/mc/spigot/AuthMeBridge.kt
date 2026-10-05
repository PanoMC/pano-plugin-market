package com.panomc.plugins.market.mc.spigot

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.plugin.EventExecutor
import org.bukkit.plugin.Plugin
import java.lang.reflect.Method

/**
 * AuthMe by reflection (19 section 3: "AuthMe absent, or `AuthMeApi.isAuthenticated(player)`"): the component compiles
 * and loads without AuthMe on the class path, and no AuthMe class is referenced by a verified bytecode path.
 * An unknown authentication plugin is not detected and counts as "online" (documented, MC-R6).
 */
class AuthMeBridge(private val plugin: Plugin) {
    private val listener = object : Listener {}

    fun installed(): Boolean = Bukkit.getPluginManager().isPluginEnabled(PLUGIN)

    private val api: Pair<Any?, Method>? by lazy {
        val p = Bukkit.getPluginManager().getPlugin(PLUGIN) ?: return@lazy null
        val cls = Class.forName("fr.xephi.authme.api.v3.AuthMeApi", true, p.javaClass.classLoader)
        cls.getMethod("getInstance").invoke(null) to cls.getMethod("isAuthenticated", Player::class.java)
    }

    /** Throws when the API is unusable (the caller treats that as "not authenticated yet" and waits for the login event). */
    fun isAuthenticated(player: Player): Boolean {
        val (instance, method) = api ?: throw IllegalStateException("AuthMe is not loaded")
        return method.invoke(instance, player) as Boolean
    }

    /** Registers the AuthMe login / logout events; `false` when AuthMe is missing or its events cannot be found. */
    fun registerEvents(onLogin: (Player) -> Unit, onLogout: (Player) -> Unit): Boolean {
        val p = Bukkit.getPluginManager().getPlugin(PLUGIN) ?: return false
        return try {
            register(p, "fr.xephi.authme.events.LoginEvent", onLogin)
            register(p, "fr.xephi.authme.events.LogoutEvent", onLogout)
            true
        } catch (_: Throwable) {
            false
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun register(authMe: Plugin, eventClass: String, handler: (Player) -> Unit) {
        val cls = Class.forName(eventClass, true, authMe.javaClass.classLoader) as Class<out Event>
        val getPlayer = cls.getMethod("getPlayer")
        val executor = EventExecutor { _, event ->
            if (cls.isInstance(event)) (getPlayer.invoke(event) as? Player)?.let(handler)
        }
        Bukkit.getPluginManager().registerEvent(cls, listener, EventPriority.MONITOR, executor, plugin, true)
    }

    companion object {
        const val PLUGIN = "AuthMe"
    }
}
