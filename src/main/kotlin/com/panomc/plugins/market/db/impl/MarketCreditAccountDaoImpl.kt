package com.panomc.plugins.market.db.impl

import com.panomc.platform.annotation.Dao
import com.panomc.plugins.market.db.MarketSchema
import com.panomc.plugins.market.db.dao.MarketCreditAccountDao
import com.panomc.plugins.market.db.dao.isDuplicateKey
import com.panomc.plugins.market.db.model.*
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.mysqlclient.MySQLClient
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope

@Dao
@Lazy
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class MarketCreditAccountDaoImpl : MarketCreditAccountDao() {

    override suspend fun init(sqlClient: SqlClient) {
        // Never throws (01 section 14.1 rule 3): a failed CREATE is logged and left to MarketSchema.ensure / SchemaVerifier.
        val errors = MarketSchema.installTable(sqlClient, MarketSchema.CREDIT_ACCOUNT, prefix())
        // The five system accounts are seeded here and by the 6 to 7 migration (01 section 7.1); INSERT IGNORE, so repeating is harmless.
        if (errors.isEmpty()) MarketSchema.seedCreditSystemAccounts(sqlClient, prefix())
    }

    override suspend fun add(account: MarketCreditAccount, sqlClient: SqlClient): Long? {
        val query =
            "INSERT INTO `${prefix() + tableName}` (`type`, `userId`, `systemKey`, `balance`, `createdAt`, `updatedAt`) VALUES (?, ?, ?, ?, ?, ?)"
        val values = Tuple.tuple()
            .addValue(account.type.name)
            .addValue(account.userId)
            .addValue(account.systemKey?.name)
            .addValue(account.balance)
            .addValue(account.createdAt)
            .addValue(account.updatedAt)

        return try {
            sqlClient.preparedQuery(query).execute(values).coAwait().property(MySQLClient.LAST_INSERTED_ID)
        } catch (e: Exception) {
            if (e.isDuplicateKey()) null else throw e
        }
    }

    override suspend fun getById(id: Long, sqlClient: SqlClient): MarketCreditAccount? =
        sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE `id` = ?")
            .execute(Tuple.of(id))
            .coAwait()
            .toEntities()
            .getOrNull(0)

    override suspend fun getByUserId(userId: Long, sqlClient: SqlClient): MarketCreditAccount? =
        sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE `userId` = ?")
            .execute(Tuple.of(userId))
            .coAwait()
            .toEntities()
            .getOrNull(0)

    override suspend fun getBySystemKey(key: CreditSystemKey, sqlClient: SqlClient): MarketCreditAccount? =
        sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE `systemKey` = ?")
            .execute(Tuple.of(key.name))
            .coAwait()
            .toEntities()
            .getOrNull(0)

    override suspend fun getSystemAccounts(sqlClient: SqlClient): List<MarketCreditAccount> =
        sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE `type` = ? ORDER BY `id` ASC")
            .execute(Tuple.of(CreditAccountType.SYSTEM.name))
            .coAwait()
            .toEntities()

    override suspend fun seedSystemAccounts(sqlClient: SqlClient): Int =
        sqlClient.query(MarketSchema.seedCreditSystemAccountsSql(prefix())).execute().coAwait().rowCount()

    override suspend fun insertUserAccountIgnore(userId: Long, sqlClient: SqlClient): Boolean {
        val now = System.currentTimeMillis()
        return sqlClient
            .preparedQuery(
                "INSERT IGNORE INTO `${prefix() + tableName}` (`type`, `userId`, `balance`, `createdAt`, `updatedAt`) VALUES (?, ?, 0, ?, ?)"
            )
            .execute(Tuple.of(CreditAccountType.USER.name, userId, now, now))
            .coAwait()
            .rowCount() > 0
    }

    override suspend fun lockByIds(ids: Collection<Long>, sqlClient: SqlClient): List<MarketCreditAccount> {
        val distinct = ids.distinct().sorted()
        if (distinct.isEmpty()) return emptyList()
        val marks = distinct.joinToString(", ") { "?" }
        return sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE `id` IN ($marks) ORDER BY `id` ASC FOR UPDATE")
            .execute(Tuple.from(distinct))
            .coAwait()
            .toEntities()
    }

    override suspend fun addToBalance(accountId: Long, delta: Long, guarded: Boolean, sqlClient: SqlClient): Int =
        sqlClient
            .preparedQuery(
                "UPDATE `${prefix() + tableName}` SET `balance` = `balance` + ?, `updatedAt` = ? WHERE `id` = ? AND (? = 0 OR `balance` + ? >= 0)"
            )
            .execute(Tuple.of(delta, System.currentTimeMillis(), accountId, if (guarded) 1 else 0, delta))
            .coAwait()
            .rowCount()

    override suspend fun uninstall(sqlClient: SqlClient) {
        sqlClient
            .query("DROP TABLE IF EXISTS `${prefix() + tableName}`")
            .execute()
            .coAwait()
    }
}
