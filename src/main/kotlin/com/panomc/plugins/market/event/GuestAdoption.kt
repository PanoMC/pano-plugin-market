package com.panomc.plugins.market.event

import com.panomc.plugins.market.db.tx.MarketDb
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.util.Locale

/** What the platform says about a registered account: its [username] as stored, its e-mail and whether the e-mail was verified. */
class AccountFacts(val id: Long, val username: String, val email: String?, val emailVerified: Boolean)

/** The platform's accounts behind the guest adoption (the production binding reads `user`; tests use a map). */
fun interface AccountLookup {
    /** The account with this [username] (any case), or `null` when nobody registered it. */
    suspend fun byUsername(username: String, sqlClient: SqlClient): AccountFacts?
}

/**
 * Guest -> account adoption (01 section 5.5, 11 section 5.3): when somebody registers the Minecraft name a guest bought for, a job rewrites the keys
 * `g:<name>` to `u:<id>`.
 *
 * - **always** (the goods and the limits belong to the player name): `market_entitlement.ownerKey`, `market_order.recipientKey`,
 *   `market_redemption.buyerKey` and `recipientKey`;
 * - **only when the order e-mail equals the e-mail of the new account (case-insensitive) and that e-mail is verified**: `market_order.userId` and
 *   `buyerKey` of the orders the name paid for, `recipientUserId` of the orders it received. Every other guest order stays a guest order, reachable by its
 *   token only. Registering a name therefore never exposes the previous guest's e-mail, address or invoice.
 *
 * [run] looks at the names that carry a `g:` key in alphabetical rounds of [limit] names (the cursor wraps), so names that nobody registered cannot starve
 * the others. One transaction per name; a name that fails (a unique key clash of `uq_buyer_idem`) is logged and tried again in a later round.
 */
class GuestAdoption(
    private val db: MarketDb,
    private val prefix: () -> String,
    private val client: suspend () -> SqlClient,
    private val accounts: AccountLookup
) {
    @Volatile
    private var cursor = ""

    private fun t(name: String) = "`${prefix()}$name`"

    /** Names that have a guest key somewhere, above the cursor, at most [limit]. */
    private suspend fun names(limit: Int): List<String> {
        val c = client()
        val found = c.preparedQuery(
            "SELECT n FROM (" +
                "SELECT SUBSTRING(`ownerKey`, 3) AS n FROM ${t("market_entitlement")} WHERE `ownerKey` LIKE 'g:%' " +
                "UNION SELECT SUBSTRING(`recipientKey`, 3) FROM ${t("market_order")} WHERE `recipientKey` LIKE 'g:%' " +
                "UNION SELECT SUBSTRING(`buyerKey`, 3) FROM ${t("market_order")} WHERE `buyerKey` LIKE 'g:%' " +
                "UNION SELECT SUBSTRING(`buyerKey`, 3) FROM ${t("market_redemption")} WHERE `buyerKey` LIKE 'g:%' " +
                "UNION SELECT SUBSTRING(`recipientKey`, 3) FROM ${t("market_redemption")} WHERE `recipientKey` LIKE 'g:%'" +
                ") names WHERE n > ? ORDER BY n LIMIT $limit"
        ).execute(Tuple.of(cursor)).coAwait().map { it.getString("n") }

        cursor = if (found.size < limit) "" else found.last()

        return found
    }

    /** One round; returns the number of names that were adopted by an account. */
    suspend fun run(limit: Int = DEFAULT_ROUND): Int {
        var adopted = 0

        for (name in names(limit)) {
            try {
                val account = accounts.byUsername(name, client()) ?: continue

                if (account.username.lowercase(Locale.ROOT) != name) continue

                db.tx { c -> adopt(c, name, account) }
                adopted++
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                logger.warn("the guest orders of {} could not be adopted, the next round tries again: {}", name, t.toString())
            }
        }

        return adopted
    }

    /** The rewrite for one [name] and its [account], on the connection of the caller's transaction. */
    internal suspend fun adopt(c: SqlConnection, name: String, account: AccountFacts) {
        val old = "g:$name"
        val new = "u:${account.id}"

        suspend fun exec(statement: String, vararg args: Any?) = c.preparedQuery(statement).execute(Tuple.from(args.toList())).coAwait().rowCount()

        exec("UPDATE ${t("market_entitlement")} SET `ownerKey` = ? WHERE `ownerKey` = ?", new, old)
        exec("UPDATE ${t("market_order")} SET `recipientKey` = ? WHERE `recipientKey` = ?", new, old)
        exec("UPDATE ${t("market_redemption")} SET `buyerKey` = ? WHERE `buyerKey` = ?", new, old)
        exec("UPDATE ${t("market_redemption")} SET `recipientKey` = ? WHERE `recipientKey` = ?", new, old)

        val email = account.email?.trim()?.takeIf { it.isNotEmpty() }

        if (!account.emailVerified || email == null) return

        // the orders this name paid for, and the gifts it received, whose e-mail is the verified one of the account
        exec("UPDATE ${t("market_order")} SET `userId` = ?, `buyerKey` = ? WHERE `buyerKey` = ? AND `userId` IS NULL AND LOWER(`email`) = ?", account.id, new, old, email.lowercase(Locale.ROOT))
        exec(
            "UPDATE ${t("market_order")} SET `recipientUserId` = ? WHERE `recipientKey` = ? AND `recipientUserId` IS NULL AND LOWER(`email`) = ?",
            account.id, new, email.lowercase(Locale.ROOT)
        )
    }

    companion object {
        const val DEFAULT_ROUND = 200

        private val logger = LoggerFactory.getLogger(GuestAdoption::class.java)
    }
}
