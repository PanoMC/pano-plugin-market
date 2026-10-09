package com.panomc.plugins.market.e2e

import com.panomc.plugins.market.e2e.support.E2eTestBase
import com.panomc.plugins.market.support.Await
import com.panomc.plugins.market.support.FakePayGateway
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Row
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import java.security.KeyPairGenerator
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import com.panomc.plugins.market.util.MarketPaths

/**
 * The inline delivery engine on a real instance (MK-102, 08 sections 4.1, 5 to 7, 17): a paid order with a `CREDIT` and a `PERMISSION via=PANO`
 * action, once for a registered buyer and once as a gift for a name that has no Pano user. The platform adapters (`PlatformPermissionWriter`,
 * `PlatformPlayerAccounts`), the production wiring of `OrderRouteSupport` (`DeliveryEffects` inside `InvoiceEffects`, the webhook reporter) and the
 * registration of the `delivery` job in `MarketScheduler` run here for the first time against the platform's own tables.
 *
 * The scheduler stats are not part of `GET /health` yet (its `jobs` array is a stub), so the job's registration is proven by its effect: rows that
 * only the `delivery` job can move (`PENDING` to `CONFIRMED` with `attempts = 1`) after the payment webhook.
 *
 * E2E-08 adds the scenarios D-01 to D-06 of 17 section 9.6 (a command that waits for a server, the explicit re-run, the action webhook with its HMAC and
 * retries, the webhook target policy, a timed product that expires, a refund before a delayed delivery ran). Time travel is by row rewind only
 * (`webhook_delivery.nextAttemptAt`, `market_entitlement.startsAt` / `expiresAt`).
 */
class DeliveryE2E : E2eTestBase() {
    override val tag = "dlv"

    private val rankGroup = "e2e-rank"
    private val rawNode = "essentials.e2efly"
    private val sequence = AtomicInteger()

    /** `group.<name>` needs a group to join: created through the permission snapshot the way the panel does, read-modify-write of the whole grid. */
    private fun ensureRankGroup() = ensureGroup(rankGroup)

    private fun ensureGroup(name: String) {
        val snapshot = admin.get("/api/v1/panel/permission/snapshot").ok().obj()
        val groups = snapshot.getJsonArray("groups") ?: JsonArray()

        if (groups.any { (it as JsonObject).getString("name") == name }) return

        groups.add(JsonObject().put("name", name).put("displayName", name))
        admin.post(
            "/api/v1/panel/permission/snapshot",
            JsonObject().put("groups", groups).put("tracks", snapshot.getJsonArray("tracks") ?: JsonArray()).put("nodes", snapshot.getJsonArray("nodes") ?: JsonArray())
        ).ok()
    }

    /** A product of its own: 5 credits and a rank (`group.e2e-rank`) plus a raw game permission, both through the Pano permission tables. */
    private fun rankProduct(): Long {
        ensureRankGroup()

        val n = sequence.incrementAndGet()
        val actions = JsonArray()
            .add(JsonObject().put("id", "a1").put("type", "CREDIT").put("phase", "GRANT").put("value", 5))
            .add(
                JsonObject().put("id", "a2").put("type", "PERMISSION").put("phase", "GRANT").put("via", "PANO")
                    .put("value", JsonArray().add("group.$rankGroup").add(rawNode))
            )

        return catalog.product(
            key = "DLV$n", slug = "e2e-dlv-${System.currentTimeMillis().toString(36)}-$n", name = "Delivery rank $n", price = "4.00", actions = actions.encode()
        )
    }

    private fun deliveries(publicId: String): List<Row> = db.sql(
        "SELECT d.* FROM `pano_market_delivery` d JOIN `pano_market_order` o ON o.`id` = d.`orderId` WHERE o.`publicId` = ? ORDER BY d.`id`", publicId
    )

    /** Waits for the `delivery` job: [count] rows exist and none is open any more. */
    private fun awaitSettled(publicId: String, count: Int): List<Row> = Await.untilValue(150_000, 1000, "the $count deliveries of $publicId are settled") {
        deliveries(publicId).takeIf { rows -> rows.size == count && rows.none { it.getString("status") in setOf("PENDING", "SCHEDULED", "SENDING") } }
    }

    /** The Pano permission rows of a user: node to context (an `active` row only). */
    private fun nodesOf(userId: Long): Map<String, JsonObject> =
        db.sql("SELECT `node`, `context`, `active` FROM `pano_permission_node` WHERE `holderType` = 'USER' AND `holderId` = ?", userId)
            .filter { row -> row.getValue("active").let { it == true || (it as? Number)?.toInt() == 1 } }
            .associate { it.getString("node") to JsonObject(it.getString("context")) }

    private fun payAndAwaitCompleted(client: com.panomc.plugins.market.e2e.support.E2eClient, body: JsonObject): String {
        val publicId = publicIdOf(checkout(client, body).ok())

        payViaFake(publicId)
        awaitOrder(publicId, "COMPLETED")

        return publicId
    }

    @Test
    fun `a paid order for a registered buyer delivers the credit through the ledger and the rank into the Pano permission tables`() {
        val productId = rankProduct()
        val buyer = buyer()
        val publicId = payAndAwaitCompleted(buyer.client, cart(line(productId)))
        val orderId = orderRow(publicId).getLong("id")

        // the rows were planned inside the O2 transaction (the order is COMPLETED), the job executes them
        val rows = awaitSettled(publicId, 2)
        val byType = rows.associateBy { it.getString("actionType") }
        val credit = byType.getValue("CREDIT")
        val permission = byType.getValue("PERMISSION")

        for (row in rows) {
            assertEquals("CONFIRMED", row.getString("status"), "${row.getString("actionType")} row: ${row.getString("lastErrorCode")}")
            assertEquals("GRANT", row.getString("phase"))
            assertEquals(1, row.getInteger("attempts"), "claimed and executed once by the delivery job")
            assertEquals("INLINE", row.getString("transport"))
        }

        val itemId = db.long("SELECT `id` FROM `pano_market_order_item` WHERE `orderId` = ?", orderId)
        assertEquals("$itemId:a1:0:0:GRANT:0", credit.getString("idempotencyKey"))
        assertEquals("$itemId:a2:0:0:GRANT:0", permission.getString("idempotencyKey"))

        // CREDIT: one ACTION transaction with the delivery key, 5.00 credits on the buyer's account
        val tx = db.sql("SELECT * FROM `pano_market_credit_tx` WHERE `type` = 'ACTION' AND `deliveryId` = ?", credit.getLong("id"))

        assertEquals(1, tx.size)
        assertEquals("delivery:${credit.getLong("id")}", tx[0].getString("idempotencyKey"))
        assertEquals(500L, tx[0].getLong("amount"))
        assertEquals(buyer.userId, tx[0].getLong("userId"))
        assertEquals(500L, db.long("SELECT `balance` FROM `pano_market_credit_account` WHERE `userId` = ?", buyer.userId))

        // PERMISSION via PANO: user rows of the platform; the group node has no `pano` key, the raw game node is kept out of Pano's own checks
        val nodes = nodesOf(buyer.userId)

        assertTrue("group.$rankGroup" in nodes, "the rank node was written: ${nodes.keys}")
        assertTrue(rawNode in nodes, "the raw node was written: ${nodes.keys}")
        assertFalse(nodes.getValue("group.$rankGroup").containsKey("pano"), "a group node carries no pano=false")
        assertEquals(false, nodes.getValue(rawNode).getBoolean("pano"), "a raw node carries pano=false")
        assertEquals(1L, db.count("permission_node", "`holderType` = 'USER' AND `holderId` = ? AND `node` = ?", buyer.userId, rawNode))
        assertEquals(1L, db.count("permission_node", "`holderType` = 'USER' AND `holderId` = ? AND `node` = ?", buyer.userId, "group.$rankGroup"))
        assertNull(permission.getString("lastErrorCode"))

        assertEquals("FULFILLED", orderRow(publicId).getString("fulfillmentStatus"))
        assertEquals(1L, db.count("market_entitlement", "`orderId` = ? AND `status` = 'ACTIVE'", orderId))
        assertEquals(0L, orderEvents(publicId, "DELIVERY_FAILED"))
    }

    @Test
    fun `a gift for a name without a Pano user fails the credit with NO_ACCOUNT, still gives the rank and creates exactly one user`() {
        val productId = rankProduct()
        val buyer = buyer()
        val recipient = "e2edg" + System.nanoTime().toString(36).takeLast(7)

        assertEquals(0L, db.count("user", "`username` = ?", recipient))

        val publicId = payAndAwaitCompleted(buyer.client, cart(line(productId)).put("recipientUsername", recipient))
        val rows = awaitSettled(publicId, 2)
        val byType = rows.associateBy { it.getString("actionType") }
        val credit = byType.getValue("CREDIT")
        val permission = byType.getValue("PERMISSION")

        // CREDIT: no account for that name, nothing is parked on a shell account; the failure is final and visible
        assertEquals("FAILED", credit.getString("status"))
        assertEquals("NO_ACCOUNT", credit.getString("lastErrorCode"))
        assertEquals(0L, db.count("market_credit_tx", "`deliveryId` = ?", credit.getLong("id")))
        assertEquals(1L, orderEvents(publicId, "DELIVERY_FAILED"))

        // PERMISSION: the user is created the way a join of that player would create it, and holds both nodes
        assertEquals("CONFIRMED", permission.getString("status"), permission.getString("lastErrorCode"))
        assertEquals(1L, db.count("user", "`username` = ?", recipient), "exactly one new user row for the recipient")

        val userId = db.long("SELECT `id` FROM `pano_user` WHERE `username` = ?", recipient)!!
        val nodes = nodesOf(userId)

        assertTrue("group.$rankGroup" in nodes && rawNode in nodes, "both nodes of the rank were written for the new user: ${nodes.keys}")
        assertFalse(nodes.getValue("group.$rankGroup").containsKey("pano"))
        assertEquals(false, nodes.getValue(rawNode).getBoolean("pano"))
        assertEquals(0L, db.count("permission_node", "`holderType` = 'USER' AND `holderId` = ? AND `node` = ?", buyer.userId, rawNode), "the buyer got nothing")

        // one delivered, one failed: PARTIAL (08 section 13)
        assertEquals("PARTIAL", orderRow(publicId).getString("fulfillmentStatus"))
    }

    // --- E2E-08: D-01 to D-06 ------------------------------------------------------------------------------------------

    private val oneDayMs = 86_400_000L
    private var registeredServerId: Long? = null

    @AfterAll
    fun removeRegisteredServer() {
        val id = registeredServerId ?: return

        // the server only exists for D-01 (its delivery was cancelled by the scenario): remove it so later classes of the run see the instance without one
        val answer = admin.post("/api/v1/panel/servers/$id/delete", JsonObject().put("currentPassword", session.env.adminPassword()))

        check(answer.status in 200..299) { "removing the test server answered ${answer.status} ${answer.error}" }
    }

    /**
     * A server the platform has accepted but that never connects: `POST /api/server/connect` is the same REST call the Minecraft plugin makes first
     * (it answers a token and the AES key, wrapped for the public key we send), then the panel accepts the connect request. The row is
     * `permissionGranted = 1`, so a `COMMAND` action can be saved against it, and it is not connected, so its deliveries wait.
     */
    private fun grantedServer(): Long {
        registeredServerId?.let { return it }

        val keys = KeyPairGenerator.getInstance("RSA").also { it.initialize(2048) }.generateKeyPair()
        val platformCode = admin.get("/api/v1/panel/basicData").ok().obj().getValue("platformServerMatchKey").toString()
        val name = "e2e-srv-" + System.nanoTime().toString(36).takeLast(8)

        visitor("mc").post(
            "/api/v1/server/connect",
            JsonObject().put("platformCode", platformCode).put("serverName", name).put("host", "127.0.0.1").put("port", 25565).put("playerCount", 0)
                .put("maxPlayerCount", 20).put("serverType", "PAPER").put("serverVersion", "1.21").put("startTime", System.currentTimeMillis())
                .put("publicKey", Base64.getEncoder().encodeToString(keys.public.encoded))
        ).ok()

        val id = db.long("SELECT `id` FROM `pano_server` WHERE `name` = ? ORDER BY `id` DESC LIMIT 1", name) ?: throw AssertionError("the connect request created no server row")

        admin.post("/api/v1/panel/servers/$id/accept", JsonObject()).ok()
        registeredServerId = id

        return id
    }

    private fun action(id: String, type: String, value: Any, phase: String = "GRANT"): JsonObject =
        JsonObject().put("id", id).put("type", type).put("phase", phase).put("value", value)

    private fun product(name: String, price: String, actions: JsonArray, extra: Map<String, String> = emptyMap()): Long {
        val n = sequence.incrementAndGet()

        return catalog.product(key = "DLV${name.uppercase()}$n", slug = "e2e-${name.lowercase()}-${System.currentTimeMillis().toString(36)}-$n", name = "$name $n", price = price, actions = actions.encode(), extra = extra)
    }

    private fun flag(row: Row, column: String): Boolean = row.getValue(column).let { it == true || (it as? Number)?.toInt() == 1 }

    private fun webhookRows(deliveryId: Long): List<Row> = db.sql("SELECT * FROM `pano_webhook_delivery` WHERE `ownerRef` = ? ORDER BY `id`", "delivery:$deliveryId")

    @Test
    fun `D-01 a command waits for a server - WAITING_SERVER, fulfillment PENDING, listed, the sale is not blocked`() {
        val serverId = grantedServer()
        val productId = product(
            "Diamonds", "2.00",
            JsonArray().add(
                action("a1", "COMMAND", JsonArray().add("give {username} diamond {quantity}")).put("serverMode", "FIXED").put("targetServers", JsonArray().add(serverId)).put("requiresOnline", false)
            )
        )
        val buyer = buyer()
        val publicId = payAndAwaitCompleted(buyer.client, cart(line(productId)))

        // the sale was not blocked by the missing server: the order is COMPLETED, the row waits
        assertEquals("COMPLETED", orderStatus(publicId))

        val row = Await.untilValue(120_000, 1000, "the COMMAND row of $publicId is WAITING_SERVER") {
            deliveries(publicId).singleOrNull()?.takeIf { it.getString("status") == "WAITING_SERVER" }
        }

        assertEquals("COMMAND", row.getString("actionType"))
        assertEquals("GRANT", row.getString("phase"))
        assertEquals(serverId, row.getLong("serverId"))
        assertEquals("PENDING", orderRow(publicId).getString("fulfillmentStatus"), "nothing was delivered yet")
        assertEquals(0L, orderEvents(publicId, "DELIVERY_FAILED"))

        // listed under the status filter (search = the buyer's name, so the rows of other scenarios do not matter)
        val listed = admin.get("${MarketPaths.PANEL_ROOT}/deliveries?status=WAITING_SERVER&search=${buyer.username}").ok().obj().getJsonArray("items").map { it as JsonObject }

        assertEquals(listOf(row.getLong("id")), listed.map { it.getLong("id") })
        assertEquals("WAITING_SERVER", listed.single().getString("status"))
        assertEquals("COMMAND", listed.single().getString("actionType"))

        // an open row is not part of the health queue (it would never drain), and the admin can cancel it
        admin.post("${MarketPaths.PANEL_ROOT}/deliveries/${row.getLong("id")}/cancel", JsonObject()).ok()

        assertEquals("CANCELLED", deliveries(publicId).single().getString("status"))
        assertEquals(0L, admin.get("${MarketPaths.PANEL_ROOT}/deliveries?status=WAITING_SERVER&search=${buyer.username}").ok().obj().getJsonObject("page").getLong("totalItems"))
    }

    @Test
    fun `D-02 an explicit re-run creates new rows with attemptGroup 1, leaves the old rows alone and grants the credit a second time through the new row only`() {
        val productId = product("Rerun", "3.00", JsonArray().add(action("a1", "CREDIT", 5)))
        val buyer = buyer()
        val publicId = payAndAwaitCompleted(buyer.client, cart(line(productId)))
        val orderId = orderRow(publicId).getLong("id")
        val first = awaitSettled(publicId, 1).single()
        val itemId = db.long("SELECT `id` FROM `pano_market_order_item` WHERE `orderId` = ?", orderId)

        assertEquals("CONFIRMED", first.getString("status"))
        assertEquals(0, first.getInteger("attemptGroup"))
        assertEquals("$itemId:a1:0:0:GRANT:0", first.getString("idempotencyKey"))
        assertEquals(500L, db.long("SELECT `balance` FROM `pano_market_credit_account` WHERE `userId` = ?", buyer.userId))

        fun snapshot(row: Row) = listOf(row.getLong("id"), row.getString("status"), row.getInteger("attempts"), row.getInteger("attemptGroup"), row.getString("idempotencyKey"), row.getLong("confirmedAt"), row.getString("result"))

        val before = snapshot(first)

        // the effective row took effect, so this grants again: the admin holds PAY as well as OM
        val answer = admin.post("${MarketPaths.PANEL_ROOT}/orders/$orderId/deliveries/rerun", JsonObject().put("all", true)).ok().obj()

        assertEquals(1, answer.getInteger("created"))
        assertEquals(0, answer.getInteger("skipped"))

        val rows = awaitSettled(publicId, 2)
        val second = rows.single { it.getInteger("attemptGroup") == 1 }

        assertNotEquals(first.getLong("id"), second.getLong("id"))
        assertEquals("CONFIRMED", second.getString("status"))
        assertEquals("$itemId:a1:0:0:GRANT:1", second.getString("idempotencyKey"))
        assertEquals(before, snapshot(rows.single { it.getLong("id") == first.getLong("id") }), "the old row is untouched")

        // one ACTION transaction per row, keyed by the row: the second grant comes from the new row only
        val firstTx = db.sql("SELECT * FROM `pano_market_credit_tx` WHERE `type` = 'ACTION' AND `deliveryId` = ?", first.getLong("id"))
        val secondTx = db.sql("SELECT * FROM `pano_market_credit_tx` WHERE `type` = 'ACTION' AND `deliveryId` = ?", second.getLong("id"))

        assertEquals(1, firstTx.size)
        assertEquals(1, secondTx.size)
        assertEquals("delivery:${second.getLong("id")}", secondTx.single().getString("idempotencyKey"))
        assertEquals(500L, secondTx.single().getLong("amount"))
        assertEquals(1000L, db.long("SELECT `balance` FROM `pano_market_credit_account` WHERE `userId` = ?", buyer.userId))
        assertEquals(1, orderEvents(publicId, "DELIVERY_RERUN").toInt())

        // the identical request again re-runs the (now second) effective row: again exactly one more row, never a repeat of an old key
        admin.post("${MarketPaths.PANEL_ROOT}/orders/$orderId/deliveries/rerun", JsonObject().put("all", true)).ok()

        val three = awaitSettled(publicId, 3)

        assertEquals(listOf(0, 1, 2), three.map { it.getInteger("attemptGroup") }.sorted())
        assertEquals(3, three.map { it.getString("idempotencyKey") }.toSet().size)
        assertEquals(1500L, db.long("SELECT `balance` FROM `pano_market_credit_account` WHERE `userId` = ?", buyer.userId))
    }

    @Test
    fun `D-03 an action webhook is signed with the HMAC, retried with backoff after 500 and 500, sends the same event id each time and ends SUCCEEDED`() {
        val hook = "d03" + System.nanoTime().toString(36).takeLast(8)
        val secret = "whsec_e2e_" + System.nanoTime().toString(36)

        gateway.hookStatus(hook, 500, 500, 200)

        val productId = product(
            "Hooked", "2.00",
            JsonArray().add(
                action("a1", "WEBHOOK", JsonObject().put("url", "${gateway.baseUrl}/hooks/$hook").put("format", "JSON").put("signing", "HMAC_SHA256").put("secret", secret))
            )
        )
        val buyer = buyer()
        val publicId = payAndAwaitCompleted(buyer.client, cart(line(productId)))
        val delivery = Await.untilValue(60_000, 500, "the WEBHOOK row of $publicId") { deliveries(publicId).singleOrNull() }
        val deliveryId = delivery.getLong("id")

        assertEquals("WEBHOOK", delivery.getString("actionType"))

        // attempt 1 and 2 answer 500: the row waits for its backoff, which the scenario skips by rewinding nextAttemptAt
        for (attempt in 1..2) {
            val failed = Await.untilValue(90_000, 500, "webhook attempt $attempt failed") {
                webhookRows(deliveryId).singleOrNull()?.takeIf { it.getInteger("attempts") == attempt && it.getString("status") == "FAILED" }
            }

            assertEquals(500, failed.getInteger("lastStatusCode"))
            assertTrue(failed.getLong("nextAttemptAt") > System.currentTimeMillis(), "the backoff is in the future")

            db.rewind("webhook_delivery", failed.getLong("id"), "nextAttemptAt", 3_600_000)
        }

        val done = Await.untilValue(90_000, 500, "the webhook of $publicId succeeded") { webhookRows(deliveryId).singleOrNull()?.takeIf { it.getString("status") == "SUCCEEDED" } }

        assertEquals(3, done.getInteger("attempts"))
        assertEquals(200, done.getInteger("lastStatusCode"))

        // the log: three attempts of one row (one event id), and the panel detail says the same
        val detail = admin.get("${com.panomc.platform.route.ApiPaths.PANEL_ROOT}/webhook-deliveries/${done.getLong("id")}").ok().obj()
        val view = detail.getJsonObject("delivery") ?: detail

        assertEquals(3, view.getInteger("attempts"))
        assertEquals("SUCCEEDED", view.getString("status"))

        // what the sink saw
        val requests = gateway.hooks(hook)

        assertEquals(3, requests.size)
        assertEquals(listOf("1", "2", "3"), requests.map { it.header("X-Pano-Attempt") })
        assertEquals(1, requests.map { it.header("X-Pano-Event-Id") }.toSet().size, "the event id is stable across the retries")
        assertEquals(done.getString("eventId"), requests.first().header("X-Pano-Event-Id"))
        assertEquals(1, requests.map { it.bodyText() }.toSet().size, "the body is identical on every attempt")
        assertEquals("delivery:$deliveryId", done.getString("ownerRef"))

        for (request in requests) {
            val header = request.header("X-Pano-Signature") ?: throw AssertionError("no X-Pano-Signature")
            val parts = header.split(',').associate { it.substringBefore('=') to it.substringAfter('=') }

            assertEquals(FakePayGateway.hmac(secret, parts.getValue("t").toLong(), request.body), parts.getValue("v1"), "the signature verifies with the action's secret")
        }

        // D12: the delivery engine heard about it
        val settled = awaitSettled(publicId, 1).single()

        assertEquals("CONFIRMED", settled.getString("status"))
        assertEquals("FULFILLED", orderRow(publicId).getString("fulfillmentStatus"))
    }

    @Test
    fun `D-04 the webhook target policy refuses the metadata address and a file URL in a WEBHOOK action, whatever the private-targets switch of the platform says`() {
        // the flag is the platform's (webhooks.allow-private-targets, doc 06 section 4.1); link-local and non-http addresses are refused with it as well
        val n = sequence.incrementAndGet()

        for (url in listOf("http://169.254.169.254/latest", "file:///etc/passwd")) {
            val product = admin.multipart(
                "POST", "${MarketPaths.PANEL_ROOT}/products",
                mapOf(
                    "name" to "Policy $n", "slug" to "e2e-policy-${System.currentTimeMillis().toString(36)}-$n", "price" to "1.00", "status" to "ACTIVE",
                    "actions" to JsonArray().add(action("a1", "WEBHOOK", JsonObject().put("url", url).put("format", "JSON").put("signing", "NONE"))).encode()
                )
            )

            assertEquals(400, product.status, "$url: ${product.json}")
            assertTrue(product.json.toString().contains("INVALID_WEBHOOK_URL"), "the field error names the rule: ${product.json}")
        }
    }

    @Test
    fun `D-05 a timed product reminds, then expires - the entitlement ends, the EXPIRE row removes the permission, one reminder mail`() {
        val group = "e2e-timed"

        ensureGroup(group)

        val productId = product(
            "Timed", "8.00", JsonArray().add(action("a1", "PERMISSION", JsonArray().add("group.$group"))),
            mapOf("billingMode" to "TIMED", "periodUnit" to "DAY", "periodCount" to "30")
        )
        val buyer = buyer()
        val publicId = payAndAwaitCompleted(buyer.client, cart(line(productId)))
        val orderId = orderRow(publicId).getLong("id")
        val grant = awaitSettled(publicId, 1).single()
        val entitlement = db.sql("SELECT * FROM `pano_market_entitlement` WHERE `orderId` = ?", orderId).single()
        val entitlementId = entitlement.getLong("id")

        assertEquals("CONFIRMED", grant.getString("status"))
        assertEquals("ACTIVE", entitlement.getString("status"))
        assertTrue("group.$group" in nodesOf(buyer.userId), "the rank was granted")
        assertEquals(30 * oneDayMs, entitlement.getLong("expiresAt") - entitlement.getLong("startsAt"), "a period of 30 days")
        assertEquals(0L, db.count("market_mail_outbox", "`kind` = 'EXPIRY_REMINDER' AND `refId` = ?", entitlementId))

        // 2 days before the end: inside subscriptionReminderDays (3) and the period (42 days) is at least twice the lead
        db.rewind("market_entitlement", entitlementId, "startsAt", 40 * oneDayMs)
        db.rewind("market_entitlement", entitlementId, "expiresAt", 28 * oneDayMs)

        val mail = Await.untilValue(90_000, 1000, "the EXPIRY_REMINDER of entitlement $entitlementId") {
            db.sql("SELECT * FROM `pano_market_mail_outbox` WHERE `kind` = 'EXPIRY_REMINDER' AND `refId` = ?", entitlementId).singleOrNull()
        }

        assertEquals("ENTITLEMENT", mail.getString("refType"))
        assertEquals("${buyer.username}@example.com", mail.getString("recipient"))
        assertTrue(mail.getString("status") in setOf("PENDING", "SENDING", "SENT", "FAILED", "SKIPPED"), "status ${mail.getString("status")}")
        assertEquals("ACTIVE", db.string("SELECT `status` FROM `pano_market_entitlement` WHERE `id` = ?", entitlementId), "a reminder does not end anything")
        assertTrue(db.long("SELECT `reminderSentAt` FROM `pano_market_entitlement` WHERE `id` = ?", entitlementId) != null)
        assertTrue("group.$group" in nodesOf(buyer.userId))

        // past the end: expired, and the automatic inverse of the PERMISSION action runs as an EXPIRE row
        db.rewind("market_entitlement", entitlementId, "expiresAt", 3 * oneDayMs)

        Await.until(90_000, 1000, "entitlement $entitlementId EXPIRED") { db.string("SELECT `status` FROM `pano_market_entitlement` WHERE `id` = ?", entitlementId) == "EXPIRED" }

        val rows = Await.untilValue(90_000, 1000, "the EXPIRE row of $publicId is settled") {
            deliveries(publicId).takeIf { r -> r.size == 2 && r.none { it.getString("status") in setOf("PENDING", "SCHEDULED", "SENDING") } }
        }
        val expire = rows.single { it.getString("phase") == "EXPIRE" }
        val ended = db.sql("SELECT * FROM `pano_market_entitlement` WHERE `id` = ?", entitlementId).single()

        assertEquals("PERMISSION", expire.getString("actionType"))
        assertEquals("a1", expire.getString("actionId"))
        assertEquals("CONFIRMED", expire.getString("status"), expire.getString("lastErrorCode"))
        assertEquals("EXPIRED", ended.getString("endReason"))
        assertTrue(ended.getLong("endedAt") != null)
        assertFalse("group.$group" in nodesOf(buyer.userId), "the permission was removed: ${nodesOf(buyer.userId).keys}")
        assertEquals(1L, db.count("market_mail_outbox", "`kind` = 'EXPIRY_REMINDER' AND `refId` = ?", entitlementId), "still exactly one reminder")
        assertEquals("CONFIRMED", rows.single { it.getString("phase") == "GRANT" }.getString("status"), "the grant row is history, not rewritten")
    }

    @Test
    fun `D-06 a refund before a delayed delivery ran cancels the GRANT row and plans no REVOKE`() {
        val productId = product("Delayed", "4.00", JsonArray().add(action("a1", "CREDIT", 5).put("delay", 3600)))
        val buyer = buyer()
        val publicId = payAndAwaitCompleted(buyer.client, cart(line(productId)))
        val orderId = orderRow(publicId).getLong("id")
        val scheduled = Await.untilValue(30_000, 500, "the delayed row of $publicId") { deliveries(publicId).singleOrNull() }

        assertEquals("SCHEDULED", scheduled.getString("status"))
        assertEquals("GRANT", scheduled.getString("phase"))
        assertTrue(scheduled.getLong("runAfter") > System.currentTimeMillis() + 3_000_000L, "an hour away")
        assertEquals(0L, db.count("market_credit_tx", "`deliveryId` = ?", scheduled.getLong("id")))

        // a full refund with revoke: the row that never ran is cancelled, nothing has to be undone. `manual` (money returned outside the gateway, 21 section 3.6)
        // because the fake gateway only refunds a payment whose id it was told in the paid event, and ids of one JVM (`pay_<n>`) repeat across runs on a kept database
        admin.post(
            "${MarketPaths.PANEL_ROOT}/orders/$orderId/refunds", JsonObject().put("revoke", true).put("manual", true).put("reason", "E2E D-06"), mapOf("Idempotency-Key" to idempotencyKey())
        ).ok()
        awaitOrder(publicId, "REFUNDED")

        val rows = deliveries(publicId)

        assertEquals(1, rows.size, "no REVOKE row was planned: ${rows.map { it.getString("phase") + "/" + it.getString("status") }}")
        assertEquals("GRANT", rows.single().getString("phase"))
        assertEquals("CANCELLED", rows.single().getString("status"))
        assertEquals(scheduled.getLong("id"), rows.single().getLong("id"))
        assertEquals(0L, db.count("market_credit_tx", "`userId` = ? AND `type` = 'ACTION'", buyer.userId), "the credit was never granted, and so never taken back")
        assertEquals(0L, db.long("SELECT COALESCE(SUM(`balance`), 0) FROM `pano_market_credit_account` WHERE `userId` = ?", buyer.userId))
    }
}
