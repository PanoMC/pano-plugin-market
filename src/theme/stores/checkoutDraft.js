// Checkout draft (14 §10.2): sessionStorage['pano-plugin-market-checkout'], restored on mount and written on
// every change (debounced 200 ms). Legal acceptance is never stored. Cleared after a successful checkout and
// on logout. `createCheckoutDraft(deps)` builds an instance with injectable storage and timers (tests);
// `checkoutDraft` is the instance of the running theme. Nothing here reads storage at module top level.
import { get, writable } from 'svelte/store';
import { emptyAddress } from '../lib/checkoutModel.js';
import { isLoggedIn } from './session.js';

export const DRAFT_KEY = 'pano-plugin-market-checkout';
export const DRAFT_DEBOUNCE = 200;

const ADDRESS_KEYS = Object.keys(emptyAddress());
const BILLING_KEYS = [...ADDRESS_KEYS, 'type', 'identityNumber', 'taxOffice', 'taxNumber'];

export const defaultDraft = () => ({
  guest: { username: '', email: '' },
  isGift: false,
  recipientUsername: '',
  giftMessage: '',
  couponCode: '',
  creatorCode: '',
  shippingAddressId: null,
  shippingAddress: emptyAddress(),
  shippingMethodId: null,
  saveAddress: false,
  billingOpen: false,
  billingSameAsShipping: true,
  billingInfo: {
    ...emptyAddress(),
    type: 'INDIVIDUAL',
    identityNumber: '',
    taxOffice: '',
    taxNumber: '',
  },
  paymentMethodId: null,
  payWithCredits: false,
  useCredits: null,
  idempotencyKey: null,
  bodyHash: null,
});

const isObject = (value) => value !== null && typeof value === 'object' && !Array.isArray(value);
const str = (value, fallback = '') => (typeof value === 'string' ? value : fallback);
const bool = (value, fallback) => (typeof value === 'boolean' ? value : fallback);
const idOrNull = (value) =>
  typeof value === 'string' && value !== ''
    ? value
    : Number.isFinite(value) && typeof value === 'number'
      ? value
      : null;

function pickStrings(source, keys, base) {
  const out = { ...base };
  if (isObject(source)) for (const key of keys) out[key] = str(source[key], base[key]);
  return out;
}

/** Draft of a stored string: unknown keys dropped, wrong types replaced by the default; garbage => defaults. */
export function parseDraft(raw) {
  const base = defaultDraft();
  let value = raw;

  if (typeof raw === 'string') {
    try {
      value = JSON.parse(raw);
    } catch (e) {
      return base;
    }
  }

  if (!isObject(value)) return base;

  const useCredits =
    value.useCredits === 'MAX'
      ? 'MAX'
      : typeof value.useCredits === 'number' &&
          Number.isFinite(value.useCredits) &&
          value.useCredits >= 0
        ? value.useCredits
        : null;

  const billing = pickStrings(value.billingInfo, BILLING_KEYS, base.billingInfo);
  if (billing.type !== 'COMPANY') billing.type = 'INDIVIDUAL';

  return {
    guest: pickStrings(value.guest, ['username', 'email'], base.guest),
    isGift: bool(value.isGift, base.isGift),
    recipientUsername: str(value.recipientUsername),
    giftMessage: str(value.giftMessage),
    couponCode: str(value.couponCode),
    creatorCode: str(value.creatorCode),
    shippingAddressId: idOrNull(value.shippingAddressId),
    shippingAddress: pickStrings(value.shippingAddress, ADDRESS_KEYS, base.shippingAddress),
    shippingMethodId: idOrNull(value.shippingMethodId),
    saveAddress: bool(value.saveAddress, base.saveAddress),
    billingOpen: bool(value.billingOpen, base.billingOpen),
    billingSameAsShipping: bool(value.billingSameAsShipping, base.billingSameAsShipping),
    billingInfo: billing,
    paymentMethodId: idOrNull(value.paymentMethodId),
    payWithCredits: bool(value.payWithCredits, base.payWithCredits),
    useCredits,
    idempotencyKey: str(value.idempotencyKey) || null,
    bodyHash: str(value.bodyHash) || null,
  };
}

function defaultStorage() {
  try {
    return globalThis.sessionStorage ?? null;
  } catch (e) {
    return null;
  }
}

const defaultTiming = () => ({
  ms: DRAFT_DEBOUNCE,
  set: (fn, ms) => setTimeout(fn, ms),
  clear: (id) => clearTimeout(id),
});

export function createCheckoutDraft(overrides = {}) {
  const deps = { storage: defaultStorage, timing: defaultTiming(), ...overrides };
  const state = writable(defaultDraft());

  let restored = false;
  let timer = null;

  const store = () => {
    try {
      return typeof deps.storage === 'function' ? deps.storage() : deps.storage;
    } catch (e) {
      return null;
    }
  };

  function write() {
    timer = null;

    try {
      store()?.setItem(DRAFT_KEY, JSON.stringify(get(state)));
    } catch (e) {
      // storage unavailable (private mode / quota): the draft lives for this page only
    }
  }

  function schedule() {
    if (!restored) return;
    if (timer !== null) deps.timing.clear(timer);
    timer = deps.timing.set(write, deps.timing.ms);
  }

  /** Reads the stored draft (once per mount; garbage => defaults) and starts persisting changes. */
  function restore() {
    let raw = null;

    try {
      raw = store()?.getItem(DRAFT_KEY) ?? null;
    } catch (e) {
      raw = null;
    }

    state.set(raw === null ? defaultDraft() : parseDraft(raw));
    restored = true;

    return get(state);
  }

  function patch(partial) {
    // only draft members are kept: legal acceptance and other page state never reach the storage
    const known = {};
    for (const key of Object.keys(defaultDraft())) if (key in partial) known[key] = partial[key];

    state.update((draft) => ({ ...draft, ...known }));
    schedule();
  }

  function set(draft) {
    state.set(parseDraft(draft));
    schedule();
  }

  /** Writes at once (before navigating away). */
  function flush() {
    if (timer !== null) deps.timing.clear(timer);
    if (restored) write();
    timer = null;
  }

  /** After a successful checkout and on logout: storage emptied, state back to the defaults. */
  function clear() {
    if (timer !== null) deps.timing.clear(timer);
    timer = null;

    try {
      store()?.removeItem(DRAFT_KEY);
    } catch (e) {
      // not critical
    }

    state.set(defaultDraft());
  }

  /** Leaving the page: stop pending writes (the next mount restores). */
  function detach() {
    flush();
    restored = false;
  }

  /** Clears the draft when the buyer logs out (logged in -> not logged in). Returns the unsubscribe. */
  function watchLogout(loggedIn = isLoggedIn) {
    let was = null;

    return loggedIn.subscribe((value) => {
      if (was === true && value === false) clear();
      was = value;
    });
  }

  return {
    subscribe: state.subscribe,
    get: () => get(state),
    restore,
    patch,
    set,
    flush,
    clear,
    detach,
    watchLogout,
  };
}

export const checkoutDraft = createCheckoutDraft();
