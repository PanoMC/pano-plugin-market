package com.panomc.plugins.market.mc.core.wire

import com.panomc.plugins.pano.core.platform.PlatformMessageResponse
import java.util.UUID

// MARKET_CONFIG (19 section 7.1).

/** Request `{have}`: the hash the component holds, `null` when it holds none. */
class MarketConfigRequest(
    componentVersion: String,
    protocol: Int = MarketWire.PROTOCOL,
    val have: String?,
    eventId: UUID = UUID.randomUUID()
) : MarketRequest(componentVersion, protocol, eventId)

/**
 * The `mc*` settings of 00 section 12: panel defaults merged with the per-server override. Defaults here are the
 * documented panel defaults, used only when a key is missing from the response.
 */
data class MarketMcSettings(
    val mcStoreCommand: Boolean = true,
    val mcCreditsCommand: Boolean = true,
    val mcJoinNotifications: Boolean = true,
    val mcStoreMenu: Boolean = true,
    val mcAdminCommands: Boolean = true,
    val mcPlaceholders: Boolean = true,
    val mcLuckPerms: Boolean = true,
    val mcBroadcast: Boolean = false,
    val mcBroadcastTemplate: String? = null,
    val mcDisabledAdminCommands: List<String> = emptyList(),
    val mcVaultMode: String = "OFF",
    val mcVaultRate: Double = 1.0,
    val mcVaultDirection: String = "BOTH"
)

data class MarketConfigMessage(
    val accepted: Boolean = false,
    val reason: String? = null,
    val configHash: String? = null,
    val settings: MarketMcSettings? = null,
    /** `locale -> key -> text`, at most three locales (19 section 7.1). */
    val texts: Map<String, Map<String, String>> = emptyMap(),
    val storeUrl: String? = null,
    val creditName: String? = null,
    val currency: String? = null,
    val serverId: Long? = null,
    /** Absolute product URL with a `{slug}` placeholder (front-end URL map); absent from older Pano builds. */
    val productUrlTemplate: String? = null,
    /** Absolute register page (front-end URL map); absent from older Pano builds. */
    val registerUrl: String? = null
) : PlatformMessageResponse
