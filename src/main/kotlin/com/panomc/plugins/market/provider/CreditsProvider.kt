package com.panomc.plugins.market.provider

import com.panomc.plugins.market.spi.common.LocalizedText
import com.panomc.plugins.market.spi.common.ProviderDescriptor
import com.panomc.plugins.market.spi.payment.CheckoutSnapshot
import com.panomc.plugins.market.spi.payment.Eligibility
import com.panomc.plugins.market.spi.payment.PaymentContext

/**
 * `credits`: chosen automatically when the buyer pays the whole order with credits (02 section 12). A real ledger HOLD
 * always exists before this provider runs, so it only answers `Completed(Succeeded(paid = 0))`; refunds never reach
 * it (the credit part of a refund is a ledger transaction). Not a configurable method: its switches are the store's
 * credit settings.
 */
internal class CreditsProvider : ZeroAmountProvider() {
    override val id: String = ID

    override val descriptor = ProviderDescriptor(
        LocalizedText.of("Store credits", "tr" to "Mağaza kredisi", "ru" to "Кредиты магазина"),
        LocalizedText.of(
            "The whole order is paid with the buyer's store credits.",
            "tr" to "Siparişin tamamı alıcının mağaza kredisiyle ödenir.",
            "ru" to "Весь заказ оплачивается кредитами магазина покупателя."
        ),
        "coins"
    )

    /** Credits belong to an account: logged-in buyers only. */
    override val guestsAllowed: Boolean = false

    override fun checkEligibility(ctx: PaymentContext, checkout: CheckoutSnapshot): Eligibility =
        if (checkout.buyer.guest) Eligibility.ineligible(
            "LOGIN_REQUIRED",
            LocalizedText.of("Credits can only be used when logged in.", "tr" to "Kredi yalnızca giriş yapıldığında kullanılabilir.", "ru" to "Кредиты доступны только после входа.")
        ) else Eligibility.eligible()

    companion object {
        const val ID = "credits"
    }
}
