package com.panomc.plugins.market.db

import com.panomc.platform.db.DatabaseMigration
import com.panomc.plugins.market.db.migration.MarketMigration2to3
import com.panomc.plugins.market.db.migration.MarketMigration3to4
import com.panomc.plugins.market.db.migration.MarketMigration4to5
import com.panomc.plugins.market.db.migration.MarketMigration5to6
import com.panomc.plugins.market.db.migration.MarketMigration6to7
import com.panomc.plugins.market.db.migration.MarketMigration7to8
import com.panomc.plugins.market.db.migration.MarketMigration8to9
import com.panomc.plugins.market.db.migration.MarketMigration9to10
import com.panomc.plugins.market.support.MarketMigrationTestBase
import com.panomc.plugins.market.support.MarketTestDb
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.SqlClient
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The migration chain from the frozen scheme-version-2 install (17 section 11.3 `MigrationChainIT`, 01 section 14.1
 * rule 6): the resulting schema equals the schema of a fresh `ensure()` (table, column, type, default and index
 * sets), the seed rows are unchanged in their existing columns, and a step is idempotent and survives being
 * interrupted half-way. Each later migration slice appends its step to [chain]; `2 -> 3`, `3 -> 4`, `4 -> 5` (orders) and `5 -> 6` (payments) and `6 -> 7` (credits) are in.
 */
class MigrationChainIT : MarketMigrationTestBase() {
    private val chain: List<() -> DatabaseMigration> = listOf({ MarketMigration2to3() }, { MarketMigration3to4() }, { MarketMigration4to5() }, { MarketMigration5to6() }, { MarketMigration6to7() }, { MarketMigration7to8() }, { MarketMigration8to9() }, { MarketMigration9to10() })

    private suspend fun runChain(client: SqlClient = pool) {
        for (step in chain) step().migrate(client)
    }

    /** The schema of a fresh install, read from a second throwaway database. */
    private suspend fun freshSchema(): SchemaSnapshot {
        val admin = MarketTestDb.adminPool()
        val reference = MarketTestDb.newDatabaseName()
        var referencePool: Pool? = null
        try {
            MarketTestDb.createDatabase(admin, reference)
            referencePool = MarketTestDb.pool(reference, 2)
            val report = MarketSchema.ensure(referencePool, prefix)
            assertTrue(report.clean, report.ddlErrors.toString())
            return SchemaSnapshot.take(referencePool)
        } finally {
            runCatching { referencePool?.close()?.coAwait() }
            runCatching { MarketTestDb.dropDatabase(admin, reference) }
            runCatching { admin.close().coAwait() }
        }
    }

    /** Every row of every table in the columns the table has right now, as comparable strings. */
    private suspend fun dump(columnsOf: Map<String, List<String>>): Map<String, List<List<String>>> =
        columnsOf.mapValues { (table, columns) ->
            val select = columns.joinToString(", ") { "`$it`" }
            sql("SELECT $select FROM `$table` ORDER BY `id`").map { row -> columns.map { c -> "${row.getValue(c)}" } }
        }

    private suspend fun columnsOfCurrentTables(): Map<String, List<String>> =
        sql(
            "SELECT TABLE_NAME AS t, COLUMN_NAME AS c FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() " +
                "ORDER BY TABLE_NAME, ORDINAL_POSITION"
        ).groupBy({ it.getString("t") }, { it.getString("c") })

    @Test
    fun `the step declares 2 to 3 with one handler per statement`() {
        val migration = MarketMigration2to3()
        assertEquals(2, migration.from)
        assertEquals(3, migration.to)
        assertTrue(migration.isMigratable(2) && !migration.isMigratable(1))
        // 2 category columns + 29 product columns + 3 product indexes + 6 CREATE TABLE
        assertEquals(2 + 29 + 3 + 6, migration.handlers.size)
        assertEquals(2, MarketSchema.CATEGORY.alters.size)
        assertEquals(32, MarketSchema.PRODUCT.alters.size)
        assertTrue(MarketSchema.PRODUCT.alters.all { it.contains("IF NOT EXISTS") })
    }

    @Test
    fun `the chain from schema v2 and seed v2 equals a fresh ensure schema`(): Unit = runBlocking {
        assertEquals(10, SchemaSnapshot.take(pool).tables.size)
        runChain()
        val migrated = SchemaSnapshot.take(pool)
        val fresh = freshSchema()
        assertEquals(46, migrated.tables.size)
        assertEquals(fresh.tables, migrated.tables)
        assertEquals(fresh.columns, migrated.columns)
        assertEquals(fresh.keys, migrated.keys)
        // the schema is complete, the legacy rows are not converted yet: that is the job of the fixups run by ensure()
        assertEquals(emptyList<SchemaVerifier.Finding>(), SchemaVerifier.verify(pool, prefix).findings)
        assertTrue(MarketSchema.ensure(pool, prefix).clean)
        val verdict = SchemaVerifier.verify(pool, prefix)
        assertTrue(verdict.ok, verdict.describe().toString())
    }

    @Test
    fun `seed rows are unchanged in their existing columns and new columns carry their defaults`(): Unit = runBlocking {
        val columns = columnsOfCurrentTables()
        assertEquals(10, columns.size)
        val before = dump(columns)
        assertEquals(6, before.getValue("pano_market_product").size)

        runChain()

        assertEquals(before, dump(columns))

        assertEquals(6L, count("market_product", "`kind` = 'STANDARD' AND `billingMode` = 'ONE_TIME' AND `allowGift` = 1 AND `physical` = 0 AND `hasVariants` = 0 AND `soldCount` = 0"))
        assertEquals(6L, count("market_product", "`periodUnit` IS NULL AND `deletedAt` IS NULL AND `compareAtPrice` IS NULL AND `vatPercent` IS NULL AND `creditAmount` IS NULL"))
        assertEquals(3L, count("market_category", "`tiered` = 0 AND `upgradeMode` = 'DIFFERENCE'"))
        // actions[].id is not backfilled in SQL: the JSON text of every product is the seeded one
        assertEquals(0L, count("market_product", "`actions` LIKE '%\"id\"%'"))
        // the new tables start empty
        for (t in listOf("product_variant", "product_price", "product_field", "bundle_item", "product_provider_meta", "currency_rate")) {
            assertEquals(0L, count("market_$t"), t)
        }
    }

    @Test
    fun `the new indexes of market_product exist after the step`(): Unit = runBlocking {
        runChain()
        val names = sql(
            "SELECT DISTINCT INDEX_NAME AS n FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pano_market_product'"
        ).map { it.getString("n") }.toSet()
        assertTrue(names.containsAll(listOf("idx_imageFileName", "idx_kind", "idx_category_tier", "unique_slug", "PRIMARY")), names.toString())
    }

    @Test
    fun `running the step twice changes neither schema nor data`(): Unit = runBlocking {
        runChain()
        val columns = columnsOfCurrentTables()
        val schema = SchemaSnapshot.take(pool)
        val data = dump(columns)
        runChain()
        assertEquals(schema, SchemaSnapshot.take(pool))
        assertEquals(data, dump(columns))
        // ensure on top of the migrated schema changes no object; its fixups convert the legacy order rows, once
        assertTrue(MarketSchema.ensure(pool, prefix).clean)
        assertEquals(schema, SchemaSnapshot.take(pool))
        val converted = dump(columns)
        assertTrue(MarketSchema.ensure(pool, prefix).fixupsRun.isEmpty())
        assertEquals(schema, SchemaSnapshot.take(pool))
        assertEquals(converted, dump(columns))
    }

    @Test
    fun `a step interrupted after any number of handlers is completed by ensure and the full step`(): Unit = runBlocking {
        runChain()
        val expected = SchemaSnapshot.take(pool)
        val handlerCount = MarketMigration2to3().handlers.size

        // first half, a single handler, all but one handler
        for (stopAfter in listOf(handlerCount / 2, 1, handlerCount - 1)) {
            resetState()
            val handlers = MarketMigration2to3().handlers
            for (handler in handlers.take(stopAfter)) handler(pool)
            assertTrue(SchemaSnapshot.take(pool) != expected, "interrupted after $stopAfter handlers is a partial schema")

            val report = MarketSchema.ensure(pool, prefix)
            assertTrue(report.clean, report.ddlErrors.toString())
            runChain()
            assertEquals(expected, SchemaSnapshot.take(pool), "interrupted after $stopAfter handlers")
            assertTrue(SchemaVerifier.verify(pool, prefix).ok)
        }
    }

    @Test
    fun `ensure alone brings the version 2 install to the same schema`(): Unit = runBlocking {
        val report = MarketSchema.ensure(pool, prefix)
        assertTrue(report.clean, report.ddlErrors.toString())
        assertEquals(freshSchema(), SchemaSnapshot.take(pool))
    }

    @Test
    fun `a failing statement is logged, the step goes on and the verifier names what is missing`(): Unit = runBlocking {
        sql("CREATE VIEW `pano_market_product_variant` AS SELECT 1 AS id") // CREATE TABLE IF NOT EXISTS is a no-op on a name in use
        try {
            MarketMigration2to3().migrate(pool) // does not throw
            MarketMigration3to4().migrate(pool)
            MarketMigration4to5().migrate(pool)
            MarketMigration5to6().migrate(pool)
            MarketMigration6to7().migrate(pool)
            MarketMigration7to8().migrate(pool)
            MarketMigration8to9().migrate(pool)
            MarketMigration9to10().migrate(pool)

            val findings = SchemaVerifier.verify(pool, prefix).findings
            assertEquals(listOf("pano_market_product_variant"), findings.map { it.target })
            // every other statement still ran
            assertEquals(22L + 29L, sql(
                "SELECT COUNT(*) AS c FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pano_market_product'"
            ).single().getLong("c"))

            val closed = MarketTestDb.pool(databaseName, 1)
            closed.close().coAwait()
            MarketMigration2to3().migrate(closed) // a dead connection does not throw either
            MarketMigration3to4().migrate(closed)
            MarketMigration4to5().migrate(closed)
            MarketMigration5to6().migrate(closed)
            MarketMigration6to7().migrate(closed)
            MarketMigration7to8().migrate(closed)
            MarketMigration8to9().migrate(closed)
            MarketMigration9to10().migrate(closed)
        } finally {
            sql("DROP VIEW IF EXISTS `pano_market_product_variant`")
        }
    }

    @Test
    fun `the step declares 3 to 4 with one handler per statement`() {
        val migration = MarketMigration3to4()
        assertEquals(3, migration.from)
        assertEquals(4, migration.to)
        assertTrue(migration.isMigratable(3) && !migration.isMigratable(2))
        // discount +3, coupon +3, creator code +4 columns and 1 index, gift +5; 3 CREATE TABLE
        assertEquals(3 + 3 + (4 + 1) + 5 + 3, migration.handlers.size)
        assertEquals(5, MarketSchema.CREATOR_CODE.alters.size)
        assertTrue((MarketSchema.DISCOUNT.alters + MarketSchema.COUPON.alters + MarketSchema.CREATOR_CODE.alters + MarketSchema.GIFT.alters).all { it.contains("IF NOT EXISTS") })
        assertTrue(MarketSchema.GIFT.alters.none { it.trim().startsWith("UPDATE", ignoreCase = true) }, "no backfill statement")
    }

    @Test
    fun `step 3 to 4 gives seeded gifts redeemLimit 1 from the column default and keeps the other columns`(): Unit = runBlocking {
        MarketMigration2to3().migrate(pool)
        val columns = columnsOfCurrentTables()
        val before = dump(columns)
        val gifts = before.getValue("pano_market_gift").size
        assertTrue(gifts > 0)

        MarketMigration3to4().migrate(pool)

        assertEquals(before, dump(columns))
        assertEquals(gifts.toLong(), count("market_gift", "`redeemLimit` = 1 AND `customerRedeemLimit` = 1 AND `usedCount` = 0 AND `name` = '' AND `deletedAt` IS NULL"))
        assertEquals(sql("SELECT COUNT(*) AS c FROM `pano_market_discount`").single().getLong("c"), count("market_discount", "`showBadge` = 1 AND `legacyUsedCount` = 0 AND `deletedAt` IS NULL"))
        assertEquals(sql("SELECT COUNT(*) AS c FROM `pano_market_coupon`").single().getLong("c"), count("market_coupon", "`categoryIds` IS NULL AND `legacyUsedCount` = 0 AND `deletedAt` IS NULL"))
        assertEquals(sql("SELECT COUNT(*) AS c FROM `pano_market_creator_code`").single().getLong("c"), count("market_creator_code", "`creatorUserId` IS NULL AND `paidOut` = 0 AND `legacyUsedCount` = 0 AND `deletedAt` IS NULL"))
        // the column default itself is 1, and an explicit NULL stays NULL (unlimited gift)
        assertEquals("1", sql("SELECT COLUMN_DEFAULT AS d FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pano_market_gift' AND COLUMN_NAME = 'redeemLimit'").single().getString("d"))
        for (t in listOf("redemption", "creator_earning", "creator_payout")) assertEquals(0L, count("market_$t"), t)
        val names = sql("SELECT DISTINCT INDEX_NAME AS n FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pano_market_creator_code'").map { it.getString("n") }.toSet()
        assertTrue("idx_creatorUser" in names, names.toString())
    }

    @Test
    fun `step 3 to 4 survives an interruption and runs twice`(): Unit = runBlocking {
        runChain()
        val expected = SchemaSnapshot.take(pool)
        val total = MarketMigration3to4().handlers.size
        for (stopAfter in listOf(total / 2, 1, total - 1)) {
            resetState()
            MarketMigration2to3().migrate(pool)
            for (handler in MarketMigration3to4().handlers.take(stopAfter)) handler(pool)
            assertTrue(SchemaSnapshot.take(pool) != expected, "interrupted after $stopAfter handlers is partial")
            assertTrue(MarketSchema.ensure(pool, prefix).clean)
            MarketMigration3to4().migrate(pool)
            assertEquals(expected, SchemaSnapshot.take(pool), "interrupted after $stopAfter handlers")
            MarketMigration3to4().migrate(pool)
            assertEquals(expected, SchemaSnapshot.take(pool))
            assertTrue(SchemaVerifier.verify(pool, prefix).ok)
        }
    }

    @Test
    fun `the step declares 4 to 5 with one handler per statement and no data statement`() {
        val migration = MarketMigration4to5()
        assertEquals(4, migration.from)
        assertEquals(5, migration.to)
        assertTrue(migration.isMigratable(4) && !migration.isMigratable(3))
        // order: MODIFY status + 70 columns + 10 indexes; item: 25 columns; CREATE order_event, legal_text, sequence,
        // entitlement, address, cart, cart_item, invoice
        assertEquals(1 + 70 + 10 + 25 + 8, migration.handlers.size)
        // the table carries the version 1 -> 2 exchangeRate alter in front of the 81 statements of this step
        assertEquals(1 + 1 + 70 + 10, MarketSchema.ORDER.alters.size)
        assertEquals(25, MarketSchema.ORDER_ITEM.alters.size)
        assertEquals(1 + 2 + 10, MarketSchema.ORDER.keys.size) // PRIMARY + userId + status + the ten new ones
        val all = MarketSchema.ORDER.alters + MarketSchema.ORDER_ITEM.alters
        assertTrue(all.none { it.trim().startsWith("UPDATE", ignoreCase = true) || it.trim().startsWith("INSERT", ignoreCase = true) }, "no backfill statement")
        assertTrue(all.filter { !it.contains("MODIFY COLUMN") }.all { it.contains("IF NOT EXISTS") })
        assertTrue(all.single { it.contains("MODIFY COLUMN") }.contains("`status` VARCHAR(24) NOT NULL DEFAULT 'PENDING'"))
    }

    @Test
    fun `step 4 to 5 widens status, adds the order columns with their defaults and does not convert a legacy row`(): Unit = runBlocking {
        MarketMigration2to3().migrate(pool)
        MarketMigration3to4().migrate(pool)
        val columns = columnsOfCurrentTables()
        val before = dump(columns)
        assertEquals(3, before.getValue("pano_market_order").size)
        suspend fun statusLength() = sql(
            "SELECT CHARACTER_MAXIMUM_LENGTH AS l FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pano_market_order' AND COLUMN_NAME = 'status'"
        ).single().getLong("l")
        assertEquals(16L, statusLength())

        MarketMigration4to5().migrate(pool)

        assertEquals(24L, statusLength())
        assertEquals(before, dump(columns)) // existing columns of every existing row unchanged, in particular status
        // the 70 + 25 new columns carry their declared defaults on the seeded rows; the legacy conversion is the fixups' job
        assertEquals(
            3L,
            count(
                "market_order",
                "`publicId` IS NULL AND `accessToken` IS NULL AND `source` = 'STOREFRONT' AND `buyerKey` = '' AND `idempotencyKey` IS NULL AND `recipientKey` = '' " +
                    "AND `reservationState` = 'NONE' AND `baseCurrency` = '' AND `fxRate` = 1 AND `pricingMode` = 'MARKET' AND `pricesIncludeVat` = 1 AND `subtotal` = 0 " +
                    "AND `gatewayAmount` = 0 AND `paidAt` IS NULL AND `disputeStatus` = 'NONE' AND `fulfillmentStatus` = 'NONE' AND `fulfillmentBy` = 'MARKET' " +
                    "AND `shippingStatus` = 'NOT_REQUIRED' AND `testMode` = 0 AND `isGift` = 0"
            )
        )
        assertEquals(
            5L,
            count("market_order_item", "`kind` = 'PRODUCT' AND `listUnitPrice` = 0 AND `lineTotal` = 0 AND `stockReserved` = 0 AND `physical` = 0 AND `snapshot` IS NULL")
        )
        for (t in listOf("order_event", "legal_text", "sequence")) assertEquals(0L, count("market_$t"), t)
        val keys = sql("SELECT DISTINCT INDEX_NAME AS n FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pano_market_order'").map { it.getString("n") }.toSet()
        assertTrue(
            keys.containsAll(
                listOf("userId", "status", "uq_publicId", "uq_buyer_idem", "idx_recipient", "idx_expiry", "idx_paidAt", "idx_email", "idx_player", "idx_subscription", "idx_coupon", "idx_creator")
            ),
            keys.toString()
        )
        // a longer status now fits
        sql("UPDATE `pano_market_order` SET `status` = 'PARTIALLY_REFUNDED' WHERE `id` = 2")
        assertEquals("PARTIALLY_REFUNDED", sql("SELECT `status` FROM `pano_market_order` WHERE `id` = 2").single().getString("status"))
    }

    @Test
    fun `step 4 to 5 survives an interruption and runs twice`(): Unit = runBlocking {
        runChain()
        val expected = SchemaSnapshot.take(pool)
        val total = MarketMigration4to5().handlers.size
        for (stopAfter in listOf(total / 2, 1, total - 1)) {
            resetState()
            MarketMigration2to3().migrate(pool)
            MarketMigration3to4().migrate(pool)
            for (handler in MarketMigration4to5().handlers.take(stopAfter)) handler(pool)
            assertTrue(SchemaSnapshot.take(pool) != expected, "interrupted after $stopAfter handlers is partial")
            assertTrue(MarketSchema.ensure(pool, prefix).clean)
            MarketMigration4to5().migrate(pool)
            assertEquals(expected, SchemaSnapshot.take(pool), "interrupted after $stopAfter handlers")
            MarketMigration4to5().migrate(pool)
            assertEquals(expected, SchemaSnapshot.take(pool))
            assertTrue(SchemaVerifier.verify(pool, prefix).ok)
        }
    }

    @Test
    fun `the step declares 5 to 6 with one handler per statement and no data statement`() {
        val migration = MarketMigration5to6()
        assertEquals(5, migration.from)
        assertEquals(6, migration.to)
        assertTrue(migration.isMigratable(5) && !migration.isMigratable(4))
        // payment method: 14 columns; CREATE payment, payment_event, refund, refund_item, dispute, provider_state
        assertEquals(14 + 6, migration.handlers.size)
        assertEquals(14, MarketSchema.PAYMENT_METHOD.alters.size)
        assertTrue(MarketSchema.PAYMENT_METHOD.alters.all { it.contains("ADD COLUMN IF NOT EXISTS") })
        assertTrue(MarketSchema.PAYMENT_METHOD.alters.none { it.trim().startsWith("UPDATE", ignoreCase = true) || it.trim().startsWith("INSERT", ignoreCase = true) })
    }

    @Test
    fun `step 5 to 6 adds the payment method columns with their defaults and keeps the seeded rows`(): Unit = runBlocking {
        MarketMigration2to3().migrate(pool)
        MarketMigration3to4().migrate(pool)
        MarketMigration4to5().migrate(pool)
        val columns = columnsOfCurrentTables()
        val before = dump(columns)
        val rows = before.getValue("pano_market_payment_method").size

        MarketMigration5to6().migrate(pool)

        assertEquals(before, dump(columns)) // existing columns of every existing row unchanged
        assertEquals(
            rows.toLong(),
            count(
                "market_payment_method",
                "`position` = 0 AND `customLabel` IS NULL AND `customDescription` IS NULL AND `feeMode` = 'NONE' AND `feePercent` = 0 AND `feeFixed` = 0 " +
                    "AND `minAmount` IS NULL AND `maxAmount` IS NULL AND `currencies` IS NULL AND `testMode` = 0 AND `lastInboundAt` IS NULL " +
                    "AND `lastError` IS NULL AND `lastErrorAt` IS NULL AND `settingsUpdatedAt` IS NULL"
            )
        )
        for (t in listOf("payment", "payment_event", "refund", "refund_item", "dispute", "provider_state")) assertEquals(0L, count("market_$t"), t)
        // the credit ledger of scheme version 7 does not exist yet: it is all the verifier misses
        assertEquals(
            (listOf("pano_market_credit_account", "pano_market_credit_entry", "pano_market_credit_tx") + step8Tables + step9Tables).sorted(),
            SchemaVerifier.verify(pool, prefix).findings.map { it.target }.sorted()
        )
    }

    @Test
    fun `step 5 to 6 survives an interruption and runs twice`(): Unit = runBlocking {
        runChain()
        val expected = SchemaSnapshot.take(pool)
        val total = MarketMigration5to6().handlers.size
        for (stopAfter in listOf(total / 2, 1, total - 1)) {
            resetState()
            MarketMigration2to3().migrate(pool)
            MarketMigration3to4().migrate(pool)
            MarketMigration4to5().migrate(pool)
            for (handler in MarketMigration5to6().handlers.take(stopAfter)) handler(pool)
            assertTrue(SchemaSnapshot.take(pool) != expected, "interrupted after $stopAfter handlers is partial")
            assertTrue(MarketSchema.ensure(pool, prefix).clean)
            MarketMigration5to6().migrate(pool)
            assertEquals(expected, SchemaSnapshot.take(pool), "interrupted after $stopAfter handlers")
            MarketMigration5to6().migrate(pool)
            assertEquals(expected, SchemaSnapshot.take(pool))
            assertTrue(SchemaVerifier.verify(pool, prefix).ok)
        }
    }

    private val step9Tables = listOf("subscription", "subscription_renewal", "block", "throttle", "goal").map { "pano_market_$it" }.toSet()

    private val step8Tables = listOf("delivery", "server_state", "webhook_endpoint", "webhook_delivery", "mail_outbox").map { "pano_market_$it" }.toSet()

    private suspend fun systemAccounts(): List<String> =
        sql("SELECT `systemKey` FROM `pano_market_credit_account` WHERE `type` = 'SYSTEM' ORDER BY `id`").map { it.getString("systemKey") }

    @Test
    fun `the step declares 6 to 7 with the three tables and the seed as one handler each`() {
        val migration = MarketMigration6to7()
        assertEquals(6, migration.from)
        assertEquals(7, migration.to)
        assertTrue(migration.isMigratable(6) && !migration.isMigratable(5))
        // CREATE credit_account, credit_tx, credit_entry + the INSERT IGNORE of the system accounts
        assertEquals(3 + 1, migration.handlers.size)
        assertEquals(listOf("ISSUANCE", "SPENT", "HOLD", "REVOKED", "EXTERNAL"), MarketSchema.CREDIT_SYSTEM_KEYS)
        val statements = MarketMigration6to7.statements().map { it("pano_") }
        assertTrue(statements.take(3).all { it.startsWith("CREATE TABLE IF NOT EXISTS") })
        assertTrue(statements.last().startsWith("INSERT IGNORE INTO `pano_market_credit_account`"))
    }

    @Test
    fun `step 6 to 7 creates the ledger tables and seeds the five system accounts, twice leaves five rows`(): Unit = runBlocking {
        MarketMigration2to3().migrate(pool)
        MarketMigration3to4().migrate(pool)
        MarketMigration4to5().migrate(pool)
        MarketMigration5to6().migrate(pool)
        val columns = columnsOfCurrentTables()
        val before = dump(columns)

        MarketMigration6to7().migrate(pool)

        assertEquals(before, dump(columns)) // existing tables untouched
        assertEquals(listOf("ISSUANCE", "SPENT", "HOLD", "REVOKED", "EXTERNAL"), systemAccounts())
        assertEquals(5L, count("market_credit_account"))
        assertEquals(5L, count("market_credit_account", "`type` = 'SYSTEM' AND `userId` IS NULL AND `balance` = 0"))
        assertEquals(0L, count("market_credit_tx"))
        assertEquals(0L, count("market_credit_entry"))
        val seeded = dump(columnsOfCurrentTables()).getValue("pano_market_credit_account")

        MarketMigration6to7().migrate(pool)
        assertEquals(5L, count("market_credit_account"))
        assertEquals(seeded, dump(columnsOfCurrentTables()).getValue("pano_market_credit_account"))
        // only the tables of steps 8 to 10 are still missing
        assertEquals((step8Tables + step9Tables).sorted(), SchemaVerifier.verify(pool, prefix).findings.map { it.target }.sorted())
    }

    @Test
    fun `step 6 to 7 restores a deleted system account and survives an interruption`(): Unit = runBlocking {
        runChain()
        val expected = SchemaSnapshot.take(pool)
        sql("DELETE FROM `pano_market_credit_account` WHERE `systemKey` = 'HOLD'")
        assertEquals(4L, count("market_credit_account"))
        MarketMigration6to7().migrate(pool)
        assertEquals(5L, count("market_credit_account"))

        val total = MarketMigration6to7().handlers.size
        for (stopAfter in listOf(total / 2, 1, total - 1)) {
            resetState()
            MarketMigration2to3().migrate(pool)
            MarketMigration3to4().migrate(pool)
            MarketMigration4to5().migrate(pool)
            MarketMigration5to6().migrate(pool)
            for (handler in MarketMigration6to7().handlers.take(stopAfter)) handler(pool)
            if (stopAfter < total - 1) {
                assertTrue(SchemaSnapshot.take(pool) != expected, "interrupted after $stopAfter handlers is a partial schema")
            } else {
                // all three tables exist, only the seed is missing (and the five tables of step 8 are not there yet)
                assertEquals(expected.tables.filterNot { row -> (step8Tables + step9Tables).any { row.startsWith("$it|") } }, SchemaSnapshot.take(pool).tables)
                assertEquals(0L, count("market_credit_account"), "interrupted before the seed")
            }
            assertTrue(MarketSchema.ensure(pool, prefix).clean)
            MarketMigration6to7().migrate(pool)
            MarketMigration7to8().migrate(pool)
            MarketMigration8to9().migrate(pool)
            MarketMigration9to10().migrate(pool)
            assertEquals(expected, SchemaSnapshot.take(pool), "interrupted after $stopAfter handlers")
            assertEquals(5L, count("market_credit_account"), "interrupted after $stopAfter handlers")
            assertTrue(SchemaVerifier.verify(pool, prefix).ok)
        }
    }

    @Test
    fun `ensure seeds the system accounts of a fresh install once and restores a deleted one`(): Unit = runBlocking {
        MarketTestDb.dropAllTables(pool)
        assertTrue(MarketSchema.ensure(pool, prefix).clean)
        assertEquals(listOf("ISSUANCE", "SPENT", "HOLD", "REVOKED", "EXTERNAL"), systemAccounts())
        assertTrue(MarketSchema.ensure(pool, prefix).clean)
        assertEquals(5L, count("market_credit_account"))
        sql("DELETE FROM `pano_market_credit_account` WHERE `systemKey` = 'EXTERNAL'")
        assertTrue(MarketSchema.ensure(pool, prefix).clean)
        assertEquals(5L, count("market_credit_account"))
    }

    @Test
    fun `the step declares 7 to 8 with one CREATE handler per table`() {
        val migration = MarketMigration7to8()
        assertEquals(7, migration.from)
        assertEquals(8, migration.to)
        assertTrue(migration.isMigratable(7) && !migration.isMigratable(6))
        assertEquals(5, migration.handlers.size)
        assertTrue(MarketMigration7to8.statements().map { it("pano_") }.all { it.startsWith("CREATE TABLE IF NOT EXISTS") })
    }

    @Test
    fun `step 7 to 8 creates the five empty tables and leaves every existing table untouched, twice changes nothing`(): Unit = runBlocking {
        for (step in chain.take(5)) step().migrate(pool)
        val columns = columnsOfCurrentTables()
        val before = dump(columns)
        assertTrue(step8Tables.none { it in columns.keys })

        MarketMigration7to8().migrate(pool)

        assertEquals(before, dump(columns))
        val after = columnsOfCurrentTables()
        assertTrue(step8Tables.all { it in after.keys })
        for (t in listOf("delivery", "server_state", "webhook_endpoint", "webhook_delivery", "mail_outbox")) assertEquals(0L, count("market_$t"), t)
        val schema = SchemaSnapshot.take(pool)
        MarketMigration7to8().migrate(pool)
        assertEquals(schema, SchemaSnapshot.take(pool))
        // only the tables of the later steps are missing
        assertEquals(step9Tables.sorted(), SchemaVerifier.verify(pool, prefix).findings.map { it.target }.sorted())
    }

    @Test
    fun `step 7 to 8 interrupted after any number of handlers is completed by the full step`(): Unit = runBlocking {
        runChain()
        val expected = SchemaSnapshot.take(pool)
        val total = MarketMigration7to8().handlers.size
        for (stopAfter in listOf(total / 2, 1, total - 1)) {
            resetState()
            for (step in chain.take(5)) step().migrate(pool)
            for (handler in MarketMigration7to8().handlers.take(stopAfter)) handler(pool)
            assertTrue(SchemaSnapshot.take(pool) != expected, "interrupted after $stopAfter handlers is a partial schema")
            MarketMigration7to8().migrate(pool)
            MarketMigration8to9().migrate(pool)
            MarketMigration9to10().migrate(pool)
            assertEquals(expected, SchemaSnapshot.take(pool), "interrupted after $stopAfter handlers")
            assertEquals(emptyList<SchemaVerifier.Finding>(), SchemaVerifier.verify(pool, prefix).findings)
        }
    }

    // --- 8 -> 9 and 9 -> 10 (MK-030) -----------------------------------------------------------------------------

    @Test
    fun `the steps declare 8 to 9 and 9 to 10 with one CREATE handler per table`() {
        val first = MarketMigration8to9()
        assertEquals(8, first.from)
        assertEquals(9, first.to)
        assertTrue(first.isMigratable(8) && !first.isMigratable(7))
        assertEquals(2, first.handlers.size)
        val second = MarketMigration9to10()
        assertEquals(9, second.from)
        assertEquals(10, second.to)
        assertTrue(second.isMigratable(9) && !second.isMigratable(8))
        assertEquals(3, second.handlers.size)
        val statements = MarketMigration8to9.statements() + MarketMigration9to10.statements()
        assertTrue(statements.map { it("pano_") }.all { it.startsWith("CREATE TABLE IF NOT EXISTS") })
        assertEquals(10, chain.last()().to)
    }

    @Test
    fun `steps 8 to 9 and 9 to 10 create the empty tables, leave the others untouched and change nothing when run twice`(): Unit = runBlocking {
        for (step in chain.take(6)) step().migrate(pool)
        val columns = columnsOfCurrentTables()
        val before = dump(columns)
        assertTrue(step9Tables.none { it in columns.keys })

        MarketMigration8to9().migrate(pool)
        MarketMigration9to10().migrate(pool)

        assertEquals(before, dump(columns))
        val after = columnsOfCurrentTables()
        assertTrue(step9Tables.all { it in after.keys })
        for (t in listOf("subscription", "subscription_renewal", "block", "throttle", "goal")) assertEquals(0L, count("market_$t"), t)
        val schema = SchemaSnapshot.take(pool)
        MarketMigration8to9().migrate(pool)
        MarketMigration9to10().migrate(pool)
        assertEquals(schema, SchemaSnapshot.take(pool))
        assertEquals(schema, freshSchema())
        assertEquals(emptyList<SchemaVerifier.Finding>(), SchemaVerifier.verify(pool, prefix).findings)
    }

    @Test
    fun `steps 8 to 9 and 9 to 10 interrupted after any number of handlers are completed by the full steps`(): Unit = runBlocking {
        runChain()
        val expected = SchemaSnapshot.take(pool)
        for ((step, stops) in listOf<Pair<() -> DatabaseMigration, List<Int>>>({ MarketMigration8to9() } to listOf(1), { MarketMigration9to10() } to listOf(1, 2))) {
            for (stopAfter in stops) {
                resetState()
                for (s in chain.take(6)) s().migrate(pool)
                if (step().to == 10) MarketMigration8to9().migrate(pool)
                for (handler in step().handlers.take(stopAfter)) handler(pool)
                assertTrue(SchemaSnapshot.take(pool) != expected, "interrupted after $stopAfter handlers of ${step().from} to ${step().to} is a partial schema")
                if (step().to == 9) MarketMigration8to9().migrate(pool)
                MarketMigration9to10().migrate(pool)
                assertEquals(expected, SchemaSnapshot.take(pool), "interrupted after $stopAfter handlers of ${step().from} to ${step().to}")
                assertEquals(emptyList<SchemaVerifier.Finding>(), SchemaVerifier.verify(pool, prefix).findings)
            }
        }
    }
}
