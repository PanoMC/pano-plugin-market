package com.panomc.plugins.market.db.impl

import com.panomc.platform.annotation.Dao
import com.panomc.plugins.market.db.MarketSchema
import com.panomc.plugins.market.db.dao.MarketMailOutboxDao
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
class MarketMailOutboxDaoImpl : MarketMailOutboxDao() {

    override suspend fun init(sqlClient: SqlClient) {
        // Never throws (01 section 14.1 rule 3): a failed CREATE is logged and left to MarketSchema.ensure / SchemaVerifier.
        MarketSchema.installTable(sqlClient, MarketSchema.MAIL_OUTBOX, prefix())
    }

    override suspend fun add(mail: MarketMailOutbox, sqlClient: SqlClient): Long? {
        val query =
            "INSERT INTO `${prefix() + tableName}` (`kind`, `refType`, `refId`, `refKey`, `orderId`, `userId`, `recipient`, `locale`, `params`, `status`, `attempts`, `nextAttemptAt`, `claimedUntil`, `lastError`, `sentAt`, `createdAt`, `updatedAt`) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        val values = Tuple.tuple()
            .addValue(mail.kind.name)
            .addValue(mail.refType.name)
            .addValue(mail.refId)
            .addValue(mail.refKey)
            .addValue(mail.orderId)
            .addValue(mail.userId)
            .addValue(mail.recipient)
            .addValue(mail.locale)
            .addValue(mail.params)
            .addValue(mail.status.name)
            .addValue(mail.attempts)
            .addValue(mail.nextAttemptAt)
            .addValue(mail.claimedUntil)
            .addValue(mail.lastError)
            .addValue(mail.sentAt)
            .addValue(mail.createdAt)
            .addValue(mail.updatedAt)

        return try {
            sqlClient.preparedQuery(query).execute(values).coAwait().property(MySQLClient.LAST_INSERTED_ID)
        } catch (e: Exception) {
            if (e.isDuplicateKey()) null else throw e
        }
    }

    override suspend fun getById(id: Long, sqlClient: SqlClient): MarketMailOutbox? =
        sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE `id` = ?")
            .execute(Tuple.of(id))
            .coAwait()
            .toEntities()
            .getOrNull(0)

    override suspend fun getByKey(kind: MailKind, refType: MailRefType, refId: Long, refKey: String, recipient: String, sqlClient: SqlClient): MarketMailOutbox? =
        sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE `kind` = ? AND `refType` = ? AND `refId` = ? AND `refKey` = ? AND `recipient` = ?")
            .execute(Tuple.of(kind.name, refType.name, refId, refKey, recipient))
            .coAwait()
            .toEntities()
            .getOrNull(0)

    override suspend fun getByOrderId(orderId: Long, sqlClient: SqlClient): List<MarketMailOutbox> =
        sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE `orderId` = ? ORDER BY `id` ASC")
            .execute(Tuple.of(orderId))
            .coAwait()
            .toEntities()

    override suspend fun getDue(status: MailStatus, now: Long, limit: Int, sqlClient: SqlClient): List<MarketMailOutbox> =
        sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE `status` = ? AND `nextAttemptAt` IS NOT NULL AND `nextAttemptAt` <= ? ORDER BY `nextAttemptAt` ASC, `id` ASC LIMIT ?")
            .execute(Tuple.of(status.name, now, limit))
            .coAwait()
            .toEntities()

    override suspend fun transition(
        id: Long, from: MailStatus, to: MailStatus, attempts: Int, nextAttemptAt: Long?, lastError: String?, sentAt: Long?,
        now: Long, sqlClient: SqlClient
    ): Boolean =
        sqlClient
            .preparedQuery("UPDATE `${prefix() + tableName}` SET `status` = ?, `attempts` = ?, `nextAttemptAt` = ?, `claimedUntil` = NULL, `lastError` = ?, `sentAt` = ?, `updatedAt` = ? WHERE `id` = ? AND `status` = ?")
            .execute(
                Tuple.tuple().addValue(to.name).addValue(attempts).addValue(nextAttemptAt).addValue(lastError)
                    .addValue(sentAt).addValue(now).addValue(id).addValue(from.name)
            )
            .coAwait()
            .rowCount() > 0

    override suspend fun uninstall(sqlClient: SqlClient) {
        sqlClient
            .query("DROP TABLE IF EXISTS `${prefix() + tableName}`")
            .execute()
            .coAwait()
    }
}
