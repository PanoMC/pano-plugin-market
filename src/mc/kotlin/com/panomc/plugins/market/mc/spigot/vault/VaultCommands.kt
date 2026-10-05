package com.panomc.plugins.market.mc.spigot.vault

import com.panomc.plugins.market.mc.core.feature.MarketCommands
import com.panomc.plugins.market.mc.core.feature.McSender
import com.panomc.plugins.market.mc.core.feature.Messages
import com.panomc.plugins.market.mc.core.feature.Msg
import com.panomc.plugins.market.mc.core.wire.PlayerRef
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap

/**
 * `/credits convert <amount>` (credits -> server money) and `/credits deposit <amount>` (server money -> credits), the
 * CONVERT mode of the Vault bridge (19 section 10). Registered on the `credits` command with `registerSub(..., Feature.VAULT)`,
 * so the vault switches of the panel and of `config.yml` already apply before anything here runs. Only players, one
 * conversion per player at a time, the mode / direction / rate read live from the effective config on every use.
 */
class VaultCommands(
    private val ops: VaultOps,
    private val settings: () -> VaultSettings,
    private val texts: VaultMessages,
    private val messages: Messages,
    private val economy: () -> ServerEconomy?
) {
    private val busy = ConcurrentHashMap.newKeySet<String>()

    fun convert(sender: McSender, args: List<String>) = run(sender, args, toServer = true)

    fun deposit(sender: McSender, args: List<String>) = run(sender, args, toServer = false)

    private fun run(sender: McSender, args: List<String>, toServer: Boolean) {
        if (sender.isConsole) return sender.send(messages.text(Msg.COMMAND_PLAYER_ONLY, sender.locale))
        val s = settings()
        when (s.mode) {
            VaultMode.OFF -> return sender.send(messages.text(Msg.COMMAND_DISABLED, sender.locale))
            VaultMode.PROVIDER -> return sender.send(t(sender, VaultMsg.WRONG_MODE))
            VaultMode.CONVERT -> Unit
        }
        val allowed = if (toServer) s.direction.allowsToServer else s.direction.allowsToCredits
        if (!allowed) return sender.send(t(sender, VaultMsg.DIRECTION_OFF))
        val rate = s.rate ?: return sender.send(t(sender, VaultMsg.RATE_INVALID))
        if (args.size != 1) return sender.send(t(sender, if (toServer) VaultMsg.USAGE_CONVERT else VaultMsg.USAGE_DEPOSIT))
        val typed = parseAmount(args[0]) ?: return sender.send(t(sender, VaultMsg.BAD_AMOUNT))
        val amounts = (if (toServer) Conversion.toServer(typed, rate) else Conversion.toCredits(typed, rate)) ?: return sender.send(t(sender, VaultMsg.TOO_SMALL))
        val eco = economy() ?: return sender.send(t(sender, VaultMsg.NO_ECONOMY))

        val key = sender.name.lowercase()
        if (!busy.add(key)) return sender.send(t(sender, VaultMsg.BUSY))
        val player = PlayerRef(sender.name, sender.uuid)
        val credits = amounts.credits.toDouble()
        val money = amounts.money.toDouble()
        val moneyText = eco.format(money)
        val creditsText = MarketCommands.number(credits)
        // One answer per conversion; whatever happens, the player may start the next one afterwards.
        val finish = { lines: List<String> ->
            busy.remove(key)
            lines.forEach { sender.send(it) }
        }
        try {
            if (toServer) {
                ops.convertToServer(player, credits, money) { outcome -> finish(toServerLines(sender, outcome, creditsText, moneyText)) }
            } else {
                ops.convertToCredits(player, money, credits) { outcome -> finish(toCreditsLines(sender, outcome, creditsText, moneyText)) }
            }
        } catch (e: Throwable) {
            busy.remove(key)
            throw e
        }
    }

    private fun toServerLines(sender: McSender, o: ConvertOutcome, credits: String, money: String): List<String> = when (o) {
        is ConvertOutcome.Done -> listOf(t(sender, VaultMsg.CONVERTED, "credits" to credits, "money" to money, "balance" to number(o.balance)))
        is ConvertOutcome.Refused -> listOf(refusal(sender, o.code))
        is ConvertOutcome.NotApplied -> listOf(notApplied(sender, o.reason))
        ConvertOutcome.Unknown -> listOf(t(sender, VaultMsg.UNKNOWN_TO_SERVER))
        is ConvertOutcome.PayoutFailed -> listOf(t(sender, if (o.restored) VaultMsg.PAYOUT_FAILED_RESTORED else VaultMsg.PAYOUT_FAILED_PENDING))
        ConvertOutcome.NoEconomy -> listOf(t(sender, VaultMsg.NO_ECONOMY))
        ConvertOutcome.NotConnected -> listOf(messages.text(Msg.ERROR_NOT_CONNECTED, sender.locale))
        ConvertOutcome.JournalFailed -> listOf(t(sender, VaultMsg.JOURNAL_FAILED))
        ConvertOutcome.Pending, ConvertOutcome.InsufficientMoney, is ConvertOutcome.MoneyRefused -> listOf(t(sender, VaultMsg.REFUSED, "code" to "UNEXPECTED"))
    }

    private fun toCreditsLines(sender: McSender, o: ConvertOutcome, credits: String, money: String): List<String> = when (o) {
        is ConvertOutcome.Done -> listOf(t(sender, VaultMsg.DEPOSITED, "credits" to credits, "money" to money, "balance" to number(o.balance)))
        is ConvertOutcome.Refused -> listOf(refusal(sender, o.code)) + when (o.refunded) {
            true -> listOf(t(sender, VaultMsg.MONEY_RETURNED))
            false -> listOf(t(sender, VaultMsg.MONEY_RETURN_PENDING))
            null -> emptyList()
        }
        ConvertOutcome.Pending -> listOf(t(sender, VaultMsg.PENDING_TO_CREDITS))
        ConvertOutcome.InsufficientMoney -> listOf(t(sender, VaultMsg.INSUFFICIENT_MONEY))
        is ConvertOutcome.MoneyRefused -> listOf(t(sender, VaultMsg.MONEY_REFUSED, "error" to (o.error ?: "unknown")))
        ConvertOutcome.NoEconomy -> listOf(t(sender, VaultMsg.NO_ECONOMY))
        ConvertOutcome.NotConnected -> listOf(messages.text(Msg.ERROR_NOT_CONNECTED, sender.locale))
        ConvertOutcome.JournalFailed -> listOf(t(sender, VaultMsg.JOURNAL_FAILED))
        is ConvertOutcome.NotApplied, ConvertOutcome.Unknown, is ConvertOutcome.PayoutFailed -> listOf(t(sender, VaultMsg.REFUSED, "code" to "UNEXPECTED"))
    }

    private fun refusal(sender: McSender, code: String): String = when (code) {
        "INSUFFICIENT_CREDITS" -> t(sender, VaultMsg.INSUFFICIENT_CREDITS)
        "NO_ACCOUNT" -> t(sender, VaultMsg.NO_ACCOUNT)
        "CREDITS_DISABLED" -> t(sender, VaultMsg.CREDITS_OFF)
        else -> t(sender, VaultMsg.REFUSED, "code" to code)
    }

    private fun notApplied(sender: McSender, reason: String): String = when (reason) {
        "RATE_LIMITED" -> messages.text(Msg.ERROR_RATE_LIMITED, sender.locale)
        "MARKET_NOT_READY" -> messages.text(Msg.ERROR_NOT_READY, sender.locale)
        "VERSION_MISMATCH", "PROTOCOL_UNSUPPORTED" -> messages.text(Msg.ERROR_VERSION, sender.locale)
        else -> t(sender, VaultMsg.NOT_APPLIED, "reason" to reason)
    }

    private fun number(v: Double?): String = MarketCommands.number(v ?: 0.0)

    private fun t(sender: McSender, key: String, vararg args: Pair<String, Any?>) = texts.text(key, sender.locale, *args)

    private fun parseAmount(raw: String): BigDecimal? {
        if (!AMOUNT.matches(raw)) return null
        val v = BigDecimal(raw)
        return if (v.signum() > 0) v else null
    }

    private companion object {
        val AMOUNT = Regex("\\d{1,12}(\\.\\d{1,2})?")
    }
}
