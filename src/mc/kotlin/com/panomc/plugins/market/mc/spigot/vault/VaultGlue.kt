@file:Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")

package com.panomc.plugins.market.mc.spigot.vault

import com.panomc.plugins.market.mc.core.feature.MarketCommands
import com.panomc.plugins.market.mc.core.wire.PlayerRef
import net.milkbowl.vault.economy.AbstractEconomy
import net.milkbowl.vault.economy.Economy
import net.milkbowl.vault.economy.EconomyResponse
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.ServicePriority
import org.bukkit.plugin.ServicesManager
import java.util.UUID

/*
 * Everything in this file touches Vault classes. Nothing else in the component does: the bridge only reaches this file
 * after it saw the Vault plugin enabled, so a server without Vault never loads these classes.
 */

/**
 * The Pano credits as the Vault `Economy` (PROVIDER mode, 19 section 10). Thin: every decision is in [CreditEconomy].
 * Bank and multi-world methods are unsupported (`hasBankSupport() = false`); the world argument is ignored.
 * Parameters are declared nullable on purpose: another plugin passing a nameless `OfflinePlayer` must get a FAILURE, not a
 * `NullPointerException` thrown through its call.
 */
class ProviderEconomy(
    private val core: CreditEconomy,
    private val creditName: () -> String,
    private val active: () -> Boolean
) : AbstractEconomy() {
    override fun isEnabled(): Boolean = active()

    override fun getName(): String = "Pano Market"

    override fun hasBankSupport(): Boolean = false

    override fun fractionalDigits(): Int = 2

    override fun format(amount: Double): String = "${MarketCommands.number(amount)} ${creditName()}"

    override fun currencyNamePlural(): String = creditName()

    override fun currencyNameSingular(): String = creditName()

    override fun hasAccount(playerName: String?): Boolean = core.registered(playerName)

    override fun hasAccount(playerName: String?, worldName: String?): Boolean = core.registered(playerName)

    override fun getBalance(playerName: String?): Double = core.balance(playerName)

    override fun getBalance(playerName: String?, world: String?): Double = core.balance(playerName)

    override fun has(playerName: String?, amount: Double): Boolean = core.has(playerName, amount)

    override fun has(playerName: String?, worldName: String?, amount: Double): Boolean = core.has(playerName, amount)

    override fun withdrawPlayer(playerName: String?, amount: Double): EconomyResponse = response(core.withdraw(playerName, amount))

    override fun withdrawPlayer(playerName: String?, worldName: String?, amount: Double): EconomyResponse = response(core.withdraw(playerName, amount))

    override fun depositPlayer(playerName: String?, amount: Double): EconomyResponse = response(core.deposit(playerName, amount))

    override fun depositPlayer(playerName: String?, worldName: String?, amount: Double): EconomyResponse = response(core.deposit(playerName, amount))

    // An account is a Pano account, made on the website: nothing can create one from here (true when it exists).
    override fun createPlayerAccount(playerName: String?): Boolean = core.registered(playerName)

    override fun createPlayerAccount(playerName: String?, worldName: String?): Boolean = core.registered(playerName)

    override fun createBank(name: String?, player: String?): EconomyResponse = noBanks()

    override fun deleteBank(name: String?): EconomyResponse = noBanks()

    override fun bankBalance(name: String?): EconomyResponse = noBanks()

    override fun bankHas(name: String?, amount: Double): EconomyResponse = noBanks()

    override fun bankWithdraw(name: String?, amount: Double): EconomyResponse = noBanks()

    override fun bankDeposit(name: String?, amount: Double): EconomyResponse = noBanks()

    override fun isBankOwner(name: String?, playerName: String?): EconomyResponse = noBanks()

    override fun isBankMember(name: String?, playerName: String?): EconomyResponse = noBanks()

    override fun getBanks(): MutableList<String> = ArrayList()

    // The OfflinePlayer variants of AbstractEconomy read `player.getName()` unguarded: a player without a name must fail cleanly.
    override fun hasAccount(player: OfflinePlayer?): Boolean = core.registered(player?.name)

    override fun hasAccount(player: OfflinePlayer?, worldName: String?): Boolean = core.registered(player?.name)

    override fun getBalance(player: OfflinePlayer?): Double = core.balance(player?.name)

    override fun getBalance(player: OfflinePlayer?, world: String?): Double = core.balance(player?.name)

    override fun has(player: OfflinePlayer?, amount: Double): Boolean = core.has(player?.name, amount)

    override fun has(player: OfflinePlayer?, worldName: String?, amount: Double): Boolean = core.has(player?.name, amount)

    override fun withdrawPlayer(player: OfflinePlayer?, amount: Double): EconomyResponse = response(core.withdraw(player?.name, amount))

    override fun withdrawPlayer(player: OfflinePlayer?, worldName: String?, amount: Double): EconomyResponse = response(core.withdraw(player?.name, amount))

    override fun depositPlayer(player: OfflinePlayer?, amount: Double): EconomyResponse = response(core.deposit(player?.name, amount))

    override fun depositPlayer(player: OfflinePlayer?, worldName: String?, amount: Double): EconomyResponse = response(core.deposit(player?.name, amount))

    override fun createPlayerAccount(player: OfflinePlayer?): Boolean = core.registered(player?.name)

    override fun createPlayerAccount(player: OfflinePlayer?, worldName: String?): Boolean = core.registered(player?.name)

    override fun createBank(name: String?, player: OfflinePlayer?): EconomyResponse = noBanks()

    override fun isBankOwner(name: String?, player: OfflinePlayer?): EconomyResponse = noBanks()

    override fun isBankMember(name: String?, player: OfflinePlayer?): EconomyResponse = noBanks()

    private fun response(r: EconomyResult): EconomyResponse =
        if (r.ok) EconomyResponse(r.amount, r.balance, EconomyResponse.ResponseType.SUCCESS, null)
        else EconomyResponse(0.0, r.balance, EconomyResponse.ResponseType.FAILURE, r.error ?: "The operation failed.")

    private fun noBanks() = EconomyResponse(0.0, 0.0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Pano credits do not support banks.")
}

/** The server's real Vault economy as a [ServerEconomy]. Uses the `OfflinePlayer` calls when the UUID is known, else the name. */
class VaultServerEconomy(
    private val economy: Economy,
    private val offlinePlayer: (PlayerRef) -> OfflinePlayer? = { p -> p.uuid?.let { u -> parse(u)?.let { Bukkit.getOfflinePlayer(it) } } }
) : ServerEconomy {
    override val name: String get() = economy.name ?: "Vault economy"

    override fun format(amount: Double): String = try {
        economy.format(amount) ?: MarketCommands.number(amount)
    } catch (_: Throwable) {
        MarketCommands.number(amount)
    }

    override fun has(player: PlayerRef, amount: Double): Boolean {
        val op = offlinePlayer(player)
        return if (op != null) economy.has(op, amount) else economy.has(player.username, amount)
    }

    override fun withdraw(player: PlayerRef, amount: Double): ServerResult {
        val op = offlinePlayer(player)
        val r = if (op != null) economy.withdrawPlayer(op, amount) else economy.withdrawPlayer(player.username, amount)
        return result(r)
    }

    override fun deposit(player: PlayerRef, amount: Double): ServerResult {
        val op = offlinePlayer(player)
        val r = if (op != null) economy.depositPlayer(op, amount) else economy.depositPlayer(player.username, amount)
        return result(r)
    }

    private fun result(r: EconomyResponse?): ServerResult =
        if (r != null && r.transactionSuccess()) ServerResult(true) else ServerResult(false, r?.errorMessage ?: "the economy refused the transaction")

    private companion object {
        fun parse(u: String): UUID? = try {
            UUID.fromString(u)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

/** The Bukkit services manager side of the bridge: register / unregister the credits economy, find the server's own. */
object VaultGlue {
    fun createProvider(core: CreditEconomy, creditName: () -> String, active: () -> Boolean): Any = ProviderEconomy(core, creditName, active)

    fun register(plugin: Plugin, services: ServicesManager, provider: Any) {
        services.register(Economy::class.java, provider as Economy, plugin, ServicePriority.Highest)
    }

    fun unregister(services: ServicesManager, provider: Any) {
        services.unregister(Economy::class.java, provider)
    }

    /** `true` while [provider] is the registration Vault hands to other plugins (nobody else outranks it). */
    fun isActiveProvider(services: ServicesManager, provider: Any): Boolean = services.getRegistration(Economy::class.java)?.provider === provider

    /** The name of the economy that currently wins (for the log when [provider] is not the one). */
    fun activeProviderName(services: ServicesManager): String? = services.getRegistration(Economy::class.java)?.provider?.name

    /** The server's own economy: the best registered one that is not [exclude] (our own credits provider). */
    fun serverEconomy(services: ServicesManager, exclude: Any?, offlinePlayer: ((PlayerRef) -> OfflinePlayer?)? = null): ServerEconomy? {
        val registrations = services.getRegistrations(Economy::class.java) ?: return null
        val best = registrations.filter { it.provider !== exclude && it.provider !is ProviderEconomy }.maxByOrNull { it.priority.ordinal } ?: return null
        val provider = best.provider ?: return null
        return if (offlinePlayer != null) VaultServerEconomy(provider, offlinePlayer) else VaultServerEconomy(provider)
    }
}
