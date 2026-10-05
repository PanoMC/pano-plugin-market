package com.panomc.plugins.market.support

/**
 * Base of every `Migration*IT` (17 section 5.1): an **empty** throwaway database into which the frozen
 * scheme-version-2 install (`fixtures/schema-v2.sql` + `fixtures/seed-v2.sql`) is loaded before every test. There is
 * no truncate and no `MarketSchema.ensure()` in the set-up: the test itself runs the migration chain, `ensure()` and
 * the fixups. `InvariantChecker.assertAll` runs afterwards with the legacy-aware invariants (17 section 7).
 *
 * The fixtures are never regenerated from current code (01 section 14.1 rule 6).
 */
abstract class MarketMigrationTestBase : MarketDbTestBase() {
    override suspend fun installSchema() {
        // Nothing: the database stays empty until resetState() loads the version-2 install.
    }

    override suspend fun resetState() {
        MarketTestDb.dropAllTables(pool)
        MarketTestDb.runScript(pool, fixture(SCHEMA_V2))
        MarketTestDb.runScript(pool, fixture(SEED_V2))
    }

    override suspend fun assertInvariants() {
        // Legacy-aware variant when the checker has one, else the plain one.
        LateBound.callFirst(
            "com.panomc.plugins.market.support.InvariantChecker", "assertAll",
            listOf(arrayOf(pool, true), arrayOf(pool))
        )
    }

    private fun fixture(name: String): String {
        val stream = MarketMigrationTestBase::class.java.getResourceAsStream("/fixtures/$name")
            ?: error("fixture /fixtures/$name is missing from the test resources")
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    companion object {
        const val SCHEMA_V2 = "schema-v2.sql"
        const val SEED_V2 = "seed-v2.sql"
    }
}
