package com.panomc.plugins.market.core.catalog

import com.panomc.plugins.market.db.model.BillingMode
import com.panomc.plugins.market.db.model.MarketProduct
import com.panomc.plugins.market.db.model.PeriodUnit
import com.panomc.plugins.market.db.model.ProductFieldType
import com.panomc.plugins.market.db.model.ProductKind
import com.panomc.plugins.market.util.MarketStatus
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProductRequestParserTest {
    private fun parse(vararg pairs: Pair<String, Any?>): ProductInput = ProductRequestParser.parse(JsonObject(mapOf(*pairs)))

    @Test
    fun `only the keys that were sent are present so a partial update leaves the rest alone`() {
        val input = parse("name" to "VIP", "price" to "12.50")

        assertEquals(setOf("name", "price"), input.scalars.keys)
        assertEquals(1250L, input.scalars["price"])
        assertNull(input.variants)
        assertNull(input.fields)
        assertNull(input.bundleItems)
        assertNull(input.prices)
        assertNull(input.providerMeta)
        assertNull(input.actions)
        assertTrue(input.parseErrors.isEmpty())

        val stored = MarketProduct(id = 5, slug = "vip", name = "Old", price = 999, creditPrice = 300, featured = true, stock = 7, soldCount = 4)
        val merged = input.applyTo(stored, "vip", 1000)

        assertEquals("VIP", merged.name)
        assertEquals(1250L, merged.price)
        assertEquals(300L, merged.creditPrice)
        assertTrue(merged.featured)
        assertEquals(7, merged.stock)
        assertEquals(4, merged.soldCount)
    }

    @Test
    fun `stock is honoured on create only`() {
        val input = parse("name" to "x", "stock" to 5)

        assertEquals(5, input.applyTo(null, "x", 1).stock)
        assertEquals(9, input.applyTo(MarketProduct(id = 1, stock = 9), "x", 1).stock)
        // an update that clears nothing and a stored unlimited stock stay unlimited
        assertNull(input.applyTo(MarketProduct(id = 1, stock = null), "x", 1).stock)
    }

    @Test
    fun `money is read as exact decimals from numbers and strings and rounds half up at two places`() {
        assertEquals(1999L, parse("price" to 19.99).scalars["price"])
        assertEquals(1999L, parse("price" to "19.99").scalars["price"])
        assertEquals(2000L, parse("price" to 20).scalars["price"])
        assertEquals(1L, parse("price" to "0.005").scalars["price"])
        assertEquals(0L, parse("price" to "").scalars["price"])
        assertNull(parse("compareAtPrice" to "").scalars["compareAtPrice"])
        assertNull(parse("compareAtPrice" to null).scalars["compareAtPrice"])
        // the classic double trap: 1.15 * 100 is 114.99999999999999
        assertEquals(115L, parse("price" to 1.15).scalars["price"])
        assertEquals(29L, parse("price" to "0.29").scalars["price"])
    }

    @Test
    fun `bad values land in parseErrors under their key and nothing throws`() {
        val input = parse(
            "price" to "abc", "stock" to "1.5", "featured" to "maybe", "status" to "DELETED", "kind" to "GADGET",
            "categoryId" to "x", "requiredProducts" to "[1,\"a\"]", "variants" to "{oops", "price2" to 1
        )

        assertEquals(
            setOf("price", "stock", "featured", "status", "kind", "categoryId", "requiredProducts", "variants"),
            input.parseErrors.keys
        )
        assertTrue(input.parseErrors.values.all { it == "INVALID" })
    }

    @Test
    fun `an absurd amount is OUT_OF_RANGE and a huge integer for an int column is OUT_OF_RANGE`() {
        assertEquals("OUT_OF_RANGE", parse("price" to "1e15").parseErrors["price"])
        assertEquals("OUT_OF_RANGE", parse("weightGrams" to "99999999999").parseErrors["weightGrams"])
    }

    @Test
    fun `blank and null clear a nullable column and fall back to the default of a required one`() {
        val input = parse(
            "description" to "", "shortDescription" to null, "sku" to " ", "categoryId" to "-1", "status" to "", "icon" to "",
            "kind" to "", "billingMode" to "", "periodUnit" to "", "vatPercent" to "", "allowGift" to ""
        )

        assertNull(input.scalars["description"])
        assertNull(input.scalars["shortDescription"])
        assertNull(input.scalars["sku"])
        assertNull(input.scalars["categoryId"])
        assertEquals(MarketStatus.ACTIVE, input.scalars["status"])
        assertEquals("fa-box", input.scalars["icon"])
        assertEquals(ProductKind.STANDARD, input.scalars["kind"])
        assertEquals(BillingMode.ONE_TIME, input.scalars["billingMode"])
        assertNull(input.scalars["periodUnit"])
        assertNull(input.scalars["vatPercent"])
        assertEquals(true, input.scalars["allowGift"])
        assertTrue(input.parseErrors.isEmpty())
    }

    @Test
    fun `values are normalised on the way in`() {
        val input = parse(
            "hsCode" to "6109.10 00", "originCountry" to " tr ", "vatPercent" to "20", "requiredProducts" to "[1,\"2\",2]",
            "serverChoices" to "[3,\"4\",3]", "periodUnit" to "MONTH", "billingMode" to "SUBSCRIPTION"
        )

        assertEquals("61091000", input.scalars["hsCode"])
        assertEquals("TR", input.scalars["originCountry"])
        assertEquals(2000L, input.scalars["vatPercent"])
        assertEquals(listOf(1L, 2L), input.scalars["requiredProducts"])
        assertEquals("[3,4]", input.scalars["serverChoices"])
        assertEquals(PeriodUnit.MONTH, input.scalars["periodUnit"])
        assertEquals(BillingMode.SUBSCRIPTION, input.scalars["billingMode"])
        assertEquals(emptyMap<String, String>(), input.parseErrors)
    }

    @Test
    fun `an empty serverChoices array clears the column`() {
        assertNull(parse("serverChoices" to "[]").scalars["serverChoices"])
        assertEquals("INVALID", parse("serverChoices" to "[0]").parseErrors["serverChoices"])
    }

    @Test
    fun `variants fields prices bundle items and provider meta arrive as drafts when present`() {
        val input = parse(
            "variants" to JsonArray().add(
                JsonObject().put("name", "L / Red").put("price", "9.99").put("stock", 4).put("optionValues", JsonObject().put("size", "l"))
                    .put("attributes", JsonObject().put("color", "red")).put("prices", JsonArray().add(JsonObject().put("currency", "usd").put("price", 11)))
            ).add(JsonObject().put("id", 7).put("name", "S").put("removeImage", true)).encode(),
            "fields" to JsonArray().add(
                JsonObject().put("fieldKey", "nick").put("label", "Nick").put("type", "SELECT").put("required", "true")
                    .put("options", JsonArray().add(JsonObject().put("value", "a").put("label", "A")))
            ).encode(),
            "bundleItems" to JsonArray().add(JsonObject().put("productId", 3).put("quantity", 2)).encode(),
            "prices" to JsonArray().add(JsonObject().put("variantId", 7).put("currency", "eur").put("price", "5")).encode(),
            "providerMeta" to JsonObject().put("stripe", JsonObject().put("packageId", "123")).encode()
        )

        assertEquals(emptyMap<String, String>(), input.parseErrors)

        val variants = input.variants!!
        assertEquals(2, variants.size)
        assertEquals("L / Red", variants[0].name)
        assertNull(variants[0].id)
        assertEquals(999L, variants[0].price)
        assertEquals(4, variants[0].stock)
        assertEquals(mapOf("size" to "l"), variants[0].optionValues)
        assertEquals(mapOf("color" to "red"), variants[0].attributes)
        assertEquals("USD", variants[0].prices!![0].currency)
        assertEquals(1100L, variants[0].prices!![0].price)
        assertEquals(7L, variants[1].id)
        assertTrue(variants[1].image is ImageChange.Remove)
        assertNull(variants[1].prices)
        // sending variants without hasVariants means "has variants when the list is not empty"
        assertEquals(true, input.scalars["hasVariants"])

        val field = input.fields!!.single()
        assertEquals(ProductFieldType.SELECT, field.type)
        assertTrue(field.required)
        assertEquals(listOf("a" to "A"), field.options)

        assertEquals(BundleItemDraft(3, 0, 2, null).let { Triple(it.productId, it.variantId, it.quantity) }, input.bundleItems!!.single().let { Triple(it.productId, it.variantId, it.quantity) })
        assertEquals(7L, input.prices!!.single().variantId)
        assertEquals("EUR", input.prices!!.single().currency)
        assertEquals("""{"packageId":"123"}""", input.providerMeta!!["stripe"])
    }

    @Test
    fun `an empty array is a present empty set which clears it, an absent key leaves it`() {
        val input = parse("variants" to "[]", "fields" to "", "prices" to JsonArray())

        assertEquals(emptyList<VariantDraft>(), input.variants)
        assertEquals(emptyList<FieldDraft>(), input.fields)
        assertEquals(emptyList<PriceDraft>(), input.prices)
        assertNull(input.bundleItems)
        assertEquals(false, input.scalars["hasVariants"])
    }

    @Test
    fun `an explicit hasVariants is not overridden by the derivation`() {
        val input = parse("variants" to """[{"name":"A"}]""", "hasVariants" to "false")

        assertEquals(false, input.scalars["hasVariants"])
    }

    @Test
    fun `errors inside set parts carry the dotted path`() {
        val input = parse(
            "variants" to """[{"name":"ok"},{"name":"x","price":"abc"},"not an object"]""",
            "fields" to """[{"fieldKey":"a","label":"A","minLength":"x"}]""",
            "prices" to """[{"currency":"EUR"}]""",
            "bundleItems" to """[{"quantity":2}]"""
        )

        assertEquals("INVALID", input.parseErrors["variants.1.price"])
        assertEquals("INVALID", input.parseErrors["variants.2"])
        assertEquals("INVALID", input.parseErrors["fields.0.minLength"])
        assertEquals("REQUIRED", input.parseErrors["prices.0.price"])
        assertEquals("REQUIRED", input.parseErrors["bundleItems.0.productId"])
    }

    @Test
    fun `provider meta must be objects under a plain provider id and stay small`() {
        assertEquals("INVALID", parse("providerMeta" to """{"Bad Id":{}}""").parseErrors["providerMeta.Bad Id"])
        assertEquals("INVALID", parse("providerMeta" to """{"stripe":"x"}""").parseErrors["providerMeta.stripe"])
        assertEquals("INVALID", parse("providerMeta" to "[1]").parseErrors["providerMeta"])
        val big = JsonObject().put("stripe", JsonObject().put("blob", "x".repeat(9000))).encode()
        assertEquals("TOO_LONG", parse("providerMeta" to big).parseErrors["providerMeta.stripe"])
    }

    @Test
    fun `actions are normalised and their errors surface with the actions path`() {
        val ok = parse("actions" to """[{"type":"CREDIT","value":5}]""")
        assertNotNull(ok.actions)
        assertTrue(ok.actions!!.contains("\"a1\""))

        val bad = parse("actions" to """[{"type":"NOPE"}]""")
        assertEquals("INVALID", bad.parseErrors["actions.0.type"])
        assertNull(bad.actions)
    }

    @Test
    fun `image changes come from the stored uploads and removeImage`() {
        assertTrue(ProductRequestParser.parse(JsonObject(), ImageChange.Keep).image is ImageChange.Keep)
        assertTrue(ProductRequestParser.parse(JsonObject().put("removeImage", "true")).image is ImageChange.Remove)
        val set = ProductRequestParser.parse(JsonObject().put("removeImage", "true"), ImageChange.Set("a.png")).image
        assertEquals("a.png", (set as ImageChange.Set).fileName)

        val withVariantImage = ProductRequestParser.parse(JsonObject().put("variants", """[{"name":"A"},{"name":"B"}]"""), variantImages = mapOf(1 to "v.png"))
        assertTrue(withVariantImage.variants!![0].image is ImageChange.Keep)
        assertEquals("v.png", (withVariantImage.variants!![1].image as ImageChange.Set).fileName)
    }

    @Test
    fun `forced values - a bundle is never physical, periods belong to timed and subscription, tiers and periods force quantity one`() {
        val bundle = parse("kind" to "BUNDLE", "physical" to true, "creditAmount" to 5).applyTo(null, "b", 1)
        assertFalse(bundle.physical)
        assertNull(bundle.creditAmount)

        val oneTime = parse("billingMode" to "ONE_TIME", "periodUnit" to "DAY", "periodCount" to 3, "subscriptionMaxCycles" to 2).applyTo(null, "b", 1)
        assertNull(oneTime.periodUnit)
        assertNull(oneTime.periodCount)
        assertNull(oneTime.subscriptionMaxCycles)

        val timed = parse("billingMode" to "TIMED", "periodUnit" to "DAY", "periodCount" to 30, "subscriptionMaxCycles" to 2, "maxQuantityPerOrder" to 5).applyTo(null, "b", 1)
        assertEquals(PeriodUnit.DAY, timed.periodUnit)
        assertEquals(30, timed.periodCount)
        assertNull(timed.subscriptionMaxCycles)
        assertEquals(1, timed.maxQuantityPerOrder)

        val tiered = parse("maxQuantityPerOrder" to 5).applyTo(null, "b", 1, categoryTiered = true)
        assertEquals(1, tiered.maxQuantityPerOrder)
        assertEquals(5, parse("maxQuantityPerOrder" to 5).applyTo(null, "b", 1).maxQuantityPerOrder)

        val pack = parse("kind" to "CREDIT_PACK", "creditAmount" to 25).applyTo(null, "p", 1)
        assertEquals(2500L, pack.creditAmount)
    }
}
