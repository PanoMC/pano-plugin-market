package com.panomc.plugins.market.mc.feature

import com.panomc.plugins.market.mc.core.feature.MarketCommands
import com.panomc.plugins.market.mc.core.wire.MarketAdminMessage
import com.panomc.plugins.market.mc.core.wire.MarketMcSettings
import com.panomc.plugins.market.mc.core.wire.MarketQueryData
import com.panomc.plugins.market.mc.core.wire.MarketQueryMessage
import com.panomc.plugins.market.mc.link.proxyOf
import com.panomc.plugins.market.mc.spigot.FakeBukkit
import com.panomc.plugins.market.mc.spigot.MarketBukkitCommand
import com.panomc.plugins.market.mc.spigot.MarketScheduler
import com.panomc.plugins.market.mc.spigot.SpigotCommands
import com.panomc.plugins.market.mc.spigot.SpigotFeatureHost
import com.panomc.plugins.market.mc.spigot.SpigotMessages
import com.panomc.plugins.market.mc.spigot.SpigotSender
import net.md_5.bungee.api.chat.BaseComponent
import net.md_5.bungee.api.chat.ClickEvent
import org.bukkit.command.Command
import org.bukkit.command.CommandMap
import org.bukkit.command.BlockCommandSender
import org.bukkit.command.CommandSender
import org.bukkit.command.ConsoleCommandSender
import org.bukkit.command.RemoteConsoleCommandSender
import org.bukkit.entity.Player
import org.bukkit.permissions.Permission
import org.bukkit.permissions.PermissionDefault
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** The Spigot glue of the features: command registration, sender wrapper, chat output, through the fake Bukkit server. */
class SpigotFeaturesTest {
    @TempDir
    lateinit var dir: Path

    private val registered = CopyOnWriteArrayList<Pair<String, Command>>()
    private val permissions = CopyOnWriteArrayList<Permission>()
    private val removedPermissions = CopyOnWriteArrayList<Permission>()
    private val broadcasts = CopyOnWriteArrayList<String>()
    private val onlinePlayers = HashMap<String, Player>()

    private val map: CommandMap = proxyOf(CommandMap::class.java) { m, a ->
        when (m.name) {
            "register" -> {
                registered.add((a[0] as String) to (a[a.size - 1] as Command))
                true
            }
            else -> throw UnsupportedOperationException("CommandMap.${m.name}")
        }
    }

    @BeforeEach
    fun setUp() {
        FakeBukkit.install()
        FakeBukkit.extraPluginManager["addPermission"] = { a -> permissions.add(a[0] as Permission); null }
        FakeBukkit.extraPluginManager["recalculatePermissionDefaults"] = { null }
        FakeBukkit.extraPluginManager["getPermissionSubscriptions"] = { emptySet<Any>() }
        FakeBukkit.extraPluginManager["removePermission"] = { a -> removedPermissions.add(a[0] as Permission); null }
        FakeBukkit.extraServer["getPlayerExact"] = { a -> onlinePlayers[(a[0] as String).lowercase()] }
        FakeBukkit.extraServer["broadcastMessage"] = { a -> broadcasts.add(a[0] as String); 1 }
    }

    @AfterEach
    fun tearDown() {
        FakeBukkit.reset()
    }

    private class Wired(val rig: FeatureRig, val host: SpigotFeatureHost, val commands: SpigotCommands)

    private fun wire(config: String? = null, panel: MarketMcSettings? = MarketMcSettings()): Wired {
        val plugin = FakeBukkit.plugin("PanoMarket")
        val log = CollectingLog()
        val host = SpigotFeatureHost(MarketScheduler(plugin, false), log)
        val rig = FeatureRig(dir, config, log = log, featureHost = host)
        if (panel != null) rig.loadConfig(panoConfig("h1", panel))
        val commands = SpigotCommands(plugin, rig.features, host, log) { map }
        commands.register()
        return Wired(rig, host, commands)
    }

    private fun command(name: String): MarketBukkitCommand = registered.map { it.second }.filterIsInstance<MarketBukkitCommand>().first { it.name == name }

    private class Recorder {
        val lines = CopyOnWriteArrayList<String>()
        val threads = CopyOnWriteArrayList<String>()
        fun record(text: String) {
            lines.add(text)
            threads.add(Thread.currentThread().name)
        }

        fun plain() = lines.map { it.replace(Regex("§."), "") }
    }

    private fun sender(rec: Recorder, name: String = "CONSOLE", nodes: Set<String> = emptySet(), player: Boolean = false, spigot: Any? = null): CommandSender {
        val uuid = UUID.nameUUIDFromBytes(name.toByteArray())
        if (!player) {
            return senderOf(ConsoleCommandSender::class.java, rec, name, nodes)
        }
        return proxyOf(Player::class.java) { m, a ->
            when (m.name) {
                "getName" -> name
                "getUniqueId" -> uuid
                "isOnline" -> true
                "sendMessage" -> { if (a[0] is String) rec.record(a[0] as String) else (a[0] as Array<*>).forEach { rec.record(it as String) }; null }
                "hasPermission" -> a[0] in nodes
                "spigot" -> spigot ?: throw UnsupportedOperationException("no chat API")
                else -> throw UnsupportedOperationException("Player.${m.name}")
            }
        }
    }

    /** A non-player sender of the given Bukkit kind (console, command block, RCON, a plain `CommandSender`). */
    private fun <T : CommandSender> senderOf(type: Class<T>, rec: Recorder, name: String, nodes: Set<String> = emptySet()): T = proxyOf(type) { m, a ->
        when (m.name) {
            "getName" -> name
            "sendMessage" -> { if (a[0] is String) rec.record(a[0] as String) else (a[0] as Array<*>).forEach { rec.record(it as String) }; null }
            "hasPermission" -> a[0] in nodes
            else -> throw UnsupportedOperationException("${type.simpleName}.${m.name}")
        }
    }

    private fun await(what: String, timeoutMs: Long = 3_000, condition: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < end) {
            if (condition()) return
            Thread.sleep(5)
        }
        throw AssertionError("timed out waiting for: $what")
    }

    @Test
    fun `the three commands are registered with their aliases and the admin nodes default to operators`() {
        val w = wire(config = "command-aliases:\n  store: [buy, shop]\n  credits: [bal]\n  panomarket: [pm]\n")
        assertEquals(listOf("panomarket", "panomarket", "panomarket"), registered.map { it.first })
        assertEquals(listOf("store", "credits", "panomarket"), registered.map { it.second.name })
        assertEquals(listOf("buy", "shop"), command("store").aliases)
        assertEquals(listOf("bal"), command("credits").aliases)
        assertEquals(listOf("pm"), command("panomarket").aliases)
        assertEquals(MarketCommands.NODES, permissions.map { it.name }, w.rig.log.lines.toString())
        assertTrue(permissions.all { it.default == PermissionDefault.OP })
    }

    @Test
    fun `the default aliases give store its buy alias`() {
        wire()
        assertEquals(listOf("buy"), command("store").aliases)
        assertEquals(emptyList<String>(), command("credits").aliases)
    }

    @Test
    fun `a command runs the feature logic and the reply is delivered on the server main thread`() {
        wire()
        val rec = Recorder()
        assertTrue(command("store").execute(sender(rec, "Steve", player = true), "store", arrayOf()))
        await("the reply") { rec.lines.isNotEmpty() }
        assertEquals(listOf("Store: https://shop.example.com"), rec.plain())
        assertEquals(listOf("fake-main"), rec.threads, "chat output goes through the scheduler wrapper")
    }

    @Test
    fun `a clickable link is built with the Spigot chat API and falls back to plain text without it`() {
        wire()
        val sent = CopyOnWriteArrayList<Array<out BaseComponent>>()
        val spigot = object : Player.Spigot() {
            override fun sendMessage(component: BaseComponent) {
                sent.add(arrayOf(component))
            }

            override fun sendMessage(vararg components: BaseComponent) {
                sent.add(components)
            }
        }
        val rec = Recorder()
        command("store").execute(sender(rec, "Steve", player = true, spigot = spigot), "store", arrayOf())
        await("the clickable line") { sent.isNotEmpty() }
        val parts = sent.single()
        assertTrue(parts.isNotEmpty())
        parts.forEach {
            assertEquals(ClickEvent.Action.OPEN_URL, it.clickEvent.action)
            assertEquals("https://shop.example.com", it.clickEvent.value)
        }
        assertEquals("Store: https://shop.example.com", parts.joinToString("") { it.toPlainText() })
        assertTrue(rec.lines.isEmpty(), "no duplicate plain line")

        val plain = Recorder()
        command("store").execute(sender(plain, "Alex", player = true), "store", arrayOf())
        await("the plain fallback") { plain.lines.isNotEmpty() }
        assertEquals(listOf("Store: https://shop.example.com"), plain.plain())
    }

    @Test
    fun `a non-web link is never made clickable`() {
        val sent = CopyOnWriteArrayList<Array<out BaseComponent>>()
        val spigot = object : Player.Spigot() {
            override fun sendMessage(vararg components: BaseComponent) {
                sent.add(components)
            }
        }
        val rec = Recorder()
        SpigotMessages.send(sender(rec, "Steve", player = true, spigot = spigot), "x", "javascript:alert(1)")
        assertTrue(sent.isEmpty())
        assertEquals(listOf("x"), rec.lines)
        val console = Recorder()
        SpigotMessages.send(sender(console), "y", "https://ok.example")
        assertEquals(listOf("y"), console.lines, "the console gets text")
    }

    @Test
    fun `the sender wrapper tells console from player and passes permission nodes through`() {
        val w = wire()
        val rec = Recorder()
        val player = SpigotSender(sender(rec, "Steve", setOf("panomarket.admin.grant"), player = true), w.host)
        val console = SpigotSender(sender(rec), w.host)
        assertFalse(player.isConsole)
        assertEquals("Steve", player.name)
        assertEquals(UUID.nameUUIDFromBytes("Steve".toByteArray()).toString(), player.uuid)
        assertTrue(player.hasPermission("panomarket.admin.grant"))
        assertFalse(player.hasPermission("panomarket.admin.credits.give"))
        assertTrue(console.isConsole)
        assertNull(console.uuid)
        assertTrue(console.hasPermission("anything"), "the console has every node")
        w.host.remember("Steve", "tr_TR")
        assertEquals("tr_TR", player.locale)
        assertNull(console.locale)
        w.host.forget("Steve")
        assertNull(player.locale)
    }

    @Test
    fun `locales are read reflectively where the server has Player getLocale`() {
        class WithLocale { @Suppress("unused") fun getLocale() = "ru_RU" }
        assertEquals("ru_RU", SpigotMessages.localeOf(WithLocale()))
        assertNull(SpigotMessages.localeOf(Any()))
    }

    @Test
    fun `admin commands from a player need the node here and Pano's permission there`() {
        val w = wire()
        w.rig.link.handler = { MarketAdminMessage(true, null, true, null, 25.0) }
        val denied = Recorder()
        command("panomarket").execute(sender(denied, "Mod", player = true), "panomarket", arrayOf("credits", "give", "Alex", "5"))
        await("denied") { denied.lines.isNotEmpty() }
        assertEquals(listOf("You do not have permission to use this command."), denied.plain())
        assertTrue(w.rig.link.requests.isEmpty())

        val ok = Recorder()
        command("panomarket").execute(sender(ok, "Admin", setOf("panomarket.admin.credits.give"), player = true), "panomarket", arrayOf("credits", "give", "Alex", "5"))
        await("ok") { ok.lines.isNotEmpty() }
        assertEquals(listOf("Gave 5 credits to Alex. New balance: 25"), ok.plain())
        assertEquals(1, w.rig.link.requests.size)
    }

    @Test
    fun `credits through the Bukkit command reaches Pano and answers`() {
        val w = wire()
        w.rig.link.handler = { MarketQueryMessage(true, null, MarketQueryData(registered = true, balance = 7.0, creditName = "Credits")) }
        val rec = Recorder()
        command("credits").execute(sender(rec, "Steve", player = true), "credits", arrayOf())
        await("balance") { rec.lines.isNotEmpty() }
        assertEquals(listOf("Your balance: 7 Credits"), rec.plain())
    }

    @Test
    fun `tab completion goes through the same permission rules`() {
        val w = wire()
        val rec = Recorder()
        assertEquals(listOf("history"), command("store").tabComplete(sender(rec, "Steve", player = true), "store", arrayOf("")))
        assertEquals(emptyList<String>(), command("panomarket").tabComplete(sender(rec, "Steve", player = true), "panomarket", arrayOf("")))
        assertEquals(listOf("grant"), command("panomarket").tabComplete(sender(rec, "Admin", setOf("panomarket.admin.grant"), player = true), "panomarket", arrayOf("g")))
        assertEquals(listOf("credits", "grant", "purchases", "recover", "status"), command("panomarket").tabComplete(sender(rec), "panomarket", arrayOf("")))
        assertTrue(w.commands.javaClass.name.isNotEmpty())
    }

    @Test
    fun `after unregister a stale command says it is not available and the permissions are removed`() {
        val w = wire()
        val stale = command("store")
        w.commands.unregister()
        val rec = Recorder()
        stale.execute(sender(rec, "Steve", player = true), "store", arrayOf())
        await("unavailable") { rec.lines.isNotEmpty() }
        assertEquals(listOf("The store is not available right now."), rec.plain())
        assertEquals(emptyList<String>(), stale.tabComplete(sender(rec), "store", arrayOf("")))
        assertEquals(MarketCommands.NODES, removedPermissions.map { it.name })
    }

    @Test
    fun `a server whose command map cannot be reached registers nothing but still declares the nodes`() {
        val plugin = FakeBukkit.plugin("PanoMarket")
        val log = CollectingLog()
        val rig = FeatureRig(dir, null, log = log)
        val host = SpigotFeatureHost(MarketScheduler(plugin, false), log)
        SpigotCommands(plugin, rig.features, host, log) { null }.register()
        assertTrue(registered.isEmpty())
        assertTrue(log.has("command map"))
        assertNull(SpigotCommands.reflectCommandMap(), "the fake server is not a CraftServer")
    }

    @Test
    fun `the host sends to one online player and broadcasts on the server thread`() {
        val w = wire()
        val rec = Recorder()
        onlinePlayers["steve"] = sender(rec, "Steve", player = true) as Player
        w.host.sendTo("STEVE", "§ahi", null)
        w.host.sendTo("nobody", "lost", null)
        await("direct message") { rec.lines.isNotEmpty() }
        assertEquals(listOf("§ahi"), rec.lines)
        assertEquals(listOf("fake-main"), rec.threads)
        w.host.broadcast("§6Alex bought Coal")
        await("broadcast") { broadcasts.isNotEmpty() }
        assertEquals(listOf("§6Alex bought Coal"), broadcasts)
    }

    @Test
    fun `a scheduler that refuses (plugin disabled) never throws into the engine`() {
        val w = wire()
        FakeBukkit.refuseTasks = IllegalStateException("Plugin attempted to register task while disabled")
        w.host.broadcast("x")
        w.host.sendTo("Steve", "x", null)
        assertTrue(broadcasts.isEmpty())
        FakeBukkit.refuseTasks = null
    }

    @Test
    fun `the engine's broadcast callback reaches Bukkit chat through the host`() {
        val w = wire()
        w.rig.features.callbacks.showBroadcast("&aSteve &7bought &fDiamonds")
        await("broadcast") { broadcasts.isNotEmpty() }
        assertEquals(listOf("§aSteve §7bought §fDiamonds"), broadcasts)
    }

    // ---- the console is a positive test (review fix: command blocks and other senders are not the console) ------------

    private val moneyOps = listOf(
        arrayOf("credits", "give", "Alex", "5"), arrayOf("credits", "take", "Alex", "5"), arrayOf("credits", "set", "Alex", "5"),
        arrayOf("grant", "Alex", "12", "1"), arrayOf("purchases", "Alex"), arrayOf("recover", "confirm"), arrayOf("recover"), arrayOf("status")
    )

    private fun foreignSenders(rec: Recorder): Map<String, CommandSender> = mapOf(
        "BlockCommandSender" to senderOf(BlockCommandSender::class.java, rec, "@"),
        "plain non-player sender (entity through /execute as, minecart, ProxiedCommandSender)" to senderOf(CommandSender::class.java, rec, "Zombie"),
        "op-like sender holding every node" to senderOf(CommandSender::class.java, rec, "Op", MarketCommands.NODES.toSet())
    )

    @Test
    fun `a command block or any other non-player non-console sender sends nothing over the link`() {
        val w = wire()
        w.rig.link.handler = { MarketAdminMessage(true, null, true, "ORD-9", 25.0) }
        for ((label, _) in foreignSenders(Recorder())) {
            val rec = Recorder()
            val foreign = foreignSenders(rec).getValue(label)
            for (args in moneyOps) {
                command("panomarket").execute(foreign, "panomarket", args)
            }
            await("$label refusals") { rec.lines.size >= moneyOps.size }
            assertEquals(moneyOps.size, rec.lines.size, label)
            assertTrue(rec.plain().all { it == "You do not have permission to use this command." }, "$label: ${rec.plain()}")
            assertTrue(w.rig.link.requests.isEmpty(), "$label reached Pano: ${w.rig.link.requests}")
            assertEquals(0, w.rig.control.confirmed, "$label confirmed a recovery")
            assertEquals(0, w.rig.control.syncs, label)
            assertEquals(emptyList<String>(), command("panomarket").tabComplete(foreign, "panomarket", arrayOf("")), label)
        }
    }

    @Test
    fun `a command block cannot run the store and credits commands either`() {
        val w = wire()
        val rec = Recorder()
        val block = senderOf(BlockCommandSender::class.java, rec, "@")
        command("store").execute(block, "store", arrayOf("history"))
        command("credits").execute(block, "credits", arrayOf())
        await("refusals") { rec.lines.size == 2 }
        assertTrue(w.rig.link.requests.isEmpty())
    }

    @Test
    fun `the real console and RCON still run the admin commands as the console actor`() {
        val w = wire()
        w.rig.link.handler = { MarketAdminMessage(true, null, true, null, 9.0) }
        val rec = Recorder()
        command("panomarket").execute(senderOf(ConsoleCommandSender::class.java, rec, "CONSOLE"), "panomarket", arrayOf("credits", "give", "Alex", "5"))
        command("panomarket").execute(senderOf(RemoteConsoleCommandSender::class.java, rec, "Rcon"), "panomarket", arrayOf("credits", "give", "Alex", "5"))
        await("both answers") { rec.lines.size == 2 }
        val requests = w.rig.link.requests.map { it as com.panomc.plugins.market.mc.core.wire.MarketAdminRequest }
        assertEquals(2, requests.size)
        assertTrue(requests.all { it.actor.console })
        assertTrue(SpigotSender(senderOf(ConsoleCommandSender::class.java, rec, "CONSOLE"), w.host).isConsole)
        assertTrue(SpigotSender(senderOf(RemoteConsoleCommandSender::class.java, rec, "Rcon"), w.host).isConsole)
        val block = SpigotSender(senderOf(BlockCommandSender::class.java, rec, "@"), w.host)
        assertFalse(block.isConsole)
        assertFalse(block.supported)
    }
}
