package com.panomc.plugins.market.routes.panel.settings

import com.panomc.plugins.market.db.MarketDaoITBase
import com.panomc.plugins.market.job.MarketScheduler
import com.panomc.plugins.market.provider.ProviderAvailability
import com.panomc.plugins.market.provider.ProviderKind
import com.panomc.plugins.market.provider.ProviderListing
import com.panomc.plugins.market.provider.ProviderState
import com.panomc.plugins.market.runtime.MarketRuntime
import com.panomc.plugins.market.service.CreditCheckResult
import com.panomc.plugins.market.service.CreditProblem
import com.panomc.plugins.market.service.ServerReadiness
import com.panomc.plugins.market.service.ServerView
import com.panomc.plugins.market.support.FakeClock
import com.panomc.plugins.market.support.Fixtures
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * `GET /health` of MK-172 on a real MariaDB: the four queue counters the E2E drain waits on, the failed / deferred counters, the locked subjects and the rejected
 * events of the last hour, the job lag, the provider and server rows, the credit check and `?recheck=credits`, and a part that cannot be read answering empty.
 */
class HealthIT : MarketDaoITBase() {
    /** Raw rows of the queues are written on purpose without the owners the invariants of the services expect. */
    override suspend fun assertInvariants() {}

    private val clock = FakeClock()
    private var stats: List<MarketScheduler.JobStats> = emptyList()
    private var listings: List<ProviderListing> = emptyList()
    private var servers: List<ServerView> = emptyList()
    private var last: CreditCheckResult? = null
    private var recheckResult: CreditCheckResult? = null
    private var recheckCalls = 0
    private var failServers = false

    @BeforeEach
    fun fresh() {
        runBlocking { resetState() }

        stats = emptyList()
        listings = emptyList()
        servers = emptyList()
        last = null
        recheckResult = null
        recheckCalls = 0
        failServers = false
    }

    private fun reader() = MarketHealthReader(
        clock = clock, prefix = { prefix }, client = { pool }, jobStats = { stats }, providerListings = { listings },
        serverViews = { if (failServers) throw IllegalStateException("boom") else servers },
        lastCredits = { last }, recheckCredits = { recheckCalls++; recheckResult }
    )

    private suspend fun delivery(status: String, n: Int = 1) = repeat(n) { Fixtures.insertRaw(pool, "market_delivery", mapOf("status" to status, "idempotencyKey" to "k-$status-${System.nanoTime()}-$it")) }

    private suspend fun mail(status: String) = Fixtures.insertRaw(pool, "market_mail_outbox", mapOf("status" to status, "kind" to "ORDER_RECEIVED", "refType" to "ORDER", "refId" to System.nanoTime()))

    private suspend fun webhook(status: String) = Fixtures.insertRaw(pool, "webhook_delivery", mapOf("source" to "market", "status" to status, "eventId" to "e-$status-${System.nanoTime()}"))

    private suspend fun event(status: String, createdAt: Long = clock.now()) =
        Fixtures.insertRaw(pool, "market_payment_event", mapOf("providerId" to "fake", "direction" to "IN", "eventKey" to "ev-$status-${System.nanoTime()}", "status" to status, "createdAt" to createdAt))

    @Test
    fun `an empty store reports zero counters, a stable shape and a clean credit check`() = runBlocking {
        val extras = reader().read(null)

        assertEquals(HealthExtras.ZERO_QUEUES, extras.queues)
        assertEquals(listOf("deliveriesPending", "deliveriesFailed", "mailsPending", "webhooksPending", "deferredEvents", "failedEvents"), extras.queues.keys.toList())
        assertEquals(0, extras.lockedSubjects)
        assertEquals(0, extras.rejectedEventsLastHour)
        assertEquals(true, extras.credits["ok"])
        assertNull(extras.credits["checkedAt"])
        assertEquals(emptyList<Any>(), extras.jobs)
    }

    @Test
    fun `the four drain counters count what still has work, the others what needs a human`() = runBlocking {
        for (s in listOf("PENDING", "SCHEDULED", "WAITING_SERVER", "WAITING_PLAYER", "SENDING", "SENT", "QUEUED")) delivery(s)
        for (s in listOf("CONFIRMED", "CANCELLED")) delivery(s, 2)
        delivery("FAILED", 3)
        for (s in listOf("PENDING", "SENDING", "SENT", "SKIPPED")) mail(s)
        mail("FAILED")
        for (s in listOf("PENDING", "SENDING", "FAILED", "SUCCEEDED", "DEAD")) webhook(s)
        for (s in listOf("DEFERRED", "DEFERRED", "RECEIVED", "PROCESSED", "DUPLICATE", "SUPERSEDED")) event(s)
        event("FAILED")

        val q = reader().read(null).queues

        assertEquals(7L, q["deliveriesPending"])
        assertEquals(3L, q["deliveriesFailed"])
        assertEquals(2L, q["mailsPending"])
        assertEquals(3L, q["webhooksPending"])
        assertEquals(2L, q["deferredEvents"])
        assertEquals(1L, q["failedEvents"])
    }

    @Test
    fun `the queues drain to zero when the work is done`() = runBlocking {
        delivery("PENDING")
        mail("PENDING")
        webhook("PENDING")
        event("DEFERRED")

        val busy = reader().read(null).queues

        assertEquals(listOf(1L, 1L, 1L, 1L), listOf(busy["deliveriesPending"], busy["mailsPending"], busy["webhooksPending"], busy["deferredEvents"]))

        sql("UPDATE `${prefix}market_delivery` SET `status` = 'CONFIRMED'")
        sql("UPDATE `${prefix}market_mail_outbox` SET `status` = 'SENT'")
        sql("UPDATE `${prefix}webhook_delivery` SET `status` = 'SUCCEEDED'")
        sql("UPDATE `${prefix}market_payment_event` SET `status` = 'PROCESSED'")

        val drained = reader().read(null).queues

        assertEquals(listOf(0L, 0L, 0L, 0L), listOf(drained["deliveriesPending"], drained["mailsPending"], drained["webhooksPending"], drained["deferredEvents"]))
    }

    @Test
    fun `locked subjects count only locks that are still in force and rejected events only the last hour`() = runBlocking {
        val now = clock.now()

        Fixtures.insertRaw(pool, "market_throttle", mapOf("scope" to "REVEAL", "subject" to "a", "lockedUntil" to now + 60_000))
        Fixtures.insertRaw(pool, "market_throttle", mapOf("scope" to "REVEAL", "subject" to "b", "lockedUntil" to now - 1))
        Fixtures.insertRaw(pool, "market_throttle", mapOf("scope" to "COUPON", "subject" to "c", "lockedUntil" to null))
        Fixtures.insertRaw(pool, "market_throttle", mapOf("scope" to "GIFT", "subject" to "d", "lockedUntil" to now + 1))

        event("REJECTED", now - 1_000)
        event("REJECTED", now - 3_599_000)
        event("REJECTED", now - 3_601_000)
        event("PROCESSED", now)

        val extras = reader().read(null)

        assertEquals(2, extras.lockedSubjects)
        assertEquals(2, extras.rejectedEventsLastHour)
    }

    @Test
    fun `jobs report the last start, the lag past the slot and the last error`() = runBlocking {
        val now = clock.now()

        stats = listOf(
            MarketScheduler.JobStats("delivery", 10, 0, 3, null, now - 2_000, 5_000),
            MarketScheduler.JobStats("mail-outbox", 4, 1, 0, "IllegalStateException: boom", now - 95_000, 15_000),
            MarketScheduler.JobStats("housekeeping", 0, 0, 0, null, null, 60_000)
        )

        val jobs = reader().read(null).jobs

        assertEquals(listOf("delivery", "mail-outbox", "housekeeping"), jobs.map { it["name"] })
        assertEquals(now - 2_000, jobs[0]["lastRunAt"])
        assertEquals(0L, jobs[0]["lagSeconds"])
        assertNull(jobs[0]["lastError"])
        assertEquals(80L, jobs[1]["lagSeconds"])
        assertEquals("IllegalStateException: boom", jobs[1]["lastError"])
        assertNull(jobs[2]["lastRunAt"])
        assertNull(jobs[2]["lagSeconds"])
    }

    @Test
    fun `providers map the registry states and servers carry the readiness`() = runBlocking {
        listings = listOf(
            ProviderListing(ProviderKind.PAYMENT, "stripe", ProviderState(ProviderAvailability.AVAILABLE), null),
            ProviderListing(ProviderKind.PAYMENT, "old", ProviderState(ProviderAvailability.INCOMPATIBLE, spiVersion = 0), null),
            ProviderListing(ProviderKind.SHIPPING, "manual", ProviderState(ProviderAvailability.MISSING), null),
            ProviderListing(ProviderKind.PAYMENT, null, ProviderState(ProviderAvailability.INVALID), null)
        )
        servers = listOf(ServerView(3, "Lobby", "SPIGOT", true, false, "1.0.0", "1.0.0", ServerReadiness.COMPONENT_MISSING, 4, 0, "/dl", null, emptyList(), null))

        val extras = reader().read(null)

        assertEquals(
            listOf(mapOf("id" to "manual", "state" to "UNAVAILABLE"), mapOf("id" to "old", "state" to "INCOMPATIBLE"), mapOf("id" to "stripe", "state" to "AVAILABLE")),
            extras.providers
        )
        assertEquals(listOf(mapOf("id" to 3L, "marketState" to "COMPONENT_MISSING", "waitingDeliveries" to 4L)), extras.servers)
    }

    @Test
    fun `the credit check is the last one, recheck runs it now`() = runBlocking {
        last = CreditCheckResult(true, 111, emptyList())
        recheckResult = CreditCheckResult(false, 222, listOf(CreditProblem("L5", "account", 7, "10", "9")))

        val normal = reader().read(null).credits

        assertEquals(mapOf("ok" to true, "checkedAt" to 111L, "problems" to emptyList<String>()), normal)
        assertEquals(0, recheckCalls)

        val rechecked = reader().read("credits").credits

        assertEquals(1, recheckCalls)
        assertEquals(false, rechecked["ok"])
        assertEquals(222L, rechecked["checkedAt"])
        assertEquals(1, (rechecked["problems"] as List<*>).size)
        assertTrue((rechecked["problems"] as List<*>).single().toString().contains("L5"))

        // any other value of recheck is ignored
        assertEquals(1, run { reader().read("everything"); recheckCalls })
    }

    @Test
    fun `a part that cannot be read answers empty and the others still answer`() = runBlocking {
        failServers = true
        delivery("FAILED")
        stats = listOf(MarketScheduler.JobStats("delivery", 1, 0, 0, null, clock.now(), 5_000))

        val extras = reader().read(null)

        assertEquals(emptyList<Any>(), extras.servers)
        assertEquals(1L, extras.queues["deliveriesFailed"])
        assertEquals(1, extras.jobs.size)
    }

    @Test
    fun `the body carries every documented key with the extras in place`() {
        val body = marketHealthBody(
            MarketRuntime.Health(MarketRuntime.State.READY, emptyList(), emptyMap(), emptyList(), MarketRuntime.HostCapabilities(mail = true, notifications = true)), "OK", emptyList(),
            HealthExtras(queues = mapOf("deliveriesPending" to 2L), lockedSubjects = 5, rejectedEventsLastHour = 6)
        )

        assertEquals(mapOf("deliveriesPending" to 2L), body["queues"])
        assertEquals(5L, body["lockedSubjects"])
        assertEquals(6L, body["rejectedEventsLastHour"])
        assertEquals("OK", body["mail"])
    }
}
