package com.panomc.plugins.market.db

/**
 * Table prefix seam (17 section 4 S5). The platform's `Dao.getTablePrefix()` is final and reads
 * `Main.applicationContext`, which does not exist in a test JVM. Production leaves [prefixOverride] `null` (the
 * platform prefix applies); database tests set it to `"pano_"` before any DAO call.
 */
object MarketTables {
    @Volatile
    var prefixOverride: String? = null
}
