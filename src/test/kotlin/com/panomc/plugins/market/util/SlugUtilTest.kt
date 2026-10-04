package com.panomc.plugins.market.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SlugUtilTest {
    @Test
    fun `slugify lowercases and joins words with dashes`() {
        assertEquals("vip-rank", SlugUtil.slugify("VIP Rank"))
        assertEquals("a-b-c", SlugUtil.slugify("a   b\tc"))
    }

    @Test
    fun `slugify transliterates Turkish characters`() {
        assertEquals("cigdem-ozlem-sukru-gunes", SlugUtil.slugify("Çiğdem Özlem Şükrü Güneş"))
        // dotted capital I and dotless lowercase i must both map to plain i
        assertEquals("istanbul-isik", SlugUtil.slugify("İstanbul Işık"))
        assertEquals("ilk", SlugUtil.slugify("ılk"))
    }

    @Test
    fun `slugify collapses punctuation runs and trims dashes`() {
        assertEquals("hello-world", SlugUtil.slugify("  --Hello, World!!--  "))
        assertEquals("rank-1-2", SlugUtil.slugify("Rank #1 / 2"))
    }

    @Test
    fun `slugify drops characters it cannot transliterate`() {
        assertEquals("", SlugUtil.slugify(""))
        assertEquals("", SlugUtil.slugify("!!!"))
        assertEquals("", SlugUtil.slugify("日本語"))
        assertEquals("a-b", SlugUtil.slugify("a 日本語 b"))
    }

    @Test
    fun `slugify caps the length and never ends with a dash`() {
        val long = "a".repeat(500)
        assertEquals(240, SlugUtil.slugify(long).length)

        // the cut lands right after a separator: the trailing dash must be trimmed
        val cutOnDash = "a".repeat(239) + " b"
        val slug = SlugUtil.slugify(cutOnDash)
        assertEquals("a".repeat(239), slug)
        assertFalse(slug.endsWith("-"))
    }

    @Test
    fun `copySlug appends copy suffixes`() {
        assertEquals("vip-copy", SlugUtil.copySlug("vip", 1))
        assertEquals("vip-copy", SlugUtil.copySlug("vip", 0))
        assertEquals("vip-copy-2", SlugUtil.copySlug("vip", 2))
        assertEquals("vip-copy-15", SlugUtil.copySlug("vip", 15))
    }

    @Test
    fun `copySlug keeps suffixed slugs within the column limit`() {
        val copy = SlugUtil.copySlug("a".repeat(300), 123)

        assertTrue(copy.length <= 255)
        assertTrue(copy.endsWith("-copy-123"))
    }

    @Test
    fun `copySlug trims a dangling dash before the suffix`() {
        assertEquals("vip-copy", SlugUtil.copySlug("vip-", 1))
    }
}
