package com.panomc.plugins.market.db.impl

import com.panomc.platform.annotation.Dao
import com.panomc.plugins.market.db.MarketSchema
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
        // Never throws (01 section 14.1 rule 3): a failed CREATE is logged and left to MarketSchema.ensure / SchemaVerifier.
        MarketSchema.installTable(sqlClient, MarketSchema.PRODUCT, prefix())
    }

    override suspend fun add(product: MarketProduct, sqlClient: SqlClient): Long {
        val query =
            "INSERT INTO `${prefix() + tableName}` (`slug`, `name`, `description`, `categoryId`, `price`, `creditPrice`, `stock`, `requiredProducts`, `requireOnlyOne`, `requiredPermission`, `status`, `featured`, `durationType`, `durationStart`, `durationExpiry`, `priority`, `icon`, `imageFileName`, `actions`, `createdAt`, `updatedAt`) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"

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

    override suspend fun setStock(id: Long, stock: Int?, sqlClient: SqlClient) {
        sqlClient
            .preparedQuery("UPDATE `${prefix() + tableName}` SET `stock` = ?, `updatedAt` = ? WHERE `id` = ?")
            .execute(Tuple.of(stock, System.currentTimeMillis(), id))
            .coAwait()
    }

    // The counters (stock) are not written by the generic update (00 section 8.3, bug 2 of the backend map):
    // they change only through guarded atomic statements, never from a stale read.
    override suspend fun update(product: MarketProduct, sqlClient: SqlClient) {
        val query =
            "UPDATE `${prefix() + tableName}` SET `slug` = ?, `name` = ?, `description` = ?, `categoryId` = ?, `price` = ?, `creditPrice` = ?, `requiredProducts` = ?, `requireOnlyOne` = ?, `requiredPermission` = ?, `status` = ?, `featured` = ?, `durationType` = ?, `durationStart` = ?, `durationExpiry` = ?, `priority` = ?, `icon` = ?, `imageFileName` = ?, `actions` = ?, `updatedAt` = ? WHERE `id` = ?"

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
        val query = "DELETE FROM `${prefix() + tableName}` WHERE `id` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(id))
            .coAwait()
    }

    override suspend fun getById(id: Long, sqlClient: SqlClient): MarketProduct? {
        val query = "SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE `id` = ?"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(id))
            .coAwait()

        return rows.toEntities().getOrNull(0)
    }

    override suspend fun getBySlug(slug: String, sqlClient: SqlClient): MarketProduct? {
        val query = "SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE `slug` = ?"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(slug))
            .coAwait()

        return rows.toEntities().getOrNull(0)
    }

    override suspend fun getVisibleProducts(sqlClient: SqlClient): List<MarketProduct> {
        val query =
            "SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE `status` = ? ORDER BY `priority` DESC, `name` ASC"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(MarketStatus.ACTIVE.name))
            .coAwait()

        return rows.toEntities()
    }

    override suspend fun getByImageFileName(imageFileName: String, sqlClient: SqlClient): MarketProduct? {
        val query = "SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE `imageFileName` = ?"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(imageFileName))
            .coAwait()

        return rows.toEntities().getOrNull(0)
    }

    override suspend fun getAllPaged(page: Long, search: String?, status: String?, sqlClient: SqlClient): List<MarketProduct> {
        val offset = (page - 1) * 10
        val categoryTable = prefix() + "market_category"
        val query = StringBuilder(
            "SELECT p.*, c.`name` AS `categoryName` FROM `${prefix() + tableName}` p LEFT JOIN `$categoryTable` c ON p.`categoryId` = c.`id` WHERE 1=1"
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
        val query = StringBuilder("SELECT COUNT(`id`) FROM `${prefix() + tableName}` WHERE 1=1")
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
        val query = "SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` ORDER BY `name` ASC"

        val rows: RowSet<Row> = sqlClient
            .query(query)
            .execute()
            .coAwait()

        return rows.toEntities()
    }

    override suspend fun getByIds(ids: List<Long>, sqlClient: SqlClient): List<MarketProduct> {
        if (ids.isEmpty()) return emptyList()

        val placeholders = ids.joinToString(", ") { "?" }
        val query = "SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE `id` IN ($placeholders)"

        val params = Tuple.tuple()
        ids.forEach { params.addLong(it) }

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(params)
            .coAwait()

        return rows.toEntities()
    }

    override suspend fun clearCategory(categoryId: Long, sqlClient: SqlClient) {
        val query = "UPDATE `${prefix() + tableName}` SET `categoryId` = NULL WHERE `categoryId` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(categoryId))
            .coAwait()
    }

    override suspend fun uninstall(sqlClient: SqlClient) {
        sqlClient
            .query("DROP TABLE IF EXISTS `${prefix() + tableName}`")
            .execute()
            .coAwait()
    }
}
