// Pure model of the product form (13 §8): state defaults, loading from the API, forced field
// rules, client validation with dotted error paths, the multipart payload of 04 §5 and the
// mapping of server `fieldErrors` back onto inputs and tabs. No Svelte, no SDK import.
import { toEpoch, toLocalInput } from '../../utils/format.js';
import { slugError } from '../../utils/validate.js';
import { MAX_AXES, MAX_AXIS_VALUES, MAX_VARIANTS, blankVariant } from '../../utils/variants.js';

export const KINDS = ['STANDARD', 'BUNDLE', 'CREDIT_PACK'];
export const STATUSES = ['ACTIVE', 'INACTIVE', 'ARCHIVED'];
export const BILLING_MODES = ['ONE_TIME', 'TIMED', 'SUBSCRIPTION'];
export const TIMED_UNITS = ['MINUTE', 'HOUR', 'DAY', 'WEEK', 'MONTH', 'YEAR'];
export const SUBSCRIPTION_UNITS = ['DAY', 'WEEK', 'MONTH', 'YEAR'];

export const MAX_BUNDLE_ROWS = 50;
export const MAX_ATTRIBUTES = 20;
export const ATTRIBUTE_KEY_PATTERN = /^[a-z][a-z0-9_]{0,31}$/;

/** Tab ids in display order. */
export const TAB_IDS = [
  'general',
  'pricing',
  'variants',
  'fields',
  'bundle',
  'shipping',
  'limits',
  'providers',
  'seo',
  'actions',
];

/** Period units a billing mode offers (02 §5.1). ONE_TIME has none. */
export function unitsFor(billingMode) {
  if (billingMode === 'TIMED') return TIMED_UNITS;
  if (billingMode === 'SUBSCRIPTION') return SUBSCRIPTION_UNITS;
  return [];
}

const TR_MAP = { ç: 'c', ğ: 'g', ı: 'i', ö: 'o', ş: 's', ü: 'u' };

/** Slug derived from a name: `^[a-z0-9]+(?:-[a-z0-9]+)*$` or ''. */
export function slugify(text) {
  let value = String(text ?? '')
    .toLocaleLowerCase('en-US')
    .replace(/ı/g, 'i')
    .normalize('NFD')
    .replace(/[̀-ͯ]/g, '');
  for (const [from, to] of Object.entries(TR_MAP)) value = value.split(from).join(to);
  return value
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 255)
    .replace(/-+$/g, '');
}

/** Exponent of the base currency (decimals allowed); 2 when the context is missing. */
export function currencyExponent(ctx, code = ctx?.currency) {
  const found = (ctx?.currencies ?? []).find((c) => c.code === code);
  return found && Number.isInteger(found.exponent) ? found.exponent : 2;
}

/** True when per-currency prices are offered (13 §8.2). */
export function isMulti(ctx) {
  return ctx?.currencyMode === 'MULTI' && (ctx?.additionalCurrencies ?? []).length > 0;
}

/** One row per additional currency, in the context's order; existing values are kept. */
export function ensurePriceRows(rows, ctx) {
  const codes = (ctx?.additionalCurrencies ?? []).map((c) => (typeof c === 'string' ? c : c.code));
  return codes.map((code) => {
    const found = (rows ?? []).find((row) => row.currency === code);
    return {
      currency: code,
      price: found?.price ?? null,
      compareAtPrice: found?.compareAtPrice ?? null,
    };
  });
}

export function defaultProduct(ctx) {
  return {
    dbId: null,
    name: '',
    slug: '',
    shortDescription: '',
    description: '',
    categoryId: null,
    kind: 'STANDARD',
    status: 'ACTIVE',
    featured: false,
    physical: false,
    allowGift: true,
    tierRank: null,
    icon: 'fa-box',
    priority: 0,
    price: 0,
    compareAtPrice: null,
    creditPrice: 0,
    creditAmount: null,
    vatPercent: null,
    billingMode: 'ONE_TIME',
    periodCount: null,
    periodUnit: null,
    subscriptionMaxCycles: null,
    prices: ensurePriceRows([], ctx),
    hasStockLimit: false,
    stock: 0,
    saleWindow: false,
    durationStart: '',
    durationExpiry: '',
    requiredProducts: [],
    requireOnlyOne: false,
    requiredPermission: '',
    limitPerPlayer: null,
    maxQuantityPerOrder: null,
    cooldownSeconds: null,
    sku: '',
    weightGrams: null,
    lengthMm: null,
    widthMm: null,
    heightMm: null,
    hsCode: '',
    originCountry: '',
    hasVariants: false,
    variantOptions: [],
    variants: [],
    bundleItems: [],
    fields: [],
    providerMeta: {},
    serverChoices: [],
    actions: [],
    metaTitle: '',
    metaDescription: '',
  };
}

const orNull = (value) => (value === undefined ? null : value);

function attributeRows(attributes) {
  return Object.entries(attributes ?? {}).map(([key, value]) => ({ key, value: String(value) }));
}

function variantFromApi(v, ctx, priceRows) {
  return blankVariant({
    id: v.id,
    name: v.name ?? '',
    sku: v.sku ?? '',
    optionValues: { ...(v.optionValues ?? {}) },
    attributes: attributeRows(v.attributes),
    price: orNull(v.price),
    compareAtPrice: orNull(v.compareAtPrice),
    creditPrice: orNull(v.creditPrice),
    stock: orNull(v.stock),
    weightGrams: orNull(v.weightGrams),
    periodCount: orNull(v.periodCount),
    status: v.status === 'INACTIVE' ? 'INACTIVE' : 'ACTIVE',
    position: v.position ?? 0,
    prices: ensurePriceRows(priceRows, ctx),
    imageFileName: v.imageFileName ?? null,
  });
}

/** Maps `GET /products/:id` `product` onto the editable state. */
export function fromApi(p, ctx) {
  const base = defaultProduct(ctx);
  const allPrices = Array.isArray(p.prices) ? p.prices : [];
  const rowsOf = (variantId) => allPrices.filter((row) => (row.variantId ?? 0) === variantId);
  const variants = (p.variants ?? [])
    .map((v) => variantFromApi(v, ctx, rowsOf(v.id)))
    .sort((a, b) => a.position - b.position);

  return {
    ...base,
    dbId: p.id ?? null,
    name: p.name ?? '',
    slug: p.slug ?? '',
    shortDescription: p.shortDescription ?? '',
    description: p.description ?? '',
    categoryId: p.categoryId ?? null,
    kind: KINDS.includes(p.kind) ? p.kind : 'STANDARD',
    status: STATUSES.includes(p.status) ? p.status : 'ACTIVE',
    featured: !!p.featured,
    physical: !!p.physical,
    allowGift: p.allowGift !== false,
    tierRank: orNull(p.tierRank),
    icon: p.icon || 'fa-box',
    priority: p.priority ?? 0,
    price: p.price ?? 0,
    compareAtPrice: orNull(p.compareAtPrice),
    creditPrice: p.creditPrice ?? 0,
    creditAmount: orNull(p.creditAmount),
    vatPercent: orNull(p.vatPercent),
    billingMode: BILLING_MODES.includes(p.billingMode) ? p.billingMode : 'ONE_TIME',
    periodCount: orNull(p.periodCount),
    periodUnit: orNull(p.periodUnit),
    subscriptionMaxCycles: orNull(p.subscriptionMaxCycles),
    prices: ensurePriceRows(rowsOf(0), ctx),
    hasStockLimit: p.stock !== null && p.stock !== undefined,
    stock: p.stock ?? 0,
    saleWindow: p.durationType === 'TEMPORARY',
    durationStart: p.durationStart ? toLocalInput(p.durationStart) : '',
    durationExpiry: p.durationExpiry ? toLocalInput(p.durationExpiry) : '',
    requiredProducts: p.requiredProducts ?? [],
    requireOnlyOne: !!p.requireOnlyOne,
    requiredPermission: p.requiredPermission ?? '',
    limitPerPlayer: orNull(p.limitPerPlayer),
    maxQuantityPerOrder: orNull(p.maxQuantityPerOrder),
    cooldownSeconds: orNull(p.cooldownSeconds),
    sku: p.sku ?? '',
    weightGrams: orNull(p.weightGrams),
    lengthMm: orNull(p.lengthMm),
    widthMm: orNull(p.widthMm),
    heightMm: orNull(p.heightMm),
    hsCode: p.hsCode ?? '',
    originCountry: p.originCountry ?? '',
    hasVariants: !!p.hasVariants,
    variantOptions: (p.variantOptions ?? []).map((axis) => ({
      key: axis.key,
      label: axis.label ?? '',
      locked: true,
      values: (axis.values ?? []).map((value) => ({
        key: value.key,
        label: value.label ?? '',
        locked: true,
      })),
    })),
    variants,
    bundleItems: (p.bundleItems ?? []).map((row) => ({
      productId: row.productId,
      variantId: row.variantId ?? 0,
      quantity: row.quantity ?? 1,
      name: row.name ?? row.productName ?? '',
      variantName: row.variantName ?? '',
      hasVariants: !!row.hasVariants,
    })),
    fields: p.fields ?? [],
    providerMeta: p.providerMeta ?? {},
    serverChoices: p.serverChoices ?? [],
    actions: p.actions ?? [],
    metaTitle: p.metaTitle ?? '',
    metaDescription: p.metaDescription ?? '',
  };
}

/**
 * Applies the rules that force other fields (13 §8.1, §8.2, 04 §5) by mutating `product`; only
 * assigns on a difference so it is safe inside an effect. `category` is the selected category (or
 * null for none); `undefined` = categories unknown, tier rank left alone. Returns true when changed.
 */
export function applyRules(product, ctx, category) {
  let changed = false;
  const set = (key, value) => {
    if (product[key] !== value) {
      product[key] = value;
      changed = true;
    }
  };

  if (product.kind === 'CREDIT_PACK' || product.kind === 'BUNDLE') set('billingMode', 'ONE_TIME');
  if (product.kind !== 'STANDARD') set('physical', false);
  if (product.kind === 'CREDIT_PACK') {
    set('creditPrice', 0);
    set('hasVariants', false);
  }
  if (product.billingMode === 'SUBSCRIPTION') set('creditPrice', 0);
  if (product.billingMode !== 'ONE_TIME') set('physical', false);
  if (product.billingMode === 'ONE_TIME') {
    set('periodCount', null);
    set('periodUnit', null);
  }
  if (product.billingMode !== 'SUBSCRIPTION') set('subscriptionMaxCycles', null);
  if (product.billingMode !== 'ONE_TIME') {
    const units = unitsFor(product.billingMode);
    if (!units.includes(product.periodUnit)) set('periodUnit', 'DAY');
  }
  if (product.kind !== 'CREDIT_PACK') set('creditAmount', null);
  if (category !== undefined && category?.tiered !== true) set('tierRank', null);
  return changed;
}

/** Tab ids that are shown for this product (13 §8). */
export function visibleTabs(product, ctx) {
  return TAB_IDS.filter((id) => {
    if (id === 'bundle') return product.kind === 'BUNDLE';
    if (id === 'shipping') return !!product.physical;
    if (id === 'providers') return (ctx?.productMetaSchemas ?? []).length > 0;
    return true;
  });
}

/** Tab owning an error path; null for the always-visible sidebar fields. */
export function tabOfPath(path) {
  const head = String(path ?? '').split('.')[0];
  switch (head) {
    case 'name':
    case 'slug':
    case 'shortDescription':
    case 'description':
    case 'durationStart':
    case 'durationExpiry':
      return 'general';
    case 'price':
    case 'compareAtPrice':
    case 'creditPrice':
    case 'creditAmount':
    case 'vatPercent':
    case 'billingMode':
    case 'periodCount':
    case 'periodUnit':
    case 'subscriptionMaxCycles':
    case 'stock':
    case 'prices':
      return 'pricing';
    case 'variants':
    case 'variantOptions':
      return 'variants';
    case 'bundleItems':
      return 'bundle';
    case 'fields':
      return 'fields';
    case 'weightGrams':
    case 'lengthMm':
    case 'widthMm':
    case 'heightMm':
    case 'sku':
    case 'hsCode':
    case 'originCountry':
      return 'shipping';
    case 'limitPerPlayer':
    case 'maxQuantityPerOrder':
    case 'cooldownSeconds':
    case 'requiredProducts':
    case 'requiredPermission':
      return 'limits';
    case 'providerMeta':
      return 'providers';
    case 'metaTitle':
    case 'metaDescription':
      return 'seo';
    case 'actions':
    case 'serverChoices':
      return 'actions';
    default:
      return null;
  }
}

/** Distinct tabs (in display order) that contain at least one error path. */
export function tabsWithErrors(errors) {
  const found = new Set();
  for (const path of Object.keys(errors ?? {})) {
    const tab = tabOfPath(path);
    if (tab) found.add(tab);
  }
  return TAB_IDS.filter((id) => found.has(id));
}

/** First error path in tab order, then in path order of insertion. */
export function firstErrorPath(errors) {
  const paths = Object.keys(errors ?? {});
  if (paths.length === 0) return null;
  const rank = (path) => {
    const tab = tabOfPath(path);
    return tab === null ? -1 : TAB_IDS.indexOf(tab);
  };
  return [...paths].sort((a, b) => rank(a) - rank(b))[0];
}

const KNOWN_FIELD_ERRORS = new Set([
  'REQUIRED',
  'TOO_LONG',
  'INVALID',
  'INVALID_FORMAT',
  'RESERVED_SLUG',
  'SLUG_ALREADY_EXISTS',
  'OUT_OF_RANGE',
  'NOT_INTEGER',
  'MAX_DECIMALS',
  'COMPARE_NOT_ABOVE_PRICE',
  'REQUIRES_PRICE',
  'DUPLICATE',
  'TOO_MANY',
  'PHYSICAL_NOT_ALLOWED',
  'INVALID_DATE_RANGE',
  'NEEDS_ACTIVE_VARIANT',
  'NEEDS_ITEMS',
]);

export const FIELD_ERROR_CODES = [...KNOWN_FIELD_ERRORS];

/** Locale key (relative to the plugin root) of a field error code; unknown codes read as "invalid". */
export function fieldErrorKey(code) {
  return `pages.create-product.field-errors.${KNOWN_FIELD_ERRORS.has(code) ? code : 'INVALID'}`;
}

// ---- validation ---------------------------------------------------------------------------

const isNum = (value) => value !== null && value !== undefined && value !== '';

function checkInteger(value, { min, max } = {}) {
  if (!isNum(value)) return null;
  const n = Number(value);
  if (!Number.isInteger(n)) return 'NOT_INTEGER';
  if ((min !== undefined && n < min) || (max !== undefined && n > max)) return 'OUT_OF_RANGE';
  return null;
}

function checkMoney(value, exponent = 2) {
  if (!isNum(value)) return null;
  const n = Number(value);
  if (!Number.isFinite(n)) return 'INVALID';
  if (n < 0) return 'OUT_OF_RANGE';
  if (Math.round(n * 100) / 100 !== n) return 'MAX_DECIMALS';
  if (exponent === 0 && !Number.isInteger(n)) return 'MAX_DECIMALS';
  return null;
}

function validatePriceRows(rows, ctx, prefix, errors) {
  for (const row of rows ?? []) {
    const exponent = currencyExponent(ctx, row.currency);
    const p = `${prefix}.${row.currency}`;
    const priceError = checkMoney(row.price, exponent);
    if (priceError) errors[`${p}.price`] = priceError;
    const compareError = checkMoney(row.compareAtPrice, exponent);
    if (compareError) errors[`${p}.compareAtPrice`] = compareError;
    else if (isNum(row.compareAtPrice)) {
      if (!isNum(row.price)) errors[`${p}.compareAtPrice`] = 'REQUIRES_PRICE';
      else if (!priceError && !(Number(row.compareAtPrice) > Number(row.price)))
        errors[`${p}.compareAtPrice`] = 'COMPARE_NOT_ABOVE_PRICE';
    }
  }
}

/**
 * Client-side validation of every rule of 13 §8.1-8.4 (plus the server rules of 04 §5 that need no
 * lookup). Returns `{ errors }`, a map of dotted path -> code; empty = valid. Per-currency rows use
 * the currency code as the index: `prices.EUR.price`, `variants.2.prices.EUR.price`.
 *
 * `category` is the selected category (`{tiered}`) or null. `isEdit` is true for a saved product.
 */
export function validateProduct(product, ctx, { category = null, isEdit = false } = {}) {
  const errors = {};
  const exponent = currencyExponent(ctx);
  const multi = isMulti(ctx);

  // general
  const name = String(product.name ?? '').trim();
  if (name === '') errors.name = 'REQUIRED';
  else if (name.length > 255) errors.name = 'TOO_LONG';

  if (String(product.slug ?? '') === '') errors.slug = 'REQUIRED';
  else if (product.slug.length > 255) errors.slug = 'TOO_LONG';
  else {
    const issue = slugError(product.slug);
    if (issue) errors.slug = issue === 'RESERVED_SLUG' ? 'RESERVED_SLUG' : 'INVALID_FORMAT';
  }
  if (String(product.shortDescription ?? '').length > 512) errors.shortDescription = 'TOO_LONG';

  if (product.saleWindow) {
    const start = toEpoch(product.durationStart);
    const end = toEpoch(product.durationExpiry);
    if (start !== null && end !== null && end <= start)
      errors.durationExpiry = 'INVALID_DATE_RANGE';
  }

  if (category?.tiered === true) {
    if (!isNum(product.tierRank)) errors.tierRank = 'REQUIRED';
    else {
      const issue = checkInteger(product.tierRank, { min: 0 });
      if (issue) errors.tierRank = issue;
    }
  }

  // pricing
  if (!isNum(product.price)) errors.price = 'REQUIRED';
  else {
    const issue = checkMoney(product.price, exponent);
    if (issue) errors.price = issue;
  }
  const compareIssue = checkMoney(product.compareAtPrice, exponent);
  if (compareIssue) errors.compareAtPrice = compareIssue;
  else if (isNum(product.compareAtPrice) && !errors.price) {
    if (!(Number(product.compareAtPrice) > Number(product.price)))
      errors.compareAtPrice = 'COMPARE_NOT_ABOVE_PRICE';
  }

  if (
    ctx?.creditsEnabled &&
    product.kind !== 'CREDIT_PACK' &&
    product.billingMode !== 'SUBSCRIPTION'
  ) {
    const issue = checkMoney(product.creditPrice);
    if (issue) errors.creditPrice = issue;
  }
  if (product.kind === 'CREDIT_PACK') {
    if (!isNum(product.creditAmount)) errors.creditAmount = 'REQUIRED';
    else {
      const n = Number(product.creditAmount);
      if (!Number.isFinite(n) || n <= 0) errors.creditAmount = 'OUT_OF_RANGE';
      else if (Math.round(n * 100) / 100 !== n) errors.creditAmount = 'MAX_DECIMALS';
    }
  }

  if (isNum(product.vatPercent)) {
    const n = Number(product.vatPercent);
    if (!Number.isFinite(n) || n < 0 || n > 100) errors.vatPercent = 'OUT_OF_RANGE';
    else if (Math.round(n * 100) / 100 !== n) errors.vatPercent = 'MAX_DECIMALS';
  }

  if (product.billingMode !== 'ONE_TIME') {
    if (!isNum(product.periodCount)) errors.periodCount = 'REQUIRED';
    else {
      const issue = checkInteger(product.periodCount, { min: 1 });
      if (issue) errors.periodCount = issue;
    }
    if (!unitsFor(product.billingMode).includes(product.periodUnit)) errors.periodUnit = 'REQUIRED';
  }
  if (product.billingMode === 'SUBSCRIPTION' && isNum(product.subscriptionMaxCycles)) {
    const issue = checkInteger(product.subscriptionMaxCycles, { min: 1 });
    if (issue) errors.subscriptionMaxCycles = issue;
  }

  if (!isEdit && product.hasStockLimit && !product.hasVariants) {
    if (!isNum(product.stock)) errors.stock = 'REQUIRED';
    else {
      const issue = checkInteger(product.stock, { min: 0 });
      if (issue) errors.stock = issue;
    }
  }

  if (multi) validatePriceRows(product.prices, ctx, 'prices', errors);

  // limits and shipping numbers the shell owns the save for
  for (const key of ['limitPerPlayer', 'maxQuantityPerOrder', 'cooldownSeconds']) {
    const issue = checkInteger(product[key], { min: 1 });
    if (issue) errors[key] = issue;
  }
  if (product.physical) {
    if (!isNum(product.weightGrams)) errors.weightGrams = 'REQUIRED';
    else {
      const issue = checkInteger(product.weightGrams, { min: 1 });
      if (issue) errors.weightGrams = issue;
    }
  }

  // variants
  const axes = product.variantOptions ?? [];
  if (axes.length > MAX_AXES) errors.variantOptions = 'TOO_MANY';
  axes.forEach((axis, i) => {
    if (String(axis.label ?? '').trim() === '') errors[`variantOptions.${i}.label`] = 'REQUIRED';
    else if (axis.label.length > 255) errors[`variantOptions.${i}.label`] = 'TOO_LONG';
    if ((axis.values ?? []).length > MAX_AXIS_VALUES)
      errors[`variantOptions.${i}.values`] = 'TOO_MANY';
    (axis.values ?? []).forEach((value, j) => {
      if (String(value.label ?? '').trim() === '')
        errors[`variantOptions.${i}.values.${j}.label`] = 'REQUIRED';
      else if (value.label.length > 255)
        errors[`variantOptions.${i}.values.${j}.label`] = 'TOO_LONG';
    });
  });

  if (product.hasVariants) {
    const variants = product.variants ?? [];
    if (variants.length > MAX_VARIANTS) errors.variants = 'TOO_MANY';
    else if (!variants.some((v) => v.status === 'ACTIVE')) errors.variants = 'NEEDS_ACTIVE_VARIANT';

    const seen = new Map();
    variants.forEach((variant, i) => {
      const at = (field) => `variants.${i}.${field}`;
      const vName = String(variant.name ?? '').trim();
      if (vName === '') errors[at('name')] = 'REQUIRED';
      else if (vName.length > 255) errors[at('name')] = 'TOO_LONG';
      else {
        const lowered = vName.toLocaleLowerCase();
        if (seen.has(lowered)) {
          errors[at('name')] = 'DUPLICATE';
          errors[`variants.${seen.get(lowered)}.name`] = 'DUPLICATE';
        } else seen.set(lowered, i);
      }
      if (String(variant.sku ?? '').length > 64) errors[at('sku')] = 'TOO_LONG';

      const priceIssue = checkMoney(variant.price, exponent);
      if (priceIssue) errors[at('price')] = priceIssue;
      const effective = isNum(variant.price) ? Number(variant.price) : Number(product.price);
      const compare = checkMoney(variant.compareAtPrice, exponent);
      if (compare) errors[at('compareAtPrice')] = compare;
      else if (isNum(variant.compareAtPrice) && !priceIssue && Number.isFinite(effective)) {
        if (!(Number(variant.compareAtPrice) > effective))
          errors[at('compareAtPrice')] = 'COMPARE_NOT_ABOVE_PRICE';
      }
      if (ctx?.creditsEnabled && product.kind !== 'CREDIT_PACK') {
        const issue = checkMoney(variant.creditPrice);
        if (issue) errors[at('creditPrice')] = issue;
      }
      if (!variant.id) {
        const issue = checkInteger(variant.stock, { min: 0 });
        if (issue) errors[at('stock')] = issue;
      }
      if (product.physical) {
        const issue = checkInteger(variant.weightGrams, { min: 1 });
        if (issue) errors[at('weightGrams')] = issue;
      }
      if (product.billingMode !== 'ONE_TIME') {
        const issue = checkInteger(variant.periodCount, { min: 1 });
        if (issue) errors[at('periodCount')] = issue;
      }

      const attrs = variant.attributes ?? [];
      if (attrs.length > MAX_ATTRIBUTES) errors[at('attributes')] = 'TOO_MANY';
      const keys = new Set();
      attrs.forEach((row, j) => {
        if (!ATTRIBUTE_KEY_PATTERN.test(row.key ?? ''))
          errors[at(`attributes.${j}.key`)] = 'INVALID_FORMAT';
        else if (keys.has(row.key)) errors[at(`attributes.${j}.key`)] = 'DUPLICATE';
        keys.add(row.key);
        if (String(row.value ?? '').length > 255) errors[at(`attributes.${j}.value`)] = 'TOO_LONG';
      });

      if (multi) validatePriceRows(variant.prices, ctx, at('prices'), errors);
    });
  }

  // bundle
  if (product.kind === 'BUNDLE') {
    const rows = product.bundleItems ?? [];
    if (rows.length === 0) errors.bundleItems = 'NEEDS_ITEMS';
    else if (rows.length > MAX_BUNDLE_ROWS) errors.bundleItems = 'TOO_MANY';
    rows.forEach((row, i) => {
      if (!isNum(row.quantity)) errors[`bundleItems.${i}.quantity`] = 'REQUIRED';
      else {
        const issue = checkInteger(row.quantity, { min: 1, max: 99 });
        if (issue) errors[`bundleItems.${i}.quantity`] = issue;
      }
      if (row.hasVariants && !row.variantId) errors[`bundleItems.${i}.variantId`] = 'REQUIRED';
    });
  }

  return { errors };
}

// ---- bundle rows --------------------------------------------------------------------------

/**
 * Adds `{productId, variantId, quantity}` to the bundle rows; the same `(productId, variantId)`
 * merges quantities (capped at 99). Returns a new array.
 */
export function addBundleRow(rows, row) {
  const variantId = row.variantId ?? 0;
  const quantity = Math.max(1, Math.trunc(Number(row.quantity ?? 1)) || 1);
  const index = rows.findIndex(
    (r) => r.productId === row.productId && (r.variantId ?? 0) === variantId,
  );
  if (index === -1) return [...rows, { ...row, variantId, quantity: Math.min(99, quantity) }];
  return rows.map((r, i) =>
    i === index ? { ...r, quantity: Math.min(99, Number(r.quantity || 0) + quantity) } : r,
  );
}

/** Products a bundle may contain (13 §8.4): STANDARD, not a subscription, not the bundle itself. */
export function bundleCandidates(products, selfId) {
  return (products ?? []).filter(
    (p) =>
      (p.kind ?? 'STANDARD') === 'STANDARD' &&
      p.billingMode !== 'SUBSCRIPTION' &&
      (selfId === null || selfId === undefined || p.id !== selfId),
  );
}

// ---- payload ------------------------------------------------------------------------------

function priceWire(rows, ctx, variantId) {
  const allowed = new Set(
    (ctx?.additionalCurrencies ?? []).map((c) => (typeof c === 'string' ? c : c.code)),
  );
  return (rows ?? [])
    .filter((row) => allowed.has(row.currency) && isNum(row.price))
    .map((row) => ({
      ...(variantId === undefined ? {} : { variantId }),
      currency: row.currency,
      price: Number(row.price),
      compareAtPrice: isNum(row.compareAtPrice) ? Number(row.compareAtPrice) : null,
    }));
}

function variantWire(variant, index, ctx, multi) {
  const wire = {
    ...(variant.id ? { id: variant.id } : {}),
    name: String(variant.name).trim(),
    sku: variant.sku === '' ? null : variant.sku,
    optionValues: variant.optionValues ?? {},
    attributes: Object.fromEntries((variant.attributes ?? []).map((row) => [row.key, row.value])),
    price: isNum(variant.price) ? Number(variant.price) : null,
    compareAtPrice: isNum(variant.compareAtPrice) ? Number(variant.compareAtPrice) : null,
    creditPrice: isNum(variant.creditPrice) ? Number(variant.creditPrice) : null,
    weightGrams: isNum(variant.weightGrams) ? Number(variant.weightGrams) : null,
    periodCount: isNum(variant.periodCount) ? Number(variant.periodCount) : null,
    status: variant.status === 'INACTIVE' ? 'INACTIVE' : 'ACTIVE',
    position: index,
  };
  if (!variant.id) {
    wire.stock = isNum(variant.stock) ? Number(variant.stock) : null;
    if (multi) wire.prices = priceWire(variant.prices, ctx);
  }
  if (variant.removeImage && !variant.imageFile) wire.removeImage = true;
  return wire;
}

/**
 * Builds the save request (13 §8.10, 04 §5). Returns
 * `{ scalars: [[name, string]], json: { part: value }, files: [{ part, file }], pathMap }`.
 *
 * - nullable scalars that are empty are omitted (the PUT is a full form, as before);
 * - `stock` is sent on create only, `prices` only in MULTI mode (full replacement of the set);
 * - `pathMap` maps the server's indexed `prices.<i>...` / `variants.<i>.prices.<j>...` paths onto the
 *   currency-coded paths the inputs carry.
 */
export function buildPayload(
  product,
  ctx,
  { isEdit = false, imageFile = null, removeImage = false } = {},
) {
  const multi = isMulti(ctx);
  const scalars = [];
  const put = (name, value) => scalars.push([name, String(value)]);
  const putOpt = (name, value) => {
    if (isNum(value)) put(name, value);
  };

  put('name', String(product.name).trim());
  put('slug', product.slug);
  put('description', product.description ?? '');
  putOpt('shortDescription', product.shortDescription === '' ? null : product.shortDescription);
  put(
    'categoryId',
    product.categoryId === null || product.categoryId === undefined ? -1 : product.categoryId,
  );
  put('kind', product.kind);
  put('status', product.status);
  put('featured', !!product.featured);
  put('physical', !!product.physical);
  put('allowGift', !!product.allowGift);
  put('priority', product.priority || 0);
  put('icon', product.icon || 'fa-box');

  put('price', product.price);
  putOpt('compareAtPrice', product.compareAtPrice);
  put('creditPrice', isNum(product.creditPrice) ? product.creditPrice : 0);
  if (product.kind === 'CREDIT_PACK') put('creditAmount', product.creditAmount);
  putOpt('vatPercent', product.vatPercent);
  put('billingMode', product.billingMode);
  if (product.billingMode !== 'ONE_TIME') {
    put('periodCount', product.periodCount);
    put('periodUnit', product.periodUnit);
  }
  if (product.billingMode === 'SUBSCRIPTION')
    putOpt('subscriptionMaxCycles', product.subscriptionMaxCycles);
  if (product.tierRank !== null && product.tierRank !== undefined && product.tierRank !== '')
    put('tierRank', product.tierRank);

  if (!isEdit && product.hasStockLimit && !product.hasVariants) put('stock', product.stock || 0);

  put('durationType', product.saleWindow ? 'TEMPORARY' : 'LIFETIME');
  if (product.saleWindow) {
    const start = toEpoch(product.durationStart);
    const end = toEpoch(product.durationExpiry);
    if (start !== null) put('durationStart', start);
    if (end !== null) put('durationExpiry', end);
  }

  put('requireOnlyOne', !!product.requireOnlyOne);
  if (product.requiredPermission) put('requiredPermission', product.requiredPermission);
  putOpt('limitPerPlayer', product.limitPerPlayer);
  putOpt('maxQuantityPerOrder', product.maxQuantityPerOrder);
  putOpt('cooldownSeconds', product.cooldownSeconds);
  if (product.sku) put('sku', product.sku);
  putOpt('weightGrams', product.physical ? product.weightGrams : null);
  putOpt('lengthMm', product.physical ? product.lengthMm : null);
  putOpt('widthMm', product.physical ? product.widthMm : null);
  putOpt('heightMm', product.physical ? product.heightMm : null);
  if (product.physical && product.hsCode) put('hsCode', product.hsCode);
  if (product.physical && product.originCountry) put('originCountry', product.originCountry);
  if (product.metaTitle) put('metaTitle', product.metaTitle);
  if (product.metaDescription) put('metaDescription', product.metaDescription);
  put('hasVariants', !!product.hasVariants);
  if (isEdit && removeImage && !imageFile) put('removeImage', true);

  const json = {};
  const files = [];
  const pathMap = {};

  json.requiredProducts = product.requiredProducts ?? [];
  json.serverChoices = product.serverChoices ?? [];
  json.actions = product.actions ?? [];
  json.fields = (product.fields ?? []).map((row) => row);
  json.providerMeta = product.providerMeta ?? {};

  const axes = (product.variantOptions ?? []).map((axis) => ({
    key: axis.key,
    label: axis.label,
    values: (axis.values ?? []).map((value) => ({ key: value.key, label: value.label })),
  }));
  json.variantOptions = product.hasVariants ? axes : [];

  const variants = product.hasVariants ? (product.variants ?? []) : [];
  json.variants = variants.map((variant, i) => {
    if (variant.imageFile) files.push({ part: `variantImage_${i}`, file: variant.imageFile });
    return variantWire(variant, i, ctx, multi);
  });

  json.bundleItems =
    product.kind === 'BUNDLE'
      ? (product.bundleItems ?? []).map((row) => ({
          productId: row.productId,
          variantId: row.variantId ?? 0,
          quantity: Number(row.quantity),
        }))
      : [];

  if (multi) {
    const rows = priceWire(product.prices, ctx, 0);
    rows.forEach((row, i) => {
      pathMap[`prices.${i}.price`] = `prices.${row.currency}.price`;
      pathMap[`prices.${i}.compareAtPrice`] = `prices.${row.currency}.compareAtPrice`;
      pathMap[`prices.${i}.currency`] = `prices.${row.currency}.price`;
    });
    let offset = rows.length;
    const all = [...rows];
    if (product.hasVariants) {
      variants.forEach((variant, vi) => {
        if (!variant.id) return;
        priceWire(variant.prices, ctx, variant.id).forEach((row) => {
          pathMap[`prices.${offset}.price`] = `variants.${vi}.prices.${row.currency}.price`;
          pathMap[`prices.${offset}.compareAtPrice`] =
            `variants.${vi}.prices.${row.currency}.compareAtPrice`;
          pathMap[`prices.${offset}.currency`] = `variants.${vi}.prices.${row.currency}.price`;
          offset++;
          all.push(row);
        });
      });
    }
    json.prices = all;
    variants.forEach((variant, vi) => {
      if (variant.id) return;
      const wire = json.variants[vi].prices ?? [];
      wire.forEach((row, j) => {
        pathMap[`variants.${vi}.prices.${j}.price`] = `variants.${vi}.prices.${row.currency}.price`;
        pathMap[`variants.${vi}.prices.${j}.compareAtPrice`] =
          `variants.${vi}.prices.${row.currency}.compareAtPrice`;
        pathMap[`variants.${vi}.prices.${j}.currency`] =
          `variants.${vi}.prices.${row.currency}.price`;
      });
    });
  }

  if (imageFile) files.push({ part: 'image', file: imageFile });
  return { scalars, json, files, pathMap };
}

/**
 * Server `fieldErrors` (`{ "variants.2.price": "OUT_OF_RANGE" }`) -> the paths the inputs carry.
 * Unmapped paths pass through unchanged. A value that is not a string is read as INVALID.
 */
export function mapServerErrors(fieldErrors, pathMap = {}) {
  const out = {};
  for (const [path, code] of Object.entries(fieldErrors ?? {})) {
    out[pathMap[path] ?? path] = typeof code === 'string' ? code : 'INVALID';
  }
  return out;
}

/** Plain-JSON snapshot used for the dirty check; a File counts through name, size and date. */
export function snapshot(product) {
  return JSON.stringify(product, (_key, value) => {
    if (typeof File !== 'undefined' && value instanceof File)
      return `file:${value.name}:${value.size}:${value.lastModified}`;
    return value;
  });
}
