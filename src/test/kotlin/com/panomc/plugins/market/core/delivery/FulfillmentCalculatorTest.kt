package com.panomc.plugins.market.core.delivery

import com.panomc.plugins.market.db.model.DeliveryActionType
import com.panomc.plugins.market.db.model.DeliveryPhase
import com.panomc.plugins.market.db.model.DeliverySourceType
import com.panomc.plugins.market.db.model.DeliveryStatus
import com.panomc.plugins.market.db.model.DeliveryStatus.*
import com.panomc.plugins.market.db.model.EntitlementStatus
import com.panomc.plugins.market.db.model.FulfillmentBy
import com.panomc.plugins.market.db.model.FulfillmentStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 08 section 13: one test per row of the table, the effective-row rule, and invariant I21. */
class FulfillmentCalculatorTest {
    private var seq = 0

    private fun row(
        status: DeliveryStatus,
        phase: DeliveryPhase = DeliveryPhase.GRANT,
        attemptGroup: Int = 0,
        item: Long = 1,
        action: String = "a${seq++}",
        server: Long = 0,
        unit: Int = 0,
        source: DeliverySourceType = DeliverySourceType.ORDER_ITEM
    ) = DeliveryRow(
        sourceType = source, orderItemId = item, actionId = action, actionType = DeliveryActionType.COMMAND, phase = phase,
        serverId = server, unitIndex = unit, attemptGroup = attemptGroup, status = status
    )

    private fun status(vararg rows: DeliveryRow, entitlements: List<EntitlementStatus> = emptyList()) =
        FulfillmentCalculator.calculate(rows.toList(), entitlements).status

    // ---- the rows of the table, in order ----------------------------------------------------------------------------

    @Test
    fun `row 1 - an undo that has not happened yet makes the order PARTIAL`() {
        for (open in listOf(PENDING, SCHEDULED, WAITING_SERVER, SENDING, SENT, QUEUED, FAILED)) {
            for (phase in listOf(DeliveryPhase.REVOKE, DeliveryPhase.EXPIRE)) {
                val result = FulfillmentCalculator.calculate(
                    listOf(row(CONFIRMED), row(open, phase)), listOf(EntitlementStatus.REVOKED)
                )

                assertEquals(FulfillmentStatus.PARTIAL, result.status, "$phase $open")
            }
        }
    }

    @Test
    fun `row 2 - every entitlement REVOKED and an undo that is done or cancelled gives REVOKED`() {
        assertEquals(
            FulfillmentStatus.REVOKED,
            status(row(CONFIRMED), row(CONFIRMED, DeliveryPhase.REVOKE), entitlements = listOf(EntitlementStatus.REVOKED, EntitlementStatus.REVOKED))
        )
        assertEquals(
            FulfillmentStatus.REVOKED,
            status(row(CONFIRMED), row(CANCELLED, DeliveryPhase.REVOKE), entitlements = listOf(EntitlementStatus.REVOKED))
        )

        // One entitlement still live: not REVOKED.
        assertNotEquals(
            FulfillmentStatus.REVOKED,
            status(row(CONFIRMED), entitlements = listOf(EntitlementStatus.REVOKED, EntitlementStatus.ACTIVE))
        )
        // No entitlement at all: "at least one exists" fails.
        assertEquals(FulfillmentStatus.FULFILLED, status(row(CONFIRMED)))
    }

    @Test
    fun `row 3 - no effective rows is NONE`() {
        assertEquals(FulfillmentStatus.NONE, status())
        assertEquals(FulfillmentStatus.NONE, status(entitlements = listOf(EntitlementStatus.ACTIVE)))
    }

    @Test
    fun `row 4 - no failed and nothing open is FULFILLED`() {
        assertEquals(FulfillmentStatus.FULFILLED, status(row(CONFIRMED)))
        assertEquals(FulfillmentStatus.FULFILLED, status(row(CONFIRMED), row(CONFIRMED), row(CONFIRMED, DeliveryPhase.RENEW)))
    }

    @Test
    fun `row 5 - no failed and nothing confirmed yet is PENDING`() {
        for (open in listOf(PENDING, SCHEDULED, WAITING_SERVER, WAITING_PLAYER, SENDING, SENT, QUEUED)) {
            assertEquals(FulfillmentStatus.PENDING, status(row(open)), "$open")
            assertEquals(FulfillmentStatus.PENDING, status(row(open), row(open)), "$open")
        }
    }

    @Test
    fun `row 6 - failed with nothing confirmed and nothing open is FAILED`() {
        assertEquals(FulfillmentStatus.FAILED, status(row(FAILED)))
        assertEquals(FulfillmentStatus.FAILED, status(row(FAILED), row(FAILED)))
    }

    @Test
    fun `row 7 - everything else is PARTIAL`() {
        assertEquals(FulfillmentStatus.PARTIAL, status(row(CONFIRMED), row(FAILED)))
        assertEquals(FulfillmentStatus.PARTIAL, status(row(CONFIRMED), row(PENDING)))
        assertEquals(FulfillmentStatus.PARTIAL, status(row(CONFIRMED), row(SENT)))
        assertEquals(FulfillmentStatus.PARTIAL, status(row(FAILED), row(PENDING)))
        assertEquals(FulfillmentStatus.PARTIAL, status(row(CONFIRMED), row(FAILED), row(QUEUED)))
    }

    // ---- effective rows ----------------------------------------------------------------------------------------------

    @Test
    fun `a re-run row supersedes the failed row of attemptGroup 0 (case 76)`() {
        val failed = row(FAILED, action = "a1")

        assertEquals(FulfillmentStatus.FAILED, status(failed))

        val rerun = failed.copy(attemptGroup = 1, status = PENDING)

        assertEquals(FulfillmentStatus.PENDING, status(failed, rerun))
        assertEquals(FulfillmentStatus.FULFILLED, status(failed, rerun.copy(status = CONFIRMED)))
        assertEquals(FulfillmentStatus.FULFILLED, status(rerun.copy(status = CONFIRMED), failed), "the order of the input does not matter")

        // A confirmed first attempt followed by a failed re-run: the re-run is the effective one.
        assertEquals(FulfillmentStatus.FAILED, status(failed.copy(status = CONFIRMED), failed.copy(attemptGroup = 1)))
    }

    @Test
    fun `cancelled rows do not count (case 77), also when a cancelled row supersedes another`() {
        assertEquals(FulfillmentStatus.FULFILLED, status(row(CONFIRMED), row(CANCELLED)))
        assertEquals(FulfillmentStatus.NONE, status(row(CANCELLED)))
        assertEquals(FulfillmentStatus.NONE, status(row(CANCELLED), row(CANCELLED, DeliveryPhase.RENEW)))

        // The cancelled re-run supersedes the failed first attempt: the logical delivery is gone (reduce first, then drop).
        val first = row(FAILED, action = "a9")

        assertEquals(FulfillmentStatus.NONE, status(first, first.copy(attemptGroup = 1, status = CANCELLED)))
    }

    @Test
    fun `the effective row is chosen per item, action, server, unit and phase`() {
        val base = row(FAILED, action = "a1", server = 4, unit = 0)

        // Different server, unit, phase, item or action: each is its own logical delivery.
        val others = listOf(
            base.copy(serverId = 5, attemptGroup = 1, status = CONFIRMED),
            base.copy(unitIndex = 1, attemptGroup = 1, status = CONFIRMED),
            base.copy(phase = DeliveryPhase.RENEW, attemptGroup = 1, status = CONFIRMED),
            base.copy(orderItemId = 2, attemptGroup = 1, status = CONFIRMED),
            base.copy(actionId = "a2", attemptGroup = 1, status = CONFIRMED)
        )

        for (other in others) {
            assertEquals(FulfillmentStatus.PARTIAL, status(base, other), other.toString())
        }

        assertEquals(1, FulfillmentCalculator.effective(listOf(base, base.copy(attemptGroup = 3), base.copy(attemptGroup = 2))).size)
        assertEquals(3, FulfillmentCalculator.effective(listOf(base, base.copy(attemptGroup = 3), base.copy(attemptGroup = 2))).single().attemptGroup)
    }

    @Test
    fun `only ORDER_ITEM rows take part`() {
        assertEquals(FulfillmentStatus.NONE, status(row(FAILED, source = DeliverySourceType.CHARGEBACK_ACTION)))
        assertEquals(FulfillmentStatus.FULFILLED, status(row(CONFIRMED), row(FAILED, source = DeliverySourceType.CREATOR_PAYOUT)))
    }

    @Test
    fun `GRANT and RENEW rows are the delivered goods, an undo row never counts as one`() {
        assertEquals(FulfillmentStatus.FULFILLED, status(row(CONFIRMED, DeliveryPhase.GRANT), row(CONFIRMED, DeliveryPhase.RENEW)))
        // An EXPIRE row that is done leaves the order FULFILLED (the goods were delivered, the term simply ended).
        assertEquals(FulfillmentStatus.FULFILLED, status(row(CONFIRMED), row(CONFIRMED, DeliveryPhase.EXPIRE)))
        assertEquals(FulfillmentStatus.NONE, status(row(CONFIRMED, DeliveryPhase.REVOKE)))
    }

    // ---- invariant I21 and the panel counters ------------------------------------------------------------------------

    @Test
    fun `I21 - an order is never REVOKED while an effective REVOKE or EXPIRE row is not CONFIRMED or CANCELLED`() {
        var checked = 0

        for (undo in DeliveryStatus.entries) {
            for (phase in listOf(DeliveryPhase.REVOKE, DeliveryPhase.EXPIRE)) {
                for (grant in listOf(CONFIRMED, FAILED, SENT, CANCELLED)) {
                    val result = status(row(grant), row(undo, phase), entitlements = listOf(EntitlementStatus.REVOKED))

                    if (undo != CONFIRMED && undo != CANCELLED) {
                        assertNotEquals(FulfillmentStatus.REVOKED, result, "grant $grant, $phase $undo")
                    }

                    checked++
                }
            }
        }

        assertEquals(10 * 2 * 4, checked)
    }

    @Test
    fun `a failed undo is superseded by a successful re-run of the undo`() {
        val failed = row(FAILED, DeliveryPhase.REVOKE, action = "r1")

        assertEquals(FulfillmentStatus.PARTIAL, status(row(CONFIRMED), failed, entitlements = listOf(EntitlementStatus.REVOKED)))
        assertEquals(
            FulfillmentStatus.REVOKED,
            status(row(CONFIRMED), failed, failed.copy(attemptGroup = 1, status = CONFIRMED), entitlements = listOf(EntitlementStatus.REVOKED))
        )
    }

    @Test
    fun `revokePending counts open undo rows, revokeFailed the failed ones (R2-1, R2-2)`() {
        val waiting = FulfillmentCalculator.calculate(listOf(row(CONFIRMED), row(WAITING_SERVER, DeliveryPhase.REVOKE)), listOf(EntitlementStatus.REVOKED))

        assertEquals(FulfillmentCalculator.Result(FulfillmentStatus.PARTIAL, 1, 0), waiting)

        val failed = FulfillmentCalculator.calculate(listOf(row(CONFIRMED), row(FAILED, DeliveryPhase.REVOKE)), listOf(EntitlementStatus.REVOKED))

        assertEquals(FulfillmentCalculator.Result(FulfillmentStatus.PARTIAL, 0, 1), failed)

        val mixed = FulfillmentCalculator.calculate(
            listOf(row(CONFIRMED), row(SENT, DeliveryPhase.REVOKE), row(QUEUED, DeliveryPhase.EXPIRE), row(FAILED, DeliveryPhase.REVOKE), row(CONFIRMED, DeliveryPhase.REVOKE), row(CANCELLED, DeliveryPhase.REVOKE))
        )

        assertEquals(2, mixed.revokePending)
        assertEquals(1, mixed.revokeFailed)

        // The server comes back and confirms: REVOKED, nothing pending.
        val done = FulfillmentCalculator.calculate(listOf(row(CONFIRMED), row(CONFIRMED, DeliveryPhase.REVOKE)), listOf(EntitlementStatus.REVOKED))

        assertEquals(FulfillmentCalculator.Result(FulfillmentStatus.REVOKED, 0, 0), done)
    }

    // ---- authority and per-item values -------------------------------------------------------------------------------

    @Test
    fun `an order fulfilled by its gateway stays NONE (R2-9)`() {
        val result = FulfillmentCalculator.calculate(emptyList(), listOf(EntitlementStatus.REVOKED), FulfillmentBy.GATEWAY)

        assertEquals(FulfillmentCalculator.Result(FulfillmentStatus.NONE, 0, 0), result)
        assertEquals(FulfillmentStatus.NONE, FulfillmentCalculator.status(listOf(row(CONFIRMED)), emptyList(), FulfillmentBy.GATEWAY))
    }

    @Test
    fun `the same function over one item gives the value of that item`() {
        val rows = listOf(row(CONFIRMED, item = 1), row(FAILED, item = 2), row(CONFIRMED, item = 2), row(SENT, item = 3))

        val values = FulfillmentCalculator.perItem(rows, mapOf(4L to listOf(EntitlementStatus.ACTIVE)))

        assertEquals(
            mapOf(
                1L to FulfillmentStatus.FULFILLED,
                2L to FulfillmentStatus.PARTIAL,
                3L to FulfillmentStatus.PENDING,
                4L to FulfillmentStatus.NONE
            ),
            values
        )
    }

    @Test
    fun `isOpen is true for every status that can still change by itself`() {
        val open = DeliveryStatus.entries.filter { FulfillmentCalculator.isOpen(it) }.toSet()

        assertEquals(setOf(PENDING, SCHEDULED, WAITING_SERVER, WAITING_PLAYER, SENDING, SENT, QUEUED), open)
        assertTrue(!FulfillmentCalculator.isOpen(CONFIRMED) && !FulfillmentCalculator.isOpen(FAILED) && !FulfillmentCalculator.isOpen(CANCELLED))
    }
}
