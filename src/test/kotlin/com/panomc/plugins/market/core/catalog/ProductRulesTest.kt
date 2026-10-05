package com.panomc.plugins.market.core.catalog

import com.panomc.plugins.market.db.model.MarketProduct
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The rule matrix of 04 section 5, 01 section 2.2 and 10 section 2.1 on the pure parser + rules pair. */
class ProductRulesTest {
    private fun base(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(mapOf("name" to "Item", "price" to "10") + mapOf(*pairs))

    /** Parse -> merge -> validate the way `CatalogService.save` does, without a database. */
    private fun errors(
        json: JsonObject,
        stored: MarketProduct? = null,
        currency: String = "EUR",
        tiered: Boolean = false,
        storedVariants: Int = 0,
        storedBundle: Int = 0
    ): Map<String, String> {
        val input = ProductRequestParser.parse(json)
        val slug = if (stored == null || input.has("slug")) SlugRules.resolve(input.scalars["slug"] as? String, (input.scalars["name"] ?: stored?.name) as? String) else stored.slug
        val product = input.applyTo(stored, slug, 1, tiered)

        return ProductRules.validate(
            product,
            input,
            ProductRules.Context(currency, stored == null, tiered, input.variants?.size ?: storedVariants, input.bundleItems?.size ?: storedBundle)
        )
    }

    private fun ok(json: JsonObject, vararg more: Any?) = assertEquals(emptyMap<String, String>(), errors(json))

    private fun assertCode(code: String, path: String, json: JsonObject, stored: MarketProduct? = null) =
        assertEquals(code, errors(json, stored)[path], "expected $path = $code in ${errors(json, stored)}")

    // ---- the basics

    @Test
    fun `a minimal product is valid and the name is required`() {
        ok(base())
        assertCode("REQUIRED", "name", JsonObject().put("price", 1))
        assertCode("REQUIRED", "name", base("name" to "  "))
        assertCode("TOO_LONG", "name", base("name" to "x".repeat(256)))
        assertCode("REQUIRED", "slug", base("name" to "!!!"))
    }

    @Test
    fun `reserved slugs are refused whichever way they were asked for`() {
        assertCode("RESERVED_SLUG", "slug", base("slug" to "checkout"))
        assertCode("RESERVED_SLUG", "slug", base("slug" to "Order"))
        assertCode("RESERVED_SLUG", "slug", base("name" to "Cart", "slug" to ""))
        ok(base("slug" to "checkout-pass"))
    }

    @Test
    fun `price ranges - no negative amounts, none above the cap`() {
        assertCode("OUT_OF_RANGE", "price", base("price" to "-1"))
        assertCode("OUT_OF_RANGE", "price", base("price" to "100000000"))
        ok(base("price" to "0"))
        ok(base("price" to "99999999.99"))
        assertCode("OUT_OF_RANGE", "creditPrice", base("creditPrice" to "-0.01"))
    }

    @Test
    fun `compareAtPrice must exceed the price`() {
        assertCode("MUST_EXCEED_PRICE", "compareAtPrice", base("compareAtPrice" to "10"))
        assertCode("MUST_EXCEED_PRICE", "compareAtPrice", base("compareAtPrice" to "9.99"))
        ok(base("compareAtPrice" to "10.01"))
        assertCode("OUT_OF_RANGE", "compareAtPrice", base("compareAtPrice" to "-5"))
    }

    @Test
    fun `zero-decimal currencies take whole units only`() {
        assertEquals(emptyMap<String, String>(), errors(base("price" to "500"), currency = "JPY"))
        assertEquals("NOT_WHOLE_UNITS", errors(base("price" to "500.5"), currency = "JPY")["price"])
        assertEquals("NOT_WHOLE_UNITS", errors(base("price" to "500", "compareAtPrice" to "700.25"), currency = "JPY")["compareAtPrice"])
        assertEquals(emptyMap<String, String>(), errors(base("price" to "500.5"), currency = "EUR"))
    }

    @Test
    fun `stock is range checked on create and ignored on update`() {
        assertCode("OUT_OF_RANGE", "stock", base("stock" to -1))
        assertCode("OUT_OF_RANGE", "stock", base("stock" to 1_000_000_001))
        ok(base("stock" to 0))
        ok(base("stock" to ""))
        // an update with an out-of-range stock is not an error: the value is never written
        assertEquals(emptyMap<String, String>(), errors(JsonObject().put("stock", -5), MarketProduct(id = 1, slug = "a", name = "A")))
    }

    @Test
    fun `status is one of ACTIVE, INACTIVE, ARCHIVED`() {
        ok(base("status" to "ARCHIVED"))
        assertCode("INVALID", "status", base("status" to "HIDDEN"))
    }

    @Test
    fun `text lengths and control characters`() {
        assertCode("TOO_LONG", "shortDescription", base("shortDescription" to "x".repeat(513)))
        assertCode("TOO_LONG", "metaTitle", base("metaTitle" to "x".repeat(256)))
        assertCode("TOO_LONG", "metaDescription", base("metaDescription" to "x".repeat(513)))
        assertCode("TOO_LONG", "description", base("description" to "x".repeat(100_001)))
        assertCode("INVALID", "name", base("name" to "bad\u0000name"))
        ok(base("description" to "x".repeat(100_000)))
    }

    @Test
    fun `vat override is a percent from 0 to 100`() {
        ok(base("vatPercent" to "0"))
        ok(base("vatPercent" to "100"))
        assertCode("OUT_OF_RANGE", "vatPercent", base("vatPercent" to "100.01"))
        assertCode("OUT_OF_RANGE", "vatPercent", base("vatPercent" to "-1"))
    }

    @Test
    fun `limits and counters`() {
        assertCode("OUT_OF_RANGE", "limitPerPlayer", base("limitPerPlayer" to 0))
        assertCode("OUT_OF_RANGE", "maxQuantityPerOrder", base("maxQuantityPerOrder" to 0))
        assertCode("OUT_OF_RANGE", "maxQuantityPerOrder", base("maxQuantityPerOrder" to 1001))
        assertCode("OUT_OF_RANGE", "cooldownSeconds", base("cooldownSeconds" to -1))
        assertCode("OUT_OF_RANGE", "tierRank", base("tierRank" to -1))
        assertCode("INVALID_RANGE", "durationExpiry", base("durationType" to "TEMPORARY", "durationStart" to 2000, "durationExpiry" to 1000))
        ok(base("limitPerPlayer" to 1, "maxQuantityPerOrder" to 1000, "cooldownSeconds" to 0))
    }

    @Test
    fun `a tiered category needs a tier rank`() {
        assertEquals("REQUIRED", errors(base(), tiered = true)["tierRank"])
        assertEquals(emptyMap<String, String>(), errors(base("tierRank" to 2), tiered = true))
    }

    // ---- kind and billing matrix

    @Test
    fun `physical needs STANDARD and ONE_TIME`() {
        ok(base("physical" to true, "weightGrams" to 100))
        assertCode("PHYSICAL_NOT_ALLOWED", "physical", base("physical" to true, "weightGrams" to 100, "billingMode" to "TIMED", "periodUnit" to "DAY", "periodCount" to 1))
        assertCode("PHYSICAL_NOT_ALLOWED", "physical", base("physical" to true, "weightGrams" to 100, "billingMode" to "SUBSCRIPTION", "periodUnit" to "MONTH", "periodCount" to 1))
        assertCode("PHYSICAL_NOT_ALLOWED", "physical", base("physical" to true, "weightGrams" to 100, "kind" to "CREDIT_PACK", "creditAmount" to 5))
        // a bundle's own flag is forced to 0, not refused
        assertEquals(emptyMap<String, String>(), errors(base("physical" to true, "kind" to "BUNDLE"), storedBundle = 1))
    }

    @Test
    fun `shipping data - weight required when physical, dimensions all or none, ranges`() {
        assertCode("REQUIRED", "weightGrams", base("physical" to true))
        assertCode("OUT_OF_RANGE", "weightGrams", base("weightGrams" to 0))
        assertCode("OUT_OF_RANGE", "weightGrams", base("weightGrams" to 1_000_001))
        ok(base("weightGrams" to 1_000_000))

        assertCode("DIMENSIONS_INCOMPLETE", "widthMm", base("lengthMm" to 10))
        assertCode("DIMENSIONS_INCOMPLETE", "heightMm", base("lengthMm" to 10, "widthMm" to 10))
        assertCode("OUT_OF_RANGE", "lengthMm", base("lengthMm" to 5001, "widthMm" to 1, "heightMm" to 1))
        assertCode("OUT_OF_RANGE", "heightMm", base("lengthMm" to 1, "widthMm" to 1, "heightMm" to 0))
        ok(base("lengthMm" to 5000, "widthMm" to 1, "heightMm" to 1))

        // physical false keeps the data as sent but never judges it as required
        ok(base("physical" to false, "lengthMm" to 10, "widthMm" to 10, "heightMm" to 10))
    }

    @Test
    fun `customs data - hs code, origin country and sku`() {
        ok(base("hsCode" to "6109.10", "originCountry" to "tr", "sku" to "A-1/b.2_c"))
        assertCode("INVALID", "hsCode", base("hsCode" to "12345"))
        assertCode("INVALID", "hsCode", base("hsCode" to "12345678901"))
        assertCode("INVALID", "hsCode", base("hsCode" to "61a910"))
        assertCode("INVALID", "originCountry", base("originCountry" to "XX"))
        assertCode("INVALID", "originCountry", base("originCountry" to "TUR"))
        assertCode("INVALID", "sku", base("sku" to "has space"))
        assertCode("INVALID", "sku", base("sku" to "a".repeat(65)))
    }

    @Test
    fun `CREDIT_PACK rules`() {
        ok(base("kind" to "CREDIT_PACK", "creditAmount" to "25"))
        assertCode("REQUIRED", "creditAmount", base("kind" to "CREDIT_PACK"))
        assertCode("REQUIRED", "creditAmount", base("kind" to "CREDIT_PACK", "creditAmount" to "0"))
        assertCode("MUST_BE_ZERO", "creditPrice", base("kind" to "CREDIT_PACK", "creditAmount" to "5", "creditPrice" to "1"))
        assertCode("NOT_ALLOWED", "billingMode", base("kind" to "CREDIT_PACK", "creditAmount" to "5", "billingMode" to "TIMED", "periodUnit" to "DAY", "periodCount" to 1))
        assertCode("NOT_ALLOWED", "hasVariants", base("kind" to "CREDIT_PACK", "creditAmount" to "5", "hasVariants" to true, "variants" to """[{"name":"a"}]"""))
    }

    @Test
    fun `TIMED needs a period and SUBSCRIPTION a billing interval`() {
        assertCode("REQUIRED", "periodUnit", base("billingMode" to "TIMED"))
        assertCode("REQUIRED", "periodCount", base("billingMode" to "TIMED", "periodUnit" to "DAY"))
        assertCode("OUT_OF_RANGE", "periodCount", base("billingMode" to "TIMED", "periodUnit" to "DAY", "periodCount" to 0))
        assertCode("OUT_OF_RANGE", "periodCount", base("billingMode" to "TIMED", "periodUnit" to "DAY", "periodCount" to 100_001))
        ok(base("billingMode" to "TIMED", "periodUnit" to "MINUTE", "periodCount" to 30))
        ok(base("billingMode" to "TIMED", "periodUnit" to "HOUR", "periodCount" to 2))

        ok(base("billingMode" to "SUBSCRIPTION", "periodUnit" to "MONTH", "periodCount" to 1, "subscriptionMaxCycles" to 12))
        assertCode("INVALID_FOR_SUBSCRIPTION", "periodUnit", base("billingMode" to "SUBSCRIPTION", "periodUnit" to "HOUR", "periodCount" to 1))
        assertCode("INVALID_FOR_SUBSCRIPTION", "periodUnit", base("billingMode" to "SUBSCRIPTION", "periodUnit" to "MINUTE", "periodCount" to 1))
        assertCode("OUT_OF_RANGE", "subscriptionMaxCycles", base("billingMode" to "SUBSCRIPTION", "periodUnit" to "DAY", "periodCount" to 1, "subscriptionMaxCycles" to 0))
        assertCode("MUST_BE_ZERO", "creditPrice", base("billingMode" to "SUBSCRIPTION", "periodUnit" to "DAY", "periodCount" to 1, "creditPrice" to "2"))
    }

    @Test
    fun `ONE_TIME ignores a stale period instead of judging it`() {
        ok(base("billingMode" to "ONE_TIME", "periodUnit" to "HOUR", "periodCount" to 0))
    }

    @Test
    fun `STANDARD versus BUNDLE bundle rows`() {
        assertCode("REQUIRED", "bundleItems", base("kind" to "BUNDLE"))
        assertEquals("NOT_ALLOWED", errors(base(), storedBundle = 2)["bundleItems"])
        assertEquals(emptyMap<String, String>(), errors(base("kind" to "BUNDLE", "bundleItems" to """[{"productId":3,"quantity":2}]""")))
    }

    // ---- variants

    private fun variants(json: String, vararg extra: Pair<String, Any?>) = base("variants" to json, *extra)

    @Test
    fun `variants - hasVariants and the list must agree`() {
        assertCode("REQUIRED", "variants", base("hasVariants" to true))
        assertCode("NOT_ALLOWED", "variants", base("hasVariants" to false, "variants" to """[{"name":"a"}]"""))
        ok(variants("""[{"name":"A"},{"name":"B"}]"""))
        assertEquals("REQUIRED", errors(JsonObject().put("variants", "[]").put("hasVariants", true), MarketProduct(id = 1, slug = "a", name = "A", hasVariants = true))["variants"])
    }

    @Test
    fun `variants - field rules with dotted paths`() {
        assertCode("REQUIRED", "variants.0.name", variants("""[{"price":"1"}]"""))
        assertCode("TOO_LONG", "variants.1.name", variants("""[{"name":"a"},{"name":"${"x".repeat(256)}"}]"""))
        assertCode("INVALID", "variants.0.sku", variants("""[{"name":"a","sku":"bad sku"}]"""))
        assertCode("DUPLICATE", "variants.1.sku", variants("""[{"name":"a","sku":"S1"},{"name":"b","sku":"S1"}]"""))
        assertCode("OUT_OF_RANGE", "variants.0.price", variants("""[{"name":"a","price":"-1"}]"""))
        assertCode("OUT_OF_RANGE", "variants.0.creditPrice", variants("""[{"name":"a","creditPrice":"-1"}]"""))
        assertCode("OUT_OF_RANGE", "variants.0.stock", variants("""[{"name":"a","stock":-1}]"""))
        assertCode("OUT_OF_RANGE", "variants.0.weightGrams", variants("""[{"name":"a","weightGrams":0}]"""))
        assertCode("INVALID", "variants.0.status", variants("""[{"name":"a","status":"ARCHIVED"}]"""))
        assertCode("DUPLICATE", "variants.1.id", variants("""[{"id":4,"name":"a"},{"id":4,"name":"b"}]"""))
    }

    @Test
    fun `variants - compareAtPrice must exceed the variant price or else the product price`() {
        assertCode("MUST_EXCEED_PRICE", "variants.0.compareAtPrice", variants("""[{"name":"a","price":"20","compareAtPrice":"20"}]"""))
        ok(variants("""[{"name":"a","price":"20","compareAtPrice":"20.01"}]"""))
        // no variant price: the product's 10.00 applies
        assertCode("MUST_EXCEED_PRICE", "variants.0.compareAtPrice", variants("""[{"name":"a","compareAtPrice":"10"}]"""))
    }

    @Test
    fun `variants - zero-decimal base currency needs whole units`() {
        assertEquals("NOT_WHOLE_UNITS", errors(variants("""[{"name":"a","price":"10.5"}]""", "price" to "10"), currency = "JPY")["variants.0.price"])
    }

    @Test
    fun `variants - a period count only on timed or subscription products, credit price zero for subscriptions`() {
        assertCode("NOT_APPLICABLE", "variants.0.periodCount", variants("""[{"name":"a","periodCount":30}]"""))
        ok(variants("""[{"name":"30 days","periodCount":30},{"name":"90 days","periodCount":90}]""", "billingMode" to "TIMED", "periodUnit" to "DAY", "periodCount" to 30))
        assertCode(
            "MUST_BE_ZERO", "variants.0.creditPrice",
            variants("""[{"name":"a","creditPrice":"5"}]""", "billingMode" to "SUBSCRIPTION", "periodUnit" to "DAY", "periodCount" to 30)
        )
    }

    @Test
    fun `variants - attributes are small key value strings`() {
        ok(variants("""[{"name":"a","attributes":{"color":"red","size":"l"}}]"""))
        assertCode("INVALID", "variants.0.attributes", variants("""[{"name":"a","attributes":{"Bad Key":"x"}}]"""))
        assertCode("INVALID", "variants.0.attributes", variants("""[{"name":"a","attributes":{"k":"${"x".repeat(256)}"}}]"""))
        assertCode("INVALID", "variants.0.attributes", variants("""[{"name":"a","attributes":{"k":{"nested":1}}}]"""))
    }

    @Test
    fun `variants - option axes and the values a variant picks`() {
        val axes = JsonArray().add(
            JsonObject().put("key", "size").put("label", "Size").put("values", JsonArray().add(JsonObject().put("key", "s").put("label", "S")).add(JsonObject().put("key", "l").put("label", "L")))
        ).encode()

        ok(variants("""[{"name":"S","optionValues":{"size":"s"}},{"name":"L","optionValues":{"size":"l"}}]""", "variantOptions" to axes))
        assertCode("INVALID", "variants.0.optionValues", variants("""[{"name":"X","optionValues":{"size":"xl"}}]""", "variantOptions" to axes))
        assertCode("INVALID", "variants.0.optionValues", variants("""[{"name":"X"}]""", "variantOptions" to axes))
        assertCode("INVALID", "variants.0.optionValues", variants("""[{"name":"X","optionValues":{"color":"red"}}]""", "variantOptions" to axes))
        assertCode("DUPLICATE", "variants.1.optionValues", variants("""[{"name":"S","optionValues":{"size":"s"}},{"name":"S2","optionValues":{"size":"s"}}]""", "variantOptions" to axes))
        // no axes at all: a variant picks nothing
        assertCode("INVALID", "variants.0.optionValues", variants("""[{"name":"S","optionValues":{"size":"s"}}]"""))
    }

    @Test
    fun `variant option axes are validated on their own`() {
        assertCode("INVALID", "variantOptions", base("variantOptions" to "not json"))
        assertCode("INVALID", "variantOptions.0.key", base("variantOptions" to """[{"key":"Size","label":"S","values":[{"key":"a","label":"A"}]}]"""))
        assertCode("REQUIRED", "variantOptions.0.label", base("variantOptions" to """[{"key":"size","values":[{"key":"a","label":"A"}]}]"""))
        assertCode("REQUIRED", "variantOptions.0.values", base("variantOptions" to """[{"key":"size","label":"S","values":[]}]"""))
        assertCode("DUPLICATE", "variantOptions.0.values.1.key", base("variantOptions" to """[{"key":"size","label":"S","values":[{"key":"a","label":"A"},{"key":"a","label":"B"}]}]"""))
        assertCode("DUPLICATE", "variantOptions.1.key", base("variantOptions" to """[{"key":"size","label":"S","values":[{"key":"a","label":"A"}]},{"key":"size","label":"T","values":[{"key":"a","label":"A"}]}]"""))
        assertCode("TOO_MANY", "variantOptions", base("variantOptions" to JsonArray().apply { repeat(6) { add(JsonObject().put("key", "k$it")) } }.encode()))
    }

    @Test
    fun `at most 200 variants`() {
        val many = JsonArray().apply { repeat(201) { add(JsonObject().put("name", "v$it")) } }.encode()
        assertCode("TOO_MANY", "variants", base("variants" to many))
    }

    // ---- fields

    private fun fields(json: String) = base("fields" to json)

    @Test
    fun `fields - key shape, uniqueness and labels`() {
        ok(fields("""[{"fieldKey":"nick","label":"Nick"},{"fieldKey":"age_2","label":"Age","type":"NUMBER"}]"""))
        assertCode("INVALID", "fields.0.fieldKey", fields("""[{"fieldKey":"Nick","label":"x"}]"""))
        assertCode("INVALID", "fields.0.fieldKey", fields("""[{"fieldKey":"1nick","label":"x"}]"""))
        assertCode("INVALID", "fields.0.fieldKey", fields("""[{"fieldKey":"${"a".repeat(33)}","label":"x"}]"""))
        assertCode("DUPLICATE", "fields.1.fieldKey", fields("""[{"fieldKey":"a","label":"x"},{"fieldKey":"a","label":"y"}]"""))
        assertCode("REQUIRED", "fields.0.label", fields("""[{"fieldKey":"a"}]"""))
        assertCode("TOO_LONG", "fields.0.helpText", fields("""[{"fieldKey":"a","label":"x","helpText":"${"h".repeat(513)}"}]"""))
        assertCode("TOO_MANY", "fields", fields(JsonArray().apply { repeat(31) { add(JsonObject().put("fieldKey", "k$it").put("label", "L")) } }.encode()))
    }

    @Test
    fun `fields - SELECT needs unique options, other types take none`() {
        ok(fields("""[{"fieldKey":"c","label":"C","type":"SELECT","options":[{"value":"a","label":"A"},{"value":"b","label":"B"}]}]"""))
        assertCode("REQUIRED", "fields.0.options", fields("""[{"fieldKey":"c","label":"C","type":"SELECT"}]"""))
        assertCode("REQUIRED", "fields.0.options", fields("""[{"fieldKey":"c","label":"C","type":"SELECT","options":[]}]"""))
        assertCode("DUPLICATE", "fields.0.options", fields("""[{"fieldKey":"c","label":"C","type":"SELECT","options":[{"value":"a","label":"A"},{"value":"a","label":"B"}]}]"""))
        assertCode("INVALID", "fields.0.options", fields("""[{"fieldKey":"c","label":"C","type":"SELECT","options":[{"value":"","label":"A"}]}]"""))
        assertCode("NOT_APPLICABLE", "fields.0.options", fields("""[{"fieldKey":"c","label":"C","type":"TEXT","options":[{"value":"a","label":"A"}]}]"""))
    }

    @Test
    fun `fields - the pattern passes the safe grammar and only TEXT has one`() {
        ok(fields("""[{"fieldKey":"c","label":"C","pattern":"[a-z]{3,8}"}]"""))
        listOf("(a+)+$", "(a|aa)+$", "(.*a){20}", "(a)\\1", "(?=a)b", "(?i)a", "[a-").forEach { hostile ->
            val result = errors(fields(JsonArray().add(JsonObject().put("fieldKey", "c").put("label", "C").put("pattern", hostile)).encode()))
            assertEquals("INVALID_PATTERN", result["fields.0.pattern"], hostile)
        }
        assertCode("TOO_LONG", "fields.0.pattern", fields("""[{"fieldKey":"c","label":"C","pattern":"${"a".repeat(256)}"}]"""))
        assertCode("NOT_APPLICABLE", "fields.0.pattern", fields("""[{"fieldKey":"c","label":"C","type":"NUMBER","pattern":"[0-9]+"}]"""))
    }

    @Test
    fun `fields - length and value ranges belong to their type`() {
        ok(fields("""[{"fieldKey":"c","label":"C","minLength":2,"maxLength":128}]"""))
        assertCode("OUT_OF_RANGE", "fields.0.maxLength", fields("""[{"fieldKey":"c","label":"C","maxLength":129}]"""))
        assertCode("OUT_OF_RANGE", "fields.0.maxLength", fields("""[{"fieldKey":"c","label":"C","maxLength":0}]"""))
        assertCode("INVALID_RANGE", "fields.0.minLength", fields("""[{"fieldKey":"c","label":"C","minLength":10,"maxLength":5}]"""))
        assertCode("NOT_APPLICABLE", "fields.0.maxLength", fields("""[{"fieldKey":"c","label":"C","type":"NUMBER","maxLength":5}]"""))
        ok(fields("""[{"fieldKey":"c","label":"C","type":"NUMBER","minValue":1,"maxValue":10}]"""))
        assertCode("INVALID_RANGE", "fields.0.minValue", fields("""[{"fieldKey":"c","label":"C","type":"NUMBER","minValue":10,"maxValue":1}]"""))
        assertCode("NOT_APPLICABLE", "fields.0.minValue", fields("""[{"fieldKey":"c","label":"C","type":"TEXT","minValue":1}]"""))
    }

    @Test
    fun `fields - a default value has to fit its own type`() {
        ok(fields("""[{"fieldKey":"c","label":"C","type":"SELECT","options":[{"value":"a","label":"A"}],"defaultValue":"a"}]"""))
        assertCode("INVALID", "fields.0.defaultValue", fields("""[{"fieldKey":"c","label":"C","type":"SELECT","options":[{"value":"a","label":"A"}],"defaultValue":"z"}]"""))
        assertCode("INVALID", "fields.0.defaultValue", fields("""[{"fieldKey":"c","label":"C","type":"NUMBER","minValue":1,"maxValue":5,"defaultValue":"9"}]"""))
        assertCode("INVALID", "fields.0.defaultValue", fields("""[{"fieldKey":"c","label":"C","type":"CHECKBOX","defaultValue":"yes"}]"""))
        assertCode("INVALID", "fields.0.defaultValue", fields("""[{"fieldKey":"c","label":"C","type":"DISCORD_ID","defaultValue":"123"}]"""))
        assertCode("INVALID", "fields.0.defaultValue", fields("""[{"fieldKey":"c","label":"C","type":"USERNAME","defaultValue":"a b"}]"""))
        ok(fields("""[{"fieldKey":"c","label":"C","type":"EMAIL","defaultValue":"a@b.test"},{"fieldKey":"d","label":"D","type":"CHECKBOX","defaultValue":"true"}]"""))
    }

    // ---- prices

    private fun prices(json: String, vararg extra: Pair<String, Any?>) = base("prices" to json, *extra)

    @Test
    fun `prices - currency must be supported, not the base currency, once per variant`() {
        ok(prices("""[{"currency":"USD","price":"11"},{"currency":"TRY","price":"300"}]"""))
        assertCode("INVALID", "prices.0.currency", prices("""[{"currency":"ZZZ","price":"1"}]"""))
        assertCode("INVALID", "prices.0.currency", prices("""[{"currency":"KWD","price":"1"}]"""))
        assertCode("BASE_CURRENCY", "prices.0.currency", prices("""[{"currency":"EUR","price":"1"}]"""))
        assertCode("DUPLICATE", "prices.1.currency", prices("""[{"currency":"USD","price":"1"},{"currency":"USD","price":"2"}]"""))
        ok(prices("""[{"currency":"USD","price":"1"},{"variantId":4,"currency":"USD","price":"2"}]"""))
        assertCode("INVALID", "prices.0.variantId", prices("""[{"variantId":-3,"currency":"USD","price":"1"}]"""))
    }

    @Test
    fun `prices - amounts follow the exponent of their own currency`() {
        ok(prices("""[{"currency":"JPY","price":"1500"}]"""))
        assertCode("NOT_WHOLE_UNITS", "prices.0.price", prices("""[{"currency":"JPY","price":"1500.5"}]"""))
        assertCode("NOT_WHOLE_UNITS", "prices.0.compareAtPrice", prices("""[{"currency":"JPY","price":"1500","compareAtPrice":"2000.5"}]"""))
        ok(prices("""[{"currency":"USD","price":"15.55"}]"""))
        assertCode("OUT_OF_RANGE", "prices.0.price", prices("""[{"currency":"USD","price":"-1"}]"""))
        assertCode("MUST_EXCEED_PRICE", "prices.0.compareAtPrice", prices("""[{"currency":"USD","price":"10","compareAtPrice":"10"}]"""))
        ok(prices("""[{"currency":"USD","price":"10","compareAtPrice":"10.01"}]"""))
    }

    @Test
    fun `prices - nested rows are judged with their own dotted path and may not name a variant`() {
        assertCode(
            "BASE_CURRENCY", "variants.0.prices.0.currency",
            variants("""[{"name":"a","prices":[{"currency":"EUR","price":"1"}]}]""")
        )
        assertCode(
            "DUPLICATE", "variants.0.prices.1.currency",
            variants("""[{"name":"a","prices":[{"currency":"USD","price":"1"},{"currency":"USD","price":"2"}]}]""")
        )
        assertCode(
            "OUT_OF_RANGE", "variants.1.prices.0.price",
            variants("""[{"name":"a"},{"name":"b","prices":[{"currency":"USD","price":"-2"}]}]""")
        )
    }

    @Test
    fun `prices - the same variant and currency in the top level list and in the variant is a duplicate`() {
        val json = base(
            "variants" to """[{"id":4,"name":"a","prices":[{"currency":"USD","price":"1"}]}]""",
            "prices" to """[{"variantId":4,"currency":"USD","price":"2"}]"""
        )
        assertEquals("DUPLICATE", errors(json)["variants.0.prices.0.currency"])
    }

    // ---- bundle items

    @Test
    fun `bundle items - structure and limits`() {
        fun bundle(items: String) = base("kind" to "BUNDLE", "bundleItems" to items)

        ok(bundle("""[{"productId":3,"quantity":2},{"productId":3,"variantId":9,"quantity":1}]"""))
        assertCode("OUT_OF_RANGE", "bundleItems.0.quantity", bundle("""[{"productId":3,"quantity":0}]"""))
        assertCode("OUT_OF_RANGE", "bundleItems.0.quantity", bundle("""[{"productId":3,"quantity":1001}]"""))
        assertCode("DUPLICATE", "bundleItems.1.productId", bundle("""[{"productId":3},{"productId":3}]"""))
        assertCode("TOO_MANY", "bundleItems", bundle(JsonArray().apply { repeat(51) { add(JsonObject().put("productId", it + 1)) } }.encode()))
    }

    // ---- misc

    @Test
    fun `server choices are a short list of ids`() {
        ok(base("serverChoices" to "[1,2,3]"))
        assertTrue(errors(base("serverChoices" to JsonArray().apply { repeat(101) { add(it + 1) } }.encode())).containsKey("serverChoices"))
    }

    @Test
    fun `parse errors are part of the result so a bad value can never be saved`() {
        val result = errors(base("price" to "abc", "stock" to "x"))

        assertEquals("INVALID", result["price"])
        assertEquals("INVALID", result["stock"])
    }

    @Test
    fun `several problems are reported together`() {
        val result = errors(base("name" to "", "price" to "-1", "kind" to "CREDIT_PACK", "billingMode" to "TIMED", "physical" to true))

        assertTrue(result.keys.containsAll(listOf("name", "price", "creditAmount", "billingMode", "periodUnit", "periodCount", "physical", "weightGrams")), result.toString())
    }
}
