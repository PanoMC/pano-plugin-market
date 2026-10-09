package com.panomc.plugins.market.e2e

import com.panomc.plugins.market.e2e.support.E2eClient
import com.panomc.plugins.market.e2e.support.E2eSession
import com.panomc.plugins.market.e2e.support.E2eTestBase
import com.panomc.plugins.market.support.Await
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.TimeUnit
import com.panomc.plugins.market.util.MarketPaths

/**
 * Abuse and limits on a real instance (17 section 9.8, 11): the checkout rate limit (L1), the durable code brute-force lock (it survives a restart of
 * the instance), the throttle of the secret reveal, and text that tries to smuggle a console command through a name or a field. A-01 to A-04.
 *
 * Every scenario that lowers a limit does it through [E2eSession.withSettings], which puts the previous value back, and never leaves a lock behind
 * for a user that other scenarios use (a lock belongs to a buyer made for the scenario; the admin's reveal lock is rewound into the past).
 */
class AbuseE2E : E2eTestBase() {
    override val tag = "abu"

    private fun productId(key: String = "VIP"): Long = catalog.fresh(key).id

    private fun throttleRow(scope: String, subject: String) =
        db.sql("SELECT * FROM `pano_market_throttle` WHERE `scope` = ? AND `subject` = ?", scope, subject).firstOrNull()

    // --- A-01 ----------------------------------------------------------------------------------------------------------

    @Test
    fun `A-01 checkout rate limit - the 4th checkout in the minute is 429 with retryAfter and the limit lifts after the window`() {
        val free = catalog.fresh("FREE").id
        val buyer = buyer()

        session.withSettings(JsonObject().put("checkoutRateLimitPerMinute", 3)) {
            val orders = ArrayList<String>()

            repeat(3) { n ->
                val answer = checkout(buyer.client, cart(line(free)), key = idempotencyKey())

                assertEquals(200, answer.status, "checkout #${n + 1} is inside the limit: ${answer.error}")
                orders += publicIdOf(answer)
            }

            val fourth = checkout(buyer.client, cart(line(free)), key = idempotencyKey())

            assertEquals(429, fourth.status, "the 4th checkout within the minute")
            assertEquals("TOO_MANY_REQUESTS", fourth.error)

            val retryAfter = fourth.details.getInteger("retryAfter")

            assertNotNull(retryAfter, "the answer carries retryAfter")
            assertTrue(retryAfter in 1..60, "retryAfter is seconds inside the minute: $retryAfter")

            // a refused request creates nothing
            assertEquals(3L, db.count("market_order", "`userId` = ?", buyer.userId))

            // another buyer is not affected (the buyer bucket is per buyer)
            val other = buyer()

            assertEquals(200, checkout(other.client, cart(line(free)), key = idempotencyKey()).status)

            // the bucket refills: after retryAfter seconds the buyer may check out again
            Thread.sleep(TimeUnit.SECONDS.toMillis(retryAfter.toLong()) + 1_500)

            val again = checkout(buyer.client, cart(line(free)), key = idempotencyKey())

            assertEquals(200, again.status, "allowed again after the window: ${again.error}")
            assertEquals(4L, db.count("market_order", "`userId` = ?", buyer.userId))
        }
    }

    // --- A-02 ----------------------------------------------------------------------------------------------------------

    /** Restarts the isolated instance through its script (stop, then start --keep): every process by the PID the script recorded. */
    private fun restartInstance() {
        val dir = session.env.dir ?: error("MARKET_E2E_DIR is not set")
        val script = File(dir).canonicalFile.resolve("../../../scripts/e2e-instance.sh").canonicalFile

        check(script.isFile) { "no $script" }

        val log = File(File(dir).canonicalFile.parentFile, "restart-a02.log")
        val instanceDir = File(dir).canonicalFile.name
        val options = if (instanceDir.startsWith("instance-")) {
            listOf("--name", instanceDir.removePrefix("instance-"), "--http-port", java.net.URI(baseUrl).port.toString(), "--gateway-port", session.env.gatewayPort.toString())
        } else {
            emptyList()
        }
        val process = ProcessBuilder(listOf(script.path, "restart") + options).redirectErrorStream(true).redirectOutput(log).start()

        check(process.waitFor(15, TimeUnit.MINUTES)) { "the restart did not finish in 15 minutes" }
        check(process.exitValue() == 0) { "the restart failed with ${process.exitValue()}: ${log.readLines().takeLast(5)}" }

        Await.until(120_000, 1000, "the store answers after the restart") {
            runCatching { E2eClient(baseUrl, "probe").get("${MarketPaths.SITE_ROOT}/store", log = false).status == 200 }.getOrDefault(false)
        }
    }

    @Test
    fun `A-02 coupon brute-force lock - 3 wrong codes lock the buyer even for a valid code and the lock survives a restart of the instance`() {
        val product = productId()
        val buyer = buyer()
        val valid = catalog.freshCoupon(10).second
        val subject = "b:u:${buyer.userId}"

        session.withSettings(JsonObject().put("couponLockThreshold", 3)) {
            // a valid code works before the lock
            val before = buyer.client.post("${MarketPaths.SITE_ROOT}/checkout/quote", cart(line(product)).put("couponCode", valid)).ok().obj().getJsonObject("quote")

            assertEquals(true, before.getJsonObject("coupon").getBoolean("valid"))

            // three different wrong codes (the same wrong code is counted once per window)
            repeat(3) { n ->
                val answer = buyer.client.post("${MarketPaths.SITE_ROOT}/checkout/quote", cart(line(product)).put("couponCode", "NOPE${System.nanoTime()}$n")).ok()
                val coupon = answer.obj().getJsonObject("quote").getJsonObject("coupon")

                assertEquals(false, coupon.getBoolean("valid"))
            }

            // the 4th attempt, with a code that exists, is refused at checkout
            val locked = checkout(buyer.client, cart(line(product)).put("couponCode", valid))

            assertEquals(429, locked.status, "locked: ${locked.error}")
            assertEquals("CODE_ATTEMPTS_LOCKED", locked.error)
            assertNotNull(locked.details.getInteger("retryAfter"))
            assertTrue(locked.details.getInteger("retryAfter") >= 1)

            // the quote shows the lock without failing
            val quote = buyer.client.post("${MarketPaths.SITE_ROOT}/checkout/quote", cart(line(product)).put("couponCode", valid)).ok().obj().getJsonObject("quote")

            assertEquals(false, quote.getJsonObject("coupon").getBoolean("valid"))
            assertEquals("CODE_ATTEMPTS_LOCKED", quote.getJsonObject("coupon").getString("reason"))

            // the lock is a row, not memory
            val row = throttleRow("COUPON", subject)

            assertNotNull(row, "a market_throttle row for $subject")
            assertTrue(row!!.getLong("lockedUntil") > System.currentTimeMillis(), "locked in the future")

            // restart the instance: the lock is still there
            restartInstance()
            admin.login(session.env.adminUser, session.env.adminPassword(), panel = true).also { assertEquals(200, it.status, "the admin logs in again after the restart") }

            // The fake provider plugin starts after the market. On an instance that was started fresh, the provider secrets saved before the first
            // restart are unreadable after it (finding of this slice, evidence/E2E-09.md: no secret.key exists after the first boot, a new key is
            // created at the restart), so the two methods are configured again exactly as the session bootstrap does and the wait is for ACTIVE.
            for (id in listOf("fake", "fake-eur")) {
                admin.post("${MarketPaths.PANEL_ROOT}/payment-methods/$id", JsonObject().put("settings", JsonObject().put("gatewayUrl", gateway.baseUrl).put("secret", gateway.secret))).ok()
                admin.post("${MarketPaths.PANEL_ROOT}/payment-methods/$id/toggle", JsonObject().put("enabled", true)).ok()
            }

            Await.until(120_000, 1000, "the fake providers are ACTIVE after the restart") {
                val providers = admin.get("${MarketPaths.PANEL_ROOT}/payment-providers", log = false).obj().getJsonArray("items").map { it as JsonObject }

                listOf("fake", "fake-eur").all { id -> providers.firstOrNull { it.getString("id") == id }?.getString("state") == "ACTIVE" }
            }

            var afterRestart = checkout(buyer.client, cart(line(product)).put("couponCode", valid))

            if (afterRestart.status == 401) {
                buyer.client.login(buyer.username, E2eSession.PASSWORD).also { assertEquals(200, it.status, "the buyer logs in again") }
                afterRestart = checkout(buyer.client, cart(line(product)).put("couponCode", valid))
            }

            assertEquals(429, afterRestart.status, "still locked after the restart: ${afterRestart.error}")
            assertEquals("CODE_ATTEMPTS_LOCKED", afterRestart.error)

            // a buyer that never failed is not locked by someone else's failures
            val bystander = buyer()

            val free = checkout(bystander.client, cart(line(product)).put("couponCode", valid))

            assertEquals(200, free.status, "a buyer that never failed is not locked: ${free.error} ${free.json}")
        }
    }

    // --- A-03 ----------------------------------------------------------------------------------------------------------

    @Test
    fun `A-03 secret reveal throttle - wrong passwords lock the reveal, settings stay masked, the fake secret is in no event body and no log`() {
        // some provider traffic exists (a paid order), so the event table has bodies to scan
        val buyerPay = buyer()
        val publicId = publicIdOf(checkout(buyerPay.client, cart(line(productId()))).ok())

        payViaFake(publicId)
        awaitOrder(publicId, "COMPLETED")

        val statuses = ArrayList<Int>()
        val adminId = db.long("SELECT `id` FROM `pano_user` WHERE `username` = ?", session.env.adminUser)!!

        try {
            repeat(5) { n ->
                val wrong = admin.post("${MarketPaths.PANEL_ROOT}/payment-methods/fake/reveal", JsonObject().put("password", "definitely-wrong-$n-${System.nanoTime()}"))

                statuses += wrong.status
                assertFalse(wrong.status in 200..299, "a wrong password never reveals: ${wrong.status}")
            }

            assertTrue(statuses.none { it == 429 }, "the first five wrong passwords are answered as wrong passwords: $statuses")

            val locked = admin.post("${MarketPaths.PANEL_ROOT}/payment-methods/fake/reveal", JsonObject().put("password", "definitely-wrong-again"))

            assertEquals(429, locked.status, "the 6th attempt is throttled: ${locked.error}")
            assertEquals("TOO_MANY_REQUESTS", locked.error)
            assertNotNull(locked.details.getInteger("retryAfter"))
            assertNotNull(throttleRow("REVEAL", "u:$adminId")?.getLong("lockedUntil"), "the lock is a row")
            assertFalse(locked.text.contains(gateway.secret))
        } finally {
            // put the admin's reveal throttle back: lock and window both into the past
            throttleRow("REVEAL", "u:$adminId")?.let { row ->
                db.rewind("market_throttle", row.getLong("id"), "windowStart", 3_600_000)
                if (row.getValue("lockedUntil") != null) db.rewind("market_throttle", row.getLong("id"), "lockedUntil", 3_600_000)
            }
        }

        // settings responses show the mask, never the secret
        val settings = admin.get("${MarketPaths.PANEL_ROOT}/settings").ok()

        assertTrue(settings.text.contains("********"), "a configured secret is shown as the mask")
        assertFalse(settings.text.contains(gateway.secret), "the settings response never carries the secret")

        val providers = admin.get("${MarketPaths.PANEL_ROOT}/payment-providers").ok()

        assertFalse(providers.text.contains(gateway.secret), "the provider list never carries the secret")

        // stored raw traffic and the instance log never hold it
        val events = db.long("SELECT COUNT(*) FROM `pano_market_payment_event`")!!

        assertTrue(events > 0, "the scan has event rows to look at")
        assertEquals(0L, db.long("SELECT COUNT(*) FROM `pano_market_payment_event` WHERE `body` LIKE ? OR `headers` LIKE ? OR `url` LIKE ?", "%${gateway.secret}%", "%${gateway.secret}%", "%${gateway.secret}%"))

        val log = File(session.env.dir ?: error("no instance dir"), "pano.log")

        assertTrue(log.isFile, "the instance log exists")
        assertFalse(log.useLines { lines -> lines.any { it.contains(gateway.secret) } }, "pano.log never contains the fake secret")
    }

    // --- A-04 ----------------------------------------------------------------------------------------------------------

    @Test
    fun `A-04 command injection through a username or a field is refused and nothing reaches a delivery payload`() {
        val cmd = catalog.fresh("VIP").id
        val withField = catalog.fresh("VAR")
        val variantId = db.long("SELECT `id` FROM `pano_market_product_variant` WHERE `productId` = ? ORDER BY `position` LIMIT 1", withField.id)
        val http = visitor("injector")
        val marker = "op me"
        val before = db.long("SELECT COUNT(*) FROM `pano_market_order`")!!
        val deliveriesBefore = db.long("SELECT COUNT(*) FROM `pano_market_delivery`")!!

        // guest user names: a command separator, a line break, a carriage return
        for (name in listOf("x; op me", "Steve\nop", "Steve\rop me", "Steve /op me")) {
            val body = cart(line(cmd)).put("guest", JsonObject().put("username", name).put("email", "guest@example.com"))
            val answer = checkout(http, body)

            assertEquals(400, answer.status, "guest username ${name.replace("\n", "\\n").replace("\r", "\\r")}: ${answer.error}")
            assertTrue(answer.error in setOf("BUYER_INFO_REQUIRED", "INVALID_CART"), "refused as ${answer.error}")
        }

        // a gift recipient name is checked the same way
        val buyer = buyer()

        for (name in listOf("x; op me", "Steve\nop")) {
            val answer = checkout(buyer.client, cart(line(cmd)).put("recipientUsername", name))

            assertTrue(answer.status in 400..499, "recipient ${name.replace("\n", "\\n")}: ${answer.status} ${answer.error}")
            assertFalse(answer.status in 200..299)
        }

        // custom field values with CR, LF and a separator
        for (value in listOf("hello\r\nop me", "hello\nop me", "hello\rop me")) {
            val line = line(withField.id, 1, variantId).put("fieldValues", JsonObject().put("note", value))
            val answer = checkout(buyer.client, cart(line))

            assertEquals(400, answer.status, "field value ${value.replace("\n", "\\n").replace("\r", "\\r")}: ${answer.error} ${answer.json}")
            assertTrue(answer.error in setOf("INVALID_CART", "BUYER_INFO_REQUIRED"), "refused as ${answer.error}")
        }

        // nothing was created and nothing carries the text
        assertEquals(before, db.long("SELECT COUNT(*) FROM `pano_market_order`"), "no order exists for any refused checkout")
        assertEquals(deliveriesBefore, db.long("SELECT COUNT(*) FROM `pano_market_delivery`"), "no delivery row")
        assertEquals(0L, db.long("SELECT COUNT(*) FROM `pano_market_delivery` WHERE `payload` LIKE ? OR `playerUsername` LIKE ?", "%$marker%", "%$marker%"))
        assertEquals(0L, db.long("SELECT COUNT(*) FROM `pano_market_order_item` WHERE `fieldValues` LIKE ?", "%$marker%"))
    }
}
