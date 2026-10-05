package com.panomc.plugins.market.db.impl

import com.panomc.platform.annotation.Dao
import com.panomc.plugins.market.db.MarketSchema
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
        // Never throws (01 section 14.1 rule 3): a failed CREATE is logged and left to MarketSchema.ensure / SchemaVerifier.
        MarketSchema.installTable(sqlClient, MarketSchema.CATEGORY, prefix())
    }

    override suspend fun add(category: MarketCategory, sqlClient: SqlClient): Long {
        val query =
            "INSERT INTO `${prefix() + tableName}` (`name`, `description`, `icon`, `color`, `status`, `parentId`, `position`, `imageFileName`, `createdAt`, `updatedAt`) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"

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
            "UPDATE `${prefix() + tableName}` SET `name` = ?, `description` = ?, `icon` = ?, `color` = ?, `status` = ?, `parentId` = ?, `position` = ?, `imageFileName` = ?, `updatedAt` = ? WHERE `id` = ?"

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
        val query = "DELETE FROM `${prefix() + tableName}` WHERE `id` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(id))
            .coAwait()
    }

    override suspend fun getById(id: Long, sqlClient: SqlClient): MarketCategory? {
        val query = "SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE `id` = ?"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(id))
            .coAwait()

        return rows.toEntities().getOrNull(0)
    }

    override suspend fun getByImageFileName(imageFileName: String, sqlClient: SqlClient): MarketCategory? {
        val query = "SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE `imageFileName` = ?"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(imageFileName))
            .coAwait()

        return rows.toEntities().getOrNull(0)
    }

    override suspend fun getAll(search: String?, sqlClient: SqlClient): List<MarketCategory> {
        val query = StringBuilder("SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE 1=1")
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

    override suspend fun getNamesByIds(ids: List<Long>, sqlClient: SqlClient): Map<Long, String> {
        if (ids.isEmpty()) return emptyMap()

        val placeholders = ids.joinToString(", ") { "?" }
        val query = "SELECT `id`, `name` FROM `${prefix() + tableName}` WHERE `id` IN ($placeholders)"

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

    override suspend fun getMaxPosition(parentId: Long?, sqlClient: SqlClient): Int {
        val query =
            "SELECT MAX(`position`) FROM `${prefix() + tableName}` WHERE `parentId` <=> ?"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(parentId))
            .coAwait()

        return rows.toList()[0].getInteger(0) ?: -1
    }

    override suspend fun updateParentAndPosition(id: Long, parentId: Long?, position: Int, sqlClient: SqlClient) {
        val query =
            "UPDATE `${prefix() + tableName}` SET `parentId` = ?, `position` = ?, `updatedAt` = ? WHERE `id` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(parentId, position, System.currentTimeMillis(), id))
            .coAwait()
    }

    override suspend fun reparentChildren(fromParentId: Long, toParentId: Long?, sqlClient: SqlClient) {
        val query =
            "UPDATE `${prefix() + tableName}` SET `parentId` = ? WHERE `parentId` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(toParentId, fromParentId))
            .coAwait()
    }

    override suspend fun clearProductsCategory(categoryId: Long, sqlClient: SqlClient) {
        val query =
            "UPDATE `${prefix() + productTableName}` SET `categoryId` = NULL WHERE `categoryId` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(categoryId))
            .coAwait()
    }

    override suspend fun getProductCountsByCategory(sqlClient: SqlClient): Map<Long, Long> {
        val query =
            "SELECT `categoryId`, COUNT(*) FROM `${prefix() + productTableName}` WHERE `categoryId` IS NOT NULL GROUP BY `categoryId`"

        val rows: RowSet<Row> = sqlClient
            .query(query)
            .execute()
            .coAwait()

        return rows.associate { it.getLong(0) to it.getLong(1) }
    }

    override suspend fun uninstall(sqlClient: SqlClient) {
        sqlClient
            .query("DROP TABLE IF EXISTS `${prefix() + tableName}`")
            .execute()
            .coAwait()
    }
}
