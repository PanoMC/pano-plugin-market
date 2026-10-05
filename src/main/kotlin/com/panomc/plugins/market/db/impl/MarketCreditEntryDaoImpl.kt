package com.panomc.plugins.market.db.impl

import com.panomc.platform.annotation.Dao
import com.panomc.plugins.market.db.MarketSchema
import com.panomc.plugins.market.db.dao.MarketCreditEntryDao
import com.panomc.plugins.market.db.model.*
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.mysqlclient.MySQLClient
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope

@Dao
@Lazy
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class MarketCreditEntryDaoImpl : MarketCreditEntryDao() {

    override suspend fun init(sqlClient: SqlClient) {
        // Never throws (01 section 14.1 rule 3): a failed CREATE is logged and left to MarketSchema.ensure / SchemaVerifier.
        MarketSchema.installTable(sqlClient, MarketSchema.CREDIT_ENTRY, prefix())
    }

    override suspend fun add(entry: MarketCreditEntry, sqlClient: SqlClient): Long {
        val query =
            "INSERT INTO `${prefix() + tableName}` (`txId`, `accountId`, `amount`, `balanceAfter`, `createdAt`, `updatedAt`) VALUES (?, ?, ?, ?, ?, ?)"
        val values = Tuple.tuple()
            .addValue(entry.txId)
            .addValue(entry.accountId)
            .addValue(entry.amount)
            .addValue(entry.balanceAfter)
            .addValue(entry.createdAt)
            .addValue(entry.updatedAt)

        return sqlClient.preparedQuery(query).execute(values).coAwait().property(MySQLClient.LAST_INSERTED_ID)
    }

    override suspend fun getById(id: Long, sqlClient: SqlClient): MarketCreditEntry? =
        sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE `id` = ?")
            .execute(Tuple.of(id))
            .coAwait()
            .toEntities()
            .getOrNull(0)

    override suspend fun getByTxId(txId: Long, sqlClient: SqlClient): List<MarketCreditEntry> =
        sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE `txId` = ? ORDER BY `id` ASC")
            .execute(Tuple.of(txId))
            .coAwait()
            .toEntities()

    override suspend fun getByAccountId(accountId: Long, limit: Int, sqlClient: SqlClient): List<MarketCreditEntry> =
        sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE `accountId` = ? ORDER BY `id` DESC LIMIT ?")
            .execute(Tuple.of(accountId, limit))
            .coAwait()
            .toEntities()

    override suspend fun sumByTxId(txId: Long, sqlClient: SqlClient): Long =
        sqlClient
            .preparedQuery("SELECT COALESCE(SUM(`amount`), 0) FROM `${prefix() + tableName}` WHERE `txId` = ?")
            .execute(Tuple.of(txId))
            .coAwait()
            .first()
            .getLong(0)

    override suspend fun sumByAccountId(accountId: Long, sqlClient: SqlClient): Long =
        sqlClient
            .preparedQuery("SELECT COALESCE(SUM(`amount`), 0) FROM `${prefix() + tableName}` WHERE `accountId` = ?")
            .execute(Tuple.of(accountId))
            .coAwait()
            .first()
            .getLong(0)

    override suspend fun uninstall(sqlClient: SqlClient) {
        sqlClient
            .query("DROP TABLE IF EXISTS `${prefix() + tableName}`")
            .execute()
            .coAwait()
    }
}
