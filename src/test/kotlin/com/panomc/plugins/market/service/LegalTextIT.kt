package com.panomc.plugins.market.service

import com.panomc.plugins.market.db.MarketDaoITBase
import com.panomc.plugins.market.error.RequestValueException
import com.panomc.plugins.market.support.Fixtures
import com.panomc.plugins.market.support.Race
import com.panomc.plugins.market.support.TestWiring
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `LegalTextService` on a real MariaDB (MK-071): a new text is version N+1 and the one active row of its locale, content
 * is sanitised on write (hash over the sanitised text) and on read, the fallback chain of 06 section 8.1, and
 * concurrent publishers never leave a locale with zero or two active rows.
 */
class LegalTextIT : MarketDaoITBase() {
    private val w by lazy { TestWiring(pool) }

    @Volatile
    private var site = "en-US"
    private val service by lazy { LegalTextService(w.db, w.clock, w.legalTexts, { site }) }

    private suspend fun activeCount(locale: String) = count("market_legal_text", "`locale` = ? AND `active` = 1", locale)

    @Test
    fun `first text is version 1 and active, the next is N+1 and takes over`(): Unit = runBlocking {
        val first = service.publish("en-US", "Terms", "<p>one</p>", 5)
        val second = service.publish("en-US", "Terms", "<p>two</p>", 5)

        assertEquals(1, first.version)
        assertEquals(2, second.version)

        val rows = service.list(pool)
        assertEquals(listOf(2, 1), rows.map { it.version })
        assertEquals(listOf(true, false), rows.map { it.active })
        assertEquals(1L, activeCount("en-US"))
        assertEquals(second.id, service.activeFor("en-US", pool)!!.id)
    }

    @Test
    fun `exactly one version per locale is active and locales are independent`(): Unit = runBlocking {
        service.publish("en-US", "T", "<p>en 1</p>", null)
        service.publish("tr", "T", "<p>tr 1</p>", null)
        service.publish("en-US", "T", "<p>en 2</p>", null)
        service.publish("tr", "T", "<p>tr 2</p>", null)
        service.publish("ru", "T", "<p>ru 1</p>", null)

        listOf("en-US", "tr", "ru").forEach { assertEquals(1L, activeCount(it), it) }
        assertEquals(5L, count("market_legal_text"))
        assertEquals(3L, count("market_legal_text", "`active` = 1"))

        // The older versions stay and are never edited.
        assertEquals(setOf("<p>en 1</p>", "<p>en 2</p>"), service.list(pool).filter { it.locale == "en-US" }.map { it.content }.toSet())
        assertEquals(1, w.legalTexts.getByLocale("en-US", pool).count { it.active })
    }

    @Test
    fun `version is the highest so far plus one over every locale`(): Unit = runBlocking {
        service.publish("tr", "T", "<p>a</p>", null)
        service.publish("tr", "T", "<p>b</p>", null)
        val ru = service.publish("ru", "T", "<p>c</p>", null)

        assertEquals(3, ru.version)
        assertEquals(3, w.legalTexts.maxVersion(pool))
    }

    @Test
    fun `content is sanitised on write and the hash is over the sanitised text`(): Unit = runBlocking {
        val dirty = "<p onclick=\"x()\">Hi <b>there</b></p><script>alert(1)</script><a href=\"javascript:alert(1)\">l</a><img src=x onerror=alert(1)>"
        val saved = service.publish("en-US", "Terms", dirty, 1)
        val row = w.legalTexts.getById(saved.id, pool)!!

        assertFalse(row.content.contains("script", ignoreCase = true))
        assertFalse(row.content.contains("onclick", ignoreCase = true))
        assertFalse(row.content.contains("onerror", ignoreCase = true))
        assertFalse(row.content.contains("javascript:", ignoreCase = true))
        assertTrue(row.content.contains("<b>there</b>"))
        assertEquals(LegalTextService.sha256(row.content), row.contentHash)
    }

    @Test
    fun `content is sanitised again on read when a row was written raw`(): Unit = runBlocking {
        val id = Fixtures.insertRaw(
            pool, "market_legal_text",
            mapOf(
                "version" to 1, "locale" to "en-US", "title" to "Raw", "active" to 1,
                "content" to "<p>ok</p><script>alert(1)</script><p onmouseover=\"x()\">z</p>", "contentHash" to "0".repeat(64)
            )
        )

        val listed = service.list(pool).single { it.id == id }
        val active = service.activeFor("en-US", pool)!!

        listOf(listed.content, active.content).forEach {
            assertFalse(it.contains("script", ignoreCase = true))
            assertFalse(it.contains("onmouseover", ignoreCase = true))
            assertTrue(it.contains("<p>ok</p>"))
        }
        assertEquals(id, service.get(id, pool)!!.id)
        assertFalse(service.get(id, pool)!!.content.contains("script", ignoreCase = true))
    }

    @Test
    fun `invalid input is a 400 and stores nothing`(): Unit = runBlocking {
        fun bad(locale: String?, title: String?, content: String?, field: String, reason: String) {
            val e = assertThrows(RequestValueException::class.java) { runBlocking { service.publish(locale, title, content, 1) } }
            assertEquals(field to reason, e.field to e.reason)
        }

        bad(null, "T", "<p>x</p>", "locale", "INVALID")
        bad("english", "T", "<p>x</p>", "locale", "INVALID")
        bad("EN", "T", "<p>x</p>", "locale", "INVALID")
        bad("en-US", "  ", "<p>x</p>", "title", "REQUIRED")
        bad("en-US", null, "<p>x</p>", "title", "REQUIRED")
        bad("en-US", "t".repeat(256), "<p>x</p>", "title", "TOO_LONG")
        bad("en-US", "T", null, "content", "REQUIRED")
        bad("en-US", "T", "   ", "content", "REQUIRED")
        bad("en-US", "T", "<script>alert(1)</script>", "content", "REQUIRED")
        bad("en-US", "T", "x".repeat(LegalTextService.MAX_CONTENT + 1), "content", "TOO_LONG")

        assertEquals(0L, count("market_legal_text"))
    }

    @Test
    fun `title is trimmed and stripped of control characters, content at the limit is accepted`(): Unit = runBlocking {
        val saved = service.publish("tr", "  Kullanım\u0000 Şartları\n ", "x".repeat(LegalTextService.MAX_CONTENT), 1)

        assertEquals("Kullanım Şartları", w.legalTexts.getById(saved.id, pool)!!.title)
    }

    @Test
    fun `active text follows the fallback chain and is null without any text`(): Unit = runBlocking {
        assertNull(service.activeFor("tr", pool))

        service.publish("ru", "R", "<p>ru</p>", null)
        service.publish("en-US", "E", "<p>en</p>", null)

        assertEquals("ru", service.activeFor("ru", pool)!!.locale)
        assertEquals("en-US", service.activeFor("en-GB", pool)!!.locale)
        assertEquals("en-US", service.activeFor("de", pool)!!.locale)
        assertEquals("en-US", service.activeFor(null, pool)!!.locale)

        site = "ru"
        assertEquals("ru", service.activeFor("de", pool)!!.locale)
        site = "xx"
        assertEquals("en-US", service.activeFor("de", pool)!!.locale)
    }

    @Test
    fun `concurrent publishers of one locale get distinct versions and one active row`(): Unit = runBlocking {
        repeat(Race.rounds) { round ->
            val locale = listOf("en-US", "tr", "ru", "de", "fr")[round]
            val results = Race.run(10) { service.publish(locale, "T", "<p>text $it</p>", null) }

            assertTrue(results.all { it.isSuccess }, results.firstOrNull { it.isFailure }?.exceptionOrNull()?.toString())
            val versions = results.map { it.getOrThrow().version }

            assertEquals(10, versions.toSet().size, "versions of $locale: $versions")
            assertEquals(1L, activeCount(locale))
            assertEquals(10L, count("market_legal_text", "`locale` = ?", locale))
        }
    }

    @Test
    fun `concurrent publishers of different locales keep one active row each`(): Unit = runBlocking {
        val locales = listOf("en-US", "tr", "ru", "de")
        val results = Race.run(12) { service.publish(locales[it % locales.size], "T", "<p>$it</p>", null) }

        assertTrue(results.all { it.isSuccess }, results.firstOrNull { it.isFailure }?.exceptionOrNull()?.toString())
        locales.forEach { assertEquals(1L, activeCount(it), it) }
        assertNotNull(service.activeFor("tr", pool))
    }
}
