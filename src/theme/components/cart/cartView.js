// Pure view helpers of the cart UI (14 §7): which rows to show, subtotal, error text keys, the NavCart
// count source and its visibility. No SDK import and no browser global, so bun tests run them directly.
import { COUNT_KEY, countOf, parseCountCache, parseStored } from '../../lib/cartModel.js';
import { lineKey } from '../../lib/lineKey.js';

export { COUNT_KEY };

/** Line level codes with their own `theme.errors.<CODE>` text; anything else falls back to GENERIC. */
export const LINE_ERROR_CODES = [
  'PRODUCT_UNAVAILABLE',
  'VARIANT_REQUIRED',
  'VARIANT_UNAVAILABLE',
  'OUT_OF_STOCK',
  'QUANTITY_REDUCED',
  'MAX_QUANTITY',
  'PURCHASE_LIMIT_REACHED',
  'COOLDOWN_ACTIVE',
  'REQUIREMENT_NOT_MET',
  'PERMISSION_REQUIRED',
  'ALREADY_OWNED',
  'FIELD_REQUIRED',
  'FIELD_INVALID',
  'SERVER_REQUIRED',
  'SERVER_UNAVAILABLE',
  'GIFT_NOT_ALLOWED',
  'LOGIN_REQUIRED',
  'NOT_IN_CURRENCY',
  'CREDITS_ONLY',
  'NOT_PAYABLE_WITH_CREDITS',
  'PHYSICAL_NOT_SUPPORTED',
  'RECURRING_NOT_SUPPORTED',
  'BUYER_BLOCKED',
  'SUBSCRIPTION_MUST_BE_ALONE',
];

export const errorKey = (code) =>
  LINE_ERROR_CODES.includes(code) ? `theme.errors.${code}` : 'theme.errors.GENERIC';

/** Which body the offcanvas shows (table of 14 §7.1). Order matters: the first match wins. */
export function viewState(s) {
  if (!s || s.mode === 'NONE' || s.status === 'LOADING') return 'LOADING';
  if (s.count === 0 && s.status !== 'ERROR') return 'EMPTY';
  if (s.status === 'ERROR') return s.lines.length ? 'ERROR_ROWS' : 'ERROR';
  if (!s.quote || s.quoteStale) return 'META_ROWS';
  return 'QUOTE_ROWS';
}

/** `errors` of a row plus the notice for a line the theme lowered to the available stock (`reduced` = lineKeys of the cart state). */
const withReduced = (errors, key, reduced) =>
  Array.isArray(reduced) && reduced.includes(key) && !errors.includes('QUANTITY_REDUCED')
    ? [...errors, 'QUANTITY_REDUCED']
    : errors;

/** Rows drawn from the local lines' display meta (no quote yet, or a stale one). */
export function metaRows(lines, reduced = []) {
  return lines.map((line) => ({
    key: lineKey(line),
    slug: line.meta?.slug || '',
    name: line.meta?.name || '',
    variantName: line.meta?.variantName || '',
    imageFileName: line.meta?.imageFileName || '',
    quantity: line.quantity,
    maxQuantity: null,
    unitPrice: line.meta?.price ?? 0,
    listUnitPrice: null,
    creditUnitPrice: null,
    lineTotal: (Number(line.meta?.price) || 0) * line.quantity,
    fieldValues: line.fieldValues || {},
    targetServerId: line.targetServerId ?? null,
    errors: withReduced([], lineKey(line), reduced),
  }));
}

/** Rows of a quote; bundle children are part of their bundle line and not shown on their own. */
export function quoteRows(quote, reduced = []) {
  return (quote?.lines || [])
    .filter((q) => q.kind !== 'BUNDLE_CHILD')
    .map((q) => ({
      key: lineKey(q),
      slug: q.slug || '',
      name: q.name || '',
      variantName: q.variantName || '',
      imageFileName: q.imageFileName || '',
      quantity: q.quantity,
      maxQuantity: Number(q.maxQuantity) >= 1 ? Number(q.maxQuantity) : null,
      unitPrice: q.unitPrice,
      listUnitPrice: q.listUnitPrice,
      creditUnitPrice: q.creditUnitPrice ?? null,
      lineTotal: q.lineTotal,
      fieldValues: q.fieldValues || {},
      targetServerId: q.targetServerId ?? null,
      errors: withReduced(Array.isArray(q.errors) ? q.errors : [], lineKey(q), reduced),
    }));
}

/** Subtotal of the cart footer: the sum of `lines[].lineTotal`, in cents so decimals never drift. */
export function subtotalOf(quote) {
  const cents = (quote?.lines || []).reduce(
    (sum, q) => sum + Math.round((Number(q.lineTotal) || 0) * 100),
    0,
  );
  return cents / 100;
}

export const isDiscounted = (row) =>
  row.listUnitPrice !== null &&
  row.listUnitPrice !== undefined &&
  Number(row.unitPrice) < Number(row.listUnitPrice);

/** Credit price badge: only with a credit price on the line and credits enabled. */
export const showCredit = (row, creditsEnabled) =>
  Boolean(creditsEnabled) && row.creditUnitPrice !== null && row.creditUnitPrice !== undefined;

/** Field values as "label: value" pairs, empty values dropped, booleans as yes/no words supplied by the caller. */
export function fieldPairs(fieldValues, labels = {}, yesNo = { true: 'yes', false: 'no' }) {
  return Object.entries(fieldValues || {})
    .filter(([, v]) => v !== '' && v !== null && v !== undefined)
    .map(([key, v]) => ({
      label: labels[key] || key,
      value: typeof v === 'boolean' ? yesNo[String(v)] : String(v),
    }));
}

/** NavCart is drawn only with items or on /store pages. */
export const navVisible = (count, pathname) =>
  count > 0 || /^\/store(\/|$)/.test(String(pathname || ''));

/**
 * Where NavCart gets its count (14 §7.3): the live cart once initialised; guests read the local cart (no
 * request); a logged-in user reads the 60 s session cache, else asks `me/summary` once.
 * -> { count } | { fetch: true }
 */
export function resolveNavCount({ mode, count, user, localRaw, cacheRaw, now }) {
  if (mode && mode !== 'NONE') return { count };
  if (!user) return { count: countOf(parseStored(localRaw).items) };

  const cached = parseCountCache(cacheRaw, user.id ?? null, now);
  return cached === null ? { fetch: true } : { count: cached };
}

export const countCacheValue = (user, count, at) =>
  JSON.stringify({ userId: user?.id ?? null, count, at });
