package com.panomc.plugins.market.db.impl

import com.panomc.platform.annotation.Dao
import com.panomc.plugins.market.db.MarketSchema
import com.panomc.plugins.market.db.dao.MarketWebhookDeliveryDao
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
class MarketWebhookDeliveryDaoImpl : MarketWebhookDeliveryDao() {

    override suspend fun init(sqlClient: SqlClient) {
        // Never throws (01 section 14.1 rule 3): a failed CREATE is logged and left to MarketSchema.ensure / SchemaVerifier.
        MarketSchema.installTable(sqlClient, MarketSchema.WEBHOOK_DELIVERY, prefix())
    }

    override suspend fun add(delivery: MarketWebhookDelivery, sqlClient: SqlClient): Long? {
        val query =
            "INSERT INTO `${prefix() + tableName}` (`endpointId`, `deliveryId`, `eventId`, `event`, `orderId`, `url`, `format`, `signing`, `secret`, `body`, `status`, `attempts`, `maxAttempts`, `nextAttemptAt`, `claimedUntil`, `lastStatusCode`, `lastError`, `lastResponse`, `durationMs`, `deliveredAt`, `createdAt`, `updatedAt`) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        val values = Tuple.tuple()
            .addValue(delivery.endpointId)
            .addValue(delivery.deliveryId)
            .addValue(delivery.eventId)
            .addValue(delivery.event)
            .addValue(delivery.orderId)
            .addValue(delivery.url)
            .addValue(delivery.format.name)
            .addValue(delivery.signing.name)
            .addValue(delivery.secret)
            .addValue(delivery.body)
            .addValue(delivery.status.name)
            .addValue(delivery.attempts)
            .addValue(delivery.maxAttempts)
            .addValue(delivery.nextAttemptAt)
            .addValue(delivery.claimedUntil)
            .addValue(delivery.lastStatusCode)
            .addValue(delivery.lastError)
            .addValue(delivery.lastResponse)
            .addValue(delivery.durationMs)
            .addValue(delivery.deliveredAt)
            .addValue(delivery.createdAt)
            .addValue(delivery.updatedAt)

        return try {
            sqlClient.preparedQuery(query).execute(values).coAwait().property(MySQLClient.LAST_INSERTED_ID)
        } catch (e: Exception) {
            if (e.isDuplicateKey()) null else throw e
        }
    }

    override suspend fun getById(id: Long, sqlClient: SqlClient): MarketWebhookDelivery? =
        sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE `id` = ?")
            .execute(Tuple.of(id))
            .coAwait()
            .toEntities()
            .getOrNull(0)

    override suspend fun getByEventId(eventId: String, sqlClient: SqlClient): MarketWebhookDelivery? =
        sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE `eventId` = ?")
            .execute(Tuple.of(eventId))
            .coAwait()
            .toEntities()
            .getOrNull(0)

    override suspend fun getByEndpointId(endpointId: Long, limit: Int, sqlClient: SqlClient): List<MarketWebhookDelivery> =
        sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE `endpointId` = ? ORDER BY `id` DESC LIMIT ?")
            .execute(Tuple.of(endpointId, limit))
            .coAwait()
            .toEntities()

    override suspend fun getByOrderId(orderId: Long, sqlClient: SqlClient): List<MarketWebhookDelivery> =
        sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE `orderId` = ? ORDER BY `id` ASC")
            .execute(Tuple.of(orderId))
            .coAwait()
            .toEntities()

    override suspend fun getDue(status: WebhookDeliveryStatus, now: Long, limit: Int, sqlClient: SqlClient): List<MarketWebhookDelivery> =
        sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE `status` = ? AND `nextAttemptAt` IS NOT NULL AND `nextAttemptAt` <= ? ORDER BY `nextAttemptAt` ASC, `id` ASC LIMIT ?")
            .execute(Tuple.of(status.name, now, limit))
            .coAwait()
            .toEntities()

    override suspend fun markResult(
        id: Long, from: WebhookDeliveryStatus, to: WebhookDeliveryStatus, attempts: Int, nextAttemptAt: Long?,
        statusCode: Int?, error: String?, response: String?, durationMs: Int?, deliveredAt: Long?, now: Long, sqlClient: SqlClient
    ): Boolean =
        sqlClient
            .preparedQuery("UPDATE `${prefix() + tableName}` SET `status` = ?, `attempts` = ?, `nextAttemptAt` = ?, `claimedUntil` = NULL, `lastStatusCode` = ?, `lastError` = ?, `lastResponse` = ?, `durationMs` = ?, `deliveredAt` = ?, `updatedAt` = ? WHERE `id` = ? AND `status` = ?")
            .execute(
                Tuple.tuple()
                    .addValue(to.name).addValue(attempts).addValue(nextAttemptAt).addValue(statusCode).addValue(error)
                    .addValue(response).addValue(durationMs).addValue(deliveredAt).addValue(now).addValue(id).addValue(from.name)
            )
            .coAwait()
            .rowCount() > 0

    override suspend fun uninstall(sqlClient: SqlClient) {
        sqlClient
            .query("DROP TABLE IF EXISTS `${prefix() + tableName}`")
            .execute()
            .coAwait()
    }
}
