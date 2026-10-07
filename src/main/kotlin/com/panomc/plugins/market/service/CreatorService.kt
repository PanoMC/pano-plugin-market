package com.panomc.plugins.market.service

import com.panomc.platform.error.NotFound
import com.panomc.plugins.market.config.MarketConfig
import com.panomc.plugins.market.core.credit.CreditMath
import com.panomc.plugins.market.core.delivery.DeliveryError
import com.panomc.plugins.market.core.delivery.DeliveryEvent
import com.panomc.plugins.market.core.money.Conversions
import com.panomc.plugins.market.core.money.Rounding
import com.panomc.plugins.market.core.order.OrderEffect
import com.panomc.plugins.market.core.pricing.MethodInput
import com.panomc.plugins.market.core.time.Clock
import com.panomc.plugins.market.db.dao.MarketCreatorEarningDao
import com.panomc.plugins.market.db.dao.MarketCreatorPayoutDao
import com.panomc.plugins.market.db.dao.MarketOrderDao
import com.panomc.plugins.market.db.model.CreatorEarningState
import com.panomc.plugins.market.db.model.CreatorPayoutMethod
import com.panomc.plugins.market.db.model.CreatorPayoutState
import com.panomc.plugins.market.db.model.CreditSystemKey
import com.panomc.plugins.market.db.model.CreditTxType
import com.panomc.plugins.market.db.model.DeliveryStatus
import com.panomc.plugins.market.db.model.MarketCreatorEarning
import com.panomc.plugins.market.db.model.MarketCreatorPayout
import com.panomc.plugins.market.db.model.MarketOrder
import com.panomc.plugins.market.db.model.MarketOrderItem
import com.panomc.plugins.market.db.model.OrderItemKind
import com.panomc.plugins.market.db.model.OrderSource
import com.panomc.plugins.market.db.tx.LockedOrder
import com.panomc.plugins.market.db.tx.MarketDb
import com.panomc.plugins.market.error.CreatorHasNoAccount
import com.panomc.plugins.market.error.CreditsDisabled
import com.panomc.plugins.market.error.IdempotencyConflict
import com.panomc.plugins.market.error.InvalidPayoutAmount
import com.panomc.plugins.market.error.InvalidState
import com.panomc.plugins.market.error.RequestValueException
import com.panomc.plugins.market.service.platform.UserDirectory
import com.panomc.plugins.market.util.MoneyUtil
import com.panomc.plugins.market.util.Paging
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.security.MessageDigest

/**
 * The creator payouts of `method = ACTION` follow their `market_delivery` rows (21 section 7.4): `DeliveryService` calls [lock] before an order-less row of a
 * payout changes and [settle] after it did, in the same transaction. [NONE] is a delivery service that has no payouts attached (the delivery tests).
 */
interface PayoutSettlement {
    /**
     * Serialises the rows of one payout: the payout row is locked before a row of it changes, so two rows finishing together cannot both miss each other.
     * Answers the payout's state under the lock (`null` when there is no such payout): a row of a `CANCELLED` payout must not leave a final state again
     * (a retry or a late result would run the command for money that was given back), the delivery service refuses every move except the cancel itself.
     */
    suspend fun lock(conn: SqlClient, payoutId: Long): CreatorPayoutState?

    /**
     * Follows the effective rows of a `PENDING` or `FAILED` payout: any open row => `PENDING` (a `FAILED` payout whose row was retried is open again),
     * every row `CONFIRMED` => `PAID` (also from `FAILED`: a late result of a row that had failed), none open and one `FAILED` => `FAILED`; nothing otherwise.
     */
    suspend fun settle(conn: SqlClient, payoutId: Long)

    companion object {
        val NONE: PayoutSettlement = object : PayoutSettlement {
            override suspend fun lock(conn: SqlClient, payoutId: Long): CreatorPayoutState? = null
            override suspend fun settle(conn: SqlClient, payoutId: Long) = Unit
        }
    }
}

/**
 * The reversal of a creator earning (21 section 7.3), the one implementation of O10 (refund) and O11 (chargeback): `target` is the earning's `amount` when the
 * order becomes `REFUNDED` or is charged back ([fully]), else `floor(amount x refundedTotalAfter / totalPrice)`; `delta = target - reversedAmount` (not positive:
 * skipped); `reversedAmount += delta`, the code's cached `earnings -= delta`; a fully reversed row ends `REVERSED` unless it is `PAID` (a paid row stays `PAID`: the
 * reversal then shows up as a negative `available`, which blocks further payouts until new earnings cover it; nothing is clawed back from the creator).
 * The caller holds the order lock; the code row is locked here (a no-op when the order transition locked it already).
 */
object CreatorReversal {
    suspend fun reverse(
        earnings: MarketCreatorEarningDao, tablePrefix: String, conn: SqlConnection, order: MarketOrder, refundedTotalAfter: Long, fully: Boolean
    ) {
        val rows = earnings.getByOrderId(order.id, conn).sortedBy { it.id }

        if (rows.isEmpty()) return

        val codes = "`${tablePrefix}market_creator_code`"

        conn.query("SELECT `id` FROM $codes WHERE `id` IN (${rows.map { it.creatorCodeId }.toSortedSet().joinToString(",")}) ORDER BY `id` FOR UPDATE").execute().coAwait()

        for (stale in rows) {
            // read again under the code lock: a concurrent reversal of the same order has committed or is gone
            val earning = earnings.getById(stale.id, conn) ?: continue
            val target = if (fully || order.totalPrice <= 0L || refundedTotalAfter >= order.totalPrice) earning.amount
            else BigInteger.valueOf(earning.amount).multiply(BigInteger.valueOf(refundedTotalAfter)).divide(BigInteger.valueOf(order.totalPrice)).toLong()
            val delta = target - earning.reversedAmount

            if (delta <= 0L) continue

            if (!earnings.addReversed(earning.id, delta, conn)) continue

            conn.preparedQuery("UPDATE $codes SET `earnings` = `earnings` - ? WHERE `id` = ?").execute(Tuple.of(delta, earning.creatorCodeId)).coAwait()

            if (earning.reversedAmount + delta >= earning.amount) {
                // a PAID row stays PAID; PENDING and AVAILABLE rows end REVERSED
                if (!earnings.transition(earning.id, CreatorEarningState.PENDING, CreatorEarningState.REVERSED, conn)) {
                    earnings.transition(earning.id, CreatorEarningState.AVAILABLE, CreatorEarningState.REVERSED, conn)
                }
            }
        }
    }
}

/** What `POST /creator-codes/:id/payouts` asks for; [actions] is the canonical JSON of the validated actions (`ACTION` only), [note] is required for `MANUAL`. */
class CreatorPayoutInput(val amount: Long, val method: CreatorPayoutMethod, val actions: String?, val note: String?)

/** The payout row after the request; [replay] is true when the `Idempotency-Key` had been used for the same request (nothing was written). */
class CreatorPayoutOutcome(val payout: MarketCreatorPayout, val replay: Boolean, val creatorCode: String)

/** The balances of one creator code (21 section 7.2), money x 100 in the base currency. [available] may be negative. */
class CreatorBalance(val earned: Long, val reversed: Long, val pending: Long, val payable: Long, val paidOut: Long) {
    val available: Long get() = payable - paidOut
}

/** The page asked for lies beyond the last one (404 `PAGE_NOT_FOUND` in the route). */
class CreatorPageOutOfRange : RuntimeException()

/**
 * Creator earnings, reversal and payouts (21 section 7, 07 section 12, 08 section 12, 04 sections 4 and 6): [accrue] at O2 / O4, [CreatorReversal] at O10 / O11,
 * [requestPayout] / [cancelPayout] (`CREDIT`, `ACTION`, `MANUAL`), the settlement of `ACTION` payouts from their delivery rows ([PayoutSettlement]) and the read
 * models of the panel and of the creator. Every method runs on the connection of the caller's `MarketDb.tx`, except the read models and the payout
 * operations, which open their own transaction.
 *
 * Lock order (00 section 8.3): the creator code row is level 1, the credit accounts level 4. A payout locks its code row first, then the payout row
 * (the delivery rows of an `ACTION` payout lock the payout row before they change), then the ledger accounts. Orders are not locked by a payout.
 *
 * The creator code row has columns the entity does not carry (`creatorUserId`, `paidOut`, `deletedAt`); they are read and written with SQL here.
 * Due earnings (`PENDING` with `availableAt <= now`) are released by [releaseDue] before every balance is read, so a payout and every view are right
 * without waiting for a background job (`HousekeepingJob` of MK-153 may call [releaseDue] hourly as well).
 */
class CreatorService(
    private val db: MarketDb,
    private val clock: Clock,
    private val config: () -> MarketConfig,
    private val earnings: MarketCreatorEarningDao,
    private val payouts: MarketCreatorPayoutDao,
    private val credits: CreditService,
    private val deliveries: DeliveryService,
    private val users: UserDirectory,
    private val prefix: () -> String,
    private val client: suspend () -> SqlClient
) : PayoutSettlement {
    private fun t(name: String) = "`${prefix()}$name`"

    private class CodeRow(val id: Long, val creator: String, val code: String, val creatorUserId: Long?, val paidOut: Long, val commissionPercent: Long)

    private suspend fun lockCode(conn: SqlClient, id: Long): CodeRow? =
        conn.preparedQuery("SELECT `id`, `creator`, `code`, `creatorUserId`, `paidOut`, `commissionPercent` FROM ${t("market_creator_code")} WHERE `id` = ? FOR UPDATE")
            .execute(Tuple.of(id)).coAwait().firstOrNull()?.let {
                CodeRow(it.getLong("id"), it.getString("creator"), it.getString("code"), it.getLong("creatorUserId"), it.getLong("paidOut"), it.getLong("commissionPercent"))
            }

    // ===================================================================================================== accrual (O2 / O4)

    /**
     * 21 section 7.1: the earning of [order] for its creator code, `PENDING` until `paidAt + creatorEarningHoldDays` (`AVAILABLE` at once for 0 days),
     * `market_creator_code.earnings += amount`. `testMode` and every source other than `STOREFRONT` earn nothing (renewals, manual, in-game and gift orders); so does an
     * order whose commission is 0 or whose value is 0. `uq_order_code` makes a replay harmless. [order] must be read after `StampPaid`. Answers the new earning id.
     */
    suspend fun accrue(conn: SqlConnection, order: MarketOrder, items: List<MarketOrderItem>): Long? {
        val codeId = order.creatorCodeId ?: return null

        if (order.testMode || order.source != OrderSource.STOREFRONT) return null

        val code = lockCode(conn, codeId)

        if (code == null) {
            logger.warn("order {}: creator code {} is gone, no earning", order.id, codeId)

            return null
        }

        val c = config()
        val conversions = Conversions(
            order.baseCurrency.ifBlank { c.currency }, order.currency, order.fxRate, maxOf(1L, MoneyUtil.toMinor(c.creditValue)), c.removeCents, order.displayCurrency, order.displayRate
        )
        val base = baseAmount(order, items, conversions)
        val amount = Rounding.pctQ(base, code.commissionPercent.coerceIn(0L, 10_000L), conversions.bq)

        if (base <= 0L || amount <= 0L) return null

        val now = clock.now()
        val paidAt = order.paidAt ?: now
        val availableAt = paidAt + c.creatorEarningHoldDays.coerceAtLeast(0) * DAY_MS
        val id = earnings.add(
            MarketCreatorEarning(
                creatorCodeId = codeId, creatorUserId = code.creatorUserId, orderId = order.id, baseAmount = base, commissionPercent = code.commissionPercent, amount = amount,
                currency = conversions.baseCurrency, state = if (c.creatorEarningHoldDays <= 0) CreatorEarningState.AVAILABLE else CreatorEarningState.PENDING,
                availableAt = availableAt, createdAt = now, updatedAt = now
            ),
            conn
        ) ?: return null

        conn.preparedQuery("UPDATE ${t("market_creator_code")} SET `earnings` = `earnings` + ? WHERE `id` = ?").execute(Tuple.of(amount, codeId)).coAwait()

        return id
    }

    /**
     * 05 section 10: `baseAmount = fromOrder(sum(lineTotal - vatAmount))` over the `PRODUCT` / `BUNDLE` lines that are not credit packs (excluding VAT, shipping and fee);
     * an order that was paid entirely in credits earns on `halfUp(sum(credit lineTotal) x cv / 100)`.
     */
    internal fun baseAmount(order: MarketOrder, items: List<MarketOrderItem>, conversions: Conversions): Long {
        if (order.paymentMethodId == MethodInput.CREDITS && order.creditAmount > 0) {
            val creditTotal = CreditRunSnapshot.itemsTotal(order, items, conversions) ?: return 0L

            return Rounding.ratioQ(BigDecimal.valueOf(creditTotal).multiply(BigDecimal.valueOf(conversions.creditValue)), BigDecimal(100), conversions.bq)
        }

        var net = 0L

        for (item in items) {
            if (item.kind != OrderItemKind.PRODUCT && item.kind != OrderItemKind.BUNDLE) continue
            if (CreditService.isCreditPack(item)) continue

            net = Math.addExact(net, Math.subtractExact(item.lineTotal, item.vatAmount))
        }

        return conversions.fromOrder(net)
    }

    /** `PENDING` to `AVAILABLE` for every earning whose hold has passed; the number flipped. */
    suspend fun releaseDue(c: SqlClient): Int = earnings.releaseDue(clock.now(), c)

    // ===================================================================================================== balances

    /** 21 section 7.2 for one code; the due earnings are released first. */
    suspend fun balance(codeId: Long, c: SqlClient): CreatorBalance {
        releaseDue(c)

        return balanceOf(codeId, c)
    }

    private suspend fun balanceOf(codeId: Long, c: SqlClient): CreatorBalance {
        val row = c.preparedQuery(
            "SELECT COALESCE(SUM(`amount`), 0) AS earned, COALESCE(SUM(`reversedAmount`), 0) AS reversed, " +
                "COALESCE(SUM(CASE WHEN `state` = 'PENDING' THEN `amount` - `reversedAmount` ELSE 0 END), 0) AS pending, " +
                "COALESCE(SUM(CASE WHEN `state` IN ('AVAILABLE', 'PAID') THEN `amount` - `reversedAmount` ELSE 0 END), 0) AS payable " +
                "FROM ${t("market_creator_earning")} WHERE `creatorCodeId` = ?"
        ).execute(Tuple.of(codeId)).coAwait().first()
        val paidOut = c.preparedQuery("SELECT `paidOut` FROM ${t("market_creator_code")} WHERE `id` = ?").execute(Tuple.of(codeId)).coAwait().firstOrNull()?.getLong("paidOut") ?: 0L

        return CreatorBalance(row.getLong("earned"), row.getLong("reversed"), row.getLong("pending"), row.getLong("payable"), paidOut)
    }

    // ===================================================================================================== payout (21 section 7.4)

    /**
     * One transaction, code row locked first: a key that was used answers the stored payout (409 `IDEMPOTENCY_CONFLICT` when the request differs); `available`
     * is recomputed under the lock, `amount <= 0 || amount > available` is 400 `INVALID_PAYOUT_AMOUNT {available}` (also when `available` is negative);
     * `paidOut += amount`; earnings are marked `PAID` oldest first as far as the amount covers them; then by method: `CREDIT` posts `CREATOR_PAYOUT`
     * `payout:<id>` (400 `CREATOR_HAS_NO_ACCOUNT`, 409 `CREDITS_DISABLED`) and is `PAID` at once, `MANUAL` is `PAID` at once, `ACTION` plans the `cp:<id>` rows
     * and stays `PENDING` until they are settled ([settle]). 404 for an unknown code.
     */
    suspend fun requestPayout(codeId: Long, input: CreatorPayoutInput, idempotencyKey: String, actorUserId: Long): CreatorPayoutOutcome {
        val hash = requestHash(codeId, input)

        return db.tx { conn ->
            val code = lockCode(conn, codeId) ?: throw NotFound()

            payouts.getByIdempotencyKey(idempotencyKey, conn)?.let { return@tx replay(it, codeId, hash, code.code) }

            val c = config()

            releaseDue(conn)

            val balance = balanceOf(codeId, conn)
            val available = balance.payable - code.paidOut

            if (input.amount <= 0L || input.amount > available) throw InvalidPayoutAmount(MoneyUtil.toDecimal(available))

            val method = input.method
            var creditAmount = 0L

            when (method) {
                CreatorPayoutMethod.CREDIT -> {
                    if (code.creatorUserId == null) throw CreatorHasNoAccount()
                    if (!c.creditsEnabled) throw CreditsDisabled()

                    creditAmount = CreditMath.creditsFor(input.amount, maxOf(1L, MoneyUtil.toMinor(c.creditValue)), BigDecimal.ONE, RoundingMode.HALF_UP)

                    if (creditAmount <= 0L) throw InvalidPayoutAmount(MoneyUtil.toDecimal(available))
                }

                CreatorPayoutMethod.MANUAL -> if (input.note.isNullOrBlank()) throw RequestValueException("note", "REQUIRED")

                CreatorPayoutMethod.ACTION -> if (input.actions.isNullOrBlank()) throw RequestValueException("actions", "REQUIRED")
            }

            val now = clock.now()
            val id = payouts.add(
                MarketCreatorPayout(
                    creatorCodeId = codeId, creatorUserId = code.creatorUserId, amount = input.amount, currency = c.currency, method = method,
                    state = CreatorPayoutState.PENDING, actions = if (method == CreatorPayoutMethod.ACTION) input.actions else null, note = input.note?.trim()?.takeIf { it.isNotEmpty() },
                    paidBy = actorUserId, idempotencyKey = idempotencyKey, idempotencyHash = hash, createdAt = now, updatedAt = now
                ),
                conn
            ) ?: payouts.getByIdempotencyKey(idempotencyKey, conn)?.let { return@tx replay(it, codeId, hash, code.code) }
            ?: throw IllegalStateException("payout key $idempotencyKey is taken but its row is not visible")

            conn.preparedQuery("UPDATE ${t("market_creator_code")} SET `paidOut` = `paidOut` + ? WHERE `id` = ?").execute(Tuple.of(input.amount, codeId)).coAwait()
            markPaid(conn, codeId, id, input.amount, code.paidOut)

            when (method) {
                CreatorPayoutMethod.CREDIT -> {
                    val posted = credits.post(
                        Posting(
                            CreditTxType.CREATOR_PAYOUT, "payout:$id", code.creatorUserId!!, creditAmount, AccountRef.System(CreditSystemKey.ISSUANCE),
                            AccountRef.User(code.creatorUserId), actorUserId = actorUserId, note = input.note?.trim()?.takeIf { it.isNotEmpty() }?.take(Posting.MAX_NOTE_LENGTH)
                        ),
                        conn
                    )

                    payouts.transition(id, CreatorPayoutState.PENDING, CreatorPayoutState.PAID, actorUserId, now, posted.tx.id, now, conn)
                }

                CreatorPayoutMethod.MANUAL -> payouts.transition(id, CreatorPayoutState.PENDING, CreatorPayoutState.PAID, actorUserId, now, null, now, conn)

                CreatorPayoutMethod.ACTION -> {
                    deliveries.insertPlanned(conn, deliveries.planPayoutActions(conn, id, code.creator, input.amount, c.currency, input.actions))

                    // rows that were born FAILED (no target server, invalid player) settle the payout at once
                    settle(conn, id)
                }
            }

            CreatorPayoutOutcome(payouts.getById(id, conn)!!, false, code.code)
        }
    }

    private fun replay(existing: MarketCreatorPayout, codeId: Long, hash: String, code: String): CreatorPayoutOutcome {
        if (existing.creatorCodeId != codeId || existing.idempotencyHash != hash) throw IdempotencyConflict()

        return CreatorPayoutOutcome(existing, true, code)
    }

    /**
     * Earnings are marked `PAID` oldest first as far as the payouts cover them: what earlier payouts paid of a row that is still `AVAILABLE` counts first
     * (`paidOutBefore - sum of the PAID rows`), a row that is not covered completely stays `AVAILABLE` (no row is split).
     */
    private suspend fun markPaid(conn: SqlConnection, codeId: Long, payoutId: Long, amount: Long, paidOutBefore: Long) {
        val paidRows = earnings.sumNet(codeId, listOf(CreatorEarningState.PAID), conn)
        var cover = amount + maxOf(0L, paidOutBefore - paidRows)

        for (row in earnings.getByCodeId(codeId, conn).filter { it.state == CreatorEarningState.AVAILABLE }.sortedBy { it.id }) {
            val net = row.amount - row.reversedAmount

            if (net > cover) break

            if (earnings.attachPayout(row.id, payoutId, conn)) cover -= net
        }
    }

    /**
     * 21 section 7.4: only `PENDING` (an `ACTION` payout whose rows are not confirmed) or `FAILED`; 409 `INVALID_STATE` otherwise, 404 for an unknown payout.
     * Refused (409 `INVALID_STATE`) while a command may still run (`DELIVERING`), when one row was confirmed (`PARTIALLY_DELIVERED`: the creator has that part, the whole
     * amount would be paid again) and when a failed row may have taken effect (`DELIVERING`: `UNKNOWN_OUTCOME`, `ONLINE_WAIT_EXPIRED`, `WEBHOOK_DEAD` end in `CONFIRMED` when
     * the late result arrives). A cancelled payout's rows cannot move any more ([PayoutSettlement.lock]).
     */
    suspend fun cancelPayout(payoutId: Long): CreatorPayoutOutcome = db.tx { conn ->
        val first = payouts.getById(payoutId, conn) ?: throw NotFound()
        val code = lockCode(conn, first.creatorCodeId) ?: throw NotFound()

        lock(conn, payoutId)

        val payout = payouts.getById(payoutId, conn) ?: throw NotFound()

        if (payout.state != CreatorPayoutState.PENDING && payout.state != CreatorPayoutState.FAILED) throw InvalidState(payout.state.name)

        val rows = payoutRows(conn, payoutId)

        // a row that was sent may still take effect: cancelling the payout would pay the creator twice
        if (rows.any { it.status in IN_FLIGHT }) throw InvalidState(DELIVERING)

        val effective = effectiveRows(rows)

        // a row that took effect (or may have) is money the creator already has: the whole amount must not go back to the balance
        if (effective.any { it.status == DeliveryStatus.CONFIRMED }) throw InvalidState(PARTIALLY_DELIVERED)
        if (effective.any { it.status == DeliveryStatus.FAILED && it.lastErrorCode in MAY_HAVE_RUN }) throw InvalidState(DELIVERING)

        val now = clock.now()

        if (!payouts.transition(payoutId, payout.state, CreatorPayoutState.CANCELLED, null, null, null, now, conn)) throw InvalidState(payout.state.name)

        for (row in rows) {
            if (row.status in TERMINAL) continue

            deliveries.apply(conn, row.id, DeliveryEvent.Cancel())
        }

        conn.preparedQuery("UPDATE ${t("market_creator_code")} SET `paidOut` = `paidOut` - ? WHERE `id` = ?").execute(Tuple.of(payout.amount, code.id)).coAwait()
        conn.preparedQuery("UPDATE ${t("market_creator_earning")} SET `state` = 'AVAILABLE', `payoutId` = NULL, `updatedAt` = ? WHERE `payoutId` = ? AND `state` = 'PAID'")
            .execute(Tuple.of(now, payoutId)).coAwait()

        CreatorPayoutOutcome(payouts.getById(payoutId, conn)!!, false, code.code)
    }

    private fun requestHash(codeId: Long, input: CreatorPayoutInput): String {
        val text = "$codeId|${input.amount}|${input.method.name}|${input.note?.trim().orEmpty()}|${input.actions.orEmpty()}"

        return MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    // ===================================================================================================== ACTION payouts follow their rows

    private class PayoutRow(val id: Long, val actionId: String, val serverId: Long, val unitIndex: Int, val attemptGroup: Int, val status: DeliveryStatus, val lastErrorCode: String?)

    /** The effective row of a logical delivery is the one of the highest attempt group, cancelled rows are dropped (08 section 13). */
    private fun effectiveRows(rows: List<PayoutRow>): List<PayoutRow> =
        rows.groupBy { Triple(it.actionId, it.serverId, it.unitIndex) }.values.map { group -> group.maxBy { it.attemptGroup } }.filter { it.status != DeliveryStatus.CANCELLED }

    private suspend fun payoutRows(c: SqlClient, payoutId: Long): List<PayoutRow> =
        c.preparedQuery(
            "SELECT `id`, `actionId`, `serverId`, `unitIndex`, `attemptGroup`, `status`, `lastErrorCode` FROM ${t("market_delivery")} WHERE `sourceType` = 'CREATOR_PAYOUT' AND `sourceId` = ? ORDER BY `id`"
        ).execute(Tuple.of(payoutId)).coAwait().map {
            PayoutRow(it.getLong("id"), it.getString("actionId"), it.getLong("serverId") ?: 0L, it.getInteger("unitIndex") ?: 0, it.getInteger("attemptGroup") ?: 0, DeliveryStatus.valueOf(it.getString("status")), it.getString("lastErrorCode"))
        }

    override suspend fun lock(conn: SqlClient, payoutId: Long): CreatorPayoutState? =
        conn.preparedQuery("SELECT `state` FROM ${t("market_creator_payout")} WHERE `id` = ? FOR UPDATE").execute(Tuple.of(payoutId)).coAwait().firstOrNull()
            ?.getString("state")?.let { CreatorPayoutState.valueOf(it) }

    override suspend fun settle(conn: SqlClient, payoutId: Long) {
        val payout = payouts.getById(payoutId, conn) ?: return

        if (payout.method != CreatorPayoutMethod.ACTION || (payout.state != CreatorPayoutState.PENDING && payout.state != CreatorPayoutState.FAILED)) return

        val effective = effectiveRows(payoutRows(conn, payoutId))

        if (effective.isEmpty()) return

        val now = clock.now()

        when {
            // a failed row that was retried is open again: the payout follows it back
            effective.any { it.status != DeliveryStatus.CONFIRMED && it.status != DeliveryStatus.FAILED } ->
                if (payout.state == CreatorPayoutState.FAILED) payouts.transition(payoutId, CreatorPayoutState.FAILED, CreatorPayoutState.PENDING, null, null, null, now, conn)

            effective.all { it.status == DeliveryStatus.CONFIRMED } -> payouts.transition(payoutId, payout.state, CreatorPayoutState.PAID, null, now, null, now, conn)

            payout.state == CreatorPayoutState.PENDING -> payouts.transition(payoutId, CreatorPayoutState.PENDING, CreatorPayoutState.FAILED, null, null, null, now, conn)
        }
    }

    // ===================================================================================================== read models (21 section 7.5, 04 sections 4 and 6)

    private fun money(value: Long): Double = MoneyUtil.toDecimal(value)

    /** `GET /creator-codes/report`: one row per code, [from] / [to] (epoch ms) bound the earnings counted in `earned`, `pending`, `reversed`, `revenue` and `uses`; `paidOut` and `available` are the balances now. */
    suspend fun report(from: Long?, to: Long?): JsonObject {
        val c = client()

        releaseDue(c)

        val range = StringBuilder()
        val args = ArrayList<Any?>()

        if (from != null) {
            range.append(" AND e.`createdAt` >= ?")
            args += from
        }

        if (to != null) {
            range.append(" AND e.`createdAt` <= ?")
            args += to
        }

        val rows = c.preparedQuery(
            "SELECT c.`id`, c.`creator`, c.`code`, c.`paidOut`, COUNT(e.`id`) AS uses, COALESCE(SUM(e.`baseAmount`), 0) AS revenue, COALESCE(SUM(e.`amount`), 0) AS earned, " +
                "COALESCE(SUM(e.`reversedAmount`), 0) AS reversed, COALESCE(SUM(CASE WHEN e.`state` = 'PENDING' THEN e.`amount` - e.`reversedAmount` ELSE 0 END), 0) AS pending, " +
                "(SELECT COALESCE(SUM(x.`amount` - x.`reversedAmount`), 0) FROM ${t("market_creator_earning")} x WHERE x.`creatorCodeId` = c.`id` AND x.`state` IN ('AVAILABLE', 'PAID')) AS payable " +
                "FROM ${t("market_creator_code")} c LEFT JOIN ${t("market_creator_earning")} e ON e.`creatorCodeId` = c.`id`$range " +
                "WHERE c.`deletedAt` IS NULL OR EXISTS (SELECT 1 FROM ${t("market_creator_earning")} y WHERE y.`creatorCodeId` = c.`id`) GROUP BY c.`id`, c.`creator`, c.`code`, c.`paidOut` ORDER BY c.`id`"
        ).execute(Tuple.from(args)).coAwait()

        return JsonObject()
            .put(
                "creators",
                JsonArray(
                    rows.map {
                        JsonObject().put("id", it.getLong("id")).put("creator", it.getString("creator")).put("code", it.getString("code")).put("uses", it.getLong("uses"))
                            .put("revenue", money(it.getLong("revenue"))).put("earned", money(it.getLong("earned"))).put("pending", money(it.getLong("pending")))
                            .put("reversed", money(it.getLong("reversed"))).put("paidOut", money(it.getLong("paidOut"))).put("available", money(it.getLong("payable") - it.getLong("paidOut")))
                    }
                )
            )
            .put("currency", config().currency)
    }

    /** `GET /creator-codes/:id/earnings`: [state] filters, paged newest first; 404 for an unknown code, [CreatorPageOutOfRange] beyond the last page. */
    suspend fun earningsOf(codeId: Long, state: CreatorEarningState?, window: Paging.Window): JsonObject {
        val c = client()

        requireCode(codeId, c)
        releaseDue(c)

        val where = if (state == null) "`creatorCodeId` = ?" else "`creatorCodeId` = ? AND `state` = ?"
        val args = if (state == null) listOf<Any?>(codeId) else listOf(codeId, state.name)
        val count = c.preparedQuery("SELECT COUNT(*) FROM ${t("market_creator_earning")} WHERE $where").execute(Tuple.from(args)).coAwait().first().getLong(0)
        val total = Paging.totalPages(count, window.pageSize)

        if (Paging.isBeyondLast(window.page, total)) throw CreatorPageOutOfRange()

        val rows = c.preparedQuery(
            "SELECT `id`, `orderId`, `baseAmount`, `commissionPercent`, `amount`, `reversedAmount`, `state`, `availableAt`, `createdAt` FROM ${t("market_creator_earning")} " +
                "WHERE $where ORDER BY `id` DESC LIMIT ? OFFSET ?"
        ).execute(Tuple.from(args + listOf(window.pageSize.toLong(), window.offset))).coAwait()

        return JsonObject()
            .put(
                "earnings",
                JsonArray(
                    rows.map {
                        JsonObject().put("id", it.getLong("id")).put("orderId", it.getLong("orderId")).put("baseAmount", money(it.getLong("baseAmount")))
                            .put("commissionPercent", money(it.getLong("commissionPercent"))).put("amount", money(it.getLong("amount")))
                            .put("reversedAmount", money(it.getLong("reversedAmount"))).put("state", it.getString("state")).put("availableAt", it.getLong("availableAt"))
                            .put("createdAt", it.getLong("createdAt"))
                    }
                )
            )
            .put("earningCount", count)
            .put("totalPage", total)
    }

    /** `GET /creator-codes/:id/payouts`: newest first, `paidBy` is the admin's username (null when unknown); 404 for an unknown code. */
    suspend fun payoutsOf(codeId: Long): JsonObject {
        val c = client()

        requireCode(codeId, c)

        val list = payouts.getByCodeId(codeId, c)
        val names = list.mapNotNull { it.paidBy }.toSet().associateWith { users.usernameOf(it, c) }

        return JsonObject().put(
            "payouts",
            JsonArray(
                list.map {
                    JsonObject().put("id", it.id).put("amount", money(it.amount)).put("currency", it.currency).put("method", it.method.name).put("state", it.state.name)
                        .put("note", it.note).put("paidBy", it.paidBy?.let { id -> names[id] }).put("paidAt", it.paidAt).put("createdAt", it.createdAt)
                }
            )
        )
    }

    private suspend fun requireCode(codeId: Long, c: SqlClient) {
        if (c.preparedQuery("SELECT `id` FROM ${t("market_creator_code")} WHERE `id` = ?").execute(Tuple.of(codeId)).coAwait().firstOrNull() == null) throw NotFound()
    }

    /**
     * `GET /api/market/me/creator`: the codes whose `creatorUserId` is [userId], the totals over them, their earnings (newest first, paged; the order number only) and
     * their payouts. 404 when the user owns no code; [CreatorPageOutOfRange] beyond the last page. No buyer data is exposed.
     */
    suspend fun mine(userId: Long, window: Paging.Window): JsonObject {
        val c = client()

        releaseDue(c)

        val codes = c.preparedQuery(
            "SELECT `id`, `code`, `discount`, `unit`, `commissionPercent`, `usedCount`, `status` FROM ${t("market_creator_code")} WHERE `creatorUserId` = ? AND `deletedAt` IS NULL ORDER BY `id`"
        ).execute(Tuple.of(userId)).coAwait().toList()

        if (codes.isEmpty()) throw NotFound()

        val ids = codes.map { it.getLong("id") }
        val marks = ids.joinToString(",") { "?" }
        var earned = 0L
        var reversed = 0L
        var pending = 0L
        var payable = 0L
        var paidOut = 0L

        for (id in ids) {
            val b = balanceOf(id, c)

            earned += b.earned
            reversed += b.reversed
            pending += b.pending
            payable += b.payable
            paidOut += b.paidOut
        }

        val count = c.preparedQuery("SELECT COUNT(*) FROM ${t("market_creator_earning")} WHERE `creatorCodeId` IN ($marks)").execute(Tuple.from(ids)).coAwait().first().getLong(0)
        val total = Paging.totalPages(count, window.pageSize)

        if (Paging.isBeyondLast(window.page, total)) throw CreatorPageOutOfRange()

        val earningRows = c.preparedQuery(
            "SELECT `orderId`, `amount`, `state`, `availableAt`, `createdAt` FROM ${t("market_creator_earning")} WHERE `creatorCodeId` IN ($marks) ORDER BY `id` DESC LIMIT ? OFFSET ?"
        ).execute(Tuple.from(ids + listOf(window.pageSize.toLong(), window.offset))).coAwait()
        val payoutRows = c.preparedQuery(
            "SELECT `amount`, `method`, `state`, `paidAt`, `createdAt` FROM ${t("market_creator_payout")} WHERE `creatorCodeId` IN ($marks) ORDER BY `id` DESC LIMIT $MINE_PAYOUTS"
        ).execute(Tuple.from(ids)).coAwait()

        return JsonObject()
            .put(
                "codes",
                JsonArray(
                    codes.map {
                        JsonObject().put("code", it.getString("code")).put("discount", money(it.getLong("discount"))).put("unit", it.getString("unit"))
                            .put("commissionPercent", money(it.getLong("commissionPercent"))).put("usedCount", it.getInteger("usedCount")).put("status", it.getString("status"))
                    }
                )
            )
            .put(
                "totals",
                JsonObject().put("earned", money(earned)).put("reversed", money(reversed)).put("pending", money(pending)).put("paidOut", money(paidOut))
                    .put("available", money(payable - paidOut)).put("currency", config().currency)
            )
            .put(
                "earnings",
                JsonArray(
                    earningRows.map {
                        JsonObject().put("orderNumber", "#${it.getLong("orderId")}").put("amount", money(it.getLong("amount"))).put("state", it.getString("state"))
                            .put("availableAt", it.getLong("availableAt")).put("createdAt", it.getLong("createdAt"))
                    }
                )
            )
            .put("earningCount", count)
            .put("totalPage", total)
            .put(
                "payouts",
                JsonArray(
                    payoutRows.map {
                        JsonObject().put("amount", money(it.getLong("amount"))).put("method", it.getString("method")).put("state", it.getString("state")).put("paidAt", it.getLong("paidAt"))
                            .put("createdAt", it.getLong("createdAt"))
                    }
                )
            )
    }

    companion object {
        const val DAY_MS = 86_400_000L
        const val DELIVERING = "DELIVERING"
        const val PARTIALLY_DELIVERED = "PARTIALLY_DELIVERED"
        private val MAY_HAVE_RUN = setOf(DeliveryError.UNKNOWN_OUTCOME, DeliveryError.ONLINE_WAIT_EXPIRED, DeliveryError.WEBHOOK_DEAD)
        private const val MINE_PAYOUTS = 50
        private val IN_FLIGHT = setOf(DeliveryStatus.SENDING, DeliveryStatus.SENT, DeliveryStatus.QUEUED)
        private val TERMINAL = setOf(DeliveryStatus.CONFIRMED, DeliveryStatus.FAILED, DeliveryStatus.CANCELLED)
        private val logger = LoggerFactory.getLogger(CreatorService::class.java)
    }
}

/**
 * The `AccrueCreatorEarning` effect of O2 / O4 (06 section 11, 21 section 7.1) for [OrderService]: wrap the [ForeignEffects] it is given so that the effect calls
 * [CreatorService.accrue] and every other effect still goes to [next]. The order is read again, because the `LockedOrder` was read before `StampPaid`
 * (`paidAt`). [creators] is a provider so that the order service can be built before the creator service exists.
 */
class CreatorEffects(
    private val creators: () -> CreatorService,
    private val orders: MarketOrderDao,
    private val next: ForeignEffects = ForeignEffects.PENDING_SLICES
) : ForeignEffects {
    override suspend fun apply(conn: SqlConnection, locked: LockedOrder, effect: OrderEffect) {
        if (effect !is OrderEffect.AccrueCreatorEarning) {
            next.apply(conn, locked, effect)

            return
        }

        if (locked.order.creatorCodeId == null) return

        val order = orders.getById(locked.order.id, conn) ?: error("order ${locked.order.id} vanished inside its transaction")

        creators().accrue(conn, order, locked.items)
    }
}
