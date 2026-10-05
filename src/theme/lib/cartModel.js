// Pure cart model (14 §6): guest storage v2 with legacy migration, line operations, quote clamping and
// the subscription replace decision. Imports nothing from the SDK and touches no browser global.
import { lineKey } from './lineKey.js';

export const STORAGE_KEY = 'pano-plugin-market-cart';
export const COUNT_KEY = 'pano-plugin-market-count';
export const VERSION = 2;
export const MAX_QUANTITY = 99;
export const MAX_LINES = 50;
export const MAX_FIELD_KEYS = 20;
export const COUNT_CACHE_MS = 60_000;

const BILLING_MODES = ['ONE_TIME', 'TIMED', 'SUBSCRIPTION'];

const isInt = (n) => typeof n === 'number' && Number.isInteger(n);
const isPlainObject = (o) => o !== null && typeof o === 'object' && !Array.isArray(o);
const str = (value, max = 200) => (typeof value === 'string' ? value.slice(0, max) : '');

function cleanFieldValues(raw) {
  if (raw === undefined || raw === null) return {};
  if (!isPlainObject(raw)) return null;

  const keys = Object.keys(raw);
  if (keys.length > MAX_FIELD_KEYS) return null;

  const out = {};

  for (const key of keys) {
    const v = raw[key];
    if (
      typeof v !== 'string' &&
      typeof v !== 'boolean' &&
      !(typeof v === 'number' && Number.isFinite(v))
    )
      return null;
    out[key] = v;
  }

  return out;
}

function cleanMeta(raw) {
  if (!isPlainObject(raw)) return undefined;

  return {
    name: str(raw.name),
    variantName: str(raw.variantName),
    slug: str(raw.slug),
    imageFileName: str(raw.imageFileName, 255),
    price: typeof raw.price === 'number' && Number.isFinite(raw.price) ? raw.price : 0,
    billingMode: BILLING_MODES.includes(raw.billingMode) ? raw.billingMode : 'ONE_TIME',
  };
}

/** One stored version-2 item, or null when it fails validation (14 §6.2 rule 3). */
function normalizeV2Item(item) {
  if (!isPlainObject(item)) return null;

  const variantId = item.variantId === undefined || item.variantId === null ? 0 : item.variantId;
  const targetServerId = item.targetServerId === undefined ? null : item.targetServerId;
  const fieldValues = cleanFieldValues(item.fieldValues);

  if (!isInt(item.productId) || item.productId <= 0) return null;
  if (!isInt(variantId) || variantId < 0) return null;
  if (!isInt(item.quantity) || item.quantity < 1 || item.quantity > MAX_QUANTITY) return null;
  if (fieldValues === null) return null;
  if (targetServerId !== null && !isInt(targetServerId)) return null;

  const line = {
    productId: item.productId,
    variantId,
    quantity: item.quantity,
    fieldValues,
    targetServerId,
  };
  const meta = cleanMeta(item.meta);
  if (meta) line.meta = meta;

  return line;
}

/** One legacy `{ productId, quantity }` item, or null (numeric productId, quantity floored and > 0). */
function normalizeLegacyItem(item) {
  if (!item || typeof item.productId !== 'number' || typeof item.quantity !== 'number') return null;
  if (!isInt(item.productId) || item.productId <= 0) return null;

  const quantity = Math.floor(item.quantity);
  if (!(quantity > 0)) return null;

  return {
    productId: item.productId,
    variantId: 0,
    quantity: Math.min(quantity, MAX_QUANTITY),
    fieldValues: {},
    targetServerId: null,
  };
}

/** Lines with an equal lineKey are merged (quantities added, capped at 99); at most 50 lines survive. */
export function mergeLines(lines) {
  const byKey = new Map();

  for (const line of lines) {
    const key = lineKey(line);
    const existing = byKey.get(key);

    if (existing) existing.quantity = Math.min(existing.quantity + line.quantity, MAX_QUANTITY);
    else byKey.set(key, { ...line });
  }

  return [...byKey.values()].slice(0, MAX_LINES);
}

/** Parses the localStorage value (14 §6.2). Always returns `{ v: 2, items }`; never throws. */
export function parseStored(raw) {
  const empty = { v: VERSION, items: [] };

  if (typeof raw !== 'string' || raw === '') return empty;

  let parsed;
  try {
    parsed = JSON.parse(raw);
  } catch (e) {
    return empty;
  }

  if (Array.isArray(parsed))
    return { v: VERSION, items: mergeLines(parsed.map(normalizeLegacyItem).filter(Boolean)) };

  if (isPlainObject(parsed) && parsed.v === VERSION && Array.isArray(parsed.items))
    return { v: VERSION, items: mergeLines(parsed.items.map(normalizeV2Item).filter(Boolean)) };

  return empty;
}

/** The value written to localStorage: `itemId` and other runtime-only keys are not persisted. */
export function serialize(lines) {
  return JSON.stringify({
    v: VERSION,
    items: lines.map((l) => {
      const out = {
        productId: l.productId,
        variantId: l.variantId || 0,
        quantity: l.quantity,
        fieldValues: l.fieldValues || {},
        targetServerId: l.targetServerId ?? null,
      };
      if (l.meta) out.meta = l.meta;
      return out;
    }),
  });
}

/** Wire shape (`CartLine` of 04 §2): no meta, no local keys, optional members only when set. */
export function toWire(line) {
  const out = { productId: line.productId, quantity: line.quantity };

  if (line.variantId) out.variantId = line.variantId;
  if (line.fieldValues && Object.keys(line.fieldValues).length)
    out.fieldValues = { ...line.fieldValues };
  if (line.targetServerId) out.targetServerId = line.targetServerId;

  return out;
}

export const toWireItems = (lines) => lines.map(toWire);

export const countOf = (lines) => lines.reduce((sum, l) => sum + (Number(l.quantity) || 0), 0);

/** Display-only data shown before the first quote arrives. */
export function buildMeta(product, variantName = '') {
  return cleanMeta({
    name: product?.name,
    variantName: variantName || product?.variantName || '',
    slug: product?.slug,
    imageFileName: product?.imageFileName,
    price: product?.price,
    billingMode: product?.billingMode,
  });
}

/** Adds a line, merging into an identical one. `full` = the 50-line limit stopped a new line. */
export function addLine(lines, line) {
  const key = lineKey(line);
  const index = lines.findIndex((l) => lineKey(l) === key);

  if (index >= 0) {
    const next = lines.map((l) => ({ ...l }));
    next[index].quantity = Math.min(next[index].quantity + line.quantity, MAX_QUANTITY);
    return { lines: next, full: false };
  }

  if (lines.length >= MAX_LINES) return { lines, full: true };

  return { lines: [...lines, { ...line }], full: false };
}

export function removeLine(lines, key) {
  return lines.filter((l) => lineKey(l) !== key);
}

/**
 * Patches the line `key`: quantity (<= 0 removes it, clamped to 99), fieldValues, targetServerId.
 * A patch that makes two lines identical merges them.
 */
export function updateLine(lines, key, patch) {
  if (!lines.some((l) => lineKey(l) === key)) return lines;

  const next = [];

  for (const l of lines) {
    if (lineKey(l) !== key) {
      next.push({ ...l });
      continue;
    }

    const updated = { ...l };
    if ('quantity' in patch)
      updated.quantity = Math.min(Math.floor(Number(patch.quantity)), MAX_QUANTITY);
    if ('fieldValues' in patch) updated.fieldValues = patch.fieldValues || {};
    if ('targetServerId' in patch) updated.targetServerId = patch.targetServerId ?? null;

    if (!(updated.quantity >= 1)) continue;
    next.push(updated);
  }

  return mergeLines(next);
}

/** Matches the quote line of a local line by lineKey over the quote line's own members (bundle children never match). */
export function findQuoteLine(quote, line) {
  const key = lineKey(line);

  return (quote?.lines || []).find((q) => q.kind !== 'BUNDLE_CHILD' && lineKey(q) === key) || null;
}

/**
 * Clamps guest quantities after a quote (14 §6.4): QUANTITY_REDUCED or quantity > maxQuantity >= 1 sets the
 * quantity to maxQuantity. Unavailable lines are kept. `changed` = some quantity moved; `stale` = a moved
 * quantity no longer matches the quote line it was priced with (the quote must be asked again).
 */
export function applyQuote(lines, quote) {
  let changed = false;
  let stale = false;

  const next = lines.map((line) => {
    const q = findQuoteLine(quote, line);
    if (!q) return line;

    const max = Number(q.maxQuantity);
    const reduced = Array.isArray(q.errors) && q.errors.includes('QUANTITY_REDUCED');

    if (!(max >= 1) || !(reduced || line.quantity > max)) return line;

    const quantity = Math.min(Math.floor(max), MAX_QUANTITY);
    if (quantity === line.quantity) return line;

    changed = true;
    if (q.quantity !== quantity) stale = true;

    return { ...line, quantity };
  });

  return { lines: changed ? next : lines, changed, stale };
}

function billingModeOf(line, mode, quote) {
  if (mode === 'SERVER') {
    const q = findQuoteLine(quote, line);
    if (q?.billingMode) return q.billingMode;
  }

  return line.meta?.billingMode || 'ONE_TIME';
}

/**
 * Replace decision of `cart.add` (14 §6.5, 00 §7.5: a subscription order holds one item).
 *   ADD               cart empty, or no subscription involved -> merge by lineKey
 *   REPLACE           a subscription meets other content -> ask, then replace the cart with the new line
 *   ALREADY_IN_CART   the cart is exactly this subscription line -> nothing to do
 * `state` = { mode, lines, quote, count }.
 */
export function replaceDecision(state, line) {
  const lines = state?.lines || [];

  if (!lines.length) return 'ADD';

  const incomingSub = line.meta?.billingMode === 'SUBSCRIPTION';
  const cartSub =
    lines.some((l) => billingModeOf(l, state.mode, state.quote) === 'SUBSCRIPTION') ||
    (state.mode === 'SERVER' &&
      (state.quote?.lines || []).some((q) => q.billingMode === 'SUBSCRIPTION'));

  if (!incomingSub && !cartSub) return 'ADD';
  if (lines.length === 1 && lineKey(lines[0]) === lineKey(line)) return 'ALREADY_IN_CART';

  return 'REPLACE';
}

/** Local lines from a server cart response (`cart.items`), with display meta taken from the quote. */
export function linesFromServer(items, quote) {
  return (Array.isArray(items) ? items : []).map((item) => {
    const line = {
      itemId: item.id,
      productId: item.productId,
      variantId: item.variantId || 0,
      quantity: item.quantity,
      fieldValues: isPlainObject(item.fieldValues) ? item.fieldValues : {},
      targetServerId: item.targetServerId ?? null,
    };
    const q = findQuoteLine(quote, line);

    if (q)
      line.meta = {
        name: str(q.name),
        variantName: str(q.variantName),
        slug: str(q.slug),
        imageFileName: str(q.imageFileName, 255),
        price: typeof q.unitPrice === 'number' ? q.unitPrice : 0,
        billingMode: BILLING_MODES.includes(q.billingMode) ? q.billingMode : 'ONE_TIME',
      };

    return line;
  });
}

/** True when the quote carries at least one message with a code (a merge dropped or changed lines). */
export const hasMessageCodes = (quote) =>
  Array.isArray(quote?.messages) && quote.messages.some((m) => m && m.code);

/** The cached item count of NavCart (sessionStorage, valid 60 s for the same user), or null. */
export function parseCountCache(raw, userId, now) {
  try {
    const parsed = typeof raw === 'string' ? JSON.parse(raw) : null;

    if (!isPlainObject(parsed)) return null;
    if (String(parsed.userId) !== String(userId)) return null;
    if (!(typeof parsed.count === 'number' && parsed.count >= 0) || typeof parsed.at !== 'number')
      return null;
    if (now - parsed.at > COUNT_CACHE_MS || now < parsed.at) return null;

    return parsed.count;
  } catch (e) {
    return null;
  }
}
