package com.panomc.plugins.market.core.order

import com.panomc.plugins.market.config.BillingInfoMode
import com.panomc.plugins.market.db.model.BillingMode
import com.panomc.plugins.market.db.model.ProductFieldType
import com.panomc.plugins.market.db.model.ProductKind
import com.panomc.plugins.market.spi.payment.BuyerField
import com.panomc.plugins.market.util.MarketStatus
import com.panomc.plugins.market.util.ProductDurationType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `LineRules` (06 sections 6.3 and 6.4, 09 section 4.1): every line code of 04 section 11 that the rules produce, the
 * aggregation over several lines (stock, limit, cooldown, prerequisites), the clamping the pricing code relies on, and
 * `RequiredBuyerFields` (06 section 8.2).
 */
class LineRulesTest {
    private val now = 1_000_000_000L

    private fun product(
        id: Long = 1,
        kind: ProductKind = ProductKind.STANDARD,
        billing: BillingMode = BillingMode.ONE_TIME,
        status: MarketStatus = MarketStatus.ACTIVE,
        deleted: Boolean = false,
        categoryActive: Boolean = true,
        durationType: ProductDurationType = ProductDurationType.LIFETIME,
        start: Long? = null,
        expiry: Long? = null,
        hasVariants: Boolean = false,
        variants: List<RuleVariant> = emptyList(),
        stock: Int? = null,
        perOrder: Int? = null,
        limit: Int? = null,
        cooldown: Long? = null,
        required: List<Long> = emptyList(),
        onlyOne: Boolean = false,
        permission: String? = null,
        allowGift: Boolean = true,
        tier: RuleTier? = null,
        fields: List<RuleField> = emptyList(),
        choices: List<Long> = emptyList(),
        buyerChoice: Boolean = false,
        children: List<RuleChild> = emptyList()
    ) = RuleProduct(
        id, kind, billing, status, deleted, categoryActive, durationType, start, expiry, hasVariants, variants.associateBy { it.id }, stock, perOrder, limit,
        cooldown, required, onlyOne, permission, allowGift, tier, fields, choices, buyerChoice, children
    )

    private fun line(p: RuleProduct?, quantity: Int = 1, variant: Long = 0, values: Map<String, Any?> = emptyMap(), server: Long? = null, key: String? = null, productId: Long = p?.id ?: 99) =
        RuleLine(key ?: "k${p?.id}-$variant-$quantity-${values.hashCode()}-$server", productId, variant, quantity, values, server, p)

    private fun ctx(
        loggedIn: Boolean = true,
        gift: Boolean = false,
        granted: Set<String> = emptySet(),
        servers: Set<Long> = emptySet(),
        usage: Map<Long, ProductUsage> = emptyMap(),
        owned: List<OwnedEntitlement> = emptyList(),
        subscribed: Set<Long> = emptySet(),
        at: Long = now
    ) = RuleContext(at, loggedIn, gift, { it in granted }, servers, usage, owned, subscribed)

    private fun errors(lines: List<RuleLine>, ctx: RuleContext = ctx()): List<List<String>> = LineRules.evaluate(lines, ctx).lines.map { it.errors }

    private fun one(l: RuleLine, ctx: RuleContext = ctx()): LineVerdict = LineRules.evaluate(listOf(l), ctx).lines.single()

    private fun codes(l: RuleLine, ctx: RuleContext = ctx()): List<String> = one(l, ctx).errors

    // ---------------------------------------------------------------------------------------------------- availability

    @Test
    fun `a sellable product is clean and priceable`() {
        val v = one(line(product()))

        assertTrue(v.errors.isEmpty())
        assertTrue(v.priceable)
        assertEquals(1, v.quantity)
        assertEquals(999, v.maxQuantity)
    }

    @Test
    fun `PRODUCT_UNAVAILABLE for a missing deleted inactive hidden archived or category-hidden product`() {
        val cases = listOf(
            null,
            product(deleted = true),
            product(status = MarketStatus.INACTIVE),
            product(status = MarketStatus.HIDDEN),
            product(status = MarketStatus.ARCHIVED),
            product(categoryActive = false)
        )

        for (p in cases) {
            val v = one(line(p))

            assertEquals(listOf(LineCode.PRODUCT_UNAVAILABLE), v.errors, "$p")
            assertFalse(v.priceable)
            assertEquals(0, v.maxQuantity)
        }
    }

    @Test
    fun `a TEMPORARY window is start inclusive and expiry exclusive`() {
        val p = { start: Long?, expiry: Long? -> product(durationType = ProductDurationType.TEMPORARY, start = start, expiry = expiry) }

        assertTrue(codes(line(p(now, now + 10))).isEmpty(), "at the start")
        assertEquals(listOf(LineCode.PRODUCT_UNAVAILABLE), codes(line(p(now + 1, null))), "before the start")
        assertEquals(listOf(LineCode.PRODUCT_UNAVAILABLE), codes(line(p(null, now))), "at the expiry")
        assertTrue(codes(line(p(null, now + 1))).isEmpty(), "just before the expiry")
        assertTrue(codes(line(p(null, null))).isEmpty(), "an open window")
        // a LIFETIME product ignores stray window columns
        assertTrue(codes(line(product(start = now + 5, expiry = now - 5))).isEmpty())
    }

    @Test
    fun `a bundle is unavailable when a child is missing deleted inactive or its fixed variant is gone`() {
        val ok = RuleChild(product(id = 2), 0, 1)
        val bad = listOf(
            RuleChild(null, 0, 1),
            RuleChild(product(id = 3, deleted = true), 0, 1),
            RuleChild(product(id = 3, status = MarketStatus.INACTIVE), 0, 1),
            RuleChild(product(id = 3, hasVariants = true), 77, 1),
            RuleChild(product(id = 3, hasVariants = true, variants = listOf(RuleVariant(8, 3, active = false, deleted = false, stock = null))), 8, 1),
            RuleChild(product(id = 3, hasVariants = true, variants = listOf(RuleVariant(8, 3, active = true, deleted = true, stock = null))), 8, 1),
            RuleChild(product(id = 3, hasVariants = true, variants = listOf(RuleVariant(8, 4, active = true, deleted = false, stock = null))), 8, 1)
        )

        assertTrue(codes(line(product(id = 10, kind = ProductKind.BUNDLE, children = listOf(ok)))).isEmpty())

        for (child in bad) {
            val v = one(line(product(id = 10, kind = ProductKind.BUNDLE, children = listOf(ok, child))))

            assertEquals(listOf(LineCode.PRODUCT_UNAVAILABLE), v.errors)
            assertFalse(v.priceable)
        }
    }

    // ---------------------------------------------------------------------------------------------------- variants

    @Test
    fun `variant rules`() {
        val live = RuleVariant(5, 1, active = true, deleted = false, stock = null)
        val withVariants = product(hasVariants = true, variants = listOf(live, RuleVariant(6, 1, false, false, null), RuleVariant(7, 1, true, true, null), RuleVariant(8, 2, true, false, null)))

        assertEquals(listOf(LineCode.VARIANT_REQUIRED), codes(line(withVariants)))
        assertTrue(codes(line(withVariants, variant = 5)).isEmpty())
        // the variant table of a product only knows its own variants (8 belongs to product 2 and was never loaded for 1)
        for (id in listOf(6L, 7L, 8L, 99L)) assertEquals(listOf(LineCode.VARIANT_UNAVAILABLE), codes(line(withVariants, variant = id)), "variant $id")
        assertEquals(listOf(LineCode.VARIANT_UNAVAILABLE), codes(line(product(), variant = 5)), "a variant for a product without variants")
        assertFalse(one(line(withVariants)).priceable)
        assertFalse(one(line(withVariants, variant = 99)).priceable)
    }

    @Test
    fun `a variant row of another product is refused even when it sits in the table`() {
        val foreign = RuleVariant(8, 2, true, false, null)

        assertEquals(listOf(LineCode.VARIANT_UNAVAILABLE), codes(line(product(hasVariants = true, variants = listOf(foreign)), variant = 8)))
    }

    // ------------------------------------------------------------------------------------------------------ fields

    private fun field(type: ProductFieldType, required: Boolean = false, options: List<String> = emptyList(), pattern: String? = null, min: Int? = null, max: Int? = null, minV: Long? = null, maxV: Long? = null) =
        RuleField("f", type, required, options, pattern, min, max, minV, maxV)

    private fun fieldCode(f: RuleField, value: Any?): String? = LineRules.fieldVerdict(f, value)

    @Test
    fun `FIELD_REQUIRED for an empty required field of every type and nothing for an empty optional one`() {
        for (type in ProductFieldType.entries) {
            for (empty in listOf(null, "", "   ")) {
                assertEquals(LineCode.FIELD_REQUIRED, fieldCode(field(type, required = true), empty), "$type [$empty]")
                assertNull(fieldCode(field(type), empty), "$type optional [$empty]")
            }
        }

        // an unticked required checkbox is empty, a ticked one is fine
        assertEquals(LineCode.FIELD_REQUIRED, fieldCode(field(ProductFieldType.CHECKBOX, required = true), false))
        assertEquals(LineCode.FIELD_REQUIRED, fieldCode(field(ProductFieldType.CHECKBOX, required = true), "false"))
        assertNull(fieldCode(field(ProductFieldType.CHECKBOX, required = true), true))
        assertNull(fieldCode(field(ProductFieldType.CHECKBOX), false))
    }

    @Test
    fun `NUMBER takes an integer inside its bounds`() {
        val f = field(ProductFieldType.NUMBER, minV = 1, maxV = 10)

        for (ok in listOf<Any>(1, 10L, 5.0, "7", " 3 ")) assertNull(fieldCode(f, ok), "$ok")
        for (bad in listOf<Any>(0, 11, 5.5, "5.5", "abc", true, -3, "1e3", "99999999999999999999")) assertEquals(LineCode.FIELD_INVALID, fieldCode(f, bad), "$bad")
        assertNull(fieldCode(field(ProductFieldType.NUMBER), -5), "no bounds")
    }

    @Test
    fun `SELECT takes only a listed value`() {
        val f = field(ProductFieldType.SELECT, options = listOf("red", "green", "3"))

        assertNull(fieldCode(f, "red"))
        assertNull(fieldCode(f, 3))
        assertEquals(LineCode.FIELD_INVALID, fieldCode(f, "blue"))
        assertEquals(LineCode.FIELD_INVALID, fieldCode(f, "Red"))
    }

    @Test
    fun `CHECKBOX takes a boolean or its text`() {
        val f = field(ProductFieldType.CHECKBOX)

        for (ok in listOf<Any>(true, false, "true", "FALSE")) assertNull(fieldCode(f, ok), "$ok")
        for (bad in listOf<Any>("yes", 1, "maybe")) assertEquals(LineCode.FIELD_INVALID, fieldCode(f, bad), "$bad")
    }

    @Test
    fun `USERNAME EMAIL and DISCORD_ID formats`() {
        val username = field(ProductFieldType.USERNAME)

        for (ok in listOf("Steve", "a_b", "abc", "A".repeat(16))) assertNull(fieldCode(username, ok), ok)
        for (bad in listOf("ab", "A".repeat(17), "has space", "dash-ed", "ünicode")) assertEquals(LineCode.FIELD_INVALID, fieldCode(username, bad), bad)

        val email = field(ProductFieldType.EMAIL)

        for (ok in listOf("a@b.co", "  Mixed.Case@Example.COM ", "x+y@sub.domain.example")) assertNull(fieldCode(email, ok), ok)
        for (bad in listOf("a@b", "a b@c.de", "@x.de", "a@b.c", "a@@b.de")) assertEquals(LineCode.FIELD_INVALID, fieldCode(email, bad), bad)

        val discord = field(ProductFieldType.DISCORD_ID)

        for (ok in listOf("12345678901234567", "123456789012345678", "12345678901234567890", 123456789012345678L)) assertNull(fieldCode(discord, ok), "$ok")
        for (bad in listOf("1234567890123456", "123456789012345678901", "12345678901234567a")) assertEquals(LineCode.FIELD_INVALID, fieldCode(discord, bad), bad)
    }

    @Test
    fun `TEXT length bounds line breaks pattern and the 128 character cap`() {
        val f = field(ProductFieldType.TEXT, min = 3, max = 8)

        assertNull(fieldCode(f, "abc"))
        assertNull(fieldCode(f, "abcdefgh"))
        assertEquals(LineCode.FIELD_INVALID, fieldCode(f, "ab"))
        assertEquals(LineCode.FIELD_INVALID, fieldCode(f, "abcdefghi"))
        assertEquals(LineCode.FIELD_INVALID, fieldCode(f, "abc\ndef"))
        assertEquals(LineCode.FIELD_INVALID, fieldCode(f, "abc\rdef"))
        assertEquals(LineCode.FIELD_INVALID, fieldCode(f, "abc\u0000"))

        // a maxLength above 128 is capped at 128
        val wide = field(ProductFieldType.TEXT, max = 500)

        assertNull(fieldCode(wide, "x".repeat(128)))
        assertEquals(LineCode.FIELD_INVALID, fieldCode(wide, "x".repeat(129)))

        // the pattern is an anchored full match
        val pattern = field(ProductFieldType.TEXT, pattern = "[A-Z]{2}-[0-9]{3}")

        assertNull(fieldCode(pattern, "AB-123"))
        assertEquals(LineCode.FIELD_INVALID, fieldCode(pattern, "AB-1234"))
        assertEquals(LineCode.FIELD_INVALID, fieldCode(pattern, "xAB-123"))

        // counted in code points
        assertNull(fieldCode(field(ProductFieldType.TEXT, max = 2), "😀😀"))
    }

    @Test
    fun `a non scalar value is invalid and unknown keys are dropped silently`() {
        assertEquals(LineCode.FIELD_INVALID, fieldCode(field(ProductFieldType.TEXT), listOf("a")))
        assertEquals(LineCode.FIELD_INVALID, fieldCode(field(ProductFieldType.TEXT), mapOf("a" to 1)))

        val p = product(fields = listOf(RuleField("nick", ProductFieldType.TEXT, true)))
        val v = one(line(p, values = mapOf("nick" to "Steve", "junk" to "x")))

        assertTrue(v.errors.isEmpty())
        assertEquals(mapOf("nick" to "Steve"), v.fieldValues)
        assertEquals(listOf(LineCode.FIELD_REQUIRED), codes(line(p, values = mapOf("junk" to "x"))))
    }

    @Test
    fun `FIELD codes of several fields are all reported once`() {
        val p = product(
            fields = listOf(
                RuleField("a", ProductFieldType.TEXT, true),
                RuleField("b", ProductFieldType.NUMBER, false, minValue = 1),
                RuleField("c", ProductFieldType.TEXT, true)
            )
        )

        assertEquals(listOf(LineCode.FIELD_REQUIRED, LineCode.FIELD_INVALID), codes(line(p, values = mapOf("b" to 0))))
    }

    // ------------------------------------------------------------------------------------------------------ server

    @Test
    fun `server choice rules`() {
        val p = product(buyerChoice = true, choices = listOf(4, 5))
        val servers = setOf(4L)

        assertEquals(listOf(LineCode.SERVER_REQUIRED), codes(line(p), ctx(servers = servers)))
        assertEquals(listOf(LineCode.SERVER_UNAVAILABLE), codes(line(p, server = 9), ctx(servers = servers)), "not a choice")
        assertEquals(listOf(LineCode.SERVER_UNAVAILABLE), codes(line(p, server = 5), ctx(servers = servers)), "a choice that no longer exists")

        val ok = one(line(p, server = 4), ctx(servers = servers))

        assertTrue(ok.errors.isEmpty())
        assertEquals(4L, ok.targetServerId)

        // a product without a buyer choice drops the target
        val plain = one(line(product(), server = 4), ctx(servers = servers))

        assertTrue(plain.errors.isEmpty())
        assertNull(plain.targetServerId)
    }

    // ------------------------------------------------------------------------------------- gift, login, permission

    @Test
    fun `GIFT_NOT_ALLOWED for a product that refuses gifts and for a subscription in a gift cart`() {
        assertEquals(listOf(LineCode.GIFT_NOT_ALLOWED), codes(line(product(allowGift = false)), ctx(gift = true)))
        assertEquals(listOf(LineCode.GIFT_NOT_ALLOWED), codes(line(product(billing = BillingMode.SUBSCRIPTION)), ctx(gift = true)))
        assertTrue(codes(line(product(allowGift = false)), ctx(gift = false)).isEmpty())
        assertTrue(codes(line(product()), ctx(gift = true)).isEmpty())
    }

    @Test
    fun `LOGIN_REQUIRED for a guest buying a credit pack or a subscription`() {
        assertEquals(listOf(LineCode.LOGIN_REQUIRED), codes(line(product(kind = ProductKind.CREDIT_PACK)), ctx(loggedIn = false)))
        assertEquals(listOf(LineCode.LOGIN_REQUIRED), codes(line(product(billing = BillingMode.SUBSCRIPTION)), ctx(loggedIn = false)))
        assertTrue(codes(line(product()), ctx(loggedIn = false)).isEmpty())
        assertTrue(codes(line(product(kind = ProductKind.CREDIT_PACK)), ctx(loggedIn = true)).isEmpty())
    }

    @Test
    fun `PERMISSION_REQUIRED unless the recipient holds the node`() {
        val p = product(permission = "vip.buy")

        assertEquals(listOf(LineCode.PERMISSION_REQUIRED), codes(line(p)))
        assertTrue(codes(line(p), ctx(granted = setOf("vip.buy"))).isEmpty())
        assertTrue(codes(line(product(permission = "  "))).isEmpty(), "a blank node is no requirement")
    }

    // -------------------------------------------------------------------------------------------------- quantity

    @Test
    fun `MAX_QUANTITY above maxQuantityPerOrder and maxQuantity reports the cap`() {
        val p = product(perOrder = 3)

        assertTrue(codes(line(p, quantity = 3)).isEmpty())
        assertEquals(listOf(LineCode.MAX_QUANTITY), codes(line(p, quantity = 4)))
        assertEquals(3, one(line(p, quantity = 4)).maxQuantity)
        assertEquals(999, one(line(product(), quantity = 500)).maxQuantity)
    }

    @Test
    fun `a timed and a tiered product allow one and are priced as one`() {
        for (p in listOf(product(billing = BillingMode.TIMED), product(tier = RuleTier(1, 2)))) {
            val v = one(line(p, quantity = 3))

            assertEquals(listOf(LineCode.MAX_QUANTITY), v.errors)
            assertEquals(1, v.quantity, "the pricing code never sees more than one")
            assertEquals(1, v.maxQuantity)
            assertTrue(codes(line(p, quantity = 1)).isEmpty())
        }
    }

    @Test
    fun `a subscription is reduced to one with QUANTITY_REDUCED and no error`() {
        val result = LineRules.evaluate(listOf(line(product(billing = BillingMode.SUBSCRIPTION), quantity = 4, key = "s")), ctx())
        val v = result.lines.single()

        assertTrue(v.errors.isEmpty())
        assertEquals(1, v.quantity)
        assertEquals(1, v.maxQuantity)
        assertEquals(listOf("QUANTITY_REDUCED"), result.messages.map { it.code })
        assertEquals("warning", result.messages.single().level)
        assertEquals("s", result.messages.single().lineKey)
        assertTrue(result.ok)
    }

    @Test
    fun `a subscription must be alone`() {
        val sub = product(id = 1, billing = BillingMode.SUBSCRIPTION)
        val other = product(id = 2)

        val alone = LineRules.evaluate(listOf(line(sub)), ctx())

        assertTrue(alone.messages.none { it.code == LineCode.SUBSCRIPTION_MUST_BE_ALONE })

        val mixed = LineRules.evaluate(listOf(line(sub), line(other)), ctx())
        val message = mixed.messages.single { it.code == LineCode.SUBSCRIPTION_MUST_BE_ALONE }

        assertEquals("error", message.level)
        assertNull(message.lineKey)
        assertFalse(mixed.ok)

        // two subscriptions are not alone either
        assertTrue(LineRules.evaluate(listOf(line(sub), line(product(id = 3, billing = BillingMode.SUBSCRIPTION))), ctx()).messages.any { it.code == LineCode.SUBSCRIPTION_MUST_BE_ALONE })
    }

    // ------------------------------------------------------------------------------------------------- ownership

    @Test
    fun `ALREADY_OWNED for the same or a higher tier of the category and nothing for a lower one`() {
        val p = product(tier = RuleTier(10, 2))

        assertEquals(listOf(LineCode.ALREADY_OWNED), codes(line(p), ctx(owned = listOf(OwnedEntitlement(5, 10, 2)))))
        assertEquals(listOf(LineCode.ALREADY_OWNED), codes(line(p), ctx(owned = listOf(OwnedEntitlement(5, 10, 3)))))
        assertTrue(codes(line(p), ctx(owned = listOf(OwnedEntitlement(5, 10, 1)))).isEmpty(), "a lower tier is an upgrade")
        assertTrue(codes(line(p), ctx(owned = listOf(OwnedEntitlement(5, 11, 9)))).isEmpty(), "another category")
        assertTrue(codes(line(p), ctx(owned = listOf(OwnedEntitlement(5, null, null)))).isEmpty())
    }

    @Test
    fun `ALREADY_OWNED for a subscription the recipient already has`() {
        val p = product(id = 7, billing = BillingMode.SUBSCRIPTION)

        assertEquals(listOf(LineCode.ALREADY_OWNED), codes(line(p), ctx(subscribed = setOf(7))))
        assertTrue(codes(line(p), ctx(subscribed = setOf(8))).isEmpty())
    }

    // ----------------------------------------------------------------------------------------------------- stock

    @Test
    fun `OUT_OF_STOCK at zero and MAX_QUANTITY with the remainder for a partial shortage`() {
        assertEquals(listOf(LineCode.OUT_OF_STOCK), codes(line(product(stock = 0))))
        assertEquals(listOf(LineCode.OUT_OF_STOCK), codes(line(product(stock = -2))))
        assertEquals(0, one(line(product(stock = 0))).maxQuantity)

        val partial = one(line(product(stock = 3), quantity = 5))

        assertEquals(listOf(LineCode.MAX_QUANTITY), partial.errors)
        assertEquals(3, partial.maxQuantity)
        assertTrue(codes(line(product(stock = 3), quantity = 3)).isEmpty())
        assertTrue(codes(line(product(stock = null), quantity = 900)).isEmpty(), "unlimited")
    }

    @Test
    fun `variant stock is the stock of a variant line`() {
        val p = product(hasVariants = true, stock = 100, variants = listOf(RuleVariant(5, 1, true, false, 2), RuleVariant(6, 1, true, false, null)))

        assertEquals(listOf(LineCode.MAX_QUANTITY), codes(line(p, quantity = 3, variant = 5)))
        assertTrue(codes(line(p, quantity = 2, variant = 5)).isEmpty())
        assertTrue(codes(line(p, quantity = 50, variant = 6)).isEmpty(), "an unlimited variant is not limited by the product stock")
    }

    @Test
    fun `lines of one product share its stock in cart order`() {
        val p = product(stock = 5)
        val result = errors(listOf(line(p, quantity = 3, key = "a"), line(p, quantity = 2, key = "b"), line(p, quantity = 1, key = "c")))

        assertEquals(listOf(emptyList(), emptyList(), listOf(LineCode.OUT_OF_STOCK)), result)

        val split = LineRules.evaluate(listOf(line(p, quantity = 4, key = "a"), line(p, quantity = 4, key = "b")), ctx())

        assertTrue(split.lines[0].errors.isEmpty())
        assertEquals(listOf(LineCode.MAX_QUANTITY), split.lines[1].errors)
        assertEquals(1, split.lines[1].maxQuantity)
    }

    @Test
    fun `bundle children consume the stock of their product and are reported on the bundle line`() {
        val child = product(id = 2, stock = 5)
        val bundle = product(id = 10, kind = ProductKind.BUNDLE, children = listOf(RuleChild(child, 0, 2)))

        assertTrue(codes(line(bundle, quantity = 2)).isEmpty(), "2 x 2 = 4 of 5")

        val over = one(line(bundle, quantity = 3))

        assertEquals(listOf(LineCode.MAX_QUANTITY), over.errors)
        assertEquals(2, over.maxQuantity, "floor(5 / 2)")
        assertEquals(2L, over.details.single().productId, "the code belongs to the child")

        // the child bought on its own competes for the same stock
        val both = errors(listOf(line(bundle, quantity = 2, key = "b"), line(child, quantity = 2, key = "c")))

        assertEquals(listOf(emptyList(), listOf(LineCode.MAX_QUANTITY)), both)

        val gone = product(id = 3, stock = 1)

        assertEquals(listOf(LineCode.OUT_OF_STOCK), codes(line(product(id = 11, kind = ProductKind.BUNDLE, children = listOf(RuleChild(gone, 0, 2))))))
    }

    @Test
    fun `a blocked line takes no stock`() {
        val p = product(stock = 1)
        val broken = RuleLine("bad", 1, 9, 1, emptyMap(), null, product(hasVariants = true, stock = 1))

        assertEquals(listOf(listOf(LineCode.VARIANT_UNAVAILABLE), emptyList()), errors(listOf(broken, line(p, key = "ok"))))
    }

    // ---------------------------------------------------------------------------------------------------- limits

    @Test
    fun `PURCHASE_LIMIT_REACHED when used plus requested passes the limit`() {
        val p = product(limit = 3)
        val usage = { used: Long -> mapOf(1L to ProductUsage(used, null)) }

        assertTrue(codes(line(p, quantity = 3), ctx(usage = usage(0))).isEmpty())
        assertTrue(codes(line(p, quantity = 1), ctx(usage = usage(2))).isEmpty())
        assertEquals(listOf(LineCode.PURCHASE_LIMIT_REACHED), codes(line(p, quantity = 2), ctx(usage = usage(2))))
        assertEquals(listOf(LineCode.PURCHASE_LIMIT_REACHED), codes(line(p, quantity = 1), ctx(usage = usage(3))))

        val v = one(line(p, quantity = 2), ctx(usage = usage(2)))

        assertEquals(1, v.maxQuantity, "the allowance left")
        assertEquals(3, v.details.single().limit)
        assertEquals(0, one(line(p, quantity = 1), ctx(usage = usage(5))).maxQuantity)
        assertTrue(codes(line(product(limit = null), quantity = 50), ctx(usage = usage(9999))).isEmpty())
    }

    @Test
    fun `the limit counts every line and bundle child of a product`() {
        val p = product(id = 1, limit = 3)
        val child = product(id = 1, limit = 3)
        val bundle = product(id = 10, kind = ProductKind.BUNDLE, children = listOf(RuleChild(child, 0, 2)))

        assertEquals(
            listOf(listOf(LineCode.PURCHASE_LIMIT_REACHED), listOf(LineCode.PURCHASE_LIMIT_REACHED)),
            errors(listOf(line(p, quantity = 2, key = "a"), line(p, quantity = 2, key = "b")))
        )

        // 1 bundle = 2 of the child, plus 2 direct: 4 > 3 on both lines
        assertEquals(
            listOf(listOf(LineCode.PURCHASE_LIMIT_REACHED), listOf(LineCode.PURCHASE_LIMIT_REACHED)),
            errors(listOf(line(bundle, quantity = 1, key = "a"), line(p, quantity = 2, key = "b")))
        )
    }

    @Test
    fun `a timed product with a running chain is an extension and not a second unit`() {
        val p = product(billing = BillingMode.TIMED, limit = 1)
        val used = mapOf(1L to ProductUsage(1, null))

        assertEquals(listOf(LineCode.PURCHASE_LIMIT_REACHED), codes(line(p), ctx(usage = used)), "no chain: the order counts")
        assertTrue(codes(line(p), ctx(usage = used, owned = listOf(OwnedEntitlement(1, null, null)))).isEmpty())
    }

    // -------------------------------------------------------------------------------------------------- cooldown

    @Test
    fun `COOLDOWN_ACTIVE with retryAfter rounded up and the boundary second is free`() {
        val p = product(cooldown = 60)
        val last = { ago: Long -> mapOf(1L to ProductUsage(1, now - ago)) }

        val v = one(line(p), ctx(usage = last(10_000)))

        assertEquals(listOf(LineCode.COOLDOWN_ACTIVE), v.errors)
        assertEquals(50L, v.details.single().retryAfterSeconds)
        assertEquals(0, v.maxQuantity)
        assertEquals(51L, one(line(p), ctx(usage = last(9_999))).details.single().retryAfterSeconds, "50.001 s is 51")
        assertEquals(1L, one(line(p), ctx(usage = last(59_001))).details.single().retryAfterSeconds)
        assertTrue(codes(line(p), ctx(usage = last(60_000))).isEmpty(), "exactly cooldown seconds ago")
        assertTrue(codes(line(p), ctx(usage = emptyMap())).isEmpty(), "never bought")
        assertTrue(codes(line(product(cooldown = 0)), ctx(usage = last(1))).isEmpty())
        assertTrue(codes(line(product(cooldown = null)), ctx(usage = last(1))).isEmpty())
    }

    @Test
    fun `the cooldown of a bundle child is reported on the bundle`() {
        val child = product(id = 2, cooldown = 100)
        val bundle = product(id = 10, kind = ProductKind.BUNDLE, children = listOf(RuleChild(child, 0, 1)))
        val v = one(line(bundle), ctx(usage = mapOf(2L to ProductUsage(1, now - 1000))))

        assertEquals(listOf(LineCode.COOLDOWN_ACTIVE), v.errors)
        assertEquals(2L, v.details.single().productId)
        assertEquals(99L, v.details.single().retryAfterSeconds)
    }

    @Test
    fun `retryAfter rounds up`() {
        assertEquals(1L, LineRules.retryAfter(now + 1, now))
        assertEquals(1L, LineRules.retryAfter(now + 1000, now))
        assertEquals(2L, LineRules.retryAfter(now + 1001, now))
    }

    // ---------------------------------------------------------------------------------------- required products

    @Test
    fun `REQUIREMENT_NOT_MET needs every required product unless requireOnlyOne`() {
        val all = product(id = 1, required = listOf(2, 3))
        val any = product(id = 1, required = listOf(2, 3), onlyOne = true)
        fun held(vararg ids: Long) = ctx(owned = ids.map { OwnedEntitlement(it, null, null) })

        assertEquals(listOf(LineCode.REQUIREMENT_NOT_MET), codes(line(all)))
        assertEquals(listOf(LineCode.REQUIREMENT_NOT_MET), codes(line(all), held(2)))
        assertTrue(codes(line(all), held(2, 3)).isEmpty())
        assertEquals(listOf(LineCode.REQUIREMENT_NOT_MET), codes(line(any)))
        assertTrue(codes(line(any), held(3)).isEmpty())
        assertTrue(codes(line(product(required = emptyList()))).isEmpty(), "a product whose prerequisites were all deleted has none")
    }

    @Test
    fun `a prerequisite in the same cart satisfies the requirement`() {
        val base = product(id = 2)
        val dependent = product(id = 1, required = listOf(2))

        assertEquals(listOf<List<String>>(emptyList(), emptyList()), errors(listOf(line(dependent, key = "d"), line(base, key = "b"))))
        // a prerequisite that cannot be bought does not count
        assertEquals(listOf(listOf(LineCode.REQUIREMENT_NOT_MET), listOf(LineCode.PRODUCT_UNAVAILABLE)), errors(listOf(line(dependent, key = "d"), line(product(id = 2, deleted = true), key = "b"))))
        // a product is never its own prerequisite
        assertTrue(codes(line(product(id = 1, required = listOf(1)))).isEmpty())
    }

    // ------------------------------------------------------------------------------------------- combined / shape

    @Test
    fun `every failing code of a line is reported and the verdict order follows the cart`() {
        val p = product(
            perOrder = 2, stock = 0, permission = "x", allowGift = false, fields = listOf(RuleField("f", ProductFieldType.TEXT, true)), limit = 1
        )
        val v = one(line(p, quantity = 5), ctx(gift = true, usage = mapOf(1L to ProductUsage(1, null))))

        assertEquals(
            listOf(
                LineCode.FIELD_REQUIRED, LineCode.GIFT_NOT_ALLOWED, LineCode.MAX_QUANTITY, LineCode.PERMISSION_REQUIRED, LineCode.OUT_OF_STOCK,
                LineCode.PURCHASE_LIMIT_REACHED
            ),
            v.errors
        )

        val a = line(product(id = 1), key = "first")
        val b = line(product(id = 2, stock = 0), key = "second")

        assertEquals(listOf("first", "second"), LineRules.evaluate(listOf(a, b), ctx()).lines.map { it.lineKey })
    }

    @Test
    fun `an empty cart is ok and has no verdicts`() {
        val r = LineRules.evaluate(emptyList(), ctx())

        assertTrue(r.lines.isEmpty())
        assertTrue(r.messages.isEmpty())
        assertTrue(r.ok)
    }

    // ----------------------------------------------------------------------------------- RequiredBuyerFields

    @Test
    fun `required buyer fields come from the billing mode and the provider`() {
        val none = RequiredBuyerFields.of(BillingInfoMode.OPTIONAL, emptySet(), false, null, false)

        assertTrue(none.isEmpty())
        assertTrue(RequiredBuyerFields.of(BillingInfoMode.OFF, emptySet(), true, "TR", true).isEmpty())

        val required = RequiredBuyerFields.of(BillingInfoMode.REQUIRED, emptySet(), false, null, false)

        assertEquals(
            listOf("billingInfo.type", "billingInfo.firstName", "billingInfo.lastName", "billingInfo.country", "billingInfo.city", "billingInfo.line1"),
            required
        )
        assertEquals(required + listOf("billingInfo.company", "billingInfo.taxNumber"), RequiredBuyerFields.of(BillingInfoMode.REQUIRED, emptySet(), true, null, false))

        val provider = RequiredBuyerFields.of(
            BillingInfoMode.OPTIONAL,
            setOf(BuyerField.PHONE, BuyerField.EMAIL, BuyerField.IDENTITY_NUMBER, BuyerField.FIRST_NAME, BuyerField.LAST_NAME, BuyerField.COUNTRY),
            false, null, false
        )

        assertEquals(
            listOf("email", "billingInfo.firstName", "billingInfo.lastName", "billingInfo.phone", "billingInfo.country", "billingInfo.identityNumber"),
            provider
        )

        // the postal code is waived for TR; SHIPPING_ADDRESS is the order's own address when something ships
        assertEquals(listOf("billingInfo.country", "billingInfo.city", "billingInfo.line1", "billingInfo.postalCode"), RequiredBuyerFields.of(BillingInfoMode.OFF, setOf(BuyerField.BILLING_ADDRESS), false, "DE", false))
        assertEquals(listOf("billingInfo.country", "billingInfo.city", "billingInfo.line1"), RequiredBuyerFields.of(BillingInfoMode.OFF, setOf(BuyerField.BILLING_ADDRESS), false, "tr", false))
        assertEquals(listOf("shippingAddress"), RequiredBuyerFields.of(BillingInfoMode.OFF, setOf(BuyerField.SHIPPING_ADDRESS), false, null, true))
        assertEquals(
            listOf("billingInfo.country", "billingInfo.city", "billingInfo.line1", "billingInfo.postalCode"),
            RequiredBuyerFields.of(BillingInfoMode.OFF, setOf(BuyerField.SHIPPING_ADDRESS), false, null, false)
        )

        // a path required twice is listed once
        assertEquals(1, RequiredBuyerFields.of(BillingInfoMode.REQUIRED, setOf(BuyerField.COUNTRY, BuyerField.FIRST_NAME), false, null, false).count { it == "billingInfo.country" })
    }
}
