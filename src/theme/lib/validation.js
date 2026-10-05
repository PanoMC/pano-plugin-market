// Client-side validation of the storefront forms (14 §9.2 custom fields, §10.5 buyer, gift and address).
// Pure: no SDK, no DOM. Every validator answers a message-code or null (valid); the codes are the
// `theme.errors.*` keys FIELD_REQUIRED / FIELD_INVALID (04 §11). The server validates again.

export const REQUIRED = 'FIELD_REQUIRED';
export const INVALID = 'FIELD_INVALID';

export const USERNAME_PATTERN = /^[A-Za-z0-9_.*]{1,32}$/;
export const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
export const DISCORD_ID_PATTERN = /^\d{17,20}$/;
export const E164_PATTERN = /^\+[1-9]\d{6,14}$/;
export const TR_IDENTITY_PATTERN = /^\d{11}$/;

export const DEFAULT_TEXT_MAX = 128;
export const EMAIL_MAX = 255;
export const ADDRESS_TEXT_MAX = 255;
export const POSTAL_CODE_MAX = 16;
export const IDENTITY_MAX = 32;
export const TAX_MAX = 32;
export const GIFT_MESSAGE_MAX = 255;

const isEmpty = (value) =>
  value === undefined || value === null || (typeof value === 'string' && value.trim() === '');

const asText = (value) => (typeof value === 'string' ? value.trim() : String(value ?? '').trim());

const limit = (value) => {
  const n = Number(value);
  return value !== null && value !== undefined && value !== '' && Number.isFinite(n) ? n : null;
};

/** A pattern of the admin is anchored as `^(?:<pattern>)$`; an uncompilable one is skipped (null). */
export function compilePattern(pattern) {
  if (typeof pattern !== 'string' || pattern === '') return null;

  try {
    return new RegExp(`^(?:${pattern})$`);
  } catch (e) {
    return null;
  }
}

/** Value of a field as sent to the cart: trimmed text, integer number, boolean. null = omit (empty). */
export function normalizeValue(field, value) {
  if (field?.type === 'CHECKBOX') return value === true ? true : null;
  if (isEmpty(value)) return null;

  if (field?.type === 'NUMBER') {
    const n = typeof value === 'number' ? value : Number(String(value).trim());
    return Number.isFinite(n) ? n : null;
  }

  return asText(value);
}

/**
 * Validates one custom field of a product (`fields[]` of ProductDetail). `value` is the raw control value.
 * Returns `FIELD_REQUIRED`, `FIELD_INVALID` or null.
 */
export function validateField(field, value) {
  const type = field?.type || 'TEXT';
  const required = field?.required === true;

  if (type === 'CHECKBOX') return required && value !== true ? REQUIRED : null;

  if (isEmpty(value)) return required ? REQUIRED : null;

  if (type === 'NUMBER') {
    const text = typeof value === 'number' ? String(value) : String(value).trim();
    if (!/^-?\d+$/.test(text)) return INVALID;

    const n = Number(text);
    const min = limit(field?.minValue);
    const max = limit(field?.maxValue);
    if (!Number.isSafeInteger(n) || (min !== null && n < min) || (max !== null && n > max))
      return INVALID;

    return null;
  }

  const text = asText(value);

  if (type === 'SELECT') {
    const options = Array.isArray(field?.options) ? field.options : [];
    return options.some((option) => String(option?.value ?? option) === text) ? null : INVALID;
  }

  if (type === 'USERNAME') return USERNAME_PATTERN.test(text) ? null : INVALID;
  if (type === 'EMAIL')
    return text.length <= EMAIL_MAX && EMAIL_PATTERN.test(text) ? null : INVALID;
  if (type === 'DISCORD_ID') return DISCORD_ID_PATTERN.test(text) ? null : INVALID;

  // TEXT (and any type this theme does not know yet: treated as text)
  const min = limit(field?.minLength);
  const max = limit(field?.maxLength) ?? DEFAULT_TEXT_MAX;
  if (text.length > max || (min !== null && text.length < min)) return INVALID;

  const re = compilePattern(field?.pattern);
  if (re && !re.test(text)) return INVALID;

  return null;
}

/** { <fieldKey>: code } for every invalid field of `fields`, in field order (insertion order). */
export function validateFields(fields, values) {
  const errors = {};

  for (const field of Array.isArray(fields) ? fields : []) {
    const code = validateField(field, values?.[field.fieldKey]);
    if (code) errors[field.fieldKey] = code;
  }

  return errors;
}

/** The wire `fieldValues` of a cart line: trimmed, optional empty values omitted. */
export function toFieldValues(fields, values) {
  const out = {};

  for (const field of Array.isArray(fields) ? fields : []) {
    const value = normalizeValue(field, values?.[field.fieldKey]);
    if (value !== null) out[field.fieldKey] = value;
  }

  return out;
}

/** Initial control values: `defaultValue` (CHECKBOX: boolean, NUMBER: kept as typed, others: text). */
export function initialFieldValues(fields) {
  const out = {};

  for (const field of Array.isArray(fields) ? fields : []) {
    const given = field?.defaultValue;

    if (field?.type === 'CHECKBOX') out[field.fieldKey] = given === true || given === 'true';
    else out[field.fieldKey] = given === null || given === undefined ? '' : String(given);
  }

  return out;
}

// ---- buyer, gift, address (used by the checkout slices) ----------------------------------------------

export function validateUsername(value, { required = true } = {}) {
  if (isEmpty(value)) return required ? REQUIRED : null;

  return USERNAME_PATTERN.test(asText(value)) ? null : INVALID;
}

export function validateEmail(value, { required = true } = {}) {
  if (isEmpty(value)) return required ? REQUIRED : null;

  const text = asText(value);

  return text.length <= EMAIL_MAX && EMAIL_PATTERN.test(text) ? null : INVALID;
}

/** E.164: `+` followed by 7 to 15 digits, no leading zero. */
export function validatePhone(value, { required = false } = {}) {
  if (isEmpty(value)) return required ? REQUIRED : null;

  return E164_PATTERN.test(asText(value)) ? null : INVALID;
}

/** Turkish identity number: 11 digits; any other country: up to 32 characters. */
export function validateIdentityNumber(value, country, { required = false } = {}) {
  if (isEmpty(value)) return required ? REQUIRED : null;

  const text = asText(value);

  if (country === 'TR') return TR_IDENTITY_PATTERN.test(text) ? null : INVALID;

  return text.length <= IDENTITY_MAX ? null : INVALID;
}

/** Gift recipient: required, username pattern, and not the buyer (case-insensitive) => GIFT_SELF. */
export function validateGiftRecipient(value, ownUsername) {
  if (isEmpty(value)) return REQUIRED;

  const text = asText(value);
  if (!USERNAME_PATTERN.test(text)) return INVALID;

  const own = asText(ownUsername).toLowerCase();
  if (own && own === text.toLowerCase()) return 'GIFT_SELF';

  return null;
}

export function validateGiftMessage(value) {
  return typeof value === 'string' && value.length > GIFT_MESSAGE_MAX ? INVALID : null;
}

/**
 * Required address fields of a country: `addressFields[country]`, else `addressFields['*']`, plus the
 * caller's additions. checkout/config `addressFields` maps a country (or `*`) to a list of field names.
 */
export function requiredAddressFields(addressFields, country, extra = []) {
  const map = addressFields && typeof addressFields === 'object' ? addressFields : {};
  const base = Array.isArray(map[country]) ? map[country] : Array.isArray(map['*']) ? map['*'] : [];

  return new Set([...base, ...extra]);
}

/**
 * { <field>: code } for an address. `required` = Set of field names; `countries` = the allowed country
 * codes of the select (null = any). Every text is at most 255 characters, `postalCode` at most 16.
 */
export function validateAddress(address, required = new Set(), countries = null) {
  const errors = {};
  const value = address && typeof address === 'object' ? address : {};
  const fields = [
    'firstName',
    'lastName',
    'company',
    'phone',
    'country',
    'state',
    'city',
    'district',
    'neighborhood',
    'line1',
    'line2',
    'postalCode',
  ];

  for (const field of fields) {
    const raw = value[field];

    if (isEmpty(raw)) {
      if (required.has(field)) errors[field] = REQUIRED;
      continue;
    }

    const text = asText(raw);

    if (field === 'phone') {
      if (!E164_PATTERN.test(text)) errors[field] = INVALID;
    } else if (field === 'country') {
      if (Array.isArray(countries) && !countries.includes(text)) errors[field] = INVALID;
    } else if (text.length > (field === 'postalCode' ? POSTAL_CODE_MAX : ADDRESS_TEXT_MAX)) {
      errors[field] = INVALID;
    }
  }

  return errors;
}
