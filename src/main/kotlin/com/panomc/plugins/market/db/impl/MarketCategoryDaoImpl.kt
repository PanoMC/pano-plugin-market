package com.panomc.plugins.market.db.impl

import com.panomc.platform.annotation.Dao
import com.panomc.plugins.market.db.dao.MarketCategoryDao
import com.panomc.plugins.market.db.model.MarketCategory
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
class MarketCategoryDaoImpl : MarketCategoryDao() {

    private val productTableName = "market_product"

    override suspend fun init(sqlClient: SqlClient) {
        sqlClient
            .query(
                """
                            CREATE TABLE IF NOT EXISTS `${getTablePrefix() + tableName}` (
                              `id` bigint NOT NULL AUTO_INCREMENT,
                              `name` VARCHAR(255) NOT NULL,
                              `description` MEDIUMTEXT,
                              `icon` VARCHAR(64) NOT NULL DEFAULT 'fa-folder',
                              `color` VARCHAR(16) NOT NULL DEFAULT '#0d6efd',
                              `status` VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
                              `parentId` bigint,
                              `position` int NOT NULL DEFAULT 0,
                              `imageFileName` VARCHAR(255),
                              `createdAt` BIGINT(20) NOT NULL,
                              `updatedAt` BIGINT(20) NOT NULL,
                              PRIMARY KEY (`id`),
                              INDEX (`parentId`, `position`)
                            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Market category table.';
                        """
            )
            .execute()
            .coAwait()
    }

    override suspend fun add(category: MarketCategory, sqlClient: SqlClient): Long {
        val query =
            "INSERT INTO `${getTablePrefix() + tableName}` (`name`, `description`, `icon`, `color`, `status`, `parentId`, `position`, `imageFileName`, `createdAt`, `updatedAt`) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    category.name,
                    category.description,
                    category.icon,
                    category.color,
                    category.status.name,
                    category.parentId,
                    category.position,
                    category.imageFileName,
                    category.createdAt,
                    category.updatedAt
                )
            )
            .coAwait()

        return rows.property(MySQLClient.LAST_INSERTED_ID)
    }

    override suspend fun update(category: MarketCategory, sqlClient: SqlClient) {
        val query =
            "UPDATE `${getTablePrefix() + tableName}` SET `name` = ?, `description` = ?, `icon` = ?, `color` = ?, `status` = ?, `parentId` = ?, `position` = ?, `imageFileName` = ?, `updatedAt` = ? WHERE `id` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    category.name,
                    category.description,
                    category.icon,
                    category.color,
                    category.status.name,
                    category.parentId,
                    category.position,
                    category.imageFileName,
                    category.updatedAt,
                    category.id
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

    override suspend fun getById(id: Long, sqlClient: SqlClient): MarketCategory? {
        val query = "SELECT ${fields.toTableQuery()} FROM `${getTablePrefix() + tableName}` WHERE `id` = ?"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(id))
            .coAwait()

        return rows.toEntities().getOrNull(0)
    }

    override suspend fun getByImageFileName(imageFileName: String, sqlClient: SqlClient): MarketCategory? {
        val query = "SELECT ${fields.toTableQuery()} FROM `${getTablePrefix() + tableName}` WHERE `imageFileName` = ?"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(imageFileName))
            .coAwait()

        return rows.toEntities().getOrNull(0)
    }

    override suspend fun getAll(search: String?, sqlClient: SqlClient): List<MarketCategory> {
        val query = StringBuilder("SELECT ${fields.toTableQuery()} FROM `${getTablePrefix() + tableName}` WHERE 1=1")
        val params = Tuple.tuple()

        if (!search.isNullOrBlank()) {
            query.append(" AND `name` LIKE ?")
            params.addString("%$search%")
        }

        query.append(" ORDER BY `position` ASC, `id` ASC")

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query.toString())
            .execute(params)
            .coAwait()

        return rows.toEntities()
    }

    override suspend fun getMaxPosition(parentId: Long?, sqlClient: SqlClient): Int {
        val query =
            "SELECT MAX(`position`) FROM `${getTablePrefix() + tableName}` WHERE `parentId` <=> ?"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(parentId))
            .coAwait()

        return rows.toList()[0].getInteger(0) ?: -1
    }

    override suspend fun updateParentAndPosition(id: Long, parentId: Long?, position: Int, sqlClient: SqlClient) {
        val query =
            "UPDATE `${getTablePrefix() + tableName}` SET `parentId` = ?, `position` = ?, `updatedAt` = ? WHERE `id` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(parentId, position, System.currentTimeMillis(), id))
            .coAwait()
    }

    override suspend fun reparentChildren(fromParentId: Long, toParentId: Long?, sqlClient: SqlClient) {
        val query =
            "UPDATE `${getTablePrefix() + tableName}` SET `parentId` = ? WHERE `parentId` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(toParentId, fromParentId))
            .coAwait()
    }

    override suspend fun clearProductsCategory(categoryId: Long, sqlClient: SqlClient) {
        val query =
            "UPDATE `${getTablePrefix() + productTableName}` SET `categoryId` = NULL WHERE `categoryId` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(categoryId))
            .coAwait()
    }

    override suspend fun getProductCountsByCategory(sqlClient: SqlClient): Map<Long, Long> {
        val query =
            "SELECT `categoryId`, COUNT(*) FROM `${getTablePrefix() + productTableName}` WHERE `categoryId` IS NOT NULL GROUP BY `categoryId`"

        val rows: RowSet<Row> = sqlClient
            .query(query)
            .execute()
            .coAwait()

        return rows.associate { it.getLong(0) to it.getLong(1) }
    }

    override suspend fun uninstall(sqlClient: SqlClient) {
        sqlClient
            .query("DROP TABLE IF EXISTS `${getTablePrefix() + tableName}`")
            .execute()
            .coAwait()
    }
}
