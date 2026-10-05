package com.panomc.plugins.market.routes.panel.product

import com.panomc.plugins.market.db.model.BillingMode
import com.panomc.plugins.market.db.model.MarketProduct
import com.panomc.plugins.market.db.model.MarketProductPrice
import com.panomc.plugins.market.db.model.MarketProductVariant
import com.panomc.plugins.market.db.model.ProductKind
import com.panomc.plugins.market.service.CatalogService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProductJsonTest {
    @Test
    fun `the detail carries every column and the set parts, money as decimals and vat as a percent`() {
        val view = CatalogService.ProductView(
            product = MarketProduct(
                id = 3, slug = "vip", name = "VIP", price = 1999, creditPrice = 500, compareAtPrice = 2999, vatPercent = 800,
                kind = ProductKind.CREDIT_PACK, creditAmount = 2500, billingMode = BillingMode.ONE_TIME,
                serverChoices = "[1,2]", variantOptions = """[{"key":"size"}]""", actions = """[{"type":"CREDIT","value":1}]"""
            ),
            variants = listOf(MarketProductVariant(id = 9, productId = 3, name = "L", price = 1500, optionValues = """{"size":"l"}""", stock = 4)),
            fields = emptyList(),
            bundleItems = emptyList(),
            prices = listOf(MarketProductPrice(productId = 3, variantId = 0, currency = "USD", price = 2200, compareAtPrice = null)),
            providerMeta = mapOf("stripe" to """{"packageId":"1"}""")
        )

        val json = ProductJson.detail(view)

        assertEquals(19.99, json.getDouble("price"))
        assertEquals(5.0, json.getDouble("creditPrice"))
        assertEquals(29.99, json.getDouble("compareAtPrice"))
        assertEquals(8.0, json.getDouble("vatPercent"))
        assertEquals(25.0, json.getDouble("creditAmount"))
        assertEquals("CREDIT_PACK", json.getString("kind"))
        assertEquals(listOf(1, 2), json.getJsonArray("serverChoices").map { it as Int })
        assertEquals("size", json.getJsonArray("variantOptions").getJsonObject(0).getString("key"))
        assertEquals("a1", json.getJsonArray("actions").getJsonObject(0).getString("id"))

        val variant = json.getJsonArray("variants").getJsonObject(0)
        assertEquals(15.0, variant.getDouble("price"))
        assertEquals("l", variant.getJsonObject("optionValues").getString("size"))
        assertNull(variant.getValue("compareAtPrice"))

        val price = json.getJsonArray("prices").getJsonObject(0)
        assertEquals("USD", price.getString("currency"))
        assertEquals(22.0, price.getDouble("price"))
        assertEquals("1", json.getJsonObject("providerMeta").getJsonObject("stripe").getString("packageId"))

        listOf("variants", "fields", "bundleItems", "prices", "providerMeta", "soldCount", "hasVariants", "allowGift", "metaTitle").forEach {
            assertTrue(json.containsKey(it), it)
        }
    }

    @Test
    fun `rows and picker rows carry the new list fields`() {
        val product = MarketProduct(id = 1, slug = "a", name = "A", kind = ProductKind.BUNDLE, hasVariants = true, soldCount = 7)

        assertEquals("BUNDLE", ProductJson.row(product).getString("kind"))
        assertEquals(7, ProductJson.row(product).getInteger("soldCount"))
        assertEquals(setOf("id", "name", "kind", "billingMode", "hasVariants", "status"), ProductJson.simple(product).fieldNames())
    }
}
