package com.panomc.plugins.market.provider

import com.panomc.plugins.market.spi.common.HttpReply
import com.panomc.plugins.market.spi.common.LocalizedText
import com.panomc.plugins.market.spi.common.ProviderDescriptor
import com.panomc.plugins.market.spi.common.ProviderErrorCode
import com.panomc.plugins.market.spi.common.ProviderException
import com.panomc.plugins.market.spi.common.ProviderSettings
import com.panomc.plugins.market.spi.common.SettingsSchema
import com.panomc.plugins.market.spi.common.TestModeSupport
import com.panomc.plugins.market.spi.common.WebhookSetup
import com.panomc.plugins.market.spi.common.settingsSchema
import com.panomc.plugins.market.spi.payment.CheckoutSnapshot
import com.panomc.plugins.market.spi.payment.Eligibility
import com.panomc.plugins.market.spi.payment.InboundResult
import com.panomc.plugins.market.spi.payment.InstructionField
import com.panomc.plugins.market.spi.payment.PaymentCapabilities
import com.panomc.plugins.market.spi.payment.PaymentContext
import com.panomc.plugins.market.spi.payment.PaymentInboundRequest
import com.panomc.plugins.market.spi.payment.PaymentProvider
import com.panomc.plugins.market.spi.payment.RecurringSupport
import com.panomc.plugins.market.spi.payment.RefundSupport
import com.panomc.plugins.market.spi.payment.SettingsValidation
import com.panomc.plugins.market.spi.payment.StartPaymentRequest
import com.panomc.plugins.market.spi.payment.StartPaymentResult
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject

/** One receiving account of the bank transfer method. [currency] blank = accepts any currency. */
internal class BankAccount(val bank: String, val holder: String, val iban: String, val currency: String?) {
    fun accepts(orderCurrency: String): Boolean = currency == null || currency.equals(orderCurrency, ignoreCase = true)

    fun toJson(): JsonObject = JsonObject().put("bank", bank).put("holder", holder).put("iban", iban).also { if (currency != null) it.put("currency", currency) }
}

/**
 * `bank-transfer` (02 section 12, 06 section 14.1): offline payment. `startPayment` answers `Instructions` (accounts,
 * exact amount, the attempt reference as the transfer note); the attempt stays PENDING until the buyer's notice and the
 * admin's decision, both of which market turns into events (the provider never sees them). The attempt lifetime
 * (`bankTransferExpiryHours`) is a store setting market applies, not a provider setting.
 *
 * Settings: `accounts` (a JSON array of `{bank, holder, iban, currency?}` kept in a textarea until the panel has a
 * list editor), `instructions` (plain text, escaped by market when rendered), `requireBuyerNotice` (read by market:
 * the admin may only approve after the buyer pressed "I have paid"). The single-account keys of the old catalogue
 * (`bankName`, `iban`, `accountHolder`, `description`) are read as the first account when `accounts` is empty and
 * [migrateLegacy] folds them into the new keys on the first save (16 section 16). `autoApprove` has no equivalent and
 * is dropped: a transfer is never approved without a human.
 */
internal class BankTransferProvider : PaymentProvider {
    override val id: String = ID

    override val descriptor = ProviderDescriptor(
        LocalizedText.of("Bank transfer", "tr" to "Banka havalesi / EFT", "ru" to "Банковский перевод"),
        LocalizedText.of(
            "The buyer transfers the amount to the store's bank account; an admin approves the payment.",
            "tr" to "Alıcı tutarı mağazanın banka hesabına gönderir; ödemeyi yönetici onaylar.",
            "ru" to "Покупатель переводит сумму на банковский счёт магазина, оплату подтверждает администратор."
        ),
        "building-columns"
    )

    override fun settingsSchema(): SettingsSchema = settingsSchema {
        textarea(KEY_ACCOUNTS) {
            label = LocalizedText.of("Bank accounts", "tr" to "Banka hesapları", "ru" to "Банковские счета")
            help = LocalizedText.of(
                "A JSON list: [{\"bank\":\"...\",\"holder\":\"...\",\"iban\":\"...\",\"currency\":\"EUR\"}]. Leave the currency out to accept any currency.",
                "tr" to "JSON listesi: [{\"bank\":\"...\",\"holder\":\"...\",\"iban\":\"...\",\"currency\":\"TRY\"}]. Her para birimi için currency alanını boş bırakın.",
                "ru" to "Список JSON: [{\"bank\":\"...\",\"holder\":\"...\",\"iban\":\"...\",\"currency\":\"EUR\"}]. Без currency счёт принимает любую валюту."
            )
        }
        textarea(KEY_INSTRUCTIONS) {
            label = LocalizedText.of("Instructions for the buyer", "tr" to "Alıcı için açıklama", "ru" to "Инструкции для покупателя")
            help = LocalizedText.of(
                "Plain text shown under the account details.",
                "tr" to "Hesap bilgilerinin altında gösterilen düz metin.",
                "ru" to "Обычный текст под реквизитами."
            )
        }
        switch(KEY_REQUIRE_NOTICE) {
            label = LocalizedText.of("Require the buyer's notice", "tr" to "Alıcı bildirimi zorunlu", "ru" to "Требовать уведомление покупателя")
            help = LocalizedText.of(
                "The admin can approve the payment only after the buyer reported the transfer.",
                "tr" to "Yönetici ödemeyi ancak alıcı havaleyi bildirdikten sonra onaylayabilir.",
                "ru" to "Администратор может подтвердить оплату только после уведомления покупателя."
            )
            default = false
        }
    }

    override fun capabilities(settings: ProviderSettings): PaymentCapabilities = PaymentCapabilities().also {
        val accounts = accountsOf(settings)
        it.currencies = if (accounts.isEmpty() || accounts.any { a -> a.currency == null }) null else accounts.mapNotNull { a -> a.currency?.uppercase() }.toSet()
        it.refund = RefundSupport.NONE
        it.recurring = RecurringSupport.NONE
        it.longPending = true
        it.webhookSetup = WebhookSetup.NONE
        it.testMode = TestModeSupport.NONE
    }

    override suspend fun validateSettings(ctx: PaymentContext, settings: ProviderSettings): SettingsValidation {
        val raw = settings.string(KEY_ACCOUNTS)
        if (raw == null && settings.string(LEGACY_IBAN) != null) return validateAccounts(accountsOf(settings))
        val parsed = try {
            parseAccounts(raw)
        } catch (e: IllegalArgumentException) {
            return SettingsValidation.invalid(
                mapOf(KEY_ACCOUNTS to LocalizedText.of("The account list is not valid: ${e.message}", "tr" to "Hesap listesi geçersiz: ${e.message}", "ru" to "Список счетов неверен: ${e.message}"))
            )
        }
        return validateAccounts(parsed)
    }

    private fun validateAccounts(accounts: List<BankAccount>): SettingsValidation =
        if (accounts.isEmpty()) SettingsValidation.invalid(
            mapOf(KEY_ACCOUNTS to LocalizedText.of("Add at least one bank account.", "tr" to "En az bir banka hesabı ekleyin.", "ru" to "Добавьте хотя бы один банковский счёт."))
        ) else SettingsValidation.ok()

    override fun checkEligibility(ctx: PaymentContext, checkout: CheckoutSnapshot): Eligibility {
        val accounts = accountsOf(ctx.settings)
        return if (accounts.any { it.accepts(checkout.order.currency) }) Eligibility.eligible()
        else Eligibility.ineligible(
            "NO_ACCOUNT_FOR_CURRENCY",
            LocalizedText.of(
                "No bank account accepts ${checkout.order.currency}.",
                "tr" to "${checkout.order.currency} için banka hesabı yok.",
                "ru" to "Нет банковского счёта для ${checkout.order.currency}."
            )
        )
    }

    override suspend fun startPayment(ctx: PaymentContext, request: StartPaymentRequest): StartPaymentResult {
        val currency = request.amount.currency
        val accounts = accountsOf(ctx.settings).filter { it.accepts(currency) }
        if (accounts.isEmpty()) {
            throw ProviderException(ProviderErrorCode.CONFIGURATION, "no bank account accepts $currency")
        }
        val fields = ArrayList<InstructionField>()
        val numbered = accounts.size > 1
        accounts.forEachIndexed { i, a ->
            val n = if (numbered) " ${i + 1}" else ""
            fields += InstructionField(LocalizedText.of("Bank$n", "tr" to "Banka$n", "ru" to "Банк$n"), a.bank)
            fields += InstructionField(LocalizedText.of("Account holder$n", "tr" to "Hesap sahibi$n", "ru" to "Владелец счёта$n"), a.holder)
            fields += InstructionField(LocalizedText.of("IBAN$n", "tr" to "IBAN$n", "ru" to "IBAN$n"), a.iban)
        }
        fields += InstructionField(LocalizedText.of("Amount", "tr" to "Tutar", "ru" to "Сумма"), "${request.amount.toDecimalString()} $currency")
        fields += InstructionField(LocalizedText.of("Transfer note", "tr" to "Açıklama (havale notu)", "ru" to "Назначение платежа"), request.attempt.reference)

        val custom = ctx.settings.string(KEY_INSTRUCTIONS)
        val body = LocalizedText.of(
            listOfNotNull(
                "Transfer exactly the amount below and write the transfer note in the description of the transfer.",
                custom
            ).joinToString("\n\n"),
            "tr" to listOfNotNull("Aşağıdaki tutarı aynen gönderin ve havale açıklamasına havale notunu yazın.", custom).joinToString("\n\n"),
            "ru" to listOfNotNull("Переведите точную сумму и укажите назначение платежа в комментарии к переводу.", custom).joinToString("\n\n")
        )
        return StartPaymentResult.Instructions(body, fields).also { it.buyerConfirms = true }
    }

    /** No route of this provider is reachable from outside (the buyer's notice and the admin decision are market's). */
    override suspend fun handleInbound(ctx: PaymentContext, request: PaymentInboundRequest): InboundResult =
        InboundResult.rejected(HttpReply.text("Not found", 404), "$id has no inbound routes")

    companion object {
        const val ID = "bank-transfer"
        const val KEY_ACCOUNTS = "accounts"
        const val KEY_INSTRUCTIONS = "instructions"
        const val KEY_REQUIRE_NOTICE = "requireBuyerNotice"

        const val LEGACY_BANK = "bankName"
        const val LEGACY_IBAN = "iban"
        const val LEGACY_HOLDER = "accountHolder"
        const val LEGACY_DESCRIPTION = "description"
        const val LEGACY_AUTO_APPROVE = "autoApprove"

        const val MAX_ACCOUNTS = 10

        /**
         * The account list in effect: `accounts` when it holds at least one entry, else the legacy single account
         * (`bankName`, `iban`, `accountHolder`), else empty. Never throws: unreadable input yields no accounts.
         */
        fun accountsOf(settings: ProviderSettings): List<BankAccount> {
            val listed = try {
                parseAccounts(settings.string(KEY_ACCOUNTS))
            } catch (e: IllegalArgumentException) {
                emptyList()
            }
            if (listed.isNotEmpty()) return listed
            return legacyAccount(settings.string(LEGACY_BANK), settings.string(LEGACY_HOLDER), settings.string(LEGACY_IBAN))?.let { listOf(it) } ?: emptyList()
        }

        private fun legacyAccount(bank: String?, holder: String?, iban: String?): BankAccount? =
            if (iban == null) null else BankAccount(bank ?: "", holder ?: "", iban, null)

        /** Parses the `accounts` value; blank = empty list; throws [IllegalArgumentException] with a short reason. */
        fun parseAccounts(raw: String?): List<BankAccount> {
            if (raw.isNullOrBlank()) return emptyList()
            val array = try {
                JsonArray(raw)
            } catch (e: Exception) {
                throw IllegalArgumentException("not a JSON list")
            }
            require(array.size() <= MAX_ACCOUNTS) { "at most $MAX_ACCOUNTS accounts" }
            return (0 until array.size()).map { i ->
                val o = array.getValue(i) as? JsonObject ?: throw IllegalArgumentException("entry ${i + 1} is not an object")
                fun text(key: String, max: Int): String? {
                    val v = o.getValue(key)
                    require(v == null || v is String) { "entry ${i + 1}: $key must be text" }
                    return (v as String?)?.trim()?.takeIf { it.isNotEmpty() }?.also { require(it.length <= max) { "entry ${i + 1}: $key is too long" } }
                }
                val iban = text("iban", 64) ?: throw IllegalArgumentException("entry ${i + 1}: iban is required")
                val currency = text("currency", 3)
                require(currency == null || Regex("^[A-Za-z]{3}$").matches(currency)) { "entry ${i + 1}: currency must be a 3 letter code" }
                BankAccount(text("bank", 128) ?: "", text("holder", 128) ?: "", iban, currency?.uppercase())
            }
        }

        /**
         * Folds the legacy single-account keys of a stored settings object into the new keys (16 section 16): the
         * legacy account becomes the first entry of `accounts` (ahead of any entry already there), `description`
         * becomes `instructions` when that is empty, `autoApprove` is dropped. A stored object without legacy keys is
         * returned unchanged (a copy).
         */
        fun migrateLegacy(stored: JsonObject): JsonObject {
            val out = stored.copy()
            val legacyKeys = listOf(LEGACY_BANK, LEGACY_IBAN, LEGACY_HOLDER, LEGACY_DESCRIPTION, LEGACY_AUTO_APPROVE)
            if (legacyKeys.none { out.containsKey(it) }) return out
            fun str(key: String): String? = (out.getValue(key) as? String)?.trim()?.takeIf { it.isNotEmpty() }
            val existing = try {
                parseAccounts(str(KEY_ACCOUNTS))
            } catch (e: IllegalArgumentException) {
                null
            }
            val first = legacyAccount(str(LEGACY_BANK), str(LEGACY_HOLDER), str(LEGACY_IBAN))
            // An unreadable accounts value is left alone for the admin to fix; the legacy keys stay until it parses.
            if (existing != null) {
                val merged = listOfNotNull(first) + existing
                if (merged.isNotEmpty()) out.put(KEY_ACCOUNTS, JsonArray(merged.map { it.toJson() }).encode())
                if (str(KEY_INSTRUCTIONS) == null) str(LEGACY_DESCRIPTION)?.let { out.put(KEY_INSTRUCTIONS, it) }
                legacyKeys.forEach { out.remove(it) }
            }
            return out
        }
    }
}
