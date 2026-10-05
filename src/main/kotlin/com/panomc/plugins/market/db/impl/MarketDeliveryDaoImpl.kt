package com.panomc.plugins.market.db.impl

import com.panomc.platform.annotation.Dao
import com.panomc.plugins.market.db.MarketSchema
import com.panomc.plugins.market.db.dao.MarketDeliveryDao
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
class MarketDeliveryDaoImpl : MarketDeliveryDao() {

    override suspend fun init(sqlClient: SqlClient) {
        // Never throws (01 section 14.1 rule 3): a failed CREATE is logged and left to MarketSchema.ensure / SchemaVerifier.
        MarketSchema.installTable(sqlClient, MarketSchema.DELIVERY, prefix())
    }

    override suspend fun add(delivery: MarketDelivery, sqlClient: SqlClient): Long? {
        val query =
            "INSERT INTO `${prefix() + tableName}` (`sourceType`, `orderId`, `orderItemId`, `sourceId`, `entitlementId`, `subscriptionId`, `phase`, `actionId`, `actionType`, `unitIndex`, `attemptGroup`, `serverId`, `idempotencyKey`, `status`, `requiresOnline`, `playerUsername`, `playerUuid`, `payload`, `result`, `transport`, `guaranteed`, `attempts`, `runAfter`, `nextAttemptAt`, `cancelRequestedAt`, `waitUntil`, `claimToken`, `claimedUntil`, `sentAt`, `confirmedAt`, `lastErrorCode`, `lastError`, `createdAt`, `updatedAt`) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        val values = Tuple.tuple()
            .addValue(delivery.sourceType.name)
            .addValue(delivery.orderId)
            .addValue(delivery.orderItemId)
            .addValue(delivery.sourceId)
            .addValue(delivery.entitlementId)
            .addValue(delivery.subscriptionId)
            .addValue(delivery.phase.name)
            .addValue(delivery.actionId)
            .addValue(delivery.actionType.name)
            .addValue(delivery.unitIndex)
            .addValue(delivery.attemptGroup)
            .addValue(delivery.serverId)
            .addValue(delivery.idempotencyKey)
            .addValue(delivery.status.name)
            .addValue(if (delivery.requiresOnline) 1 else 0)
            .addValue(delivery.playerUsername)
            .addValue(delivery.playerUuid)
            .addValue(delivery.payload)
            .addValue(delivery.result)
            .addValue(delivery.transport?.name)
            .addValue(if (delivery.guaranteed) 1 else 0)
            .addValue(delivery.attempts)
            .addValue(delivery.runAfter)
            .addValue(delivery.nextAttemptAt)
            .addValue(delivery.cancelRequestedAt)
            .addValue(delivery.waitUntil)
            .addValue(delivery.claimToken)
            .addValue(delivery.claimedUntil)
            .addValue(delivery.sentAt)
            .addValue(delivery.confirmedAt)
            .addValue(delivery.lastErrorCode)
            .addValue(delivery.lastError)
            .addValue(delivery.createdAt)
            .addValue(delivery.updatedAt)

        return try {
            sqlClient.preparedQuery(query).execute(values).coAwait().property(MySQLClient.LAST_INSERTED_ID)
        } catch (e: Exception) {
            if (e.isDuplicateKey()) null else throw e
        }
    }

    override suspend fun getById(id: Long, sqlClient: SqlClient): MarketDelivery? =
        sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE `id` = ?")
            .execute(Tuple.of(id))
            .coAwait()
            .toEntities()
            .getOrNull(0)

    override suspend fun getByIdempotencyKey(idempotencyKey: String, sqlClient: SqlClient): MarketDelivery? =
        sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE `idempotencyKey` = ?")
            .execute(Tuple.of(idempotencyKey))
            .coAwait()
            .toEntities()
            .getOrNull(0)

    override suspend fun getByOrderId(orderId: Long, sqlClient: SqlClient): List<MarketDelivery> =
        sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE `orderId` = ? ORDER BY `id` ASC")
            .execute(Tuple.of(orderId))
            .coAwait()
            .toEntities()

    override suspend fun getByOrderItemId(orderItemId: Long, sqlClient: SqlClient): List<MarketDelivery> =
        sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE `orderItemId` = ? ORDER BY `id` ASC")
            .execute(Tuple.of(orderItemId))
            .coAwait()
            .toEntities()

    override suspend fun getDue(status: DeliveryStatus, now: Long, limit: Int, sqlClient: SqlClient): List<MarketDelivery> =
        sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE `status` = ? AND `nextAttemptAt` IS NOT NULL AND `nextAttemptAt` <= ? ORDER BY `nextAttemptAt` ASC, `id` ASC LIMIT ?")
            .execute(Tuple.of(status.name, now, limit))
            .coAwait()
            .toEntities()

    override suspend fun getByServerAndStatus(serverId: Long, status: DeliveryStatus, limit: Int, sqlClient: SqlClient): List<MarketDelivery> =
        sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE `serverId` = ? AND `status` = ? ORDER BY `id` ASC LIMIT ?")
            .execute(Tuple.of(serverId, status.name, limit))
            .coAwait()
            .toEntities()

    override suspend fun transition(
        id: Long, from: DeliveryStatus, to: DeliveryStatus, now: Long,
        nextAttemptAt: Long?, lastErrorCode: String?, lastError: String?, sqlClient: SqlClient
    ): Boolean =
        sqlClient
            .preparedQuery("UPDATE `${prefix() + tableName}` SET `status` = ?, `nextAttemptAt` = ?, `lastErrorCode` = ?, `lastError` = ?, `updatedAt` = ? WHERE `id` = ? AND `status` = ?")
            .execute(Tuple.of(to.name, nextAttemptAt, lastErrorCode, lastError, now, id, from.name))
            .coAwait()
            .rowCount() > 0

    override suspend fun uninstall(sqlClient: SqlClient) {
        sqlClient
            .query("DROP TABLE IF EXISTS `${prefix() + tableName}`")
            .execute()
            .coAwait()
    }
}
