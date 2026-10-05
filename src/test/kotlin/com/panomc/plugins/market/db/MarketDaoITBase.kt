package com.panomc.plugins.market.db

import com.google.gson.GsonBuilder
import com.panomc.platform.db.DBEntity
import com.panomc.platform.util.deserializer.BooleanDeserializer
import com.panomc.platform.util.deserializer.LenientListLongAdapterFactory
import com.panomc.platform.util.deserializer.LenientListStringAdapterFactory
import com.panomc.platform.util.deserializer.LenientMapStringAdapterFactory
import com.panomc.plugins.market.support.MarketDbTestBase
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance

/**
 * Base of the `*DaoIT` classes that read entities back. A DAO maps a row through `DBEntity.gson`, which the platform
 * builds in `SpringConfig.gson` with a boolean adapter for `TINYINT(1)` (rows hold 0 / 1) and the lenient list
 * adapters; the plain `GsonBuilder().create()` that [com.panomc.plugins.market.support.MarketTestDb.ensureGson]
 * installs cannot read a `Boolean` column. This base installs the same adapters (the subset a market entity needs)
 * after the parent's `@BeforeAll` ran, so the DAOs are tested through the mapping they use in production.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class MarketDaoITBase : MarketDbTestBase() {
    @BeforeAll
    fun installPlatformGson() {
        DBEntity.gson = GsonBuilder()
            .registerTypeAdapterFactory(LenientListLongAdapterFactory())
            .registerTypeAdapterFactory(LenientListStringAdapterFactory())
            .registerTypeAdapterFactory(LenientMapStringAdapterFactory())
            .registerTypeAdapter(Boolean::class.java, BooleanDeserializer())
            .registerTypeAdapter(java.lang.Boolean::class.java, BooleanDeserializer())
            .create()
    }
}
