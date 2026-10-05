// Order access tokens (14 §11.1). One sessionStorage key per order, so the capability does not outlive the tab.
// Every access is wrapped: storage can be missing (SSR), blocked or throw (private window).
import { isUsableToken } from '../lib/orderState.js';

/** Key prefix; the key of an order is `pano-plugin-market-order:<publicId>`. */
export const ORDER_TOKEN_PREFIX = 'pano-plugin-market-order:';

export const orderTokenKey = (publicId) => `${ORDER_TOKEN_PREFIX}${publicId}`;

function storage() {
  try {
    return typeof sessionStorage === 'undefined' ? null : sessionStorage;
  } catch (e) {
    return null;
  }
}

/** Stores the token of an order; false when nothing was stored (bad token, no storage). */
export function save(publicId, token) {
  if (typeof publicId !== 'string' || publicId === '' || !isUsableToken(token)) return false;

  try {
    const store = storage();
    if (!store) return false;
    store.setItem(orderTokenKey(publicId), token);

    return true;
  } catch (e) {
    return false;
  }
}

/** The stored token of an order, or null. */
export function get(publicId) {
  if (typeof publicId !== 'string' || publicId === '') return null;

  try {
    const value = storage()?.getItem(orderTokenKey(publicId));

    return isUsableToken(value) ? value : null;
  } catch (e) {
    return null;
  }
}

export function remove(publicId) {
  if (typeof publicId !== 'string' || publicId === '') return;

  try {
    storage()?.removeItem(orderTokenKey(publicId));
  } catch (e) {
    // nothing to remove
  }
}

/** `{ 'X-Order-Token': token }` when a token is known (explicit one first, then the stored one), else `undefined`. */
export function tokenHeaders(publicId, explicit = null) {
  const token = isUsableToken(explicit) ? explicit : get(publicId);

  return token ? { 'X-Order-Token': token } : undefined;
}

export const orderTokens = { save, get, remove, tokenHeaders };
