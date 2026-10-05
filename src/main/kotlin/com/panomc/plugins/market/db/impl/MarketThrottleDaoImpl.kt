package com.panomc.plugins.market.db.impl

import com.panomc.platform.annotation.Dao
import com.panomc.plugins.market.db.MarketSchema
import com.panomc.plugins.market.db.dao.MarketThrottleDao
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
class MarketThrottleDaoImpl : MarketThrottleDao() {

    override suspend fun init(sqlClient: SqlClient) {
        // Never throws (01 section 14.1 rule 3): a failed CREATE is logged and left to MarketSchema.ensure / SchemaVerifier.
        MarketSchema.installTable(sqlClient, MarketSchema.THROTTLE, prefix())
    }

    private suspend fun one(where: String, values: Tuple, sqlClient: SqlClient): MarketThrottle? =
        sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE $where LIMIT 1")
            .execute(values)
            .coAwait()
            .toEntities()
            .getOrNull(0)

    private suspend fun many(where: String, order: String, values: Tuple, sqlClient: SqlClient): List<MarketThrottle> =
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

    override suspend fun fail(scope: String, subject: String, threshold: Int, windowMs: Long, lockMs: Long, now: Long, sqlClient: SqlClient): Long? {
        val t = prefix() + tableName
        // 11 section 12.1: one statement; the assignments are evaluated left to right, so `lockedUntil` sees the new `count`.
        val query =
            "INSERT INTO `$t` (`scope`, `subject`, `count`, `windowStart`, `lockedUntil`, `createdAt`, `updatedAt`) " +
                "VALUES (?, ?, 1, ?, IF(? <= 1, ? + ?, NULL), ?, ?) " +
                "ON DUPLICATE KEY UPDATE " +
                "`count` = IF(`windowStart` + ? <= ?, 1, `count` + 1), " +
                "`windowStart` = IF(`windowStart` + ? <= ?, ?, `windowStart`), " +
                "`lockedUntil` = IF(`count` >= ?, ? + ?, `lockedUntil`), " +
                "`updatedAt` = ?"
        val values = Tuple.tuple()
            .addValue(scope).addValue(subject.take(SUBJECT_MAX)).addValue(now).addValue(threshold).addValue(now).addValue(lockMs).addValue(now).addValue(now)
            .addValue(windowMs).addValue(now)
            .addValue(windowMs).addValue(now).addValue(now)
            .addValue(threshold).addValue(now).addValue(lockMs)
            .addValue(now)
        sqlClient.preparedQuery(query).execute(values).coAwait()
        return lockedUntil(scope, subject, now, sqlClient)
    }

    override suspend fun get(scope: String, subject: String, sqlClient: SqlClient): MarketThrottle? =
        one("`scope` = ? AND `subject` = ?", Tuple.of(scope, subject.take(SUBJECT_MAX)), sqlClient)

    override suspend fun lockedUntil(scope: String, subject: String, now: Long, sqlClient: SqlClient): Long? =
        get(scope, subject, sqlClient)?.lockedUntil?.takeIf { it > now }

    override suspend fun lockedUntilAny(scope: String, subjects: List<String>, now: Long, sqlClient: SqlClient): Long? {
        if (subjects.isEmpty()) return null
        val marks = subjects.joinToString(", ") { "?" }
        val values = Tuple.tuple().addValue(scope)
        subjects.forEach { values.addValue(it.take(SUBJECT_MAX)) }
        return many("`scope` = ? AND `subject` IN ($marks)", "`id` ASC", values, sqlClient)
            .mapNotNull { it.lockedUntil }
            .filter { it > now }
            .maxOrNull()
    }

    override suspend fun reset(scope: String, subject: String, sqlClient: SqlClient): Boolean =
        sqlClient
            .preparedQuery("DELETE FROM `${prefix() + tableName}` WHERE `scope` = ? AND `subject` = ?")
            .execute(Tuple.of(scope, subject.take(SUBJECT_MAX)))
            .coAwait()
            .rowCount() > 0

    override suspend fun purge(windowBefore: Long, now: Long, sqlClient: SqlClient): Int =
        sqlClient
            .preparedQuery("DELETE FROM `${prefix() + tableName}` WHERE `windowStart` < ? AND (`lockedUntil` IS NULL OR `lockedUntil` <= ?)")
            .execute(Tuple.of(windowBefore, now))
            .coAwait()
            .rowCount()

    private companion object {
        const val SUBJECT_MAX = 191
    }

    override suspend fun uninstall(sqlClient: SqlClient) {
        sqlClient
            .query("DROP TABLE IF EXISTS `${prefix() + tableName}`")
            .execute()
            .coAwait()
    }
}
