package com.panomc.plugins.market.e2e

import com.panomc.plugins.market.core.abuse.PiiMask
import com.panomc.plugins.market.e2e.support.E2eClient
import com.panomc.plugins.market.e2e.support.E2eResponse
import com.panomc.plugins.market.e2e.support.E2eSession
import com.panomc.plugins.market.e2e.support.E2eTestBase
import com.panomc.plugins.market.permission.MarketNode
import com.panomc.plugins.market.support.Await
import com.panomc.plugins.market.support.PanelEndpointMatrix
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import com.panomc.plugins.market.util.MarketPaths

/**
 * The API contract on a real instance (17 section 9.9, 11 section 14.3, 04 section 12): the "route tests". API-01 to API-10.
 *
 * What is asserted, in the words of the host:
 * - API-01 / API-02: a user holding exactly one node (and `ACCESS_PANEL`) calls **every** LIVE row of `permission-matrix.tsv`; `PanelEndpointMatrix.allows`
 *   decides whether the node grants it. Denied is 403 `NO_PERMISSION`, granted is anything but 401 / 403 `NO_PERMISSION` / 5xx. The route list that decides
 *   which rows exist is the one the plugin reports itself (`GET /health` `routes[]`), so a registered panel route missing from the TSV fails here.
 * - Order of the host's handlers: body parsing, then Vert.x schema validation, then `onBeforeHandle` (login, CSRF, panel access), then the route's own
 *   permission check. A request whose body fails the schema is answered 400 **before** any permission or login check, so the matrix sends a body that
 *   passes the schema of every route ([MatrixBodies]) and the permission check is what answers.
 * - Granted calls use ids that do not exist (999999999), so they end in 404 / 400 and change nothing; the few rows that would create something are
 *   cleaned up through the admin at the end, and the rows with an outside effect (legal text, rate refresh, test mail) are only called as denied.
 * - API-07 compares the answers of the 53 handlers that existed at commit `c58d57a` with `golden/api/v1-keysets.json` (see its `how`): every recorded key
 *   must still be there, additive keys are allowed.
 *
 * Methods run in name order, so API-10 (it starts and stops a second instance) is last.
 */
@TestMethodOrder(MethodOrderer.MethodName::class)
class ApiContractE2E : E2eTestBase() {
    override val tag = "api"

    private val unique = AtomicInteger()
    private val run = System.currentTimeMillis().toString(36)

    private val panelPrefix = PanelEndpointMatrix.API_PREFIX

    // --- actors --------------------------------------------------------------------------------------------------------

    private class PanelUser(val label: String, val client: E2eClient, val held: Set<MarketNode>, val umbrella: Boolean)

    private var users: List<PanelUser>? = null

    /**
     * One user per node, one holding the umbrella, one holding `ACCESS_PANEL` only, one holding SET but no `ACCESS_PANEL`. Built once (the whole
     * permission grid is one snapshot, saved in one write).
     */
    private fun panelUsers(): List<PanelUser> = users ?: synchronized(this) {
        users ?: run {
            val specs = MarketNode.values().map { Triple("node-${it.shortName}", setOf(it), false) } +
                Triple("umbrella", emptySet<MarketNode>(), true) +
                Triple("none", emptySet<MarketNode>(), false) +
                Triple("nopanel", setOf(MarketNode.SETTINGS), false)
            val buyers = specs.map { (label, _, _) -> label to buyer(canPay = false) }

            val snapshot = admin.get("/api/v1/panel/permission/snapshot").ok().obj()
            val nodes = (snapshot.getJsonArray("nodes") ?: JsonArray()).map { it as JsonObject }.toMutableList()

            fun grant(userId: Long, node: String) {
                nodes.add(JsonObject().put("holderType", "USER").put("holderId", userId).put("node", node).put("active", true).put("context", JsonObject()))
            }

            specs.forEachIndexed { i, (label, held, umbrella) ->
                val userId = buyers[i].second.userId

                if (label != "nopanel") grant(userId, PANEL_ACCESS)
                held.forEach { grant(userId, NODE_PREFIX + NODE_SUFFIX.getValue(it)) }
                if (umbrella) grant(userId, NODE_PREFIX + "manage.market")
            }

            admin.post(
                "/api/v1/panel/permission/snapshot",
                JsonObject().put("groups", snapshot.getJsonArray("groups") ?: JsonArray()).put("tracks", snapshot.getJsonArray("tracks") ?: JsonArray()).put("nodes", JsonArray(nodes))
            ).ok()

            specs.mapIndexed { i, (label, held, umbrella) ->
                val client = buyers[i].second.client

                // a panel session for everybody who may enter the panel (the platform refuses the panel login of the rest)
                // (a login, unlike the registration, needs a verified e-mail; the harness may write that one column)
                db.verifyEmail(buyers[i].second.userId)

                if (label != "nopanel") client.login(buyers[i].second.username, E2eSession.PASSWORD, panel = true).also { assertEquals(200, it.status, "$label panel login: ${it.error}") }

                PanelUser(label, client, held, umbrella)
            }.also { users = it }
        }
    }

    private fun user(label: String): PanelUser = panelUsers().first { it.label == label }

    // --- the route list ------------------------------------------------------------------------------------------------

    /**
     * The routes the plugin reports. `GET /health` lists the path each class declares (relative to its namespace, 04 section 2); the tests address the
     * routes by their full path, so the panel namespace prefix is put in front of a panel class and the site prefix in front of every other one.
     */
    private fun healthRoutes(): List<JsonObject> = admin.get("${MarketPaths.PANEL_ROOT}/health").ok().obj().getJsonArray("routes").map { it as JsonObject }.map { route ->
        val panel = route.getString("auth").let { it.startsWith("P:") || it == "LEGACY-PANEL" }

        JsonObject().put("method", route.getString("method")).put("auth", route.getString("auth")).put("path", (if (panel) MarketPaths.PANEL_ROOT else MarketPaths.SITE_ROOT) + route.getString("path"))
    }

    private fun liveRows(): List<PanelEndpointMatrix.Row> = PanelEndpointMatrix.readTsv().filter { it.live }

    /** `/products/:id` -> `/products/999999999`; names the placeholders by what the route expects. */
    private fun concrete(path: String): String {
        val withValues = Regex(":(\\w+)").replace(path) { m ->
            when (m.groupValues[1]) {
                "fileName" -> "none.png"
                "username" -> "nobody"
                "actionId" -> "none"
                "publicId" -> "XXXXXXXXXXXXXXXX"
                "attemptToken", "installToken", "token" -> "0".repeat(40)
                else -> "999999999"
            }
        }

        // the provider id of these two families is a slug, not a number
        return if (withValues.startsWith("/payment-methods/999999999") || withValues.startsWith("/shipping/carriers/999999999")) withValues.replace("999999999", "nonexistent") else withValues
    }

    /** A body that passes the schema of every row but names nothing that exists. `null` = no body (GET). Multipart rows are a field map. */
    private fun matrixBody(row: PanelEndpointMatrix.Row, n: String): Any? {
        if (row.method == "GET") return null

        val form = mapOf("name" to "api-matrix-$n", "slug" to "api-matrix-$n", "price" to "1.00", "status" to "INACTIVE")

        return when (row.key) {
            "POST /categories", "PUT /categories/:id", "POST /products", "PUT /products/:id" -> form
            "POST /categories/sort" -> JsonObject().put("id", MISSING_ID).put("position", "BEFORE").put("targetId", MISSING_ID - 1)
            "POST /products/:id/stock" -> JsonObject().put("mode", "SET").put("value", 1)
            "POST /comparisons", "PUT /comparisons/:id" -> JsonObject().put("name", "api-matrix-$n")
            "POST /creator-codes/:id/payouts" -> JsonObject().put("amount", 1).put("method", "CREDIT")
            "PUT /orders/:id/status" -> JsonObject().put("status", "COMPLETED")
            "PUT /orders/:id/exchange-rate" -> JsonObject().put("exchangeRate", 1.5)
            "POST /orders", "POST /orders/quote" -> JsonObject().put("playerUsername", "nobody").put("items", JsonArray().add(JsonObject().put("productId", MISSING_ID).put("quantity", 1)))
            "POST /orders/:id/review" -> JsonObject().put("decision", "ACCEPT")
            "POST /orders/:id/bank-transfer" -> JsonObject().put("decision", "APPROVE")
            "PUT /disputes/:disputeId" -> JsonObject().put("status", "WON")
            "POST /credits/accounts/:userId/grant", "POST /credits/accounts/:userId/revoke" -> JsonObject().put("amount", 1).put("note", "api matrix")
            "POST /orders/:id/mails/resend" -> JsonObject().put("kind", "ORDER_CONFIRMATION")
            "PUT /orders/:id/note" -> JsonObject().put("note", "api matrix")
            "POST /settings/legal" -> JsonObject().put("locale", "en-US").put("title", "api matrix").put("content", "<p>api matrix</p>")
            "POST /payment-methods/sort" -> JsonObject().put("ids", JsonArray().add("nonexistent"))
            "POST /shipping/zones/sort", "POST /shipping/methods/sort" -> JsonObject().put("ids", JsonArray().add(MISSING_ID))
            "POST /payment-methods/:id/toggle", "POST /shipping/carriers/:id/toggle" -> JsonObject().put("enabled", true)
            "POST /payment-methods/:id/reveal", "POST /shipping/carriers/:id/reveal" -> JsonObject().put("password", "api matrix")
            "PUT /settings/invoice-sequence" -> JsonObject().put("series", "E2EAPI").put("nextNumber", 1)
            else -> JsonObject()
        }
    }

    /**
     * Rows a granted caller must not really execute: the legal text would become the active version of the instance, the two refreshes and the test
     * mail reach the outside world. They are called as **denied** by every other user, which is the gate that matters; the granted call is skipped.
     */
    private val grantedSkipped = setOf("POST /settings/legal", "POST /settings/exchange-rate/refresh", "POST /settings/currencies/refresh", "POST /settings/mail/test")

    /** Rows that create something when a caller who passes the gate sends [matrixBody]; the id is collected and deleted at the end. */
    private val creators = mapOf("POST /categories" to "/categories", "POST /products" to "/products", "POST /comparisons" to "/comparisons")

    private fun call(client: E2eClient, row: PanelEndpointMatrix.Row, n: String, csrf: Boolean = true): E2eResponse {
        val path = panelPrefix + concrete(row.path)
        val body = matrixBody(row, n)
        val headers = if (row.method == "GET") emptyMap() else mapOf("Idempotency-Key" to idempotencyKey())

        return when (body) {
            null -> client.request(row.method, path, null, headers, csrf = csrf, log = false)
            is JsonObject -> client.request(row.method, path, body, headers, csrf = csrf, log = false)
            else -> {
                @Suppress("UNCHECKED_CAST")
                val fields = body as Map<String, String>
                multipart(client, row.method, path, fields, csrf)
            }
        }
    }

    /** The harness client's multipart helper always sends the CSRF token; the CSRF scenarios need to leave it out. */
    private fun multipart(client: E2eClient, method: String, path: String, fields: Map<String, String>, csrf: Boolean = true): E2eResponse {
        val boundary = "----e2eapi" + System.nanoTime()
        val out = java.io.ByteArrayOutputStream()

        fields.forEach { (name, value) ->
            out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray(Charsets.UTF_8))
            out.write(value.toByteArray(Charsets.UTF_8))
            out.write("\r\n".toByteArray(Charsets.UTF_8))
        }
        out.write("--$boundary--\r\n".toByteArray(Charsets.UTF_8))

        return client.request(method, path, out.toByteArray(), mapOf("Content-Type" to "multipart/form-data; boundary=$boundary"), csrf = csrf, log = false)
    }

    private fun isNoPermission(answer: E2eResponse) = answer.status == 403 && answer.error == "NO_PERMISSION"

    private val matrixCleanup = ArrayList<String>()

    private fun collect(row: PanelEndpointMatrix.Row, answer: E2eResponse) {
        val base = creators[row.key] ?: return

        if (answer.status == 200) answer.json?.getLong("id")?.let { matrixCleanup += "$panelPrefix$base/$it" }
    }

    private fun cleanUpMatrix() {
        matrixCleanup.forEach { admin.delete(it) }
        matrixCleanup.clear()
    }

    // --- API-01 --------------------------------------------------------------------------------------------------------

    @Test
    fun `API-01 permission matrix - a user with one node calls every panel endpoint, the node decides between answered and 403 NO_PERMISSION, anonymous is 401`() {
        val rows = liveRows()
        val routes = healthRoutes()

        // the plugin's own route list is the truth about which panel routes exist: both directions, with the auth class
        val registered = routes.filter { it.getString("path").startsWith("$panelPrefix/") }.associate { "${it.getString("method")} ${it.getString("path").removePrefix(panelPrefix)}" to it.getString("auth") }

        assertEquals(rows.map { it.key }.toSortedSet(), registered.keys.toSortedSet(), "the panel routes of /health and the LIVE rows of permission-matrix.tsv are the same set")
        rows.forEach { assertEquals(it.auth, registered[it.key], "auth class of ${it.key}") }
        // 150 before MK-15 moved the webhook endpoints to core; the set equality above is the real gate
        assertTrue(rows.size >= 146, "the matrix has ${rows.size} LIVE rows")

        // the settings as the answer shows them, without the invoice sequence list: the one granted call that writes (PUT /settings/invoice-sequence for the
        // series E2EAPI, which nothing issues from) adds a row there
        fun settingsView(): JsonObject = admin.get("${MarketPaths.PANEL_ROOT}/settings").ok().obj().also { it.remove("invoiceSequences") }

        val settingsBefore = settingsView().encode()
        val failures = ArrayList<String>()
        var calls = 0
        var granted = 0
        var denied = 0

        try {
            val nodeUsers = MarketNode.values().map { user("node-${it.shortName}") }

            for (row in rows) {
                val n = "${run}${unique.incrementAndGet()}"

                for (u in nodeUsers) {
                    val allowed = PanelEndpointMatrix.allows(u.held, u.umbrella, row.auth)

                    if (allowed && row.key in grantedSkipped) continue

                    val answer = call(u.client, row, n)

                    calls++

                    if (allowed) {
                        granted++
                        collect(row, answer)
                        if (isNoPermission(answer) || answer.status == 401 || answer.status >= 500) failures += "${u.label} ${row.key} should be granted (${row.auth}) but got ${answer.status} ${answer.error}"
                    } else {
                        denied++
                        if (!isNoPermission(answer)) failures += "${u.label} ${row.key} should be 403 NO_PERMISSION (${row.auth}) but got ${answer.status} ${answer.error}"
                    }
                }
            }
        } finally {
            cleanUpMatrix()
        }

        // OV alone never changes state: every non-GET answers 403 to the OV user
        val viewer = user("node-OV")
        val mutations = rows.filter { it.method != "GET" }

        assertTrue(mutations.isNotEmpty())
        for (row in mutations) {
            assertFalse(PanelEndpointMatrix.allows(viewer.held, false, row.auth) && !row.auth.contains("OV"), "the matrix lets OV alone change state on ${row.key}")
            if (!PanelEndpointMatrix.allows(viewer.held, false, row.auth)) {
                val answer = call(viewer.client, row, "${run}ov${unique.incrementAndGet()}")

                if (!isNoPermission(answer)) failures += "OV alone on ${row.key} should be 403 NO_PERMISSION but got ${answer.status} ${answer.error}"
            }
        }

        // a panel user with no market node at all: 403 on every row
        val none = user("none")

        for (row in rows) {
            val answer = call(none.client, row, "${run}${unique.incrementAndGet()}")

            if (!isNoPermission(answer)) failures += "no node ${row.key}: ${answer.status} ${answer.error}"
        }

        // a user who holds SET but not ACCESS_PANEL cannot use the panel API (403), and a market node is not a substitute for it
        val nopanel = user("nopanel")

        for (row in rows) {
            val answer = call(nopanel.client, row, "${run}${unique.incrementAndGet()}")

            if (answer.status != 403) failures += "no ACCESS_PANEL ${row.key}: ${answer.status} ${answer.error}"
        }

        // anonymous: 401 NOT_LOGGED_IN on every row
        val anonymous = visitor("anonymous")

        for (row in rows) {
            val answer = call(anonymous, row, "${run}${unique.incrementAndGet()}")

            if (answer.status != 401 || answer.error != "NOT_LOGGED_IN") failures += "anonymous ${row.key}: ${answer.status} ${answer.error}"
        }

        println("API-01 rows=${rows.size} calls(node users)=$calls granted=$granted denied=$denied skippedAsGranted=${grantedSkipped.size}")
        assertTrue(failures.isEmpty(), "API-01 failures (${failures.size}):\n" + failures.take(60).joinToString("\n"))

        // the calls of the granted users left the instance as it was
        assertEquals(settingsBefore, settingsView().encode(), "the settings are unchanged after the matrix")

        val sequences = admin.get("${MarketPaths.PANEL_ROOT}/settings").ok().obj().getJsonArray("invoiceSequences").map { it as JsonObject }.filter { it.getString("series") == "E2EAPI" }

        assertTrue(sequences.all { it.getLong("lastNumber") == 0L }, "the E2EAPI series issued nothing: $sequences")
    }

    // --- API-02 --------------------------------------------------------------------------------------------------------

    @Test
    fun `API-02 umbrella permission - a role with manage market only is allowed on every endpoint`() {
        val umbrella = user("umbrella")
        val rows = liveRows()
        val failures = ArrayList<String>()

        try {
            for (row in rows) {
                if (row.key in grantedSkipped) continue

                val answer = call(umbrella.client, row, "${run}u${unique.incrementAndGet()}")

                collect(row, answer)
                if (isNoPermission(answer) || answer.status == 401 || answer.status >= 500) failures += "umbrella ${row.key} (${row.auth}): ${answer.status} ${answer.error}"
            }
        } finally {
            cleanUpMatrix()
        }

        assertTrue(failures.isEmpty(), "API-02 failures:\n" + failures.joinToString("\n"))

        // the skipped rows are still reachable by the umbrella: they are only not executed; the denied side proves their gate in API-01
        assertEquals(setOf("P:SET"), liveRows().filter { it.key in grantedSkipped }.map { it.auth }.toSet())
    }

    // --- API-03 --------------------------------------------------------------------------------------------------------

    @Test
    fun `API-03 CSRF - every USER and PUB-M mutation and every panel mutation with a session cookie and no token is 403 INVALID_CSRF_TOKEN, inbound routes need none`() {
        val routes = healthRoutes()
        val buyer = buyer()
        val failures = ArrayList<String>()

        // the buyer sends its session cookie and no X-CSRF-Token
        val buyerRoutes = routes.filter { it.getString("auth") in setOf("USER", "PUB-M") && it.getString("method") != "GET" }

        assertTrue(buyerRoutes.size >= 15, "enumerated ${buyerRoutes.size} USER / PUB-M mutating routes")

        for (route in buyerRoutes) {
            val path = concrete(route.getString("path"))
            val answer = buyer.client.request(route.getString("method"), path, JsonObject(), mapOf("Idempotency-Key" to idempotencyKey()), csrf = false, log = false)

            if (answer.status != 403 || answer.error != "INVALID_CSRF_TOKEN") failures += "${route.getString("method")} ${route.getString("path")} (${route.getString("auth")}): ${answer.status} ${answer.error}"
        }

        // with the token the same call gets past the CSRF gate (it ends in whatever the route says, never INVALID_CSRF_TOKEN)
        for (route in buyerRoutes) {
            val answer = buyer.client.request(route.getString("method"), concrete(route.getString("path")), JsonObject(), mapOf("Idempotency-Key" to idempotencyKey()), log = false)

            if (answer.error == "INVALID_CSRF_TOKEN") failures += "with a token ${route.getString("method")} ${route.getString("path")} is still INVALID_CSRF_TOKEN"
        }

        // a guest has no ambient credential: a PUB-M call without a session is not a CSRF case
        val guest = visitor("guest")
        val guestAnswer = guest.request("POST", "${MarketPaths.SITE_ROOT}/checkout/quote", JsonObject().put("items", JsonArray()), mapOf("Idempotency-Key" to idempotencyKey()), log = false)

        assertNotEquals("INVALID_CSRF_TOKEN", guestAnswer.error, "a guest PUB-M call is not subject to CSRF")

        // panel mutations: the admin (holds every node) without the token
        val rows = liveRows().filter { it.method != "GET" }

        try {
            for (row in rows) {
                val answer = call(admin, row, "${run}c${unique.incrementAndGet()}", csrf = false)

                collect(row, answer)
                if (answer.status != 403 || answer.error != "INVALID_CSRF_TOKEN") failures += "panel ${row.key}: ${answer.status} ${answer.error}"
            }
        } finally {
            cleanUpMatrix()
        }

        // inbound payment and shipping routes: the cookie of a logged-in buyer and no token is not a CSRF failure
        val inbound = routes.filter { it.getString("method") == "ROUTE" }

        assertTrue(inbound.size >= 6, "enumerated ${inbound.size} inbound routes")

        for (route in inbound) {
            for (method in listOf("POST", "PUT")) {
                val answer = buyer.client.request(method, concrete(route.getString("path")), JsonObject(), csrf = false, log = false)

                if (answer.error == "INVALID_CSRF_TOKEN" || answer.status == 401 || answer.status >= 500) failures += "inbound $method ${route.getString("path")}: ${answer.status} ${answer.error}"
            }
        }

        println("API-03 user/pub-m=${buyerRoutes.size} panel=${rows.size} inbound=${inbound.size}")
        assertTrue(failures.isEmpty(), "API-03 failures (${failures.size}):\n" + failures.take(40).joinToString("\n"))
    }

    // --- API-04 --------------------------------------------------------------------------------------------------------

    private fun assertEnvelope(answer: E2eResponse, status: Int, error: String? = null, what: String) {
        assertEquals(status, answer.status, "$what: status ${answer.status} ${answer.json?.encode()?.take(200)}")
        assertEquals(setOf("error"), answer.obj().fieldNames(), "$what: the body is exactly the envelope")
        assertNotNull(answer.error, "$what: error code")
        if (error != null) assertEquals(error, answer.error, "$what: error code")
    }

    @Test
    fun `API-04 error envelope and validation - wrong types, negative price and unknown properties are 400 never 500, pageSize 101 is refused`() {
        val buyer = buyer()
        val key = mapOf("Idempotency-Key" to idempotencyKey())

        // an id that is not an integer
        assertEnvelope(admin.get("${MarketPaths.PANEL_ROOT}/products/1.5"), 400, "BAD_REQUEST", "panel product 1.5").also { assertNotNull(it) }
        assertNotNull(admin.get("${MarketPaths.PANEL_ROOT}/products/1.5").details.getString("bodyValidationError"), "bodyValidationError names the field")
        assertEnvelope(admin.put("${MarketPaths.PANEL_ROOT}/orders/1.5/status", JsonObject().put("status", "COMPLETED")), 400, "BAD_REQUEST", "order status 1.5")
        assertEnvelope(admin.get("${MarketPaths.PANEL_ROOT}/orders?page=1.5"), 400, "BAD_REQUEST", "page 1.5")
        assertEnvelope(buyer.client.request("DELETE", "${MarketPaths.SITE_ROOT}/me/cart/items/1.5", null, log = false), 400, "BAD_REQUEST", "cart item 1.5")

        // a wrong type in a body
        val wrongType = JsonObject().put("items", JsonArray().add(JsonObject().put("productId", "1.5").put("quantity", 1))).put("paymentMethodId", "fake")

        assertEnvelope(buyer.client.post("${MarketPaths.SITE_ROOT}/checkout", wrongType, key), 400, "BAD_REQUEST", "checkout productId \"1.5\"")
        assertNotNull(buyer.client.post("${MarketPaths.SITE_ROOT}/checkout", wrongType, key).details.getString("bodyValidationError"))
        assertEnvelope(buyer.client.post("${MarketPaths.SITE_ROOT}/checkout/quote", wrongType), 400, "BAD_REQUEST", "quote productId \"1.5\"")
        assertEnvelope(admin.post("${MarketPaths.PANEL_ROOT}/settings", JsonObject().put("vatPercent", "twenty")), 400, null, "a string for a number")

        // a negative price, a negative quantity
        val negative = admin.multipart("POST", "${MarketPaths.PANEL_ROOT}/products", mapOf("name" to "neg", "slug" to "api-neg-$run", "price" to "-5", "status" to "ACTIVE"))

        assertEnvelope(negative, 400, "INVALID_PRODUCT", "negative price")
        assertEquals("OUT_OF_RANGE", negative.details.getJsonObject("fieldErrors").getString("price"))
        assertEquals(0L, db.count("market_product", "`slug` = ?", "api-neg-$run"), "a refused product is not stored")
        assertEnvelope(buyer.client.post("${MarketPaths.SITE_ROOT}/checkout", JsonObject().put("items", JsonArray().add(line(catalog.id("VIP"), -1))).put("paymentMethodId", "fake"), key), 400, "INVALID_CART", "negative quantity")
        assertEnvelope(admin.post("${MarketPaths.PANEL_ROOT}/credits/accounts/${buyer.userId}/grant", JsonObject().put("amount", -1).put("note", "x"), key), 400, null, "negative credit amount")

        // an unknown property under additionalProperties:false
        assertEnvelope(admin.post("${MarketPaths.PANEL_ROOT}/settings", JsonObject().put("noSuchSetting", 1)), 400, "BAD_REQUEST", "unknown settings property")
        assertEnvelope(admin.put("${MarketPaths.PANEL_ROOT}/settings/invoice-sequence", JsonObject().put("series", "A").put("nextNumber", 1).put("extra", true)), 400, "BAD_REQUEST", "unknown invoice sequence property")

        // pageSize above the maximum is refused, not clamped; the public store allows 60
        for (path in listOf("${MarketPaths.PANEL_ROOT}/orders", "${MarketPaths.PANEL_ROOT}/coupons", "${MarketPaths.PANEL_ROOT}/products", "${MarketPaths.PANEL_ROOT}/credits/accounts")) {
            val refused = admin.get("$path?pageSize=101")

            assertEnvelope(refused, 400, "INVALID_FIELDS", "$path pageSize=101")
            assertEquals("OUT_OF_RANGE", refused.fields.getString("pageSize"), "$path names pageSize (04 section 4: INVALID_FIELDS with fields.pageSize)")
            assertEquals(200, admin.get("$path?pageSize=100").status, "$path pageSize=100 is the maximum and works")
        }

        assertEnvelope(buyer.client.get("${MarketPaths.SITE_ROOT}/me/orders?pageSize=101"), 400, "INVALID_FIELDS", "buyer orders pageSize=101")
        assertEnvelope(visitor().get("${MarketPaths.SITE_ROOT}/store/products?pageSize=101"), 400, "INVALID_FIELDS", "store pageSize=101")

        // a page beyond the last
        assertEnvelope(admin.get("${MarketPaths.PANEL_ROOT}/coupons?page=9999"), 404, "PAGE_NOT_FOUND", "page beyond the last")
    }

    // --- API-05 --------------------------------------------------------------------------------------------------------

    @Test
    fun `API-05 order access - a wrong token and another user get the limited view, an unknown id is 404, the invoice needs ownership, a numeric id is never accepted`() {
        val owner = buyer()
        val other = buyer()
        val stranger = visitor("stranger")
        val product = catalog.fresh("VIP").id
        val answer = checkout(owner.client, cart(line(product))).ok()
        val publicId = publicIdOf(answer)
        val token = answer.obj().getString("orderToken")

        assertNotNull(token, "the owner got the order token once")

        // the owner: the full view
        assertEquals(false, order(owner.client, publicId).getBoolean("limited"), "owner view")

        // a wrong token, another logged-in user, a stranger: the limited view (status, items, totals; no e-mail)
        val views = mapOf(
            "stranger with a wrong token" to order(stranger, publicId, "0".repeat(40)),
            "stranger without a token" to order(stranger, publicId),
            "another user without a token" to order(other.client, publicId),
            "another user with a wrong token" to order(other.client, publicId, "wrong-token")
        )

        for ((who, view) in views) {
            assertEquals(true, view.getBoolean("limited"), "$who gets the limited view")
            assertEquals(null, view.getString("email"), "$who: no e-mail")
            assertEquals(publicId, view.getString("publicId"))
        }

        // the token opens the full view for anybody who holds it
        assertEquals(false, order(stranger, publicId, token).getBoolean("limited"), "the token holder")

        // an unknown public id
        assertEnvelope(stranger.get("${MarketPaths.SITE_ROOT}/orders/ZZZZZZZZZZZZZZZZ"), 404, "NOT_FOUND", "unknown public id")
        assertEnvelope(owner.client.get("${MarketPaths.SITE_ROOT}/orders/ZZZZZZZZZZZZZZZZ/status"), 404, "NOT_FOUND", "unknown public id, status")

        // the numeric id is never accepted on the public routes (not by the owner, not with the token, not by the admin)
        val numeric = orderRow(publicId).getLong("id").toString()

        for (client in listOf(owner.client, stranger, admin)) {
            assertEnvelope(client.get("${MarketPaths.SITE_ROOT}/orders/$numeric", mapOf("X-Order-Token" to token)), 404, "NOT_FOUND", "numeric id ${client.label}")
            assertEnvelope(client.get("${MarketPaths.SITE_ROOT}/orders/$numeric/status"), 404, "NOT_FOUND", "numeric id status ${client.label}")
            assertEnvelope(client.get("${MarketPaths.SITE_ROOT}/orders/$numeric/invoice"), 404, "NOT_FOUND", "numeric id invoice ${client.label}")
        }

        // the invoice without ownership: 404 (never 403: the order is not revealed)
        assertEnvelope(other.client.get("${MarketPaths.SITE_ROOT}/orders/$publicId/invoice"), 404, "NOT_FOUND", "invoice, another user")
        assertEnvelope(stranger.get("${MarketPaths.SITE_ROOT}/orders/$publicId/invoice"), 404, "NOT_FOUND", "invoice, a stranger")
        assertEnvelope(other.client.get("${MarketPaths.SITE_ROOT}/orders/$publicId/invoice", mapOf("X-Order-Token" to "wrong-token")), 404, "NOT_FOUND", "invoice, wrong token")

        // owner-only mutations without ownership: 404 as well, and nothing changed
        val cancel = other.client.post("${MarketPaths.SITE_ROOT}/orders/$publicId/cancel", JsonObject())

        assertEnvelope(cancel, 404, "NOT_FOUND", "cancel by another user")
        assertEquals("PENDING", orderStatus(publicId), "another user cannot cancel the order")
    }

    // --- API-06 --------------------------------------------------------------------------------------------------------

    private fun enableMaintenance(enabled: Boolean) {
        val maintenance = JsonObject().put("enabled", enabled).put("bypassPermissionNode", "").put("showLoginButton", true).put("customLoginUrl", "").put("showSiteLogo", false)
            .put("title", "e2e").put("messageHtml", "").put("customCss", "")

        admin.multipart("PUT", "/api/v1/panel/settings", mapOf("maintenance" to maintenance.encode(), "password" to session.env.adminPassword())).ok()
    }

    @Test
    fun `API-06 inbound route properties - answers under maintenance and to GET POST PUT, 413 above 1 MB, 404 for an unknown provider and an unknown token`() {
        val guest = visitor("gateway")
        val webhook = "${MarketPaths.SITE_ROOT}/payments/fake/webhook"
        val methods = listOf("GET", "POST", "PUT")

        fun probe(): Map<String, Int> = methods.associateWith { guest.request(it, webhook, if (it == "GET") null else JsonObject().put("e2e", "api-06"), log = false).status }

        val baseline = probe()

        println("API-06 baseline webhook statuses $baseline")

        for ((method, status) in baseline) assertTrue(status in 100..499 && status != 405 && status != 503, "$method $webhook answers ($status)")

        // maintenance on: the public site is closed to a visitor, the gateway still gets the same answers
        enableMaintenance(true)

        try {
            val closed = guest.get("${MarketPaths.SITE_ROOT}/store")

            assertEquals(503, closed.status, "maintenance is active: a visitor is refused ${closed.error}")
            assertEquals(baseline, probe(), "the webhook route answers the same under maintenance mode")

            val notify = guest.request("POST", "${MarketPaths.SITE_ROOT}/payments/fake/notify/${"0".repeat(40)}", JsonObject(), log = false)

            assertEquals(404, notify.status, "an unknown notify token under maintenance")
        } finally {
            enableMaintenance(false)
        }

        assertEquals(200, guest.get("${MarketPaths.SITE_ROOT}/store").status, "maintenance is off again")

        // body above 1 MB: 413, nothing stored
        val eventsBefore = db.count("market_payment_event")
        val big = ByteArray(1_048_576 + 4096) { 'a'.code.toByte() }
        val tooLarge = guest.request("POST", webhook, big, mapOf("Content-Type" to "application/octet-stream"), log = false)

        assertEquals(413, tooLarge.status, "a body above 1 MB")
        assertEquals(eventsBefore, db.count("market_payment_event"), "an over-limit body stores nothing")

        val small = guest.request("POST", webhook, ByteArray(1024) { 'a'.code.toByte() }, mapOf("Content-Type" to "application/octet-stream"), log = false)

        assertNotEquals(413, small.status, "a small body is not refused as too large")

        // unknown provider id, unknown notify token, shapes the routes refuse
        assertEquals(404, guest.request("POST", "${MarketPaths.SITE_ROOT}/payments/no-such-provider/webhook", JsonObject(), log = false).status, "unknown provider")
        assertEquals(404, guest.request("GET", "${MarketPaths.SITE_ROOT}/payments/no-such-provider/webhook", null, log = false).status, "unknown provider GET")
        assertEquals(404, guest.request("POST", "${MarketPaths.SITE_ROOT}/payments/fake/notify/${"1".repeat(40)}", JsonObject(), log = false).status, "unknown notify token")
        assertEquals(404, guest.request("POST", "${MarketPaths.SITE_ROOT}/payments/fake/notify/not-a-token", JsonObject(), log = false).status, "notify token of the wrong shape")
        assertEquals(404, guest.request("GET", "${MarketPaths.SITE_ROOT}/payments/fake/return/${"2".repeat(40)}/success", null, log = false).status.let { if (it == 303) 404 else it }, "unknown return token")
    }

    // --- API-07 --------------------------------------------------------------------------------------------------------

    private class Golden(val tag: String, val method: String, val path: String, val kind: String, val keys: Set<String>, val removed: Set<String>) {
        val key: String get() = "$method $path"
    }

    private fun golden(): List<Golden> {
        val text = ApiContractE2E::class.java.getResourceAsStream("/golden/api/v1-keysets.json")?.bufferedReader()?.readText() ?: error("golden/api/v1-keysets.json is missing")
        val doc = JsonObject(text)

        assertEquals("c58d57a", doc.getString("recordedFrom"))

        // the file records the handlers of c58d57a under their pre-cutover paths; the live routes are addressed by the versioned ones
        fun moved(path: String) = when {
            path.startsWith("/api/panel/market") -> MarketPaths.PANEL_ROOT + path.removePrefix("/api/panel/market")
            path.startsWith("/api/market") -> MarketPaths.SITE_ROOT + path.removePrefix("/api/market")
            else -> path
        }

        return doc.getJsonArray("endpoints").map { it as JsonObject }.map { e ->
            Golden(
                e.getString("tag"), e.getString("method"), moved(e.getString("path")), e.getString("kind"), e.getJsonArray("keys").map { it as String }.toSet(),
                (e.getJsonArray("intentionalRemovals") ?: JsonArray()).map { (it as JsonObject).getString("key") }.toSet()
            )
        }
    }

    /** Every key path of a JSON answer: `a.b`, arrays as `a[]` (the elements' keys under `a[].k`). */
    private fun keyPaths(value: Any?, prefix: String = "", out: MutableSet<String> = sortedSetOf()): Set<String> {
        when (value) {
            is JsonObject -> value.forEach { (k, v) ->
                val path = if (prefix.isEmpty()) k else "$prefix.$k"

                out += path
                keyPaths(v, path, out)
            }
            is JsonArray -> value.forEach { keyPaths(it, "$prefix[]", out) }
        }

        return out
    }

    private inner class Fixture {
        val admin = this@ApiContractE2E.admin
        val n = unique.incrementAndGet()
        val categoryId: Long
        val childCategoryId: Long
        val scratchCategory: Long
        val product: ApiContractE2E.Product
        val scratchProduct: Long
        val comparisonId: Long
        val scratchComparison: Long
        val orderDbId: Long
        val created = ArrayList<String>()
        val ids = HashMap<String, Long>()

        init {
            fun category(name: String, parent: Long? = null): Long {
                val fields = linkedMapOf("name" to name, "status" to "ACTIVE", "description" to "d", "icon" to "fa-box")

                parent?.let { fields["parentId"] = it.toString() }

                return admin.multipart("POST", "${MarketPaths.PANEL_ROOT}/categories", fields).ok().obj().getLong("id")
            }

            categoryId = category("API root $run$n")
            childCategoryId = category("API child $run$n", categoryId)
            scratchCategory = category("API scratch $run$n")

            val fresh = catalog.fresh("VIP", "categoryId" to categoryId.toString())

            product = Product(fresh.id, fresh.slug)
            scratchProduct = catalog.fresh("FREE").id
            comparisonId = admin.post("${MarketPaths.PANEL_ROOT}/comparisons", JsonObject().put("name", "api $run$n").put("status", "ACTIVE").put("selectedProducts", JsonArray().add(product.id))).ok().obj().getLong("id")
            scratchComparison = admin.post("${MarketPaths.PANEL_ROOT}/comparisons", JsonObject().put("name", "api scratch $run$n")).ok().obj().getLong("id")

            for (group in listOf("coupons", "discounts", "gifts", "creator-codes")) {
                ids[group] = createPromotion(group, "scratch")
                ids["$group:main"] = createPromotion(group, "main")
            }

            val buyer = buyer()
            val publicId = publicIdOf(checkout(buyer.client, cart(line(product.id))).ok())

            payViaFake(publicId)
            awaitOrder(publicId, "COMPLETED")
            orderDbId = orderRow(publicId).getLong("id")
        }

        private fun createPromotion(group: String, suffix: String): Long {
            val code = "API${run.uppercase()}${unique.incrementAndGet()}"
            val body = when (group) {
                "coupons" -> JsonObject().put("name", "api $code").put("code", code).put("discount", 10).put("unit", "PERCENT")
                "discounts" -> JsonObject().put("name", "api $code").put("value", 10).put("unit", "PERCENT").put("scope", "ALL")
                "gifts" -> JsonObject().put("name", "api $code").put("code", code).put("type", "PRODUCT").put("productId", product.id)
                else -> JsonObject().put("creator", "api$suffix${unique.get()}").put("code", code).put("discount", 5).put("unit", "PERCENT").put("commissionPercent", 10)
            }

            return admin.post("${MarketPaths.PANEL_ROOT}/$group", body).ok().obj().getLong("id")
        }

        fun cleanUp() {
            created.forEach { admin.delete(it) }
            for (group in listOf("coupons", "discounts", "gifts", "creator-codes")) admin.delete("${MarketPaths.PANEL_ROOT}/$group/${ids["$group:main"]}")
            admin.delete("${MarketPaths.PANEL_ROOT}/comparisons/$comparisonId")
            admin.delete("${MarketPaths.PANEL_ROOT}/products/${product.id}")
            admin.delete("${MarketPaths.PANEL_ROOT}/products/$scratchProduct")
            admin.delete("${MarketPaths.PANEL_ROOT}/categories/$scratchCategory")
            admin.delete("${MarketPaths.PANEL_ROOT}/categories/$childCategoryId")
            admin.delete("${MarketPaths.PANEL_ROOT}/categories/$categoryId")
        }
    }

    private class Product(val id: Long, val slug: String)

    private fun Fixture.probe(g: Golden): E2eResponse? {
        val p = "${MarketPaths.PANEL_ROOT}"
        val visitor = visitor("golden")
        val form = mapOf("name" to "api golden $run${unique.incrementAndGet()}", "slug" to "api-golden-$run${unique.incrementAndGet()}", "price" to "2.00", "status" to "ACTIVE")
        val group = g.path.removePrefix("$p/").substringBefore('/')

        fun remember(answer: E2eResponse, base: String): E2eResponse {
            answer.json?.getLong("id")?.let { created += "$p$base/$it" }

            return answer
        }

        return when (g.key) {
            "GET ${MarketPaths.SITE_ROOT}/store" -> visitor.get("${MarketPaths.SITE_ROOT}/store")
            "GET ${MarketPaths.SITE_ROOT}/products/:slug" -> visitor.get("${MarketPaths.SITE_ROOT}/products/${product.slug}")
            "GET ${MarketPaths.PANEL_ROOT}/categories" -> admin.get("$p/categories")
            "POST ${MarketPaths.PANEL_ROOT}/categories" -> remember(admin.multipart("POST", "$p/categories", mapOf("name" to "api created $run${unique.incrementAndGet()}", "status" to "ACTIVE")), "/categories")
            "PUT ${MarketPaths.PANEL_ROOT}/categories/:id" -> admin.multipart("PUT", "$p/categories/$scratchCategory", mapOf("name" to "api renamed $run", "status" to "ACTIVE"))
            "DELETE ${MarketPaths.PANEL_ROOT}/categories/:id" -> {
                val doomed = admin.multipart("POST", "$p/categories", mapOf("name" to "api doomed $run${unique.incrementAndGet()}", "status" to "ACTIVE")).ok().obj().getLong("id")

                admin.delete("$p/categories/$doomed")
            }
            "POST ${MarketPaths.PANEL_ROOT}/categories/sort" -> admin.post("$p/categories/sort", JsonObject().put("id", childCategoryId).put("position", "AFTER").put("targetId", categoryId))
            "GET ${MarketPaths.PANEL_ROOT}/products" -> admin.get("$p/products")
            "GET ${MarketPaths.PANEL_ROOT}/products/simple" -> admin.get("$p/products/simple")
            "GET ${MarketPaths.PANEL_ROOT}/products/:id" -> admin.get("$p/products/${product.id}")
            "POST ${MarketPaths.PANEL_ROOT}/products" -> remember(admin.multipart("POST", "$p/products", form), "/products")
            "PUT ${MarketPaths.PANEL_ROOT}/products/:id" -> admin.multipart("PUT", "$p/products/$scratchProduct", form)
            "POST ${MarketPaths.PANEL_ROOT}/products/:id/clone" -> remember(admin.post("$p/products/${product.id}/clone", JsonObject()), "/products")
            "DELETE ${MarketPaths.PANEL_ROOT}/products/:id" -> {
                val doomed = admin.multipart("POST", "$p/products", form).ok().obj().getLong("id")

                admin.delete("$p/products/$doomed")
            }
            "GET ${MarketPaths.PANEL_ROOT}/comparisons" -> admin.get("$p/comparisons")
            "GET ${MarketPaths.PANEL_ROOT}/comparisons/:id" -> admin.get("$p/comparisons/$comparisonId")
            "POST ${MarketPaths.PANEL_ROOT}/comparisons" -> remember(admin.post("$p/comparisons", JsonObject().put("name", "api created $run${unique.incrementAndGet()}")), "/comparisons")
            "PUT ${MarketPaths.PANEL_ROOT}/comparisons/:id" -> admin.put("$p/comparisons/$scratchComparison", JsonObject().put("name", "api renamed $run"))
            "POST ${MarketPaths.PANEL_ROOT}/comparisons/:id/clone" -> remember(admin.post("$p/comparisons/$comparisonId/clone", JsonObject()), "/comparisons")
            "DELETE ${MarketPaths.PANEL_ROOT}/comparisons/:id" -> {
                val doomed = admin.post("$p/comparisons", JsonObject().put("name", "api doomed $run${unique.incrementAndGet()}")).ok().obj().getLong("id")

                admin.delete("$p/comparisons/$doomed")
            }
            "GET ${MarketPaths.PANEL_ROOT}/orders" -> admin.get("$p/orders")
            "GET ${MarketPaths.PANEL_ROOT}/orders/:id" -> admin.get("$p/orders/$orderDbId")
            "PUT ${MarketPaths.PANEL_ROOT}/orders/:id/status" -> admin.put("$p/orders/$orderDbId/status", JsonObject().put("status", "COMPLETED"))
            "PUT ${MarketPaths.PANEL_ROOT}/orders/:id/exchange-rate" -> admin.put("$p/orders/$orderDbId/exchange-rate", JsonObject().put("exchangeRate", 1.25))
            "POST ${MarketPaths.PANEL_ROOT}/orders/:id/exchange-rate/refresh" -> admin.post("$p/orders/$orderDbId/exchange-rate/refresh", JsonObject())
            "GET ${MarketPaths.PANEL_ROOT}/stats" -> admin.get("$p/stats")
            "GET ${MarketPaths.PANEL_ROOT}/settings" -> admin.get("$p/settings")
            "POST ${MarketPaths.PANEL_ROOT}/settings" -> admin.post("$p/settings", JsonObject().put("storeName", admin.get("$p/settings").ok().obj().getString("storeName")))
            "POST ${MarketPaths.PANEL_ROOT}/settings/credits" -> admin.post("$p/settings/credits", JsonObject().put("creditsEnabled", true))
            "POST ${MarketPaths.PANEL_ROOT}/settings/exchange-rate/refresh" -> admin.post("$p/settings/exchange-rate/refresh", JsonObject())
            "POST ${MarketPaths.PANEL_ROOT}/payment-methods/:id" ->
                admin.post("$p/payment-methods/fake", JsonObject().put("settings", JsonObject().put("gatewayUrl", gateway.baseUrl).put("secret", gateway.secret)))
            "POST ${MarketPaths.PANEL_ROOT}/payment-methods/:id/toggle" -> admin.post("$p/payment-methods/fake/toggle", JsonObject().put("enabled", true))
            "POST ${MarketPaths.PANEL_ROOT}/payment-methods/:id/reveal" -> admin.post("$p/payment-methods/fake/reveal", JsonObject().put("password", session.env.adminPassword()))
            else -> when {
                group in setOf("coupons", "discounts", "gifts", "creator-codes") -> promotionProbe(g, group)
                else -> throw AssertionError("no probe for the golden endpoint ${g.key}")
            }
        }
    }

    private fun Fixture.promotionProbe(g: Golden, group: String): E2eResponse {
        val p = "${MarketPaths.PANEL_ROOT}"
        val code = "API${run.uppercase()}${unique.incrementAndGet()}"

        return when (g.method) {
            "GET" -> admin.get("$p/$group")
            "POST" -> {
                val body = when (group) {
                    "coupons" -> JsonObject().put("name", "api $code").put("code", code).put("discount", 10).put("unit", "PERCENT")
                    "discounts" -> JsonObject().put("name", "api $code").put("value", 10).put("unit", "PERCENT").put("scope", "ALL")
                    "gifts" -> JsonObject().put("name", "api $code").put("code", code).put("type", "PRODUCT").put("productId", product.id)
                    else -> JsonObject().put("creator", "apic${unique.get()}").put("code", code).put("discount", 5).put("unit", "PERCENT").put("commissionPercent", 10)
                }
                val answer = admin.post("$p/$group", body)

                answer.json?.getLong("id")?.let { created += "$p/$group/$it" }
                answer
            }
            "PUT" -> admin.put("$p/$group/${ids.getValue(group)}", JsonObject().put("status", "ACTIVE"))
            else -> admin.delete("$p/$group/${ids.getValue(group)}")
        }
    }

    @Test
    fun `API-07 backwards compatibility - every recorded key of the 53 handlers of c58d57a is still in the live answer`() {
        val endpoints = golden()

        // a key may only be missing from the live answer when the spec removes it on purpose and the golden file says so (04 section 3: the store list is cards, no description)
        assertEquals(mapOf("GET ${MarketPaths.SITE_ROOT}/store" to setOf("items[].description")), endpoints.filter { it.removed.isNotEmpty() }.associate { it.key to it.removed }, "the acknowledged removals")
        assertEquals(53, endpoints.size, "the golden file holds the 53 route classes of c58d57a (the recon doc counted 54)")
        assertEquals(endpoints.size, endpoints.map { it.key }.toSet().size, "no endpoint twice")
        assertEquals(setOf("KEEP", "CHANGE"), endpoints.map { it.tag }.toSet())

        // every golden endpoint is a route the plugin registers today
        val registered = healthRoutes().map { "${it.getString("method")} ${it.getString("path")}" }.toSet()

        for (g in endpoints) assertTrue(g.key in registered, "${g.key} is still registered")

        val fixture = Fixture()
        val failures = ArrayList<String>()
        val unobserved = ArrayList<String>()
        var observed = 0

        try {
            for (g in endpoints) {
                if (g.kind == "FILE") {
                    // a file route: registered (above) and answers an unknown file name with the platform's 404, not a 500
                    val path = g.path.replace(":fileName", "no-such-file.png")
                    val answer = (if (g.path.startsWith(MarketPaths.PANEL_ROOT)) admin else visitor()).get(path)

                    if (answer.status !in setOf(404, 400)) failures += "${g.key}: an unknown file answered ${answer.status}"

                    continue
                }

                val answer = fixture.probe(g)!!

                if (answer.status != 200) {
                    // the two rate refreshes reach an outside service; when it is unreachable the honest answer is 502 and nothing is compared
                    if (g.path.endsWith("exchange-rate/refresh") && answer.status == 502) {
                        unobserved += "${g.key} (502 ${answer.error}: exchange-rate service unreachable)"

                        continue
                    }

                    failures += "${g.key}: status ${answer.status} ${answer.error}"

                    continue
                }

                observed++

                val live = keyPaths(answer.obj())
                // the success body has no `result` key any more (04 section 3); the golden file recorded it
                val missing = g.keys - live - "result"

                if (missing.isNotEmpty()) failures += "${g.key} (${g.tag}) lost keys: ${missing.sorted()}"
            }
        } finally {
            fixture.cleanUp()
        }

        println("API-07 golden=${endpoints.size} observed=$observed unobserved=${unobserved.size} $unobserved")
        assertTrue(failures.isEmpty(), "API-07 failures (${failures.size}):\n" + failures.joinToString("\n"))
        assertTrue(observed >= 45, "only $observed endpoints were observed")
    }

    // --- API-08 --------------------------------------------------------------------------------------------------------

    private fun csvRows(text: String): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0

        while (i < text.length) {
            val c = text[i]

            when {
                quoted && c == '"' && i + 1 < text.length && text[i + 1] == '"' -> { cell.append('"'); i++ }
                c == '"' -> quoted = !quoted
                !quoted && c == ',' -> { row.add(cell.toString()); cell.setLength(0) }
                !quoted && c == '\n' -> { row.add(cell.toString().removeSuffix("\r")); cell.setLength(0); rows += row; row = ArrayList() }
                else -> cell.append(c)
            }

            i++
        }

        if (cell.isNotEmpty() || row.isNotEmpty()) { row.add(cell.toString()); rows += row }

        return rows
    }

    @Test
    fun `API-08 CSV export - a UTF-8 BOM, one row per order item, a formula in a product name is neutralised, the export is logged`() {
        val formula = "=HYPERLINK(\"http://example.com/x\",\"click\")"
        val vip = catalog.fresh("VIP", "name" to formula)
        val second = catalog.fresh("FREE", "name" to "plain item")
        val buyer = buyer()
        val publicId = publicIdOf(checkout(buyer.client, cart(line(vip.id), line(second.id))).ok())

        payViaFake(publicId)
        awaitOrder(publicId, "COMPLETED")

        val logsBefore = db.count("panel_activity_log", "`type` = 'EXPORTED_MARKET_ORDERS'")
        val answer = admin.get("${MarketPaths.PANEL_ROOT}/orders/export?search=$publicId&columns=publicId,status,productName")

        assertEquals(200, answer.status, "export: ${answer.status} ${answer.error}")
        assertTrue(answer.header("Content-Type")!!.startsWith("text/csv"), "content type ${answer.header("Content-Type")}")
        assertTrue(answer.header("Content-Disposition")!!.startsWith("attachment"), "an attachment")
        assertEquals("nosniff", answer.header("X-Content-Type-Options"))

        val bytes = answer.body

        assertTrue(bytes.size > 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte(), "the file starts with a UTF-8 byte order mark")

        val rows = csvRows(String(bytes, 3, bytes.size - 3, Charsets.UTF_8)).filter { it.any { cell -> cell.isNotEmpty() } }
        val header = rows.first()
        val data = rows.drop(1)

        assertEquals(listOf("publicId", "status", "productName"), header.map { it.removePrefix("'") }.take(3), "header row: $header")
        assertEquals(2, data.size, "one row per order item (the order has two): $data")
        assertTrue(data.all { it[0] == publicId }, "every row is the order")

        val names = data.map { it[2] }

        assertTrue(names.any { it == "'$formula" || it == "'$formula " || it.startsWith("'=HYPERLINK(") }, "the formula is exported prefixed with a quote: $names")
        assertFalse(names.any { it.startsWith("=") }, "no cell starts with =: $names")
        assertTrue(names.any { it.contains("plain item") })

        // activity log (the route writes it after the last chunk went out, so the row may land a moment after the answer)
        Await.until(10_000, 100, "EXPORTED_MARKET_ORDERS written") { db.count("panel_activity_log", "`type` = 'EXPORTED_MARKET_ORDERS'") > logsBefore }
        assertEquals(logsBefore + 1, db.count("panel_activity_log", "`type` = 'EXPORTED_MARKET_ORDERS'"), "EXPORTED_MARKET_ORDERS written once")

        val log = db.sql("SELECT `details` FROM `pano_panel_activity_log` WHERE `type` = 'EXPORTED_MARKET_ORDERS' ORDER BY `id` DESC LIMIT 1").first()
        val details = JsonObject(log.getValue("details").toString())

        assertEquals(2, details.getInteger("rows"), "the log counts the rows: $details")

        // the PII columns need OM or PAY: the viewer is refused, not silently given them
        val viewer = user("node-OV").client

        assertEquals(403, viewer.get("${MarketPaths.PANEL_ROOT}/orders/export?columns=email").status, "email column below the PII tier")
        assertEquals(200, viewer.get("${MarketPaths.PANEL_ROOT}/orders/export?columns=publicId,status").status)

        // L11 (MK-152 seam named in evidence/MK-170.md): 6 exports a minute per panel user, the next one is 429 with retryAfter; another user is not affected
        val statuses = (1..10).map { viewer.get("${MarketPaths.PANEL_ROOT}/orders/export?columns=publicId", log = false) }
        val firstRefused = statuses.indexOfFirst { it.status == 429 }

        assertTrue(firstRefused in 1..5, "the 7th export of the minute is refused (the viewer had already made 2): statuses ${statuses.map { it.status }}")
        assertTrue(statuses.drop(firstRefused).all { it.status == 429 }, "once refused, refused: ${statuses.map { it.status }}")
        assertEquals("TOO_MANY_REQUESTS", statuses[firstRefused].error)
        assertTrue(statuses[firstRefused].details.getInteger("retryAfter") in 1..60, "retryAfter: ${statuses[firstRefused].json}")
        assertEquals(200, user("umbrella").client.get("${MarketPaths.PANEL_ROOT}/orders/export?columns=publicId", log = false).status, "another user is not limited by the viewer's bucket")
    }

    // --- API-09 --------------------------------------------------------------------------------------------------------

    private val adminId: Long by lazy { db.long("SELECT `id` FROM `pano_user` WHERE `username` = ?", session.env.adminUser)!! }

    private fun lastLogId(): Long = db.long("SELECT COALESCE(MAX(`id`), 0) FROM `pano_panel_activity_log`") ?: 0L

    private val forbiddenDetailKeys = Regex("(secret|token|password|email|mail|address|passwd|apikey|api_key)", RegexOption.IGNORE_CASE)

    /** [masked] lists the already masked values a log may legitimately carry (an e-mail mask keeps its `@`); they are cut out before the e-mail check. */
    private fun assertOneLog(expected: String, from: Long, what: String, masked: List<String> = emptyList()): JsonObject {
        val rows = db.sql("SELECT `type`, `userId`, `pluginId`, `details` FROM `pano_panel_activity_log` WHERE `id` > ? ORDER BY `id`", from)

        assertEquals(listOf(expected), rows.map { it.getString("type") }, "$what: exactly one activity log of the type listed in 04 section 10")

        val row = rows.single()
        val details = JsonObject(row.getValue("details").toString())

        assertEquals(adminId, row.getLong("userId"), "$what: the actor")
        assertNotNull(details.getString("username"), "$what: details carry the actor's username: $details")

        val keys = keyPaths(details)

        assertTrue(keys.none { forbiddenDetailKeys.containsMatchIn(it) }, "$what: a detail key that looks like a secret, token, address or e-mail: $keys")

        val flat = details.encode()
        val unmasked = masked.fold(flat) { text, mask -> text.replace(mask, "") }

        assertFalse(unmasked.contains("@"), "$what: an e-mail in the details: $flat")
        assertFalse(flat.contains(gateway.secret), "$what: the provider secret in the details")
        assertFalse(Regex("[0-9a-f]{32,}").containsMatchIn(flat), "$what: a long hex token in the details: $flat")

        return details
    }

    private fun logged(expected: String, what: String, masked: List<String> = emptyList(), call: () -> E2eResponse): Pair<E2eResponse, JsonObject> {
        val from = lastLogId()
        val answer = call().ok()

        return answer to assertOneLog(expected, from, what, masked)
    }

    @Test
    fun `API-09 activity logs - each mutating panel call writes exactly one log of its type, without secrets, tokens, addresses or e-mail`() {
        val p = "${MarketPaths.PANEL_ROOT}"
        val n = "$run${unique.incrementAndGet()}"
        val key = { mapOf("Idempotency-Key" to idempotencyKey()) }
        var performed = 0

        fun stepLog(expected: String, what: String, masked: List<String> = emptyList(), call: () -> E2eResponse): Pair<E2eResponse, JsonObject> =
            logged(expected, what, masked, call).also { performed++ }

        fun step(expected: String, what: String, call: () -> E2eResponse): E2eResponse = stepLog(expected, what, emptyList(), call).first

        // catalogue
        val categoryId = step("CREATED_MARKET_CATEGORY", "create category") { admin.multipart("POST", "$p/categories", mapOf("name" to "api9 $n", "status" to "ACTIVE")) }.obj().getLong("id")

        step("UPDATED_MARKET_CATEGORY", "update category") { admin.multipart("PUT", "$p/categories/$categoryId", mapOf("name" to "api9 renamed $n", "status" to "ACTIVE")) }
        step("DELETED_MARKET_CATEGORY", "delete category") { admin.delete("$p/categories/$categoryId") }

        val form = mapOf("name" to "api9 product $n", "slug" to "api9-$n", "price" to "3.00", "status" to "ACTIVE")
        val productId = step("CREATED_MARKET_PRODUCT", "create product") { admin.multipart("POST", "$p/products", form) }.obj().getLong("id")

        step("UPDATED_MARKET_PRODUCT", "update product") { admin.multipart("PUT", "$p/products/$productId", form + ("name" to "api9 product renamed $n")) }
        step("UPDATED_MARKET_PRODUCT_STOCK", "stock") { admin.post("$p/products/$productId/stock", JsonObject().put("mode", "SET").put("value", 5)) }

        val cloneId = step("CREATED_MARKET_PRODUCT", "clone product") { admin.post("$p/products/$productId/clone", JsonObject()) }.obj().getLong("id")

        step("DELETED_MARKET_PRODUCT", "delete clone") { admin.delete("$p/products/$cloneId") }
        step("DELETED_MARKET_PRODUCT", "delete product") { admin.delete("$p/products/$productId") }

        val comparisonId = step("CREATED_MARKET_COMPARISON", "create comparison") { admin.post("$p/comparisons", JsonObject().put("name", "api9 $n")) }.obj().getLong("id")

        step("UPDATED_MARKET_COMPARISON", "update comparison") { admin.put("$p/comparisons/$comparisonId", JsonObject().put("name", "api9 renamed $n")) }
        step("DELETED_MARKET_COMPARISON", "delete comparison") { admin.delete("$p/comparisons/$comparisonId") }

        // promotions
        val product = catalog.fresh("VIP").id
        val code = "API9${n.uppercase()}"
        val bodies = mapOf(
            "coupons" to Triple("COUPON", JsonObject().put("name", "api9 $n").put("code", code).put("discount", 10).put("unit", "PERCENT"), JsonObject().put("status", "INACTIVE")),
            "discounts" to Triple("DISCOUNT", JsonObject().put("name", "api9 $n").put("value", 10).put("unit", "PERCENT").put("scope", "ALL"), JsonObject().put("status", "INACTIVE")),
            "gifts" to Triple("GIFT", JsonObject().put("name", "api9 $n").put("code", "G$code").put("type", "PRODUCT").put("productId", product), JsonObject().put("status", "INACTIVE")),
            "creator-codes" to Triple("CREATOR_CODE", JsonObject().put("creator", "api9$n").put("code", "C$code").put("discount", 5).put("unit", "PERCENT").put("commissionPercent", 10), JsonObject().put("status", "INACTIVE"))
        )

        for ((group, spec) in bodies) {
            val (type, create, update) = spec
            val id = step("CREATED_MARKET_$type", "create $group") { admin.post("$p/$group", create) }.obj().getLong("id")

            step("UPDATED_MARKET_$type", "update $group") { admin.put("$p/$group/$id", update) }
            step("DELETED_MARKET_$type", "delete $group") { admin.delete("$p/$group/$id") }
        }

        // money: credits
        val target = buyer(canPay = false)

        step("GRANTED_MARKET_CREDITS", "grant credits") { admin.post("$p/credits/accounts/${target.userId}/grant", JsonObject().put("amount", 7).put("note", "api9"), key()) }
        step("REVOKED_MARKET_CREDITS", "revoke credits") { admin.post("$p/credits/accounts/${target.userId}/revoke", JsonObject().put("amount", 3).put("note", "api9"), key()) }

        // orders
        val orderBuyer = buyer()
        val publicId = publicIdOf(checkout(orderBuyer.client, cart(line(catalog.fresh("VIP").id))).ok())
        val orderId = orderRow(publicId).getLong("id")

        step("UPDATED_MARKET_ORDER_NOTE", "order note") { admin.put("$p/orders/$orderId/note", JsonObject().put("note", "api9 note for ${orderBuyer.username}")) }
        // mark paid goes through UPDATED_MARKET_ORDER_STATUS (04 section 10); the route logs a cancellation as CANCELLED_MARKET_ORDER (both are catalogue types)
        step("UPDATED_MARKET_ORDER_STATUS", "order status COMPLETED") { admin.put("$p/orders/$orderId/status", JsonObject().put("status", "COMPLETED")) }

        val cancelPublicId = publicIdOf(checkout(orderBuyer.client, cart(line(catalog.fresh("VIP").id))).ok())

        step("CANCELLED_MARKET_ORDER", "order status CANCELLED") { admin.put("$p/orders/${orderRow(cancelPublicId).getLong("id")}/status", JsonObject().put("status", "CANCELLED")) }

        // money: a paid order is refunded (RF scenarios); the log names the order and the amount, never the provider's references
        val refundPaid = publicIdOf(checkout(orderBuyer.client, cart(line(catalog.fresh("VIP").id))).ok())
        val paymentReference = payViaFake(refundPaid)

        awaitOrder(refundPaid, "COMPLETED")

        val refundOrderId = orderRow(refundPaid).getLong("id")

        // the fake gateway's webhook carries no payment id, so the id the refund needs lands with the start result; wait for it (a fast job cadence can complete the order first)
        Await.until(30_000, 100, "the paid attempt of order $refundOrderId has its gateway transaction id") {
            db.string("SELECT `gatewayTransactionId` FROM `pano_market_payment` WHERE `orderId` = ? ORDER BY `id` DESC LIMIT 1", refundOrderId) != null
        }

        val refundDetails = stepLog("REFUNDED_MARKET_ORDER", "refund") {
            admin.post("$p/orders/$refundOrderId/refunds", JsonObject().put("amount", 4.00), key())
        }.second
        val gatewayRefundId = db.string("SELECT `gatewayRefundId` FROM `pano_market_refund` WHERE `orderId` = ? ORDER BY `id` DESC LIMIT 1", refundOrderId)

        assertEquals(refundOrderId, refundDetails.getLong("orderId"), "the refund log names the order: $refundDetails")
        assertEquals(4.0, refundDetails.getDouble("amount"), 0.0001, "the refund log names the amount: $refundDetails")
        assertFalse(refundDetails.encode().contains(paymentReference), "the refund log carries no payment reference: $refundDetails")
        gatewayRefundId?.let { assertFalse(refundDetails.encode().contains(it), "the refund log carries no gateway refund id: $refundDetails") }

        // money: a manual order for a named player (P-19)
        val manualProduct = catalog.fresh("VIP").id
        val manualDetails = stepLog("CREATED_MARKET_ORDER", "manual order") {
            admin.post("$p/orders", JsonObject().put("playerUsername", orderBuyer.username).put("items", JsonArray().add(line(manualProduct))), key())
        }.second

        assertNotNull(manualDetails.getLong("orderId"), "the manual order log names the order: $manualDetails")
        assertEquals(orderBuyer.username, manualDetails.getString("playerUsername"), "the manual order log names the player: $manualDetails")

        // provider settings: the save carries the secret, the log must not; the reveal and a failed reveal are logged by method id only
        val methodLogs = listOf(
            stepLog("UPDATED_MARKET_PAYMENT_METHOD", "payment method settings save") {
                admin.post("$p/payment-methods/fake", JsonObject().put("settings", JsonObject().put("gatewayUrl", gateway.baseUrl).put("secret", gateway.secret)))
            }.second,
            stepLog("REVEALED_MARKET_PAYMENT_SECRET", "secret reveal") {
                admin.post("$p/payment-methods/fake/reveal", JsonObject().put("password", session.env.adminPassword()))
            }.second
        )

        methodLogs.forEach { assertEquals("fake", it.getString("name"), "the method log names the provider id only: $it") }

        val wrongFrom = lastLogId()
        val wrong = admin.post("$p/payment-methods/fake/reveal", JsonObject().put("password", "api9-wrong-$n"))

        assertFalse(wrong.status in 200..299, "a wrong password never reveals: ${wrong.status}")
        assertEquals("fake", assertOneLog("FAILED_MARKET_SECRET_REVEAL", wrongFrom, "failed secret reveal").getString("name"))
        performed++
        // the reveal throttle counts the wrong attempt; put it back so AbuseE2E (which counts five wrong attempts itself) is not affected
        db.sql("SELECT `id`, `lockedUntil` FROM `pano_market_throttle` WHERE `scope` = 'REVEAL' AND `subject` = ?", "u:$adminId").forEach { row ->
            db.rewind("market_throttle", row.getLong("id"), "windowStart", 3_600_000)
            if (row.getValue("lockedUntil") != null) db.rewind("market_throttle", row.getLong("id"), "lockedUntil", 3_600_000)
        }

        // settings and providers
        val storeName = admin.get("$p/settings").ok().obj().getString("storeName")

        step("UPDATED_MARKET_SETTINGS", "settings") { admin.post("$p/settings", JsonObject().put("storeName", storeName)) }
        step("TOGGLED_MARKET_PAYMENT_METHOD", "toggle method") { admin.post("$p/payment-methods/fake/toggle", JsonObject().put("enabled", true)) }

        // blocks, shipping zones (the webhook endpoints are core's since MK-15: their routes and activity logs are not the market's)
        val block = step("CREATED_MARKET_BLOCK", "create block") { admin.post("$p/blocks", JsonObject().put("type", "PLAYER").put("value", "api9block$n").put("reason", "api9")) }.obj().getLong("id")

        step("DELETED_MARKET_BLOCK", "delete block") { admin.delete("$p/blocks/$block") }

        // an e-mail block: the log carries only the mask (`j***@e***.com`), never the address nor its local part
        val local = "api9mail${n.lowercase()}"
        val address = "$local@api9-example.test"
        val mask = PiiMask.email(address)!!
        val mailBlock = stepLog("CREATED_MARKET_BLOCK", "create e-mail block", masked = listOf(mask)) {
            admin.post("$p/blocks", JsonObject().put("type", "EMAIL").put("value", address).put("reason", "api9"))
        }
        val mailDetails = JsonObject(db.sql("SELECT `details` FROM `pano_panel_activity_log` WHERE `type` = 'CREATED_MARKET_BLOCK' ORDER BY `id` DESC LIMIT 1").single().getValue("details").toString())

        assertEquals(mask, mailDetails.getString("value"), "the e-mail block log holds the masked value: $mailDetails")
        assertFalse(mailDetails.encode().contains(address) || mailDetails.encode().contains(local), "the e-mail block log holds no address: $mailDetails")

        val deleteMailDetails = stepLog("DELETED_MARKET_BLOCK", "delete e-mail block", masked = listOf(mask)) { admin.delete("$p/blocks/${mailBlock.first.obj().getLong("id")}") }.second

        assertEquals(mask, deleteMailDetails.getString("value"), "the e-mail block deletion log holds the masked value too: $deleteMailDetails")
        assertFalse(deleteMailDetails.encode().contains(address) || deleteMailDetails.encode().contains(local), "the e-mail block deletion log holds no address: $deleteMailDetails")

        val zone = step("CREATED_MARKET_SHIPPING_ZONE", "create zone") {
            admin.post("$p/shipping/zones", JsonObject().put("name", "api9 $n").put("countries", JsonArray().add("LU")).put("status", "INACTIVE"))
        }.obj().getLong("id")

        step("UPDATED_MARKET_SHIPPING_ZONE", "update zone") { admin.put("$p/shipping/zones/$zone", JsonObject().put("name", "api9 renamed $n")) }
        step("DELETED_MARKET_SHIPPING_ZONE", "delete zone") { admin.delete("$p/shipping/zones/$zone") }

        // a refused call writes no log
        val before = lastLogId()

        assertEquals(400, admin.post("$p/coupons", JsonObject()).status)
        assertEquals(404, admin.delete("$p/coupons/999999999").status)
        assertEquals(before, lastLogId(), "a refused call writes no activity log")

        println("API-09 performed=$performed")
        // 46 before MK-15 moved the webhook endpoints to core: their create, update and delete calls are no longer the market's
        assertTrue(performed >= 43, "performed $performed logged calls")
    }

    // --- API-10 --------------------------------------------------------------------------------------------------------

    private fun runScript(vararg args: String, timeoutMinutes: Long = 15): Pair<Int, String> {
        val dir = File(session.env.dir ?: error("MARKET_E2E_DIR is not set")).canonicalFile
        val script = dir.resolve("../../../scripts/e2e-instance.sh").canonicalFile

        check(script.isFile) { "no $script" }

        val log = File(dir.parentFile, "api10-${args.first()}.log")
        val process = ProcessBuilder(listOf(script.path) + args).redirectErrorStream(true).redirectOutput(log).start()

        check(process.waitFor(timeoutMinutes, TimeUnit.MINUTES)) { "${args.toList()} did not finish in $timeoutMinutes minutes" }

        return process.exitValue() to log.readLines().takeLast(6).joinToString("\n")
    }

    @Test
    fun `API-10 demo and usage modes - in demo mode checkout is DISABLED_FOR_DEMO and the webhook route still answers`() {
        // A separate, short-lived instance (own directory, database and the ports +12 / +13 of the slot's block): the script installs it, stops it, and the
        // JVM is started by hand with the one extra argument `--demo` (the script has no switch for it). Stopped by its exact PID.
        val http = demoHttpPort()
        val demoName = "fd${System.getenv("PANO_OF_SLOT") ?: ""}"
        val options = arrayOf("--name", demoName, "--http-port", http.toString(), "--gateway-port", (http + 1).toString())
        val started = runScript("start", *options)

        assertEquals(0, started.first, "the demo instance installed: ${started.second}")

        // the same calls outside demo mode: the control for what the demo run answers
        val plain = E2eClient("http://127.0.0.1:$http", "plain")
        val methods = listOf("GET", "POST", "PUT")
        val webhookPath = "${MarketPaths.SITE_ROOT}/payments/fake/webhook"
        fun webhookStatuses(client: E2eClient) = methods.associateWith { client.request(it, webhookPath, if (it == "GET") null else JsonObject().put("e2e", "api-10"), log = false).status }
        val plainCheckout = plain.request("POST", "${MarketPaths.SITE_ROOT}/checkout", JsonObject().put("items", JsonArray()).put("paymentMethodId", "fake"), mapOf("Idempotency-Key" to idempotencyKey()), log = false)
        val plainWebhook = webhookStatuses(plain)

        assertNotEquals("DISABLED_FOR_DEMO", plainCheckout.error, "outside demo mode checkout is not refused by the demo gate: ${plainCheckout.status} ${plainCheckout.error}")
        println("API-10 outside demo: checkout ${plainCheckout.status} ${plainCheckout.error}, webhook $plainWebhook")

        val stopped = runScript("stop", *options)

        assertEquals(0, stopped.first, "the demo instance stopped for the hand start: ${stopped.second}")

        val instance = File(session.env.dir!!).canonicalFile.parentFile.resolve("instance-$demoName")
        val jar = instance.resolve("pano.jar") // the copy the script staged for this instance, never build/libs

        check(jar.isFile) { "no $jar" }

        val java = System.getenv("MARKET_E2E_JAVA") ?: "/usr/lib/jvm/java-11-openjdk/bin/java"
        val builder = ProcessBuilder(java, "-XX:MaxRAMPercentage=30", "-Dpano.market.fakeProvider=true", "-Dpf4j.pluginsDir=plugins", "-jar", jar.path, "-nogui", "--demo")

        builder.directory(instance).redirectErrorStream(true).redirectOutput(instance.resolve("pano-demo.log")).redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
        builder.environment()["PANO_DB_HOST"] = System.getenv("PANO_IT_MARIADB")!!.substringBefore(':')
        builder.environment()["PANO_DB_PORT"] = System.getenv("PANO_IT_MARIADB")!!.substringAfter(':', "3306")
        builder.environment()["PANO_DB_NAME"] = "pano_market_e2e_$demoName"
        builder.environment()["PANO_DB_USER"] = "root"
        builder.environment()["PANO_DB_PASSWORD"] = System.getenv("PANO_IT_MARIADB_PASSWORD") ?: ""
        builder.environment()["PANO_HTTP_PORT"] = http.toString()

        val process = builder.start()

        try {
            val base = "http://127.0.0.1:$http"
            val guest = E2eClient(base, "demo")

            Await.until(240_000, 1000, "the demo instance answers") { runCatching { guest.get("${MarketPaths.SITE_ROOT}/store", log = false).status == 200 }.getOrDefault(false) }

            // reads still work in demo mode
            assertEquals(200, guest.get("${MarketPaths.SITE_ROOT}/store").status, "a GET works in demo mode")

            // the checkout (a mutation) is refused by the platform's demo gate
            val checkout = guest.request("POST", "${MarketPaths.SITE_ROOT}/checkout", JsonObject().put("items", JsonArray()).put("paymentMethodId", "fake"), mapOf("Idempotency-Key" to idempotencyKey()), log = false)

            assertEquals("DISABLED_FOR_DEMO", checkout.error, "checkout in demo mode: ${checkout.status} ${checkout.json}")
            assertEquals(401, checkout.status, "the platform answers DisabledForDemo with 401")

            val quote = guest.request("POST", "${MarketPaths.SITE_ROOT}/checkout/quote", JsonObject().put("items", JsonArray()), mapOf("Idempotency-Key" to idempotencyKey()), log = false)

            assertEquals("DISABLED_FOR_DEMO", quote.error, "quote in demo mode")

            // a gateway is not a visitor: the webhook route answers in demo mode exactly like it does outside it (the demo gate is not applied to it)
            val demoWebhook = webhookStatuses(guest)

            for (method in methods) assertNotEquals("DISABLED_FOR_DEMO", guest.request(method, webhookPath, if (method == "GET") null else JsonObject(), log = false).error, "$method webhook in demo mode")

            assertEquals(plainWebhook, demoWebhook, "the webhook route answers the same in demo mode and outside it")
            println("API-10 demo: checkout ${checkout.status} ${checkout.error}, webhook $demoWebhook")

            assertEquals(404, guest.request("POST", "${MarketPaths.SITE_ROOT}/payments/no-such-provider/webhook", JsonObject(), log = false).status)
        } finally {
            // exact PID, SIGTERM first
            process.destroy()

            if (!process.waitFor(90, TimeUnit.SECONDS)) process.destroyForcibly().waitFor(30, TimeUnit.SECONDS)
        }

        assertFalse(process.isAlive, "the demo instance JVM is gone")
    }

    private companion object {
        const val MISSING_ID = 999_999_999L
        const val PANEL_ACCESS = "pano.panel.access.panel"
        const val NODE_PREFIX = "pano.plugin.pano-plugin-market."
        fun demoHttpPort(): Int = System.getenv("PANO_OF_SLOT_BASE")?.toIntOrNull()?.plus(12)
            ?: throw IllegalStateException("no test-instance slot: run through pano-open-frontend-spec/tools/of-slot.sh")

        val NODE_SUFFIX = mapOf(
            MarketNode.CATALOG to "manage.market.catalog",
            MarketNode.ORDERS_VIEW to "view.market.orders",
            MarketNode.ORDERS_MANAGE to "manage.market.orders",
            MarketNode.PAYMENTS to "manage.market.payments",
            MarketNode.DISCOUNTS to "manage.market.discounts",
            MarketNode.SETTINGS to "manage.market.settings",
            MarketNode.STATS to "view.market.stats"
        )
    }
}
