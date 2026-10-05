package com.panomc.plugins.market.db

import com.panomc.plugins.market.core.time.Ids
import com.panomc.plugins.market.db.impl.MarketOrderDaoImpl
import com.panomc.plugins.market.db.impl.MarketOrderItemDaoImpl
import com.panomc.plugins.market.db.migration.MarketMigration2to3
import com.panomc.plugins.market.db.migration.MarketMigration3to4
import com.panomc.plugins.market.db.migration.MarketMigration4to5
import com.panomc.plugins.market.db.model.MarketOrder
import com.panomc.plugins.market.db.model.MarketOrderItem
import com.panomc.plugins.market.db.model.OrderSource
import com.panomc.plugins.market.support.MarketMigrationTestBase
import com.panomc.plugins.market.support.SeqIds
import io.vertx.sqlclient.Row
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The legacy fixups of 01 section 14.2 on the frozen scheme-version-2 install (17 section 11.3
 * `MigrationFixupRerunIT`, the order part): after the chain the three seeded orders and five items are converted by
 * `ensure()` (source `LEGACY`, `publicId` and `accessToken` from `Ids`, payer / recipient keys, base currency, subtotal,
 * gateway amount, line totals, `paidAt` and `COMMITTED`), a fixup whose columns were nulled again runs again, the
 * one-shot fixups (`soldCount`, `legacyUsedCount`) run exactly once, and orders written by the new code are left alone.
 */
class MigrationFixupRerunIT : MarketMigrationTestBase() {
    private suspend fun migrate() {
        MarketMigration2to3().migrate(pool)
        MarketMigration3to4().migrate(pool)
        MarketMigration4to5().migrate(pool)
    }

    private suspend fun ensure(ids: Ids = SeqIds()) =
        MarketSchema.ensure(pool, prefix, MarketSchema.tables, MarketSchema.fixups(ids))

    private suspend fun orders(): List<Row> = sql("SELECT * FROM `pano_market_order` ORDER BY `id`")

    @Test
    fun `ensure after the chain converts the legacy orders and items`(): Unit = runBlocking {
        migrate()
        val ids = SeqIds()
        val report = ensure(ids)
        assertTrue(report.clean, report.fixupErrors.toString())
        assertEquals(MarketSchema.fixups().map { it.id }, report.fixupsRun)

        val rows = orders()
        assertEquals(3, rows.size)
        for (row in rows) {
            assertEquals("LEGACY", row.getString("source"))
            assertTrue(Ids.PUBLIC_ID_REGEX.matches(row.getString("publicId")), row.getString("publicId"))
            assertEquals(40, row.getString("accessToken").length)
            assertEquals("u:${row.getLong("userId")}", row.getString("buyerKey"))
            assertEquals(row.getString("playerUsername"), row.getString("recipientUsername"))
            assertEquals(row.getString("buyerKey"), row.getString("recipientKey"))
            assertEquals(row.getLong("userId"), row.getLong("recipientUserId"))
            assertEquals(row.getString("currency"), row.getString("baseCurrency"))
            assertEquals(row.getLong("totalPrice"), row.getLong("subtotal"))
            assertEquals(row.getLong("totalPrice"), row.getLong("gatewayAmount"))
            assertEquals(0L, row.getLong("creditValue"))
        }
        // publicIds are unique and came from the Ids given to the fixup (the sequence ids are 18 zeros + 2 characters)
        val publicIds = rows.map { it.getString("publicId") }
        assertEquals(3, publicIds.toSet().size)
        assertTrue(publicIds.all { it.startsWith("0".repeat(18)) }, publicIds.toString())
        assertEquals(6L, ids.issued) // one public id and one access token per order, nothing else

        // paid legacy orders: COMPLETED (2) and REFUNDED (3); the PENDING one (1) stays unpaid
        val byId = rows.associateBy { it.getLong("id") }
        for (id in listOf(2L, 3L)) {
            val row = byId.getValue(id)
            assertEquals(row.getLong("updatedAt"), row.getLong("paidAt"))
            assertEquals(row.getLong("totalPrice"), row.getLong("paidAmount"))
            assertEquals("COMMITTED", row.getString("reservationState"))
        }
        assertNull(byId.getValue(1).getValue("paidAt"))
        assertEquals(0L, byId.getValue(1).getLong("paidAmount"))
        assertEquals("NONE", byId.getValue(1).getString("reservationState"))
        assertEquals("COMPLETED", byId.getValue(2).getString("status"))
        assertEquals("REFUNDED", byId.getValue(3).getString("status"))

        // items: list price and line total from the unit price and quantity; the order arithmetic of I8 holds
        for (row in sql("SELECT * FROM `pano_market_order_item` ORDER BY `id`")) {
            assertEquals(row.getLong("unitPrice"), row.getLong("listUnitPrice"))
            assertEquals(row.getLong("unitPrice") * row.getInteger("quantity"), row.getLong("lineTotal"))
        }
        assertEquals(
            emptyList<Long>(),
            sql(
                "SELECT o.`id` FROM `pano_market_order` o WHERE o.`totalPrice` <> (SELECT COALESCE(SUM(i.`lineTotal`), 0) FROM `pano_market_order_item` i WHERE i.`orderId` = o.`id`) + o.`shippingTotal` + o.`paymentFee` " +
                    "OR o.`gatewayAmount` + o.`creditValue` <> o.`totalPrice`"
            ).map { it.getLong("id") }
        )

        val verdict = SchemaVerifier.verify(pool, prefix)
        assertTrue(verdict.ok, verdict.describe().toString())
    }

    @Test
    fun `a second ensure changes nothing and the real random ids have the public id format`(): Unit = runBlocking {
        migrate()
        assertTrue(MarketSchema.ensure(pool, prefix).clean) // production path: SecureIds
        val first = orders().map { listOf(it.getString("publicId"), it.getString("accessToken")) }
        assertTrue(first.all { Ids.PUBLIC_ID_REGEX.matches(it[0]) && Regex("^[0-9a-f]{40}$").matches(it[1]) })
        assertEquals(3, first.toSet().size)
        val second = MarketSchema.ensure(pool, prefix)
        assertTrue(second.clean && second.fixupsRun.isEmpty(), second.fixupsRun.toString())
        assertEquals(first, orders().map { listOf(it.getString("publicId"), it.getString("accessToken")) })
    }

    @Test
    fun `nulling the fixed columns and running ensure again brings the values back`(): Unit = runBlocking {
        migrate()
        ensure()
        val before = orders().map { row -> row.toJson().encode() }
        val itemsBefore = sql("SELECT * FROM `pano_market_order_item` ORDER BY `id`").map { it.toJson().encode() }

        sql(
            "UPDATE `pano_market_order` SET `paidAt` = NULL, `paidAmount` = 0, `reservationState` = 'NONE', `subtotal` = 0, `gatewayAmount` = 0, " +
                "`baseCurrency` = '', `recipientKey` = '', `recipientUsername` = '', `recipientUserId` = NULL"
        )
        sql("UPDATE `pano_market_order_item` SET `lineTotal` = 0, `listUnitPrice` = 0")
        val report = ensure()
        assertTrue(report.clean, report.fixupErrors.toString())
        assertEquals(
            listOf("order-recipient-username", "order-recipient-key", "order-base-currency", "order-legacy-totals", "order-item-money", "legacy-paid-orders"),
            report.fixupsRun
        )
        // everything is back (publicId, accessToken and buyerKey were not touched, so those fixups saw nothing to do)
        assertEquals(before, orders().map { it.toJson().encode() })
        assertEquals(itemsBefore, sql("SELECT * FROM `pano_market_order_item` ORDER BY `id`").map { it.toJson().encode() })
    }

    @Test
    fun `a legacy row that lost its public id, buyer key and marker is converted again`(): Unit = runBlocking {
        migrate()
        ensure()
        val publicIdOf2 = orders().single { it.getLong("id") == 2L }.getString("publicId")
        sql("UPDATE `pano_market_order` SET `publicId` = NULL, `accessToken` = NULL, `buyerKey` = '', `source` = 'STOREFRONT' WHERE `id` = 2")
        val ids = SeqIds(100)
        val report = ensure(ids)
        assertTrue(report.clean, report.fixupErrors.toString())
        val row = orders().single { it.getLong("id") == 2L }
        assertEquals("LEGACY", row.getString("source"))
        assertEquals("u:102", row.getString("buyerKey"))
        assertTrue(Ids.PUBLIC_ID_REGEX.matches(row.getString("publicId")))
        assertTrue(row.getString("publicId") != publicIdOf2)
        assertEquals(2L, ids.issued - 100) // exactly one public id and one token
    }

    @Test
    fun `one-shot fixups run once, write their marker rows and fill the counters`(): Unit = runBlocking {
        migrate()
        ensure()
        // soldCount: quantities of the LEGACY COMPLETED order 2 (products 2 and 3); the PENDING and REFUNDED orders count nothing
        assertEquals(
            mapOf(1L to 0, 2L to 2, 3L to 1, 4L to 0, 5L to 0, 6L to 0),
            sql("SELECT `id`, `soldCount` FROM `pano_market_product` ORDER BY `id`").associate { it.getLong("id") to it.getInteger("soldCount") }
        )
        // legacyUsedCount copies usedCount of discounts, coupons and creator codes
        assertEquals(listOf(7, 0), sql("SELECT `legacyUsedCount` FROM `pano_market_discount` ORDER BY `id`").map { it.getInteger(0) })
        assertEquals(listOf(4, 0), sql("SELECT `legacyUsedCount` FROM `pano_market_coupon` ORDER BY `id`").map { it.getInteger(0) })
        assertEquals(listOf(2), sql("SELECT `legacyUsedCount` FROM `pano_market_creator_code`").map { it.getInteger(0) })
        assertEquals(
            listOf("fixup:legacyUsedCount", "fixup:soldCount"),
            sql("SELECT `name` FROM `pano_market_sequence` WHERE `name` LIKE 'fixup:%' ORDER BY `name`").map { it.getString(0) }
        )
        assertEquals(listOf(1L, 1L), sql("SELECT `value` FROM `pano_market_sequence` WHERE `name` LIKE 'fixup:%'").map { it.getLong(0) })

        // the counters move on afterwards; ensure never overwrites them again
        sql("UPDATE `pano_market_product` SET `soldCount` = 99 WHERE `id` = 2")
        sql("UPDATE `pano_market_coupon` SET `usedCount` = 9 WHERE `id` = 1")
        val report = ensure()
        assertFalse("soldCount" in report.fixupsRun || "legacyUsedCount" in report.fixupsRun, report.fixupsRun.toString())
        assertEquals(99, sql("SELECT `soldCount` FROM `pano_market_product` WHERE `id` = 2").single().getInteger(0))
        assertEquals(4, sql("SELECT `legacyUsedCount` FROM `pano_market_coupon` WHERE `id` = 1").single().getInteger(0))
        assertEquals(1L, count("market_sequence", "`name` = 'fixup:soldCount'"))
        assertEquals(1L, count("market_sequence", "`name` = 'fixup:legacyUsedCount'"))
    }

    @Test
    fun `an order written by the new code is not touched by any fixup`(): Unit = runBlocking {
        migrate()
        val orderDao = MarketOrderDaoImpl()
        val itemDao = MarketOrderItemDaoImpl()
        // a gift-code order: priced line that totals 0, nothing paid by a gateway
        val id = orderDao.add(
            MarketOrder(
                userId = 7, playerUsername = "Gifter", totalPrice = 0, currency = "USD", paymentMethodId = "free",
                status = com.panomc.plugins.market.util.OrderStatus.COMPLETED, publicId = "N".repeat(20), accessToken = "c".repeat(40),
                source = OrderSource.GIFT_CODE, buyerKey = "u:7", recipientUsername = "Gifter", recipientKey = "u:7",
                baseCurrency = "EUR", subtotal = 1000, discountTotal = 1000, updatedAt = 5
            ),
            pool
        )
        itemDao.add(MarketOrderItem(orderId = id, productId = 2, productName = "Starter Crate", quantity = 1, unitPrice = 1000, listUnitPrice = 1000, lineTotal = 0), pool)

        val report = ensure()
        assertTrue(report.clean, report.fixupErrors.toString())
        val row = orders().single { it.getLong("id") == id }
        assertEquals("GIFT_CODE", row.getString("source"))
        assertEquals("N".repeat(20), row.getString("publicId"))
        assertEquals("c".repeat(40), row.getString("accessToken"))
        assertEquals("EUR", row.getString("baseCurrency"))
        assertEquals(1000L, row.getLong("subtotal"))
        assertEquals(0L, row.getLong("gatewayAmount"))
        assertNull(row.getValue("paidAt"))
        assertEquals("NONE", row.getString("reservationState"))
        assertEquals(0L, sql("SELECT `lineTotal` FROM `pano_market_order_item` WHERE `orderId` = ?", id).single().getLong(0))
        // and it is not counted as sold by the legacy soldCount fixup
        assertEquals(2, sql("SELECT `soldCount` FROM `pano_market_product` WHERE `id` = 2").single().getInteger(0))
    }

    @Test
    fun `a public id that collides is drawn again`(): Unit = runBlocking {
        migrate()
        val taken = "T".repeat(20)
        sql("UPDATE `pano_market_order` SET `publicId` = ?, `buyerKey` = 'u:101', `source` = 'STOREFRONT' WHERE `id` = 1", taken)
        val seq = SeqIds(500)
        var first = true
        val ids = object : Ids by seq {
            override fun publicId(): String = if (first) { first = false; taken } else seq.publicId()
        }
        val report = ensure(ids)
        assertTrue(report.clean, report.fixupErrors.toString())
        val publicIds = orders().map { it.getString("publicId") }
        assertEquals(3, publicIds.toSet().size)
        assertEquals(taken, publicIds.first())
    }

    @Test
    fun `a failing marker statement stops the dependent fixups instead of leaving unmarked legacy rows`(): Unit = runBlocking {
        migrate()
        // `source` too narrow for 'LEGACY': the marker UPDATE fails inside every fixup that starts with it
        sql("UPDATE `pano_market_order` SET `source` = 'STOR'")
        sql("ALTER TABLE `pano_market_order` MODIFY COLUMN `source` VARCHAR(4) NOT NULL DEFAULT 'STOR'")
        val broken = ensure()
        assertFalse(broken.clean)
        // nothing was filled in while the marker could not be written
        assertEquals(3L, count("market_order", "`publicId` IS NULL"))
        assertEquals(3L, count("market_order", "`buyerKey` = ''"))
        val verdict = SchemaVerifier.verify(pool, prefix)
        assertFalse(verdict.ok)
        assertTrue(verdict.unfixed.containsKey("legacy-order-marker"), verdict.describe().toString())

        // the obstacle is removed (a later start): everything is converted and marked
        sql("ALTER TABLE `pano_market_order` MODIFY COLUMN `source` VARCHAR(24) NOT NULL DEFAULT 'STOREFRONT'")
        val fixed = ensure()
        assertTrue(fixed.clean, fixed.fixupErrors.toString())
        assertEquals(3L, count("market_order", "`source` = 'LEGACY' AND `publicId` IS NOT NULL AND `buyerKey` <> ''"))
        assertTrue(SchemaVerifier.verify(pool, prefix).ok)
    }
}
