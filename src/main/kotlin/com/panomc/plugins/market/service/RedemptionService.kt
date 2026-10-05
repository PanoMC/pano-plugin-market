package com.panomc.plugins.market.service

import com.panomc.plugins.market.core.time.Clock
import com.panomc.plugins.market.db.dao.MarketRedemptionDao
import com.panomc.plugins.market.db.model.MarketRedemption
import com.panomc.plugins.market.db.model.RedemptionKind
import com.panomc.plugins.market.db.model.RedemptionState
import com.panomc.plugins.market.db.tx.CodeRef
import com.panomc.plugins.market.db.tx.LockedCode
import com.panomc.plugins.market.db.tx.Locks
import com.panomc.plugins.market.error.InvalidCoupon
import com.panomc.plugins.market.error.InvalidCreatorCode
import com.panomc.plugins.market.error.InvalidGiftCode
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple

/** One use of a code or an automatic discount by an order: what [RedemptionService.reserve] counts and [RedemptionService.record] stores. */
class CodeUse(
    val kind: RedemptionKind,
    val refId: Long,
    /** Snapshot of the code text, `null` for a discount. */
    val code: String?,
    /** The discount this reference gave, in the order currency. */
    val amount: Long,
    val currency: String
) {
    internal val ref get() = CodeRef(kind, refId)
}

/**
 * Who the per-customer limit of a code counts against (01 section 3.5, 06 section 7.2): the payer ([buyerKey], and
 * [email] for guests) and the recipient. [recipientKey] is stored on the redemption row; [recipientKeys] is the key set
 * K of the count (a recipient can be known as `u:<id>` and as `g:<name>`); empty keys never match.
 */
class CustomerKeys(
    val userId: Long?,
    val buyerKey: String,
    val email: String?,
    val recipientKey: String = "",
    val recipientKeys: List<String> = listOfNotNull(recipientKey.ifEmpty { null })
)

/**
 * A discount that cannot be used any more: its `usageLimit` is exhausted or its row is gone or soft-deleted. Internal:
 * checkout turns it into `QuoteChanged` and re-prices the cart without the discount (06 section 5.3 B9); it never
 * reaches a buyer.
 */
class DiscountUnavailable(val discountId: Long) : RuntimeException("discount $discountId is exhausted or gone")

/**
 * The use of coupons, creator codes, gift codes and automatic discounts by orders (06 section 7.2): the per-customer
 * limit, the atomic global counter, the `market_redemption` row, and the `HELD` / `APPLIED` / `RELEASED` life of that
 * row. Every method runs on the connection of the caller's `MarketDb.tx` and never opens a transaction of its own; the
 * code rows are locked here in the global order ([Locks.codes]) unless the caller's lock already holds them.
 *
 * `usedCount` of a code row always equals the number of its `HELD` + `APPLIED` redemptions (invariant I6): it is moved
 * only by the guarded statements below, in the same transaction as the row it counts.
 */
class RedemptionService(
    private val clock: Clock,
    private val locks: Locks,
    private val redemptions: MarketRedemptionDao
) {
    private fun table(name: String) = "`${redemptions.prefix()}$name`"

    /** Locks the rows [uses] refers to (levels 1 and 2 of the lock order); the call of [reserve] does this itself, a caller that also locks products must do it first. */
    suspend fun lockFor(conn: SqlConnection, uses: Collection<CodeUse>): Map<CodeRef, LockedCode> =
        locks.codes(conn, uses.map { it.ref })

    /**
     * Steps 1 and 2 of 06 section 7.2 for every use, in lock order: the per-customer limit (`customerRedeemLimit`, coupon
     * and gift only) counted under the row lock, then the guarded `usedCount + 1`. Nothing is written when a use fails;
     * uses that came before it in the loop are rolled back by the caller's transaction (an exception always rolls back).
     *
     * Throws `InvalidCoupon` / `InvalidCreatorCode` / `InvalidGiftCode` with `CODE_LIMIT_REACHED` (limit reached) or
     * `CODE_NOT_FOUND` (row gone or soft-deleted), and [DiscountUnavailable] for a discount.
     */
    suspend fun reserve(conn: SqlConnection, uses: List<CodeUse>, customer: CustomerKeys) {
        if (uses.isEmpty()) return

        require(uses.map { it.ref }.toSet().size == uses.size) { "a code or discount is used once per order" }

        val locked = lockFor(conn, uses)

        for (use in uses.sortedBy { it.ref }) {
            val row = locked[use.ref]

            if (row == null || row.deletedAt != null) throw notFound(use)

            val customerLimit = row.customerLimit

            if (customerLimit != null) {
                val used = redemptions.countForCustomer(
                    use.kind, use.refId, customer.buyerKey, customer.email?.lowercase(), customer.recipientKeys.filter { it.isNotEmpty() }, conn
                )

                if (used >= customerLimit) throw limitReached(use)
            }

            val limitColumn = if (use.kind == RedemptionKind.DISCOUNT) "usageLimit" else "redeemLimit"
            val changed = conn.preparedQuery(
                "UPDATE ${table(Locks.tableOf(use.kind))} SET `usedCount` = `usedCount` + 1 WHERE `id` = ? AND (`$limitColumn` IS NULL OR `usedCount` < `$limitColumn`)"
            ).execute(Tuple.of(use.refId)).coAwait().rowCount()

            if (changed == 0) throw limitReached(use)
        }
    }

    /**
     * Step 3 of 06 section 7.2: one `HELD` row per use for [orderId]. `uq_kind_ref_order` makes a re-run harmless: a row
     * that exists is left as it is. Returns the number of rows inserted.
     */
    suspend fun record(conn: SqlConnection, orderId: Long, uses: List<CodeUse>, customer: CustomerKeys): Int {
        var inserted = 0

        for (use in uses.sortedBy { it.ref }) {
            val now = clock.now()
            val id = redemptions.add(
                MarketRedemption(
                    kind = use.kind, refId = use.refId, code = use.code, orderId = orderId, userId = customer.userId,
                    buyerKey = customer.buyerKey, email = customer.email?.lowercase(), recipientKey = customer.recipientKey,
                    amount = use.amount, currency = use.currency, state = RedemptionState.HELD, createdAt = now, updatedAt = now
                ),
                conn
            )

            if (id != null) inserted++
        }

        return inserted
    }

    /** [reserve] and [record] in one call, for a caller that already has the order id. */
    suspend fun hold(conn: SqlConnection, orderId: Long, uses: List<CodeUse>, customer: CustomerKeys): Int {
        reserve(conn, uses, customer)

        return record(conn, orderId, uses, customer)
    }

    /** O2 / O4: every `HELD` row of the order becomes `APPLIED` (06 section 7.2 "Commit"); the number of rows changed. */
    suspend fun commit(conn: SqlConnection, orderId: Long): Int =
        conn.preparedQuery("UPDATE ${table("market_redemption")} SET `state` = 'APPLIED', `updatedAt` = ? WHERE `orderId` = ? AND `state` = 'HELD'")
            .execute(Tuple.of(clock.now(), orderId)).coAwait().rowCount()

    /**
     * O5 to O8: every `HELD` row of the order is selected `FOR UPDATE` and, one by one, becomes `RELEASED` with
     * `usedCount - 1` on its code row (06 section 7.2 "Release"). The code rows must be locked already
     * (`Locks.forOrder(RELEASE)`). Each row moves once: the state change is guarded, the counter follows only a row that
     * really changed. Returns the number of rows released.
     */
    suspend fun release(conn: SqlConnection, orderId: Long): Int {
        val held = conn.preparedQuery(
            "SELECT `id`, `kind`, `refId` FROM ${table("market_redemption")} WHERE `orderId` = ? AND `state` = 'HELD' ORDER BY `kind`, `refId` FOR UPDATE"
        ).execute(Tuple.of(orderId)).coAwait()
        var released = 0

        for (row in held) {
            val moved = conn.preparedQuery("UPDATE ${table("market_redemption")} SET `state` = 'RELEASED', `updatedAt` = ? WHERE `id` = ? AND `state` = 'HELD'")
                .execute(Tuple.of(clock.now(), row.getLong("id"))).coAwait().rowCount()

            if (moved == 0) continue

            released++

            conn.preparedQuery("UPDATE ${table(Locks.tableOf(RedemptionKind.valueOf(row.getString("kind"))))} SET `usedCount` = `usedCount` - 1 WHERE `id` = ? AND `usedCount` > 0")
                .execute(Tuple.of(row.getLong("refId"))).coAwait()
        }

        return released
    }

    /**
     * The code part of a re-reserve (06 section 7.4 `reReserve`): every `RELEASED` row of the order goes back to `HELD`
     * and its counter to `usedCount + 1` **without** the limit condition, because the buyer already paid the discounted
     * price. A coupon, creator code or gift whose row no longer exists fails the whole step (`CODE_NOT_FOUND`); a
     * discount that is gone is skipped and its row stays `RELEASED` (nothing counts it). Returns the rows brought back.
     */
    suspend fun reHold(conn: SqlConnection, orderId: Long): Int {
        val released = conn.preparedQuery(
            "SELECT `id`, `kind`, `refId`, `code` FROM ${table("market_redemption")} WHERE `orderId` = ? AND `state` = 'RELEASED' ORDER BY `kind`, `refId` FOR UPDATE"
        ).execute(Tuple.of(orderId)).coAwait()
        var held = 0

        for (row in released) {
            val kind = RedemptionKind.valueOf(row.getString("kind"))
            val counted = conn.preparedQuery("UPDATE ${table(Locks.tableOf(kind))} SET `usedCount` = `usedCount` + 1 WHERE `id` = ?")
                .execute(Tuple.of(row.getLong("refId"))).coAwait().rowCount()

            if (counted == 0) {
                if (kind == RedemptionKind.DISCOUNT) continue

                throw notFound(CodeUse(kind, row.getLong("refId"), row.getString("code"), 0, ""))
            }

            conn.preparedQuery("UPDATE ${table("market_redemption")} SET `state` = 'HELD', `updatedAt` = ? WHERE `id` = ? AND `state` = 'RELEASED'")
                .execute(Tuple.of(clock.now(), row.getLong("id"))).coAwait()

            held++
        }

        return held
    }

    private fun limitReached(use: CodeUse): Throwable = when (use.kind) {
        RedemptionKind.COUPON -> InvalidCoupon(CODE_LIMIT_REACHED)
        RedemptionKind.CREATOR_CODE -> InvalidCreatorCode(CODE_LIMIT_REACHED)
        RedemptionKind.GIFT -> InvalidGiftCode(CODE_LIMIT_REACHED)
        RedemptionKind.DISCOUNT -> DiscountUnavailable(use.refId)
    }

    private fun notFound(use: CodeUse): Throwable = when (use.kind) {
        RedemptionKind.COUPON -> InvalidCoupon(CODE_NOT_FOUND)
        RedemptionKind.CREATOR_CODE -> InvalidCreatorCode(CODE_NOT_FOUND)
        RedemptionKind.GIFT -> InvalidGiftCode(CODE_NOT_FOUND)
        RedemptionKind.DISCOUNT -> DiscountUnavailable(use.refId)
    }

    companion object {
        const val CODE_LIMIT_REACHED = "CODE_LIMIT_REACHED"
        const val CODE_NOT_FOUND = "CODE_NOT_FOUND"
    }
}
