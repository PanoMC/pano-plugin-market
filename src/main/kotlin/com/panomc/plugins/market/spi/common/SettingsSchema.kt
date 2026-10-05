package com.panomc.plugins.market.spi.common

import com.panomc.plugins.market.spi.MarketSpi
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject

/** Field kinds of the settings form. New values only at the end. */
enum class FieldType { TEXT, PASSWORD, TEXTAREA, SECRET_TEXTAREA, NUMBER, SELECT, SWITCH, URL, READONLY, NOTICE, HIDDEN }

class SettingsField(val key: String, val type: FieldType, val label: LocalizedText) {
    var help: LocalizedText? = null
    var placeholder: String? = null
    var required: Boolean = false

    /** String | Boolean | Number. */
    var default: Any? = null
    var options: List<SelectOption> = emptyList()

    /** Anchored regex, validated server-side too; must satisfy [SafeRegex.checkGrammar]. */
    var pattern: String? = null
    var min: Long? = null
    var max: Long? = null
    var group: String? = null

    /** Show only when another field has one of the values. */
    var visibleWhen: FieldCondition? = null
    var readonly: ReadonlyValue? = null
    var noticeLevel: NoticeLevel = NoticeLevel.INFO

    /** HIDDEN: stored encrypted. */
    var hiddenSecret: Boolean = false

    /** HIDDEN: cleared when one of these fields changes. */
    var dependsOn: Set<String> = emptySet()

    val secret: Boolean get() = type == FieldType.PASSWORD || type == FieldType.SECRET_TEXTAREA || hiddenSecret

    /** READONLY and NOTICE fields are display only: never stored, never accepted from the form. */
    val stored: Boolean get() = type != FieldType.READONLY && type != FieldType.NOTICE
}

class SelectOption(val value: String, val label: LocalizedText)

class FieldCondition(val field: String, val anyOf: Set<String>)

enum class NoticeLevel { INFO, WARNING }

sealed class ReadonlyValue {
    /** Copyable callback / IPN URL. */
    class WebhookUrl(val channel: String = MarketSpi.DEFAULT_CHANNEL) : ReadonlyValue()

    /** For gateways that allow-list return domains. */
    object ReturnUrlPrefix : ReadonlyValue()

    object SiteBaseUrl : ReadonlyValue()

    class Static(val text: LocalizedText) : ReadonlyValue()
}

class SettingsGroup(val key: String, val label: LocalizedText)

class SettingsAction(val id: String, val label: LocalizedText) {
    var description: LocalizedText? = null

    /** Confirmation modal text. */
    var confirm: LocalizedText? = null
    var requiresSavedSettings: Boolean = true
}

class SettingsSchema(val fields: List<SettingsField>, val groups: List<SettingsGroup>, val actions: List<SettingsAction>) {
    fun field(key: String): SettingsField? = fields.firstOrNull { it.key == key }

    /** The fields whose values are stored (everything but READONLY and NOTICE). */
    val storedFields: List<SettingsField> get() = fields.filter { it.stored }

    /** Keys of the secret fields (encrypted at rest, masked in the panel). */
    val secretKeys: Set<String> get() = fields.filter { it.secret }.map { it.key }.toSet()

    /**
     * Wire format of 02 section 4. HIDDEN fields are not part of it. [resolveReadonly] supplies the server resolved
     * `value` of a READONLY field (webhook URL, base URL, ...); null leaves it out.
     */
    fun toJson(resolveReadonly: (ReadonlyValue) -> String? = { null }): JsonObject = JsonObject()
        .put("fields", JsonArray(fields.filter { it.type != FieldType.HIDDEN }.map { fieldJson(it, resolveReadonly) }))
        .put("groups", JsonArray(groups.map { JsonObject().put("key", it.key).put("label", it.label.toJson()) }))
        .put("actions", JsonArray(actions.map { actionJson(it) }))

    private fun fieldJson(f: SettingsField, resolveReadonly: (ReadonlyValue) -> String?): JsonObject {
        val json = JsonObject().put("key", f.key).put("type", f.type.name).put("label", f.label.toJson())
        f.help?.let { json.put("help", it.toJson()) }
        f.placeholder?.let { json.put("placeholder", it) }
        json.put("required", f.required)
        f.default?.let { json.put("default", it) }
        if (f.options.isNotEmpty()) {
            json.put("options", JsonArray(f.options.map { JsonObject().put("value", it.value).put("label", it.label.toJson()) }))
        }
        f.pattern?.let { json.put("pattern", it) }
        f.min?.let { json.put("min", it) }
        f.max?.let { json.put("max", it) }
        f.group?.let { json.put("group", it) }
        f.visibleWhen?.let { json.put("visibleWhen", JsonObject().put("field", it.field).put("anyOf", JsonArray(it.anyOf.sorted()))) }
        f.readonly?.let { json.put("readonly", readonlyJson(it, resolveReadonly)) }
        if (f.type == FieldType.NOTICE) json.put("noticeLevel", f.noticeLevel.name)
        json.put("secret", f.secret)
        return json
    }

    private fun readonlyJson(value: ReadonlyValue, resolve: (ReadonlyValue) -> String?): JsonObject {
        val json = JsonObject()
        when (value) {
            is ReadonlyValue.WebhookUrl -> json.put("kind", "WEBHOOK_URL").put("channel", value.channel)
            is ReadonlyValue.ReturnUrlPrefix -> json.put("kind", "RETURN_URL_PREFIX")
            is ReadonlyValue.SiteBaseUrl -> json.put("kind", "SITE_BASE_URL")
            is ReadonlyValue.Static -> json.put("kind", "STATIC").put("text", value.text.toJson())
        }
        resolve(value)?.let { json.put("value", it) }
        return json
    }

    private fun actionJson(a: SettingsAction): JsonObject {
        val json = JsonObject().put("id", a.id).put("label", a.label.toJson())
        a.description?.let { json.put("description", it.toJson()) }
        a.confirm?.let { json.put("confirm", it.toJson()) }
        return json.put("requiresSavedSettings", a.requiresSavedSettings)
    }
}

/** Builds a schema; the only way plugins create one. Throws [IllegalArgumentException] on an inconsistent schema. */
fun settingsSchema(block: SettingsSchemaBuilder.() -> Unit): SettingsSchema = SettingsSchemaBuilder().apply(block).build()

class FieldBuilder internal constructor(private val key: String, private val type: FieldType) {
    /** Required: a field without a label is a build error. */
    var label: LocalizedText? = null
    var help: LocalizedText? = null
    var placeholder: String? = null
    var required: Boolean = false
    var default: Any? = null
    var pattern: String? = null
    var min: Long? = null
    var max: Long? = null
    var group: String? = null
    var noticeLevel: NoticeLevel = NoticeLevel.INFO
    internal var readonly: ReadonlyValue? = null
    private val options = ArrayList<SelectOption>()
    private var visibleWhen: FieldCondition? = null
    private var dependsOn: Set<String> = emptySet()
    private var hiddenSecret: Boolean = false

    fun option(value: String, label: LocalizedText) {
        options.add(SelectOption(value, label))
    }

    fun visibleWhen(field: String, vararg anyOf: String) {
        visibleWhen = FieldCondition(field, anyOf.toSet())
    }

    fun dependsOn(vararg keys: String) {
        dependsOn = keys.toSet()
    }

    internal fun markHiddenSecret() {
        hiddenSecret = true
    }

    internal fun build(): SettingsField {
        val field = SettingsField(key, type, requireNotNull(label) { "Field '$key' has no label" })
        field.help = help
        field.placeholder = placeholder
        field.required = required
        field.default = default
        field.options = options.toList()
        field.pattern = pattern
        field.min = min
        field.max = max
        field.group = group
        field.visibleWhen = visibleWhen
        field.readonly = readonly
        field.noticeLevel = noticeLevel
        field.hiddenSecret = hiddenSecret
        field.dependsOn = dependsOn
        return field
    }
}

class ActionBuilder internal constructor(private val id: String) {
    var label: LocalizedText? = null
    var description: LocalizedText? = null
    var confirm: LocalizedText? = null
    var requiresSavedSettings: Boolean = true

    internal fun build(): SettingsAction {
        val action = SettingsAction(id, requireNotNull(label) { "Action '$id' has no label" })
        action.description = description
        action.confirm = confirm
        action.requiresSavedSettings = requiresSavedSettings
        return action
    }
}

class SettingsSchemaBuilder internal constructor() {
    private val fields = ArrayList<SettingsField>()
    private val groups = ArrayList<SettingsGroup>()
    private val actions = ArrayList<SettingsAction>()

    fun group(key: String, label: LocalizedText) {
        groups.add(SettingsGroup(key, label))
    }

    fun text(key: String, block: FieldBuilder.() -> Unit) = add(key, FieldType.TEXT, block)

    /** Secret single line (type PASSWORD): encrypted at rest, masked in the panel. */
    fun secret(key: String, block: FieldBuilder.() -> Unit) = add(key, FieldType.PASSWORD, block)

    fun textarea(key: String, block: FieldBuilder.() -> Unit) = add(key, FieldType.TEXTAREA, block)

    fun secretTextarea(key: String, block: FieldBuilder.() -> Unit) = add(key, FieldType.SECRET_TEXTAREA, block)

    fun number(key: String, block: FieldBuilder.() -> Unit) = add(key, FieldType.NUMBER, block)

    fun select(key: String, block: FieldBuilder.() -> Unit) = add(key, FieldType.SELECT, block)

    fun switch(key: String, block: FieldBuilder.() -> Unit) = add(key, FieldType.SWITCH, block)

    fun url(key: String, block: FieldBuilder.() -> Unit) = add(key, FieldType.URL, block)

    /** Display only value resolved by the server. */
    fun readonly(key: String, value: ReadonlyValue, block: FieldBuilder.() -> Unit) = add(key, FieldType.READONLY, block) { it.readonly = value }

    fun webhookUrl(key: String, channel: String = MarketSpi.DEFAULT_CHANNEL, block: FieldBuilder.() -> Unit) =
        readonly(key, ReadonlyValue.WebhookUrl(channel), block)

    fun returnUrlPrefix(key: String, block: FieldBuilder.() -> Unit) = readonly(key, ReadonlyValue.ReturnUrlPrefix, block)

    fun siteBaseUrl(key: String, block: FieldBuilder.() -> Unit) = readonly(key, ReadonlyValue.SiteBaseUrl, block)

    fun notice(key: String, level: NoticeLevel = NoticeLevel.INFO, block: FieldBuilder.() -> Unit) =
        add(key, FieldType.NOTICE, block) { it.noticeLevel = level }

    /** Provider derived value: stored, never rendered, never accepted from the form. */
    fun hidden(key: String, block: FieldBuilder.() -> Unit) = add(key, FieldType.HIDDEN, block)

    /** Like [hidden] but encrypted at rest. */
    fun hiddenSecret(key: String, block: FieldBuilder.() -> Unit) = add(key, FieldType.HIDDEN, block) { it.markHiddenSecret() }

    fun action(id: String, block: ActionBuilder.() -> Unit) {
        actions.add(ActionBuilder(id).apply(block).build())
    }

    private fun add(key: String, type: FieldType, block: FieldBuilder.() -> Unit, before: (FieldBuilder) -> Unit = {}) {
        val builder = FieldBuilder(key, type)
        before(builder)
        builder.block()
        fields.add(builder.build())
    }

    internal fun build(): SettingsSchema {
        validate(fields, groups, actions)
        return SettingsSchema(fields.toList(), groups.toList(), actions.toList())
    }

    private fun validate(fields: List<SettingsField>, groups: List<SettingsGroup>, actions: List<SettingsAction>) {
        val keys = HashSet<String>()
        for (f in fields) {
            require(KEY.matches(f.key)) { "Invalid field key '${f.key}'" }
            require(keys.add(f.key)) { "Duplicate field key '${f.key}'" }
        }
        val groupKeys = HashSet<String>()
        for (g in groups) require(groupKeys.add(g.key)) { "Duplicate group '${g.key}'" }
        val actionIds = HashSet<String>()
        for (a in actions) {
            require(KEY.matches(a.id)) { "Invalid action id '${a.id}'" }
            require(actionIds.add(a.id)) { "Duplicate action '${a.id}'" }
        }
        for (f in fields) validateField(f, keys, groupKeys)
    }

    private fun validateField(f: SettingsField, keys: Set<String>, groupKeys: Set<String>) {
        val at = "Field '${f.key}'"
        f.group?.let { require(it in groupKeys) { "$at refers to unknown group '$it'" } }
        f.visibleWhen?.let {
            require(it.field in keys && it.field != f.key) { "$at is visible only when unknown field '${it.field}'" }
            require(it.anyOf.isNotEmpty()) { "$at has an empty visibleWhen value set" }
        }
        for (dep in f.dependsOn) require(f.type == FieldType.HIDDEN && dep in keys && dep != f.key) {
            "$at: dependsOn is only for HIDDEN fields and must name another field ('$dep')"
        }
        require(f.hiddenSecret.not() || f.type == FieldType.HIDDEN) { "$at: hiddenSecret needs type HIDDEN" }
        require((f.type == FieldType.SELECT) == f.options.isNotEmpty()) {
            if (f.type == FieldType.SELECT) "$at is a SELECT without options" else "$at has options but is not a SELECT"
        }
        require(f.options.map { it.value }.toSet().size == f.options.size) { "$at has duplicate option values" }
        require((f.type == FieldType.READONLY) == (f.readonly != null)) { "$at: readonly value needs type READONLY and vice versa" }
        if (f.pattern != null) {
            require(f.type == FieldType.TEXT || f.type == FieldType.PASSWORD || f.type == FieldType.TEXTAREA ||
                f.type == FieldType.SECRET_TEXTAREA || f.type == FieldType.URL) { "$at: pattern is only for text fields" }
            val problem = SafeRegex.checkGrammar(f.pattern!!)
            require(problem == null) { "$at: pattern refused ($problem)" }
        }
        if (f.min != null || f.max != null) {
            require(f.type == FieldType.NUMBER) { "$at: min / max are only for NUMBER fields" }
            if (f.min != null && f.max != null) require(f.min!! <= f.max!!) { "$at: min is greater than max" }
        }
        if (f.type == FieldType.READONLY || f.type == FieldType.NOTICE) {
            require(!f.required && f.default == null) { "$at is display only: no required / default" }
        }
        if (f.secret) require(f.default == null) { "$at is a secret: no default" }
        f.default?.let { d ->
            when (f.type) {
                FieldType.SWITCH -> require(d is Boolean) { "$at: default of a SWITCH must be a Boolean" }
                FieldType.NUMBER -> {
                    require(d is Number) { "$at: default of a NUMBER must be a Number" }
                    f.min?.let { require(d.toDouble() >= it) { "$at: default below min" } }
                    f.max?.let { require(d.toDouble() <= it) { "$at: default above max" } }
                }
                FieldType.SELECT -> require(d is String && f.options.any { it.value == d }) { "$at: default is not one of the options" }
                else -> require(d is String || d is Boolean || d is Number) { "$at: default must be a String, Boolean or Number" }
            }
        }
    }

    private companion object {
        val KEY = Regex("^[A-Za-z][A-Za-z0-9_.-]{0,63}$")
    }
}
