package com.panomc.plugins.market.db.impl

import com.panomc.platform.annotation.Dao
import com.panomc.plugins.market.db.MarketSchema
import com.panomc.plugins.market.db.dao.MarketBlockDao
import com.panomc.plugins.market.db.dao.isDuplicateKey
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
class MarketBlockDaoImpl : MarketBlockDao() {

    override suspend fun init(sqlClient: SqlClient) {
        // Never throws (01 section 14.1 rule 3): a failed CREATE is logged and left to MarketSchema.ensure / SchemaVerifier.
        MarketSchema.installTable(sqlClient, MarketSchema.BLOCK, prefix())
    }

    private suspend fun one(where: String, values: Tuple, sqlClient: SqlClient): MarketBlock? =
        sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE $where LIMIT 1")
            .execute(values)
            .coAwait()
            .toEntities()
            .getOrNull(0)

    private suspend fun many(where: String, order: String, values: Tuple, sqlClient: SqlClient): List<MarketBlock> =
        sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE $where ORDER BY $order")
            .execute(values)
            .coAwait()
            .toEntities()

    private suspend fun change(set: String, where: String, values: Tuple, sqlClient: SqlClient): Int =
        sqlClient
            .preparedQuery("UPDATE `${prefix() + tableName}` SET $set WHERE $where")
            .execute(values)
            .coAwait()
            .rowCount()

    override suspend fun add(block: MarketBlock, sqlClient: SqlClient): Long? {
        val query =
            "INSERT INTO `${prefix() + tableName}` (`type`, `value`, `reason`, `source`, `orderId`, `createdBy`, `expiresAt`, `hitCount`, `lastHitAt`, `createdAt`, `updatedAt`) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        val values = Tuple.tuple()
            .addValue(block.type.name)
            .addValue(block.value)
            .addValue(block.reason)
            .addValue(block.source.name)
            .addValue(block.orderId)
            .addValue(block.createdBy)
            .addValue(block.expiresAt)
            .addValue(block.hitCount)
            .addValue(block.lastHitAt)
            .addValue(block.createdAt)
            .addValue(block.updatedAt)

        return try {
            sqlClient.preparedQuery(query).execute(values).coAwait().property(MySQLClient.LAST_INSERTED_ID)
        } catch (e: Exception) {
            if (e.isDuplicateKey()) null else throw e
        }
    }

    override suspend fun getById(id: Long, sqlClient: SqlClient): MarketBlock? = one("`id` = ?", Tuple.of(id), sqlClient)

    override suspend fun getByTypeAndValue(type: BlockType, value: String, sqlClient: SqlClient): MarketBlock? =
        one("`type` = ? AND `value` = ?", Tuple.of(type.name, value), sqlClient)

    override suspend fun getActiveByType(type: BlockType, now: Long, sqlClient: SqlClient): List<MarketBlock> =
        many("`type` = ? AND (`expiresAt` IS NULL OR `expiresAt` > ?)", "`id` ASC", Tuple.of(type.name, now), sqlClient)

    override suspend fun getAll(sqlClient: SqlClient): List<MarketBlock> =
        many("1 = 1", "`id` DESC", Tuple.tuple(), sqlClient)

    override suspend fun recordHit(id: Long, now: Long, sqlClient: SqlClient): Boolean =
        change("`hitCount` = `hitCount` + 1, `lastHitAt` = ?, `updatedAt` = ?", "`id` = ?", Tuple.of(now, now, id), sqlClient) > 0

    override suspend fun delete(id: Long, sqlClient: SqlClient): Boolean =
        sqlClient
            .preparedQuery("DELETE FROM `${prefix() + tableName}` WHERE `id` = ?")
            .execute(Tuple.of(id))
            .coAwait()
            .rowCount() > 0

    override suspend fun deleteExpired(now: Long, sqlClient: SqlClient): Int =
        sqlClient
            .preparedQuery("DELETE FROM `${prefix() + tableName}` WHERE `expiresAt` IS NOT NULL AND `expiresAt` <= ?")
            .execute(Tuple.of(now))
            .coAwait()
            .rowCount()

    override suspend fun uninstall(sqlClient: SqlClient) {
        sqlClient
            .query("DROP TABLE IF EXISTS `${prefix() + tableName}`")
            .execute()
            .coAwait()
    }
}
