package com.panomc.plugins.market.db.impl

import com.panomc.platform.annotation.Dao
import com.panomc.plugins.market.db.MarketSchema
import com.panomc.plugins.market.db.dao.MarketSubscriptionDao
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
class MarketSubscriptionDaoImpl : MarketSubscriptionDao() {

    override suspend fun init(sqlClient: SqlClient) {
        // Never throws (01 section 14.1 rule 3): a failed CREATE is logged and left to MarketSchema.ensure / SchemaVerifier.
        MarketSchema.installTable(sqlClient, MarketSchema.SUBSCRIPTION, prefix())
    }

    private suspend fun one(where: String, values: Tuple, sqlClient: SqlClient): MarketSubscription? =
        sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE $where LIMIT 1")
            .execute(values)
            .coAwait()
            .toEntities()
            .getOrNull(0)

    private suspend fun many(where: String, order: String, values: Tuple, sqlClient: SqlClient): List<MarketSubscription> =
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

    override suspend fun add(subscription: MarketSubscription, sqlClient: SqlClient): Long? {
        val query =
            "INSERT INTO `${prefix() + tableName}` (`userId`, `playerUsername`, `ownerKey`, `email`, `productId`, `variantId`, `productName`, `initialOrderId`, `initialOrderItemId`, `entitlementId`, `providerId`, `mode`, `status`, `intervalUnit`, `intervalCount`, `price`, `currency`, `maxCycles`, `cycleCount`, `currentPeriodStart`, `currentPeriodEnd`, `nextChargeAt`, `nextQueryAt`, `lastQueriedAt`, `remoteCancelState`, `remoteCancelAttempts`, `graceEndsAt`, `cancelAtPeriodEnd`, `cancelRequestedAt`, `cancelledAt`, `endedAt`, `endReason`, `gatewaySubscriptionId`, `gatewayCustomerId`, `storedMethod`, `storedMethodLabel`, `failCount`, `lastFailureAt`, `reminderSentAt`, `targetServerId`, `fieldValues`, `providerData`, `testMode`, `createdAt`, `updatedAt`) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        val values = Tuple.tuple()
            .addValue(subscription.userId)
            .addValue(subscription.playerUsername)
            .addValue(subscription.ownerKey)
            .addValue(subscription.email)
            .addValue(subscription.productId)
            .addValue(subscription.variantId)
            .addValue(subscription.productName)
            .addValue(subscription.initialOrderId)
            .addValue(subscription.initialOrderItemId)
            .addValue(subscription.entitlementId)
            .addValue(subscription.providerId)
            .addValue(subscription.mode.name)
            .addValue(subscription.status.name)
            .addValue(subscription.intervalUnit.name)
            .addValue(subscription.intervalCount)
            .addValue(subscription.price)
            .addValue(subscription.currency)
            .addValue(subscription.maxCycles)
            .addValue(subscription.cycleCount)
            .addValue(subscription.currentPeriodStart)
            .addValue(subscription.currentPeriodEnd)
            .addValue(subscription.nextChargeAt)
            .addValue(subscription.nextQueryAt)
            .addValue(subscription.lastQueriedAt)
            .addValue(subscription.remoteCancelState.name)
            .addValue(subscription.remoteCancelAttempts)
            .addValue(subscription.graceEndsAt)
            .addValue(if (subscription.cancelAtPeriodEnd) 1 else 0)
            .addValue(subscription.cancelRequestedAt)
            .addValue(subscription.cancelledAt)
            .addValue(subscription.endedAt)
            .addValue(subscription.endReason)
            .addValue(subscription.gatewaySubscriptionId)
            .addValue(subscription.gatewayCustomerId)
            .addValue(subscription.storedMethod)
            .addValue(subscription.storedMethodLabel)
            .addValue(subscription.failCount)
            .addValue(subscription.lastFailureAt)
            .addValue(subscription.reminderSentAt)
            .addValue(subscription.targetServerId)
            .addValue(subscription.fieldValues)
            .addValue(subscription.providerData)
            .addValue(if (subscription.testMode) 1 else 0)
            .addValue(subscription.createdAt)
            .addValue(subscription.updatedAt)

        return try {
            sqlClient.preparedQuery(query).execute(values).coAwait().property(MySQLClient.LAST_INSERTED_ID)
        } catch (e: Exception) {
            if (e.isDuplicateKey()) null else throw e
        }
    }

    override suspend fun getById(id: Long, sqlClient: SqlClient): MarketSubscription? = one("`id` = ?", Tuple.of(id), sqlClient)

    override suspend fun getByGatewaySubscription(providerId: String, gatewaySubscriptionId: String, sqlClient: SqlClient): MarketSubscription? =
        one("`providerId` = ? AND `gatewaySubscriptionId` = ?", Tuple.of(providerId, gatewaySubscriptionId), sqlClient)

    override suspend fun getByOwnerKey(ownerKey: String, sqlClient: SqlClient): List<MarketSubscription> =
        many("`ownerKey` = ?", "`id` ASC", Tuple.of(ownerKey), sqlClient)

    override suspend fun getByUserId(userId: Long, sqlClient: SqlClient): List<MarketSubscription> =
        many("`userId` = ?", "`id` ASC", Tuple.of(userId), sqlClient)

    override suspend fun getDueForCharge(status: SubscriptionStatus, now: Long, limit: Int, sqlClient: SqlClient): List<MarketSubscription> =
        many("`status` = ? AND `nextChargeAt` IS NOT NULL AND `nextChargeAt` <= ?", "`nextChargeAt` ASC, `id` ASC LIMIT $limit", Tuple.of(status.name, now), sqlClient)

    override suspend fun getPeriodEnded(status: SubscriptionStatus, now: Long, limit: Int, sqlClient: SqlClient): List<MarketSubscription> =
        many("`status` = ? AND `currentPeriodEnd` IS NOT NULL AND `currentPeriodEnd` <= ?", "`currentPeriodEnd` ASC, `id` ASC LIMIT $limit", Tuple.of(status.name, now), sqlClient)

    override suspend fun getDueForQuery(now: Long, limit: Int, sqlClient: SqlClient): List<MarketSubscription> =
        many("`nextQueryAt` IS NOT NULL AND `nextQueryAt` <= ?", "`nextQueryAt` ASC, `id` ASC LIMIT $limit", Tuple.of(now), sqlClient)

    override suspend fun transition(id: Long, from: SubscriptionStatus, to: SubscriptionStatus, now: Long, sqlClient: SqlClient): Boolean =
        change("`status` = ?, `updatedAt` = ?", "`id` = ? AND `status` = ?", Tuple.of(to.name, now, id, from.name), sqlClient) > 0

    override suspend fun recordPaidPeriod(
        id: Long, expectedCycleCount: Int, periodStart: Long, periodEnd: Long, nextChargeAt: Long?, now: Long, sqlClient: SqlClient
    ): Boolean =
        change(
            "`cycleCount` = `cycleCount` + 1, `currentPeriodStart` = ?, `currentPeriodEnd` = ?, `nextChargeAt` = ?, `updatedAt` = ?",
            "`id` = ? AND `cycleCount` = ?",
            Tuple.of(periodStart, periodEnd, nextChargeAt, now, id, expectedCycleCount), sqlClient
        ) > 0

    override suspend fun setCancelAtPeriodEnd(id: Long, value: Boolean, now: Long, sqlClient: SqlClient): Boolean =
        change(
            "`cancelAtPeriodEnd` = ?, `cancelRequestedAt` = ?, `updatedAt` = ?", "`id` = ?",
            Tuple.of(if (value) 1 else 0, if (value) now else null, now, id), sqlClient
        ) > 0

    override suspend fun updateRemoteCancel(
        id: Long, from: RemoteCancelState, to: RemoteCancelState, attempts: Int, nextQueryAt: Long?, now: Long, sqlClient: SqlClient
    ): Boolean =
        change(
            "`remoteCancelState` = ?, `remoteCancelAttempts` = ?, `nextQueryAt` = ?, `updatedAt` = ?", "`id` = ? AND `remoteCancelState` = ?",
            Tuple.of(to.name, attempts, nextQueryAt, now, id, from.name), sqlClient
        ) > 0

    override suspend fun update(subscription: MarketSubscription, sqlClient: SqlClient): Boolean {
        val values = Tuple.tuple()
            .addValue(subscription.email)
            .addValue(subscription.entitlementId)
            .addValue(subscription.mode.name)
            .addValue(subscription.status.name)
            .addValue(subscription.intervalUnit.name)
            .addValue(subscription.intervalCount)
            .addValue(subscription.price)
            .addValue(subscription.currency)
            .addValue(subscription.maxCycles)
            .addValue(subscription.cycleCount)
            .addValue(subscription.currentPeriodStart)
            .addValue(subscription.currentPeriodEnd)
            .addValue(subscription.nextChargeAt)
            .addValue(subscription.nextQueryAt)
            .addValue(subscription.lastQueriedAt)
            .addValue(subscription.remoteCancelState.name)
            .addValue(subscription.remoteCancelAttempts)
            .addValue(subscription.graceEndsAt)
            .addValue(if (subscription.cancelAtPeriodEnd) 1 else 0)
            .addValue(subscription.cancelRequestedAt)
            .addValue(subscription.cancelledAt)
            .addValue(subscription.endedAt)
            .addValue(subscription.endReason)
            .addValue(subscription.gatewaySubscriptionId)
            .addValue(subscription.gatewayCustomerId)
            .addValue(subscription.storedMethod)
            .addValue(subscription.storedMethodLabel)
            .addValue(subscription.failCount)
            .addValue(subscription.lastFailureAt)
            .addValue(subscription.reminderSentAt)
            .addValue(subscription.targetServerId)
            .addValue(subscription.fieldValues)
            .addValue(subscription.providerData)
            .addValue(if (subscription.testMode) 1 else 0)
            .addValue(subscription.updatedAt)
            .addValue(subscription.id)
        return change("`email` = ?, `entitlementId` = ?, `mode` = ?, `status` = ?, `intervalUnit` = ?, `intervalCount` = ?, `price` = ?, `currency` = ?, `maxCycles` = ?, `cycleCount` = ?, `currentPeriodStart` = ?, `currentPeriodEnd` = ?, `nextChargeAt` = ?, `nextQueryAt` = ?, `lastQueriedAt` = ?, `remoteCancelState` = ?, `remoteCancelAttempts` = ?, `graceEndsAt` = ?, `cancelAtPeriodEnd` = ?, `cancelRequestedAt` = ?, `cancelledAt` = ?, `endedAt` = ?, `endReason` = ?, `gatewaySubscriptionId` = ?, `gatewayCustomerId` = ?, `storedMethod` = ?, `storedMethodLabel` = ?, `failCount` = ?, `lastFailureAt` = ?, `reminderSentAt` = ?, `targetServerId` = ?, `fieldValues` = ?, `providerData` = ?, `testMode` = ?, `updatedAt` = ?", "`id` = ?", values, sqlClient) > 0
    }

    override suspend fun uninstall(sqlClient: SqlClient) {
        sqlClient
            .query("DROP TABLE IF EXISTS `${prefix() + tableName}`")
            .execute()
            .coAwait()
    }
}
