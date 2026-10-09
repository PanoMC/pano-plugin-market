package com.panomc.plugins.market.error

import com.panomc.platform.error.BadRequest
import com.panomc.platform.error.InvalidCsrfToken
import com.panomc.platform.error.NoPermission
import com.panomc.platform.error.NotFound
import com.panomc.platform.error.NotLoggedIn
import com.panomc.platform.error.PageNotFound
import com.panomc.platform.model.Error
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.net.URLDecoder

/**
 * 04 section 11 against the code: every error code of the catalogue has a class with the documented HTTP status and
 * extras, nothing is in the package that the catalogue does not list, and the codes are unique.
 */
class ErrorCatalogTest {
    /** `STATUS CODE [extra,extra,...]` exactly as the tables of 04 section 11 list them (optional extras included). */
    private val spec = """
        409 CODE_ALREADY_EXISTS
        409 SLUG_ALREADY_EXISTS
        400 INVALID_CATEGORY_MOVE
        400 INVALID_PASSWORD
        400 PAYMENT_METHOD_NOT_CONFIGURED
        502 EXCHANGE_RATE_FETCH_FAILED
        400 EMPTY_CART
        400 INVALID_CART lineErrors
        400 INVALID_COUPON reason
        400 INVALID_CREATOR_CODE reason
        400 INVALID_GIFT_CODE reason
        400 INVALID_RECIPIENT
        400 MINIMUM_ORDER_AMOUNT_NOT_REACHED minimum
        400 LEGAL_ACCEPTANCE_REQUIRED legalTextId
        400 BUYER_INFO_REQUIRED fields
        400 SHIPPING_ADDRESS_REQUIRED fields
        400 SHIPPING_UNAVAILABLE reason
        400 PAYMENT_METHOD_UNAVAILABLE reason
        400 SUBSCRIPTION_MUST_BE_ALONE
        400 INSUFFICIENT_CREDITS balance,maxApplicable
        400 INVALID_ORDER_TRANSITION use,reason
        400 INVALID_REFUND_AMOUNT max,maxGateway,maxCredit
        400 REFUND_NOT_SUPPORTED
        400 CASCADE_DECISION_REQUIRED
        400 STATUS_QUERY_NOT_SUPPORTED
        400 INVALID_PROVIDER_SETTINGS fieldErrors
        400 INVALID_SETTINGS fieldErrors
        400 INVALID_PRODUCT fieldErrors
        400 INVALID_WEBHOOK_URL reason
        400 INVALID_CREDIT_AMOUNT reason,min,max
        400 INVALID_PAYOUT_AMOUNT available
        400 CREATOR_HAS_NO_ACCOUNT
        400 PUBLIC_URL_REQUIRED
        400 RESERVED_SLUG
        400 INVALID_BLOCK reason
        400 INVALID_SHIPMENT fieldErrors
        400 INVALID_SHIPMENT_TRANSITION from,to
        400 INVALID_MAIL_KIND
        400 MAIL_RECIPIENT_REQUIRED
        400 INVALID_INVOICE_SEQUENCE
        403 BUYER_BLOCKED
        409 OUT_OF_STOCK lines
        409 PURCHASE_LIMIT_REACHED productId,limit
        409 COOLDOWN_ACTIVE productId,retryAfter
        409 PRODUCT_REQUIREMENT_NOT_MET productId
        409 PRICE_CHANGED quote
        409 ORDER_NOT_PAYABLE
        409 ORDER_NOT_CANCELLABLE
        409 ORDER_NOT_SHIPPABLE reason
        409 SUBSCRIPTION_NOT_CANCELLABLE
        409 SUBSCRIPTION_NOT_RESUMABLE
        409 SUBSCRIPTION_NOT_RETRYABLE
        409 SUBSCRIPTION_NOT_MANAGEABLE
        409 DELIVERY_NOT_RETRYABLE
        409 DELIVERY_NOT_CANCELLABLE
        409 SHIPMENT_NOT_CANCELLABLE reason
        409 INVALID_STATE state
        409 IDEMPOTENCY_CONFLICT
        409 PROVIDER_UNAVAILABLE state
        409 CATEGORY_IN_USE
        409 BLOCK_ALREADY_EXISTS
        409 CREDITS_DISABLED
        409 MAIL_DISABLED
        409 MAIL_NOT_APPLICABLE
        409 INVOICE_NOT_ISSUABLE
        429 TOO_MANY_REQUESTS retryAfter
        429 CODE_ATTEMPTS_LOCKED retryAfter
        500 INVOICE_RENDER_FAILED
        502 PAYMENT_PROVIDER_ERROR code
        502 SHIPPING_PROVIDER_ERROR code,shipmentId
        502 MAIL_SEND_FAILED
        503 STORE_DISABLED
        503 STORE_UNAVAILABLE
        503 STORE_BUSY
    """.trimIndent().lines().filter { it.isNotBlank() }.map { line ->
        val parts = line.trim().split(" ")
        Triple(parts[1], parts[0].toInt(), if (parts.size > 2) parts[2].split(",").toSet() else emptySet())
    }

    /** What a client reads beyond the code: `error.message` and the keys of `error.details` (the envelope of 04 section 3). */
    private fun extrasOf(error: Error): Set<String> {
        val envelope = JsonObject(error.encode()).getJsonObject("error")

        return (envelope.fieldNames() - setOf("code", "fields", "details")) + (envelope.getJsonObject("details")?.fieldNames() ?: emptySet())
    }

    @Test
    fun `the specification table has the documented size`() {
        // 6 existing + 34 x 400 + 1 x 403 + 24 x 409 + 2 x 429 + 1 x 500 + 3 x 502 + 3 x 503, 04 section 11.
        assertEquals(6 + 34 + 1 + 24 + 2 + 1 + 3 + 3, spec.size)
        assertEquals(spec.size, spec.map { it.first }.toSet().size, "codes in the table are unique")
    }

    @Test
    fun `every code of the table has a class with its HTTP status and extras`() {
        val byCode = ErrorCatalog.entries.associateBy { it.code }

        for ((code, status, extras) in spec) {
            val entry = byCode[code] ?: error("no error class for $code")
            val sample = entry.sample()

            assertEquals(code, sample.getErrorCode(), "code of ${entry.type.simpleName}")
            assertEquals(status, sample.getStatusCode(), "HTTP status of $code")
            assertEquals(extras, extrasOf(sample), "extras of $code")
        }
    }

    @Test
    fun `an error body is the envelope with the code and the extras as details`() {
        val body = JsonObject(InvalidRefundAmount(10.5, 7.0).encode())

        assertEquals(setOf("error"), body.fieldNames(), "no result key")
        assertEquals("INVALID_REFUND_AMOUNT", body.getJsonObject("error").getString("code"))

        val details = body.getJsonObject("error").getJsonObject("details")

        assertEquals(10.5, details.getDouble("max"))
        assertEquals(7.0, details.getDouble("maxGateway"))
        assertTrue(!details.containsKey("maxCredit"), "an omitted optional extra is absent, not null")
    }

    @Test
    fun `the catalogue lists exactly the table and no code twice`() {
        val catalogCodes = ErrorCatalog.entries.map { it.code }

        assertEquals(catalogCodes.size, catalogCodes.toSet().size, "a code is listed twice")
        assertEquals(spec.map { it.first }.toSet(), catalogCodes.toSet())
    }

    @Test
    fun `every error class of the package is in the catalogue`() {
        val listed = ErrorCatalog.entries.map { it.type }.toSet()
        val inPackage = errorClassesOfPackage()

        assertTrue(inPackage.size >= spec.size, "found ${inPackage.size} classes, expected at least ${spec.size}")
        assertEquals(emptySet<Class<*>>(), inPackage - listed, "error classes missing from ErrorCatalog")
        assertEquals(emptySet<Class<*>>(), listed - inPackage, "catalogue entries without a class in the package")
    }

    @Test
    fun `the platform codes the market reuses exist with their statuses`() {
        assertEquals("BAD_REQUEST" to 400, BadRequest().let { it.getErrorCode() to it.getStatusCode() })
        assertEquals("NOT_FOUND" to 404, NotFound().let { it.getErrorCode() to it.getStatusCode() })
        assertEquals("PAGE_NOT_FOUND" to 404, PageNotFound().let { it.getErrorCode() to it.getStatusCode() })
        assertEquals("NOT_LOGGED_IN" to 401, NotLoggedIn().let { it.getErrorCode() to it.getStatusCode() })
        assertEquals("NO_PERMISSION" to 403, NoPermission().let { it.getErrorCode() to it.getStatusCode() })
        assertEquals("INVALID_CSRF_TOKEN" to 403, InvalidCsrfToken().let { it.getErrorCode() to it.getStatusCode() })
    }

    private fun errorClassesOfPackage(): Set<Class<*>> {
        val path = ErrorCatalog::class.java.`package`.name.replace('.', '/')
        val dirs = ErrorCatalog::class.java.classLoader.getResources(path).toList()
            .map { File(URLDecoder.decode(it.path, "UTF-8")) }
            .filter { it.isDirectory }

        assertTrue(dirs.isNotEmpty(), "the error package is a directory on the test classpath")

        return dirs.flatMap { dir -> dir.listFiles { f -> f.name.endsWith(".class") && !f.name.contains('$') }!!.toList() }
            .map { Class.forName("${ErrorCatalog::class.java.`package`.name}.${it.name.removeSuffix(".class")}") }
            .filter { Error::class.java.isAssignableFrom(it) && it != Error::class.java }
            .toSet()
    }
}
