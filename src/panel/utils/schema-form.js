// Pure core of the schema-driven provider form (13 §16.3): text resolution, defaults, visibility,
// validation and the payload sent to the server. Used by SchemaForm / SecretInput and by the
// PaymentMethodModal (and later the carrier modal, MPU-17). No Svelte, no SDK import.

/** Literal the server sends for a stored secret and accepts back as "keep it". */
export const SECRET_MASK = '********';

/** Codes validateField returns; each has `schema.errors.<CODE>` in the locale files. */
export const SCHEMA_ERROR_CODES = [
  'REQUIRED',
  'INVALID_NUMBER',
  'OUT_OF_RANGE',
  'INVALID_URL',
  'INVALID_OPTION',
  'INVALID_FORMAT',
];

/** READONLY / NOTICE are rendered only; HIDDEN (provider-derived) is never part of the form. */
export const isStorable = (field) =>
  field.type !== 'READONLY' && field.type !== 'NOTICE' && field.type !== 'HIDDEN';

/** A field whose value is a secret: flagged `secret`, or one of the two secret input types. */
export const isSecretField = (field) =>
  field?.secret === true || field?.type === 'PASSWORD' || field?.type === 'SECRET_TEXTAREA';

const isEmpty = (value) => value === '' || value === null || value === undefined;

/**
 * `LocalizedText` -> string. `{key, fallback}` asks `rawTranslate(key)` (the raw SDK `_`, because a
 * provider's keys live under its own `plugins.<pluginId>.*`) and falls back when the key is missing
 * (the translator echoes the key); `{default, translations}` picks the locale, then its language,
 * then the default; `null` is ''. A plain string is returned unchanged.
 */
export function resolveText(text, locale = 'en-US', rawTranslate = (key) => key) {
  if (text === null || text === undefined) return '';
  if (typeof text === 'string') return text;
  if (typeof text.key === 'string') {
    const translated = rawTranslate(text.key);
    return translated !== text.key ? translated : (text.fallback ?? '');
  }
  const translations = text.translations ?? {};
  const language = String(locale ?? 'en-US').split('-')[0];
  return translations[locale] ?? translations[language] ?? text.default ?? '';
}

/**
 * Form values of a schema. Stored value, else the field default, else false for a switch and ''
 * for the rest. READONLY / NOTICE / HIDDEN fields are not part of the values. A stored secret
 * arrives as the mask and is kept as is.
 */
export function initialValues(schema, stored = {}) {
  const values = {};
  for (const field of schema?.fields ?? []) {
    if (!isStorable(field)) continue;
    const have = stored?.[field.key];
    values[field.key] =
      have !== undefined && have !== null
        ? have
        : (field.default ?? (field.type === 'SWITCH' ? false : ''));
  }
  return values;
}

/**
 * Visible when there is no condition, or the referenced field matches (as a string) and is itself
 * visible. `schema` provides the referenced field for the chain; without it only the direct
 * condition is checked. A field met twice (a cycle) counts as visible.
 */
export function isVisible(field, values, schema = null, seen = new Set()) {
  const rule = field.visibleWhen;
  if (!rule) return true;
  if (seen.has(field.key)) return true;
  seen.add(field.key);
  const parent = (schema?.fields ?? []).find((f) => f.key === rule.field);
  if (parent && !isVisible(parent, values, schema, seen)) return false;
  return (rule.anyOf ?? []).includes(String(values?.[rule.field]));
}

/**
 * null when the value is acceptable, otherwise a code of SCHEMA_ERROR_CODES. `visible` false (the
 * field is hidden by a condition) and non-storable fields are skipped.
 */
export function validateField(field, value, visible = true) {
  if (!visible || !isStorable(field)) return null;
  if (field.type === 'SWITCH') return null;
  const secret = isSecretField(field);
  if (isEmpty(value)) return field.required ? 'REQUIRED' : null;
  // a secret still showing the mask counts as filled and is not matched against anything
  if (secret && value === SECRET_MASK) return null;
  const text = String(value);
  if (field.type === 'NUMBER') {
    if (!/^-?\d+$/.test(text)) return 'INVALID_NUMBER';
    const n = Number(text);
    const below = field.min !== undefined && field.min !== null && n < field.min;
    const above = field.max !== undefined && field.max !== null && n > field.max;
    if (below || above) return 'OUT_OF_RANGE';
  }
  if (field.type === 'URL') {
    try {
      const url = new URL(text);
      if (url.protocol !== 'http:' && url.protocol !== 'https:') return 'INVALID_URL';
    } catch {
      return 'INVALID_URL';
    }
  }
  if (field.type === 'SELECT' && !(field.options ?? []).some((o) => o.value === text))
    return 'INVALID_OPTION';
  if (field.pattern && !secret) {
    try {
      if (!new RegExp(field.pattern).test(text)) return 'INVALID_FORMAT';
    } catch {
      // a pattern that does not compile is ignored client-side (the server validates it too)
    }
  }
  return null;
}

/** `{ key: code }` for every visible, storable field of the schema. */
export function validate(schema, values) {
  const errors = {};
  for (const field of schema?.fields ?? []) {
    const code = validateField(field, values?.[field.key], isVisible(field, values, schema));
    if (code) errors[field.key] = code;
  }
  return errors;
}

/**
 * The `settings` object of the save request: every storable key, hidden ones included (their
 * current value goes back unchanged). A secret still showing the mask is sent as the mask, one
 * removed through the "Remove" action (value `null`) as `null`. NUMBER becomes a Number (null when
 * empty), SWITCH a boolean.
 */
export function buildSettingsPayload(schema, values) {
  const out = {};
  for (const field of schema?.fields ?? []) {
    if (!isStorable(field)) continue;
    const value = values?.[field.key];
    if (isSecretField(field)) {
      out[field.key] = value === null ? null : isEmpty(value) ? '' : value;
    } else if (field.type === 'NUMBER') {
      const n = isEmpty(value) ? null : Number(value);
      out[field.key] = n === null || Number.isNaN(n) ? null : n;
    } else if (field.type === 'SWITCH') {
      out[field.key] = !!value;
    } else {
      out[field.key] = value ?? '';
    }
  }
  return out;
}

/** Local hint only (the server's `state` is authoritative): every visible required field is filled. */
export function isConfigured(schema, values) {
  for (const field of schema?.fields ?? []) {
    if (!field.required || !isStorable(field)) continue;
    if (!isVisible(field, values, schema)) continue;
    if (validateField(field, values?.[field.key], true) === 'REQUIRED') return false;
  }
  return true;
}

/** True when the editable values differ from the baseline (action buttons with requiresSavedSettings). */
export function isDirty(schema, values, baseline) {
  for (const field of schema?.fields ?? []) {
    if (!isStorable(field)) continue;
    const a = values?.[field.key];
    const b = baseline?.[field.key];
    if (a !== b && !(isEmpty(a) && isEmpty(b) && a !== null && b !== null)) return true;
  }
  return false;
}

/**
 * Fields to render in order: the ungrouped ones first (heading null), then one block per group in
 * schema order. A block without a visible field is dropped; a field of an unknown group is shown
 * with the ungrouped ones.
 */
export function groupFields(schema, values) {
  const known = new Set((schema?.groups ?? []).map((g) => g.key));
  const visible = (schema?.fields ?? []).filter((f) => isVisible(f, values, schema));
  const blocks = [];
  const loose = visible.filter((f) => !f.group || !known.has(f.group));
  if (loose.length) blocks.push({ key: null, label: null, fields: loose });
  for (const group of schema?.groups ?? []) {
    const fields = visible.filter((f) => f.group === group.key);
    if (fields.length) blocks.push({ key: group.key, label: group.label, fields });
  }
  return blocks;
}

/** What a READONLY field shows: the live webhook URL of its channel for WEBHOOK_URL, else the server value. */
export function readonlyValue(field, webhookUrls = {}) {
  const info = field.readonly;
  if (!info) return '';
  if (info.kind === 'WEBHOOK_URL' && info.channel && webhookUrls?.[info.channel])
    return webhookUrls[info.channel];
  return info.value ?? '';
}

// -- secret input protocol (13 §16.3) ---------------------------------------------------------

/** The server holds a value for this secret: it was loaded masked (`baseline` = initialValues). */
export const isStoredSecret = (baseline, key) => baseline?.[key] === SECRET_MASK;

/** Focus on a masked value empties the input so typing replaces it. */
export const secretOnFocus = (value) => (value === SECRET_MASK ? '' : value);

/**
 * Blur with nothing typed: a field that was removed stays removed (`null`), a field that was masked
 * before gets the mask back (so the server keeps the stored value); anything typed is kept.
 */
export function secretOnBlur(value, { hadMask = false, removed = false } = {}) {
  if (!isEmpty(value)) return value;
  if (removed) return null;
  return hadMask ? SECRET_MASK : (value ?? '');
}

/** A mask prefix is never part of a new secret (paste over the mask, autofill): `********abc` is `abc`. */
export function secretOnInput(value) {
  const text = String(value ?? '');
  return text !== SECRET_MASK && text.startsWith(SECRET_MASK) ? text.slice(SECRET_MASK.length) : text;
}

/**
 * Values after a successful reveal: only secret fields still showing the mask are replaced by the
 * revealed value; a field the admin typed into (or removed) keeps what it has.
 */
export function applyReveal(schema, values, revealed) {
  const out = { ...values };
  for (const field of schema?.fields ?? []) {
    if (!isSecretField(field)) continue;
    if (out[field.key] === SECRET_MASK && revealed?.[field.key] !== undefined && revealed[field.key] !== null)
      out[field.key] = revealed[field.key];
  }
  return out;
}

/**
 * Text of one field error. `error` is a client code (`schema.errors.<CODE>`), the server's
 * `LocalizedText` of a `fieldErrors` entry, or a plain server string.
 */
export function errorText(error, { locale = 'en-US', rawTranslate, translate }) {
  if (error === null || error === undefined || error === '') return '';
  if (typeof error === 'string') {
    return SCHEMA_ERROR_CODES.includes(error) ? translate(`schema.errors.${error}`) : error;
  }
  return resolveText(error, locale, rawTranslate);
}
