package com.panomc.plugins.market.permission

import com.panomc.platform.auth.Permission

/**
 * The seven granular nodes of 04 section 9 (the umbrella [ManageMarketPermission] is separate: it is added to every
 * check). The short names are the ones the endpoint matrix of 11 section 14.3 uses.
 */
enum class MarketNode(val shortName: String, private val factory: () -> Permission) {
    CATALOG("CAT", { ManageMarketCatalogPermission() }),
    ORDERS_VIEW("OV", { ViewMarketOrdersPermission() }),
    ORDERS_MANAGE("OM", { ManageMarketOrdersPermission() }),
    PAYMENTS("PAY", { ManageMarketPaymentsPermission() }),
    DISCOUNTS("DISC", { ManageMarketDiscountsPermission() }),
    SETTINGS("SET", { ManageMarketSettingsPermission() }),
    STATS("STATS", { ViewMarketStatsPermission() });

    val permission: Permission get() = factory()

    companion object {
        /** All eight permission classes, the umbrella last. */
        val allPermissionClasses: List<Class<out Permission>> = listOf(
            ManageMarketCatalogPermission::class.java,
            ViewMarketOrdersPermission::class.java,
            ManageMarketOrdersPermission::class.java,
            ManageMarketPaymentsPermission::class.java,
            ManageMarketDiscountsPermission::class.java,
            ManageMarketSettingsPermission::class.java,
            ViewMarketStatsPermission::class.java,
            ManageMarketPermission::class.java
        )
    }
}
