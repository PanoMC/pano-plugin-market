package com.panomc.plugins.market.routes.api.payment

import com.panomc.plugins.market.config.MarketConfig
import com.panomc.plugins.market.core.abuse.Redactor
import com.panomc.plugins.market.core.payment.ProviderMoneyPolicy
import com.panomc.plugins.market.db.dao.MarketPaymentMethodDao
import com.panomc.plugins.market.provider.ProviderAvailability
import com.panomc.plugins.market.provider.ProviderKind
import com.panomc.plugins.market.provider.ProviderLookup
import com.panomc.plugins.market.provider.SecretCipher
import com.panomc.plugins.market.provider.SettingsCodec
import com.panomc.plugins.market.service.PaymentContexts
import com.panomc.plugins.market.spi.common.TestModeSupport
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.SqlClient
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

/**
 * [InboundProviders] on the provider registry (02 section 7.1): a registered, compatible provider receives every request whatever its method row
 * says (`ACTIVE`, `DISABLED`, `NOT_CONFIGURED`, or no row yet), with the settings as they are stored, possibly incomplete (a gateway's validation
 * handshake arrives before the settings are). A provider that is not registered is `Unavailable` when it is known to market (a method row, an
 * incompatible / shadowed plugin, or the token of one of its attempts) and `Unknown` otherwise.
 *
 * [stateValues] adds the values of the provider's `market_provider_state` to what the redactor removes from stored rows (02 section 3).
 */
class RegistryInboundProviders(
    private val lookup: ProviderLookup,
    private val methods: MarketPaymentMethodDao,
    private val cipher: SecretCipher,
    private val contexts: PaymentContexts,
    private val config: () -> MarketConfig,
    private val client: suspend () -> SqlClient,
    private val stateValues: suspend (providerId: String) -> Set<String> = { emptySet() }
) : InboundProviders {
    override suspend fun resolve(providerId: String, knownAttempt: Boolean): ProviderAccess {
        val resolved = lookup.payment(providerId)

        if (resolved == null) {
            val state = lookup.state(ProviderKind.PAYMENT, providerId)
            val known = knownAttempt || state.availability != ProviderAvailability.MISSING || methods.getByMethodId(providerId, client()) != null

            return if (known) ProviderAccess.Unavailable else ProviderAccess.Unknown
        }

        val provider = resolved.provider
        val row = methods.getByMethodId(providerId, client())
        val codec = SettingsCodec(provider.settingsSchema(), cipher)
        val stored = row?.settings?.let { runCatching { JsonObject(it) }.getOrNull() }
        val settings = codec.decrypt(stored)
        val c = config()
        var testMode = c.testMode || row?.testMode == true
        var policy = ProviderMoneyPolicy()

        try {
            val caps = provider.capabilities(settings)

            policy = ProviderMoneyPolicy(caps.buyerMayPayMore, caps.priceAuthority)
            testMode = when (caps.testMode) {
                TestModeSupport.FLAG -> testMode
                TestModeSupport.DERIVED -> caps.derivedTestMode == true
                TestModeSupport.NONE -> false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // capabilities is a pure function of the settings; a handshake before the settings are complete must still reach the provider
            logger.warn("provider {} threw while it described itself, the default money policy applies to its inbound request: {}", providerId, e.javaClass.simpleName)
        }

        val state = try {
            stateValues(providerId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptySet()
        }
        val secrets = settings.valuesOf(provider.settingsSchema().secretKeys) + state
        val effectiveTestMode = testMode

        return ProviderAccess.Ready(provider, policy, Redactor(secrets)) { forAttempt -> contexts.create(provider, settings, forAttempt ?: effectiveTestMode) }
    }

    private companion object {
        val logger = LoggerFactory.getLogger(RegistryInboundProviders::class.java)
    }
}
