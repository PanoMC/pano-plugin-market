package com.panomc.plugins.market.core.order

/**
 * The address properties a buyer must give, per country, before any provider adds its own (10 section 3.3). Served as
 * `addressFields` of `GET /api/market/checkout/config`: the key `"*"` is the entry of every country without its own.
 */
object AddressFieldSets {
    const val DEFAULT_KEY = "*"

    private val DEFAULT = listOf("firstName", "lastName", "phone", "country", "city", "line1", "postalCode")

    /** Needs a state or province besides the default set. */
    private val WITH_STATE = setOf("US", "CA", "AU", "BR", "MX", "IN")

    /** Countries without a postal code system (or where it is not used). */
    private val NO_POSTAL_CODE = setOf("AE", "HK", "MO", "QA", "PA", "BS", "JM", "FJ", "GH", "KE", "UG", "AO", "BW", "BZ", "ZW", "CI")

    private val TURKEY = listOf("firstName", "lastName", "phone", "country", "city", "district", "line1")

    fun forCountry(country: String): List<String> = when {
        country == "TR" -> TURKEY
        country in WITH_STATE -> DEFAULT + "state"
        country in NO_POSTAL_CODE -> DEFAULT - "postalCode"
        else -> DEFAULT
    }

    /** `{"*": default, "<country>": set, ...}` with the countries that deviate from the default, sorted by code. */
    fun asMap(): Map<String, List<String>> {
        val map = LinkedHashMap<String, List<String>>()
        map[DEFAULT_KEY] = DEFAULT

        (listOf("TR") + WITH_STATE + NO_POSTAL_CODE).sorted().forEach { map[it] = forCountry(it) }

        return map
    }
}
