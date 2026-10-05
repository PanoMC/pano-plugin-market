package com.panomc.plugins.market.db

import com.panomc.platform.db.DBEntity
import com.panomc.platform.db.Dao

/**
 * Base class of every market DAO implementation (17 section 4 S5). Implementations call [prefix] and never
 * `getTablePrefix()` directly, so the same code runs against a throwaway test database.
 */
abstract class MarketDao<T : DBEntity>(entityClass: Class<T>) : Dao<T>(entityClass) {
    /** The table prefix: [MarketTables.prefixOverride] when set (tests), otherwise the platform's prefix. */
    fun prefix(): String = MarketTables.prefixOverride ?: getTablePrefix()
}
