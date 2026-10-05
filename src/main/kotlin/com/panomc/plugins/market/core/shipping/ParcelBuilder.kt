package com.panomc.plugins.market.core.shipping

import com.panomc.plugins.market.spi.shipping.Parcel

/** Dimensions in mm of a default parcel (settings keys `defaultParcelLengthMm|WidthMm|HeightMm`, 10 section 8.1). */
class ParcelSize(val lengthMm: Int, val widthMm: Int, val heightMm: Int)

/** Parcels (10 section 5.5). Pure. */
object ParcelBuilder {
    /**
     * Exactly one parcel for the checkout: weight `max(1, sum)`; dimensions of the line with the largest volume among
     * lines that have all three, else [providerDefault], else [manualDefault], else none.
     * Throws [IllegalArgumentException] above [ShippableLines.MAX_WEIGHT_GRAMS] (callers check [ShippableLines.isTooHeavy]).
     */
    fun forCheckout(lines: List<ShippableLine>, providerDefault: ParcelSize? = null, manualDefault: ParcelSize? = null): Parcel {
        val weight = ShippableLines.weightGrams(lines)
        require(weight <= ShippableLines.MAX_WEIGHT_GRAMS) { "the cart is too heavy for a parcel: $weight g" }

        val largest = lines.filter { it.hasDimensions }.maxByOrNull { it.volume }  // first of equal volumes
        val size = largest?.let { ParcelSize(it.lengthMm!!, it.widthMm!!, it.heightMm!!) } ?: providerDefault ?: manualDefault

        return Parcel(maxOf(1L, weight).toInt(), size?.lengthMm, size?.widthMm, size?.heightMm)
    }

    /** A provider with `requiresDimensions` cannot quote a parcel without dimensions (its live rate is `null`). */
    fun hasDimensions(parcel: Parcel): Boolean = parcel.lengthMm != null && parcel.widthMm != null && parcel.heightMm != null
}
