package com.panomc.plugins.market.core.webhook

/** Admin-defined extra headers of an endpoint (08 section 15.2, 11 section 7.3). */
object WebhookHeaders {
    const val MAX_HEADERS = 10
    const val MAX_VALUE_LENGTH = 512

    private val NAME = Regex("^[A-Za-z0-9\\-]{1,64}$")
    private val VALUE = Regex("^[\\u0020-\\u007e]{1,$MAX_VALUE_LENGTH}$")
    private val FORBIDDEN = setOf("host", "content-length", "content-type", "transfer-encoding", "connection", "user-agent")

    fun isAllowedName(name: String): Boolean =
        NAME.matches(name) && name.lowercase() !in FORBIDDEN && !name.startsWith("X-Pano-", ignoreCase = true)

    fun isAllowedValue(value: String): Boolean = VALUE.matches(value)

    /** Field errors keyed `headers.<name>` (or `headers` for the count), empty when [headers] is acceptable. */
    fun validate(headers: Map<String, String>): Map<String, String> {
        val errors = LinkedHashMap<String, String>()
        if (headers.size > MAX_HEADERS) errors["headers"] = "TOO_MANY"
        val seen = HashSet<String>()
        for ((name, value) in headers) {
            if (!isAllowedName(name)) errors["headers.$name"] = "INVALID_NAME"
            else if (!seen.add(name.lowercase())) errors["headers.$name"] = "DUPLICATE"
            else if (!isAllowedValue(value)) errors["headers.$name"] = "INVALID_VALUE"
        }
        return errors
    }

    /**
     * What is actually put on a request: the entries that pass the rules, in order. A stored entry that no longer
     * passes (a rule tightened later) is dropped rather than sent; `X-Pano-*`, `Host` and the framing headers can
     * therefore never be overridden through the stored JSON.
     */
    fun sendable(headers: Map<String, String>): List<Pair<String, String>> =
        headers.entries.filter { isAllowedName(it.key) && isAllowedValue(it.value) }.take(MAX_HEADERS).map { it.key to it.value }
}
