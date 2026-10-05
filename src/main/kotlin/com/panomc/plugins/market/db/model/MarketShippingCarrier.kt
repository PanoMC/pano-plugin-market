package com.panomc.plugins.market.db.model

import com.panomc.platform.db.DBEntity

/**
 * `market_shipping_carrier` (01 section 11.4): the configuration of one shipping provider, one row per provider id
 * (`uq_provider`). [settings] is stored encrypted by the caller (kept verbatim); [webhookToken] is the random token
 * in the inbound path.
 */
open class MarketShippingCarrier(
    val id: Long = -1,
    val providerId: String = "",
    val enabled: Boolean = false,
    val settings: String? = null,
    val testMode: Boolean = false,
    val webhookToken: String = "",
    val lastInboundAt: Long? = null,
    val lastError: String? = null,
    val lastErrorAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) : DBEntity()
