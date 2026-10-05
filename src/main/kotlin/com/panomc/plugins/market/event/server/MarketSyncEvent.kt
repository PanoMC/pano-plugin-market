package com.panomc.plugins.market.event.server

import com.panomc.platform.db.model.Server
import com.panomc.platform.server.ServerEvent
import com.panomc.plugins.market.runtime.MarketRuntime
import com.panomc.plugins.market.service.McSyncService

/**
 * The server event `MARKET_SYNC` (08 section 8.1): the Pano side of the pull protocol the Minecraft component speaks. The platform derives the
 * event name from the class name (`ServerEvent.getEventName`), so renaming this class would silence every component.
 *
 * `MarketPlugin.onStart` registers one instance with `ServerManager.registerEvent` and `onStop` / `onDisable` remove it. The frame arrives over the
 * server's authenticated, AES-256-GCM encrypted socket, so [server] is a trusted identity. The event is registered before the store is READY, when
 * the service (its beans, its database) may not exist yet: while the runtime gate is closed (00 section 8.9) it answers `MARKET_NOT_READY` itself and
 * never looks the service up.
 */
class MarketSyncEvent(
    private val service: () -> McSyncService,
    private val ready: () -> Boolean = { MarketRuntime.isReady }
) : ServerEvent<MarketSyncEventRequest, MarketSyncEventResponse>() {
    override suspend fun handle(request: MarketSyncEventRequest, server: Server): MarketSyncEventResponse =
        if (ready()) service().handle(request, server) else McSyncService.refusal(McSyncService.REASON_NOT_READY, null)
}
