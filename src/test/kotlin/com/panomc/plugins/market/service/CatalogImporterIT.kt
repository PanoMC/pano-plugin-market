package com.panomc.plugins.market.service

import com.panomc.plugins.market.db.MarketDaoITBase
import com.panomc.plugins.market.spi.common.Money
import com.panomc.plugins.market.spi.payment.ActionResult
import com.panomc.plugins.market.spi.payment.ImportedCategory
import com.panomc.plugins.market.spi.payment.ImportedProduct
import com.panomc.plugins.market.spi.payment.IntervalUnit
import com.panomc.plugins.market.support.TestWiring
import io.vertx.core.json.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** `CatalogImporter` on a real MariaDB: create, re-import updates by provider meta, categories by name, skips, currency guard. */
class CatalogImporterIT : MarketDaoITBase() {
    private val w by lazy { TestWiring(pool) }
    private val catalog by lazy {
        CatalogService(w.db, { w.config }, w.clock, w.products, w.variants, w.prices, w.fields, w.bundleItems, w.providerMeta, w.categories, w.comparisons)
    }
    private val importer by lazy { CatalogImporter(w.db, { w.config }, catalog, w.categories, w.products, w.providerMeta) }

    override suspend fun assertInvariants() {}

    private fun product(id: String, name: String, cents: Long, category: String?, currency: String = "EUR", recurring: Boolean = false) =
        ImportedProduct(
            externalId = id, name = name, descriptionHtml = "<p>$name</p><script>x()</script>", price = Money(cents, currency),
            categoryExternalId = category, imageUrl = null,
            intervalUnit = if (recurring) IntervalUnit.MONTH else null, intervalCount = if (recurring) 1 else null,
            meta = JsonObject().put("packageId", id)
        )

    private fun catalogue(vararg products: ImportedProduct) = ActionResult.CatalogImport(
        listOf(ImportedCategory("c2", "Kits", "c1"), ImportedCategory("c1", "Ranks", null)),
        products.toList()
    )

    @Test
    fun `first import creates categories and products, a second import updates them in place`(): Unit = runBlocking {
        val first = importer.import("tebex", catalogue(product("10", "VIP", 500, "c1"), product("11", "Starter Kit", 250, "c2"), product("12", "Monthly", 300, "c1", recurring = true)))

        assertTrue(first.success)
        assertEquals(2L, count("market_product"))
        assertEquals(2L, count("market_category"))

        val all = w.db.tx { c -> w.categories.getAll(null, c) }
        val ranks = all.first { it.name == "Ranks" }
        val kits = all.first { it.name == "Kits" }
        assertNull(ranks.parentId)
        assertEquals(ranks.id, kits.parentId)

        val metas = w.db.tx { c -> w.providerMeta.getByProviderId("tebex", c) }
        assertEquals(setOf("10", "11"), metas.map { JsonObject(it.meta).getString("packageId") }.toSet())
        val vipId = metas.first { JsonObject(it.meta).getString("packageId") == "10" }.productId
        val vip = catalog.get(vipId).product
        assertEquals(500L, vip.price)
        assertEquals(ranks.id, vip.categoryId)
        assertFalse(vip.description.orEmpty().contains("script"))

        val second = importer.import("tebex", catalogue(product("10", "VIP+", 900, "c1"), product("11", "Starter Kit", 250, "c2")))

        assertTrue(second.success)
        assertEquals(2L, count("market_product"))
        assertEquals(2L, count("market_category"))
        val updated = catalog.get(vipId).product
        assertEquals("VIP+", updated.name)
        assertEquals(900L, updated.price)
    }

    @Test
    fun `a name another product already has gets a free slug`(): Unit = runBlocking {
        importer.import("tebex", catalogue(product("20", "Gold", 100, null)))
        val result = importer.import("paynow", catalogue(product("20", "Gold", 100, null)))

        assertTrue(result.success)
        assertEquals(2L, count("market_product"))
    }

    @Test
    fun `a foreign currency imports nothing`(): Unit = runBlocking {
        val result = importer.import("tebex", catalogue(product("30", "VIP", 500, "c1", currency = "USD")))

        assertFalse(result.success)
        assertEquals(0L, count("market_product"))
        assertEquals(0L, count("market_category"))
    }

    @Test
    fun `parents come first, a cycle does not loop`() {
        val ordered = CatalogImporter.parentsFirst(
            listOf(ImportedCategory("a", "A", "b"), ImportedCategory("b", "B", null), ImportedCategory("x", "X", "y"), ImportedCategory("y", "Y", "x"))
        ).map { it.externalId }

        assertTrue(ordered.indexOf("b") < ordered.indexOf("a"))
        assertEquals(4, ordered.size)
    }
}
