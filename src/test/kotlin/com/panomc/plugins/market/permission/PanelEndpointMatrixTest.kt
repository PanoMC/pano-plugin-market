package com.panomc.plugins.market.permission

import com.panomc.plugins.market.support.PanelEndpointMatrix
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The checked-in endpoint matrix (11 section 14.3, 17 section 15, MK-160): `permission-matrix.tsv` against the routes the code declares. An endpoint
 * that is added, removed or moved to another node without a matching TSV row fails here, so a route can never go live without its node being reviewed.
 */
class PanelEndpointMatrixTest {
    /** Every panel endpoint of 04 sections 5 to 8 (KEEP, CHANGE and NEW), typed once more from the contract: dropping a TSV row cannot go unnoticed. */
    private val contract: List<String> = """
        DELETE /blocks/:id
        DELETE /categories/:id
        DELETE /comparisons/:id
        DELETE /coupons/:id
        DELETE /creator-codes/:id
        DELETE /discounts/:id
        DELETE /gifts/:id
        DELETE /goals/:id
        DELETE /products/:id
        DELETE /shipping/methods/:id
        DELETE /shipping/zones/:id
        GET /blocks
        GET /categories
        GET /categories/image/:fileName
        GET /comparisons
        GET /comparisons/:id
        GET /context
        GET /coupons
        GET /coupons/:id/redemptions
        GET /creator-codes
        GET /creator-codes/:id/earnings
        GET /creator-codes/:id/payouts
        GET /creator-codes/:id/redemptions
        GET /creator-codes/report
        GET /credits/accounts
        GET /credits/accounts/:userId
        GET /credits/transactions
        GET /deliveries
        GET /discounts
        GET /gifts
        GET /gifts/:id/redemptions
        GET /goals
        GET /health
        GET /mails
        GET /mc-component/download
        GET /orders
        GET /orders/:id
        GET /orders/:id/invoice
        GET /orders/:id/refund-preview
        GET /orders/:id/shipping
        GET /orders/export
        GET /payment-events
        GET /payment-providers
        GET /payments/:paymentId/events
        GET /players/:username/summary
        GET /products
        GET /products/:id
        GET /products/image/:fileName
        GET /products/simple
        GET /servers
        GET /settings
        GET /settings/currencies
        GET /settings/invoice/preview
        GET /settings/legal
        GET /shipments
        GET /shipments/:id
        GET /shipments/:id/label
        GET /shipping/carriers
        GET /shipping/carriers/:id/services
        GET /shipping/methods
        GET /shipping/zones
        GET /stats
        GET /subscriptions
        GET /subscriptions/:id
        POST /blocks
        POST /categories
        POST /categories/sort
        POST /comparisons
        POST /comparisons/:id/clone
        POST /coupons
        POST /creator-codes
        POST /creator-codes/:id/payouts
        POST /creator-payouts/:id/cancel
        POST /credits/accounts/:userId/grant
        POST /credits/accounts/:userId/revoke
        POST /deliveries/:id/cancel
        POST /deliveries/:id/retry
        POST /discounts
        POST /gifts
        POST /goals
        POST /mails/:id/retry
        POST /orders
        POST /orders/:id/anonymize
        POST /orders/:id/bank-transfer
        POST /orders/:id/chargeback-actions
        POST /orders/:id/deliveries/rerun
        POST /orders/:id/disputes
        POST /orders/:id/exchange-rate/refresh
        POST /orders/:id/invoice/regenerate
        POST /orders/:id/mails/resend
        POST /orders/:id/refunds
        POST /orders/:id/review
        POST /orders/:id/revoke
        POST /orders/:id/shipments
        POST /orders/:id/shipping/rates
        POST /orders/quote
        POST /payment-events/:eventId/replay
        POST /payment-methods/:id
        POST /payment-methods/:id/actions/:actionId
        POST /payment-methods/:id/reveal
        POST /payment-methods/:id/toggle
        POST /payment-methods/sort
        POST /payments/:paymentId/query
        POST /products
        POST /products/:id/clone
        POST /products/:id/stock
        POST /refunds/:refundId/cancel
        POST /refunds/:refundId/retry
        POST /settings
        POST /settings/credits
        POST /settings/currencies/refresh
        POST /settings/exchange-rate/refresh
        POST /settings/legal
        POST /settings/mail/test
        POST /shipments/:id/cancel
        POST /shipments/:id/retry
        POST /shipments/:id/track
        POST /shipping/carriers/:id
        POST /shipping/carriers/:id/actions/:actionId
        POST /shipping/carriers/:id/reveal
        POST /shipping/carriers/:id/toggle
        POST /shipping/methods
        POST /shipping/methods/sort
        POST /shipping/zones
        POST /shipping/zones/sort
        POST /subscriptions/:id/cancel
        POST /subscriptions/:id/retry
        PUT /categories/:id
        PUT /comparisons/:id
        PUT /coupons/:id
        PUT /creator-codes/:id
        PUT /discounts/:id
        PUT /disputes/:disputeId
        PUT /gifts/:id
        PUT /goals/:id
        PUT /orders/:id/exchange-rate
        PUT /orders/:id/note
        PUT /orders/:id/shipping-address
        PUT /orders/:id/status
        PUT /products/:id
        PUT /servers/:id/settings
        PUT /settings/currencies
        PUT /settings/invoice-sequence
        PUT /shipments/:id
        PUT /shipping/methods/:id
        PUT /shipping/zones/:id
    """.trimIndent().lines().map { it.trim() }.filter { it.isNotEmpty() }

    private val rows = PanelEndpointMatrix.readTsv()

    private val code = PanelEndpointMatrix.codeRoutes()

    @Test
    fun `the TSV has no duplicate method and path and every row is well formed`() {
        assertEquals(rows.size, rows.map { it.key }.toSet().size, "duplicate (method, path): " + rows.groupBy { it.key }.filter { it.value.size > 1 }.keys)

        for (row in rows) {
            assertTrue(row.method in setOf("GET", "POST", "PUT", "DELETE"), "method of ${row.key}")
            assertTrue(row.path.startsWith("/") && !row.path.contains("//") && !row.path.endsWith("/"), "path of ${row.key}")
            assertTrue(row.state == "LIVE" || row.state == "PENDING", "state of ${row.key}")
            row.nodes // throws for an unknown node name
            assertTrue(row.state == "LIVE" || row.slice.isNotBlank(), "a PENDING row names the slice that adds it: ${row.key}")
        }
    }

    @Test
    fun `every endpoint of the contract has a row, and the TSV adds nothing the contract does not know`() {
        assertEquals(contract.size, contract.toSet().size)
        assertEquals(contract.toSet(), rows.map { it.key }.toSet())
    }

    @Test
    fun `every route the code declares is listed with exactly the nodes of its row`() {
        val byKey = rows.associateBy { it.key }
        val problems = mutableListOf<String>()

        for (route in code) {
            val row = byKey[route.key]

            if (row == null) problems += "${route.key} (${route.type.simpleName}) is not in permission-matrix.tsv"
            else if (row.auth != route.auth) problems += "${route.key} (${route.type.simpleName}) declares ${route.auth}, the TSV says ${row.auth}"
        }

        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    @Test
    fun `a route class is declared for no (method, path) twice`() {
        val duplicated = code.groupBy { it.key }.filter { it.value.size > 1 }

        assertTrue(duplicated.isEmpty(), "declared twice: " + duplicated.mapValues { e -> e.value.map { it.type.simpleName } })
    }

    @Test
    fun `every LIVE row has a route and a PENDING row that already has a route agrees with it`() {
        val declared = code.map { it.key }.toSet()

        val missing = rows.filter { it.live && it.key !in declared }.map { it.key }
        assertTrue(missing.isEmpty(), "LIVE in the TSV but no route class: $missing")

        // A route that landed while its row still says PENDING: the auth was compared above; the row only needs flipping to LIVE.
        val stale = rows.filter { !it.live && it.key in declared }.map { "${it.key} (${it.slice})" }
        assertTrue(stale.isEmpty(), "the route exists, flip the row to LIVE: $stale")
    }

    @Test
    fun `OV alone never changes state`() {
        val offenders = rows.filter { OV in it.nodes && it.method != "GET" }.map { it.key }

        assertTrue(offenders.isEmpty(), "a non-GET endpoint any holder of OV may call: $offenders")
    }

    @Test
    fun `everything that moves value needs PAY and nothing else`() {
        val moneyMovers = listOf(
            "POST /creator-codes/:id/payouts", "POST /creator-payouts/:id/cancel", "PUT /orders/:id/status", "PUT /orders/:id/exchange-rate", "POST /orders/:id/exchange-rate/refresh",
            "POST /orders", "POST /orders/quote", "POST /orders/:id/anonymize", "POST /orders/:id/chargeback-actions", "GET /orders/:id/refund-preview", "POST /orders/:id/refunds",
            "POST /refunds/:refundId/retry", "POST /refunds/:refundId/cancel", "POST /orders/:id/review", "POST /orders/:id/bank-transfer", "POST /orders/:id/disputes", "PUT /disputes/:disputeId",
            "POST /payment-events/:eventId/replay", "GET /credits/accounts", "GET /credits/accounts/:userId", "GET /credits/transactions", "POST /credits/accounts/:userId/grant",
            "POST /credits/accounts/:userId/revoke", "POST /subscriptions/:id/cancel", "POST /subscriptions/:id/retry"
        )

        for (key in moneyMovers) assertEquals("P:PAY", rows.single { it.key == key }.auth, key)
    }

    @Test
    fun `secrets and settings need SET`() {
        val settings = rows.filter { it.path.startsWith("/settings") || it.path.startsWith("/payment-") || it.path.startsWith("/shipping/") || it.path == "/health" }

        assertTrue(settings.size >= 30)
        // GET /payment-events is the OV event list (11 14.3); the provider, method, carrier and settings routes are SET.
        for (row in settings.filter { it.path != "/payment-events" && it.path != "/payment-events/:eventId/replay" }) assertEquals("P:SET", row.auth, row.key)
    }

    @Test
    fun `the exceptions of 14_3 are the documented ones`() {
        fun auth(key: String) = rows.single { it.key == key }.auth

        assertEquals("P:ANY", auth("GET /servers"))
        assertEquals("P:ANY", auth("GET /context"))
        assertEquals("P:ANY", auth("GET /categories"))
        assertEquals("P:ANY", auth("GET /products/simple"))
        assertEquals("P:ANY", auth("GET /mc-component/download"))
        assertEquals("P:CAT,PAY", auth("GET /products/:id"))
        assertEquals("P:PAY,DISC", auth("GET /creator-codes/report"))
        assertEquals("P:PAY,DISC", auth("GET /creator-codes/:id/earnings"))
        assertEquals("P:PAY,DISC", auth("GET /creator-codes/:id/payouts"))
        assertEquals("P:OV,PAY", auth("GET /players/:username/summary"))
        assertEquals("P:OM,PAY", auth("GET /orders/:id/invoice"))
        assertEquals("P:OM,PAY", auth("GET /orders/:id/shipping"))
        assertEquals("P:OM", auth("POST /orders/:id/deliveries/rerun"), "OM here, the PAY upgrade for rows that took effect is checked in the route")
        assertTrue(rows.single { it.key == "POST /orders/:id/deliveries/rerun" }.note.contains("PAY"))
        assertEquals("P:STATS", auth("GET /stats"))
    }

    @Test
    fun `rows per auth class are the ones of 14_3`() {
        assertEquals(
            mapOf(
                "P:ANY" to 5, "P:CAT" to 22, "P:CAT,PAY" to 1, "P:DISC" to 19, "P:PAY,DISC" to 3, "P:PAY" to 25, "P:OV" to 11, "P:OV,PAY" to 1,
                "P:OM,PAY" to 2, "P:OM" to 20, "P:STATS" to 1, "P:SET" to 36
            ),
            rows.groupingBy { it.auth }.eachCount()
        )
    }

    @Test
    fun `the node check matches the rows for a user with one node, the umbrella or nothing`() {
        // 11 19.10 item 2 at the unit level: who passes the check of which row (the HTTP proof is E2E-10).
        for (row in rows) {
            assertTrue(PanelEndpointMatrix.allows(emptySet(), true, row.auth), "umbrella passes ${row.key}")
            assertFalse(PanelEndpointMatrix.allows(emptySet(), false, row.auth), "a user with no market node fails ${row.key}")

            for (node in MarketNode.values()) {
                val expected = if (row.nodes.isEmpty()) true else node in row.nodes

                assertEquals(expected, PanelEndpointMatrix.allows(setOf(node), false, row.auth), "${node.shortName} on ${row.key}")
            }
        }

        // The documented examples of 19.10: DISC alone reads the pickers but not /products/:id, PAY alone refunds but cannot edit a coupon.
        fun passes(node: MarketNode, key: String) = PanelEndpointMatrix.allows(setOf(node), false, rows.single { it.key == key }.auth)

        assertTrue(passes(MarketNode.DISCOUNTS, "GET /products/simple"))
        assertTrue(passes(MarketNode.DISCOUNTS, "GET /categories"))
        assertFalse(passes(MarketNode.DISCOUNTS, "GET /products/:id"))
        assertTrue(passes(MarketNode.PAYMENTS, "POST /orders/:id/refunds"))
        assertFalse(passes(MarketNode.PAYMENTS, "PUT /coupons/:id"))
        assertTrue(passes(MarketNode.PAYMENTS, "GET /creator-codes/report"))
        assertFalse(passes(MarketNode.ORDERS_MANAGE, "POST /orders/:id/refunds"))
        assertTrue(passes(MarketNode.ORDERS_MANAGE, "POST /blocks"))
        assertFalse(passes(MarketNode.ORDERS_VIEW, "POST /blocks"))
        assertFalse(passes(MarketNode.SETTINGS, "GET /orders"))
    }

    private companion object {
        val OV = MarketNode.ORDERS_VIEW
    }
}
