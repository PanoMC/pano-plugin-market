package com.panomc.plugins.market.db.impl

import com.panomc.platform.annotation.Dao
import com.panomc.plugins.market.db.MarketSchema
import com.panomc.plugins.market.db.dao.MarketShippingCarrierDao
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
class MarketShippingCarrierDaoImpl : MarketShippingCarrierDao() {

    override suspend fun init(sqlClient: SqlClient) {
        // Never throws (01 section 14.1 rule 3): a failed CREATE is logged and left to MarketSchema.ensure / SchemaVerifier.
        MarketSchema.installTable(sqlClient, MarketSchema.SHIPPING_CARRIER, prefix())
    }

    private suspend fun one(where: String, values: Tuple, sqlClient: SqlClient): MarketShippingCarrier? =
        sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE $where LIMIT 1")
            .execute(values)
            .coAwait()
            .toEntities()
            .getOrNull(0)

    private suspend fun many(where: String, order: String, values: Tuple, sqlClient: SqlClient): List<MarketShippingCarrier> =
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

    private suspend fun remove(where: String, values: Tuple, sqlClient: SqlClient): Int =
        sqlClient
            .preparedQuery("DELETE FROM `${prefix() + tableName}` WHERE $where")
            .execute(values)
            .coAwait()
            .rowCount()

    override suspend fun add(carrier: MarketShippingCarrier, sqlClient: SqlClient): Long? {
        val query =
            "INSERT INTO `${prefix() + tableName}` (`providerId`, `enabled`, `settings`, `testMode`, `webhookToken`, `lastInboundAt`, `lastError`, `lastErrorAt`, `createdAt`, `updatedAt`) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        val values = Tuple.tuple()
            .addValue(carrier.providerId)
            .addValue(if (carrier.enabled) 1 else 0)
            .addValue(carrier.settings)
            .addValue(if (carrier.testMode) 1 else 0)
            .addValue(carrier.webhookToken)
            .addValue(carrier.lastInboundAt)
            .addValue(carrier.lastError)
            .addValue(carrier.lastErrorAt)
            .addValue(carrier.createdAt)
            .addValue(carrier.updatedAt)

        return try {
            sqlClient.preparedQuery(query).execute(values).coAwait().property(MySQLClient.LAST_INSERTED_ID)
        } catch (e: Exception) {
            if (e.isDuplicateKey()) null else throw e
        }
    }

    override suspend fun getById(id: Long, sqlClient: SqlClient): MarketShippingCarrier? = one("`id` = ?", Tuple.of(id), sqlClient)

    override suspend fun update(carrier: MarketShippingCarrier, sqlClient: SqlClient): Boolean {
        val values = Tuple.tuple()
            .addValue(if (carrier.enabled) 1 else 0)
            .addValue(carrier.settings)
            .addValue(if (carrier.testMode) 1 else 0)
            .addValue(carrier.webhookToken)
            .addValue(carrier.lastInboundAt)
            .addValue(carrier.lastError)
            .addValue(carrier.lastErrorAt)
            .addValue(carrier.updatedAt)
            .addValue(carrier.id)
        return try {
            change("`enabled` = ?, `settings` = ?, `testMode` = ?, `webhookToken` = ?, `lastInboundAt` = ?, `lastError` = ?, `lastErrorAt` = ?, `updatedAt` = ?", "`id` = ?", values, sqlClient) > 0
        } catch (e: Exception) {
            if (e.isDuplicateKey()) false else throw e
        }
    }

    override suspend fun getByProviderId(providerId: String, sqlClient: SqlClient): MarketShippingCarrier? =
        one("`providerId` = ?", Tuple.of(providerId), sqlClient)

    override suspend fun getByWebhookToken(webhookToken: String, sqlClient: SqlClient): MarketShippingCarrier? =
        one("`webhookToken` = ?", Tuple.of(webhookToken), sqlClient)

    override suspend fun getAll(sqlClient: SqlClient): List<MarketShippingCarrier> =
        many("1 = 1", "`id` ASC", Tuple.tuple(), sqlClient)

    override suspend fun getEnabled(sqlClient: SqlClient): List<MarketShippingCarrier> =
        many("`enabled` = 1", "`id` ASC", Tuple.tuple(), sqlClient)

    override suspend fun recordInbound(providerId: String, now: Long, sqlClient: SqlClient): Boolean =
        change("`lastInboundAt` = ?, `updatedAt` = ?", "`providerId` = ?", Tuple.of(now, now, providerId), sqlClient) > 0

    override suspend fun recordError(id: Long, error: String?, now: Long, sqlClient: SqlClient): Boolean =
        change(
            "`lastError` = ?, `lastErrorAt` = ?, `updatedAt` = ?", "`id` = ?",
            Tuple.of(error, if (error == null) null else now, now, id), sqlClient
        ) > 0

    override suspend fun uninstall(sqlClient: SqlClient) {
        sqlClient
            .query("DROP TABLE IF EXISTS `${prefix() + tableName}`")
            .execute()
            .coAwait()
    }
}
