package com.panomc.plugins.market.db.impl

import com.panomc.platform.annotation.Dao
import com.panomc.plugins.market.db.MarketSchema
import com.panomc.plugins.market.db.dao.MarketEntitlementDao
import com.panomc.plugins.market.db.dao.isDuplicateKey
import com.panomc.plugins.market.db.model.*
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.mysqlclient.MySQLClient
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope

@Dao
@Lazy
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class MarketEntitlementDaoImpl : MarketEntitlementDao() {

    override suspend fun init(sqlClient: SqlClient) {
        // Never throws (01 section 14.1 rule 3): a failed CREATE is logged and left to MarketSchema.ensure / SchemaVerifier.
        MarketSchema.installTable(sqlClient, MarketSchema.ENTITLEMENT, prefix())
    }

    override suspend fun add(entitlement: MarketEntitlement, sqlClient: SqlClient): Long {
        val query =
            "INSERT INTO `${prefix() + tableName}` (`userId`, `playerUsername`, `ownerKey`, `productId`, `variantId`, `orderId`, `orderItemId`, `subscriptionId`, `quantity`, `status`, `startsAt`, `expiresAt`, `tierCategoryId`, `tierRank`, `pricePaid`, `replacedById`, `endReason`, `reminderSentAt`, `endedAt`, `createdAt`, `updatedAt`) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        val values = Tuple.tuple()
            .addValue(entitlement.userId)
            .addValue(entitlement.playerUsername)
            .addValue(entitlement.ownerKey)
            .addValue(entitlement.productId)
            .addValue(entitlement.variantId)
            .addValue(entitlement.orderId)
            .addValue(entitlement.orderItemId)
            .addValue(entitlement.subscriptionId)
            .addValue(entitlement.quantity)
            .addValue(entitlement.status.name)
            .addValue(entitlement.startsAt)
            .addValue(entitlement.expiresAt)
            .addValue(entitlement.tierCategoryId)
            .addValue(entitlement.tierRank)
            .addValue(entitlement.pricePaid)
            .addValue(entitlement.replacedById)
            .addValue(entitlement.endReason)
            .addValue(entitlement.reminderSentAt)
            .addValue(entitlement.endedAt)
            .addValue(entitlement.createdAt)
            .addValue(entitlement.updatedAt)

        return sqlClient.preparedQuery(query).execute(values).coAwait().property(MySQLClient.LAST_INSERTED_ID)
    }

    override suspend fun getById(id: Long, sqlClient: SqlClient): MarketEntitlement? {
        val rows: RowSet<Row> = sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE `id` = ?")
            .execute(Tuple.of(id))
            .coAwait()

        return rows.toEntities().getOrNull(0)
    }

    override suspend fun getByOwnerAndProduct(ownerKey: String, productId: Long, sqlClient: SqlClient): List<MarketEntitlement> =
        sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE `ownerKey` = ? AND `productId` = ? ORDER BY `id` ASC")
            .execute(Tuple.of(ownerKey, productId))
            .coAwait()
            .toEntities()

    override suspend fun getByOrderItemId(orderItemId: Long, sqlClient: SqlClient): List<MarketEntitlement> =
        sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE `orderItemId` = ? ORDER BY `id` ASC")
            .execute(Tuple.of(orderItemId))
            .coAwait()
            .toEntities()

    override suspend fun end(id: Long, status: EntitlementStatus, endReason: String, endedAt: Long, sqlClient: SqlClient): Boolean =
        sqlClient
            .preparedQuery("UPDATE `${prefix() + tableName}` SET `status` = ?, `endReason` = ?, `endedAt` = ?, `updatedAt` = ? WHERE `id` = ?")
            .execute(Tuple.of(status.name, endReason, endedAt, System.currentTimeMillis(), id))
            .coAwait()
            .rowCount() > 0

    override suspend fun uninstall(sqlClient: SqlClient) {
        sqlClient
            .query("DROP TABLE IF EXISTS `${prefix() + tableName}`")
            .execute()
            .coAwait()
    }
}
