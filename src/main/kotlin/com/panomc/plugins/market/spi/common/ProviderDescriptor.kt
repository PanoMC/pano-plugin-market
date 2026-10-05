package com.panomc.plugins.market.spi.common

/** What a provider shows about itself in the panel and at checkout. Optional data are `var`s (02 section 9 rule 2). */
class ProviderDescriptor(val displayName: LocalizedText, val description: LocalizedText, val icon: String) {
    /** Served by market: `GET /api/market/payment-providers/:id/logo`. */
    var logo: ProviderAsset? = null

    /** `"#635bff"`. */
    var color: String? = null

    /** `"tr"`, `"global"` or an ISO country: panel filter. */
    var region: String = "global"
    var docsUrl: String? = null
    var verification: Verification = Verification.UNVERIFIED

    /** One line under the method at checkout. */
    var checkoutHint: LocalizedText? = null

    /** Links a gateway obliges the store to show. */
    var storefrontNotices: List<StorefrontNotice> = emptyList()
}

/** `image/png`, `image/svg+xml` or `image/webp`, at most 64 KB. */
class ProviderAsset(val contentType: String, val bytes: ByteArray) {
    init {
        require(contentType in ALLOWED_TYPES) { "Unsupported logo type '$contentType'" }
        require(bytes.size <= MAX_BYTES) { "Logo is ${bytes.size} bytes, the limit is $MAX_BYTES" }
    }

    companion object {
        const val MAX_BYTES = 64 * 1024
        val ALLOWED_TYPES = setOf("image/png", "image/svg+xml", "image/webp")
    }
}

class StorefrontNotice(val label: LocalizedText, val url: String)

/** Owner rule: everything that is not live-tested is labelled. New values only at the end. */
enum class Verification { UNVERIFIED, DOC_SAMPLES, SANDBOX, LIVE }
