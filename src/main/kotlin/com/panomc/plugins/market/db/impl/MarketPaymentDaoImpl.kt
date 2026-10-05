package com.panomc.plugins.market.db.impl

import com.panomc.platform.annotation.Dao
import com.panomc.plugins.market.db.MarketSchema
import com.panomc.plugins.market.db.dao.MarketPaymentDao
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
class MarketPaymentDaoImpl : MarketPaymentDao() {

    override suspend fun init(sqlClient: SqlClient) {
        // Never throws (01 section 14.1 rule 3): a failed CREATE is logged and left to MarketSchema.ensure / SchemaVerifier.
        MarketSchema.installTable(sqlClient, MarketSchema.PAYMENT, prefix())
    }

    override suspend fun add(payment: MarketPayment, sqlClient: SqlClient): Long? {
        val query =
            "INSERT INTO `${prefix() + tableName}` (`orderId`, `subscriptionId`, `providerId`, `methodLabel`, `status`, `reference`, `token`, `amount`, `currency`, `feeAmount`, `creditAmount`, `creditValue`, `orderTotal`, `startKind`, `startPayload`, `gatewayTransactionId`, `gatewayRefs`, `providerData`, `paidAmount`, `paidCurrency`, `gatewayFee`, `netAmount`, `settlementCurrency`, `settlementAmount`, `installments`, `methodDetail`, `testMode`, `duplicate`, `refundedAmount`, `failureCode`, `failureMessage`, `adminMessage`, `clientIp`, `userAgent`, `startedAt`, `paidAt`, `expiresAt`, `closedAt`, `nextQueryAt`, `queryCount`, `lastQueriedAt`, `createdAt`, `updatedAt`) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        val values = Tuple.tuple()
            .addValue(payment.orderId)
            .addValue(payment.subscriptionId)
            .addValue(payment.providerId)
            .addValue(payment.methodLabel)
            .addValue(payment.status.name)
            .addValue(payment.reference)
            .addValue(payment.token)
            .addValue(payment.amount)
            .addValue(payment.currency)
            .addValue(payment.feeAmount)
            .addValue(payment.creditAmount)
            .addValue(payment.creditValue)
            .addValue(payment.orderTotal)
            .addValue(payment.startKind)
            .addValue(payment.startPayload)
            .addValue(payment.gatewayTransactionId)
            .addValue(payment.gatewayRefs)
            .addValue(payment.providerData)
            .addValue(payment.paidAmount)
            .addValue(payment.paidCurrency)
            .addValue(payment.gatewayFee)
            .addValue(payment.netAmount)
            .addValue(payment.settlementCurrency)
            .addValue(payment.settlementAmount)
            .addValue(payment.installments)
            .addValue(payment.methodDetail)
            .addValue(payment.testMode)
            .addValue(payment.duplicate)
            .addValue(payment.refundedAmount)
            .addValue(payment.failureCode)
            .addValue(payment.failureMessage)
            .addValue(payment.adminMessage)
            .addValue(payment.clientIp)
            .addValue(payment.userAgent)
            .addValue(payment.startedAt)
            .addValue(payment.paidAt)
            .addValue(payment.expiresAt)
            .addValue(payment.closedAt)
            .addValue(payment.nextQueryAt)
            .addValue(payment.queryCount)
            .addValue(payment.lastQueriedAt)
            .addValue(payment.createdAt)
            .addValue(payment.updatedAt)

        return try {
            sqlClient.preparedQuery(query).execute(values).coAwait().property(MySQLClient.LAST_INSERTED_ID)
        } catch (e: Exception) {
            if (e.isDuplicateKey()) null else throw e
        }
    }

    override suspend fun getById(id: Long, sqlClient: SqlClient): MarketPayment? =
        sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE `id` = ?")
            .execute(Tuple.of(id))
            .coAwait()
            .toEntities()
            .getOrNull(0)

    override suspend fun getByReference(reference: String, sqlClient: SqlClient): MarketPayment? =
        sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE `reference` = ?")
            .execute(Tuple.of(reference))
            .coAwait()
            .toEntities()
            .getOrNull(0)

    override suspend fun getByToken(token: String, sqlClient: SqlClient): MarketPayment? =
        sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE `token` = ?")
            .execute(Tuple.of(token))
            .coAwait()
            .toEntities()
            .getOrNull(0)

    override suspend fun getByProviderTransaction(providerId: String, gatewayTransactionId: String, sqlClient: SqlClient): MarketPayment? =
        sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE `providerId` = ? AND `gatewayTransactionId` = ?")
            .execute(Tuple.of(providerId, gatewayTransactionId))
            .coAwait()
            .toEntities()
            .getOrNull(0)

    override suspend fun getByOrderId(orderId: Long, sqlClient: SqlClient): List<MarketPayment> =
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
