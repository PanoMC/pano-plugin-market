package com.panomc.plugins.market.mc.spigot.vault

import com.panomc.plugins.market.mc.core.feature.Feature
import com.panomc.plugins.market.mc.core.feature.FeatureHost
import com.panomc.plugins.market.mc.core.feature.GameLink
import com.panomc.plugins.market.mc.core.feature.MarketFeatures
import com.panomc.plugins.market.mc.core.link.PresenceTracker
import com.panomc.plugins.market.mc.core.platform.McClock
import com.panomc.plugins.market.mc.core.platform.McLog
import com.panomc.plugins.market.mc.core.platform.McScheduler
import com.panomc.plugins.market.mc.core.platform.McTimer
import com.panomc.plugins.market.mc.core.platform.SingleThreadScheduler
import com.panomc.plugins.market.mc.core.platform.SystemMcClock
import com.panomc.plugins.market.mc.spigot.MarketScheduler
import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.ServicesManager
import java.nio.file.Path

/**
 * The Vault bridge of the Spigot / Paper / Folia component (19 section 10, owner: "two modes, or off"). One instance per
 * enabled component; the platform main calls [start] after the features exist, [onPlayerPresent] on every authenticated
 * join and [stop] on disable.
 *
 * - `OFF` (default): nothing is registered, `/credits convert|deposit` answer "switched off".
 * - `CONVERT`: `/credits convert` and `/credits deposit` move money between the player's credits and the server's own
 *   economy (found through Vault; ours is never picked). No economy is registered.
 * - `PROVIDER`: the credits are registered as the Vault `Economy` (priority Highest) while Vault is installed.
 * The mode is read live every [pollMs] (the panel can change it any time): the economy is registered / unregistered on the
 * server thread, and journal entries of money operations whose outcome was unknown are resolved at every start in any mode.
 *
 * Vault classes are only touched through [VaultGlue] and only after the Vault plugin was seen enabled.
 */
class VaultBridge(
    private val plugin: Plugin,
    private val features: MarketFeatures,
    link: GameLink,
    private val marketScheduler: MarketScheduler,
    private val tracker: PresenceTracker,
    private val host: FeatureHost,
    dataDir: Path,
    componentVersion: String,
    private val log: McLog,
    clock: McClock = SystemMcClock,
    private val engineScheduler: McScheduler = SingleThreadScheduler("PanoMarket-vault", log),
    private val pollMs: Long = 5_000,
    providerTimeoutMs: Long = CreditEconomy.DEFAULT_TIMEOUT_MS,
    refreshMs: Long = CreditEconomy.DEFAULT_REFRESH_MS,
    retryBaseMs: Long = 2_000,
    private val services: () -> ServicesManager? = { try { Bukkit.getServicesManager() } catch (_: Throwable) { null } },
    private val vaultPresent: () -> Boolean = { try { Bukkit.getPluginManager().isPluginEnabled("Vault") } catch (_: Throwable) { false } }
) {
    private val settings = VaultSettingsReader(features.config)
    private val texts = VaultMessages(features.messages)
    private val client = EconomyClient(link, componentVersion, log)
    private val journal = VaultJournal(dataDir.resolve("vault"), log, clock::now)
    private val mainThread = MainThread { task -> marketScheduler.runGlobal(task) }

    private val ops = VaultOps(
        client, journal, engineScheduler, clock, log, mainThread,
        economy = { serverEconomy() },
        notifier = { entry, notice, code -> notify(entry, notice, code) },
        retryBaseMs = retryBaseMs
    )

    private val credit = CreditEconomy(
        ops, client, engineScheduler, clock, log,
        uuidOf = { tracker.uuid(it) },
        presentNames = { tracker.presentNames() },
        timeoutMs = providerTimeoutMs,
        refreshMs = refreshMs
    )

    private val commands = VaultCommands(ops, { settings.current() }, texts, features.messages) { serverEconomy() }

    /** The registered credits economy (a `ProviderEconomy`), `null` while not registered. */
    @Volatile
    private var provider: Any? = null

    @Volatile
    private var pollTimer: McTimer? = null

    @Volatile
    private var running = false
    private var warnedMissingVault = false
    private var warnedOutranked: String? = null

    /** The Vault plugin is installed and enabled: what `MARKET_SYNC.vault` reports and the PROVIDER mode needs. */
    fun vaultAvailable(): Boolean = vaultPresent()

    /** The credits economy is registered with Vault right now (PROVIDER mode in force). */
    fun providerActive(): Boolean = provider != null

    fun start() {
        if (running) return
        running = true
        ops.start()
        features.commands.registerSub("credits", "convert", Feature.VAULT) { sender, args -> commands.convert(sender, args) }
        features.commands.registerSub("credits", "deposit", Feature.VAULT) { sender, args -> commands.deposit(sender, args) }
        credit.start()
        apply(onServerThread = true)
        schedulePoll()
    }

    fun stop() {
        running = false
        pollTimer?.cancel()
        pollTimer = null
        credit.stop()
        unregisterProvider()
        ops.stop()
        (engineScheduler as? SingleThreadScheduler)?.shutdown(1_000)
    }

    /** An authenticated join: the balance cache of that player is loaded (PROVIDER mode only). */
    fun onPlayerPresent(username: String) {
        if (provider != null) credit.onPresent(username)
    }

    private fun schedulePoll() {
        if (!running) return
        pollTimer = engineScheduler.schedule(pollMs) {
            try {
                apply(onServerThread = false)
            } catch (t: Throwable) {
                if (t is VirtualMachineError) throw t
                log.warn("Vault: the mode check failed: ${t.message}")
            } finally {
                schedulePoll()
            }
        }
    }

    /** Brings the registration in line with the effective mode. Registering / unregistering happens on the server thread. */
    fun apply(onServerThread: Boolean) {
        val mode = settings.current().mode
        val wantProvider = mode == VaultMode.PROVIDER
        if (wantProvider && !vaultAvailable()) {
            if (!warnedMissingVault) {
                warnedMissingVault = true
                log.warn("Vault: the panel set mcVaultMode to PROVIDER but the Vault plugin is not installed, so the credits are not registered as the economy.")
            }
            return unregisterOnThread(onServerThread)
        }
        warnedMissingVault = false
        if (wantProvider && provider == null) registerOnThread(onServerThread)
        if (!wantProvider && provider != null) unregisterOnThread(onServerThread)
    }

    private fun registerOnThread(onServerThread: Boolean) = onThread(onServerThread) { registerProvider() }

    private fun unregisterOnThread(onServerThread: Boolean) {
        if (provider != null) onThread(onServerThread) { unregisterProvider() }
    }

    private fun onThread(onServerThread: Boolean, task: () -> Unit) {
        if (onServerThread) return task()
        try {
            marketScheduler.runGlobal(Runnable { task() })
        } catch (t: Throwable) {
            if (t is VirtualMachineError) throw t
            log.warn("Vault: a task could not be handed to the server thread: ${t.message}")
        }
    }

    private fun registerProvider() {
        if (provider != null) return
        val sm = services() ?: return log.warn("Vault: the services manager is not available.")
        try {
            val p = VaultGlue.createProvider(credit, { features.config.remote?.creditName ?: DEFAULT_CREDIT_NAME }, { provider != null })
            VaultGlue.register(plugin, sm, p)
            provider = p
            log.info("Vault: the Pano credits are now the server economy (mode PROVIDER).")
            if (!VaultGlue.isActiveProvider(sm, p)) {
                val other = VaultGlue.activeProviderName(sm)
                if (other != warnedOutranked) {
                    warnedOutranked = other
                    log.warn("Vault: another economy ($other) outranks the Pano credits, so Vault plugins will not use them.")
                }
            } else {
                warnedOutranked = null
            }
        } catch (t: Throwable) {
            if (t is VirtualMachineError) throw t
            log.error("Vault: the credits economy could not be registered: ${t.message}", t)
        }
    }

    private fun unregisterProvider() {
        val p = provider ?: return
        provider = null
        try {
            services()?.let { VaultGlue.unregister(it, p) }
            log.info("Vault: the Pano credits are no longer the server economy.")
        } catch (t: Throwable) {
            if (t is VirtualMachineError) throw t
            log.warn("Vault: the credits economy could not be unregistered cleanly: ${t.message}")
        }
    }

    /** The server's own economy (never ours), `null` without Vault or without any other registered economy. Server thread. */
    private fun serverEconomy(): ServerEconomy? {
        if (!vaultAvailable()) return null
        val sm = services() ?: return null
        return try {
            VaultGlue.serverEconomy(sm, provider)
        } catch (t: Throwable) {
            if (t is VirtualMachineError) throw t
            log.warn("Vault: the server economy could not be looked up: ${t.message}")
            null
        }
    }

    private fun notify(entry: VaultEntry, notice: Notice, code: String?) {
        val locale = host.localeOf(entry.username)
        val text = when (notice) {
            Notice.DEPOSIT_ARRIVED -> texts.text(VaultMsg.LATE_DEPOSITED, locale, "credits" to com.panomc.plugins.market.mc.core.feature.MarketCommands.number(entry.credits))
            Notice.CONVERSION_UNDONE -> texts.text(VaultMsg.LATE_UNDONE, locale)
            Notice.MONEY_REFUNDED -> texts.text(VaultMsg.LATE_REFUNDED, locale, "code" to (code ?: "REFUSED"))
            Notice.MONEY_REFUND_STUCK -> texts.text(VaultMsg.LATE_REFUND_STUCK, locale, "code" to (code ?: "REFUSED"))
        }
        host.sendTo(entry.username, text)
    }

    private companion object {
        const val DEFAULT_CREDIT_NAME = "Credits"
    }
}
