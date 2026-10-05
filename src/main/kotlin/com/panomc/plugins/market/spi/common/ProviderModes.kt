package com.panomc.plugins.market.spi.common

/** How a provider reports test mode (shared by payment and shipping providers). New values only at the end. */
enum class TestModeSupport {
    /** Uses `ProviderContext.testMode` (method flag or store-wide test mode). */
    FLAG,

    /** Derived from the configured keys: the provider states what they are. */
    DERIVED,

    /** No test mode; never a test method. */
    NONE
}

/** How the gateway learns market's webhook URL (shared by payment and shipping providers). New values only at the end. */
enum class WebhookSetup { PER_PAYMENT, MANUAL_URL, AUTO_REGISTER, NONE }
