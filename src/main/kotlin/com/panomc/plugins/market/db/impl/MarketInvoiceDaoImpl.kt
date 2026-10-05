package com.panomc.plugins.market.db.impl

import com.panomc.platform.annotation.Dao
import com.panomc.plugins.market.db.MarketSchema
import com.panomc.plugins.market.db.dao.MarketInvoiceDao
import com.panomc.plugins.market.db.dao.isDuplicateKey
import com.panomc.plugins.market.db.model.*
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
class MarketInvoiceDaoImpl : MarketInvoiceDao() {

    override suspend fun init(sqlClient: SqlClient) {
        // Never throws (01 section 14.1 rule 3): a failed CREATE is logged and left to MarketSchema.ensure / SchemaVerifier.
        MarketSchema.installTable(sqlClient, MarketSchema.INVOICE, prefix())
    }

    override suspend fun add(invoice: MarketInvoice, sqlClient: SqlClient): Long? {
        val query =
            "INSERT INTO `${prefix() + tableName}` (`orderId`, `type`, `refundId`, `series`, `sequence`, `number`, `locale`, `currency`, `total`, `vatTotal`, `snapshot`, `fileName`, `issuedAt`, `createdAt`, `updatedAt`) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        val values = Tuple.tuple()
            .addValue(invoice.orderId)
            .addValue(invoice.type.name)
            .addValue(invoice.refundId)
            .addValue(invoice.series)
            .addValue(invoice.sequence)
            .addValue(invoice.number)
            .addValue(invoice.locale)
            .addValue(invoice.currency)
            .addValue(invoice.total)
            .addValue(invoice.vatTotal)
            .addValue(invoice.snapshot)
            .addValue(invoice.fileName)
            .addValue(invoice.issuedAt)
            .addValue(invoice.createdAt)
            .addValue(invoice.updatedAt)

        return try {
            sqlClient.preparedQuery(query).execute(values).coAwait().property(MySQLClient.LAST_INSERTED_ID)
        } catch (e: Exception) {
            if (e.isDuplicateKey()) null else throw e
        }
    }

    override suspend fun getById(id: Long, sqlClient: SqlClient): MarketInvoice? =
        sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE `id` = ?")
            .execute(Tuple.of(id))
            .coAwait()
            .toEntities()
            .getOrNull(0)

    override suspend fun getByOrderId(orderId: Long, sqlClient: SqlClient): List<MarketInvoice> =
        sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE `orderId` = ? ORDER BY `id` ASC")
            .execute(Tuple.of(orderId))
            .coAwait()
            .toEntities()

    override suspend fun getByOrderTypeRefund(orderId: Long, type: InvoiceType, refundId: Long, sqlClient: SqlClient): MarketInvoice? =
        sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE `orderId` = ? AND `type` = ? AND `refundId` = ?")
            .execute(Tuple.of(orderId, type.name, refundId))
            .coAwait()
            .toEntities()
            .getOrNull(0)

    override suspend fun uninstall(sqlClient: SqlClient) {
        sqlClient
            .query("DROP TABLE IF EXISTS `${prefix() + tableName}`")
            .execute()
            .coAwait()
    }
}
