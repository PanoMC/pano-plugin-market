package com.panomc.plugins.market.db.dao

import com.panomc.plugins.market.db.MarketDao
import com.panomc.plugins.market.db.model.*
import io.vertx.sqlclient.SqlClient

/** Raw provider traffic (01 section 6.3). */
abstract class MarketPaymentEventDao : MarketDao<MarketPaymentEvent>(MarketPaymentEvent::class.java) {
    /** The new id, or `null` on a unique-key duplicate. */
    abstract suspend fun add(paymentEvent: MarketPaymentEvent, sqlClient: SqlClient): Long?

    abstract suspend fun getById(id: Long, sqlClient: SqlClient): MarketPaymentEvent?

    /** The event stored under this key (`uq_event`). */
    abstract suspend fun getByEventKey(providerId: String, direction: PaymentEventDirection, eventKey: String, sqlClient: SqlClient): MarketPaymentEvent?

    /** Every event resolved to a payment, oldest first (`idx_payment`). */
    abstract suspend fun getByPaymentId(paymentId: Long, sqlClient: SqlClient): List<MarketPaymentEvent>
}
