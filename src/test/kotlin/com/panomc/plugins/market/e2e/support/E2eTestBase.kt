package com.panomc.plugins.market.e2e.support

import com.panomc.plugins.market.support.Await
import com.panomc.plugins.market.support.FakePayGateway
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Row
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import java.math.BigDecimal
import java.util.UUID
import com.panomc.plugins.market.util.MarketPaths

/**
 * Base of every tier-T4 class (17 section 8): the once-per-JVM [E2eSession] (guards, admin session, settings, fake providers, standard
 * catalogue) and, after every scenario, the drain of the queues and the global invariants on the instance database (17 section 9).
 * A class that cannot reach a guarded, bootstrapped instance fails in `@BeforeAll` and no scenario runs.
 *
 * The helpers keep the scenarios short: buyers, cart bodies, checkout, "paid via fake", order and payment rows.
 */
@Tag("e2e")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class E2eTestBase {
    protected val session: E2eSession by lazy { E2eSession.get() }
    protected val admin: E2eClient get() = session.admin
    protected val gateway: FakePayGateway get() = session.gateway
    protected val db: E2eDb get() = session.db
    protected val catalog: E2eCatalog get() = session.catalog
    protected val baseUrl: String get() = session.env.url

    /** Up to four characters naming the class in buyer user names (`e2e<tag><run>_<n>`). */
    protected abstract val tag: String

    @BeforeAll
    fun e2eBootstrap() {
        session
    }

    @AfterEach
    fun e2eDrainAndCheck() {
        session.drainAndCheck()
    }

    // --- actors --------------------------------------------------------------------------------------------------------

    /** A registered buyer; [canPay] puts it into the `e2e-payer` group (the PAY node: test-mode methods usable), `false` leaves an ordinary user. */
    protected fun buyer(canPay: Boolean = true): E2eBuyer = session.newBuyer(tag, canPay)

    /** A visitor with no session (a guest checkout, a public read). */
    protected fun visitor(label: String = "visitor"): E2eClient = E2eClient(baseUrl, label)

    // --- requests ------------------------------------------------------------------------------------------------------

    protected fun line(productId: Long, quantity: Int = 1, variantId: Long? = null): JsonObject =
        JsonObject().put("productId", productId).put("quantity", quantity).also { l -> variantId?.let { l.put("variantId", it) } }

    protected fun cart(vararg lines: JsonObject): JsonObject = JsonObject().put("items", JsonArray(lines.toList()))

    protected fun idempotencyKey(): String = UUID.randomUUID().toString()

    /** `POST /api/market/checkout` with `paymentMethodId = fake` unless [method] says otherwise. */
    protected fun checkout(
        client: E2eClient, body: JsonObject, method: String? = "fake", key: String = idempotencyKey(), headers: Map<String, String> = emptyMap()
    ): E2eResponse {
        if (method != null && !body.containsKey("paymentMethodId")) body.put("paymentMethodId", method)
        return client.post("${MarketPaths.SITE_ROOT}/checkout", body, mapOf("Idempotency-Key" to key) + headers)
    }

    protected fun publicIdOf(checkout: E2eResponse): String = checkout.obj().getJsonObject("order").getString("publicId")

    /** The gateway reference of the newest attempt of an order (an assertion read; the reference is not part of the order view). */
    protected fun referenceOf(publicId: String, attempt: Int = 0): String {
        val rows = db.sql(
            "SELECT p.`reference` AS r FROM `pano_market_payment` p JOIN `pano_market_order` o ON o.`id` = p.`orderId` WHERE o.`publicId` = ? ORDER BY p.`id` DESC",
            publicId
        )
        return rows.getOrNull(attempt)?.getString("r") ?: throw AssertionError("order $publicId has no payment attempt #$attempt")
    }

    protected fun orderRow(publicId: String): Row =
        db.sql("SELECT * FROM `pano_market_order` WHERE `publicId` = ?", publicId).firstOrNull() ?: throw AssertionError("no order $publicId")

    protected fun orderStatus(publicId: String): String = orderRow(publicId).getString("status")

    protected fun attemptStatus(reference: String): String = db.string("SELECT `status` FROM `pano_market_payment` WHERE `reference` = ?", reference) ?: "<none>"

    /** Waits for the scheduler / webhook path to bring the order to [status]. */
    protected fun awaitOrder(publicId: String, status: String, timeoutMs: Long = 30_000) {
        Await.until(timeoutMs, 250, "order $publicId is $status") { orderStatus(publicId) == status }
    }

    /** "paid via fake": `gateway.pay(reference)` and a check that every answer was 200. */
    protected fun payViaFake(publicId: String, amount: BigDecimal? = null): String {
        val reference = referenceOf(publicId)
        val answers = gateway.pay(reference, amount)
        check(answers.all { it.statusCode() == 200 }) { "the webhook was answered ${answers.map { it.statusCode() }}" }
        return reference
    }

    protected fun decimal(value: Any?): BigDecimal = BigDecimal(value.toString())

    /** `GET /api/market/orders/:publicId`: the answer is `{order: OrderView}`; this returns the view. */
    protected fun order(client: E2eClient, publicId: String, orderToken: String? = null): JsonObject =
        client.get("${MarketPaths.SITE_ROOT}/orders/$publicId", orderToken?.let { mapOf("X-Order-Token" to it) } ?: emptyMap()).ok().obj().getJsonObject("order")

    protected fun productStock(productId: Long): Long? = db.long("SELECT `stock` FROM `pano_market_product` WHERE `id` = ?", productId)

    protected fun orderEvents(publicId: String, type: String): Long =
        db.count("market_order_event", "`type` = ? AND `orderId` = (SELECT `id` FROM `pano_market_order` WHERE `publicId` = ?)", type, publicId)
}
