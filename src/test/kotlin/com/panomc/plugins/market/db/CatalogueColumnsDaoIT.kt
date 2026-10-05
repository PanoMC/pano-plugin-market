package com.panomc.plugins.market.db

import com.panomc.plugins.market.db.impl.MarketCategoryDaoImpl
import com.panomc.plugins.market.db.impl.MarketProductDaoImpl
import com.panomc.plugins.market.db.model.MarketCategory
import com.panomc.plugins.market.db.model.MarketProduct
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The version 3 columns of `market_category` (+2) and `market_product` (+29) next to the unchanged existing DAOs
 * (01 sections 2.1, 2.2): the old insert, update and read paths keep working and the new columns carry their
 * defaults; the generic update never writes `soldCount`.
 */
class CatalogueColumnsDaoIT : MarketDaoITBase() {
    private val products = MarketProductDaoImpl()
    private val categories = MarketCategoryDaoImpl()

    @Test
    fun `a product written by the existing dao carries the defaults of the new columns`(): Unit = runBlocking {
        val id = products.add(MarketProduct(slug = "vip", name = "VIP", price = 1999), pool)
        val row = sql("SELECT * FROM `pano_market_product` WHERE `id` = ?", id).single()
        assertEquals("STANDARD", row.getString("kind"))
        assertEquals("ONE_TIME", row.getString("billingMode"))
        assertEquals(1, (row.getValue("allowGift") as Number).toInt())
        assertEquals(0, (row.getValue("physical") as Number).toInt())
        assertEquals(0, (row.getValue("hasVariants") as Number).toInt())
        assertEquals(0, row.getInteger("soldCount"))
        for (nullable in listOf(
            "shortDescription", "compareAtPrice", "vatPercent", "sku", "weightGrams", "lengthMm", "widthMm", "heightMm", "hsCode",
            "originCountry", "periodUnit", "periodCount", "subscriptionMaxCycles", "limitPerPlayer", "maxQuantityPerOrder",
            "cooldownSeconds", "tierRank", "creditAmount", "serverChoices", "variantOptions", "metaTitle", "metaDescription", "deletedAt"
        )) assertNull(row.getValue(nullable), nullable)

        val read = products.getById(id, pool)!!
        assertEquals("VIP", read.name)
        assertEquals(1999L, read.price)
        assertEquals(1, products.getAllPaged(1, null, null, pool).size)
    }

    @Test
    fun `every one of the 29 new product columns can be written and read back`(): Unit = runBlocking {
        val id = products.add(MarketProduct(slug = "box", name = "Box"), pool)
        sql(
            "UPDATE `pano_market_product` SET `kind` = 'CREDIT_PACK', `shortDescription` = 'short', `compareAtPrice` = 5000, " +
                "`vatPercent` = 2000, `physical` = 1, `sku` = 'S1', `weightGrams` = 100, `lengthMm` = 10, `widthMm` = 20, " +
                "`heightMm` = 30, `hsCode` = '6109.10', `originCountry` = 'TR', `billingMode` = 'SUBSCRIPTION', " +
                "`periodUnit` = 'MONTH', `periodCount` = 1, `subscriptionMaxCycles` = 12, `limitPerPlayer` = 2, " +
                "`maxQuantityPerOrder` = 3, `cooldownSeconds` = 86400, `tierRank` = 4, `creditAmount` = 10000, " +
                "`allowGift` = 0, `serverChoices` = '[1,2]', `hasVariants` = 1, `variantOptions` = '[]', `metaTitle` = 'mt', " +
                "`metaDescription` = 'md', `soldCount` = 9, `deletedAt` = 1700000000000 WHERE `id` = ?",
            id
        )
        val row = sql("SELECT * FROM `pano_market_product` WHERE `id` = ?", id).single()
        assertEquals("CREDIT_PACK", row.getString("kind"))
        assertEquals("short", row.getString("shortDescription"))
        assertEquals(5000L, row.getLong("compareAtPrice"))
        assertEquals(2000L, row.getLong("vatPercent"))
        assertEquals("S1", row.getString("sku"))
        assertEquals("6109.10", row.getString("hsCode"))
        assertEquals("TR", row.getString("originCountry"))
        assertEquals("SUBSCRIPTION", row.getString("billingMode"))
        assertEquals("MONTH", row.getString("periodUnit"))
        assertEquals(86400L, row.getLong("cooldownSeconds"))
        assertEquals(10000L, row.getLong("creditAmount"))
        assertEquals("[1,2]", row.getString("serverChoices"))
        assertEquals(9, row.getInteger("soldCount"))
        assertEquals(1_700_000_000_000, row.getLong("deletedAt"))
        // the existing dao still reads the row (extra columns are not entity fields)
        assertEquals("Box", products.getById(id, pool)!!.name)
    }

    @Test
    fun `the generic product update does not write soldCount`(): Unit = runBlocking {
        val id = products.add(MarketProduct(slug = "p", name = "P"), pool)
        sql("UPDATE `pano_market_product` SET `soldCount` = 4 WHERE `id` = ?", id)
        products.update(MarketProduct(id = id, slug = "p", name = "P renamed", price = 10), pool)
        assertEquals(4, sql("SELECT `soldCount` FROM `pano_market_product` WHERE `id` = ?", id).single().getInteger("soldCount"))
        assertEquals("P renamed", products.getById(id, pool)!!.name)
    }

    @Test
    fun `a category written by the existing dao carries tiered 0 and upgradeMode DIFFERENCE`(): Unit = runBlocking {
        val id = categories.add(MarketCategory(name = "Ranks"), pool)
        val row = sql("SELECT `tiered`, `upgradeMode` FROM `pano_market_category` WHERE `id` = ?", id).single()
        assertEquals(0, (row.getValue("tiered") as Number).toInt())
        assertEquals("DIFFERENCE", row.getString("upgradeMode"))
        sql("UPDATE `pano_market_category` SET `tiered` = 1, `upgradeMode` = 'FULL' WHERE `id` = ?", id)
        assertEquals("Ranks", categories.getById(id, pool)!!.name)
        categories.update(MarketCategory(id = id, name = "Ranks 2"), pool)
        val after = sql("SELECT `tiered`, `upgradeMode` FROM `pano_market_category` WHERE `id` = ?", id).single()
        assertEquals(1, (after.getValue("tiered") as Number).toInt())
        assertEquals("FULL", after.getString("upgradeMode"))
    }
}
