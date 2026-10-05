package com.panomc.plugins.market.core.delivery

import com.panomc.plugins.market.spi.common.SafeRegex

/**
 * Renders a console command template (08 section 3.3, 11 section 6.4): every substituted value must pass the validator
 * of its kind, the result must be a single line without a leading `/`, at most 1024 UTF-8 bytes. Substitution is single
 * pass (substituted text is never scanned again). A failure is a [Result.Fail] with code `RENDER_ERROR`; the planner
 * stores the delivery row as `FAILED` and the order transaction is never aborted by it.
 *
 * `{uuid}` is left literally in the command: the Minecraft component binds it to the player's UUID on that server.
 */
object CommandRenderer {
    const val RENDER_ERROR = "RENDER_ERROR"
    const val MAX_BYTES = 1024

    const val EMPTY_VARIABLE = "EMPTY_VARIABLE"
    const val INVALID_VALUE = "INVALID_VALUE"
    const val COMMAND_TOO_LONG = "COMMAND_TOO_LONG"
    const val FIELD_NOT_USABLE = "FIELD_NOT_USABLE"
    const val FORBIDDEN_VARIABLE = "FORBIDDEN_VARIABLE"
    const val INVALID_COMMAND = "INVALID_COMMAND"

    sealed class Result {
        class Ok(val command: String) : Result()

        /** [variable] is the token name (`null` for a defect of the whole command), [reason] one of the constants above. */
        class Fail(val variable: String?, val reason: String) : Result() {
            val code: String get() = RENDER_ERROR

            /** The text for `market_delivery.lastError`: `<variable>: <reason>`. */
            val message: String get() = if (variable == null) reason else "$variable: $reason"
        }
    }

    private val BUYER_USERNAME = Regex("[A-Za-z0-9_]{3,16}")
    private val ADMIN_USERNAME = Regex("[A-Za-z0-9_.*]{1,32}")
    private val ALNUM = Regex("[A-Za-z0-9]")
    private val NUMBER = Regex("-?[0-9]{1,18}")
    private val DECIMAL = Regex("[0-9]{1,16}(\\.[0-9]{1,2})?")
    private val IDENTIFIER = Regex("[A-Za-z0-9_.:\\-]{1,64}")
    private val EMAIL = Regex("[A-Za-z0-9._%+\\-]{1,64}@[A-Za-z0-9.\\-]{1,190}\\.[A-Za-z]{2,24}")
    private val DISCORD_ID = Regex("[0-9]{17,20}")
    private val FREE_TEXT = Regex("[\\p{L}\\p{N} _.,:+#()\\[\\]\\-]{0,128}")

    /** 11 section 6.4 rule 4: a `TEXT` field without its own pattern may not contain a space (a space adds arguments). */
    private const val FIELD_TEXT_DEFAULT = "^[\\p{L}\\p{N}_.#+-]{1,64}$"

    /** Username rule used for the player of a delivery as well (`INVALID_PLAYER`): the admin alphabet, one alphanumeric. */
    fun isAdminUsername(name: String): Boolean = ADMIN_USERNAME.matches(name) && ALNUM.containsMatchIn(name)

    fun isBuyerUsername(name: String): Boolean = BUYER_USERNAME.matches(name) && ALNUM.containsMatchIn(name)

    fun render(template: String, ctx: VariableContext): Result {
        val out = StringBuilder(template.length + 32)
        var last = 0

        for (match in VariableContext.TOKEN.findAll(template)) {
            val name = match.groupValues[1]
            val value = ctx.resolve(name) ?: continue

            out.append(template, last, match.range.first)
            last = match.range.last + 1

            if (value.kind == ValueKind.LATE_BOUND) {
                out.append("{uuid}")
                continue
            }

            when (val checked = substitute(name, value, match.groups[2]?.value)) {
                is Checked.Bad -> return checked.fail
                is Checked.Good -> out.append(checked.text)
            }
        }

        out.append(template, last, template.length)

        val command = out.toString().trim()

        if (command.isEmpty()) return Result.Fail(null, INVALID_COMMAND)
        if (command.startsWith("/")) return Result.Fail(null, INVALID_COMMAND)
        if (command.any { Character.isISOControl(it) }) return Result.Fail(null, INVALID_COMMAND)
        if (command.toByteArray(Charsets.UTF_8).size > MAX_BYTES) return Result.Fail(null, COMMAND_TOO_LONG)

        return Result.Ok(command)
    }

    private sealed class Checked {
        class Good(val text: String) : Checked()
        class Bad(val fail: Result.Fail) : Checked()
    }

    private fun substitute(name: String, value: VariableValue, default: String?): Checked {
        fun bad(reason: String) = Checked.Bad(Result.Fail(name, reason))

        if (value.kind == ValueKind.FORBIDDEN) return bad(FORBIDDEN_VARIABLE)
        if (!value.usableInCommands) return bad(FIELD_NOT_USABLE)

        // Free text loses control characters first (08 section 3.3); everything else is validated as it stands.
        val raw = if (value.kind == ValueKind.FREE_TEXT || value.kind == ValueKind.FIELD_TEXT) value.raw.filterNot { Character.isISOControl(it) } else value.raw

        if (raw.isBlank()) {
            val fallback = default?.takeIf { it.isNotBlank() } ?: return bad(EMPTY_VARIABLE)

            return Checked.Good(fallback)
        }

        if (raw.startsWith("@")) return bad(INVALID_VALUE)

        val valid = when (value.kind) {
            ValueKind.USERNAME -> {
                val rule = if (value.origin == UsernameOrigin.ADMIN) ADMIN_USERNAME else BUYER_USERNAME

                rule.matches(raw) && ALNUM.containsMatchIn(raw)
            }

            ValueKind.NUMBER -> NUMBER.matches(raw)
            ValueKind.DECIMAL -> DECIMAL.matches(raw)
            ValueKind.IDENTIFIER -> IDENTIFIER.matches(raw)
            ValueKind.EMAIL -> EMAIL.matches(raw)
            ValueKind.DISCORD_ID -> DISCORD_ID.matches(raw)
            ValueKind.FREE_TEXT -> FREE_TEXT.matches(raw)
            ValueKind.FIELD_TEXT -> FREE_TEXT.matches(raw) && SafeRegex.matches(value.pattern ?: FIELD_TEXT_DEFAULT, raw)
            ValueKind.FORBIDDEN, ValueKind.LATE_BOUND -> false
        }

        return if (valid) Checked.Good(raw) else bad(INVALID_VALUE)
    }
}
