package com.panomc.plugins.market.db.impl

import com.panomc.platform.annotation.Dao
import com.panomc.plugins.market.db.dao.MarketProductDao
import com.panomc.plugins.market.db.model.MarketProduct
import com.panomc.plugins.market.util.MarketStatus
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
class MarketProductDaoImpl : MarketProductDao() {

    override suspend fun init(sqlClient: SqlClient) {
        sqlClient
            .query(
                """
                            CREATE TABLE IF NOT EXISTS `${getTablePrefix() + tableName}` (
                              `id` bigint NOT NULL AUTO_INCREMENT,
                              `slug` VARCHAR(255) NOT NULL,
                              `name` VARCHAR(255) NOT NULL,
                              `description` MEDIUMTEXT,
                              `categoryId` bigint,
                              `price` BIGINT NOT NULL DEFAULT 0,
                              `creditPrice` BIGINT NOT NULL DEFAULT 0,
                              `stock` INT,
                              `requiredProducts` MEDIUMTEXT,
                              `requireOnlyOne` TINYINT(1) NOT NULL DEFAULT 0,
                              `requiredPermission` VARCHAR(255),
                              `status` VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
                              `featured` TINYINT(1) NOT NULL DEFAULT 0,
                              `durationType` VARCHAR(16) NOT NULL DEFAULT 'LIFETIME',
                              `durationStart` BIGINT,
                              `durationExpiry` BIGINT,
                              `priority` INT NOT NULL DEFAULT 0,
                              `icon` VARCHAR(64) NOT NULL DEFAULT 'fa-box',
                              `imageFileName` VARCHAR(255),
                              `actions` MEDIUMTEXT,
                              `createdAt` BIGINT(20) NOT NULL,
                              `updatedAt` BIGINT(20) NOT NULL,
                              PRIMARY KEY (`id`),
                              UNIQUE KEY `unique_slug` (`slug`),
                              INDEX (`categoryId`),
                              INDEX (`status`)
                            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Market product table.';
                        """
            )
            .execute()
            .coAwait()
    }

    override suspend fun add(product: MarketProduct, sqlClient: SqlClient): Long {
        val query =
            "INSERT INTO `${getTablePrefix() + tableName}` (`slug`, `name`, `description`, `categoryId`, `price`, `creditPrice`, `stock`, `requiredProducts`, `requireOnlyOne`, `requiredPermission`, `status`, `featured`, `durationType`, `durationStart`, `durationExpiry`, `priority`, `icon`, `imageFileName`, `actions`, `createdAt`, `updatedAt`) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    product.slug,
                    product.name,
                    product.description,
                    product.categoryId,
                    product.price,
                    product.creditPrice,
                    product.stock,
                    JsonArray(product.requiredProducts).encode(),
                    product.requireOnlyOne,
                    product.requiredPermission,
                    product.status.name,
                    product.featured,
                    product.durationType.name,
                    product.durationStart,
                    product.durationExpiry,
                    product.priority,
                    product.icon,
                    product.imageFileName,
                    product.actions ?: "[]",
                    product.createdAt,
                    product.updatedAt
                )
            )
            .coAwait()

        return rows.property(MySQLClient.LAST_INSERTED_ID)
    }

    override suspend fun update(product: MarketProduct, sqlClient: SqlClient) {
        val query =
            "UPDATE `${getTablePrefix() + tableName}` SET `slug` = ?, `name` = ?, `description` = ?, `categoryId` = ?, `price` = ?, `creditPrice` = ?, `stock` = ?, `requiredProducts` = ?, `requireOnlyOne` = ?, `requiredPermission` = ?, `status` = ?, `featured` = ?, `durationType` = ?, `durationStart` = ?, `durationExpiry` = ?, `priority` = ?, `icon` = ?, `imageFileName` = ?, `actions` = ?, `updatedAt` = ? WHERE `id` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    product.slug,
                    product.name,
                    product.description,
                    product.categoryId,
                    product.price,
                    product.creditPrice,
                    product.stock,
                    JsonArray(product.requiredProducts).encode(),
                    product.requireOnlyOne,
                    product.requiredPermission,
                    product.status.name,
                    product.featured,
                    product.durationType.name,
                    product.durationStart,
                    product.durationExpiry,
                    product.priority,
                    product.icon,
                    product.imageFileName,
                    product.actions ?: "[]",
                    product.updatedAt,
                    product.id
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

    override suspend fun getById(id: Long, sqlClient: SqlClient): MarketProduct? {
        val query = "SELECT ${fields.toTableQuery()} FROM `${getTablePrefix() + tableName}` WHERE `id` = ?"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(id))
            .coAwait()

        return rows.toEntities().getOrNull(0)
    }

    override suspend fun getBySlug(slug: String, sqlClient: SqlClient): MarketProduct? {
        val query = "SELECT ${fields.toTableQuery()} FROM `${getTablePrefix() + tableName}` WHERE `slug` = ?"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(slug))
            .coAwait()

        return rows.toEntities().getOrNull(0)
    }

    override suspend fun getVisibleProducts(sqlClient: SqlClient): List<MarketProduct> {
        val query =
            "SELECT ${fields.toTableQuery()} FROM `${getTablePrefix() + tableName}` WHERE `status` = ? ORDER BY `priority` DESC, `name` ASC"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(MarketStatus.ACTIVE.name))
            .coAwait()

        return rows.toEntities()
    }

    override suspend fun getByImageFileName(imageFileName: String, sqlClient: SqlClient): MarketProduct? {
        val query = "SELECT ${fields.toTableQuery()} FROM `${getTablePrefix() + tableName}` WHERE `imageFileName` = ?"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(imageFileName))
            .coAwait()

        return rows.toEntities().getOrNull(0)
    }

    override suspend fun getAllPaged(page: Long, search: String?, status: String?, sqlClient: SqlClient): List<MarketProduct> {
        val offset = (page - 1) * 10
        val categoryTable = getTablePrefix() + "market_category"
        val query = StringBuilder(
            "SELECT p.*, c.`name` AS `categoryName` FROM `${getTablePrefix() + tableName}` p LEFT JOIN `$categoryTable` c ON p.`categoryId` = c.`id` WHERE 1=1"
        )
        val params = Tuple.tuple()

        if (!search.isNullOrBlank()) {
            query.append(" AND (p.`name` LIKE ? OR p.`slug` LIKE ?)")
            val searchParam = "%$search%"
            params.addString(searchParam)
            params.addString(searchParam)
        }

        if (!status.isNullOrBlank()) {
            query.append(" AND p.`status` = ?")
            params.addString(status)
        }

        query.append(" ORDER BY p.`priority` DESC, p.`id` DESC LIMIT 10 OFFSET ?")
        params.addLong(offset)

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query.toString())
            .execute(params)
            .coAwait()

        return rows.toEntities()
    }

    override suspend fun count(search: String?, status: String?, sqlClient: SqlClient): Long {
        val query = StringBuilder("SELECT COUNT(`id`) FROM `${getTablePrefix() + tableName}` WHERE 1=1")
        val params = Tuple.tuple()

        if (!search.isNullOrBlank()) {
            query.append(" AND (`name` LIKE ? OR `slug` LIKE ?)")
            val searchParam = "%$search%"
            params.addString(searchParam)
            params.addString(searchParam)
        }

        if (!status.isNullOrBlank()) {
            query.append(" AND `status` = ?")
            params.addString(status)
        }

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query.toString())
            .execute(params)
            .coAwait()

        if (rows.size() == 0) return 0L
        return rows.toList()[0].getLong(0)
    }

    override suspend fun getAllSimple(sqlClient: SqlClient): List<MarketProduct> {
        val query = "SELECT ${fields.toTableQuery()} FROM `${getTablePrefix() + tableName}` ORDER BY `name` ASC"

        val rows: RowSet<Row> = sqlClient
            .query(query)
            .execute()
            .coAwait()

        return rows.toEntities()
    }

    override suspend fun getByIds(ids: List<Long>, sqlClient: SqlClient): List<MarketProduct> {
        if (ids.isEmpty()) return emptyList()

        val placeholders = ids.joinToString(", ") { "?" }
        val query = "SELECT ${fields.toTableQuery()} FROM `${getTablePrefix() + tableName}` WHERE `id` IN ($placeholders)"

        val params = Tuple.tuple()
        ids.forEach { params.addLong(it) }

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(params)
            .coAwait()

        return rows.toEntities()
    }

    override suspend fun clearCategory(categoryId: Long, sqlClient: SqlClient) {
        val query = "UPDATE `${getTablePrefix() + tableName}` SET `categoryId` = NULL WHERE `categoryId` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(categoryId))
            .coAwait()
    }

    override suspend fun uninstall(sqlClient: SqlClient) {
        sqlClient
            .query("DROP TABLE IF EXISTS `${getTablePrefix() + tableName}`")
            .execute()
            .coAwait()
    }
}
