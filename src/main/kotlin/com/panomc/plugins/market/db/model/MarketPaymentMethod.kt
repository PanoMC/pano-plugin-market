package com.panomc.plugins.market.db.model

import com.panomc.platform.db.DBEntity

// `settings` is a raw JSON object string (may hold gateway secrets) — kept typed String so Gson
// row-deserialization maps the column verbatim; endpoints encode/decode it with JsonObject.
open class MarketPaymentMethod(
    val id: Long = -1,
    val methodId: String = "",
    val enabled: Boolean = false,
    val settings: String = "{}",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) : DBEntity()
