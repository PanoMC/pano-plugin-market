package com.panomc.plugins.market.core.credit

import com.panomc.plugins.market.config.CurrencyMode
import com.panomc.plugins.market.config.MultiCurrencyFallback
import com.panomc.plugins.market.core.credit.CreditEligibility.CartClass
import com.panomc.plugins.market.core.credit.CreditEligibility.FullCreditFacts
import com.panomc.plugins.market.core.credit.CreditEligibility.LineFacts
import com.panomc.plugins.market.core.credit.CreditEligibility.MixedFacts
import com.panomc.plugins.market.core.credit.CreditEligibility.Mode
import com.panomc.plugins.market.core.credit.CreditEligibility.OnlyCredits
import com.panomc.plugins.market.core.credit.CreditEligibility.Rule
import com.panomc.plugins.market.core.credit.CreditEligibility.Verdict
import com.panomc.plugins.market.core.pricing.LineKind
import com.panomc.plugins.market.core.pricing.MixedPayment
import com.panomc.plugins.market.core.pricing.PricingCode
import com.panomc.plugins.market.core.pricing.PricingConfig
import com.panomc.plugins.market.core.pricing.PricingFixtures
import com.panomc.plugins.market.core.pricing.PricingFixtures.P1
import com.panomc.plugins.market.core.pricing.PricingFixtures.P3
import com.panomc.plugins.market.core.pricing.PricingFixtures.P6
import com.panomc.plugins.market.core.pricing.PricingFixtures.P9
import com.panomc.plugins.market.core.pricing.PricingFixtures.buyer
import com.panomc.plugins.market.core.pricing.PricingFixtures.line
import com.panomc.plugins.market.core.pricing.PricingFixtures.method
import com.panomc.plugins.market.core.pricing.PricingFixtures.topUp
import com.panomc.plugins.market.core.pricing.PricingMode
import com.panomc.plugins.market.core.pricing.PricingProfile
import com.panomc.plugins.market.db.model.OrderSource
import com.panomc.plugins.market.spi.payment.PriceAuthority
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 07 section 19.3 (U-E1 .. U-E16): one case per rule F1 to F7 and M1 to M6, the cart classification of section 13, and the
 * request modes of section 6.3; the last two tests run the engine over a grid of carts and require that eligibility and the
 * engine's tender give the same answer.
 */
class CreditEligibilityTest {
    private val vip = LineFacts("L1", LineKind.PRODUCT, subscription = false, creditUnitPrice = 10_000, listUnitPrice = 10_000)

    private fun full(
        creditsEnabled: Boolean = true,
        loggedIn: Boolean = true,
        mode: PricingMode = PricingMode.MARKET,
        lines: List<LineFacts> = listOf(vip),
        moneyTotal: Long = 10_000,
        creditTotal: Long = 10_000
    ) = CreditEligibility.fullCredit(FullCreditFacts(creditsEnabled, loggedIn, mode, lines, moneyTotal, creditTotal))

    private fun mixed(
        creditsEnabled: Boolean = true,
        allowMixed: Boolean = true,
        onlyAccept: Boolean = false,
        loggedIn: Boolean = true,
        mode: PricingMode = PricingMode.MARKET,
        granting: Boolean = false,
        subscription: Boolean = false,
        source: OrderSource = OrderSource.STOREFRONT,
        methodMixed: Boolean? = true,
        balance: Long = 5_000
    ) = CreditEligibility.mixed(
        MixedFacts(creditsEnabled, allowMixed, onlyAccept, loggedIn, mode, granting, subscription, source, methodMixed, balance)
    )

    // ---------------------------------------------------------------- full credit: F1 .. F7

    @Test
    fun `an ordinary cart is payable in credits`() {
        assertEquals(Verdict.OK, full())
        assertTrue(full().ok)
    }

    @Test
    fun `U-E1 F1 credits switched off`() {
        assertEquals(Verdict(Rule.F1, PricingCode.CREDITS_DISABLED), full(creditsEnabled = false))
    }

    @Test
    fun `U-E2 F2 a guest cannot pay with credits`() {
        assertEquals(Verdict(Rule.F2, PricingCode.LOGIN_REQUIRED), full(loggedIn = false))
    }

    @Test
    fun `U-E3 F3 an order whose price the gateway sets is not payable with credits`() {
        assertEquals(Verdict(Rule.F3, PricingCode.EXTERNAL_PRICING), full(mode = PricingMode.EXTERNAL_TAX))
        assertEquals(Verdict(Rule.F3, PricingCode.EXTERNAL_PRICING), full(mode = PricingMode.EXTERNAL))
    }

    @Test
    fun `U-E4 F4 a credit pack or a top-up line cannot be bought with credits`() {
        val pack = LineFacts("L6", LineKind.CREDIT_PACK, false, creditUnitPrice = 0, listUnitPrice = 10_000)
        val topUp = LineFacts("topup", LineKind.CREDIT_TOPUP, false, creditUnitPrice = 0, listUnitPrice = 5_000)
        assertEquals(Verdict(Rule.F4, PricingCode.NOT_PAYABLE_WITH_CREDITS, listOf("L6")), full(lines = listOf(vip, pack)))
        assertEquals(Verdict(Rule.F4, PricingCode.NOT_PAYABLE_WITH_CREDITS, listOf("topup")), full(lines = listOf(topUp)))
        assertEquals(listOf("L6", "topup"), full(lines = listOf(pack, vip, topUp)).lineKeys)
        // even a credit price on the line does not help
        val paidPack = LineFacts("L6", LineKind.CREDIT_PACK, false, creditUnitPrice = 5_000, listUnitPrice = 10_000)
        assertEquals(Rule.F4, full(lines = listOf(paidPack)).failed)
    }

    @Test
    fun `U-E5 F5 a subscription with a credit price is payable in full because it is then manual`() {
        val sub = LineFacts("L9", LineKind.PRODUCT, subscription = true, creditUnitPrice = 3_000, listUnitPrice = 3_000)
        assertEquals(Verdict.OK, full(lines = listOf(sub), moneyTotal = 3_000, creditTotal = 3_000))
        // without a credit price it fails the credit price rule, not a subscription rule
        val noPrice = LineFacts("L9", LineKind.PRODUCT, subscription = true, creditUnitPrice = 0, listUnitPrice = 3_000)
        assertEquals(Verdict(Rule.F6, PricingCode.NOT_PAYABLE_WITH_CREDITS, listOf("L9")), full(lines = listOf(noPrice)))
    }

    @Test
    fun `U-E6 F6 every product or bundle line needs a credit price unless it is free anyway`() {
        val sword = LineFacts("L3", LineKind.PRODUCT, false, creditUnitPrice = 0, listUnitPrice = 4_990)
        val bundle = LineFacts("L5", LineKind.BUNDLE, false, creditUnitPrice = 0, listUnitPrice = 12_000)
        assertEquals(Verdict(Rule.F6, PricingCode.NOT_PAYABLE_WITH_CREDITS, listOf("L3")), full(lines = listOf(vip, sword)))
        assertEquals(listOf("L3", "L5"), full(lines = listOf(sword, vip, bundle)).lineKeys)
        // a variant "not sold for credits" arrives as the effective price 0
        assertEquals(Rule.F6, full(lines = listOf(LineFacts("L1v2", LineKind.PRODUCT, false, CreditPricing.effectiveCreditPrice(10_000, 0), 10_000))).failed)
        // free in money costs 0 credits: allowed (05 section 8.1)
        val free = LineFacts("L8", LineKind.PRODUCT, false, creditUnitPrice = 0, listUnitPrice = 0)
        assertEquals(Verdict.OK, full(lines = listOf(vip, free)))
        // a product priced in credits only is allowed too
        val perk = LineFacts("L7", LineKind.PRODUCT, false, creditUnitPrice = 4_000, listUnitPrice = 0)
        assertEquals(Verdict.OK, full(lines = listOf(perk), moneyTotal = 0, creditTotal = 4_000))
    }

    @Test
    fun `U-E7 F7 an order that is free in money and in credits goes to the free provider`() {
        val free = LineFacts("L8", LineKind.PRODUCT, false, creditUnitPrice = 0, listUnitPrice = 0)
        val v = full(lines = listOf(free), moneyTotal = 0, creditTotal = 0)
        assertEquals(Verdict(Rule.F7, null), v)
        assertFalse(v.ok)
        // a zero money total with a credit price is NOT free: it must never be given away (05 section 8.1, row 53)
        assertEquals(Verdict.OK, full(moneyTotal = 0, creditTotal = 4_000))
        // a money total with no credit total is a payable order as far as the rules go
        assertEquals(Verdict.OK, full(moneyTotal = 10_000, creditTotal = 0))
    }

    @Test
    fun `the first failing rule gives the reason`() {
        val pack = LineFacts("L6", LineKind.CREDIT_PACK, false, 0, 10_000)
        val sword = LineFacts("L3", LineKind.PRODUCT, false, 0, 4_990)
        // everything wrong at once: F1
        assertEquals(Rule.F1, full(creditsEnabled = false, loggedIn = false, mode = PricingMode.EXTERNAL, lines = listOf(pack, sword)).failed)
        assertEquals(Rule.F2, full(loggedIn = false, mode = PricingMode.EXTERNAL, lines = listOf(pack, sword)).failed)
        assertEquals(Rule.F3, full(mode = PricingMode.EXTERNAL, lines = listOf(pack, sword)).failed)
        assertEquals(Rule.F4, full(lines = listOf(pack, sword)).failed)
        assertEquals(Rule.F6, full(lines = listOf(sword)).failed)
    }

    @Test
    fun `the credits option reports why it is unavailable`() {
        assertNull(CreditEligibility.optionReason(Verdict.OK, balance = 10_000, creditTotal = 10_000))
        assertEquals(PricingCode.INSUFFICIENT_CREDITS, CreditEligibility.optionReason(Verdict.OK, balance = 9_999, creditTotal = 10_000))
        assertEquals(PricingCode.LOGIN_REQUIRED, CreditEligibility.optionReason(full(loggedIn = false), 99_999, 10_000))
        assertEquals(PricingCode.NOT_PAYABLE_WITH_CREDITS, CreditEligibility.optionReason(full(lines = listOf(LineFacts("L3", LineKind.PRODUCT, false, 0, 4_990))), 99_999, 10_000))
        assertNull(CreditEligibility.optionReason(Verdict(Rule.F7, null), 0, 0), "a free order has no credit reason")
        // a debt covers nothing
        assertEquals(PricingCode.INSUFFICIENT_CREDITS, CreditEligibility.optionReason(Verdict.OK, balance = -5, creditTotal = 1))
    }

    // ---------------------------------------------------------------- mixed: M1 .. M6

    @Test
    fun `an ordinary storefront cart with a balance may pay partly with credits`() {
        assertEquals(Verdict.OK, mixed())
        assertEquals(Verdict.OK, mixed(methodMixed = null), "no method selected yet: assumed capable")
    }

    @Test
    fun `U-E8 M1 the store settings`() {
        val notSupported = PricingCode.MIXED_CREDIT_NOT_SUPPORTED
        assertEquals(Verdict(Rule.M1, notSupported), mixed(creditsEnabled = false))
        assertEquals(Verdict(Rule.M1, notSupported), mixed(allowMixed = false))
        assertEquals(Verdict(Rule.M1, notSupported), mixed(onlyAccept = true))
    }

    @Test
    fun `U-E9 M2 a guest`() {
        assertEquals(Verdict(Rule.M2, PricingCode.MIXED_CREDIT_NOT_SUPPORTED), mixed(loggedIn = false))
    }

    @Test
    fun `U-E10 M3 gateway pricing`() {
        assertEquals(Verdict(Rule.M3, PricingCode.MIXED_CREDIT_NOT_SUPPORTED), mixed(mode = PricingMode.EXTERNAL_TAX))
        assertEquals(Verdict(Rule.M3, PricingCode.MIXED_CREDIT_NOT_SUPPORTED), mixed(mode = PricingMode.EXTERNAL))
    }

    @Test
    fun `U-E11 M4 a credit purchase a subscription or any source but the storefront`() {
        val notSupported = PricingCode.MIXED_CREDIT_NOT_SUPPORTED
        assertEquals(Verdict(Rule.M4, notSupported), mixed(granting = true))
        assertEquals(Verdict(Rule.M4, notSupported), mixed(subscription = true))
        for (source in listOf(OrderSource.PANEL, OrderSource.GIFT_CODE, OrderSource.RENEWAL, OrderSource.EXTERNAL, OrderSource.INGAME, OrderSource.LEGACY)) {
            assertEquals(Verdict(Rule.M4, notSupported), mixed(source = source), "$source")
        }
    }

    @Test
    fun `U-E12 M5 the method must take a credit part and the failure marks the method only`() {
        assertEquals(Verdict(Rule.M5, PricingCode.MIXED_CREDIT_NOT_SUPPORTED), mixed(methodMixed = false))
    }

    @Test
    fun `U-E13 M6 nothing to spend has no reason`() {
        assertEquals(Verdict(Rule.M6, null), mixed(balance = 0))
        assertEquals(Verdict(Rule.M6, null), mixed(balance = -300), "a debt has nothing to spend")
        assertEquals(Verdict.OK, mixed(balance = 1))
    }

    @Test
    fun `U-E14 onlyAcceptCredits with the mixed setting on is never mixed`() {
        assertEquals(Rule.M1, mixed(allowMixed = true, onlyAccept = true).failed)
        assertEquals(Rule.M1, mixed(allowMixed = true, onlyAccept = true, granting = true).failed, "not even for a credit purchase")
    }

    @Test
    fun `the first failing mixed rule gives the verdict`() {
        assertEquals(Rule.M1, mixed(creditsEnabled = false, loggedIn = false, mode = PricingMode.EXTERNAL, granting = true, methodMixed = false, balance = 0).failed)
        assertEquals(Rule.M2, mixed(loggedIn = false, mode = PricingMode.EXTERNAL, granting = true, methodMixed = false, balance = 0).failed)
        assertEquals(Rule.M3, mixed(mode = PricingMode.EXTERNAL, granting = true, methodMixed = false, balance = 0).failed)
        assertEquals(Rule.M4, mixed(granting = true, methodMixed = false, balance = 0).failed)
        assertEquals(Rule.M5, mixed(methodMixed = false, balance = 0).failed)
        assertEquals(Rule.M6, mixed(balance = 0).failed)
    }

    // ---------------------------------------------------------------- cart classes and onlyAcceptCredits (section 13)

    @Test
    fun `U-E15 a cart is credit-granting product combined or empty`() {
        assertEquals(CartClass.EMPTY, CreditEligibility.classify(emptyList()))
        assertEquals(CartClass.PRODUCT, CreditEligibility.classify(listOf(LineKind.PRODUCT, LineKind.BUNDLE)))
        assertEquals(CartClass.CREDIT_GRANTING, CreditEligibility.classify(listOf(LineKind.CREDIT_PACK)))
        assertEquals(CartClass.CREDIT_GRANTING, CreditEligibility.classify(listOf(LineKind.CREDIT_PACK, LineKind.CREDIT_PACK)))
        assertEquals(CartClass.CREDIT_GRANTING, CreditEligibility.classify(listOf(LineKind.CREDIT_TOPUP)), "a top-up line alone is credit-granting")
        assertEquals(CartClass.COMBINED, CreditEligibility.classify(listOf(LineKind.PRODUCT, LineKind.CREDIT_PACK)))
        assertEquals(CartClass.COMBINED, CreditEligibility.classify(listOf(LineKind.CREDIT_TOPUP, LineKind.BUNDLE)))
        assertEquals(CartClass.COMBINED, CreditEligibility.classify(listOf(LineKind.CREDIT_PACK, LineKind.CREDIT_TOPUP, LineKind.PRODUCT)))
    }

    @Test
    fun `onlyAcceptCredits decides by cart class`() {
        assertEquals(OnlyCredits.UNAFFECTED, CreditEligibility.onlyAcceptCredits(CartClass.CREDIT_GRANTING, 5_000, 0))
        assertEquals(OnlyCredits.UNAFFECTED, CreditEligibility.onlyAcceptCredits(CartClass.EMPTY, 0, 0))
        assertEquals(OnlyCredits.CREDITS_ONLY, CreditEligibility.onlyAcceptCredits(CartClass.PRODUCT, 10_000, 10_000))
        assertEquals(OnlyCredits.FREE, CreditEligibility.onlyAcceptCredits(CartClass.PRODUCT, 0, 0))
        // a product priced in credits only is not free: the money total is 0 but it costs credits
        assertEquals(OnlyCredits.CREDITS_ONLY, CreditEligibility.onlyAcceptCredits(CartClass.PRODUCT, 0, 4_000))
        assertEquals(OnlyCredits.SEPARATE_ORDER, CreditEligibility.onlyAcceptCredits(CartClass.COMBINED, 15_000, 10_000))
    }

    @Test
    fun `checkout of a credits-only cart needs the credits method`() {
        assertEquals(PricingCode.CREDITS_REQUIRED, CreditEligibility.onlyCreditsCheckoutReason(OnlyCredits.CREDITS_ONLY, Mode.NONE))
        assertEquals(PricingCode.CREDITS_REQUIRED, CreditEligibility.onlyCreditsCheckoutReason(OnlyCredits.CREDITS_ONLY, Mode.MIXED))
        assertNull(CreditEligibility.onlyCreditsCheckoutReason(OnlyCredits.CREDITS_ONLY, Mode.FULL_CREDIT))
        for (other in listOf(OnlyCredits.UNAFFECTED, OnlyCredits.FREE, OnlyCredits.SEPARATE_ORDER)) {
            assertNull(CreditEligibility.onlyCreditsCheckoutReason(other, Mode.NONE), "$other")
        }
    }

    // ---------------------------------------------------------------- which mode applies (section 6.3)

    @Test
    fun `U-E16 payWithCredits with another payment method is an invalid request`() {
        assertEquals(Mode.INVALID, CreditEligibility.requestMode(true, "stripe", null))
        assertEquals(Mode.INVALID, CreditEligibility.requestMode(true, "stripe", 5_000L), "useCredits does not rescue it")
        assertEquals(Mode.INVALID, CreditEligibility.requestMode(true, "free", null))
        assertEquals(Mode.INVALID, CreditEligibility.requestMode(true, "", null))
    }

    @Test
    fun `payWithCredits and method credits are the same request and useCredits is ignored in that mode`() {
        assertEquals(Mode.FULL_CREDIT, CreditEligibility.requestMode(true, null, null))
        assertEquals(Mode.FULL_CREDIT, CreditEligibility.requestMode(true, "credits", null))
        assertEquals(Mode.FULL_CREDIT, CreditEligibility.requestMode(false, "credits", null))
        assertEquals(Mode.FULL_CREDIT, CreditEligibility.requestMode(false, "credits", 5_000L))
        assertEquals(Mode.FULL_CREDIT, CreditEligibility.requestMode(true, null, 5_000L))
    }

    @Test
    fun `useCredits above zero means mixed and absent or zero means no credits`() {
        assertEquals(Mode.MIXED, CreditEligibility.requestMode(false, null, 1L))
        assertEquals(Mode.MIXED, CreditEligibility.requestMode(false, "stripe", 5_000L))
        assertEquals(Mode.MIXED, CreditEligibility.requestMode(false, null, MixedPayment.MAX))
        assertEquals(Mode.NONE, CreditEligibility.requestMode(false, null, null))
        assertEquals(Mode.NONE, CreditEligibility.requestMode(false, null, 0L))
        assertEquals(Mode.NONE, CreditEligibility.requestMode(false, "stripe", null))
    }

    // ---------------------------------------------------------------- the engine agrees (a grid of carts)

    private fun cfg(creditsEnabled: Boolean, mixed: Boolean, onlyCredits: Boolean) = PricingConfig(
        baseCurrency = "TRY", currencyMode = CurrencyMode.SINGLE, additionalCurrencies = emptyList(),
        multiCurrencyFallback = MultiCurrencyFallback.CONVERT, rates = emptyMap(), vatBp = 2000, pricesIncludeVat = true,
        removeCents = false, minimumOrderAmount = 0, combineDiscountsAndCoupons = true, creditsEnabled = creditsEnabled,
        onlyAcceptCredits = onlyCredits, creditValue = 100, allowMixedCreditPayment = mixed, cashbackBp = 0
    )

    private class Cart(val name: String, val products: List<PricingFixtures.Product>, val topUp: Boolean = false)

    private val free = PricingFixtures.Product(41, "Free", price = 0, creditPrice = 0)
    private val perk = PricingFixtures.Product(42, "Perk", price = 0, creditPrice = 4_000)

    private val allCarts = listOf(
        Cart("vip", listOf(P1)), Cart("sword", listOf(P3)), Cart("pack", listOf(P6)), Cart("topup", emptyList(), topUp = true),
        Cart("sub", listOf(P9)), Cart("free", listOf(free)), Cart("perk", listOf(perk)), Cart("vip+pack", listOf(P1, P6)),
        Cart("vip+sword", listOf(P1, P3))
    )

    private fun linesOf(cart: Cart) = cart.products.map { line(it) } + (if (cart.topUp) listOf(topUp(5_000)) else emptyList())

    private fun factsOf(cart: Cart): List<LineFacts> = cart.products.map {
        LineFacts("L${it.id}", it.kind, it.subscription, it.creditPrice, it.price)
    } + (if (cart.topUp) listOf(LineFacts("topup", LineKind.CREDIT_TOPUP, false, 0, 5_000)) else emptyList())

    @Test
    fun `full-credit eligibility and the engine's tender agree over a grid of carts`() {
        var runs = 0
        var payable = 0
        val reasons = java.util.TreeMap<String, Int>()
        for (enabled in listOf(true, false)) for (loggedIn in listOf(true, false)) for (mode in PricingMode.values()) for (cart in allCarts) {
            val buyer = buyer(balance = 1_000_000, loggedIn = loggedIn, userId = if (loggedIn) 1 else null, recipientUserId = if (loggedIn) 1 else null)
            val bd = PricingFixtures.full(*linesOf(cart).toTypedArray(), config = cfg(enabled, mixed = true, onlyCredits = false), payWithCredits = true, buyer = buyer, mode = mode)
            val engineCredits = bd.credits
            val verdict = CreditEligibility.fullCredit(
                FullCreditFacts(enabled, loggedIn, mode, factsOf(cart), bd.items.itemsTotal, engineCredits?.creditTotal ?: 0L)
            )
            val where = "enabled=$enabled loggedIn=$loggedIn mode=$mode cart=${cart.name} verdict=$verdict"
            val enginePayable = engineCredits?.payableInCredits == true
            // rules F1 to F6 are the engine's payableInCredits; F7 is the free order, which the engine also treats as payable
            assertEquals(verdict.failed == null || verdict.failed == Rule.F7, enginePayable, where)
            if (!enginePayable) {
                assertEquals(verdict.reason, bd.tender.unavailable, "the reason of the refusal: $where")
                reasons.merge(verdict.reason.toString(), 1) { a, b -> a + b }
            } else {
                assertNull(bd.tender.unavailable, where)
                payable++
            }
            runs++
        }
        assertEquals(2 * 2 * 3 * allCarts.size, runs)
        // the carts that can be paid in credits (vip, subscription with a credit price, free, credits-only) in the one setting where
        // credits are on, the buyer is in and the gateway does not set the price
        assertEquals(4, payable, "payable carts of the grid")
        for (code in listOf("CREDITS_DISABLED", "LOGIN_REQUIRED", "EXTERNAL_PRICING", "NOT_PAYABLE_WITH_CREDITS")) {
            assertTrue((reasons[code] ?: 0) >= 5, "the grid must refuse with $code: $reasons")
        }
    }

    @Test
    fun `mixed eligibility and the engine's maximum agree over a grid of carts`() {
        var runs = 0
        var eligible = 0
        val failed = java.util.TreeMap<Rule, Int>()
        val moneyCarts = allCarts.filter { it.name in setOf("vip", "sword", "pack", "topup", "sub", "vip+pack", "vip+sword") }
        for (enabled in listOf(true, false)) for (allow in listOf(true, false)) for (only in listOf(true, false)) for (loggedIn in listOf(true, false))
            for (mode in PricingMode.values()) for (cart in moneyCarts) for (methodMixed in listOf<Boolean?>(null, true, false))
                for (balance in listOf(0L, 5_000L)) for (source in listOf(OrderSource.STOREFRONT, OrderSource.PANEL)) {
                    val buyer = buyer(balance = balance, loggedIn = loggedIn, userId = if (loggedIn) 1 else null, recipientUserId = if (loggedIn) 1 else null)
                    val profile = if (source == OrderSource.STOREFRONT) PricingProfile.STOREFRONT else PricingProfile.PANEL
                    // a method prices the order the way the cart was priced: the engine refuses a mismatch (a caller bug)
                    val authority = when (mode) {
                        PricingMode.MARKET -> PriceAuthority.MARKET
                        PricingMode.EXTERNAL_TAX -> PriceAuthority.GATEWAY_ADDS_TAX
                        PricingMode.EXTERNAL -> PriceAuthority.GATEWAY_CATALOG
                    }
                    val m = methodMixed?.let { method("m", authority = authority, mixedCredit = it) }
                    val bd = PricingFixtures.full(
                        *linesOf(cart).toTypedArray(), config = cfg(enabled, allow, only), profile = profile, buyer = buyer, mode = mode,
                        useCredits = MixedPayment.MAX, method = m
                    )
                    val hasGranting = cart.products.any { it.kind == LineKind.CREDIT_PACK } || cart.topUp
                    val verdict = CreditEligibility.mixed(
                        MixedFacts(enabled, allow, only, loggedIn, mode, hasGranting, cart.products.any { it.subscription }, source, methodMixed, if (loggedIn) balance else 0L)
                    )
                    val where = "enabled=$enabled allow=$allow only=$only loggedIn=$loggedIn mode=$mode cart=${cart.name} method=$methodMixed balance=$balance source=$source verdict=$verdict"
                    assertEquals(verdict.ok, (bd.credits?.maxApplicable ?: 0L) > 0L, "the maximum: $where")
                    // the message the quote carries for a refused request
                    val forced = profile == PricingProfile.STOREFRONT && only && enabled && !hasGranting
                    if (!forced) {
                        assertEquals(
                            verdict.reason == PricingCode.MIXED_CREDIT_NOT_SUPPORTED,
                            bd.messages.any { it.code == PricingCode.MIXED_CREDIT_NOT_SUPPORTED },
                            "the message: $where"
                        )
                    }
                    if (verdict.ok) eligible++ else failed.merge(verdict.failed!!, 1) { a, b -> a + b }
                    runs++
                }
        assertEquals(2 * 2 * 2 * 2 * 3 * moneyCarts.size * 3 * 2 * 2, runs)
        // credits on, mixed allowed, not onlyAcceptCredits, buyer in, gateway does not set the price, balance above 0, storefront:
        // the three plain carts (vip, sword, vip+sword) x the two method states that take a credit part (none yet, capable)
        assertEquals(6, eligible, "eligible carts of the grid")
        for (rule in listOf(Rule.M1, Rule.M2, Rule.M3, Rule.M4, Rule.M5, Rule.M6)) {
            // M5 and M6 are reached by 3 plain carts x 2 (balance resp. method) = 6 setups each, the earlier rules by many more
            assertTrue((failed[rule] ?: 0) >= 6, "the grid must fail $rule at least 6 times: $failed")
        }
    }
}
