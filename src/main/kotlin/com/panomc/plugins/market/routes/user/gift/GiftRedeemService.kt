package com.panomc.plugins.market.routes.user.gift

import com.panomc.plugins.market.core.cart.CartLimits
import com.panomc.plugins.market.core.time.Clock
import com.panomc.plugins.market.error.CodeAttemptsLocked
import com.panomc.plugins.market.error.InvalidGiftCode
import com.panomc.plugins.market.routes.panel.discount.PromotionRules
import com.panomc.plugins.market.service.CheckoutResult
import com.panomc.plugins.market.service.CheckoutService
import com.panomc.plugins.market.service.QuoteCaller
import com.panomc.plugins.market.service.RedemptionService
import io.vertx.sqlclient.SqlClient

/**
 * The brute-force lock of codes (11 section 12), scope `GIFT` here. `CodeGuard` (MK-152) is its implementation; until it lands [NONE] never locks and
 * never counts, so nothing is guessed at here: the seam is what the redeem route asks first (21 section 6) and what it tells about an unknown code.
 */
interface GiftCodeGuard {
    /** `lockedUntil` (epoch ms) when one of [subjects] is locked, else `null`. A locked subject is refused even with a valid code (the code is not looked up). */
    suspend fun lockedUntil(subjects: List<String>): Long?

    /** An unknown (or malformed, or soft-deleted) code was tried by [subjects]: counted (11 section 12.2 step 4). */
    suspend fun recordUnknown(subjects: List<String>, code: String)

    companion object {
        val NONE: GiftCodeGuard = object : GiftCodeGuard {
            override suspend fun lockedUntil(subjects: List<String>): Long? = null

            override suspend fun recordUnknown(subjects: List<String>, code: String) = Unit
        }
    }
}

/** The guard the redeem route uses; MK-152 sets its `CodeGuard` here (a plugin start sets it once). */
object GiftCodeGuards {
    @Volatile
    var guard: GiftCodeGuard = GiftCodeGuard.NONE
}

/**
 * `POST /api/market/me/gifts/redeem` (21 section 6): the code guard first, the lookup of the (normalised) code, then
 * [CheckoutService.redeemGift] with the gift's id. An unknown, malformed or soft-deleted code is the same `CODE_NOT_FOUND` and is counted by the
 * guard; a lock answers 429 `CODE_ATTEMPTS_LOCKED` before any lookup.
 */
class GiftRedeemService(
    private val checkout: CheckoutService,
    private val redemptions: RedemptionService,
    private val client: suspend () -> SqlClient,
    private val clock: Clock,
    private val guard: () -> GiftCodeGuard = { GiftCodeGuards.guard }
) {
    suspend fun redeem(rawCode: String?, targetServerId: Long?, fieldValues: Map<String, Any?>, caller: QuoteCaller, locale: String?): CheckoutResult {
        val subjects = subjectsOf(caller)
        val code = CartLimits.normalizeCode(rawCode)?.takeIf { CartLimits.codeFits(it) && PromotionRules.codeValid(it) }

        guard().lockedUntil(subjects)?.let { until -> throw CodeAttemptsLocked(maxOf(1L, Math.ceil((until - clock.now()) / 1000.0).toLong())) }

        val gift = code?.let { redemptions.giftByCode(client(), it) }

        if (gift == null) {
            guard().recordUnknown(subjects, code ?: rawCode.orEmpty())

            throw InvalidGiftCode(RedemptionService.CODE_NOT_FOUND)
        }

        return checkout.redeemGift(CheckoutService.GiftRedeemRequest(gift.id, targetServerId, fieldValues, locale), caller, client())
    }

    /** 11 section 12.2: `ip:<ip>` when the address is trusted, `b:<buyerKey>` for the account; a caller with neither is `anon`. */
    private fun subjectsOf(caller: QuoteCaller): List<String> = buildList {
        caller.clientIp?.let { add("ip:$it") }
        caller.userId?.let { add("b:u:$it") }

        if (isEmpty()) add("anon")
    }
}
