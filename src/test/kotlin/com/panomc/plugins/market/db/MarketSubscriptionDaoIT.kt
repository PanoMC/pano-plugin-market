package com.panomc.plugins.market.db

import com.panomc.plugins.market.db.impl.MarketSubscriptionDaoImpl
import com.panomc.plugins.market.db.impl.MarketSubscriptionRenewalDaoImpl
import com.panomc.plugins.market.db.model.*
import com.panomc.plugins.market.support.Race
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** `market_subscription` and `market_subscription_renewal` (01 section 10). */
class MarketSubscriptionDaoIT : MarketDaoITBase() {
    private val subs = MarketSubscriptionDaoImpl()
    private val renewals = MarketSubscriptionRenewalDaoImpl()

    private fun sub(gatewayId: String? = "sub_1", provider: String = "stripe", owner: String = "u:7", userId: Long? = 7) = MarketSubscription(
        userId = userId, playerUsername = "Steve", ownerKey = owner, email = "a@b.c", productId = 5, variantId = 6, productName = "VIP",
        initialOrderId = 100, initialOrderItemId = 101, entitlementId = 102, providerId = provider, mode = SubscriptionMode.MERCHANT,
        status = SubscriptionStatus.ACTIVE, intervalUnit = SubscriptionIntervalUnit.WEEK, intervalCount = 2, price = 1999, currency = "EUR",
        maxCycles = 12, cycleCount = 3, currentPeriodStart = 1000, currentPeriodEnd = 2000, nextChargeAt = 1900, nextQueryAt = 1950,
        lastQueriedAt = 1500, remoteCancelState = RemoteCancelState.PENDING, remoteCancelAttempts = 2, graceEndsAt = 2500, cancelAtPeriodEnd = true,
        cancelRequestedAt = 1600, cancelledAt = 1700, endedAt = 1800, endReason = "BUYER_CANCEL", gatewaySubscriptionId = gatewayId,
        gatewayCustomerId = "cus_1", storedMethod = "v1:xyz", storedMethodLabel = "Visa 4242", failCount = 1, lastFailureAt = 1400,
        reminderSentAt = 1300, targetServerId = 9, fieldValues = "{\"a\":1}", providerData = "v1:abc", testMode = true, createdAt = 10, updatedAt = 20
    )

    private fun renewal(subscriptionId: Long, index: Int = 1) = MarketSubscriptionRenewal(
        subscriptionId = subscriptionId, periodIndex = index, periodStart = 1000, periodEnd = 2000, orderId = 11, paymentId = 12,
        status = RenewalStatus.PENDING, amount = 1999, currency = "EUR", attempts = 1, nextAttemptAt = 1500, lastError = "boom", createdAt = 10, updatedAt = 20
    )

    @Test
    fun `a subscription round-trips every column`(): Unit = runBlocking {
        val id = subs.add(sub(), pool)!!
        val r = subs.getById(id, pool)!!
        assertEquals(listOf<Any?>(7L, "Steve", "u:7", "a@b.c", 5L, 6L, "VIP", 100L, 101L, 102L, "stripe"),
            listOf(r.userId, r.playerUsername, r.ownerKey, r.email, r.productId, r.variantId, r.productName, r.initialOrderId, r.initialOrderItemId, r.entitlementId, r.providerId))
        assertEquals(SubscriptionMode.MERCHANT, r.mode)
        assertEquals(SubscriptionStatus.ACTIVE, r.status)
        assertEquals(SubscriptionIntervalUnit.WEEK, r.intervalUnit)
        assertEquals(listOf<Any?>(2, 1999L, "EUR", 12, 3), listOf(r.intervalCount, r.price, r.currency, r.maxCycles, r.cycleCount))
        assertEquals(listOf<Any?>(1000L, 2000L, 1900L, 1950L, 1500L), listOf(r.currentPeriodStart, r.currentPeriodEnd, r.nextChargeAt, r.nextQueryAt, r.lastQueriedAt))
        assertEquals(RemoteCancelState.PENDING, r.remoteCancelState)
        assertEquals(listOf<Any?>(2, 2500L, 1600L, 1700L, 1800L, "BUYER_CANCEL"), listOf(r.remoteCancelAttempts, r.graceEndsAt, r.cancelRequestedAt, r.cancelledAt, r.endedAt, r.endReason))
        assertTrue(r.cancelAtPeriodEnd)
        assertTrue(r.testMode)
        assertEquals(listOf<Any?>("sub_1", "cus_1", "v1:xyz", "Visa 4242", 1, 1400L, 1300L, 9L, "{\"a\":1}", "v1:abc", 10L, 20L),
            listOf(r.gatewaySubscriptionId, r.gatewayCustomerId, r.storedMethod, r.storedMethodLabel, r.failCount, r.lastFailureAt, r.reminderSentAt, r.targetServerId, r.fieldValues, r.providerData, r.createdAt, r.updatedAt))
    }

    @Test
    fun `a minimal subscription gets the column defaults`(): Unit = runBlocking {
        sql(
            "INSERT INTO `pano_market_subscription` (`playerUsername`, `ownerKey`, `productId`, `productName`, `initialOrderId`, `initialOrderItemId`, `providerId`, `mode`, `intervalUnit`, `intervalCount`, `price`, `currency`, `createdAt`, `updatedAt`) " +
                "VALUES ('Alex', 'p:alex', 1, 'P', 2, 3, 'x', 'MANUAL', 'MONTH', 1, 500, 'USD', 1, 1)"
        )
        val r = subs.getByOwnerKey("p:alex", pool).single()
        assertEquals(SubscriptionStatus.PENDING, r.status)
        assertEquals(0L, r.variantId)
        assertEquals(0, r.cycleCount)
        assertEquals(RemoteCancelState.NONE, r.remoteCancelState)
        assertEquals(0, r.remoteCancelAttempts)
        assertFalse(r.cancelAtPeriodEnd)
        assertFalse(r.testMode)
        assertEquals(0, r.failCount)
        assertNull(r.userId)
        assertNull(r.gatewaySubscriptionId)
    }

    @Test
    fun `provider and gateway subscription id are unique, a missing gateway id never collides`(): Unit = runBlocking {
        assertNotNull(subs.add(sub("g1"), pool))
        assertNull(subs.add(sub("g1", owner = "u:8", userId = 8), pool), "same provider and gateway id")
        assertNotNull(subs.add(sub("g1", provider = "paypal"), pool), "another provider")
        assertNotNull(subs.add(sub(null), pool))
        assertNotNull(subs.add(sub(null), pool))
        assertEquals(4L, count("market_subscription"))
        val failure = runCatching {
            sql("INSERT INTO `pano_market_subscription` (`playerUsername`, `ownerKey`, `productId`, `productName`, `initialOrderId`, `initialOrderItemId`, `providerId`, `mode`, `intervalUnit`, `intervalCount`, `price`, `currency`, `gatewaySubscriptionId`, `createdAt`, `updatedAt`) VALUES ('x', 'k', 1, 'P', 1, 1, 'stripe', 'GATEWAY', 'DAY', 1, 1, 'EUR', 'g1', 1, 1)")
        }.exceptionOrNull()
        assertNotNull(failure)
        assertEquals(4L, count("market_subscription"))
        assertEquals(sub("g1").ownerKey, subs.getByGatewaySubscription("stripe", "g1", pool)!!.ownerKey)
        assertNull(subs.getByGatewaySubscription("stripe", "nope", pool))
    }

    @Test
    fun `lookups by owner and user and the due lists`(): Unit = runBlocking {
        val a = subs.add(sub("a", owner = "u:1", userId = 1), pool)!!
        val b = subs.add(sub("b", owner = "u:1", userId = 1), pool)!!
        subs.add(sub("c", owner = "u:2", userId = 2), pool)!!
        assertEquals(listOf(a, b), subs.getByOwnerKey("u:1", pool).map { it.id })
        assertEquals(listOf(a, b), subs.getByUserId(1, pool).map { it.id })
        assertEquals(listOf(a, b, a + 2).sorted(), subs.getDueForCharge(SubscriptionStatus.ACTIVE, 1900, 10, pool).map { it.id })
        assertTrue(subs.getDueForCharge(SubscriptionStatus.ACTIVE, 1899, 10, pool).isEmpty())
        assertTrue(subs.getDueForCharge(SubscriptionStatus.PAST_DUE, 5000, 10, pool).isEmpty())
        assertEquals(1, subs.getDueForCharge(SubscriptionStatus.ACTIVE, 5000, 1, pool).size)
        assertEquals(3, subs.getPeriodEnded(SubscriptionStatus.ACTIVE, 2000, 10, pool).size)
        assertTrue(subs.getPeriodEnded(SubscriptionStatus.ACTIVE, 1999, 10, pool).isEmpty())
        assertEquals(3, subs.getDueForQuery(1950, 10, pool).size)
        assertTrue(subs.getDueForQuery(1949, 10, pool).isEmpty())
    }

    @Test
    fun `status transition is compare-and-set and the paid period is booked once`(): Unit = runBlocking {
        val id = subs.add(sub("t"), pool)!!
        assertFalse(subs.transition(id, SubscriptionStatus.PENDING, SubscriptionStatus.PAST_DUE, 50, pool))
        assertTrue(subs.transition(id, SubscriptionStatus.ACTIVE, SubscriptionStatus.PAST_DUE, 50, pool))
        assertEquals(SubscriptionStatus.PAST_DUE, subs.getById(id, pool)!!.status)
        assertEquals(50L, subs.getById(id, pool)!!.updatedAt)

        // cycleCount is 3: two writers book period 4, one wins
        val results = Race.run(2) { subs.recordPaidPeriod(id, 3, 2000, 3000, 2900, 60, pool) }
        assertEquals(1, results.count { it.getOrThrow() })
        val row = subs.getById(id, pool)!!
        assertEquals(listOf<Any?>(4, 2000L, 3000L, 2900L), listOf(row.cycleCount, row.currentPeriodStart, row.currentPeriodEnd, row.nextChargeAt))
        assertFalse(subs.recordPaidPeriod(id, 3, 3000, 4000, null, 70, pool))
        assertTrue(subs.recordPaidPeriod(id, 4, 3000, 4000, null, 70, pool))
        assertNull(subs.getById(id, pool)!!.nextChargeAt)
    }

    @Test
    fun `cancel flag, remote cancel queue and the full update`(): Unit = runBlocking {
        val id = subs.add(sub("u"), pool)!!
        assertTrue(subs.setCancelAtPeriodEnd(id, false, 5, pool))
        assertFalse(subs.getById(id, pool)!!.cancelAtPeriodEnd)
        assertNull(subs.getById(id, pool)!!.cancelRequestedAt)
        assertTrue(subs.setCancelAtPeriodEnd(id, true, 6, pool))
        assertTrue(subs.getById(id, pool)!!.cancelAtPeriodEnd)
        assertEquals(6L, subs.getById(id, pool)!!.cancelRequestedAt)

        assertFalse(subs.updateRemoteCancel(id, RemoteCancelState.NONE, RemoteCancelState.DONE, 3, null, 7, pool))
        assertTrue(subs.updateRemoteCancel(id, RemoteCancelState.PENDING, RemoteCancelState.FAILED, 3, 8000, 7, pool))
        val row = subs.getById(id, pool)!!
        assertEquals(listOf<Any?>(RemoteCancelState.FAILED, 3, 8000L), listOf(row.remoteCancelState, row.remoteCancelAttempts, row.nextQueryAt))

        val changed = MarketSubscription(
            id = id, status = SubscriptionStatus.CANCELLED, mode = SubscriptionMode.GATEWAY, intervalUnit = SubscriptionIntervalUnit.YEAR,
            intervalCount = 1, price = 1, currency = "USD", gatewaySubscriptionId = "g-new", endReason = "ADMIN_CANCEL", updatedAt = 99,
            providerId = "ignored", userId = 999, ownerKey = "ignored", productId = 999
        )
        assertTrue(subs.update(changed, pool))
        val after = subs.getById(id, pool)!!
        assertEquals(SubscriptionStatus.CANCELLED, after.status)
        assertEquals("g-new", after.gatewaySubscriptionId)
        assertEquals("ADMIN_CANCEL", after.endReason)
        assertEquals(SubscriptionIntervalUnit.YEAR, after.intervalUnit)
        // immutable origin columns are not written
        assertEquals(listOf<Any?>(7L, "u:7", 5L, "stripe", 100L, 10L), listOf(after.userId, after.ownerKey, after.productId, after.providerId, after.initialOrderId, after.createdAt))
        assertFalse(subs.update(MarketSubscription(id = 12345), pool))
    }

    // --- renewal ---------------------------------------------------------------------------------------------------

    @Test
    fun `a renewal round-trips and a period of a subscription exists only once`(): Unit = runBlocking {
        val sid = subs.add(sub("r"), pool)!!
        val id = renewals.add(renewal(sid, 1), pool)!!
        val r = renewals.getById(id, pool)!!
        assertEquals(listOf<Any?>(sid, 1, 1000L, 2000L, 11L, 12L, RenewalStatus.PENDING, 1999L, "EUR", 1, 1500L, "boom", 10L, 20L),
            listOf(r.subscriptionId, r.periodIndex, r.periodStart, r.periodEnd, r.orderId, r.paymentId, r.status, r.amount, r.currency, r.attempts, r.nextAttemptAt, r.lastError, r.createdAt, r.updatedAt))

        assertNull(renewals.add(renewal(sid, 1).let { MarketSubscriptionRenewal(subscriptionId = sid, periodIndex = 1, amount = 5, currency = "USD") }, pool))
        assertEquals(1999L, renewals.getByPeriod(sid, 1, pool)!!.amount, "first row intact")
        assertNotNull(renewals.add(renewal(sid, 2), pool), "next period")
        val other = subs.add(sub("r2"), pool)!!
        assertNotNull(renewals.add(renewal(other, 1), pool), "same period of another subscription")
        val failure = runCatching {
            sql("INSERT INTO `pano_market_subscription_renewal` (`subscriptionId`, `periodIndex`, `periodStart`, `periodEnd`, `amount`, `currency`, `createdAt`, `updatedAt`) VALUES ($sid, 2, 1, 2, 3, 'EUR', 1, 1)")
        }.exceptionOrNull()
        assertNotNull(failure)
        assertEquals(3L, count("market_subscription_renewal"))
        assertEquals(listOf(1, 2), renewals.getBySubscriptionId(sid, pool).map { it.periodIndex })
    }

    @Test
    fun `two writers preparing the same period create one renewal`(): Unit = runBlocking {
        val sid = subs.add(sub("race"), pool)!!
        val ids = Race.run(4) { renewals.add(renewal(sid, 1), pool) }.map { it.getOrThrow() }
        assertEquals(1, ids.count { it != null })
        assertEquals(1L, count("market_subscription_renewal"))
    }

    @Test
    fun `renewal due list, transition, attach and attempts`(): Unit = runBlocking {
        val sid = subs.add(sub("d"), pool)!!
        val a = renewals.add(renewal(sid, 1), pool)!!
        val b = renewals.add(MarketSubscriptionRenewal(subscriptionId = sid, periodIndex = 2, status = RenewalStatus.PENDING, nextAttemptAt = null), pool)!!
        assertEquals(listOf(a), renewals.getDue(RenewalStatus.PENDING, 1500, 10, pool).map { it.id })
        assertTrue(renewals.getDue(RenewalStatus.PENDING, 1499, 10, pool).isEmpty())
        assertTrue(renewals.getDue(RenewalStatus.FAILED, 9999, 10, pool).isEmpty())

        assertFalse(renewals.transition(a, RenewalStatus.FAILED, RenewalStatus.PAID, 5, pool))
        assertTrue(renewals.transition(a, RenewalStatus.PENDING, RenewalStatus.PAID, 5, pool))
        assertFalse(renewals.transition(a, RenewalStatus.PENDING, RenewalStatus.SKIPPED, 6, pool))
        assertEquals(RenewalStatus.PAID, renewals.getById(a, pool)!!.status)

        assertTrue(renewals.attach(b, 77, null, 7, pool))
        assertTrue(renewals.attach(b, null, 88, 8, pool))
        val row = renewals.getById(b, pool)!!
        assertEquals(listOf<Any?>(77L, 88L), listOf(row.orderId, row.paymentId))
        assertTrue(renewals.recordAttempt(b, 4000, "e1", 9, pool))
        assertTrue(renewals.recordAttempt(b, null, "e2", 10, pool))
        val after = renewals.getById(b, pool)!!
        assertEquals(listOf<Any?>(2, null, "e2"), listOf(after.attempts, after.nextAttemptAt, after.lastError))
    }
}
