package com.panomc.plugins.market.service

import com.panomc.plugins.market.db.dao.MarketOrderDao
import com.panomc.plugins.market.db.dao.MarketOrderItemDao
import com.panomc.plugins.market.db.model.MarketOrder
import com.panomc.plugins.market.db.model.MarketOrderItem
import com.panomc.plugins.market.db.model.OrderSource
import com.panomc.plugins.market.util.OrderStatus
import io.vertx.sqlclient.SqlClient

/**
 * What runs after the transaction that stamped an order paid committed (`AfterCommit.OrderPaid`) to tell the game servers about the purchase (08 section 8.1,
 * 19 section 9: "filled at O2 (after commit)"). Best effort: a failure is logged by the caller and never touches the order.
 */
fun interface PaidOrderAnnouncer {
    suspend fun paid(orderId: Long, client: SqlClient)

    companion object {
        val NONE = PaidOrderAnnouncer { _, _ -> }
    }
}

/**
 * [PaidOrderAnnouncer] over [McSyncService.announce] (WIRE-3, the open seam of MK-103). Only a `STOREFRONT` order that is `COMPLETED` is announced: an in-game purchase
 * announces itself ([McGameService], which also knows a replay), a renewal is no purchase of the player, a panel order, a gift code redemption and an external order
 * have no buyer who chose to be announced. The rest of the filter (hidden, test mode, no product line, `mcBroadcast`, ready servers) is [McSyncService.announce]'s.
 */
class PurchaseAnnouncements(
    private val orders: MarketOrderDao,
    private val items: MarketOrderItemDao,
    private val announce: suspend (MarketOrder, List<MarketOrderItem>) -> Int
) : PaidOrderAnnouncer {
    override suspend fun paid(orderId: Long, client: SqlClient) {
        val order = orders.getById(orderId, client) ?: return

        if (order.source != OrderSource.STOREFRONT || order.status != OrderStatus.COMPLETED) return

        announce(order, items.getByOrderIds(listOf(orderId), client))
    }
}
