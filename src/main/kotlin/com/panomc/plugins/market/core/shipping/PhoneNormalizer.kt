package com.panomc.plugins.market.core.shipping

/** Phone numbers to E.164 (10 section 3.2). */
object PhoneNormalizer {
    private val E164 = Regex("^\\+[1-9][0-9]{7,14}$")

    fun isE164(value: String): Boolean = E164.matches(value)

    /**
     * 1. drop everything except digits and a leading `+`; a leading `00` becomes `+`;
     * 2. starting with `+`: keep;
     * 3. else strip one leading `0` (trunk prefix) and prepend `+<calling code of country>`; an unknown or missing country is invalid;
     * 4. valid iff `^\+[1-9][0-9]{7,14}$`.
     *
     * Returns `null` when the result is not a valid number.
     */
    fun toE164(raw: String?, country: String?): String? {
        if (raw == null) return null

        val trimmed = raw.trim()
        val plus = trimmed.startsWith("+")
        val digits = trimmed.filter { it in '0'..'9' }
        if (digits.isEmpty()) return null

        val candidate = when {
            plus -> "+$digits"
            digits.startsWith("00") -> "+" + digits.substring(2)
            else -> {
                val code = CallingCodes.of(country?.trim()?.uppercase()) ?: return null
                "+" + code + digits.removePrefix("0")
            }
        }

        return candidate.takeIf { isE164(it) }
    }
}
