package com.panomc.plugins.market.db.model

import com.panomc.platform.db.DBEntity

/** `market_shipping_rate.basis` (01 section 11.3). */
enum class ShippingRateBasis { FLAT, WEIGHT, AMOUNT, QUANTITY }

/**
 * `market_shipping_rate` (01 section 11.3): one rule row of a (method, zone). [rangeFrom] / [rangeTo] are inclusive
 * and in grams, minor units or items by [basis] (`rangeTo == null` = open); [price] and [perUnitPrice] are minor
 * units in the base currency.
 */
open class MarketShippingRate(
    val id: Long = -1,
    val methodId: Long = 0,
    val zoneId: Long = 0,
    val basis: ShippingRateBasis = ShippingRateBasis.FLAT,
    val rangeFrom: Long = 0,
    val rangeTo: Long? = null,
    val price: Long = 0,
    val perUnitPrice: Long = 0,
    val position: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) : DBEntity()
