package com.panomc.plugins.market.db.impl

import com.panomc.platform.annotation.Dao
import com.panomc.plugins.market.db.MarketSchema
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.mysqlclient.MySQLClient
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope

import com.panomc.plugins.market.db.dao.MarketRedemptionDao
import com.panomc.plugins.market.db.dao.isDuplicateKey
import com.panomc.plugins.market.db.model.MarketRedemption
import com.panomc.plugins.market.db.model.RedemptionKind
import com.panomc.plugins.market.db.model.RedemptionState

@Dao
@Lazy
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class MarketRedemptionDaoImpl : MarketRedemptionDao() {

    override suspend fun init(sqlClient: SqlClient) {
        // Never throws (01 section 14.1 rule 3): a failed CREATE is logged and left to MarketSchema.ensure / SchemaVerifier.
        MarketSchema.installTable(sqlClient, MarketSchema.REDEMPTION, prefix())
    }

    override suspend fun add(redemption: MarketRedemption, sqlClient: SqlClient): Long? {
        val query =
            "INSERT INTO `${prefix() + tableName}` (`kind`, `refId`, `code`, `orderId`, `userId`, `buyerKey`, `email`, `recipientKey`, `amount`, `currency`, `state`, `createdAt`, `updatedAt`) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        val values = Tuple.tuple()
            .addValue(redemption.kind.name)
            .addValue(redemption.refId)
            .addValue(redemption.code)
            .addValue(redemption.orderId)
            .addValue(redemption.userId)
            .addValue(redemption.buyerKey)
            .addValue(redemption.email)
            .addValue(redemption.recipientKey)
            .addValue(redemption.amount)
            .addValue(redemption.currency)
            .addValue(redemption.state.name)
            .addValue(redemption.createdAt)
            .addValue(redemption.updatedAt)

        return try {
            sqlClient.preparedQuery(query).execute(values).coAwait().property(MySQLClient.LAST_INSERTED_ID)
        } catch (e: Exception) {
            if (e.isDuplicateKey()) null else throw e
        }
    }

    override suspend fun getById(id: Long, sqlClient: SqlClient): MarketRedemption? {
        val rows: RowSet<Row> = sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE `id` = ?")
            .execute(Tuple.of(id))
            .coAwait()

        return rows.toEntities().getOrNull(0)
    }

    override suspend fun get(kind: RedemptionKind, refId: Long, orderId: Long, sqlClient: SqlClient): MarketRedemption? {
        val rows: RowSet<Row> = sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE `kind` = ? AND `refId` = ? AND `orderId` = ?")
            .execute(Tuple.of(kind.name, refId, orderId))
            .coAwait()

        return rows.toEntities().getOrNull(0)
    }

    override suspend fun getByOrderId(orderId: Long, sqlClient: SqlClient): List<MarketRedemption> {
        val rows: RowSet<Row> = sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE `orderId` = ? ORDER BY `id` ASC")
            .execute(Tuple.of(orderId))
            .coAwait()

        return rows.toEntities()
    }

    override suspend fun transition(id: Long, from: RedemptionState, to: RedemptionState, sqlClient: SqlClient): Boolean =
        sqlClient
            .preparedQuery("UPDATE `${prefix() + tableName}` SET `state` = ?, `updatedAt` = ? WHERE `id` = ? AND `state` = ?")
            .execute(Tuple.of(to.name, System.currentTimeMillis(), id, from.name))
            .coAwait()
            .rowCount() > 0

    override suspend fun countActive(kind: RedemptionKind, refId: Long, sqlClient: SqlClient): Long =
        sqlClient
            .preparedQuery("SELECT COUNT(*) FROM `${prefix() + tableName}` WHERE `kind` = ? AND `refId` = ? AND `state` IN ('HELD', 'APPLIED')")
            .execute(Tuple.of(kind.name, refId))
            .coAwait()
            .first()
            .getLong(0)

    override suspend fun countForCustomer(
        kind: RedemptionKind,
        refId: Long,
        buyerKey: String,
        email: String?,
        recipientKeys: List<String>,
        sqlClient: SqlClient
    ): Long {
        val values = Tuple.tuple().addValue(kind.name).addValue(refId).addValue(buyerKey)
        val conditions = StringBuilder("`buyerKey` = ?")
        if (email != null) {
            conditions.append(" OR (`email` IS NOT NULL AND `email` = ?)")
            values.addValue(email)
        }
        if (recipientKeys.isNotEmpty()) {
            conditions.append(" OR `recipientKey` IN (").append(recipientKeys.joinToString(", ") { "?" }).append(")")
            recipientKeys.forEach { values.addValue(it) }
        }

        return sqlClient
            .preparedQuery(
                "SELECT COUNT(*) FROM `${prefix() + tableName}` WHERE `kind` = ? AND `refId` = ? AND `state` IN ('HELD', 'APPLIED') AND ($conditions)"
            )
            .execute(values)
            .coAwait()
            .first()
            .getLong(0)
    }

    override suspend fun deleteByOrderId(orderId: Long, sqlClient: SqlClient): Int =
        sqlClient
            .preparedQuery("DELETE FROM `${prefix() + tableName}` WHERE `orderId` = ?")
            .execute(Tuple.of(orderId))
            .coAwait()
            .rowCount()

    override suspend fun uninstall(sqlClient: SqlClient) {
        sqlClient
            .query("DROP TABLE IF EXISTS `${prefix() + tableName}`")
            .execute()
            .coAwait()
    }
}
