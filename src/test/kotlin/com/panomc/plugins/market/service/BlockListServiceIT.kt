package com.panomc.plugins.market.service

import com.panomc.platform.error.NotFound
import com.panomc.platform.model.Error
import com.panomc.plugins.market.config.MarketConfig
import com.panomc.plugins.market.core.abuse.BlockSubjects
import com.panomc.plugins.market.core.cart.CartLine
import com.panomc.plugins.market.db.MarketDaoITBase
import com.panomc.plugins.market.db.model.BlockSource
import com.panomc.plugins.market.db.model.BlockType
import com.panomc.plugins.market.db.model.MarketBlock
import com.panomc.plugins.market.db.model.MarketOrder
import com.panomc.plugins.market.db.model.MarketProduct
import com.panomc.plugins.market.db.model.OrderEventType
import com.panomc.plugins.market.db.model.PaymentStatus
import com.panomc.plugins.market.error.BlockAlreadyExists
import com.panomc.plugins.market.error.BuyerBlocked
import com.panomc.plugins.market.error.InvalidBlock
import com.panomc.plugins.market.service.platform.DirectoryUser
import com.panomc.plugins.market.service.platform.UserDirectory
import com.panomc.plugins.market.spi.payment.DisputeState
import com.panomc.plugins.market.spi.payment.PaymentEvent
import com.panomc.plugins.market.spi.payment.PaymentTarget
import com.panomc.plugins.market.support.MarketTestDb
import com.panomc.plugins.market.support.Race
import com.panomc.plugins.market.support.TestUser
import com.panomc.plugins.market.support.TestWiring
import com.panomc.plugins.market.util.OrderStatus
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.SqlClient
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The block list on a real MariaDB (MK-151; 11 sections 9 and 10, 06 section 6.8, tests 19.8 of 11 and F-18 / V-08 of 17): the lookup behind quote and
 * checkout (a message at the quote, 403 `BUYER_BLOCKED` at checkout, nothing revealed), the once-a-minute atomic `hitCount`, expiry, the IP ranges kept in
 * memory, a payer blocked after checkout (REVIEW `BLOCKED_BUYER`), the guards of `/pay` and friends, the chargeback blocks the dispute service wrote (the
 * recipient and the e-mail, never a typed guest payer name), and the panel operations (duplicate, replaced expiry, value rules, SELF, list, delete).
 * The global invariants (I1 to I22) are checked after every test by the base class.
 */
class BlockListServiceIT : MarketDaoITBase() {
    private lateinit var w: TestWiring
    private lateinit var ph: PaymentHarness
    private lateinit var blocks: BlockListService
    private val vertx: Vertx = Vertx.vertx()

    @AfterAll
    fun closeVertx() {
        vertx.close()
    }

    @BeforeEach
    fun freshState() {
        runBlocking { resetState() }
        w = TestWiring(pool)

        val directory = object : UserDirectory {
            override suspend fun byUsername(username: String, sqlClient: SqlClient): DirectoryUser? = w.users.idOf(username)?.let { DirectoryUser(it, w.users.nameOf(it)!!) }

            override suspend fun usernameOf(userId: Long, sqlClient: SqlClient): String? = w.users.nameOf(userId)

            override suspend fun emailOf(userId: Long, sqlClient: SqlClient): String? = null

            override suspend fun hasPermission(userId: Long, node: String): Boolean = false
        }

        blocks = BlockListService(w.clock, w.blocks, directory, w.db)
        ph = PaymentHarness(w, vertx)
        ph.rebuild(extraGuards = listOf(BlockedBuyerGuard(blocks)))
        ph.h.blocked = { payer, recipient, email, ip, userId -> runBlocking { blocks.blocked(payer, recipient, email, ip, userId, w.pool) } }
    }

    override suspend fun assertInvariants() {
        w.assertInvariants()
    }

    private val fx get() = w.fixtures
    private val h get() = ph.h

    // ------------------------------------------------------------------------------------------------------ helpers

    private val admin = BlockListService.Actor(900, "Admin")

    private suspend fun block(type: String, value: String, expiresAt: Long? = null, actor: BlockListService.Actor = admin): MarketBlock = blocks.create(type, value, "test", expiresAt, actor)

    private suspend fun row(id: Long): MarketBlock? = w.blocks.getById(id, pool)

    private suspend fun user(name: String): Pair<TestUser, QuoteCaller> {
        val u = fx.user(name)

        h.emails[u.id] = "$name@example.com"

        return u to QuoteCaller(u.id)
    }

    private fun line(product: MarketProduct) = h.line(product)

    private suspend fun quote(product: MarketProduct, caller: QuoteCaller = QuoteCaller.GUEST, recipient: String? = null, guest: GuestInput = GuestInput("Steve", "steve@example.com")): Quote =
        h.service.quote(
            QuoteInput(items = listOf(CartLine(product.id, 0, 1, emptyMap(), null)), guest = if (caller.loggedIn) null else guest, recipientUsername = recipient), caller, pool
        )

    private suspend fun checkout(product: MarketProduct, caller: QuoteCaller = QuoteCaller.GUEST, guest: Map<String, Any?>? = mapOf("username" to "Steve", "email" to "steve@example.com"), vararg extra: Pair<String, Any?>): CheckoutResult =
        h.checkout(h.body("items" to listOf(line(product)), "paymentMethodId" to "fake", "guest" to guest, *extra), caller = caller)

    private suspend fun expectBlocked(block: suspend () -> Any?) {
        val e = try {
            block()

            null
        } catch (e: Error) {
            e
        } ?: error("expected BUYER_BLOCKED, nothing was thrown")

        assertEquals("BUYER_BLOCKED", e.getErrorCode())
        assertEquals(403, e.getStatusCode())
        assertEquals(setOf("result", "error"), JsonObject(e.encode()).fieldNames(), "no block id, type or reason in the answer")
    }

    private suspend fun orderOf(result: CheckoutResult): MarketOrder = ph.order(result.order.getString("publicId"))

    private suspend fun orderCount(): Int = sql("SELECT COUNT(*) AS c FROM `${MarketTestDb.TABLE_PREFIX}market_order`").single().getInteger("c")

    // ================================================================================== F-18: blocked buyer at quote and checkout

    @Test
    fun `F-18 a blocked PLAYER is warned at the quote and refused with 403 at checkout, in any case, and nothing is written`(): Unit = runBlocking {
        val p = fx.product(price = 1000)

        fx.paymentMethod("fake")
        block("PLAYER", "STEVE")

        assertEquals("steve", w.blocks.getAll(pool).single().value, "stored lower-cased")

        val q = quote(p)

        assertEquals(listOf("BUYER_BLOCKED"), q.messages.filter { it.code == "BUYER_BLOCKED" }.map { it.code })
        assertNull(quote(p, guest = GuestInput("Alex", "alex@example.com")).messages.firstOrNull { it.code == "BUYER_BLOCKED" }, "somebody else is not blocked")

        expectBlocked { checkout(p) }
        expectBlocked { checkout(p, guest = mapOf("username" to "sTeVe", "email" to "other@example.com")) }
        assertEquals(0, orderCount())

        // somebody else buys
        checkout(p, guest = mapOf("username" to "Alex", "email" to "alex@example.com"))
        assertEquals(1, orderCount())
    }

    @Test
    fun `F-18 EMAIL blocks, exact and whole domain, refuse a guest and the account e-mail of a logged-in buyer, and never evil-example dot com for at example dot com`(): Unit = runBlocking {
        val p = fx.product(price = 1000)

        fx.paymentMethod("fake")
        block("EMAIL", "Steve@Example.com")
        block("EMAIL", "@bad.org")

        expectBlocked { checkout(p, guest = mapOf("username" to "Zed", "email" to "STEVE@example.com")) }
        expectBlocked { checkout(p, guest = mapOf("username" to "Zed", "email" to "anyone@bad.org")) }
        assertEquals(0, orderCount())
        assertNotNull(quote(p, guest = GuestInput("Zed", "anyone@bad.org")).messages.firstOrNull { it.code == "BUYER_BLOCKED" })

        // a different address and a look-alike domain pass
        checkout(p, guest = mapOf("username" to "Zed", "email" to "steve2@example.com"))
        checkout(p, guest = mapOf("username" to "Zed", "email" to "x@evil-bad.org"))
        checkout(p, guest = mapOf("username" to "Zed", "email" to "x@sub.bad.org"))
        assertEquals(3, orderCount())

        // the e-mail of a logged-in buyer's account is the order e-mail
        val (_, bob) = user("Bob")

        block("EMAIL", "bob@example.com")
        expectBlocked { checkout(p, caller = bob, guest = null) }
    }

    @Test
    fun `F-18 a blocked USER id follows the account whatever name it checks out with`(): Unit = runBlocking {
        val p = fx.product(price = 1000)

        fx.paymentMethod("fake")

        val (alex, caller) = user("Alex")

        block("USER", alex.id.toString())
        assertNotNull(quote(p, caller).messages.firstOrNull { it.code == "BUYER_BLOCKED" })
        expectBlocked { checkout(p, caller, guest = null) }

        // the same name as a guest is a different buyer for a USER block
        checkout(p, guest = mapOf("username" to "Alex", "email" to "alex@example.com"))
        assertEquals(1, orderCount())
    }

    @Test
    fun `F-18 IP blocks, single address and CIDR for IPv4 and IPv6, refuse the caller's address, an expired block no longer applies`(): Unit = runBlocking {
        val p = fx.product(price = 1000)

        fx.paymentMethod("fake")

        val single = block("IP", "198.51.100.9")

        block("IP", "203.0.113.77/24")
        block("IP", "2001:DB8:0:0:0:0:0:0/32")

        assertEquals(setOf("198.51.100.9", "203.0.113.0/24", "2001:db8::/32"), w.blocks.getAll(pool).map { it.value }.toSet(), "canonical text, host bits zeroed")

        for (ip in listOf("198.51.100.9", "203.0.113.200", "2001:db8:abcd::1")) {
            val caller = QuoteCaller(null, clientIp = ip)

            assertNotNull(quote(p, caller).messages.firstOrNull { it.code == "BUYER_BLOCKED" }, "quote $ip")
            expectBlocked { checkout(p, caller) }
        }

        for (ip in listOf("198.51.100.10", "203.0.114.1", "2001:db9::1")) checkout(p, QuoteCaller(null, clientIp = ip))

        assertEquals(3, orderCount())

        // an expired range stops matching without waiting for the reload, and the row is only a row
        assertTrue(w.blocks.delete(single.id, pool))
        blocks.reloadIps()
        checkout(p, QuoteCaller(null, clientIp = "198.51.100.9"))

        val timed = block("IP", "192.0.2.0/24", expiresAt = w.clock.now() + 60_000)

        expectBlocked { checkout(p, QuoteCaller(null, clientIp = "192.0.2.5")) }
        w.clock.advance(60_001)
        checkout(p, QuoteCaller(null, clientIp = "192.0.2.5"))
        assertNotNull(row(timed.id), "the row stays until the housekeeping job deletes it")
    }

    @Test
    fun `an expired PLAYER block is ignored by the lookup and a block that is not yet expired still applies`(): Unit = runBlocking {
        val p = fx.product(price = 1000)

        fx.paymentMethod("fake")
        block("PLAYER", "steve", expiresAt = w.clock.now() + 3_600_000)
        expectBlocked { checkout(p) }
        w.clock.advance(3_599_999)
        expectBlocked { checkout(p) }
        w.clock.advance(1)
        checkout(p)
        assertEquals(1, orderCount())
    }

    @Test
    fun `a gift to a blocked recipient is refused like a blocked payer, by name and by the account of that name`(): Unit = runBlocking {
        val p = fx.product(price = 1000)

        fx.paymentMethod("fake")
        h.config = h.config.copy(allowGiftPurchase = true)

        val (_, alex) = user("Alex")
        val (bob, _) = user("Bob")
        val (carol, _) = user("Carol")

        block("PLAYER", "bob")
        block("USER", carol.id.toString())

        assertNotNull(quote(p, alex, recipient = "Bob").messages.firstOrNull { it.code == "BUYER_BLOCKED" })
        expectBlocked { checkout(p, alex, guest = null, "recipientUsername" to "Bob") }
        expectBlocked { checkout(p, alex, guest = null, "recipientUsername" to "Carol") }
        assertEquals(0, orderCount())
        assertNotNull(bob.id)

        val (_, dave) = user("Dave")

        checkout(p, alex, guest = null, "recipientUsername" to "Dave")
        assertNotNull(dave.userId)
        assertEquals(1, orderCount())
    }

    @Test
    fun `a replay of a checkout is not judged again, the idempotent answer is the stored order`(): Unit = runBlocking {
        val p = fx.product(price = 1000)

        fx.paymentMethod("fake")

        val first = h.checkout(h.body("items" to listOf(line(p)), "paymentMethodId" to "fake"), key = "fixed-key-0000001")

        block("PLAYER", "steve")

        val replay = h.checkout(h.body("items" to listOf(line(p)), "paymentMethodId" to "fake"), key = "fixed-key-0000001")

        assertEquals(first.order.getString("publicId"), replay.order.getString("publicId"))
        expectBlocked { h.checkout(h.body("items" to listOf(line(p)), "paymentMethodId" to "fake"), key = "fixed-key-0000002") }
    }

    // ================================================================================== atomic hit count

    @Test
    fun `F-18 hitCount grows at most once a minute per row, atomically under 20 parallel checks, and an expired row never counts`(): Unit = runBlocking {
        val id = block("PLAYER", "steve").id
        val expired = block("PLAYER", "late", expiresAt = w.clock.now() + 1_000)

        val outcomes = Race.run(20) { blocks.check(BlockSubjects(usernames = setOf("Steve")), w.pool) }

        assertTrue(outcomes.all { it.isSuccess && it.getOrNull()?.blockId == id })
        assertEquals(1, row(id)!!.hitCount, "twenty hits in one minute are one count")
        assertEquals(w.clock.now(), row(id)!!.lastHitAt)

        blocks.check(BlockSubjects(usernames = setOf("steve")), w.pool)
        w.clock.advance(59_999)
        blocks.check(BlockSubjects(usernames = setOf("steve")), w.pool)
        assertEquals(1, row(id)!!.hitCount)

        w.clock.advance(1)

        val second = Race.run(20) { blocks.check(BlockSubjects(usernames = setOf("steve")), w.pool) }

        assertTrue(second.all { it.isSuccess })
        assertEquals(2, row(id)!!.hitCount, "the next minute adds exactly one")

        w.clock.advance(2_000)
        assertNull(blocks.check(BlockSubjects(usernames = setOf("late")), w.pool))
        assertEquals(0, row(expired.id)!!.hitCount, "an expired block is not a hit")

        // the first count of a row that was never hit
        val other = block("EMAIL", "x@y.com")

        blocks.check(BlockSubjects(emails = setOf("X@Y.com")), w.pool)
        assertEquals(1, row(other.id)!!.hitCount)
    }

    @Test
    fun `first hit wins in the order USER, PLAYER, EMAIL, IP and the payer is judged before the recipient`(): Unit = runBlocking {
        val (alex, _) = user("Alex")
        val ip = block("IP", "203.0.113.0/24")
        val email = block("EMAIL", "a@b.com")
        val player = block("PLAYER", "alex")
        val userBlock = block("USER", alex.id.toString())
        val subjects = BlockSubjects(usernames = setOf("Alex"), userIds = setOf(alex.id), emails = setOf("a@b.com"), ip = "203.0.113.5")

        assertEquals(userBlock.id, blocks.check(subjects, pool)!!.blockId)
        assertTrue(w.blocks.delete(userBlock.id, pool))
        assertEquals(player.id, blocks.check(subjects, pool)!!.blockId)
        assertTrue(w.blocks.delete(player.id, pool))
        assertEquals(email.id, blocks.check(subjects, pool)!!.blockId)
        assertTrue(w.blocks.delete(email.id, pool))
        assertEquals(ip.id, blocks.check(subjects, pool)!!.blockId)

        val bobBlock = block("PLAYER", "bob")
        val hit = blocks.check(BlockSubjects(usernames = setOf("Alex"), ip = "203.0.113.5"), BlockSubjects(usernames = setOf("Bob")), pool)!!

        assertEquals(BlockRole.PAYER, hit.role, "the payer's own hit comes first")
        assertTrue(w.blocks.delete(ip.id, pool))
        blocks.reloadIps()

        val recipient = blocks.check(BlockSubjects(usernames = setOf("Alex")), BlockSubjects(usernames = setOf("Bob")), pool)!!

        assertEquals(BlockRole.RECIPIENT, recipient.role)
        assertEquals(bobBlock.id, recipient.blockId)
        assertEquals("PLAYER", recipient.type)
    }

    @Test
    fun `the IP ranges are kept in memory for 60 seconds, a write through the service is live at once`(): Unit = runBlocking {
        val ip = "203.0.113.5"

        assertNull(blocks.check(BlockSubjects(ip = ip), pool))

        // written behind the service's back: not seen before the reload interval
        val now = w.clock.now()

        w.blocks.add(MarketBlock(type = BlockType.IP, value = "203.0.113.0/24", source = BlockSource.MANUAL, createdAt = now, updatedAt = now), pool)
        assertNull(blocks.check(BlockSubjects(ip = ip), pool), "the cached (empty) list is still used")
        w.clock.advance(BlockListService.IP_RELOAD_MS)
        assertNotNull(blocks.check(BlockSubjects(ip = ip), pool))

        // written through the service
        block("IP", "198.51.100.0/24")
        assertNotNull(blocks.check(BlockSubjects(ip = "198.51.100.77"), pool))
    }

    // ================================================================================== a payer blocked after checkout

    @Test
    fun `a payer blocked after checkout is REVIEW at payment, nothing is delivered, and an unblocked order completes`(): Unit = runBlocking {
        val p = fx.product(price = 1000, stock = 5)

        fx.paymentMethod("fake")

        val clean = orderOf(checkout(p, guest = mapOf("username" to "Alex", "email" to "alex@example.com")))
        val blockedLater = orderOf(checkout(p))

        block("PLAYER", "steve")

        val applied = ph.succeed(blockedLater.id, ph.attempts(blockedLater.id).single())
        val held = ph.order(blockedLater.id)

        assertEquals(PaymentStatus.SUCCEEDED, applied.attemptStatus, "the money arrived and is recorded")
        assertEquals(OrderStatus.REVIEW, held.status)
        assertEquals("BLOCKED_BUYER", held.reviewReason)
        assertEquals(1000, held.paidAmount, "the money is on the order, a rejection refunds it")
        assertTrue(ph.effects.of(held.id).isEmpty(), "nothing is delivered")
        assertEquals(0, w.products.getById(p.id, pool)!!.soldCount)
        assertTrue(ph.alerts.any { it.first == held.id && it.second == "BLOCKED_BUYER" })
        assertTrue(w.orderEvents.getByOrderId(held.id, pool).any { it.type == OrderEventType.STATUS_CHANGED && it.toStatus == "REVIEW" && it.message == BlockedBuyerGuard.NOTE })

        ph.succeed(clean.id, ph.attempts(clean.id).single())
        assertEquals(OrderStatus.COMPLETED, ph.order(clean.id).status)
    }

    @Test
    fun `a blocked USER, a blocked e-mail and a blocked gift recipient divert a paid order, an IP block does not (no IP at payment)`(): Unit = runBlocking {
        h.config = h.config.copy(allowGiftPurchase = true)
        fx.paymentMethod("fake")

        val p = fx.product(price = 1000, stock = 9)
        val (alex, alexCaller) = user("Alex")
        val (_, bobCaller) = user("Bob")
        val (carol, _) = user("Carol")

        val byUser = orderOf(checkout(p, alexCaller, guest = null))
        val byEmail = orderOf(checkout(p, bobCaller, guest = null))
        val gift = orderOf(checkout(p, alexCaller, guest = null, "recipientUsername" to "Carol"))
        val byIp = orderOf(checkout(p, QuoteCaller(null, clientIp = "203.0.113.5"), guest = mapOf("username" to "Zed", "email" to "zed@example.com")))

        // the user block would also stop the gift (its payer is Alex): block the pieces apart
        block("EMAIL", "bob@example.com")
        block("IP", "203.0.113.0/24")
        block("USER", carol.id.toString())

        for (o in listOf(byEmail, gift)) {
            ph.succeed(o.id, ph.attempts(o.id).single())
            assertEquals(OrderStatus.REVIEW, ph.order(o.id).status, "order ${o.id}")
            assertEquals("BLOCKED_BUYER", ph.order(o.id).reviewReason)
        }

        ph.succeed(byIp.id, ph.attempts(byIp.id).single())
        assertEquals(OrderStatus.COMPLETED, ph.order(byIp.id).status, "the address is judged at the request only")

        block("USER", alex.id.toString())
        ph.succeed(byUser.id, ph.attempts(byUser.id).single())
        assertEquals(OrderStatus.REVIEW, ph.order(byUser.id).status)
        assertEquals("BLOCKED_BUYER", ph.order(byUser.id).reviewReason)
    }

    // ================================================================================== /pay, /payment/continue, /bank-transfer/notify

    @Test
    fun `the payer of an order and the caller's IP are judged before a new attempt, with 403 and no detail`(): Unit = runBlocking {
        fx.paymentMethod("fake")

        val p = fx.product(price = 1000, stock = 5)
        val order = orderOf(checkout(p))

        blocks.requireOrderBuyerAllowed(order, "203.0.113.5", pool)

        block("IP", "203.0.113.0/24")
        expectBlocked { blocks.requireOrderBuyerAllowed(order, "203.0.113.5", pool) }
        blocks.requireOrderBuyerAllowed(order, "198.51.100.5", pool)
        blocks.requireOrderBuyerAllowed(order, null, pool)

        block("EMAIL", "@example.com")
        expectBlocked { blocks.requireOrderBuyerAllowed(order, null, pool) }
    }

    // ================================================================================== chargeback blocks (V-08 twin)

    private fun disputes(r: RefundWorld): DisputeService = DisputeService(
        w.db, r.d.locks, w.clock, { w.config }, w.orders, w.orderItems, w.orderEvents, w.payments, w.disputes, w.blocks, w.deliveries, w.entitlements, w.creditTxs, r.d.credits,
        r.d.service, r.d.entitlementService, r.service, StandardDisputeEffects(r.effects, r.webhooks.service) { _, _, _ -> },
        DisputeAlerts { _, _, _ -> }
    )

    private suspend fun chargeback(disputes: DisputeService, r: RefundWorld, paid: PaidOrder, id: String) {
        val event = PaymentEvent.DisputeUpdated(PaymentTarget.Attempt(paid.attempt.id), DisputeState.OPENED)

        event.gatewayDisputeId = id

        disputes.onDisputeUpdated(event, r.attempt(paid.attempt.id), "evt", null)
    }

    private fun chargebackConfig() = w.configure {
        MarketConfig(
            currency = com.panomc.plugins.market.util.CurrencyType.EUR, vatPercent = 20.0, showVatInPrice = true, creditValue = 1.0, storeTimeZone = "UTC", autoBlockOnChargeback = true
        )
    }

    @Test
    fun `V-08 a guest gift charged back blocks the recipient and the e-mail and never the payer's typed name, and the lookup agrees`(): Unit = runBlocking {
        chargebackConfig()

        val r = RefundWorld(w, vertx)
        val disputes = disputes(r)
        val paid = r.place(null, listOf(RefundLine(1000)), email = "attacker@example.com")

        MarketTestDb.sql(
            pool, "UPDATE `${MarketTestDb.TABLE_PREFIX}market_order` SET `playerUsername` = 'Victim', `recipientUsername` = 'Attacker', `recipientKey` = 'g:attacker', `isGift` = 1 WHERE `id` = ?",
            paid.order.id
        )
        chargeback(disputes, r, paid, "dp_v08")

        assertEquals(setOf("PLAYER:attacker", "EMAIL:attacker@example.com"), w.blocks.getAll(pool).map { "${it.type}:${it.value}" }.toSet())
        assertTrue(w.blocks.getAll(pool).all { it.source == BlockSource.CHARGEBACK && it.orderId == paid.order.id })

        // the next checkout of the victim is untouched, the attacker's recipient name and address are refused
        assertFalse(blocks.blocked("Victim", "Victim", null, null, null, pool), "the typed payer name is nobody")
        assertTrue(blocks.blocked("Mallory", "Attacker", "mallory@example.com", null, null, pool), "the recipient name")
        assertTrue(blocks.blocked("Mallory", "Mallory", "attacker@example.com", null, null, pool), "the e-mail")
        assertFalse(blocks.blocked("Victim", "Victim", "victim@example.com", null, null, pool))

        // O12 (a won dispute): the rows go, the same buyer may buy again
        val won = PaymentEvent.DisputeUpdated(PaymentTarget.Attempt(paid.attempt.id), DisputeState.WON)

        won.gatewayDisputeId = "dp_v08"
        disputes.onDisputeUpdated(won, r.attempt(paid.attempt.id), "evt2", null)
        assertTrue(w.blocks.getAll(pool).isEmpty())
        assertFalse(blocks.blocked("Mallory", "Attacker", "attacker@example.com", null, null, pool))
    }

    @Test
    fun `a charged-back order of a logged-in buyer blocks the recipient, the account and the e-mail, and the checkout refuses each of them`(): Unit = runBlocking {
        chargebackConfig()
        fx.paymentMethod("fake")

        val r = RefundWorld(w, vertx)
        val disputes = disputes(r)
        val steve = fx.user("Steve")
        val paid = r.place(steve, listOf(RefundLine(1000)), email = "steve@example.com")

        chargeback(disputes, r, paid, "dp_user")

        assertEquals(setOf("PLAYER:steve", "USER:${steve.id}", "EMAIL:steve@example.com"), w.blocks.getAll(pool).map { "${it.type}:${it.value}" }.toSet())

        val p = fx.product(price = 1000)

        h.emails[steve.id] = "steve@example.com"
        expectBlocked { checkout(p, QuoteCaller(steve.id), guest = null) }
        expectBlocked { checkout(p, guest = mapOf("username" to "STEVE", "email" to "new@example.com")) }
        expectBlocked { checkout(p, guest = mapOf("username" to "Other", "email" to "Steve@Example.com")) }
        checkout(p, guest = mapOf("username" to "Other", "email" to "other@example.com"))
    }

    @Test
    fun `a chargeback row is a row like any other, the panel removes it and a manual row of the same value stays untouched by a chargeback`(): Unit = runBlocking {
        chargebackConfig()

        val r = RefundWorld(w, vertx)
        val disputes = disputes(r)
        val manual = block("PLAYER", "steve")
        val paid = r.place(fx.user("Steve"), listOf(RefundLine(1000)), email = "steve@example.com")

        chargeback(disputes, r, paid, "dp_manual")

        val all = w.blocks.getAll(pool)

        assertEquals(BlockSource.MANUAL, all.single { it.id == manual.id }.source, "the existing manual row is left as it is")
        assertEquals(2, all.count { it.source == BlockSource.CHARGEBACK })

        val listed = blocks.list(null, BlockSource.CHARGEBACK, null, 1, 10, pool)

        assertEquals(2, listed.total)

        val gone = blocks.delete(listed.rows.first().block.id, pool)

        assertEquals(BlockSource.CHARGEBACK, gone.source)
        assertEquals(2, w.blocks.getAll(pool).size)
    }

    // ================================================================================== panel operations

    private suspend fun invalid(reason: String, block: suspend () -> Any?) {
        val e = assertThrows(InvalidBlock::class.java) { runBlocking { block() } }

        assertEquals("INVALID_BLOCK", e.getErrorCode())
        assertEquals(400, e.getStatusCode())
        assertEquals(reason, JsonObject(e.encode()).getString("reason"))
    }

    @Test
    fun `a duplicate value is 409, an expired duplicate is replaced, the same value in another case or form is the same value`(): Unit = runBlocking {
        val first = block("PLAYER", "Steve")

        assertThrows(BlockAlreadyExists::class.java) { runBlocking { block("PLAYER", "STEVE") } }
        assertThrows(BlockAlreadyExists::class.java) { runBlocking { block("PLAYER", " steve ") } }
        assertEquals(1, w.blocks.getAll(pool).size)

        block("IP", "2001:db8::1")
        assertThrows(BlockAlreadyExists::class.java) { runBlocking { block("IP", "2001:0DB8:0:0:0:0:0:1") } }
        block("EMAIL", "a@b.com")
        assertThrows(BlockAlreadyExists::class.java) { runBlocking { block("EMAIL", "A@B.COM") } }
        block("IP", "10.0.0.0/8")
        assertThrows(BlockAlreadyExists::class.java) { runBlocking { block("IP", "10.1.2.3/8") } }

        // replaced when the old one expired
        val timed = block("EMAIL", "@gone.org", expiresAt = w.clock.now() + 1_000)

        assertThrows(BlockAlreadyExists::class.java) { runBlocking { block("EMAIL", "@gone.org") } }
        w.clock.advance(1_000)

        val again = block("EMAIL", "@gone.org")

        assertTrue(again.id != timed.id)
        assertNull(row(timed.id))
        assertNull(again.expiresAt)
        assertNotNull(row(first.id))
    }

    @Test
    fun `the value rules, the wide range, SELF, the reason length and the expiry are 400 INVALID_BLOCK with a reason`(): Unit = runBlocking {
        val (carol, _) = user("Carol")

        invalid("TYPE") { blocks.create("NOPE", "x", null, null, admin) }
        invalid("TYPE") { blocks.create(null, "x", null, null, admin) }
        invalid("VALUE") { blocks.create("PLAYER", "bad name!", null, null, admin) }
        invalid("VALUE") { blocks.create("PLAYER", null, null, null, admin) }
        invalid("VALUE") { blocks.create("EMAIL", "not-an-address", null, null, admin) }
        invalid("VALUE") { blocks.create("EMAIL", "@nodot", null, null, admin) }
        invalid("VALUE") { blocks.create("IP", "999.1.1.1", null, null, admin) }
        invalid("VALUE") { blocks.create("USER", "abc", null, null, admin) }
        invalid("VALUE") { blocks.create("USER", "999999", null, null, admin) }
        invalid("RANGE_TOO_WIDE") { blocks.create("IP", "10.0.0.0/7", null, null, admin) }
        invalid("RANGE_TOO_WIDE") { blocks.create("IP", "2001:db8::/15", null, null, admin) }
        invalid("REASON") { blocks.create("PLAYER", "x", "r".repeat(256), null, admin) }
        invalid("EXPIRES") { blocks.create("PLAYER", "x", null, w.clock.now(), admin) }
        invalid("EXPIRES") { blocks.create("PLAYER", "x", null, w.clock.now() - 1, admin) }
        invalid("SELF") { blocks.create("USER", carol.id.toString(), null, null, BlockListService.Actor(carol.id, carol.username)) }
        invalid("SELF") { blocks.create("PLAYER", "CAROL", null, null, BlockListService.Actor(carol.id, carol.username)) }
        assertTrue(w.blocks.getAll(pool).isEmpty(), "nothing was written")

        // the limits themselves are accepted, a 255 character reason too
        blocks.create("IP", "10.0.0.0/8", "r".repeat(255), null, admin)
        blocks.create("IP", "2001:db8::/16", null, null, admin)
        blocks.create("USER", carol.id.toString(), null, null, admin)
        assertEquals(3, w.blocks.getAll(pool).size)
    }

    @Test
    fun `list filters by type, source and value prefix, pages newest first and names the creator, delete removes one row and 404s a missing one`(): Unit = runBlocking {
        val adminUser = fx.user("Admina")
        val actor = BlockListService.Actor(adminUser.id, adminUser.username)

        block("PLAYER", "alpha", actor = actor)
        block("PLAYER", "alpine", actor = actor)
        block("EMAIL", "alp@x.com", actor = actor)
        val ip = block("IP", "203.0.113.5", actor = actor)

        val all = blocks.list(null, null, null, 1, 10, pool)

        assertEquals(4, all.total)
        assertEquals(listOf(ip.id), all.rows.take(1).map { it.block.id }, "newest first")
        assertEquals(setOf("Admina"), all.rows.map { it.createdByUsername }.toSet())
        assertEquals(2, blocks.list(BlockType.PLAYER, null, null, 1, 10, pool).total)
        assertEquals(3, blocks.list(null, null, "AL", 1, 10, pool).total, "prefix, case-insensitive")
        assertEquals(1, blocks.list(null, null, "alph", 1, 10, pool).total)
        assertEquals(0, blocks.list(null, BlockSource.CHARGEBACK, null, 1, 10, pool).total)
        assertEquals(4, blocks.list(null, BlockSource.MANUAL, null, 1, 10, pool).total)

        val page2 = blocks.list(null, null, null, 2, 3, pool)

        assertEquals(4, page2.total)
        assertEquals(1, page2.rows.size)

        val removed = blocks.delete(ip.id, pool)

        assertEquals("203.0.113.5", removed.value)
        assertNull(row(ip.id))
        assertThrows(NotFound::class.java) { runBlocking { blocks.delete(ip.id, pool) } }
        assertThrows(NotFound::class.java) { runBlocking { blocks.delete(987654, pool) } }

        // the range is gone from memory at once
        assertNull(blocks.check(BlockSubjects(ip = "203.0.113.5"), pool))
    }

    @Test
    fun `the masked value of an activity log hides an e-mail and an address but not a domain, a name or an id`() {
        assertEquals("j***@e***.com", BlockListService.maskedValue(BlockType.EMAIL, "john@example.com"))
        assertEquals("@example.com", BlockListService.maskedValue(BlockType.EMAIL, "@example.com"))
        assertEquals("203.0.113.x", BlockListService.maskedValue(BlockType.IP, "203.0.113.57"))
        assertEquals("203.0.113.x/24", BlockListService.maskedValue(BlockType.IP, "203.0.113.0/24"))
        assertEquals("steve", BlockListService.maskedValue(BlockType.PLAYER, "steve"))
        assertEquals("12", BlockListService.maskedValue(BlockType.USER, "12"))
    }

    @Test
    fun `a hit that cannot be counted never fails the check`(): Unit = runBlocking {
        val id = block("PLAYER", "steve").id
        val failing = object : com.panomc.plugins.market.db.dao.MarketBlockDao() {
            override suspend fun add(block: MarketBlock, sqlClient: SqlClient) = w.blocks.add(block, sqlClient)

            override suspend fun getById(id: Long, sqlClient: SqlClient) = w.blocks.getById(id, sqlClient)

            override suspend fun getByTypeAndValue(type: BlockType, value: String, sqlClient: SqlClient) = w.blocks.getByTypeAndValue(type, value, sqlClient)

            override suspend fun getActiveByType(type: BlockType, now: Long, sqlClient: SqlClient) = w.blocks.getActiveByType(type, now, sqlClient)

            override suspend fun getAll(sqlClient: SqlClient) = w.blocks.getAll(sqlClient)

            override suspend fun recordHit(id: Long, now: Long, sqlClient: SqlClient): Boolean = throw IllegalStateException("counter is down")

            override suspend fun delete(id: Long, sqlClient: SqlClient) = w.blocks.delete(id, sqlClient)

            override suspend fun deleteExpired(now: Long, sqlClient: SqlClient) = w.blocks.deleteExpired(now, sqlClient)

            override suspend fun init(sqlClient: SqlClient) = Unit

            override suspend fun uninstall(sqlClient: SqlClient) = Unit
        }
        val service = BlockListService(w.clock, failing, object : UserDirectory {
            override suspend fun byUsername(username: String, sqlClient: SqlClient): DirectoryUser? = null

            override suspend fun usernameOf(userId: Long, sqlClient: SqlClient): String? = null

            override suspend fun emailOf(userId: Long, sqlClient: SqlClient): String? = null

            override suspend fun hasPermission(userId: Long, node: String): Boolean = false
        })

        assertEquals(id, service.check(BlockSubjects(usernames = setOf("steve")), pool)!!.blockId)
        assertEquals(0, row(id)!!.hitCount)
    }
}
