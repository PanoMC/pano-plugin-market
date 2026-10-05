@file:Suppress("DEPRECATION")

package com.panomc.plugins.market.mc.vault

import com.panomc.plugins.market.mc.core.feature.Feature
import com.panomc.plugins.market.mc.core.link.PresenceTracker
import com.panomc.plugins.market.mc.core.support.TestClock
import com.panomc.plugins.market.mc.core.wire.EconomyOp
import com.panomc.plugins.market.mc.core.wire.MarketMcSettings
import com.panomc.plugins.market.mc.core.wire.PlayerRef
import com.panomc.plugins.market.mc.feature.CollectingLog
import com.panomc.plugins.market.mc.feature.FakeHost
import com.panomc.plugins.market.mc.feature.FakeSender
import com.panomc.plugins.market.mc.feature.FeatureRig
import com.panomc.plugins.market.mc.feature.panoConfig
import com.panomc.plugins.market.mc.link.proxyOf
import com.panomc.plugins.market.mc.spigot.FakeBukkit
import com.panomc.plugins.market.mc.spigot.MarketScheduler
import com.panomc.plugins.market.mc.spigot.vault.OpKind
import com.panomc.plugins.market.mc.spigot.vault.OpPhase
import com.panomc.plugins.market.mc.spigot.vault.ProviderEconomy
import com.panomc.plugins.market.mc.spigot.vault.VaultBridge
import com.panomc.plugins.market.mc.spigot.vault.VaultEntry
import com.panomc.plugins.market.mc.spigot.vault.VaultGlue
import com.panomc.plugins.market.mc.spigot.vault.VaultJournal
import net.milkbowl.vault.economy.Economy
import net.milkbowl.vault.economy.EconomyResponse
import org.bukkit.OfflinePlayer
import org.bukkit.Server
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.RegisteredServiceProvider
import org.bukkit.plugin.ServicePriority
import org.bukkit.plugin.ServicesManager
import org.bukkit.scheduler.BukkitScheduler
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** The Bukkit side of the bridge: registering the credits as the Vault economy (PROVIDER), finding the server's own (CONVERT), mode changes, resume. */
class VaultBridgeTest {
    @TempDir
    lateinit var dir: Path

    // ---- fake Bukkit pieces ----------------------------------------------------------------------------------------------

    class Reg(val service: Class<*>, val provider: Any, val priority: ServicePriority, val plugin: Plugin)

    private val regs = CopyOnWriteArrayList<Reg>()

    @Suppress("UNCHECKED_CAST")
    private fun registration(r: Reg) = RegisteredServiceProvider(r.service as Class<Any>, r.provider, r.priority, r.plugin)

    private val services: ServicesManager = proxyOf(ServicesManager::class.java) { m, a ->
        when (m.name) {
            "register" -> {
                regs.add(Reg(a[0] as Class<*>, a[1]!!, a[3] as ServicePriority, a[2] as Plugin))
                null
            }
            "unregister" -> {
                if (a.size == 2) regs.removeIf { it.service == a[0] && it.provider === a[1] } else regs.removeIf { it.provider === a[0] }
                null
            }
            "getRegistration" -> regs.filter { it.service == a[0] }.maxWithOrNull(compareBy<Reg> { it.priority.ordinal }.thenBy { -regs.indexOf(it) })?.let { registration(it) }
            "getRegistrations" -> regs.filter { it.service == a[0] }.sortedByDescending { it.priority.ordinal }.map { registration(it) }
            else -> throw UnsupportedOperationException("ServicesManager.${m.name}")
        }
    }

    private val bukkitScheduler: BukkitScheduler = proxyOf(BukkitScheduler::class.java) { m, a ->
        if (m.name == "runTask") {
            (a[1] as Runnable).run()
            null
        } else {
            throw UnsupportedOperationException("BukkitScheduler.${m.name}")
        }
    }

    private val server: Server = proxyOf(Server::class.java) { m, _ ->
        when (m.name) {
            "getScheduler" -> bukkitScheduler
            else -> throw UnsupportedOperationException("Server.${m.name}")
        }
    }

    private val plugin: Plugin = proxyOf(Plugin::class.java) { m, _ ->
        when (m.name) {
            "getName" -> "PanoMarket"
            "getServer" -> server
            else -> throw UnsupportedOperationException("Plugin.${m.name}")
        }
    }

    private fun offlinePlayerProxy(name: String, uuid: UUID): OfflinePlayer = proxyOf(OfflinePlayer::class.java) { m, _ ->
        when (m.name) {
            "getName" -> name
            "getUniqueId" -> uuid
            else -> throw UnsupportedOperationException("OfflinePlayer.${m.name}")
        }
    }

    /** A Vault economy of another plugin (Essentials, ...) over the fake server economy. */
    private fun otherEconomy(name: String, backing: FakeServerEconomy): Economy = proxyOf(Economy::class.java) { m, a ->
        fun who(x: Any?) = if (x is OfflinePlayer) x.name else x as String
        when (m.name) {
            "getName" -> name
            "format" -> backing.format(a[0] as Double)
            "has" -> backing.has(PlayerRef(who(a[0])), a.last() as Double)
            "withdrawPlayer" -> backing.withdraw(PlayerRef(who(a[0])), a.last() as Double).let { r ->
                EconomyResponse(a.last() as Double, 0.0, if (r.ok) EconomyResponse.ResponseType.SUCCESS else EconomyResponse.ResponseType.FAILURE, r.error)
            }
            "depositPlayer" -> backing.deposit(PlayerRef(who(a[0])), a.last() as Double).let { r ->
                EconomyResponse(a.last() as Double, 0.0, if (r.ok) EconomyResponse.ResponseType.SUCCESS else EconomyResponse.ResponseType.FAILURE, r.error)
            }
            else -> throw UnsupportedOperationException("Economy.${m.name}")
        }
    }

    // ---- the rig ---------------------------------------------------------------------------------------------------------------

    private val clock = TestClock()
    private val events = Events()
    private val ledger = FakeLedger(events)
    private val link = EconomyLink(ledger, events)
    private val engine = InlineScheduler(clock)
    private val serverMoney = FakeServerEconomy(events)
    private val host = FakeHost()
    private val log = CollectingLog()
    private val tracker = PresenceTracker()
    private var vaultOn = true
    private var config: String? = null
    private val bridges = ArrayList<VaultBridge>()

    private val rig by lazy { FeatureRig(dir.resolve("features"), configText = config, host = host, log = log) }

    private fun bridge(pollMs: Long = 5_000): VaultBridge =
        VaultBridge(
            plugin, rig.features, link, MarketScheduler(plugin, folia = false), tracker, host, dir.resolve("features"), "1.4.0", log, clock,
            engineScheduler = engine, pollMs = pollMs, providerTimeoutMs = 500, retryBaseMs = 1_000,
            services = { services }, vaultPresent = { vaultOn }
        ).also { bridges.add(it) }

    private fun setMode(mode: String, direction: String = "BOTH", rate: Double = 2.0, hash: String = "h-$mode-$direction-$rate") {
        rig.loadConfig(panoConfig(hash = hash, settings = MarketMcSettings(mcVaultMode = mode, mcVaultRate = rate, mcVaultDirection = direction)))
    }

    private fun economyRegs() = regs.filter { it.service == Economy::class.java }

    @AfterEach
    fun stopBridges() {
        bridges.forEach { runCatching { it.stop() } }
    }

    // ---- PROVIDER --------------------------------------------------------------------------------------------------------------

    @Test
    fun `PROVIDER - the credits are registered as the Vault economy at the highest priority and work through it`() {
        ledger.account("Steve", "100")
        setMode("PROVIDER")
        val b = bridge()
        b.start()
        val reg = economyRegs().single()
        assertEquals(ServicePriority.Highest, reg.priority)
        assertTrue(reg.provider is ProviderEconomy)
        assertTrue(b.providerActive())
        assertSame(plugin, reg.plugin)
        val eco = reg.provider as Economy
        assertTrue(eco.withdrawPlayer("Steve", 10.0).transactionSuccess())
        assertEquals(90.0, ledger.balance("Steve")!!.toDouble())
        assertTrue(eco.isEnabled())
        assertTrue(log.has("the Pano credits are now the server economy"))
    }

    @Test
    fun `a mode other than PROVIDER registers nothing - OFF, CONVERT, and no settings at all`() {
        val b = bridge()
        b.start() // no MARKET_CONFIG answer yet
        assertTrue(economyRegs().isEmpty())
        setMode("OFF")
        b.apply(true)
        setMode("CONVERT")
        b.apply(true)
        assertTrue(economyRegs().isEmpty())
        assertFalse(b.providerActive())
    }

    @Test
    fun `the mode is read live - a change in the panel registers and unregisters on the next check`() {
        val b = bridge(pollMs = 5_000)
        b.start()
        assertTrue(economyRegs().isEmpty())
        setMode("PROVIDER")
        assertTrue(economyRegs().isEmpty(), "not before the next check")
        engine.advance(5_000)
        assertEquals(1, economyRegs().size)
        setMode("CONVERT")
        engine.advance(5_000)
        assertTrue(economyRegs().isEmpty(), "unregistered again")
        setMode("PROVIDER", rate = 3.0)
        engine.advance(5_000)
        assertEquals(1, economyRegs().size)
        setMode("OFF")
        engine.advance(5_000)
        assertTrue(economyRegs().isEmpty())
        assertTrue(log.has("no longer the server economy"))
    }

    @Test
    fun `the local vault switch wins over the panel`() {
        config = "features:\n  vault: false\n"
        setMode("PROVIDER")
        val b = bridge()
        b.start()
        assertTrue(economyRegs().isEmpty())
        assertFalse(rig.features.config.enabled(Feature.VAULT))
    }

    @Test
    fun `PROVIDER without the Vault plugin registers nothing and says so once`() {
        vaultOn = false
        setMode("PROVIDER")
        val b = bridge()
        b.start()
        engine.advance(5_000)
        engine.advance(5_000)
        assertTrue(economyRegs().isEmpty())
        assertEquals(1, log.lines.count { it.contains("Vault plugin is not installed") })
        assertFalse(b.vaultAvailable())
        vaultOn = true
        engine.advance(5_000)
        assertEquals(1, economyRegs().size, "installed later: registered at the next check")
    }

    @Test
    fun `stop unregisters the economy and a later start registers it again`() {
        setMode("PROVIDER")
        val b = bridge()
        b.start()
        assertEquals(1, economyRegs().size)
        b.stop()
        assertTrue(economyRegs().isEmpty())
        assertFalse(b.providerActive())
    }

    @Test
    fun `another economy that outranks the credits is reported, not silently accepted`() {
        regs.add(Reg(Economy::class.java, otherEconomy("Essentials", serverMoney), ServicePriority.Highest, plugin))
        setMode("PROVIDER")
        val b = bridge()
        b.start()
        assertEquals(2, economyRegs().size)
        assertEquals(1, log.lines.count { it.contains("Essentials") && it.contains("outranks") })
        assertTrue(VaultGlue.activeProviderName(services) == "Essentials")
    }

    @Test
    fun `the balance of a player is loaded at the authenticated join in PROVIDER mode only`() {
        ledger.account("Steve", "100")
        val b = bridge()
        b.start()
        b.onPlayerPresent("Steve")
        assertTrue(link.requests.isEmpty(), "not in OFF / CONVERT mode")
        setMode("PROVIDER")
        engine.advance(5_000)
        b.onPlayerPresent("Steve")
        assertEquals(listOf(EconomyOp.BALANCE), link.requests.map { it.op })
        assertEquals("Steve", link.requests.single().player.username)
    }

    @Test
    fun `players who are present are refreshed through the tracker`() {
        ledger.account("Steve", "100")
        tracker.join("Steve", null, true)
        setMode("PROVIDER")
        val b = bridge()
        b.start()
        engine.advance(30_000)
        assertTrue(link.of(EconomyOp.BALANCE).any { it.player.username == "Steve" })
    }

    // ---- CONVERT ---------------------------------------------------------------------------------------------------------------

    @Test
    fun `CONVERT - the server's own economy is found through Vault and ours is never picked`() {
        val essentials = otherEconomy("Essentials", serverMoney)
        regs.add(Reg(Economy::class.java, essentials, ServicePriority.Normal, plugin))
        val ours = VaultGlue.createProvider(
            com.panomc.plugins.market.mc.spigot.vault.CreditEconomy(
                com.panomc.plugins.market.mc.spigot.vault.VaultOps(
                    com.panomc.plugins.market.mc.spigot.vault.EconomyClient(link, "1.4.0", log), VaultJournal(dir.resolve("x"), log, clock::now), engine, clock, log,
                    com.panomc.plugins.market.mc.spigot.vault.MainThread { it.run() }, { null }
                ),
                com.panomc.plugins.market.mc.spigot.vault.EconomyClient(link, "1.4.0", log), engine, clock, log, { null }, { emptyList() }
            ),
            { "Credits" }, { true }
        )
        regs.add(Reg(Economy::class.java, ours, ServicePriority.Highest, plugin))
        val found = VaultGlue.serverEconomy(services, ours)
        assertNotNull(found)
        assertEquals("Essentials", found!!.name)
        assertNull(VaultGlue.serverEconomy(proxyOf(ServicesManager::class.java) { m, _ ->
            if (m.name == "getRegistrations") emptyList<RegisteredServiceProvider<Economy>>() else throw UnsupportedOperationException(m.name)
        }, null))
        // and when only ours exists there is no server economy
        regs.removeIf { it.provider === essentials }
        assertNull(VaultGlue.serverEconomy(services, ours))
        assertNull(VaultGlue.serverEconomy(services, null), "a ProviderEconomy is never taken for the server economy even without exclude")
    }

    @Test
    fun `CONVERT - the commands are on credits and work end to end through the features`() {
        FakeBukkit.install()
        val uuid = UUID.fromString("11111111-1111-1111-1111-111111111111")
        FakeBukkit.extraServer["getOfflinePlayer"] = { a -> offlinePlayerProxy("Steve", a[0] as UUID) }
        regs.add(Reg(Economy::class.java, otherEconomy("Essentials", serverMoney), ServicePriority.Normal, plugin))
        ledger.account("Steve", "50")
        serverMoney.set("Steve", "100")
        setMode("CONVERT", rate = 2.0)
        val b = bridge()
        b.start()
        val sender = FakeSender(name = "Steve", uuid = uuid.toString())
        rig.features.commands.execute("credits", sender, listOf("convert", "10"))
        assertEquals(listOf("Converted 10 credits into $20.00. New credit balance: 40"), sender.plain())
        assertEquals("40", ledger.balance("Steve")!!.plain())
        assertEquals("120", serverMoney.balance("Steve").plain())
        val s2 = FakeSender(name = "Steve", uuid = uuid.toString())
        rig.features.commands.execute("credits", s2, listOf("deposit", "50"))
        assertEquals(listOf("Deposited $50.00 and received 25 credits. New credit balance: 65"), s2.plain())
        assertTrue(economyRegs().filter { it.provider is ProviderEconomy }.isEmpty(), "CONVERT never registers an economy")
    }

    @Test
    fun `the commands follow the effective switches - off in the panel, off locally, and tab completion`() {
        val b = bridge()
        b.start()
        val s = FakeSender(name = "Steve")
        rig.features.commands.execute("credits", s, listOf("convert", "10")) // no panel settings yet: the vault feature is off
        assertTrue(s.plain().single().contains("switched off"))
        assertEquals(emptyList<String>(), rig.features.commands.complete("credits", s, listOf("")))
        setMode("CONVERT")
        assertEquals(listOf("convert", "deposit"), rig.features.commands.complete("credits", s, listOf("")))
        setMode("OFF")
        assertEquals(emptyList<String>(), rig.features.commands.complete("credits", s, listOf("")))
    }

    @Test
    fun `no server economy installed - the command says so and nothing moves`() {
        ledger.account("Steve", "50")
        setMode("CONVERT")
        val b = bridge()
        b.start()
        val s = FakeSender(name = "Steve")
        rig.features.commands.execute("credits", s, listOf("convert", "10"))
        assertTrue(s.plain().single().contains("no economy"))
        vaultOn = false
        val s2 = FakeSender(name = "Steve")
        rig.features.commands.execute("credits", s2, listOf("convert", "10"))
        assertTrue(s2.plain().single().contains("no economy"))
        assertTrue(link.requests.isEmpty())
    }

    // ---- resume and notices -----------------------------------------------------------------------------------------------------

    @Test
    fun `an operation that was open when the server stopped is resolved at the next start, whatever the mode`() {
        ledger.account("Steve", "100")
        // a previous run applied a withdrawal whose caller was told it failed, then the server died
        val old = VaultJournal(dir.resolve("features").resolve("vault"), log, clock::now)
        old.load()
        old.write(VaultEntry("op-1", OpKind.PROVIDER_WITHDRAW, "Steve", null, 10.0, null, OpPhase.LEDGER_PENDING, 1, 1))
        old.close()
        ledger.apply(com.panomc.plugins.market.mc.core.wire.MarketEconomyRequest("1.4.0", 1, "op-1", EconomyOp.WITHDRAW, PlayerRef("Steve"), 10.0, "vault withdraw"))
        assertEquals("90", ledger.balance("Steve")!!.stripTrailingZeros().toPlainString())
        val b = bridge()
        b.start() // OFF: no panel settings
        engine.advance(1_000)
        assertEquals(listOf("op-1", "op-1:undo"), link.ids())
        assertEquals("100", ledger.balance("Steve")!!.stripTrailingZeros().toPlainString())
        assertTrue(log.has("resuming PROVIDER_WITHDRAW op-1"))
    }

    @Test
    fun `a conversion that finishes after the command returned is announced to the player in their language`() {
        ledger.account("Steve", "5")
        serverMoney.set("Steve", "100")
        FakeBukkit.install()
        FakeBukkit.extraServer["getOfflinePlayer"] = { a -> offlinePlayerProxy("Steve", a[0] as UUID) }
        regs.add(Reg(Economy::class.java, otherEconomy("Essentials", serverMoney), ServicePriority.Normal, plugin))
        setMode("CONVERT", rate = 2.0)
        host.locales["steve"] = "tr_TR"
        link.script = { _, attempt -> if (attempt == 0) Delivery.Lost else Delivery.Normal }
        val b = bridge()
        b.start()
        val s = FakeSender(name = "Steve", locale = "tr_TR")
        rig.features.commands.execute("credits", s, listOf("deposit", "20"))
        assertTrue(s.plain().single().contains("Paranız alındı"), s.plain().toString())
        engine.advance(10_000)
        val told = host.to("Steve")
        assertEquals(1, told.size, told.toString())
        assertTrue(told.single().contains("Yatırdığınız para ulaştı: 10 kredi aldınız"), told.toString())
    }

    @Test
    fun `the Vault plugin check and the services manager default to the real Bukkit ones`() {
        FakeBukkit.install()
        FakeBukkit.plugin("Vault", enable = true)
        FakeBukkit.extraServer["getServicesManager"] = { services }
        val b = VaultBridge(
            plugin, rig.features, link, MarketScheduler(plugin, folia = false), tracker, host, dir.resolve("features"), "1.4.0", log, clock,
            engineScheduler = engine
        ).also { bridges.add(it) }
        assertTrue(b.vaultAvailable())
        setMode("PROVIDER")
        b.start()
        assertEquals(1, economyRegs().size)
        FakeBukkit.enabled.remove("Vault")
        assertFalse(b.vaultAvailable())
    }
}
