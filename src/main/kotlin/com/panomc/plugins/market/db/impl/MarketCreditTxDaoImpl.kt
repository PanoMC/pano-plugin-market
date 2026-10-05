package com.panomc.plugins.market.db.impl

import com.panomc.platform.annotation.Dao
import com.panomc.plugins.market.db.MarketSchema
import com.panomc.plugins.market.db.dao.MarketCreditTxDao
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
class MarketCreditTxDaoImpl : MarketCreditTxDao() {

    override suspend fun init(sqlClient: SqlClient) {
        // Never throws (01 section 14.1 rule 3): a failed CREATE is logged and left to MarketSchema.ensure / SchemaVerifier.
        MarketSchema.installTable(sqlClient, MarketSchema.CREDIT_TX, prefix())
    }

    override suspend fun add(tx: MarketCreditTx, sqlClient: SqlClient): Long? {
        val query =
            "INSERT INTO `${prefix() + tableName}` (`type`, `idempotencyKey`, `userId`, `amount`, `shortfall`, `orderId`, `refundId`, `deliveryId`, `actorUserId`, `note`, `createdAt`, `updatedAt`) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        val values = Tuple.tuple()
            .addValue(tx.type.name)
            .addValue(tx.idempotencyKey)
            .addValue(tx.userId)
            .addValue(tx.amount)
            .addValue(tx.shortfall)
            .addValue(tx.orderId)
            .addValue(tx.refundId)
            .addValue(tx.deliveryId)
            .addValue(tx.actorUserId)
            .addValue(tx.note)
            .addValue(tx.createdAt)
            .addValue(tx.updatedAt)

        return try {
            sqlClient.preparedQuery(query).execute(values).coAwait().property(MySQLClient.LAST_INSERTED_ID)
        } catch (e: Exception) {
            if (e.isDuplicateKey()) null else throw e
        }
    }

    override suspend fun getById(id: Long, sqlClient: SqlClient): MarketCreditTx? =
        sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE `id` = ?")
            .execute(Tuple.of(id))
            .coAwait()
            .toEntities()
            .getOrNull(0)

    override suspend fun getByIdempotencyKey(idempotencyKey: String, sqlClient: SqlClient): MarketCreditTx? =
        sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE `idempotencyKey` = ?")
            .execute(Tuple.of(idempotencyKey))
            .coAwait()
            .toEntities()
            .getOrNull(0)

    override suspend fun getByUserId(userId: Long, limit: Int, sqlClient: SqlClient): List<MarketCreditTx> =
        sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE `userId` = ? ORDER BY `id` DESC LIMIT ?")
            .execute(Tuple.of(userId, limit))
            .coAwait()
            .toEntities()

    override suspend fun getByOrderId(orderId: Long, sqlClient: SqlClient): List<MarketCreditTx> =
        sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE `orderId` = ? ORDER BY `id` ASC")
            .execute(Tuple.of(orderId))
            .coAwait()
            .toEntities()

    override suspend fun uninstall(sqlClient: SqlClient) {
        sqlClient
            .query("DROP TABLE IF EXISTS `${prefix() + tableName}`")
            .execute()
            .coAwait()
    }
}
