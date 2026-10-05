package com.panomc.plugins.market.core.catalog

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SlugRulesTest {
    @Test
    fun `the reserved slugs are exactly checkout, order and cart`() {
        assertEquals(setOf("checkout", "order", "cart"), SlugRules.RESERVED)
        assertEquals("RESERVED_SLUG", SlugRules.check("checkout"))
        assertEquals("RESERVED_SLUG", SlugRules.check("order"))
        assertEquals("RESERVED_SLUG", SlugRules.check("cart"))
    }

    @Test
    fun `a reserved word is caught after normalisation so no spelling gets around it`() {
        assertEquals("RESERVED_SLUG", SlugRules.check(SlugRules.resolve("Checkout", "x")))
        assertEquals("RESERVED_SLUG", SlugRules.check(SlugRules.resolve("  CART  ", "x")))
        assertEquals("RESERVED_SLUG", SlugRules.check(SlugRules.resolve(null, "Order")))
        assertEquals("RESERVED_SLUG", SlugRules.check(SlugRules.resolve("-checkout-", "x")))
        // a longer slug that merely starts with a reserved word is fine
        assertNull(SlugRules.check("checkout-pass"))
        assertNull(SlugRules.check("my-order"))
        assertNull(SlugRules.check("cart2"))
    }

    @Test
    fun `resolve prefers the requested slug, falls back to the name and may come out blank`() {
        assertEquals("vip-rank", SlugRules.resolve("VIP Rank", "Other"))
        assertEquals("diamond-kit", SlugRules.resolve(null, "Diamond Kit"))
        assertEquals("diamond-kit", SlugRules.resolve("   ", "Diamond Kit"))
        assertEquals("diamond-kit", SlugRules.resolve("!!!", "Diamond Kit"))
        assertEquals("", SlugRules.resolve(null, "!!!"))
        assertEquals("REQUIRED", SlugRules.check(""))
    }

    @Test
    fun `Turkish letters are transliterated and the length is capped before any suffix`() {
        assertEquals("cigdem-ozlem", SlugRules.resolve(null, "Çiğdem Özlem"))
        assertEquals(240, SlugRules.resolve("a".repeat(500), "x").length)
    }

    @Test
    fun `an archived slug keeps the id, fits the column and can never be a live slug`() {
        assertEquals("vip--d17", SlugRules.archivedSlug("vip", 17))
        assertTrue(SlugRules.isArchivedSlug("vip--d17"))
        assertFalse(SlugRules.isArchivedSlug("vip-d17"))

        val long = SlugRules.archivedSlug("a".repeat(255), 123456789012L)
        assertEquals(SlugRules.COLUMN_LENGTH, long.length)
        assertTrue(long.endsWith("--d123456789012"))

        // slugify collapses every run of non-alphanumerics into one dash, so two dashes in a row cannot be requested
        listOf("vip--d17", "vip - -d17", "vip__d17", "vip--d17--d9").forEach { requested ->
            assertFalse("--" in SlugRules.resolve(requested, "x"), requested)
        }
    }
}
