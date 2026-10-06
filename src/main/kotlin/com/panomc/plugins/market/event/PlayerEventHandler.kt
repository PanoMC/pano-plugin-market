package com.panomc.plugins.market.event

import com.panomc.platform.api.annotation.EventListener
import com.panomc.platform.api.event.PlayerEventListener
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.User
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.core.time.SystemClock
import com.panomc.plugins.market.db.dao.MarketOrderDao
import com.panomc.plugins.market.db.tx.MarketDb
import com.panomc.plugins.market.routes.api.order.paymentService
import com.panomc.plugins.market.routes.api.order.subscriptionService
import io.vertx.sqlclient.Pool
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

@EventListener
class PlayerEventHandler(
    private val plugin: MarketPlugin,
    private val marketOrderDao: MarketOrderDao
) : PlayerEventListener {
    private val databaseManager by lazy {
        plugin.applicationContext.getBean(DatabaseManager::class.java)
    }

    // Orders are permanent financial records: anonymize (NULL userId) but keep the playerUsername snapshot.
    override suspend fun onDelete(user: User) {
        val sqlClient = databaseManager.getSqlClient()

        // subscriptions first (09 section 10.4): they end and lose their personal data while their orders still name the user (the lock is the initial order's)
        try {
            val db = MarketDb({ databaseManager.getSqlClient() as Pool }, SystemClock)

            subscriptionService(plugin).onUserDeleted(db, { after -> paymentService(plugin).runAfterCommit(after) }, user.id)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // the deletion of the account goes on: a subscription that could not be ended is a visible log line, never a blocked deletion
            logger.error("the subscriptions of the deleted user {} could not be ended: {}", user.id, t.toString())
        }

        marketOrderDao.anonymizeByUserId(user.id, sqlClient)
    }

    private companion object {
        val logger = LoggerFactory.getLogger(PlayerEventHandler::class.java)
    }
}
