package com.panomc.plugins.market.db.model

import com.panomc.platform.db.DBEntity
import com.panomc.plugins.market.util.MarketStatus

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
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) : DBEntity()
