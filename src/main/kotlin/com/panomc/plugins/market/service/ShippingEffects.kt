package com.panomc.plugins.market.service

import com.panomc.plugins.market.core.order.OrderEffect
import com.panomc.plugins.market.db.tx.LockedOrder
import io.vertx.sqlclient.SqlConnection

/**
 * The shipping side of the order transitions (WIRE-1, 06 section 11 `StartShipping`, 10 section 7.3): O2 and O4 hand `StartShipping` to
 * [ShippingService.startShipping] inside their transaction, every other effect goes to [next]. [shipping] is a provider so the order service can be
 * built before the shipping service exists (the composition root passes a lazy lookup).
 */
class ShippingEffects(
    private val shipping: () -> ShippingService,
    private val next: ForeignEffects = ForeignEffects.PENDING_SLICES
) : ForeignEffects {
    override suspend fun apply(conn: SqlConnection, locked: LockedOrder, effect: OrderEffect) {
        if (effect !is OrderEffect.StartShipping) {
            next.apply(conn, locked, effect)

            return
        }

        shipping().startShipping(conn, locked.order.id)
    }
}
