package com.panomc.plugins.market.service

import com.panomc.plugins.market.core.time.Clock
import com.panomc.plugins.market.db.dao.MarketLegalTextDao
import com.panomc.plugins.market.db.model.MarketLegalText
import com.panomc.plugins.market.db.tx.MarketDb
import com.panomc.plugins.market.error.RequestValueException
import com.panomc.plugins.market.util.HtmlSanitizer
import io.vertx.sqlclient.SqlClient
import java.security.MessageDigest
import java.util.Locale

/**
 * Versions of the legal text (06 section 8.1, 01 section 5.4, 11 section 6.1). Rows are never edited or deleted: a new
 * text is a new version (the highest version so far plus one) that becomes the one active row of its locale, in one
 * transaction. The content is sanitised on write (the hash is over the sanitised text) and again on read, so a row
 * written raw into the database is still served clean.
 */
class LegalTextService(
    private val db: MarketDb,
    private val clock: Clock,
    private val legalTexts: MarketLegalTextDao,
    /** The site's default locale (the platform setting), the third step of the fallback chain. */
    private val siteLocale: () -> String
) {
    /** A version as served: [content] is sanitised. */
    class LegalView(
        val id: Long,
        val version: Int,
        val locale: String,
        val title: String,
        val content: String,
        val contentHash: String,
        val active: Boolean,
        val createdAt: Long
    )

    class Published(val id: Long, val version: Int, val locale: String)

    /** `GET /settings/legal`: every version of every locale, newest first. */
    suspend fun list(sqlClient: SqlClient): List<LegalView> = legalTexts.getAll(sqlClient).map { it.toView() }

    /**
     * `POST /settings/legal`: stores a new version and makes it the active one of [locale].
     * [RequestValueException] (400) for a bad locale, title or content.
     */
    suspend fun publish(locale: String?, title: String?, content: String?, createdBy: Long?): Published {
        val loc = locale?.trim().orEmpty()
        if (!LOCALE.matches(loc)) throw RequestValueException("locale", "INVALID")

        val cleanTitle = title.orEmpty().filterNot { Character.isISOControl(it) }.trim()
        if (cleanTitle.isEmpty()) throw RequestValueException("title", "REQUIRED")
        if (cleanTitle.length > MAX_TITLE) throw RequestValueException("title", "TOO_LONG")

        val raw = content.orEmpty()
        if (raw.length > MAX_CONTENT) throw RequestValueException("content", "TOO_LONG")

        val sanitised = HtmlSanitizer.sanitize(raw)
        if (sanitised.isBlank()) throw RequestValueException("content", "REQUIRED")

        val hash = sha256(sanitised)

        return db.tx { conn ->
            val now = clock.now()
            var version = legalTexts.maxVersion(conn) + 1
            var id: Long? = null
            var tries = 0

            // `(version, locale)` is unique: a concurrent publisher of the same locale that committed first makes the
            // insert answer `null`; the next free number is taken and the row is inserted again.
            while (id == null) {
                id = legalTexts.add(
                    MarketLegalText(
                        version = version, locale = loc, title = cleanTitle, content = sanitised, contentHash = hash,
                        active = false, createdBy = createdBy, createdAt = now, updatedAt = now
                    ),
                    conn
                )

                if (id == null) {
                    check(++tries < MAX_VERSION_TRIES) { "no free legal text version for $loc" }
                    version = legalTexts.maxVersion(conn) + 1
                }
            }

            legalTexts.activate(id, conn)

            Published(id, version, loc)
        }
    }

    /** The active text for an order or a page: see [pick]. */
    suspend fun activeFor(requestedLocale: String?, sqlClient: SqlClient): LegalView? =
        pick(legalTexts.getAllActive(sqlClient), requestedLocale, siteLocale())?.toView()

    /** One version by id (the text an order accepted), sanitised. */
    suspend fun get(id: Long, sqlClient: SqlClient): LegalView? = legalTexts.getById(id, sqlClient)?.toView()

    private fun MarketLegalText.toView() =
        LegalView(id, version, locale, title, HtmlSanitizer.sanitize(content), contentHash, active, createdAt)

    companion object {
        const val MAX_TITLE = 255
        const val MAX_CONTENT = 200_000
        private const val MAX_VERSION_TRIES = 50

        private val LOCALE = Regex("[a-z]{2,3}(-[A-Za-z]{2,4})?")

        fun sha256(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        private fun language(locale: String) = locale.substringBefore('-').lowercase(Locale.ROOT)

        /**
         * Fallback chain of 06 section 8.1 over the active rows: the requested locale, the same language in any region
         * (lowest locale first), the site default locale, then the lowest locale alphabetically. `null` when nothing is active.
         */
        fun pick(active: List<MarketLegalText>, requested: String?, siteDefault: String): MarketLegalText? {
            if (active.isEmpty()) return null

            val byLocale = active.sortedBy { it.locale }
            val want = requested?.trim().orEmpty()

            if (want.isNotEmpty()) {
                byLocale.firstOrNull { it.locale.equals(want, ignoreCase = true) }?.let { return it }
                byLocale.firstOrNull { language(it.locale) == language(want) }?.let { return it }
            }

            byLocale.firstOrNull { it.locale.equals(siteDefault.trim(), ignoreCase = true) }?.let { return it }

            return byLocale.first()
        }
    }
}
