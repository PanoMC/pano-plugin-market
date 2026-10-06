package com.panomc.plugins.market.e2e.support

import com.panomc.plugins.market.support.Await
import com.panomc.plugins.market.support.FakePayGateway
import com.panomc.plugins.market.support.InvariantChecker
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger

/**
 * The once-per-JVM bootstrap of 17 section 8.2: the guards, the admin session, the market settings, the fake gateway with the providers `fake`
 * and `fake-eur`, the standard catalogue. A class reads it through [E2eSession.get]; the first call does the work, a failure is rethrown to every
 * class (the whole run is then useless, nothing is half-seeded for the next class).
 */
class E2eSession private constructor(val env: E2eEnv) {
    val admin = E2eClient(env.url, "admin")
    val db = E2eDb(env.database)
    val gateway: FakePayGateway = FakePayGateway(
        port = env.gatewayPort, webhookTarget = { "${env.url}/api/market/payments/fake/webhook" }, eventPrefix = System.currentTimeMillis().toString(36)
    )
    val catalog = E2eCatalog(admin, db)

    private val buyerSeq = AtomicInteger()
    private val permissionLock = Any()
    private val runTag = System.currentTimeMillis().toString(36).takeLast(4)

    /**
     * `MARKET_E2E_BOOTSTRAP_TESTMODE=false` makes the bootstrap leave the store in live mode: the self-test that proves [E2eInstanceGuard] aborts a
     * run whose instance reports `testMode=false` (17 section 15). Never set in a normal run.
     */
    private val bootstrapTestMode: Boolean = System.getenv("MARKET_E2E_BOOTSTRAP_TESTMODE")?.lowercase() != "false"

    private fun bootstrap() {
        // 1. admin session (panel login), cookie jar and CSRF token kept by [admin]
        val login = admin.login(env.adminUser, env.adminPassword(), panel = true)
        check(login.status == 200 && admin.csrfToken != null) { "admin login failed: ${login.status} ${login.error}" }

        // 2. buyers get a session on registration
        admin.multipart("PUT", "/api/panel/settings", mapOf("requireEmailVerification" to "false")).ok()

        // 2b. 06 section 6.7 "Test mode": a method in test mode is usable only by a session holding SET, PAY or the umbrella node, and never by a guest.
        // The instance runs in test mode (the fake provider is ineligible otherwise, 17 section 6.1), so a buyer without the node is refused with
        // TEST_MODE. The harness keeps the `default` group untouched (every registered user of the instance is in it) and gives the PAY node to a
        // dedicated group, `e2e-payer`, that a buyer joins on request: `newBuyer(tag, canPay = true)` (the default) adds it, `canPay = false` leaves a
        // buyer who is refused (CheckoutE2E asserts that refusal, and guests are refused as well).
        prepareTestModePayerGroup()

        // 3. market settings (17 section 8.2 step 3)
        admin.post(
            "/api/panel/market/settings",
            JsonObject()
                .put("testMode", bootstrapTestMode).put("currency", "EUR").put("statsCurrency", "EUR").put("vatPercent", 20).put("showVatInPrice", true)
                .put("allowGuestCheckout", true).put("allowGiftPurchase", true).put("orderExpiryMinutes", 60)
                .put("checkoutRateLimitPerMinute", 100_000).put("quoteRateLimitPerMinute", 100_000).put("couponLockThreshold", 1000)
                .put("allowPrivateWebhookTargets", true).put("invoiceEnabled", true).put("sendEmailAfterPurchase", true).put("storeTimeZone", "UTC")
                .put("storeEnabled", true).put("minimumOrderAmount", 0)
        ).ok()
        // 17 section 8.2 step 3 also sets creditValue and allowMixedCreditPayment; the credit settings route accepts them only after MK-093, so until then
        // the narrower body is sent (creditValue defaults to 1.0; mixed payment, which this slice's scenarios do not use, stays at its default)
        val credits = admin.post("/api/panel/market/settings/credits", JsonObject().put("creditsEnabled", true).put("creditValue", 1.0).put("allowMixedCreditPayment", true))
        if (credits.status == 400) admin.post("/api/panel/market/settings/credits", JsonObject().put("creditsEnabled", true)).ok() else credits.ok()

        // 4. the fake providers, configured against the gateway of this JVM and enabled
        for (id in listOf("fake", "fake-eur")) {
            admin.post("/api/panel/market/payment-methods/$id", JsonObject().put("settings", JsonObject().put("gatewayUrl", gateway.baseUrl).put("secret", gateway.secret))).ok()
            admin.post("/api/panel/market/payment-methods/$id/toggle", JsonObject().put("enabled", true)).ok()
        }
        val providers = admin.get("/api/panel/market/payment-providers").ok().obj().getJsonArray("providers")
        for (id in listOf("fake", "fake-eur")) {
            val state = providers.map { it as JsonObject }.firstOrNull { it.getString("id") == id }?.getString("state")
            check(state == "ACTIVE") { "provider $id is $state, expected ACTIVE (a plugin with dependencies=pano-plugin-market must start next to the market)" }
        }

        // the guard of 17 section 15: the instance must report testMode=true after the bootstrap
        E2eInstanceGuard.checkTestMode(E2eClient(env.url, "guard").get("/api/market/store").ok().obj())

        // 5. the standard catalogue through the panel API
        catalog.seed()
    }

    /**
     * Creates the group [PAYER_GROUP] holding [PAY_NODE] and, for an instance database kept from an earlier run of this harness (`--keep`), takes the
     * PAY node away from `default` again. The snapshot route replaces the whole permission grid, so the current snapshot is read and written back.
     */
    private fun prepareTestModePayerGroup(): Unit = synchronized(permissionLock) {
        val snapshot = admin.get("/api/panel/permission/snapshot").ok().obj()
        val groups = snapshot.getJsonArray("groups") ?: JsonArray()
        val nodes = (snapshot.getJsonArray("nodes") ?: JsonArray()).map { it as JsonObject }.toMutableList()

        val leftover = nodes.removeAll { it.getString("holderType") == "GROUP" && it.getString("holderName") == "default" && it.getString("node") == PAY_NODE }
        val hasGroup = groups.map { it as JsonObject }.any { it.getString("name") == PAYER_GROUP }
        val hasNode = nodes.any { it.getString("holderType") == "GROUP" && it.getString("holderName") == PAYER_GROUP && it.getString("node") == PAY_NODE && it.getBoolean("active", true) }

        if (!leftover && hasGroup && hasNode) return

        if (!hasGroup) groups.add(JsonObject().put("name", PAYER_GROUP).put("displayName", PAYER_GROUP))
        if (!hasNode) nodes.add(JsonObject().put("holderType", "GROUP").put("holderId", -1).put("holderName", PAYER_GROUP).put("node", PAY_NODE).put("active", true).put("context", JsonObject()))
        saveSnapshot(snapshot, groups, nodes)
    }

    /** Puts [userId] into [PAYER_GROUP] (the group node `group.e2e-payer` of the user, read-modify-write of the whole snapshot under a lock). */
    private fun addToPayerGroup(userId: Long): Unit = synchronized(permissionLock) {
        val snapshot = admin.get("/api/panel/permission/snapshot").ok().obj()
        val nodes = (snapshot.getJsonArray("nodes") ?: JsonArray()).map { it as JsonObject }.toMutableList()
        val membership = "group.$PAYER_GROUP"

        if (nodes.any { it.getString("holderType") == "USER" && it.getLong("holderId") == userId && it.getString("node") == membership }) return

        nodes.add(JsonObject().put("holderType", "USER").put("holderId", userId).put("node", membership).put("active", true).put("context", JsonObject()))
        saveSnapshot(snapshot, snapshot.getJsonArray("groups") ?: JsonArray(), nodes)
    }

    private fun saveSnapshot(snapshot: JsonObject, groups: JsonArray, nodes: List<JsonObject>) {
        admin.post(
            "/api/panel/permission/snapshot",
            JsonObject().put("groups", groups).put("tracks", snapshot.getJsonArray("tracks") ?: JsonArray()).put("nodes", JsonArray(nodes))
        ).ok()
    }

    /**
     * A buyer of its own: `POST /api/auth/register` (the session comes with the answer), named `e2e<tag>_<n>` (at most 16 characters). With [canPay]
     * the buyer is put into the `e2e-payer` group, so the test-mode fake provider is usable (06 section 6.7); without it the buyer is an ordinary
     * registered user whom a test-mode method refuses with `TEST_MODE`.
     */
    fun newBuyer(tag: String, canPay: Boolean = true): E2eBuyer {
        val n = buyerSeq.incrementAndGet()
        val username = "e2e${tag.take(4)}${runTag}_$n".take(16)
        val client = E2eClient(env.url, username)
        val body = JsonObject().put("username", username).put("email", "$username@example.com").put("password", PASSWORD).put("passwordRepeat", PASSWORD).put("agreement", true)
        val answer = client.post("/api/auth/register", body)
        check(answer.status == 200) { "register $username failed: ${answer.status} ${answer.error}" }
        client.csrfToken = answer.json?.getString("csrfToken") ?: error("register answered without a session (requireEmailVerification still on?)")
        client.username = username
        client.userId = db.long("SELECT `id` FROM `pano_user` WHERE `username` = ?", username)
        if (canPay) addToPayerGroup(client.userId ?: error("no user id for $username"))
        return E2eBuyer(client, username)
    }

    /**
     * Waits until the queues of `GET /api/panel/market/health` are empty (17 section 8.3) and runs every global invariant on the instance database.
     * Both are required after every scenario.
     */
    fun drainAndCheck() {
        Await.until(30_000, 250, "queues drained") {
            val queues = admin.get("/api/panel/market/health", log = false).obj().getJsonObject("queues")
            // `mailsPending` of the health answer counts every PENDING / SENDING row. The instance has a dummy SMTP host (17 section 8.3), so a mail that was
            // tried and failed stays PENDING under its retry backoff for minutes: that row is as drained as the harness can make it. A mail is therefore
            // drained once the mail job has claimed it at least once (no PENDING row with `attempts = 0`, no row in SENDING).
            val mailsUntried = db.count("market_mail_outbox", "(`status` = 'PENDING' AND `attempts` = 0) OR `status` = 'SENDING'")
            listOf("deliveriesPending", "webhooksPending", "deferredEvents").all { (queues?.getInteger(it) ?: 0) == 0 } && mailsUntried == 0L
        }
        runBlocking { InvariantChecker.assertAll(db.pool) }

        // a check that was skipped (its table or column is missing) proves nothing: on the complete schema of the instance every invariant must have run
        val run = InvariantChecker.lastRun
        check(run.ran.isNotEmpty() && run.skipped.isEmpty()) { "invariants ran=${run.ran.size} skipped=${run.skipped}" }
    }

    /** Sets store settings for the duration of [block] and puts the previous values back (settings are global state of the one instance). */
    fun <T> withSettings(changes: JsonObject, block: () -> T): T {
        val before = admin.get("/api/panel/market/settings").ok().obj().let { settings -> (settings.getJsonObject("settings") ?: settings) }
        val restore = JsonObject().also { r -> changes.fieldNames().forEach { k -> before.getValue(k)?.let { v -> r.put(k, v) } } }
        admin.post("/api/panel/market/settings", changes).ok()
        try {
            return block()
        } finally {
            admin.post("/api/panel/market/settings", restore)
        }
    }

    companion object {
        const val PASSWORD = "E2e-Buyer-Passw0rd!"
        /** `ManageMarketPaymentsPermission` as the platform names a plugin panel permission: `pano.plugin.<plugin id>.<key with dots>`. */
        /** The group that holds [PAY_NODE]; a buyer is in it only when the scenario asks for a paying buyer. */
        const val PAYER_GROUP = "e2e-payer"
        const val PAY_NODE = "pano.plugin.pano-plugin-market.manage.market.payments"

        @Volatile
        private var cached: E2eSession? = null

        @Synchronized
        fun get(): E2eSession {
            cached?.let { return it }
            val session = E2eSession(E2eEnv.load())
            try {
                session.bootstrap()
            } catch (e: Throwable) {
                runCatching { session.gateway.close() }
                runCatching { session.db.close() }
                throw e
            }
            cached = session
            Runtime.getRuntime().addShutdownHook(Thread { runCatching { session.gateway.close() }; runCatching { session.db.close() } })
            return session
        }
    }
}

/** A registered buyer with its own client (cookies, CSRF token). */
class E2eBuyer(val client: E2eClient, val username: String) {
    val userId: Long get() = client.userId ?: error("no user id")
}

/** A second client of the same buyer (its own connection, the buyer's session): one per virtual request of a same-buyer race. */
fun E2eBuyer.another(baseUrl: String, label: String): E2eClient = E2eClient(baseUrl, label).also { it.adoptSession(client) }
