package com.panomc.plugins.market.db.model

import com.panomc.platform.db.DBEntity

/** `market_sequence` (01 section 6.7): a named counter; rows named `fixup:<id>` mark completed one-shot fixups. */
open class MarketSequence(
    val id: Long = -1,
    val name: String = "",
    val value: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) : DBEntity()
