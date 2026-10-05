package com.panomc.plugins.market.mc.vault

import com.panomc.plugins.market.mc.core.platform.SingleThreadScheduler
import com.panomc.plugins.market.mc.core.support.ManualScheduler
import com.panomc.plugins.market.mc.core.support.TestClock
import com.panomc.plugins.market.mc.core.support.TestLog
import com.panomc.plugins.market.mc.core.wire.EconomyOp
import com.panomc.plugins.market.mc.link.proxyOf
import com.panomc.plugins.market.mc.spigot.vault.CreditEconomy
import com.panomc.plugins.market.mc.spigot.vault.EconomyClient
import com.panomc.plugins.market.mc.spigot.vault.ProviderEconomy
import com.panomc.plugins.market.mc.spigot.vault.VaultJournal
import com.panomc.plugins.market.mc.spigot.vault.VaultOps
import net.milkbowl.vault.economy.Economy
import net.milkbowl.vault.economy.EconomyResponse
import org.bukkit.OfflinePlayer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * PROVIDER mode: the credits as the Vault `Economy` (19 section 10). Reads come from the cache and never reach the
 * network on the calling thread; writes go to the ledger; every failure is a FAILURE; bank methods are unsupported.
 */
class CreditEconomyTest {
    @TempDir
    lateinit var dir: Path

    private val log = TestLog()
    private val clock = TestClock()
    private val events = Events()
    private val ledger = FakeLedger(events)
    private val link = EconomyLink(ledger, events)
    private val client = EconomyClient(link, "1.4.0", log)
    private val inline = InlineScheduler(clock)
    private val present = CopyOnWriteArrayList<String>()
    private val uuids = HashMap<String, String>()
    private var active = true
    private var creditName = "Credits"
    private val toClose = ArrayList<SingleThreadScheduler>()

    private fun journal() = VaultJournal(dir.resolve("vault"), log, clock::now)

    private fun opsOn(scheduler: com.panomc.plugins.market.mc.core.platform.McScheduler) = VaultOps(
        client, journal(), scheduler, clock, log, ManualMainThread(true), { null }, retryBaseMs = 1_000, retryMaxMs = 8_000
    ).also { it.start() }

    private fun economy(
        scheduler: com.panomc.plugins.market.mc.core.platform.McScheduler = inline,
        timeoutMs: Long = 800,
        refreshMs: Long = 30_000
    ): ProviderEconomy {
        val ops = opsOn(scheduler)
        val core = CreditEconomy(ops, client, scheduler, clock, log, { uuids[it.lowercase()] }, { present.toList() }, timeoutMs, refreshMs)
        core.start()
        return ProviderEconomy(core, { creditName }, { active })
    }

    private fun offline(name: String?): OfflinePlayer = proxyOf(OfflinePlayer::class.java) { m, _ ->
        when (m.name) {
            "getName" -> name
            "getUniqueId" -> UUID.nameUUIDFromBytes((name ?: "x").toByteArray())
            else -> throw UnsupportedOperationException("OfflinePlayer.${m.name}")
        }
    }

    @AfterEach
    fun closeThreads() {
        toClose.forEach { it.shutdown(500) }
    }

    // ---- the Economy contract ----------------------------------------------------------------------------------------------

    @Test
    fun `the economy describes itself - no banks, two decimals, the credit name`() {
        val eco = economy()
        assertEquals("Pano Market", eco.getName())
        assertTrue(eco.isEnabled())
        assertFalse(eco.hasBankSupport())
        assertEquals(2, eco.fractionalDigits())
        assertEquals("Credits", eco.currencyNamePlural())
        assertEquals("Credits", eco.currencyNameSingular())
        assertEquals("12.5 Credits", eco.format(12.5))
        assertEquals("100 Credits", eco.format(100.0))
        creditName = "Gems"
        assertEquals("1.25 Gems", eco.format(1.25))
        active = false
        assertFalse(eco.isEnabled(), "the economy reports itself disabled once the bridge unregistered it")
    }

    @Test
    fun `bank and multi-world methods are unsupported`() {
        val eco = economy()
        val responses = listOf(
            eco.createBank("b", "Steve"), eco.createBank("b", offline("Steve")), eco.deleteBank("b"), eco.bankBalance("b"),
            eco.bankHas("b", 1.0), eco.bankWithdraw("b", 1.0), eco.bankDeposit("b", 1.0),
            eco.isBankOwner("b", "Steve"), eco.isBankOwner("b", offline("Steve")), eco.isBankMember("b", "Steve"), eco.isBankMember("b", offline("Steve"))
        )
        responses.forEach {
            assertEquals(EconomyResponse.ResponseType.NOT_IMPLEMENTED, it.type)
            assertFalse(it.transactionSuccess())
        }
        assertTrue(eco.getBanks().isEmpty())
        assertTrue(link.requests.isEmpty(), "banks never reach Pano")
    }

    @Test
    fun `withdrawPlayer and depositPlayer move credits through the ledger in every spelling`() {
        ledger.account("Steve", "100")
        val eco = economy()
        val a = eco.withdrawPlayer("Steve", 10.0)
        assertTrue(a.transactionSuccess())
        assertEquals(10.0, a.amount)
        assertEquals(90.0, a.balance)
        assertEquals(EconomyResponse.ResponseType.SUCCESS, a.type)
        assertTrue(eco.withdrawPlayer("Steve", "world", 5.0).transactionSuccess())
        assertTrue(eco.withdrawPlayer(offline("Steve"), 5.0).transactionSuccess())
        assertTrue(eco.withdrawPlayer(offline("Steve"), "world", 5.0).transactionSuccess())
        assertEquals(75.0, ledger.balance("Steve")!!.toDouble())
        assertTrue(eco.depositPlayer("Steve", 1.0).transactionSuccess())
        assertTrue(eco.depositPlayer("Steve", "world", 1.0).transactionSuccess())
        assertTrue(eco.depositPlayer(offline("Steve"), 1.0).transactionSuccess())
        assertTrue(eco.depositPlayer(offline("Steve"), "world", 1.0).transactionSuccess())
        assertEquals(79.0, ledger.balance("Steve")!!.toDouble())
        assertEquals(listOf("WITHDRAW", "WITHDRAW", "WITHDRAW", "WITHDRAW", "DEPOSIT", "DEPOSIT", "DEPOSIT", "DEPOSIT"), link.requests.filter { it.op != EconomyOp.BALANCE }.map { it.op })
    }

    @Test
    fun `an insufficient balance, an unknown account and credits switched off are FAILURE with the reason`() {
        ledger.account("Steve", "5")
        val eco = economy()
        val low = eco.withdrawPlayer("Steve", 10.0)
        assertEquals(EconomyResponse.ResponseType.FAILURE, low.type)
        assertEquals(0.0, low.amount)
        assertEquals(5.0, low.balance)
        assertTrue(low.errorMessage.contains("Insufficient"))
        assertTrue(eco.withdrawPlayer("Nobody", 1.0).errorMessage.contains("no account"))
        ledger.creditsDisabled = true
        assertTrue(eco.depositPlayer("Steve", 1.0).errorMessage.contains("switched off"))
        assertEquals("5", ledger.balance("Steve")!!.stripTrailingZeros().toPlainString())
    }

    @Test
    fun `disconnected is FAILURE and nothing is sent`() {
        ledger.account("Steve", "100")
        val eco = economy()
        link.up = false
        val r = eco.withdrawPlayer("Steve", 10.0)
        assertEquals(EconomyResponse.ResponseType.FAILURE, r.type)
        assertTrue(r.errorMessage.contains("not connected"))
        assertTrue(link.requests.isEmpty())
    }

    @Test
    fun `negative, not-a-number and infinite amounts are FAILURE without a request, zero is a no-op success`() {
        ledger.account("Steve", "100")
        val eco = economy()
        assertFalse(eco.withdrawPlayer("Steve", -1.0).transactionSuccess())
        assertFalse(eco.depositPlayer("Steve", -1.0).transactionSuccess())
        assertFalse(eco.withdrawPlayer("Steve", Double.NaN).transactionSuccess())
        assertFalse(eco.depositPlayer("Steve", Double.POSITIVE_INFINITY).transactionSuccess())
        assertTrue(eco.withdrawPlayer("Steve", 0.0).transactionSuccess())
        assertTrue(eco.depositPlayer("Steve", 0.0).transactionSuccess())
        assertTrue(link.of(EconomyOp.WITHDRAW).isEmpty() && link.of(EconomyOp.DEPOSIT).isEmpty())
    }

    @Test
    fun `amounts are kept to two decimals and rounding never favours the player`() {
        ledger.account("Steve", "100")
        val eco = economy()
        val w = eco.withdrawPlayer("Steve", 0.001)
        assertEquals(0.01, w.amount, "a withdrawal is rounded up")
        assertEquals(0.01, link.of(EconomyOp.WITHDRAW).single().amount)
        val d = eco.depositPlayer("Steve", 1.999)
        assertEquals(1.99, d.amount, "a deposit is rounded down")
        val none = eco.depositPlayer("Steve", 0.004)
        assertTrue(none.transactionSuccess())
        assertEquals(0.0, none.amount)
        assertEquals(1, link.of(EconomyOp.DEPOSIT).size, "a deposit that rounds to nothing is not sent")
    }

    @Test
    fun `a player without a name is a FAILURE, not a NullPointerException`() {
        val eco = economy()
        assertFalse(eco.withdrawPlayer(offline(null), 1.0).transactionSuccess())
        assertFalse(eco.depositPlayer(null as String?, 1.0).transactionSuccess())
        assertFalse(eco.hasAccount(offline(null)))
        assertEquals(0.0, eco.getBalance(offline(null)))
        assertFalse(eco.has(offline(null), 1.0))
        assertFalse(eco.createPlayerAccount(offline(null)))
        assertTrue(link.requests.isEmpty())
    }

    @Test
    fun `the uuid of a present player is sent as a hint and the account is never created from here`() {
        ledger.account("Steve", "100")
        uuids["steve"] = "aaaaaaaa-0000-0000-0000-000000000001"
        val eco = economy()
        eco.withdrawPlayer("Steve", 1.0)
        assertEquals("aaaaaaaa-0000-0000-0000-000000000001", link.requests.first { it.op == EconomyOp.WITHDRAW }.player.uuid)
        assertFalse(eco.createPlayerAccount("Nobody"))
        assertFalse(eco.createPlayerAccount("Nobody", "world"))
    }

    // ---- the cache ---------------------------------------------------------------------------------------------------------

    @Test
    fun `reads answer from the cache and never touch the network on the calling thread`() {
        ledger.account("Steve", "100")
        val manual = ManualScheduler(clock)
        val eco = economy(scheduler = manual)
        // not loaded yet: fail closed, and the load is only queued
        assertEquals(0.0, eco.getBalance("Steve"))
        assertFalse(eco.hasAccount("Steve"))
        assertFalse(eco.has("Steve", 1.0))
        assertTrue(link.requests.isEmpty(), "no request was made by the read calls themselves")
        manual.runUntilIdle()
        assertEquals(1, link.of(EconomyOp.BALANCE).size, "one load for all three reads")
        val before = link.requests.size
        assertEquals(100.0, eco.getBalance("Steve"))
        assertEquals(100.0, eco.getBalance("Steve", "world"))
        assertEquals(100.0, eco.getBalance(offline("Steve")))
        assertTrue(eco.hasAccount("Steve"))
        assertTrue(eco.hasAccount(offline("Steve")))
        assertTrue(eco.has("Steve", 100.0))
        assertFalse(eco.has("Steve", 100.01))
        assertFalse(eco.has("Steve", -1.0))
        assertEquals(before, link.requests.size, "answered from memory")
    }

    @Test
    fun `an unknown account is cached as no account`() {
        val manual = ManualScheduler(clock)
        val eco = economy(scheduler = manual)
        eco.getBalance("Nobody")
        manual.runUntilIdle()
        assertFalse(eco.hasAccount("Nobody"))
        assertEquals(0.0, eco.getBalance("Nobody"))
    }

    @Test
    fun `the balance is loaded at the authenticated join and refreshed after every operation`() {
        ledger.account("Steve", "100")
        val manual = ManualScheduler(clock)
        val ops = opsOn(manual)
        val core = CreditEconomy(ops, client, manual, clock, log, { null }, { present.toList() })
        core.start()
        core.onPresent("Steve")
        manual.runUntilIdle()
        assertEquals(100.0, core.balance("Steve"))
        // the write blocks on the calling thread, so it needs a scheduler that runs by itself
        val eco = economy()
        eco.withdrawPlayer("Steve", 30.0)
        assertEquals(70.0, eco.getBalance("Steve"), "the balance of the answer is the new cached value")
    }

    @Test
    fun `players who are present are refreshed every 30 seconds and the others are left alone`() {
        ledger.account("Steve", "100")
        ledger.account("Alex", "50")
        val manual = ManualScheduler(clock)
        val ops = opsOn(manual)
        val core = CreditEconomy(ops, client, manual, clock, log, { null }, { present.toList() })
        core.start()
        present.add("Steve")
        manual.advance(30_000)
        manual.runUntilIdle()
        assertEquals(listOf("Steve"), link.of(EconomyOp.BALANCE).map { it.player.username })
        ledger.account("Steve", "90")
        manual.advance(30_000)
        manual.runUntilIdle()
        assertEquals(90.0, core.balance("Steve"))
        assertEquals(2, link.of(EconomyOp.BALANCE).size)
        core.stop()
        manual.advance(120_000)
        manual.runUntilIdle()
        assertEquals(2, link.of(EconomyOp.BALANCE).size, "stop ends the refresh")
    }

    @Test
    fun `a stale entry that is read starts a background refresh and answers with what it has`() {
        ledger.account("Steve", "100")
        val manual = ManualScheduler(clock)
        val ops = opsOn(manual)
        val core = CreditEconomy(ops, client, manual, clock, log, { null }, { emptyList() })
        core.start()
        core.onPresent("Steve")
        manual.runUntilIdle()
        ledger.account("Steve", "80")
        clock.advance(31_000)
        assertEquals(100.0, core.balance("Steve"), "the stale value answers at once")
        manual.runUntilIdle()
        assertEquals(80.0, core.balance("Steve"))
    }

    @Test
    fun `entries of players who left are dropped after a while`() {
        ledger.account("Steve", "100")
        val manual = ManualScheduler(clock)
        val ops = opsOn(manual)
        val core = CreditEconomy(ops, client, manual, clock, log, { null }, { emptyList() })
        core.start()
        core.onPresent("Steve")
        manual.runUntilIdle()
        assertEquals(100.0, core.balance("Steve"))
        clock.advance(11 * 60_000)
        core.refreshPresent()
        link.up = false // a fresh load can no longer repopulate it
        manual.runUntilIdle()
        assertEquals(0.0, core.balance("Steve"))
    }

    @Test
    fun `no refresh or load happens while the connection is down`() {
        ledger.account("Steve", "100")
        val manual = ManualScheduler(clock)
        val ops = opsOn(manual)
        val core = CreditEconomy(ops, client, manual, clock, log, { null }, { listOf("Steve") })
        core.start()
        link.up = false
        manual.advance(30_000)
        manual.runUntilIdle()
        assertTrue(link.requests.isEmpty())
    }

    // ---- latency and threads -----------------------------------------------------------------------------------------------

    @Test
    fun `a write is sent from the bridge thread, not from the calling thread, and the call blocks no longer than the budget`() {
        ledger.account("Steve", "100")
        val engine = SingleThreadScheduler("vault-test-engine", log).also { toClose.add(it) }
        val eco = economy(scheduler = engine, timeoutMs = 150)
        val ok = eco.withdrawPlayer("Steve", 1.0)
        assertTrue(ok.transactionSuccess())
        assertEquals("vault-test-engine", link.threads.last(), "the request left from the bridge thread")
        assertNotEquals(Thread.currentThread().name, link.threads.last())

        link.script = { _, _ -> Delivery.HeldApplied }
        val began = System.nanoTime()
        val slow = eco.withdrawPlayer("Steve", 1.0)
        val tookMs = (System.nanoTime() - began) / 1_000_000
        assertFalse(slow.transactionSuccess())
        assertTrue(slow.errorMessage.contains("did not answer"))
        assertTrue(tookMs in 120..1_500, "blocked about the budget ($tookMs ms)")
    }

    @Test
    fun `a timed out withdrawal is reverted when the late success arrives, so the caller's FAILURE was true`() {
        ledger.account("Steve", "100")
        val eco = economy(timeoutMs = 80)
        link.script = { _, _ -> Delivery.HeldApplied }
        val r = eco.withdrawPlayer("Steve", 10.0)
        assertFalse(r.transactionSuccess())
        assertEquals(90.0, ledger.balance("Steve")!!.toDouble(), "Pano did apply it")
        link.script = { _, _ -> Delivery.Normal }
        link.releaseHeld()
        assertEquals(100.0, ledger.balance("Steve")!!.toDouble())
        assertTrue(link.requests.any { it.operationId.endsWith(":undo") && it.op == EconomyOp.DEPOSIT })
    }
}
