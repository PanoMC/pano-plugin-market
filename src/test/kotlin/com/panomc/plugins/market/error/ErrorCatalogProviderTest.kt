package com.panomc.plugins.market.error

import com.panomc.platform.api.ErrorCatalogProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 04 section 3: market's `ErrorCatalog` is the plugin's `ErrorCatalogProvider`, with the content it had. */
class ErrorCatalogProviderTest {
    @Test
    fun `the catalogue is a provider whose builders give the same codes and statuses`() {
        val provider: ErrorCatalogProvider = ErrorCatalog

        assertTrue(provider.entries.isNotEmpty())
        assertEquals(ErrorCatalog.entries.size, provider.entries.size)
        assertEquals(ErrorCatalog.entries.map { it.code to it.status }, provider.entries.map { it().let { e -> e.getErrorCode() to e.getStatusCode() } })
    }

    @Test
    fun `the plugin offers the catalogue as a bean of the platform contract`() {
        val bean: ErrorCatalogProvider = MarketErrorCatalogProvider()

        assertEquals(ErrorCatalog.entries.map { it.code }, bean.entries.map { it().getErrorCode() })
        assertTrue(MarketErrorCatalogProvider::class.java.isAnnotationPresent(org.springframework.stereotype.Component::class.java))
    }

    @Test
    fun `every code is unique inside the plugin and shaped as the envelope wants`() {
        val codes = (ErrorCatalog as ErrorCatalogProvider).entries.map { it().getErrorCode() }

        assertEquals(codes.size, codes.toSet().size)
        assertTrue(codes.all { Regex("^[A-Z][A-Z0-9_]*$").matches(it) })
    }
}
