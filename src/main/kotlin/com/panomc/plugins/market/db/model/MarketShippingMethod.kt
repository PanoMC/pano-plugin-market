package com.panomc.plugins.market.db.model

import com.panomc.platform.db.DBEntity

/** `market_shipping_method.rateSource` (01 section 11.2). */
enum class ShippingRateSource { RULES, CARRIER, CARRIER_WITH_FALLBACK }

/**
 * `market_shipping_method` (01 section 11.2). Money columns are minor units (x100) in the base currency, price basis;
 * [vatPercent] is basis points (`null` = global VAT). [settings] is JSON. A method is retired with [deletedAt]
 * (soft delete), never removed.
 */
open class MarketShippingMethod(
    val id: Long = -1,
    val name: String = "",
    val description: String? = null,
    val providerId: String = "manual",
    val serviceCode: String? = null,
    val rateSource: ShippingRateSource = ShippingRateSource.RULES,
    val freeShippingThreshold: Long? = null,
    val handlingFee: Long = 0,
    val vatPercent: Long? = null,
    val minDeliveryDays: Int? = null,
    val maxDeliveryDays: Int? = null,
    val maxWeightGrams: Int? = null,
    val carrierName: String? = null,
    val trackingUrlTemplate: String? = null,
    val settings: String? = null,
    val position: Int = 0,
    val status: String = "ACTIVE",
    val deletedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) : DBEntity()
