package com.panomc.plugins.market.e2e

import com.panomc.platform.route.ApiPaths
import com.panomc.plugins.market.e2e.support.E2eBuyer
import com.panomc.plugins.market.e2e.support.E2eTestBase
import com.panomc.plugins.market.support.Await
import com.panomc.plugins.market.support.FakePayGateway
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Row
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.atomic.AtomicInteger
import com.panomc.plugins.market.util.MarketPaths

/**
 * Subscriptions on a real instance (17 section 9.6, 09 sections 8 to 10): S-01 a merchant-initiated subscription from checkout to a renewal, S-02 a failed
 * renewal that runs through grace to expiry, S-03 cancel at period end and resume, S-04 a gateway-managed subscription driven by the provider's own
 * events, S-05 the cart and guest rules.
 *
 * Time travel is by row rewind only: `market_subscription.nextChargeAt` / `graceEndsAt` / `currentPeriodEnd`. The `SubscriptionJob` ticks every 60 s, so a
 * scenario waits for the tick (`Await`, never a fixed sleep). Every subscription product of these scenarios has a CREDIT and a PERMISSION action, so the
 * delivery rows (grant, renew, the automatic inverse at the end) are visible.
 */
class SubscriptionE2E : E2eTestBase() {
    override val tag = "sub"

    private val sequence = AtomicInteger()
    private val day = 86_400_000L
    private val group = "e2e-sub"
    private val http = HttpClient.newHttpClient()

    // --- fixtures ------------------------------------------------------------------------------------------------------

    private fun ensureGroup() {
        val snapshot = admin.get("/api/v1/panel/permission/snapshot").ok().obj()
        val groups = snapshot.getJsonArray("groups") ?: JsonArray()

        if (groups.any { (it as JsonObject).getString("name") == group }) return

        groups.add(JsonObject().put("name", group).put("displayName", group))
        admin.post(
            "/api/v1/panel/permission/snapshot",
            JsonObject().put("groups", groups).put("tracks", snapshot.getJsonArray("tracks") ?: JsonArray()).put("nodes", snapshot.getJsonArray("nodes") ?: JsonArray())
        ).ok()
    }

    /** A monthly subscription of 6.00 EUR: one credit (GRANT) and the rank `group.e2e-sub` (GRANT, undone automatically at the end). */
    private fun subscriptionProduct(): Long {
        ensureGroup()

        val n = sequence.incrementAndGet()
        val actions = JsonArray()
            .add(JsonObject().put("id", "a1").put("type", "CREDIT").put("phase", "GRANT").put("value", 1))
            .add(JsonObject().put("id", "a2").put("type", "PERMISSION").put("phase", "GRANT").put("value", JsonArray().add("group.$group")))

        return catalog.product(
            key = "SUBE$n", slug = "e2e-sub-${System.currentTimeMillis().toString(36)}-$n", name = "Subscription $n", price = "6.00", actions = actions.encode(),
            extra = mapOf("billingMode" to "SUBSCRIPTION", "periodUnit" to "MONTH", "periodCount" to "1")
        )
    }

    private fun flag(row: Row, column: String): Boolean = row.getValue(column).let { it == true || (it as? Number)?.toInt() == 1 }

    private fun subscriptionOfOrder(publicId: String): Row = db.sql(
        "SELECT s.* FROM `pano_market_subscription` s JOIN `pano_market_order` o ON o.`subscriptionId` = s.`id` WHERE o.`publicId` = ? AND o.`source` <> 'RENEWAL'", publicId
    ).singleOrNull() ?: throw AssertionError("order $publicId has no subscription")

    private fun subscription(id: Long): Row = db.sql("SELECT * FROM `pano_market_subscription` WHERE `id` = ?", id).single()

    private fun status(id: Long): String = subscription(id).getString("status")

    private fun renewalOrders(subscriptionId: Long): List<Row> =
        db.sql("SELECT * FROM `pano_market_order` WHERE `subscriptionId` = ? AND `source` = 'RENEWAL' ORDER BY `id`", subscriptionId)

    private fun deliveriesOfOrder(orderId: Long): List<Row> =
        db.sql("SELECT d.* FROM `pano_market_delivery` d JOIN `pano_market_order_item` i ON i.`id` = d.`orderItemId` WHERE i.`orderId` = ? ORDER BY d.`id`", orderId)

    private fun nodesOf(userId: Long): Set<String> = db.sql(
        "SELECT `node`, `active` FROM `pano_permission_node` WHERE `holderType` = 'USER' AND `holderId` = ?", userId
    ).filter { row -> row.getValue("active").let { it == true || (it as? Number)?.toInt() == 1 } }.map { it.getString("node") }.toSet()

    private fun credits(userId: Long): Long = db.long("SELECT `balance` FROM `pano_market_credit_account` WHERE `userId` = ?", userId) ?: 0L

    private fun mails(kind: String, subscriptionId: Long): List<Row> =
        db.sql("SELECT * FROM `pano_market_mail_outbox` WHERE `kind` = ? AND `refType` = 'SUBSCRIPTION' AND `refId` = ?", kind, subscriptionId)

    /** Charges the stored method received by the gateway: `chargeRecurring` calls whose body carries [token] (the gateway is shared by every class of the run). */
    private fun chargesOf(token: String) = gateway.requests(FakePayGateway.Op.CHARGE).filter { it.bodyText().contains(token) }

    /** One signed event of the fake gateway to `/api/market/payments/<provider>/webhook`; the answer is asserted to be 200. */
    private fun sendEvent(provider: String, type: String, data: JsonObject) {
        val body = gateway.eventBody(type, data, gateway.nextEventId())
        val request = HttpRequest.newBuilder(URI.create("$baseUrl${MarketPaths.SITE_ROOT}/payments/$provider/webhook")).header("Content-Type", "application/json")
            .header("X-Fake-Signature", gateway.signatureHeader(body, FakePayGateway.Signature.VALID)!!).POST(HttpRequest.BodyPublishers.ofByteArray(body)).build()
        val answer = http.send(request, HttpResponse.BodyHandlers.ofString())

        assertEquals(200, answer.statusCode(), "the $type event to $provider was answered ${answer.statusCode()}")
    }

    /** The gateway reports the first payment as paid and hands over a stored method (merchant-initiated recurring). */
    private fun payWithStoredMethod(publicId: String, token: String) {
        val reference = referenceOf(publicId)
        val payment = gateway.payments[reference] ?: throw AssertionError("the gateway knows no payment $reference")

        gateway.setStatus(reference, "paid")
        sendEvent(
            "fake", "payment.succeeded",
            JsonObject().put("reference", reference).put("amount", payment.amount.toPlainString()).put("currency", payment.currency)
                .put("storedMethod", JsonObject().put("token", token).put("label", "Visa 4242"))
        )
        awaitOrder(publicId, "COMPLETED")
    }

    /** A buyer with a freshly activated `MERCHANT` subscription: (buyer, public id of the initial order, subscription id, stored method token). */
    private class Started(val buyer: E2eBuyer, val publicId: String, val subscriptionId: Long, val token: String)

    private fun startMerchantSubscription(productId: Long): Started {
        val buyer = buyer()
        val publicId = publicIdOf(checkout(buyer.client, cart(line(productId))).ok())
        val token = "tok_e2e_" + System.nanoTime().toString(36)

        assertEquals("PENDING", subscriptionOfOrder(publicId).getString("status"), "a subscription is PENDING until the first payment")

        payWithStoredMethod(publicId, token)

        val sub = Await.untilValue(30_000, 500, "the subscription of $publicId is ACTIVE") { subscriptionOfOrder(publicId).takeIf { it.getString("status") == "ACTIVE" } }

        return Started(buyer, publicId, sub.getLong("id"), token)
    }

    private fun settledDeliveries(orderId: Long, count: Int): List<Row> = Await.untilValue(90_000, 1000, "the $count deliveries of order $orderId are settled") {
        deliveriesOfOrder(orderId).takeIf { rows -> rows.size >= count && rows.none { it.getString("status") in setOf("PENDING", "SCHEDULED", "SENDING") } }
    }

    // --- S-01 ----------------------------------------------------------------------------------------------------------

    @Test
    fun `S-01 a merchant-initiated subscription activates with the stored method, is charged once when its time comes and renews`() {
        val sinkName = "s01" + System.nanoTime().toString(36).takeLast(8)
        val endpointId = admin.post(
            "${ApiPaths.PANEL_ROOT}/webhooks",
            JsonObject().put("name", "E2E subscription sink").put("url", "${gateway.baseUrl}/hooks/$sinkName")
                .put("events", JsonArray().add("market.subscription.started").add("market.subscription.renewed")).put("format", "JSON").put("signing", "NONE")
        ).ok().obj().getLong("id")

        try {
            val productId = subscriptionProduct()
            val started = startMerchantSubscription(productId)
            val subscriptionId = started.subscriptionId
            val sub = subscription(subscriptionId)
            val initialOrder = orderRow(started.publicId)

            // activation (09 section 4.4): merchant mode from the stored method, period, nextChargeAt = period end, entitlement without an end of its own
            assertEquals("MERCHANT", sub.getString("mode"))
            assertEquals("fake", sub.getString("providerId"))
            assertEquals(1, sub.getInteger("cycleCount"))
            assertEquals(sub.getLong("currentPeriodEnd"), sub.getLong("nextChargeAt"))
            assertTrue(sub.getLong("currentPeriodEnd") > System.currentTimeMillis() + 25 * day, "a month")
            assertEquals("Visa 4242", sub.getString("storedMethodLabel"))
            assertFalse(sub.getString("storedMethod").contains(started.token), "the stored method is encrypted at rest")
            assertEquals(initialOrder.getLong("totalPrice"), sub.getLong("price"))
            assertNull(db.sql("SELECT `expiresAt` FROM `pano_market_entitlement` WHERE `subscriptionId` = ?", subscriptionId).single().getValue("expiresAt"))
            assertEquals(1L, orderEvents(started.publicId, "SUBSCRIPTION_STARTED"))

            val grants = settledDeliveries(initialOrder.getLong("id"), 2)

            assertTrue(grants.all { it.getString("phase") == "GRANT" && it.getString("status") == "CONFIRMED" }, grants.map { it.getString("status") }.toString())
            assertEquals(100L, credits(started.buyer.userId))
            assertTrue("group.$group" in nodesOf(started.buyer.userId))
            assertEquals(0, chargesOf(started.token).size, "nothing is charged for the first period: the buyer paid it")

            // the charge date comes: one chargeRecurring call, one renewal order, one cycle
            db.rewind("market_subscription", subscriptionId, "nextChargeAt", 40 * day)

            val renewal = Await.untilValue(150_000, 1000, "the renewal order of subscription $subscriptionId is COMPLETED") {
                renewalOrders(subscriptionId).singleOrNull()?.takeIf { it.getString("status") == "COMPLETED" }
            }
            val renewed = Await.untilValue(30_000, 500, "the subscription counted the cycle") { subscription(subscriptionId).takeIf { it.getInteger("cycleCount") == 2 } }

            assertEquals("RENEWAL", renewal.getString("source"))
            assertEquals(sub.getLong("price"), renewal.getLong("totalPrice"), "a renewal charges the frozen price")
            assertEquals(1, chargesOf(started.token).size, "chargeRecurring was called exactly once")
            assertEquals("ACTIVE", renewed.getString("status"))
            assertTrue(renewed.getLong("currentPeriodEnd") > sub.getLong("currentPeriodEnd"), "the period moved on")
            assertEquals(renewed.getLong("currentPeriodEnd"), renewed.getLong("nextChargeAt"), "the next charge is the new period end")
            assertEquals(0, renewed.getInteger("failCount"))
            assertEquals(1L, orderEvents(renewal.getString("publicId"), "SUBSCRIPTION_RENEWED"))

            val renewalRow = db.sql("SELECT * FROM `pano_market_subscription_renewal` WHERE `subscriptionId` = ?", subscriptionId).single()

            assertEquals(1, renewalRow.getInteger("periodIndex"), "the first renewal is period index 1")
            assertEquals("PAID", renewalRow.getString("status"))
            assertEquals(renewal.getLong("id"), renewalRow.getLong("orderId"))
            assertEquals(1, renewalRow.getInteger("attempts"))
            assertEquals("SUCCEEDED", db.string("SELECT `status` FROM `pano_market_payment` WHERE `orderId` = ?", renewal.getLong("id")))

            // RENEW deliveries: the product has no RENEW action, so the credit runs again (and the permission is extended)
            val renewDeliveries = settledDeliveries(renewal.getLong("id"), 1)

            assertTrue(renewDeliveries.all { it.getString("phase") == "RENEW" }, renewDeliveries.map { it.getString("phase") }.toString())
            assertEquals("CONFIRMED", renewDeliveries.single { it.getString("actionType") == "CREDIT" }.getString("status"))
            assertEquals(200L, credits(started.buyer.userId))

            // the store webhooks: started, then renewed with the cycle
            Await.until(60_000, 500, "the sink got subscription.started and subscription.renewed") {
                val events = gateway.hooks(sinkName).map { JsonObject(it.bodyText()).getString("event") }

                "market.subscription.started" in events && "market.subscription.renewed" in events
            }

            val bodies = gateway.hooks(sinkName).map { JsonObject(it.bodyText()) }
            val renewedHook = bodies.single { it.getString("event") == "market.subscription.renewed" }.getJsonObject("data")

            assertEquals(subscriptionId, renewedHook.getJsonObject("subscription").getLong("id"))
            assertEquals(2, renewedHook.getJsonObject("subscription").getInteger("cycleCount"))
            assertEquals(renewal.getString("publicId"), renewedHook.getJsonObject("order").getString("publicId"))
            assertEquals(1, bodies.count { it.getString("event") == "market.subscription.started" })
        } finally {
            admin.delete("${ApiPaths.PANEL_ROOT}/webhooks/$endpointId")
        }
    }

    // --- S-02 ----------------------------------------------------------------------------------------------------------

    @Test
    fun `S-02 a declined renewal puts the subscription PAST_DUE with a mail and a grace end, and the end of grace expires it`() {
        val started = startMerchantSubscription(subscriptionProduct())
        val subscriptionId = started.subscriptionId
        val orderId = orderRow(started.publicId).getLong("id")

        settledDeliveries(orderId, 2)
        assertTrue("group.$group" in nodesOf(started.buyer.userId))

        gateway.failNext(FakePayGateway.Op.CHARGE, 402)
        db.rewind("market_subscription", subscriptionId, "nextChargeAt", 40 * day)

        val pastDue = Await.untilValue(150_000, 1000, "subscription $subscriptionId is PAST_DUE") { subscription(subscriptionId).takeIf { it.getString("status") == "PAST_DUE" } }

        assertEquals(1, pastDue.getInteger("failCount"))
        assertNotNull(pastDue.getLong("graceEndsAt"))
        assertTrue(pastDue.getLong("graceEndsAt") > System.currentTimeMillis(), "the grace period is running")
        assertEquals(1, chargesOf(started.token).size, "the declined charge reached the gateway once")
        assertEquals(1, mails("SUBSCRIPTION_PAYMENT_FAILED", subscriptionId).size, "one SUBSCRIPTION_PAYMENT_FAILED mail")
        assertEquals("${started.buyer.username}@example.com", mails("SUBSCRIPTION_PAYMENT_FAILED", subscriptionId).single().getString("recipient"))
        assertEquals(1L, orderEvents(started.publicId, "SUBSCRIPTION_PAST_DUE"))
        assertEquals("FAILED", db.sql("SELECT `status` FROM `pano_market_payment` WHERE `orderId` = (SELECT `orderId` FROM `pano_market_subscription_renewal` WHERE `subscriptionId` = ?)", subscriptionId).single().getString("status"))
        assertEquals("ACTIVE", db.string("SELECT `status` FROM `pano_market_entitlement` WHERE `subscriptionId` = ?", subscriptionId), "access continues during grace")
        assertTrue("group.$group" in nodesOf(started.buyer.userId))

        // the end of grace (it runs from the end of the paid period, a month away, so the rewind has to cover that too)
        db.rewind("market_subscription", subscriptionId, "graceEndsAt", 60 * day)

        val ended = Await.untilValue(150_000, 1000, "subscription $subscriptionId is EXPIRED") { subscription(subscriptionId).takeIf { it.getString("status") == "EXPIRED" } }

        assertEquals("PAYMENT_FAILED", ended.getString("endReason"))
        assertNotNull(ended.getLong("endedAt"))
        assertNull(ended.getString("storedMethod"), "the stored method is dropped")
        assertNull(ended.getValue("nextChargeAt"))
        assertNull(ended.getValue("graceEndsAt"))

        val entitlement = db.sql("SELECT * FROM `pano_market_entitlement` WHERE `subscriptionId` = ?", subscriptionId).single()

        assertEquals("EXPIRED", entitlement.getString("status"))
        assertEquals("SUBSCRIPTION_ENDED", entitlement.getString("endReason"))

        val rows = Await.untilValue(90_000, 1000, "the EXPIRE rows of subscription $subscriptionId are settled") {
            deliveriesOfOrder(orderId).takeIf { r -> r.any { it.getString("phase") == "EXPIRE" } && r.none { it.getString("status") in setOf("PENDING", "SCHEDULED", "SENDING") } }
        }
        val expire = rows.filter { it.getString("phase") == "EXPIRE" }

        assertEquals(listOf("PERMISSION"), expire.map { it.getString("actionType") }, "the automatic inverse of the permission only: credits are never taken back")
        assertEquals("CONFIRMED", expire.single().getString("status"), expire.single().getString("lastErrorCode"))
        assertFalse("group.$group" in nodesOf(started.buyer.userId), "the rank is gone")
        assertEquals(100L, credits(started.buyer.userId), "the credit stays")
        assertEquals(1L, orderEvents(started.publicId, "SUBSCRIPTION_ENDED"))
        assertEquals(1, mails("SUBSCRIPTION_ENDED", subscriptionId).size)
        assertEquals("CANCELLED", renewalOrders(subscriptionId).single().getString("status"), "the unpaid renewal order is closed with the subscription")
        assertEquals(1, chargesOf(started.token).size, "no further charge after the end")
    }

    // --- S-03 ----------------------------------------------------------------------------------------------------------

    @Test
    fun `S-03 the buyer cancels at period end, can resume before it, cancels again and the end of the period makes it CANCELLED without another charge`() {
        val started = startMerchantSubscription(subscriptionProduct())
        val subscriptionId = started.subscriptionId
        val client = started.buyer.client
        val orderId = orderRow(started.publicId).getLong("id")

        settledDeliveries(orderId, 2)

        // somebody else's subscription is not visible
        assertEquals(404, buyer().client.post("${MarketPaths.SITE_ROOT}/me/subscriptions/$subscriptionId/cancel", JsonObject().put("atPeriodEnd", true)).status)
        assertEquals("ACTIVE", status(subscriptionId))

        // cancel at period end: still ACTIVE, flag set, mail queued
        val cancelled = client.post("${MarketPaths.SITE_ROOT}/me/subscriptions/$subscriptionId/cancel", JsonObject().put("atPeriodEnd", true)).ok().obj()
        val afterCancel = subscription(subscriptionId)

        assertEquals(subscriptionId, (cancelled.getJsonObject("subscription") ?: cancelled).getLong("id"))
        assertEquals("ACTIVE", afterCancel.getString("status"))
        assertTrue(flag(afterCancel, "cancelAtPeriodEnd"))
        assertEquals("BUYER_CANCEL", afterCancel.getString("endReason"))
        assertEquals(1, mails("SUBSCRIPTION_CANCELLED", subscriptionId).size)

        val listed = client.get("${MarketPaths.SITE_ROOT}/me/subscriptions").ok().obj().getJsonArray("items").map { it as JsonObject }.single { it.getLong("id") == subscriptionId }

        assertEquals(true, listed.getBoolean("cancelAtPeriodEnd"))
        assertEquals(true, listed.getBoolean("canResume"))

        // an identical second request changes nothing (idempotent)
        client.post("${MarketPaths.SITE_ROOT}/me/subscriptions/$subscriptionId/cancel", JsonObject().put("atPeriodEnd", true)).ok()
        assertEquals(1, mails("SUBSCRIPTION_CANCELLED", subscriptionId).size)

        // resume before the end clears the flag
        client.post("${MarketPaths.SITE_ROOT}/me/subscriptions/$subscriptionId/resume", JsonObject()).ok()

        val resumed = subscription(subscriptionId)

        assertEquals("ACTIVE", resumed.getString("status"))
        assertFalse(flag(resumed, "cancelAtPeriodEnd"), "resume cleared the flag")
        assertNull(resumed.getString("endReason"))
        assertEquals(resumed.getLong("currentPeriodEnd"), resumed.getLong("nextChargeAt"), "billing is armed again")
        assertEquals(1L, orderEvents(started.publicId, "SUBSCRIPTION_RESUMED"))

        // cancel again, then the period runs out
        client.post("${MarketPaths.SITE_ROOT}/me/subscriptions/$subscriptionId/cancel", JsonObject().put("atPeriodEnd", true)).ok()
        assertTrue(flag(subscription(subscriptionId), "cancelAtPeriodEnd"))
        db.rewind("market_subscription", subscriptionId, "currentPeriodEnd", 40 * day)

        val ended = Await.untilValue(150_000, 1000, "subscription $subscriptionId is CANCELLED") { subscription(subscriptionId).takeIf { it.getString("status") == "CANCELLED" } }

        assertEquals("BUYER_CANCEL", ended.getString("endReason"))
        assertNotNull(ended.getLong("cancelledAt"))
        assertNull(ended.getValue("nextChargeAt"))
        assertEquals(0, chargesOf(started.token).size, "a cancelled subscription is never charged")
        assertEquals("EXPIRED", db.string("SELECT `status` FROM `pano_market_entitlement` WHERE `subscriptionId` = ?", subscriptionId))

        val expire = Await.untilValue(90_000, 1000, "the EXPIRE row of subscription $subscriptionId is settled") {
            deliveriesOfOrder(orderId).filter { it.getString("phase") == "EXPIRE" }.takeIf { r -> r.isNotEmpty() && r.none { it.getString("status") in setOf("PENDING", "SCHEDULED", "SENDING") } }
        }

        assertEquals("CONFIRMED", expire.single().getString("status"))
        assertFalse("group.$group" in nodesOf(started.buyer.userId))

        // a closed subscription cannot be cancelled or resumed again
        assertEquals(409, client.post("${MarketPaths.SITE_ROOT}/me/subscriptions/$subscriptionId/cancel", JsonObject().put("atPeriodEnd", true)).status)
        assertEquals(409, client.post("${MarketPaths.SITE_ROOT}/me/subscriptions/$subscriptionId/resume", JsonObject()).status)
    }

    // --- S-04 ----------------------------------------------------------------------------------------------------------

    @Test
    fun `S-04 a gateway-managed subscription is created by payment succeeded with its subscription, renewed and ended by the gateway's own events`() {
        val productId = subscriptionProduct()
        val buyer = buyer()
        val publicId = publicIdOf(checkout(buyer.client, cart(line(productId)), method = "fake-eur").ok())
        val reference = referenceOf(publicId)
        val payment = gateway.payments[reference] ?: throw AssertionError("the gateway knows no payment $reference")
        val gatewaySubscription = "gsub_e2e_" + System.nanoTime().toString(36)
        val start = System.currentTimeMillis()
        val firstEnd = start + 30 * day

        assertEquals("GATEWAY", subscriptionOfOrder(publicId).getString("mode"), "the provisional mode of a gateway-managed offer")

        gateway.setStatus(reference, "paid")
        sendEvent(
            "fake-eur", "payment.succeeded",
            JsonObject().put("reference", reference).put("amount", payment.amount.toPlainString()).put("currency", payment.currency)
                .put("subscription", JsonObject().put("id", gatewaySubscription).put("periodStart", start).put("periodEnd", firstEnd))
        )
        awaitOrder(publicId, "COMPLETED")

        val sub = Await.untilValue(30_000, 500, "the gateway subscription of $publicId is ACTIVE") { subscriptionOfOrder(publicId).takeIf { it.getString("status") == "ACTIVE" } }
        val subscriptionId = sub.getLong("id")

        assertEquals("GATEWAY", sub.getString("mode"))
        assertEquals("fake-eur", sub.getString("providerId"))
        assertEquals(gatewaySubscription, sub.getString("gatewaySubscriptionId"))
        assertEquals(firstEnd, sub.getLong("currentPeriodEnd"), "the gateway's period is used")
        assertNull(sub.getValue("nextChargeAt"), "the gateway charges, not the store")
        assertNotNull(sub.getLong("nextQueryAt"))
        assertEquals(1, sub.getInteger("cycleCount"))

        // the gateway renews: a renewal order of source RENEWAL, paid through a SUCCEEDED attempt of its own
        val secondEnd = firstEnd + 30 * day

        sendEvent(
            "fake-eur", "subscription.renewed",
            JsonObject().put("subscriptionId", gatewaySubscription).put("amount", payment.amount.toPlainString()).put("currency", "EUR")
                .put("periodStart", firstEnd).put("periodEnd", secondEnd).put("paymentId", "pay_e2e_" + System.nanoTime().toString(36))
        )

        val renewal = Await.untilValue(60_000, 500, "the renewal order of subscription $subscriptionId is COMPLETED") {
            renewalOrders(subscriptionId).singleOrNull()?.takeIf { it.getString("status") == "COMPLETED" }
        }
        val renewed = Await.untilValue(30_000, 500, "the subscription counted the cycle") { subscription(subscriptionId).takeIf { it.getInteger("cycleCount") == 2 } }

        assertEquals("RENEWAL", renewal.getString("source"))
        assertEquals(secondEnd, renewed.getLong("currentPeriodEnd"))
        assertEquals("ACTIVE", renewed.getString("status"))
        assertEquals("SUCCEEDED", db.string("SELECT `status` FROM `pano_market_payment` WHERE `orderId` = ?", renewal.getLong("id")))
        assertEquals("fake-eur", db.string("SELECT `providerId` FROM `pano_market_payment` WHERE `orderId` = ?", renewal.getLong("id")))
        assertEquals(1L, orderEvents(renewal.getString("publicId"), "SUBSCRIPTION_RENEWED"))

        // the same renewal again (the gateway repeats itself): a no-op, no second order
        sendEvent(
            "fake-eur", "subscription.renewed",
            JsonObject().put("subscriptionId", gatewaySubscription).put("amount", payment.amount.toPlainString()).put("currency", "EUR")
                .put("periodStart", firstEnd).put("periodEnd", secondEnd)
        )
        assertEquals(1, renewalOrders(subscriptionId).size)

        // the gateway cancels inside the paid period: the access runs to the period end (S8), then the period runs out
        sendEvent("fake-eur", "subscription.updated", JsonObject().put("subscriptionId", gatewaySubscription).put("status", "CANCELLED"))

        val scheduled = Await.untilValue(30_000, 500, "the cancellation of subscription $subscriptionId is recorded") {
            subscription(subscriptionId).takeIf { flag(it, "cancelAtPeriodEnd") }
        }

        assertEquals("ACTIVE", scheduled.getString("status"), "paid access is not cut short")
        assertNotNull(scheduled.getString("endReason"))

        db.rewind("market_subscription", subscriptionId, "currentPeriodEnd", 90 * day)

        val ended = Await.untilValue(150_000, 1000, "subscription $subscriptionId is CANCELLED") { subscription(subscriptionId).takeIf { it.getString("status") == "CANCELLED" } }

        assertNotNull(ended.getLong("endedAt"))
        assertEquals("EXPIRED", db.string("SELECT `status` FROM `pano_market_entitlement` WHERE `subscriptionId` = ?", subscriptionId))
        assertEquals(0, gateway.cancelledSubscriptions.keys.count { it == gatewaySubscription }, "the gateway ended it itself, the store has nothing to cancel remotely")
    }

    // --- S-05 ----------------------------------------------------------------------------------------------------------

    @Test
    fun `S-05 a subscription must be alone in the cart and a guest cannot buy one`() {
        val subscriptionId = subscriptionProduct()
        val buyer = buyer()
        val before = db.count("market_order", "`userId` = ?", buyer.userId)

        val mixed = checkout(buyer.client, cart(line(subscriptionId), line(catalog.id("VIP"))))

        assertEquals(400, mixed.status, "${mixed.json}")
        assertEquals("SUBSCRIPTION_MUST_BE_ALONE", mixed.error)
        assertEquals(before, db.count("market_order", "`userId` = ?", buyer.userId), "no order was created")

        val guestBody = cart(line(subscriptionId)).put("guest", JsonObject().put("username", "Guest_" + System.nanoTime().toString(36).takeLast(8)).put("email", "guest@example.com"))
        val guest = checkout(visitor("guest"), guestBody)

        assertEquals(401, guest.status, "${guest.json}")
        assertEquals("NOT_LOGGED_IN", guest.error)
        assertNotEquals(200, checkout(visitor("guest2"), cart(line(subscriptionId), line(catalog.id("VIP")))).status)

        // the same subscription alone is fine for the registered buyer, and a second one while it is open is refused
        val publicId = publicIdOf(checkout(buyer.client, cart(line(subscriptionId))).ok())
        val again = checkout(buyer.client, cart(line(subscriptionId)))

        assertEquals("PENDING", orderStatus(publicId))
        assertEquals(400, again.status, "${again.json}")
        assertTrue(again.json.toString().contains("ALREADY_OWNED"), "the second purchase of an open subscription: ${again.json}")
    }
}
