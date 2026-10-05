package com.panomc.plugins.market.db.impl

import com.panomc.platform.annotation.Dao
import com.panomc.plugins.market.db.MarketSchema
import com.panomc.plugins.market.db.dao.MarketWebhookEndpointDao
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
class MarketWebhookEndpointDaoImpl : MarketWebhookEndpointDao() {

    override suspend fun init(sqlClient: SqlClient) {
        // Never throws (01 section 14.1 rule 3): a failed CREATE is logged and left to MarketSchema.ensure / SchemaVerifier.
        MarketSchema.installTable(sqlClient, MarketSchema.WEBHOOK_ENDPOINT, prefix())
    }

    override suspend fun add(endpoint: MarketWebhookEndpoint, sqlClient: SqlClient): Long {
        val query =
            "INSERT INTO `${prefix() + tableName}` (`name`, `url`, `events`, `format`, `signing`, `secret`, `headers`, `template`, `enabled`, `maxAttempts`, `failureCount`, `lastStatusCode`, `lastDeliveryAt`, `disabledReason`, `createdAt`, `updatedAt`) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        val values = Tuple.tuple()
            .addValue(endpoint.name)
            .addValue(endpoint.url)
            .addValue(endpoint.events)
            .addValue(endpoint.format.name)
            .addValue(endpoint.signing.name)
            .addValue(endpoint.secret)
            .addValue(endpoint.headers)
            .addValue(endpoint.template)
            .addValue(if (endpoint.enabled) 1 else 0)
            .addValue(endpoint.maxAttempts)
            .addValue(endpoint.failureCount)
            .addValue(endpoint.lastStatusCode)
            .addValue(endpoint.lastDeliveryAt)
            .addValue(endpoint.disabledReason)
            .addValue(endpoint.createdAt)
            .addValue(endpoint.updatedAt)

        return sqlClient.preparedQuery(query).execute(values).coAwait().property(MySQLClient.LAST_INSERTED_ID)
    }

    override suspend fun getById(id: Long, sqlClient: SqlClient): MarketWebhookEndpoint? =
        sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE `id` = ?")
            .execute(Tuple.of(id))
            .coAwait()
            .toEntities()
            .getOrNull(0)

    override suspend fun getAll(sqlClient: SqlClient): List<MarketWebhookEndpoint> =
        sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` ORDER BY `id` ASC")
            .execute()
            .coAwait()
            .toEntities()

    override suspend fun update(endpoint: MarketWebhookEndpoint, now: Long, sqlClient: SqlClient): Boolean =
        sqlClient
            .preparedQuery("UPDATE `${prefix() + tableName}` SET `name` = ?, `url` = ?, `events` = ?, `format` = ?, `signing` = ?, `secret` = ?, `headers` = ?, `template` = ?, `enabled` = ?, `maxAttempts` = ?, `disabledReason` = ?, `updatedAt` = ? WHERE `id` = ?")
            .execute(
                Tuple.tuple()
                    .addValue(endpoint.name)
                    .addValue(endpoint.url)
                    .addValue(endpoint.events)
                    .addValue(endpoint.format.name)
                    .addValue(endpoint.signing.name)
                    .addValue(endpoint.secret)
                    .addValue(endpoint.headers)
                    .addValue(endpoint.template)
                    .addValue(if (endpoint.enabled) 1 else 0)
                    .addValue(endpoint.maxAttempts)
                    .addValue(endpoint.disabledReason)
                    .addValue(now)
                    .addValue(endpoint.id)
            )
            .coAwait()
            .rowCount() > 0

    override suspend fun delete(id: Long, sqlClient: SqlClient): Boolean =
        sqlClient
            .preparedQuery("DELETE FROM `${prefix() + tableName}` WHERE `id` = ?")
            .execute(Tuple.of(id))
            .coAwait()
            .rowCount() > 0

    override suspend fun recordOutcome(id: Long, success: Boolean, statusCode: Int?, now: Long, disableAfter: Int, sqlClient: SqlClient): Boolean {
        // MariaDB evaluates the SET list left to right: `enabled` and `disabledReason` read the old failureCount, which is assigned last.
        val query = if (success) {
            "UPDATE `${prefix() + tableName}` SET `failureCount` = 0, `lastStatusCode` = ?, `lastDeliveryAt` = ?, `updatedAt` = ? WHERE `id` = ?"
        } else {
            "UPDATE `${prefix() + tableName}` SET " +
                "`enabled` = IF(`failureCount` + 1 >= ?, 0, `enabled`), " +
                "`disabledReason` = IF(`failureCount` + 1 >= ?, '$AUTO_DISABLED_REASON', `disabledReason`), " +
                "`lastStatusCode` = ?, `lastDeliveryAt` = ?, `updatedAt` = ?, `failureCount` = `failureCount` + 1 WHERE `id` = ?"
        }
        val values = if (success) Tuple.of(statusCode, now, now, id) else Tuple.of(disableAfter, disableAfter, statusCode, now, now, id)
        return sqlClient.preparedQuery(query).execute(values).coAwait().rowCount() > 0
    }

    override suspend fun uninstall(sqlClient: SqlClient) {
        sqlClient
            .query("DROP TABLE IF EXISTS `${prefix() + tableName}`")
            .execute()
            .coAwait()
    }
}
