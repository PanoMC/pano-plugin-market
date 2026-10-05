package com.panomc.plugins.market.db.impl

import com.panomc.platform.annotation.Dao
import com.panomc.plugins.market.db.MarketSchema
import com.panomc.plugins.market.db.dao.MarketLegalTextDao
import com.panomc.plugins.market.db.dao.isDuplicateKey
import com.panomc.plugins.market.db.model.MarketLegalText
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.mysqlclient.MySQLClient
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope

@Dao
@Lazy
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class MarketLegalTextDaoImpl : MarketLegalTextDao() {

    override suspend fun init(sqlClient: SqlClient) {
        // Never throws (01 section 14.1 rule 3): a failed CREATE is logged and left to MarketSchema.ensure / SchemaVerifier.
        MarketSchema.installTable(sqlClient, MarketSchema.LEGAL_TEXT, prefix())
    }

    override suspend fun add(text: MarketLegalText, sqlClient: SqlClient): Long? {
        val query =
            "INSERT INTO `${prefix() + tableName}` (`version`, `locale`, `title`, `content`, `contentHash`, `active`, `createdBy`, `createdAt`, `updatedAt`) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
        val values = Tuple.tuple()
            .addValue(text.version)
            .addValue(text.locale)
            .addValue(text.title)
            .addValue(text.content)
            .addValue(text.contentHash)
            .addValue(text.active)
            .addValue(text.createdBy)
            .addValue(text.createdAt)
            .addValue(text.updatedAt)

        return try {
            sqlClient.preparedQuery(query).execute(values).coAwait().property(MySQLClient.LAST_INSERTED_ID)
        } catch (e: Exception) {
            if (e.isDuplicateKey()) null else throw e
        }
    }

    override suspend fun getById(id: Long, sqlClient: SqlClient): MarketLegalText? {
        val rows: RowSet<Row> = sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE `id` = ?")
            .execute(Tuple.of(id))
            .coAwait()

        return rows.toEntities().getOrNull(0)
    }

    override suspend fun getActive(locale: String, sqlClient: SqlClient): MarketLegalText? {
        val rows: RowSet<Row> = sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE `locale` = ? AND `active` = 1 ORDER BY `version` DESC LIMIT 1")
            .execute(Tuple.of(locale))
            .coAwait()

        return rows.toEntities().getOrNull(0)
    }

    override suspend fun getByLocale(locale: String, sqlClient: SqlClient): List<MarketLegalText> {
        val rows: RowSet<Row> = sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE `locale` = ? ORDER BY `version` DESC")
            .execute(Tuple.of(locale))
            .coAwait()

        return rows.toEntities()
    }

    override suspend fun getAllActive(sqlClient: SqlClient): List<MarketLegalText> =
        sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE `active` = 1 ORDER BY `locale`")
            .execute()
            .coAwait()
            .toEntities()

    override suspend fun getAll(sqlClient: SqlClient): List<MarketLegalText> =
        sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` ORDER BY `version` DESC, `locale`")
            .execute()
            .coAwait()
            .toEntities()

    override suspend fun maxVersion(sqlClient: SqlClient): Int =
        sqlClient
            .preparedQuery("SELECT COALESCE(MAX(`version`), 0) FROM `${prefix() + tableName}`")
            .execute()
            .coAwait()
            .first()
            .getInteger(0)

    override suspend fun activate(id: Long, sqlClient: SqlClient): Boolean {
        val table = prefix() + tableName
        val exists = sqlClient.preparedQuery("SELECT `locale` FROM `$table` WHERE `id` = ?")
            .execute(Tuple.of(id)).coAwait().firstOrNull() ?: return false

        // One statement: the locale never has zero or two active rows between two statements.
        sqlClient
            .preparedQuery("UPDATE `$table` SET `active` = IF(`id` = ?, 1, 0), `updatedAt` = ? WHERE `locale` = ?")
            .execute(Tuple.of(id, System.currentTimeMillis(), exists.getString("locale")))
            .coAwait()

        return true
    }

    override suspend fun uninstall(sqlClient: SqlClient) {
        sqlClient
            .query("DROP TABLE IF EXISTS `${prefix() + tableName}`")
            .execute()
            .coAwait()
    }
}
