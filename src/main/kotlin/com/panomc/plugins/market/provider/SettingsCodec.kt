package com.panomc.plugins.market.provider

import com.panomc.plugins.market.spi.common.FieldType
import com.panomc.plugins.market.spi.common.LocalizedText
import com.panomc.plugins.market.spi.common.ProviderErrorCode
import com.panomc.plugins.market.spi.common.ProviderException
import com.panomc.plugins.market.spi.common.ProviderSettings
import com.panomc.plugins.market.spi.common.SafeRegex
import com.panomc.plugins.market.spi.common.SettingsField
import com.panomc.plugins.market.spi.common.SettingsSchema
import io.vertx.core.json.JsonObject
import java.net.URI

/** Why one settings field was refused: a stable [code] and the text the panel shows (`fieldErrors` of `INVALID_PROVIDER_SETTINGS`). */
class FieldIssue(val code: String, val text: LocalizedText)

/** Outcome of [SettingsCodec.applyForm]. [settings] is what to store (secrets encrypted); it is only meaningful when [ok]. */
class SettingsSaveResult(
    val settings: JsonObject,
    val issues: Map<String, FieldIssue>,
    /** Keys of the form fields whose value changed (secrets included; a secret is never named by its value). */
    val changedKeys: Set<String>,
    /** HIDDEN fields that were cleared because a field they depend on changed. */
    val clearedHidden: Set<String>
) {
    val ok: Boolean get() = issues.isEmpty()

    /** The `fieldErrors` map of the error response. */
    val fieldErrors: Map<String, LocalizedText> get() = issues.mapValues { it.value.text }
}

/** Outcome of [SettingsCodec.applyPatch]. */
class SettingsPatchResult(val settings: JsonObject, val applied: Set<String>, val rejected: Set<String>)

/**
 * The decrypted settings of one provider, as the provider reads them. [unreadable] names the secret fields whose
 * stored value could not be decrypted (missing or wrong key, damaged value): they read as absent, so the provider is
 * `NOT_CONFIGURED` and the method row gets `lastError = SECRET_UNREADABLE`.
 */
class StoredProviderSettings(values: Map<String, Any?>, val unreadable: Set<String> = emptySet()) : ProviderSettings {
    private val values: Map<String, Any?> = LinkedHashMap(values)

    override fun string(key: String): String? = when (val v = values[key]) {
        null -> null
        is String -> v.trim().ifEmpty { null }
        else -> v.toString()
    }

    override fun require(key: String): String =
        string(key) ?: throw ProviderException(ProviderErrorCode.CONFIGURATION, "Setting '$key' is not configured")

    override fun boolean(key: String, default: Boolean): Boolean = when (val v = values[key]) {
        null -> default
        is Boolean -> v
        else -> v.toString().trim().lowercase().let { it == "true" || it == "1" }
    }

    override fun long(key: String): Long? = when (val v = values[key]) {
        null -> null
        is Number -> v.toLong()
        else -> v.toString().trim().toLongOrNull()
    }

    override fun asJson(): JsonObject = JsonObject().also { json -> values.forEach { (k, v) -> if (v != null) json.put(k, v) } }

    /** Values of the keys in [keys] (the secret fields), for the [com.panomc.plugins.market.core.abuse.Redactor]. */
    fun valuesOf(keys: Set<String>): Set<String> = keys.mapNotNull { values[it]?.toString() }.toSet()

    override fun toString(): String = "StoredProviderSettings(${values.size} values)"
}

/**
 * Reads and writes the `settings` JSON of one provider according to its [SettingsSchema] (02 section 4, 11 section
 * 8.1 / 8.2): validation of what the panel posts, encryption of secret fields at rest, masking in responses.
 *
 * Stored form: a JSON object of the stored fields; text as strings, numbers as longs, switches as booleans; secret
 * fields (PASSWORD, SECRET_TEXTAREA, HIDDEN with hiddenSecret) as `v1:` ciphertext. Unknown keys, READONLY and NOTICE
 * fields are never stored; HIDDEN fields are never accepted from the panel form.
 *
 * Write protocol for a secret in the form: the key absent, `""` or the mask `"********"` keeps the stored value, a
 * JSON `null` clears it, anything else replaces it.
 */
class SettingsCodec(val schema: SettingsSchema, private val cipher: SecretCipher) {
    private val formFields: List<SettingsField> = schema.fields.filter { it.stored && it.type != FieldType.HIDDEN }
    private val hiddenFields: List<SettingsField> = schema.fields.filter { it.type == FieldType.HIDDEN }

    /**
     * Validates [form] (the body of the panel save) against the schema and merges it into [stored] (what the database
     * holds, or null for a first save). Every problem is reported at once; the result then has [SettingsSaveResult.ok]
     * `false` and the caller stores nothing.
     */
    fun applyForm(stored: JsonObject?, form: JsonObject): SettingsSaveResult {
        val current = stored ?: JsonObject()
        val out = JsonObject()
        val issues = LinkedHashMap<String, FieldIssue>()
        val changed = LinkedHashSet<String>()
        val newPlain = HashMap<String, String?>() // plaintext after the save, for the dependsOn comparison
        val oldPlain = HashMap<String, String?>()
        val explicitClear = HashSet<String>()

        for (field in formFields) {
            val key = field.key
            val old = plainValue(field, current)
            oldPlain[key] = old?.toString()
            val present = form.containsKey(key)
            var next: Any? = old
            if (present) {
                val raw = form.getValue(key)
                if (field.secret && raw == null) {
                    next = null
                    explicitClear.add(key)
                } else if (field.secret && raw is String && (raw.isBlank() || raw.trim() == MASK)) {
                    next = old
                } else {
                    when (val parsed = parse(field, raw)) {
                        is Parsed.Bad -> issues[key] = parsed.issue
                        is Parsed.Value -> next = parsed.value
                    }
                }
            } else if (old == null && !field.secret) {
                next = field.default?.let { coerceDefault(field, it) }
            }
            newPlain[key] = next?.toString()
            if (next?.toString() != old?.toString()) changed.add(key)
            if (key !in issues && next != null) {
                checkConstraints(field, next)?.let { issues[key] = it }
            }
        }
        // required: only for fields that are visible with the resulting values
        for (field in formFields) {
            val key = field.key
            if (key in issues || !field.required || field.type == FieldType.SWITCH) continue
            val value = newPlain[key]
            if (value.isNullOrEmpty() && isVisible(field, newPlain)) issues[key] = issue(REQUIRED, "This field is required.", "Bu alan zorunludur.", "Это поле обязательно.")
        }

        val cleared = LinkedHashSet<String>()
        if (issues.isEmpty()) {
            for (field in formFields) {
                val plain = parsedPlain(field, newPlain[field.key])
                if (plain == null) {
                    // An unreadable secret that was neither replaced nor cleared stays as it is: the key may come back from a backup.
                    val raw = current.getValue(field.key)
                    if (field.secret && field.key !in explicitClear && oldPlain[field.key] == null && raw is String && raw.isNotEmpty()) out.put(field.key, raw)
                    continue
                }
                out.put(field.key, if (field.secret) cipher.encrypt(plain.toString()) else plain)
            }
            for (hidden in hiddenFields) {
                val existing = current.getValue(hidden.key) as? String ?: continue
                if (hidden.dependsOn.any { newPlain[it] != oldPlain[it] }) cleared.add(hidden.key) else out.put(hidden.key, existing)
            }
        }
        return SettingsSaveResult(out, issues, changed, cleared)
    }

    /**
     * Writes provider derived values (`ActionResult.SettingsPatch`) into [stored]: only HIDDEN fields accept them (a
     * `null` value removes the field), `hiddenSecret` fields are encrypted. Other keys are [SettingsPatchResult.rejected].
     */
    fun applyPatch(stored: JsonObject?, values: Map<String, String?>): SettingsPatchResult {
        val out = (stored ?: JsonObject()).copy()
        val applied = LinkedHashSet<String>()
        val rejected = LinkedHashSet<String>()
        for ((key, value) in values) {
            val field = hiddenFields.firstOrNull { it.key == key }
            if (field == null) {
                rejected.add(key)
                continue
            }
            if (value == null) out.remove(key) else out.put(key, if (field.secret) cipher.encrypt(value) else value)
            applied.add(key)
        }
        return SettingsPatchResult(out, applied, rejected)
    }

    /** The settings for a panel response: secrets as the mask (`""` when unset), HIDDEN, READONLY and NOTICE omitted. */
    fun mask(stored: JsonObject?): JsonObject {
        val current = stored ?: JsonObject()
        val out = JsonObject()
        for (field in formFields) {
            val raw = current.getValue(field.key)
            if (field.secret) {
                out.put(field.key, if (raw is String && raw.isNotEmpty()) MASK else "")
            } else if (raw != null) {
                out.put(field.key, raw)
            }
        }
        return out
    }

    /** The settings as a provider reads them: secrets decrypted, unreadable ones absent and listed. */
    fun decrypt(stored: JsonObject?): StoredProviderSettings {
        val current = stored ?: JsonObject()
        val values = LinkedHashMap<String, Any?>()
        val unreadable = LinkedHashSet<String>()
        for (field in schema.fields.filter { it.stored }) {
            val raw = current.getValue(field.key) ?: continue
            if (field.secret) {
                val plain = (raw as? String)?.let { cipher.decrypt(it) }
                if (plain == null) unreadable.add(field.key) else values[field.key] = plain
            } else if (field.type != FieldType.HIDDEN || raw is String) {
                values[field.key] = raw
            }
        }
        return StoredProviderSettings(values, unreadable)
    }

    /** The secret fields of the form, decrypted, for `POST .../reveal`. Unreadable ones come back as `""`. */
    fun reveal(stored: JsonObject?): JsonObject {
        val current = stored ?: JsonObject()
        val out = JsonObject()
        for (field in formFields.filter { it.secret }) {
            val raw = current.getValue(field.key) as? String
            out.put(field.key, raw?.let { cipher.decrypt(it) } ?: "")
        }
        return out
    }

    /** Re-encrypts secrets that are still legacy plaintext; `null` when there is nothing to do. */
    fun encryptLegacyPlaintext(stored: JsonObject?): JsonObject? {
        if (stored == null) return null
        var out: JsonObject? = null
        for (field in schema.fields.filter { it.secret && it.stored }) {
            val raw = stored.getValue(field.key) as? String ?: continue
            if (cipher.needsEncryption(raw)) {
                if (out == null) out = stored.copy()
                out.put(field.key, cipher.encrypt(raw))
            }
        }
        return out
    }

    /** Secret fields whose stored value cannot be decrypted (`lastError = SECRET_UNREADABLE`). */
    fun unreadableSecrets(stored: JsonObject?): Set<String> = decrypt(stored).unreadable

    /**
     * Required fields that have no value (visible ones only) plus the unreadable secrets: a provider with any of
     * these is `NOT_CONFIGURED`.
     */
    fun missingRequired(stored: JsonObject?): Set<String> {
        val decrypted = decrypt(stored)
        val values = HashMap<String, String?>()
        for (field in formFields) values[field.key] = decrypted.string(field.key)
        val missing = LinkedHashSet<String>(decrypted.unreadable)
        for (field in formFields) {
            if (field.required && field.type != FieldType.SWITCH && decrypted.string(field.key) == null && isVisible(field, values)) missing.add(field.key)
        }
        return missing
    }

    // ---- internals

    private sealed class Parsed {
        class Value(val value: Any?) : Parsed()
        class Bad(val issue: FieldIssue) : Parsed()
    }

    private fun plainValue(field: SettingsField, current: JsonObject): Any? {
        val raw = current.getValue(field.key) ?: return null
        return when {
            field.secret -> (raw as? String)?.let { cipher.decrypt(it) }
            field.type == FieldType.NUMBER -> (raw as? Number)?.toLong()
            field.type == FieldType.SWITCH -> raw as? Boolean
            else -> raw as? String
        }
    }

    private fun parsedPlain(field: SettingsField, text: String?): Any? {
        if (text == null) return null
        return when (field.type) {
            FieldType.SWITCH -> text.toBooleanStrictOrNull()
            FieldType.NUMBER -> text.toLongOrNull()
            else -> text.takeIf { it.isNotEmpty() }
        }
    }

    private fun coerceDefault(field: SettingsField, default: Any): Any? = when (field.type) {
        FieldType.SWITCH -> default as? Boolean
        FieldType.NUMBER -> (default as? Number)?.toLong()
        else -> default.toString()
    }

    private fun isVisible(field: SettingsField, values: Map<String, String?>): Boolean {
        val cond = field.visibleWhen ?: return true
        return values[cond.field] in cond.anyOf
    }

    /** One posted value to its stored type; blank text, blank numbers and blank selects mean "no value". */
    private fun parse(field: SettingsField, raw: Any?): Parsed {
        if (raw == null) return Parsed.Value(null)
        return when (field.type) {
            FieldType.SWITCH -> when {
                raw is Boolean -> Parsed.Value(raw)
                raw is String && raw.trim().lowercase() == "true" -> Parsed.Value(true)
                raw is String && raw.trim().lowercase() == "false" -> Parsed.Value(false)
                raw is String && raw.isBlank() -> Parsed.Value(null)
                else -> Parsed.Bad(issue(INVALID_TYPE, "Expected true or false.", "Doğru veya yanlış bekleniyor.", "Ожидается true или false."))
            }
            FieldType.NUMBER -> when {
                raw is Int || raw is Long || raw is Short || raw is Byte -> Parsed.Value((raw as Number).toLong())
                raw is Number -> if (raw.toDouble() == Math.floor(raw.toDouble()) && !raw.toDouble().isInfinite() && Math.abs(raw.toDouble()) < 9.0e15) Parsed.Value(raw.toLong()) else Parsed.Bad(notANumber())
                raw is String && raw.isBlank() -> Parsed.Value(null)
                raw is String -> raw.trim().toLongOrNull()?.let { Parsed.Value(it) } ?: Parsed.Bad(notANumber())
                else -> Parsed.Bad(notANumber())
            }
            else -> {
                if (raw !is String) return Parsed.Bad(issue(INVALID_TYPE, "Expected text.", "Metin bekleniyor.", "Ожидается текст."))
                val text = raw.trim()
                Parsed.Value(text.ifEmpty { null })
            }
        }
    }

    private fun notANumber() = issue(INVALID_NUMBER, "Enter a whole number.", "Tam sayı girin.", "Введите целое число.")

    private fun checkConstraints(field: SettingsField, value: Any): FieldIssue? {
        when (field.type) {
            FieldType.NUMBER -> {
                val n = (value as Number).toLong()
                val min = field.min
                val max = field.max
                if ((min != null && n < min) || (max != null && n > max)) {
                    val range = when {
                        min != null && max != null -> "$min - $max"
                        min != null -> ">= $min"
                        else -> "<= $max"
                    }
                    return issue(OUT_OF_RANGE, "The value must be $range.", "Değer $range olmalıdır.", "Значение должно быть $range.")
                }
            }
            FieldType.SELECT -> if (field.options.none { it.value == value }) {
                return issue(INVALID_OPTION, "Choose one of the listed options.", "Listedeki seçeneklerden birini seçin.", "Выберите один из вариантов.")
            }
            FieldType.SWITCH -> {}
            else -> {
                val text = value as String
                val limit = if (field.type == FieldType.TEXTAREA || field.type == FieldType.SECRET_TEXTAREA) MAX_TEXTAREA else MAX_TEXT
                if (text.length > limit) {
                    return issue(TOO_LONG, "At most $limit characters.", "En fazla $limit karakter.", "Не более $limit символов.")
                }
                if (field.type == FieldType.URL && !isHttpUrl(text)) {
                    return issue(INVALID_URL, "Enter a full http(s) address.", "Tam bir http(s) adresi girin.", "Введите полный адрес http(s).")
                }
                val pattern = field.pattern
                if (pattern != null) {
                    when (SafeRegex.test(pattern, text)) {
                        SafeRegex.Verdict.MATCH -> {}
                        SafeRegex.Verdict.NO_MATCH -> return issue(PATTERN_MISMATCH, "The value has the wrong format.", "Değer biçimi yanlış.", "Неверный формат значения.")
                        SafeRegex.Verdict.INVALID -> return issue(FIELD_INVALID, "The value cannot be checked.", "Değer doğrulanamadı.", "Значение невозможно проверить.")
                    }
                }
            }
        }
        return null
    }

    private fun isHttpUrl(text: String): Boolean = try {
        val uri = URI(text)
        (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) && !uri.host.isNullOrEmpty() && uri.userInfo == null
    } catch (e: Exception) {
        false
    }

    private fun issue(code: String, en: String, tr: String, ru: String) =
        FieldIssue(code, LocalizedText.of(en, "tr" to tr, "ru" to ru))

    companion object {
        /** The sentinel a secret is shown as; posting it back keeps the stored value. */
        const val MASK = "********"

        /** `market_payment_method.lastError` when a stored secret cannot be decrypted. */
        const val SECRET_UNREADABLE = "SECRET_UNREADABLE"

        const val REQUIRED = "REQUIRED"
        const val INVALID_TYPE = "INVALID_TYPE"
        const val INVALID_NUMBER = "INVALID_NUMBER"
        const val INVALID_OPTION = "INVALID_OPTION"
        const val INVALID_URL = "INVALID_URL"
        const val OUT_OF_RANGE = "OUT_OF_RANGE"
        const val PATTERN_MISMATCH = "PATTERN_MISMATCH"
        const val FIELD_INVALID = "FIELD_INVALID"
        const val TOO_LONG = "TOO_LONG"

        const val MAX_TEXT = 4096
        const val MAX_TEXTAREA = 32_768
    }
}
