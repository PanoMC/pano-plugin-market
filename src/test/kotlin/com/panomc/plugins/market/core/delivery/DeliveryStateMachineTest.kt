package com.panomc.plugins.market.core.delivery

import com.panomc.plugins.market.core.delivery.DeliveryEffect.*
import com.panomc.plugins.market.core.delivery.DeliveryTransition.Move
import com.panomc.plugins.market.core.delivery.DeliveryTransition.NoOp
import com.panomc.plugins.market.core.delivery.DeliveryTransition.Rejected
import com.panomc.plugins.market.db.model.DeliveryActionType
import com.panomc.plugins.market.db.model.DeliveryPhase
import com.panomc.plugins.market.db.model.DeliveryStatus
import com.panomc.plugins.market.db.model.DeliveryStatus.*
import com.panomc.plugins.market.db.model.DeliveryTransport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * `DeliveryStateMachine.decide` against the table of 08 section 6 (D1 - D22), exhaustively: every one of the 10 statuses
 * against every event, for the three kinds of row that exist (inline `CREDIT`, inline `WEBHOOK`, server `COMMAND`).
 * Allowed moves are listed once per event in [cases]; every other pair must be `NoOp` (events of the machine) or
 * `Rejected` (the two admin operations).
 */
class DeliveryStateMachineTest {
    private val now = 1_700_000_000_000L
    private val rules = DeliveryRules(maxAttempts = 5, ackTimeoutSeconds = 30)

    private enum class Kind(val transport: DeliveryTransport, val type: DeliveryActionType) {
        CREDIT(DeliveryTransport.INLINE, DeliveryActionType.CREDIT),
        WEBHOOK(DeliveryTransport.INLINE, DeliveryActionType.WEBHOOK),
        COMMAND(DeliveryTransport.MARKET_MC, DeliveryActionType.COMMAND)
    }

    private val inline = listOf(Kind.CREDIT, Kind.WEBHOOK)
    private val server = listOf(Kind.COMMAND)
    private val every = Kind.entries

    /**
     * A row in which every time-based precondition of every event holds: past `runAfter`, due `nextAttemptAt`, expired
     * claim, expired wait. Cases that need the opposite build their own row.
     */
    private fun row(status: DeliveryStatus, kind: Kind, phase: DeliveryPhase = DeliveryPhase.GRANT) = DeliveryRow(
        orderItemId = 7, actionId = "a1", actionType = kind.type, phase = phase,
        serverId = if (kind.transport == DeliveryTransport.MARKET_MC) 4 else 0,
        transport = kind.transport, status = status, attempts = 1,
        runAfter = now - 60_000, nextAttemptAt = now - 1, claimedUntil = if (status == SENDING) now - 1 else null,
        waitUntil = now - 2 * 3_600_000L, sentAt = now - 10_000
    )

    private class Case(
        val name: String,
        val event: DeliveryEvent,
        /** What every pair not in [moves] must give. */
        val otherwise: (DeliveryStatus) -> DeliveryTransition = { NoOp },
        val prep: (DeliveryRow) -> DeliveryRow = { it },
        val moves: Map<Pair<Kind, DeliveryStatus>, Pair<DeliveryStatus, String>>
    )

    private fun moves(kinds: List<Kind>, from: List<DeliveryStatus>, to: DeliveryStatus, rule: String): Map<Pair<Kind, DeliveryStatus>, Pair<DeliveryStatus, String>> =
        kinds.flatMap { k -> from.map { s -> (k to s) to (to to rule) } }.toMap()

    private val unsent = listOf(PENDING, SCHEDULED, WAITING_SERVER, WAITING_PLAYER)
    private val inFlight = listOf(SENT, QUEUED)
    private val notCancellable = listOf(SENDING, CONFIRMED, FAILED, CANCELLED)
    private val nonTerminal = unsent + inFlight + SENDING

    private val rejectCancel: (DeliveryStatus) -> DeliveryTransition = { Rejected(DeliveryError.DELIVERY_NOT_CANCELLABLE) }
    private val rejectRetry: (DeliveryStatus) -> DeliveryTransition = { Rejected(DeliveryError.DELIVERY_NOT_RETRYABLE) }

    private val cases: List<Case> = listOf(
        Case("D1 promote", DeliveryEvent.Promote, moves = moves(every, listOf(SCHEDULED), PENDING, "D1")),
        Case("D2 claim", DeliveryEvent.Claim, moves = moves(inline, listOf(PENDING), SENDING, "D2")),
        Case(
            "D3 inline succeeded", DeliveryEvent.InlineSucceeded("{\"ok\":1}"),
            moves = moves(listOf(Kind.CREDIT), listOf(SENDING), CONFIRMED, "D3") + moves(listOf(Kind.WEBHOOK), listOf(SENDING), SENT, "D3")
        ),
        Case("D4 retryable error", DeliveryEvent.InlineFailed(DeliveryError.DB_ERROR, "x", retryable = true), moves = moves(inline, listOf(SENDING), PENDING, "D4")),
        Case("D5 non-retryable error", DeliveryEvent.InlineFailed(DeliveryError.INVALID_PLAYER, "x", retryable = false), moves = moves(inline, listOf(SENDING), FAILED, "D5")),
        Case("D6 stale claim", DeliveryEvent.ClaimExpired, moves = moves(every, listOf(SENDING), PENDING, "D6")),
        Case(
            "D7 server not ready", DeliveryEvent.ServerNotReady(DeliveryError.SERVER_OFFLINE),
            moves = moves(server, listOf(PENDING, WAITING_SERVER), WAITING_SERVER, "D7")
        ),
        Case(
            "D8 / D9 offer", DeliveryEvent.Offer,
            moves = moves(server, listOf(PENDING, WAITING_SERVER), SENT, "D8") + moves(server, listOf(SENT), SENT, "D9")
        ),
        Case(
            "D10 re-offer budget exhausted", DeliveryEvent.Offer, prep = { it.copy(attempts = 10) },
            moves = moves(server, listOf(PENDING, WAITING_SERVER), SENT, "D8") + moves(server, listOf(SENT), FAILED, "D10")
        ),
        Case("D11 result QUEUED", DeliveryEvent.ServerResult(ResultStatus.QUEUED), moves = moves(server, listOf(SENT), QUEUED, "D11")),
        Case("D12 result DONE", DeliveryEvent.ServerResult(ResultStatus.DONE), moves = moves(server, inFlight, CONFIRMED, "D12")),
        Case("D13 result FAILED", DeliveryEvent.ServerResult(ResultStatus.FAILED, DeliveryError.COMMAND_ERROR, "boom"), moves = moves(server, inFlight, FAILED, "D13")),
        Case("D14 result EXPIRED", DeliveryEvent.ServerResult(ResultStatus.EXPIRED), moves = moves(server, inFlight, FAILED, "D14")),
        Case("D14 wait expired (job)", DeliveryEvent.WaitExpired, moves = moves(server, inFlight, FAILED, "D14")),
        Case(
            "D15 result CANCELLED after a cancel request", DeliveryEvent.ServerResult(ResultStatus.CANCELLED),
            prep = { it.copy(cancelRequestedAt = now - 5) }, moves = moves(server, inFlight, CANCELLED, "D15")
        ),
        Case(
            "D15 result UNKNOWN after a cancel request", DeliveryEvent.ServerResult(ResultStatus.UNKNOWN),
            prep = { it.copy(cancelRequestedAt = now - 5) }, moves = moves(server, inFlight, CANCELLED, "D15")
        ),
        Case(
            "CANCELLED answer to a cancel nobody asked for is a failure", DeliveryEvent.ServerResult(ResultStatus.CANCELLED),
            moves = moves(server, inFlight, FAILED, "D13")
        ),
        Case("UNKNOWN answer without a cancel request is ignored", DeliveryEvent.ServerResult(ResultStatus.UNKNOWN), moves = emptyMap()),
        Case(
            "D16 / D17 cancel by an end flow", DeliveryEvent.Cancel(DeliveryError.ORDER_REVOKED), otherwise = rejectCancel,
            moves = moves(every, unsent, CANCELLED, "D16") + moves(every, listOf(SENT), SENT, "D17") + moves(every, listOf(QUEUED), QUEUED, "D17") +
                moves(inline, listOf(SENDING), SENDING, "D17")
        ),
        Case(
            "D16 / D17 admin cancel (a claimed row is not cancellable, 14.3)", DeliveryEvent.Cancel(DeliveryError.CANCELLED_BY_ADMIN), otherwise = rejectCancel,
            moves = moves(every, unsent, CANCELLED, "D16") + moves(every, listOf(SENT), SENT, "D17") + moves(every, listOf(QUEUED), QUEUED, "D17")
        ),
        Case(
            "D4 retryable error after an end-flow cancel request", DeliveryEvent.InlineFailed(DeliveryError.DB_ERROR, "x", retryable = true),
            prep = { it.copy(cancelRequestedAt = now - 5, lastErrorCode = DeliveryError.ORDER_REVOKED) }, moves = moves(inline, listOf(SENDING), CANCELLED, "D4")
        ),
        Case(
            "D5 error after an end-flow cancel request", DeliveryEvent.InlineFailed(DeliveryError.INVALID_PLAYER, "x"),
            prep = { it.copy(cancelRequestedAt = now - 5, lastErrorCode = DeliveryError.ORDER_REVOKED) }, moves = moves(inline, listOf(SENDING), FAILED, "D5")
        ),
        Case(
            "D3 success after an end-flow cancel request", DeliveryEvent.InlineSucceeded(),
            prep = { it.copy(cancelRequestedAt = now - 5, lastErrorCode = DeliveryError.ORDER_REVOKED) },
            moves = moves(listOf(Kind.CREDIT), listOf(SENDING), CONFIRMED, "D3") + moves(listOf(Kind.WEBHOOK), listOf(SENDING), SENT, "D3")
        ),
        Case(
            "D6 stale claim after an end-flow cancel request", DeliveryEvent.ClaimExpired,
            prep = { it.copy(cancelRequestedAt = now - 5, lastErrorCode = DeliveryError.ORDER_REVOKED) }, moves = moves(every, listOf(SENDING), CANCELLED, "D6")
        ),
        Case(
            "D18 / D19 retry", DeliveryEvent.Retry, otherwise = rejectRetry,
            moves = moves(every, listOf(FAILED, WAITING_SERVER), PENDING, "D18") + moves(every, listOf(SENT), SENT, "D19")
        ),
        Case("D20 server removed", DeliveryEvent.ServerRemoved, moves = moves(server, nonTerminal, FAILED, "D20")),
        Case(
            "D12 webhook succeeded", DeliveryEvent.WebhookSucceeded,
            prep = { if (it.status == FAILED) it.copy(lastErrorCode = DeliveryError.WEBHOOK_DEAD) else it },
            moves = moves(listOf(Kind.WEBHOOK), listOf(SENT, FAILED), CONFIRMED, "D12")
        ),
        Case("D21 webhook dead", DeliveryEvent.WebhookDead, moves = moves(listOf(Kind.WEBHOOK), listOf(SENT), FAILED, "D21")),
        Case(
            "D22 gate open, nothing delivered", DeliveryEvent.GateOpened(nothingDelivered = true),
            prep = { it.copy(phase = DeliveryPhase.REVOKE) },
            // A held server undo is WAITING_SERVER once D7 labelled it, so the gate must reach that state too.
            moves = moves(every, listOf(PENDING), CANCELLED, "D22") + moves(server, listOf(WAITING_SERVER), CANCELLED, "D22")
        )
    )

    @Test
    fun `every status times every event times every row kind equals the table`() {
        assertEquals(10, DeliveryStatus.entries.size)

        var checked = 0
        var moved = 0

        for (case in cases) {
            for (kind in Kind.entries) {
                for (status in DeliveryStatus.entries) {
                    val label = "${case.name}: $kind x $status"
                    val result = DeliveryStateMachine.decide(case.prep(row(status, kind)), case.event, now, rules, Random(7))
                    val expected = case.moves[kind to status]

                    if (expected == null) {
                        assertEquals(case.otherwise(status), result, label)
                    } else {
                        assertTrue(result is Move, "$label: $result")
                        result as Move
                        assertEquals(expected.first, result.to, label)
                        assertEquals(expected.second, result.rule, label)
                        moved++
                    }

                    checked++
                }
            }
        }

        assertEquals(cases.size * 3 * 10, checked)
        assertEquals(cases.sumOf { it.moves.size }, moved)
    }

    @Test
    fun `every rule D1 to D22 is produced by at least one case`() {
        val seen = HashSet<String>()

        for (case in cases) {
            for (kind in Kind.entries) {
                for (status in DeliveryStatus.entries) {
                    val result = DeliveryStateMachine.decide(case.prep(row(status, kind)), case.event, now, rules, Random(7))
                    if (result is Move) seen += result.rule
                }
            }
        }

        assertEquals((1..22).map { "D$it" }.toSet(), seen)
    }

    @Test
    fun `server rows are never SENDING and WAITING_PLAYER is never produced`() {
        for (case in cases) {
            for (kind in Kind.entries) {
                for (status in DeliveryStatus.entries) {
                    val result = DeliveryStateMachine.decide(case.prep(row(status, kind)), case.event, now, rules, Random(7))

                    if (result is Move) {
                        assertNotEquals(WAITING_PLAYER, result.to, "${case.name}: $kind x $status")

                        if (kind.transport == DeliveryTransport.MARKET_MC) {
                            assertNotEquals(SENDING, result.to, "${case.name}: $kind x $status")
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `a row that is WAITING_PLAYER can only be cancelled or failed by a server removal`() {
        val waiting = row(WAITING_PLAYER, Kind.COMMAND)

        for (case in cases) {
            val result = DeliveryStateMachine.decide(case.prep(waiting), case.event, now, rules, Random(7))

            if (result is Move) assertTrue(result.rule == "D16" || result.rule == "D20", "${case.name}: ${result.rule}")
        }
    }

    // ---- the effects of each rule -----------------------------------------------------------------------------------

    private fun move(row: DeliveryRow, event: DeliveryEvent, random: Random = Random(1)): Move {
        val result = DeliveryStateMachine.decide(row, event, now, rules, random)
        assertTrue(result is Move, "$event on $row: $result")
        return result as Move
    }

    @Test
    fun `D1 promotes at runAfter and not before`() {
        val scheduled = row(SCHEDULED, Kind.COMMAND).copy(runAfter = now)

        assertEquals(Move(PENDING, listOf(SetNextAttemptAt(now)), "D1"), move(scheduled, DeliveryEvent.Promote))
        assertEquals(NoOp, DeliveryStateMachine.decide(scheduled.copy(runAfter = now + 1), DeliveryEvent.Promote, now, rules))
    }

    @Test
    fun `D2 claims for 60 seconds, counts the attempt and only when due`() {
        val pending = row(PENDING, Kind.CREDIT)

        assertEquals(Move(SENDING, listOf(Claim(now + 60_000), IncrementAttempts), "D2"), move(pending, DeliveryEvent.Claim))
        assertEquals(NoOp, DeliveryStateMachine.decide(pending.copy(nextAttemptAt = now + 1), DeliveryEvent.Claim, now, rules))
        assertEquals(NoOp, DeliveryStateMachine.decide(pending.copy(nextAttemptAt = null), DeliveryEvent.Claim, now, rules))
        assertEquals(NoOp, DeliveryStateMachine.decide(row(PENDING, Kind.COMMAND), DeliveryEvent.Claim, now, rules), "a server row is never claimed")
    }

    @Test
    fun `D3 CREDIT and PERMISSION are confirmed, a WEBHOOK is only SENT, a permission grant is re-asserted after 60 s`() {
        val credit = move(row(SENDING, Kind.CREDIT), DeliveryEvent.InlineSucceeded("""{"creditTxId":5}"""))

        assertEquals(CONFIRMED, credit.to)
        assertEquals(
            listOf(
                StampSent(now), StampConfirmed(now), RecordResult("""{"creditTxId":5}"""), ClearClaim,
                SetNextAttemptAt(null), ClearError, RecomputeFulfillment
            ),
            credit.effects
        )

        val webhook = move(row(SENDING, Kind.WEBHOOK), DeliveryEvent.InlineSucceeded("""{"webhookDeliveryId":9}"""))

        assertEquals(SENT, webhook.to)
        assertFalse(webhook.effects.any { it is StampConfirmed })
        assertTrue(StampSent(now) in webhook.effects)

        val permission = row(SENDING, Kind.CREDIT).copy(actionType = DeliveryActionType.PERMISSION)

        assertTrue(SetNextAttemptAt(now + 60_000) in move(permission, DeliveryEvent.InlineSucceeded()).effects)
        assertTrue(SetNextAttemptAt(now + 60_000) in move(permission.copy(phase = DeliveryPhase.RENEW), DeliveryEvent.InlineSucceeded()).effects)
        assertTrue(SetNextAttemptAt(null) in move(permission.copy(phase = DeliveryPhase.REVOKE), DeliveryEvent.InlineSucceeded()).effects)
    }

    @Test
    fun `D4 retries with the backoff of 30 s doubling to 1 h, jitter within 20 percent, until the attempts are used up`() {
        val bases = listOf(30_000L, 60_000L, 120_000L, 240_000L)

        for ((index, base) in bases.withIndex()) {
            val attempts = index + 1
            val result = move(row(SENDING, Kind.CREDIT).copy(attempts = attempts), DeliveryEvent.InlineFailed(DeliveryError.DB_ERROR, "db down", true), Random(attempts))

            assertEquals("D4", result.rule)

            val next = result.effects.filterIsInstance<SetNextAttemptAt>().single().at!!
            val delay = next - now

            assertTrue(delay >= (base * 0.8).toLong() && delay <= (base * 1.2).toLong() + 1, "attempt $attempts: $delay vs $base")
            assertTrue(SetError(DeliveryError.DB_ERROR, "db down") in result.effects)
            assertTrue(ClearClaim in result.effects)
        }

        // 5 attempts used (deliveryMaxAttempts = 5): a retryable error is final (D5).
        val exhausted = move(row(SENDING, Kind.CREDIT).copy(attempts = 5), DeliveryEvent.InlineFailed(DeliveryError.DB_ERROR, "db down", true))

        assertEquals("D5", exhausted.rule)
        assertEquals(FAILED, exhausted.to)
    }

    @Test
    fun `retry delay is capped at one hour and the ack delay doubles from the configured base without jitter`() {
        assertEquals(listOf(30_000L, 60_000L, 120_000L), (1..3).map { DeliveryStateMachine.ackDelayMs(it, rules) })
        assertEquals(3_600_000L, DeliveryStateMachine.ackDelayMs(30, rules))
        assertEquals(3_600_000L, DeliveryStateMachine.ackDelayMs(3, DeliveryRules(ackTimeoutSeconds = 3600)))

        for (attempt in 1..20) {
            val delay = DeliveryStateMachine.retryDelayMs(attempt, Random(attempt))

            assertTrue(delay >= 24_000L && delay <= 4_320_000L, "attempt $attempt: $delay")
        }
    }

    @Test
    fun `D5 records the failure on the order and recomputes the fulfilment`() {
        val result = move(row(SENDING, Kind.CREDIT), DeliveryEvent.InlineFailed(DeliveryError.INVALID_PLAYER, "bad name"))

        assertEquals(
            listOf(
                SetError(DeliveryError.INVALID_PLAYER, "bad name"), SetNextAttemptAt(null), ClearClaim,
                RecordDeliveryFailed(DeliveryError.INVALID_PLAYER), RecomputeFulfillment
            ),
            result.effects
        )
        assertTrue(result.touchesOrder)
    }

    @Test
    fun `D6 returns a stale claim to PENDING at once and leaves a live claim alone`() {
        val sending = row(SENDING, Kind.CREDIT)

        assertEquals(Move(PENDING, listOf(SetNextAttemptAt(now), ClearClaim), "D6"), move(sending, DeliveryEvent.ClaimExpired))
        assertEquals(NoOp, DeliveryStateMachine.decide(sending.copy(claimedUntil = now), DeliveryEvent.ClaimExpired, now, rules))
        assertEquals(NoOp, DeliveryStateMachine.decide(sending.copy(claimedUntil = now + 1), DeliveryEvent.ClaimExpired, now, rules))
    }

    @Test
    fun `D7 waits 15 seconds past runAfter, takes only the three readiness codes and refreshes a changed label`() {
        val pending = row(PENDING, Kind.COMMAND).copy(runAfter = now - 15_000)

        assertEquals(
            Move(WAITING_SERVER, listOf(SetError(DeliveryError.COMPONENT_MISSING), SetNextAttemptAt(null)), "D7"),
            move(pending, DeliveryEvent.ServerNotReady(DeliveryError.COMPONENT_MISSING))
        )
        assertEquals(NoOp, DeliveryStateMachine.decide(pending.copy(runAfter = now - 14_999), DeliveryEvent.ServerNotReady(DeliveryError.SERVER_OFFLINE), now, rules))
        assertEquals(Rejected("INVALID_READINESS_CODE"), DeliveryStateMachine.decide(pending, DeliveryEvent.ServerNotReady(DeliveryError.UNKNOWN_OUTCOME), now, rules))

        val waiting = row(WAITING_SERVER, Kind.COMMAND).copy(lastErrorCode = DeliveryError.SERVER_OFFLINE)

        assertEquals(NoOp, DeliveryStateMachine.decide(waiting, DeliveryEvent.ServerNotReady(DeliveryError.SERVER_OFFLINE), now, rules))
        assertEquals(
            Move(WAITING_SERVER, listOf(SetError(DeliveryError.VERSION_MISMATCH)), "D7"),
            move(waiting, DeliveryEvent.ServerNotReady(DeliveryError.VERSION_MISMATCH))
        )
    }

    @Test
    fun `D8 offers a PENDING or WAITING_SERVER row once and arms the ack timer`() {
        for (status in listOf(PENDING, WAITING_SERVER)) {
            val result = move(row(status, Kind.COMMAND).copy(attempts = 0, sentAt = null, lastErrorCode = DeliveryError.SERVER_OFFLINE), DeliveryEvent.Offer)

            assertEquals(Move(SENT, listOf(IncrementAttempts, StampSent(now), SetNextAttemptAt(now + 30_000), ClearError), "D8"), result)
        }

        assertEquals(NoOp, DeliveryStateMachine.decide(row(PENDING, Kind.COMMAND).copy(runAfter = now + 1), DeliveryEvent.Offer, now, rules), "not yet due")
        assertEquals(NoOp, DeliveryStateMachine.decide(row(PENDING, Kind.CREDIT), DeliveryEvent.Offer, now, rules), "inline rows are never offered")
    }

    @Test
    fun `D9 re-offers the same key with a doubling ack timer and only when the timer is due`() {
        val sent = row(SENT, Kind.COMMAND)

        val second = move(sent.copy(attempts = 1), DeliveryEvent.Offer)

        assertEquals(Move(SENT, listOf(IncrementAttempts, StampSent(now), SetNextAttemptAt(now + 60_000), ClearError), "D9"), second)
        assertEquals(SetNextAttemptAt(now + 120_000), move(sent.copy(attempts = 2), DeliveryEvent.Offer).effects[2])
        assertEquals(SetNextAttemptAt(now + 240_000), move(sent.copy(attempts = 3), DeliveryEvent.Offer).effects[2])

        assertEquals(NoOp, DeliveryStateMachine.decide(sent.copy(nextAttemptAt = now + 1), DeliveryEvent.Offer, now, rules))
        assertEquals(NoOp, DeliveryStateMachine.decide(sent.copy(cancelRequestedAt = now - 1), DeliveryEvent.Offer, now, rules), "a row with a cancel request is not offered")
    }

    @Test
    fun `D10 ends the re-offer budget after deliveryMaxAttempts plus 5 offers or after 30 days`() {
        val sent = row(SENT, Kind.COMMAND)

        assertEquals("D9", move(sent.copy(attempts = 9), DeliveryEvent.Offer).rule)

        val byBudget = move(sent.copy(attempts = 10), DeliveryEvent.Offer)

        assertEquals(
            Move(
                FAILED,
                listOf(SetError(DeliveryError.UNKNOWN_OUTCOME), SetNextAttemptAt(null), RecordDeliveryFailed(DeliveryError.UNKNOWN_OUTCOME), RecomputeFulfillment),
                "D10"
            ),
            byBudget
        )

        assertEquals("D10", move(sent.copy(attempts = 2, sentAt = now - 30L * 86_400_000L - 1), DeliveryEvent.Offer).rule)
        assertEquals("D9", move(sent.copy(attempts = 2, sentAt = now - 30L * 86_400_000L + 1), DeliveryEvent.Offer).rule)
    }

    @Test
    fun `SENT is never final - a lost result is re-offered until the budget ends and a late DONE still wins`() {
        var row = row(SENT, Kind.COMMAND).copy(attempts = 1, nextAttemptAt = now)
        var clock = now
        var offers = 1

        while (true) {
            val result = DeliveryStateMachine.decide(row, DeliveryEvent.Offer, clock, rules)

            assertTrue(result is Move, "offer $offers: $result")
            result as Move
            row = apply(row, result, clock)

            if (result.to == FAILED) break

            assertEquals(SENT, row.status)
            assertEquals("D9", result.rule)

            offers++
            assertTrue(offers <= 20, "the budget must end")

            // Nothing is offered while the ack timer runs.
            assertEquals(NoOp, DeliveryStateMachine.decide(row, DeliveryEvent.Offer, clock, rules))
            clock = row.nextAttemptAt!!
        }

        assertEquals(DeliveryError.UNKNOWN_OUTCOME, row.lastErrorCode)
        assertEquals(10, row.attempts)

        // The component had executed it after all: a positive result wins (D12) ...
        val late = move(row, DeliveryEvent.ServerResult(ResultStatus.DONE, result = """{"executedAt":1}"""))

        assertEquals(CONFIRMED, late.to)
        assertEquals("D12", late.rule)
        assertTrue(ClearError in late.effects)

        // ... and a negative one cannot undo a confirmed row.
        val confirmed = apply(row, late, clock)

        assertEquals(NoOp, DeliveryStateMachine.decide(confirmed, DeliveryEvent.ServerResult(ResultStatus.FAILED, DeliveryError.COMMAND_ERROR), clock, rules))
    }

    @Test
    fun `D11 QUEUED is accepted only on a SENT row`() {
        val sent = row(SENT, Kind.COMMAND)

        assertEquals(Move(QUEUED, listOf(SetNextAttemptAt(null)), "D11"), move(sent, DeliveryEvent.ServerResult(ResultStatus.QUEUED)))

        for (status in DeliveryStatus.entries - SENT) {
            assertEquals(NoOp, DeliveryStateMachine.decide(row(status, Kind.COMMAND), DeliveryEvent.ServerResult(ResultStatus.QUEUED), now, rules), "$status")
        }
    }

    @Test
    fun `D12 a positive result wins over the late failures and over nothing else`() {
        val done = DeliveryEvent.ServerResult(ResultStatus.DONE, result = """{"executedAt":5}""")

        for (code in listOf(DeliveryError.UNKNOWN_OUTCOME, DeliveryError.ONLINE_WAIT_EXPIRED, DeliveryError.WEBHOOK_DEAD)) {
            val failed = row(FAILED, Kind.COMMAND).copy(lastErrorCode = code)

            assertEquals(CONFIRMED, move(failed, done).to, code)
        }

        // 08 section 20 case 25: DONE on FAILED (COMMAND_ERROR) changes nothing.
        for (code in listOf(DeliveryError.COMMAND_ERROR, DeliveryError.REJECTED, DeliveryError.SERVER_REMOVED, null)) {
            assertEquals(NoOp, DeliveryStateMachine.decide(row(FAILED, Kind.COMMAND).copy(lastErrorCode = code), done, now, rules), "$code")
        }

        // 08 section 20 case 26: QUEUED on a CONFIRMED row changes nothing.
        assertEquals(NoOp, DeliveryStateMachine.decide(row(CONFIRMED, Kind.COMMAND), DeliveryEvent.ServerResult(ResultStatus.QUEUED), now, rules))

        val result = move(row(QUEUED, Kind.COMMAND), done)

        assertEquals(listOf(StampConfirmed(now), RecordResult("""{"executedAt":5}"""), ClearError, SetNextAttemptAt(null), RecomputeFulfillment), result.effects)

        // DONE after a cancel request: the command ran, so it is CONFIRMED, not CANCELLED.
        assertEquals(CONFIRMED, move(row(SENT, Kind.COMMAND).copy(cancelRequestedAt = now - 1), done).to)
    }

    @Test
    fun `D13 keeps the four component codes and maps every other code to REJECTED with the original in lastError`() {
        for (code in listOf(DeliveryError.COMMAND_ERROR, DeliveryError.LUCKPERMS_MISSING, DeliveryError.DISABLED_LOCALLY, DeliveryError.INVALID_PAYLOAD)) {
            val result = move(row(SENT, Kind.COMMAND), DeliveryEvent.ServerResult(ResultStatus.FAILED, code, "msg", """{"commands":[]}"""))

            assertEquals(
                listOf(
                    RecordResult("""{"commands":[]}"""), SetError(code, "msg"), SetNextAttemptAt(null),
                    RecordDeliveryFailed(code), RecomputeFulfillment
                ),
                result.effects, code
            )
        }

        val other = move(row(QUEUED, Kind.COMMAND), DeliveryEvent.ServerResult(ResultStatus.FAILED, "SOMETHING_NEW", "msg"))

        assertTrue(SetError(DeliveryError.REJECTED, "SOMETHING_NEW: msg") in other.effects)
        assertEquals(DeliveryError.REJECTED, DeliveryStateMachine.mapResultCode("X"))
        assertEquals(DeliveryError.REJECTED, DeliveryStateMachine.mapResultCode(null))

        val long = move(row(SENT, Kind.COMMAND), DeliveryEvent.ServerResult(ResultStatus.FAILED, DeliveryError.COMMAND_ERROR, "x".repeat(900)))

        assertEquals(512, long.effects.filterIsInstance<SetError>().single().message!!.length)
    }

    @Test
    fun `D14 an expired online wait fails the row, from the component or from the job after the grace hour`() {
        val fromComponent = move(row(QUEUED, Kind.COMMAND), DeliveryEvent.ServerResult(ResultStatus.EXPIRED))

        assertEquals(
            listOf(
                SetError(DeliveryError.ONLINE_WAIT_EXPIRED), SetNextAttemptAt(null), RecordDeliveryFailed(DeliveryError.ONLINE_WAIT_EXPIRED), RecomputeFulfillment
            ),
            fromComponent.effects
        )

        val queued = row(QUEUED, Kind.COMMAND)
        val limit = now - 3_600_000L

        assertEquals("D14", move(queued.copy(waitUntil = limit - 1), DeliveryEvent.WaitExpired).rule)
        assertEquals(NoOp, DeliveryStateMachine.decide(queued.copy(waitUntil = limit), DeliveryEvent.WaitExpired, now, rules))
        assertEquals(NoOp, DeliveryStateMachine.decide(queued.copy(waitUntil = null), DeliveryEvent.WaitExpired, now, rules))
    }

    @Test
    fun `D15 turns CANCELLED only after a cancel was asked and keeps the recorded reason`() {
        val asked = move(row(QUEUED, Kind.COMMAND), DeliveryEvent.Cancel(DeliveryError.ORDER_REVOKED))

        assertEquals(Move(QUEUED, listOf(RequestCancel(now), SetError(DeliveryError.ORDER_REVOKED)), "D17"), asked)

        val inFlight = row(QUEUED, Kind.COMMAND).copy(cancelRequestedAt = now - 1, lastErrorCode = DeliveryError.ORDER_REVOKED)
        val cancelled = move(inFlight, DeliveryEvent.ServerResult(ResultStatus.CANCELLED))

        assertEquals(Move(CANCELLED, listOf(SetNextAttemptAt(null), RecomputeFulfillment), "D15"), cancelled)

        // A cancel request without a stored reason becomes an admin cancel.
        val noReason = move(inFlight.copy(lastErrorCode = null), DeliveryEvent.ServerResult(ResultStatus.UNKNOWN))

        assertTrue(SetError(DeliveryError.CANCELLED_BY_ADMIN) in noReason.effects)

        // A CANCELLED answer to a cancel nobody asked for is a REJECTED failure with the original word in lastError.
        val unasked = move(row(SENT, Kind.COMMAND), DeliveryEvent.ServerResult(ResultStatus.CANCELLED, message = "removed"))

        assertEquals(FAILED, unasked.to)
        assertTrue(SetError(DeliveryError.REJECTED, "CANCELLED: removed") in unasked.effects)
    }

    @Test
    fun `D16 cancels an unsent row at once with its reason, D17 only asks for in-flight rows and is idempotent`() {
        for (reason in listOf(DeliveryError.CANCELLED_BY_ADMIN, DeliveryError.ORDER_REVOKED, DeliveryError.ENTITLEMENT_ENDED)) {
            val result = move(row(PENDING, Kind.COMMAND), DeliveryEvent.Cancel(reason))

            assertEquals(Move(CANCELLED, listOf(SetError(reason), SetNextAttemptAt(null), RecomputeFulfillment), "D16"), result, reason)
        }

        // An unknown reason is an admin cancel.
        assertEquals(SetError(DeliveryError.CANCELLED_BY_ADMIN), move(row(PENDING, Kind.COMMAND), DeliveryEvent.Cancel("whatever")).effects[0])

        val requested = row(SENT, Kind.COMMAND).copy(cancelRequestedAt = now - 1)

        assertEquals(NoOp, DeliveryStateMachine.decide(requested, DeliveryEvent.Cancel(), now, rules), "a second cancel request changes nothing")
    }

    @Test
    fun `D18 retries the same row from the start and D19 re-offers a SENT row at once`() {
        val failed = row(FAILED, Kind.COMMAND).copy(attempts = 4, lastErrorCode = DeliveryError.COMMAND_ERROR)

        assertEquals(
            Move(PENDING, listOf(ResetAttempts, SetNextAttemptAt(now), ClearError, RecomputeFulfillment), "D18"),
            move(failed, DeliveryEvent.Retry)
        )
        assertEquals(Move(SENT, listOf(SetNextAttemptAt(now)), "D19"), move(row(SENT, Kind.COMMAND), DeliveryEvent.Retry))
    }

    @Test
    fun `retry rules of 08 section 14_2`() {
        val failed = row(FAILED, Kind.COMMAND).copy(sentAt = now - 3 * 86_400_000L)

        for (code in listOf(DeliveryError.RENDER_ERROR, DeliveryError.NO_TARGET_SERVER, DeliveryError.SERVER_REMOVED, DeliveryError.INVALID_PLAYER, DeliveryError.WEBHOOK_DEAD)) {
            assertEquals(Rejected(DeliveryError.DELIVERY_NOT_RETRYABLE), DeliveryStateMachine.decide(failed.copy(lastErrorCode = code), DeliveryEvent.Retry, now, rules), code)
            assertFalse(DeliveryStateMachine.isRetryable(failed.copy(lastErrorCode = code), now))
        }

        for (code in listOf(DeliveryError.COMMAND_ERROR, DeliveryError.NO_ACCOUNT, DeliveryError.UNKNOWN_OUTCOME, DeliveryError.ONLINE_WAIT_EXPIRED, DeliveryError.DB_ERROR, null)) {
            assertEquals("D18", move(failed.copy(lastErrorCode = code), DeliveryEvent.Retry).rule, "$code")
            assertTrue(DeliveryStateMachine.isRetryable(failed.copy(lastErrorCode = code), now))
        }

        // The game server may have forgotten the key after 30 days: use a re-run instead.
        assertEquals(
            Rejected(DeliveryError.DELIVERY_NOT_RETRYABLE),
            DeliveryStateMachine.decide(failed.copy(lastErrorCode = DeliveryError.UNKNOWN_OUTCOME, sentAt = now - 31L * 86_400_000L), DeliveryEvent.Retry, now, rules)
        )

        // A pending cancel makes a retry pointless.
        assertEquals(
            Rejected(DeliveryError.DELIVERY_NOT_RETRYABLE),
            DeliveryStateMachine.decide(row(SENT, Kind.COMMAND).copy(cancelRequestedAt = now - 1), DeliveryEvent.Retry, now, rules)
        )
    }

    @Test
    fun `D20 fails every open server row of a removed server and ignores inline rows`() {
        for (status in listOf(PENDING, SCHEDULED, WAITING_SERVER, SENT, QUEUED)) {
            val result = move(row(status, Kind.COMMAND), DeliveryEvent.ServerRemoved)

            assertEquals(
                Move(
                    FAILED,
                    listOf(
                        SetError(DeliveryError.SERVER_REMOVED), SetNextAttemptAt(null), ClearClaim,
                        RecordDeliveryFailed(DeliveryError.SERVER_REMOVED), RecomputeFulfillment
                    ),
                    "D20"
                ),
                result, "$status"
            )
        }

        assertEquals(NoOp, DeliveryStateMachine.decide(row(PENDING, Kind.CREDIT), DeliveryEvent.ServerRemoved, now, rules))
    }

    @Test
    fun `D12 and D21 for an action webhook follow its outbox row`() {
        val sent = row(SENT, Kind.WEBHOOK)

        assertEquals(Move(CONFIRMED, listOf(StampConfirmed(now), ClearError, SetNextAttemptAt(null), RecomputeFulfillment), "D12"), move(sent, DeliveryEvent.WebhookSucceeded))

        assertEquals(
            Move(
                FAILED,
                listOf(SetError(DeliveryError.WEBHOOK_DEAD), SetNextAttemptAt(null), RecordDeliveryFailed(DeliveryError.WEBHOOK_DEAD), RecomputeFulfillment),
                "D21"
            ),
            move(sent, DeliveryEvent.WebhookDead)
        )

        // Redeliver of a dead row: success moves the failed delivery to CONFIRMED; any other failure code stays.
        assertEquals("D12", move(row(FAILED, Kind.WEBHOOK).copy(lastErrorCode = DeliveryError.WEBHOOK_DEAD), DeliveryEvent.WebhookSucceeded).rule)
        assertEquals(NoOp, DeliveryStateMachine.decide(row(FAILED, Kind.WEBHOOK).copy(lastErrorCode = DeliveryError.RENDER_ERROR), DeliveryEvent.WebhookSucceeded, now, rules))

        // A webhook event never touches a server row.
        assertEquals(NoOp, DeliveryStateMachine.decide(row(SENT, Kind.COMMAND), DeliveryEvent.WebhookDead, now, rules))
    }

    @Test
    fun `D22 cancels a held undo whose predecessor never took effect, only for EXPIRE and REVOKE rows`() {
        for (phase in listOf(DeliveryPhase.EXPIRE, DeliveryPhase.REVOKE)) {
            val result = move(row(PENDING, Kind.COMMAND, phase), DeliveryEvent.GateOpened(true))

            assertEquals(
                Move(CANCELLED, listOf(SetError(DeliveryError.NOTHING_TO_REVOKE), SetNextAttemptAt(null), RecomputeFulfillment), "D22"),
                result, phase.name
            )
            assertEquals(NoOp, DeliveryStateMachine.decide(row(PENDING, Kind.COMMAND, phase), DeliveryEvent.GateOpened(false), now, rules))
        }

        for (phase in listOf(DeliveryPhase.GRANT, DeliveryPhase.RENEW)) {
            assertEquals(NoOp, DeliveryStateMachine.decide(row(PENDING, Kind.COMMAND, phase), DeliveryEvent.GateOpened(true), now, rules), phase.name)
            assertEquals(NoOp, DeliveryStateMachine.decide(row(WAITING_SERVER, Kind.COMMAND, phase), DeliveryEvent.GateOpened(true), now, rules), "WAITING_SERVER ${phase.name}")
        }
    }

    @Test
    fun `D22 reaches a held undo that D7 already labelled WAITING_SERVER, so it is never offered to the game server`() {
        for (phase in listOf(DeliveryPhase.EXPIRE, DeliveryPhase.REVOKE)) {
            // The revoke command waits for the predecessor gate while its server is offline: PENDING -> D7 -> WAITING_SERVER.
            var r = row(PENDING, Kind.COMMAND, phase).copy(runAfter = now - 20_000, attempts = 0, sentAt = null)

            r = apply(r, move(r, DeliveryEvent.ServerNotReady(DeliveryError.SERVER_OFFLINE)), now)
            assertEquals(WAITING_SERVER, r.status)
            assertEquals(DeliveryError.SERVER_OFFLINE, r.lastErrorCode)

            // Gate still closed (a predecessor is in flight) or the predecessor was delivered: the row is left alone.
            assertEquals(NoOp, DeliveryStateMachine.decide(r, DeliveryEvent.GateOpened(false), now, rules), phase.name)

            // The predecessor never executed: nothing to undo, no command may run.
            val gate = move(r, DeliveryEvent.GateOpened(true))

            assertEquals(
                Move(CANCELLED, listOf(SetError(DeliveryError.NOTHING_TO_REVOKE), SetNextAttemptAt(null), RecomputeFulfillment), "D22"),
                gate, phase.name
            )

            r = apply(r, gate, now)

            assertEquals(CANCELLED, r.status)
            assertEquals(DeliveryError.NOTHING_TO_REVOKE, r.lastErrorCode)
            assertEquals(NoOp, DeliveryStateMachine.decide(r, DeliveryEvent.Offer, now + 3_600_000, rules), "a cancelled undo is never offered")
        }

        // An inline row is never WAITING_SERVER; the state is only taken for server rows.
        assertEquals(NoOp, DeliveryStateMachine.decide(row(WAITING_SERVER, Kind.CREDIT, DeliveryPhase.REVOKE), DeliveryEvent.GateOpened(true), now, rules))
    }

    // ---- an end flow reaches a claimed inline row (review fix, 08 section 11.1) -----------------------------------------

    private val endReasons = listOf(DeliveryError.ORDER_REVOKED, DeliveryError.ENTITLEMENT_ENDED)

    @Test
    fun `D17 asks for the cancel of a SENDING inline row when an end flow runs, the admin cancel and a server row stay rejected`() {
        for (reason in endReasons) {
            for (kind in inline) {
                val claimed = row(SENDING, kind).copy(claimedUntil = now + 30_000)

                assertEquals(Move(SENDING, listOf(RequestCancel(now), SetError(reason)), "D17"), move(claimed, DeliveryEvent.Cancel(reason)), "$kind $reason")
                assertFalse(move(claimed, DeliveryEvent.Cancel(reason)).touchesOrder)

                // Idempotent: a second end flow changes nothing.
                assertEquals(NoOp, DeliveryStateMachine.decide(claimed.copy(cancelRequestedAt = now - 1), DeliveryEvent.Cancel(reason), now, rules), "$kind $reason again")
            }
        }

        val claimed = row(SENDING, Kind.CREDIT)

        assertEquals(Rejected(DeliveryError.DELIVERY_NOT_CANCELLABLE), DeliveryStateMachine.decide(claimed, DeliveryEvent.Cancel(DeliveryError.CANCELLED_BY_ADMIN), now, rules))
        assertEquals(Rejected(DeliveryError.DELIVERY_NOT_CANCELLABLE), DeliveryStateMachine.decide(claimed, DeliveryEvent.Cancel("whatever"), now, rules), "an unknown reason is an admin cancel")
        assertEquals(Rejected(DeliveryError.DELIVERY_NOT_CANCELLABLE), DeliveryStateMachine.decide(row(SENDING, Kind.COMMAND), DeliveryEvent.Cancel(DeliveryError.ORDER_REVOKED), now, rules), "a server row is never SENDING")
    }

    /** A claimed inline grant, still within its claim, that an end flow has asked to cancel (D17). */
    private fun cancelRequestedWhileSending(kind: Kind = Kind.CREDIT, reason: String = DeliveryError.ORDER_REVOKED): DeliveryRow {
        val claimed = row(SENDING, kind).copy(attempts = 1, claimedUntil = now + 30_000, nextAttemptAt = null)

        return apply(claimed, move(claimed, DeliveryEvent.Cancel(reason)), now)
    }

    @Test
    fun `after an end-flow cancel a SENDING grant that executes is CONFIRMED and its inverse then undoes it (D3)`() {
        val r = cancelRequestedWhileSending()

        assertEquals(SENDING, r.status)
        assertEquals(now, r.cancelRequestedAt)
        assertEquals(DeliveryError.ORDER_REVOKED, r.lastErrorCode)

        val done = move(r, DeliveryEvent.InlineSucceeded("""{"creditTxId":5}"""))

        assertEquals(CONFIRMED, done.to)
        assertEquals("D3", done.rule)
        assertEquals(null, apply(r, done, now).lastErrorCode, "the stored cancel reason is cleared by the success")
    }

    @Test
    fun `after an end-flow cancel a retryable failure (D4) or a stale claim (D6) ends CANCELLED with the reason and never runs again`() {
        for (reason in endReasons) {
            for (kind in inline) {
                val r = cancelRequestedWhileSending(kind, reason)

                val failed = move(r, DeliveryEvent.InlineFailed(DeliveryError.DB_ERROR, "db down", retryable = true))
                val stale = move(r.copy(claimedUntil = now - 1), DeliveryEvent.ClaimExpired)

                for ((label, result) in listOf("D4" to failed, "D6" to stale)) {
                    assertEquals(
                        Move(CANCELLED, listOf(SetNextAttemptAt(null), ClearClaim, RecomputeFulfillment), label), result,
                        "$label $kind $reason: the recorded reason stays, no SetError overwrites it"
                    )
                    assertTrue(result.touchesOrder, label)

                    val after = apply(r, result, now)

                    assertEquals(CANCELLED, after.status)
                    assertEquals(reason, after.lastErrorCode)
                    assertEquals(null, after.claimedUntil)
                    assertEquals(null, after.nextAttemptAt)

                    // Terminal: not claimed, not promoted, not retried, not cancelled again.
                    assertEquals(NoOp, DeliveryStateMachine.decide(after, DeliveryEvent.Claim, now + 1, rules), label)
                    assertEquals(NoOp, DeliveryStateMachine.decide(after, DeliveryEvent.ClaimExpired, now + 1, rules), label)
                    assertEquals(Rejected(DeliveryError.DELIVERY_NOT_RETRYABLE), DeliveryStateMachine.decide(after, DeliveryEvent.Retry, now + 1, rules), label)
                }
            }
        }

        // A cancel request without a stored reason becomes an admin cancel.
        val noReason = cancelRequestedWhileSending().copy(lastErrorCode = null)

        assertTrue(SetError(DeliveryError.CANCELLED_BY_ADMIN) in move(noReason, DeliveryEvent.InlineFailed(DeliveryError.DB_ERROR, retryable = true)).effects)
        assertTrue(SetError(DeliveryError.CANCELLED_BY_ADMIN) in move(noReason.copy(claimedUntil = now - 1), DeliveryEvent.ClaimExpired).effects)
    }

    @Test
    fun `after an end-flow cancel a final failure (D5) stays FAILED, and a stale claim without a cancel request still returns to PENDING`() {
        val r = cancelRequestedWhileSending()

        val final = move(r, DeliveryEvent.InlineFailed(DeliveryError.INVALID_PLAYER, "bad name"))

        assertEquals("D5", final.rule)
        assertEquals(FAILED, final.to)

        // Attempts used up: the retryable failure is final as well (D5), not a cancel.
        assertEquals(FAILED, move(r.copy(attempts = 5), DeliveryEvent.InlineFailed(DeliveryError.DB_ERROR, "db", retryable = true)).to)

        // Nobody asked to cancel: D4 and D6 are unchanged.
        val plain = row(SENDING, Kind.CREDIT).copy(attempts = 1)

        assertEquals(PENDING, move(plain, DeliveryEvent.InlineFailed(DeliveryError.DB_ERROR, "x", retryable = true)).to)
        assertEquals(PENDING, move(plain, DeliveryEvent.ClaimExpired).to)
    }

    @Test
    fun `transitions that touch the order lock it, the plain ones do not (D2 D6 to D9 D11)`() {
        val untouched = listOf(
            move(row(PENDING, Kind.CREDIT), DeliveryEvent.Claim),
            move(row(SENDING, Kind.CREDIT), DeliveryEvent.ClaimExpired),
            move(row(PENDING, Kind.COMMAND).copy(runAfter = now - 20_000), DeliveryEvent.ServerNotReady(DeliveryError.SERVER_OFFLINE)),
            move(row(PENDING, Kind.COMMAND), DeliveryEvent.Offer),
            move(row(SENT, Kind.COMMAND), DeliveryEvent.Offer),
            move(row(SENT, Kind.COMMAND), DeliveryEvent.ServerResult(ResultStatus.QUEUED))
        )

        for (m in untouched) assertFalse(m.touchesOrder, m.rule)

        for (rule in listOf("D5", "D10", "D12", "D13", "D14", "D16", "D20", "D21", "D22")) {
            val touched = cases.flatMap { case ->
                Kind.entries.flatMap { kind ->
                    DeliveryStatus.entries.mapNotNull { status ->
                        (DeliveryStateMachine.decide(case.prep(row(status, kind)), case.event, now, rules, Random(7)) as? Move)?.takeIf { it.rule == rule }
                    }
                }
            }

            assertTrue(touched.isNotEmpty(), rule)
            assertTrue(touched.all { it.touchesOrder }, rule)
        }
    }

    @Test
    fun `every move into FAILED is recorded on the order`() {
        for (case in cases) {
            for (kind in Kind.entries) {
                for (status in DeliveryStatus.entries) {
                    val result = DeliveryStateMachine.decide(case.prep(row(status, kind)), case.event, now, rules, Random(7))

                    if (result is Move && result.to == FAILED) {
                        assertTrue(result.effects.any { it is RecordDeliveryFailed }, "${case.name}: $kind x $status")
                        assertTrue(RecomputeFulfillment in result.effects, "${case.name}: $kind x $status")
                    }
                }
            }
        }
    }

    // ---- whole lives ------------------------------------------------------------------------------------------------

    /** Applies a decision to a row the way the service would (only the columns [DeliveryRow] carries). */
    private fun apply(row: DeliveryRow, move: Move, at: Long): DeliveryRow {
        var r = row.copy(status = move.to)

        for (effect in move.effects) {
            r = when (effect) {
                is Claim -> r.copy(claimedUntil = effect.until)
                ClearClaim -> r.copy(claimedUntil = null)
                IncrementAttempts -> r.copy(attempts = r.attempts + 1)
                ResetAttempts -> r.copy(attempts = 0)
                is StampSent -> r.copy(sentAt = r.sentAt ?: effect.at)
                is SetNextAttemptAt -> r.copy(nextAttemptAt = effect.at)
                is SetError -> r.copy(lastErrorCode = effect.code)
                ClearError -> r.copy(lastErrorCode = null)
                is RequestCancel -> r.copy(cancelRequestedAt = effect.at)
                is StampConfirmed, is RecordResult, RecomputeFulfillment, is RecordDeliveryFailed -> r
            }
        }

        return r
    }

    @Test
    fun `life of an inline CREDIT with a delay, a failed attempt and a crash`() {
        var r = DeliveryRow(actionType = DeliveryActionType.CREDIT, transport = DeliveryTransport.INLINE, status = SCHEDULED, runAfter = now + 60_000, nextAttemptAt = now + 60_000)
        var t = now

        assertEquals(NoOp, DeliveryStateMachine.decide(r, DeliveryEvent.Promote, t, rules))

        t += 60_000
        r = apply(r, DeliveryStateMachine.decide(r, DeliveryEvent.Promote, t, rules) as Move, t)
        assertEquals(PENDING, r.status)

        r = apply(r, DeliveryStateMachine.decide(r, DeliveryEvent.Claim, t, rules) as Move, t)
        assertEquals(SENDING, r.status)
        assertEquals(1, r.attempts)

        // The worker dies: after the claim time the job puts the row back (D6), and another worker takes it.
        t += 61_000
        r = apply(r, DeliveryStateMachine.decide(r, DeliveryEvent.ClaimExpired, t, rules) as Move, t)
        assertEquals(PENDING, r.status)

        r = apply(r, DeliveryStateMachine.decide(r, DeliveryEvent.Claim, t, rules) as Move, t)
        assertEquals(2, r.attempts)

        r = apply(r, DeliveryStateMachine.decide(r, DeliveryEvent.InlineFailed(DeliveryError.DB_ERROR, "x", true), t, rules, Random(3)) as Move, t)
        assertEquals(PENDING, r.status)
        assertTrue(r.nextAttemptAt!! > t)

        t = r.nextAttemptAt!!
        r = apply(r, DeliveryStateMachine.decide(r, DeliveryEvent.Claim, t, rules) as Move, t)
        r = apply(r, DeliveryStateMachine.decide(r, DeliveryEvent.InlineSucceeded("{}"), t, rules) as Move, t)

        assertEquals(CONFIRMED, r.status)
        assertEquals(3, r.attempts)
        assertEquals(null, r.lastErrorCode)
    }

    @Test
    fun `life of a server COMMAND that waits for its server, is queued for the player and is confirmed`() {
        var r = DeliveryRow(actionType = DeliveryActionType.COMMAND, transport = DeliveryTransport.MARKET_MC, serverId = 4, status = PENDING, runAfter = now, nextAttemptAt = now)
        var t = now + 20_000

        r = apply(r, DeliveryStateMachine.decide(r, DeliveryEvent.ServerNotReady(DeliveryError.SERVER_OFFLINE), t, rules) as Move, t)
        assertEquals(WAITING_SERVER, r.status)
        assertEquals(null, r.nextAttemptAt)

        t += 3_600_000
        r = apply(r, DeliveryStateMachine.decide(r, DeliveryEvent.Offer, t, rules) as Move, t)
        assertEquals(SENT, r.status)
        assertEquals(1, r.attempts)
        assertEquals(null, r.lastErrorCode)

        r = apply(r, DeliveryStateMachine.decide(r, DeliveryEvent.ServerResult(ResultStatus.QUEUED), t, rules) as Move, t)
        assertEquals(QUEUED, r.status)

        // The server is removed while the player is away.
        val removed = DeliveryStateMachine.decide(r, DeliveryEvent.ServerRemoved, t, rules)
        assertEquals(FAILED, (removed as Move).to)

        r = apply(r, DeliveryStateMachine.decide(r, DeliveryEvent.ServerResult(ResultStatus.DONE), t, rules) as Move, t)
        assertEquals(CONFIRMED, r.status)
    }
}
