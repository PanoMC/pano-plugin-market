package com.panomc.plugins.market.service

import com.panomc.plugins.market.config.MarketConfig
import com.panomc.plugins.market.core.delivery.ActionParser
import com.panomc.plugins.market.core.delivery.Coverage
import com.panomc.plugins.market.db.model.DeliveryActionType
import com.panomc.plugins.market.core.delivery.DeliveryError
import com.panomc.plugins.market.core.money.Conversions
import com.panomc.plugins.market.core.pricing.PricePaid
import com.panomc.plugins.market.core.subscription.PeriodCalculator
import com.panomc.plugins.market.core.time.Clock
import com.panomc.plugins.market.db.dao.MarketEntitlementDao
import com.panomc.plugins.market.db.model.EntitlementStatus
import com.panomc.plugins.market.db.model.MarketEntitlement
import com.panomc.plugins.market.db.model.MarketOrder
import com.panomc.plugins.market.db.model.MarketOrderItem
import com.panomc.plugins.market.db.model.OrderItemKind
import com.panomc.plugins.market.db.model.PeriodUnit
import com.panomc.plugins.market.util.MoneyUtil
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple

/**
 * What a player owns (01 section 5.5, 08 section 10). At O2 / O4 ([onPaid]) every `PRODUCT`, `BUNDLE` and `BUNDLE_CHILD` line of the order gets
 * its own entitlement row (`orderItemId` is NN, so a refund of one purchase removes exactly its period). `CREDIT_TOPUP` lines own nothing.
 *
 * Timing (08 section 10.2, `billingMode = TIMED`): the chain is the owner's `ACTIVE` entitlements of the same `(ownerKey, productId, variantId)`
 * that have not run out. Chain empty => `[now, addPeriod(now))`; chain non-empty => the new link starts at the chain end (an extension on
 * repurchase, delivery phase `RENEW`, see [isExtension]). `ONE_TIME` products are permanent (`expiresAt = NULL`); a `SUBSCRIPTION` line carries
 * the order's `subscriptionId` and a NULL expiry, ended only by `SubscriptionService` (MK-121).
 *
 * `pricePaid` (05 section 5.2) is the base-currency value of one unit as paid plus the carried-over upgrade deduction.
 *
 * A tier upgrade (05 section 5.2, a line with `upgradeFromEntitlementId`) marks the entitlement it replaces `UPGRADED` (`replacedById` = the new one,
 * `endReason = UPGRADE`) in the same transaction, in both upgrade modes. Nothing is planned for the old tier: its grants stay as they are, the new tier's own
 * actions replace them (08 has no end flow for an upgrade), and `EntitlementExpiryJob` never ends an `UPGRADED` row.
 *
 * Ending (MK-107): [expire] is one step of `EntitlementExpiryJob` (08 section 10.3), [revoke] is the shared end flow of 08 section 11.1 (refund, chargeback,
 * manual revoke): rows cancelled / planned by [DeliveryService], entitlement `REVOKED`, later links of a timed chain pulled forward, coverage rule.
 * [liveSuccessor] follows the upgrade chain of 08 section 11.2. The caller holds the order lock; the chain of an owner and product is locked row by row
 * (`FOR UPDATE` on `idx_owner_product`, which also covers the gap), so two orders paid at the same instant for one timed product extend one after the other.
 */
class EntitlementService(
    private val clock: Clock,
    private val config: () -> MarketConfig,
    private val entitlements: MarketEntitlementDao
) {
    /** The entitlement of one order line; [extended] is true when it starts after the moment it was created (an extension of a timed chain). */
    class Granted(val item: MarketOrderItem, val entitlement: MarketEntitlement, val extended: Boolean)

    suspend fun onPaid(conn: SqlClient, order: MarketOrder, items: List<MarketOrderItem>): List<Granted> {
        val now = clock.now()
        val c = config()
        val zone = PeriodCalculator.zoneOf(c.storeTimeZone)
        val conversions = Conversions(
            order.baseCurrency.ifBlank { c.currency.name }, order.currency, order.fxRate, maxOf(1L, MoneyUtil.toMinor(c.creditValue)), c.removeCents,
            order.displayCurrency, order.displayRate
        )
        val owner = ownerOf(order)
        val granted = ArrayList<Granted>()

        for (item in items.sortedBy { it.id }) {
            val productId = item.productId ?: continue

            if (item.kind == OrderItemKind.CREDIT_TOPUP) continue

            // a replay of the effect (the same item twice) writes nothing new
            entitlements.getByOrderItemId(item.id, conn).firstOrNull()?.let {
                granted += Granted(item, it, isExtension(it))

                continue
            }

            val snapshot = item.snapshot?.takeIf { it.isNotBlank() }?.let { runCatching { JsonObject(it) }.getOrNull() } ?: JsonObject()
            val billing = snapshot.getString("billingMode") ?: "ONE_TIME"
            val variantId = item.variantId ?: 0L
            var startsAt = now
            var expiresAt: Long? = null
            var subscriptionId: Long? = null

            when (billing) {
                "TIMED" -> {
                    val unit = snapshot.getString("periodUnit")?.let { name -> PeriodUnit.entries.firstOrNull { it.name == name } }
                    val count = snapshot.getInteger("periodCount") ?: 0

                    if (unit != null && count >= 1) {
                        lockChain(conn, owner.key, productId)

                        val chainEnd = entitlements.getByOwnerAndProduct(owner.key, productId, conn)
                            .filter { it.status == EntitlementStatus.ACTIVE && it.variantId == variantId && it.subscriptionId == null }
                            .mapNotNull { it.expiresAt }
                            .filter { it > now }
                            .maxOrNull()

                        startsAt = chainEnd ?: now
                        expiresAt = PeriodCalculator(zone, unit, count).boundary(startsAt, 1)
                    }
                }

                "SUBSCRIPTION" -> if (item.kind != OrderItemKind.BUNDLE_CHILD) subscriptionId = order.subscriptionId
            }

            val id = entitlements.add(
                MarketEntitlement(
                    userId = owner.userId, playerUsername = owner.username, ownerKey = owner.key, productId = productId, variantId = variantId,
                    orderId = order.id, orderItemId = item.id, subscriptionId = subscriptionId, quantity = item.quantity, status = EntitlementStatus.ACTIVE,
                    startsAt = startsAt, expiresAt = expiresAt, tierCategoryId = snapshot.getLong("tierCategoryId"), tierRank = snapshot.getInteger("tierRank"),
                    pricePaid = pricePaid(order, item, conversions), createdAt = now, updatedAt = now
                ),
                conn
            )

            item.upgradeFromEntitlementId?.let { markUpgraded(conn, it, id, owner.key, now) }

            granted += Granted(item, entitlements.getById(id, conn) ?: error("entitlement $id was just inserted"), startsAt > now)
        }

        return granted
    }

    private fun table() = "`${entitlements.prefix()}market_entitlement`"

    /** Locks every entitlement row of one owner and product, in id order (and the gap behind them), before a chain is read. */
    private suspend fun lockChain(conn: SqlClient, ownerKey: String, productId: Long) {
        conn.preparedQuery("SELECT `id` FROM ${table()} WHERE `ownerKey` = ? AND `productId` = ? ORDER BY `id` FOR UPDATE").execute(Tuple.of(ownerKey, productId)).coAwait()
    }

    /**
     * The tier upgrade mark (05 section 5.2): the replaced entitlement of the same owner becomes `UPGRADED`. Only an `ACTIVE` row moves; one that ran out or was
     * revoked between the quote and the payment is left as it is (the buyer paid the difference that was quoted, its `pricePaid` is already in the new row).
     */
    private suspend fun markUpgraded(conn: SqlClient, oldId: Long, newId: Long, ownerKey: String, now: Long) {
        conn.preparedQuery(
            "UPDATE ${table()} SET `status` = 'UPGRADED', `replacedById` = ?, `endReason` = 'UPGRADE', `endedAt` = ?, `updatedAt` = ? " +
                "WHERE `id` = ? AND `ownerKey` = ? AND `status` = 'ACTIVE' AND `id` <> ?"
        ).execute(Tuple.of(newId, now, now, oldId, ownerKey, newId)).coAwait()
    }

    // ===== ending ownership ================================================================================================

    /** What [expire] did: `SKIPPED` (the row was not due any more), `CHAIN_CONTINUES` (ended without actions: a later link of the chain runs on), `ENDED`. */
    enum class ExpiryOutcome { SKIPPED, CHAIN_CONTINUES, ENDED }

    class Expired(val outcome: ExpiryOutcome, val entitlement: MarketEntitlement?, val plan: DeliveryService.EndPlan?)

    /**
     * One step of `EntitlementExpiryJob` (08 section 10.3), on the connection of a transaction that holds the order lock: `ACTIVE` -> `EXPIRED`
     * (`endReason = EXPIRED`) when the row is due and not owned by a subscription (0 rows: skip), then, unless another `ACTIVE` entitlement of the same
     * `(ownerKey, productId, variantId)` runs on past [now], the `EXPIRE` rows through [DeliveryService.planEnd]. The rows are created here, at expiry,
     * never at purchase.
     */
    suspend fun expire(conn: SqlConnection, delivery: DeliveryService, order: MarketOrder, items: List<MarketOrderItem>, entitlementId: Long): Expired {
        val now = clock.now()
        val moved = conn.preparedQuery(
            "UPDATE ${table()} SET `status` = 'EXPIRED', `endReason` = 'EXPIRED', `endedAt` = ?, `updatedAt` = ? " +
                "WHERE `id` = ? AND `status` = 'ACTIVE' AND `subscriptionId` IS NULL AND `expiresAt` IS NOT NULL AND `expiresAt` <= ?"
        ).execute(Tuple.of(now, now, entitlementId, now)).coAwait().rowCount()

        if (moved == 0) return Expired(ExpiryOutcome.SKIPPED, null, null)

        val ended = entitlements.getById(entitlementId, conn) ?: return Expired(ExpiryOutcome.SKIPPED, null, null)
        val continues = conn.preparedQuery(
            "SELECT COUNT(*) AS n FROM ${table()} WHERE `ownerKey` = ? AND `productId` = ? AND `variantId` = ? AND `status` = 'ACTIVE' AND `id` <> ? AND `expiresAt` > ?"
        ).execute(Tuple.of(ended.ownerKey, ended.productId, ended.variantId, ended.id, now)).coAwait().first().getLong("n") > 0

        if (continues) return Expired(ExpiryOutcome.CHAIN_CONTINUES, ended, null)

        val item = items.firstOrNull { it.id == ended.orderItemId } ?: return Expired(ExpiryOutcome.ENDED, ended, null)
        val parent = if (item.kind == OrderItemKind.BUNDLE_CHILD) items.firstOrNull { it.id == item.parentItemId } else null
        // a child of a bundle ends alone: its bundle line is only there for the target and the fields, and is switched off
        val scope = listOfNotNull(parent, item)
        val units = HashMap<Long, IntRange>().also { map ->
            parent?.let { map[it.id] = NO_UNITS }
            map[item.id] = 0 until item.quantity
        }
        val plan = delivery.planEnd(conn, order, scope, DeliveryError.ENTITLEMENT_ENDED, emptyMap(), units)

        return Expired(ExpiryOutcome.ENDED, ended, plan)
    }

    /** What [revoke] did: the end flow of the delivery engine, the entitlements it moved to `REVOKED`, and the coverage it found per item. */
    class Revoked(val plan: DeliveryService.EndPlan, val ended: List<Long>, val coverage: Map<Long, Coverage>, val units: Map<Long, IntRange>)

    /**
     * The shared end flow of 08 section 11.1 for a revoke (refund `revoke = 1`, chargeback, manual revoke), on the connection of a transaction that holds
     * the order lock. [targets] maps an order item id to the units to revoke; `null` means every not-yet-revoked unit (the manual revoke and a chargeback, 08
     * section 11.2). A bundle line takes its children with it (the range scaled by the child's quantity per bundle). [revokedBefore] are the units that
     * earlier revokes covered; items missing from it are derived ([revokedUnitsOf]).
     *
     * 1. [DeliveryService.planRevoke]: unsent rows covering the units are cancelled (D16), rows in flight get a cancel request (D17), then the `REVOKE` rows are
     *    planned (held by the predecessor gate until the in-flight rows resolved). A refund before a delayed delivery therefore cancels the delivery and
     *    needs no `REVOKE` row.
     * 2. The entitlement of a line whose units are all revoked now becomes `REVOKED` (`endReason` [endReason]: `REFUND`, `CHARGEBACK` or `ADMIN`); an
     *    `UPGRADED` one too, an `EXPIRED` one stays `EXPIRED`. A partial revoke leaves it `ACTIVE`.
     * 3. For a revoked link of a timed chain every later `ACTIVE` link of the same `(ownerKey, productId, variantId)` with `startsAt >= revoked.startsAt` moves
     *    forward by `removed = revoked.expiresAt - max(now, revoked.startsAt)` (never below 0).
     * 4. Coverage rule: when the owner still has an `ACTIVE` link of that chain with `startsAt <= now < expiresAt` after step 3, the plan of that line has no
     *    permission removal, command or webhook, but a permission `EXTEND` to the new chain end (`null` = permanent; credits are still reversed).
     *
     * The entitlements are changed before the rows are planned, because the coverage of step 4 is an input of the plan (08 section 11.1 lists it as step 5).
     */
    suspend fun revoke(
        conn: SqlConnection,
        delivery: DeliveryService,
        order: MarketOrder,
        items: List<MarketOrderItem>,
        targets: Map<Long, IntRange?>,
        endReason: String,
        reason: String = DeliveryError.ORDER_REVOKED,
        revokedBefore: Map<Long, Set<Int>> = emptyMap(),
        attemptGroup: Int = 0
    ): Revoked {
        val now = clock.now()
        val byId = items.associateBy { it.id }
        val gone = HashMap<Long, Set<Int>>()
        val units = LinkedHashMap<Long, IntRange>()

        fun clip(item: MarketOrderItem, range: IntRange): IntRange? =
            (maxOf(range.first, 0)..minOf(range.last, item.quantity - 1)).takeIf { !it.isEmpty() }

        for ((id, requested) in targets) {
            val item = byId[id] ?: throw NoSuchElementException("order item $id is not part of order ${order.id}")

            if (item.kind == OrderItemKind.CREDIT_TOPUP || item.kind == OrderItemKind.BUNDLE_CHILD && item.parentItemId in targets.keys) continue

            val before = revokedBefore[id] ?: revokedUnitsOf(conn, item)
            // "every not-yet-revoked unit": from the first unit that is not revoked yet (the revoked units grow from 0, 08 section 11.2)
            val range = clip(item, requested ?: ((0 until item.quantity).firstOrNull { it !in before } ?: continue).let { it until item.quantity }) ?: continue

            gone[id] = before
            units[id] = range

            if (item.kind == OrderItemKind.BUNDLE) {
                for (child in items.filter { it.parentItemId == id }) {
                    val n = if (item.quantity > 0) child.quantity / item.quantity else 1
                    val childRange = clip(child, (range.first * n)..((range.last + 1) * n - 1)) ?: continue
                    // units of the child that an earlier revoke (the child alone) already took back are never planned again (they would debit credits twice)
                    val goneChild = revokedBefore[child.id] ?: revokedUnitsOf(conn, child)
                    val first = childRange.firstOrNull { it !in goneChild } ?: continue

                    gone[child.id] = goneChild
                    units[child.id] = first..childRange.last
                }
            }
        }

        // the parent of a child that is revoked alone is only there for the target and the fields
        val planItems = LinkedHashMap<Long, MarketOrderItem>()

        for (id in units.keys) {
            val item = byId.getValue(id)

            item.parentItemId?.takeIf { it !in units }?.let { p -> byId[p]?.let { parent -> planItems[p] = parent; units[p] = NO_UNITS } }
            planItems[id] = item
        }

        val ended = ArrayList<Long>()
        val coverage = HashMap<Long, Coverage>()

        for ((id, range) in units.toMap()) {
            if (range.isEmpty()) continue

            val item = byId.getValue(id)
            val before = gone[id].orEmpty()
            val whole = (0 until item.quantity).all { it in before || it in range }

            if (!whole) continue

            for (e in entitlements.getByOrderItemId(id, conn)) {
                val moved = conn.preparedQuery(
                    "UPDATE ${table()} SET `status` = 'REVOKED', `endReason` = ?, `endedAt` = ?, `updatedAt` = ? WHERE `id` = ? AND `status` IN ('ACTIVE', 'UPGRADED')"
                ).execute(Tuple.of(endReason, now, now, e.id)).coAwait().rowCount() > 0

                if (moved) {
                    ended += e.id

                    if (e.status == EntitlementStatus.ACTIVE && e.subscriptionId == null && e.expiresAt != null) pullForward(conn, e, now)
                }

                // the coverage rule (08 section 11.1 step 5) depends on the owner still holding a covering ACTIVE link, not on the status of the ended
                // one: a link that expired with CHAIN_CONTINUES (no EXPIRE rows ran) and is revoked later must not lose the permission either
                if (billingOf(item) == "TIMED" || billingOf(item) == "SUBSCRIPTION") coveringChain(conn, e, now)?.let { coverage[id] = Coverage(e.id, it.chainEnd) }
            }
        }

        val plan = delivery.planRevoke(conn, order, planItems.values.toList(), units, gone, reason, coverage, attemptGroup)

        return Revoked(plan, ended, coverage, units)
    }

    /** `removed = revoked.expiresAt - max(now, revoked.startsAt)`: every later `ACTIVE` link of the chain moves forward by that much (08 section 11.1 step 4). */
    private suspend fun pullForward(conn: SqlConnection, revoked: MarketEntitlement, now: Long) {
        val removed = maxOf((revoked.expiresAt ?: return) - maxOf(now, revoked.startsAt), 0L)

        if (removed == 0L) return

        conn.preparedQuery(
            "SELECT `id` FROM ${table()} WHERE `ownerKey` = ? AND `productId` = ? AND `variantId` = ? AND `status` = 'ACTIVE' AND `id` <> ? " +
                "AND `subscriptionId` IS NULL AND `expiresAt` IS NOT NULL AND `startsAt` >= ? ORDER BY `id` FOR UPDATE"
        ).execute(Tuple.of(revoked.ownerKey, revoked.productId, revoked.variantId, revoked.id, revoked.startsAt)).coAwait()

        conn.preparedQuery(
            "UPDATE ${table()} SET `startsAt` = `startsAt` - ?, `expiresAt` = `expiresAt` - ?, `updatedAt` = ? WHERE `ownerKey` = ? AND `productId` = ? AND `variantId` = ? " +
                "AND `status` = 'ACTIVE' AND `id` <> ? AND `subscriptionId` IS NULL AND `expiresAt` IS NOT NULL AND `startsAt` >= ?"
        ).execute(Tuple.of(removed, removed, now, revoked.ownerKey, revoked.productId, revoked.variantId, revoked.id, revoked.startsAt)).coAwait()
    }

    private class Covering(val chainEnd: Long?)

    /** Step 4 of [revoke]: the chain end when an `ACTIVE` link of the chain covers [now] after [ended] was taken out, else `null`. A permanent link makes the end permanent. */
    private suspend fun coveringChain(conn: SqlConnection, ended: MarketEntitlement, now: Long): Covering? {
        val chain = entitlements.getByOwnerAndProduct(ended.ownerKey, ended.productId, conn)
            .filter { it.status == EntitlementStatus.ACTIVE && it.variantId == ended.variantId && it.id != ended.id }

        if (chain.none { it.startsAt <= now && (it.expiresAt == null || it.expiresAt > now) }) return null

        val end = if (chain.any { it.expiresAt == null }) null else chain.mapNotNull { it.expiresAt }.max()

        return Covering(end)
    }

    private fun billingOf(item: MarketOrderItem): String =
        item.snapshot?.takeIf { it.isNotBlank() }?.let { runCatching { JsonObject(it).getString("billingMode") }.getOrNull() } ?: "ONE_TIME"

    /**
     * The units of [item] that earlier revokes covered (08 section 11.2 "not-yet-revoked units" needs them): all of them when its entitlement is `REVOKED`;
     * otherwise the units of the non-cancelled `REVOKE` rows of a `perUnit` command / webhook action, and, when any other `REVOKE` row is alive (those rows
     * hold only the first unit of their range), the first `refundedQuantity` units (a refund revokes `refundedBefore until refundedBefore + n`). A caller
     * that knows the ranges passes them as `revokedBefore` instead.
     */
    suspend fun revokedUnitsOf(conn: SqlClient, item: MarketOrderItem): Set<Int> {
        if (entitlements.getByOrderItemId(item.id, conn).any { it.status == EntitlementStatus.REVOKED }) return (0 until item.quantity).toSet()

        val actions = ActionParser.parseStored(item.snapshot?.takeIf { it.isNotBlank() }?.let { runCatching { JsonObject(it).getJsonArray("actions")?.encode() }.getOrNull() })
            .actions.associateBy { it.id }
        val rows = conn.preparedQuery(
            "SELECT `actionId`, `actionType`, `unitIndex` FROM `${entitlements.prefix()}market_delivery` WHERE `orderItemId` = ? AND `phase` = 'REVOKE' AND `status` <> 'CANCELLED'"
        ).execute(Tuple.of(item.id)).coAwait()
        val out = HashSet<Int>()
        var whole = false

        for (row in rows) {
            val type = row.getString("actionType")
            val perUnit = actions[row.getString("actionId")]?.perUnit == true && (type == DeliveryActionType.COMMAND.name || type == DeliveryActionType.WEBHOOK.name)

            if (perUnit) out += row.getInteger("unitIndex") else whole = true
        }

        if (whole) out += (0 until minOf(item.refundedQuantity, item.quantity))

        return out.filter { it in 0 until item.quantity }.toSet()
    }

    /**
     * The live successor of an upgraded entitlement (08 section 11.2, 21 section 5.4): `replacedById` is followed from row to row (1 to 2 to 3) until a row has
     * none. A row that a revoke turned from `UPGRADED` to `REVOKED` keeps its link, so the chain can still be walked after it.
     */
    suspend fun liveSuccessor(conn: SqlClient, entitlement: MarketEntitlement): MarketEntitlement? {
        var current = entitlement
        var hops = 0

        while (current.replacedById != null && hops++ < MAX_UPGRADE_HOPS) {
            current = current.replacedById?.let { entitlements.getById(it, conn) } ?: return null
        }

        return current.takeIf { it.id != entitlement.id }
    }

    private class Owner(val userId: Long?, val username: String, val key: String)

    private fun ownerOf(order: MarketOrder): Owner {
        val username = order.recipientUsername.ifBlank { order.playerUsername }
        val key = order.recipientKey.ifBlank { order.recipientUserId?.let { "u:$it" } ?: "g:${username.lowercase()}" }

        return Owner(order.recipientUserId, username, key)
    }

    /** 05 section 5.2: base-currency value of one unit, credit mode (a full-credit order) from the credit run's line total. */
    private fun pricePaid(order: MarketOrder, item: MarketOrderItem, conversions: Conversions): Long {
        val quantity = maxOf(item.quantity, 1)
        val upgradePerUnit = item.upgradeAmount
        val creditLine = CreditRunSnapshot.read(item.snapshot)

        if (order.creditAmount > 0 && order.gatewayAmount == 0L && creditLine != null) return PricePaid.creditOrder(conversions, creditLine, quantity, upgradePerUnit)

        val basis = if (order.pricesIncludeVat) item.lineTotal else item.lineTotal - item.vatAmount

        return PricePaid.moneyOrder(conversions, maxOf(basis, 0L), quantity, upgradePerUnit)
    }

    companion object {
        private val NO_UNITS = 1..0
        private const val MAX_UPGRADE_HOPS = 32

        /**
         * An extension of a timed chain starts at the chain end, after the instant [onPaid] created it; every other entitlement starts at that
         * instant (`startsAt == createdAt`). The delivery phase of the line follows: `RENEW` for an extension, `GRANT` otherwise (08 section 10.2).
         */
        fun isExtension(entitlement: MarketEntitlement): Boolean = entitlement.startsAt > entitlement.createdAt
    }
}
