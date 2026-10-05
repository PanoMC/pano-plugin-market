// Submitting the checkout (14 §10.7): the body, the idempotency key, the POST and the plan for its outcome.
// Pure of the SDK and the DOM: `call` and the draft store are injected, unit-tested.
import { canonicalJson, resolveIdempotency } from './idempotency.js';
import { checkoutAction } from './errorMap.js';
import { afterCheckout, orderPagePath } from './paymentStart.js';
import { wireAddress } from './checkoutModel.js';

export const CHECKOUT_PATH = '/api/market/checkout';
export const IDEMPOTENCY_HEADER = 'Idempotency-Key';
/** sessionStorage key prefix of an order's access token (14 §11.1: `pano-plugin-market-order:<publicId>`). */
export const ORDER_TOKEN_PREFIX = 'pano-plugin-market-order:';

const isObject = (value) => value !== null && typeof value === 'object' && !Array.isArray(value);
const money = (value) => Math.round(Number(value) * 100) / 100;

/**
 * The body of `POST /api/market/checkout`: the quote body plus `expectedTotal` (the `total` the buyer is looking
 * at), `acceptLegal` + `legalTextId` when the legal text is required, `hideFromBroadcast` when ticked. `useCredits`
 * is always a number here: the credits the quote applied (never `"MAX"`); when none were applied it is left out.
 */
export function buildSubmitBody({
  quoteBody,
  quote,
  config = null,
  accepted = false,
  hide = false,
}) {
  const body = { ...(isObject(quoteBody) ? quoteBody : {}) };

  if (body.payWithCredits === true) delete body.useCredits;
  else if (body.useCredits !== undefined) {
    const applied = money(quote?.credits?.applied);

    if (Number.isFinite(applied) && applied > 0) body.useCredits = applied;
    else delete body.useCredits;
  }

  if (quote && quote.total !== undefined && quote.total !== null) body.expectedTotal = quote.total;

  if (config?.legal?.required === true) {
    body.acceptLegal = accepted === true;
    if (config.legal.id !== undefined && config.legal.id !== null)
      body.legalTextId = config.legal.id;
  }

  if (hide === true) body.hideFromBroadcast = true;

  return body;
}

/**
 * Key + headers for `body`: the draft's key while the body is unchanged (a retry replays), else a new UUID v4.
 * `patch` = the draft members to store.
 */
export function prepareSubmit({ draft, body, randomBytes }) {
  const resolved = resolveIdempotency(draft, body, randomBytes);

  return {
    headers: { [IDEMPOTENCY_HEADER]: resolved.idempotencyKey },
    patch: { idempotencyKey: resolved.idempotencyKey, bodyHash: resolved.bodyHash },
    reused: resolved.reused,
  };
}

/**
 * POSTs the checkout. `draftStore` = `{ get(), patch(p) }` (the checkout draft); the key and hash are written
 * to it before the request, so a lost answer is retried with the same key. `fresh` (see `quoteIsFresh`) must be
 * true: with a stale quote nothing is sent and the answer is `{ ok: false, code: 'STALE_QUOTE' }`. Resolves to `{ ok: true, order,
 * orderToken, payment, body, key }` or `{ ok: false, code, ...extras, body, key }` (never throws).
 */
export async function submitCheckout({
  call,
  draftStore,
  quoteBody,
  quote,
  config,
  accepted,
  hide,
  randomBytes,
  fresh = false,
}) {
  // the body is built from the quote (credits applied, expected total): never from an answer that is not current
  if (fresh !== true) return { ok: false, code: 'STALE_QUOTE', body: null, key: null };

  const body = buildSubmitBody({ quoteBody, quote, config, accepted, hide });
  const prepared = prepareSubmit({ draft: draftStore.get(), body, randomBytes });
  const key = prepared.headers[IDEMPOTENCY_HEADER];

  draftStore.patch(prepared.patch);

  let res;
  try {
    res = await call('POST', CHECKOUT_PATH, { body, headers: prepared.headers });
  } catch (e) {
    res = { ok: false, code: 'NETWORK' };
  }

  if (!res || typeof res !== 'object') res = { ok: false, code: 'NETWORK' };

  return { ...res, body, key };
}

/** Writes the order's access token (14 §11.1); a storage that throws is ignored (the order mail link works). */
export function saveOrderToken(storage, publicId, token) {
  if (!storage || typeof publicId !== 'string' || publicId === '') return false;
  if (typeof token !== 'string' || token === '') return false;

  try {
    storage.setItem(`${ORDER_TOKEN_PREFIX}${publicId}`, token);

    return true;
  } catch (e) {
    return false;
  }
}

const hasOrder = (res) => isObject(res?.order) && typeof res.order.publicId === 'string';

/**
 * What the page does after a successful answer (or PAYMENT_PROVIDER_ERROR with an order): `{ token, clearDraft,
 * clearCart, saveAddress, navigation }`. `topup` leaves the cart alone; the address is saved only for a logged-in
 * buyer who typed a new address and ticked the box.
 */
export function successPlan({
  res,
  topup = null,
  loggedIn = false,
  draft,
  context = {},
  providerError = false,
}) {
  const order = res?.order;

  return {
    token:
      typeof res?.orderToken === 'string' && res.orderToken !== ''
        ? { publicId: order?.publicId, value: res.orderToken }
        : null,
    clearDraft: true,
    clearCart: topup === null,
    saveAddress:
      loggedIn === true &&
      topup === null &&
      draft?.saveAddress === true &&
      (draft.shippingAddressId === null || draft.shippingAddressId === undefined)
        ? wireAddress(draft.shippingAddress)
        : null,
    navigation: providerError
      ? { type: 'GOTO', path: orderPagePath(order?.publicId) }
      : afterCheckout(res?.payment, order, context),
  };
}

/**
 * Turns a failed answer into the page's plan: the action of 14 §10.9 plus the draft patch for the idempotency
 * key (`{}` keeps it) and, for PAYMENT_PROVIDER_ERROR with an order, the success plan (the order exists).
 */
export function failurePlan({ res, topup = null, loggedIn = false, draft, context = {} }) {
  const action = checkoutAction(res?.code ?? 'NETWORK', res);
  const keyPatch = action.dropKey ? { idempotencyKey: null, bodyHash: null } : {};

  if (action.kind === 'ORDER_CREATED') {
    if (!hasOrder(res)) {
      const generic = checkoutAction('GENERIC', res);

      return { action: generic, keyPatch: { idempotencyKey: null, bodyHash: null }, success: null };
    }

    return {
      action,
      keyPatch: {},
      success: successPlan({ res, topup, loggedIn, draft, context, providerError: true }),
    };
  }

  return { action, keyPatch, success: null };
}

/**
 * The quote signature to hold after a failed submit: the outcomes that keep the idempotency key (`keepKey`: NETWORK,
 * STORE_BUSY, TOO_MANY_REQUESTS, INVALID_CSRF_TOKEN, ...) must be replayed with the same body, so the page neither
 * asks for a new quote nor lets the shown one change until an input changes (14 §10.9). `null` = no hold.
 */
export const quoteHoldAfter = (action, signature) =>
  action?.keepKey === true && typeof signature === 'string' ? signature : null;

/** True while the quote request for `signature` is suppressed by a hold. */
export const quoteHeld = (hold, signature) =>
  typeof hold === 'string' && hold !== '' && hold === signature;

/** Stable text of a submitted body (what the hash is made of): handy in tests and logs. */
export const submitBodyText = (body) => canonicalJson(body);
