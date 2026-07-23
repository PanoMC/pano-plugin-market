package com.panomc.plugins.market.db.impl

import com.panomc.platform.annotation.Dao
import com.panomc.plugins.market.db.dao.MarketDiscountDao
import com.panomc.plugins.market.db.model.MarketDiscount
import io.vertx.core.json.JsonArray
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
class MarketDiscountDaoImpl : MarketDiscountDao() {
    override suspend fun init(sqlClient: SqlClient) {
        sqlClient.query(
            """
                CREATE TABLE IF NOT EXISTS `${getTablePrefix() + tableName}` (
                    `id` bigint NOT NULL AUTO_INCREMENT,
                    `name` VARCHAR(255) NOT NULL,
                    `value` BIGINT NOT NULL,
                    `unit` VARCHAR(8) NOT NULL DEFAULT 'PERCENT',
                    `minPaymentAmount` BIGINT,
                    `scope` VARCHAR(16) NOT NULL DEFAULT 'ALL',
                    `productIds` MEDIUMTEXT,
                    `categoryIds` MEDIUMTEXT,
                    `startDate` BIGINT,
                    `expiryDate` BIGINT,
                    `usageLimit` INT,
                    `usedCount` INT NOT NULL DEFAULT 0,
                    `status` VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
                    `createdAt` BIGINT(20) NOT NULL,
                    `updatedAt` BIGINT(20) NOT NULL,
                    PRIMARY KEY (`id`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Market automatic discounts table.';
            """
        ).execute().coAwait()
    }

    override suspend fun add(discount: MarketDiscount, sqlClient: SqlClient): Long {
        val query =
            "INSERT INTO `${getTablePrefix() + tableName}` (`name`, `value`, `unit`, `minPaymentAmount`, `scope`, `productIds`, `categoryIds`, `startDate`, `expiryDate`, `usageLimit`, `usedCount`, `status`, `createdAt`, `updatedAt`) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    discount.name,
                    discount.value,
                    discount.unit.name,
                    discount.minPaymentAmount,
                    discount.scope.name,
                    discount.productIds?.let { JsonArray(it).encode() },
                    discount.categoryIds?.let { JsonArray(it).encode() },
                    discount.startDate,
                    discount.expiryDate,
                    discount.usageLimit,
                    discount.usedCount,
                    discount.status.name,
                    discount.createdAt,
                    discount.updatedAt
                )
            )
            .coAwait()

        return rows.property(MySQLClient.LAST_INSERTED_ID)
    }

    override suspend fun update(discount: MarketDiscount, sqlClient: SqlClient) {
        val query =
            "UPDATE `${getTablePrefix() + tableName}` SET `name` = ?, `value` = ?, `unit` = ?, `minPaymentAmount` = ?, `scope` = ?, `productIds` = ?, `categoryIds` = ?, `startDate` = ?, `expiryDate` = ?, `usageLimit` = ?, `usedCount` = ?, `status` = ?, `updatedAt` = ? WHERE `id` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    discount.name,
                    discount.value,
                    discount.unit.name,
                    discount.minPaymentAmount,
                    discount.scope.name,
                    discount.productIds?.let { JsonArray(it).encode() },
                    discount.categoryIds?.let { JsonArray(it).encode() },
                    discount.startDate,
                    discount.expiryDate,
                    discount.usageLimit,
                    discount.usedCount,
                    discount.status.name,
                    discount.updatedAt,
                    discount.id
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

    override suspend fun getById(id: Long, sqlClient: SqlClient): MarketDiscount? {
        val rows: RowSet<Row> = sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM `${getTablePrefix() + tableName}` WHERE `id` = ?")
            .execute(Tuple.of(id))
            .coAwait()

        return rows.toEntities().getOrNull(0)
    }

    override suspend fun getAll(page: Long, search: String?, status: String?, sqlClient: SqlClient): List<MarketDiscount> {
        val offset = (page - 1) * 10
        val query = StringBuilder("SELECT ${fields.toTableQuery()} FROM `${getTablePrefix() + tableName}` WHERE 1=1")
        val params = Tuple.tuple()

        if (!status.isNullOrBlank()) {
            query.append(" AND `status` = ?")
            params.addString(status)
        }

        if (!search.isNullOrBlank()) {
            query.append(" AND `name` LIKE ?")
            params.addString("%$search%")
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
            query.append(" AND `name` LIKE ?")
            params.addString("%$search%")
        }

        val rows: RowSet<Row> = sqlClient.preparedQuery(query.toString()).execute(params).coAwait()

        if (rows.size() == 0) return 0L
        return rows.toList()[0].getLong(0)
    }

    override suspend fun uninstall(sqlClient: SqlClient) {
        sqlClient.query("DROP TABLE IF EXISTS `${getTablePrefix() + tableName}`").execute().coAwait()
    }
}
