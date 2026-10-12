package com.panomc.plugins.market.feature

import java.util.Properties

/**
 * A part of the market that a release can ship switched off. The decision is ours as developers and is made when the
 * jar is built (`marketFeature.<key>` in `gradle.properties`, written to [MarketFeatures.RESOURCE]); a site owner has
 * no setting for it. A feature that is off keeps its code, its tables and its rows: its routes answer 404, its jobs do
 * not run and nothing of it is listed.
 *
 * @property key the name in `gradle.properties`, in the resource and in `GET /context` (`features.<key>`).
 * @property requires a feature this one cannot be on without.
 */
enum class MarketFeature(val key: String, val requires: MarketFeature? = null) {
    /** Physical products: shipping methods, zones, the address book, shipments. */
    PHYSICAL_SHIPPING("physicalShipping"),

    /** Carrier plugins on top of shipping: labels, tracking, carrier webhooks. */
    SHIPPING_CARRIERS("shippingCarriers", PHYSICAL_SHIPPING)
}

/** Which [MarketFeature]s this build has on. A key the resource does not name is on. */
object MarketFeatures {
    const val RESOURCE = "/market-features.properties"

    /** `-Dmarket.feature.<key>=true|false` wins over the resource: the test tasks run with every feature on. */
    const val SYSTEM_PROPERTY_PREFIX = "market.feature."

    private val built: Map<MarketFeature, Boolean> by lazy { read() }

    @Volatile
    internal var overrides: Map<MarketFeature, Boolean> = emptyMap()

    fun enabled(feature: MarketFeature): Boolean {
        val own = overrides[feature] ?: built[feature] ?: true

        return own && (feature.requires?.let { enabled(it) } ?: true)
    }

    /** `{ physicalShipping: false, ... }` for `GET /context`. */
    fun asMap(): Map<String, Boolean> = MarketFeature.entries.associate { it.key to enabled(it) }

    /** Runs [block] with [values] in place of the build's; for tests. */
    internal inline fun <T> with(vararg values: Pair<MarketFeature, Boolean>, block: () -> T): T {
        val before = overrides

        overrides = before + values

        try {
            return block()
        } finally {
            overrides = before
        }
    }

    internal fun parse(properties: Properties, system: (String) -> String? = System::getProperty): Map<MarketFeature, Boolean> =
        MarketFeature.entries.mapNotNull { feature ->
            val value = system(SYSTEM_PROPERTY_PREFIX + feature.key) ?: properties.getProperty(feature.key)

            value?.let { feature to it.trim().equals("true", ignoreCase = true) }
        }.toMap()

    private fun read(): Map<MarketFeature, Boolean> {
        val properties = Properties()

        MarketFeatures::class.java.getResourceAsStream(RESOURCE)?.use { properties.load(it) }

        return parse(properties)
    }
}
