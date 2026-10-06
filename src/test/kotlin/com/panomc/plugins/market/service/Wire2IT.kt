package com.panomc.plugins.market.service

import com.panomc.plugins.market.db.model.BlockSource
import com.panomc.plugins.market.db.model.BlockType
import com.panomc.plugins.market.db.model.DeliveryPhase
import com.panomc.plugins.market.db.model.DisputeRecordStatus
import com.panomc.plugins.market.db.model.EntitlementStatus
import com.panomc.plugins.market.db.model.MarketBlock
import com.panomc.plugins.market.db.model.MarketDispute
import com.panomc.plugins.market.db.model.MarketOrder
import com.panomc.plugins.market.db.model.RemoteCancelState
import com.panomc.plugins.market.db.model.SubscriptionStatus
import com.panomc.plugins.market.db.tx.OrderLockScope
import com.panomc.plugins.market.db.tx.txRestartingOnOrderChange
import com.panomc.plugins.market.routes.api.payment.InboundEventContext
import com.panomc.plugins.market.service.platform.DirectoryUser
import com.panomc.plugins.market.service.platform.UserDirectory
import com.panomc.plugins.market.spi.common.Money
import com.panomc.plugins.market.spi.common.ProviderErrorCode
import com.panomc.plugins.market.spi.common.ProviderException
import com.panomc.plugins.market.spi.payment.PaymentEvent
import com.panomc.plugins.market.spi.payment.RecurringSupport
import com.panomc.plugins.market.spi.payment.StoredPaymentMethod
import com.panomc.plugins.market.util.OrderStatus
import io.vertx.sqlclient.SqlClient
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * WIRE-2: the chargeback side of the subscription slices against the real block list and the real subscription service on a real MariaDB.
 *
 * - 11 section 9.3 (open item of MK-151): the subscription of a buyer who is stopped by a block ends with `CHARGEBACK` when the matching row has
 *   `source = CHARGEBACK`, with `ADMIN_CANCEL` for any other block; proven through the real [BlockListService] (not a lambda) for a merchant charge (`ACTIVE` and
 *   `PAST_DUE`) and for the renewal a gateway sends;
 * - 11 section 10 step 4: after a chargeback the buyer's other open subscriptions (same `ownerKey`) end at once as `CHARGEBACK` ([SubscriptionService.onChargebackOwner]).
 *
 * The invariants I1 to I22 are checked after every test by the base class.
 */
internal class Wire2IT : RenewalITBase() {
    private lateinit var blockList: BlockListService

    @BeforeEach
    fun wireBlockList() {
        val directory = object : UserDirectory {
            override suspend fun byUsername(username: String, sqlClient: SqlClient): DirectoryUser? = w.users.idOf(username)?.let { DirectoryUser(it, w.users.nameOf(it)!!) }

            override suspend fun usernameOf(userId: Long, sqlClient: SqlClient): String? = w.users.nameOf(userId)

            override suspend fun emailOf(userId: Long, sqlClient: SqlClient): String? = null

            override suspend fun hasPermission(userId: Long, node: String): Boolean = false
        }

        blockList = BlockListService(w.clock, w.blocks, directory, w.db)
        rw.blocks = blockList.asBuyerBlocks(recordHit = false)
        rw.build()
    }

    private suspend fun block(userId: Long, source: BlockSource, expiresAt: Long? = null) {
        val now = w.clock.now()

        w.blocks.add(
            MarketBlock(type = BlockType.USER, value = userId.toString(), reason = "test", source = source, orderId = null, createdBy = null, expiresAt = expiresAt, createdAt = now, updatedAt = now),
            pool
        )
        blockList.reloadIps()
    }

    private fun expireRows(orderId: Long) = runBlocking { sw.dw.rows(orderId).count { it.phase == DeliveryPhase.EXPIRE } }

    private fun declined() {
        fake.onChargeRecurring = { throw ProviderException(ProviderErrorCode.GATEWAY_REJECTED, "card declined", "402") }
    }

    private suspend fun gateway(name: String = "Alex", gatewayId: String = "sub_gw"): Pair<Active, Long> {
        val product = subProduct()

        fx.paymentMethod("fake")
        sw.caps(RecurringSupport.GATEWAY_MANAGED)

        val (user, caller) = user(name)
        val first = buy(product, caller)
        val end = w.clock.now() + 30 * day

        succeed(first, subscription = gatewayState(gatewayId, periodStart = w.clock.now(), periodEnd = end))

        return Active(user, caller, product, order(first.id), subscription(first.subscriptionId!!)) to end
    }

    /** Another merchant subscription (another product) of the account that [a] belongs to. */
    private suspend fun anotherFor(a: Active): Active {
        val product = subProduct()

        fx.paymentMethod("fake")
        sw.caps(RecurringSupport.MERCHANT_INITIATED)

        val first = buy(product, a.caller)

        succeed(first, stored = StoredPaymentMethod("tok_x${first.id}").also { it.label = "Visa 4242"; it.expiresAt = w.clock.now() + 400 * day; it.gatewayCustomerId = "cus_x${first.id}" })

        return Active(a.user, a.caller, product, order(first.id), subscription(first.subscriptionId!!))
    }

    // ==================================================================================== the source of the block reaches the end reason

    @Test
    fun `the seam names the source of the matching block and a lambda seam counts as manual`(): Unit = runBlocking {
        val (user, _) = user("Alex")
        val seam = blockList.asBuyerBlocks(recordHit = false) as SourcedBuyerBlocks

        assertNull(seam.blockedBy("Alex", "Alex", null, null, user.id, pool))
        assertFalse(seam.blocked("Alex", "Alex", null, null, user.id, pool))

        block(user.id, BlockSource.CHARGEBACK)

        assertEquals(BlockSource.CHARGEBACK, seam.blockedBy("Alex", "Alex", null, null, user.id, pool))
        assertTrue(seam.blocked("Alex", "Alex", null, null, user.id, pool), "blocked() is blockedBy() != null")
        assertTrue(blockList.blocked("Alex", "Alex", null, null, user.id, pool))

        val other = fx.user("Bea")

        block(other.id, BlockSource.MANUAL)

        assertEquals(BlockSource.MANUAL, seam.blockedBy("Bea", "Bea", null, null, other.id, pool))
        assertNull(seam.blockedBy("Cy", "Cy", null, null, 999_999L, pool))
    }

    @Test
    fun `an expired chargeback block stops nobody`(): Unit = runBlocking {
        val (user, _) = user("Alex")
        val seam = blockList.asBuyerBlocks(recordHit = false) as SourcedBuyerBlocks

        block(user.id, BlockSource.CHARGEBACK, expiresAt = w.clock.now() - 1)

        assertNull(seam.blockedBy("Alex", "Alex", null, null, user.id, pool))
    }

    @Test
    fun `a merchant subscription of a chargeback-blocked buyer is not charged and ends at its period end as CHARGEBACK`(): Unit = runBlocking {
        val a = activeMerchant()

        // the charge is due while the paid period still has time left (as in test 42 of the job)
        sql("UPDATE `pano_market_subscription` SET `nextChargeAt` = ? WHERE `id` = ?", w.clock.now(), a.sub.id)
        block(a.user.id, BlockSource.CHARGEBACK)
        chargeSucceeds()
        rw.job.runOnce()

        assertEquals(0, charges().size, "nobody on the block list is charged")

        val scheduled = subscription(a.sub.id)

        assertEquals(SubscriptionStatus.ACTIVE, scheduled.status, "S8: the paid period runs out")
        assertTrue(scheduled.cancelAtPeriodEnd)
        assertEquals("CHARGEBACK", scheduled.endReason)
        assertNull(scheduled.nextChargeAt)

        w.clock.set(scheduled.currentPeriodEnd!! + 1)
        rw.job.runOnce()

        val ended = subscription(a.sub.id)

        assertTrue(ended.status == SubscriptionStatus.CANCELLED || ended.status == SubscriptionStatus.EXPIRED, "ended: ${ended.status}")
        assertEquals("CHARGEBACK", ended.endReason, "the reason survives to the end")
        assertEquals(0, charges().size)
    }

    @Test
    fun `a manual block of the same buyer still ends as ADMIN_CANCEL through the real block list`(): Unit = runBlocking {
        val a = activeMerchant()

        sql("UPDATE `pano_market_subscription` SET `nextChargeAt` = ? WHERE `id` = ?", w.clock.now(), a.sub.id)
        block(a.user.id, BlockSource.MANUAL)
        rw.job.runOnce()

        assertEquals(0, charges().size)
        assertEquals("ADMIN_CANCEL", subscription(a.sub.id).endReason)
    }

    @Test
    fun `a past due merchant subscription of a chargeback-blocked buyer ends at once as CHARGEBACK`(): Unit = runBlocking {
        val a = activeMerchant()

        declined()
        w.clock.set(a.sub.nextChargeAt!!)
        rw.job.runOnce()

        val pastDue = subscription(a.sub.id)

        assertEquals(SubscriptionStatus.PAST_DUE, pastDue.status)

        block(a.user.id, BlockSource.CHARGEBACK)
        w.clock.set(pastDue.nextChargeAt!!)
        rw.job.runOnce()

        val ended = subscription(a.sub.id)

        assertEquals(SubscriptionStatus.CANCELLED, ended.status, "S7: no paid time is left")
        assertEquals("CHARGEBACK", ended.endReason)
        assertNull(ended.storedMethod)
    }

    @Test
    fun `the renewal a gateway sends for a chargeback-blocked buyer is recorded, then the subscription is scheduled to end as CHARGEBACK and the gateway is told`(): Unit = runBlocking {
        val (g, end) = gateway()

        block(g.user.id, BlockSource.CHARGEBACK)
        w.clock.set(end + 60_000)

        val renewed = PaymentEvent.SubscriptionRenewed("sub_gw", Money(g.sub.price, g.sub.currency)).also {
            it.gatewayTransactionId = "txn_cb_blocked"
            it.periodStart = end
            it.periodEnd = end + 30 * day
        }

        rw.sink.apply(renewed, null, InboundEventContext(1, "fake", null, null, w.clock.now()))

        val row = subscription(g.sub.id)

        assertEquals(2, row.cycleCount, "the money arrived: the renewal is recorded")
        assertEquals(SubscriptionStatus.ACTIVE, row.status)
        assertTrue(row.cancelAtPeriodEnd)
        assertEquals("CHARGEBACK", row.endReason)
        assertEquals(RemoteCancelState.PENDING, row.remoteCancelState)
    }

    // ==================================================================================== 11 section 10 step 4: the buyer's other subscriptions

    private suspend fun chargedBack(source: MarketOrder) {
        val stub = MarketOrder(id = source.id, status = OrderStatus.CHARGEBACK, subscriptionId = source.subscriptionId, source = source.source)

        sw.db.txRestartingOnOrderChange { conn ->
            sw.locks.forOrder(conn, source.id, OrderLockScope.RELEASE) { rw.subs.onOrderChargeback(conn, stub, MarketDispute()) }
        }
    }

    @Test
    fun `a chargeback ends the buyer's other open subscriptions as CHARGEBACK with EXPIRE rows, leaves other buyers and closed rows alone, and is repeatable`(): Unit = runBlocking {
        val charged = activeMerchant(name = "Alex")
        val sibling = anotherFor(charged)
        val pastDue = anotherFor(charged)
        val closed = anotherFor(charged)

        // the third one is PAST_DUE (a declined charge); the fourth was cancelled by the buyer before
        declined()
        sql("UPDATE `pano_market_subscription` SET `nextChargeAt` = NULL WHERE `id` IN (?, ?, ?)", charged.sub.id, sibling.sub.id, closed.sub.id)
        w.clock.set(pastDue.sub.nextChargeAt!!)
        rw.job.runOnce()
        assertEquals(SubscriptionStatus.PAST_DUE, subscription(pastDue.sub.id).status)

        sql("UPDATE `pano_market_subscription` SET `status` = 'CANCELLED', `endReason` = 'BUYER_CANCEL' WHERE `id` = ?", closed.sub.id)

        val stranger = activeMerchant(name = "Zed")
        val ended = rw.subs.onChargebackOwner(sw.db, { }, order(charged.order.id))

        assertEquals(2, ended, "the active sibling and the past due one")

        for (id in listOf(sibling.sub.id, pastDue.sub.id)) {
            val row = subscription(id)

            assertEquals(SubscriptionStatus.CANCELLED, row.status, "#$id")
            assertEquals("CHARGEBACK", row.endReason, "#$id")
            assertNull(row.storedMethod, "#$id")
        }

        assertTrue(expireRows(sibling.order.id) > 0, "their goods came from other orders: expired, not revoked")
        assertEquals(EntitlementStatus.EXPIRED, w.entitlements.getById(sibling.sub.entitlementId!!, pool)!!.status)
        assertEquals(SubscriptionStatus.ACTIVE, subscription(charged.sub.id).status, "the charged-back order's own subscription is onOrderChargeback's")
        assertEquals(SubscriptionStatus.ACTIVE, subscription(stranger.sub.id).status, "another buyer")
        assertEquals("BUYER_CANCEL", subscription(closed.sub.id).endReason, "a closed row keeps its own reason")
        assertEquals(0, rw.subs.onChargebackOwner(sw.db, { }, order(charged.order.id)), "nothing is left to end")
    }

    @Test
    fun `the chargeback of one order ends its own subscription first, then the others of the buyer, and a gateway row queues its remote cancel`(): Unit = runBlocking {
        val (g, _) = gateway(name = "Gil", gatewayId = "sub_one")
        val other = anotherFor(g)

        chargedBack(g.order)

        assertEquals("CHARGEBACK", subscription(g.sub.id).endReason)
        assertEquals(RemoteCancelState.PENDING, subscription(g.sub.id).remoteCancelState)
        assertEquals(SubscriptionStatus.ACTIVE, subscription(other.sub.id).status, "not in the dispute's transaction: the others follow after its commit")

        assertEquals(1, rw.subs.onChargebackOwner(sw.db, { }, order(g.order.id)))

        val row = subscription(other.sub.id)

        assertEquals(SubscriptionStatus.CANCELLED, row.status)
        assertEquals("CHARGEBACK", row.endReason)
    }

    @Test
    fun `a gateway subscription of the buyer queues its remote cancel when the chargeback of another order ends it`(): Unit = runBlocking {
        val charged = activeMerchant(name = "Hal")

        sw.caps(RecurringSupport.GATEWAY_MANAGED)

        val product = subProduct()
        val first = buy(product, charged.caller)
        val end = w.clock.now() + 30 * day

        succeed(first, subscription = gatewayState("sub_hal", periodStart = w.clock.now(), periodEnd = end))

        val gw = subscription(first.subscriptionId!!)

        assertEquals(1, rw.subs.onChargebackOwner(sw.db, { }, order(charged.order.id)))
        assertEquals(SubscriptionStatus.CANCELLED, subscription(gw.id).status)
        assertEquals("CHARGEBACK", subscription(gw.id).endReason)
        assertEquals(RemoteCancelState.PENDING, subscription(gw.id).remoteCancelState, "the gateway is told to stop billing")
        assertEquals(SubscriptionStatus.ACTIVE, subscription(charged.sub.id).status)
    }

    // ==================================================================================== the step is recoverable (review fix)

    /** The order as the O11 transaction leaves it (status `CHARGEBACK`, a dispute row opened at [at]) plus its own subscription's ending; the after-commit hook is NOT run. */
    private suspend fun committedChargeback(a: Active, at: Long) {
        chargedBack(a.order)
        sql("UPDATE `pano_market_order` SET `status` = 'CHARGEBACK' WHERE `id` = ?", a.order.id)
        // revokeOnChargeback (default): the dispute flow's REVOKE rows end the order's own entitlements (I20)
        sql("UPDATE `pano_market_entitlement` SET `status` = 'REVOKED' WHERE `orderId` = ?", a.order.id)
        // O11 takes the units out of the product's soldCount (I17)
        sql("UPDATE `pano_market_product` SET `soldCount` = `soldCount` - 1 WHERE `id` = ?", a.product.id)
        w.disputes.add(MarketDispute(orderId = a.order.id, status = DisputeRecordStatus.OPEN, openedAt = at, createdAt = at, updatedAt = at), pool)
    }

    private fun failUpdatesOf(subscriptionId: Long) = runBlocking {
        sql(
            "CREATE TRIGGER `w2_fail_sub` BEFORE UPDATE ON `pano_market_subscription` FOR EACH ROW BEGIN " +
                "IF NEW.`id` = $subscriptionId THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'injected failure'; END IF; END"
        )
    }

    private fun stopFailing() = runBlocking { sql("DROP TRIGGER IF EXISTS `w2_fail_sub`") }

    @Test
    fun `one sibling that fails to end does not stop the next, and the sweep of the job ends it afterwards`(): Unit = runBlocking {
        val charged = activeMerchant(name = "Ivo")
        val first = anotherFor(charged)
        val second = anotherFor(charged)

        committedChargeback(charged, at = Long.MAX_VALUE - 1)
        failUpdatesOf(first.sub.id)

        try {
            assertEquals(1, rw.subs.onChargebackOwner(sw.db, { }, order(charged.order.id)), "the failing one is skipped, the next one ends")
        } finally {
            stopFailing()
        }

        assertEquals(SubscriptionStatus.ACTIVE, subscription(first.sub.id).status, "the failure rolled that one back")
        assertEquals(SubscriptionStatus.CANCELLED, subscription(second.sub.id).status)
        assertEquals("CHARGEBACK", subscription(second.sub.id).endReason)

        // nothing re-runs the hook; the next job tick derives the leftover from committed state
        rw.job.runOnce()

        assertEquals(SubscriptionStatus.CANCELLED, subscription(first.sub.id).status)
        assertEquals("CHARGEBACK", subscription(first.sub.id).endReason)
    }

    @Test
    fun `a hook that never ran is made good by the next job run, once, and only for the owner's earlier subscriptions of a chargeback that still stands`(): Unit = runBlocking {
        val charged = activeMerchant(name = "Jan")
        val early = anotherFor(charged)
        val late = anotherFor(charged)
        val stranger = activeMerchant(name = "Kim")
        val won = activeMerchant(name = "Lea")
        val wonSibling = anotherFor(won)

        sql("UPDATE `pano_market_subscription` SET `createdAt` = 1000")
        sql("UPDATE `pano_market_subscription` SET `createdAt` = 3000 WHERE `id` = ?", late.sub.id)

        committedChargeback(charged, at = 2000)

        // a dispute that was won: the order is back to COMPLETED, so nothing is owed
        w.disputes.add(MarketDispute(orderId = won.order.id, status = DisputeRecordStatus.WON, openedAt = 2000, createdAt = 2000, updatedAt = 2000), pool)

        rw.job.runOnce()

        assertEquals(SubscriptionStatus.CANCELLED, subscription(early.sub.id).status)
        assertEquals("CHARGEBACK", subscription(early.sub.id).endReason)
        assertTrue(expireRows(early.order.id) > 0, "expired, not revoked")
        assertEquals(SubscriptionStatus.ACTIVE, subscription(late.sub.id).status, "it started after the chargeback")
        assertEquals(SubscriptionStatus.ACTIVE, subscription(stranger.sub.id).status)
        assertEquals(SubscriptionStatus.ACTIVE, subscription(wonSibling.sub.id).status, "a won dispute leaves the order out of the sweep")
        assertEquals(0, rw.subs.chargebackOwnersPending(pool, "pano_", 50).size, "the late subscription is not the sweep's, nothing else is pending")

        val events = count("market_order_event", "`orderId` = ?", early.order.id)

        rw.job.runOnce()

        assertEquals(events, count("market_order_event", "`orderId` = ?", early.order.id), "a repeat changes nothing")
    }

    @Test
    fun `the sweep sees a charged-back order whose buyer still has an open subscription and nothing after they ended`(): Unit = runBlocking {
        val charged = activeMerchant(name = "Max")
        val sibling = anotherFor(charged)

        assertEquals(0, rw.subs.chargebackOwnersPending(pool, "pano_", 50).size, "no chargeback, nothing pending")

        committedChargeback(charged, at = Long.MAX_VALUE - 1)

        assertEquals(listOf(charged.order.id), rw.subs.chargebackOwnersPending(pool, "pano_", 50).map { it.first })
        assertEquals(1, rw.subs.onChargebackOwnerOf(sw.db, { }, charged.order.id, Long.MAX_VALUE))
        assertEquals(SubscriptionStatus.CANCELLED, subscription(sibling.sub.id).status)
        assertTrue(rw.subs.chargebackOwnersPending(pool, "pano_", 50).isEmpty())
    }
}
