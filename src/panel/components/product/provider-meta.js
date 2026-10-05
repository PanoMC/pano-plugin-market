// Pure helpers of the Providers tab (13 §8.8): the per-product settings a payment provider
// declares in `productMetaSchema` (shape of 13 §16.3, delivered by GET /context as
// `productMetaSchemas[{providerId, name, schema}]`). Self-contained on purpose: the shared
// schema-form module belongs to the payment-method screens. No Svelte, no SDK import.

export const STORABLE = (field) => field.type !== 'READONLY' && field.type !== 'NOTICE';
export const SECRET_MASK = '********';

/** A secret field: flagged `secret`, or one of the two secret input types (13 §16.3). */
export const isSecretField = (field) =>
  field?.secret === true || field?.type === 'PASSWORD' || field?.type === 'SECRET_TEXTAREA';

/** Mask protocol (13 §16.3): focus on a masked value empties the input so typing replaces it. */
export const secretOnFocus = (value) => (value === SECRET_MASK ? '' : value);

/** Mask protocol: blur with nothing typed restores the mask when the field was masked before. */
export const secretOnBlur = (value, hadMask) =>
  hadMask && (value === '' || value === null || value === undefined) ? SECRET_MASK : value;

/**
 * Typed value of a secret input: a mask prefix is never part of a new secret (paste over a masked
 * value, a browser autofill), so `********abc` becomes `abc` and the bare mask stays the mask.
 */
export const secretOnInput = (value) => {
  const text = String(value ?? '');
  return text !== SECRET_MASK && text.startsWith(SECRET_MASK)
    ? text.slice(SECRET_MASK.length)
    : text;
};

/**
 * `LocalizedText` -> string. `{key, fallback}` asks `rawTranslate(key)` (a provider's keys live under
 * its own `plugins.<pluginId>.*`); `{default, translations}` picks the locale, then its language, then
 * the default; `null` is ''.
 */
export function resolveText(text, locale = 'en-US', rawTranslate = (key) => key) {
  if (text === null || text === undefined) return '';
  if (typeof text === 'string') return text;
  if (typeof text.key === 'string') {
    const translated = rawTranslate(text.key);
    return translated !== text.key ? translated : (text.fallback ?? '');
  }
  const translations = text.translations ?? {};
  const language = String(locale).split('-')[0];
  return translations[locale] ?? translations[language] ?? text.default ?? '';
}

/** Stored value, else the field default, else false for a switch and '' for the rest. */
export function initialValues(schema, stored = {}) {
  const values = {};
  for (const field of schema?.fields ?? []) {
    if (!STORABLE(field)) continue;
    const have = stored?.[field.key];
    values[field.key] =
      have !== undefined && have !== null
        ? have
        : (field.default ?? (field.type === 'SWITCH' ? false : ''));
  }
  return values;
}

/** A field is visible when it has no condition, or the referenced field is visible and matches. */
export function isVisible(field, values, schema, seen = new Set()) {
  const rule = field.visibleWhen;
  if (!rule) return true;
  if (seen.has(field.key)) return true;
  seen.add(field.key);
  const parent = (schema?.fields ?? []).find((f) => f.key === rule.field);
  if (parent && !isVisible(parent, values, schema, seen)) return false;
  return (rule.anyOf ?? []).includes(String(values?.[rule.field]));
}

const isEmpty = (value) => value === '' || value === null || value === undefined;

/** null when the value is acceptable, otherwise a code of 13 §16.3. */
export function validateField(field, value, visible = true) {
  if (!visible || !STORABLE(field)) return null;
  const secret = isSecretField(field);
  if (field.type === 'SWITCH') return null;
  if (isEmpty(value)) return field.required ? 'REQUIRED' : null;
  if (secret && value === SECRET_MASK) return null;
  const text = String(value);
  if (field.type === 'NUMBER') {
    if (!/^-?\d+$/.test(text)) return 'INVALID_NUMBER';
    const n = Number(text);
    if (
      (field.min !== undefined && field.min !== null && n < field.min) ||
      (field.max !== undefined && field.max !== null && n > field.max)
    )
      return 'OUT_OF_RANGE';
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
      // a pattern that does not compile is ignored client-side
    }
  }
  return null;
}

/** `{ key: code }` for every visible field of the schema. */
export function validateSchema(schema, values) {
  const errors = {};
  for (const field of schema?.fields ?? []) {
    const code = validateField(field, values?.[field.key], isVisible(field, values, schema));
    if (code) errors[field.key] = code;
  }
  return errors;
}

/** The value sent for the schema: every storable key (hidden ones unchanged), numbers as numbers. */
export function buildMeta(schema, values) {
  const out = {};
  for (const field of schema?.fields ?? []) {
    if (!STORABLE(field)) continue;
    const value = values?.[field.key];
    if (field.type === 'NUMBER') out[field.key] = isEmpty(value) ? null : Number(value);
    else if (field.type === 'SWITCH') out[field.key] = !!value;
    else out[field.key] = value ?? '';
  }
  return out;
}

/** `providerMeta` part: one entry per declared provider, defaults filled in, others dropped. */
export function buildProviderMeta(schemas, stored = {}) {
  const out = {};
  for (const entry of schemas ?? []) {
    out[entry.providerId] = buildMeta(
      entry.schema,
      initialValues(entry.schema, stored?.[entry.providerId]),
    );
  }
  return out;
}

/** `{ 'providerMeta.<providerId>.<key>': CODE }` for the whole product. */
export function validateProviderMeta(schemas, stored = {}) {
  const errors = {};
  for (const entry of schemas ?? []) {
    const values = initialValues(entry.schema, stored?.[entry.providerId]);
    for (const [key, code] of Object.entries(validateSchema(entry.schema, values)))
      errors[`providerMeta.${entry.providerId}.${key}`] = code;
  }
  return errors;
}

/** Fields of a schema grouped for rendering: ungrouped first, then one block per `groups[]`. */
export function groupFields(schema) {
  const fields = schema?.fields ?? [];
  const groups = schema?.groups ?? [];
  const known = new Set(groups.map((g) => g.key));
  const blocks = [
    { key: null, label: null, fields: fields.filter((f) => !f.group || !known.has(f.group)) },
  ];
  for (const group of groups)
    blocks.push({
      key: group.key,
      label: group.label,
      fields: fields.filter((f) => f.group === group.key),
    });
  return blocks.filter((block) => block.fields.length > 0);
}

export const META_ERROR_CODES = [
  'REQUIRED',
  'INVALID_NUMBER',
  'OUT_OF_RANGE',
  'INVALID_URL',
  'INVALID_OPTION',
  'INVALID_FORMAT',
];

export function metaErrorKey(code) {
  return `pages.create-product.field-errors.${META_ERROR_CODES.includes(code) ? code : 'INVALID'}`;
}
