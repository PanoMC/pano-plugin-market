package com.panomc.plugins.market.db.impl

import com.panomc.platform.annotation.Dao
import com.panomc.plugins.market.db.MarketSchema
import com.panomc.plugins.market.db.dao.MarketProductFieldDao
import com.panomc.plugins.market.db.dao.isDuplicateKey
import com.panomc.plugins.market.db.model.MarketProductField
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
class MarketProductFieldDaoImpl : MarketProductFieldDao() {

    override suspend fun init(sqlClient: SqlClient) {
        // Never throws (01 section 14.1 rule 3): a failed CREATE is logged and left to MarketSchema.ensure / SchemaVerifier.
        MarketSchema.installTable(sqlClient, MarketSchema.PRODUCT_FIELD, prefix())
    }

    /** The columns written by both statements, in the order of the SQL below. */
    private fun values(field: MarketProductField): Tuple = Tuple.tuple()
        .addValue(field.fieldKey)
        .addValue(field.label)
        .addValue(field.helpText)
        .addValue(field.type.name)
        .addValue(field.required)
        .addValue(field.options)
        .addValue(field.pattern)
        .addValue(field.minLength)
        .addValue(field.maxLength)
        .addValue(field.minValue)
        .addValue(field.maxValue)
        .addValue(field.placeholder)
        .addValue(field.defaultValue)
        .addValue(field.usableInCommands)
        .addValue(field.position)

    override suspend fun add(field: MarketProductField, sqlClient: SqlClient): Long? {
        val query =
            "INSERT INTO `${prefix() + tableName}` (`fieldKey`, `label`, `helpText`, `type`, `required`, `options`, `pattern`, `minLength`, `maxLength`, `minValue`, `maxValue`, `placeholder`, `defaultValue`, `usableInCommands`, `position`, `productId`, `createdAt`, `updatedAt`) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"

        return try {
            sqlClient
                .preparedQuery(query)
                .execute(values(field).addValue(field.productId).addValue(field.createdAt).addValue(field.updatedAt))
                .coAwait()
                .property(MySQLClient.LAST_INSERTED_ID)
        } catch (e: Exception) {
            if (e.isDuplicateKey()) null else throw e
        }
    }

    override suspend fun update(field: MarketProductField, sqlClient: SqlClient): Boolean {
        val query =
            "UPDATE `${prefix() + tableName}` SET `fieldKey` = ?, `label` = ?, `helpText` = ?, `type` = ?, `required` = ?, `options` = ?, `pattern` = ?, `minLength` = ?, `maxLength` = ?, `minValue` = ?, `maxValue` = ?, `placeholder` = ?, `defaultValue` = ?, `usableInCommands` = ?, `position` = ?, `updatedAt` = ? WHERE `id` = ?"

        return try {
            sqlClient
                .preparedQuery(query)
                .execute(values(field).addValue(field.updatedAt).addValue(field.id))
                .coAwait()
            true
        } catch (e: Exception) {
            if (e.isDuplicateKey()) false else throw e
        }
    }

    override suspend fun deleteById(id: Long, sqlClient: SqlClient) {
        sqlClient
            .preparedQuery("DELETE FROM `${prefix() + tableName}` WHERE `id` = ?")
            .execute(Tuple.of(id))
            .coAwait()
    }

    override suspend fun deleteByProductId(productId: Long, sqlClient: SqlClient): Int =
        sqlClient
            .preparedQuery("DELETE FROM `${prefix() + tableName}` WHERE `productId` = ?")
            .execute(Tuple.of(productId))
            .coAwait()
            .rowCount()

    override suspend fun getById(id: Long, sqlClient: SqlClient): MarketProductField? {
        val rows: RowSet<Row> = sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE `id` = ?")
            .execute(Tuple.of(id))
            .coAwait()

        return rows.toEntities().getOrNull(0)
    }

    override suspend fun getByProductIdAndKey(productId: Long, fieldKey: String, sqlClient: SqlClient): MarketProductField? {
        val rows: RowSet<Row> = sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE `productId` = ? AND `fieldKey` = ?")
            .execute(Tuple.of(productId, fieldKey))
            .coAwait()

        return rows.toEntities().getOrNull(0)
    }

    override suspend fun getByProductId(productId: Long, sqlClient: SqlClient): List<MarketProductField> {
        val rows: RowSet<Row> = sqlClient
            .preparedQuery(
                "SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE `productId` = ? ORDER BY `position` ASC, `id` ASC"
            )
            .execute(Tuple.of(productId))
            .coAwait()

        return rows.toEntities()
    }

    override suspend fun getByProductIds(productIds: List<Long>, sqlClient: SqlClient): List<MarketProductField> {
        if (productIds.isEmpty()) return emptyList()

        val placeholders = productIds.joinToString(", ") { "?" }
        val params = Tuple.tuple()
        productIds.forEach { params.addLong(it) }

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(
                "SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE `productId` IN ($placeholders) ORDER BY `productId` ASC, `position` ASC, `id` ASC"
            )
            .execute(params)
            .coAwait()

        return rows.toEntities()
    }

    override suspend fun uninstall(sqlClient: SqlClient) {
        sqlClient
            .query("DROP TABLE IF EXISTS `${prefix() + tableName}`")
            .execute()
            .coAwait()
    }
}
