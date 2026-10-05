package com.panomc.plugins.market.db

import com.panomc.plugins.market.db.impl.MarketCreatorCodeDaoImpl
import com.panomc.plugins.market.db.impl.MarketGiftDaoImpl
import com.panomc.plugins.market.db.model.MarketCreatorCode
import com.panomc.plugins.market.db.model.MarketGift
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The scheme version 4 columns of the four promotion tables (01 sections 3.1 - 3.4) next to the unchanged existing
 * DAOs: a row written the old way carries the new defaults, in particular a gift gets `redeemLimit = 1` from the
 * column default while an explicit `NULL` stays `NULL` (unlimited).
 */
class PromotionColumnsDaoIT : MarketDaoITBase() {
    @Test
    fun `a gift written by the existing dao carries redeemLimit 1 and an explicit NULL stays unlimited`(): Unit = runBlocking {
        val gifts = MarketGiftDaoImpl()
        val id = gifts.add(MarketGift(code = "GIFT1", productId = 1), pool)
        val row = sql("SELECT * FROM `pano_market_gift` WHERE `id` = ?", id).single()
        assertEquals(1, row.getInteger("redeemLimit"))
        assertEquals(1, row.getInteger("customerRedeemLimit"))
        assertEquals(0, row.getInteger("usedCount"))
        assertEquals("", row.getString("name"))
        assertNull(row.getValue("deletedAt"))

        sql("INSERT INTO `pano_market_gift` (`code`, `type`, `status`, `createdAt`, `updatedAt`, `redeemLimit`) VALUES ('GIFT2', 'CREDIT', 'ACTIVE', 1, 1, NULL)")
        val unlimited = sql("SELECT `redeemLimit`, `customerRedeemLimit` FROM `pano_market_gift` WHERE `code` = 'GIFT2'").single()
        assertNull(unlimited.getValue("redeemLimit"))
        assertEquals(1, unlimited.getInteger("customerRedeemLimit"))
    }

    @Test
    fun `a creator code written by the existing dao carries the defaults of the four new columns`(): Unit = runBlocking {
        val id = MarketCreatorCodeDaoImpl().add(MarketCreatorCode(creator = "steve", code = "STEVE"), pool)
        val row = sql("SELECT * FROM `pano_market_creator_code` WHERE `id` = ?", id).single()
        assertNull(row.getValue("creatorUserId"))
        assertEquals(0L, row.getLong("paidOut"))
        assertNull(row.getValue("deletedAt"))
        assertEquals(0, row.getInteger("legacyUsedCount"))
    }

    @Test
    fun `discount and coupon defaults of the new columns`(): Unit = runBlocking {
        sql("INSERT INTO `pano_market_discount` (`name`, `value`, `createdAt`, `updatedAt`) VALUES ('d', 10, 1, 1)")
        val discount = sql("SELECT `showBadge`, `deletedAt`, `legacyUsedCount` FROM `pano_market_discount`").single()
        assertEquals(1, (discount.getValue("showBadge") as Number).toInt())
        assertNull(discount.getValue("deletedAt"))
        assertEquals(0, discount.getInteger("legacyUsedCount"))

        sql("INSERT INTO `pano_market_coupon` (`code`, `discount`, `createdAt`, `updatedAt`) VALUES ('C1', 10, 1, 1)")
        val coupon = sql("SELECT `categoryIds`, `deletedAt`, `legacyUsedCount` FROM `pano_market_coupon`").single()
        assertNull(coupon.getValue("categoryIds"))
        assertNull(coupon.getValue("deletedAt"))
        assertEquals(0, coupon.getInteger("legacyUsedCount"))
    }
}
