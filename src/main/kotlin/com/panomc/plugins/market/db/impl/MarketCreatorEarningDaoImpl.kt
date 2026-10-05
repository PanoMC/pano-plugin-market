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

import com.panomc.plugins.market.db.dao.MarketCreatorEarningDao
import com.panomc.plugins.market.db.dao.isDuplicateKey
import com.panomc.plugins.market.db.model.CreatorEarningState
import com.panomc.plugins.market.db.model.MarketCreatorEarning

@Dao
@Lazy
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class MarketCreatorEarningDaoImpl : MarketCreatorEarningDao() {

    override suspend fun init(sqlClient: SqlClient) {
        // Never throws (01 section 14.1 rule 3): a failed CREATE is logged and left to MarketSchema.ensure / SchemaVerifier.
        MarketSchema.installTable(sqlClient, MarketSchema.CREATOR_EARNING, prefix())
    }

    override suspend fun add(earning: MarketCreatorEarning, sqlClient: SqlClient): Long? {
        val query =
            "INSERT INTO `${prefix() + tableName}` (`creatorCodeId`, `creatorUserId`, `orderId`, `baseAmount`, `commissionPercent`, `amount`, `currency`, `state`, `availableAt`, `reversedAmount`, `payoutId`, `createdAt`, `updatedAt`) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        val values = Tuple.tuple()
            .addValue(earning.creatorCodeId)
            .addValue(earning.creatorUserId)
            .addValue(earning.orderId)
            .addValue(earning.baseAmount)
            .addValue(earning.commissionPercent)
            .addValue(earning.amount)
            .addValue(earning.currency)
            .addValue(earning.state.name)
            .addValue(earning.availableAt)
            .addValue(earning.reversedAmount)
            .addValue(earning.payoutId)
            .addValue(earning.createdAt)
            .addValue(earning.updatedAt)

        return try {
            sqlClient.preparedQuery(query).execute(values).coAwait().property(MySQLClient.LAST_INSERTED_ID)
        } catch (e: Exception) {
            if (e.isDuplicateKey()) null else throw e
        }
    }

    private suspend fun select(where: String, values: Tuple, order: String, sqlClient: SqlClient): List<MarketCreatorEarning> {
        val rows: RowSet<Row> = sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE $where ORDER BY $order")
            .execute(values)
            .coAwait()

        return rows.toEntities()
    }

    override suspend fun getById(id: Long, sqlClient: SqlClient): MarketCreatorEarning? =
        select("`id` = ?", Tuple.of(id), "`id` ASC", sqlClient).getOrNull(0)

    override suspend fun get(orderId: Long, creatorCodeId: Long, sqlClient: SqlClient): MarketCreatorEarning? =
        select("`orderId` = ? AND `creatorCodeId` = ?", Tuple.of(orderId, creatorCodeId), "`id` ASC", sqlClient).getOrNull(0)

    override suspend fun getByCodeId(creatorCodeId: Long, sqlClient: SqlClient): List<MarketCreatorEarning> =
        select("`creatorCodeId` = ?", Tuple.of(creatorCodeId), "`id` ASC", sqlClient)

    override suspend fun getByOrderId(orderId: Long, sqlClient: SqlClient): List<MarketCreatorEarning> =
        select("`orderId` = ?", Tuple.of(orderId), "`id` ASC", sqlClient)

    override suspend fun transition(id: Long, from: CreatorEarningState, to: CreatorEarningState, sqlClient: SqlClient): Boolean =
        sqlClient
            .preparedQuery("UPDATE `${prefix() + tableName}` SET `state` = ?, `updatedAt` = ? WHERE `id` = ? AND `state` = ?")
            .execute(Tuple.of(to.name, System.currentTimeMillis(), id, from.name))
            .coAwait()
            .rowCount() > 0

    override suspend fun releaseDue(now: Long, sqlClient: SqlClient): Int =
        sqlClient
            .preparedQuery("UPDATE `${prefix() + tableName}` SET `state` = 'AVAILABLE', `updatedAt` = ? WHERE `state` = 'PENDING' AND `availableAt` IS NOT NULL AND `availableAt` <= ?")
            .execute(Tuple.of(now, now))
            .coAwait()
            .rowCount()

    override suspend fun addReversed(id: Long, delta: Long, sqlClient: SqlClient): Boolean =
        sqlClient
            .preparedQuery("UPDATE `${prefix() + tableName}` SET `reversedAmount` = `reversedAmount` + ?, `updatedAt` = ? WHERE `id` = ? AND `reversedAmount` + ? <= `amount`")
            .execute(Tuple.of(delta, System.currentTimeMillis(), id, delta))
            .coAwait()
            .rowCount() > 0

    override suspend fun attachPayout(id: Long, payoutId: Long, sqlClient: SqlClient): Boolean =
        sqlClient
            .preparedQuery("UPDATE `${prefix() + tableName}` SET `payoutId` = ?, `state` = 'PAID', `updatedAt` = ? WHERE `id` = ? AND `state` = 'AVAILABLE'")
            .execute(Tuple.of(payoutId, System.currentTimeMillis(), id))
            .coAwait()
            .rowCount() > 0

    override suspend fun sumNet(creatorCodeId: Long, states: List<CreatorEarningState>, sqlClient: SqlClient): Long {
        if (states.isEmpty()) return 0
        val values = Tuple.tuple().addValue(creatorCodeId)
        states.forEach { values.addValue(it.name) }
        return sqlClient
            .preparedQuery(
                "SELECT COALESCE(SUM(`amount` - `reversedAmount`), 0) FROM `${prefix() + tableName}` WHERE `creatorCodeId` = ? AND `state` IN (${states.joinToString(", ") { "?" }})"
            )
            .execute(values)
            .coAwait()
            .first()
            .getLong(0)
    }

    override suspend fun uninstall(sqlClient: SqlClient) {
        sqlClient
            .query("DROP TABLE IF EXISTS `${prefix() + tableName}`")
            .execute()
            .coAwait()
    }
}
