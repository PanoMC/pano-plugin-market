package com.panomc.plugins.market.db.impl

import com.panomc.platform.annotation.Dao
import com.panomc.plugins.market.db.MarketSchema
import com.panomc.plugins.market.db.dao.MarketSubscriptionRenewalDao
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
class MarketSubscriptionRenewalDaoImpl : MarketSubscriptionRenewalDao() {

    override suspend fun init(sqlClient: SqlClient) {
        // Never throws (01 section 14.1 rule 3): a failed CREATE is logged and left to MarketSchema.ensure / SchemaVerifier.
        MarketSchema.installTable(sqlClient, MarketSchema.SUBSCRIPTION_RENEWAL, prefix())
    }

    private suspend fun one(where: String, values: Tuple, sqlClient: SqlClient): MarketSubscriptionRenewal? =
        sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE $where LIMIT 1")
            .execute(values)
            .coAwait()
            .toEntities()
            .getOrNull(0)

    private suspend fun many(where: String, order: String, values: Tuple, sqlClient: SqlClient): List<MarketSubscriptionRenewal> =
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

    override suspend fun add(renewal: MarketSubscriptionRenewal, sqlClient: SqlClient): Long? {
        val query =
            "INSERT INTO `${prefix() + tableName}` (`subscriptionId`, `periodIndex`, `periodStart`, `periodEnd`, `orderId`, `paymentId`, `status`, `amount`, `currency`, `attempts`, `nextAttemptAt`, `lastError`, `createdAt`, `updatedAt`) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        val values = Tuple.tuple()
            .addValue(renewal.subscriptionId)
            .addValue(renewal.periodIndex)
            .addValue(renewal.periodStart)
            .addValue(renewal.periodEnd)
            .addValue(renewal.orderId)
            .addValue(renewal.paymentId)
            .addValue(renewal.status.name)
            .addValue(renewal.amount)
            .addValue(renewal.currency)
            .addValue(renewal.attempts)
            .addValue(renewal.nextAttemptAt)
            .addValue(renewal.lastError)
            .addValue(renewal.createdAt)
            .addValue(renewal.updatedAt)

        return try {
            sqlClient.preparedQuery(query).execute(values).coAwait().property(MySQLClient.LAST_INSERTED_ID)
        } catch (e: Exception) {
            if (e.isDuplicateKey()) null else throw e
        }
    }

    override suspend fun getById(id: Long, sqlClient: SqlClient): MarketSubscriptionRenewal? = one("`id` = ?", Tuple.of(id), sqlClient)

    override suspend fun getByPeriod(subscriptionId: Long, periodIndex: Int, sqlClient: SqlClient): MarketSubscriptionRenewal? =
        one("`subscriptionId` = ? AND `periodIndex` = ?", Tuple.of(subscriptionId, periodIndex), sqlClient)

    override suspend fun getBySubscriptionId(subscriptionId: Long, sqlClient: SqlClient): List<MarketSubscriptionRenewal> =
        many("`subscriptionId` = ?", "`periodIndex` ASC", Tuple.of(subscriptionId), sqlClient)

    override suspend fun getDue(status: RenewalStatus, now: Long, limit: Int, sqlClient: SqlClient): List<MarketSubscriptionRenewal> =
        many("`status` = ? AND `nextAttemptAt` IS NOT NULL AND `nextAttemptAt` <= ?", "`nextAttemptAt` ASC, `id` ASC LIMIT $limit", Tuple.of(status.name, now), sqlClient)

    override suspend fun transition(id: Long, from: RenewalStatus, to: RenewalStatus, now: Long, sqlClient: SqlClient): Boolean =
        change("`status` = ?, `updatedAt` = ?", "`id` = ? AND `status` = ?", Tuple.of(to.name, now, id, from.name), sqlClient) > 0

    override suspend fun attach(id: Long, orderId: Long?, paymentId: Long?, now: Long, sqlClient: SqlClient): Boolean =
        change(
            "`orderId` = COALESCE(?, `orderId`), `paymentId` = COALESCE(?, `paymentId`), `updatedAt` = ?", "`id` = ?",
            Tuple.of(orderId, paymentId, now, id), sqlClient
        ) > 0

    override suspend fun recordAttempt(id: Long, nextAttemptAt: Long?, lastError: String?, now: Long, sqlClient: SqlClient): Boolean =
        change(
            "`attempts` = `attempts` + 1, `nextAttemptAt` = ?, `lastError` = ?, `updatedAt` = ?", "`id` = ?",
            Tuple.of(nextAttemptAt, lastError, now, id), sqlClient
        ) > 0

    override suspend fun uninstall(sqlClient: SqlClient) {
        sqlClient
            .query("DROP TABLE IF EXISTS `${prefix() + tableName}`")
            .execute()
            .coAwait()
    }
}
