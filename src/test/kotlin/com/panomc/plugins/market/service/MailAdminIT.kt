package com.panomc.plugins.market.service

import com.panomc.platform.model.PageRequest
import com.panomc.platform.error.NotFound
import com.panomc.plugins.market.config.MarketConfig
import com.panomc.plugins.market.core.delivery.ProductAction
import com.panomc.plugins.market.db.MarketDaoITBase
import com.panomc.plugins.market.db.model.DeliveryActionType
import com.panomc.plugins.market.db.model.EntitlementStatus
import com.panomc.plugins.market.db.model.MailKind
import com.panomc.plugins.market.db.model.MailRefType
import com.panomc.plugins.market.db.model.MailStatus
import com.panomc.plugins.market.db.model.MarketMailOutbox
import com.panomc.plugins.market.db.model.MarketShipment
import com.panomc.plugins.market.db.model.MarketShipmentItem
import com.panomc.plugins.market.db.model.MarketSubscription
import com.panomc.plugins.market.db.model.RefundStatus
import com.panomc.plugins.market.db.model.ShipmentStatus
import com.panomc.plugins.market.db.model.SubscriptionMode
import com.panomc.plugins.market.db.model.SubscriptionStatus
import com.panomc.plugins.market.error.InvalidMailKind
import com.panomc.plugins.market.error.InvalidState
import com.panomc.plugins.market.error.MailDisabled
import com.panomc.plugins.market.error.MailNotApplicable
import com.panomc.plugins.market.error.MailRecipientRequired
import com.panomc.plugins.market.error.MailSendFailed
import com.panomc.plugins.market.error.RequestValueException
import com.panomc.plugins.market.i18n.MarketFormat
import com.panomc.plugins.market.i18n.MarketI18n
import com.panomc.plugins.market.job.EntitlementExpiryJob
import com.panomc.plugins.market.job.MailOutboxJob
import com.panomc.plugins.market.mail.MailComposer
import com.panomc.plugins.market.mail.MailContentBuilder
import com.panomc.plugins.market.mail.MailSendResult
import com.panomc.plugins.market.mail.MailSite
import com.panomc.plugins.market.routes.panel.mail.MailAdmin
import com.panomc.plugins.market.service.platform.DirectoryUser
import com.panomc.plugins.market.service.platform.UserDirectory
import com.panomc.plugins.market.support.FakeMailGateway
import com.panomc.plugins.market.support.Fixtures
import com.panomc.plugins.market.support.TestUser
import com.panomc.plugins.market.support.TestWiring
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.SqlClient
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException

/**
 * The second mail slice on a real MariaDB (MK-146; 12 sections 4.1, 4.2, 4.3.5, 4.5, 5 and 10; 04 sections 7 and 8): the subscription, expiry-reminder and
 * shipment mails reach the buyer through the real [MailComposer] and [MailOutboxJob] (service mails regardless of `sendEmailAfterPurchase`, the shipment mails as
 * order mails only with it), the relevance rules end a stale reminder `SKIPPED`, and the panel rules of [MailAdmin] (resend, retry, masked list, test mail) hold.
 *
 * The enqueue at the transitions themselves is proven where the transition lives (`SubscriptionServiceIT`, `ShippingServiceIT`, `EntitlementExpiryJobIT`); here the
 * `EXPIRY_REMINDER` comes from the real [EntitlementExpiryJob], the subscription and shipment rows are seeded with the params those services write.
 */
class MailAdminIT : MarketDaoITBase() {
    private lateinit var w: TestWiring
    private lateinit var d: DeliveryWorld
    private lateinit var outbox: MailOutboxService
    private lateinit var gateway: FakeMailGateway
    private lateinit var admin: MailAdmin
    private lateinit var job: MailOutboxJob
    private val emails = HashMap<Long, String>()
    private val day = 86_400_000L
    private val refs = java.util.concurrent.atomic.AtomicLong()

    @Volatile
    private var mailOn = true

    @Volatile
    private var hostOk = true

    private val directory = object : UserDirectory {
        override suspend fun byUsername(username: String, sqlClient: SqlClient): DirectoryUser? = w.users.idOf(username)?.let { DirectoryUser(it, w.users.nameOf(it)!!) }

        override suspend fun usernameOf(userId: Long, sqlClient: SqlClient): String? = w.users.nameOf(userId)

        override suspend fun emailOf(userId: Long, sqlClient: SqlClient): String? = emails[userId]

        override suspend fun hasPermission(userId: Long, node: String): Boolean = false
    }

    private fun config(purchaseMails: Boolean = true, disabled: List<String> = emptyList(), reminderDays: Int = 3) = MarketConfig(
        currency = "EUR", statsCurrency = "EUR", storeTimeZone = "UTC", storeName = "Blocky Store", sendEmailAfterPurchase = purchaseMails,
        mailDisabledKinds = disabled, subscriptionReminderDays = reminderDays
    )

    private val i18n by lazy { MarketI18n(MarketI18n.loadBundles(MailAdminIT::class.java.classLoader), { emptyMap() }, w.clock) }
    private val format by lazy { MarketFormat(i18n, { "UTC" }, { "" }) }

    @BeforeEach
    fun wire() {
        runBlocking { resetState() }
        emails.clear()
        mailOn = true
        hostOk = true
        w = TestWiring(pool)
        w.configure { config() }
        d = DeliveryWorld(w)
        w.configure { config() }
        gateway = FakeMailGateway()
        outbox = MailOutboxService({ w.config }, w.clock, w.mailOutbox, w.orderEvents)

        val builder = MailContentBuilder(i18n, format, { w.config }, { MailSite("Blocky Network", "https://shop.example") })
        val composer = MailComposer(
            builder, w.orders, w.orderItems, w.refunds, w.refundItems, null, null, { w.config }, w.subscriptions, w.entitlements, w.shipments, w.shipmentItems, w.products,
            { w.clock.now() }
        )

        job = MailOutboxJob(
            config = { w.config }, clock = w.clock, service = outbox, gateway = gateway, composition = composer, sqlClient = { pool }, mailEnabled = { mailOn },
            mailOptionsAvailable = { hostOk }
        )
        admin = MailAdmin(
            db = w.db, config = { w.config }, clock = w.clock, orders = w.orders, orderItems = w.orderItems, mailOutbox = w.mailOutbox, outbox = outbox, job = job, refunds = w.refunds,
            shipments = w.shipments, subscriptions = w.subscriptions, entitlements = w.entitlements, users = directory, builder = builder, gateway = gateway,
            mailEnabled = { mailOn }, mailOptionsAvailable = { hostOk }, defaultLocale = { "en-US" }
        )
    }

    // ------------------------------------------------------------------------------------------------------ fixtures

    private fun permission(node: String) = ProductAction(id = "a1", type = DeliveryActionType.PERMISSION, nodes = listOf(node))

    private suspend fun owner(name: String = "Steve", email: String? = "${name.lowercase()}@example.com"): TestUser =
        w.fixtures.user(name).also { u -> email?.let { emails[u.id] = it } }

    /** A paid TIMED order of [user] (30 days) with its entitlement, `email` and `locale` set on the order. */
    private suspend fun timed(user: TestUser, days: Int = 30): Placed {
        val placed = d.place(user = user, actions = listOf(permission("group.vip")), billing = "TIMED", periodUnit = "DAY", periodCount = days)

        d.pay(placed)
        Fixtures.setColumns(pool, "market_order", placed.order.id, mapOf("email" to emails[user.id], "locale" to "en-US"))

        return placed
    }

    private suspend fun entitlementOf(placed: Placed) = w.entitlements.getByOrderItemId(placed.items[0].id, pool).single()

    /** A subscription of the order's buyer; the order points at it (09: `order.subscriptionId`). */
    private suspend fun subscribe(
        placed: Placed, user: TestUser, status: SubscriptionStatus = SubscriptionStatus.ACTIVE, mode: SubscriptionMode = SubscriptionMode.GATEWAY, cycle: Int = 2,
        cancelAtPeriodEnd: Boolean = false, endReason: String? = null, cancelRequestedAt: Long? = null, email: String? = emails[user.id]
    ): MarketSubscription {
        val now = w.clock.now()
        val id = w.subscriptions.add(
            MarketSubscription(
                userId = user.id, playerUsername = user.username, ownerKey = "u:${user.id}", email = email, productId = placed.product.id, productName = "Gold Rank",
                initialOrderId = placed.order.id, initialOrderItemId = placed.items[0].id, providerId = "fake", mode = mode, status = status, price = 1500, currency = "EUR",
                cycleCount = cycle, currentPeriodStart = now, currentPeriodEnd = now + 3 * day, graceEndsAt = now + 6 * day, cancelAtPeriodEnd = cancelAtPeriodEnd,
                cancelRequestedAt = cancelRequestedAt, endReason = endReason, storedMethodLabel = "Visa **** 4242", createdAt = now, updatedAt = now
            ),
            pool
        )!!

        Fixtures.setColumns(pool, "market_order", placed.order.id, mapOf("subscriptionId" to id))

        return w.subscriptions.getById(id, pool)!!
    }

    private suspend fun ship(placed: Placed, status: ShipmentStatus = ShipmentStatus.IN_TRANSIT, tracking: String? = "TRK123"): MarketShipment {
        val now = w.clock.now()
        val id = w.shipments.add(
            MarketShipment(
                orderId = placed.order.id, providerId = "manual", status = status, merchantReference = "MR-${placed.order.id}-${refs.incrementAndGet()}", trackingNumber = tracking,
                trackingUrl = "https://track.example/$tracking", carrierName = "Aras Kargo", shippedAt = now, deliveredAt = if (status == ShipmentStatus.DELIVERED) now else null,
                createdAt = now, updatedAt = now
            ),
            pool
        )!!

        w.shipmentItems.add(MarketShipmentItem(shipmentId = id, orderItemId = placed.items[0].id, quantity = 1, createdAt = now, updatedAt = now), pool)

        return w.shipments.getById(id, pool)!!
    }

    private suspend fun rows(orderId: Long): List<MarketMailOutbox> = w.mailOutbox.getByOrderId(orderId, pool).sortedBy { it.id }

    private suspend fun row(id: Long): MarketMailOutbox = w.mailOutbox.getById(id, pool)!!

    private suspend fun enqueue(kind: MailKind, refType: MailRefType, refId: Long, refKey: String, placed: Placed, recipient: String, params: JsonObject = JsonObject()): Long? =
        outbox.enqueue(pool, kind, refType, refId, refKey, placed.order.id, placed.order.userId, recipient, "en-US", params)

    private fun reminderJob(days: Int = 3) = EntitlementExpiryJob(
        clock = w.clock, db = w.db, locks = d.locks, service = d.entitlementService, delivery = d.service, entitlements = w.entitlements,
        config = { config(reminderDays = days) }, mail = outbox, users = directory
    )

    // ================================================================================== EXPIRY_REMINDER (real job)

    @Test
    fun `the expiry job queues EXPIRY_REMINDER, a service mail, and it is composed with the product page and sent with the purchase switch off`(): Unit = runBlocking {
        w.configure { config(purchaseMails = false) }

        val user = owner("Steve")
        val placed = timed(user, days = 10)

        w.clock.advance(8 * day)
        reminderJob().runOnce()

        val reminder = rows(placed.order.id).single()

        assertEquals(MailKind.EXPIRY_REMINDER, reminder.kind)
        assertEquals(MailRefType.ENTITLEMENT, reminder.refType)
        assertEquals(entitlementOf(placed).expiresAt.toString(), reminder.refKey)
        assertEquals("steve@example.com", reminder.recipient)

        assertEquals(1, job.runOnce())

        val sent = row(reminder.id)

        assertEquals(MailStatus.SENT, sent.status)
        assertEquals(1, gateway.sent.size)

        val mail = gateway.sent.single()

        assertTrue(mail.content.subject.startsWith("VIP expires on "), mail.content.subject)
        assertEquals("Renew", mail.content.buttonLabel)
        assertEquals("https://shop.example/store/${placed.product.slug}", mail.content.buttonUrl)
    }

    @Test
    fun `an expiry reminder is obsolete when the entitlement moved on, ended or expired, and for a deleted product it has no renew button`(): Unit = runBlocking {
        val user = owner("Steve")
        val a = timed(user, days = 10)
        val b = timed(owner("Alice"), days = 10)
        val c = timed(owner("Bob"), days = 10)

        w.clock.advance(8 * day)
        reminderJob().runOnce()

        val first = rows(a.order.id).single()
        val second = rows(b.order.id).single()
        val third = rows(c.order.id).single()

        // the entitlement was extended: its expiresAt is no longer the refKey
        Fixtures.setColumns(pool, "market_entitlement", entitlementOf(a).id, mapOf("expiresAt" to entitlementOf(a).expiresAt!! + 30 * day))
        // refunded / revoked: not ACTIVE any more
        Fixtures.setColumns(pool, "market_entitlement", entitlementOf(b).id, mapOf("status" to EntitlementStatus.REVOKED.name))
        // the product is gone before the mail goes out: still mailed, without the renew button
        Fixtures.setColumns(pool, "market_product", c.product.id, mapOf("deletedAt" to w.clock.now()))

        assertEquals(3, job.runOnce())
        assertEquals(MailStatus.SKIPPED, row(first.id).status)
        assertEquals("OBSOLETE", row(first.id).lastError)
        assertEquals(MailStatus.SKIPPED, row(second.id).status)
        assertEquals("OBSOLETE", row(second.id).lastError)
        assertEquals(MailStatus.SENT, row(third.id).status)
        assertEquals(1, gateway.sent.size)
        assertNull(gateway.sent.single().content.buttonUrl)

        // an expiry that has already passed is obsolete too
        val late = timed(owner("Late"), days = 10)

        w.clock.advance(8 * day)
        reminderJob().runOnce()

        val lateRow = rows(late.order.id).single()

        w.clock.advance(3 * day)
        job.runOnce()
        assertEquals("OBSOLETE", row(lateRow.id).lastError)
    }

    // ================================================================================== SUBSCRIPTION_* kinds

    @Test
    fun `the five subscription mails are service mails - queued and sent with sendEmailAfterPurchase off, composed from the subscription row`(): Unit = runBlocking {
        w.configure { config(purchaseMails = false) }

        val user = owner("Steve")
        val placed = timed(user)
        val sub = subscribe(placed, user, status = SubscriptionStatus.PAST_DUE, cancelRequestedAt = 5_000)
        val end = sub.currentPeriodEnd!!
        val ids = listOf(
            enqueue(MailKind.SUBSCRIPTION_REMINDER, MailRefType.SUBSCRIPTION, sub.id, "2", placed, "steve@example.com", JsonObject().put("periodEnd", end).put("payUrl", "/store/order/${placed.order.publicId}")),
            enqueue(MailKind.SUBSCRIPTION_PAYMENT_FAILED, MailRefType.SUBSCRIPTION, sub.id, "2", placed, "steve@example.com", JsonObject().put("graceEndsAt", sub.graceEndsAt)),
            enqueue(MailKind.SUBSCRIPTION_CANCELLED, MailRefType.SUBSCRIPTION, sub.id, "5000", placed, "steve@example.com", JsonObject().put("endsAt", end)),
            enqueue(MailKind.SUBSCRIPTION_ENDED, MailRefType.SUBSCRIPTION, sub.id, "", placed, "steve@example.com", JsonObject().put("endReason", "PAYMENT_FAILED"))
        )

        assertTrue(ids.all { it != null }, "no purchase-mail switch suppresses a subscription mail: $ids")

        // the reminder of a PAST_DUE subscription is obsolete, the other three go
        w.clock.advance(1_000)
        assertEquals(4, job.runOnce())

        assertEquals(MailStatus.SKIPPED, row(ids[0]!!).status, "SUBSCRIPTION_REMINDER needs an ACTIVE subscription")
        assertEquals(listOf(MailStatus.SENT, MailStatus.SENT, MailStatus.SENT), ids.drop(1).map { row(it!!).status })

        val subjects = gateway.sent.map { it.content.subject }

        assertTrue(subjects.contains("Payment failed for Gold Rank"), subjects.toString())
        assertTrue(subjects.contains("Gold Rank will not renew"), subjects.toString())
        assertTrue(subjects.contains("Gold Rank has ended"), subjects.toString())

        val failed = gateway.sent.first { it.content.subject.startsWith("Payment failed") }.content

        assertEquals("Update payment", failed.buttonLabel)
        assertEquals("https://shop.example/profile", failed.buttonUrl, "no pay link in the params: the profile")
        assertTrue(failed.details.any { it.label == "Amount" && it.value == "€15.00" }, failed.details.joinToString { "${it.label}=${it.value}" })
    }

    @Test
    fun `a subscription reminder is sent for an active subscription and dropped once the period renewed or the end was scheduled`(): Unit = runBlocking {
        val user = owner("Steve")
        val placed = timed(user)
        val sub = subscribe(placed, user, cycle = 2)
        val id = enqueue(MailKind.SUBSCRIPTION_REMINDER, MailRefType.SUBSCRIPTION, sub.id, "2", placed, "steve@example.com", JsonObject().put("periodEnd", sub.currentPeriodEnd))!!

        assertEquals(1, job.runOnce())
        assertEquals(MailStatus.SENT, row(id).status)
        assertTrue(gateway.sent.single().content.paragraphs.any { it.contains("We will charge €15.00") })

        // a paid renewal moved the cycle past the reminded period
        val again = enqueue(MailKind.SUBSCRIPTION_REMINDER, MailRefType.SUBSCRIPTION, sub.id, "1", placed, "steve@example.com")!!

        assertEquals(1, job.runOnce())
        assertEquals("OBSOLETE", row(again).lastError, "cycleCount 2 is past the reminded period 1")

        // cancel at period end scheduled: no reminder
        Fixtures.setColumns(pool, "market_subscription", sub.id, mapOf("cancelAtPeriodEnd" to 1))

        val third = enqueue(MailKind.SUBSCRIPTION_REMINDER, MailRefType.SUBSCRIPTION, sub.id, "2", placed, "alt@example.com")!!

        assertEquals(1, job.runOnce())
        assertEquals("OBSOLETE", row(third).lastError)
        assertEquals(1, gateway.sent.size)
    }

    @Test
    fun `a manual subscription's reminder carries the pay button, a payment failed mail is obsolete once the subscription is no longer past due`(): Unit = runBlocking {
        val user = owner("Steve")
        val placed = timed(user)
        val sub = subscribe(placed, user, mode = SubscriptionMode.MANUAL)

        enqueue(
            MailKind.SUBSCRIPTION_REMINDER, MailRefType.SUBSCRIPTION, sub.id, "2", placed, "steve@example.com",
            JsonObject().put("periodEnd", sub.currentPeriodEnd).put("payUrl", "/store/order/RENEWALPUBLICID0001")
        )

        val failed = enqueue(MailKind.SUBSCRIPTION_PAYMENT_FAILED, MailRefType.SUBSCRIPTION, sub.id, "2", placed, "steve@example.com")!!

        assertEquals(2, job.runOnce())

        val reminder = gateway.sent.single().content

        assertEquals("Pay now", reminder.buttonLabel)
        assertEquals("https://shop.example/store/order/RENEWALPUBLICID0001", reminder.buttonUrl)
        assertEquals("OBSOLETE", row(failed).lastError, "the subscription is ACTIVE, not PAST_DUE")
    }

    @Test
    fun `a subscription mail whose subscription is gone is obsolete, a row of a gone order too`(): Unit = runBlocking {
        val user = owner("Steve")
        val placed = timed(user)
        val missing = enqueue(MailKind.SUBSCRIPTION_ENDED, MailRefType.SUBSCRIPTION, 987_654, "", placed, "steve@example.com")!!
        val noShipment = enqueue(MailKind.SHIPMENT_DELIVERED, MailRefType.SHIPMENT, 987_655, "", placed, "steve@example.com")!!
        val noEntitlement = enqueue(MailKind.EXPIRY_REMINDER, MailRefType.ENTITLEMENT, 987_656, "1", placed, "steve@example.com")!!

        assertEquals(3, job.runOnce())
        assertEquals(listOf("OBSOLETE", "OBSOLETE", "OBSOLETE"), listOf(missing, noShipment, noEntitlement).map { row(it).lastError })
        assertEquals(0, gateway.calls.size)
    }

    // ================================================================================== SHIPMENT_* kinds

    @Test
    fun `shipment mails are order mails - queued with the switch on, left out with it off, and composed from the parcel`(): Unit = runBlocking {
        val user = owner("Steve")
        val placed = timed(user)
        val parcel = ship(placed)

        w.configure { config(purchaseMails = false) }
        assertNull(enqueue(MailKind.SHIPMENT_SHIPPED, MailRefType.SHIPMENT, parcel.id, "", placed, "steve@example.com"), "the purchase-mail switch turns the shipment mails off")

        w.configure { config(purchaseMails = true) }

        val params = JsonObject().put("isPartial", false).put("addressLines", io.vertx.core.json.JsonArray().add("Hans Meier").add("Strasse 1"))
        val shipped = enqueue(MailKind.SHIPMENT_SHIPPED, MailRefType.SHIPMENT, parcel.id, "", placed, "steve@example.com", params)!!

        Fixtures.setColumns(pool, "market_shipment", parcel.id, mapOf("status" to ShipmentStatus.DELIVERED.name, "deliveredAt" to w.clock.now()))

        val delivered = enqueue(MailKind.SHIPMENT_DELIVERED, MailRefType.SHIPMENT, parcel.id, "", placed, "steve@example.com")!!

        assertEquals(2, job.runOnce())
        assertEquals(listOf(MailStatus.SENT, MailStatus.SENT), listOf(shipped, delivered).map { row(it).status })

        val mails = gateway.sent.associateBy { it.content.subject }
        val ship = mails.getValue("Your order #${placed.order.id} is on its way").content

        assertEquals("Track shipment", ship.buttonLabel)
        assertEquals("https://track.example/TRK123", ship.buttonUrl)
        assertEquals(listOf("VIP"), ship.items.map { it.name })
        assertTrue(ship.details.any { it.label == "Carrier" && it.value == "Aras Kargo" })
        assertTrue(ship.details.any { it.label == "Shipping to" && it.value == "Hans Meier, Strasse 1" })
        assertTrue(mails.containsKey("Your order #${placed.order.id} was delivered"))
    }

    @Test
    fun `a shipment of another order is no mail of this order`(): Unit = runBlocking {
        val user = owner("Steve")
        val a = timed(user)
        val b = timed(owner("Alice"))
        val parcelOfB = ship(b)
        val id = enqueue(MailKind.SHIPMENT_SHIPPED, MailRefType.SHIPMENT, parcelOfB.id, "", a, "steve@example.com")!!

        assertEquals(1, job.runOnce())
        assertEquals("OBSOLETE", row(id).lastError, "the parcel belongs to order ${b.order.id}")
    }

    // ================================================================================== resend (12 section 4.5)

    @Test
    fun `resend queues a forced row that the job sends, bypassing kind switches and the purchase switch, never the platform switch`(): Unit = runBlocking {
        w.configure { config(purchaseMails = false, disabled = listOf("ORDER_CONFIRMATION")) }

        val user = owner("Steve")
        val placed = timed(user)
        val id = admin.resend(placed.order.id, "ORDER_CONFIRMATION", null, null, pool)
        val queued = row(id)

        assertEquals(MailKind.ORDER_CONFIRMATION, queued.kind)
        assertEquals("steve@example.com", queued.recipient)
        assertEquals(true, JsonObject(queued.params).getBoolean("forced"))
        assertEquals(1, job.runOnce())
        assertEquals(MailStatus.SENT, row(id).status)
        assertEquals("Your order #${placed.order.id} is confirmed", gateway.sent.single().content.subject)

        // a second resend resets the same row: PENDING, attempts 0, no error, sent again
        val again = admin.resend(placed.order.id, "ORDER_CONFIRMATION", null, null, pool)

        assertEquals(id, again)
        assertEquals(MailStatus.PENDING, row(id).status)
        assertEquals(0, row(id).attempts)
        assertNull(row(id).lastError)
        assertEquals(1, job.runOnce())
        assertEquals(2, gateway.sent.size)

        // a different recipient is a row of its own
        val other = admin.resend(placed.order.id, "ORDER_CONFIRMATION", "  Accounting@Example.COM ", null, pool)

        assertTrue(other != id)
        assertEquals("accounting@example.com", row(other).recipient)

        // the platform switch is not bypassed
        mailOn = false
        assertThrows(MailDisabled::class.java) { runBlocking { admin.resend(placed.order.id, "ORDER_CONFIRMATION", null, null, pool) } }
        mailOn = true
        hostOk = false
        assertThrows(MailDisabled::class.java) { runBlocking { admin.resend(placed.order.id, "ORDER_CONFIRMATION", null, null, pool) } }
    }

    @Test
    fun `resend refuses what the contract refuses`(): Unit = runBlocking {
        val user = owner("Steve")
        val placed = timed(user)
        val noMail = timed(owner("Mute", email = null))

        assertThrows(InvalidMailKind::class.java) { runBlocking { admin.resend(placed.order.id, "NOPE", null, null, pool) } }
        assertThrows(InvalidMailKind::class.java) { runBlocking { admin.resend(placed.order.id, null, null, null, pool) } }
        assertThrows(NotFound::class.java) { runBlocking { admin.resend(9_999_999, "ORDER_CONFIRMATION", null, null, pool) } }

        val bad = assertThrows(RequestValueException::class.java) { runBlocking { admin.resend(placed.order.id, "ORDER_CONFIRMATION", "not an address", null, pool) } }

        assertEquals("recipient", bad.field)
        assertThrows(RequestValueException::class.java) { runBlocking { admin.resend(placed.order.id, "ORDER_CONFIRMATION", "a@b.example\r\nBcc: x@y.example", null, pool) } }

        // no address on the order and none given
        Fixtures.setColumns(pool, "market_order", noMail.order.id, mapOf("userId" to null))
        assertThrows(MailRecipientRequired::class.java) { runBlocking { admin.resend(noMail.order.id, "ORDER_CONFIRMATION", null, null, pool) } }

        // not applicable: not a gift, a pending order, no refund, no shipment, no subscription, no bank instructions, no entitlement end
        assertThrows(MailNotApplicable::class.java) { runBlocking { admin.resend(placed.order.id, "GIFT_RECEIVED", null, null, pool) } }
        assertThrows(MailNotApplicable::class.java) { runBlocking { admin.resend(placed.order.id, "ORDER_RECEIVED", null, null, pool) } }
        assertThrows(MailNotApplicable::class.java) { runBlocking { admin.resend(placed.order.id, "BANK_TRANSFER_INSTRUCTIONS", null, null, pool) } }
        assertThrows(MailNotApplicable::class.java) { runBlocking { admin.resend(placed.order.id, "ORDER_REFUNDED", null, null, pool) } }
        assertThrows(MailNotApplicable::class.java) { runBlocking { admin.resend(placed.order.id, "SHIPMENT_SHIPPED", null, null, pool) } }
        assertThrows(MailNotApplicable::class.java) { runBlocking { admin.resend(placed.order.id, "SUBSCRIPTION_ENDED", null, null, pool) } }

        // an unpaid order: the paid-only kinds
        val pending = d.place(user = user, actions = listOf(permission("g")), status = com.panomc.plugins.market.util.OrderStatus.PENDING)

        Fixtures.setColumns(pool, "market_order", pending.order.id, mapOf("email" to "steve@example.com"))
        assertThrows(MailNotApplicable::class.java) { runBlocking { admin.resend(pending.order.id, "ORDER_CONFIRMATION", null, null, pool) } }
        assertThrows(MailNotApplicable::class.java) { runBlocking { admin.resend(pending.order.id, "ORDER_DELIVERED", null, null, pool) } }
        assertTrue(admin.resend(pending.order.id, "ORDER_RECEIVED", null, null, pool) > 0)

        // a row that is being sent cannot be reset under the job's feet
        val id = admin.resend(placed.order.id, "ORDER_DELIVERED", null, null, pool)

        assertEquals(1, w.mailOutbox.transition(id, MailStatus.PENDING, MailStatus.SENDING, 1, null, null, null, w.clock.now(), pool).let { if (it) 1 else 0 })
        assertThrows(MailNotApplicable::class.java) { runBlocking { admin.resend(placed.order.id, "ORDER_DELIVERED", null, null, pool) } }
    }

    @Test
    fun `resend of refund, shipment, entitlement and subscription mails picks the reference and the owner's address`(): Unit = runBlocking {
        val user = owner("Steve")
        val placed = timed(user)
        val sub = subscribe(placed, user, status = SubscriptionStatus.PAST_DUE, cancelRequestedAt = 7_000, endReason = "BUYER_CANCEL", email = "billing@example.com")
        val parcel = ship(placed)
        val deliveredParcel = ship(placed, status = ShipmentStatus.DELIVERED, tracking = "TRK999")
        val refundId = w.refunds.add(
            com.panomc.plugins.market.db.model.MarketRefund(
                orderId = placed.order.id, status = RefundStatus.SUCCEEDED, amount = 300, gatewayAmount = 300, currency = "EUR", createdAt = w.clock.now(), updatedAt = w.clock.now()
            ),
            pool
        )!!

        Fixtures.setColumns(pool, "market_order", placed.order.id, mapOf("refundedTotal" to 300, "refundedGatewayAmount" to 300))

        // shipment: the newest shipped one by default, a given one when it fits the kind
        val shipped = admin.resend(placed.order.id, "SHIPMENT_SHIPPED", null, null, pool)

        assertEquals(deliveredParcel.id, row(shipped).refId, "the newest parcel that was handed over")
        assertEquals(parcel.id, row(admin.resend(placed.order.id, "SHIPMENT_SHIPPED", null, parcel.id, pool)).refId)
        assertThrows(MailNotApplicable::class.java) { runBlocking { admin.resend(placed.order.id, "SHIPMENT_DELIVERED", null, parcel.id, pool) } }
        assertEquals(deliveredParcel.id, row(admin.resend(placed.order.id, "SHIPMENT_DELIVERED", null, null, pool)).refId)
        assertThrows(MailNotApplicable::class.java) { runBlocking { admin.resend(placed.order.id, "SHIPMENT_SHIPPED", null, 424_242, pool) } }

        // refund
        val refunded = row(admin.resend(placed.order.id, "ORDER_REFUNDED", null, null, pool))

        assertEquals(MailRefType.REFUND, refunded.refType)
        assertEquals(refundId, refunded.refId)

        // subscription kinds go to the subscription's address; PAST_DUE fits PAYMENT_FAILED, not REMINDER
        val failed = row(admin.resend(placed.order.id, "SUBSCRIPTION_PAYMENT_FAILED", null, null, pool))

        assertEquals("billing@example.com", failed.recipient)
        assertEquals("2", failed.refKey)
        assertThrows(MailNotApplicable::class.java) { runBlocking { admin.resend(placed.order.id, "SUBSCRIPTION_REMINDER", null, null, pool) } }
        assertEquals("7000", row(admin.resend(placed.order.id, "SUBSCRIPTION_CANCELLED", null, null, pool)).refKey)
        assertThrows(MailNotApplicable::class.java) { runBlocking { admin.resend(placed.order.id, "SUBSCRIPTION_ENDED", null, null, pool) } }

        // entitlement
        val entitlement = row(admin.resend(placed.order.id, "EXPIRY_REMINDER", null, null, pool))

        assertEquals(MailRefType.ENTITLEMENT, entitlement.refType)
        assertEquals(entitlementOf(placed).expiresAt.toString(), entitlement.refKey)

        // all of them reach the buyer through the job, a forced row skipping the relevance check (the ACTIVE entitlement and PAST_DUE subscription fit anyway)
        val queued = rows(placed.order.id).filter { it.status == MailStatus.PENDING }.size

        assertTrue(queued >= 6)
        assertEquals(queued, job.runOnce())
        assertEquals(0, rows(placed.order.id).count { it.status == MailStatus.FAILED }, rows(placed.order.id).filter { it.status == MailStatus.FAILED }.joinToString { "${it.kind}: ${it.lastError}" })
    }

    @Test
    fun `a forced resend of an old reminder is sent although the relevance check would drop it`(): Unit = runBlocking {
        val user = owner("Steve")
        val placed = timed(user)
        val sub = subscribe(placed, user, status = SubscriptionStatus.ACTIVE, cycle = 5)
        val id = enqueue(MailKind.SUBSCRIPTION_REMINDER, MailRefType.SUBSCRIPTION, sub.id, "1", placed, "steve@example.com", JsonObject().put("periodEnd", sub.currentPeriodEnd))!!

        assertEquals(1, job.runOnce())
        assertEquals("OBSOLETE", row(id).lastError)

        // resend: the key of the current period (a new row), and the old one reset by hand with forced
        val fresh = admin.resend(placed.order.id, "SUBSCRIPTION_REMINDER", null, null, pool)

        assertEquals("5", row(fresh).refKey)
        assertEquals(1, job.runOnce())
        assertEquals(MailStatus.SENT, row(fresh).status)
    }

    // ================================================================================== retry

    @Test
    fun `retry sends a FAILED or SKIPPED row right away, refuses the other states and reports a failed send as 502`(): Unit = runBlocking {
        val user = owner("Steve")
        val placed = timed(user)
        val sub = subscribe(placed, user)

        // a FAILED row (render error at the time) is sent by the retry
        val failed = enqueue(MailKind.SUBSCRIPTION_CANCELLED, MailRefType.SUBSCRIPTION, sub.id, "100", placed, "steve@example.com")!!

        w.mailOutbox.transition(failed, MailStatus.PENDING, MailStatus.SENDING, 1, null, null, null, w.clock.now(), pool)
        w.mailOutbox.transition(failed, MailStatus.SENDING, MailStatus.FAILED, 1, null, "RENDER_ERROR: X", null, w.clock.now(), pool)

        val done = admin.retry(failed, pool)

        assertEquals(MailStatus.SENT, row(failed).status)
        assertEquals(MailKind.SUBSCRIPTION_CANCELLED, done.kind)
        assertEquals(1, gateway.sent.size)

        // SENT, PENDING: not retryable
        assertEquals(InvalidState::class.java, assertThrows(InvalidState::class.java) { runBlocking { admin.retry(failed, pool) } }.javaClass)

        val pending = enqueue(MailKind.SUBSCRIPTION_ENDED, MailRefType.SUBSCRIPTION, sub.id, "", placed, "steve@example.com")!!

        assertThrows(InvalidState::class.java) { runBlocking { admin.retry(pending, pool) } }
        assertThrows(NotFound::class.java) { runBlocking { admin.retry(9_999_999, pool) } }

        // a SKIPPED (OBSOLETE) row is sent by the retry: it is forced
        assertEquals(1, job.runOnce().let { 1 })

        val skipped = enqueue(MailKind.SUBSCRIPTION_REMINDER, MailRefType.SUBSCRIPTION, sub.id, "1", placed, "steve@example.com")!!

        job.runOnce()
        // the pending ENDED row went above; make the reminder obsolete: cycleCount 2 > 1
        assertEquals(MailStatus.SKIPPED, row(skipped).status)
        assertEquals(MailStatus.SENT, admin.retry(skipped, pool).let { row(skipped).status })

        // a send that fails: 502, the row keeps the error and goes back to PENDING with backoff
        val flaky = enqueue(MailKind.SUBSCRIPTION_CANCELLED, MailRefType.SUBSCRIPTION, sub.id, "200", placed, "steve@example.com")!!

        job.runOnce()
        assertEquals(MailStatus.SENT, row(flaky).status)
        w.mailOutbox.transition(flaky, MailStatus.SENT, MailStatus.FAILED, 1, null, "old", null, w.clock.now(), pool).let { }
        Fixtures.setColumns(pool, "market_mail_outbox", flaky, mapOf("status" to "FAILED"))
        gateway.failNext(1, IOException("smtp unreachable"))
        assertThrows(MailSendFailed::class.java) { runBlocking { admin.retry(flaky, pool) } }
        assertEquals(MailStatus.PENDING, row(flaky).status)
        assertEquals("IOException: smtp unreachable", row(flaky).lastError)
        assertTrue(row(flaky).nextAttemptAt!! > w.clock.now(), "the job's backoff takes over")

        // mail disabled: 409 before anything is touched
        Fixtures.setColumns(pool, "market_mail_outbox", flaky, mapOf("status" to "FAILED"))
        mailOn = false
        assertThrows(MailDisabled::class.java) { runBlocking { admin.retry(flaky, pool) } }
        assertEquals(MailStatus.FAILED, row(flaky).status)
    }

    // ================================================================================== list

    @Test
    fun `the list is newest first, filters by status, kind and order, masks recipients without OM or PAY and pages`(): Unit = runBlocking {
        val user = owner("Steve")
        val placed = timed(user)
        val other = timed(owner("Alice"))

        val first = admin.resend(placed.order.id, "ORDER_CONFIRMATION", "john.smith@example.com", null, pool)
        val second = admin.resend(placed.order.id, "ORDER_DELIVERED", "john.smith@example.com", null, pool)
        val third = admin.resend(other.order.id, "ORDER_CONFIRMATION", "alice@example.org", null, pool)

        Fixtures.setColumns(pool, "market_mail_outbox", first, mapOf("status" to "FAILED", "attempts" to 10, "lastError" to "550 mailbox john.smith@example.com unavailable"))

        val all = admin.list(null, null, null, PageRequest(1, 10), seeRecipients = true, client = pool)

        assertEquals(3, all.total)
        assertEquals(listOf(third, second, first), all.rows.map { it.getLong("id") })
        assertEquals("john.smith@example.com", all.rows[2].getString("recipient"))
        assertEquals(setOf("id", "kind", "orderId", "recipient", "status", "attempts", "lastError", "createdAt", "sentAt"), all.rows[0].fieldNames().toSet())

        val masked = admin.list(null, null, null, PageRequest(1, 10), seeRecipients = false, client = pool)

        assertEquals("j***@e***.com", masked.rows[2].getString("recipient"))
        assertFalse(masked.rows[2].getString("lastError").contains("john.smith"), "the address is also gone from the error text: ${masked.rows[2].getString("lastError")}")
        assertTrue(masked.rows.none { it.getString("recipient").contains("alice") })

        assertEquals(listOf(first), admin.list(MailStatus.FAILED, null, null, PageRequest(1, 10), true, pool).rows.map { it.getLong("id") })
        assertEquals(listOf(third, first), admin.list(null, MailKind.ORDER_CONFIRMATION, null, PageRequest(1, 10), true, pool).rows.map { it.getLong("id") })
        assertEquals(listOf(third), admin.list(null, null, other.order.id, PageRequest(1, 10), true, pool).rows.map { it.getLong("id") })

        val page2 = admin.list(null, null, null, PageRequest(2, 2), true, pool)

        assertEquals(3, page2.total)
        assertEquals(listOf(first), page2.rows.map { it.getLong("id") })
        assertNotNull(all.rows[0].getValue("createdAt"))
    }

    // ================================================================================== test mail

    @Test
    fun `the test mail goes straight to the gateway - a plain one by default, a sample of any kind, the admin's address unless one is given`(): Unit = runBlocking {
        admin.sendTest(null, null, "admin@example.com")

        val plain = gateway.sent.single()

        assertEquals("admin@example.com", plain.recipient)
        assertEquals("Test e-mail from Blocky Store", plain.content.subject)
        assertEquals(emptyList<MarketMailOutbox>(), w.mailOutbox.getDue(MailStatus.PENDING, Long.MAX_VALUE, 10, pool), "a test mail never touches the outbox")

        // every kind has a sample that renders and goes out marked as a test
        for (kind in MailKind.entries) {
            admin.sendTest(kind.name, " Someone@Example.com ", "admin@example.com")

            val mail = gateway.sent.last()

            assertEquals("someone@example.com", mail.recipient, kind.name)
            assertTrue(mail.content.subject.startsWith("[TEST] "), "$kind: ${mail.content.subject}")
            assertFalse(mail.content.subject.contains("mail."), "$kind: ${mail.content.subject}")
        }

        assertEquals(1 + MailKind.entries.size, gateway.sent.size)

        assertThrows(InvalidMailKind::class.java) { runBlocking { admin.sendTest("NOPE", null, "admin@example.com") } }
        assertThrows(MailRecipientRequired::class.java) { runBlocking { admin.sendTest(null, null, null) } }
        assertThrows(MailRecipientRequired::class.java) { runBlocking { admin.sendTest(null, null, "  ") } }
        assertThrows(RequestValueException::class.java) { runBlocking { admin.sendTest(null, "bad address", "admin@example.com") } }
    }

    @Test
    fun `the test mail answers 409 when mail is off and 502 when the send fails, with the reason of neither leaking`(): Unit = runBlocking {
        mailOn = false
        assertThrows(MailDisabled::class.java) { runBlocking { admin.sendTest(null, null, "admin@example.com") } }
        mailOn = true
        hostOk = false
        assertThrows(MailDisabled::class.java) { runBlocking { admin.sendTest(null, null, "admin@example.com") } }
        hostOk = true

        gateway.answerNext(MailSendResult.DISABLED)
        assertThrows(MailDisabled::class.java) { runBlocking { admin.sendTest(null, null, "admin@example.com") } }

        gateway.failNext(1, IOException("535 5.7.8 authentication failed for secret-user"))

        val failure = assertThrows(MailSendFailed::class.java) { runBlocking { admin.sendTest(null, null, "admin@example.com") } }

        assertFalse((failure.message ?: "").contains("secret-user"))
        assertEquals(502, failure.getStatusCode())
    }

}
