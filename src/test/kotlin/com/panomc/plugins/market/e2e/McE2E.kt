package com.panomc.plugins.market.e2e

import com.panomc.plugins.market.e2e.mc.FakeMcSender
import com.panomc.plugins.market.e2e.mc.FakeMcServer
import com.panomc.plugins.market.e2e.support.E2eBuyer
import com.panomc.plugins.market.e2e.support.E2eTestBase
import com.panomc.plugins.market.mc.core.platform.DispatchResult
import com.panomc.plugins.market.mc.spigot.vault.EconomyAnswer
import com.panomc.plugins.market.mc.spigot.vault.EconomyClient
import com.panomc.plugins.market.mc.core.wire.AdminActor
import com.panomc.plugins.market.mc.core.wire.AdminOp
import com.panomc.plugins.market.mc.core.wire.AdminTarget
import com.panomc.plugins.market.mc.core.wire.EconomyOp
import com.panomc.plugins.market.mc.core.wire.MarketAdminMessage
import com.panomc.plugins.market.mc.core.wire.MarketAdminRequest
import com.panomc.plugins.market.mc.core.wire.MarketConfigMessage
import com.panomc.plugins.market.mc.core.wire.MarketConfigRequest
import com.panomc.plugins.market.mc.core.wire.MarketPurchaseMessage
import com.panomc.plugins.market.mc.core.wire.MarketPurchaseRequest
import com.panomc.plugins.market.mc.core.wire.MarketQueryMessage
import com.panomc.plugins.market.mc.core.wire.MarketQueryRequest
import com.panomc.plugins.market.mc.core.wire.MarketRequest
import com.panomc.plugins.market.mc.core.wire.PlayerRef
import com.panomc.plugins.market.mc.core.wire.QueryType
import com.panomc.plugins.market.support.Await
import com.panomc.plugins.pano.core.platform.PlatformMessageResponse
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Row
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import com.panomc.plugins.market.util.MarketPaths

/**
 * T4 scenarios MC-E1 to MC-E7 of 19 section 13 (17 section 9.11 V-17), played by a [FakeMcServer] against the isolated instance: the REAL market
 * component of the jar (`mc.core`) on a real WebSocket to the real `ServerManager` / `McSyncService` / `McGameService`, over the same
 * `POST /api/server/connect` + accept road a Minecraft server walks. Only Bukkit is replaced (`FakeMcPlatform`).
 *
 * Every scenario owns a server of its own (registered in the scenario, removed afterwards), so what one leaves behind never decides another. The queue
 * drain and the global invariants of `E2eTestBase` run after each one.
 */
class McE2E : E2eTestBase() {
    override val tag = "mc"

    private val sequence = AtomicInteger()
    private val servers = ArrayList<FakeMcServer>()
    private val blocks = ArrayList<String>()

    @AfterEach
    fun closeFakeServers() {
        // a block a scenario placed and did not lift (the scenario failed half way) must not outlive it
        blocks.forEach { runCatching { admin.delete("${MarketPaths.PANEL_ROOT}/blocks/$it") } }
        blocks.clear()
        servers.forEach { runCatching { it.close() } }
        servers.clear()
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    /** A server that walked the whole road and is `READY` for the market (the component announced the version Pano asks for unless [version] says otherwise). */
    private fun server(version: String? = null, awaitReady: Boolean = version == null): FakeMcServer {
        val n = sequence.incrementAndGet()
        val dir = Files.createTempDirectory("fake-mc-$n-")
        val server = FakeMcServer(session, "e$n", dir).also { servers += it }

        server.launch(version)

        if (awaitReady) Await.until(60_000, 200, "server ${server.serverId} is READY") { server.view().getString("marketState") == "READY" }

        return server
    }

    /** A product whose only action is a console command on [server] (`COMMAND`, FIXED, the player need not be online). */
    private fun commandProduct(
        server: FakeMcServer, commands: List<String>, price: String = "2.00", creditPrice: String? = null, stock: Int? = null, extra: Map<String, String> = emptyMap()
    ): Long {
        val n = sequence.incrementAndGet()
        val action = JsonObject().put("id", "a1").put("type", "COMMAND").put("phase", "GRANT").put("value", JsonArray(commands))
            .put("serverMode", "FIXED").put("targetServers", JsonArray().add(server.serverId)).put("requiresOnline", false)

        return catalog.product(
            key = "MCE$n", slug = "e2e-mce-${System.currentTimeMillis().toString(36)}-$n", name = "MC product $n", price = price, creditPrice = creditPrice, stock = stock,
            actions = JsonArray().add(action).encode(), extra = extra
        )
    }

    /** A product with a credit price and no action: nothing is delivered, only the order and the ledger are of interest. */
    private fun creditProduct(creditPrice: String = "4.00", stock: Int? = null, extra: Map<String, String> = emptyMap()): Long {
        val n = sequence.incrementAndGet()

        return catalog.product(
            key = "MCP$n", slug = "e2e-mcp-${System.currentTimeMillis().toString(36)}-$n", name = "MC credit product $n", price = "2.00", creditPrice = creditPrice, stock = stock,
            extra = extra
        )
    }

    private fun deliveries(publicId: String): List<Row> = db.sql(
        "SELECT d.* FROM `pano_market_delivery` d JOIN `pano_market_order` o ON o.`id` = d.`orderId` WHERE o.`publicId` = ? ORDER BY d.`id`", publicId
    )

    private fun payAndAwaitCompleted(buyer: E2eBuyer, productId: Long, quantity: Int = 1): String {
        val publicId = publicIdOf(checkout(buyer.client, cart(line(productId, quantity))).ok())

        payViaFake(publicId)
        awaitOrder(publicId, "COMPLETED")

        return publicId
    }

    private fun awaitDelivery(publicId: String, status: String, timeoutMs: Long = 120_000): Row =
        Await.untilValue(timeoutMs, 200, "the delivery of $publicId is $status") { deliveries(publicId).singleOrNull()?.takeIf { it.getString("status") == status } }

    private fun creditMinor(userId: Long): Long = db.long("SELECT `balance` FROM `pano_market_credit_account` WHERE `userId` = ?", userId) ?: 0L

    private fun giveCredits(buyer: E2eBuyer, amount: Number): Long {
        admin.post(
            "${MarketPaths.PANEL_ROOT}/credits/accounts/${buyer.userId}/grant", JsonObject().put("amount", amount).put("note", "e2e mc credits"), mapOf("Idempotency-Key" to idempotencyKey())
        ).ok()

        return creditMinor(buyer.userId)
    }

    private fun <R : PlatformMessageResponse> ask(server: FakeMcServer, request: MarketRequest, type: Class<R>): R {
        val answer = CompletableFuture<R?>()

        server.link.request(request, type) { answer.complete(it) }

        return answer.get(40, TimeUnit.SECONDS) ?: throw AssertionError("${request.javaClass.simpleName} got no answer")
    }

    private fun purchase(server: FakeMcServer, player: String, productId: Long, operationId: String = UUID.randomUUID().toString(), quantity: Int = 1, legalTextId: Long? = null) =
        ask(server, MarketPurchaseRequest(server.componentVersion, operationId = operationId, player = PlayerRef(player, null), productId = productId, quantity = quantity, confirmLegalTextId = legalTextId), MarketPurchaseMessage::class.java)

    private fun adminOp(
        server: FakeMcServer, op: String, actor: AdminActor, target: String, amount: Double? = null, operationId: String = UUID.randomUUID().toString(), note: String? = null
    ) = ask(server, MarketAdminRequest(server.componentVersion, operationId = operationId, op = op, actor = actor, target = AdminTarget(target), amount = amount, productId = null, quantity = null, note = note), MarketAdminMessage::class.java)

    /** `MARKET_ECONOMY` through the component's own `EconomyClient` (the one the Vault bridge uses, Bukkit-free), answers classified like the bridge does. */
    private fun economy(server: FakeMcServer, op: String, player: String, amount: Double?, operationId: String = UUID.randomUUID().toString()): EconomyAnswer {
        val answer = CompletableFuture<EconomyAnswer>()

        EconomyClient(server.link, server.componentVersion, server.log).send(op, PlayerRef(player, null), amount, operationId, "e2e MC-E6") { answer.complete(it) }

        return answer.get(40, TimeUnit.SECONDS)
    }

    private fun txOf(serverId: Long, operationId: String): Row? =
        db.sql("SELECT * FROM `pano_market_credit_tx` WHERE `idempotencyKey` = ?", "mc:$serverId:$operationId").firstOrNull()

    private fun lastLogId(): Long = db.long("SELECT COALESCE(MAX(`id`), 0) FROM `pano_panel_activity_log`") ?: 0L

    private fun ingameLogs(after: Long): List<Row> =
        db.sql("SELECT `type`, `userId`, `details` FROM `pano_panel_activity_log` WHERE `id` > ? AND `type` LIKE '%INGAME' ORDER BY `id`", after)

    private fun putOverride(server: FakeMcServer, settings: JsonObject?) {
        admin.put("${MarketPaths.PANEL_ROOT}/servers/${server.serverId}/settings", JsonObject().put("settings", settings)).ok()
    }

    // ---- MC-E1 -----------------------------------------------------------------------------------------------------

    @Test
    fun `MC-E1 a COMMAND delivery goes SENT then CONFIRMED, the order is FULFILLED and the acknowledgement clears the result`() {
        val server = server()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)

        // the command is held on the fake console, so the row can be observed between the offer (SENT) and the result (CONFIRMED)
        server.platform.dispatcher = {
            started.countDown()
            release.await(90, TimeUnit.SECONDS)
            DispatchResult.OK
        }

        val productId = commandProduct(server, listOf("give {username} diamond {quantity}"))
        val buyer = buyer()
        val publicId = payAndAwaitCompleted(buyer, productId, quantity = 2)

        assertTrue(started.await(120, TimeUnit.SECONDS), "the command reached the fake console")

        val sent = deliveries(publicId).single()

        assertEquals("SENT", sent.getString("status"), "offered to the server, the result is not in yet")
        assertEquals("COMMAND", sent.getString("actionType"))
        assertEquals(server.serverId, sent.getLong("serverId"))
        assertEquals("PENDING", orderRow(publicId).getString("fulfillmentStatus"), "nothing is confirmed yet")
        assertEquals(listOf("give ${buyer.username} diamond 2"), server.platform.console, "the command arrived with the placeholders expanded")

        release.countDown()

        val confirmed = awaitDelivery(publicId, "CONFIRMED")

        assertNotNull(confirmed.getValue("confirmedAt"))
        Await.until(60_000, 200, "order $publicId is FULFILLED") { orderRow(publicId).getString("fulfillmentStatus") == "FULFILLED" }
        assertEquals(1, server.platform.ran("give ${buyer.username} diamond 2"), "executed exactly once")

        // the acknowledgement cleared the result: the component's store holds nothing unacknowledged and the key is no longer reported
        Await.until(60_000, 200, "no unacknowledged result is left on the component") { server.component.runtime.status().engine.unacked == 0 }

        val key = confirmed.getString("idempotencyKey")
        val reported = server.syncRequests().count { request -> request.getJsonArray("results").any { (it as JsonObject).getString("key") == key } }

        assertTrue(reported >= 1, "the result of $key was reported")

        val acked = server.received("MARKET_SYNC").count { it.getJsonArray("acked")?.contains(key) == true }

        assertTrue(acked >= 1, "Pano acknowledged $key")
        assertEquals(1, server.platform.console.size, "no second run of anything")
    }

    // ---- MC-E2 -----------------------------------------------------------------------------------------------------

    @Test
    fun `MC-E2 a version mismatch is refused and the rows wait, after the version is updated they are delivered`() {
        val server = server(version = "0.0.1-e2e-old")
        val required = server.requiredVersion()

        assertNotEquals(required, "0.0.1-e2e-old")

        // {accepted: false, VERSION_MISMATCH} on the wire, and the panel list says so
        val refusal = Await.untilValue(60_000, 200, "a refused sync answer") {
            server.received("MARKET_SYNC").firstOrNull { it.getBoolean("accepted") == false }
        }

        assertEquals("VERSION_MISMATCH", refusal.getString("reason"))
        assertEquals(required, refusal.getString("marketVersion"))
        Await.until(60_000, 200, "marketState is VERSION_MISMATCH") { server.view().getString("marketState") == "VERSION_MISMATCH" }
        assertEquals(false, server.component.runtime.status().engine.accepted, "the component knows it was refused")

        val productId = commandProduct(server, listOf("give {username} emerald"))
        val buyer = buyer()
        val publicId = payAndAwaitCompleted(buyer, productId)

        // the sale is not blocked, the row waits (a PENDING row becomes WAITING_SERVER after 15 s)
        awaitDelivery(publicId, "WAITING_SERVER")
        assertEquals("VERSION_MISMATCH", server.view().getString("marketState"))
        assertTrue(server.view().getLong("waitingDeliveries") >= 1, "the panel counts the waiting row")
        assertEquals(emptyList<String>(), server.platform.console, "nothing was handed to a component that Pano refused")

        // the plugin is updated and restarted: the same state directory, the version Pano asks for
        server.restart(required)

        Await.until(60_000, 200, "marketState is READY") { server.view().getString("marketState") == "READY" }
        awaitDelivery(publicId, "CONFIRMED")
        Await.until(60_000, 200, "order $publicId is FULFILLED") { orderRow(publicId).getString("fulfillmentStatus") == "FULFILLED" }
        assertEquals(listOf("give ${buyer.username} emerald"), server.platform.console)
        assertEquals(required, server.view().getString("mcComponentVersion"), "Pano recorded the updated version")
    }

    // ---- MC-E3 -----------------------------------------------------------------------------------------------------

    @Test
    fun `MC-E3 an offer that never arrived is offered again and executed once`() {
        val server = server()
        val productId = commandProduct(server, listOf("give {username} gold_ingot"))
        val buyer = buyer()

        // the first answer that carries a delivery is dropped by the harness: Pano counts it as offered (SENT), the component never saw it
        server.dropNext("MARKET_SYNC") { it.getJsonArray("deliveries")?.isEmpty == false }

        val publicId = payAndAwaitCompleted(buyer, productId)

        Await.until(120_000, 200, "the offer was dropped") { server.dropped.size == 1 }

        val sent = awaitDelivery(publicId, "SENT")

        assertEquals(emptyList<String>(), server.platform.console, "the component never saw the offer, so nothing ran")

        // the acknowledgement window runs out: the row is due again and the next sync offers the same key
        val key = sent.getString("idempotencyKey")

        db.sql("UPDATE `pano_market_delivery` SET `nextAttemptAt` = ? WHERE `id` = ?", System.currentTimeMillis() - 60_000, sent.getLong("id"))
        server.component.runtime.syncSoon()

        awaitDelivery(publicId, "CONFIRMED")
        assertEquals(listOf("give ${buyer.username} gold_ingot"), server.platform.console, "executed once")

        val offers = server.received("MARKET_SYNC").count { frame -> frame.getJsonArray("deliveries").any { (it as JsonObject).getString("key") == key } }

        assertTrue(offers >= 2, "the key was offered again after the lost answer (offered $offers times)")
    }

    @Test
    fun `MC-E3 a lost acknowledgement makes the component report the result again and the command still runs once`() {
        val server = server()
        val productId = commandProduct(server, listOf("give {username} iron_ingot"))
        val buyer = buyer()

        // the answer that acknowledges the result is dropped: the component does not know Pano took it
        server.dropNext("MARKET_SYNC") { it.getJsonArray("acked")?.isEmpty == false }

        val publicId = payAndAwaitCompleted(buyer, productId)
        val confirmed = awaitDelivery(publicId, "CONFIRMED")
        val key = confirmed.getString("idempotencyKey")

        Await.until(120_000, 200, "the acknowledgement was dropped") { server.dropped.size == 1 }
        // ... the component reports again, this time the answer reaches it, and the store is clean
        Await.until(120_000, 200, "the result is acknowledged and cleared") { server.component.runtime.status().engine.unacked == 0 }

        val reports = server.syncRequests().count { request -> request.getJsonArray("results").any { (it as JsonObject).getString("key") == key } }

        assertTrue(reports >= 2, "the result of $key was reported again after the lost answer (reported $reports times)")
        assertEquals(1, server.platform.ran("give ${buyer.username} iron_ingot"), "executed once")
        assertEquals(1, server.platform.console.size)
        Await.until(60_000, 200, "order $publicId is FULFILLED") { orderRow(publicId).getString("fulfillmentStatus") == "FULFILLED" }
    }

    // ---- MC-E4 -----------------------------------------------------------------------------------------------------

    @Test
    fun `MC-E4 a MARKET_PURCHASE debits the credits and delivers, a replay of the operationId returns the same order`() {
        val server = server()
        val productId = commandProduct(server, listOf("give {username} netherite_ingot"), price = "2.00", creditPrice = "4.00")
        val buyer = buyer()
        val start = giveCredits(buyer, 10)
        val operationId = UUID.randomUUID().toString()

        assertEquals(1000L, start)

        val bought = purchase(server, buyer.username, productId, operationId)

        assertTrue(bought.accepted, bought.reason)
        assertEquals(true, bought.ok, bought.code)
        assertNotNull(bought.orderPublicId)
        assertEquals(4.0, bought.creditTotal!!, 0.0001)
        assertEquals(6.0, bought.balance!!, 0.0001)
        assertEquals(600L, creditMinor(buyer.userId))

        val order = orderRow(bought.orderPublicId!!)

        assertEquals("INGAME", order.getString("source"))
        assertEquals("COMPLETED", order.getString("status"))
        assertEquals("mc:${server.serverId}:$operationId", order.getString("idempotencyKey"))

        // delivered: the row is picked up by the connected server without anybody asking
        server.component.runtime.syncSoon()
        awaitDelivery(bought.orderPublicId!!, "CONFIRMED")
        assertEquals(listOf("give ${buyer.username} netherite_ingot"), server.platform.console)

        // the same operationId again: the same order, no second debit, no second order
        val replay = purchase(server, buyer.username, productId, operationId)

        assertEquals(true, replay.ok, replay.code)
        assertEquals(bought.orderPublicId, replay.orderPublicId)
        assertEquals(6.0, replay.balance!!, 0.0001)
        assertEquals(600L, creditMinor(buyer.userId))
        assertEquals(1L, db.count("market_order", "`userId` = ?", buyer.userId))
        assertEquals(1, server.platform.console.size, "the replay delivered nothing more")
    }

    @Test
    fun `MC-E4 a purchase that cannot happen answers its code and takes nothing`() {
        val server = server()
        val buyer = buyer()
        val rich = giveCredits(buyer, 50)

        assertEquals(5000L, rich)

        fun ordersOf(b: E2eBuyer) = db.count("market_order", "`userId` = ?", b.userId)

        fun failed(answer: MarketPurchaseMessage, code: String): MarketPurchaseMessage {
            assertTrue(answer.accepted, "the request was accepted: ${answer.reason}")
            assertEquals(false, answer.ok)
            assertEquals(code, answer.code)

            return answer
        }

        // out of stock: the last piece goes to the first buyer
        val last = creditProduct(stock = 1)

        assertEquals(true, purchase(server, buyer.username, last).ok)

        val second = buyer()

        giveCredits(second, 50)
        failed(purchase(server, second.username, last), "OUT_OF_STOCK")
        assertEquals(0L, ordersOf(second))
        assertEquals(5000L, creditMinor(second.userId), "nothing was taken")

        // per-player limit
        val once = creditProduct(extra = mapOf("limitPerPlayer" to "1"))

        assertEquals(true, purchase(server, second.username, once).ok)

        val limit = failed(purchase(server, second.username, once), "PURCHASE_LIMIT_REACHED")

        assertEquals(once, (limit.extras!!["productId"] as Number).toLong())
        assertEquals(1L, ordersOf(second), "only the first one exists")

        // not enough credits: the answer names the balance
        val poor = buyer()

        giveCredits(poor, 1)

        val broke = failed(purchase(server, poor.username, creditProduct()), "INSUFFICIENT_CREDITS")

        assertEquals(1.0, (broke.extras!!["balance"] as Number).toDouble(), 0.0001)
        assertEquals(0L, ordersOf(poor))
        assertEquals(100L, creditMinor(poor.userId))

        // a player without a Pano account
        failed(purchase(server, "NoAccount" + System.nanoTime().toString(36).takeLast(6), creditProduct()), "LOGIN_REQUIRED")

        // a blocked buyer
        val blockedBuyer = buyer()

        giveCredits(blockedBuyer, 50)

        val blockId = admin.post("${MarketPaths.PANEL_ROOT}/blocks", JsonObject().put("type", "PLAYER").put("value", blockedBuyer.username).put("reason", "e2e MC-E4")).ok().obj().getLong("id").toString()

        blocks += blockId
        failed(purchase(server, blockedBuyer.username, creditProduct()), "BUYER_BLOCKED")
        assertEquals(0L, ordersOf(blockedBuyer))
        assertEquals(5000L, creditMinor(blockedBuyer.userId))
        admin.delete("${MarketPaths.PANEL_ROOT}/blocks/$blockId").ok()
        blocks.clear()

        // a legal text that is required: refused without the confirmation (the answer names the text), accepted with it
        val title = "MC-E4 terms ${System.nanoTime().toString(36)}"
        val legalId = admin.post("${MarketPaths.PANEL_ROOT}/settings/legal", JsonObject().put("locale", "en-US").put("title", title).put("content", "<p>The terms.</p>")).ok().obj().getLong("id")
        val legalBuyer = buyer()

        giveCredits(legalBuyer, 50)

        val legalProduct = creditProduct()

        session.withSettings(JsonObject().put("legalTextRequired", true)) {
            val refused = failed(purchase(server, legalBuyer.username, legalProduct), "LEGAL_ACCEPTANCE_REQUIRED")

            assertEquals(legalId, (refused.extras!!["legalTextId"] as Number).toLong())
            failed(purchase(server, legalBuyer.username, legalProduct, legalTextId = legalId + 100_000), "LEGAL_ACCEPTANCE_REQUIRED")
            assertEquals(0L, ordersOf(legalBuyer))
            assertEquals(5000L, creditMinor(legalBuyer.userId))

            val accepted = purchase(server, legalBuyer.username, legalProduct, legalTextId = legalId)

            assertEquals(true, accepted.ok, accepted.code)
            assertEquals(legalId, orderRow(accepted.orderPublicId!!).getLong("legalTextId"), "the accepted text is stored with the order")
        }
    }

    // ---- MC-E5 -----------------------------------------------------------------------------------------------------

    @Test
    fun `MC-E5 a player actor without the Pano node gets NO_PERMISSION and nothing is posted`() {
        val server = server()
        val actor = buyer(canPay = false)
        val target = buyer()
        val before = creditMinor(target.userId)
        val logs = lastLogId()
        val operationId = UUID.randomUUID().toString()

        val refused = adminOp(server, AdminOp.GIVE_CREDITS, AdminActor(false, actor.username, null), target.username, 25.0, operationId)

        assertTrue(refused.accepted, refused.reason)
        assertEquals(false, refused.ok)
        assertEquals("NO_PERMISSION", refused.code)
        assertNull(txOf(server.serverId, operationId), "no ledger transaction exists")
        assertEquals(before, creditMinor(target.userId))
        assertEquals(emptyList<Row>(), ingameLogs(logs), "no activity log")

        // the in-game command shows the refusal to the sender, still nothing posted
        Await.until(60_000, 200, "the component holds the panel settings") { server.features.config.remote != null }

        val sender = FakeMcSender(actor.username, nodes = setOf("panomarket.admin.credits.give"))

        server.features.commands.execute("panomarket", sender, listOf("credits", "give", target.username, "25"))
        Await.until(30_000, 100, "the sender was told") { sender.lines.isNotEmpty() }
        assertEquals(before, creditMinor(target.userId))
        assertEquals(emptyList<Row>(), ingameLogs(logs))
    }

    @Test
    fun `MC-E5 a player actor with the Pano node posts a ledger transaction and an activity log, the console is allowed without a log`() {
        val server = server()
        val actor = buyer(canPay = true)
        val target = buyer()
        val before = creditMinor(target.userId)

        Await.until(60_000, 200, "the component holds the panel settings") { server.features.config.remote != null }

        // the in-game command of a player whose Pano account holds the PAY node
        val logs = lastLogId()
        val sender = FakeMcSender(actor.username, nodes = setOf("panomarket.admin.credits.give"))

        server.features.commands.execute("panomarket", sender, listOf("credits", "give", target.username, "7.5", "welcome", "gift"))
        Await.until(30_000, 100, "the credits arrived") { creditMinor(target.userId) == before + 750 }
        Await.until(30_000, 100, "the sender was told") { sender.lines.isNotEmpty() }

        val tx = db.sql(
            "SELECT * FROM `pano_market_credit_tx` WHERE `userId` = ? AND `idempotencyKey` LIKE ? ORDER BY `id` DESC", target.userId, "mc:${server.serverId}:%"
        ).first()

        assertEquals("GRANT", tx.getString("type"))
        assertEquals(750L, tx.getLong("amount"))
        assertEquals(actor.userId, tx.getLong("actorUserId"), "the ledger names the Pano account of the actor")
        assertTrue(tx.getString("note").contains("welcome gift"), tx.getString("note"))

        val written = Await.untilValue(30_000, 100, "the activity log was written") { ingameLogs(logs).takeIf { it.isNotEmpty() } }

        assertEquals(1, written.size, "one activity log")
        assertEquals(actor.userId, written.single().getLong("userId"))
        assertTrue(written.single().getString("details").contains(target.username))

        // a direct request with the same operation id is a replay: no second transaction, no second log
        val operationId = tx.getString("idempotencyKey").substringAfterLast(':')
        val again = adminOp(server, AdminOp.GIVE_CREDITS, AdminActor(false, actor.username, null), target.username, 7.5, operationId, "welcome gift")

        assertEquals(true, again.ok, again.code)
        assertEquals(before + 750, creditMinor(target.userId))
        assertEquals(1, ingameLogs(logs).size)

        // the console: allowed, no activity log (it has no Pano user), the ledger carries the note
        val consoleLogs = lastLogId()
        val consoleOp = UUID.randomUUID().toString()
        val done = adminOp(server, AdminOp.GIVE_CREDITS, AdminActor(true, null, null), target.username, 2.0, consoleOp)

        assertEquals(true, done.ok, done.code)
        assertEquals((before + 750 + 200) / 100.0, done.balance!!, 0.0001)

        val consoleTx = txOf(server.serverId, consoleOp)!!

        assertEquals(200L, consoleTx.getLong("amount"))
        assertNull(consoleTx.getValue("actorUserId"), "the console is no Pano user")
        assertTrue(consoleTx.getString("note").contains("console"), consoleTx.getString("note"))
        assertEquals(emptyList<Row>(), ingameLogs(consoleLogs), "console operations are in the ledger note, not in the activity log")

        // and the console typing the command
        val console = FakeMcSender("CONSOLE", isConsole = true)

        server.features.commands.execute("panomarket", console, listOf("credits", "give", target.username, "1"))
        Await.until(30_000, 100, "the console credits arrived") { creditMinor(target.userId) == before + 750 + 200 + 100 }
        assertEquals(emptyList<Row>(), ingameLogs(consoleLogs))
    }

    // ---- MC-E6 -----------------------------------------------------------------------------------------------------

    @Test
    fun `MC-E6 MARKET_ECONOMY deposits and withdraws as EXTERNAL_IN and EXTERNAL_OUT, an overdraw and a switched-off bridge are refused`() {
        val server = server()
        val buyer = buyer()
        val start = giveCredits(buyer, 10)

        assertEquals(1000L, start)

        // the bridge is OFF by default: a non-transient refusal, nothing posted
        assertEquals(EconomyAnswer.Refused("VAULT_DISABLED", null), economy(server, EconomyOp.DEPOSIT, buyer.username, 5.0))
        assertEquals(1000L, creditMinor(buyer.userId))

        putOverride(server, JsonObject().put("mcVaultMode", "CONVERT"))

        val deposit = UUID.randomUUID().toString()

        assertEquals(EconomyAnswer.Ok(15.0), economy(server, EconomyOp.DEPOSIT, buyer.username, 5.0, deposit))
        assertEquals(1500L, creditMinor(buyer.userId))
        assertEquals("EXTERNAL_IN", txOf(server.serverId, deposit)!!.getString("type"))
        assertEquals(500L, txOf(server.serverId, deposit)!!.getLong("amount"))

        // a repeated id answers from the first transaction, nothing is posted twice
        assertEquals(EconomyAnswer.Ok(15.0), economy(server, EconomyOp.DEPOSIT, buyer.username, 5.0, deposit))
        assertEquals(1500L, creditMinor(buyer.userId))

        val withdraw = UUID.randomUUID().toString()

        assertEquals(EconomyAnswer.Ok(12.0), economy(server, EconomyOp.WITHDRAW, buyer.username, 3.0, withdraw))
        assertEquals(1200L, creditMinor(buyer.userId))
        assertEquals("EXTERNAL_OUT", txOf(server.serverId, withdraw)!!.getString("type"))
        assertEquals(300L, txOf(server.serverId, withdraw)!!.getLong("amount"))

        // overdraw: refused with the balance, nothing posted
        val overdraw = UUID.randomUUID().toString()

        assertEquals(EconomyAnswer.Refused("INSUFFICIENT_CREDITS", 12.0), economy(server, EconomyOp.WITHDRAW, buyer.username, 500.0, overdraw))
        assertNull(txOf(server.serverId, overdraw))
        assertEquals(1200L, creditMinor(buyer.userId))

        // a compensation id (`<id>:undo`) is an ordinary operation id: the undo of the deposit takes the 5 credits out again
        assertEquals(EconomyAnswer.Ok(7.0), economy(server, EconomyOp.WITHDRAW, buyer.username, 5.0, "$deposit:undo"))
        assertEquals("EXTERNAL_OUT", txOf(server.serverId, "$deposit:undo")!!.getString("type"))
        assertEquals(700L, creditMinor(buyer.userId))

        // BALANCE reads and records nothing
        val ledgerRows = db.count("market_credit_tx", "`userId` = ?", buyer.userId)

        assertEquals(EconomyAnswer.Ok(7.0), economy(server, EconomyOp.BALANCE, buyer.username, null))
        assertEquals(ledgerRows, db.count("market_credit_tx", "`userId` = ?", buyer.userId))

        // the override goes back to OFF: refused again
        putOverride(server, JsonObject().put("mcVaultMode", "OFF"))
        assertEquals(EconomyAnswer.Refused("VAULT_DISABLED", null), economy(server, EconomyOp.DEPOSIT, buyer.username, 1.0))
        assertEquals(700L, creditMinor(buyer.userId))
    }

    // ---- MC-E7 -----------------------------------------------------------------------------------------------------

    @Test
    fun `MC-E7 a per-server override changes the configHash and the component pulls the new configuration`() {
        val server = server()
        val other = server()

        Await.until(60_000, 200, "the components hold their first configuration") { server.features.config.remote != null && other.features.config.remote != null }

        val otherHash = other.features.config.remote!!.configHash

        val first = server.features.config.remote!!
        val firstHash = first.configHash

        assertTrue(firstHash.isNotBlank())
        assertFalse(first.settings.mcBroadcast, "the panel default")
        assertEquals(firstHash, server.received("MARKET_CONFIG").firstOrNull()?.getString("configHash") ?: firstHash)

        val pulled = server.sentFrames.count { it.first == "MARKET_CONFIG" }

        // an override for this server only
        putOverride(server, JsonObject().put("mcBroadcast", true).put("mcJoinNotifications", false))

        Await.until(120_000, 200, "the component re-pulled and holds the new hash") { server.features.config.remote?.configHash?.let { it != firstHash } == true }

        val next = server.features.config.remote!!

        assertNotEquals(firstHash, next.configHash)
        assertTrue(next.settings.mcBroadcast, "the override reached the component")
        assertFalse(next.settings.mcJoinNotifications)
        assertTrue(server.sentFrames.count { it.first == "MARKET_CONFIG" } > pulled, "the component asked again (MARKET_CONFIG)")
        assertEquals(next.configHash, Await.untilValue(60_000, 200, "a sync answer carries the new hash") {
            server.received("MARKET_SYNC").lastOrNull { it.getString("configHash") == next.configHash }?.getString("configHash")
        })

        // a request that already holds the hash gets no body (unchanged)
        val unchanged = ask(server, MarketConfigRequest(server.componentVersion, have = next.configHash), MarketConfigMessage::class.java)

        assertTrue(unchanged.accepted, unchanged.reason)
        assertEquals(next.configHash, unchanged.configHash)
        assertNull(unchanged.settings, "an unchanged answer carries no settings")

        // clearing the override brings the first hash back
        putOverride(server, null)
        Await.until(120_000, 200, "the first configuration is back") { server.features.config.remote?.configHash == firstHash }
        assertFalse(server.features.config.remote!!.settings.mcBroadcast)

        // the override was for this server only: another server of the store never moved
        assertEquals(otherHash, other.features.config.remote!!.configHash)
        assertNotEquals(firstHash, otherHash, "the hash covers the identity of the server too")
    }

    // the query seam is part of the same wire: one read so the harness proves MARKET_QUERY end to end as well
    @Test
    fun `MC-E4 MARKET_QUERY BALANCE answers the credits of a linked player and registered=false for a stranger`() {
        val server = server()
        val buyer = buyer()

        giveCredits(buyer, 12)

        val known = ask(server, MarketQueryRequest(server.componentVersion, type = QueryType.BALANCE, player = PlayerRef(buyer.username, null), page = null, args = null), MarketQueryMessage::class.java)

        assertTrue(known.accepted, known.reason)
        assertEquals(12.0, known.data!!.balance!!, 0.0001)

        val stranger = ask(
            server, MarketQueryRequest(server.componentVersion, type = QueryType.BALANCE, player = PlayerRef("Nobody" + System.nanoTime().toString(36).takeLast(6), null), page = null, args = null),
            MarketQueryMessage::class.java
        )

        assertTrue(stranger.accepted, stranger.reason)
        assertEquals(false, stranger.data?.registered)
    }
}
