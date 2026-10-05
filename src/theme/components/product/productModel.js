// Pure model of the product page (14 §9): result mapping of the load, the buyable state, the quantity
// limit, the form validation and the cart line. No SDK, no DOM: unit-tested.
import { validateFields, toFieldValues } from '../../lib/validation.js';
import { effectiveProduct, hasAxes, resolve, usableAxes } from '../../lib/variants.js';
import { productMeta, productPageTitle, productPath } from '../../lib/seo.js';

export const MAX_QUANTITY = 99;
export const STORE_NAV_KEY = 'plugins.pano-plugin-market.nav-store';

/** `purchasable.reason` codes that have a `theme.errors.<code>` text; anything else shows GENERIC. */
export const REASON_CODES = [
  'PRODUCT_UNAVAILABLE',
  'VARIANT_REQUIRED',
  'VARIANT_UNAVAILABLE',
  'OUT_OF_STOCK',
  'MAX_QUANTITY',
  'PURCHASE_LIMIT_REACHED',
  'COOLDOWN_ACTIVE',
  'REQUIREMENT_NOT_MET',
  'PERMISSION_REQUIRED',
  'ALREADY_OWNED',
  'SERVER_UNAVAILABLE',
  'GIFT_NOT_ALLOWED',
  'LOGIN_REQUIRED',
  'NOT_IN_CURRENCY',
  'CREDITS_ONLY',
  'NOT_PAYABLE_WITH_CREDITS',
  'PHYSICAL_NOT_SUPPORTED',
  'RECURRING_NOT_SUPPORTED',
  'BUYER_BLOCKED',
];

/** Text key (without the `theme.errors.` prefix) of a reason or message code. */
export function reasonCode(code) {
  return REASON_CODES.includes(code) ? code : 'GENERIC';
}

/** Slug of the route: decoded by the host when it announces 'decoded-route-params', else decoded here. */
export function resolveSlug(rawSlug, hostDecodes) {
  const raw = String(rawSlug ?? '');
  if (hostDecodes) return raw;

  try {
    return decodeURIComponent(raw);
  } catch (e) {
    return raw;
  }
}

/** Breadcrumbs of 14 §9.1; the category crumb only when the product has a category name. */
export function productBreadcrumbs(product) {
  const crumbs = [{ label: STORE_NAV_KEY, href: '/store' }];

  if (product.categoryName && product.categoryId != null)
    crumbs.push({
      label: product.categoryName,
      raw: true,
      href: `/store?category=${encodeURIComponent(product.categoryId)}`,
    });

  crumbs.push({ label: product.name, raw: true });

  return crumbs;
}

/**
 * Result of the product load. `res` = ApiResult of GET products/:slug, `settings` = the store settings
 * (null when they could not be fetched). Returns `{ notFound: true }` (the page throws a 404) or the load
 * result `{ data, pageTitle?, meta?, breadcrumbs? }`.
 */
export function resolveProductLoad({
  res,
  settings,
  slug,
  origin,
  features = {},
  variantParam = null,
}) {
  if (!res || !res.ok) {
    const code = res?.code || 'NETWORK';

    if (code === 'NOT_FOUND') return { notFound: true };
    if (code === 'STORE_DISABLED') return { data: { state: 'DISABLED' } };

    return { data: { state: 'ERROR', code } };
  }

  const product = res.product;

  if (!product || typeof product !== 'object') return { data: { state: 'ERROR', code: 'NETWORK' } };

  const result = {
    data: {
      state: 'READY',
      product,
      settings: settings || {},
      // false when the settings fetch failed: the {} above is a render placeholder, never to be stored
      settingsLoaded: settings != null,
      slug,
      // the page renders its own h1 only when the host hides the title (14 §9.2)
      titleOptions: features.titleOptions === true,
      variantParam: typeof variantParam === 'string' ? variantParam : null,
    },
    pageTitle: productPageTitle(product, { titleOptions: features.titleOptions === true }),
    breadcrumbs: productBreadcrumbs(product),
  };

  if (features.meta === true) result.meta = productMeta({ product, slug, origin });

  return result;
}

// ---- what the buyer can do ----------------------------------------------------------------------------

/** Variant of the current choice: from axis buttons, or the select's variant id; null when incomplete. */
export function currentVariant(product, { selection = {}, variantId = null } = {}) {
  const variants = Array.isArray(product?.variants) ? product.variants : [];
  if (!product?.hasVariants && !variants.length) return null;

  if (hasAxes(product?.variantOptions))
    return resolve(variants, selection, usableAxes(product.variantOptions));

  const id = Number(variantId);

  return variants.find((variant) => Number(variant?.id) === id) ?? null;
}

/** True when the buyer has to pick a variant but none is resolved. */
export function variantMissing(product, variant) {
  return (product?.hasVariants || (product?.variants?.length ?? 0) > 0) && !variant;
}

/**
 * Highest quantity: min(maxQuantityPerOrder ?? 99, resolved stock ?? 99, limitPerPlayer ?? 99, 99).
 * `stock` is the variant's when a variant is resolved, else the product's.
 */
export function quantityMax(product, variant) {
  const positive = (value) => {
    const n = Number(value);
    return value !== null && value !== undefined && Number.isFinite(n) && n > 0
      ? Math.floor(n)
      : null;
  };
  const stock = variant ? variant.stock : product?.stock;

  return Math.min(
    positive(product?.maxQuantityPerOrder) ?? MAX_QUANTITY,
    positive(stock) ?? MAX_QUANTITY,
    positive(product?.limitPerPlayer) ?? MAX_QUANTITY,
    MAX_QUANTITY,
  );
}

/** The quantity control is shown only for one-time products with room for more than one. */
export function quantityVisible(product, variant) {
  return (product?.billingMode ?? 'ONE_TIME') === 'ONE_TIME' && quantityMax(product, variant) > 1;
}

/** Clamps typed text / number to [1, max]; anything that is not a number becomes 1. */
export function clampQuantity(value, max) {
  const n = Math.floor(Number(value));
  const top = Math.max(1, Number(max) || 1);

  if (!Number.isFinite(n) || n < 1) return 1;

  return Math.min(n, top);
}

/**
 * What the buy buttons do:
 *  `LOGIN`    purchasable.reason LOGIN_REQUIRED => the sign-in link
 *  `BLOCKED`  purchasable.ok === false => disabled with the reason text
 *  `SOLD_OUT` the resolved product is out of stock => disabled "Sold out"
 *  `SUBSCRIBE` / `BUY` otherwise
 */
export function buyState(product, variant) {
  const purchasable = product?.purchasable;

  if (purchasable && purchasable.ok === false) {
    if (purchasable.reason === 'LOGIN_REQUIRED') return { kind: 'LOGIN', reason: 'LOGIN_REQUIRED' };

    return { kind: 'BLOCKED', reason: reasonCode(purchasable.reason) };
  }

  const view = effectiveProduct(product, variant);
  if (view?.inStock === false) return { kind: 'SOLD_OUT', reason: 'OUT_OF_STOCK' };

  return { kind: product?.billingMode === 'SUBSCRIPTION' ? 'SUBSCRIBE' : 'BUY', reason: null };
}

/** Server the line is delivered on: the only choice, the buyer's pick, or null. */
export function targetServer(product, serverId) {
  const choices = Array.isArray(product?.serverChoices) ? product.serverChoices : [];

  if (choices.length === 1) return choices[0].id;
  if (choices.length === 0) return null;

  const id = Number(serverId);

  return choices.some((choice) => Number(choice.id) === id) ? id : null;
}

export const ID_VARIANT = 'mp-variant';
export const ID_SERVER = 'mp-server';
export const fieldId = (fieldKey) => `mf-${fieldKey}`;

/**
 * Validates variant, custom fields and server. Returns `{ variant, fields, server, firstInvalid }`:
 * `variant` / `server` are codes (VARIANT_REQUIRED / SERVER_REQUIRED) or null, `fields` is
 * { fieldKey: code }, `firstInvalid` is the element id that receives focus (variant, fields in order, server).
 */
export function validateForm(product, { variant, fieldValues, serverId }) {
  const errors = { variant: null, fields: {}, server: null, firstInvalid: null };

  if (variantMissing(product, variant)) {
    errors.variant = 'VARIANT_REQUIRED';
    errors.firstInvalid = ID_VARIANT;
  }

  errors.fields = validateFields(product?.fields, fieldValues);
  const firstField = (product?.fields || []).find((field) => errors.fields[field.fieldKey]);
  if (firstField && !errors.firstInvalid) errors.firstInvalid = fieldId(firstField.fieldKey);

  if ((product?.serverChoices?.length ?? 0) > 0 && targetServer(product, serverId) === null) {
    errors.server = 'SERVER_REQUIRED';
    if (!errors.firstInvalid) errors.firstInvalid = ID_SERVER;
  }

  return errors;
}

/** True when validateForm found nothing. */
export function formValid(errors) {
  return !errors.variant && !errors.server && Object.keys(errors.fields).length === 0;
}

/**
 * Cart line of the page: { productId, variantId?, quantity, fieldValues?, targetServerId?, variantName? }.
 * Optional empty values are omitted; `variantName` is display meta for the cart (never sent).
 */
export function buildLine(product, { variant, fieldValues, serverId, quantity }) {
  const line = {
    productId: product.id,
    quantity: lineQuantity(product, variant, quantity),
  };

  if (variant) {
    line.variantId = variant.id;
    if (variant.name) line.variantName = variant.name;
  }

  const values = toFieldValues(product.fields, fieldValues);
  if (Object.keys(values).length) line.fieldValues = values;

  const server = targetServer(product, serverId);
  if (server !== null) line.targetServerId = server;

  return line;
}

/** Subscriptions are bought alone, one unit at a time. */
export function lineQuantity(product, variant, quantity) {
  return product?.billingMode === 'SUBSCRIPTION'
    ? 1
    : clampQuantity(quantity, quantityMax(product, variant));
}

export { productPath };
