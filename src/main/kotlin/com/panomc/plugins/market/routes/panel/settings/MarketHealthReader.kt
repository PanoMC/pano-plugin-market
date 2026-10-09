package com.panomc.plugins.market.routes.panel.settings

import com.panomc.plugins.market.core.time.Clock
import com.panomc.plugins.market.job.MarketScheduler
import com.panomc.plugins.market.provider.ProviderAvailability
import com.panomc.plugins.market.provider.ProviderListing
import com.panomc.plugins.market.service.CreditCheckResult
import com.panomc.plugins.market.service.ServerView
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

/**
 * The live half of `GET /health` (04 section 8): what the runtime state and the schema verification cannot say. Every part is read on its own and a part that
 * fails (a table the verifier already reported missing, a provider that throws) is logged and answers its empty value, so the page still answers while the store
 * is `DEGRADED`; the page is the tool to look at a store in trouble.
 */
class HealthExtras(
    val jobs: List<Map<String, Any?>> = emptyList(),
    val queues: Map<String, Long> = ZERO_QUEUES,
    val providers: List<Map<String, Any?>> = emptyList(),
    val servers: List<Map<String, Any?>> = emptyList(),
    val credits: Map<String, Any?> = mapOf("ok" to true, "checkedAt" to null, "problems" to emptyList<String>()),
    val lockedSubjects: Long = 0,
    val rejectedEventsLastHour: Long = 0
) {
    companion object {
        val ZERO_QUEUES: Map<String, Long> = linkedMapOf(
            "deliveriesPending" to 0L, "deliveriesFailed" to 0L, "mailsPending" to 0L, "webhooksPending" to 0L, "deferredEvents" to 0L, "failedEvents" to 0L
        )

        val EMPTY = HealthExtras()
    }
}

class MarketHealthReader(
    private val clock: Clock,
    private val prefix: () -> String,
    private val client: suspend () -> SqlClient,
    private val jobStats: () -> List<MarketScheduler.JobStats>,
    private val providerListings: () -> List<ProviderListing>,
    private val serverViews: suspend () -> List<ServerView>,
    private val lastCredits: () -> CreditCheckResult?,
    private val recheckCredits: suspend () -> CreditCheckResult?
) {
    private fun table(name: String) = "`${prefix()}$name`"

    /** [recheck] `credits`: run the full ledger check now instead of reporting the last one. */
    suspend fun read(recheck: String?): HealthExtras {
        val sql = part("client") { client() }
        val queues = if (sql == null) HealthExtras.ZERO_QUEUES else part("queues") { queues(sql) } ?: HealthExtras.ZERO_QUEUES

        return HealthExtras(
            jobs = part("jobs") { jobs() } ?: emptyList(),
            queues = queues,
            providers = part("providers") { providerStates() } ?: emptyList(),
            servers = part("servers") { serverStates() } ?: emptyList(),
            credits = credits(recheck == "credits"),
            lockedSubjects = sql?.let { part("lockedSubjects") { count(it, "market_throttle", "`lockedUntil` IS NOT NULL AND `lockedUntil` > ?", clock.now()) } } ?: 0,
            rejectedEventsLastHour = sql?.let {
                part("rejectedEvents") { count(it, "market_payment_event", "`status` = 'REJECTED' AND `createdAt` > ?", clock.now() - HOUR_MS) }
            } ?: 0
        )
    }

    private suspend fun <T> part(name: String, body: suspend () -> T): T? = try {
        body()
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        logger.warn("health part {} could not be read: {}", name, t.toString())

        null
    }

    private fun jobs(): List<Map<String, Any?>> {
        val now = clock.now()

        return jobStats().map {
            val last = it.lastStartedAt

            linkedMapOf(
                "name" to it.name,
                "lastRunAt" to last,
                // seconds past the slot the job should have started in (0 while it is on time, null before its first run)
                "lagSeconds" to last?.let { at -> maxOf(0L, now - (at + it.everyMs)) / 1000 },
                "lastError" to it.lastError
            )
        }
    }

    private fun providerStates(): List<Map<String, Any?>> = providerListings().filter { it.id != null }.map {
        // the registry's MISSING is the panel's UNAVAILABLE (13 section 4.2)
        mapOf("id" to it.id, "state" to if (it.state.availability == ProviderAvailability.MISSING) "UNAVAILABLE" else it.state.availability.name)
    }.sortedBy { it["id"] as String }

    private suspend fun serverStates(): List<Map<String, Any?>> = serverViews().map {
        mapOf("id" to it.id, "marketState" to it.marketState.name, "waitingDeliveries" to it.waitingDeliveries)
    }

    /**
     * The four counters the E2E drain waits on are the number of rows that still have work to do: deliveries that are not terminal, mails and webhook rows (the market's rows in core's `webhook_delivery`) that
     * are not sent (a `FAILED` webhook row is retried, `DEAD` is not), payment events that are `DEFERRED`; `failed` ones are the rows that need a human.
     */
    private suspend fun queues(sql: SqlClient): Map<String, Long> = linkedMapOf(
        "deliveriesPending" to count(sql, "market_delivery", "`status` IN ('PENDING', 'SCHEDULED', 'WAITING_SERVER', 'WAITING_PLAYER', 'SENDING', 'SENT', 'QUEUED')"),
        "deliveriesFailed" to count(sql, "market_delivery", "`status` = 'FAILED'"),
        "mailsPending" to count(sql, "market_mail_outbox", "`status` IN ('PENDING', 'SENDING')"),
        "webhooksPending" to count(sql, "webhook_delivery", "`source` = 'market' AND `status` IN ('PENDING', 'SENDING', 'FAILED')"),
        "deferredEvents" to count(sql, "market_payment_event", "`status` = 'DEFERRED'"),
        "failedEvents" to count(sql, "market_payment_event", "`status` = 'FAILED'")
    )

    private suspend fun count(sql: SqlClient, name: String, where: String, vararg args: Any?): Long =
        sql.preparedQuery("SELECT COUNT(*) AS n FROM ${table(name)} WHERE $where").execute(Tuple.from(args.toList())).coAwait().first().getLong("n")

    private suspend fun credits(recheck: Boolean): Map<String, Any?> {
        val result = try {
            if (recheck) recheckCredits() else lastCredits()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            logger.warn("the credit check failed: {}", t.toString())

            return mapOf("ok" to false, "checkedAt" to null, "problems" to listOf("CHECK_FAILED ${t.javaClass.simpleName}"))
        } ?: return HealthExtras.EMPTY.credits

        return mapOf("ok" to result.ok, "checkedAt" to result.checkedAt, "problems" to result.problems.map { it.toString() })
    }

    companion object {
        private const val HOUR_MS = 3_600_000L
        private val logger = LoggerFactory.getLogger(MarketHealthReader::class.java)
    }
}
