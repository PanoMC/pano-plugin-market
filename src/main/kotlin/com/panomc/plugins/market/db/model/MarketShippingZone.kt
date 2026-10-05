package com.panomc.plugins.market.db.model

import com.panomc.platform.db.DBEntity

/**
 * `market_shipping_zone` (01 section 11.1). [countries] is a JSON list of ISO alpha-2 codes (`["*"]` = everywhere
 * else), [regions] `[{country, states:[...]}]` and [postalPatterns] a JSON list of prefixes (`"34*"`) or ranges
 * (`"1000-1999"`); the three are stored verbatim. Matching is by `position`.
 */
open class MarketShippingZone(
    val id: Long = -1,
    val name: String = "",
    val countries: String = "[]",
    val regions: String? = null,
    val postalPatterns: String? = null,
    val position: Int = 0,
    val status: String = "ACTIVE",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) : DBEntity()
