package com.panomc.plugins.market.permission

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.model.PanelApi
import com.panomc.plugins.market.routes.base.MarketApi
import com.panomc.plugins.market.routes.api.payment.MarketInboundApi
import com.panomc.plugins.market.routes.base.MarketPanelApi
import com.panomc.plugins.market.routes.base.MarketPublicMutationApi
import com.panomc.plugins.market.routes.base.MarketUserApi
import com.panomc.plugins.market.support.PanelEndpointMatrix
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.lang.reflect.Modifier
import com.panomc.platform.route.Mount
import com.panomc.platform.route.Namespace

/**
 * 11 section 14.2 and 19.10 item 1 (MK-160): the structure that makes the permission check impossible to forget. Every panel route extends
 * [MarketPanelApi], whose `handle` is final and runs the node check first; no route re-implements the check, bypasses the base or sits under another
 * platform base class, and the nodes a route declares are exactly the ones of the checked-in matrix.
 */
class PermissionReflectionTest {
    private val classes = PanelEndpointMatrix.panelRouteClasses()

    private val concrete = classes.filter { !Modifier.isAbstract(it.modifiers) }

    @Test
    fun `there are panel route classes to look at`() {
        assertTrue(concrete.size >= 100, "found ${concrete.size} concrete panel route classes")
    }

    @Test
    fun `every PanelApi under routes panel extends MarketPanelApi`() {
        val offenders = classes.filter { PanelApi::class.java.isAssignableFrom(it) && !MarketPanelApi::class.java.isAssignableFrom(it) }.map { it.name }

        assertTrue(offenders.isEmpty(), "extends the platform PanelApi directly (only the node check of MarketPanelApi is allowed): $offenders")
    }

    @Test
    fun `every Endpoint under routes panel is a MarketPanelApi`() {
        val offenders = classes.filter { it.isAnnotationPresent(Endpoint::class.java) && !MarketPanelApi::class.java.isAssignableFrom(it) }.map { it.name }

        assertTrue(offenders.isEmpty(), "an @Endpoint under routes.panel that is not a MarketPanelApi (no node check): $offenders")
    }

    @Test
    fun `handle is final in the base and no route declares it again`() {
        val handle = MarketPanelApi::class.java.declaredMethods.single { it.name == "handle" && !it.isSynthetic && !it.isBridge }

        assertTrue(Modifier.isFinal(handle.modifiers), "MarketPanelApi.handle must be final: it is the first-line permission check")

        val offenders = classes.filter { type -> type.declaredMethods.any { it.name == "handle" && it.parameterCount == 2 } }.map { it.name }

        assertTrue(offenders.isEmpty(), "re-declares handle(context, continuation): $offenders")
    }

    @Test
    fun `no panel route authorises by itself`() {
        val root = File("src/main/kotlin/com/panomc/plugins/market/routes/panel")

        assertTrue(root.isDirectory, "run from the project directory: ${root.absolutePath}")

        val offenders = root.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }
            .filter { file -> file.readLines().any { it.contains("requirePermission(") || it.contains("requireAnyPermission(") } }
            .map { it.name }.toList()

        assertTrue(offenders.isEmpty(), "the node check belongs to MarketPanelApi, these call the platform check themselves: $offenders")
    }

    @Test
    fun `every concrete route declares its nodes and a path under the panel prefix`() {
        for (type in concrete) {
            val route = PanelEndpointMatrix.instantiate(type) as MarketPanelApi

            route.nodes // a route whose nodes were never initialised throws here
            assertTrue(route.paths.isNotEmpty(), "${type.simpleName} declares a path")

            assertEquals(Mount.API, route.mount, "${type.simpleName} is mounted under /api/v1")
            assertEquals(Namespace.PANEL, route.namespace, "${type.simpleName} is a panel route")

            for (path in route.paths) {
                assertTrue(path.url.startsWith("/") && !path.url.startsWith("/api") && !path.url.startsWith("/panel"), "${type.simpleName}: ${path.url} is relative to the panel namespace")
            }
        }
    }

    @Test
    fun `the nodes of every route equal the nodes of its TSV row`() {
        val rows = PanelEndpointMatrix.readTsv().associateBy { it.key }

        for (type in concrete) {
            val route = PanelEndpointMatrix.instantiate(type) as MarketPanelApi

            for (path in route.paths) {
                val key = "${path.routeType.name} ${path.url}"
                val row = rows[key] ?: error("$key (${type.simpleName}) has no row in permission-matrix.tsv")

                assertEquals(row.nodes, route.nodes, "$key (${type.simpleName}): declared nodes against the TSV")
            }
        }
    }

    @Test
    fun `the accepted permissions of every distinct node set are those nodes plus the umbrella`() {
        for (auth in PanelEndpointMatrix.readTsv().map { it.auth }.toSet()) {
            val nodes = PanelEndpointMatrix.nodesOf(auth)
            val accepted: List<Class<*>> = MarketPermissions.accepted(nodes).map { it.javaClass }
            val expected: List<Class<*>> = (if (nodes.isEmpty()) MarketNode.values().toList() else nodes.toList()).map { it.permission.javaClass } + ManageMarketPermission::class.java

            assertEquals(expected.toSet(), accepted.toSet(), auth)
            assertTrue(ManageMarketPermission::class.java in accepted, "$auth accepts the umbrella role")
        }
    }

    @Test
    fun `every market route outside the panel sits under one of the market bases`() {
        val api = PanelEndpointMatrix.classesUnder("com.panomc.plugins.market.routes.api") + PanelEndpointMatrix.classesUnder("com.panomc.plugins.market.routes.user")
        val bases = listOf(MarketApi::class.java, MarketUserApi::class.java, MarketPublicMutationApi::class.java, MarketInboundApi::class.java)
        val offenders = api.filter { it.isAnnotationPresent(Endpoint::class.java) && bases.none { base -> base.isAssignableFrom(it) } }.map { it.name }

        assertTrue(offenders.isEmpty(), "a route without a market base class (no runtime gate, no CSRF proof): $offenders")
    }
}
