package com.panomc.plugins.market.db.model

import com.panomc.platform.db.DBEntity

enum class ProductFieldType {
    TEXT, NUMBER, SELECT, CHECKBOX, USERNAME, EMAIL, DISCORD_ID
}

/** `market_product_field` (01 section 2.5): a custom field the buyer fills in; `{field.<fieldKey>}` in commands. */
open class MarketProductField(
    val id: Long = -1,
    val productId: Long = -1,
    val fieldKey: String = "",
    val label: String = "",
    val helpText: String? = null,
    val type: ProductFieldType = ProductFieldType.TEXT,
    val required: Boolean = false,
    /** JSON `[{value,label}]` for SELECT. */
    val options: String? = null,
    val pattern: String? = null,
    val minLength: Int? = null,
    val maxLength: Int? = null,
    val minValue: Long? = null,
    val maxValue: Long? = null,
    val placeholder: String? = null,
    val defaultValue: String? = null,
    val usableInCommands: Boolean = true,
    val position: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) : DBEntity()
