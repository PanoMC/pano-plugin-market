package com.panomc.plugins.market.support

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.model.Path
import com.panomc.platform.model.PanelApi
import com.panomc.plugins.market.permission.MarketNode
import com.panomc.plugins.market.routes.base.MarketPanelApi
import com.panomc.plugins.market.routes.base.RouteAuth
import org.springframework.cglib.proxy.Enhancer
import org.springframework.cglib.proxy.Factory
import org.springframework.cglib.proxy.MethodInterceptor
import org.springframework.objenesis.ObjenesisStd
import java.io.File
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.jar.JarFile
import com.panomc.plugins.market.util.MarketPaths

/**
 * The panel endpoint matrix of 11 section 14.3 as the tests see it (MK-160, 17 section 15): the checked-in `permission-matrix.tsv` on one side,
 * the routes the code actually declares on the other, found by reflection over `routes.panel` (no host, no router: a route class is built with
 * placeholders for its constructor parameters, which is enough because every route reads its collaborators lazily).
 *
 * TSV columns: `method`, `path` (below the market panel API, `MarketPaths.PANEL_ROOT`), `auth` (`P:<node>[,<node>]`, `P:ANY` = any market node, the umbrella is implied),
 * `state` (`LIVE` = a route class exists, `PENDING` = the slice in `slice` still adds it), `slice`, `note`. The E2E matrix test (E2E-10) reads the same
 * file and calls every LIVE row with a user that holds one node.
 */
object PanelEndpointMatrix {
    /** Where the panel routes are mounted; the routes themselves declare paths relative to it (04 section 2). */
    const val API_PREFIX = MarketPaths.PANEL_ROOT
    const val PANEL_PACKAGE = "com.panomc.plugins.market.routes.panel"
    private const val TSV_RESOURCE = "/permission-matrix.tsv"

    data class Row(val method: String, val path: String, val auth: String, val state: String, val slice: String, val note: String) {
        val key: String get() = "$method $path"
        val nodes: Set<MarketNode> get() = nodesOf(auth)
        val live: Boolean get() = state == "LIVE"
    }

    class CodeRoute(val method: String, val path: String, val auth: String, val type: Class<*>) {
        val key: String get() = "$method $path"
    }

    /** `P:CAT,PAY` -> {CATALOG, PAYMENTS}; `P:ANY` -> empty (= any market node). */
    fun nodesOf(auth: String): Set<MarketNode> {
        require(auth.startsWith("P:")) { "not a panel auth: $auth" }
        val body = auth.removePrefix("P:")

        if (body == "ANY") return emptySet()

        return body.split(",").map { short -> MarketNode.values().firstOrNull { it.shortName == short } ?: throw IllegalArgumentException("unknown node '$short' in $auth") }.toSet()
    }

    /** Does a caller who holds exactly [held] (and the umbrella when [umbrella]) pass the node check of a route with [auth]? Same rule as `MarketPermissions.require`. */
    fun allows(held: Set<MarketNode>, umbrella: Boolean, auth: String): Boolean {
        if (umbrella) return true

        val needed = nodesOf(auth)

        return if (needed.isEmpty()) held.isNotEmpty() else held.any { it in needed }
    }

    fun readTsv(): List<Row> {
        val text = PanelEndpointMatrix::class.java.getResourceAsStream(TSV_RESOURCE)?.bufferedReader()?.readText() ?: error("$TSV_RESOURCE is missing from the test resources")

        return text.lineSequence().filter { it.isNotBlank() && !it.startsWith("#") }.map { line ->
            val cells = line.split("\t")
            require(cells.size in 5..6) { "permission-matrix.tsv: expected 5 or 6 columns, got ${cells.size}: $line" }
            Row(cells[0], cells[1], cells[2], cells[3], cells[4], cells.getOrElse(5) { "" })
        }.toList()
    }

    /** Every top-level class below [pkg] (nested and synthetic classes skipped), loaded without running static initialisers. */
    fun classesUnder(pkg: String, anchor: Class<*> = MarketPanelApi::class.java): List<Class<*>> {
        val prefix = pkg.replace('.', '/') + "/"
        val location = anchor.protectionDomain.codeSource.location
        val names = sortedSetOf<String>()

        val file = File(location.toURI())
        if (file.isDirectory) {
            val root = File(file, prefix)
            if (root.isDirectory) root.walkTopDown().filter { it.isFile && it.name.endsWith(".class") }.forEach { names += file.toPath().relativize(it.toPath()).toString().replace(File.separatorChar, '/') }
        } else {
            JarFile(file).use { jar -> jar.entries().asSequence().map { it.name }.filter { it.startsWith(prefix) && it.endsWith(".class") }.forEach { names += it } }
        }

        return names.filter { !it.substringAfterLast('/').contains('$') }
            .map { Class.forName(it.removeSuffix(".class").replace('/', '.'), false, anchor.classLoader) }
    }

    /** Every class below `routes.panel` that is a [PanelApi] or carries `@Endpoint` (abstract bases included). */
    fun panelRouteClasses(): List<Class<*>> =
        classesUnder(PANEL_PACKAGE).filter { PanelApi::class.java.isAssignableFrom(it) || it.isAnnotationPresent(Endpoint::class.java) }

    /** The routes the code declares: one entry per (class, path). Fails with the class name when a route cannot be built. */
    fun codeRoutes(): List<CodeRoute> = panelRouteClasses().filter { !Modifier.isAbstract(it.modifiers) }.flatMap { type ->
        val route = instantiate(type)
        @Suppress("UNCHECKED_CAST")
        val paths = type.getMethod("getPaths").invoke(route) as List<Path>

        paths.map { path ->
            require(path.url.startsWith("/") && !path.url.startsWith("/api") && !path.url.startsWith("/panel")) { "${type.simpleName}: ${path.url} must be relative to the panel namespace" }
            CodeRoute(path.routeType.name, path.url, RouteAuth.describe(route), type)
        }
    }

    /** An instance of [type] built through its constructor with placeholders for the parameters (no collaborator is touched). */
    fun instantiate(type: Class<*>): Any {
        val constructor = type.constructors.minByOrNull { it.parameterCount } ?: error("${type.name} has no public constructor")
        val args = constructor.parameterTypes.map { placeholder(it) }.toTypedArray()

        return try {
            constructor.newInstance(*args)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw IllegalStateException("${type.simpleName} could not be built with placeholder arguments: ${e.targetException}", e.targetException)
        }
    }

    private val objenesis = ObjenesisStd()

    private fun default(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Long.TYPE -> 0L
        java.lang.Integer.TYPE -> 0
        java.lang.Double.TYPE -> 0.0
        java.lang.Float.TYPE -> 0f
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Character.TYPE -> 0.toChar()
        else -> null
    }

    private fun placeholder(type: Class<*>): Any? = when {
        type.isPrimitive -> default(type)
        type == String::class.java -> ""
        type.isInterface -> Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ -> default(method.returnType) }
        Modifier.isFinal(type.modifiers) -> objenesis.newInstance(type)
        else -> {
            val enhancer = Enhancer()
            enhancer.setSuperclass(type)
            enhancer.setCallbackType(MethodInterceptor::class.java)
            val generated = enhancer.createClass()
            val instance = objenesis.newInstance(generated)
            (instance as Factory).setCallbacks(arrayOf(MethodInterceptor { _, method, _, _ -> default(method.returnType) }))
            instance
        }
    }
}
