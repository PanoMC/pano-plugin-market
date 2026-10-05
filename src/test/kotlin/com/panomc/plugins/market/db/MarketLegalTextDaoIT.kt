package com.panomc.plugins.market.db

import com.panomc.plugins.market.db.dao.isDuplicateKey
import com.panomc.plugins.market.db.impl.MarketLegalTextDaoImpl
import com.panomc.plugins.market.db.impl.MarketSequenceDaoImpl
import com.panomc.plugins.market.db.model.MarketLegalText
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** `market_legal_text` (01 section 5.4) and `market_sequence` (01 section 6.7, the fixup markers). */
class MarketLegalTextDaoIT : MarketDaoITBase() {
    private val dao = MarketLegalTextDaoImpl()

    private fun text(version: Int, locale: String = "en-US", active: Boolean = false) = MarketLegalText(
        version = version, locale = locale, title = "Terms v$version", content = "<p>v$version</p>",
        contentHash = version.toString().padStart(64, 'f'), active = active, createdBy = 3, createdAt = 10, updatedAt = 20
    )

    @Test
    fun `a legal text round-trips every column`(): Unit = runBlocking {
        val written = text(4, "tr", active = true)
        EntityRoundTrip.differsFromDefaults(written, MarketLegalText())
        val id = dao.add(written, pool)!!
        val read = dao.getById(id, pool)!!
        assertEquals(id, read.id)
        EntityRoundTrip.assertSame(written, read)
        assertNull(dao.getById(9999, pool))
    }

    @Test
    fun `a duplicate of version and locale is answered with null and rejected by the unique key`(): Unit = runBlocking {
        assertNotNull(dao.add(text(1), pool))
        assertNull(dao.add(MarketLegalText(version = 1, locale = "en-US", title = "other", content = "x", contentHash = "0".repeat(64)), pool))
        assertEquals(1L, count("market_legal_text"))
        assertEquals("Terms v1", dao.getByLocale("en-US", pool).single().title)
        val raw = runCatching {
            sql("INSERT INTO `pano_market_legal_text` (`version`, `locale`, `title`, `content`, `contentHash`, `createdAt`, `updatedAt`) VALUES (1, 'en-US', 't', 'c', 'h', 1, 1)")
        }.exceptionOrNull()
        assertTrue(raw != null && raw.isDuplicateKey())
        // the same version in another locale, and another version in the same locale, are other rows
        assertNotNull(dao.add(text(1, "tr"), pool))
        assertNotNull(dao.add(text(2), pool))
    }

    @Test
    fun `activate leaves exactly one active row per locale`(): Unit = runBlocking {
        val v1 = dao.add(text(1), pool)!!
        val v2 = dao.add(text(2), pool)!!
        val tr = dao.add(text(3, "tr", active = true), pool)!!
        assertNull(dao.getActive("en-US", pool))

        assertTrue(dao.activate(v1, pool))
        assertEquals(v1, dao.getActive("en-US", pool)!!.id)
        assertTrue(dao.activate(v2, pool))
        assertEquals(v2, dao.getActive("en-US", pool)!!.id)
        assertEquals(1L, count("market_legal_text", "`locale` = 'en-US' AND `active` = 1"))
        assertFalse(dao.getById(v1, pool)!!.active)
        // the other locale is not touched
        assertEquals(tr, dao.getActive("tr", pool)!!.id)
        assertFalse(dao.activate(9999, pool))
        // the content of an old version is untouched
        assertEquals("<p>v1</p>", dao.getById(v1, pool)!!.content)
        assertEquals(listOf(2, 1), dao.getByLocale("en-US", pool).map { it.version })
    }

    @Test
    fun `maxVersion is the highest version over all locales`(): Unit = runBlocking {
        assertEquals(0, dao.maxVersion(pool))
        dao.add(text(1), pool)
        dao.add(text(5, "tr"), pool)
        assertEquals(5, dao.maxVersion(pool))
    }

    @Test
    fun `sequence rows are unique by name and the fixup markers exist on a fresh install`(): Unit = runBlocking {
        val sequences = MarketSequenceDaoImpl()
        // the installer ran ensure(): both one-shot fixups left their marker (value 1)
        assertEquals(1L, sequences.getValue("fixup:soldCount", pool))
        assertEquals(1L, sequences.getValue("fixup:legacyUsedCount", pool))
        assertNull(sequences.getValue("invoice:INV", pool))
        // a raw insert of name and value only works (the timestamp columns default to 0) ...
        sql("INSERT INTO `pano_market_sequence` (`name`, `value`) VALUES ('invoice:INV', 0)")
        assertEquals(0L, sequences.getValue("invoice:INV", pool))
        // ... and the name is unique
        val raw = runCatching { sql("INSERT INTO `pano_market_sequence` (`name`, `value`) VALUES ('invoice:INV', 5)") }.exceptionOrNull()
        assertTrue(raw != null && raw.isDuplicateKey())
    }
}
