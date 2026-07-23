package com.panomc.plugins.market.db.model

import com.panomc.platform.db.DBEntity
import com.panomc.plugins.market.util.MarketStatus

// productIds / features / cellValues are stored as raw JSON strings and round-tripped verbatim
// (productIds may contain null slots, feature ids are client-generated) — never typed collections.
open class MarketComparison(
    val id: Long = -1,
    val name: String = "",
    val status: MarketStatus = MarketStatus.ACTIVE,
    val priority: Int = 0,
    val productIds: String = "[]",
    val features: String = "[]",
    val cellValues: String = "{}",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) : DBEntity()
