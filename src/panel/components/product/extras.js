// Pure validation and helpers of the second half of the product form (13 §8.5-8.9): custom fields,
// shipping, limits, SEO, provider meta and actions. `validateExtras` is merged into
// `validateProduct` (model.js) so the one save path validates every tab. No Svelte, no SDK import.
import { validateActions } from '../../utils/actions.js';
import { validateFields } from './fields.js';
import { validateProviderMeta } from './provider-meta.js';
import { COUNTRY_CODES } from './countries.js';

export const HS_CODE_PATTERN = /^[0-9.]{4,16}$/;
export const COOLDOWN_UNITS = ['MINUTE', 'HOUR', 'DAY'];
const UNIT_SECONDS = { MINUTE: 60, HOUR: 3600, DAY: 86400 };

const isSet = (value) => value !== null && value !== undefined && value !== '';
const intIssue = (value, min) => {
  const n = Number(value);
  if (!Number.isInteger(n)) return 'NOT_INTEGER';
  return n < min ? 'OUT_OF_RANGE' : null;
};

/** Shipping scalars of 13 §8.6 (weight is validated by the shell). Only for physical products. */
export function validateShipping(product) {
  const errors = {};
  if (!product.physical) return errors;
  if (isSet(product.weightGrams)) {
    const issue = intIssue(product.weightGrams, 1);
    if (issue) errors.weightGrams = issue;
  }
  const dims = ['lengthMm', 'widthMm', 'heightMm'];
  const given = dims.filter((name) => isSet(product[name]));
  for (const name of given) {
    const issue = intIssue(product[name], 1);
    if (issue) errors[name] = issue;
  }
  if (given.length > 0 && given.length < dims.length) {
    for (const name of dims) if (!isSet(product[name])) errors[name] = 'ALL_OR_NONE';
  }
  if (String(product.sku ?? '').length > 64) errors.sku = 'TOO_LONG';
  if (isSet(product.hsCode) && !HS_CODE_PATTERN.test(String(product.hsCode)))
    errors.hsCode = 'INVALID_FORMAT';
  if (isSet(product.originCountry) && !COUNTRY_CODES.includes(String(product.originCountry)))
    errors.originCountry = 'INVALID';
  return errors;
}

export function validateSeo(product) {
  const errors = {};
  if (String(product.metaTitle ?? '').length > 255) errors.metaTitle = 'TOO_LONG';
  if (String(product.metaDescription ?? '').length > 512) errors.metaDescription = 'TOO_LONG';
  return errors;
}

/** True when `maxQuantityPerOrder` is fixed to 1 (13 §8.7): not one-time, or a tiered category. */
export function maxQuantityLocked(product) {
  return product.billingMode !== 'ONE_TIME' || isSet(product.tierRank);
}

/** Limits: the integer rules (the shell checks the same three) and the permission node. */
export function validateLimits(product) {
  const errors = {};
  for (const name of ['limitPerPlayer', 'maxQuantityPerOrder', 'cooldownSeconds']) {
    if (!isSet(product[name])) continue;
    const issue = intIssue(product[name], 1);
    if (issue) errors[name] = issue;
  }
  if (String(product.requiredPermission ?? '').length > 255) errors.requiredPermission = 'TOO_LONG';
  return errors;
}

/** Cooldown seconds -> the largest unit that divides them evenly (13 §8.7). */
export function splitCooldown(seconds) {
  if (!isSet(seconds) || !Number.isFinite(Number(seconds)) || Number(seconds) <= 0)
    return { value: '', unit: 'MINUTE' };
  const n = Number(seconds);
  for (const unit of ['DAY', 'HOUR', 'MINUTE']) {
    if (n % UNIT_SECONDS[unit] === 0) return { value: n / UNIT_SECONDS[unit], unit };
  }
  // not a whole number of minutes: keep the exact value in minutes, rounded up (never shorter)
  return { value: Math.ceil(n / 60), unit: 'MINUTE' };
}

/** Typed number + unit -> seconds, null when empty, NaN when not a positive whole number. */
export function joinCooldown(value, unit) {
  if (!isSet(value)) return null;
  const n = Number(value);
  if (!Number.isInteger(n) || n < 1) return NaN;
  return n * (UNIT_SECONDS[unit] ?? 60);
}

/**
 * Everything the tabs of MPU-08 own, in one map of dotted paths -> code. `servers` is the platform
 * server list or null when unknown (ids are then not checked). `ctx` supplies `productMetaSchemas`.
 */
export function validateExtras(product, ctx = null, { servers = null } = {}) {
  return {
    ...validateFields(product.fields),
    ...validateShipping(product),
    ...validateLimits(product),
    ...validateSeo(product),
    ...validateProviderMeta(ctx?.productMetaSchemas ?? [], product.providerMeta),
    ...validateActions(product.actions, product, servers),
  };
}

const EXTRA_ERROR_CODES = [
  'REQUIRED',
  'TOO_LONG',
  'INVALID',
  'INVALID_FORMAT',
  'OUT_OF_RANGE',
  'NOT_INTEGER',
  'ALL_OR_NONE',
];

/** Locale key of a code raised by the shipping / limits / SEO checks; unknown codes read as invalid. */
export function extraErrorKey(code) {
  return `pages.create-product.field-errors.${EXTRA_ERROR_CODES.includes(code) ? code : 'INVALID'}`;
}
