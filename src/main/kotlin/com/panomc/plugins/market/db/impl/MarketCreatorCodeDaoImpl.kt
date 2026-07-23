package com.panomc.plugins.market.db.impl

import com.panomc.platform.annotation.Dao
import com.panomc.plugins.market.db.dao.MarketCreatorCodeDao
import com.panomc.plugins.market.db.model.MarketCreatorCode
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
class MarketCreatorCodeDaoImpl : MarketCreatorCodeDao() {
    override suspend fun init(sqlClient: SqlClient) {
        sqlClient.query(
            """
                CREATE TABLE IF NOT EXISTS `${getTablePrefix() + tableName}` (
                    `id` bigint NOT NULL AUTO_INCREMENT,
                    `creator` VARCHAR(64) NOT NULL,
                    `code` VARCHAR(64) NOT NULL,
                    `discount` BIGINT NOT NULL,
                    `unit` VARCHAR(8) NOT NULL DEFAULT 'PERCENT',
                    `commissionPercent` BIGINT NOT NULL DEFAULT 0,
                    `startDate` BIGINT,
                    `expiryDate` BIGINT,
                    `redeemLimit` INT,
                    `usedCount` INT NOT NULL DEFAULT 0,
                    `earnings` BIGINT NOT NULL DEFAULT 0,
                    `status` VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
                    `createdAt` BIGINT(20) NOT NULL,
                    `updatedAt` BIGINT(20) NOT NULL,
                    PRIMARY KEY (`id`),
                    UNIQUE KEY `unique_code` (`code`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Market creator codes table.';
            """
        ).execute().coAwait()
    }

    override suspend fun add(creatorCode: MarketCreatorCode, sqlClient: SqlClient): Long {
        val query =
            "INSERT INTO `${getTablePrefix() + tableName}` (`creator`, `code`, `discount`, `unit`, `commissionPercent`, `startDate`, `expiryDate`, `redeemLimit`, `usedCount`, `earnings`, `status`, `createdAt`, `updatedAt`) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    creatorCode.creator,
                    creatorCode.code,
                    creatorCode.discount,
                    creatorCode.unit.name,
                    creatorCode.commissionPercent,
                    creatorCode.startDate,
                    creatorCode.expiryDate,
                    creatorCode.redeemLimit,
                    creatorCode.usedCount,
                    creatorCode.earnings,
                    creatorCode.status.name,
                    creatorCode.createdAt,
                    creatorCode.updatedAt
                )
            )
            .coAwait()

        return rows.property(MySQLClient.LAST_INSERTED_ID)
    }

    override suspend fun update(creatorCode: MarketCreatorCode, sqlClient: SqlClient) {
        val query =
            "UPDATE `${getTablePrefix() + tableName}` SET `creator` = ?, `code` = ?, `discount` = ?, `unit` = ?, `commissionPercent` = ?, `startDate` = ?, `expiryDate` = ?, `redeemLimit` = ?, `usedCount` = ?, `earnings` = ?, `status` = ?, `updatedAt` = ? WHERE `id` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    creatorCode.creator,
                    creatorCode.code,
                    creatorCode.discount,
                    creatorCode.unit.name,
                    creatorCode.commissionPercent,
                    creatorCode.startDate,
                    creatorCode.expiryDate,
                    creatorCode.redeemLimit,
                    creatorCode.usedCount,
                    creatorCode.earnings,
                    creatorCode.status.name,
                    creatorCode.updatedAt,
                    creatorCode.id
                )
            )
            .coAwait()
    }

    override suspend fun deleteById(id: Long, sqlClient: SqlClient) {
        sqlClient
            .preparedQuery("DELETE FROM `${getTablePrefix() + tableName}` WHERE `id` = ?")
            .execute(Tuple.of(id))
            .coAwait()
    }

    override suspend fun getById(id: Long, sqlClient: SqlClient): MarketCreatorCode? {
        val rows: RowSet<Row> = sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM `${getTablePrefix() + tableName}` WHERE `id` = ?")
            .execute(Tuple.of(id))
            .coAwait()

        return rows.toEntities().getOrNull(0)
    }

    override suspend fun getByCode(code: String, sqlClient: SqlClient): MarketCreatorCode? {
        val rows: RowSet<Row> = sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM `${getTablePrefix() + tableName}` WHERE `code` = ?")
            .execute(Tuple.of(code))
            .coAwait()

        return rows.toEntities().getOrNull(0)
    }

    override suspend fun getAll(page: Long, search: String?, status: String?, sqlClient: SqlClient): List<MarketCreatorCode> {
        val offset = (page - 1) * 10
        val query = StringBuilder("SELECT ${fields.toTableQuery()} FROM `${getTablePrefix() + tableName}` WHERE 1=1")
        val params = Tuple.tuple()

        if (!status.isNullOrBlank()) {
            query.append(" AND `status` = ?")
            params.addString(status)
        }

        if (!search.isNullOrBlank()) {
            query.append(" AND (`creator` LIKE ? OR `code` LIKE ?)")
            val searchParam = "%$search%"
            params.addString(searchParam)
            params.addString(searchParam)
        }

        query.append(" ORDER BY `id` DESC LIMIT 10 OFFSET ?")
        params.addLong(offset)

        val rows: RowSet<Row> = sqlClient.preparedQuery(query.toString()).execute(params).coAwait()
        return rows.toEntities()
    }

    override suspend fun count(search: String?, status: String?, sqlClient: SqlClient): Long {
        val query = StringBuilder("SELECT COUNT(`id`) FROM `${getTablePrefix() + tableName}` WHERE 1=1")
        val params = Tuple.tuple()

        if (!status.isNullOrBlank()) {
            query.append(" AND `status` = ?")
            params.addString(status)
        }

        if (!search.isNullOrBlank()) {
            query.append(" AND (`creator` LIKE ? OR `code` LIKE ?)")
            val searchParam = "%$search%"
            params.addString(searchParam)
            params.addString(searchParam)
        }

        val rows: RowSet<Row> = sqlClient.preparedQuery(query.toString()).execute(params).coAwait()

        if (rows.size() == 0) return 0L
        return rows.toList()[0].getLong(0)
    }

    override suspend fun uninstall(sqlClient: SqlClient) {
        sqlClient.query("DROP TABLE IF EXISTS `${getTablePrefix() + tableName}`").execute().coAwait()
    }
}
