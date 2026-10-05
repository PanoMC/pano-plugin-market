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

import com.panomc.plugins.market.db.dao.MarketCreatorPayoutDao
import com.panomc.plugins.market.db.dao.isDuplicateKey
import com.panomc.plugins.market.db.model.CreatorPayoutState
import com.panomc.plugins.market.db.model.MarketCreatorPayout

@Dao
@Lazy
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class MarketCreatorPayoutDaoImpl : MarketCreatorPayoutDao() {

    override suspend fun init(sqlClient: SqlClient) {
        // Never throws (01 section 14.1 rule 3): a failed CREATE is logged and left to MarketSchema.ensure / SchemaVerifier.
        MarketSchema.installTable(sqlClient, MarketSchema.CREATOR_PAYOUT, prefix())
    }

    override suspend fun add(payout: MarketCreatorPayout, sqlClient: SqlClient): Long? {
        val query =
            "INSERT INTO `${prefix() + tableName}` (`creatorCodeId`, `creatorUserId`, `amount`, `currency`, `method`, `state`, `creditTxId`, `actions`, `note`, `paidBy`, `paidAt`, `idempotencyKey`, `idempotencyHash`, `createdAt`, `updatedAt`) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        val values = Tuple.tuple()
            .addValue(payout.creatorCodeId)
            .addValue(payout.creatorUserId)
            .addValue(payout.amount)
            .addValue(payout.currency)
            .addValue(payout.method.name)
            .addValue(payout.state.name)
            .addValue(payout.creditTxId)
            .addValue(payout.actions)
            .addValue(payout.note)
            .addValue(payout.paidBy)
            .addValue(payout.paidAt)
            .addValue(payout.idempotencyKey)
            .addValue(payout.idempotencyHash)
            .addValue(payout.createdAt)
            .addValue(payout.updatedAt)

        return try {
            sqlClient.preparedQuery(query).execute(values).coAwait().property(MySQLClient.LAST_INSERTED_ID)
        } catch (e: Exception) {
            if (e.isDuplicateKey()) null else throw e
        }
    }

    private suspend fun select(where: String, values: Tuple, order: String, sqlClient: SqlClient): List<MarketCreatorPayout> {
        val rows: RowSet<Row> = sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE $where ORDER BY $order")
            .execute(values)
            .coAwait()

        return rows.toEntities()
    }

    override suspend fun getById(id: Long, sqlClient: SqlClient): MarketCreatorPayout? =
        select("`id` = ?", Tuple.of(id), "`id` ASC", sqlClient).getOrNull(0)

    override suspend fun getByIdempotencyKey(key: String, sqlClient: SqlClient): MarketCreatorPayout? =
        select("`idempotencyKey` = ?", Tuple.of(key), "`id` ASC", sqlClient).getOrNull(0)

    override suspend fun getByCodeId(creatorCodeId: Long, sqlClient: SqlClient): List<MarketCreatorPayout> =
        select("`creatorCodeId` = ?", Tuple.of(creatorCodeId), "`id` DESC", sqlClient)

    override suspend fun transition(
        id: Long,
        from: CreatorPayoutState,
        to: CreatorPayoutState,
        paidBy: Long?,
        paidAt: Long?,
        creditTxId: Long?,
        updatedAt: Long,
        sqlClient: SqlClient
    ): Boolean =
        sqlClient
            .preparedQuery(
                "UPDATE `${prefix() + tableName}` SET `state` = ?, `paidBy` = COALESCE(?, `paidBy`), `paidAt` = COALESCE(?, `paidAt`), `creditTxId` = COALESCE(?, `creditTxId`), `updatedAt` = ? WHERE `id` = ? AND `state` = ?"
            )
            .execute(Tuple.tuple().addValue(to.name).addValue(paidBy).addValue(paidAt).addValue(creditTxId).addValue(updatedAt).addValue(id).addValue(from.name))
            .coAwait()
            .rowCount() > 0

    override suspend fun uninstall(sqlClient: SqlClient) {
        sqlClient
            .query("DROP TABLE IF EXISTS `${prefix() + tableName}`")
            .execute()
            .coAwait()
    }
}
