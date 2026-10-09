package com.panomc.plugins.market.mc.core.feature

import com.panomc.plugins.market.mc.core.platform.DeliverySettings
import com.panomc.plugins.market.mc.core.wire.MarketMcSettings
import java.util.concurrent.atomic.AtomicReference

/** The optional in-game features (19 section 9); each has a local switch and a panel switch. */
enum class Feature {
    STORE_COMMAND, CREDITS_COMMAND, BROADCAST, JOIN_NOTIFICATIONS, STORE_MENU, ADMIN_COMMANDS, PLACEHOLDERS, VAULT, LUCKPERMS
}

/**
 * Effective setting (19 section 9, MC-U6): the local `config.yml` hard-disable wins, then the per-server override, then
 * the panel default. The local file can only turn a feature OFF, it can never enable one the panel disabled.
 */
object FeatureResolver {
    /** @param serverOverride the per-server value (`market_server_state.settings`), `null` when the server has none. */
    fun resolve(local: Boolean, serverOverride: Boolean?, panelDefault: Boolean): Boolean = local && (serverOverride ?: panelDefault)
}

/** The last accepted `MARKET_CONFIG` answer. */
data class RemoteConfig(
    val configHash: String,
    /** Panel defaults merged with this server's override by Pano (19 section 7.1): the "override beats default" half. */
    val settings: MarketMcSettings,
    val texts: Map<String, Map<String, String>>,
    val storeUrl: String?,
    val creditName: String?,
    val currency: String?,
    val serverId: Long?,
    val productUrlTemplate: String? = null,
    val registerUrl: String? = null
) {
    /** The store, product and register links of this answer. */
    val links: StoreLinks get() = StoreLinks(storeUrl, productUrlTemplate, registerUrl)
}

/**
 * [LocalConfig] + the last `MARKET_CONFIG`, read live. Until the first answer arrived the built-in panel defaults apply to
 * the harmless features (never to the admin commands, see [adminCommandDisabled] and `MarketCommands.adminAllowed`),
 * with one exception: broadcasts. Pano only offers a broadcast while `mcBroadcast` is on for this server, so an offered
 * one is proof of the setting and must not be lost to a start-up race (the first sync runs before the first config pull).
 */
class EffectiveConfig(val local: LocalConfig) {
    private val remoteRef = AtomicReference<RemoteConfig?>(null)

    val remote: RemoteConfig? get() = remoteRef.get()

    fun update(config: RemoteConfig) = remoteRef.set(config)

    fun enabled(feature: Feature): Boolean {
        val localOn = local.features.allows(feature)
        val r = remote?.settings
        if (r == null) {
            return FeatureResolver.resolve(localOn, null, if (feature == Feature.BROADCAST) true else DEFAULTS.panel(feature))
        }
        return FeatureResolver.resolve(localOn, r.panel(feature), false)
    }

    /**
     * `mcDisabledAdminCommands`: `give-credits`, `take-credits`, `set-credits`, `grant-product`, `purchases`. Fails CLOSED
     * while the panel settings are unknown (no accepted `MARKET_CONFIG` yet, e.g. after a restart): a command the panel
     * switched off must never slip through the start-up window (19 section 7.4 half 1, section 9).
     */
    fun adminCommandDisabled(name: String): Boolean {
        val settings = remote?.settings ?: return true
        return settings.mcDisabledAdminCommands.any { it.equals(name, true) }
    }

    /** The live view the engine reads at call time. */
    val deliverySettings: DeliverySettings = object : DeliverySettings {
        override val deliveriesEnabled: Boolean get() = local.enabled && local.deliveries
        override val luckPermsEnabled: Boolean get() = enabled(Feature.LUCKPERMS)
        override val broadcastEnabled: Boolean get() = enabled(Feature.BROADCAST)
    }

    companion object {
        private val DEFAULTS = MarketMcSettings()

        private fun MarketMcSettings.panel(feature: Feature): Boolean = when (feature) {
            Feature.STORE_COMMAND -> mcStoreCommand
            Feature.CREDITS_COMMAND -> mcCreditsCommand
            Feature.BROADCAST -> mcBroadcast
            Feature.JOIN_NOTIFICATIONS -> mcJoinNotifications
            Feature.STORE_MENU -> mcStoreMenu
            Feature.ADMIN_COMMANDS -> mcAdminCommands
            Feature.PLACEHOLDERS -> mcPlaceholders
            Feature.VAULT -> !mcVaultMode.equals("OFF", true)
            Feature.LUCKPERMS -> mcLuckPerms
        }
    }
}
