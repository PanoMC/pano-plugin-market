package com.panomc.plugins.market.db

import com.panomc.platform.db.DBEntity
import io.vertx.sqlclient.SqlClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The prefix seam of 17 section 4 S5: a market DAO never needs `Main.applicationContext` when the override is set. */
class MarketDaoPrefixTest {
    class ProbeEntity : DBEntity()

    private class ProbeDao : MarketDao<ProbeEntity>(ProbeEntity::class.java) {
        override suspend fun init(sqlClient: SqlClient) {}
        fun table() = prefix() + tableName
    }

    @BeforeEach
    fun clearOverrideBefore() {
        MarketTables.prefixOverride = null
    }

    @AfterEach
    fun clearOverrideAfter() {
        MarketTables.prefixOverride = null
    }

    @Test
    fun `the override wins and the platform is not consulted`() {
        MarketTables.prefixOverride = "pano_"
        assertEquals("pano_", ProbeDao().prefix())
        assertEquals("pano_probe_entity", ProbeDao().table())
        MarketTables.prefixOverride = "x_"
        assertEquals("x_probe_entity", ProbeDao().table())
    }

    @Test
    fun `without an override the platform prefix is asked, which needs the application context`() {
        // No Spring context in a unit test: reaching the platform means the seam fell through, as intended.
        assertThrows<UninitializedPropertyAccessException> { ProbeDao().prefix() }
    }

    @Test
    fun `an empty override is a real prefix, not a fall-through`() {
        MarketTables.prefixOverride = ""
        assertEquals("", ProbeDao().prefix())
    }
}
