package com.panomc.plugins.market.mc.core.link

import com.panomc.plugins.pano.core.platform.PlatformMessage
import com.panomc.plugins.pano.core.platform.PlatformMessageHandler

/**
 * `MARKET_WAKE`, pushed by Pano when something changed for this server (a delivery to offer or cancel, an announcement, the in-game
 * settings, a player's credit balance, the purchase placeholders). It carries no data: the component answers it with the request it
 * would otherwise have made on a timer. Decoded by Gson, which skips constructors: every field may be `null`.
 */
class MarketWakeMessage(
    val reasons: List<String>? = null,
    val players: List<String>? = null
) : PlatformMessage

/** The handler `pano-mc-plugin` calls for `MARKET_WAKE` (the event name is this class name minus `Handler`). */
class MarketWakeHandler(private val onWake: (reasons: List<String>, players: List<String>) -> Unit) : PlatformMessageHandler<MarketWakeMessage>() {
    override suspend fun handle(response: MarketWakeMessage) {
        onWake(response.reasons.orEmpty(), response.players.orEmpty())
    }
}
