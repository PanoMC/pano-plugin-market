package com.panomc.plugins.market.mc.vault

import com.panomc.plugins.market.mc.core.wire.EconomyOp
import com.panomc.plugins.market.mc.feature.FakeSender
import com.panomc.plugins.market.mc.feature.FeatureRig
import com.panomc.plugins.market.mc.spigot.vault.VaultCommands
import com.panomc.plugins.market.mc.spigot.vault.VaultDirection
import com.panomc.plugins.market.mc.spigot.vault.VaultMessages
import com.panomc.plugins.market.mc.spigot.vault.VaultMode
import com.panomc.plugins.market.mc.spigot.vault.VaultSettings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Path

/** `/credits convert` and `/credits deposit`: modes, directions, the rate and its rounding, input checks, every outcome's message. */
class VaultCommandsTest {
    @TempDir
    lateinit var dir: Path

    private val rig by lazy { VaultRig(dir.resolve("ops")) }
    private val features by lazy { FeatureRig(dir.resolve("features")) }
    private var settings = VaultSettings(VaultMode.CONVERT, VaultDirection.BOTH, BigDecimal("2.5"))
    private val commands by lazy {
        VaultCommands(rig.ops, { settings }, VaultMessages(features.features.messages), features.features.messages) { if (rig.hasEconomy) rig.eco else null }
    }

    private fun steve(locale: String? = null, console: Boolean = false) = FakeSender(name = if (console) "CONSOLE" else "Steve", isConsole = console, locale = locale)

    private fun said(s: FakeSender) = s.plain().joinToString(" | ")

    private fun credits() = rig.ledger.balance("Steve")!!.stripTrailingZeros().toPlainString()

    private fun money() = rig.eco.balance("Steve").stripTrailingZeros().toPlainString()

    private fun accounts(credits: String = "50", money: String = "100") {
        rig.ledger.account("Steve", credits)
        rig.eco.set("Steve", money)
    }

    // ---- gates --------------------------------------------------------------------------------------------------------------

    @Test
    fun `the console is refused`() {
        accounts()
        val c = steve(console = true)
        commands.convert(c, listOf("10"))
        assertTrue(said(c).contains("Only players"))
        assertTrue(rig.link.requests.isEmpty())
    }

    @Test
    fun `mode OFF says the command is switched off and PROVIDER says nothing is converted here`() {
        accounts()
        settings = VaultSettings.OFF
        val a = steve()
        commands.convert(a, listOf("10"))
        assertTrue(said(a).contains("switched off"))
        settings = VaultSettings(VaultMode.PROVIDER, VaultDirection.BOTH, null)
        val b = steve()
        commands.deposit(b, listOf("10"))
        assertTrue(said(b).contains("not converted on this server"))
        assertTrue(rig.link.requests.isEmpty() && rig.eco.calls.isEmpty())
    }

    @Test
    fun `direction TO_SERVER allows convert only, TO_CREDITS deposit only, NONE neither, BOTH both`() {
        accounts()
        fun run(direction: VaultDirection, toServer: Boolean): String {
            settings = VaultSettings(VaultMode.CONVERT, direction, BigDecimal("2"))
            val s = steve()
            if (toServer) commands.convert(s, listOf("1")) else commands.deposit(s, listOf("2"))
            return said(s)
        }
        assertTrue(run(VaultDirection.TO_SERVER, false).contains("direction is switched off"))
        assertTrue(run(VaultDirection.TO_CREDITS, true).contains("direction is switched off"))
        assertTrue(run(VaultDirection.NONE, true).contains("direction is switched off"))
        assertTrue(run(VaultDirection.NONE, false).contains("direction is switched off"))
        assertEquals(0, rig.link.requests.size)
        assertTrue(run(VaultDirection.TO_SERVER, true).contains("Converted"))
        assertTrue(run(VaultDirection.TO_CREDITS, false).contains("Deposited"))
        assertTrue(run(VaultDirection.BOTH, true).contains("Converted"))
        assertTrue(run(VaultDirection.BOTH, false).contains("Deposited"))
    }

    @Test
    fun `a missing or unusable rate refuses both commands`() {
        accounts()
        settings = VaultSettings(VaultMode.CONVERT, VaultDirection.BOTH, null)
        val s = steve()
        commands.convert(s, listOf("10"))
        commands.deposit(s, listOf("10"))
        assertEquals(2, s.lines.count { it.contains("rate is not set up") })
        assertTrue(rig.link.requests.isEmpty())
    }

    @Test
    fun `usage is shown for a wrong number of arguments and a bad amount never reaches Pano`() {
        accounts()
        val s = steve()
        commands.convert(s, emptyList())
        commands.convert(s, listOf("1", "2"))
        commands.deposit(s, emptyList())
        assertEquals(2, s.lines.count { it.contains("/credits convert <amount>") })
        assertEquals(1, s.lines.count { it.contains("/credits deposit <amount>") })
        val bad = steve()
        for (a in listOf("abc", "-5", "0", "0.0", "1.234", "1e3", "", "1,5", "NaN", "9999999999999", " 5", "+5")) {
            commands.convert(bad, listOf(a))
            commands.deposit(bad, listOf(a))
        }
        assertEquals(24, bad.lines.count { it.contains("must be a number") }, said(bad))
        assertTrue(rig.link.requests.isEmpty() && rig.eco.calls.isEmpty())
    }

    @Test
    fun `an amount too small to convert is refused before anything moves`() {
        accounts()
        settings = VaultSettings(VaultMode.CONVERT, VaultDirection.BOTH, BigDecimal("0.1"))
        val a = steve()
        commands.convert(a, listOf("0.01")) // 0.001 money rounds to nothing
        settings = VaultSettings(VaultMode.CONVERT, VaultDirection.BOTH, BigDecimal("2"))
        commands.deposit(a, listOf("0.01")) // 0.005 credits round to nothing
        assertEquals(2, a.lines.count { it.contains("too small") })
        assertTrue(rig.link.requests.isEmpty())
    }

    @Test
    fun `no server economy - nothing is converted`() {
        accounts()
        rig.hasEconomy = false
        val s = steve()
        commands.convert(s, listOf("10"))
        commands.deposit(s, listOf("10"))
        assertEquals(2, s.lines.count { it.contains("no economy") })
        assertTrue(rig.link.requests.isEmpty())
    }

    @Test
    fun `no connection to Pano - nothing is taken`() {
        accounts()
        rig.link.up = false
        val s = steve()
        commands.convert(s, listOf("10"))
        commands.deposit(s, listOf("10"))
        assertEquals(2, s.lines.count { it.contains("not connected") })
        assertEquals("100", money())
        assertEquals("50", credits())
    }

    // ---- amounts and rounding ---------------------------------------------------------------------------------------------------

    @Test
    fun `convert - credits are charged exactly and the money is paid at the rate`() {
        accounts()
        val s = steve()
        commands.convert(s, listOf("10"))
        assertEquals("40", credits())
        assertEquals("125", money())
        assertEquals(listOf("Converted 10 credits into $25.00. New credit balance: 40"), s.plain())
        assertEquals(10.0, rig.link.requests.single().amount)
        assertEquals(EconomyOp.WITHDRAW, rig.link.requests.single().op)
    }

    @Test
    fun `deposit - money is converted at the rate and the credits are rounded down, the cost is what those credits cost`() {
        accounts()
        settings = VaultSettings(VaultMode.CONVERT, VaultDirection.BOTH, BigDecimal("3"))
        val s = steve()
        commands.deposit(s, listOf("10")) // 10 / 3 = 3.33 credits, which cost 9.99
        assertEquals("53.33", credits())
        assertEquals("90.01", money())
        assertEquals(3.33, rig.link.requests.single().amount)
        assertTrue(rig.eco.calls.single().contains("9.99"))
        assertEquals(listOf("Deposited $9.99 and received 3.33 credits. New credit balance: 53.33"), s.plain())
    }

    @Test
    fun `convert rounds the money paid down`() {
        accounts()
        settings = VaultSettings(VaultMode.CONVERT, VaultDirection.BOTH, BigDecimal("0.333"))
        commands.convert(steve(), listOf("1.5")) // 0.4995 -> 0.49
        assertEquals("100.49", money())
    }

    @Test
    fun `input with one or two decimals is accepted`() {
        accounts()
        settings = VaultSettings(VaultMode.CONVERT, VaultDirection.BOTH, BigDecimal("1"))
        val s = steve()
        commands.convert(s, listOf("0.01"))
        commands.convert(s, listOf("2.5"))
        commands.convert(s, listOf("3.25"))
        assertEquals(3, s.lines.count { it.contains("Converted") }, said(s))
    }

    // ---- Pano's answers --------------------------------------------------------------------------------------------------------

    @Test
    fun `Pano refuses a convert - the reason is told and no money moves`() {
        accounts(credits = "5")
        val low = steve()
        commands.convert(low, listOf("10"))
        assertTrue(said(low).contains("do not have enough credits"))
        rig.ledger.creditsDisabled = true
        val off = steve()
        commands.convert(off, listOf("1"))
        assertTrue(said(off).contains("Credits are switched off"))
        assertEquals("100", money())
        val nobody = FakeSender(name = "Nobody")
        rig.ledger.creditsDisabled = false
        commands.convert(nobody, listOf("1"))
        assertTrue(said(nobody).contains("account on the website"))
        rig.link.script = { _, _ -> Delivery.NotAccepted("RATE_LIMITED") }
        val busy = steve()
        commands.convert(busy, listOf("1"))
        assertTrue(said(busy).contains("busy"), said(busy))
    }

    @Test
    fun `a payout the economy refuses gives the credits back and says so`() {
        accounts()
        rig.eco.refuseDeposit = true
        val s = steve()
        commands.convert(s, listOf("10"))
        assertTrue(said(s).contains("credits were given back"))
        assertEquals("50", credits())
    }

    @Test
    fun `no answer from Pano on a convert says nothing was added and the credits come back`() {
        accounts()
        rig.link.script = { _, attempt -> if (attempt == 0) Delivery.Lost else Delivery.Normal }
        val s = steve()
        commands.convert(s, listOf("10"))
        assertTrue(said(s).contains("did not answer"))
        assertEquals("100", money())
    }

    @Test
    fun `deposit refused by Pano gives the money back and tells both`() {
        rig.eco.set("Nobody", "100")
        val s = FakeSender(name = "Nobody")
        commands.deposit(s, listOf("20"))
        assertEquals(2, s.lines.size, said(s))
        assertTrue(s.plain()[0].contains("account on the website"))
        assertTrue(s.plain()[1].contains("money was given back"))
        assertEquals("100", rig.eco.balance("Nobody").plain())
    }

    @Test
    fun `deposit refused while the refund fails says the money is being given back`() {
        rig.eco.set("Nobody", "100")
        rig.eco.failDeposits = 1
        val s = FakeSender(name = "Nobody")
        commands.deposit(s, listOf("20"))
        assertTrue(s.plain()[1].contains("being given back"))
        rig.settle()
        assertEquals("100", rig.eco.balance("Nobody").plain())
    }

    @Test
    fun `deposit with no answer says the credits arrive later and the late arrival is announced by the bridge`() {
        accounts()
        rig.link.script = { _, attempt -> if (attempt == 0) Delivery.Lost else Delivery.Normal }
        val s = steve()
        commands.deposit(s, listOf("20"))
        assertTrue(said(s).contains("credits arrive as soon as the store answers"))
        assertEquals("80", money())
        rig.settle()
        assertEquals("58", credits())
        assertEquals(1, rig.notices.size)
    }

    @Test
    fun `deposit without enough money sends nothing`() {
        accounts(money = "5")
        val s = steve()
        commands.deposit(s, listOf("20"))
        assertTrue(said(s).contains("do not have enough money"))
        assertTrue(rig.link.requests.isEmpty())
    }

    @Test
    fun `a deposit the economy refuses to take is told with the economy's reason`() {
        accounts()
        rig.eco.refuseWithdraw = true
        val s = steve()
        commands.deposit(s, listOf("20"))
        assertTrue(said(s).contains("refused to take the money (refused)"), said(s))
        assertTrue(rig.link.requests.isEmpty())
    }

    // ---- one at a time ------------------------------------------------------------------------------------------------------------

    @Test
    fun `a player can run one conversion at a time`() {
        accounts()
        rig.link.script = { _, _ -> Delivery.HeldApplied }
        val first = steve()
        commands.convert(first, listOf("10"))
        assertTrue(first.lines.isEmpty(), "still waiting for Pano")
        val second = steve()
        commands.convert(second, listOf("10"))
        assertTrue(said(second).contains("previous conversion has not finished"))
        assertEquals(1, rig.link.requests.size)
        rig.link.script = { _, _ -> Delivery.Normal }
        rig.link.releaseHeld()
        assertTrue(said(first).contains("Converted"))
        val third = steve()
        commands.convert(third, listOf("1"))
        assertTrue(said(third).contains("Converted"))
        assertEquals(2, rig.link.ids().size)
    }

    @Test
    fun `the busy mark is released after every outcome including a refusal`() {
        accounts(credits = "5")
        val s = steve()
        commands.convert(s, listOf("10"))
        commands.convert(s, listOf("1"))
        assertFalse(said(s).contains("previous conversion"))
        assertEquals(2, s.lines.size)
    }

    // ---- languages ----------------------------------------------------------------------------------------------------------------

    @Test
    fun `messages follow the player's language with English as the fallback`() {
        accounts()
        val tr = steve("tr_TR")
        commands.convert(tr, listOf("10"))
        assertTrue(said(tr).contains("kredi") && said(tr).contains("çevrildi"), said(tr))
        val ru = steve("ru_RU")
        commands.convert(ru, listOf("1"))
        assertTrue(said(ru).contains("Обменяно"), said(ru))
        val de = steve("de_DE")
        commands.convert(de, listOf("1"))
        assertTrue(said(de).contains("Converted"), said(de))
    }

    @Test
    fun `a local lang file overrides a vault message like every other message`() {
        accounts()
        java.nio.file.Files.createDirectories(dir.resolve("features").resolve("lang"))
        java.nio.file.Files.write(dir.resolve("features").resolve("lang").resolve("en-US.yml"), "vault.converted: \"&aSwapped {credits} for {money}\"\n".toByteArray())
        val s = steve()
        commands.convert(s, listOf("10"))
        assertEquals(listOf("Swapped 10 for $25.00"), s.plain())
    }
}
