package com.panomc.plugins.market.mc.spigot.vault

import com.panomc.plugins.market.mc.core.feature.ChatFormat
import com.panomc.plugins.market.mc.core.feature.Messages

/** The message keys of the Vault bridge. Everything generic (not connected, console, command off, ...) reuses the keys of `Msg`. */
object VaultMsg {
    const val USAGE_CONVERT = "vault.usage.convert"
    const val USAGE_DEPOSIT = "vault.usage.deposit"
    const val WRONG_MODE = "vault.wrongMode"
    const val DIRECTION_OFF = "vault.directionOff"
    const val RATE_INVALID = "vault.rateInvalid"
    const val BAD_AMOUNT = "vault.badAmount"
    const val TOO_SMALL = "vault.tooSmall"
    const val BUSY = "vault.busy"
    const val NO_ECONOMY = "vault.noEconomy"
    const val JOURNAL_FAILED = "vault.journalFailed"

    const val CONVERTED = "vault.converted"
    const val DEPOSITED = "vault.deposited"

    const val NO_ACCOUNT = "vault.noAccount"
    const val INSUFFICIENT_CREDITS = "vault.insufficientCredits"
    const val INSUFFICIENT_MONEY = "vault.insufficientMoney"
    const val MONEY_REFUSED = "vault.moneyRefused"
    const val CREDITS_OFF = "vault.creditsOff"
    const val REFUSED = "vault.refused"
    const val NOT_APPLIED = "vault.notApplied"

    const val UNKNOWN_TO_SERVER = "vault.unknownToServer"
    const val PAYOUT_FAILED_RESTORED = "vault.payoutFailedRestored"
    const val PAYOUT_FAILED_PENDING = "vault.payoutFailedPending"
    const val PENDING_TO_CREDITS = "vault.pendingToCredits"
    const val MONEY_RETURNED = "vault.moneyReturned"
    const val MONEY_RETURN_PENDING = "vault.moneyReturnPending"

    const val LATE_DEPOSITED = "vault.lateDeposited"
    const val LATE_UNDONE = "vault.lateUndone"
    const val LATE_REFUNDED = "vault.lateRefunded"
    const val LATE_REFUND_STUCK = "vault.lateRefundStuck"

    // the error texts of the credits economy (PROVIDER mode): other plugins (/pay, shops) print them to the player
    const val ECONOMY_NO_NAME = "vault.economy.noName"
    const val ECONOMY_NOT_NUMBER = "vault.economy.notNumber"
    const val ECONOMY_NEGATIVE_WITHDRAW = "vault.economy.negativeWithdraw"
    const val ECONOMY_NEGATIVE_DEPOSIT = "vault.economy.negativeDeposit"
    const val ECONOMY_BUSY = "vault.economy.busy"
    const val ECONOMY_NOT_CONNECTED = "vault.economy.notConnected"
    const val ECONOMY_UNKNOWN = "vault.economy.unknown"
    const val ECONOMY_JOURNAL_FAILED = "vault.economy.journalFailed"
    const val ECONOMY_STOPPING = "vault.economy.stopping"
    const val ECONOMY_INSUFFICIENT = "vault.economy.insufficientCredits"
    const val ECONOMY_NO_ACCOUNT = "vault.economy.noAccount"
    const val ECONOMY_REFUSED = "vault.economy.refused"

    val ALL: List<String> = listOf(
        USAGE_CONVERT, USAGE_DEPOSIT, WRONG_MODE, DIRECTION_OFF, RATE_INVALID, BAD_AMOUNT, TOO_SMALL, BUSY, NO_ECONOMY, JOURNAL_FAILED,
        CONVERTED, DEPOSITED, NO_ACCOUNT, INSUFFICIENT_CREDITS, INSUFFICIENT_MONEY, MONEY_REFUSED, CREDITS_OFF, REFUSED, NOT_APPLIED,
        UNKNOWN_TO_SERVER, PAYOUT_FAILED_RESTORED, PAYOUT_FAILED_PENDING, PENDING_TO_CREDITS, MONEY_RETURNED, MONEY_RETURN_PENDING,
        LATE_DEPOSITED, LATE_UNDONE, LATE_REFUNDED, LATE_REFUND_STUCK,
        ECONOMY_NO_NAME, ECONOMY_NOT_NUMBER, ECONOMY_NEGATIVE_WITHDRAW, ECONOMY_NEGATIVE_DEPOSIT, ECONOMY_BUSY, ECONOMY_NOT_CONNECTED,
        ECONOMY_UNKNOWN, ECONOMY_JOURNAL_FAILED, ECONOMY_STOPPING, ECONOMY_INSUFFICIENT, ECONOMY_NO_ACCOUNT, ECONOMY_REFUSED
    )
}

/**
 * The bundled texts of the Vault bridge in en-US, tr and ru. They live in code, not in the `mc/lang` files, because the lang
 * files and `Msg` belong to the in-game features (MC-05); [VaultMessages] still lets the local `lang/<locale>.yml` override
 * and the `texts` of `MARKET_CONFIG` replace any of these keys first, exactly like every other message.
 */
object VaultTexts {
    val EN: Map<String, String> = mapOf(
        VaultMsg.USAGE_CONVERT to "&eUsage: &f/credits convert <amount> &7(turns that many credits into server money)",
        VaultMsg.USAGE_DEPOSIT to "&eUsage: &f/credits deposit <amount> &7(turns that much server money into credits)",
        VaultMsg.WRONG_MODE to "&cCredits and server money are not converted on this server.",
        VaultMsg.DIRECTION_OFF to "&cThis conversion direction is switched off on this server.",
        VaultMsg.RATE_INVALID to "&cThe conversion rate is not set up correctly. Please tell an administrator.",
        VaultMsg.BAD_AMOUNT to "&cThe amount must be a number such as 10 or 2.5 (at most two decimals, above zero).",
        VaultMsg.TOO_SMALL to "&cThat amount is too small, or too large, to convert.",
        VaultMsg.BUSY to "&cYour previous conversion has not finished yet.",
        VaultMsg.NO_ECONOMY to "&cThe server has no economy, so nothing can be converted.",
        VaultMsg.JOURNAL_FAILED to "&cThe conversion could not be recorded safely, so nothing was done.",
        VaultMsg.CONVERTED to "&aCredits converted: &e{credits}&a, received &e{money}&a. New credit balance: &e{balance}",
        VaultMsg.DEPOSITED to "&aDeposited &e{money}&a. Credits received: &e{credits}&a. New credit balance: &e{balance}",
        VaultMsg.NO_ACCOUNT to "&cYou need an account on the website first.",
        VaultMsg.INSUFFICIENT_CREDITS to "&cYou do not have enough credits.",
        VaultMsg.INSUFFICIENT_MONEY to "&cYou do not have enough money.",
        VaultMsg.MONEY_REFUSED to "&cThe server economy refused to take the money ({error}).",
        VaultMsg.CREDITS_OFF to "&cCredits are switched off in the store.",
        VaultMsg.REFUSED to "&cThe store refused the conversion ({code}).",
        VaultMsg.NOT_APPLIED to "&cThe store could not take the request right now ({reason}). Nothing was changed.",
        VaultMsg.UNKNOWN_TO_SERVER to "&eThe store did not answer. If your credits were taken they are given back automatically; no money was added.",
        VaultMsg.PAYOUT_FAILED_RESTORED to "&cThe server economy refused the payout, so your credits were given back.",
        VaultMsg.PAYOUT_FAILED_PENDING to "&cThe server economy refused the payout. Your credits are being given back, this can take a moment.",
        VaultMsg.PENDING_TO_CREDITS to "&eYour money was taken. The credits arrive as soon as the store answers.",
        VaultMsg.MONEY_RETURNED to "&aYour money was given back.",
        VaultMsg.MONEY_RETURN_PENDING to "&eYour money is being given back, this can take a moment.",
        VaultMsg.LATE_DEPOSITED to "&aYour deposit arrived. Credits received: &e{credits}&a.",
        VaultMsg.LATE_UNDONE to "&eYour conversion could not be completed. Any credits that were taken were given back.",
        VaultMsg.LATE_REFUNDED to "&eThe store refused your deposit ({code}). Your money was given back.",
        VaultMsg.LATE_REFUND_STUCK to "&cThe store refused your deposit ({code}) and your money could not be given back yet. It is retried automatically; ask an administrator if it does not arrive.",
        VaultMsg.ECONOMY_NO_NAME to "&cThe player has no name.",
        VaultMsg.ECONOMY_NOT_NUMBER to "&cThe amount is not a number.",
        VaultMsg.ECONOMY_NEGATIVE_WITHDRAW to "&cCannot withdraw negative funds.",
        VaultMsg.ECONOMY_NEGATIVE_DEPOSIT to "&cCannot deposit negative funds.",
        VaultMsg.ECONOMY_BUSY to "&cPano cannot take the request right now ({reason}).",
        VaultMsg.ECONOMY_NOT_CONNECTED to "&cPano is not connected, the credits economy is not available.",
        VaultMsg.ECONOMY_UNKNOWN to "&ePano did not answer in time; if the ledger applied the operation it is reverted automatically.",
        VaultMsg.ECONOMY_JOURNAL_FAILED to "&cThe operation could not be recorded safely, so it was not run.",
        VaultMsg.ECONOMY_STOPPING to "&cThe Market component is stopping.",
        VaultMsg.ECONOMY_INSUFFICIENT to "&cInsufficient credits.",
        VaultMsg.ECONOMY_NO_ACCOUNT to "&cThe player has no account on the website.",
        VaultMsg.ECONOMY_REFUSED to "&cPano refused the operation ({code})."
    )

    val TR: Map<String, String> = mapOf(
        VaultMsg.USAGE_CONVERT to "&eKullanım: &f/credits convert <miktar> &7(o kadar krediyi sunucu parasına çevirir)",
        VaultMsg.USAGE_DEPOSIT to "&eKullanım: &f/credits deposit <miktar> &7(o kadar sunucu parasını krediye çevirir)",
        VaultMsg.WRONG_MODE to "&cBu sunucuda kredi ve sunucu parası birbirine çevrilmiyor.",
        VaultMsg.DIRECTION_OFF to "&cBu çevirme yönü bu sunucuda kapalı.",
        VaultMsg.RATE_INVALID to "&cÇevirme oranı doğru ayarlanmamış. Lütfen bir yöneticiye haber verin.",
        VaultMsg.BAD_AMOUNT to "&cMiktar 10 ya da 2.5 gibi bir sayı olmalı (en fazla iki ondalık, sıfırdan büyük).",
        VaultMsg.TOO_SMALL to "&cBu miktar çevirmek için çok küçük ya da çok büyük.",
        VaultMsg.BUSY to "&cÖnceki çevirme işleminiz henüz bitmedi.",
        VaultMsg.NO_ECONOMY to "&cSunucuda bir ekonomi yok, bu yüzden hiçbir şey çevrilemez.",
        VaultMsg.JOURNAL_FAILED to "&cÇevirme güvenli biçimde kaydedilemedi, bu yüzden hiçbir şey yapılmadı.",
        VaultMsg.CONVERTED to "&e{credits}&a kredi &e{money}&a paraya çevrildi. Yeni kredi bakiyeniz: &e{balance}",
        VaultMsg.DEPOSITED to "&e{money}&a yatırıldı ve &e{credits}&a kredi aldınız. Yeni kredi bakiyeniz: &e{balance}",
        VaultMsg.NO_ACCOUNT to "&cÖnce web sitesinde bir hesabınız olmalı.",
        VaultMsg.INSUFFICIENT_CREDITS to "&cYeterli krediniz yok.",
        VaultMsg.INSUFFICIENT_MONEY to "&cYeterli paranız yok.",
        VaultMsg.MONEY_REFUSED to "&cSunucu ekonomisi parayı almayı reddetti ({error}).",
        VaultMsg.CREDITS_OFF to "&cMağazada kredi kapalı.",
        VaultMsg.REFUSED to "&cMağaza çevirmeyi reddetti ({code}).",
        VaultMsg.NOT_APPLIED to "&cMağaza isteği şu an alamadı ({reason}). Hiçbir şey değişmedi.",
        VaultMsg.UNKNOWN_TO_SERVER to "&eMağaza yanıt vermedi. Kredileriniz alındıysa otomatik olarak geri verilir; hesabınıza para eklenmedi.",
        VaultMsg.PAYOUT_FAILED_RESTORED to "&cSunucu ekonomisi ödemeyi reddetti, bu yüzden kredileriniz geri verildi.",
        VaultMsg.PAYOUT_FAILED_PENDING to "&cSunucu ekonomisi ödemeyi reddetti. Kredileriniz geri veriliyor, bu biraz sürebilir.",
        VaultMsg.PENDING_TO_CREDITS to "&eParanız alındı. Krediler mağaza yanıt verir vermez gelecek.",
        VaultMsg.MONEY_RETURNED to "&aParanız geri verildi.",
        VaultMsg.MONEY_RETURN_PENDING to "&eParanız geri veriliyor, bu biraz sürebilir.",
        VaultMsg.LATE_DEPOSITED to "&aYatırdığınız para ulaştı: &e{credits}&a kredi aldınız.",
        VaultMsg.LATE_UNDONE to "&eÇevirme tamamlanamadı. Alınmış krediler varsa geri verildi.",
        VaultMsg.LATE_REFUNDED to "&eMağaza yatırma işleminizi reddetti ({code}). Paranız geri verildi.",
        VaultMsg.LATE_REFUND_STUCK to "&cMağaza yatırma işleminizi reddetti ({code}) ve paranız henüz geri verilemedi. Otomatik olarak tekrar denenir; gelmezse bir yöneticiye sorun.",
        VaultMsg.ECONOMY_NO_NAME to "&cOyuncunun adı yok.",
        VaultMsg.ECONOMY_NOT_NUMBER to "&cMiktar bir sayı değil.",
        VaultMsg.ECONOMY_NEGATIVE_WITHDRAW to "&cEksi bir miktar çekilemez.",
        VaultMsg.ECONOMY_NEGATIVE_DEPOSIT to "&cEksi bir miktar yatırılamaz.",
        VaultMsg.ECONOMY_BUSY to "&cPano şu anda isteği alamıyor ({reason}).",
        VaultMsg.ECONOMY_NOT_CONNECTED to "&cPano bağlı değil, kredi ekonomisi kullanılamıyor.",
        VaultMsg.ECONOMY_UNKNOWN to "&ePano zamanında yanıt vermedi; işlem uygulandıysa otomatik olarak geri alınır.",
        VaultMsg.ECONOMY_JOURNAL_FAILED to "&cİşlem güvenli şekilde kaydedilemedi, bu yüzden yapılmadı.",
        VaultMsg.ECONOMY_STOPPING to "&cMarket bileşeni duruyor.",
        VaultMsg.ECONOMY_INSUFFICIENT to "&cYeterli kredi yok.",
        VaultMsg.ECONOMY_NO_ACCOUNT to "&cOyuncunun web sitesinde hesabı yok.",
        VaultMsg.ECONOMY_REFUSED to "&cPano işlemi reddetti ({code})."
    )

    val RU: Map<String, String> = mapOf(
        VaultMsg.USAGE_CONVERT to "&eИспользование: &f/credits convert <сумма> &7(превращает столько кредитов в серверные деньги)",
        VaultMsg.USAGE_DEPOSIT to "&eИспользование: &f/credits deposit <сумма> &7(превращает столько серверных денег в кредиты)",
        VaultMsg.WRONG_MODE to "&cНа этом сервере кредиты и серверные деньги не обмениваются.",
        VaultMsg.DIRECTION_OFF to "&cЭто направление обмена на сервере отключено.",
        VaultMsg.RATE_INVALID to "&cКурс обмена настроен неверно. Сообщите администратору.",
        VaultMsg.BAD_AMOUNT to "&cСумма должна быть числом вроде 10 или 2.5 (не более двух знаков после запятой, больше нуля).",
        VaultMsg.TOO_SMALL to "&cЭта сумма слишком мала или слишком велика для обмена.",
        VaultMsg.BUSY to "&cПредыдущий обмен еще не завершен.",
        VaultMsg.NO_ECONOMY to "&cНа сервере нет экономики, поэтому обменять ничего нельзя.",
        VaultMsg.JOURNAL_FAILED to "&cОбмен не удалось надежно записать, поэтому ничего не сделано.",
        VaultMsg.CONVERTED to "&aОбменяно кредитов: &e{credits}&a, получено &e{money}&a. Новый баланс кредитов: &e{balance}",
        VaultMsg.DEPOSITED to "&aВнесено &e{money}&a, получено кредитов: &e{credits}&a. Новый баланс кредитов: &e{balance}",
        VaultMsg.NO_ACCOUNT to "&cСначала нужен аккаунт на сайте.",
        VaultMsg.INSUFFICIENT_CREDITS to "&cУ вас недостаточно кредитов.",
        VaultMsg.INSUFFICIENT_MONEY to "&cУ вас недостаточно денег.",
        VaultMsg.MONEY_REFUSED to "&cЭкономика сервера отказалась списать деньги ({error}).",
        VaultMsg.CREDITS_OFF to "&cКредиты в магазине отключены.",
        VaultMsg.REFUSED to "&cМагазин отклонил обмен ({code}).",
        VaultMsg.NOT_APPLIED to "&cМагазин сейчас не смог принять запрос ({reason}). Ничего не изменилось.",
        VaultMsg.UNKNOWN_TO_SERVER to "&eМагазин не ответил. Если кредиты были списаны, они вернутся автоматически; деньги не начислены.",
        VaultMsg.PAYOUT_FAILED_RESTORED to "&cЭкономика сервера отказалась выплатить деньги, поэтому кредиты возвращены.",
        VaultMsg.PAYOUT_FAILED_PENDING to "&cЭкономика сервера отказалась выплатить деньги. Кредиты возвращаются, это может занять немного времени.",
        VaultMsg.PENDING_TO_CREDITS to "&eВаши деньги списаны. Кредиты придут, как только магазин ответит.",
        VaultMsg.MONEY_RETURNED to "&aВаши деньги возвращены.",
        VaultMsg.MONEY_RETURN_PENDING to "&eВаши деньги возвращаются, это может занять немного времени.",
        VaultMsg.LATE_DEPOSITED to "&aВаш взнос дошел, получено кредитов: &e{credits}&a.",
        VaultMsg.LATE_UNDONE to "&eОбмен не удалось завершить. Списанные кредиты, если они были, возвращены.",
        VaultMsg.LATE_REFUNDED to "&eМагазин отклонил ваш взнос ({code}). Деньги возвращены.",
        VaultMsg.LATE_REFUND_STUCK to "&cМагазин отклонил ваш взнос ({code}), и деньги пока не удалось вернуть. Попытки повторяются автоматически; если деньги не придут, спросите администратора.",
        VaultMsg.ECONOMY_NO_NAME to "&cУ игрока нет имени.",
        VaultMsg.ECONOMY_NOT_NUMBER to "&cСумма не является числом.",
        VaultMsg.ECONOMY_NEGATIVE_WITHDRAW to "&cНельзя списать отрицательную сумму.",
        VaultMsg.ECONOMY_NEGATIVE_DEPOSIT to "&cНельзя зачислить отрицательную сумму.",
        VaultMsg.ECONOMY_BUSY to "&cPano сейчас не может принять запрос ({reason}).",
        VaultMsg.ECONOMY_NOT_CONNECTED to "&cPano не подключен, экономика кредитов недоступна.",
        VaultMsg.ECONOMY_UNKNOWN to "&ePano не ответил вовремя; если операция была применена, она будет отменена автоматически.",
        VaultMsg.ECONOMY_JOURNAL_FAILED to "&cОперацию не удалось надежно записать, поэтому она не выполнена.",
        VaultMsg.ECONOMY_STOPPING to "&cКомпонент Market останавливается.",
        VaultMsg.ECONOMY_INSUFFICIENT to "&cНедостаточно кредитов.",
        VaultMsg.ECONOMY_NO_ACCOUNT to "&cУ игрока нет аккаунта на сайте.",
        VaultMsg.ECONOMY_REFUSED to "&cPano отклонил операцию ({code})."
    )

    private val BY_LOCALE = mapOf("en-US" to EN, "tr" to TR, "ru" to RU)

    /** The bundled template of [key] for [locale] (`tr_TR` finds `tr`, an unknown language falls back to en-US). */
    fun find(key: String, locale: String?): String? {
        val normalized = locale?.let { Messages.normalize(it) }
        val table = BY_LOCALE.entries.firstOrNull { it.key.equals(normalized, true) }?.value
            ?: normalized?.substringBefore('-')?.let { lang -> BY_LOCALE.entries.firstOrNull { it.key.substringBefore('-').equals(lang, true) }?.value }
            ?: EN
        return table[key] ?: EN[key]
    }
}

/**
 * Texts of the Vault bridge: the layers of [Messages] first (local override, `MARKET_CONFIG` texts, bundled lang files),
 * then the bundled [VaultTexts]. Values inserted into a text are made safe like everywhere else.
 */
class VaultMessages(private val messages: Messages) {
    fun text(key: String, locale: String?, vararg args: Pair<String, Any?>): String {
        if (messages.has(key, locale)) return messages.text(key, locale, *args)
        val template = VaultTexts.find(key, locale) ?: return key
        var out = ChatFormat.colorize(template)
        for ((name, value) in args) out = out.replace("{$name}", ChatFormat.plainValue(value?.toString() ?: ""))
        return out
    }
}
