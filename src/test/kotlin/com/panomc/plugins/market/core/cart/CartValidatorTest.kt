package com.panomc.plugins.market.core.cart

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** `CartLineKey`, `CartLimits`, `CartLineParser` and `CartValidator` (06 section 2.1, 01 section 4.2, 17 section 11.1). */
class CartValidatorTest {
    /** The frozen vector of 17 section 11.1: computed once with `sha1sum` over the preimage below and never changed. */
    @Test
    fun `line key of the frozen vector`() {
        val values = linkedMapOf<String, Any?>("nick" to "Steve", "amount" to 3, "ok" to true)

        assertEquals("12|0|{\"amount\":\"3\",\"nick\":\"Steve\",\"ok\":\"true\"}|0", CartLineKey.preimage(12, 0, values, null))
        assertEquals("b7eac4916c15dfbe9ce7c914ac0d2c5313fd8f2b", CartLineKey.of(12, 0, values, null))
    }

    @Test
    fun `line key does not depend on the order of the field keys`() {
        val a = linkedMapOf<String, Any?>("nick" to "Steve", "amount" to 3, "ok" to true)
        val b = linkedMapOf<String, Any?>("ok" to true, "nick" to "Steve", "amount" to 3)
        val c = linkedMapOf<String, Any?>("amount" to 3, "ok" to true, "nick" to "Steve")

        assertEquals(CartLineKey.of(12, 0, a, null), CartLineKey.of(12, 0, b, null))
        assertEquals(CartLineKey.of(12, 0, a, null), CartLineKey.of(12, 0, c, null))
    }

    @Test
    fun `null encoding of variant and server and empty values`() {
        assertEquals("5|0|{}|0", CartLineKey.preimage(5, null, null, null))
        assertEquals("5|0|{}|0", CartLineKey.preimage(5, 0, emptyMap(), null))
        assertEquals("5|7|{}|9", CartLineKey.preimage(5, 7, emptyMap(), 9))
        // keys whose value is null or blank are dropped
        assertEquals("{}", CartLineKey.canonical(mapOf("a" to null, "b" to "", "c" to "   ")))
        assertEquals(CartLineKey.of(5, 0, null, null), CartLineKey.of(5, 0, mapOf("a" to null, "b" to ""), null))
        assertEquals("{\"c\":\"x\"}", CartLineKey.canonical(mapOf("a" to null, "c" to " x ")))
    }

    @Test
    fun `every part of the line changes the key`() {
        val base = CartLineKey.of(1, 0, mapOf("a" to "x"), null)

        assertNotEquals(base, CartLineKey.of(2, 0, mapOf("a" to "x"), null))
        assertNotEquals(base, CartLineKey.of(1, 3, mapOf("a" to "x"), null))
        assertNotEquals(base, CartLineKey.of(1, 0, mapOf("a" to "y"), null))
        assertNotEquals(base, CartLineKey.of(1, 0, mapOf("b" to "x"), null))
        assertNotEquals(base, CartLineKey.of(1, 0, mapOf("a" to "x"), 4))
        assertEquals(40, base.length)
        assertTrue(base.all { it in "0123456789abcdef" })
    }

    @Test
    fun `values become strings the way the theme writes them`() {
        assertEquals("{\"a\":\"3\"}", CartLineKey.canonical(mapOf("a" to 3)))
        assertEquals("{\"a\":\"3\"}", CartLineKey.canonical(mapOf("a" to 3.0)))
        assertEquals("{\"a\":\"3\"}", CartLineKey.canonical(mapOf("a" to 3L)))
        assertEquals("{\"a\":\"1.5\"}", CartLineKey.canonical(mapOf("a" to 1.50)))
        assertEquals("{\"a\":\"1.5\"}", CartLineKey.canonical(mapOf("a" to java.math.BigDecimal("1.500"))))
        assertEquals("{\"a\":\"0\"}", CartLineKey.canonical(mapOf("a" to 0.0)))
        assertEquals("{\"a\":\"100\"}", CartLineKey.canonical(mapOf("a" to java.math.BigDecimal("1E+2"))))
        assertEquals("{\"a\":\"true\",\"b\":\"false\"}", CartLineKey.canonical(mapOf("a" to true, "b" to false)))
    }

    @Test
    fun `keys sort by code point and strings are escaped like JSON stringify`() {
        assertEquals("{\"B\":\"1\",\"a\":\"2\",\"b\":\"3\"}", CartLineKey.canonical(mapOf("b" to 3, "a" to 2, "B" to 1)))
        // U+1F600 (a surrogate pair in UTF-16) sorts after U+FF5E although its first UTF-16 unit is smaller
        assertEquals("{\"\uFF5E\":\"1\",\"\uD83D\uDE00\":\"2\"}", CartLineKey.canonical(mapOf("\uD83D\uDE00" to 2, "\uFF5E" to 1)))
        assertEquals("{\"k\":\"a\\\"b\\\\c\\n\\t\\u0001\"}", CartLineKey.canonical(mapOf("k" to "a\"b\\c\n\t\u0001")))
        assertEquals("{\"k\":\"\u00e9\u20ac\"}", CartLineKey.canonical(mapOf("k" to "\u00e9\u20ac")))
        assertEquals("{\"k\":\"\\ud800\"}", CartLineKey.canonical(mapOf("k" to "\uD800")))
    }

    @Test
    fun `normalize keeps types and drops blanks`() {
        val normalized = CartLineKey.normalize(mapOf("a" to " x ", "b" to 3, "c" to true, "d" to null, "e" to "  "))

        assertEquals(mapOf<String, Any>("a" to "x", "b" to 3, "c" to true), normalized)
        assertEquals(CartLineKey.of(1, 0, mapOf("a" to " x ", "b" to 3, "c" to true), null), CartLineKey.of(1, 0, normalized, null))
    }

    // ----- limits -------------------------------------------------------------------------------------------------

    @Test
    fun `quantity is clamped into 1 to 999`() {
        assertEquals(1, CartLimits.clampQuantity(0))
        assertEquals(1, CartLimits.clampQuantity(-5))
        assertEquals(7, CartLimits.clampQuantity(7))
        assertEquals(999, CartLimits.clampQuantity(999))
        assertEquals(999, CartLimits.clampQuantity(1000))
        assertEquals(999, CartLimits.clampQuantity(Long.MAX_VALUE))
        assertEquals(999, CartLimits.addQuantities(Int.MAX_VALUE, Int.MAX_VALUE))
        assertEquals(10, CartLimits.addQuantities(4, 6))
    }

    @Test
    fun `max quantity is the smallest of stock, per order limit and 999`() {
        assertEquals(999, CartLimits.maxQuantity(null, null))
        assertEquals(5, CartLimits.maxQuantity(5, null))
        assertEquals(3, CartLimits.maxQuantity(5, 3))
        assertEquals(2, CartLimits.maxQuantity(2, 3))
        assertEquals(0, CartLimits.maxQuantity(0, 3))
        assertEquals(0, CartLimits.maxQuantity(-4, null))
        assertEquals(999, CartLimits.maxQuantity(5000, 2000))
        assertEquals(1, CartLimits.maxQuantity(null, 1))
    }

    @Test
    fun `field value shape limits`() {
        assertTrue(CartLimits.fieldValuesFit(null))
        assertTrue(CartLimits.fieldValuesFit((1..20).associate { "k$it" to "v" }))
        assertFalse(CartLimits.fieldValuesFit((1..21).associate { "k$it" to "v" }))
        assertTrue(CartLimits.fieldValuesFit(mapOf("a" to "x".repeat(128))))
        assertFalse(CartLimits.fieldValuesFit(mapOf("a" to "x".repeat(129))))
        assertFalse(CartLimits.fieldValuesFit(mapOf("a" to " " + "x".repeat(129))))
    }

    @Test
    fun `cart level codes are trimmed and upper-cased`() {
        assertEquals("SUMMER", CartLimits.normalizeCode("  summer "))
        assertNull(CartLimits.normalizeCode("   "))
        assertNull(CartLimits.normalizeCode(null))
        assertTrue(CartLimits.codeFits("X".repeat(64)))
        assertFalse(CartLimits.codeFits("X".repeat(65)))
        assertEquals(50, CartLimits.MAX_LINES)
    }

    // ----- parser -------------------------------------------------------------------------------------------------

    @Test
    fun `parser reads a line and clamps the quantity`() {
        val line = CartLineParser.parse(mapOf("productId" to 12, "quantity" to 5000, "fieldValues" to mapOf("nick" to " Steve ", "x" to null), "targetServerId" to 4))!!

        assertEquals(12L, line.productId)
        assertEquals(0L, line.variantId)
        assertEquals(999, line.quantity)
        assertEquals(mapOf<String, Any>("nick" to "Steve"), line.fieldValues)
        assertEquals(4L, line.targetServerId)
        assertEquals(CartLineKey.of(12, 0, mapOf("nick" to "Steve"), 4), line.lineKey)
        assertEquals(1, CartLineParser.parse(mapOf("productId" to 1))!!.quantity)
        assertEquals(1, CartLineParser.parse(mapOf("productId" to 1, "quantity" to -3))!!.quantity)
    }

    @Test
    fun `parser refuses what is not a line`() {
        assertNull(CartLineParser.parse(emptyMap()))
        assertNull(CartLineParser.parse(mapOf("productId" to "12")))
        assertNull(CartLineParser.parse(mapOf("productId" to 1.5)))
        assertNull(CartLineParser.parse(mapOf("productId" to 1, "quantity" to "2")))
        assertNull(CartLineParser.parse(mapOf("productId" to 1, "variantId" to "x")))
        assertNull(CartLineParser.parse(mapOf("productId" to 1, "fieldValues" to "x")))
        assertNull(CartLineParser.parse(mapOf("productId" to 1, "fieldValues" to mapOf("a" to listOf(1)))))
        assertNull(CartLineParser.parse(mapOf("productId" to 1, "fieldValues" to mapOf("a" to mapOf("b" to 1)))))
    }

    // ----- validator ----------------------------------------------------------------------------------------------

    private val facts = ProductFacts(available = true, variantIds = setOf(7, 8), fieldKeys = setOf("nick", "note"))

    @Test
    fun `a structurally valid line has no errors`() {
        assertEquals(emptyList<String>(), CartValidator.lineErrors(CartLine(1), facts))
        assertEquals(emptyList<String>(), CartValidator.lineErrors(CartLine(1, 7, 2, mapOf("nick" to "a"), 3), facts))
        assertTrue(CartValidator.isValid(CartLine(1), facts))
    }

    @Test
    fun `a missing, deleted or non positive product is PRODUCT_UNAVAILABLE alone`() {
        assertEquals(listOf("PRODUCT_UNAVAILABLE"), CartValidator.lineErrors(CartLine(1, 99, 1, mapOf("zz" to "1")), null))
        assertEquals(listOf("PRODUCT_UNAVAILABLE"), CartValidator.lineErrors(CartLine(1, 99), ProductFacts(available = false)))
        assertEquals(listOf("PRODUCT_UNAVAILABLE"), CartValidator.lineErrors(CartLine(0), facts))
        assertEquals(listOf("PRODUCT_UNAVAILABLE"), CartValidator.lineErrors(CartLine(-3), facts))
    }

    @Test
    fun `a variant of another product, deleted, negative or on a product without variants is VARIANT_UNAVAILABLE`() {
        assertEquals(listOf("VARIANT_UNAVAILABLE"), CartValidator.lineErrors(CartLine(1, 99), facts))
        assertEquals(listOf("VARIANT_UNAVAILABLE"), CartValidator.lineErrors(CartLine(1, -1), facts))
        assertEquals(listOf("VARIANT_UNAVAILABLE"), CartValidator.lineErrors(CartLine(1, 7), ProductFacts(available = true)))
        // no variant on a product that has variants is the quote's VARIANT_REQUIRED, not a structural error
        assertEquals(emptyList<String>(), CartValidator.lineErrors(CartLine(1, 0), facts))
    }

    @Test
    fun `field keys must be fields of the product and fit the limits`() {
        assertEquals(listOf("FIELD_INVALID"), CartValidator.lineErrors(CartLine(1, 0, 1, mapOf("other" to "x")), facts))
        assertEquals(listOf("FIELD_INVALID"), CartValidator.lineErrors(CartLine(1, 0, 1, mapOf("nick" to "x".repeat(129))), facts))
        assertEquals(listOf("FIELD_INVALID"), CartValidator.lineErrors(CartLine(1, 0, 1, (1..21).associate { "k$it" to "v" }), ProductFacts(true, emptySet(), (1..21).map { "k$it" }.toSet())))
    }

    @Test
    fun `a target server must be a positive id`() {
        assertEquals(listOf("SERVER_UNAVAILABLE"), CartValidator.lineErrors(CartLine(1, 0, 1, emptyMap(), 0), facts))
        assertEquals(listOf("SERVER_UNAVAILABLE"), CartValidator.lineErrors(CartLine(1, 0, 1, emptyMap(), -2), facts))
    }

    @Test
    fun `several problems are all reported`() {
        assertEquals(
            listOf("VARIANT_UNAVAILABLE", "FIELD_INVALID", "SERVER_UNAVAILABLE"),
            CartValidator.lineErrors(CartLine(1, 99, 1, mapOf("zz" to "1"), 0), facts)
        )
    }
}
