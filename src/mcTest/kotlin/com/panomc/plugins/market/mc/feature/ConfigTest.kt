package com.panomc.plugins.market.mc.feature

import com.panomc.plugins.market.mc.core.feature.EffectiveConfig
import com.panomc.plugins.market.mc.core.feature.Feature
import com.panomc.plugins.market.mc.core.feature.FeatureResolver
import com.panomc.plugins.market.mc.core.feature.LocalConfig
import com.panomc.plugins.market.mc.core.feature.LocalFeatures
import com.panomc.plugins.market.mc.core.feature.MiniYaml
import com.panomc.plugins.market.mc.core.feature.RemoteConfig
import com.panomc.plugins.market.mc.core.feature.YamlException
import com.panomc.plugins.market.mc.core.wire.MarketMcSettings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MiniYamlTest {
    @Test
    fun `scalars, nested maps, lists and comments`() {
        val m = MiniYaml.parse(
            """
            # a comment
            enabled: true
            count: 12
            ratio: 1.5
            name: 'it''s'   # trailing comment
            text: "tab\there \"q\""
            plain: hello world: still plain
            empty:
            nothing: ~
            features:
              a: false
              nested:
                deep: yes
            inline: [a, 'b c', "d"]
            none: []
            block:
              - x
              - 2
            samelevel:
            - one
            - two
            version: 1.8.8
            """.trimIndent()
        )
        assertEquals(true, m["enabled"])
        assertEquals(12L, m["count"])
        assertEquals(1.5, m["ratio"])
        assertEquals("it's", m["name"])
        assertEquals("tab\there \"q\"", m["text"])
        assertEquals("hello world: still plain", m["plain"])
        assertNull(m["empty"])
        assertNull(m["nothing"])
        assertEquals(mapOf("a" to false, "nested" to mapOf("deep" to "yes")), m["features"])
        assertEquals(listOf("a", "b c", "d"), m["inline"])
        assertEquals(emptyList<Any>(), m["none"])
        assertEquals(listOf("x", 2L), m["block"])
        assertEquals(listOf("one", "two"), m["samelevel"])
        assertEquals("1.8.8", m["version"])
    }

    @Test
    fun `a colour code at the start of a plain value and a hash inside quotes are text`() {
        val m = MiniYaml.parse("a: &cRed text\nb: \"x # y\"\nc: url#frag\n")
        assertEquals("&cRed text", m["a"])
        assertEquals("x # y", m["b"])
        assertEquals("url#frag", m["c"])
    }

    @Test
    fun `an empty document is an empty map`() {
        assertEquals(emptyMap<String, Any?>(), MiniYaml.parse(""))
        assertEquals(emptyMap<String, Any?>(), MiniYaml.parse("# only a comment\n\n"))
    }

    @Test
    fun `mistakes are errors with a line number, never a silent misreading`() {
        fun fails(text: String, fragment: String) {
            val e = assertThrows(YamlException::class.java) { MiniYaml.parse(text) }
            assertTrue(e.message!!.contains(fragment), "${e.message} should contain '$fragment'")
        }
        fails("a: 1\na: 2\n", "duplicate key 'a'")
        fails("a: 1\n  b: 2\n", "unexpected indentation")
        fails("\ta: 1\n", "tabs")
        fails("a: 'open\n", "unterminated")
        fails("a: \"bad \\q\"\n", "unknown escape")
        fails("just text\n", "key: value")
        fails("a: |\n  x\n", "multi-line")
        fails("a: {b: 1}\n", "flow maps")
        fails("a: [1, 2\n", "unterminated list")
        fails("- x\n- y\n", "must be a map")
        fails("a:\n  - k: v\n", "maps inside lists")
        fails("a: 'x' y\n", "after the quoted value")
        assertEquals(3, assertThrows(YamlException::class.java) { MiniYaml.parse("a: 1\nb: 2\nb: 3\n") }.line)
    }
}

class LocalConfigTest {
    @Test
    fun `the config yml the jar ships parses to the documented defaults with no warning`() {
        val text = ShippedResources.reader("mc/config.yml")
        assertNotNull(text, "mc/config.yml is missing")
        val c = LocalConfig.parse(text!!)
        assertNull(c.error)
        assertEquals(emptyList<String>(), c.warnings)
        assertEquals(LocalConfig(), c)
        assertTrue(c.enabled && c.deliveries)
        assertEquals(listOf("store", "buy"), c.namesOf("store"))
        assertEquals(listOf("credits"), c.namesOf("credits"))
        assertEquals(6, c.menuRows)
        assertTrue(LocalConfig.COMMANDS.all { it in c.commandAliases })
    }

    @Test
    fun `an empty file is the defaults`() {
        assertEquals(LocalConfig(), LocalConfig.parse(""))
        assertEquals(LocalConfig(), LocalConfig.parse("features:\n  broadcast: true\n"))
    }

    @Test
    fun `every switch can be turned off locally`() {
        val c = LocalConfig.parse(
            """
            enabled: true
            deliveries: false
            features:
              store-command: false
              credits-command: false
              broadcast: false
              join-notifications: false
              store-menu: false
              admin-commands: false
              placeholders: false
              vault: false
              luckperms: false
            """.trimIndent()
        )
        assertNull(c.error)
        assertFalse(c.deliveries)
        Feature.values().forEach { assertFalse(c.features.allows(it), "$it must be off") }
        assertEquals(LocalFeatures.ALL_OFF, c.features)
    }

    @Test
    fun `aliases are validated, unique and the defaults apply when the block is absent`() {
        val c = LocalConfig.parse("command-aliases:\n  store: [Shop, 'bad name', credits, buy]\n  credits: [bal, shop]\n  nonsense: [x]\n  panomarket: [pm]\n")
        assertEquals(listOf("store", "shop", "buy"), c.namesOf("store"))
        assertEquals(listOf("credits", "bal"), c.namesOf("credits"))
        assertEquals(listOf("panomarket", "pm"), c.namesOf("panomarket"))
        assertTrue(c.warnings.any { it.contains("'bad name'") })
        assertTrue(c.warnings.any { it.contains("'credits' ignored") || it.contains("alias 'credits'") })
        assertTrue(c.warnings.any { it.contains("alias 'shop'") })
        assertTrue(c.warnings.any { it.contains("unknown command 'nonsense'") })
        assertEquals(listOf("store", "buy"), LocalConfig.parse("enabled: true\n").namesOf("store"))
        assertEquals(listOf("store"), LocalConfig.parse("command-aliases:\n  credits: []\n").namesOf("store"))
    }

    @Test
    fun `at most three locales, menu rows are clamped, unknown keys are warnings`() {
        val c = LocalConfig.parse("locales: [tr, 'en-US', ru, de, 'not a locale']\nmenu-rows: 9\nmystery: 1\nfeatures:\n  teleport: true\n")
        assertEquals(listOf("tr", "en-US", "ru"), c.locales)
        assertEquals(6, c.menuRows)
        assertNull(c.error)
        assertTrue(c.warnings.any { it.contains("'de' ignored") })
        assertTrue(c.warnings.any { it.contains("not a locale") })
        assertTrue(c.warnings.any { it.contains("menu-rows 9") })
        assertTrue(c.warnings.any { it.contains("unknown key 'mystery'") })
        assertTrue(c.warnings.any { it.contains("unknown feature 'teleport'") })
        assertEquals(1, LocalConfig.parse("menu-rows: 0\n").menuRows)
    }

    @Test
    fun `a file that cannot be read fails closed without consuming deliveries, nothing is switched on by accident`() {
        listOf(
            "enabled: true\n  broken: indentation\n",
            "features:\n  broadcast: nope\n",
            "deliveries: 'false'\n",
            "features: [a]\n",
            "locales: tr\n",
            "command-aliases: [x]\n",
            "menu-rows: many\n"
        ).forEach { text ->
            val c = LocalConfig.parse(text)
            assertNotNull(c.error, "no error for: $text")
            assertFalse(c.enabled, "the component is not started (deliveries stay queued on Pano, none is answered DISABLED_LOCALLY) for: $text")
            assertFalse(c.deliveries, "deliveries stay off for: $text")
            assertEquals(LocalFeatures.ALL_OFF, c.features, "features stay off for: $text")
            assertTrue(c.error!!.startsWith("config.yml"))
        }
    }
}

/** MC-U6: local disable beats override beats default; local cannot enable. */
class EffectivePrecedenceTest {
    @Test
    fun `MC-U6 the truth table of the three layers`() {
        for (local in listOf(true, false)) for (override in listOf(null, true, false)) for (default in listOf(true, false)) {
            val expected = if (!local) false else (override ?: default)
            assertEquals(expected, FeatureResolver.resolve(local, override, default), "local=$local override=$override default=$default")
        }
        // The named rules:
        assertFalse(FeatureResolver.resolve(local = false, serverOverride = true, panelDefault = true), "local disable beats override and default")
        assertTrue(FeatureResolver.resolve(local = true, serverOverride = true, panelDefault = false), "override beats default (on)")
        assertFalse(FeatureResolver.resolve(local = true, serverOverride = false, panelDefault = true), "override beats default (off)")
        assertFalse(FeatureResolver.resolve(local = false, serverOverride = null, panelDefault = true), "local cannot enable")
        assertFalse(FeatureResolver.resolve(local = true, serverOverride = null, panelDefault = false), "the panel default off stays off")
    }

    private fun remote(settings: MarketMcSettings) = RemoteConfig("h", settings, emptyMap(), "https://s.example", "Credits", "USD", 1)

    @Test
    fun `MC-U6 through the effective config for every feature`() {
        val allOff = MarketMcSettings(
            mcStoreCommand = false, mcCreditsCommand = false, mcJoinNotifications = false, mcStoreMenu = false, mcAdminCommands = false,
            mcPlaceholders = false, mcLuckPerms = false, mcBroadcast = false, mcVaultMode = "OFF"
        )
        val allOn = MarketMcSettings(
            mcStoreCommand = true, mcCreditsCommand = true, mcJoinNotifications = true, mcStoreMenu = true, mcAdminCommands = true,
            mcPlaceholders = true, mcLuckPerms = true, mcBroadcast = true, mcVaultMode = "CONVERT"
        )
        val localOn = LocalConfig()
        val localOff = LocalConfig(features = LocalFeatures.ALL_OFF)

        // panel on, local off: off (local disable wins)
        EffectiveConfig(localOff).also { it.update(remote(allOn)) }.let { e -> Feature.values().forEach { assertFalse(e.enabled(it), "$it: local off beats panel on") } }
        // panel off, local on: off (local cannot enable)
        EffectiveConfig(localOn).also { it.update(remote(allOff)) }.let { e -> Feature.values().forEach { assertFalse(e.enabled(it), "$it: local on cannot enable what the panel disabled") } }
        // both on: on
        EffectiveConfig(localOn).also { it.update(remote(allOn)) }.let { e -> Feature.values().forEach { assertTrue(e.enabled(it), "$it") } }
        // one feature at a time
        Feature.values().forEach { only ->
            val local = LocalFeatures(
                storeCommand = only != Feature.STORE_COMMAND, creditsCommand = only != Feature.CREDITS_COMMAND, broadcast = only != Feature.BROADCAST,
                joinNotifications = only != Feature.JOIN_NOTIFICATIONS, storeMenu = only != Feature.STORE_MENU, adminCommands = only != Feature.ADMIN_COMMANDS,
                placeholders = only != Feature.PLACEHOLDERS, vault = only != Feature.VAULT, luckPerms = only != Feature.LUCKPERMS
            )
            val e = EffectiveConfig(LocalConfig(features = local)).also { it.update(remote(allOn)) }
            Feature.values().forEach { f -> assertEquals(f != only, e.enabled(f), "only $only is off locally; $f") }
        }
    }

    @Test
    fun `before the first config answer the built-in defaults apply to the harmless features, an offered broadcast is not lost, admin commands stay closed`() {
        val e = EffectiveConfig(LocalConfig())
        assertNull(e.remote)
        assertTrue(e.enabled(Feature.STORE_COMMAND))
        assertTrue(e.enabled(Feature.ADMIN_COMMANDS), "the feature switch alone is not the gate: MarketCommands also needs the settings")
        for (name in listOf("give-credits", "take-credits", "set-credits", "grant-product", "purchases")) {
            assertTrue(e.adminCommandDisabled(name), "$name must be closed while the panel settings are unknown")
        }
        val answered = EffectiveConfig(LocalConfig()).also { it.update(remote(MarketMcSettings(mcDisabledAdminCommands = listOf("take-credits")))) }
        assertFalse(answered.adminCommandDisabled("give-credits"))
        assertTrue(answered.adminCommandDisabled("TAKE-CREDITS"))
        assertFalse(e.enabled(Feature.VAULT), "Vault is off until the panel chooses a mode")
        assertTrue(e.enabled(Feature.BROADCAST), "Pano only offers broadcasts while mcBroadcast is on")
        assertFalse(EffectiveConfig(LocalConfig(features = LocalFeatures(broadcast = false))).enabled(Feature.BROADCAST), "local still wins before the first answer")
        assertTrue(e.deliverySettings.deliveriesEnabled)
    }

    @Test
    fun `the delivery settings the engine reads follow the local file and the panel live`() {
        val e = EffectiveConfig(LocalConfig(deliveries = false))
        assertFalse(e.deliverySettings.deliveriesEnabled)
        assertTrue(EffectiveConfig(LocalConfig()).deliverySettings.deliveriesEnabled)

        val live = EffectiveConfig(LocalConfig())
        assertTrue(live.deliverySettings.luckPermsEnabled)
        live.update(remote(MarketMcSettings(mcLuckPerms = false, mcBroadcast = true)))
        assertFalse(live.deliverySettings.luckPermsEnabled, "the panel switched LuckPerms off")
        assertTrue(live.deliverySettings.broadcastEnabled)
        live.update(remote(MarketMcSettings(mcLuckPerms = true, mcBroadcast = false)))
        assertTrue(live.deliverySettings.luckPermsEnabled)
        assertFalse(live.deliverySettings.broadcastEnabled)

        val localOff = EffectiveConfig(LocalConfig(features = LocalFeatures(luckPerms = false, broadcast = false)))
        localOff.update(remote(MarketMcSettings(mcLuckPerms = true, mcBroadcast = true)))
        assertFalse(localOff.deliverySettings.luckPermsEnabled)
        assertFalse(localOff.deliverySettings.broadcastEnabled)
    }

    @Test
    fun `disabled admin sub-commands come from the panel list`() {
        val e = EffectiveConfig(LocalConfig())
        assertTrue(e.adminCommandDisabled("give-credits"), "closed until the panel settings are known")
        e.update(remote(MarketMcSettings(mcDisabledAdminCommands = listOf("give-credits", "purchases"))))
        assertTrue(e.adminCommandDisabled("give-credits"))
        assertTrue(e.adminCommandDisabled("PURCHASES"))
        assertFalse(e.adminCommandDisabled("grant-product"))
    }
}
