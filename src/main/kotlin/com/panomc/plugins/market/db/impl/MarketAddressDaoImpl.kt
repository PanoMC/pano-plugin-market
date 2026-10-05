package com.panomc.plugins.market.db.impl

import com.panomc.platform.annotation.Dao
import com.panomc.plugins.market.db.MarketSchema
import com.panomc.plugins.market.db.dao.MarketAddressDao
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
class MarketAddressDaoImpl : MarketAddressDao() {

    override suspend fun init(sqlClient: SqlClient) {
        // Never throws (01 section 14.1 rule 3): a failed CREATE is logged and left to MarketSchema.ensure / SchemaVerifier.
        MarketSchema.installTable(sqlClient, MarketSchema.ADDRESS, prefix())
    }

    override suspend fun add(address: MarketAddress, sqlClient: SqlClient): Long {
        val query =
            "INSERT INTO `${prefix() + tableName}` (`userId`, `label`, `isDefault`, `firstName`, `lastName`, `company`, `phone`, `email`, `country`, `state`, `city`, `district`, `neighborhood`, `line1`, `line2`, `postalCode`, `identityNumber`, `type`, `taxOffice`, `taxNumber`, `createdAt`, `updatedAt`) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        val values = Tuple.tuple()
            .addValue(address.userId)
            .addValue(address.label)
            .addValue(if (address.isDefault) 1 else 0)
            .addValue(address.firstName)
            .addValue(address.lastName)
            .addValue(address.company)
            .addValue(address.phone)
            .addValue(address.email)
            .addValue(address.country)
            .addValue(address.state)
            .addValue(address.city)
            .addValue(address.district)
            .addValue(address.neighborhood)
            .addValue(address.line1)
            .addValue(address.line2)
            .addValue(address.postalCode)
            .addValue(address.identityNumber)
            .addValue(address.type)
            .addValue(address.taxOffice)
            .addValue(address.taxNumber)
            .addValue(address.createdAt)
            .addValue(address.updatedAt)

        return sqlClient.preparedQuery(query).execute(values).coAwait().property(MySQLClient.LAST_INSERTED_ID)
    }

    override suspend fun getById(id: Long, sqlClient: SqlClient): MarketAddress? =
        sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE `id` = ?")
            .execute(Tuple.of(id))
            .coAwait()
            .toEntities()
            .getOrNull(0)

    override suspend fun getByUserId(userId: Long, sqlClient: SqlClient): List<MarketAddress> =
        sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE `userId` = ? ORDER BY `isDefault` DESC, `id` ASC")
            .execute(Tuple.of(userId))
            .coAwait()
            .toEntities()

    override suspend fun deleteById(id: Long, sqlClient: SqlClient): Boolean =
        sqlClient
            .preparedQuery("DELETE FROM `${prefix() + tableName}` WHERE `id` = ?")
            .execute(Tuple.of(id))
            .coAwait()
            .rowCount() > 0

    override suspend fun update(address: MarketAddress, sqlClient: SqlClient): Boolean {
        val query =
            "UPDATE `${prefix() + tableName}` SET `label` = ?, `isDefault` = ?, `firstName` = ?, `lastName` = ?, `company` = ?, `phone` = ?, `email` = ?, `country` = ?, `state` = ?, `city` = ?, `district` = ?, `neighborhood` = ?, `line1` = ?, `line2` = ?, `postalCode` = ?, `identityNumber` = ?, `type` = ?, `taxOffice` = ?, `taxNumber` = ?, `updatedAt` = ? WHERE `id` = ?"
        val values = Tuple.tuple()
            .addValue(address.label)
            .addValue(if (address.isDefault) 1 else 0)
            .addValue(address.firstName)
            .addValue(address.lastName)
            .addValue(address.company)
            .addValue(address.phone)
            .addValue(address.email)
            .addValue(address.country)
            .addValue(address.state)
            .addValue(address.city)
            .addValue(address.district)
            .addValue(address.neighborhood)
            .addValue(address.line1)
            .addValue(address.line2)
            .addValue(address.postalCode)
            .addValue(address.identityNumber)
            .addValue(address.type)
            .addValue(address.taxOffice)
            .addValue(address.taxNumber)
            .addValue(address.updatedAt)
            .addValue(address.id)

        // MySQL reports 0 affected rows for an UPDATE that changes nothing: the row is judged by a read, not by the count
        sqlClient.preparedQuery(query).execute(values).coAwait()

        return getById(address.id, sqlClient) != null
    }

    override suspend fun countByUserId(userId: Long, sqlClient: SqlClient): Int =
        sqlClient
            .preparedQuery("SELECT COUNT(*) AS `n` FROM `${prefix() + tableName}` WHERE `userId` = ?")
            .execute(Tuple.of(userId))
            .coAwait()
            .first()
            .getInteger("n")

    override suspend fun clearDefault(userId: Long, keepId: Long, now: Long, sqlClient: SqlClient): Int =
        sqlClient
            .preparedQuery("UPDATE `${prefix() + tableName}` SET `isDefault` = 0, `updatedAt` = ? WHERE `userId` = ? AND `isDefault` = 1 AND `id` <> ?")
            .execute(Tuple.of(now, userId, keepId))
            .coAwait()
            .rowCount()

    override suspend fun markDefault(userId: Long, id: Long, now: Long, sqlClient: SqlClient): Boolean {
        sqlClient
            .preparedQuery("UPDATE `${prefix() + tableName}` SET `isDefault` = 1, `updatedAt` = ? WHERE `id` = ? AND `userId` = ?")
            .execute(Tuple.of(now, id, userId))
            .coAwait()

        return getById(id, sqlClient)?.userId == userId
    }

    override suspend fun uninstall(sqlClient: SqlClient) {
        sqlClient
            .query("DROP TABLE IF EXISTS `${prefix() + tableName}`")
            .execute()
            .coAwait()
    }
}
