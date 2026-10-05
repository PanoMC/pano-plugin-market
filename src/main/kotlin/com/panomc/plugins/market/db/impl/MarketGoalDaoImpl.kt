package com.panomc.plugins.market.db.impl

import com.panomc.platform.annotation.Dao
import com.panomc.plugins.market.db.MarketSchema
import com.panomc.plugins.market.db.dao.MarketGoalDao
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
class MarketGoalDaoImpl : MarketGoalDao() {

    override suspend fun init(sqlClient: SqlClient) {
        // Never throws (01 section 14.1 rule 3): a failed CREATE is logged and left to MarketSchema.ensure / SchemaVerifier.
        MarketSchema.installTable(sqlClient, MarketSchema.GOAL, prefix())
    }

    private suspend fun one(where: String, values: Tuple, sqlClient: SqlClient): MarketGoal? =
        sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE $where LIMIT 1")
            .execute(values)
            .coAwait()
            .toEntities()
            .getOrNull(0)

    private suspend fun many(where: String, order: String, values: Tuple, sqlClient: SqlClient): List<MarketGoal> =
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

    override suspend fun add(goal: MarketGoal, sqlClient: SqlClient): Long {
        val query =
            "INSERT INTO `${prefix() + tableName}` (`name`, `description`, `metric`, `productIds`, `target`, `progress`, `currency`, `period`, `periodStart`, `startsAt`, `endsAt`, `status`, `showOnStore`, `completedAt`, `position`, `createdAt`, `updatedAt`) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        val values = Tuple.tuple()
            .addValue(goal.name)
            .addValue(goal.description)
            .addValue(goal.metric.name)
            .addValue(goal.productIds)
            .addValue(goal.target)
            .addValue(goal.progress)
            .addValue(goal.currency)
            .addValue(goal.period.name)
            .addValue(goal.periodStart)
            .addValue(goal.startsAt)
            .addValue(goal.endsAt)
            .addValue(goal.status)
            .addValue(if (goal.showOnStore) 1 else 0)
            .addValue(goal.completedAt)
            .addValue(goal.position)
            .addValue(goal.createdAt)
            .addValue(goal.updatedAt)

        return sqlClient.preparedQuery(query).execute(values).coAwait().property(MySQLClient.LAST_INSERTED_ID)
    }

    override suspend fun getById(id: Long, sqlClient: SqlClient): MarketGoal? = one("`id` = ?", Tuple.of(id), sqlClient)

    override suspend fun getAll(sqlClient: SqlClient): List<MarketGoal> =
        many("1 = 1", "`position` ASC, `id` ASC", Tuple.tuple(), sqlClient)

    override suspend fun getActive(sqlClient: SqlClient): List<MarketGoal> =
        many("`status` = ?", "`position` ASC, `id` ASC", Tuple.of("ACTIVE"), sqlClient)

    override suspend fun update(goal: MarketGoal, sqlClient: SqlClient): Boolean =
        change(
            "`name` = ?, `description` = ?, `metric` = ?, `productIds` = ?, `target` = ?, `currency` = ?, `period` = ?, `startsAt` = ?, `endsAt` = ?, `status` = ?, `showOnStore` = ?, `position` = ?, `updatedAt` = ?",
            "`id` = ?",
            Tuple.tuple()
                .addValue(goal.name).addValue(goal.description).addValue(goal.metric.name).addValue(goal.productIds)
                .addValue(goal.target).addValue(goal.currency).addValue(goal.period.name).addValue(goal.startsAt)
                .addValue(goal.endsAt).addValue(goal.status).addValue(if (goal.showOnStore) 1 else 0).addValue(goal.position)
                .addValue(goal.updatedAt).addValue(goal.id),
            sqlClient
        ) > 0

    override suspend fun addProgress(id: Long, delta: Long, now: Long, sqlClient: SqlClient): Boolean =
        change("`progress` = GREATEST(`progress` + ?, 0), `updatedAt` = ?", "`id` = ?", Tuple.of(delta, now, id), sqlClient) > 0

    override suspend fun resetPeriod(id: Long, periodStart: Long, now: Long, sqlClient: SqlClient): Boolean =
        change("`progress` = 0, `periodStart` = ?, `completedAt` = NULL, `updatedAt` = ?", "`id` = ?", Tuple.of(periodStart, now, id), sqlClient) > 0

    override suspend fun markCompleted(id: Long, now: Long, sqlClient: SqlClient): Boolean =
        change("`completedAt` = ?, `updatedAt` = ?", "`id` = ? AND `completedAt` IS NULL", Tuple.of(now, now, id), sqlClient) > 0

    override suspend fun delete(id: Long, sqlClient: SqlClient): Boolean =
        sqlClient
            .preparedQuery("DELETE FROM `${prefix() + tableName}` WHERE `id` = ?")
            .execute(Tuple.of(id))
            .coAwait()
            .rowCount() > 0

    override suspend fun uninstall(sqlClient: SqlClient) {
        sqlClient
            .query("DROP TABLE IF EXISTS `${prefix() + tableName}`")
            .execute()
            .coAwait()
    }
}
