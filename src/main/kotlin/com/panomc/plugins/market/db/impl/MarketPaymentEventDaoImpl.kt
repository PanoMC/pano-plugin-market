package com.panomc.plugins.market.db.impl

import com.panomc.platform.annotation.Dao
import com.panomc.plugins.market.db.MarketSchema
import com.panomc.plugins.market.db.dao.MarketPaymentEventDao
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
class MarketPaymentEventDaoImpl : MarketPaymentEventDao() {

    override suspend fun init(sqlClient: SqlClient) {
        // Never throws (01 section 14.1 rule 3): a failed CREATE is logged and left to MarketSchema.ensure / SchemaVerifier.
        MarketSchema.installTable(sqlClient, MarketSchema.PAYMENT_EVENT, prefix())
    }

    override suspend fun add(paymentEvent: MarketPaymentEvent, sqlClient: SqlClient): Long? {
        val query =
            "INSERT INTO `${prefix() + tableName}` (`providerId`, `direction`, `channel`, `subChannel`, `eventKey`, `requestHash`, `paymentId`, `orderId`, `refundId`, `subscriptionId`, `method`, `url`, `headers`, `body`, `remoteIp`, `verified`, `eventTypes`, `status`, `attempts`, `duplicateCount`, `nextAttemptAt`, `responseStatus`, `error`, `durationMs`, `processedAt`, `createdAt`, `updatedAt`) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        val values = Tuple.tuple()
            .addValue(paymentEvent.providerId)
            .addValue(paymentEvent.direction.name)
            .addValue(paymentEvent.channel)
            .addValue(paymentEvent.subChannel)
            .addValue(paymentEvent.eventKey)
            .addValue(paymentEvent.requestHash)
            .addValue(paymentEvent.paymentId)
            .addValue(paymentEvent.orderId)
            .addValue(paymentEvent.refundId)
            .addValue(paymentEvent.subscriptionId)
            .addValue(paymentEvent.method)
            .addValue(paymentEvent.url)
            .addValue(paymentEvent.headers)
            .addValue(paymentEvent.body)
            .addValue(paymentEvent.remoteIp)
            .addValue(paymentEvent.verified)
            .addValue(paymentEvent.eventTypes)
            .addValue(paymentEvent.status.name)
            .addValue(paymentEvent.attempts)
            .addValue(paymentEvent.duplicateCount)
            .addValue(paymentEvent.nextAttemptAt)
            .addValue(paymentEvent.responseStatus)
            .addValue(paymentEvent.error)
            .addValue(paymentEvent.durationMs)
            .addValue(paymentEvent.processedAt)
            .addValue(paymentEvent.createdAt)
            .addValue(paymentEvent.updatedAt)

        return try {
            sqlClient.preparedQuery(query).execute(values).coAwait().property(MySQLClient.LAST_INSERTED_ID)
        } catch (e: Exception) {
            if (e.isDuplicateKey()) null else throw e
        }
    }

    override suspend fun getById(id: Long, sqlClient: SqlClient): MarketPaymentEvent? =
        sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE `id` = ?")
            .execute(Tuple.of(id))
            .coAwait()
            .toEntities()
            .getOrNull(0)

    override suspend fun getByEventKey(providerId: String, direction: PaymentEventDirection, eventKey: String, sqlClient: SqlClient): MarketPaymentEvent? =
        sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE `providerId` = ? AND `direction` = ? AND `eventKey` = ?")
            .execute(Tuple.of(providerId, direction.name, eventKey))
            .coAwait()
            .toEntities()
            .getOrNull(0)

    override suspend fun getByPaymentId(paymentId: Long, sqlClient: SqlClient): List<MarketPaymentEvent> =
        sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE `paymentId` = ? ORDER BY `id` ASC")
            .execute(Tuple.of(paymentId))
            .coAwait()
            .toEntities()

    override suspend fun uninstall(sqlClient: SqlClient) {
        sqlClient
            .query("DROP TABLE IF EXISTS `${prefix() + tableName}`")
            .execute()
            .coAwait()
    }
}
