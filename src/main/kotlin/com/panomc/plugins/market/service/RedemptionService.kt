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
import com.panomc.plugins.market.util.GiftType
import io.vertx.core.json.JsonArray
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.SqlClient
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
 * `market_gift` as a redemption reads it (01 section 3.4). [productIds] is the pool of a `RANDOM` gift (the single `productId` of a
 * `PRODUCT` gift is [productId]). [usedCount] is the counter of the row, never written by anything but the guarded statements.
 */
class GiftRow(
    val id: Long,
    val code: String,
    val name: String,
    val type: GiftType,
    val productId: Long?,
    val productIds: List<Long>,
    /** Credits x 100 of a `CREDIT` gift. */
    val creditAmount: Long?,
    val active: Boolean,
    val startDate: Long?,
    val expiryDate: Long?,
    val redeemLimit: Int?,
    val customerRedeemLimit: Int?,
    val usedCount: Int,
    val deletedAt: Long?
) {
    /** 21 section 6 step 1: an inactive or deleted code is `CODE_NOT_FOUND`, then the window: `CODE_NOT_STARTED` / `CODE_EXPIRED`. */
    fun checkOpen(now: Long) {
        if (deletedAt != null || !active) throw InvalidGiftCode(RedemptionService.CODE_NOT_FOUND)

        if (startDate != null && now < startDate) throw InvalidGiftCode(RedemptionService.CODE_NOT_STARTED)

        if (expiryDate != null && now > expiryDate) throw InvalidGiftCode(RedemptionService.CODE_EXPIRED)
    }

    /** The products a redemption of this gift can hand out, in the order of the pool. */
    val candidates: List<Long> get() = when (type) {
        GiftType.PRODUCT -> listOfNotNull(productId)
        GiftType.RANDOM -> productIds
        GiftType.CREDIT -> emptyList()
    }
}

/** One row of `GET /<kind>/:id/redemptions` (04 section 6). [playerUsername] is the order's payer. */
class RedemptionRow(
    val orderId: Long,
    val playerUsername: String,
    val amount: Long,
    val currency: String,
    val state: RedemptionState,
    val createdAt: Long
)

/** A page of [RedemptionRow] and the number of rows behind it. */
class RedemptionPage(val rows: List<RedemptionRow>, val total: Long)

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

    // ----- gift rows and redemption lists (MK-113) ---------------------------------------------------------------------------

    private fun giftOf(row: Row): GiftRow = GiftRow(
        id = row.getLong("id"), code = row.getString("code"), name = row.getString("name") ?: "", type = GiftType.valueOf(row.getString("type")),
        productId = row.getLong("productId"), productIds = row.getString("productIds")?.let { raw -> runCatching { JsonArray(raw).map { (it as Number).toLong() } }.getOrNull() }.orEmpty(),
        creditAmount = row.getLong("creditAmount"), active = row.getString("status") == "ACTIVE", startDate = row.getLong("startDate"), expiryDate = row.getLong("expiryDate"),
        redeemLimit = row.getInteger("redeemLimit"), customerRedeemLimit = row.getInteger("customerRedeemLimit"), usedCount = row.getInteger("usedCount"), deletedAt = row.getLong("deletedAt")
    )

    private val giftColumns =
        "`id`, `code`, `name`, `type`, `productId`, `productIds`, `creditAmount`, `status`, `startDate`, `expiryDate`, `redeemLimit`, `customerRedeemLimit`, `usedCount`, `deletedAt`"

    /** The live (not soft-deleted) gift with [code], an unlocked read; `null` when there is none. Callers normalise the code first (11 section 6.2). */
    suspend fun giftByCode(client: SqlClient, code: String): GiftRow? =
        client.preparedQuery("SELECT $giftColumns FROM ${table("market_gift")} WHERE `code` = ? AND `deletedAt` IS NULL").execute(Tuple.of(code)).coAwait()
            .firstOrNull()?.let(::giftOf)

    /** The gift row by id as it is now (an unlocked read, deleted rows included). */
    suspend fun gift(client: SqlClient, id: Long): GiftRow? =
        client.preparedQuery("SELECT $giftColumns FROM ${table("market_gift")} WHERE `id` = ?").execute(Tuple.of(id)).coAwait().firstOrNull()?.let(::giftOf)

    /** 21 section 6 step 1: the gift row `FOR UPDATE` (a locking read: the freshest committed row, whatever the snapshot of the transaction). */
    suspend fun lockGift(conn: SqlConnection, id: Long): GiftRow? =
        conn.preparedQuery("SELECT $giftColumns FROM ${table("market_gift")} WHERE `id` = ? FOR UPDATE").execute(Tuple.of(id)).coAwait().firstOrNull()?.let(::giftOf)

    /**
     * The redemptions of one coupon, creator code or gift (04 section 6), newest first, `RELEASED` rows included (the list is the history; `state`
     * says what still counts). [page] is 1-based.
     */
    suspend fun listFor(client: SqlClient, kind: RedemptionKind, refId: Long, page: Long, pageSize: Long): RedemptionPage {
        val rows = client.preparedQuery(
            "SELECT r.`orderId`, o.`playerUsername`, r.`amount`, r.`currency`, r.`state`, r.`createdAt` FROM ${table("market_redemption")} r " +
                "LEFT JOIN ${table("market_order")} o ON o.`id` = r.`orderId` WHERE r.`kind` = ? AND r.`refId` = ? ORDER BY r.`createdAt` DESC, r.`id` DESC LIMIT ? OFFSET ?"
        ).execute(Tuple.of(kind.name, refId, pageSize, (page - 1) * pageSize)).coAwait().map {
            RedemptionRow(it.getLong("orderId"), it.getString("playerUsername") ?: "", it.getLong("amount"), it.getString("currency"), RedemptionState.valueOf(it.getString("state")), it.getLong("createdAt"))
        }
        val total = client.preparedQuery("SELECT COUNT(*) AS `n` FROM ${table("market_redemption")} WHERE `kind` = ? AND `refId` = ?").execute(Tuple.of(kind.name, refId)).coAwait()
            .first().getLong("n")

        return RedemptionPage(rows, total)
    }

    /** Whether any redemption row (any state) names the code: a coupon, creator code, gift or discount with one is soft-deleted, one without is removed (01 section 13). */
    suspend fun hasRedemptions(client: SqlClient, kind: RedemptionKind, refId: Long): Boolean =
        client.preparedQuery("SELECT 1 FROM ${table("market_redemption")} WHERE `kind` = ? AND `refId` = ? LIMIT 1").execute(Tuple.of(kind.name, refId)).coAwait().iterator().hasNext()

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
        const val CODE_NOT_STARTED = "CODE_NOT_STARTED"
        const val CODE_EXPIRED = "CODE_EXPIRED"
    }
}
