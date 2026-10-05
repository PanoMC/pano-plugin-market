// Pure logic of the payment method list and modal (13 §16): ordering, filters, per-state
// behaviour, the checkout-rules form and its request body, the status tab. No Svelte, no SDK
// import, so it is unit tested.
import { PLUGIN_ID } from './plugin.js';
import { resolveText } from './schema-form.js';

export const CUSTOM_LABEL_MAX = 255;
export const CUSTOM_DESCRIPTION_MAX = 512;
export const REGIONS = ['all', 'tr', 'global'];

/** Provider states of 02 §11. */
export const PROVIDER_STATES = ['ACTIVE', 'DISABLED', 'NOT_CONFIGURED', 'INCOMPATIBLE', 'UNAVAILABLE'];

/** Ids of the providers market registers itself (02 §12); they do not count as installed payment plugins. */
const BUILT_IN_IDS = new Set(['bank-transfer', 'credits', 'free']);

/** True when the registry holds nothing but market's built-in providers (the "no payment plugins" notice). */
export const onlyBuiltIns = (providers) =>
  (providers ?? []).every((p) => BUILT_IN_IDS.has(p.id) || p.pluginId === PLUGIN_ID);

/** Only http(s) links from API data become an href (docs URL, store URL). */
export const isHttpUrl = (value) => typeof value === 'string' && /^https?:\/\//i.test(value.trim());

/** URL value of `?region=`; anything else is `all`. */
export const regionOf = (value) => (value === 'tr' || value === 'global' ? value : 'all');

/** Public logo route served by market (04 §8); the card falls back to the descriptor icon on error. */
export const logoPath = (base, id) =>
  `${base ?? ''}/api/market/payment-providers/${encodeURIComponent(id)}/logo`;

/** Name shown for a provider: the admin's custom label, else the descriptor name. */
export function providerName(provider, locale = 'en-US', rawTranslate) {
  const custom = provider?.config?.customLabel;
  if (typeof custom === 'string' && custom.trim() !== '') return custom;
  return resolveText(provider?.descriptor?.name, locale, rawTranslate);
}

/** Ascending `config.position`, ties by the descriptor name (not the custom label), then by id. */
export function sortProviders(providers, locale = 'en-US', rawTranslate) {
  const nameOf = (p) => resolveText(p?.descriptor?.name, locale, rawTranslate).toLowerCase();
  return [...(providers ?? [])].sort((a, b) => {
    const byPosition = (a.config?.position ?? 0) - (b.config?.position ?? 0);
    if (byPosition !== 0) return byPosition;
    const byName = nameOf(a).localeCompare(nameOf(b));
    return byName !== 0 ? byName : String(a.id).localeCompare(String(b.id));
  });
}

/** Region from `descriptor.region`; search over the resolved name and the id. Input order is kept. */
export function filterProviders(providers, { region = 'all', search = '' } = {}, locale = 'en-US', rawTranslate) {
  const term = String(search ?? '').trim().toLowerCase();
  return (providers ?? []).filter((p) => {
    if (region !== 'all' && p.descriptor?.region !== region) return false;
    if (!term) return true;
    return (
      providerName(p, locale, rawTranslate).toLowerCase().includes(term) ||
      resolveText(p.descriptor?.name, locale, rawTranslate).toLowerCase().includes(term) ||
      String(p.id).toLowerCase().includes(term)
    );
  });
}

/**
 * What a row offers in a state (table of 13 §16.1). `readOnly`: the modal opens without a save.
 * `reason`: the extra text group (`incompatible` / `unavailable`) and `switchHint` the tooltip key.
 */
export function rowBehavior(provider) {
  const state = provider?.state;
  const base = {
    switchOn: false,
    switchDisabled: true,
    switchHint: null,
    readOnly: false,
    danger: false,
    reason: null,
  };
  switch (state) {
    case 'ACTIVE':
      return { ...base, switchOn: true, switchDisabled: false };
    case 'DISABLED':
      return { ...base, switchDisabled: false };
    case 'NOT_CONFIGURED':
      return { ...base, switchHint: 'configure-first' };
    case 'INCOMPATIBLE':
      return { ...base, readOnly: true, danger: true, reason: 'incompatible' };
    case 'UNAVAILABLE':
      return { ...base, readOnly: true, danger: true, reason: 'unavailable' };
    default:
      // an unknown state is treated as read-only: nothing is changed on a row we cannot classify
      return { ...base, readOnly: true };
  }
}

/** Ids after moving one entry by `delta` (-1 up, +1 down) in the current order; null at an edge. */
export function movedIds(orderedProviders, id, delta) {
  const ids = orderedProviders.map((p) => p.id);
  const from = ids.indexOf(id);
  const to = from + delta;
  if (from === -1 || to < 0 || to >= ids.length) return null;
  [ids[from], ids[to]] = [ids[to], ids[from]];
  return ids;
}

/** Effective test mode of a provider (02 §5): FLAG = store flag or own flag, DERIVED = from the keys, NONE = never. */
export function effectiveTestMode(provider, ctx) {
  const mode = provider?.capabilities?.testMode ?? 'FLAG';
  if (mode === 'NONE') return false;
  if (mode === 'DERIVED') return provider?.capabilities?.derivedTestMode === true;
  return Boolean(ctx?.testMode) || Boolean(provider?.config?.testMode);
}

/** How the test mode control of the rules tab is rendered. */
export function testModeControl(capabilities) {
  const mode = capabilities?.testMode ?? 'FLAG';
  if (mode === 'NONE') return 'hidden';
  if (mode === 'DERIVED') return 'derived';
  return 'flag';
}

/** Fee and min / max controls are disabled when the gateway owns the price. */
export const pricingLocked = (capabilities) =>
  (capabilities?.priceAuthority ?? 'MARKET') !== 'MARKET';

/** Currency codes the rules tab offers: the capability list (or every server code) limited to the store's currencies. */
export function currencyChoices(capabilities, ctx) {
  const store = [ctx?.currency, ...(ctx?.additionalCurrencies ?? [])].filter(Boolean);
  const supported = capabilities?.currencies ?? (ctx?.currencies ?? []).map((c) => c.code);
  const allowed = new Set(supported);
  return store.filter((code, i) => store.indexOf(code) === i && allowed.has(code));
}

/** The rules form of a provider's `config`. Amounts are decimal numbers, `null` = empty. */
export function configToForm(config = {}) {
  const currencies = config.currencies;
  return {
    customLabel: config.customLabel ?? '',
    customDescription: config.customDescription ?? '',
    feeBuyer: config.feeMode === 'BUYER',
    feePercent: config.feePercent ?? 0,
    feeFixed: config.feeFixed ?? 0,
    minAmount: config.minAmount ?? null,
    maxAmount: config.maxAmount ?? null,
    allCurrencies: currencies === null || currencies === undefined,
    currencies: Array.isArray(currencies) ? [...currencies] : [],
    testMode: Boolean(config.testMode),
  };
}

/** Codes validateConfig returns; each has `modals.provider.error.<CODE>` in the locale files. */
export const CONFIG_ERROR_CODES = ['TOO_LONG', 'INVALID', 'OUT_OF_RANGE', 'FEE_REQUIRED', 'MIN_GT_MAX', 'REQUIRED'];

/** A server `fieldErrors` entry of a rules key as one of CONFIG_ERROR_CODES (anything else is INVALID). */
export const configErrorCode = (value) =>
  typeof value === 'string' && CONFIG_ERROR_CODES.includes(value) ? value : 'INVALID';

const isNum = (v) => typeof v === 'number' && !Number.isNaN(v);

/**
 * `{ key: code }` for the rules form (13 §16.2 table). Codes: TOO_LONG, INVALID, OUT_OF_RANGE,
 * FEE_REQUIRED, MIN_GT_MAX, REQUIRED. Fee and amount rules are skipped while the gateway owns the price.
 */
export function validateConfig(form, { capabilities = null } = {}) {
  const errors = {};
  if (String(form.customLabel ?? '').length > CUSTOM_LABEL_MAX) errors.customLabel = 'TOO_LONG';
  if (String(form.customDescription ?? '').length > CUSTOM_DESCRIPTION_MAX)
    errors.customDescription = 'TOO_LONG';

  if (!pricingLocked(capabilities)) {
    const optional = (key, value) => {
      if (value !== null && value !== undefined && !isNum(value)) errors[key] = 'INVALID';
    };
    if (form.feeBuyer) {
      const percent = form.feePercent ?? 0;
      const fixed = form.feeFixed ?? 0;
      if (!isNum(percent)) errors.feePercent = 'INVALID';
      else if (percent < 0 || percent > 100) errors.feePercent = 'OUT_OF_RANGE';
      else if (Math.round(percent * 100) / 100 !== percent) errors.feePercent = 'INVALID';
      if (!isNum(fixed)) errors.feeFixed = 'INVALID';
      else if (fixed < 0) errors.feeFixed = 'OUT_OF_RANGE';
      if (!errors.feePercent && !errors.feeFixed && percent === 0 && fixed === 0)
        errors.feePercent = 'FEE_REQUIRED';
    }
    optional('minAmount', form.minAmount);
    optional('maxAmount', form.maxAmount);
    if (!errors.minAmount && !errors.maxAmount && isNum(form.minAmount) && isNum(form.maxAmount)) {
      if (form.minAmount < 0) errors.minAmount = 'OUT_OF_RANGE';
      if (form.maxAmount < 0) errors.maxAmount = 'OUT_OF_RANGE';
      if (!errors.maxAmount && form.minAmount > form.maxAmount) errors.maxAmount = 'MIN_GT_MAX';
    }
  }

  if (!form.allCurrencies && form.currencies.length === 0) errors.currencies = 'REQUIRED';
  return errors;
}

/**
 * The `config` object of the save request. While the gateway owns the price the loaded fee and
 * amount values go back unchanged; a hidden or derived test mode control keeps the stored flag.
 */
export function buildConfig(form, { capabilities = null, original = {} } = {}) {
  const locked = pricingLocked(capabilities);
  const control = testModeControl(capabilities);
  const label = String(form.customLabel ?? '').trim();
  const description = String(form.customDescription ?? '').trim();
  const out = {
    customLabel: label === '' ? null : label,
    customDescription: description === '' ? null : description,
    feeMode: locked ? (original.feeMode ?? 'NONE') : form.feeBuyer ? 'BUYER' : 'NONE',
    feePercent: locked ? (original.feePercent ?? 0) : (form.feePercent ?? 0),
    feeFixed: locked ? (original.feeFixed ?? 0) : (form.feeFixed ?? 0),
    minAmount: locked ? (original.minAmount ?? null) : (form.minAmount ?? null),
    maxAmount: locked ? (original.maxAmount ?? null) : (form.maxAmount ?? null),
    currencies: form.allCurrencies ? null : [...form.currencies],
    testMode: control === 'flag' ? Boolean(form.testMode) : Boolean(original.testMode),
  };
  return out;
}

/** Tab holding the first invalid key of an `INVALID_PROVIDER_SETTINGS` answer: `settings` or `rules`. */
export function tabOfFirstError(fieldErrors, schema) {
  const keys = Object.keys(fieldErrors ?? {});
  if (keys.length === 0) return 'settings';
  const schemaKeys = new Set((schema?.fields ?? []).map((f) => f.key));
  const first = keys.find((k) => schemaKeys.has(k));
  if (first) return 'settings';
  return 'rules';
}

/** Tab of the first client-side error: schema fields first, then the rules form. */
export function tabOfFirstClientError(schemaErrors, configErrors) {
  if (Object.keys(schemaErrors ?? {}).length > 0) return 'settings';
  if (Object.keys(configErrors ?? {}).length > 0) return 'rules';
  return null;
}

/**
 * Rows of the capability `dl` of the status tab. `kind` tells the renderer how to print `value`:
 * `enum` (`enums.<enum>.<value>`), `bool` (yes / no) or `list` (codes, empty = every currency).
 */
export function capabilityRows(capabilities, ctx = null) {
  const c = capabilities ?? {};
  const currencies = c.currencies ?? (ctx?.currencies ?? []).map((x) => x.code);
  return [
    { key: 'refund', kind: 'enum', enum: 'refund-support', value: c.refund ?? 'NONE' },
    { key: 'recurring', kind: 'enum', enum: 'recurring-support', value: c.recurring ?? 'NONE' },
    { key: 'status-query', kind: 'bool', value: c.statusQuery === true },
    { key: 'disputes', kind: 'bool', value: c.disputeEvents === true },
    { key: 'guests', kind: 'bool', value: c.guests !== false },
    { key: 'physical-goods', kind: 'bool', value: c.physicalGoods !== false },
    { key: 'mixed-credit', kind: 'bool', value: c.mixedCredit !== false },
    { key: 'currencies', kind: 'list', value: [...currencies] },
  ];
}

/** Webhook URLs as `[channel, url]` rows, default channel first. */
export function webhookRows(webhookUrls) {
  return Object.entries(webhookUrls ?? {}).sort(([a], [b]) => {
    if (a === 'default') return -1;
    if (b === 'default') return 1;
    return a.localeCompare(b);
  });
}

/** An action button is blocked while its provider needs saved settings and the form has unsaved edits. */
export const actionBlocked = (action, dirty) => Boolean(action?.requiresSavedSettings) && Boolean(dirty);

/** Counts shown under an action result (`imported`); null when there is nothing to show. */
export function importedCounts(result) {
  const imported = result?.imported;
  if (!imported || typeof imported !== 'object') return null;
  const categories = Number(imported.categories);
  const products = Number(imported.products);
  return {
    categories: Number.isFinite(categories) ? categories : 0,
    products: Number.isFinite(products) ? products : 0,
  };
}
