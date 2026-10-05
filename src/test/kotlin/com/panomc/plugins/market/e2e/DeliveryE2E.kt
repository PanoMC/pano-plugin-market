package com.panomc.plugins.market.e2e

import com.panomc.plugins.market.e2e.support.E2eTestBase
import com.panomc.plugins.market.support.Await
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Row
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * The inline delivery engine on a real instance (MK-102, 08 sections 4.1, 5 to 7, 17): a paid order with a `CREDIT` and a `PERMISSION via=PANO`
 * action, once for a registered buyer and once as a gift for a name that has no Pano user. The platform adapters (`PlatformPermissionWriter`,
 * `PlatformPlayerAccounts`), the production wiring of `OrderRouteSupport` (`DeliveryEffects` inside `InvoiceEffects`, the webhook reporter) and the
 * registration of the `delivery` job in `MarketScheduler` run here for the first time against the platform's own tables.
 *
 * The scheduler stats are not part of `GET /health` yet (its `jobs` array is a stub), so the job's registration is proven by its effect: rows that
 * only the `delivery` job can move (`PENDING` to `CONFIRMED` with `attempts = 1`) after the payment webhook.
 */
class DeliveryE2E : E2eTestBase() {
    override val tag = "dlv"

    private val rankGroup = "e2e-rank"
    private val rawNode = "essentials.e2efly"
    private val sequence = AtomicInteger()

    /** `group.<name>` needs a group to join: created through the permission snapshot the way the panel does, read-modify-write of the whole grid. */
    private fun ensureRankGroup() {
        val snapshot = admin.get("/api/panel/permission/snapshot").ok().obj()
        val groups = snapshot.getJsonArray("groups") ?: JsonArray()

        if (groups.any { (it as JsonObject).getString("name") == rankGroup }) return

        groups.add(JsonObject().put("name", rankGroup).put("displayName", rankGroup))
        admin.post(
            "/api/panel/permission/snapshot",
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
}
