package com.panomc.plugins.market.permission

import com.panomc.platform.annotation.PermissionDefinition
import com.panomc.platform.auth.PanelPermission
import com.panomc.platform.auth.Permission
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** The eight nodes of 04 section 9: classes, keys, node strings, icons (11 section 14.1) and the three core locale fragments. */
class PermissionNodesTest {
    /** class -> (key, node suffix, icon) */
    private val expected = listOf(
        Triple(ManageMarketPermission::class.java, "MANAGE_MARKET", "manage.market") to "fa-store",
        Triple(ManageMarketCatalogPermission::class.java, "MANAGE_MARKET_CATALOG", "manage.market.catalog") to "fa-boxes-stacked",
        Triple(ViewMarketOrdersPermission::class.java, "VIEW_MARKET_ORDERS", "view.market.orders") to "fa-receipt",
        Triple(ManageMarketOrdersPermission::class.java, "MANAGE_MARKET_ORDERS", "manage.market.orders") to "fa-truck-fast",
        Triple(ManageMarketPaymentsPermission::class.java, "MANAGE_MARKET_PAYMENTS", "manage.market.payments") to "fa-money-bill-transfer",
        Triple(ManageMarketDiscountsPermission::class.java, "MANAGE_MARKET_DISCOUNTS", "manage.market.discounts") to "fa-tags",
        Triple(ManageMarketSettingsPermission::class.java, "MANAGE_MARKET_SETTINGS", "manage.market.settings") to "fa-gear",
        Triple(ViewMarketStatsPermission::class.java, "VIEW_MARKET_STATS", "view.market.stats") to "fa-chart-line"
    )

    private fun instance(type: Class<out Permission>): Permission = type.getDeclaredConstructor().newInstance()

    /** The host sets `source` (the plugin id) when it registers the permission; do the same through the field. */
    private fun withSource(permission: Permission): Permission {
        val field = Permission::class.java.getDeclaredField("source")
        field.isAccessible = true
        field.set(permission, "pano-plugin-market")

        return permission
    }

    @Test
    fun `there are exactly eight nodes and the enum covers the seven granular ones`() {
        assertEquals(8, expected.size)
        assertEquals(8, MarketNode.allPermissionClasses.size)
        assertEquals(expected.map { it.first.first }.toSet(), MarketNode.allPermissionClasses.toSet())
        assertEquals(7, MarketNode.values().size)
        assertEquals(
            MarketNode.values().map { it.permission.javaClass }.toSet(),
            MarketNode.allPermissionClasses.toSet() - ManageMarketPermission::class.java
        )
    }

    @Test
    fun `each node has the documented key, node string and icon and is a registered permission definition`() {
        for ((spec, icon) in expected) {
            val (type, key, suffix) = spec
            val permission = instance(type)

            assertTrue(permission is PanelPermission, "${type.simpleName} is a panel permission")
            assertTrue(type.isAnnotationPresent(PermissionDefinition::class.java), "${type.simpleName} is annotated")
            assertEquals(key, permission.key)
            assertEquals(icon, permission.iconName, "icon of $key")
            assertEquals("pano.plugin.pano-plugin-market.$suffix", withSource(instance(type)).toString())
            assertEquals("com.panomc.plugins.market.permission", type.`package`.name)
        }
    }

    @Test
    fun `keys and nodes are unique and the short names match the endpoint matrix`() {
        assertEquals(8, expected.map { it.first.second }.toSet().size)
        assertEquals(8, expected.map { it.first.third }.toSet().size)
        assertEquals(
            mapOf(
                "CAT" to ManageMarketCatalogPermission::class.java,
                "OV" to ViewMarketOrdersPermission::class.java,
                "OM" to ManageMarketOrdersPermission::class.java,
                "PAY" to ManageMarketPaymentsPermission::class.java,
                "DISC" to ManageMarketDiscountsPermission::class.java,
                "SET" to ManageMarketSettingsPermission::class.java,
                "STATS" to ViewMarketStatsPermission::class.java
            ),
            MarketNode.values().associate { it.shortName to it.permission.javaClass }
        )
    }

    @Test
    fun `an accepted set is the nodes plus the umbrella and no nodes means any market node`() {
        val one = MarketPermissions.accepted(setOf(MarketNode.PAYMENTS)).map { it.javaClass }
        assertEquals(listOf(ManageMarketPaymentsPermission::class.java, ManageMarketPermission::class.java), one)

        val two = MarketPermissions.accepted(setOf(MarketNode.SETTINGS, MarketNode.CATALOG)).map { it.javaClass }
        assertEquals(
            listOf(
                ManageMarketCatalogPermission::class.java,
                ManageMarketSettingsPermission::class.java,
                ManageMarketPermission::class.java
            ),
            two,
            "granular nodes in enum order, the umbrella last"
        )

        val any = MarketPermissions.accepted(emptySet()).map { it.javaClass }
        assertEquals(MarketNode.allPermissionClasses.toSet(), any.toSet())
        assertEquals(8, any.size)
        assertEquals(ManageMarketPermission::class.java, any.last())
    }

    @Test
    fun `every node has a title and a description in the three core locale fragments`() {
        for (lang in listOf("tr", "en-US", "ru")) {
            val file = File("src/locales/core/$lang.json")
            assertTrue(file.isFile, "${file.path} exists (tests run in the plugin directory)")

            val permissions = JsonObject(file.readText(Charsets.UTF_8)).getJsonObject("permissions")

            for ((spec, _) in expected) {
                val node = permissions.getJsonObject(spec.second) ?: error("$lang: permissions.${spec.second} missing")
                val title = node.getString("title")
                val description = node.getString("description")

                assertTrue(!title.isNullOrBlank(), "$lang: ${spec.second} title")
                assertTrue(!description.isNullOrBlank(), "$lang: ${spec.second} description")
                assertTrue(description.length > title.length, "$lang: ${spec.second} description says more than the title")
            }

            val titles = expected.map { permissions.getJsonObject(it.first.second).getString("title") }
            assertEquals(8, titles.toSet().size, "$lang: titles are distinct")
        }

        val tr = JsonObject(File("src/locales/core/tr.json").readText(Charsets.UTF_8)).getJsonObject("permissions")
        val en = JsonObject(File("src/locales/core/en-US.json").readText(Charsets.UTF_8)).getJsonObject("permissions")
        assertNotEquals(
            tr.getJsonObject("MANAGE_MARKET_CATALOG").getString("title"),
            en.getJsonObject("MANAGE_MARKET_CATALOG").getString("title"),
            "tr is translated, not copied from en-US"
        )
    }
}
