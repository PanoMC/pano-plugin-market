// Pure model of the checkout page (14 §10.1-10.6): load mapping, page state machine, section visibility,
// billing requirements, validation with the first invalid field, quote body, quote selection rules.
// No SDK, no DOM: unit-tested in __tests__/checkoutModel.test.js.
import {
  INVALID,
  REQUIRED,
  TAX_MAX,
  requiredAddressFields,
  validateAddress,
  validateEmail,
  validateGiftMessage,
  validateGiftRecipient,
  validateIdentityNumber,
  validateUsername,
} from './validation.js';
import { COUNTRY_CODES } from './countries.js';

export const ADDRESS_FIELDS = [
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

/** Billing-only fields that follow the address inputs in the DOM. */
export const BILLING_EXTRAS = ['identityNumber', 'taxOffice', 'taxNumber'];

export const BILLING_BASE_FIELDS = ['firstName', 'lastName', 'country', 'city', 'line1'];

export const CHECKOUT_TITLE_KEY = 'plugins.pano-plugin-market.theme.checkout.title';

export const PAGE_STATES = [
  'INIT',
  'EMPTY',
  'LOGIN_REQUIRED',
  'READY',
  'QUOTING',
  'SUBMITTING',
  'LEAVING',
  'BLOCKED',
  'DISABLED',
];

const DISABLED_CODES = ['STORE_DISABLED', 'STORE_UNAVAILABLE'];

/** Locale key of a field error code (FIELD_REQUIRED / FIELD_INVALID / GIFT_SELF) for `field`. */
export function fieldErrorKey(field, code) {
  if (code === 'GIFT_SELF') return 'theme.checkout.gift-self';
  if (code === REQUIRED) return 'theme.checkout.field-required';
  if (field === 'phone') return 'theme.checkout.phone-invalid';
  if (field === 'identityNumber') return 'theme.checkout.identity-invalid';
  if (field === 'username' || field === 'recipient') return 'theme.checkout.username-invalid';
  if (field === 'email') return 'theme.checkout.email-invalid';

  return 'theme.checkout.field-invalid';
}

/** DOM id of a checkout control: `market-checkout-<section>-<field>`. */
export const fieldId = (section, field) => `market-checkout-${section}-${field}`;

const isObject = (value) => value !== null && typeof value === 'object' && !Array.isArray(value);
const text = (value) => (typeof value === 'string' ? value.trim() : '');

export const emptyAddress = () => ({
  firstName: '',
  lastName: '',
  company: '',
  phone: '',
  country: '',
  state: '',
  city: '',
  district: '',
  neighborhood: '',
  line1: '',
  line2: '',
  postalCode: '',
});

// ---- load (14 §10.1) --------------------------------------------------------------------------------------

/** `?topup=<decimal>`: a positive amount with at most two decimals, else null (normal cart checkout). */
export function parseTopup(value) {
  if (typeof value !== 'string') return null;

  const trimmed = value.trim();
  if (!/^\d{1,9}(\.\d{1,2})?$/.test(trimmed)) return null;

  const amount = Number(trimmed);

  return amount > 0 ? amount : null;
}

/** The checkout config of an ApiResult: the `config` member when present, else the flat response body. */
export function configOf(res) {
  if (isObject(res?.config)) return res.config;
  if (!res || !res.ok) return null;

  const { ok, ...rest } = res;

  return 'guestCheckout' in rest || 'billingInfoMode' in rest || 'addressFields' in rest
    ? rest
    : null;
}

/**
 * Result of the page load: `{ data: { state, settings, config, topup }, pageTitle, meta? }`.
 * `state` = READY | DISABLED | ERROR. `meta` only when the host supports page meta (14 §4.1).
 */
export function resolveCheckoutLoad({ res, settings, topup = null, features = {} }) {
  const base = { settings: settings || {}, settingsLoaded: settings != null, topup };
  const result = {
    data: { state: 'ERROR', config: null, ...base },
    pageTitle: CHECKOUT_TITLE_KEY,
  };

  if (!res || !res.ok) {
    if (DISABLED_CODES.includes(res?.code)) result.data.state = 'DISABLED';
    else result.data.code = res?.code || 'NETWORK';
  } else {
    const config = configOf(res);

    if (config) {
      result.data.state = 'READY';
      result.data.config = config;
    } else result.data.code = 'NETWORK';
  }

  if (features.meta === true) result.meta = { robots: 'noindex,nofollow' };

  return result;
}

// ---- quote helpers ----------------------------------------------------------------------------------------

/** True when the quote carries `code` as a message or as an error of a line. */
export function quoteHasCode(quote, code, level = null) {
  if (!quote) return false;

  const inMessages =
    Array.isArray(quote.messages) &&
    quote.messages.some((m) => m?.code === code && (level === null || m.level === level));
  if (inMessages) return true;
  if (level !== null && level !== 'error') return false;

  return (
    Array.isArray(quote.lines) &&
    quote.lines.some((line) => Array.isArray(line?.errors) && line.errors.includes(code))
  );
}

/** Names of the lines carrying the line error `code` (GIFT_NOT_ALLOWED: shown under the gift switch). */
export function lineNamesWith(quote, code) {
  if (!Array.isArray(quote?.lines)) return [];

  return quote.lines
    .filter((line) => Array.isArray(line?.errors) && line.errors.includes(code))
    .map((line) => line.name)
    .filter(Boolean);
}

/** Extra shipping address fields the selected carrier asked for (message SHIPPING_ADDRESS_INVALID {fields}). */
export function extraShippingFields(quote) {
  const out = [];

  for (const message of Array.isArray(quote?.messages) ? quote.messages : [])
    if (message?.code === 'SHIPPING_ADDRESS_INVALID' && Array.isArray(message.fields))
      for (const field of message.fields)
        if (ADDRESS_FIELDS.includes(field) && !out.includes(field)) out.push(field);

  return out;
}

export const NO_CARRIER_EXTRAS = Object.freeze({ key: '', fields: Object.freeze([]) });

/** Scope of the carrier extras: they hold for one destination country and one shipping method. */
export const carrierExtrasKey = (country, shippingMethodId) =>
  `${text(country).toUpperCase()}|${shippingMethodId ?? ''}`;

/**
 * Carrier extras kept as page state (sticky): the fields SHIPPING_ADDRESS_INVALID named stay required while the
 * draft's country and shipping method are unchanged, even when a later quote (asked without the incomplete
 * address, so answered SHIPPING_ADDRESS_REQUIRED) no longer carries the message. Otherwise the address would be
 * sent and withheld in turn, one quote per flip. `current` = `{ key, fields }`; `draft` = the checkout draft
 * (read after the quote's selections were applied).
 */
export function nextCarrierExtras(current, quote, draft) {
  const key = carrierExtrasKey(draft?.shippingAddress?.country, draft?.shippingMethodId);
  const kept = current && current.key === key ? current.fields : [];
  const found = extraShippingFields(quote);

  if (found.length === 0) return kept.length > 0 ? current : NO_CARRIER_EXTRAS;

  return { key, fields: [...new Set([...kept, ...found])] };
}

/** The carrier extras in effect for `draft`: none once its country or shipping method differs from the scope. */
export function carrierExtrasFor(current, draft) {
  const key = carrierExtrasKey(draft?.shippingAddress?.country, draft?.shippingMethodId);

  return current && current.key === key ? current.fields : [];
}

// ---- page state machine (14 §10.3) ------------------------------------------------------------------------

/**
 * Current page state. `forced` is a state the submit / error mapping imposed (DISABLED, BLOCKED,
 * LOGIN_REQUIRED, EMPTY); `submit` is IDLE | SUBMITTING | LEAVING.
 * Precedence: DISABLED, INIT, LEAVING / SUBMITTING, EMPTY, LOGIN_REQUIRED, BLOCKED, QUOTING / READY.
 */
export function derivePageState({
  cartReady = false,
  count = 0,
  topup = null,
  loggedIn = false,
  guestCheckout = false,
  quote = null,
  quoting = false,
  submit = 'IDLE',
  forced = null,
} = {}) {
  if (forced === 'DISABLED') return 'DISABLED';
  if (!cartReady && topup === null) return 'INIT';
  if (submit === 'LEAVING') return 'LEAVING';
  if (submit === 'SUBMITTING') return 'SUBMITTING';
  if (forced === 'EMPTY' || (count === 0 && topup === null)) return 'EMPTY';

  if (
    forced === 'LOGIN_REQUIRED' ||
    (!loggedIn &&
      (topup !== null || guestCheckout !== true || quoteHasCode(quote, 'LOGIN_REQUIRED')))
  )
    return 'LOGIN_REQUIRED';

  if (forced === 'BLOCKED' || quoteHasCode(quote, 'BUYER_BLOCKED', 'error')) return 'BLOCKED';

  return quoting ? 'QUOTING' : 'READY';
}

// ---- sections shown (14 §10.4) ----------------------------------------------------------------------------

const BILLING_BUYER_FIELDS = [
  'FIRST_NAME',
  'LAST_NAME',
  'PHONE',
  'COUNTRY',
  'BILLING_ADDRESS',
  'IDENTITY_NUMBER',
];

const namesBillingField = (quote) =>
  Array.isArray(quote?.requiredBuyerFields) &&
  quote.requiredBuyerFields.some((f) => BILLING_BUYER_FIELDS.includes(f));

export function sectionsVisible({ config, quote, topup = null }) {
  const mode = config?.billingInfoMode || 'OFF';

  return {
    gift: config?.giftPurchase === true && topup === null,
    shipping: quote?.requiresShipping === true && topup === null,
    billing: mode !== 'OFF' || namesBillingField(quote),
  };
}

// ---- shipping address -------------------------------------------------------------------------------------

/** The country codes of the shipping select: `config.shippingCountries`, all countries when it is not a list. */
export function shippingCountries(config) {
  const list = Array.isArray(config?.shippingCountries)
    ? config.shippingCountries.filter((c) => typeof c === 'string' && c)
    : [];

  return list.length ? list.map((c) => c.toUpperCase()) : [...COUNTRY_CODES];
}

/** Required field names of a shipping address (config per-country set plus the carrier's `extra` fields). */
export function shippingRequired(config, address, extra = []) {
  return requiredAddressFields(config?.addressFields, address?.country, extra);
}

/** `{ ok, errors }`: a shipping address is complete when it has no validation error and a country. */
export function checkShippingAddress(config, address, extra = []) {
  const required = shippingRequired(config, address, extra);
  required.add('country');
  const errors = validateAddress(address, required, shippingCountries(config));

  return { required, errors, ok: Object.keys(errors).length === 0 };
}

/** The address object of a saved address (without id / label / isDefault) for copying and quoting. */
export function addressOf(saved) {
  const out = emptyAddress();

  if (isObject(saved)) for (const field of ADDRESS_FIELDS) out[field] = text(saved[field]);

  return out;
}

/** Wire shape of an address: trimmed, empty fields omitted. */
export function wireAddress(address, only = ADDRESS_FIELDS) {
  const out = {};

  for (const field of only) {
    const value = text(address?.[field]);
    if (value)
      out[field] = field === 'country' || field === 'postalCode' ? value.toUpperCase() : value;
  }

  return out;
}

// ---- billing (14 §10.5) -----------------------------------------------------------------------------------

/**
 * Required billing fields named by `quote.requiredBuyerFields` (BuyerField of 02 §5.1) as field names.
 * EMAIL and SHIPPING_ADDRESS are handled elsewhere (guest e-mail, shipping section).
 */
export function mapBuyerFields(requiredBuyerFields, addressFields, country) {
  const out = new Set();

  for (const name of Array.isArray(requiredBuyerFields) ? requiredBuyerFields : []) {
    if (name === 'FIRST_NAME') out.add('firstName');
    else if (name === 'LAST_NAME') out.add('lastName');
    else if (name === 'PHONE') out.add('phone');
    else if (name === 'COUNTRY') out.add('country');
    else if (name === 'IDENTITY_NUMBER') out.add('identityNumber');
    else if (name === 'BILLING_ADDRESS')
      for (const f of [
        'country',
        'city',
        'line1',
        ...requiredAddressFields(addressFields, country),
      ])
        out.add(f);
  }

  return out;
}

/**
 * What the billing section shows and requires.
 * `info` = draft.billingInfo (address fields + type + identityNumber / taxOffice / taxNumber);
 * `shippingAddress` = the effective shipping address (copied when "same as shipping" is on);
 * `shippingExtra` = the carrier's extra shipping fields, `shippingSaved` = a saved address is chosen (the shipping
 * section then validates nothing on the address itself).
 * While the shipping address is copied, every required billing address field the copy lacks (not enforced by the
 * shipping section: e.g. a phone) is listed in `copyFields` and shown as a billing input, so the buyer is never
 * stuck on BUYER_INFO_REQUIRED with the field hidden.
 */
export function billingRequirements({
  config,
  quote,
  info,
  open = false,
  sameAsShipping = true,
  shippingAddress = null,
  shippingRequired: shippingNeeded = false,
  shippingExtra = [],
  shippingSaved = false,
}) {
  const mode = config?.billingInfoMode || 'OFF';
  const type = info?.type === 'COMPANY' ? 'COMPANY' : 'INDIVIDUAL';
  const copy = shippingNeeded && sameAsShipping === true;
  const country = text(copy ? shippingAddress?.country : info?.country).toUpperCase();
  const mapped = mapBuyerFields(quote?.requiredBuyerFields, config?.addressFields, country);
  const named = mapped.size > 0;
  const base = new Set([
    ...BILLING_BASE_FIELDS,
    ...requiredAddressFields(config?.addressFields, country),
  ]);

  let visible;
  let forcedOpen;
  let isOpen;
  let required;

  if (mode === 'OFF') {
    visible = named;
    forcedOpen = named;
    isOpen = named;
    required = new Set(mapped);
  } else if (mode === 'OPTIONAL') {
    visible = true;
    forcedOpen = named;
    isOpen = named || open === true;
    required = named ? new Set(mapped) : new Set([...base, ...mapped]);
  } else {
    visible = true;
    forcedOpen = true;
    isOpen = true;
    required = new Set([...base, ...mapped]);
  }

  const identityRequired = mapped.has('identityNumber');
  const showIdentity = identityRequired || (country === 'TR' && type === 'INDIVIDUAL');

  if (type === 'COMPANY') {
    required.add('company');
    required.add('taxNumber');
  }

  // address inputs shown: all of them, except in mode OFF where only the named fields appear
  const fields = ADDRESS_FIELDS.filter((f) =>
    f === 'company' ? type === 'COMPANY' : mode !== 'OFF' || mapped.has(f),
  );

  let copyFields = [];

  if (isOpen && copy) {
    // what the shipping section already enforces cannot be missing while it is valid (typing in progress)
    const enforced = shippingSaved
      ? new Set()
      : new Set([
          ...requiredAddressFields(config?.addressFields, country, shippingExtra),
          'country',
        ]);
    const wanted = new Set(
      [...required].filter(
        (f) => ADDRESS_FIELDS.includes(f) && f !== 'company' && !enforced.has(f),
      ),
    );
    const lacking = validateAddress(addressOf(shippingAddress), wanted, COUNTRY_CODES);

    copyFields = ADDRESS_FIELDS.filter((f) => wanted.has(f) && f in lacking);
  }

  return {
    mode,
    visible,
    forcedOpen,
    open: isOpen,
    type,
    country,
    required,
    fields,
    // the address inputs are hidden while the shipping address is copied
    showAddress: isOpen && !copy,
    copy: isOpen && copy,
    copyFields,
    showIdentity: isOpen && showIdentity,
    identityRequired,
    showCompany: isOpen && type === 'COMPANY',
  };
}

/** Validates the billing section; `{}` when it is closed. */
export function validateBilling(req, info) {
  if (!req.open) return {};

  const errors = {};

  if (req.showAddress) {
    const address = validateAddress(
      info,
      new Set([...req.required].filter((f) => req.fields.includes(f))),
      COUNTRY_CODES,
    );
    for (const field of Object.keys(address))
      if (req.fields.includes(field)) errors[field] = address[field];
  } else if (req.copy && req.copyFields?.length > 0) {
    const address = validateAddress(info, new Set(req.copyFields), COUNTRY_CODES);
    for (const field of req.copyFields) if (address[field]) errors[field] = address[field];
  }

  if (req.showIdentity) {
    const code = validateIdentityNumber(info?.identityNumber, req.country, {
      required: req.identityRequired,
    });
    if (code) errors.identityNumber = code;
  }

  if (req.showCompany) {
    if (!text(info?.company) && req.copy) errors.company = REQUIRED;

    const number = text(info?.taxNumber);
    if (!number) errors.taxNumber = REQUIRED;
    else if (number.length > TAX_MAX) errors.taxNumber = INVALID;

    if (text(info?.taxOffice).length > TAX_MAX) errors.taxOffice = INVALID;
  }

  return errors;
}

/**
 * `billingInfo` of the body: the (copied or typed) address plus `type` and the billing extras; empty
 * fields omitted. null when the section is closed.
 */
export function effectiveBillingInfo(req, info, shippingAddress = null) {
  if (!req.open) return null;

  const source = req.copy
    ? {
        ...addressOf(shippingAddress),
        company: req.showCompany ? info?.company : '',
        // what the copy lacks comes from the billing inputs
        ...Object.fromEntries((req.copyFields ?? []).map((f) => [f, info?.[f]])),
      }
    : info;
  const out = wireAddress(source, req.copy ? ADDRESS_FIELDS : req.fields);
  out.type = req.type;

  if (req.showIdentity && text(info?.identityNumber))
    out.identityNumber = text(info.identityNumber);
  if (req.showCompany) {
    if (text(info?.taxOffice)) out.taxOffice = text(info.taxOffice);
    if (text(info?.taxNumber)) out.taxNumber = text(info.taxNumber);
  }

  return out;
}

// ---- validation of the whole page (14 §10.7 step 1) ---------------------------------------------------------

/**
 * Order of the controls in the DOM, for "focus the first invalid field": a list of
 * `[section, [field, ...]]`.
 */
const FOCUS_ORDER = [
  ['guest', ['username', 'email']],
  ['gift', ['recipient', 'message']],
  ['shipping', [...ADDRESS_FIELDS, 'method']],
  ['billing', [...ADDRESS_FIELDS, ...BILLING_EXTRAS]],
];

/** Id of the first invalid control of `errors` ({ guest, gift, shipping, billing }), or null. */
export function firstInvalidId(errors) {
  for (const [section, fields] of FOCUS_ORDER)
    for (const field of fields) if (errors?.[section]?.[field]) return fieldId(section, field);

  return null;
}

/**
 * Client validation over every visible section. `draft` is the checkout draft, `user` the logged-in user
 * (or null), `saved` the buyer's saved addresses, `carrierExtras` the sticky extra shipping fields. Returns
 * `{ errors: { guest, gift, shipping, billing }, valid, firstId, billing: <requirements> }`.
 */
export function validateCheckout({
  config,
  quote,
  topup = null,
  draft,
  user = null,
  saved = [],
  carrierExtras = [],
}) {
  const visible = sectionsVisible({ config, quote, topup });
  const errors = { guest: {}, gift: {}, shipping: {}, billing: {} };

  if (!user) {
    const username = validateUsername(draft.guest?.username);
    const email = validateEmail(draft.guest?.email);
    if (username) errors.guest.username = username;
    if (email) errors.guest.email = email;
  }

  if (visible.gift && draft.isGift) {
    const own = user ? user.username : draft.guest?.username;
    const recipient = validateGiftRecipient(draft.recipientUsername, own);
    const message = validateGiftMessage(draft.giftMessage);
    if (recipient) errors.gift.recipient = recipient;
    if (message) errors.gift.message = message;
  }

  const effective = shippingAddressOf(draft, saved);

  if (visible.shipping) {
    if (draft.shippingAddressId === null || draft.shippingAddressId === undefined) {
      const checked = checkShippingAddress(config, draft.shippingAddress, carrierExtras);
      Object.assign(errors.shipping, checked.errors);
    }

    if (
      Object.keys(errors.shipping).length === 0 &&
      Array.isArray(quote?.shippingOptions) &&
      quote.shippingOptions.length > 0 &&
      !draft.shippingMethodId
    )
      errors.shipping.method = REQUIRED;
  }

  const req = billingRequirements({
    config,
    quote,
    info: draft.billingInfo,
    open: draft.billingOpen,
    sameAsShipping: draft.billingSameAsShipping,
    shippingAddress: effective,
    shippingRequired: visible.shipping,
    shippingExtra: carrierExtras,
    shippingSaved: draft.shippingAddressId !== null && draft.shippingAddressId !== undefined,
  });

  if (visible.billing) errors.billing = validateBilling(req, draft.billingInfo);

  const firstId = firstInvalidId(errors);

  return { errors, valid: firstId === null, firstId, billing: req };
}

/** The shipping address object in effect: the chosen saved address, else the typed one. */
export function shippingAddressOf(draft, saved = []) {
  const id = draft?.shippingAddressId;

  if (id !== null && id !== undefined) {
    const found = (Array.isArray(saved) ? saved : []).find((a) => a?.id === id);
    return found ? addressOf(found) : emptyAddress();
  }

  return { ...emptyAddress(), ...(draft?.shippingAddress || {}) };
}

// ---- quote request (14 §10.6) -----------------------------------------------------------------------------

/**
 * The CartInput of a quote. `items` = wire items of a guest cart (omitted for a logged-in buyer);
 * `guest` / `recipientUsername` / `shippingAddress` / `billingInfo` are passed only when valid (the caller decides),
 * `committed` values come from blur events.
 */
export function buildQuoteBody({
  topup = null,
  loggedIn = false,
  items = [],
  guest = null,
  currency,
  locale,
  draft,
  recipientUsername = null,
  shippingAddress = null,
  billingInfo = null,
}) {
  const body = {};

  if (topup !== null) {
    body.creditTopUp = topup;
    if (draft?.paymentMethodId) body.paymentMethodId = draft.paymentMethodId;
    if (billingInfo) body.billingInfo = billingInfo;
    if (guest) body.guest = guest;
    if (currency) body.currency = currency;
    if (locale) body.locale = locale;

    return body;
  }

  if (!loggedIn) {
    body.items = (Array.isArray(items) ? items : []).map(({ meta, ...rest }) => rest);
    if (guest) body.guest = guest;
  }

  if (currency) body.currency = currency;
  // A signed-in buyer's quote falls back to the codes stored on the server cart when the body names none, and the cart is written after the
  // quote (14 §10.6): an empty string says "no code", so a code the buyer just removed is gone from the very quote that follows the removal.
  if (text(draft?.couponCode)) body.couponCode = text(draft.couponCode);
  else if (loggedIn) body.couponCode = '';
  if (text(draft?.creatorCode)) body.creatorCode = text(draft.creatorCode);
  else if (loggedIn) body.creatorCode = '';

  if (recipientUsername) {
    body.recipientUsername = recipientUsername;
    if (text(draft?.giftMessage)) body.giftMessage = text(draft.giftMessage);
  }

  if (draft?.payWithCredits === true) body.payWithCredits = true;
  else if (
    draft?.useCredits !== null &&
    draft?.useCredits !== undefined &&
    draft?.useCredits !== ''
  )
    body.useCredits = draft.useCredits;

  if (draft?.shippingAddressId !== null && draft?.shippingAddressId !== undefined)
    body.shippingAddressId = draft.shippingAddressId;
  else if (shippingAddress) body.shippingAddress = shippingAddress;

  if (draft?.shippingMethodId) body.shippingMethodId = draft.shippingMethodId;
  if (billingInfo) body.billingInfo = billingInfo;
  if (draft?.paymentMethodId && draft.payWithCredits !== true)
    body.paymentMethodId = draft.paymentMethodId;
  if (locale) body.locale = locale;

  return body;
}

/** Stable JSON (sorted keys) of a body: the trigger signature and the input of the idempotency hash. */
export function canonicalBody(value) {
  if (Array.isArray(value)) return `[${value.map(canonicalBody).join(',')}]`;
  if (isObject(value))
    return `{${Object.keys(value)
      .filter((k) => value[k] !== undefined)
      .sort()
      .map((k) => `${JSON.stringify(k)}:${canonicalBody(value[k])}`)
      .join(',')}}`;

  return JSON.stringify(value) ?? 'null';
}

/** Debounce before a quote (ms): 500 when the shipping address changed (typing), else 300. */
export function quoteDelay(previous, next) {
  const was = canonicalBody(previous?.shippingAddress ?? null);
  const now = canonicalBody(next?.shippingAddress ?? null);

  return was !== now ? 500 : 300;
}

/**
 * Selections the quote implies, as a draft patch (`{}` when nothing changes): shipping method (kept when
 * offered, else the server's preselection or the single option, else cleared) and payment method (kept
 * when available, else the single available one, else none).
 */
export function applyQuoteSelections(draft, quote) {
  const patch = {};
  if (!quote) return patch;

  const current = draft?.shippingMethodId ?? null;
  const options = Array.isArray(quote.shippingOptions) ? quote.shippingOptions : [];
  const ids = options.map((o) => o?.methodId).filter((id) => id !== undefined && id !== null);
  let shipping = current;

  if (quote.requiresShipping !== true || ids.length === 0) shipping = null;
  else if (current === null || !ids.includes(current)) {
    if (quote.shippingMethodId !== undefined && ids.includes(quote.shippingMethodId))
      shipping = quote.shippingMethodId;
    else shipping = ids.length === 1 ? ids[0] : null;
  }

  if (shipping !== current) patch.shippingMethodId = shipping;

  if (draft?.payWithCredits !== true && Number(quote.gatewayAmount) !== 0) {
    const methods = (Array.isArray(quote.paymentMethods) ? quote.paymentMethods : []).filter(
      (m) => m && m.available !== false,
    );
    const currentMethod = draft?.paymentMethodId ?? null;
    let method = currentMethod;

    if (!methods.some((m) => m.id === currentMethod))
      method = methods.length === 1 ? methods[0].id : null;

    if (method !== currentMethod) patch.paymentMethodId = method;
  }

  return patch;
}

/**
 * For a logged-in buyer the server cart's codes, recipient and gift message win over the draft on the first
 * mount (14 §10.2). `codes` = `cart.codes` of the cart store.
 */
export function mergeServerCodes(draft, codes) {
  if (!codes) return draft;

  const next = { ...draft };

  next.couponCode = codes.couponCode ?? '';
  next.creatorCode = codes.creatorCode ?? '';

  if (codes.recipientUsername) {
    next.isGift = true;
    next.recipientUsername = codes.recipientUsername;
    next.giftMessage = codes.giftMessage ?? '';
  }

  return next;
}

/** PUT /me/cart patch: only the members of `wanted` that differ from what the server holds (`held`). */
export function persistPatch(held, wanted) {
  const patch = {};

  for (const key of Object.keys(wanted))
    if (canonicalBody(held?.[key] ?? null) !== canonicalBody(wanted[key] ?? null))
      patch[key] = wanted[key] ?? null;

  return patch;
}
