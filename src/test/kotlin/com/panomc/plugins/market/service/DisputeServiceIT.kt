package com.panomc.plugins.market.service

import com.panomc.plugins.market.config.MarketConfig
import com.panomc.plugins.market.core.abuse.BlockEntry
import com.panomc.plugins.market.core.abuse.BlockMatcher
import com.panomc.plugins.market.core.abuse.BlockSubjects
import com.panomc.plugins.market.core.abuse.BlockType as CoreBlockType
import com.panomc.plugins.market.core.delivery.ProductAction
import com.panomc.plugins.market.core.refund.RefundMath
import com.panomc.plugins.market.db.MarketDaoITBase
import com.panomc.plugins.market.db.model.BlockSource
import com.panomc.plugins.market.db.model.BlockType
import com.panomc.plugins.market.db.model.CreatorEarningState
import com.panomc.plugins.market.db.model.CreditTxType
import com.panomc.plugins.market.db.model.DeliveryActionType
import com.panomc.plugins.market.db.model.DeliveryPhase
import com.panomc.plugins.market.db.model.DeliverySourceType
import com.panomc.plugins.market.db.model.DeliveryStatus
import com.panomc.plugins.market.db.model.DisputeOrigin
import com.panomc.plugins.market.db.model.DisputeRecordStatus
import com.panomc.plugins.market.db.model.DisputeStatus
import com.panomc.plugins.market.db.model.EntitlementStatus
import com.panomc.plugins.market.db.model.MarketBlock
import com.panomc.plugins.market.db.model.MarketCreatorEarning
import com.panomc.plugins.market.db.model.MarketPayment
import com.panomc.plugins.market.db.model.OrderEventType
import com.panomc.plugins.market.db.model.PaymentStatus
import com.panomc.plugins.market.db.model.RefundOrigin
import com.panomc.plugins.market.db.model.RefundStatus
import com.panomc.plugins.market.error.InsufficientCredits
import com.panomc.plugins.market.error.InvalidOrderTransition
import com.panomc.plugins.market.error.InvalidState
import com.panomc.plugins.market.routes.api.payment.EventNotHandled
import com.panomc.plugins.market.routes.api.payment.InboundEventContext
import com.panomc.plugins.market.routes.api.payment.PaymentEventSink
import com.panomc.plugins.market.spi.common.Money
import com.panomc.plugins.market.spi.payment.DisputeState
import com.panomc.plugins.market.spi.payment.PaymentEvent
import com.panomc.plugins.market.spi.payment.PaymentTarget
import com.panomc.plugins.market.spi.payment.RefundResult
import com.panomc.plugins.market.spi.payment.RefundState
import com.panomc.plugins.market.support.MarketTestDb
import com.panomc.plugins.market.support.Race
import com.panomc.plugins.market.support.TestUser
import com.panomc.plugins.market.support.TestWiring
import com.panomc.plugins.market.util.OrderStatus
import io.vertx.core.Vertx
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
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
 * `DisputeService` on a real MariaDB (MK-112; 21 sections 4 and 5, 07 section 8.5, 11 section 10, 08 sections 11 and 12; tests RF-07, RF-08, V-07 to V-10,
 * RD-D9 to RD-D11, RD-D18, R-27, F-14): the inbound `DisputeUpdated` and the panel's manual chargeback, O11 (status, revoke, upgrade successors, block list, chargeback
 * actions, creator earning, cashback and top-up clawback with debt, subscription hook, webhook), O12, and the event routing of the inbound pipeline. Invariants I1 to
 * I22 are checked after every test by the base class (I3 allows the debt of a `dispute:*` clawback, I17 the `soldCount` that O11 takes away and O12 gives back).
 */
class DisputeServiceIT : MarketDaoITBase() {
    private lateinit var w: TestWiring
    private lateinit var r: RefundWorld
    private lateinit var disputes: DisputeService
    private val vertx: Vertx = Vertx.vertx()
    private val alerts = CopyOnWriteArrayList<Triple<Long, String, JsonObject>>()
    private val subscriptionCalls = CopyOnWriteArrayList<Pair<Long, Long>>()
    private val ownerCalls = CopyOnWriteArrayList<Long>()
    private var ownerHookFails = false
    private var steveUser: TestUser? = null

    @AfterAll
    fun closeVertx() {
        vertx.close()
    }

    @BeforeEach
    fun wire() {
        steveUser = null
        alerts.clear()
        subscriptionCalls.clear()
        ownerCalls.clear()
        ownerHookFails = false
        w = TestWiring(pool)
        r = RefundWorld(w, vertx)
        build()
    }

    private fun build() {
        disputes = DisputeService(
            w.db, r.d.locks, w.clock, { w.config }, w.orders, w.orderItems, w.orderEvents, w.payments, w.disputes, w.blocks, w.deliveries, w.entitlements, w.creditTxs, r.d.credits,
            r.d.service, r.d.entitlementService, r.service,
            StandardDisputeEffects(r.effects, r.webhooks.service, { order ->
                ownerCalls += order.id

                if (ownerHookFails) throw IllegalStateException("the owner's subscriptions could not be ended")
            }) { _, order, dispute -> subscriptionCalls += order.id to dispute.id },
            DisputeAlerts { orderId, code, data -> alerts += Triple(orderId, code, data) }
        )
    }

    override suspend fun assertInvariants() {
        w.assertInvariants()
    }

    private suspend fun steve(): TestUser = steveUser ?: w.fixtures.user("Steve").also { steveUser = it }

    /** The test store with the dispute switches of one test (a [MarketConfig] cannot be copied). */
    private fun cfg(
        chargebackActions: String = "[]",
        revokeOnChargeback: Boolean = true,
        autoBlock: Boolean = true,
        revokeCreditOrders: Boolean = true
    ) = w.configure {
        MarketConfig(
            currency = "EUR", vatPercent = 20.0, showVatInPrice = true, creditValue = 1.0, storeTimeZone = "UTC",
            revokeOnChargeback = revokeOnChargeback, autoBlockOnChargeback = autoBlock, revokeCreditOrdersOnTopUpChargeback = revokeCreditOrders, chargebackActions = chargebackActions
        )
    }

    private fun permission(id: String, vararg nodes: String) = ProductAction(id = id, type = DeliveryActionType.PERMISSION, nodes = nodes.toList())

    private fun ban(id: String = "c1", server: Long = 7L) =
        ProductAction(id = id, type = DeliveryActionType.COMMAND, commands = listOf("ban {username} Chargeback"), targetServers = listOf(server))

    private fun actionsJson(vararg actions: ProductAction) = JsonArray(actions.map { it.toJson() }).encode()

    private suspend fun revokeRows(orderId: Long) = r.d.rows(orderId).filter { it.phase == DeliveryPhase.REVOKE }

    private suspend fun timeline(orderId: Long) = w.orderEvents.getByOrderId(orderId, pool)

    private suspend fun disputeRows(orderId: Long) = w.disputes.getByOrderId(orderId, pool)

    private suspend fun blocks() = MarketTestDb.sql(pool, "SELECT `type`, `value`, `source`, `orderId` FROM `${MarketTestDb.TABLE_PREFIX}market_block` ORDER BY `id`")

    private suspend fun blockRows() = MarketTestDb.sql(
        pool, "SELECT `id`, `type`, `value`, `source`, `orderId`, `reason`, `createdBy`, `expiresAt` FROM `${MarketTestDb.TABLE_PREFIX}market_block` ORDER BY `id`"
    )

    private suspend fun blockKeys() = blockRows().map { "${it.getString("type")}:${it.getString("value")}" }.toSet()

    /** What the block list says about [subjects] right now (the matcher of the checkout, over the rows as they are). */
    private suspend fun blockedBy(subjects: BlockSubjects) = BlockMatcher(
        blockRows().map { BlockEntry(it.getLong("id"), CoreBlockType.valueOf(it.getString("type")), it.getString("value"), it.getLong("expiresAt")) }
    ).match(subjects, w.clock.now())

    private suspend fun blockedNow(user: TestUser, email: String) = blockedBy(BlockSubjects(usernames = setOf(user.username), userIds = setOf(user.id), emails = setOf(email)))

    private suspend fun blockEventTypes(orderId: Long, type: OrderEventType): Set<String> =
        timeline(orderId).filter { it.type == type }.flatMap { e -> JsonObject(e.data!!).getJsonArray("types").map { it.toString() } }.toSet()

    private suspend fun manualBlock(type: BlockType, value: String, expiresAt: Long? = null): Long {
        val now = w.clock.now()

        return w.blocks.add(
            MarketBlock(type = type, value = value, reason = "manual ban", source = BlockSource.MANUAL, orderId = null, createdBy = 9, expiresAt = expiresAt, createdAt = now, updatedAt = now), pool
        )!!
    }

    private suspend fun hooks(event: String) =
        MarketTestDb.sql(pool, "SELECT `event`, `body` FROM `${MarketTestDb.TABLE_PREFIX}market_webhook_delivery` WHERE `event` = ? ORDER BY `id`", event)

    private suspend fun sold(productId: Long): Int = w.products.getById(productId, pool)!!.soldCount

    /** A paid credit top-up order of [credits] (x100) whose grant is in the ledger. */
    private suspend fun topUp(user: TestUser, credits: Long = 10_000): PaidOrder {
        val paid = r.place(user, listOf(RefundLine(credits)), grant = false)

        MarketTestDb.sql(pool, "UPDATE `${MarketTestDb.TABLE_PREFIX}market_order_item` SET `creditAmount` = ? WHERE `id` = ?", credits, paid.items[0].id)
        w.db.tx { conn -> r.d.credits.creditOrderItems(w.orders.getById(paid.order.id, conn)!!, w.orderItems.getByOrderIds(listOf(paid.order.id), conn), conn) { true } }

        return paid
    }

    /** The credits are gone: a plain `REVOKE` of [amount] from the user's balance. */
    private suspend fun spend(user: TestUser, amount: Long, key: String) {
        w.db.tx { conn ->
            r.d.credits.lockAccounts(listOf(user.id), true, conn)
            r.d.credits.revoke(user.id, amount, key, null, "spent", conn)
        }
    }

    private suspend fun entitlementOf(paid: PaidOrder) = w.entitlements.getByOrderItemId(paid.items[0].id, pool).single()

    private suspend fun ledger(user: TestUser, type: CreditTxType) = w.creditTxs.getByUserId(user.id, 100, pool).filter { it.type == type }

    /** An inbound `DisputeUpdated` for the attempt of [paid], applied the way the dispatcher's router does. */
    private suspend fun dispute(paid: PaidOrder, state: DisputeState, id: String? = "dp_1", amount: Long? = null, reason: String? = null) {
        val event = PaymentEvent.DisputeUpdated(PaymentTarget.Attempt(paid.attempt.id), state)

        event.gatewayDisputeId = id
        event.amount = amount?.let { Money(it, "EUR") }
        event.reason = reason

        disputes.onDisputeUpdated(event, r.attempt(paid.attempt.id), "evt", null)
    }

    // ===== RF-08: a chargeback ======================================================================================================

    @Test
    fun `RF-08 a dispute opened at the gateway charges the order back, revokes, blocks the buyer, reverses the earning and queues the webhook, then WON restores it`(): Unit = runBlocking {
        cfg(chargebackActions = actionsJson(ban()))
        r.d.roster.granted = listOf(7L)

        val u = steve()
        val code = w.fixtures.creatorCode(code = "CREATOR")
        val paid = r.place(u, listOf(RefundLine(10_000, actions = listOf(permission("a1", "group.vip")))), email = "Steve@Example.com")
        val itemId = paid.items[0].id
        val now = w.clock.now()

        MarketTestDb.sql(pool, "UPDATE `${MarketTestDb.TABLE_PREFIX}market_order` SET `creatorCodeId` = ? WHERE `id` = ?", code.id, paid.order.id)
        w.creatorEarnings.add(
            MarketCreatorEarning(
                creatorCodeId = code.id, orderId = paid.order.id, baseAmount = 10_000, commissionPercent = 1000, amount = 1000, currency = "EUR",
                state = CreatorEarningState.AVAILABLE, availableAt = now, createdAt = now, updatedAt = now
            ),
            pool
        )
        MarketTestDb.sql(pool, "UPDATE `${MarketTestDb.TABLE_PREFIX}market_creator_code` SET `earnings` = 1000 WHERE `id` = ?", code.id)
        r.webhooks.endpoint("https://hooks.example.com/market", events = "[\"order.chargeback\",\"order.chargeback.won\"]")

        assertEquals(1, sold(paid.products[0].id))

        // a partial dispute amount still charges the whole order back (RD-D18)
        dispute(paid, DisputeState.OPENED, "dp_1", amount = 4_000, reason = "fraudulent")

        val order = r.order(paid.order.id)
        val row = disputeRows(paid.order.id).single()

        assertEquals(OrderStatus.CHARGEBACK, order.status)
        assertEquals(OrderStatus.COMPLETED, order.statusBeforeDispute)
        assertEquals(DisputeStatus.OPEN, order.disputeStatus)
        assertEquals(DisputeRecordStatus.OPEN, row.status)
        assertEquals(DisputeOrigin.GATEWAY, row.origin)
        assertEquals(4_000, row.amount, "the disputed amount is informational")
        assertEquals("fake", row.providerId)
        assertEquals("dp_1", row.gatewayDisputeId)
        assertEquals(paid.attempt.id, row.paymentId)
        assertEquals("fraudulent", row.reason)

        // the goods: entitlement ended, REVOKE rows, the units left the sold count
        assertEquals(EntitlementStatus.REVOKED, w.entitlements.getByOrderItemId(itemId, pool).single().status)
        assertEquals("CHARGEBACK", w.entitlements.getByOrderItemId(itemId, pool).single().endReason)
        assertEquals(1, revokeRows(paid.order.id).size)
        assertEquals(0, sold(paid.products[0].id))

        // the block list: the recipient, the payer's account and the exact e-mail, all of this chargeback
        assertEquals(
            setOf("PLAYER:steve", "USER:${u.id}", "EMAIL:steve@example.com"), blocks().map { "${it.getString("type")}:${it.getString("value")}" }.toSet()
        )
        assertTrue(blocks().all { it.getString("source") == "CHARGEBACK" && it.getLong("orderId") == paid.order.id })

        val matcher = BlockMatcher(blocks().mapIndexed { i, b -> BlockEntry(i + 1L, com.panomc.plugins.market.core.abuse.BlockType.valueOf(b.getString("type")), b.getString("value")) })

        assertNotNull(matcher.match(BlockSubjects(usernames = setOf("STEVE")), w.clock.now()), "the next checkout of that buyer is refused")
        assertNotNull(matcher.match(BlockSubjects(emails = setOf("steve@example.com")), w.clock.now()))
        assertNull(matcher.match(BlockSubjects(usernames = setOf("Alex"), emails = setOf("alex@example.com")), w.clock.now()))

        // the earning is taken back in full
        val earning = w.creatorEarnings.get(paid.order.id, code.id, pool)!!

        assertEquals(1000, earning.reversedAmount)
        assertEquals(CreatorEarningState.REVERSED, earning.state)
        assertEquals(0, w.creatorCodes.getById(code.id, pool)!!.earnings)

        // the configured ban: an account order, so the payer is the target and the row is live
        val ban = r.d.rows(paid.order.id).single { it.sourceType == DeliverySourceType.CHARGEBACK_ACTION }

        assertEquals("Steve", ban.playerUsername)
        assertEquals("cb:${row.id}:c1:7:0:GRANT:0", ban.idempotencyKey)
        assertEquals(DeliveryStatus.PENDING, ban.status)
        assertFalse(ban.requiresOnline, "a ban never waits for the player")

        // the webhook and the timeline
        val hook = hooks("order.chargeback").single()
        val sent = JsonObject(hook.getString("body")).getJsonObject("data").getJsonObject("dispute")

        assertEquals(row.id, sent.getLong("id"))
        assertEquals("OPEN", sent.getString("status"))
        assertEquals("dp_1", sent.getString("gatewayDisputeId"))
        assertTrue(timeline(paid.order.id).any { it.type == OrderEventType.STATUS_CHANGED && it.toStatus == "CHARGEBACK" })
        assertTrue(timeline(paid.order.id).any { it.type == OrderEventType.DISPUTE_OPENED })
        assertEquals(listOf("PLAYER", "USER", "EMAIL").toSet(), JsonObject(timeline(paid.order.id).single { it.type == OrderEventType.BLOCK_CREATED }.data!!).getJsonArray("types").map { it.toString() }.toSet())
        assertTrue(alerts.any { it.first == paid.order.id && it.second == "CHARGEBACK_OPENED" })

        // a replay of the same event changes nothing
        dispute(paid, DisputeState.OPENED, "dp_1", amount = 4_000)

        assertEquals(1, disputeRows(paid.order.id).size)
        assertEquals(1, hooks("order.chargeback").size)
        assertEquals(1, revokeRows(paid.order.id).size)

        // WON: the status is back, the blocks go, the webhook says so, nothing is granted again
        val grantRows = r.d.rows(paid.order.id).count { it.phase == DeliveryPhase.GRANT && it.sourceType == DeliverySourceType.ORDER_ITEM }

        dispute(paid, DisputeState.WON, "dp_1")

        val after = r.order(paid.order.id)

        assertEquals(OrderStatus.COMPLETED, after.status)
        assertEquals(DisputeStatus.WON, after.disputeStatus)
        assertEquals(DisputeRecordStatus.WON, disputeRows(paid.order.id).single().status)
        assertNotNull(disputeRows(paid.order.id).single().resolvedAt)
        assertTrue(blocks().isEmpty())
        assertTrue(timeline(paid.order.id).any { it.type == OrderEventType.BLOCK_REMOVED })
        assertEquals(1, hooks("order.chargeback.won").size)
        assertEquals(EntitlementStatus.REVOKED, w.entitlements.getByOrderItemId(itemId, pool).single().status, "nothing is re-granted automatically")
        assertEquals(grantRows, r.d.rows(paid.order.id).count { it.phase == DeliveryPhase.GRANT && it.sourceType == DeliverySourceType.ORDER_ITEM })
        assertEquals(1, sold(paid.products[0].id), "the units are sold again")
        assertEquals(1000, w.creatorEarnings.get(paid.order.id, code.id, pool)!!.reversedAmount, "earnings are restored by hand")
    }

    // ===== statusBeforeDispute ======================================================================================================

    @Test
    fun `WON restores the status the order had, also PARTIALLY_REFUNDED and REFUNDED, and the refunded units stay out of the sold count`(): Unit = runBlocking {
        val partial = r.place(steve(), listOf(RefundLine(1000, actions = listOf(permission("a1", "group.vip")))))

        r.service.request(partial.order.id, RefundInput(amount = 400), r.key(), null)
        dispute(partial, DisputeState.OPENED, "dp_p")

        assertEquals(OrderStatus.CHARGEBACK, r.order(partial.order.id).status)
        assertEquals(OrderStatus.PARTIALLY_REFUNDED, r.order(partial.order.id).statusBeforeDispute)
        assertEquals(0, sold(partial.products[0].id))

        dispute(partial, DisputeState.WON, "dp_p")

        assertEquals(OrderStatus.PARTIALLY_REFUNDED, r.order(partial.order.id).status)
        assertEquals(1, sold(partial.products[0].id))

        val full = r.place(steve(), listOf(RefundLine(2000, actions = listOf(permission("b1", "group.vip2")))))

        r.service.request(full.order.id, RefundInput(), r.key(), null)

        assertEquals(OrderStatus.REFUNDED, r.order(full.order.id).status)

        dispute(full, DisputeState.OPENED, "dp_f")

        assertEquals(OrderStatus.REFUNDED, r.order(full.order.id).statusBeforeDispute)

        dispute(full, DisputeState.WON, "dp_f")

        assertEquals(OrderStatus.REFUNDED, r.order(full.order.id).status)
        assertEquals(0, sold(full.products[0].id))
    }

    // ===== a refund in flight while the dispute opens (R-27, E2E-06 review) =========================================================

    /** A full panel refund the gateway has not settled, so that it is still in flight when the dispute opens (`PENDING` rows are the gateway's, O11 step 4 leaves them). */
    private suspend fun pendingRefund(paid: PaidOrder, input: RefundInput, gatewayRefundId: String): Long {
        r.fake.onRefund = { RefundResult.Pending().also { p -> p.gatewayRefundId = gatewayRefundId } }

        val refund = r.service.request(paid.order.id, input, r.key(), null).refund

        assertEquals(RefundStatus.PENDING, refund.status)

        return refund.id
    }

    @Test
    fun `a full refund settling on the charged-back order moves statusBeforeDispute to REFUNDED, gives the codes back and WON then restores REFUNDED (R-27)`(): Unit = runBlocking {
        val coupon = w.fixtures.coupon(code = "SAVE10", redeemLimit = 5)
        val paid = r.place(steve(), listOf(RefundLine(1000, actions = listOf(permission("a1", "group.vip")))))

        MarketTestDb.sql(pool, "UPDATE `${MarketTestDb.TABLE_PREFIX}market_coupon` SET `usedCount` = `usedCount` + 1 WHERE `id` = ?", coupon.id)
        w.redemptions.add(
            com.panomc.plugins.market.db.model.MarketRedemption(
                kind = com.panomc.plugins.market.db.model.RedemptionKind.COUPON, refId = coupon.id, orderId = paid.order.id, code = "SAVE10",
                state = com.panomc.plugins.market.db.model.RedemptionState.APPLIED, buyerKey = paid.order.buyerKey
            ),
            pool
        )

        val refundId = pendingRefund(paid, RefundInput(), "gw-r27")

        dispute(paid, DisputeState.OPENED, "dp_r27")

        assertEquals(OrderStatus.CHARGEBACK, r.order(paid.order.id).status)
        assertEquals(OrderStatus.COMPLETED, r.order(paid.order.id).statusBeforeDispute)
        assertEquals(0, sold(paid.products[0].id), "O11 took the unit out of the sold count")

        val revokedByDispute = revokeRows(paid.order.id).map { it.id }

        assertEquals(1, revokedByDispute.size)

        // the gateway settles the refund the panel started before the dispute
        r.inbound(paid, RefundState.SUCCEEDED, amount = 1000, gatewayRefundId = "gw-r27")

        val settled = r.order(paid.order.id)

        assertEquals(RefundStatus.SUCCEEDED, r.refund(refundId).status)
        assertEquals(OrderStatus.CHARGEBACK, settled.status, "a refund never moves a charged-back order")
        assertEquals(OrderStatus.REFUNDED, settled.statusBeforeDispute, "what O12 restores follows the money that went back")
        assertEquals(1000, settled.refundedTotal)
        assertEquals(1, w.orderItems.getByOrderIds(listOf(paid.order.id), pool).single().refundedQuantity, "the line counts as refunded")
        assertEquals(com.panomc.plugins.market.db.model.RedemptionState.RELEASED, w.redemptions.getByOrderId(paid.order.id, pool).single().state, "a fully refunded order gives its code use back")
        assertEquals(0, w.coupons.getById(coupon.id, pool)!!.usedCount)
        assertEquals(revokedByDispute, revokeRows(paid.order.id).map { it.id }, "the chargeback had taken everything back: the refund plans no second REVOKE")
        assertEquals(0, sold(paid.products[0].id), "the unit left the sold count once, at O11")

        dispute(paid, DisputeState.WON, "dp_r27")

        val won = r.order(paid.order.id)

        assertEquals(OrderStatus.REFUNDED, won.status, "a won dispute on a refunded order is REFUNDED, not COMPLETED")
        assertEquals(1000, won.refundedTotal)
        assertEquals(0, sold(paid.products[0].id), "a refunded unit stays out of the sold count")
        assertEquals(com.panomc.plugins.market.db.model.RedemptionState.RELEASED, w.redemptions.getByOrderId(paid.order.id, pool).single().state)
    }

    @Test
    fun `a partial refund settling on the charged-back order moves statusBeforeDispute to PARTIALLY_REFUNDED and WON restores it (R-27)`(): Unit = runBlocking {
        val coupon = w.fixtures.coupon(code = "SAVE10", redeemLimit = 5)
        val paid = r.place(steve(), listOf(RefundLine(1000, actions = listOf(permission("a1", "group.vip")))))

        MarketTestDb.sql(pool, "UPDATE `${MarketTestDb.TABLE_PREFIX}market_coupon` SET `usedCount` = `usedCount` + 1 WHERE `id` = ?", coupon.id)
        w.redemptions.add(
            com.panomc.plugins.market.db.model.MarketRedemption(
                kind = com.panomc.plugins.market.db.model.RedemptionKind.COUPON, refId = coupon.id, orderId = paid.order.id, code = "SAVE10",
                state = com.panomc.plugins.market.db.model.RedemptionState.APPLIED, buyerKey = paid.order.buyerKey
            ),
            pool
        )

        pendingRefund(paid, RefundInput(amount = 400), "gw-r27p")
        dispute(paid, DisputeState.OPENED, "dp_r27p")

        assertEquals(OrderStatus.COMPLETED, r.order(paid.order.id).statusBeforeDispute)

        r.inbound(paid, RefundState.SUCCEEDED, amount = 400, gatewayRefundId = "gw-r27p")

        val settled = r.order(paid.order.id)

        assertEquals(OrderStatus.CHARGEBACK, settled.status)
        assertEquals(OrderStatus.PARTIALLY_REFUNDED, settled.statusBeforeDispute)
        assertEquals(400, settled.refundedTotal)
        assertEquals(com.panomc.plugins.market.db.model.RedemptionState.APPLIED, w.redemptions.getByOrderId(paid.order.id, pool).single().state, "a partial refund keeps the code use")
        assertEquals(0, sold(paid.products[0].id))

        dispute(paid, DisputeState.WON, "dp_r27p")

        assertEquals(OrderStatus.PARTIALLY_REFUNDED, r.order(paid.order.id).status)
        assertEquals(1, sold(paid.products[0].id), "an amount-only partial refund counts no unit as refunded (the rule of the refund service)")
    }

    @Test
    fun `a refund that settles on a charged-back order whose dispute is lost leaves it charged back, refunded and with its codes released (R-27)`(): Unit = runBlocking {
        val paid = r.place(steve(), listOf(RefundLine(1000, actions = listOf(permission("a1", "group.vip")))))

        pendingRefund(paid, RefundInput(), "gw-r27l")
        dispute(paid, DisputeState.OPENED, "dp_r27l")
        r.inbound(paid, RefundState.SUCCEEDED, amount = 1000, gatewayRefundId = "gw-r27l")
        dispute(paid, DisputeState.LOST, "dp_r27l")

        val order = r.order(paid.order.id)

        assertEquals(OrderStatus.CHARGEBACK, order.status)
        assertEquals(OrderStatus.REFUNDED, order.statusBeforeDispute)
        assertEquals(0, sold(paid.products[0].id))
    }

    @Test
    fun `a refund by line settling on the charged-back order counts its units once, plans no second REVOKE and WON gives back only the units that were not refunded (R-27)`(): Unit = runBlocking {
        val paid = r.place(
            steve(),
            listOf(
                RefundLine(600, quantity = 2, actions = listOf(permission("a1", "group.vip"), ProductAction(id = "a2", type = DeliveryActionType.CREDIT, credit = 100))),
                RefundLine(400, actions = listOf(permission("b1", "group.vip2")))
            )
        )
        val line1 = paid.items[0]

        pendingRefund(paid, RefundInput(items = listOf(RefundMath.ItemRequest(line1.id, 1))), "gw-r27i")
        dispute(paid, DisputeState.OPENED, "dp_r27i")

        assertEquals(0, sold(paid.products[0].id), "O11 took both units of line 1 out of the sold count")
        assertEquals(0, sold(paid.products[1].id))

        val revokedByDispute = revokeRows(paid.order.id).map { it.id }.toSet()

        r.inbound(paid, RefundState.SUCCEEDED, amount = 300, gatewayRefundId = "gw-r27i")

        val settled = r.order(paid.order.id)
        val items = r.items(paid.order.id).associateBy { it.id }

        assertEquals(OrderStatus.CHARGEBACK, settled.status)
        assertEquals(OrderStatus.PARTIALLY_REFUNDED, settled.statusBeforeDispute)
        assertEquals(300, settled.refundedTotal)
        assertEquals(1, items.getValue(line1.id).refundedQuantity, "one unit of line 1 counts as refunded")
        assertEquals(0, sold(paid.products[0].id), "and it does not leave the sold count a second time")
        assertEquals(0, sold(paid.products[1].id))

        val rows = revokeRows(paid.order.id)

        assertEquals(revokedByDispute, rows.map { it.id }.toSet(), "the chargeback had taken every unit back: the refund plans nothing more")
        assertEquals(rows.size, rows.map { Triple(it.orderItemId, it.actionId, it.unitIndex) }.toSet().size, "no REVOKE row twice for an item, action and unit")

        dispute(paid, DisputeState.WON, "dp_r27i")

        assertEquals(OrderStatus.PARTIALLY_REFUNDED, r.order(paid.order.id).status)
        assertEquals(1, sold(paid.products[0].id), "one of the two units of line 1 was refunded, so one comes back")
        assertEquals(1, sold(paid.products[1].id), "the untouched line comes back whole")
    }

    // ===== V-07, RD-D9, RD-D10 ======================================================================================================

    @Test
    fun `V-07 an inquiry does nothing to the order, OPENED is one O11 and WON delivered twice is one O12 with one block`(): Unit = runBlocking {
        r.webhooks.endpoint("https://hooks.example.com/market", events = "[\"*\"]")

        val paid = r.place(steve(), listOf(RefundLine(1000, actions = listOf(permission("a1", "group.vip")))))

        dispute(paid, DisputeState.INQUIRY, "dp_7")

        var order = r.order(paid.order.id)

        assertEquals(OrderStatus.COMPLETED, order.status)
        assertEquals(DisputeStatus.NONE, order.disputeStatus)
        assertEquals(DisputeRecordStatus.INQUIRY, disputeRows(paid.order.id).single().status)
        assertTrue(blocks().isEmpty())
        assertTrue(revokeRows(paid.order.id).isEmpty())
        assertEquals(EntitlementStatus.ACTIVE, w.entitlements.getByOrderItemId(paid.items[0].id, pool).single().status)
        assertTrue(timeline(paid.order.id).any { it.type == OrderEventType.DISPUTE_INQUIRY })
        assertTrue(alerts.any { it.first == paid.order.id && it.second == "DISPUTE_INQUIRY" })
        assertTrue(hooks("order.chargeback").isEmpty())

        // the inquiry becomes a dispute: the same row, one O11
        dispute(paid, DisputeState.OPENED, "dp_7")
        dispute(paid, DisputeState.OPENED, "dp_7")

        order = r.order(paid.order.id)

        assertEquals(OrderStatus.CHARGEBACK, order.status)
        assertEquals(1, disputeRows(paid.order.id).size)
        assertEquals(1, hooks("order.chargeback").size)
        assertEquals(1, revokeRows(paid.order.id).size)
        assertEquals(2, blocks().size, "the recipient and the payer's account (the order has no e-mail)")
    }

    @Test
    fun `RD-D10 events without a gateway id, OPENED twice then WON twice is one dispute row, one O11 and one O12`(): Unit = runBlocking {
        r.webhooks.endpoint("https://hooks.example.com/market", events = "[\"*\"]")

        val paid = r.place(steve(), listOf(RefundLine(1000, actions = listOf(permission("a1", "group.vip")))))

        dispute(paid, DisputeState.OPENED, id = null)
        dispute(paid, DisputeState.OPENED, id = null)

        assertEquals(1, disputeRows(paid.order.id).size)
        assertEquals(OrderStatus.CHARGEBACK, r.order(paid.order.id).status)

        dispute(paid, DisputeState.WON, id = null)
        dispute(paid, DisputeState.WON, id = null)

        assertEquals(1, disputeRows(paid.order.id).size)
        assertEquals(DisputeRecordStatus.WON, disputeRows(paid.order.id).single().status)
        assertEquals(OrderStatus.COMPLETED, r.order(paid.order.id).status)
        assertEquals(1, hooks("order.chargeback").size)
        assertEquals(1, hooks("order.chargeback.won").size)
        assertEquals(1, timeline(paid.order.id).count { it.type == OrderEventType.STATUS_CHANGED && it.toStatus == "CHARGEBACK" })
        assertEquals(1, timeline(paid.order.id).count { it.type == OrderEventType.STATUS_CHANGED && it.fromStatus == "CHARGEBACK" })
    }

    @Test
    fun `RD-D9 a LOST dispute keeps the order charged back for good, a second WON is refused for the panel and ignored for the gateway`(): Unit = runBlocking {
        val paid = r.place(steve(), listOf(RefundLine(1000, actions = listOf(permission("a1", "group.vip")))))

        dispute(paid, DisputeState.OPENED, "dp_l")
        dispute(paid, DisputeState.LOST, "dp_l")

        assertEquals(OrderStatus.CHARGEBACK, r.order(paid.order.id).status)
        assertEquals(DisputeStatus.LOST, r.order(paid.order.id).disputeStatus)
        assertEquals(DisputeRecordStatus.LOST, disputeRows(paid.order.id).single().status)

        // the gateway's late WON for a lost row changes nothing; the panel is told
        dispute(paid, DisputeState.WON, "dp_l")

        assertEquals(OrderStatus.CHARGEBACK, r.order(paid.order.id).status)

        val id = disputeRows(paid.order.id).single().id

        assertThrows(InvalidState::class.java) { runBlocking { disputes.resolve(id, DisputeRecordStatus.WON, null) } }
    }

    @Test
    fun `a dispute that is only reported LOST is still O11 (the money is gone), and a WON for a dispute never opened touches nothing`(): Unit = runBlocking {
        val lost = r.place(steve(), listOf(RefundLine(1000, actions = listOf(permission("a1", "group.vip")))))

        dispute(lost, DisputeState.LOST, "dp_x")

        assertEquals(OrderStatus.CHARGEBACK, r.order(lost.order.id).status)
        assertEquals(DisputeStatus.LOST, r.order(lost.order.id).disputeStatus)
        assertEquals(EntitlementStatus.REVOKED, w.entitlements.getByOrderItemId(lost.items[0].id, pool).single().status)

        val won = r.place(steve(), listOf(RefundLine(1000, actions = listOf(permission("b1", "group.vip2")))))

        dispute(won, DisputeState.WON, "dp_y")

        assertEquals(OrderStatus.COMPLETED, r.order(won.order.id).status)
        assertEquals(DisputeRecordStatus.WON, disputeRows(won.order.id).single().status)
        assertEquals(EntitlementStatus.ACTIVE, w.entitlements.getByOrderItemId(won.items[0].id, pool).single().status)
    }

    // ===== the panel =================================================================================================================

    @Test
    fun `the panel opens a manual chargeback once and resolves it, a double click opens one dispute and a wrong state is INVALID_STATE`(): Unit = runBlocking {
        val paid = r.place(steve(), listOf(RefundLine(1000, actions = listOf(permission("a1", "group.vip")))))
        val first = disputes.open(paid.order.id, null, "chargeback by phone", 9)
        val second = disputes.open(paid.order.id, null, null, 9)

        assertEquals(first.id, second.id)
        assertEquals(DisputeOrigin.MANUAL, first.origin)
        assertEquals(1_000, first.amount, "what the attempt took")
        assertEquals("chargeback by phone", first.reason)
        assertEquals(9L, first.createdBy)
        assertEquals(OrderStatus.CHARGEBACK, r.order(paid.order.id).status)
        assertEquals(1, disputeRows(paid.order.id).size)
        assertTrue(timeline(paid.order.id).filter { it.type == OrderEventType.STATUS_CHANGED }.all { it.actorType == com.panomc.plugins.market.db.model.OrderActorType.ADMIN })

        val closed = disputes.resolve(first.id, DisputeRecordStatus.CLOSED, 9)

        assertEquals(DisputeRecordStatus.CLOSED, closed.status)
        assertEquals(OrderStatus.COMPLETED, r.order(paid.order.id).status, "a closed open dispute means the merchant kept the money")
        // the same state again is a replayed PUT
        assertEquals(DisputeRecordStatus.CLOSED, disputes.resolve(first.id, DisputeRecordStatus.CLOSED, 9).status)
        assertThrows(InvalidState::class.java) { runBlocking { disputes.resolve(first.id, DisputeRecordStatus.LOST, 9) } }
        assertThrows(NoSuchElementException::class.java) { runBlocking { disputes.resolve(first.id + 1000, DisputeRecordStatus.WON, 9) } }

        // an order that was never paid has no O11
        val unpaid = r.place(steve(), listOf(RefundLine(1000)), grant = false)

        MarketTestDb.sql(pool, "UPDATE `${MarketTestDb.TABLE_PREFIX}market_order` SET `status` = 'PENDING', `paidAt` = NULL, `reservationState` = 'NONE' WHERE `id` = ?", unpaid.order.id)
        MarketTestDb.sql(pool, "UPDATE `${MarketTestDb.TABLE_PREFIX}market_product` SET `soldCount` = 0 WHERE `id` = ?", unpaid.products[0].id)
        assertThrows(InvalidOrderTransition::class.java) { runBlocking { disputes.open(unpaid.order.id, null, null, 9) } }

        MarketTestDb.sql(pool, "UPDATE `${MarketTestDb.TABLE_PREFIX}market_order` SET `status` = 'CANCELLED', `reservationState` = 'RELEASED' WHERE `id` = ?", unpaid.order.id)
    }

    @Test
    fun `a manual chargeback promotes the single inquiry of the order instead of adding a second dispute`(): Unit = runBlocking {
        val paid = r.place(steve(), listOf(RefundLine(1000)))

        dispute(paid, DisputeState.INQUIRY, "dp_i")

        val promoted = disputes.open(paid.order.id, null, null, 9)

        assertEquals(1, disputeRows(paid.order.id).size)
        assertEquals(DisputeRecordStatus.OPEN, promoted.status)
        assertEquals("dp_i", promoted.gatewayDisputeId)
        assertEquals(OrderStatus.CHARGEBACK, r.order(paid.order.id).status)
    }

    @Test
    fun `a second dispute on an order that is charged back is recorded and does nothing else`(): Unit = runBlocking {
        r.webhooks.endpoint("https://hooks.example.com/market", events = "[\"*\"]")

        val paid = r.place(steve(), listOf(RefundLine(1000, actions = listOf(permission("a1", "group.vip")))))

        dispute(paid, DisputeState.OPENED, "dp_a")
        dispute(paid, DisputeState.OPENED, "dp_b")

        assertEquals(2, disputeRows(paid.order.id).size)
        assertEquals(1, hooks("order.chargeback").size)
        assertEquals(1, revokeRows(paid.order.id).size)

        // WON of the first while the second is still open: the order stays charged back
        dispute(paid, DisputeState.WON, "dp_a")

        assertEquals(OrderStatus.CHARGEBACK, r.order(paid.order.id).status)

        dispute(paid, DisputeState.WON, "dp_b")

        assertEquals(OrderStatus.COMPLETED, r.order(paid.order.id).status)
        assertEquals(1, hooks("order.chargeback.won").size)
    }

    @Test
    fun `a dispute that was won and is opened again by the gateway is a second chargeback cycle of the same row`(): Unit = runBlocking {
        val paid = r.place(steve(), listOf(RefundLine(1000, actions = listOf(permission("a1", "group.vip")))))

        dispute(paid, DisputeState.OPENED, "dp_cycle")
        dispute(paid, DisputeState.WON, "dp_cycle")

        assertEquals(OrderStatus.COMPLETED, r.order(paid.order.id).status)
        assertEquals(1, sold(paid.products[0].id))
        assertTrue(blocks().isEmpty())

        dispute(paid, DisputeState.OPENED, "dp_cycle")

        val order = r.order(paid.order.id)
        val row = disputeRows(paid.order.id).single()

        assertEquals(OrderStatus.CHARGEBACK, order.status)
        assertEquals(OrderStatus.COMPLETED, order.statusBeforeDispute)
        assertEquals(DisputeRecordStatus.OPEN, row.status)
        assertNull(row.resolvedAt, "a re-opened row is not resolved")
        assertEquals(0, sold(paid.products[0].id))
        assertEquals(2, blocks().size, "the block list is written again")
        assertEquals(1, revokeRows(paid.order.id).size, "the goods were revoked once, the keys of the first cycle hold")
    }

    @Test
    fun `the same OPENED event three times at once is one chargeback`(): Unit = runBlocking {
        r.webhooks.endpoint("https://hooks.example.com/market", events = "[\"order.chargeback\"]")

        repeat(Race.rounds) { round ->
            val paid = r.place(steve(), listOf(RefundLine(1000, actions = listOf(permission("a$round", "group.vip$round")))), email = "steve$round@example.com")
            val outcomes = Race.run(3) { dispute(paid, DisputeState.OPENED, "dp_burst_$round") }

            assertTrue(outcomes.all { it.isSuccess }, "round $round: ${outcomes.mapNotNull { it.exceptionOrNull() }}")
            assertEquals(OrderStatus.CHARGEBACK, r.order(paid.order.id).status)
            assertEquals(1, disputeRows(paid.order.id).size, "round $round")
            assertEquals(1, revokeRows(paid.order.id).size, "round $round")
            assertEquals(1, timeline(paid.order.id).count { it.type == OrderEventType.STATUS_CHANGED && it.toStatus == "CHARGEBACK" }, "round $round")
        }

        assertEquals(Race.rounds, hooks("order.chargeback").size, "one webhook per chargeback")
    }

    // ===== V-10: upgrades ============================================================================================================

    @Test
    fun `V-10 the chargeback of tier 1 after upgrades 1 to 2 to 3 ends all three entitlements and plans REVOKE rows for each`(): Unit = runBlocking {
        val u = steve()
        val one = r.place(u, listOf(RefundLine(1000, actions = listOf(permission("a1", "group.vip")))))
        val two = r.place(u, listOf(RefundLine(1500, actions = listOf(permission("b1", "group.vip2")))))
        val three = r.place(u, listOf(RefundLine(2000, actions = listOf(permission("c1", "group.vip3")))))
        val e1 = w.entitlements.getByOrderItemId(one.items[0].id, pool).single()
        val e2 = w.entitlements.getByOrderItemId(two.items[0].id, pool).single()
        val e3 = w.entitlements.getByOrderItemId(three.items[0].id, pool).single()

        MarketTestDb.sql(pool, "UPDATE `${MarketTestDb.TABLE_PREFIX}market_entitlement` SET `status` = 'UPGRADED', `replacedById` = ?, `endReason` = 'UPGRADE', `endedAt` = ? WHERE `id` = ?", e2.id, w.clock.now(), e1.id)
        MarketTestDb.sql(pool, "UPDATE `${MarketTestDb.TABLE_PREFIX}market_entitlement` SET `status` = 'UPGRADED', `replacedById` = ?, `endReason` = 'UPGRADE', `endedAt` = ? WHERE `id` = ?", e3.id, w.clock.now(), e2.id)

        dispute(one, DisputeState.OPENED, "dp_u")

        for (e in listOf(e1, e2, e3)) {
            val now = w.entitlements.getById(e.id, pool)!!

            assertEquals(EntitlementStatus.REVOKED, now.status, "entitlement ${e.id}")
            assertEquals("CHARGEBACK", now.endReason)
        }

        assertEquals(1, revokeRows(one.order.id).size)
        assertEquals(1, revokeRows(two.order.id).size, "the successor order's own grant is taken back")
        assertEquals(1, revokeRows(three.order.id).size)

        // no money moves on the successor orders and they are not charged back
        assertEquals(OrderStatus.COMPLETED, r.order(two.order.id).status)
        assertEquals(OrderStatus.COMPLETED, r.order(three.order.id).status)
        assertEquals(0, r.order(three.order.id).refundedTotal)
        assertTrue(timeline(three.order.id).any { it.message == "UPGRADE_CASCADE_REVOKED" })

        val alert = alerts.single { it.second == "UPGRADE_SUCCESSORS_REVOKED" }

        assertEquals(one.order.id, alert.first)
        assertEquals(setOf(two.order.id, three.order.id), alert.third.getJsonArray("orders").map { (it as Number).toLong() }.toSet())
    }

    @Test
    fun `revokeOnChargeback off keeps the goods but the order is still charged back, blocked and its earning reversed`(): Unit = runBlocking {
        cfg(revokeOnChargeback = false)

        val paid = r.place(steve(), listOf(RefundLine(1000, actions = listOf(permission("a1", "group.vip")))))

        dispute(paid, DisputeState.OPENED, "dp_n")

        assertEquals(OrderStatus.CHARGEBACK, r.order(paid.order.id).status)
        assertEquals(EntitlementStatus.ACTIVE, w.entitlements.getByOrderItemId(paid.items[0].id, pool).single().status)
        assertTrue(revokeRows(paid.order.id).isEmpty())
        assertEquals(2, blocks().size)
    }

    @Test
    fun `autoBlockOnChargeback off writes no block row`(): Unit = runBlocking {
        cfg(autoBlock = false)

        val paid = r.place(steve(), listOf(RefundLine(1000)))

        dispute(paid, DisputeState.OPENED, "dp_nb")

        assertEquals(OrderStatus.CHARGEBACK, r.order(paid.order.id).status)
        assertTrue(blocks().isEmpty())
    }

    // ===== the block list at O11 and O12 (11 section 10, 19.8 cases 10 and 12) =====================================================

    @Test
    fun `O12 re-points the chargeback blocks to another order of the same buyer that is still OPEN, and removes them with the last one`(): Unit = runBlocking {
        val u = steve()
        val a = r.place(u, listOf(RefundLine(1000, actions = listOf(permission("a1", "group.vip")))), email = "Steve@Example.com")
        val b = r.place(u, listOf(RefundLine(1500, actions = listOf(permission("b1", "group.vip2")))), email = "Steve@Example.com")
        val all = setOf("PLAYER:steve", "USER:${u.id}", "EMAIL:steve@example.com")

        dispute(a, DisputeState.OPENED, "dp_a")
        dispute(b, DisputeState.OPENED, "dp_b")

        // the rows exist once and belong to the first chargeback: an existing row is left untouched
        assertEquals(all, blockKeys())
        assertTrue(blockRows().all { it.getLong("orderId") == a.order.id })
        assertNotNull(blockedNow(u, "steve@example.com"))

        dispute(a, DisputeState.WON, "dp_a")

        assertEquals(OrderStatus.COMPLETED, r.order(a.order.id).status)
        assertEquals(OrderStatus.CHARGEBACK, r.order(b.order.id).status)
        assertEquals(all, blockKeys(), "B is still disputed: the buyer stays blocked")
        assertTrue(blockRows().all { it.getLong("orderId") == b.order.id && it.getString("source") == "CHARGEBACK" })
        assertTrue(timeline(a.order.id).none { it.type == OrderEventType.BLOCK_REMOVED }, "nothing was removed, so nothing is said")
        assertNotNull(blockedNow(u, "steve@example.com"))

        dispute(b, DisputeState.WON, "dp_b")

        assertTrue(blockRows().isEmpty())
        assertEquals(setOf("PLAYER", "USER", "EMAIL"), blockEventTypes(b.order.id, OrderEventType.BLOCK_REMOVED))
        assertNull(blockedNow(u, "steve@example.com"))
    }

    @Test
    fun `O12 re-points only the rows whose subject the other open order shares and deletes the rest, matched by e-mail, by account and by recipient`(): Unit = runBlocking {
        // by e-mail alone: a guest order with the same address, another recipient
        val steve = steve()
        val byMail = r.place(steve, listOf(RefundLine(1000, actions = listOf(permission("a1", "group.vip")))), email = "shared@example.com")
        val guest = r.place(null, listOf(RefundLine(1000, actions = listOf(permission("g1", "group.g")))), email = "Shared@Example.com")

        dispute(byMail, DisputeState.OPENED, "dp_m1")
        dispute(guest, DisputeState.OPENED, "dp_m2")
        dispute(byMail, DisputeState.WON, "dp_m1")

        val afterMail = blockRows().associate { "${it.getString("type")}:${it.getString("value")}" to it.getLong("orderId") }

        assertEquals(setOf("PLAYER:guest", "EMAIL:shared@example.com"), afterMail.keys, "the PLAYER and USER rows of the first order are gone")
        assertEquals(guest.order.id, afterMail["EMAIL:shared@example.com"], "the e-mail row went to the order that shares it")
        assertEquals(setOf("PLAYER", "USER"), blockEventTypes(byMail.order.id, OrderEventType.BLOCK_REMOVED))

        // by account alone: the same account, a gift to another player under another address
        val alex = w.fixtures.user("Alex")
        val friend = w.fixtures.user("Friend")
        val own = r.place(alex, listOf(RefundLine(1000, actions = listOf(permission("o1", "group.o")))), email = "alex@example.com")
        val gift = r.place(alex, listOf(RefundLine(1000, actions = listOf(permission("o2", "group.o2")))), email = "friend@example.com")

        // Alex paid, Friend (another account) received
        MarketTestDb.sql(
            pool, "UPDATE `${MarketTestDb.TABLE_PREFIX}market_order` SET `recipientUsername` = 'Friend', `recipientUserId` = ?, `recipientKey` = ?, `isGift` = 1 WHERE `id` = ?",
            friend.id, "u:${friend.id}", gift.order.id
        )
        dispute(own, DisputeState.OPENED, "dp_u1")
        dispute(gift, DisputeState.OPENED, "dp_u2")
        dispute(own, DisputeState.WON, "dp_u1")

        val afterUser = blockRows().filter { it.getLong("orderId") == gift.order.id }.map { "${it.getString("type")}:${it.getString("value")}" }.toSet()

        assertTrue("USER:${alex.id}" in afterUser, "the account row went to the order of the same account")
        assertTrue("PLAYER:friend" in afterUser && "EMAIL:friend@example.com" in afterUser)
        assertTrue(blockKeys().none { it == "PLAYER:alex" || it == "EMAIL:alex@example.com" }, "another recipient and another address do not keep the first order's rows")
        assertEquals(setOf("PLAYER", "EMAIL"), blockEventTypes(own.order.id, OrderEventType.BLOCK_REMOVED))

        // by recipient alone: the same player name bought as a guest, another address
        val carl = w.fixtures.user("Carl")
        val first = r.place(carl, listOf(RefundLine(1000, actions = listOf(permission("c1", "group.c")))), email = "carl@example.com")
        val second = r.place(null, listOf(RefundLine(1000, actions = listOf(permission("c2", "group.c2")))), email = "other@example.com")

        MarketTestDb.sql(
            pool, "UPDATE `${MarketTestDb.TABLE_PREFIX}market_order` SET `playerUsername` = 'Someone', `recipientUsername` = 'CARL', `recipientKey` = 'g:carl', `isGift` = 1 WHERE `id` = ?", second.order.id
        )
        dispute(first, DisputeState.OPENED, "dp_r1")
        dispute(second, DisputeState.OPENED, "dp_r2")
        dispute(first, DisputeState.WON, "dp_r1")

        assertEquals(second.order.id, blockRows().single { it.getString("type") == "PLAYER" && it.getString("value") == "carl" }.getLong("orderId"), "the player row followed the recipient")
        assertTrue(blockKeys().none { it == "USER:${carl.id}" || it == "EMAIL:carl@example.com" })
    }

    @Test
    fun `O12 keeps the chargeback blocks for good while another order of the buyer is LOST, and a replayed WON does not move them`(): Unit = runBlocking {
        val u = steve()
        val a = r.place(u, listOf(RefundLine(1000, actions = listOf(permission("a1", "group.vip")))), email = "steve@example.com")
        val b = r.place(u, listOf(RefundLine(1500, actions = listOf(permission("b1", "group.vip2")))), email = "steve@example.com")

        dispute(a, DisputeState.OPENED, "dp_a")
        dispute(b, DisputeState.OPENED, "dp_b")
        dispute(b, DisputeState.LOST, "dp_b")

        assertEquals(DisputeStatus.LOST, r.order(b.order.id).disputeStatus)

        dispute(a, DisputeState.WON, "dp_a")

        assertEquals(3, blockRows().size)
        assertTrue(blockRows().all { it.getLong("orderId") == b.order.id && it.getString("source") == "CHARGEBACK" })
        assertNotNull(blockedNow(u, "steve@example.com"))

        // the lost order never gets its money back: a late WON for it is refused or ignored, the rows stay
        dispute(b, DisputeState.WON, "dp_b")
        dispute(a, DisputeState.WON, "dp_a")

        assertEquals(OrderStatus.CHARGEBACK, r.order(b.order.id).status)
        assertEquals(3, blockRows().size)
        assertTrue(blockRows().all { it.getLong("orderId") == b.order.id })
        assertNotNull(blockedNow(u, "steve@example.com"))
    }

    @Test
    fun `a manual block of the recipient is left untouched by O11 and survives O12, only the rows the chargeback made are removed`(): Unit = runBlocking {
        val u = steve()
        val future = w.clock.now() + 86_400_000L
        val player = manualBlock(BlockType.PLAYER, "steve")
        val mail = manualBlock(BlockType.EMAIL, "steve@example.com", expiresAt = future)
        val paid = r.place(u, listOf(RefundLine(1000, actions = listOf(permission("a1", "group.vip")))), email = "steve@example.com")
        val order = paid.order.id

        dispute(paid, DisputeState.OPENED, "dp_man")

        // only the missing subject got a row; the manual ones keep their source, order, reason, author and expiry
        for (id in listOf(player, mail)) {
            val row = w.blocks.getById(id, pool)!!

            assertEquals(BlockSource.MANUAL, row.source)
            assertNull(row.orderId)
            assertEquals("manual ban", row.reason)
            assertEquals(9L, row.createdBy)
        }

        assertNull(w.blocks.getById(player, pool)!!.expiresAt)
        assertEquals(future, w.blocks.getById(mail, pool)!!.expiresAt)
        assertEquals(setOf("USER"), blockEventTypes(order, OrderEventType.BLOCK_CREATED), "only the USER row was new")
        assertEquals(3, blockRows().size)
        assertEquals(listOf("USER"), blockRows().filter { it.getString("source") == "CHARGEBACK" }.map { it.getString("type") })

        dispute(paid, DisputeState.WON, "dp_man")

        assertEquals(setOf("PLAYER:steve", "EMAIL:steve@example.com"), blockKeys())
        assertTrue(blockRows().all { it.getString("source") == "MANUAL" && it.getLong("orderId") == null })
        assertEquals(setOf("USER"), blockEventTypes(order, OrderEventType.BLOCK_REMOVED))
    }

    @Test
    fun `an expired manual block of the recipient becomes this chargeback's active row at O11 and is deleted at O12, an unexpired one is left alone`(): Unit = runBlocking {
        val u = steve()
        val expired = manualBlock(BlockType.PLAYER, "steve", expiresAt = w.clock.now() - 1_000L)
        val live = manualBlock(BlockType.USER, u.id.toString(), expiresAt = w.clock.now() + 86_400_000L)
        val liveUntil = w.blocks.getById(live, pool)!!.expiresAt
        val paid = r.place(u, listOf(RefundLine(1000, actions = listOf(permission("a1", "group.vip")))), email = "steve@example.com")
        val order = paid.order.id

        // before the chargeback the expired row blocks nobody
        assertNull(blockedBy(BlockSubjects(usernames = setOf("Steve"))))

        dispute(paid, DisputeState.OPENED, "dp_exp")

        val revived = w.blocks.getById(expired, pool)!!

        assertEquals(BlockSource.CHARGEBACK, revived.source)
        assertEquals(order, revived.orderId)
        assertNull(revived.expiresAt, "it blocks now")
        assertNull(revived.createdBy)
        assertEquals("Chargeback on order #$order", revived.reason)
        assertEquals(BlockSource.MANUAL, w.blocks.getById(live, pool)!!.source, "an unexpired row is left untouched")
        assertNull(w.blocks.getById(live, pool)!!.orderId)
        assertEquals(liveUntil, w.blocks.getById(live, pool)!!.expiresAt)
        assertEquals(setOf("PLAYER", "EMAIL"), blockEventTypes(order, OrderEventType.BLOCK_CREATED), "the revived row counts as created, the live USER row does not")
        assertNotNull(blockedBy(BlockSubjects(usernames = setOf("Steve"))), "the player name is blocked by the revived row now")

        dispute(paid, DisputeState.WON, "dp_exp")

        assertNull(w.blocks.getById(expired, pool), "the chargeback's row is gone, it is not an expired manual row any more")
        assertNotNull(w.blocks.getById(live, pool))
        assertEquals(setOf("USER:${u.id}"), blockKeys())
        assertEquals(setOf("PLAYER", "EMAIL"), blockEventTypes(order, OrderEventType.BLOCK_REMOVED))
    }

    @Test
    fun `a guest order blocks the recipient and the e-mail only, no USER row and never an IP row`(): Unit = runBlocking {
        val paid = r.place(null, listOf(RefundLine(1000, actions = listOf(permission("a1", "group.vip")))), email = "guest@example.com")

        dispute(paid, DisputeState.OPENED, "dp_guest")

        assertEquals(setOf("PLAYER:guest", "EMAIL:guest@example.com"), blockKeys())
        assertEquals(setOf("PLAYER", "EMAIL"), blockEventTypes(paid.order.id, OrderEventType.BLOCK_CREATED))
        assertTrue(blockRows().none { it.getString("type") == "USER" || it.getString("type") == "IP" })

        // an order without an address blocks the player (and the account, when there is one) only
        val noMail = r.place(null, listOf(RefundLine(1000, actions = listOf(permission("b1", "group.vip2")))))

        MarketTestDb.sql(pool, "UPDATE `${MarketTestDb.TABLE_PREFIX}market_order` SET `recipientUsername` = 'Nomail', `recipientKey` = 'g:nomail' WHERE `id` = ?", noMail.order.id)
        dispute(noMail, DisputeState.OPENED, "dp_nomail")

        assertEquals(setOf("PLAYER:guest", "EMAIL:guest@example.com", "PLAYER:nomail"), blockKeys())
    }

    // ===== V-08: the guest gift =====================================================================================================

    @Test
    fun `V-08 a guest gift charged back blocks the recipient and the e-mail, never the payer's typed name, and holds the ban for confirmation`(): Unit = runBlocking {
        cfg(chargebackActions = actionsJson(ban()))
        r.d.roster.granted = listOf(7L)

        val paid = r.place(null, listOf(RefundLine(1000, actions = listOf(permission("a1", "group.vip")))), email = "attacker@example.com")

        // a guest who typed the name of someone else as payer and gifted to a name of their own
        MarketTestDb.sql(
            pool, "UPDATE `${MarketTestDb.TABLE_PREFIX}market_order` SET `playerUsername` = 'Victim', `recipientUsername` = 'Attacker', `recipientKey` = 'g:attacker', `isGift` = 1 WHERE `id` = ?",
            paid.order.id
        )

        dispute(paid, DisputeState.OPENED, "dp_g")

        val values = blocks().map { "${it.getString("type")}:${it.getString("value")}" }.toSet()

        assertEquals(setOf("PLAYER:attacker", "EMAIL:attacker@example.com"), values)
        assertFalse(values.any { it.contains("victim") })

        val held = r.d.rows(paid.order.id).filter { it.sourceType == DeliverySourceType.CHARGEBACK_ACTION }.single()

        assertEquals(DeliveryStatus.CANCELLED, held.status)
        assertEquals("NEEDS_CONFIRMATION", held.lastErrorCode)
        assertEquals("Attacker", held.playerUsername, "the recipient, where the goods went")
        assertTrue(alerts.any { it.first == paid.order.id && it.second == "CHARGEBACK_ACTIONS_HELD" })

        // the admin confirms: attempt group 1, live, once
        assertEquals(1, disputes.runChargebackActions(paid.order.id))

        val live = r.d.rows(paid.order.id).filter { it.sourceType == DeliverySourceType.CHARGEBACK_ACTION && it.attemptGroup == 1 }.single()

        assertEquals(DeliveryStatus.PENDING, live.status)
        assertEquals("Attacker", live.playerUsername)
        assertTrue(live.idempotencyKey.endsWith(":GRANT:1"))
        assertEquals(0, disputes.runChargebackActions(paid.order.id), "nothing is held any more")
    }

    // ===== V-09: top-up, spend, chargeback ==========================================================================================

    @Test
    fun `V-09 a charged-back top-up that was spent leaves a debt, writes the shortfall and revokes the credit-paid orders newest first`(): Unit = runBlocking {
        val u = steve()
        val topUp = r.place(u, listOf(RefundLine(10_000)), grant = false)
        val itemId = topUp.items[0].id

        MarketTestDb.sql(pool, "UPDATE `${MarketTestDb.TABLE_PREFIX}market_order_item` SET `creditAmount` = 10000 WHERE `id` = ?", itemId)
        w.db.tx { conn -> r.d.credits.creditOrderItems(w.orders.getById(topUp.order.id, conn)!!, w.orderItems.getByOrderIds(listOf(topUp.order.id), conn), conn) { true } }

        assertEquals(10_000, w.fixtures.creditBalance(u))

        // two orders paid with those credits, and the credits are gone
        val older = r.place(u, listOf(RefundLine(4_000, actions = listOf(permission("a1", "group.vip")))), creditValue = 4_000, credits = 4_000)
        val newer = r.place(u, listOf(RefundLine(6_000, actions = listOf(permission("b1", "group.vip2")))), creditValue = 6_000, credits = 6_000)
        val unrelated = r.place(u, listOf(RefundLine(500, actions = listOf(permission("c1", "group.vip3")))))

        w.db.tx { conn ->
            r.d.credits.lockAccounts(listOf(u.id), true, conn)
            r.d.credits.revoke(u.id, 10_000, "test:spend", null, "spent", conn)
        }

        assertEquals(0, w.fixtures.creditBalance(u))

        // `uncovered` is 100.00: revoking the newer order (60.00) is not enough, the older one (40.00) follows
        dispute(topUp, DisputeState.OPENED, "dp_t")

        val claw = ledger(u, CreditTxType.REVOKE).single { it.idempotencyKey.startsWith("dispute:") }

        assertEquals(10_000, claw.amount)
        assertEquals(0, claw.shortfall, "ALLOW_DEBT takes it all")
        assertTrue(claw.idempotencyKey.endsWith(":clawback:$itemId"))
        assertEquals(-10_000, w.fixtures.creditBalance(u), "the debt")

        val shortfall = timeline(topUp.order.id).single { it.type == OrderEventType.CLAWBACK_SHORTFALL }

        assertEquals(10_000, JsonObject(shortfall.data!!).getLong("uncovered"))

        for (spent in listOf(older, newer)) {
            assertEquals(EntitlementStatus.REVOKED, w.entitlements.getByOrderItemId(spent.items[0].id, pool).single().status, "order ${spent.order.id}")
            assertEquals(1, revokeRows(spent.order.id).size)
            assertEquals(OrderStatus.COMPLETED, r.order(spent.order.id).status, "no refund, no chargeback of its own")
            assertTrue(timeline(spent.order.id).any { it.message == "CREDIT_ORDER_REVOKED" })
        }

        assertEquals(EntitlementStatus.ACTIVE, w.entitlements.getByOrderItemId(unrelated.items[0].id, pool).single().status, "an order paid with money is not touched")
        assertEquals(0, w.refunds.getByOrderId(older.order.id, pool).size)

        val alert = alerts.single { it.second == "CLAWBACK_SHORTFALL" }

        assertEquals(setOf(older.order.id, newer.order.id), alert.third.getJsonArray("revoked").map { (it as Number).toLong() }.toSet())

        // a later hold fails until the debt is repaid
        assertThrows(InsufficientCredits::class.java) {
            runBlocking {
                w.db.tx { conn ->
                    r.d.credits.lockAccounts(listOf(u.id), true, conn)
                    r.d.credits.post(
                        Posting(CreditTxType.HOLD, "test:hold", u.id, 1, AccountRef.User(u.id), AccountRef.System(com.panomc.plugins.market.db.model.CreditSystemKey.HOLD), PostingPolicy.FAIL), conn
                    )
                }
            }
        }

        // a replayed event takes nothing more
        dispute(topUp, DisputeState.OPENED, "dp_t")

        assertEquals(-10_000, w.fixtures.creditBalance(u))
    }

    @Test
    fun `V-09 only the newest credit-paid orders that cover the shortfall are revoked, and none when revokeCreditOrdersOnTopUpChargeback is off`(): Unit = runBlocking {
        val u = steve()
        val topUp = r.place(u, listOf(RefundLine(10_000)), grant = false)

        MarketTestDb.sql(pool, "UPDATE `${MarketTestDb.TABLE_PREFIX}market_order_item` SET `creditAmount` = 10000 WHERE `id` = ?", topUp.items[0].id)
        w.db.tx { conn -> r.d.credits.creditOrderItems(w.orders.getById(topUp.order.id, conn)!!, w.orderItems.getByOrderIds(listOf(topUp.order.id), conn), conn) { true } }

        val older = r.place(u, listOf(RefundLine(4_000, actions = listOf(permission("a1", "group.vip")))), creditValue = 4_000, credits = 4_000)
        val newer = r.place(u, listOf(RefundLine(7_000, actions = listOf(permission("b1", "group.vip2")))), creditValue = 7_000, credits = 7_000)

        // only 30.00 of the 100.00 are gone: the shortfall is 30.00, which the newest order (70.00) covers alone
        w.db.tx { conn ->
            r.d.credits.lockAccounts(listOf(u.id), true, conn)
            r.d.credits.revoke(u.id, 10_000, "test:spend", null, "spent", conn)
            r.d.credits.grant(u.id, 7_000, "test:back", null, "returned", conn)
        }

        dispute(topUp, DisputeState.OPENED, "dp_t2")

        assertEquals(EntitlementStatus.REVOKED, w.entitlements.getByOrderItemId(newer.items[0].id, pool).single().status)
        assertEquals(EntitlementStatus.ACTIVE, w.entitlements.getByOrderItemId(older.items[0].id, pool).single().status)
        assertEquals(-3_000, w.fixtures.creditBalance(u))

        // the same with the switch off: debt and alert, nothing revoked
        cfg(revokeCreditOrders = false)

        val u2 = w.fixtures.user("Alex")
        val topUp2 = r.place(u2, listOf(RefundLine(10_000)), grant = false)

        MarketTestDb.sql(pool, "UPDATE `${MarketTestDb.TABLE_PREFIX}market_order_item` SET `creditAmount` = 10000 WHERE `id` = ?", topUp2.items[0].id)
        w.db.tx { conn -> r.d.credits.creditOrderItems(w.orders.getById(topUp2.order.id, conn)!!, w.orderItems.getByOrderIds(listOf(topUp2.order.id), conn), conn) { true } }

        val spent = r.place(u2, listOf(RefundLine(2_000, actions = listOf(permission("d1", "group.vip4")))), creditValue = 2_000, credits = 2_000)

        w.db.tx { conn ->
            r.d.credits.lockAccounts(listOf(u2.id), true, conn)
            r.d.credits.revoke(u2.id, 10_000, "test:spend2", null, "spent", conn)
        }

        dispute(topUp2, DisputeState.OPENED, "dp_t3")

        assertEquals(EntitlementStatus.ACTIVE, w.entitlements.getByOrderItemId(spent.items[0].id, pool).single().status)
        assertEquals(-10_000, w.fixtures.creditBalance(u2))
        assertEquals(listOf(spent.order.id), alerts.last { it.second == "CLAWBACK_SHORTFALL" }.third.getJsonArray("creditOrders").map { (it as Number).toLong() })
    }

    @Test
    fun `V-09 two charged-back top-ups, the second chargeback skips the order the first one revoked and takes the next one, so both credit-paid orders end revoked`(): Unit = runBlocking {
        val u = steve()
        val t1 = topUp(u)
        val t2 = topUp(u)
        val x = r.place(u, listOf(RefundLine(10_000, actions = listOf(permission("x1", "group.x")))), creditValue = 10_000, credits = 10_000)
        val y = r.place(u, listOf(RefundLine(10_000, actions = listOf(permission("y1", "group.y")))), creditValue = 10_000, credits = 10_000)

        spend(u, 20_000, "test:spend")

        assertEquals(0, w.fixtures.creditBalance(u))

        // the first chargeback: 100.00 uncovered, the newest credit-paid order (Y, 100.00) covers it
        dispute(t1, DisputeState.OPENED, "dp_t1")

        assertEquals(EntitlementStatus.REVOKED, entitlementOf(y).status)
        assertEquals(EntitlementStatus.ACTIVE, entitlementOf(x).status)
        assertEquals(-10_000, w.fixtures.creditBalance(u))
        assertEquals(listOf(y.order.id), alerts.single { it.second == "CLAWBACK_SHORTFALL" }.third.getJsonArray("revoked").map { (it as Number).toLong() })

        // the second: Y is still COMPLETED with creditAmount > 0 but has nothing left to give back, so it must not count: X is taken
        dispute(t2, DisputeState.OPENED, "dp_t2")

        assertEquals(EntitlementStatus.REVOKED, entitlementOf(x).status, "the buyer must not keep 100.00 of goods that the charged-back top-up paid for")
        assertEquals("CHARGEBACK", entitlementOf(x).endReason)
        assertEquals(EntitlementStatus.REVOKED, entitlementOf(y).status)
        assertEquals(-20_000, w.fixtures.creditBalance(u))
        assertEquals(1, revokeRows(x.order.id).size)
        assertEquals(1, revokeRows(y.order.id).size, "Y is not revoked a second time")
        assertEquals(1, timeline(y.order.id).count { it.message == "CREDIT_ORDER_REVOKED" })
        assertEquals(1, timeline(x.order.id).count { it.message == "CREDIT_ORDER_REVOKED" })

        val shortfalls = alerts.filter { it.second == "CLAWBACK_SHORTFALL" }

        assertEquals(2, shortfalls.size)
        assertEquals(listOf(x.order.id), shortfalls[1].third.getJsonArray("revoked").map { (it as Number).toLong() }, "the alert names what was revoked this time, not Y again")
        assertEquals(OrderStatus.COMPLETED, r.order(x.order.id).status)
        assertEquals(OrderStatus.COMPLETED, r.order(y.order.id).status)

        // a replay of either event changes nothing
        dispute(t1, DisputeState.OPENED, "dp_t1")
        dispute(t2, DisputeState.OPENED, "dp_t2")

        assertEquals(-20_000, w.fixtures.creditBalance(u))
        assertEquals(1, revokeRows(x.order.id).size)
        assertEquals(1, revokeRows(y.order.id).size)
    }

    @Test
    fun `V-09 a credit-paid order that was revoked by hand before does not cover the shortfall, the next older one is taken`(): Unit = runBlocking {
        val u = steve()
        val t = topUp(u)
        val older = r.place(u, listOf(RefundLine(4_000, actions = listOf(permission("a1", "group.vip")))), creditValue = 4_000, credits = 4_000)
        val newer = r.place(u, listOf(RefundLine(7_000, actions = listOf(permission("b1", "group.vip2")))), creditValue = 7_000, credits = 7_000)

        // only 30.00 of the 100.00 are gone: the shortfall is 30.00, which the newest order (70.00) would cover alone
        spend(u, 10_000, "test:spend")
        w.db.tx { conn -> r.d.credits.grant(u.id, 7_000, "test:back", null, "returned", conn) }

        // the admin already took the newer order's goods back
        w.db.tx { conn ->
            val order = w.orders.getById(newer.order.id, conn)!!
            val items = w.orderItems.getByOrderIds(listOf(newer.order.id), conn)

            r.d.entitlementService.revoke(conn, r.d.service, order, items, mapOf(items[0].id to null), "ADMIN")
        }

        assertEquals(EntitlementStatus.REVOKED, entitlementOf(newer).status)
        assertEquals(1, revokeRows(newer.order.id).size)

        dispute(t, DisputeState.OPENED, "dp_manual")

        assertEquals(EntitlementStatus.REVOKED, entitlementOf(older).status, "the older order is taken instead")
        assertEquals("CHARGEBACK", entitlementOf(older).endReason)
        assertEquals("ADMIN", entitlementOf(newer).endReason, "the manual revoke is left as it was")
        assertEquals(1, revokeRows(newer.order.id).size)
        assertEquals(1, revokeRows(older.order.id).size)
        assertTrue(timeline(newer.order.id).none { it.message == "CREDIT_ORDER_REVOKED" })
        assertTrue(timeline(older.order.id).any { it.message == "CREDIT_ORDER_REVOKED" })
        assertEquals(listOf(older.order.id), alerts.single { it.second == "CLAWBACK_SHORTFALL" }.third.getJsonArray("revoked").map { (it as Number).toLong() })
        assertEquals(-3_000, w.fixtures.creditBalance(u))
    }

    @Test
    fun `V-09 a partially refunded credit-paid order counts only the credits that did not go back to the ledger`(): Unit = runBlocking {
        val u = steve()
        val t = topUp(u)
        val older = r.place(u, listOf(RefundLine(0, actions = listOf(permission("a1", "group.vip")))), creditValue = 0, credits = 4_000)
        val newer = r.place(u, listOf(RefundLine(0, actions = listOf(permission("b1", "group.vip2")))), creditValue = 0, credits = 6_000)

        // 80.00 of the 100.00 are gone, then 30.00 of the newer order's credits are refunded back: the balance is 50.00
        spend(u, 8_000, "test:spend")
        r.service.request(newer.order.id, RefundInput(creditAmount = 3_000), r.key(), null)

        assertEquals(OrderStatus.PARTIALLY_REFUNDED, r.order(newer.order.id).status)
        assertEquals(3_000, r.order(newer.order.id).refundedCreditAmount)
        assertEquals(5_000, w.fixtures.creditBalance(u))
        assertEquals(EntitlementStatus.ACTIVE, entitlementOf(newer).status)

        // uncovered = 100.00 - 50.00 = 50.00: the newer order is worth 60.00 - 30.00 = 30.00 of that, so the older one (40.00) is needed too
        dispute(t, DisputeState.OPENED, "dp_partial")

        assertEquals(EntitlementStatus.REVOKED, entitlementOf(newer).status)
        assertEquals(EntitlementStatus.REVOKED, entitlementOf(older).status, "the refunded credits do not cover the shortfall a second time")
        assertEquals(listOf(newer.order.id, older.order.id), alerts.single { it.second == "CLAWBACK_SHORTFALL" }.third.getJsonArray("revoked").map { (it as Number).toLong() })
        assertEquals(-5_000, w.fixtures.creditBalance(u))
    }

    @Test
    fun `a charged-back top-up the buyer still holds is taken back without a shortfall, and the cashback of the order is reversed with the same debt policy`(): Unit = runBlocking {
        val u = steve()
        val paid = r.place(u, listOf(RefundLine(10_000)), grant = false)

        MarketTestDb.sql(pool, "UPDATE `${MarketTestDb.TABLE_PREFIX}market_order_item` SET `creditAmount` = 5000 WHERE `id` = ?", paid.items[0].id)
        w.db.tx { conn ->
            val order = w.orders.getById(paid.order.id, conn)!!
            val items = w.orderItems.getByOrderIds(listOf(paid.order.id), conn)

            r.d.credits.creditOrderItems(order, items, conn) { true }
            r.d.credits.cashback(order, items, com.panomc.plugins.market.core.credit.Cashback.Settings(true, 1000, 100), conn)
        }

        // 50.00 top-up + 10.00 cashback (10 % of 100.00)
        assertEquals(6_000, w.fixtures.creditBalance(u))

        dispute(paid, DisputeState.OPENED, "dp_c")

        val reversal = ledger(u, CreditTxType.CASHBACK_REVERSAL).single()

        assertTrue(reversal.idempotencyKey.startsWith("dispute:") && reversal.idempotencyKey.endsWith(":cashback"))
        assertEquals(1_000, reversal.amount)
        assertEquals(0, reversal.shortfall)
        assertTrue(timeline(paid.order.id).none { it.type == OrderEventType.CLAWBACK_SHORTFALL }, "the balance covered the top-up")
        assertEquals(0, w.fixtures.creditBalance(u))
        assertTrue(alerts.none { it.second == "CLAWBACK_SHORTFALL" })
    }

    @Test
    fun `the cashback of a charged-back order is reversed in full even when it was spent, the balance goes into debt`(): Unit = runBlocking {
        val u = steve()
        val paid = r.place(u, listOf(RefundLine(10_000, actions = listOf(permission("a1", "group.vip")))))

        w.db.tx { conn ->
            r.d.credits.cashback(
                w.orders.getById(paid.order.id, conn)!!, w.orderItems.getByOrderIds(listOf(paid.order.id), conn), com.panomc.plugins.market.core.credit.Cashback.Settings(true, 1000, 100), conn
            )
            r.d.credits.lockAccounts(listOf(u.id), true, conn)
            r.d.credits.revoke(u.id, 1_000, "test:spend", null, "spent", conn)
        }

        assertEquals(0, w.fixtures.creditBalance(u))

        dispute(paid, DisputeState.OPENED, "dp_cb")

        val reversal = ledger(u, CreditTxType.CASHBACK_REVERSAL).single()

        assertEquals(1_000, reversal.amount, "ALLOW_DEBT takes it all")
        assertEquals(0, reversal.shortfall)
        assertEquals(-1_000, w.fixtures.creditBalance(u))
    }

    // ===== the other effects of O11 =================================================================================================

    @Test
    fun `O11 hands the order to the subscription hook and cancels the refunds nobody sent, a refund in flight is left to settle`(): Unit = runBlocking {
        val paid = r.place(steve(), listOf(RefundLine(1000, actions = listOf(permission("a1", "group.vip")))))
        val waiting = r.service.request(paid.order.id, RefundInput(amount = 400, revokeFirst = true), r.key(), null).refund

        assertEquals(RefundStatus.REQUESTED, waiting.status)

        dispute(paid, DisputeState.OPENED, "dp_r")

        assertEquals(RefundStatus.CANCELLED, r.refund(waiting.id).status)
        assertEquals("CHARGEBACK", r.refund(waiting.id).failureCode)
        assertEquals(listOf(paid.order.id to disputeRows(paid.order.id).single().id), subscriptionCalls.toList())
        assertTrue(timeline(paid.order.id).any { it.message == "REFUNDS_CANCELLED_BY_CHARGEBACK" })

        // a charged-back order cannot be refunded any more
        r.expect("INVALID_ORDER_TRANSITION", 400) { r.service.request(paid.order.id, RefundInput(amount = 100), r.key(), null) }
    }

    @Test
    fun `WIRE-2 the buyer's other subscriptions are handed over once, after the commit of O11 only, never for an inquiry, a replay or a won dispute`(): Unit = runBlocking {
        val paid = r.place(steve(), listOf(RefundLine(1000)))

        dispute(paid, DisputeState.INQUIRY, "dp_w2")
        assertTrue(ownerCalls.isEmpty(), "an inquiry is not O11")

        dispute(paid, DisputeState.OPENED, "dp_w2")
        assertEquals(listOf(paid.order.id), ownerCalls.toList())
        assertEquals(OrderStatus.CHARGEBACK, r.order(paid.order.id).status, "the hook runs after the order moved and committed")

        dispute(paid, DisputeState.OPENED, "dp_w2")
        dispute(paid, DisputeState.WON, "dp_w2")
        assertEquals(listOf(paid.order.id), ownerCalls.toList(), "a replay and O12 hand nothing over")
    }

    @Test
    fun `WIRE-2 a failing hand-over is logged and never undoes the committed chargeback (the job sweep retries it, Wire2IT)`(): Unit = runBlocking {
        val paid = r.place(steve(), listOf(RefundLine(1000)))

        ownerHookFails = true
        dispute(paid, DisputeState.OPENED, "dp_w2f")

        assertEquals(listOf(paid.order.id), ownerCalls.toList())
        assertEquals(OrderStatus.CHARGEBACK, r.order(paid.order.id).status)
        assertEquals(1, disputeRows(paid.order.id).size)
        assertTrue(alerts.any { it.first == paid.order.id && it.second == "CHARGEBACK_OPENED" })
    }

    @Test
    fun `a dispute for an attempt of another order is ignored, a missing order stays untouched`(): Unit = runBlocking {
        val one = r.place(steve(), listOf(RefundLine(1000)))
        val two = r.place(steve(), listOf(RefundLine(1000)))

        dispute(one, DisputeState.OPENED, "dp_shared")
        dispute(two, DisputeState.OPENED, "dp_shared")

        assertEquals(OrderStatus.COMPLETED, r.order(two.order.id).status)
        assertTrue(disputeRows(two.order.id).isEmpty())
        assertEquals(OrderStatus.CHARGEBACK, r.order(one.order.id).status)
    }

    @Test
    fun `an id-less event with two open disputes is ambiguous, noted and alerted, nothing applied`(): Unit = runBlocking {
        val paid = r.place(steve(), listOf(RefundLine(1000)))

        dispute(paid, DisputeState.INQUIRY, "dp_1")
        dispute(paid, DisputeState.INQUIRY, "dp_2")
        dispute(paid, DisputeState.WON, id = null)

        assertTrue(disputeRows(paid.order.id).all { it.status == DisputeRecordStatus.INQUIRY })
        assertTrue(alerts.any { it.second == "DISPUTE_AMBIGUOUS" })
        assertTrue(timeline(paid.order.id).any { it.message == "DISPUTE_EVENT_AMBIGUOUS" })
    }

    @Test
    fun `an OPENED dispute for an order that is not paid is only recorded with an alert`(): Unit = runBlocking {
        val paid = r.place(steve(), listOf(RefundLine(1000)), grant = false)

        MarketTestDb.sql(pool, "UPDATE `${MarketTestDb.TABLE_PREFIX}market_order` SET `status` = 'CANCELLED', `reservationState` = 'RELEASED', `paidAt` = NULL WHERE `id` = ?", paid.order.id)
        MarketTestDb.sql(pool, "UPDATE `${MarketTestDb.TABLE_PREFIX}market_product` SET `soldCount` = 0 WHERE `id` = ?", paid.products[0].id)

        dispute(paid, DisputeState.OPENED, "dp_unpaid")

        assertEquals(OrderStatus.CANCELLED, r.order(paid.order.id).status)
        assertEquals(DisputeRecordStatus.OPEN, disputeRows(paid.order.id).single().status)
        assertTrue(alerts.any { it.second == "DISPUTE_ON_UNPAID_ORDER" })
        assertTrue(blocks().isEmpty())
    }

    // ===== RF-07 and the event routing ==============================================================================================

    private fun router(next: PaymentEventSink = PaymentEventSink.UNHANDLED) = PaymentEventRouter({ r.service }, { disputes }, next)

    private fun context() = InboundEventContext(1, "fake", "evt-${w.ids.uuid()}", null, w.clock.now())

    @Test
    fun `RF-07 an unsolicited refund event becomes a GATEWAY refund row and O10 through the routing of the pipeline`(): Unit = runBlocking {
        val paid = r.place(steve(), listOf(RefundLine(1000, actions = listOf(permission("a1", "group.vip")))))
        val event = PaymentEvent.RefundUpdated(PaymentTarget.Attempt(paid.attempt.id), RefundState.SUCCEEDED, Money(1000, "EUR"))

        event.gatewayRefundId = "re_1"

        router().apply(event, r.attempt(paid.attempt.id), context())

        val row = r.refunds(paid.order.id).single()

        assertEquals(RefundOrigin.GATEWAY, row.origin)
        assertEquals(RefundStatus.SUCCEEDED, row.status)
        assertEquals(OrderStatus.REFUNDED, r.order(paid.order.id).status)
        assertEquals(1000, r.order(paid.order.id).refundedTotal)
    }

    @Test
    fun `the router sends a dispute event to the dispute service and everything else to the next sink`(): Unit = runBlocking {
        val paid = r.place(steve(), listOf(RefundLine(1000, actions = listOf(permission("a1", "group.vip")))))
        val event = PaymentEvent.DisputeUpdated(PaymentTarget.Attempt(paid.attempt.id), DisputeState.OPENED)

        event.gatewayDisputeId = "dp_router"

        router().apply(event, r.attempt(paid.attempt.id), context())

        assertEquals(OrderStatus.CHARGEBACK, r.order(paid.order.id).status)

        // a dispute whose target is not an attempt cannot be placed on an order: it goes on, so that its request stays replayable
        val passed = CopyOnWriteArrayList<String>()
        val next = PaymentEventSink { e, _, _ -> passed += e.javaClass.simpleName }
        val subscriptionTarget = PaymentEvent.DisputeUpdated(PaymentTarget.Subscription("sub_1"), DisputeState.OPENED)

        router(next).apply(subscriptionTarget, null, context())
        router(next).apply(PaymentEvent.Cancelled(PaymentTarget.Attempt(paid.attempt.id)), r.attempt(paid.attempt.id), context())

        assertEquals(listOf("DisputeUpdated", "Cancelled"), passed.toList())
        assertThrows(EventNotHandled::class.java) { runBlocking { router().apply(subscriptionTarget, null, context()) } }
    }

    // ===== F-14: a duplicate payment ================================================================================================

    @Test
    fun `a duplicate paid attempt is refunded automatically when the provider can, the money never touches the books of the order`(): Unit = runBlocking {
        val paid = r.place(steve(), listOf(RefundLine(1000, actions = listOf(permission("a1", "group.vip")))))
        val now = w.clock.now()
        val duplicateId = w.payments.add(
            MarketPayment(
                orderId = paid.order.id, providerId = "fake", methodLabel = "Fake", status = PaymentStatus.SUCCEEDED, reference = "REFDUP000000000001", token = "%040x".format(991),
                amount = 1000, currency = "EUR", orderTotal = 1000, gatewayTransactionId = "txn-dup", paidAmount = 1000, paidCurrency = "EUR", paidAt = now, duplicate = true,
                createdAt = now, updatedAt = now
            ),
            pool
        )!!
        val redemptions = RedemptionService(w.clock, r.d.locks, w.redemptions)
        val orderService = OrderService(
            w.clock, w.ids, w.orders, w.orderItems, w.orderEvents, w.payments, redemptions, { _, _ -> false }, reservations = ReservationService(w.clock, r.d.locks, redemptions, w.orders),
            refunds = w.refunds, duplicates = DuplicateRefundPolicy { conn, providerId -> r.payments.duplicateRefundRule(conn, providerId) }
        )
        val rule = r.payments.duplicateRefundRule(pool, "fake")

        assertTrue(rule.autoRefund && rule.providerCanRefund)

        w.db.tx { conn -> orderService.onDuplicatePayment(conn, paid.order.id, duplicateId, rule.autoRefund, rule.providerCanRefund, ArrayList()) }

        val queued = r.refunds(paid.order.id).single()

        assertEquals(RefundOrigin.SYSTEM, queued.origin)
        assertEquals(RefundStatus.REQUESTED, queued.status)
        assertEquals(duplicateId, queued.paymentId)
        assertEquals("sys:dup:$duplicateId", queued.idempotencyKey)

        // the reconcile job of the refund service sends it with its own key
        assertEquals(1, r.service.reconcile().sent)

        val sent = r.refund(queued.id)

        assertEquals(RefundStatus.SUCCEEDED, sent.status)
        assertEquals(1000, r.attempt(duplicateId).refundedAmount, "the duplicate attempt gave the money back")
        assertEquals(0, r.attempt(paid.attempt.id).refundedAmount)

        val order = r.order(paid.order.id)

        assertEquals(0, order.refundedTotal, "no O10: it is not a refund of the order")
        assertEquals(OrderStatus.COMPLETED, order.status)
        assertEquals(EntitlementStatus.ACTIVE, w.entitlements.getByOrderItemId(paid.items[0].id, pool).single().status)
    }

    // ===== R-27: a dispute and a refund at the same time ===========================================================================

    @Test
    fun `R-27 a dispute and a full panel refund at the same moment end in one consistent state, REVOKE rows once per item`(): Unit = runBlocking {
        repeat(Race.rounds) { round ->
            val paid = r.place(steve(), listOf(RefundLine(1000, actions = listOf(permission("a$round", "group.vip$round")))), email = "steve$round@example.com")
            val outcomes = Race.run(2) { i ->
                if (i == 0) r.service.request(paid.order.id, RefundInput(), r.key("r27"), null) else dispute(paid, DisputeState.OPENED, "dp_r27_$round")
            }

            // the dispute always lands; the refund either was first, or is refused because the order is charged back
            assertTrue(outcomes[1].isSuccess, "round $round: the dispute ${outcomes[1].exceptionOrNull()}")
            outcomes[0].exceptionOrNull()?.let { assertTrue(it is InvalidOrderTransition || it is com.panomc.platform.model.Error, "round $round: the refund failed with $it") }

            val order = r.order(paid.order.id)

            assertEquals(OrderStatus.CHARGEBACK, order.status, "round $round")
            assertTrue(order.statusBeforeDispute in setOf(OrderStatus.COMPLETED, OrderStatus.REFUNDED, OrderStatus.PARTIALLY_REFUNDED), "round $round: ${order.statusBeforeDispute}")
            assertEquals(1, revokeRows(paid.order.id).size, "round $round: one REVOKE row for the one item")
            assertEquals(1, disputeRows(paid.order.id).size)
            assertEquals(EntitlementStatus.REVOKED, w.entitlements.getByOrderItemId(paid.items[0].id, pool).single().status)

            w.assertInvariants()
        }
    }
}
