package com.panomc.plugins.market.service

import com.panomc.plugins.market.config.MarketConfig
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
import io.vertx.sqlclient.SqlClient

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
 * Not here (MK-107): the expiry job, the end flows (status changes, pull-forward of later links, coverage), and the `UPGRADED` mark of the
 * entitlement a tier upgrade replaces. The caller holds the order lock; the chain read is not locked (two paid orders of one owner for one timed
 * product at the same instant may both start "now"; the later renewal order extends from the end of the longer chain).
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

            granted += Granted(item, entitlements.getById(id, conn) ?: error("entitlement $id was just inserted"), startsAt > now)
        }

        return granted
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
        /**
         * An extension of a timed chain starts at the chain end, after the instant [onPaid] created it; every other entitlement starts at that
         * instant (`startsAt == createdAt`). The delivery phase of the line follows: `RENEW` for an extension, `GRANT` otherwise (08 section 10.2).
         */
        fun isExtension(entitlement: MarketEntitlement): Boolean = entitlement.startsAt > entitlement.createdAt
    }
}
