package com.panomc.plugins.market.routes.api

import com.panomc.plugins.market.util.MarketStatus
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `If-None-Match` matching and the visibility rule of the public product image route (04 section 3, `images`; MK-064).
 */
class ProductImageEtagTest {
    private val etag = "\"abc123.png\""

    private fun matches(header: String?) = ProductImageResolver.etagMatches(header, etag)

    @Test
    fun `exact etag matches`() = assertTrue(matches("\"abc123.png\""))

    @Test
    fun `weak form matches`() = assertTrue(matches("W/\"abc123.png\""))

    @Test
    fun `comma list matches when one entry is the etag`() {
        assertTrue(matches("\"other.png\", \"abc123.png\""))
        assertTrue(matches("\"abc123.png\",\"other.png\""))
        assertTrue(matches("W/\"other.png\" , W/\"abc123.png\""))
    }

    @Test
    fun `star matches`() {
        assertTrue(matches("*"))
        assertTrue(matches("\"other.png\", *"))
    }

    @Test
    fun `a different etag does not match`() {
        assertFalse(matches("\"other.png\""))
        assertFalse(matches("W/\"other.png\", \"third.png\""))
        assertFalse(matches("\"abc123.png"))
        assertFalse(matches("abc123.png"))
    }

    @Test
    fun `blank or missing header does not match`() {
        assertFalse(matches(null))
        assertFalse(matches(""))
        assertFalse(matches("   "))
    }

    @Test
    fun `ACTIVE and HIDDEN live products are visible, INACTIVE ARCHIVED and soft deleted are not`() {
        assertTrue(ProductImageResolver.visible(MarketStatus.ACTIVE, null))
        assertTrue(ProductImageResolver.visible(MarketStatus.HIDDEN, null))
        assertFalse(ProductImageResolver.visible(MarketStatus.ACTIVE, 1L))
        assertFalse(ProductImageResolver.visible(MarketStatus.INACTIVE, null))
        assertFalse(ProductImageResolver.visible(MarketStatus.ARCHIVED, null))
    }
}
