package com.panomc.plugins.market.service

import com.panomc.platform.error.NotFound
import com.panomc.plugins.market.db.model.MarketOrder
import com.panomc.plugins.market.db.model.MarketProduct
import com.panomc.plugins.market.db.model.OrderEventType
import com.panomc.plugins.market.db.model.SubscriptionStatus
import com.panomc.plugins.market.error.InvalidState
import com.panomc.plugins.market.job.HousekeepingJob
import com.panomc.plugins.market.routes.api.OrderAccessFacts
import com.panomc.plugins.market.routes.api.OrderAccessRules
import com.panomc.plugins.market.routes.api.OrderRole
import com.panomc.plugins.market.support.Fixtures
import com.panomc.plugins.market.support.TestUser
import com.panomc.plugins.market.util.OrderStatus
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * PII on player deletion on a real MariaDB (MK-153; 11 sections 16 and 19.11, 01 section 13, 07 section 14.4): every row of the table of 11 section 16 for a
 * seeded user (a pending order, a paid shipped, a paid unshipped and a subscription order, credits, cart, addresses, redemption, creator code, a `USER` and a
 * `PLAYER` block ...), a second run changes nothing, a step that fails does not stop the others and is retried by the housekeeping job, an order that is still
 * delivered later loses its address, the invoice stays, the old token opens only the limited view, and the order-level anonymise action of 04 section 7.
 * A second user's rows are never touched. The invariants I1 to I22 and the credit self-check (L1 to L7, O1 to O8) run after every test.
 */
internal class PlayerErasureIT : RenewalITBase() {
    private val credits get() = CreditService(w.clock, w.creditAccounts, w.creditTxs, w.creditEntries)

    private fun service(labels: Path? = null) = PlayerErasureService(
        clock = w.clock, db = sw.db, locks = sw.locks, orderService = rw.orderService, credits = credits, prefix = { w.orders.prefix() }, client = { w.pool },
        endSubscriptions = { userId -> rw.subs.onUserDeleted(sw.db, { after -> rw.payments.runAfterCommit(after) }, userId) },
        afterCommit = { after -> rw.payments.runAfterCommit(after) }, labelsDir = labels
    )

    override suspend fun assertInvariants() {
        super.assertInvariants()

        val result = CreditReconciler(w.clock, "pano_", { w.pool }, recheckDelayMs = 0).run(full = true)

        assertTrue(result.ok, "the credit reconciler found ${result.problems}")
    }

    // ------------------------------------------------------------------------------------------------------ seed

    private class Seed(
        val user: TestUser,
        val subscriptionOrder: MarketOrder,
        val subscriptionId: Long,
        val pending: MarketOrder,
        val shipped: MarketOrder,
        val unshipped: MarketOrder,
        val shippedShipment: Long,
        val unshippedShipment: Long,
        val invoiceBefore: String
    ) {
        val paid get() = listOf(subscriptionOrder.id, shipped.id, unshipped.id)
        val all get() = listOf(subscriptionOrder.id, pending.id, shipped.id, unshipped.id)
    }

    private val address = """{"firstName":"Erin","line1":"1 Main St","country":"DE"}"""

    private suspend fun set(table: String, id: Long, vararg values: Pair<String, Any?>) = Fixtures.setColumns(pool, table, id, values.toMap())

    private suspend fun raw(table: String, vararg values: Pair<String, Any?>): Long = Fixtures.insertRaw(pool, table, values.toMap())

    private suspend fun one(table: String, id: Long, column: String): Any? = sql("SELECT `$column` AS v FROM `pano_$table` WHERE `id` = ?", id).single().getValue("v")

    private suspend fun plain(product: MarketProduct, caller: QuoteCaller): MarketOrder = buy(product, caller)

    /** A paid order of [caller] with the personal columns filled in, through the real checkout and payment. */
    private suspend fun paidOrder(product: MarketProduct, caller: QuoteCaller, shipping: String?): MarketOrder {
        val order = plain(product, caller)

        succeed(order)

        if (shipping != null) set("market_order", order.id, "requiresShipping" to true, "shippingStatus" to shipping, "shippingAddress" to address)

        return order(order.id)
    }

    /** The user of the seed and everything of 11 section 16 that belongs to him, plus [bystander]-independent rows a deletion must keep. */
    private suspend fun seed(name: String = "Erin"): Seed {
        val product = fx.product(price = 1_000)

        fx.paymentMethod("fake")

        val active = activeMerchant(name = name)
        val user = active.user
        val caller = active.caller
        val pending = plain(product, caller)
        val shipped = paidOrder(product, caller, "DELIVERED")
        val unshipped = paidOrder(product, caller, "PENDING")
        val subscriptionOrder = order(active.order.id)
        val all = listOf(subscriptionOrder.id, pending.id, shipped.id, unshipped.id)

        for (id in all) {
            set(
                "market_order", id, "clientIp" to "203.0.113.7", "userAgent" to "Mozilla/5.0", "billingInfo" to """{"name":"Erin"}""", "giftMessage" to "hi",
                "accessToken" to "t".repeat(40), "locale" to "tr"
            )
        }

        for (id in all) {
            for (item in sql("SELECT `id` FROM `pano_market_order_item` WHERE `orderId` = ?", id)) set("market_order_item", item.getLong("id"), "fieldValues" to """{"note":"x"}""")
            for (attempt in sql("SELECT `id` FROM `pano_market_payment` WHERE `orderId` = ?", id)) set("market_payment", attempt.getLong("id"), "clientIp" to "203.0.113.7", "userAgent" to "Mozilla/5.0")
        }

        // payment events: a settled one loses its body, one that can still be replayed keeps it
        raw("market_payment_event", "providerId" to "fake", "direction" to "IN", "channel" to "WEBHOOK", "eventKey" to "e:settled:$name", "orderId" to shipped.id, "body" to "{\"card\":1}", "headers" to "{\"a\":1}", "status" to "PROCESSED")
        raw("market_payment_event", "providerId" to "fake", "direction" to "IN", "channel" to "WEBHOOK", "eventKey" to "e:open:$name", "orderId" to shipped.id, "body" to "{\"card\":2}", "headers" to "{\"a\":2}", "status" to "FAILED")

        // shipments
        val shippedShipment = raw("market_shipment", "orderId" to shipped.id, "providerId" to "manual", "status" to "DELIVERED", "merchantReference" to "M-1-$name", "toAddress" to address, "fromAddress" to "{}", "labelFile" to "7-0.pdf")
        val unshippedShipment = raw("market_shipment", "orderId" to unshipped.id, "providerId" to "manual", "status" to "CREATED", "merchantReference" to "M-2-$name", "toAddress" to address, "fromAddress" to "{}")

        // webhook deliveries: a finished one is blanked, a pending one is still sent
        raw("market_webhook_delivery", "eventId" to java.util.UUID.randomUUID().toString(), "event" to "order.paid", "orderId" to shipped.id, "url" to "https://hooks.invalid/x", "format" to "JSON", "signing" to "NONE", "body" to "{\"email\":\"erin@example.com\"}", "status" to "SUCCEEDED")
        raw("market_webhook_delivery", "eventId" to java.util.UUID.randomUUID().toString(), "event" to "order.paid", "orderId" to shipped.id, "url" to "https://hooks.invalid/x", "format" to "JSON", "signing" to "NONE", "body" to "{\"email\":\"erin@example.com\"}", "status" to "PENDING")

        // credits, cart, addresses
        fx.credit(user, 5_000)

        val cart = raw("market_cart", "userId" to user.id)

        raw("market_cart_item", "cartId" to cart, "productId" to product.id, "lineKey" to "a".repeat(40))
        raw("market_address", "userId" to user.id, "label" to "home")

        // a redemption (released, so the counters of the codes stay as I6 wants them), a creator code with an earning and a payout
        raw("market_redemption", "kind" to "COUPON", "refId" to 9_999, "orderId" to shipped.id, "userId" to user.id, "buyerKey" to "u:${user.id}", "email" to "erin@example.com", "currency" to "EUR", "state" to "RELEASED")

        val code = fx.creatorCode()

        set("market_creator_code", code.id, "creatorUserId" to user.id)
        raw("market_creator_earning", "creatorCodeId" to code.id, "creatorUserId" to user.id, "orderId" to 888, "baseAmount" to 0, "commissionPercent" to 0, "amount" to 0, "currency" to "EUR", "state" to "REVERSED")
        raw("market_creator_payout", "creatorCodeId" to code.id, "creatorUserId" to user.id, "amount" to 0, "currency" to "EUR", "method" to "MANUAL", "state" to "CANCELLED", "idempotencyKey" to "p-1-$name", "idempotencyHash" to "0".repeat(64))

        // mail
        raw("market_mail_outbox", "kind" to "ORDER_CONFIRMATION", "refType" to "ORDER", "refId" to shipped.id, "orderId" to shipped.id, "userId" to user.id, "recipient" to "${name.lowercase()}@example.com", "locale" to "tr", "params" to "{\"a\":1}", "status" to "PENDING")
        raw("market_mail_outbox", "kind" to "ORDER_PAID", "refType" to "ORDER", "refId" to shipped.id, "orderId" to shipped.id, "userId" to user.id, "recipient" to "${name.lowercase()}@example.com", "locale" to "tr", "params" to "{\"a\":2}", "status" to "SENT")

        // provider state, blocks, throttle
        raw("market_provider_state", "kind" to "CUSTOMER", "providerId" to "fake", "stateKey" to "user:${user.id}:customer", "value" to "cus_1")
        raw("market_provider_state", "kind" to "CUSTOMER", "providerId" to "fake", "stateKey" to "user:${user.id}9:customer", "value" to "cus_other")
        raw("market_block", "type" to "USER", "value" to user.id.toString(), "source" to "ADMIN")
        raw("market_block", "type" to "PLAYER", "value" to name.lowercase(), "source" to "ADMIN")
        raw("market_throttle", "scope" to "CHECKOUT", "subject" to "b:u:${user.id}", "windowStart" to w.clock.now())

        // the invoice of a paid order: accounting record, kept as it is
        val sequence = count("market_invoice") + 1

        raw("market_invoice", "orderId" to shipped.id, "type" to "INVOICE", "series" to "INV", "sequence" to sequence, "number" to "INV-%06d".format(sequence))

        val invoice = invoiceDump()

        return Seed(user, subscriptionOrder, active.sub.id, order(pending.id), order(shipped.id), order(unshipped.id), shippedShipment, unshippedShipment, invoice)
    }

    private suspend fun invoiceDump(): String = sql("SELECT * FROM `pano_market_invoice` ORDER BY `id`").joinToString("|") { row -> (0 until row.size()).joinToString(",") { "${row.getValue(it)}" } }

    // ------------------------------------------------------------------------------------------------------ 19.11 case 1 and 2

    @Test
    fun `19_11 case 1 erase blanks every row of the table of 11 section 16 and keeps what must be kept`(): Unit = runBlocking {
        val bystander = seed("Bob")
        val s = seed("Erin")

        val report = service().erase(s.user.id)

        assertTrue(report.failed.isEmpty(), "failed steps ${report.failed}")
        assertFalse(report.deferred)
        assertTrue(report.complete)

        // 1: the unpaid order went through the state machine, the reason is on the timeline
        assertEquals(OrderStatus.CANCELLED, order(s.pending.id).status)

        val cancelEvent = events(s.pending.id, OrderEventType.STATUS_CHANGED).last()

        assertEquals("CANCELLED", cancelEvent.toStatus)
        assertEquals("ACCOUNT_DELETED", cancelEvent.message)

        // 2: the subscription is cancelled and has lost its personal data
        val sub = subscription(s.subscriptionId)

        assertEquals(SubscriptionStatus.CANCELLED, sub.status)
        assertEquals("ADMIN_CANCEL", sub.endReason)
        assertNull(sub.userId)
        assertNull(sub.email)
        assertNull(sub.storedMethod)
        assertNull(sub.storedMethodLabel)
        assertNull(sub.gatewayCustomerId)
        assertNull(sub.fieldValues)
        assertNull(sub.providerData)

        // 3: the credits moved to REVOKED, the account and the ledger rows lost their user, the amounts stayed
        val revoke = sql("SELECT * FROM `pano_market_credit_tx` WHERE `idempotencyKey` = ?", "user-delete:${s.user.id}").single()

        assertEquals("REVOKE", revoke.getString("type"))
        assertEquals(5_000L, revoke.getLong("amount"))
        assertEquals("ACCOUNT_DELETED", revoke.getString("note"))
        assertNull(revoke.getValue("userId"))
        assertNull(revoke.getValue("actorUserId"))
        assertEquals(0L, one("market_credit_account", s.user.accountId, "balance"))
        assertNull(one("market_credit_account", s.user.accountId, "userId"))
        assertEquals(0L, count("market_credit_tx", "`userId` = ?", s.user.id))
        assertEquals(5_000L, sql("SELECT `balance` FROM `pano_market_credit_account` WHERE `systemKey` = 'REVOKED'").single().getLong("balance"))
        assertEquals(0L, sql("SELECT COALESCE(SUM(`amount`), 0) AS s FROM `pano_market_credit_entry`").single().getLong("s"), "the ledger still balances")

        // 4: cart, cart lines, addresses
        assertEquals(0L, count("market_cart", "`userId` = ?", s.user.id))
        assertEquals(0L, count("market_cart_item", "`cartId` NOT IN (SELECT `id` FROM `pano_market_cart`)"))
        assertEquals(0L, count("market_address", "`userId` = ?", s.user.id))

        // 5: the orders keep their rows, the personal columns are blank, locale, player name and buyer key stay
        for (id in s.all) {
            val row = sql("SELECT * FROM `pano_market_order` WHERE `id` = ?", id).single()

            assertNull(row.getValue("userId"), "order $id userId")
            assertNull(row.getValue("email"), "order $id email")
            assertNull(row.getValue("clientIp"), "order $id clientIp")
            assertNull(row.getValue("userAgent"), "order $id userAgent")
            assertNull(row.getValue("billingInfo"), "order $id billingInfo")
            assertNull(row.getValue("giftMessage"), "order $id giftMessage")
            assertNull(row.getValue("accessToken"), "order $id accessToken")
            assertEquals("tr", row.getString("locale"))
            assertEquals("Erin", row.getString("playerUsername"))
            assertEquals("u:${s.user.id}", row.getString("buyerKey"))
        }

        assertNull(one("market_order", s.shipped.id, "shippingAddress"), "delivered: nothing left to ship")
        assertNull(one("market_order", s.pending.id, "shippingAddress"))
        assertNull(one("market_order", s.subscriptionOrder.id, "shippingAddress"))
        assertEquals(address, one("market_order", s.unshipped.id, "shippingAddress"), "an unshipped order keeps its address until it is shipped")

        // 7, 8: items and attempts
        assertEquals(0L, count("market_order_item", "`fieldValues` IS NOT NULL AND `orderId` IN (${s.all.joinToString(",")})"))
        assertEquals(0L, count("market_payment", "(`clientIp` IS NOT NULL OR `userAgent` IS NOT NULL) AND `orderId` IN (${s.all.joinToString(",")})"))
        assertEquals("Visa 4242", one("market_payment", attemptsOf(s.shipped.id).first(), "methodDetail"), "brand and last digits are not personal on their own")

        // 9: settled provider traffic is blanked, replayable traffic is not
        assertNull(sql("SELECT `body` FROM `pano_market_payment_event` WHERE `eventKey` = 'e:settled:Erin'").single().getValue("body"))
        assertNull(sql("SELECT `headers` FROM `pano_market_payment_event` WHERE `eventKey` = 'e:settled:Erin'").single().getValue("headers"))
        assertEquals("{\"card\":2}", sql("SELECT `body` FROM `pano_market_payment_event` WHERE `eventKey` = 'e:open:Erin'").single().getString("body"))

        // 10, 11: redemption and entitlement lose the user, the entitlement keeps player name and owner key
        assertEquals(0L, count("market_redemption", "`userId` = ?", s.user.id))
        assertNull(sql("SELECT `email` FROM `pano_market_redemption` WHERE `refId` = 9999 AND `orderId` = ?", s.shipped.id).single().getValue("email"))

        val entitlement = sql("SELECT * FROM `pano_market_entitlement` WHERE `subscriptionId` = ?", s.subscriptionId).single()

        assertNull(entitlement.getValue("userId"))
        assertEquals("Erin", entitlement.getString("playerUsername"))
        assertEquals("u:${s.user.id}", entitlement.getString("ownerKey"))

        // 12: mail
        assertEquals("SKIPPED", sql("SELECT `status` FROM `pano_market_mail_outbox` WHERE `params` = '{}' AND `kind` = 'ORDER_CONFIRMATION'").single().getString("status"))
        assertEquals("SENT", sql("SELECT `status` FROM `pano_market_mail_outbox` WHERE `kind` = 'ORDER_PAID' AND `orderId` = ?", s.shipped.id).single().getString("status"))
        assertEquals(2L, count("market_mail_outbox", "`recipient` = '' AND `params` = '{}' AND `orderId` = ?", s.shipped.id))
        assertEquals(0L, count("market_mail_outbox", "`userId` = ? AND `recipient` <> ''", s.user.id))
        assertEquals(0L, count("market_mail_outbox", "`recipient` = 'erin@example.com'"))

        // 13: a finished shipment loses its address (and its label file), a shipment still on its way keeps it
        assertEquals("{}", one("market_shipment", s.shippedShipment, "toAddress"))
        assertEquals(address, one("market_shipment", s.unshippedShipment, "toAddress"))

        // 14: finished webhook deliveries are blanked, a pending one is still sent
        assertEquals("{}", sql("SELECT `body` FROM `pano_market_webhook_delivery` WHERE `status` = 'SUCCEEDED' AND `orderId` = ?", s.shipped.id).single().getString("body"))
        assertTrue(sql("SELECT `body` FROM `pano_market_webhook_delivery` WHERE `status` = 'PENDING' AND `orderId` = ?", s.shipped.id).single().getString("body").contains("erin@example.com"))

        // 15: creator
        assertEquals(0L, count("market_creator_code", "`creatorUserId` = ?", s.user.id))
        assertEquals(0L, count("market_creator_earning", "`creatorUserId` = ?", s.user.id))
        assertEquals(0L, count("market_creator_payout", "`creatorUserId` = ?", s.user.id))
        assertEquals("streamer", sql("SELECT `creator` FROM `pano_market_creator_code` WHERE `creatorUserId` IS NULL").single().getString("creator"), "the snapshot of the creator name stays")

        // 16: provider state of the user, not of a user whose id starts the same way
        assertEquals(0L, count("market_provider_state", "`stateKey` = ?", "user:${s.user.id}:customer"))
        assertEquals(1L, count("market_provider_state", "`stateKey` = ?", "user:${s.user.id}9:customer"))

        // 17: the USER block goes, the PLAYER block stays (fraud prevention survives a deletion)
        assertEquals(0L, count("market_block", "`type` = 'USER' AND `value` = ?", s.user.id.toString()))
        assertEquals(1L, count("market_block", "`type` = 'PLAYER' AND `value` = 'erin'"))

        // 18: the throttle row
        assertEquals(0L, count("market_throttle", "`subject` = ?", "b:u:${s.user.id}"))

        // 19: the invoice is an accounting record
        assertEquals(s.invoiceBefore, invoiceDump(), "the invoice rows")

        // 20: one PII_ERASED event per affected order
        for (id in s.all) {
            val erased = events(id, OrderEventType.PII_ERASED)

            assertEquals(1, erased.size, "order $id")
            assertEquals("SYSTEM", erased.single().actorType.name)
        }

        // nothing of the second user moved
        val other = bystander
        val row = sql("SELECT * FROM `pano_market_order` WHERE `id` = ?", other.shipped.id).single()

        assertEquals(other.user.id, row.getLong("userId"))
        assertNotNull(row.getValue("email"))
        assertEquals("203.0.113.7", row.getString("clientIp"))
        assertEquals(address, one("market_order", other.unshipped.id, "shippingAddress"))
        assertEquals(1L, count("market_cart", "`userId` = ?", other.user.id))
        assertEquals(1L, count("market_address", "`userId` = ?", other.user.id))
        assertEquals(OrderStatus.PENDING, order(other.pending.id).status)
        assertEquals(SubscriptionStatus.ACTIVE, subscription(other.subscriptionId).status)
        assertEquals(5_000L, one("market_credit_account", other.user.accountId, "balance"))
        assertEquals(0L, events(other.shipped.id, OrderEventType.PII_ERASED).size.toLong())
        assertEquals(1L, count("market_block", "`type` = 'USER' AND `value` = ?", other.user.id.toString()))
        assertEquals(1L, count("market_provider_state", "`stateKey` = ?", "user:${other.user.id}:customer"))
        assertEquals(0L, count("market_sequence", "`name` LIKE 'erasure-pending:%'"), "no marker is left after a complete erasure")
    }

    @Test
    fun `19_11 case 2 erasing twice gives the same state and no error`(): Unit = runBlocking {
        val s = seed()
        val service = service()

        assertTrue(service.erase(s.user.id).complete)

        val first = snapshot()

        assertTrue(service.erase(s.user.id).complete)
        assertEquals(first, snapshot(), "a second run changes nothing")
        assertEquals(1, events(s.shipped.id, OrderEventType.PII_ERASED).size, "one PII_ERASED event per order, not per run")
        assertEquals(1L, count("market_credit_tx", "`type` = 'REVOKE'"), "the closing REVOKE is posted once")
    }

    /** The rows a second run must leave alone, as one text. */
    private suspend fun snapshot(): String {
        val out = StringBuilder()

        for (table in listOf(
            "market_order", "market_order_item", "market_payment", "market_payment_event", "market_shipment", "market_webhook_delivery", "market_mail_outbox", "market_redemption",
            "market_entitlement", "market_subscription", "market_credit_account", "market_credit_tx", "market_credit_entry", "market_order_event", "market_block", "market_provider_state",
            "market_throttle", "market_cart", "market_address", "market_creator_code"
        )) {
            out.append(table).append(": ")

            for (row in sql("SELECT * FROM `pano_$table` ORDER BY `id`")) {
                out.append((0 until row.size()).filter { row.getColumnName(it) != "updatedAt" }.joinToString(",") { "${row.getValue(it)}" }).append('|')
            }

            out.append('\n')
        }

        return out.toString()
    }

    private suspend fun attemptsOf(orderId: Long): List<Long> = sql("SELECT `id` FROM `pano_market_payment` WHERE `orderId` = ? ORDER BY `id`", orderId).map { it.getLong("id") }

    // ------------------------------------------------------------------------------------------------------ 19.11 case 3

    @Test
    fun `19_11 case 3 a failure in step 4 stops nothing else, the handler does not throw and the housekeeping job finishes the step`(): Unit = runBlocking {
        val s = seed()

        // a real failure of a real statement: the table of the cart lines is not there while the user is erased
        sql("RENAME TABLE `pano_market_cart_item` TO `pano_market_cart_item_away`")

        val report = try {
            service().erase(s.user.id)
        } finally {
            sql("RENAME TABLE `pano_market_cart_item_away` TO `pano_market_cart_item`")
        }

        assertEquals(listOf("cart-address"), report.failed)
        assertFalse(report.complete)

        // the steps after the failed one ran
        assertNull(one("market_order", s.shipped.id, "email"))
        assertEquals("{}", one("market_shipment", s.shippedShipment, "toAddress"))
        assertEquals(0L, count("market_block", "`type` = 'USER' AND `value` = ?", s.user.id.toString()))
        assertEquals(1, events(s.shipped.id, OrderEventType.PII_ERASED).size)

        // the step that failed did not happen, and the marker says the erasure is not over
        assertEquals(1L, count("market_cart", "`userId` = ?", s.user.id))
        assertEquals(1L, count("market_sequence", "`name` = ?", PlayerErasureService.markerName(s.user.id)))

        // the housekeeping job runs the erasure again for the marker
        val job = HousekeepingJob(w.clock, { "pano_" }, { w.pool }, null, null, service(), null)

        job.run(HousekeepingJob.Task.ERASURE)

        assertEquals(0L, count("market_cart", "`userId` = ?", s.user.id))
        assertEquals(0L, count("market_sequence", "`name` LIKE 'erasure-pending:%'"))
    }

    // ------------------------------------------------------------------------------------------------------ 19.11 case 4

    @Test
    fun `19_11 case 4 after the unshipped order is delivered the housekeeping job blanks its address and the address of the shipment`(): Unit = runBlocking {
        val s = seed()
        val directory = Files.createTempDirectory("labels")
        val label = Files.write(directory.resolve("${s.unshippedShipment}-0.pdf"), byteArrayOf(1, 2, 3))
        val other = Files.write(directory.resolve("${s.unshippedShipment}9-0.pdf"), byteArrayOf(4))
        val service = service(directory)
        val job = HousekeepingJob(w.clock, { "pano_" }, { w.pool }, null, null, service, null)

        try {
            service.erase(s.user.id)

            assertEquals(address, one("market_order", s.unshipped.id, "shippingAddress"))

            // nothing changed yet: the job leaves an order that still has to be shipped alone
            job.run(HousekeepingJob.Task.ERASURE)

            assertEquals(address, one("market_order", s.unshipped.id, "shippingAddress"))
            assertEquals(address, one("market_shipment", s.unshippedShipment, "toAddress"))
            assertTrue(Files.exists(label))

            // the parcel arrives
            set("market_order", s.unshipped.id, "shippingStatus" to "DELIVERED")
            set("market_shipment", s.unshippedShipment, "status" to "DELIVERED", "labelFile" to "${s.unshippedShipment}-0.pdf")

            job.run(HousekeepingJob.Task.ERASURE)

            assertNull(one("market_order", s.unshipped.id, "shippingAddress"))
            assertEquals("{}", one("market_shipment", s.unshippedShipment, "toAddress"))
            assertFalse(Files.exists(label), "the label file of that shipment is deleted")
            assertTrue(Files.exists(other), "the file of another shipment is not")
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    // ------------------------------------------------------------------------------------------------------ 19.11 case 5

    @Test
    fun `19_11 case 5 the invoice stays unchanged and the old token opens only the limited view`(): Unit = runBlocking {
        val s = seed()
        val before = order(s.shipped.id)
        val token = before.accessToken!!

        // before: the token makes the owner
        assertEquals(OrderRole.OWNER, OrderAccessRules.roleOf(OrderAccessFacts.of(before), null, OrderAccessRules.tokenValid(OrderAccessFacts.of(before), token, before.createdAt)))

        service().erase(s.user.id)

        val after = order(s.shipped.id)
        val facts = OrderAccessFacts.of(after)

        assertFalse(OrderAccessRules.tokenValid(facts, token, after.createdAt), "the token no longer matches anything")
        assertEquals(OrderRole.LIMITED, OrderAccessRules.roleOf(facts, null, OrderAccessRules.tokenValid(facts, token, after.createdAt)))
        assertEquals(s.invoiceBefore, invoiceDump())
    }

        @Test
    fun `a user without any credit account or order is erased without error`(): Unit = runBlocking {
        val loner = fx.user("Loner")
        val report = service().erase(loner.id)

        assertTrue(report.complete)
        assertEquals(0L, count("market_sequence", "`name` LIKE 'erasure-pending:%'"))
    }

    // ------------------------------------------------------------------------------------------------------ anonymise one order

    @Test
    fun `the anonymise action blanks one order and its children, keeps the money, and is idempotent`(): Unit = runBlocking {
        val s = seed()
        val service = service()

        assertTrue(service.anonymizeOrder(s.shipped.id))

        val row = sql("SELECT * FROM `pano_market_order` WHERE `id` = ?", s.shipped.id).single()

        assertNull(row.getValue("email"))
        assertNull(row.getValue("clientIp"))
        assertNull(row.getValue("billingInfo"))
        assertNull(row.getValue("accessToken"))
        assertNull(row.getValue("userId"))
        assertNull(row.getValue("shippingAddress"), "delivered")
        assertEquals(s.shipped.totalPrice, row.getLong("totalPrice"))
        assertEquals(OrderStatus.COMPLETED.name, row.getString("status"))
        assertEquals("{}", one("market_shipment", s.shippedShipment, "toAddress"))
        assertEquals(1, events(s.shipped.id, OrderEventType.PII_ERASED).size)

        // another order of the same user is untouched
        assertNotNull(one("market_order", s.unshipped.id, "email"))
        assertEquals(s.user.id, one("market_order", s.unshipped.id, "userId"))

        assertFalse(service.anonymizeOrder(s.shipped.id), "the second call changes nothing")
        assertEquals(1, events(s.shipped.id, OrderEventType.PII_ERASED).size)
    }

    @Test
    fun `the anonymise action refuses an open order and an unknown one`(): Unit = runBlocking {
        val s = seed()
        val service = service()

        val open = assertThrows(InvalidState::class.java) { runBlocking { service.anonymizeOrder(s.pending.id) } }

        assertEquals(409, open.getStatusCode())
        assertNotNull(one("market_order", s.pending.id, "email"))
        assertThrows(NotFound::class.java) { runBlocking { service.anonymizeOrder(9_999_999) } }
    }
}
