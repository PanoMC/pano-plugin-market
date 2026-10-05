package com.panomc.plugins.market.db

import com.panomc.plugins.market.db.impl.MarketBundleItemDaoImpl
import com.panomc.plugins.market.db.impl.MarketCategoryDaoImpl
import com.panomc.plugins.market.db.impl.MarketComparisonDaoImpl
import com.panomc.plugins.market.db.impl.MarketCouponDaoImpl
import com.panomc.plugins.market.db.impl.MarketCreatorEarningDaoImpl
import com.panomc.plugins.market.db.impl.MarketCreatorPayoutDaoImpl
import com.panomc.plugins.market.db.impl.MarketCreatorCodeDaoImpl
import com.panomc.plugins.market.db.impl.MarketCurrencyRateDaoImpl
import com.panomc.plugins.market.db.impl.MarketDiscountDaoImpl
import com.panomc.plugins.market.db.impl.MarketGiftDaoImpl
import com.panomc.plugins.market.db.impl.MarketOrderDaoImpl
import com.panomc.plugins.market.db.impl.MarketOrderItemDaoImpl
import com.panomc.plugins.market.db.impl.MarketPaymentMethodDaoImpl
import com.panomc.plugins.market.db.impl.MarketProductDaoImpl
import com.panomc.plugins.market.db.impl.MarketProductFieldDaoImpl
import com.panomc.plugins.market.db.impl.MarketProductPriceDaoImpl
import com.panomc.plugins.market.db.impl.MarketProductProviderMetaDaoImpl
import com.panomc.plugins.market.db.impl.MarketProductVariantDaoImpl
import com.panomc.plugins.market.db.impl.MarketRedemptionDaoImpl
import com.panomc.plugins.market.db.model.MarketCoupon
import com.panomc.plugins.market.db.model.MarketCreatorCode
import com.panomc.plugins.market.db.model.MarketDiscount
import com.panomc.plugins.market.db.model.MarketProduct
import com.panomc.plugins.market.support.MarketDbTestBase
import com.panomc.plugins.market.support.MarketTestDb
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.SqlClient
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `MarketSchema.ensure` on a real MariaDB (17 section 11.3 `MarketSchemaIT`, 01 section 14.1): the tables declared so
 * far (the ten of the existing plugin plus the six of the catalogue step, MK-023), idempotency, the repair of the
 * frozen scheme-version-2 install, the `Dao.init` contract, the fixup framework and the "generic update no longer
 * writes the counters" rule of 00 section 8.3. MK-031 changes the table assertion to 53 tables and scheme version 10
 * once the other tables exist.
 */
class MarketSchemaIT : MarketDbTestBase() {
    private val expectedTables = listOf(
        "category", "comparison", "coupon", "creator_code", "discount", "gift", "order", "order_item",
        "payment_method", "product",
        // scheme version 3 (MK-023)
        "product_variant", "product_price", "product_field", "bundle_item", "product_provider_meta", "currency_rate",
        // scheme version 4 (MK-024)
        "redemption", "creator_earning", "creator_payout"
    ).map { "pano_market_$it" }.sorted()

    /** Drops every table and runs `ensure` again: the way a test that damaged the schema puts it back. */
    private suspend fun rebuild() {
        MarketTestDb.dropAllTables(pool)
        MarketSchema.ensure(pool, prefix)
    }

    // --- ensure ------------------------------------------------------------------------------------------------

    @Test
    fun `ensure on an empty database creates the nineteen tables`(): Unit = runBlocking {
        MarketTestDb.dropAllTables(pool)
        val report = MarketSchema.ensure(pool, prefix)
        assertTrue(report.clean, report.ddlErrors.toString())
        assertEquals(expectedTables, MarketTestDb.marketTables(pool))
        assertEquals(MarketSchema.tables.map { it.physicalName(prefix) }.sorted(), MarketTestDb.marketTables(pool))
        assertTrue(SchemaVerifier.verify(pool, prefix).ok)
    }

    @Test
    fun `ensure twice changes nothing`(): Unit = runBlocking {
        MarketTestDb.dropAllTables(pool)
        val first = MarketSchema.ensure(pool, prefix)
        val before = SchemaSnapshot.take(pool)
        val second = MarketSchema.ensure(pool, prefix)
        val after = SchemaSnapshot.take(pool)
        assertTrue(first.clean && second.clean)
        assertEquals(before, after)
        assertTrue(before.columns.isNotEmpty() && before.keys.isNotEmpty() && before.tables.size == 19)
    }

    @Test
    fun `ensure brings the frozen version 2 install to the schema of a fresh install`(): Unit = runBlocking {
        val admin = MarketTestDb.adminPool()
        val reference = MarketTestDb.newDatabaseName()
        var referencePool: Pool? = null
        try {
            MarketTestDb.createDatabase(admin, reference)
            referencePool = MarketTestDb.pool(reference, 2)
            val script = MarketSchemaIT::class.java.getResourceAsStream("/fixtures/schema-v2.sql")!!
                .use { it.readBytes().toString(Charsets.UTF_8) }
            MarketTestDb.runScript(referencePool, script)
            assertEquals(10, SchemaSnapshot.take(referencePool).tables.size)

            // the repair path of every plugin start: the idempotent ALTERs and CREATEs bring version 2 up to date
            assertTrue(MarketSchema.ensure(referencePool, prefix).clean)

            MarketTestDb.dropAllTables(pool)
            MarketSchema.ensure(pool, prefix)

            val expected = SchemaSnapshot.take(pool)
            val actual = SchemaSnapshot.take(referencePool)
            assertEquals(19, expected.tables.size)
            assertEquals(expected.tables, actual.tables)
            assertEquals(expected.columns, actual.columns)
            assertEquals(expected.keys, actual.keys)
        } finally {
            runCatching { referencePool?.close()?.coAwait() }
            runCatching { MarketTestDb.dropDatabase(admin, reference) }
            runCatching { admin.close().coAwait() }
        }
    }

    @Test
    fun `ensure repairs a market order table that predates the exchange rate column`(): Unit = runBlocking {
        try {
            sql("ALTER TABLE `pano_market_order` DROP COLUMN `exchangeRate`")
            assertEquals(SchemaVerifier.Kind.MISSING_COLUMN, SchemaVerifier.verify(pool, prefix).findings.single().kind)
            MarketSchema.ensure(pool, prefix)
            assertTrue(SchemaVerifier.verify(pool, prefix).ok)
        } finally {
            rebuild()
        }
    }

    @Test
    fun `a failing CREATE is recorded and the other tables are still created`(): Unit = runBlocking {
        MarketTestDb.dropAllTables(pool)
        val broken = MarketSchema.Table(
            "market_zz_broken", "Broken.",
            listOf(MarketSchema.Column("id", "NOT_A_TYPE", autoIncrement = true)),
            listOf(MarketSchema.Key(MarketSchema.Key.PRIMARY, listOf("id"), unique = true))
        )
        val report = MarketSchema.ensure(pool, prefix, listOf(broken) + MarketSchema.tables, emptyList())
        assertEquals(1, report.ddlErrors.size)
        assertTrue(report.ddlErrors.single().contains("pano_market_zz_broken"))
        assertEquals(expectedTables, MarketTestDb.marketTables(pool))
        val result = SchemaVerifier.verify(pool, prefix, listOf(broken), emptyList())
        assertEquals(listOf("pano_market_zz_broken"), result.missingTables())
    }

    @Test
    fun `ensure on a closed pool does not throw and reports the failure`(): Unit = runBlocking {
        val closed = MarketTestDb.pool(databaseName, 1)
        closed.close().coAwait()
        val report = MarketSchema.ensure(closed, prefix)
        assertFalse(report.clean)
        assertEquals(MarketSchema.tables.sumOf { MarketSchema.ddl(it, prefix).size }, report.ddlErrors.size)
    }

    // --- Dao.init ----------------------------------------------------------------------------------------------

    private fun allDaoInits(): List<suspend (SqlClient) -> Unit> = listOf(
        { c -> MarketCategoryDaoImpl().init(c) }, { c -> MarketComparisonDaoImpl().init(c) },
        { c -> MarketCouponDaoImpl().init(c) }, { c -> MarketCreatorCodeDaoImpl().init(c) },
        { c -> MarketDiscountDaoImpl().init(c) }, { c -> MarketGiftDaoImpl().init(c) },
        { c -> MarketOrderDaoImpl().init(c) }, { c -> MarketOrderItemDaoImpl().init(c) },
        { c -> MarketPaymentMethodDaoImpl().init(c) }, { c -> MarketProductDaoImpl().init(c) },
        { c -> MarketProductVariantDaoImpl().init(c) }, { c -> MarketProductPriceDaoImpl().init(c) },
        { c -> MarketProductFieldDaoImpl().init(c) }, { c -> MarketBundleItemDaoImpl().init(c) },
        { c -> MarketProductProviderMetaDaoImpl().init(c) }, { c -> MarketCurrencyRateDaoImpl().init(c) },
        { c -> MarketRedemptionDaoImpl().init(c) }, { c -> MarketCreatorEarningDaoImpl().init(c) },
        { c -> MarketCreatorPayoutDaoImpl().init(c) }
    )

    @Test
    fun `the nineteen Dao init calls create the same schema as ensure`(): Unit = runBlocking {
        MarketTestDb.dropAllTables(pool)
        allDaoInits().forEach { it(pool) }
        allDaoInits().forEach { it(pool) } // twice: idempotent
        assertEquals(expectedTables, MarketTestDb.marketTables(pool))
        assertTrue(SchemaVerifier.verify(pool, prefix).ok)

        val viaDao = SchemaSnapshot.take(pool)
        MarketTestDb.dropAllTables(pool)
        MarketSchema.ensure(pool, prefix)
        assertEquals(SchemaSnapshot.take(pool), viaDao)
    }

    @Test
    fun `Dao init never throws, with a broken prefix or a closed pool`(): Unit = runBlocking {
        MarketTables.prefixOverride = "bad`prefix_"
        try {
            allDaoInits().forEach { it(pool) }
        } finally {
            MarketTables.prefixOverride = prefix
        }
        val closed = MarketTestDb.pool(databaseName, 1)
        closed.close().coAwait()
        allDaoInits().forEach { it(closed) }
        assertEquals(expectedTables, MarketTestDb.marketTables(pool))
    }

    // --- the counters are not written by the generic update ------------------------------------------------------

    @Test
    fun `generic update of a product does not write stock and setStock does`(): Unit = runBlocking {
        val dao = MarketProductDaoImpl()
        suspend fun stockOf(id: Long) = sql("SELECT `stock` FROM `pano_market_product` WHERE `id` = ?", id).single().getInteger("stock")
        suspend fun nameOf(id: Long) = sql("SELECT `name` FROM `pano_market_product` WHERE `id` = ?", id).single().getString("name")

        val id = dao.add(MarketProduct(slug = "rank-a", name = "Rank A", stock = 5), pool)
        assertEquals(5, stockOf(id))

        dao.update(MarketProduct(id = id, slug = "rank-a", name = "Rank A renamed", stock = 99, price = 1200), pool)
        assertEquals("Rank A renamed", nameOf(id))
        assertEquals(1200L, sql("SELECT `price` FROM `pano_market_product` WHERE `id` = ?", id).single().getLong("price"))
        assertEquals(5, stockOf(id))

        dao.setStock(id, 7, pool)
        assertEquals(7, stockOf(id))
        dao.setStock(id, null, pool)
        assertNull(stockOf(id))
    }

    @Test
    fun `generic update of coupon discount and creator code does not write usedCount or earnings`(): Unit = runBlocking {
        val coupons = MarketCouponDaoImpl()
        val couponId = coupons.add(MarketCoupon(code = "SAVE10", name = "Save", discount = 1000, usedCount = 3), pool)
        coupons.update(MarketCoupon(id = couponId, code = "SAVE10", name = "Renamed", discount = 2000, usedCount = 50), pool)
        val coupon = coupons.getById(couponId, pool)!!
        assertEquals("Renamed", coupon.name)
        assertEquals(2000L, coupon.discount)
        assertEquals(3, coupon.usedCount)

        val discounts = MarketDiscountDaoImpl()
        val discountId = discounts.add(MarketDiscount(name = "Spring", value = 500, usedCount = 2), pool)
        discounts.update(MarketDiscount(id = discountId, name = "Spring 2", value = 700, usedCount = 40), pool)
        val discount = discounts.getById(discountId, pool)!!
        assertEquals("Spring 2", discount.name)
        assertEquals(700L, discount.value)
        assertEquals(2, discount.usedCount)

        val creators = MarketCreatorCodeDaoImpl()
        val creatorId = creators.add(
            MarketCreatorCode(creator = "alex", code = "ALEX", discount = 500, usedCount = 4, earnings = 9000), pool
        )
        creators.update(
            MarketCreatorCode(id = creatorId, creator = "alex2", code = "ALEX", discount = 800, usedCount = 99, earnings = 1), pool
        )
        val creator = creators.getById(creatorId, pool)!!
        assertEquals("alex2", creator.creator)
        assertEquals(800L, creator.discount)
        assertEquals(4, creator.usedCount)
        assertEquals(9000L, creator.earnings)
    }

    // --- fixup framework ---------------------------------------------------------------------------------------

    private val probe = "pano_market_zz_fixup_probe"
    private val marker = "pano_market_zz_marker"

    private suspend fun withProbeTables(block: suspend () -> Unit) {
        try {
            sql("CREATE TABLE `$probe` (`id` INT NOT NULL AUTO_INCREMENT, `v` INT NOT NULL DEFAULT 0, PRIMARY KEY (`id`)) ENGINE=InnoDB")
            sql("CREATE TABLE `$marker` (`name` VARCHAR(64) NOT NULL, `value` BIGINT NOT NULL, PRIMARY KEY (`name`)) ENGINE=InnoDB")
            sql("INSERT INTO `$probe` (`v`) VALUES (0), (0), (5)")
            block()
        } finally {
            runCatching { sql("DROP TABLE IF EXISTS `$probe`") }
            runCatching { sql("DROP TABLE IF EXISTS `$marker`") }
        }
    }

    private suspend fun oneShotEnsure(fixup: MarketSchema.Fixup) =
        MarketSchema.ensure(pool, prefix, emptyList(), listOf(fixup), "market_zz_marker")

    private fun probeFixup(id: String = "probe", fail: Boolean = false) = MarketSchema.Fixup(
        id,
        requires = listOf("market_zz_fixup_probe"),
        pendingSql = { p -> "SELECT COUNT(*) FROM `${p}market_zz_fixup_probe` WHERE `v` = 0" }
    ) { client, p ->
        client.query("UPDATE `${p}market_zz_fixup_probe` SET `v` = 1 WHERE `v` = 0").execute().coAwait()
        if (fail) error("boom")
    }

    @Test
    fun `a fixup runs while its predicate finds rows and is idle afterwards`(): Unit = runBlocking {
        withProbeTables {
            val fixup = probeFixup()
            val before = SchemaVerifier.verify(pool, prefix, emptyList(), listOf(fixup))
            assertEquals(mapOf("probe" to 2L), before.unfixed)
            assertFalse(before.ok)

            val first = MarketSchema.ensure(pool, prefix, emptyList(), listOf(fixup))
            assertEquals(listOf("probe"), first.fixupsRun)
            assertEquals(0L, count("market_zz_fixup_probe", "`v` = 0"))
            assertTrue(SchemaVerifier.verify(pool, prefix, emptyList(), listOf(fixup)).ok)

            val second = MarketSchema.ensure(pool, prefix, emptyList(), listOf(fixup))
            assertTrue(second.fixupsRun.isEmpty() && second.clean)

            // Re-runnable: the column is nulled again (here: set back), the next ensure fixes it again.
            sql("UPDATE `$probe` SET `v` = 0 WHERE `id` = 1")
            val third = MarketSchema.ensure(pool, prefix, emptyList(), listOf(fixup))
            assertEquals(listOf("probe"), third.fixupsRun)
            assertEquals(0L, count("market_zz_fixup_probe", "`v` = 0"))
        }
    }

    @Test
    fun `a failing fixup is recorded and the next fixup still runs`(): Unit = runBlocking {
        withProbeTables {
            val report = MarketSchema.ensure(
                pool, prefix, emptyList(), listOf(probeFixup("bad", fail = true), probeFixup("good"))
            )
            assertEquals(1, report.fixupErrors.size)
            assertTrue(report.fixupErrors.single().startsWith("fixup bad"))
            // the first apply changed the rows before it threw, so the second finds nothing left
            assertTrue(report.fixupsRun.isEmpty())
            assertFalse(report.clean)
        }
    }

    @Test
    fun `a fixup whose table is missing is skipped`(): Unit = runBlocking {
        val report = MarketSchema.ensure(pool, prefix, emptyList(), listOf(probeFixup()))
        assertEquals(listOf("probe"), report.fixupsSkipped)
        assertTrue(report.fixupsRun.isEmpty() && report.clean)
    }

    @Test
    fun `a one-shot fixup runs once with its marker row`(): Unit = runBlocking {
        withProbeTables {
            val oneShot = MarketSchema.Fixup("count", requires = listOf("market_zz_fixup_probe"), oneShot = true) { client, p ->
                client.query("UPDATE `${p}market_zz_fixup_probe` SET `v` = `v` + 10").execute().coAwait()
            }
            val ensure = { runBlocking { oneShotEnsure(oneShot) } }

            val first = ensure()
            assertEquals(listOf("count"), first.fixupsRun)
            assertEquals(1L, sql("SELECT COUNT(*) AS c FROM `$marker` WHERE `name` = 'fixup:count'").first().getLong("c"))
            assertEquals(listOf(10, 10, 15), sql("SELECT `v` FROM `$probe` ORDER BY `id`").map { it.getInteger("v") })

            val second = ensure()
            assertTrue(second.fixupsRun.isEmpty())
            assertEquals(listOf(10, 10, 15), sql("SELECT `v` FROM `$probe` ORDER BY `id`").map { it.getInteger("v") })
        }
    }

    @Test
    fun `a one-shot fixup that fails leaves no marker and runs again`(): Unit = runBlocking {
        withProbeTables {
            var attempts = 0
            val flaky = MarketSchema.Fixup("flaky", requires = listOf("market_zz_fixup_probe"), oneShot = true) { client, p ->
                client.query("UPDATE `${p}market_zz_fixup_probe` SET `v` = `v` + 10").execute().coAwait()
                if (++attempts == 1) error("first attempt dies after writing")
            }
            val first = oneShotEnsure(flaky)
            assertEquals(1, first.fixupErrors.size)
            assertEquals(listOf(0, 0, 5), sql("SELECT `v` FROM `$probe` ORDER BY `id`").map { it.getInteger("v") }) // rolled back
            assertEquals(0L, sql("SELECT COUNT(*) AS c FROM `$marker`").first().getLong("c"))

            val second = oneShotEnsure(flaky)
            assertEquals(listOf("flaky"), second.fixupsRun)
            assertEquals(listOf(10, 10, 15), sql("SELECT `v` FROM `$probe` ORDER BY `id`").map { it.getInteger("v") })
        }
    }

    @Test
    fun `a one-shot fixup without its marker row is unfixed in the verdict until it ran`(): Unit = runBlocking {
        withProbeTables {
            var fail = true
            val oneShot = MarketSchema.Fixup("backfill", requires = listOf("market_zz_fixup_probe"), oneShot = true) { client, p ->
                client.query("UPDATE `${p}market_zz_fixup_probe` SET `v` = `v` + 10").execute().coAwait()
                if (fail) error("boom")
            }
            val verify = { runBlocking { SchemaVerifier.verify(pool, prefix, emptyList(), listOf(oneShot), "market_zz_marker") } }

            // never ran: unfixed
            assertEquals(mapOf("backfill" to 1L), verify().unfixed)
            assertFalse(verify().ok)

            // failed and rolled back: still unfixed
            assertEquals(1, oneShotEnsure(oneShot).fixupErrors.size)
            assertEquals(mapOf("backfill" to 1L), verify().unfixed)
            assertTrue(verify().describe().any { it.contains("backfill") })

            // succeeded: marker row exists, verdict ok
            fail = false
            assertEquals(listOf("backfill"), oneShotEnsure(oneShot).fixupsRun)
            assertTrue(verify().ok)
        }
    }

    @Test
    fun `a one-shot fixup whose marker table is missing is a finding`(): Unit = runBlocking {
        val oneShot = MarketSchema.Fixup("backfill", oneShot = true) { _, _ -> }
        val report = MarketSchema.ensure(pool, prefix, emptyList(), listOf(oneShot), "market_zz_marker")
        assertEquals(listOf("backfill"), report.fixupsSkipped)

        val result = SchemaVerifier.verify(pool, prefix, emptyList(), listOf(oneShot), "market_zz_marker")
        assertFalse(result.ok)
        assertEquals(listOf("${prefix}market_zz_marker"), result.missingTables())
        assertEquals(mapOf("backfill" to 1L), result.unfixed)
    }

    @Test
    fun `the fixup list is empty for the catalogue tables and a predicate-less fixup must be one-shot`() {
        assertTrue(MarketSchema.fixups().isEmpty())
        val failure = runCatching { MarketSchema.Fixup("x", pendingSql = null, oneShot = false) { _, _ -> } }
        assertTrue(failure.exceptionOrNull() is IllegalArgumentException)
        assertNotNull(MarketSchema.table("market_coupon"))
        assertTrue(runCatching { MarketSchema.table("market_nope") }.isFailure)
    }
}

/** Everything about the schema that `information_schema` knows, for exact equality comparisons. */
internal data class SchemaSnapshot(
    val tables: List<String>,
    val columns: List<String>,
    val keys: List<String>
) {
    companion object {
        suspend fun take(client: SqlClient): SchemaSnapshot {
            val tables = client.query(
                "SELECT TABLE_NAME, TABLE_TYPE, ENGINE, TABLE_COLLATION, TABLE_COMMENT FROM information_schema.TABLES " +
                    "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME LIKE 'pano\\_market\\_%' ORDER BY TABLE_NAME"
            ).execute().coAwait().map { r -> (0 until 5).joinToString("|") { "${r.getValue(it)}" } }
            val columns = client.query(
                "SELECT TABLE_NAME, COLUMN_NAME, ORDINAL_POSITION, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT, EXTRA " +
                    "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME LIKE 'pano\\_market\\_%' " +
                    "ORDER BY TABLE_NAME, ORDINAL_POSITION"
            ).execute().coAwait().map { r -> (0 until 7).joinToString("|") { "${r.getValue(it)}" } }
            val keys = client.query(
                "SELECT TABLE_NAME, INDEX_NAME, SEQ_IN_INDEX, COLUMN_NAME, NON_UNIQUE, INDEX_TYPE FROM information_schema.STATISTICS " +
                    "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME LIKE 'pano\\_market\\_%' ORDER BY TABLE_NAME, INDEX_NAME, SEQ_IN_INDEX"
            ).execute().coAwait().map { r -> (0 until 6).joinToString("|") { "${r.getValue(it)}" } }
            return SchemaSnapshot(tables, columns, keys)
        }
    }
}
