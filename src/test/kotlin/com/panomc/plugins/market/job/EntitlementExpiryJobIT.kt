package com.panomc.plugins.market.job

import com.panomc.plugins.market.config.MarketConfig
import com.panomc.plugins.market.core.delivery.ProductAction
import com.panomc.plugins.market.db.MarketDaoITBase
import com.panomc.plugins.market.db.model.DeliveryActionType
import com.panomc.plugins.market.db.model.DeliveryPhase
import com.panomc.plugins.market.db.model.EntitlementStatus
import com.panomc.plugins.market.db.model.MailKind
import com.panomc.plugins.market.db.model.MailRefType
import com.panomc.plugins.market.db.model.MarketEntitlement
import com.panomc.plugins.market.db.model.MarketProduct
import com.panomc.plugins.market.service.DeliveryWorld
import com.panomc.plugins.market.service.MailOutboxService
import com.panomc.plugins.market.service.Placed
import com.panomc.plugins.market.service.platform.DirectoryUser
import com.panomc.plugins.market.service.platform.UserDirectory
import com.panomc.plugins.market.support.MarketTestDb
import com.panomc.plugins.market.support.Race
import com.panomc.plugins.market.support.TestUser
import com.panomc.plugins.market.support.TestWiring
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.SqlClient
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * `EntitlementExpiryJob` on a real MariaDB (MK-107; 08 section 10.3, 12 section 4.1 `EXPIRY_REMINDER`; tests D-05, R-?): due entitlements are expired in
 * batches and oldest first, a subscription's entitlement is never touched, two workers at once end a row once, the `EXPIRE` rows appear at expiry, the
 * scheduler runs the job on its tick, and the reminder mail is queued once for the last link of a chain only. The invariants I1 to I22 are checked after
 * every test by the base class.
 */
class EntitlementExpiryJobIT : MarketDaoITBase() {
    private lateinit var w: TestWiring
    private lateinit var d: DeliveryWorld
    private val emails = HashMap<Long, String>()
    private val day = 86_400_000L

    private val directory = object : UserDirectory {
        override suspend fun byUsername(username: String, sqlClient: SqlClient): DirectoryUser? = w.users.idOf(username)?.let { DirectoryUser(it, w.users.nameOf(it)!!) }

        override suspend fun usernameOf(userId: Long, sqlClient: SqlClient): String? = w.users.nameOf(userId)

        override suspend fun emailOf(userId: Long, sqlClient: SqlClient): String? = emails[userId]

        override suspend fun hasPermission(userId: Long, node: String): Boolean = false
    }

    @BeforeEach
    fun wire() {
        w = TestWiring(pool)
        d = DeliveryWorld(w)
        emails.clear()
    }

    private fun job(batch: Int = EntitlementExpiryJob.BATCH, reminderDays: Int = 3, mail: Boolean = true) = EntitlementExpiryJob(
        clock = w.clock, db = w.db, locks = d.locks, service = d.entitlementService, delivery = d.service, entitlements = w.entitlements,
        config = { MarketConfig(currency = "EUR", storeTimeZone = "UTC", subscriptionReminderDays = reminderDays) },
        mail = if (mail) MailOutboxService({ w.config }, w.clock, w.mailOutbox, w.orderEvents) else null, users = directory, batch = batch
    )

    private fun permission(id: String, vararg nodes: String) = ProductAction(id = id, type = DeliveryActionType.PERMISSION, nodes = nodes.toList())

    private suspend fun owner(name: String = "Steve", email: String? = "$name@example.com"): TestUser = w.fixtures.user(name).also { u -> email?.let { emails[u.id] = it } }

    private suspend fun timed(user: TestUser, product: MarketProduct? = null, days: Int = 30, node: String = "group.vip"): Placed =
        d.place(user = user, actions = listOf(permission("a1", node)), billing = "TIMED", periodUnit = "DAY", periodCount = days, product = product)
            .also { d.pay(it) }

    private suspend fun entitlement(placed: Placed): MarketEntitlement = w.entitlements.getByOrderItemId(placed.items[0].id, pool).single()

    private suspend fun expireRows(placed: Placed) = d.rows(placed.order.id).filter { it.phase == DeliveryPhase.EXPIRE }

    private suspend fun reminders() = w.mailOutbox.getDue(com.panomc.plugins.market.db.model.MailStatus.PENDING, w.clock.now() + day, 100, pool).filter { it.kind == MailKind.EXPIRY_REMINDER }

    // ===== expiry ================================================================================================================

    @Test
    fun `only due entitlements are expired and their EXPIRE rows are created at expiry (D-05)`(): Unit = runBlocking {
        val a = timed(owner("Alice"), days = 10, node = "group.a")
        val b = timed(owner("Bob"), days = 40, node = "group.b")

        d.runInline()
        assertEquals(0, job().runOnce(), "nothing is due at purchase time")
        assertTrue(expireRows(a).isEmpty())

        w.clock.advance(11 * day)

        assertEquals(1, job(reminderDays = 0).runOnce())
        assertEquals(EntitlementStatus.EXPIRED, entitlement(a).status)
        assertEquals(EntitlementStatus.ACTIVE, entitlement(b).status)
        assertEquals(1, expireRows(a).size)
        assertTrue(expireRows(b).isEmpty())

        // a second tick finds nothing and plans nothing more
        assertEquals(0, job(reminderDays = 0).runOnce())
        assertEquals(1, expireRows(a).size)

        d.runInline()
        assertEquals(setOf("group.b"), d.permissionStore.of(w.users.idOf("Bob")!!).map { it.node }.toSet())
        assertEquals(0, d.permissionStore.of(w.users.idOf("Alice")!!).size)
    }

    @Test
    fun `the batch bounds one tick and the oldest expiry goes first`(): Unit = runBlocking {
        val placed = listOf(5, 3, 4).mapIndexed { i, days -> timed(owner("Batch$i"), days = days, node = "group.b$i") }

        w.clock.advance(6 * day)

        assertEquals(2, job(batch = 2, reminderDays = 0).runOnce())
        assertEquals(EntitlementStatus.EXPIRED, entitlement(placed[1]).status, "3 days")
        assertEquals(EntitlementStatus.EXPIRED, entitlement(placed[2]).status, "4 days")
        assertEquals(EntitlementStatus.ACTIVE, entitlement(placed[0]).status, "5 days waits for the next tick")

        assertEquals(1, job(batch = 2, reminderDays = 0).runOnce())
        assertEquals(EntitlementStatus.EXPIRED, entitlement(placed[0]).status)
    }

    @Test
    fun `a subscription's entitlement is never expired by the job`(): Unit = runBlocking {
        val u = owner()
        val placed = timed(u)
        val e = entitlement(placed)

        MarketTestDb.sql(pool, "UPDATE `${MarketTestDb.TABLE_PREFIX}market_entitlement` SET `subscriptionId` = 5 WHERE `id` = ?", e.id)
        w.clock.advance(31 * day)

        assertEquals(0, job(reminderDays = 0).runOnce())
        assertEquals(EntitlementStatus.ACTIVE, entitlement(placed).status)
        assertTrue(expireRows(placed).isEmpty())
    }

    @Test
    fun `two workers at the same moment end an entitlement once`(): Unit = runBlocking {
        repeat(Race.rounds) { round ->
            val placed = List(4) { i -> timed(owner("Racer${round}x$i"), days = 5, node = "group.r$i") }

            d.runInline()
            w.clock.advance(6 * day)

            val moved = Race.run(2) { job(reminderDays = 0).runOnce() }.map { it.getOrThrow() }

            assertEquals(4, moved.sum(), "round $round: every row is counted by exactly one worker")

            for (p in placed) {
                assertEquals(EntitlementStatus.EXPIRED, entitlement(p).status)
                assertEquals(1, expireRows(p).size, "one EXPIRE row set")
            }
        }
    }

    @Test
    fun `the job ends a chain link by link and only the last one plans EXPIRE`(): Unit = runBlocking {
        val u = owner()
        val first = timed(u)

        w.clock.advance(20 * day)

        val second = timed(u, first.product)

        d.runInline()
        w.clock.advance(11 * day)

        assertEquals(1, job(reminderDays = 0).runOnce())
        assertEquals(EntitlementStatus.EXPIRED, entitlement(first).status)
        assertTrue(expireRows(first).isEmpty(), "the chain goes on")
        assertEquals(1, d.permissionStore.of(u.id).size)

        w.clock.advance(30 * day)

        assertEquals(1, job(reminderDays = 0).runOnce())
        assertEquals(1, expireRows(second).size)

        d.runInline()
        assertEquals(0, d.permissionStore.of(u.id).size)
    }

    @Test
    fun `the scheduler runs the job on its tick`(): Unit = runBlocking {
        val placed = timed(owner(), days = 1)

        w.clock.advance(2 * day)

        val scheduler = MarketScheduler(w.clock, listOf(MarketJobs.entitlementExpiry(job(reminderDays = 0))))

        assertEquals(1, scheduler.tick())
        assertEquals(EntitlementStatus.EXPIRED, entitlement(placed).status)
        assertEquals("entitlement-expiry", scheduler.stats().single().name)
        assertEquals(MarketScheduler.ENTITLEMENT_EXPIRY_MS, 30_000L)
    }

    // ===== reminder ===============================================================================================================

    @Test
    fun `the reminder is queued once, inside the lead time, with the end as its key`(): Unit = runBlocking {
        val u = owner()
        val placed = timed(u)
        val end = entitlement(placed).expiresAt!!

        w.clock.advance(26 * day)
        assertEquals(0, job().runOnce(), "4 days left: outside the 3 day lead")
        assertTrue(reminders().isEmpty())

        w.clock.advance(1 * day)
        assertEquals(1, job().runOnce(), "3 days left")

        val mail = reminders().single()

        assertEquals(MailRefType.ENTITLEMENT, mail.refType)
        assertEquals(entitlement(placed).id, mail.refId)
        assertEquals(end.toString(), mail.refKey)
        assertEquals(u.id, mail.userId)
        assertEquals("Steve@example.com".lowercase(), mail.recipient)
        assertEquals(end, JsonObject(mail.params).getLong("expiresAt"))
        assertNotNull(entitlement(placed).reminderSentAt)

        assertEquals(0, job().runOnce(), "once per entitlement")
        assertEquals(1, reminders().size)
    }

    @Test
    fun `only the last link of a chain is reminded`(): Unit = runBlocking {
        val u = owner()
        val first = timed(u)

        w.clock.advance(20 * day)

        val second = timed(u, first.product)

        w.clock.advance(8 * day)

        // the first link ends in 2 days, but the chain runs on for 32 more: no reminder for it
        assertEquals(0, job().runOnce())
        assertTrue(reminders().isEmpty())

        w.clock.advance(30 * day)

        assertEquals(2, job().runOnce(), "the first link is expired and the second, the last one, is reminded")
        assertEquals(entitlement(second).id, reminders().single().refId)
    }

    @Test
    fun `no reminder for a period shorter than twice the lead`(): Unit = runBlocking {
        val short = timed(owner("Shorty"), days = 5, node = "group.s")

        w.clock.advance(3 * day)

        assertEquals(0, job().runOnce(), "2 days left, but a 5 day product is shorter than 2 x 3 days")
        assertTrue(reminders().isEmpty())
        assertNull(entitlement(short).reminderSentAt)
    }

    @Test
    fun `no reminder with the lead at zero or without a mail outbox, and none of them marks the row`(): Unit = runBlocking {
        val placed = timed(owner())

        w.clock.advance(28 * day)

        assertEquals(0, job(reminderDays = 0).runOnce(), "lead 0 switches the reminder off")
        assertEquals(0, job(mail = false).runOnce(), "no outbox: the step does nothing")
        assertNull(entitlement(placed).reminderSentAt)
        assertTrue(reminders().isEmpty())

        assertEquals(1, job().runOnce(), "a job that can send it still does")
        assertEquals(1, reminders().size)
    }

    @Test
    fun `an owner without an e-mail address is marked and gets no mail`(): Unit = runBlocking {
        val placed = timed(owner("Nomail", email = null))

        w.clock.advance(28 * day)

        assertEquals(0, job().runOnce())
        assertNotNull(entitlement(placed).reminderSentAt, "marked: the row is not looked at again")
        assertTrue(reminders().isEmpty())
        assertEquals(0, job().runOnce())
    }
}
