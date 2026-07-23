package com.panomc.plugins.market.db.model

import com.panomc.platform.annotation.Ignore
import com.panomc.platform.db.DBEntity
import com.panomc.plugins.market.util.MarketStatus
import com.panomc.plugins.market.util.ProductDurationType

open class MarketProduct(
    val id: Long = -1,
    val slug: String = "",
    val name: String = "",
    val description: String? = null,
    val categoryId: Long? = null,
    val price: Long = 0,
    val creditPrice: Long = 0,
    val stock: Int? = null,
    val requiredProducts: List<Long> = emptyList(),
    val requireOnlyOne: Boolean = false,
    val requiredPermission: String? = null,
    val status: MarketStatus = MarketStatus.ACTIVE,
    val featured: Boolean = false,
    val durationType: ProductDurationType = ProductDurationType.LIFETIME,
    val durationStart: Long? = null,
    val durationExpiry: Long? = null,
    val priority: Int = 0,
    val icon: String = "fa-box",
    val imageFileName: String? = null,
    val actions: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    // Resolved via a LEFT JOIN in getAllPaged only; @Ignore keeps it out of the column projection.
    @Ignore val categoryName: String? = null,
) : DBEntity()
