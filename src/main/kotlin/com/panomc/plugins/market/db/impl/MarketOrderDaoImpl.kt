package com.panomc.plugins.market.db.impl

import com.panomc.platform.annotation.Dao
import com.panomc.plugins.market.db.MarketSchema
import com.panomc.plugins.market.db.dao.MarketOrderDao
import com.panomc.plugins.market.db.dao.OpenOrderColumn
import com.panomc.plugins.market.db.dao.ProductOrderUsage
import com.panomc.plugins.market.db.dao.isDuplicateKey
import com.panomc.plugins.market.db.model.MarketOrder
import com.panomc.plugins.market.util.OrderStatus
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
class MarketOrderDaoImpl : MarketOrderDao() {

    private val itemTableName get() = prefix() + "market_order_item"

    override suspend fun init(sqlClient: SqlClient) {
        // Never throws (01 section 14.1 rule 3): a failed CREATE is logged and left to MarketSchema.ensure / SchemaVerifier.
        MarketSchema.installTable(sqlClient, MarketSchema.ORDER, prefix())
    }

    override suspend fun add(order: MarketOrder, sqlClient: SqlClient): Long = insert(order, sqlClient)

    override suspend fun tryAdd(order: MarketOrder, sqlClient: SqlClient): Long? =
        try {
            insert(order, sqlClient)
        } catch (e: Exception) {
            if (e.isDuplicateKey()) null else throw e
        }

    private suspend fun insert(order: MarketOrder, sqlClient: SqlClient): Long {
        val columns = columnValues(order)
        val query =
            "INSERT INTO `${prefix() + tableName}` (${columns.joinToString(", ") { "`${it.first}`" }}) VALUES (${columns.joinToString(", ") { "?" }})"

        val values = Tuple.tuple()
        columns.forEach { values.addValue(it.second) }

        val rows: RowSet<Row> = sqlClient.preparedQuery(query).execute(values).coAwait()

        return rows.property(MySQLClient.LAST_INSERTED_ID)
    }

    /** Column name to bound value, in the order of the table (enums by name). */
    private fun columnValues(o: MarketOrder): List<Pair<String, Any?>> = listOf(
        "userId" to o.userId,
        "playerUsername" to o.playerUsername,
        "totalPrice" to o.totalPrice,
        "currency" to o.currency,
        "paymentMethodId" to o.paymentMethodId,
        "paymentLabel" to o.paymentLabel,
        "status" to o.status.name,
        "createdAt" to o.createdAt,
        "updatedAt" to o.updatedAt,
        "exchangeRate" to o.exchangeRate,
        "publicId" to o.publicId,
        "accessToken" to o.accessToken,
        "source" to o.source.name,
        "buyerKey" to o.buyerKey,
        "idempotencyKey" to o.idempotencyKey,
        "idempotencyHash" to o.idempotencyHash,
        "email" to o.email,
        "locale" to o.locale,
        "clientIp" to o.clientIp,
        "userAgent" to o.userAgent,
        "recipientUsername" to o.recipientUsername,
        "recipientUserId" to o.recipientUserId,
        "recipientKey" to o.recipientKey,
        "isGift" to o.isGift,
        "giftMessage" to o.giftMessage,
        "hideFromBroadcast" to o.hideFromBroadcast,
        "reservationState" to o.reservationState.name,
        "expiresAt" to o.expiresAt,
        "baseCurrency" to o.baseCurrency,
        "fxRate" to o.fxRate,
        "displayCurrency" to o.displayCurrency,
        "displayRate" to o.displayRate,
        "pricingMode" to o.pricingMode.name,
        "pricesIncludeVat" to o.pricesIncludeVat,
        "subtotal" to o.subtotal,
        "discountTotal" to o.discountTotal,
        "couponDiscount" to o.couponDiscount,
        "creatorDiscount" to o.creatorDiscount,
        "upgradeDiscount" to o.upgradeDiscount,
        "shippingTotal" to o.shippingTotal,
        "shippingVatPercent" to o.shippingVatPercent,
        "shippingVatAmount" to o.shippingVatAmount,
        "paymentFee" to o.paymentFee,
        "paymentFeeVatPercent" to o.paymentFeeVatPercent,
        "paymentFeeVatAmount" to o.paymentFeeVatAmount,
        "vatTotal" to o.vatTotal,
        "creditAmount" to o.creditAmount,
        "creditValue" to o.creditValue,
        "gatewayAmount" to o.gatewayAmount,
        "paidAmount" to o.paidAmount,
        "refundedTotal" to o.refundedTotal,
        "refundedGatewayAmount" to o.refundedGatewayAmount,
        "refundedCreditAmount" to o.refundedCreditAmount,
        "couponId" to o.couponId,
        "creatorCodeId" to o.creatorCodeId,
        "giftId" to o.giftId,
        "couponCode" to o.couponCode,
        "creatorCode" to o.creatorCode,
        "paymentId" to o.paymentId,
        "paidAt" to o.paidAt,
        "testMode" to o.testMode,
        "statusBeforeDispute" to o.statusBeforeDispute?.name,
        "disputeStatus" to o.disputeStatus.name,
        "reviewReason" to o.reviewReason,
        "fulfillmentStatus" to o.fulfillmentStatus.name,
        "fulfillmentBy" to o.fulfillmentBy.name,
        "requiresShipping" to o.requiresShipping,
        "shippingStatus" to o.shippingStatus.name,
        "shippingAddress" to o.shippingAddress,
        "shippingMethodId" to o.shippingMethodId,
        "shippingMethodName" to o.shippingMethodName,
        "shippingQuote" to o.shippingQuote,
        "shippingWeightGrams" to o.shippingWeightGrams,
        "billingInfo" to o.billingInfo,
        "legalTextId" to o.legalTextId,
        "legalAcceptedAt" to o.legalAcceptedAt,
        "subscriptionId" to o.subscriptionId,
        "invoiceId" to o.invoiceId,
        "note" to o.note,
        "createdBy" to o.createdBy,
    )

    override suspend fun getByPublicId(publicId: String, sqlClient: SqlClient): MarketOrder? {
        val rows: RowSet<Row> = sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE `publicId` = ?")
            .execute(Tuple.of(publicId))
            .coAwait()

        return rows.toEntities().getOrNull(0)
    }

    override suspend fun getByBuyerAndIdempotencyKey(buyerKey: String, idempotencyKey: String, sqlClient: SqlClient): MarketOrder? {
        val rows: RowSet<Row> = sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE `buyerKey` = ? AND `idempotencyKey` = ?")
            .execute(Tuple.of(buyerKey, idempotencyKey))
            .coAwait()

        return rows.toEntities().getOrNull(0)
    }

    override suspend fun getAllPaged(page: Long, search: String?, status: OrderStatus?, sqlClient: SqlClient): List<MarketOrder> {
        val offset = (page - 1) * 10
        val query = StringBuilder("SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` o WHERE 1=1")
        val params = Tuple.tuple()

        appendFilters(query, params, search, status)

        query.append(" ORDER BY `createdAt` DESC LIMIT 10 OFFSET ?")
        params.addLong(offset)

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query.toString())
            .execute(params)
            .coAwait()

        return rows.toEntities()
    }

    override suspend fun count(search: String?, status: OrderStatus?, sqlClient: SqlClient): Long {
        val query = StringBuilder("SELECT COUNT(`id`) FROM `${prefix() + tableName}` o WHERE 1=1")
        val params = Tuple.tuple()

        appendFilters(query, params, search, status)

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query.toString())
            .execute(params)
            .coAwait()

        if (rows.size() == 0) return 0L
        return rows.toList()[0].getLong(0)
    }

    // Search matches playerUsername / order id / paymentLabel and any order item productName (EXISTS subquery).
    private fun appendFilters(query: StringBuilder, params: Tuple, search: String?, status: OrderStatus?) {
        if (status != null) {
            query.append(" AND `status` = ?")
            params.addString(status.name)
        }

        if (!search.isNullOrBlank()) {
            val like = "%$search%"
            query.append(
                " AND (`playerUsername` LIKE ? OR `paymentLabel` LIKE ? OR CAST(`id` AS CHAR) LIKE ?" +
                        " OR EXISTS (SELECT 1 FROM `$itemTableName` i WHERE i.`orderId` = o.`id` AND i.`productName` LIKE ?))"
            )
            params.addString(like)
            params.addString(like)
            params.addString(like)
            params.addString(like)
        }
    }

    override suspend fun getById(id: Long, sqlClient: SqlClient): MarketOrder? {
        val query = "SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE `id` = ?"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(id))
            .coAwait()

        return rows.toEntities().getOrNull(0)
    }

    override suspend fun updateStatus(id: Long, status: OrderStatus, sqlClient: SqlClient) {
        val query = "UPDATE `${prefix() + tableName}` SET `status` = ?, `updatedAt` = ? WHERE `id` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(status.name, System.currentTimeMillis(), id))
            .coAwait()
    }

    override suspend fun updateExchangeRate(id: Long, exchangeRate: Double, sqlClient: SqlClient) {
        val query = "UPDATE `${prefix() + tableName}` SET `exchangeRate` = ?, `updatedAt` = ? WHERE `id` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(exchangeRate, System.currentTimeMillis(), id))
            .coAwait()
    }

    override suspend fun anonymizeByUserId(userId: Long, sqlClient: SqlClient) {
        val query = "UPDATE `${prefix() + tableName}` SET `userId` = NULL WHERE `userId` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(userId))
            .coAwait()
    }

    override suspend fun usageByProduct(recipientKeys: Collection<String>, productIds: Collection<Long>, sqlClient: SqlClient): Map<Long, ProductOrderUsage> {
        if (recipientKeys.isEmpty() || productIds.isEmpty()) return emptyMap()

        val keys = recipientKeys.toList()
        val ids = productIds.toList()
        val query =
            "SELECT i.`productId` AS productId, COALESCE(SUM(i.`quantity` - i.`refundedQuantity`), 0) AS used, MAX(o.`createdAt`) AS lastAt" +
                " FROM `${prefix() + tableName}` o INNER JOIN `$itemTableName` i ON i.`orderId` = o.`id`" +
                " WHERE o.`recipientKey` IN (${keys.joinToString(", ") { "?" }}) AND i.`productId` IN (${ids.joinToString(", ") { "?" }})" +
                " AND o.`reservationState` IN ('HELD', 'COMMITTED') AND NOT (o.`reservationState` = 'HELD' AND o.`buyerKey` <> o.`recipientKey`)" +
                " GROUP BY i.`productId`"

        val values = Tuple.tuple()
        keys.forEach { values.addValue(it) }
        ids.forEach { values.addValue(it) }

        val rows: RowSet<Row> = sqlClient.preparedQuery(query).execute(values).coAwait()

        return rows.associate {
            val productId = it.getLong("productId")

            productId to ProductOrderUsage(productId, it.getLong("used"), it.getLong("lastAt"))
        }
    }

    override suspend fun openHeldExpiries(column: OpenOrderColumn, value: String, sqlClient: SqlClient): List<Long?> {
        val field = if (column == OpenOrderColumn.EMAIL) "LOWER(`email`)" else "`${column.column}`"
        val query =
            "SELECT `expiresAt` FROM `${prefix() + tableName}` WHERE $field = ? AND `status` = 'PENDING' AND `reservationState` = 'HELD' AND `source` = 'STOREFRONT'" +
                " ORDER BY `expiresAt` IS NULL, `expiresAt` ASC"
        val bound = if (column == OpenOrderColumn.EMAIL) value.lowercase() else value

        return sqlClient.preparedQuery(query).execute(Tuple.of(bound)).coAwait().map { it.getLong("expiresAt") }
    }

    override suspend fun openHeldIpv6(sqlClient: SqlClient): List<Pair<String, Long?>> =
        sqlClient
            .preparedQuery(
                "SELECT `clientIp`, `expiresAt` FROM `${prefix() + tableName}` WHERE `clientIp` LIKE '%:%' AND `status` = 'PENDING' AND `reservationState` = 'HELD' AND `source` = 'STOREFRONT'"
            )
            .execute()
            .coAwait()
            .map { it.getString("clientIp") to it.getLong("expiresAt") }

    // Per-order conversion factor: frozen rate if set, otherwise the currency-based fallback
    // (statsCurrency -> 1.0, salesCurrency -> the current view rate, otherwise 1.0). Bind order:
    // statsCurrency, salesCurrency, exchangeRate.
    private val conversionFactor =
        "COALESCE(`exchangeRate`, CASE WHEN `currency` = ? THEN 1.0 WHEN `currency` = ? THEN ? ELSE 1.0 END)"

    override suspend fun countAndRevenueBetween(from: Long, to: Long, statsCurrency: String, salesCurrency: String, exchangeRate: Double, sqlClient: SqlClient): Pair<Long, Double> {
        val query =
            "SELECT COUNT(`id`), COALESCE(SUM(`totalPrice` * $conversionFactor), 0) AS revenue FROM `${prefix() + tableName}` WHERE `status` = ? AND `createdAt` >= ? AND `createdAt` < ?"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(statsCurrency, salesCurrency, exchangeRate, OrderStatus.COMPLETED.name, from, to))
            .coAwait()

        val row = rows.toList()[0]
        return row.getLong(0) to (row.getDouble("revenue") / 100.0)
    }

    override suspend fun revenueByDay(from: Long, to: Long, statsCurrency: String, salesCurrency: String, exchangeRate: Double, sqlClient: SqlClient): Map<String, Double> =
        revenueGrouped("%Y-%m-%d", from, to, statsCurrency, salesCurrency, exchangeRate, sqlClient)

    override suspend fun revenueByWeek(from: Long, to: Long, statsCurrency: String, salesCurrency: String, exchangeRate: Double, sqlClient: SqlClient): Map<String, Double> =
        revenueGrouped("%x%v", from, to, statsCurrency, salesCurrency, exchangeRate, sqlClient)

    override suspend fun revenueByMonth(from: Long, to: Long, statsCurrency: String, salesCurrency: String, exchangeRate: Double, sqlClient: SqlClient): Map<String, Double> =
        revenueGrouped("%Y-%m", from, to, statsCurrency, salesCurrency, exchangeRate, sqlClient)

    private suspend fun revenueGrouped(format: String, from: Long, to: Long, statsCurrency: String, salesCurrency: String, exchangeRate: Double, sqlClient: SqlClient): Map<String, Double> {
        val query =
            "SELECT DATE_FORMAT(FROM_UNIXTIME(`createdAt` / 1000), '$format') AS bucket, COALESCE(SUM(`totalPrice` * $conversionFactor), 0) AS revenue" +
                    " FROM `${prefix() + tableName}` WHERE `status` = ? AND `createdAt` >= ? AND `createdAt` < ?" +
                    " GROUP BY bucket ORDER BY bucket ASC"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(statsCurrency, salesCurrency, exchangeRate, OrderStatus.COMPLETED.name, from, to))
            .coAwait()

        val result = LinkedHashMap<String, Double>()
        rows.forEach { row -> result[row.getString("bucket")] = row.getDouble("revenue") / 100.0 }
        return result
    }

    override suspend fun paymentMethodDistribution(sqlClient: SqlClient): Map<String, Long> {
        val query =
            "SELECT `paymentLabel` AS bucket, COUNT(`id`) AS cnt FROM `${prefix() + tableName}` WHERE `status` = ? GROUP BY `paymentLabel` ORDER BY cnt DESC"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(OrderStatus.COMPLETED.name))
            .coAwait()

        val result = LinkedHashMap<String, Long>()
        rows.forEach { row -> result[row.getString("bucket")] = row.getLong("cnt") }
        return result
    }

    override suspend fun uninstall(sqlClient: SqlClient) {
        sqlClient
            .query("DROP TABLE IF EXISTS `${prefix() + tableName}`")
            .execute()
            .coAwait()
    }
}
