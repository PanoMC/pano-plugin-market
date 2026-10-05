package com.panomc.plugins.market.db.impl

import com.panomc.platform.annotation.Dao
import com.panomc.plugins.market.db.MarketSchema
import com.panomc.plugins.market.db.dao.MarketProductDao
import com.panomc.plugins.market.db.model.MarketProduct
import com.panomc.plugins.market.db.model.ProductKind
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
            "INSERT INTO `${prefix() + tableName}` (`slug`, `name`, `description`, `categoryId`, `price`, `creditPrice`, `stock`, `requiredProducts`, `requireOnlyOne`, `requiredPermission`, `status`, `featured`, `durationType`, `durationStart`, `durationExpiry`, `priority`, `icon`, `imageFileName`, `actions`, `kind`, `shortDescription`, `compareAtPrice`, `vatPercent`, `physical`, `sku`, `weightGrams`, `lengthMm`, `widthMm`, `heightMm`, `hsCode`, `originCountry`, `billingMode`, `periodUnit`, `periodCount`, `subscriptionMaxCycles`, `limitPerPlayer`, `maxQuantityPerOrder`, `cooldownSeconds`, `tierRank`, `creditAmount`, `allowGift`, `serverChoices`, `hasVariants`, `variantOptions`, `metaTitle`, `metaDescription`, `createdAt`, `updatedAt`) VALUES (${placeholders(48)})"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.tuple()
                    .addValue(product.slug)
                    .addValue(product.name)
                    .addValue(product.description)
                    .addValue(product.categoryId)
                    .addValue(product.price)
                    .addValue(product.creditPrice)
                    .addValue(product.stock)
                    .addValue(JsonArray(product.requiredProducts).encode())
                    .addValue(product.requireOnlyOne)
                    .addValue(product.requiredPermission)
                    .addValue(product.status.name)
                    .addValue(product.featured)
                    .addValue(product.durationType.name)
                    .addValue(product.durationStart)
                    .addValue(product.durationExpiry)
                    .addValue(product.priority)
                    .addValue(product.icon)
                    .addValue(product.imageFileName)
                    .addValue(product.actions ?: "[]")
                    .also { addCatalogueValues(it, product) }
                    .addValue(product.createdAt)
                    .addValue(product.updatedAt)
            )
            .coAwait()

        return rows.property(MySQLClient.LAST_INSERTED_ID)
    }

    /** The 29 version 3 columns minus the counters (`soldCount`, `deletedAt`): 27 values, in the order of the statements. */
    private fun addCatalogueValues(tuple: Tuple, product: MarketProduct) {
        tuple
            .addValue(product.kind.name)
            .addValue(product.shortDescription)
            .addValue(product.compareAtPrice)
            .addValue(product.vatPercent)
            .addValue(product.physical)
            .addValue(product.sku)
            .addValue(product.weightGrams)
            .addValue(product.lengthMm)
            .addValue(product.widthMm)
            .addValue(product.heightMm)
            .addValue(product.hsCode)
            .addValue(product.originCountry)
            .addValue(product.billingMode.name)
            .addValue(product.periodUnit?.name)
            .addValue(product.periodCount)
            .addValue(product.subscriptionMaxCycles)
            .addValue(product.limitPerPlayer)
            .addValue(product.maxQuantityPerOrder)
            .addValue(product.cooldownSeconds)
            .addValue(product.tierRank)
            .addValue(product.creditAmount)
            .addValue(product.allowGift)
            .addValue(product.serverChoices)
            .addValue(product.hasVariants)
            .addValue(product.variantOptions)
            .addValue(product.metaTitle)
            .addValue(product.metaDescription)
    }

    private fun placeholders(count: Int) = List(count) { "?" }.joinToString(", ")

    override suspend fun setStock(id: Long, stock: Int?, sqlClient: SqlClient) {
        sqlClient
            .preparedQuery("UPDATE `${prefix() + tableName}` SET `stock` = ?, `updatedAt` = ? WHERE `id` = ?")
            .execute(Tuple.of(stock, System.currentTimeMillis(), id))
            .coAwait()
    }

    // The counters (stock, soldCount) and the soft-delete marker are not written by the generic update (00 section 8.3):
    // they change only through guarded atomic statements, never from a stale read.
    override suspend fun update(product: MarketProduct, sqlClient: SqlClient): Boolean {
        val query =
            "UPDATE `${prefix() + tableName}` SET `slug` = ?, `name` = ?, `description` = ?, `categoryId` = ?, `price` = ?, `creditPrice` = ?, `requiredProducts` = ?, `requireOnlyOne` = ?, `requiredPermission` = ?, `status` = ?, `featured` = ?, `durationType` = ?, `durationStart` = ?, `durationExpiry` = ?, `priority` = ?, `icon` = ?, `imageFileName` = ?, `actions` = ?, `kind` = ?, `shortDescription` = ?, `compareAtPrice` = ?, `vatPercent` = ?, `physical` = ?, `sku` = ?, `weightGrams` = ?, `lengthMm` = ?, `widthMm` = ?, `heightMm` = ?, `hsCode` = ?, `originCountry` = ?, `billingMode` = ?, `periodUnit` = ?, `periodCount` = ?, `subscriptionMaxCycles` = ?, `limitPerPlayer` = ?, `maxQuantityPerOrder` = ?, `cooldownSeconds` = ?, `tierRank` = ?, `creditAmount` = ?, `allowGift` = ?, `serverChoices` = ?, `hasVariants` = ?, `variantOptions` = ?, `metaTitle` = ?, `metaDescription` = ?, `updatedAt` = ? WHERE `id` = ? AND `deletedAt` IS NULL"

        val rows = sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.tuple()
                    .addValue(product.slug)
                    .addValue(product.name)
                    .addValue(product.description)
                    .addValue(product.categoryId)
                    .addValue(product.price)
                    .addValue(product.creditPrice)
                    .addValue(JsonArray(product.requiredProducts).encode())
                    .addValue(product.requireOnlyOne)
                    .addValue(product.requiredPermission)
                    .addValue(product.status.name)
                    .addValue(product.featured)
                    .addValue(product.durationType.name)
                    .addValue(product.durationStart)
                    .addValue(product.durationExpiry)
                    .addValue(product.priority)
                    .addValue(product.icon)
                    .addValue(product.imageFileName)
                    .addValue(product.actions ?: "[]")
                    .also { addCatalogueValues(it, product) }
                    .addValue(product.updatedAt)
                    .addValue(product.id)
            )
            .coAwait()

        return rows.rowCount() > 0
    }

    override suspend fun adjustStock(id: Long, delta: Int, sqlClient: SqlClient): Boolean {
        val rows = sqlClient
            .preparedQuery(
                "UPDATE `${prefix() + tableName}` SET `stock` = `stock` + ?, `updatedAt` = ? WHERE `id` = ? AND `stock` IS NOT NULL AND `stock` + ? >= 0 AND `stock` + ? <= ?"
            )
            .execute(
                Tuple.tuple()
                    .addLong(delta.toLong())
                    .addLong(System.currentTimeMillis())
                    .addLong(id)
                    .addLong(delta.toLong())
                    .addLong(delta.toLong())
                    .addLong(MAX_STOCK.toLong())
            )
            .coAwait()

        return rows.rowCount() > 0
    }

    override suspend fun markDeleted(id: Long, archivedSlug: String, deletedAt: Long, sqlClient: SqlClient): Boolean {
        val rows = sqlClient
            .preparedQuery(
                "UPDATE `${prefix() + tableName}` SET `deletedAt` = ?, `status` = ?, `slug` = ?, `updatedAt` = ? WHERE `id` = ? AND `deletedAt` IS NULL"
            )
            .execute(Tuple.of(deletedAt, MarketStatus.ARCHIVED.name, archivedSlug, deletedAt, id))
            .coAwait()

        return rows.rowCount() > 0
    }

    override suspend fun isReferenced(id: Long, sqlClient: SqlClient): Boolean = anyRow(
        sqlClient,
        id,
        listOf(
            "market_order_item" to "productId",
            "market_entitlement" to "productId",
            "market_subscription" to "productId",
            "market_cart_item" to "productId",
            "market_bundle_item" to "productId"
        )
    )

    override suspend fun isVariantReferenced(variantId: Long, sqlClient: SqlClient): Boolean = anyRow(
        sqlClient,
        variantId,
        listOf(
            "market_order_item" to "variantId",
            "market_entitlement" to "variantId",
            "market_subscription" to "variantId",
            "market_cart_item" to "variantId",
            "market_bundle_item" to "variantId"
        )
    )

    private suspend fun anyRow(sqlClient: SqlClient, value: Long, targets: List<Pair<String, String>>): Boolean {
        val query = targets.joinToString(" OR ", prefix = "SELECT (", postfix = ") AS `referenced`") { (table, column) ->
            "EXISTS (SELECT 1 FROM `${prefix() + table}` WHERE `$column` = ?)"
        }
        val params = Tuple.tuple()
        targets.forEach { _ -> params.addLong(value) }

        val rows = sqlClient.preparedQuery(query).execute(params).coAwait()

        return rows.size() > 0 && (rows.first().getValue("referenced") as Number).toInt() != 0
    }

    override suspend fun removeFromCarts(productId: Long, sqlClient: SqlClient): Int =
        sqlClient
            .preparedQuery("DELETE FROM `${prefix()}market_cart_item` WHERE `productId` = ?")
            .execute(Tuple.of(productId))
            .coAwait()
            .rowCount()

    override suspend fun removeVariantFromCarts(variantId: Long, sqlClient: SqlClient): Int =
        sqlClient
            .preparedQuery("DELETE FROM `${prefix()}market_cart_item` WHERE `variantId` = ?")
            .execute(Tuple.of(variantId))
            .coAwait()
            .rowCount()

    override suspend fun isCategoryTiered(categoryId: Long, sqlClient: SqlClient): Boolean? {
        val rows = sqlClient
            .preparedQuery("SELECT `tiered` FROM `${prefix()}market_category` WHERE `id` = ?")
            .execute(Tuple.of(categoryId))
            .coAwait()

        if (rows.size() == 0) return null

        return (rows.first().getValue("tiered") as Number).toInt() != 0
    }

    override suspend fun hasSellableShippingMethod(sqlClient: SqlClient): Boolean {
        val query =
            "SELECT 1 FROM `${prefix()}market_shipping_method` m " +
                "JOIN `${prefix()}market_shipping_rate` r ON r.`methodId` = m.`id` " +
                "JOIN `${prefix()}market_shipping_zone` z ON z.`id` = r.`zoneId` " +
                "WHERE m.`status` = 'ACTIVE' AND m.`deletedAt` IS NULL AND z.`status` = 'ACTIVE' LIMIT 1"

        return sqlClient.query(query).execute().coAwait().size() > 0
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

    override suspend fun getByIdForUpdate(id: Long, sqlClient: SqlClient): MarketProduct? {
        val query = "SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE `id` = ? FOR UPDATE"

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
            "SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE `status` = ? AND `deletedAt` IS NULL ORDER BY `priority` DESC, `name` ASC"

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

    override suspend fun getAllPaged(
        page: Long,
        pageSize: Int,
        search: String?,
        status: String?,
        kind: ProductKind?,
        categoryId: Long?,
        sqlClient: SqlClient
    ): List<MarketProduct> {
        val offset = (page - 1) * pageSize
        val categoryTable = prefix() + "market_category"
        val query = StringBuilder(
            "SELECT p.*, c.`name` AS `categoryName` FROM `${prefix() + tableName}` p LEFT JOIN `$categoryTable` c ON p.`categoryId` = c.`id` WHERE p.`deletedAt` IS NULL"
        )
        val params = Tuple.tuple()

        appendFilters(query, params, "p.", search, status, kind, categoryId)

        query.append(" ORDER BY p.`priority` DESC, p.`id` DESC LIMIT ? OFFSET ?")
        params.addLong(pageSize.toLong())
        params.addLong(offset)

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query.toString())
            .execute(params)
            .coAwait()

        return rows.toEntities()
    }

    override suspend fun count(
        search: String?,
        status: String?,
        kind: ProductKind?,
        categoryId: Long?,
        sqlClient: SqlClient
    ): Long {
        val query = StringBuilder("SELECT COUNT(`id`) FROM `${prefix() + tableName}` WHERE `deletedAt` IS NULL")
        val params = Tuple.tuple()

        appendFilters(query, params, "", search, status, kind, categoryId)

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query.toString())
            .execute(params)
            .coAwait()

        if (rows.size() == 0) return 0L
        return rows.toList()[0].getLong(0)
    }

    private fun appendFilters(
        query: StringBuilder,
        params: Tuple,
        alias: String,
        search: String?,
        status: String?,
        kind: ProductKind?,
        categoryId: Long?
    ) {
        if (!search.isNullOrBlank()) {
            query.append(" AND (${alias}`name` LIKE ? OR ${alias}`slug` LIKE ?)")
            val searchParam = "%$search%"
            params.addString(searchParam)
            params.addString(searchParam)
        }

        if (!status.isNullOrBlank()) {
            query.append(" AND ${alias}`status` = ?")
            params.addString(status)
        }

        if (kind != null) {
            query.append(" AND ${alias}`kind` = ?")
            params.addString(kind.name)
        }

        if (categoryId != null) {
            query.append(" AND ${alias}`categoryId` = ?")
            params.addLong(categoryId)
        }
    }

    override suspend fun getAllSimple(sqlClient: SqlClient): List<MarketProduct> {
        val query = "SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE `deletedAt` IS NULL ORDER BY `name` ASC"

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
