package com.panomc.plugins.market.db.impl

import com.panomc.platform.annotation.Dao
import com.panomc.plugins.market.db.MarketSchema
import com.panomc.plugins.market.db.dao.MarketShipmentDao
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
class MarketShipmentDaoImpl : MarketShipmentDao() {

    override suspend fun init(sqlClient: SqlClient) {
        // Never throws (01 section 14.1 rule 3): a failed CREATE is logged and left to MarketSchema.ensure / SchemaVerifier.
        MarketSchema.installTable(sqlClient, MarketSchema.SHIPMENT, prefix())
    }

    private suspend fun one(where: String, values: Tuple, sqlClient: SqlClient): MarketShipment? =
        sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE $where LIMIT 1")
            .execute(values)
            .coAwait()
            .toEntities()
            .getOrNull(0)

    private suspend fun many(where: String, order: String, values: Tuple, sqlClient: SqlClient): List<MarketShipment> =
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

    override suspend fun add(shipment: MarketShipment, sqlClient: SqlClient): Long? {
        val query =
            "INSERT INTO `${prefix() + tableName}` (`orderId`, `methodId`, `providerId`, `serviceCode`, `status`, `entryMode`, `merchantReference`, `carrierReference`, `trackingNumber`, `trackingUrl`, `carrierName`, `labelFile`, `labelFormat`, `documents`, `rateRef`, `cost`, `costCurrency`, `weightGrams`, `packages`, `estimatedDeliveryAt`, `note`, `lastErrorCode`, `lastError`, `claimedUntil`, `itemsReleased`, `stale`, `fromAddress`, `toAddress`, `codAmount`, `providerData`, `testMode`, `nextPollAt`, `pollCount`, `lastPolledAt`, `shippedAt`, `deliveredAt`, `cancelledAt`, `trackingMailSentAt`, `createdBy`, `createdAt`, `updatedAt`) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        val values = Tuple.tuple()
            .addValue(shipment.orderId)
            .addValue(shipment.methodId)
            .addValue(shipment.providerId)
            .addValue(shipment.serviceCode)
            .addValue(shipment.status.name)
            .addValue(shipment.entryMode.name)
            .addValue(shipment.merchantReference)
            .addValue(shipment.carrierReference)
            .addValue(shipment.trackingNumber)
            .addValue(shipment.trackingUrl)
            .addValue(shipment.carrierName)
            .addValue(shipment.labelFile)
            .addValue(shipment.labelFormat)
            .addValue(shipment.documents)
            .addValue(shipment.rateRef)
            .addValue(shipment.cost)
            .addValue(shipment.costCurrency)
            .addValue(shipment.weightGrams)
            .addValue(shipment.packages)
            .addValue(shipment.estimatedDeliveryAt)
            .addValue(shipment.note)
            .addValue(shipment.lastErrorCode)
            .addValue(shipment.lastError)
            .addValue(shipment.claimedUntil)
            .addValue(if (shipment.itemsReleased) 1 else 0)
            .addValue(if (shipment.stale) 1 else 0)
            .addValue(shipment.fromAddress)
            .addValue(shipment.toAddress)
            .addValue(shipment.codAmount)
            .addValue(shipment.providerData)
            .addValue(if (shipment.testMode) 1 else 0)
            .addValue(shipment.nextPollAt)
            .addValue(shipment.pollCount)
            .addValue(shipment.lastPolledAt)
            .addValue(shipment.shippedAt)
            .addValue(shipment.deliveredAt)
            .addValue(shipment.cancelledAt)
            .addValue(shipment.trackingMailSentAt)
            .addValue(shipment.createdBy)
            .addValue(shipment.createdAt)
            .addValue(shipment.updatedAt)

        return try {
            sqlClient.preparedQuery(query).execute(values).coAwait().property(MySQLClient.LAST_INSERTED_ID)
        } catch (e: Exception) {
            if (e.isDuplicateKey()) null else throw e
        }
    }

    override suspend fun getById(id: Long, sqlClient: SqlClient): MarketShipment? = one("`id` = ?", Tuple.of(id), sqlClient)

    override suspend fun update(shipment: MarketShipment, sqlClient: SqlClient): Boolean {
        val values = Tuple.tuple()
            .addValue(shipment.methodId)
            .addValue(shipment.serviceCode)
            .addValue(shipment.status.name)
            .addValue(shipment.entryMode.name)
            .addValue(shipment.carrierReference)
            .addValue(shipment.trackingNumber)
            .addValue(shipment.trackingUrl)
            .addValue(shipment.carrierName)
            .addValue(shipment.labelFile)
            .addValue(shipment.labelFormat)
            .addValue(shipment.documents)
            .addValue(shipment.rateRef)
            .addValue(shipment.cost)
            .addValue(shipment.costCurrency)
            .addValue(shipment.weightGrams)
            .addValue(shipment.packages)
            .addValue(shipment.estimatedDeliveryAt)
            .addValue(shipment.note)
            .addValue(shipment.lastErrorCode)
            .addValue(shipment.lastError)
            .addValue(shipment.claimedUntil)
            .addValue(if (shipment.itemsReleased) 1 else 0)
            .addValue(if (shipment.stale) 1 else 0)
            .addValue(shipment.fromAddress)
            .addValue(shipment.toAddress)
            .addValue(shipment.codAmount)
            .addValue(shipment.providerData)
            .addValue(if (shipment.testMode) 1 else 0)
            .addValue(shipment.nextPollAt)
            .addValue(shipment.pollCount)
            .addValue(shipment.lastPolledAt)
            .addValue(shipment.shippedAt)
            .addValue(shipment.deliveredAt)
            .addValue(shipment.cancelledAt)
            .addValue(shipment.trackingMailSentAt)
            .addValue(shipment.updatedAt)
            .addValue(shipment.id)
        return try {
            change("`methodId` = ?, `serviceCode` = ?, `status` = ?, `entryMode` = ?, `carrierReference` = ?, `trackingNumber` = ?, `trackingUrl` = ?, `carrierName` = ?, `labelFile` = ?, `labelFormat` = ?, `documents` = ?, `rateRef` = ?, `cost` = ?, `costCurrency` = ?, `weightGrams` = ?, `packages` = ?, `estimatedDeliveryAt` = ?, `note` = ?, `lastErrorCode` = ?, `lastError` = ?, `claimedUntil` = ?, `itemsReleased` = ?, `stale` = ?, `fromAddress` = ?, `toAddress` = ?, `codAmount` = ?, `providerData` = ?, `testMode` = ?, `nextPollAt` = ?, `pollCount` = ?, `lastPolledAt` = ?, `shippedAt` = ?, `deliveredAt` = ?, `cancelledAt` = ?, `trackingMailSentAt` = ?, `updatedAt` = ?", "`id` = ?", values, sqlClient) > 0
        } catch (e: Exception) {
            if (e.isDuplicateKey()) false else throw e
        }
    }

    override suspend fun getByMerchantReference(merchantReference: String, sqlClient: SqlClient): MarketShipment? =
        one("`merchantReference` = ?", Tuple.of(merchantReference), sqlClient)

    override suspend fun getByCarrierReference(providerId: String, carrierReference: String, sqlClient: SqlClient): MarketShipment? =
        one("`providerId` = ? AND `carrierReference` = ?", Tuple.of(providerId, carrierReference), sqlClient)

    override suspend fun getByTrackingNumber(trackingNumber: String, sqlClient: SqlClient): List<MarketShipment> =
        many("`trackingNumber` = ?", "`id` ASC", Tuple.of(trackingNumber), sqlClient)

    override suspend fun getByOrderId(orderId: Long, sqlClient: SqlClient): List<MarketShipment> =
        many("`orderId` = ?", "`id` ASC", Tuple.of(orderId), sqlClient)

    override suspend fun getDueForPoll(statuses: List<ShipmentStatus>, now: Long, limit: Int, sqlClient: SqlClient): List<MarketShipment> {
        if (statuses.isEmpty()) return emptyList()
        val marks = statuses.joinToString(", ") { "?" }
        val values = Tuple.tuple()
        statuses.forEach { values.addValue(it.name) }
        values.addValue(now)
        return many("`status` IN ($marks) AND `nextPollAt` IS NOT NULL AND `nextPollAt` <= ?", "`nextPollAt` ASC, `id` ASC LIMIT $limit", values, sqlClient)
    }

    override suspend fun transition(id: Long, from: ShipmentStatus, to: ShipmentStatus, now: Long, sqlClient: SqlClient): Boolean =
        change("`status` = ?, `updatedAt` = ?", "`id` = ? AND `status` = ?", Tuple.of(to.name, now, id, from.name), sqlClient) > 0

    override suspend fun claim(id: Long, now: Long, until: Long, sqlClient: SqlClient): Boolean =
        change(
            "`claimedUntil` = ?, `updatedAt` = ?", "`id` = ? AND (`claimedUntil` IS NULL OR `claimedUntil` <= ?)",
            Tuple.of(until, now, id, now), sqlClient
        ) > 0

    override suspend fun releaseClaim(id: Long, now: Long, sqlClient: SqlClient): Boolean =
        change("`claimedUntil` = NULL, `updatedAt` = ?", "`id` = ?", Tuple.of(now, id), sqlClient) > 0

    override suspend fun recordPoll(id: Long, polledAt: Long, nextPollAt: Long?, sqlClient: SqlClient): Boolean =
        change(
            "`pollCount` = `pollCount` + 1, `lastPolledAt` = ?, `nextPollAt` = ?, `updatedAt` = ?", "`id` = ?",
            Tuple.of(polledAt, nextPollAt, polledAt, id), sqlClient
        ) > 0

    override suspend fun uninstall(sqlClient: SqlClient) {
        sqlClient
            .query("DROP TABLE IF EXISTS `${prefix() + tableName}`")
            .execute()
            .coAwait()
    }
}
