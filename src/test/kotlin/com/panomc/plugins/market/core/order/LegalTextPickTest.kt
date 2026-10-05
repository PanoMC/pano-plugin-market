package com.panomc.plugins.market.core.order

import com.panomc.plugins.market.db.model.MarketLegalText
import com.panomc.plugins.market.service.LegalTextService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Fallback chain of 06 section 8.1: order locale, same language, site default, lowest locale. */
class LegalTextPickTest {
    private fun rows(vararg locales: String) = locales.mapIndexed { i, l -> MarketLegalText(id = i + 1L, locale = l, active = true) }

    private fun pick(requested: String?, site: String, vararg locales: String) =
        LegalTextService.pick(rows(*locales), requested, site)?.locale

    @Test
    fun `exact locale wins, case-insensitively`() {
        assertEquals("tr", pick("tr", "en-US", "en-US", "tr", "ru"))
        assertEquals("en-US", pick("EN-us", "tr", "en-US", "en-GB"))
    }

    @Test
    fun `same language in another region, lowest first`() {
        assertEquals("en-GB", pick("en-AU", "tr", "tr", "en-US", "en-GB"))
        assertEquals("pt-BR", pick("pt", "tr", "tr", "pt-BR"))
        assertEquals("tr", pick("tr-TR", "en-US", "en-US", "tr"))
    }

    @Test
    fun `site default when the requested language has no text`() {
        assertEquals("ru", pick("de", "ru", "tr", "ru", "en-US"))
        assertEquals("ru", pick(null, "RU", "tr", "ru"))
        assertEquals("ru", pick("  ", "ru", "tr", "ru"))
    }

    @Test
    fun `lowest locale alphabetically as the last resort`() {
        assertEquals("en-US", pick("de", "fr", "tr", "ru", "en-US"))
    }

    @Test
    fun `nothing active gives null`() {
        assertNull(LegalTextService.pick(emptyList(), "tr", "en-US"))
    }

    @Test
    fun `hash is sha-256 hex`() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", LegalTextService.sha256("abc"))
    }
}
