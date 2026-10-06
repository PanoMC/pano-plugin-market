package com.panomc.plugins.market.event

import com.panomc.platform.api.annotation.EventListener
import com.panomc.platform.api.event.PlayerEventListener
import com.panomc.platform.db.model.User
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.job.playerErasureService
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

@EventListener
class PlayerEventHandler(
    private val plugin: MarketPlugin
) : PlayerEventListener {
    /**
     * A deleted account (11 section 16): `PlayerErasureService.erase` ends the subscriptions, cancels the unpaid orders, closes the credit account, blanks the
     * personal columns of the orders (they stay as financial records with the `playerUsername` snapshot) and the rest of the table. It never throws: a step that
     * fails is logged and retried by `HousekeepingJob`, the deletion of the account is never blocked.
     */
    override suspend fun onDelete(user: User) {
        try {
            val report = playerErasureService(plugin).erase(user.id)

            if (!report.complete) logger.info("the erasure of user {} is not complete yet (failed steps {}, waiting for credits {}), the housekeeping job finishes it", user.id, report.failed, report.deferred)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            logger.error("the personal data of the deleted user {} could not be erased: {}", user.id, t.toString())
        }
    }

    private companion object {
        val logger = LoggerFactory.getLogger(PlayerEventHandler::class.java)
    }
}
