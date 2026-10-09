// Pure part of the checkout draft (14 §10.2): the draft shape, what a stored string may become and who owns it.
// No state, no storage, no SDK; the stateful part is `controllers/_checkoutDraftEngine.js`. Unit-tested.
import { emptyAddress } from './checkoutModel.js';

export const DRAFT_KEY = 'pano-plugin-market-checkout';
export const DRAFT_DEBOUNCE = 200;

const ADDRESS_KEYS = Object.keys(emptyAddress());
const BILLING_KEYS = [...ADDRESS_KEYS, 'type', 'identityNumber', 'taxOffice', 'taxNumber'];

/** Owner key of a session user ('' for a guest); the cart store uses the same shape. */
export const ownerKeyOf = (u) => (u ? `u:${u.id ?? u.username ?? ''}` : '');

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

/** Owner key stored beside a draft ('' = guest or unknown). */
export function storedOwner(raw) {
  let value = raw;

  if (typeof raw === 'string') {
    try {
      value = JSON.parse(raw);
    } catch (e) {
      return '';
    }
  }

  return isObject(value) && typeof value.owner === 'string' ? value.owner : '';
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
