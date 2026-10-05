package com.panomc.plugins.market.db.dao

import com.panomc.plugins.market.db.MarketDao
import com.panomc.plugins.market.db.model.MarketLegalText
import io.vertx.sqlclient.SqlClient

/** Versions of the legal text (01 section 5.4). Rows are never edited or deleted; only `active` moves. */
abstract class MarketLegalTextDao : MarketDao<MarketLegalText>(MarketLegalText::class.java) {
    /** The new id, or `null` when `(version, locale)` already exists (`uq_version_locale`). */
    abstract suspend fun add(text: MarketLegalText, sqlClient: SqlClient): Long?

    abstract suspend fun getById(id: Long, sqlClient: SqlClient): MarketLegalText?

    /** The active version of [locale], if any. */
    abstract suspend fun getActive(locale: String, sqlClient: SqlClient): MarketLegalText?

    /** Every version of [locale], newest first. */
    abstract suspend fun getByLocale(locale: String, sqlClient: SqlClient): List<MarketLegalText>

    /** The highest version over all locales (`0` when there is none): the next edit is this plus one. */
    abstract suspend fun maxVersion(sqlClient: SqlClient): Int

    /**
     * Makes row [id] the one active row of its locale in a single statement (every other row of that locale becomes
     * inactive). `false` when there is no such row.
     */
    abstract suspend fun activate(id: Long, sqlClient: SqlClient): Boolean

    /** The active row of every locale (at most one per locale), ordered by locale. */
    abstract suspend fun getAllActive(sqlClient: SqlClient): List<MarketLegalText>

    /** Every version of every locale, newest first (`version` descending, then locale). */
    abstract suspend fun getAll(sqlClient: SqlClient): List<MarketLegalText>
}
