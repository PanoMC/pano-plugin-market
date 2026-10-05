package com.panomc.plugins.market.mc.feature

import com.panomc.plugins.market.mc.core.feature.Feature
import com.panomc.plugins.market.mc.core.feature.MarketCommands
import com.panomc.plugins.market.mc.core.sync.EngineStatus
import com.panomc.plugins.market.mc.core.sync.RecoveryPreview
import com.panomc.plugins.market.mc.core.sync.RuntimeStatus
import com.panomc.plugins.market.mc.core.wire.AdminOp
import com.panomc.plugins.market.mc.core.wire.MarketAdminMessage
import com.panomc.plugins.market.mc.core.wire.MarketAdminRequest
import com.panomc.plugins.market.mc.core.wire.MarketMcSettings
import com.panomc.plugins.market.mc.core.wire.MarketQueryData
import com.panomc.plugins.market.mc.core.wire.MarketQueryMessage
import com.panomc.plugins.market.mc.core.wire.MarketQueryRequest
import com.panomc.plugins.market.mc.core.wire.QueryOrder
import com.panomc.plugins.market.mc.core.wire.QueryType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class MarketCommandsTest {
    @TempDir
    lateinit var dir: Path

    private fun rig(config: String? = null, load: Boolean = true): FeatureRig = FeatureRig(dir, config).also { if (load) it.loadConfig() }

    private fun player(nodes: Set<String> = emptySet(), name: String = "Steve") = FakeSender(name = name, nodes = nodes)
    private fun console() = FakeSender(name = "CONSOLE", isConsole = true)

    private fun FeatureRig.run(command: String, sender: FakeSender, vararg args: String) = features.commands.execute(command, sender, args.toList())

    private val admin = setOf(
        "panomarket.admin.credits.give", "panomarket.admin.credits.take", "panomarket.admin.credits.set",
        "panomarket.admin.grant", "panomarket.admin.purchases", "panomarket.admin.status"
    )

    // ---- /store ----------------------------------------------------------------------------------------------------

    @Test
    fun `store prints the clickable store address`() {
        val r = rig()
        val s = player()
        r.run("store", s)
        assertEquals(listOf("Store: https://shop.example.com"), s.plain())
        assertEquals(listOf<String?>("https://shop.example.com"), s.links)
        assertTrue(r.link.requests.none { it is MarketQueryRequest }, "no network call for the link")
    }

    @Test
    fun `store before the first config answer says the address is not known yet`() {
        val r = rig(load = false)
        val s = player()
        r.run("store", s)
        assertTrue(s.said("not known yet"))
        assertEquals(listOf<String?>(null), s.links)
    }

    @Test
    fun `store history asks Pano for the player's purchases and lists them`() {
        val r = rig()
        r.link.handler = { req ->
            assertTrue(req is MarketQueryRequest)
            MarketQueryMessage(
                true, null,
                MarketQueryData(orders = listOf(QueryOrder("ORD-1", "FULFILLED", 12.5, "USD", listOf("Diamonds", "Rank VIP"), 1_790_000_000_000L), QueryOrder("ORD-2", "PAID", 3.0, "USD", listOf("Coal"), 0)))
            )
        }
        val s = player()
        r.run("store", s, "history")
        val q = r.link.requests.single() as MarketQueryRequest
        assertEquals(QueryType.PURCHASES, q.type)
        assertEquals("Steve", q.player!!.username)
        assertEquals("11111111-1111-1111-1111-111111111111", q.player!!.uuid)
        assertEquals(
            listOf(
                "Your latest purchases:",
                "ORD-1 Diamonds, Rank VIP - 12.5 USD (FULFILLED, 2026-09-21)",
                "ORD-2 Coal - 3 USD (PAID, -)"
            ),
            s.plain()
        )
    }

    @Test
    fun `store history of a player without purchases, and for the console`() {
        val r = rig()
        r.link.handler = { MarketQueryMessage(true, null, MarketQueryData(orders = emptyList())) }
        val s = player()
        r.run("store", s, "history")
        assertEquals(listOf("You have no purchases yet."), s.plain())
        val c = console()
        r.run("store", c, "history")
        assertTrue(c.said("Only players"))
        assertEquals(1, r.link.requests.size, "the console never reaches Pano with a player query")
    }

    @Test
    fun `store with an unknown argument prints the usage, menu without a menu handler says so`() {
        val r = rig()
        val s = player()
        r.run("store", s, "nonsense")
        assertTrue(s.said("Usage: /store"))
        val m = player()
        r.run("store", m, "menu")
        assertTrue(m.said("menu is not available"))
    }

    @Test
    fun `a registered store sub-command runs only while its feature is on`() {
        val r = rig()
        var ran: List<String>? = null
        r.features.commands.registerSub("store", "menu", Feature.STORE_MENU) { _, args -> ran = args }
        r.run("store", player(), "menu", "x")
        assertEquals(listOf("x"), ran)

        ran = null
        r.loadConfig(panoConfig("h2", MarketMcSettings(mcStoreMenu = false)))
        val s = player()
        r.run("store", s, "menu")
        assertNull(ran)
        assertTrue(s.said("switched off"))
    }

    // ---- /credits --------------------------------------------------------------------------------------------------

    @Test
    fun `credits prints the balance in the credit name of the store`() {
        val r = rig()
        r.link.handler = { MarketQueryMessage(true, null, MarketQueryData(registered = true, balance = 1250.5, creditName = "Coins")) }
        val s = player()
        r.run("credits", s)
        assertEquals(listOf("Your balance: 1250.5 Coins"), s.plain())
        assertEquals(QueryType.BALANCE, (r.link.requests.single() as MarketQueryRequest).type)
    }

    @Test
    fun `credits for a player without a website account points to the register page`() {
        val r = rig()
        r.link.handler = { MarketQueryMessage(true, null, MarketQueryData(registered = false, balance = 0.0)) }
        val s = player()
        r.run("credits", s)
        assertTrue(s.said("no account on the website"))
        assertEquals(listOf<String?>("https://shop.example.com/register"), s.links)
    }

    @Test
    fun `credits for the console, with an unknown argument, and a registered sub-command`() {
        val r = rig()
        val c = console()
        r.run("credits", c)
        assertTrue(c.said("Only players"))
        val s = player()
        r.run("credits", s, "convert", "5")
        assertTrue(s.said("Usage: /credits"))
        var got: List<String>? = null
        r.features.commands.registerSub("credits", "convert", Feature.VAULT) { _, a -> got = a }
        r.run("credits", s, "convert", "5")
        assertNull(got, "Vault is off in the panel: the sub-command is disabled")
        assertTrue(s.said("switched off"))
        r.loadConfig(panoConfig("h3", MarketMcSettings(mcVaultMode = "CONVERT")))
        r.run("credits", s, "convert", "5")
        assertEquals(listOf("5"), got)
    }

    @Test
    fun `a command can be switched off by the panel or by the local file`() {
        val panel = rig()
        panel.loadConfig(panoConfig("h2", MarketMcSettings(mcStoreCommand = false, mcCreditsCommand = false)))
        listOf("store", "credits").forEach { c ->
            val s = player()
            panel.run(c, s)
            assertEquals(listOf("This command is switched off on this server."), s.plain(), c)
        }
        assertTrue(panel.link.requests.none { it is MarketQueryRequest })

        val local = rig(config = "features:\n  store-command: false\n  credits-command: false\n")
        local.loadConfig(panoConfig("h2", MarketMcSettings(mcStoreCommand = true, mcCreditsCommand = true)))
        listOf("store", "credits").forEach { c ->
            val s = player()
            local.run(c, s)
            assertEquals(listOf("This command is switched off on this server."), s.plain(), "$c: local off beats panel on")
        }
    }

    @Test
    fun `messages follow the sender's language and the console language falls back`() {
        val r = rig()
        val tr = FakeSender(name = "Ali", locale = "tr_TR")
        r.run("store", tr)
        assertEquals(listOf("Mağaza: https://shop.example.com"), tr.plain())
        val ru = FakeSender(name = "Ivan", locale = "ru_RU")
        r.run("store", ru)
        assertEquals(listOf("Магазин: https://shop.example.com"), ru.plain())
    }

    // ---- failures of the Pano round trip -------------------------------------------------------------------------

    @Test
    fun `not connected, no answer and refusals each have their own message and never hang`() {
        val r = rig()
        r.link.up = false
        val a = player()
        r.run("credits", a)
        assertTrue(a.said("not connected"))
        assertTrue(r.link.requests.none { it is MarketQueryRequest })

        r.link.up = true
        r.link.handler = { null }
        val b = player(name = "Alex")
        r.run("credits", b)
        assertTrue(b.said("did not answer in time"))

        mapOf(
            "RATE_LIMITED" to "busy",
            "MARKET_NOT_READY" to "not set up yet",
            "VERSION_MISMATCH" to "must be updated",
            "PROTOCOL_UNSUPPORTED" to "must be updated",
            "SOMETHING_ELSE" to "refused the request (SOMETHING_ELSE)"
        ).forEach { (reason, text) ->
            r.link.handler = { MarketQueryMessage(false, reason, null) }
            val s = player(name = "P$reason".take(16))
            r.run("credits", s)
            assertTrue(s.said(text), "$reason -> ${s.plain()}")
        }
    }

    @Test
    fun `a query command is throttled per player, the console is not`() {
        val r = rig()
        r.link.handler = { MarketQueryMessage(true, null, MarketQueryData(registered = true, balance = 1.0)) }
        val s = player()
        r.run("credits", s)
        r.run("credits", s)
        assertEquals(1, r.link.requests.filterIsInstance<MarketQueryRequest>().size)
        assertTrue(s.said("wait a moment"))
        r.clock.now += 1_500
        r.run("credits", s)
        assertEquals(2, r.link.requests.filterIsInstance<MarketQueryRequest>().size)
        val other = player(name = "Alex")
        r.run("credits", other)
        assertEquals(3, r.link.requests.filterIsInstance<MarketQueryRequest>().size, "another player has their own window")
    }

    // ---- /panomarket credits give|take|set -----------------------------------------------------------------------

    private fun adminOk(balance: Double = 150.0) = { _: com.panomc.plugins.market.mc.core.wire.MarketRequest -> MarketAdminMessage(true, null, true, null, balance) }

    @Test
    fun `credits give, take and set build the MARKET_ADMIN request of the spec`() {
        val r = rig()
        r.link.handler = adminOk(150.0)
        val s = player(admin, "Admin")
        r.run("panomarket", s, "credits", "give", "Alex", "50", "welcome", "back")
        r.run("panomarket", s, "credits", "take", "Alex", "2.5")
        r.run("panomarket", s, "credits", "set", "Alex", "0")
        val reqs = r.link.of(MarketAdminRequest::class.java)
        assertEquals(listOf(AdminOp.GIVE_CREDITS, AdminOp.TAKE_CREDITS, AdminOp.SET_CREDITS), reqs.map { it.op })
        assertEquals(listOf(50.0, 2.5, 0.0), reqs.map { it.amount })
        assertEquals(listOf("welcome back", null, null), reqs.map { it.note })
        reqs.forEach {
            assertEquals("Alex", it.target.username)
            assertFalse(it.actor.console)
            assertEquals("Admin", it.actor.username)
            assertEquals("11111111-1111-1111-1111-111111111111", it.actor.uuid)
            assertNull(it.productId)
            assertEquals(1, it.protocol)
        }
        assertEquals(3, reqs.map { it.operationId }.toSet().size, "every command execution has its own operationId")
        assertEquals(
            listOf(
                "Gave 50 credits to Alex. New balance: 150",
                "Took 2.5 credits from Alex. New balance: 150",
                "Set the credits of Alex to 150"
            ),
            s.plain()
        )
    }

    @Test
    fun `the console may run every admin command without nodes and is sent as console`() {
        val r = rig()
        r.link.handler = adminOk()
        val c = console()
        r.run("panomarket", c, "credits", "give", "Alex", "10")
        val req = r.link.of(MarketAdminRequest::class.java).single()
        assertTrue(req.actor.console)
        assertNull(req.actor.username)
        assertNull(req.actor.uuid)
        assertTrue(c.said("Gave 10 credits"))
    }

    @Test
    fun `first half of the double authorisation, a player without the node never reaches Pano`() {
        val r = rig()
        r.link.handler = adminOk()
        val s = player(setOf("panomarket.admin.credits.take"), "Mod")
        r.run("panomarket", s, "credits", "give", "Alex", "10")
        assertTrue(s.said("do not have permission"))
        assertTrue(r.link.requests.isEmpty())
        r.run("panomarket", s, "grant", "Alex", "5")
        r.run("panomarket", s, "purchases", "Alex")
        assertEquals(3, s.lines.count { it.contains("do not have permission") })
        assertTrue(r.link.requests.isEmpty())
        r.run("panomarket", s, "credits", "take", "Alex", "1")
        assertEquals(1, r.link.requests.size, "the node it has works")
    }

    @Test
    fun `second half, Pano's NO_PERMISSION, NO_ACCOUNT and the other codes are told`() {
        val r = rig()
        val s = player(admin, "Admin")
        mapOf(
            "NO_PERMISSION" to "website account is not allowed",
            "NO_ACCOUNT" to "Alex has no account",
            "INSUFFICIENT_CREDITS" to "does not have enough credits",
            "CREDITS_DISABLED" to "Credits are switched off",
            "WHATEVER" to "refused the command (WHATEVER)"
        ).forEach { (code, text) ->
            r.link.handler = { MarketAdminMessage(true, null, false, code) }
            val before = s.lines.size
            r.run("panomarket", s, "credits", "take", "Alex", "10")
            assertTrue(s.plain().drop(before).any { it.contains(text) }, "$code -> ${s.plain().drop(before)}")
        }
        r.link.handler = { MarketAdminMessage(false, "RATE_LIMITED", null, null) }
        r.run("panomarket", s, "credits", "give", "Alex", "1")
        assertTrue(s.said("busy"))
    }

    @Test
    fun `a money command without an answer is reported as an unknown outcome, never as failed`() {
        val r = rig()
        r.link.handler = { null }
        val s = player(admin, "Admin")
        r.run("panomarket", s, "credits", "give", "Alex", "10")
        assertTrue(s.said("may or may not have been applied"), s.plain().toString())
        assertFalse(s.said("refused"))
        r.link.up = false
        val t = player(admin, "Admin2")
        r.run("panomarket", t, "credits", "give", "Alex", "10")
        assertTrue(t.said("not connected"))
    }

    @Test
    fun `amounts are validated before anything is sent`() {
        val r = rig()
        r.link.handler = adminOk()
        val c = console()
        listOf("0", "-5", "abc", "1e3", "2.555", "", "1,5", "99999999999999", "NaN", ".5").forEach { amount ->
            val args = if (amount.isEmpty()) arrayOf("credits", "give", "Alex") else arrayOf("credits", "give", "Alex", amount)
            r.run("panomarket", c, *args)
        }
        r.run("panomarket", c, "credits", "take", "Alex", "0")
        r.run("panomarket", c, "credits", "set", "Alex", "-1")
        assertTrue(r.link.requests.isEmpty(), "no invalid amount reached Pano")
        assertEquals(12, c.lines.count { it.contains("must be a number") })
        r.run("panomarket", c, "credits", "set", "Alex", "0")
        r.run("panomarket", c, "credits", "give", "Alex", "0.01")
        assertEquals(2, r.link.requests.size, "zero is valid for set only; two decimals are fine")
    }

    @Test
    fun `player names and the note are checked`() {
        val r = rig()
        r.link.handler = adminOk()
        val c = console()
        r.run("panomarket", c, "credits", "give", "bad name!", "5")
        r.run("panomarket", c, "credits", "give")
        assertTrue(r.link.requests.isEmpty())
        assertEquals(2, c.lines.count { it.contains("player name") })
        r.run("panomarket", c, "credits", "give", ".BedrockPlayer", "5", "§cred", "note\u0007", "x".repeat(300))
        val req = r.link.of(MarketAdminRequest::class.java).single()
        assertEquals(200, req.note!!.length)
        assertTrue(req.note!!.startsWith("cred note"), req.note)
        assertEquals(".BedrockPlayer", req.target.username)
    }

    // ---- grant / purchases ---------------------------------------------------------------------------------------

    @Test
    fun `grant sends the manual-order request and syncs at once`() {
        val r = rig()
        r.link.handler = { MarketAdminMessage(true, null, true, null, null, "ORD-77") }
        val s = player(admin, "Admin")
        r.run("panomarket", s, "grant", "Alex", "42", "3")
        r.run("panomarket", s, "grant", "Alex", "42")
        val reqs = r.link.of(MarketAdminRequest::class.java)
        assertEquals(listOf(AdminOp.GRANT_PRODUCT, AdminOp.GRANT_PRODUCT), reqs.map { it.op })
        assertEquals(listOf(42L, 42L), reqs.map { it.productId })
        assertEquals(listOf(3, 1), reqs.map { it.quantity })
        assertNull(reqs[0].amount)
        assertTrue(s.said("Granted product 42 x3 to Alex (order ORD-77)"))
        assertEquals(2, r.control.syncs, "the delivery of the manual order is fetched without waiting for the next poll")
    }

    @Test
    fun `grant arguments are validated`() {
        val r = rig()
        r.link.handler = { MarketAdminMessage(true, null, true, null, null, "O") }
        val c = console()
        r.run("panomarket", c, "grant", "Alex", "abc")
        r.run("panomarket", c, "grant", "Alex", "0")
        r.run("panomarket", c, "grant", "Alex", "-3")
        r.run("panomarket", c, "grant", "Alex", "5", "0")
        r.run("panomarket", c, "grant", "Alex", "5", "1001")
        r.run("panomarket", c, "grant", "Alex", "5", "x")
        r.run("panomarket", c, "grant", "x y", "5")
        assertTrue(r.link.requests.isEmpty())
        assertEquals(3, c.lines.count { it.contains("product id") })
        assertEquals(3, c.lines.count { it.contains("quantity must be") })
        r.run("panomarket", c, "grant", "Alex", "5", "1000")
        assertEquals(1000, r.link.of(MarketAdminRequest::class.java).single().quantity)
    }

    @Test
    fun `purchases of another player are listed through MARKET_ADMIN`() {
        val r = rig()
        r.link.handler = { MarketAdminMessage(true, null, true, null, null, null, listOf(QueryOrder("ORD-9", "PAID", 4.0, "EUR", listOf("Sword"), 1_790_000_000_000L))) }
        val s = player(admin, "Admin")
        r.run("panomarket", s, "purchases", "Alex")
        assertEquals(AdminOp.PURCHASES, (r.link.requests.single() as MarketAdminRequest).op)
        assertEquals(listOf("Latest purchases of Alex:", "ORD-9 Sword - 4 EUR (PAID, 2026-09-21)"), s.plain())

        r.link.handler = { MarketAdminMessage(true, null, true, null, null, null, emptyList()) }
        r.clock.now += 5_000
        val t = player(admin, "Admin")
        r.run("panomarket", t, "purchases", "Alex")
        assertTrue(t.said("Alex has no purchases"))
    }

    // ---- disabled sub-commands -------------------------------------------------------------------------------------

    @Test
    fun `each admin sub-command can be disabled on its own by the panel list`() {
        val r = rig()
        r.link.handler = { MarketAdminMessage(true, null, true, null, 1.0, "O", emptyList()) }
        r.loadConfig(panoConfig("h2", MarketMcSettings(mcDisabledAdminCommands = listOf("give-credits", "set-credits", "grant-product", "purchases"))))
        val c = console()
        r.run("panomarket", c, "credits", "give", "Alex", "1")
        r.run("panomarket", c, "credits", "set", "Alex", "1")
        r.run("panomarket", c, "grant", "Alex", "1")
        r.run("panomarket", c, "purchases", "Alex")
        assertEquals(4, c.lines.count { it.contains("switched off") })
        assertTrue(r.link.requests.isEmpty(), "disabled means nothing is sent, even for the console")
        r.run("panomarket", c, "credits", "take", "Alex", "1")
        assertEquals(AdminOp.TAKE_CREDITS, (r.link.requests.single() as MarketAdminRequest).op, "take-credits is not in the list")
    }

    @Test
    fun `the admin-commands feature off (panel or local) disables all of them but not status and recover`() {
        listOf(
            rig().also { it.loadConfig(panoConfig("h2", MarketMcSettings(mcAdminCommands = false))) },
            rig(config = "features:\n  admin-commands: false\n").also { it.loadConfig() }
        ).forEach { r ->
            r.link.handler = adminOk()
            val c = console()
            r.run("panomarket", c, "credits", "give", "Alex", "1")
            r.run("panomarket", c, "grant", "Alex", "1")
            r.run("panomarket", c, "purchases", "Alex")
            assertEquals(3, c.lines.count { it.contains("switched off") })
            assertTrue(r.link.requests.isEmpty())
            r.run("panomarket", c, "status")
            assertTrue(c.said("Pano Market status"), "status stays available")
            r.control.preview = RecoveryPreview(1, "q", "corrupt", 2, false)
            r.run("panomarket", c, "recover")
            assertTrue(c.said("Up to 2 deliveries"), "recover stays available")
        }
    }

    // ---- status / recover ------------------------------------------------------------------------------------------

    @Test
    fun `status shows connection, version gate, queue, store, recovery, last sync and config`() {
        val r = rig()
        r.control.status = RuntimeStatus(
            true, false, true,
            EngineStatus(queued = 3, running = 1, unacked = 2, records = 9, recoveryMode = false, storeHealthy = true, accepted = true, rejectionReason = null, lastSyncAt = r.clock.now - 12_000, marketVersion = "1.4.0")
        )
        val c = console()
        r.run("panomarket", c, "status")
        assertEquals(
            listOf(
                "Pano Market status:",
                "Component version: 1.4.0",
                "Connection to Pano: connected",
                "Version check: accepted (Market 1.4.0)",
                "Deliveries: 3 waiting, 1 running, 2 not acknowledged yet",
                "Local state: ok",
                "Last sync: 12 s ago",
                "Panel settings: loaded (h1)"
            ),
            c.plain()
        )
    }

    @Test
    fun `status of a refused version, a broken disk, recovery mode, a config problem and a dead runtime`() {
        val r = rig(config = "features:\n  broadcast: maybe\n", load = false)
        r.control.status = RuntimeStatus(
            true, false, false,
            EngineStatus(queued = 0, accepted = false, rejectionReason = "VERSION_MISMATCH", marketVersion = "2.0.0", storeHealthy = false, recoveryMode = true, lastSyncAt = null)
        )
        val c = console()
        r.run("panomarket", c, "status")
        val text = c.plain().joinToString("\n")
        assertTrue(text.contains("not connected"))
        assertTrue(text.contains("mismatch (VERSION_MISMATCH) - Market 2.0.0, this component 1.4.0"))
        assertTrue(text.contains("disk refuses writes"))
        assertTrue(text.contains("Recovery mode"))
        assertTrue(text.contains("Last sync: never"))
        assertTrue(text.contains("config.yml problem"), text)
        assertTrue(text.contains("Panel settings: waiting"))

        val dead = rig()
        dead.control.status = RuntimeStatus(false, true, false, EngineStatus())
        val d = console()
        dead.run("panomarket", d, "status")
        assertTrue(d.said("could not be opened"))
    }

    @Test
    fun `status for a player needs its own node`() {
        val r = rig()
        val s = player(setOf("panomarket.admin.credits.give"))
        r.run("panomarket", s, "status")
        assertEquals(listOf("You do not have permission to use this command."), s.plain())
        val t = player(setOf(MarketCommands.NODE_STATUS))
        r.run("panomarket", t, "status")
        assertTrue(t.said("Pano Market status"))
    }

    @Test
    fun `recover is console only, previews the loss and needs the confirm word`() {
        val r = rig()
        val p = player(admin)
        r.run("panomarket", p, "recover")
        r.run("panomarket", p, "recover", "confirm")
        assertEquals(2, p.lines.count { it.contains("only be run from the console") })
        assertEquals(0, r.control.confirmed)

        val c = console()
        r.run("panomarket", c, "recover")
        assertTrue(c.said("not in recovery mode"))
        r.control.preview = RecoveryPreview(5, "store.corrupt-1", "bad snapshot", 4, true)
        val d = console()
        r.run("panomarket", d, "recover")
        assertEquals(
            listOf(
                "The local state was lost. Up to 4 deliveries may be offered again and run a second time.",
                "Run /panomarket recover confirm to accept that and continue."
            ),
            d.plain()
        )
        assertEquals(0, r.control.confirmed, "the preview never confirms")
        val e = console()
        r.run("panomarket", e, "recover", "confirm")
        assertEquals(1, r.control.confirmed)
        assertTrue(e.said("Recovery confirmed"))
        r.control.confirmResult = false
        val f = console()
        r.run("panomarket", f, "recover", "CONFIRM")
        assertTrue(f.said("not in recovery mode"))
        val g = console()
        r.run("panomarket", g, "recover", "now")
        assertEquals(2, r.control.confirmed, "any other word is not a confirmation")
    }

    // ---- help and tab completion ------------------------------------------------------------------------------------

    @Test
    fun `help lists only what the sender may use`() {
        val r = rig()
        val none = player()
        r.run("panomarket", none)
        assertEquals(listOf("You do not have permission to use this command."), none.plain())
        val some = player(setOf("panomarket.admin.grant", MarketCommands.NODE_STATUS))
        r.run("panomarket", some)
        assertEquals(
            listOf("Pano Market admin commands:", "/panomarket grant <player> <productId> [quantity]", "/panomarket status"),
            some.plain()
        )
        val c = console()
        r.run("panomarket", c, "unknown")
        assertEquals(6, c.lines.size)
        assertTrue(c.said("recover"))
    }

    @Test
    fun `tab completion offers the sub-commands the sender may run`() {
        val r = rig()
        r.features.commands.registerSub("credits", "convert", Feature.VAULT) { _, _ -> }
        val cmd = r.features.commands
        assertEquals(listOf("history"), cmd.complete("store", player(), listOf("")))
        assertEquals(listOf("history"), cmd.complete("store", player(), listOf("H")))
        assertEquals(emptyList<String>(), cmd.complete("credits", player(), listOf("")), "convert is off until the panel picks a Vault mode")
        r.loadConfig(panoConfig("h2", MarketMcSettings(mcVaultMode = "CONVERT")))
        assertEquals(listOf("convert"), cmd.complete("credits", player(), listOf("c")))
        assertEquals(emptyList<String>(), cmd.complete("panomarket", player(), listOf("")))
        assertEquals(listOf("credits", "grant", "purchases", "recover", "status"), cmd.complete("panomarket", console(), listOf("")))
        assertEquals(listOf("grant"), cmd.complete("panomarket", player(admin), listOf("g")))
        assertEquals(listOf("give", "set", "take"), cmd.complete("panomarket", console(), listOf("credits", "")))
        assertEquals(listOf("confirm"), cmd.complete("panomarket", console(), listOf("recover", "c")))
        assertEquals(emptyList<String>(), cmd.complete("panomarket", player(admin), listOf("recover", "c")))
        assertNotEquals(emptyList<String>(), MarketCommands.NODES)
        assertTrue(MarketCommands.NODES.all { it.startsWith("panomarket.admin.") })
    }

    @Test
    fun `a command that throws never escapes to the server`() {
        val r = rig()
        r.link.handler = { throw IllegalStateException("boom") }
        val s = player()
        r.run("credits", s)
        assertTrue(s.said("boom") || s.said("refused"), s.plain().toString())
        r.features.commands.registerSub("store", "bad", Feature.STORE_COMMAND) { _, _ -> throw IllegalArgumentException("bad sub") }
        val t = player()
        r.run("store", t, "bad")
        assertTrue(t.said("bad sub"))
    }
}
