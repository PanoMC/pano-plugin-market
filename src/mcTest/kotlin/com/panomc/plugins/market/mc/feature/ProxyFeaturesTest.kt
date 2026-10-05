package com.panomc.plugins.market.mc.feature

import com.panomc.plugins.market.mc.bungee.BungeeMessages
import com.panomc.plugins.market.mc.bungee.BungeeSender
import com.panomc.plugins.market.mc.bungee.MarketBungeeCommand
import com.panomc.plugins.market.mc.core.wire.MarketAdminMessage
import com.panomc.plugins.market.mc.core.wire.MarketMcSettings
import com.panomc.plugins.market.mc.core.wire.MarketQueryData
import com.panomc.plugins.market.mc.core.wire.MarketQueryMessage
import com.panomc.plugins.market.mc.link.proxyOf
import com.panomc.plugins.market.mc.velocity.MarketVelocityCommand
import com.panomc.plugins.market.mc.velocity.VelocityCommands
import com.panomc.plugins.market.mc.velocity.VelocityFeatureHost
import com.panomc.plugins.market.mc.velocity.VelocityMessages
import com.panomc.plugins.market.mc.velocity.VelocitySender
import com.velocitypowered.api.command.CommandManager
import com.velocitypowered.api.command.CommandMeta
import com.velocitypowered.api.command.CommandSource
import com.velocitypowered.api.command.SimpleCommand
import com.velocitypowered.api.proxy.ConsoleCommandSource
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent as AdventureClick
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.md_5.bungee.api.CommandSender
import net.md_5.bungee.api.chat.BaseComponent
import net.md_5.bungee.api.chat.ClickEvent
import net.md_5.bungee.api.connection.ProxiedPlayer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.Locale
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

class BungeeFeaturesTest {
    @TempDir
    lateinit var dir: Path

    private class Out {
        val components = CopyOnWriteArrayList<List<BaseComponent>>()
        fun plain() = components.map { c -> c.joinToString("") { it.toPlainText() } }
    }

    private fun capture(out: Out, a: Array<Any?>) {
        val first = a.firstOrNull()
        when (first) {
            is Array<*> -> out.components.add(first.filterIsInstance<BaseComponent>())
            is BaseComponent -> out.components.add(listOf(first))
            is String -> out.components.add(BungeeMessages.components(first, null).toList())
        }
    }

    private fun console(out: Out): CommandSender = proxyOf(CommandSender::class.java) { m, a ->
        when (m.name) {
            "getName" -> "CONSOLE"
            "sendMessage" -> { capture(out, a); null }
            "hasPermission" -> false
            else -> throw UnsupportedOperationException("CommandSender.${m.name}")
        }
    }

    private fun player(out: Out, name: String = "Steve", nodes: Set<String> = emptySet(), locale: Locale? = Locale.forLanguageTag("tr-TR")): ProxiedPlayer =
        proxyOf(ProxiedPlayer::class.java) { m, a ->
            when (m.name) {
                "getName" -> name
                "getUniqueId" -> UUID.nameUUIDFromBytes(name.toByteArray())
                "getLocale" -> locale
                "sendMessage" -> { capture(out, a); null }
                "hasPermission" -> a[0] in nodes
                else -> throw UnsupportedOperationException("ProxiedPlayer.${m.name}")
            }
        }

    private fun wired(): FeatureRig = FeatureRig(dir).also { it.loadConfig(panoConfig("h1", MarketMcSettings())) }

    @Test
    fun `the sender wrapper tells console from player, passes nodes and the client language`() {
        val out = Out()
        val p = BungeeSender(player(out, "Steve", setOf("panomarket.admin.grant")))
        assertFalse(p.isConsole)
        assertEquals("Steve", p.name)
        assertEquals(UUID.nameUUIDFromBytes("Steve".toByteArray()).toString(), p.uuid)
        assertTrue(p.hasPermission("panomarket.admin.grant"))
        assertFalse(p.hasPermission("panomarket.admin.credits.give"))
        assertEquals("tr-TR", p.locale)
        val theConsole = console(out)
        val c = BungeeSender(theConsole) { theConsole }
        assertTrue(c.isConsole)
        assertTrue(c.supported)
        assertNull(c.uuid)
        assertNull(c.locale)
        assertTrue(c.hasPermission("anything"))
    }

    @Test
    fun `a link line carries a click event for http and https only`() {
        val parts = BungeeMessages.components("§6Store: §bhttps://shop.example.com", "https://shop.example.com")
        assertTrue(parts.isNotEmpty())
        parts.forEach {
            assertEquals(ClickEvent.Action.OPEN_URL, it.clickEvent.action)
            assertEquals("https://shop.example.com", it.clickEvent.value)
        }
        BungeeMessages.components("x", "javascript:alert(1)").forEach { assertNull(it.clickEvent) }
        BungeeMessages.components("x", null).forEach { assertNull(it.clickEvent) }
    }

    @Test
    fun `the Bungee command runs the feature logic in the sender's language`() {
        val rig = wired()
        val cmd = MarketBungeeCommand("store", "store", listOf("buy"), rig.features) { true }
        assertEquals("store", cmd.name)
        assertEquals(listOf("buy"), cmd.aliases.toList())
        val out = Out()
        cmd.execute(player(out), arrayOf())
        assertEquals(listOf("Mağaza: https://shop.example.com"), out.plain())
        assertTrue(out.components.single().all { it.clickEvent?.value == "https://shop.example.com" })
    }

    @Test
    fun `the Bungee command checks the admin node and sends the console as console`() {
        val rig = wired()
        rig.link.handler = { MarketAdminMessage(true, null, true, null, 9.0) }
        val theConsole = console(Out())
        val cmd = MarketBungeeCommand("panomarket", "panomarket", emptyList(), rig.features, { theConsole }) { true }
        val denied = Out()
        cmd.execute(player(denied, "Mod", locale = null), arrayOf("credits", "give", "Alex", "5"))
        assertEquals(listOf("You do not have permission to use this command."), denied.plain())
        assertTrue(rig.link.requests.isEmpty())
        val ok = Out()
        val consoleWithOut = console(ok)
        MarketBungeeCommand("panomarket", "panomarket", emptyList(), rig.features, { consoleWithOut }) { true }
            .execute(consoleWithOut, arrayOf("credits", "give", "Alex", "5"))
        assertEquals(listOf("Gave 5 credits to Alex. New balance: 9"), ok.plain())
        val req = rig.link.requests.single() as com.panomc.plugins.market.mc.core.wire.MarketAdminRequest
        assertTrue(req.actor.console)
    }

    @Test
    fun `a sender that is neither a player nor the proxy console sends nothing over the link`() {
        val rig = wired()
        rig.link.handler = { MarketAdminMessage(true, null, true, "ORD-9", 25.0) }
        val realConsole = console(Out())
        val cmd = MarketBungeeCommand("panomarket", "panomarket", emptyList(), rig.features, { realConsole }) { true }
        val out = Out()
        val foreign = console(out) // another CommandSender implementation (a plugin's): not proxy.console
        assertFalse(BungeeSender(foreign) { realConsole }.isConsole)
        assertFalse(BungeeSender(foreign) { realConsole }.supported)
        assertFalse(BungeeSender(foreign) { null }.isConsole, "no proxy console known: nobody is the console")
        for (args in listOf(
            arrayOf("credits", "give", "Alex", "5"), arrayOf("credits", "take", "Alex", "5"), arrayOf("credits", "set", "Alex", "5"),
            arrayOf("grant", "Alex", "12", "1"), arrayOf("purchases", "Alex"), arrayOf("recover", "confirm")
        )) cmd.execute(foreign, args)
        assertEquals(6, out.plain().size)
        assertTrue(out.plain().all { it == "You do not have permission to use this command." }, out.plain().toString())
        assertTrue(rig.link.requests.isEmpty())
        assertEquals(0, rig.control.confirmed)
        assertEquals(emptyList<String>(), cmd.onTabComplete(foreign, arrayOf("")).toList())
    }

    @Test
    fun `tab completion and a stale command after unregister`() {
        val rig = wired()
        var active = true
        val cmd = MarketBungeeCommand("store", "store", emptyList(), rig.features) { active }
        assertEquals(listOf("history"), cmd.onTabComplete(player(Out(), locale = null), arrayOf("")).toList())
        active = false
        assertEquals(emptyList<String>(), cmd.onTabComplete(player(Out(), locale = null), arrayOf("")).toList())
        val out = Out()
        cmd.execute(player(out, locale = null), arrayOf())
        assertEquals(listOf("The store is not available right now."), out.plain())
    }

    @Test
    fun `the Bungee credits command reaches Pano`() {
        val rig = wired()
        rig.link.handler = { MarketQueryMessage(true, null, MarketQueryData(registered = true, balance = 3.0, creditName = "Credits")) }
        val out = Out()
        MarketBungeeCommand("credits", "credits", emptyList(), rig.features) { true }.execute(player(out, locale = null), arrayOf())
        assertEquals(listOf("Your balance: 3 Credits"), out.plain())
    }
}

class VelocityFeaturesTest {
    @TempDir
    lateinit var dir: Path

    private val plain = PlainTextComponentSerializer.plainText()

    private class Out {
        val components = CopyOnWriteArrayList<Component>()
    }

    private fun capture(out: Out, a: Array<Any?>) {
        a.filterIsInstance<Component>().firstOrNull()?.let { out.components.add(it) }
    }

    private fun console(out: Out): ConsoleCommandSource = proxyOf(ConsoleCommandSource::class.java) { m, a ->
        when (m.name) {
            "sendMessage" -> { capture(out, a); null }
            "hasPermission" -> false
            else -> throw UnsupportedOperationException("ConsoleCommandSource.${m.name}")
        }
    }

    private fun player(out: Out, name: String = "Steve", nodes: Set<String> = emptySet(), locale: Locale? = Locale.forLanguageTag("ru-RU")): Player =
        proxyOf(Player::class.java) { m, a ->
            when (m.name) {
                "getUsername" -> name
                "getUniqueId" -> UUID.nameUUIDFromBytes(name.toByteArray())
                "getEffectiveLocale" -> locale
                "sendMessage" -> { capture(out, a); null }
                "hasPermission" -> a[0] in nodes
                else -> throw UnsupportedOperationException("Player.${m.name}")
            }
        }

    private fun invocation(source: CommandSource, vararg args: String): SimpleCommand.Invocation = proxyOf(SimpleCommand.Invocation::class.java) { m, _ ->
        when (m.name) {
            "source" -> source
            "arguments" -> arrayOf(*args)
            "alias" -> "store"
            else -> throw UnsupportedOperationException("Invocation.${m.name}")
        }
    }

    private fun wired(): FeatureRig = FeatureRig(dir).also { it.loadConfig(panoConfig("h1", MarketMcSettings())) }

    @Test
    fun `the sender wrapper tells console from player and passes nodes and language`() {
        val out = Out()
        val p = VelocitySender(player(out, "Steve", setOf("panomarket.admin.grant")))
        assertFalse(p.isConsole)
        assertEquals("Steve", p.name)
        assertEquals(UUID.nameUUIDFromBytes("Steve".toByteArray()).toString(), p.uuid)
        assertTrue(p.hasPermission("panomarket.admin.grant"))
        assertFalse(p.hasPermission("panomarket.admin.credits.give"))
        assertEquals("ru-RU", p.locale)
        val c = VelocitySender(console(out))
        assertTrue(c.isConsole)
        assertTrue(c.supported)
        assertEquals("CONSOLE", c.name)
        assertNull(c.uuid)
        assertNull(c.locale)
        assertTrue(c.hasPermission("anything"))
    }

    @Test
    fun `a link line carries an open-url click event for http and https only`() {
        val c = VelocityMessages.component("§6Store: §bhttps://shop.example.com", "https://shop.example.com")
        assertEquals("Store: https://shop.example.com", plain.serialize(c))
        assertEquals(AdventureClick.Action.OPEN_URL, c.clickEvent()!!.action())
        assertEquals("https://shop.example.com", c.clickEvent()!!.value())
        assertNull(VelocityMessages.component("x", "javascript:alert(1)").clickEvent())
        assertNull(VelocityMessages.component("x", null).clickEvent())
    }

    @Test
    fun `the Velocity command runs the feature logic in the sender's language`() {
        val rig = wired()
        val out = Out()
        MarketVelocityCommand("store", rig.features) { true }.execute(invocation(player(out)))
        assertEquals(listOf("Магазин: https://shop.example.com"), out.components.map { plain.serialize(it) })
        assertEquals("https://shop.example.com", out.components.single().clickEvent()!!.value())
    }

    @Test
    fun `the Velocity command checks the admin node and sends the console as console`() {
        val rig = wired()
        rig.link.handler = { MarketAdminMessage(true, null, true, null, 9.0) }
        val cmd = MarketVelocityCommand("panomarket", rig.features) { true }
        val denied = Out()
        cmd.execute(invocation(player(denied, "Mod", locale = null), "credits", "give", "Alex", "5"))
        assertEquals(listOf("You do not have permission to use this command."), denied.components.map { plain.serialize(it) })
        assertTrue(rig.link.requests.isEmpty())
        val ok = Out()
        cmd.execute(invocation(console(ok), "credits", "give", "Alex", "5"))
        assertEquals(listOf("Gave 5 credits to Alex. New balance: 9"), ok.components.map { plain.serialize(it) })
        assertTrue((rig.link.requests.single() as com.panomc.plugins.market.mc.core.wire.MarketAdminRequest).actor.console)
    }

    @Test
    fun `a source that is neither a player nor the console sends nothing over the link`() {
        val rig = wired()
        rig.link.handler = { MarketAdminMessage(true, null, true, "ORD-9", 25.0) }
        val cmd = MarketVelocityCommand("panomarket", rig.features) { true }
        val out = Out()
        val foreign: CommandSource = proxyOf(CommandSource::class.java) { m, a ->
            when (m.name) {
                "sendMessage" -> { capture(out, a); null }
                "hasPermission" -> true // even a source that claims every node is not the console
                else -> throw UnsupportedOperationException("CommandSource.${m.name}")
            }
        }
        assertFalse(VelocitySender(foreign).isConsole)
        assertFalse(VelocitySender(foreign).supported)
        for (args in listOf(
            arrayOf("credits", "give", "Alex", "5"), arrayOf("credits", "take", "Alex", "5"), arrayOf("credits", "set", "Alex", "5"),
            arrayOf("grant", "Alex", "12", "1"), arrayOf("purchases", "Alex"), arrayOf("recover", "confirm")
        )) cmd.execute(invocation(foreign, *args))
        assertEquals(6, out.components.size)
        assertTrue(out.components.all { plain.serialize(it) == "You do not have permission to use this command." })
        assertTrue(rig.link.requests.isEmpty())
        assertEquals(0, rig.control.confirmed)
        assertEquals(emptyList<String>(), cmd.suggest(invocation(foreign, "")))
    }

    @Test
    fun `tab completion and a stale command after unregister`() {
        val rig = wired()
        var active = true
        val cmd = MarketVelocityCommand("store", rig.features) { active }
        assertEquals(listOf("history"), cmd.suggest(invocation(player(Out(), locale = null), "")))
        active = false
        assertEquals(emptyList<String>(), cmd.suggest(invocation(player(Out(), locale = null), "")))
        val out = Out()
        cmd.execute(invocation(player(out, locale = null)))
        assertEquals(listOf("The store is not available right now."), out.components.map { plain.serialize(it) })
    }

    @Test
    fun `the host sends to one player and to everybody including the console`() {
        val steveOut = Out()
        val alexOut = Out()
        val consoleOut = Out()
        val steve = player(steveOut, "Steve")
        val alex = player(alexOut, "Alex")
        val server = proxyOf(ProxyServer::class.java) { m, a ->
            when (m.name) {
                "getPlayer" -> if (a[0] is String) Optional.ofNullable(mapOf("steve" to steve, "alex" to alex)[(a[0] as String).lowercase()]) else Optional.empty<Player>()
                "getAllPlayers" -> listOf(steve, alex)
                "getConsoleCommandSource" -> console(consoleOut)
                else -> throw UnsupportedOperationException("ProxyServer.${m.name}")
            }
        }
        val host = VelocityFeatureHost(server)
        host.sendTo("STEVE", "§ahello", null)
        host.sendTo("nobody", "lost", null)
        assertEquals(listOf("hello"), steveOut.components.map { plain.serialize(it) })
        assertTrue(alexOut.components.isEmpty())
        host.broadcast("§6Alex bought Coal")
        assertEquals(listOf("hello", "Alex bought Coal"), steveOut.components.map { plain.serialize(it) })
        assertEquals(listOf("Alex bought Coal"), alexOut.components.map { plain.serialize(it) })
        assertEquals(listOf("Alex bought Coal"), consoleOut.components.map { plain.serialize(it) }, "the console sees broadcasts too")
        assertEquals("ru-RU", host.localeOf("Steve"))
        assertNull(host.localeOf("nobody"))
    }

    @Test
    fun `the commands are registered with their aliases and unregistered again`() {
        val rig = FeatureRig(dir, "command-aliases:\n  store: [buy, shop]\n  credits: [bal]\n")
        val registered = CopyOnWriteArrayList<List<String>>()
        val unregistered = CopyOnWriteArrayList<String>()
        val aliasesOf = HashMap<Any, List<String>>()
        val commandManager = proxyOf(CommandManager::class.java) { m, a ->
            when (m.name) {
                "metaBuilder" -> {
                    var primary = a[0] as String
                    var aliases = emptyList<String>()
                    lateinit var builder: CommandMeta.Builder
                    builder = proxyOf(CommandMeta.Builder::class.java) { bm, ba ->
                        when (bm.name) {
                            "aliases" -> { aliases = (ba[0] as Array<*>).map { it as String }; builder }
                            "plugin" -> builder
                            "build" -> proxyOf(CommandMeta::class.java) { _, _ -> throw UnsupportedOperationException() }.also { aliasesOf[it] = listOf(primary) + aliases }
                            else -> throw UnsupportedOperationException("Builder.${bm.name}")
                        }
                    }
                    builder
                }
                "register" -> { registered.add(aliasesOf[a[0]]!!); null }
                "unregister" -> { unregistered.add(a[0] as String); null }
                else -> throw UnsupportedOperationException("CommandManager.${m.name}")
            }
        }
        val server = proxyOf(ProxyServer::class.java) { m, _ ->
            if (m.name == "getCommandManager") commandManager else throw UnsupportedOperationException("ProxyServer.${m.name}")
        }
        val commands = VelocityCommands(server, Any(), rig.features)
        commands.register()
        assertEquals(listOf(listOf("store", "buy", "shop"), listOf("credits", "bal"), listOf("panomarket")), registered.toList())
        commands.unregister()
        assertEquals(listOf("store", "buy", "shop", "credits", "bal", "panomarket"), unregistered.toList())
    }
}
