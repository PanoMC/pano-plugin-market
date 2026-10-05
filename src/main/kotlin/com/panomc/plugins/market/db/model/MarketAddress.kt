package com.panomc.plugins.market.db.model

import com.panomc.platform.db.DBEntity

/** `market_address` (01 section 5.6): a saved address of a user; the billing-only fields stay `null` on a shipping address. */
open class MarketAddress(
    val id: Long = -1,
    val userId: Long = -1,
    val label: String? = null,
    val isDefault: Boolean = false,
    val firstName: String? = null,
    val lastName: String? = null,
    val company: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val country: String? = null,
    val state: String? = null,
    val city: String? = null,
    val district: String? = null,
    val neighborhood: String? = null,
    val line1: String? = null,
    val line2: String? = null,
    val postalCode: String? = null,
    val identityNumber: String? = null,
    val type: String? = null,
    val taxOffice: String? = null,
    val taxNumber: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) : DBEntity()
