package com.panomc.plugins.market.db.impl

import com.panomc.platform.annotation.Dao
import com.panomc.plugins.market.db.dao.MarketComparisonDao
import com.panomc.plugins.market.db.model.MarketComparison
import com.panomc.plugins.market.util.MarketStatus
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
class MarketComparisonDaoImpl : MarketComparisonDao() {

    override suspend fun init(sqlClient: SqlClient) {
        sqlClient
            .query(
                """
                            CREATE TABLE IF NOT EXISTS `${getTablePrefix() + tableName}` (
                              `id` bigint NOT NULL AUTO_INCREMENT,
                              `name` VARCHAR(255) NOT NULL,
                              `status` VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
                              `priority` INT NOT NULL DEFAULT 0,
                              `productIds` MEDIUMTEXT,
                              `features` MEDIUMTEXT,
                              `cellValues` MEDIUMTEXT,
                              `createdAt` BIGINT(20) NOT NULL,
                              `updatedAt` BIGINT(20) NOT NULL,
                              PRIMARY KEY (`id`)
                            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Market comparison table.';
                        """
            )
            .execute()
            .coAwait()
    }

    override suspend fun add(comparison: MarketComparison, sqlClient: SqlClient): Long {
        val query =
            "INSERT INTO `${getTablePrefix() + tableName}` (`name`, `status`, `priority`, `productIds`, `features`, `cellValues`, `createdAt`, `updatedAt`) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    comparison.name,
                    comparison.status.name,
                    comparison.priority,
                    comparison.productIds,
                    comparison.features,
                    comparison.cellValues,
                    comparison.createdAt,
                    comparison.updatedAt
                )
            )
            .coAwait()

        return rows.property(MySQLClient.LAST_INSERTED_ID)
    }

    override suspend fun update(comparison: MarketComparison, sqlClient: SqlClient) {
        val query =
            "UPDATE `${getTablePrefix() + tableName}` SET `name` = ?, `status` = ?, `priority` = ?, `productIds` = ?, `features` = ?, `cellValues` = ?, `updatedAt` = ? WHERE `id` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    comparison.name,
                    comparison.status.name,
                    comparison.priority,
                    comparison.productIds,
                    comparison.features,
                    comparison.cellValues,
                    comparison.updatedAt,
                    comparison.id
                )
            )
            .coAwait()
    }

    override suspend fun deleteById(id: Long, sqlClient: SqlClient) {
        val query = "DELETE FROM `${getTablePrefix() + tableName}` WHERE `id` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(id))
            .coAwait()
    }

    override suspend fun getById(id: Long, sqlClient: SqlClient): MarketComparison? {
        val query = "SELECT ${fields.toTableQuery()} FROM `${getTablePrefix() + tableName}` WHERE `id` = ?"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(id))
            .coAwait()

        return rows.toEntities().getOrNull(0)
    }

    override suspend fun getAllPaged(page: Long, status: MarketStatus?, search: String?, sqlClient: SqlClient): List<MarketComparison> {
        val offset = (page - 1) * 10
        val query = StringBuilder("SELECT ${fields.toTableQuery()} FROM `${getTablePrefix() + tableName}` WHERE 1=1")
        val params = Tuple.tuple()

        if (status != null) {
            query.append(" AND `status` = ?")
            params.addString(status.name)
        }

        if (!search.isNullOrBlank()) {
            query.append(" AND `name` LIKE ?")
            params.addString("%$search%")
        }

        query.append(" ORDER BY `priority` DESC, `id` DESC LIMIT 10 OFFSET ?")
        params.addLong(offset)

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query.toString())
            .execute(params)
            .coAwait()

        return rows.toEntities()
    }

    override suspend fun count(status: MarketStatus?, search: String?, sqlClient: SqlClient): Long {
        val query = StringBuilder("SELECT COUNT(`id`) FROM `${getTablePrefix() + tableName}` WHERE 1=1")
        val params = Tuple.tuple()

        if (status != null) {
            query.append(" AND `status` = ?")
            params.addString(status.name)
        }

        if (!search.isNullOrBlank()) {
            query.append(" AND `name` LIKE ?")
            params.addString("%$search%")
        }

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query.toString())
            .execute(params)
            .coAwait()

        if (rows.size() == 0) return 0L
        return rows.toList()[0].getLong(0)
    }

    override suspend fun getProductNamesByIds(ids: List<Long>, sqlClient: SqlClient): Map<Long, String> {
        if (ids.isEmpty()) return emptyMap()

        val placeholders = ids.joinToString(", ") { "?" }
        val query = "SELECT `id`, `name` FROM `${getTablePrefix()}market_product` WHERE `id` IN ($placeholders)"

        val params = Tuple.tuple()
        ids.forEach { params.addLong(it) }

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(params)
            .coAwait()

        val names = mutableMapOf<Long, String>()
        rows.forEach { names[it.getLong("id")] = it.getString("name") }
        return names
    }

    override suspend fun uninstall(sqlClient: SqlClient) {
        sqlClient
            .query("DROP TABLE IF EXISTS `${getTablePrefix() + tableName}`")
            .execute()
            .coAwait()
    }
}
