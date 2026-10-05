package com.panomc.plugins.market.mc.core.feature

import com.panomc.plugins.market.mc.core.sync.DeliveryRuntime
import com.panomc.plugins.market.mc.core.sync.RecoveryPreview
import com.panomc.plugins.market.mc.core.sync.RuntimeStatus
import java.util.concurrent.CompletableFuture

/** Someone who typed a command: a player or the console. All text is legacy `§` text, made by [Messages]. */
interface McSender {
    val name: String
    val isConsole: Boolean

    /** The player's UUID, `null` for the console. */
    val uuid: String?

    /** The client locale when the platform knows it (`tr_TR`), else `null`. */
    val locale: String?

    /** The console always has every node; a player's answer comes from the platform / permission plugin. */
    fun hasPermission(node: String): Boolean

    /** [openUrl] makes the line clickable where the platform can (plain http / https URLs only). */
    fun send(text: String, openUrl: String? = null)
}

/** What the features need from the platform to talk to the players. Implementations hop to the right thread themselves. */
interface FeatureHost {
    /** To one player by name (case-insensitive); nothing happens when they are not online. */
    fun sendTo(username: String, text: String, openUrl: String? = null)

    /** To everybody who is online on this server / network, and the console. */
    fun broadcast(text: String)

    /** The client locale of an online player (`tr_TR`) when the platform knows it. */
    fun localeOf(username: String): String? = null
}

/** The parts of the delivery runtime the commands drive; an interface so the command logic is testable without threads. */
interface RuntimeControl {
    fun status(): RuntimeStatus
    fun recoveryPreview(): CompletableFuture<RecoveryPreview?>
    fun confirmRecovery(): CompletableFuture<Boolean>

    /** Sync soon, e.g. after something that made Pano create a delivery (19 section 7.3). */
    fun syncSoon()
}

fun DeliveryRuntime.asControl(): RuntimeControl = object : RuntimeControl {
    override fun status(): RuntimeStatus = this@asControl.status()
    override fun recoveryPreview(): CompletableFuture<RecoveryPreview?> = this@asControl.recoveryPreview()
    override fun confirmRecovery(): CompletableFuture<Boolean> = this@asControl.confirmRecovery()
    override fun syncSoon() = this@asControl.syncSoon()
}

/** Reads a file of the jar by path (`mc/config.yml`) as UTF-8 text; `null` when it is not there. */
object ResourceFiles {
    fun reader(loader: ClassLoader): (String) -> String? = { path ->
        try {
            loader.getResourceAsStream(path)?.use { String(it.readBytes(), Charsets.UTF_8) }
        } catch (_: Exception) {
            null
        }
    }
}
