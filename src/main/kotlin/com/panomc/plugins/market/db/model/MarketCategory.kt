package com.panomc.plugins.market.db.model

import com.panomc.platform.db.DBEntity
import com.panomc.plugins.market.util.MarketStatus

/** `market_category.upgradeMode` (01 section 2.1): what the owner of a lower tier pays for a higher one. */
enum class UpgradeMode { DIFFERENCE, FULL }

open class MarketCategory(
    val id: Long = -1,
    val name: String = "",
    val description: String? = null,
    val icon: String = "fa-folder",
    val color: String = "#0d6efd",
    val status: MarketStatus = MarketStatus.ACTIVE,
    val parentId: Long? = null,
    val position: Int = 0,
    val imageFileName: String? = null,
    /** Products of the category form an upgrade ladder ordered by `tierRank` (01 section 2.1). */
    val tiered: Boolean = false,
    val upgradeMode: UpgradeMode = UpgradeMode.DIFFERENCE,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) : DBEntity()
