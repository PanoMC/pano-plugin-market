package com.panomc.plugins.market.db.impl

import com.panomc.platform.annotation.Dao
import com.panomc.plugins.market.db.MarketSchema
import com.panomc.plugins.market.db.dao.MarketRefundDao
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
class MarketRefundDaoImpl : MarketRefundDao() {

    override suspend fun init(sqlClient: SqlClient) {
        // Never throws (01 section 14.1 rule 3): a failed CREATE is logged and left to MarketSchema.ensure / SchemaVerifier.
        MarketSchema.installTable(sqlClient, MarketSchema.REFUND, prefix())
    }

    override suspend fun add(refund: MarketRefund, sqlClient: SqlClient): Long? {
        val query =
            "INSERT INTO `${prefix() + tableName}` (`orderId`, `paymentId`, `providerId`, `status`, `origin`, `idempotencyKey`, `idempotencyHash`, `amount`, `gatewayAmount`, `gatewayRefundedAmount`, `creditAmount`, `creditValue`, `currency`, `reason`, `gatewayRefundId`, `buyerActionUrl`, `revoke`, `revokeFirst`, `cascadeUpgrade`, `restock`, `creditTxId`, `initiatedBy`, `failureCode`, `failureMessage`, `nextQueryAt`, `queryCount`, `completedAt`, `createdAt`, `updatedAt`) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        val values = Tuple.tuple()
            .addValue(refund.orderId)
            .addValue(refund.paymentId)
            .addValue(refund.providerId)
            .addValue(refund.status.name)
            .addValue(refund.origin.name)
            .addValue(refund.idempotencyKey)
            .addValue(refund.idempotencyHash)
            .addValue(refund.amount)
            .addValue(refund.gatewayAmount)
            .addValue(refund.gatewayRefundedAmount)
            .addValue(refund.creditAmount)
            .addValue(refund.creditValue)
            .addValue(refund.currency)
            .addValue(refund.reason)
            .addValue(refund.gatewayRefundId)
            .addValue(refund.buyerActionUrl)
            .addValue(refund.revoke)
            .addValue(refund.revokeFirst)
            .addValue(refund.cascadeUpgrade)
            .addValue(refund.restock)
            .addValue(refund.creditTxId)
            .addValue(refund.initiatedBy)
            .addValue(refund.failureCode)
            .addValue(refund.failureMessage)
            .addValue(refund.nextQueryAt)
            .addValue(refund.queryCount)
            .addValue(refund.completedAt)
            .addValue(refund.createdAt)
            .addValue(refund.updatedAt)

        return try {
            sqlClient.preparedQuery(query).execute(values).coAwait().property(MySQLClient.LAST_INSERTED_ID)
        } catch (e: Exception) {
            if (e.isDuplicateKey()) null else throw e
        }
    }

    override suspend fun getById(id: Long, sqlClient: SqlClient): MarketRefund? =
        sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE `id` = ?")
            .execute(Tuple.of(id))
            .coAwait()
            .toEntities()
            .getOrNull(0)

    override suspend fun getByIdempotencyKey(idempotencyKey: String, sqlClient: SqlClient): MarketRefund? =
        sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE `idempotencyKey` = ?")
            .execute(Tuple.of(idempotencyKey))
            .coAwait()
            .toEntities()
            .getOrNull(0)

    override suspend fun getByProviderRefund(providerId: String, gatewayRefundId: String, sqlClient: SqlClient): MarketRefund? =
        sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE `providerId` = ? AND `gatewayRefundId` = ?")
            .execute(Tuple.of(providerId, gatewayRefundId))
            .coAwait()
            .toEntities()
            .getOrNull(0)

    override suspend fun getByOrderId(orderId: Long, sqlClient: SqlClient): List<MarketRefund> =
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
