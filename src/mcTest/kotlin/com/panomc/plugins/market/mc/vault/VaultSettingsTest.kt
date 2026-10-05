package com.panomc.plugins.market.mc.vault

import com.panomc.plugins.market.mc.core.wire.MarketMcSettings
import com.panomc.plugins.market.mc.feature.FeatureRig
import com.panomc.plugins.market.mc.feature.panoConfig
import com.panomc.plugins.market.mc.spigot.vault.Conversion
import com.panomc.plugins.market.mc.spigot.vault.VaultDirection
import com.panomc.plugins.market.mc.spigot.vault.VaultMode
import com.panomc.plugins.market.mc.spigot.vault.VaultSettings
import com.panomc.plugins.market.mc.spigot.vault.VaultSettingsReader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Path

/** Direction, rate and mode settings as the bridge reads them, and the arithmetic of the two conversions. */
class VaultSettingsTest {
    @TempDir
    lateinit var dir: Path

    private fun reader(configText: String? = null, settings: MarketMcSettings? = null): VaultSettingsReader {
        val rig = FeatureRig(dir.resolve("f-${System.nanoTime()}"), configText = configText)
        if (settings != null) rig.loadConfig(panoConfig(settings = settings))
        return VaultSettingsReader(rig.features.config)
    }

    @Test
    fun `no panel settings yet means OFF`() {
        assertEquals(VaultSettings.OFF, reader().current())
    }

    @Test
    fun `mode, direction and rate come from the panel settings`() {
        val s = reader(settings = MarketMcSettings(mcVaultMode = "CONVERT", mcVaultRate = 2.5, mcVaultDirection = "TO_CREDITS")).current()
        assertEquals(VaultMode.CONVERT, s.mode)
        assertEquals(VaultDirection.TO_CREDITS, s.direction)
        assertEquals(BigDecimal("2.5"), s.rate)
        assertTrue(s.direction.allowsToCredits && !s.direction.allowsToServer)
        val p = reader(settings = MarketMcSettings(mcVaultMode = "provider")).current()
        assertEquals(VaultMode.PROVIDER, p.mode)
    }

    @Test
    fun `OFF in the panel and the local vault switch both give OFF`() {
        assertEquals(VaultSettings.OFF, reader(settings = MarketMcSettings(mcVaultMode = "OFF")).current())
        assertEquals(VaultSettings.OFF, reader(configText = "features:\n  vault: false\n", settings = MarketMcSettings(mcVaultMode = "CONVERT")).current())
    }

    @Test
    fun `an unknown mode is OFF and an unknown direction opens no money path`() {
        assertEquals(VaultMode.OFF, reader(settings = MarketMcSettings(mcVaultMode = "SOMETHING")).current().mode)
        val d = reader(settings = MarketMcSettings(mcVaultMode = "CONVERT", mcVaultDirection = "SIDEWAYS")).current()
        assertEquals(VaultDirection.NONE, d.direction)
        assertTrue(!d.direction.allowsToServer && !d.direction.allowsToCredits)
    }

    @Test
    fun `a rate that is not above zero and finite is unusable`() {
        for (bad in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertNull(VaultSettingsReader.parseRate(bad), "$bad")
        }
        assertEquals(BigDecimal("0.01"), VaultSettingsReader.parseRate(0.01))
        assertNull(reader(settings = MarketMcSettings(mcVaultMode = "CONVERT", mcVaultRate = 0.0)).current().rate)
    }

    @Test
    fun `the settings are read at call time`() {
        val rig = FeatureRig(dir.resolve("live"))
        val r = VaultSettingsReader(rig.features.config)
        assertEquals(VaultMode.OFF, r.current().mode)
        rig.loadConfig(panoConfig(hash = "a", settings = MarketMcSettings(mcVaultMode = "CONVERT", mcVaultRate = 4.0)))
        assertEquals(BigDecimal("4.0"), r.current().rate)
        rig.loadConfig(panoConfig(hash = "b", settings = MarketMcSettings(mcVaultMode = "PROVIDER")))
        assertEquals(VaultMode.PROVIDER, r.current().mode)
    }

    // ---- the arithmetic ------------------------------------------------------------------------------------------------------

    private fun bd(s: String) = BigDecimal(s)

    @Test
    fun `credits to money - the player pays the credits typed and gets the money rounded down`() {
        val a = Conversion.toServer(bd("10"), bd("2.5"))!!
        assertEquals(bd("10.00"), a.credits)
        assertEquals(bd("25.00"), a.money)
        assertEquals(bd("0.49"), Conversion.toServer(bd("1.5"), bd("0.333"))!!.money)
        assertEquals(bd("0.01"), Conversion.toServer(bd("0.01"), bd("1"))!!.money)
        assertNull(Conversion.toServer(bd("0.01"), bd("0.1")), "money would round to nothing")
    }

    @Test
    fun `money to credits - the credits are rounded down and the cost is what those credits cost`() {
        val a = Conversion.toCredits(bd("10"), bd("3"))!!
        assertEquals(bd("3.33"), a.credits)
        assertEquals(bd("9.99"), a.money)
        val b = Conversion.toCredits(bd("1"), bd("0.3"))!!
        assertEquals(bd("3.33"), b.credits)
        assertEquals(bd("1.00"), b.money, "0.999 is rounded up to the cent, never down")
        assertNull(Conversion.toCredits(bd("0.01"), bd("2")), "the credits would round to nothing")
        assertEquals(bd("5.00"), Conversion.toCredits(bd("10"), bd("2"))!!.credits)
    }

    @Test
    fun `too many decimals and absurd sizes are refused, never silently cut`() {
        assertNull(Conversion.toServer(bd("1.234"), bd("2")))
        assertNull(Conversion.toCredits(bd("1.234"), bd("2")))
        assertNotNull(Conversion.toServer(bd("1.20"), bd("2")))
        assertNull(Conversion.toServer(bd("999999999999"), bd("10000")), "above 1e15")
        assertNull(Conversion.toCredits(bd("999999999999"), bd("0.0001")))
    }

    @Test
    fun `property - the player never gains from rounding in either direction`() {
        val rnd = java.util.Random(99)
        repeat(3_000) {
            val typed = BigDecimal(rnd.nextInt(2_000_000)).movePointLeft(2).setScale(2)
            val rate = BigDecimal(rnd.nextInt(100_000) + 1).movePointLeft(rnd.nextInt(4))
            Conversion.toServer(typed, rate)?.let { a ->
                assertEquals(typed, a.credits)
                assertTrue(a.money <= a.credits.multiply(rate), "money $typed credits at $rate paid ${a.money}")
            }
            Conversion.toCredits(typed, rate)?.let { a ->
                assertTrue(a.money <= typed, "cost ${a.money} above the typed $typed at $rate")
                assertTrue(a.credits.multiply(rate) <= a.money.add(BigDecimal("0.01")), "credits ${a.credits} cost more than charged")
                assertTrue(a.credits.multiply(rate) <= typed, "credits ${a.credits} are worth more than the money $typed at $rate")
            }
        }
    }
}
