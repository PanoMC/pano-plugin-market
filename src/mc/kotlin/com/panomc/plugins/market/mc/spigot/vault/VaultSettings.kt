package com.panomc.plugins.market.mc.spigot.vault

import com.panomc.plugins.market.mc.core.feature.EffectiveConfig
import com.panomc.plugins.market.mc.core.feature.Feature
import java.math.BigDecimal
import java.math.RoundingMode

/** `mcVaultMode` (19 section 10). */
enum class VaultMode { OFF, CONVERT, PROVIDER }

/**
 * `mcVaultDirection` of the CONVERT mode. [NONE] is what an unknown value becomes: a setting the component does not
 * understand must never open a money path (fail closed).
 */
enum class VaultDirection {
    BOTH, TO_SERVER, TO_CREDITS, NONE;

    /** `/credits convert`: credits become server money. */
    val allowsToServer: Boolean get() = this == BOTH || this == TO_SERVER

    /** `/credits deposit`: server money becomes credits. */
    val allowsToCredits: Boolean get() = this == BOTH || this == TO_CREDITS
}

/** The Vault settings in force right now. [rate] is `null` when the configured value is not a usable rate. */
data class VaultSettings(val mode: VaultMode, val direction: VaultDirection, val rate: BigDecimal?) {
    companion object {
        val OFF = VaultSettings(VaultMode.OFF, VaultDirection.NONE, null)
    }
}

/**
 * Reads the effective Vault settings at call time (never cached: the panel can change them at any moment, 00 section 12).
 * `OFF` while the panel settings are unknown (no accepted `MARKET_CONFIG` yet), while the panel mode is `OFF` and while the
 * local `config.yml` switched `vault` off (the local switch wins, 19 section 9).
 */
class VaultSettingsReader(private val config: EffectiveConfig) {
    fun current(): VaultSettings {
        val s = config.remote?.settings ?: return VaultSettings.OFF
        if (!config.enabled(Feature.VAULT)) return VaultSettings.OFF
        val mode = parseMode(s.mcVaultMode)
        if (mode == VaultMode.OFF) return VaultSettings.OFF
        return VaultSettings(mode, parseDirection(s.mcVaultDirection), parseRate(s.mcVaultRate))
    }

    /** The mode the panel set in the last accepted `MARKET_CONFIG`; `null` until one arrived (every start begins without). */
    fun panelMode(): VaultMode? = config.remote?.settings?.let { parseMode(it.mcVaultMode) }

    /** The local `config.yml` switch alone (the panel is not consulted): `false` = this server switched Vault off for good. */
    fun localSwitchOn(): Boolean = config.local.features.allows(Feature.VAULT)

    companion object {
        fun parseMode(raw: String?): VaultMode = VaultMode.values().firstOrNull { it.name.equals(raw?.trim(), true) } ?: VaultMode.OFF

        fun parseDirection(raw: String?): VaultDirection =
            VaultDirection.values().firstOrNull { it.name.equals(raw?.trim(), true) } ?: VaultDirection.NONE

        /** A rate is "server-economy units per 1 credit": finite and above zero. */
        fun parseRate(raw: Double): BigDecimal? {
            if (raw.isNaN() || raw.isInfinite() || raw <= 0.0) return null
            return BigDecimal.valueOf(raw).takeIf { it.signum() > 0 }
        }
    }
}

/** The amounts of one conversion. Credits have two decimals (07 section 3), server money is kept to two decimals too. */
data class ConversionAmounts(val credits: BigDecimal, val money: BigDecimal)

/**
 * The arithmetic of `/credits convert` and `/credits deposit`. Rounding never favours the player:
 * - credits -> server money: the player pays exactly the credits they typed and receives the money rounded DOWN;
 * - server money -> credits: the player receives the credits rounded DOWN and is charged exactly what those credits cost
 *   (never more than the money they typed), rounded UP.
 * `null` = the amount is too small (a side would be zero) or too large to be safe.
 */
object Conversion {
    private const val SCALE = 2
    private val LIMIT = BigDecimal("1000000000000000") // 1e15: far below the precision loss of a double

    fun toServer(credits: BigDecimal, rate: BigDecimal): ConversionAmounts? {
        val c = twoDecimals(credits) ?: return null
        val money = c.multiply(rate).setScale(SCALE, RoundingMode.DOWN)
        return valid(c, money)
    }

    fun toCredits(money: BigDecimal, rate: BigDecimal): ConversionAmounts? {
        val typed = twoDecimals(money) ?: return null
        val credits = typed.divide(rate, SCALE, RoundingMode.DOWN)
        val cost = credits.multiply(rate).setScale(SCALE, RoundingMode.UP)
        return valid(credits, cost)
    }

    /** The amount with exactly two decimals, `null` when it has more (nothing is silently cut off). */
    private fun twoDecimals(v: BigDecimal): BigDecimal? = if (v.stripTrailingZeros().scale() > SCALE) null else v.setScale(SCALE)

    private fun valid(credits: BigDecimal, money: BigDecimal): ConversionAmounts? {
        if (credits.signum() <= 0 || money.signum() <= 0) return null
        if (credits >= LIMIT || money >= LIMIT) return null
        return ConversionAmounts(credits, money)
    }
}
