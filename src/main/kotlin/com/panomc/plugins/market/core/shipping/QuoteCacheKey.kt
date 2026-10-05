package com.panomc.plugins.market.core.shipping

import com.panomc.plugins.market.spi.common.Address
import com.panomc.plugins.market.spi.shipping.Parcel
import java.security.MessageDigest

/** The key of the live-rate cache (10 section 5.4): SHA-256 over the request's price-relevant parts. Pure. */
object QuoteCacheKey {
    /**
     * `providerId | testMode | carrier.updatedAt | serviceCode ?: "*" | from(country, postalCode, city, district) |
     * to(country, state, city, district, postalCode) | parcels(weight, l, w, h) | orderValue.amount | currency`.
     * Address parts are compared by [ZoneMatcher.norm] (postal codes without spaces and hyphens), so case and
     * spacing do not change the key; every part is length-prefixed so that no two requests share a preimage.
     */
    fun of(
        providerId: String,
        testMode: Boolean,
        carrierUpdatedAt: Long,
        serviceCode: String?,
        from: Address?,
        to: Address,
        parcels: List<Parcel>,
        orderValue: Long,
        currency: String
    ): String {
        val parts = ArrayList<String>()
        parts += providerId
        parts += testMode.toString()
        parts += carrierUpdatedAt.toString()
        parts += serviceCode ?: "*"
        parts += if (from == null) "-" else listOf(from.country, from.postalCode.postal(), from.city, from.district).joinToString("\u001f") { it.n() }
        parts += listOf(to.country, to.state, to.city, to.district, to.postalCode.postal()).joinToString("\u001f") { it.n() }
        parts += parcels.joinToString(";") { "${it.weightGrams},${it.lengthMm ?: ""},${it.widthMm ?: ""},${it.heightMm ?: ""}" }
        parts += orderValue.toString()
        parts += currency

        val preimage = parts.joinToString("|") { "${it.length}:$it" }

        return MessageDigest.getInstance("SHA-256").digest(preimage.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun String?.postal(): String? = this?.replace(" ", "")?.replace("-", "")

    private fun String?.n(): String = if (this == null) "" else ZoneMatcher.norm(this)
}
