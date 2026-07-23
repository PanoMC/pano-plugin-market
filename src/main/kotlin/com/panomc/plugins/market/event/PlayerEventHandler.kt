package com.panomc.plugins.market.event

import com.panomc.platform.api.annotation.EventListener
import com.panomc.platform.api.event.PlayerEventListener
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.User
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.db.dao.MarketOrderDao

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

        marketOrderDao.anonymizeByUserId(user.id, sqlClient)
    }
}
