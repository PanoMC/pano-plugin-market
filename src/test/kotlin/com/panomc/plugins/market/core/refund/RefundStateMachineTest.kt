package com.panomc.plugins.market.core.refund

import com.panomc.plugins.market.core.refund.RefundEffect.ClearFailure
import com.panomc.plugins.market.core.refund.RefundEffect.ClearQuery
import com.panomc.plugins.market.core.refund.RefundEffect.PanelAlert
import com.panomc.plugins.market.core.refund.RefundEffect.RecordFailure
import com.panomc.plugins.market.core.refund.RefundEffect.ResendToGateway
import com.panomc.plugins.market.core.refund.RefundEffect.RunOrderEffects
import com.panomc.plugins.market.core.refund.RefundEffect.ScheduleQuery
import com.panomc.plugins.market.core.refund.RefundEffect.StampCompleted
import com.panomc.plugins.market.core.refund.RefundTransition.Move
import com.panomc.plugins.market.core.refund.RefundTransition.NoOp
import com.panomc.plugins.market.core.refund.RefundTransition.Rejected
import com.panomc.plugins.market.db.model.RefundOrigin
import com.panomc.plugins.market.db.model.RefundStatus
import com.panomc.plugins.market.db.model.RefundStatus.CANCELLED
import com.panomc.plugins.market.db.model.RefundStatus.FAILED
import com.panomc.plugins.market.db.model.RefundStatus.PENDING
import com.panomc.plugins.market.db.model.RefundStatus.REQUESTED
import com.panomc.plugins.market.db.model.RefundStatus.SUCCEEDED
import com.panomc.plugins.market.spi.payment.RefundState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Random

/** `RefundStateMachine` (21 section 3.7, 00 section 7.3): the whole table, status by status and event by event (RD-U3). */
class RefundStateMachineTest {
    private val statuses = RefundStatus.values().toList()

    private fun decide(status: RefundStatus, event: RefundEvent, waiting: Boolean = false) =
        RefundStateMachine.decide(RefundRowState(status, waiting), event)

    private fun reported(state: RefundState, code: String? = null, message: String? = null) = RefundEvent.Reported(state, code, message)

    private val succeeded = listOf(StampCompleted, ClearQuery, RunOrderEffects)
    private val succeededLate = listOf(ClearFailure) + succeeded
    private val pendingQuery = listOf(ScheduleQuery(5L * 60 * 1000))

    // ---------------------------------------------------------------- the table of 21 section 3.7, row by row

    @Test
    fun `REQUESTED and PENDING reach SUCCEEDED and run O10`() {
        for (s in listOf(REQUESTED, PENDING)) {
            assertEquals(Move(SUCCEEDED, succeeded), decide(s, reported(RefundState.SUCCEEDED)), s.name)
        }
        // a waiting revokeFirst row can be settled too (a gateway-side refund that raced it)
        assertEquals(Move(SUCCEEDED, succeeded), decide(REQUESTED, reported(RefundState.SUCCEEDED), waiting = true))
    }

    @Test
    fun `a late SUCCEEDED for a FAILED or CANCELLED row wins and clears the failure`() {
        for (s in listOf(FAILED, CANCELLED)) {
            assertEquals(Move(SUCCEEDED, succeededLate), decide(s, reported(RefundState.SUCCEEDED)), s.name)
        }
    }

    @Test
    fun `SUCCEEDED is final for every event of the outside world and refuses the admin`() {
        for (state in RefundState.values()) {
            assertEquals(NoOp, decide(SUCCEEDED, reported(state, "X", "y")), state.name)
        }
        assertEquals(NoOp, decide(SUCCEEDED, RefundEvent.RevokeTimeout, waiting = true))
        assertEquals(NoOp, decide(SUCCEEDED, RefundEvent.ChargebackOpened))
        assertEquals(Rejected("INVALID_STATE", SUCCEEDED), decide(SUCCEEDED, RefundEvent.AdminRetry))
        assertEquals(Rejected("INVALID_STATE", SUCCEEDED), decide(SUCCEEDED, RefundEvent.AdminCancel))
    }

    @Test
    fun `a gateway Pending moves REQUESTED to PENDING and schedules the first query after five minutes`() {
        assertEquals(Move(PENDING, pendingQuery), decide(REQUESTED, reported(RefundState.PENDING)))
        for (s in statuses - REQUESTED) assertEquals(NoOp, decide(s, reported(RefundState.PENDING)), s.name)
    }

    @Test
    fun `Failed moves REQUESTED and PENDING to FAILED and keeps the code and message`() {
        val effects = listOf(RecordFailure("CARD_REFUSED", "refused"), ClearQuery)
        for (s in listOf(REQUESTED, PENDING)) {
            assertEquals(Move(FAILED, effects), decide(s, reported(RefundState.FAILED, "CARD_REFUSED", "refused")), s.name)
        }
        assertEquals(Move(FAILED, listOf(RecordFailure(null, null), ClearQuery)), decide(PENDING, reported(RefundState.FAILED)))
        for (s in listOf(FAILED, CANCELLED, SUCCEEDED)) assertEquals(NoOp, decide(s, reported(RefundState.FAILED, "X")), s.name)
    }

    @Test
    fun `a gateway Cancelled moves only PENDING to CANCELLED`() {
        assertEquals(Move(CANCELLED, listOf(ClearQuery)), decide(PENDING, reported(RefundState.CANCELLED)))
        for (s in statuses - PENDING) assertEquals(NoOp, decide(s, reported(RefundState.CANCELLED)), s.name)
    }

    @Test
    fun `retry sends FAILED back to REQUESTED and re-sends an unknown outcome with the same key`() {
        assertEquals(Move(REQUESTED, listOf(ClearFailure, ResendToGateway)), decide(FAILED, RefundEvent.AdminRetry))
        // unsent or unknown outcome: the row stays REQUESTED and the call goes out again
        assertEquals(Move(REQUESTED, listOf(ResendToGateway)), decide(REQUESTED, RefundEvent.AdminRetry))
        // a revokeFirst row that still waits has nothing to re-send; a PENDING one is the gateway's; the others are over
        assertEquals(Rejected("INVALID_STATE", REQUESTED), decide(REQUESTED, RefundEvent.AdminRetry, waiting = true))
        for (s in listOf(PENDING, CANCELLED, SUCCEEDED)) assertEquals(Rejected("INVALID_STATE", s), decide(s, RefundEvent.AdminRetry), s.name)
    }

    @Test
    fun `cancel works from REQUESTED and FAILED only, a PENDING row cannot be cancelled by market`() {
        for (waiting in listOf(false, true)) {
            assertEquals(Move(CANCELLED, listOf(ClearQuery)), decide(REQUESTED, RefundEvent.AdminCancel, waiting))
        }
        assertEquals(Move(CANCELLED, listOf(ClearQuery)), decide(FAILED, RefundEvent.AdminCancel))
        for (s in listOf(PENDING, CANCELLED, SUCCEEDED)) assertEquals(Rejected("INVALID_STATE", s), decide(s, RefundEvent.AdminCancel), s.name)
    }

    @Test
    fun `the revokeFirst timeout cancels only a waiting row, with the code and the alert`() {
        val expected = Move(CANCELLED, listOf(RecordFailure("REVOKE_TIMEOUT", null), ClearQuery, PanelAlert("REVOKE_TIMEOUT")))
        assertEquals(expected, decide(REQUESTED, RefundEvent.RevokeTimeout, waiting = true))
        // a row that was already released and sent is not the timeout's business (the job races the release)
        assertEquals(NoOp, decide(REQUESTED, RefundEvent.RevokeTimeout, waiting = false))
        for (s in statuses - REQUESTED) assertEquals(NoOp, decide(s, RefundEvent.RevokeTimeout, waiting = true), s.name)
        assertEquals("REVOKE_TIMEOUT", RefundStateMachine.REVOKE_TIMEOUT)
        assertEquals(24L * 3600 * 1000, RefundStateMachine.REVOKE_FIRST_TIMEOUT_MS)
    }

    @Test
    fun `a dispute cancels the refunds that were not executed`() {
        assertEquals(Move(CANCELLED, listOf(RecordFailure("CHARGEBACK", null), ClearQuery)), decide(REQUESTED, RefundEvent.ChargebackOpened))
        assertEquals(Move(CANCELLED, listOf(RecordFailure("CHARGEBACK", null), ClearQuery)), decide(REQUESTED, RefundEvent.ChargebackOpened, waiting = true))
        // a refund the gateway holds (PENDING) or has done is not touched; failed and cancelled rows are over
        for (s in statuses - REQUESTED) assertEquals(NoOp, decide(s, RefundEvent.ChargebackOpened), s.name)
    }

    // ---------------------------------------------------------------- inserts

    @Test
    fun `a panel or system refund starts REQUESTED and a gateway refund in the state it reported`() {
        for (origin in listOf(RefundOrigin.PANEL, RefundOrigin.SYSTEM)) {
            assertEquals(Move(REQUESTED, emptyList()), RefundStateMachine.insert(origin))
        }
        assertEquals(Move(SUCCEEDED, succeeded), RefundStateMachine.insert(RefundOrigin.GATEWAY, RefundState.SUCCEEDED))
        assertEquals(Move(PENDING, pendingQuery), RefundStateMachine.insert(RefundOrigin.GATEWAY, RefundState.PENDING))
        assertEquals(Move(FAILED, listOf(RecordFailure(null, null), ClearQuery)), RefundStateMachine.insert(RefundOrigin.GATEWAY, RefundState.FAILED))
        assertEquals(Move(CANCELLED, listOf(ClearQuery)), RefundStateMachine.insert(RefundOrigin.GATEWAY, RefundState.CANCELLED))
        assertThrows(IllegalArgumentException::class.java) { RefundStateMachine.insert(RefundOrigin.GATEWAY) }
    }

    // ---------------------------------------------------------------- the whole grid against a literal expectation

    @Test
    fun `every status and event gives exactly the transition of the table`() {
        val s = RefundState.SUCCEEDED
        val p = RefundState.PENDING
        val f = RefundState.FAILED
        val c = RefundState.CANCELLED
        // (status, waiting) -> event -> expected; everything the table does not list is NoOp (events) or Rejected (admin)
        val grid = linkedMapOf<Pair<RefundStatus, Boolean>, Map<RefundEvent, RefundTransition>>()
        grid[REQUESTED to false] = mapOf(
            reported(s) to Move(SUCCEEDED, succeeded), reported(p) to Move(PENDING, pendingQuery),
            reported(f) to Move(FAILED, listOf(RecordFailure(null, null), ClearQuery)), reported(c) to NoOp,
            RefundEvent.AdminRetry to Move(REQUESTED, listOf(ResendToGateway)),
            RefundEvent.AdminCancel to Move(CANCELLED, listOf(ClearQuery)),
            RefundEvent.RevokeTimeout to NoOp,
            RefundEvent.ChargebackOpened to Move(CANCELLED, listOf(RecordFailure("CHARGEBACK", null), ClearQuery))
        )
        grid[REQUESTED to true] = mapOf(
            reported(s) to Move(SUCCEEDED, succeeded), reported(p) to Move(PENDING, pendingQuery),
            reported(f) to Move(FAILED, listOf(RecordFailure(null, null), ClearQuery)), reported(c) to NoOp,
            RefundEvent.AdminRetry to Rejected("INVALID_STATE", REQUESTED),
            RefundEvent.AdminCancel to Move(CANCELLED, listOf(ClearQuery)),
            RefundEvent.RevokeTimeout to Move(CANCELLED, listOf(RecordFailure("REVOKE_TIMEOUT", null), ClearQuery, PanelAlert("REVOKE_TIMEOUT"))),
            RefundEvent.ChargebackOpened to Move(CANCELLED, listOf(RecordFailure("CHARGEBACK", null), ClearQuery))
        )
        grid[PENDING to false] = mapOf(
            reported(s) to Move(SUCCEEDED, succeeded), reported(p) to NoOp,
            reported(f) to Move(FAILED, listOf(RecordFailure(null, null), ClearQuery)),
            reported(c) to Move(CANCELLED, listOf(ClearQuery)),
            RefundEvent.AdminRetry to Rejected("INVALID_STATE", PENDING), RefundEvent.AdminCancel to Rejected("INVALID_STATE", PENDING),
            RefundEvent.RevokeTimeout to NoOp, RefundEvent.ChargebackOpened to NoOp
        )
        grid[FAILED to false] = mapOf(
            reported(s) to Move(SUCCEEDED, succeededLate), reported(p) to NoOp, reported(f) to NoOp, reported(c) to NoOp,
            RefundEvent.AdminRetry to Move(REQUESTED, listOf(ClearFailure, ResendToGateway)),
            RefundEvent.AdminCancel to Move(CANCELLED, listOf(ClearQuery)),
            RefundEvent.RevokeTimeout to NoOp, RefundEvent.ChargebackOpened to NoOp
        )
        grid[CANCELLED to false] = mapOf(
            reported(s) to Move(SUCCEEDED, succeededLate), reported(p) to NoOp, reported(f) to NoOp, reported(c) to NoOp,
            RefundEvent.AdminRetry to Rejected("INVALID_STATE", CANCELLED), RefundEvent.AdminCancel to Rejected("INVALID_STATE", CANCELLED),
            RefundEvent.RevokeTimeout to NoOp, RefundEvent.ChargebackOpened to NoOp
        )
        grid[SUCCEEDED to false] = mapOf(
            reported(s) to NoOp, reported(p) to NoOp, reported(f) to NoOp, reported(c) to NoOp,
            RefundEvent.AdminRetry to Rejected("INVALID_STATE", SUCCEEDED), RefundEvent.AdminCancel to Rejected("INVALID_STATE", SUCCEEDED),
            RefundEvent.RevokeTimeout to NoOp, RefundEvent.ChargebackOpened to NoOp
        )
        var cells = 0
        for ((key, row) in grid) {
            assertEquals(8, row.size, "row $key must cover all eight events")
            for ((event, expected) in row) {
                assertEquals(expected, decide(key.first, event, key.second), "${key.first} waiting=${key.second} on $event")
                cells++
            }
        }
        assertEquals(48, cells)
        // the grid covers every status
        assertEquals(statuses.toSet(), grid.keys.map { it.first }.toSet())
    }

    // ---------------------------------------------------------------- properties over random histories

    @Test
    fun `no history runs O10 twice, leaves SUCCEEDED or invents an edge the spec does not have`() {
        val allowed = setOf(
            REQUESTED to SUCCEEDED, REQUESTED to PENDING, REQUESTED to FAILED, PENDING to SUCCEEDED, PENDING to FAILED,
            PENDING to CANCELLED, FAILED to REQUESTED, REQUESTED to CANCELLED, FAILED to CANCELLED, FAILED to SUCCEEDED,
            CANCELLED to SUCCEEDED, REQUESTED to REQUESTED
        )
        val events = listOf<RefundEvent>(
            reported(RefundState.SUCCEEDED), reported(RefundState.PENDING), reported(RefundState.FAILED, "E", "m"), reported(RefundState.CANCELLED),
            RefundEvent.AdminRetry, RefundEvent.AdminCancel, RefundEvent.RevokeTimeout, RefundEvent.ChargebackOpened
        )
        val rnd = Random(114L)
        var o10 = 0
        var histories = 0
        repeat(20000) {
            var status = REQUESTED
            var waiting = rnd.nextBoolean()
            var runs = 0
            repeat(1 + rnd.nextInt(12)) {
                val e = events[rnd.nextInt(events.size)]
                when (val t = RefundStateMachine.decide(RefundRowState(status, waiting), e)) {
                    is Move -> {
                        assertTrue(status != SUCCEEDED, "a SUCCEEDED row moved on $e")
                        assertTrue((status to t.to) in allowed, "$status -> ${t.to} on $e")
                        if (RunOrderEffects in t.effects) {
                            runs++
                            // O10 comes last, after the row bookkeeping, and only when the row ends SUCCEEDED
                            assertEquals(SUCCEEDED, t.to)
                            assertEquals(RunOrderEffects, t.effects.last())
                        }
                        waiting = false
                        status = t.to
                    }
                    is Rejected -> assertEquals(status, t.state)
                    NoOp -> Unit
                }
            }
            assertTrue(runs <= 1, "O10 ran $runs times")
            o10 += runs
            histories++
        }
        assertEquals(20000, histories)
        assertTrue(o10 > 2000, "only $o10 histories reached SUCCEEDED")
    }

    @Test
    fun `a replay of the same event never changes a settled row`() {
        val events = listOf<RefundEvent>(
            reported(RefundState.SUCCEEDED), reported(RefundState.PENDING), reported(RefundState.FAILED, "E"), reported(RefundState.CANCELLED),
            RefundEvent.RevokeTimeout, RefundEvent.ChargebackOpened
        )
        for (status in statuses) for (waiting in listOf(false, true)) for (e in events) {
            val first = RefundStateMachine.decide(RefundRowState(status, waiting), e)
            if (first is Move && first.to != REQUESTED) {
                // an event of the outside world is idempotent: the same event on the new status changes nothing
                assertEquals(NoOp, RefundStateMachine.decide(RefundRowState(first.to, false), e), "$status waiting=$waiting $e")
            }
        }
    }
}
