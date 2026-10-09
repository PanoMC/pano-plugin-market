// What happens after a successful checkout (14 §10.8). Pure: the page performs the navigation this returns.
// Nothing here trusts a URL the server (or a gateway plugin) sent: a redirect target is http(s) only, an
// attempt page must be on the same origin under /api/plugins/pano-plugin-market/payments/attempts/.
import { SITE_API_ROOT } from './paths.js';

export const ATTEMPT_PATH_PREFIX = `${SITE_API_ROOT}/payments/attempts/`;

/** Payment kinds rendered on the order page (the order carries `payment.start`, 14 §11.4). */
export const IN_PAGE_KINDS = ['IFRAME', 'EMBEDDED', 'INSTRUCTIONS', 'COMPLETED'];

const PUBLIC_ID = /^[A-Za-z0-9_-]{1,64}$/;

const hasControlChars = (value) => {
  for (let i = 0; i < value.length; i++) {
    const code = value.charCodeAt(i);
    if (code <= 0x20 || code === 0x7f) return true;
  }

  return false;
};

/** True for an absolute `http:` / `https:` URL without whitespace or control characters. */
export function isSafeExternalUrl(value) {
  if (typeof value !== 'string' || value.length < 1 || value.length > 4096) return false;
  if (hasControlChars(value)) return false;
  if (!/^https?:\/\//i.test(value)) return false;

  try {
    const url = new URL(value);

    return (url.protocol === 'http:' || url.protocol === 'https:') && url.hostname !== '';
  } catch (e) {
    return false;
  }
}

/**
 * True for the market attempt page (FORM_POST / HTML): same origin as `origin` and the path starts with
 * `<base>/api/plugins/pano-plugin-market/payments/attempts/`. `value` may be absolute or root-relative.
 */
export function isAttemptPageUrl(value, { origin, base = '' } = {}) {
  if (typeof value !== 'string' || value.length < 1 || value.length > 4096) return false;
  if (hasControlChars(value) || value.includes('\\')) return false;
  if (typeof origin !== 'string' || origin === '') return false;

  let url;
  try {
    url = new URL(value, origin);
  } catch (e) {
    return false;
  }

  if (url.origin !== new URL(origin).origin) return false;
  if (url.username !== '' || url.password !== '') return false;

  const prefix = `${typeof base === 'string' ? base.replace(/\/+$/, '') : ''}${ATTEMPT_PATH_PREFIX}`;

  return url.pathname.startsWith(prefix) && url.pathname.length > prefix.length;
}

/** Site path of the order page (`/store/order/<publicId>`), or `/store` when the id is not a plain id. */
export function orderPagePath(publicId) {
  return typeof publicId === 'string' && PUBLIC_ID.test(publicId)
    ? `/store/order/${publicId}`
    : '/store';
}

/**
 * `{ type: 'ASSIGN', url }` (leave the site: `window.location.assign(url)`) or `{ type: 'GOTO', path }`
 * (client navigation). `payment` = PaymentStart, `order` = OrderView (needs `publicId`). A kind this theme
 * does not know, an unsafe URL and a missing payment all end on the order page, which shows the state.
 * `context` = `{ origin, base }` of the running page.
 */
export function afterCheckout(payment, order, context = {}) {
  const orderPage = { type: 'GOTO', path: orderPagePath(order?.publicId) };
  const kind = payment?.kind;

  if (kind === 'REDIRECT')
    return isSafeExternalUrl(payment.url) ? { type: 'ASSIGN', url: payment.url } : orderPage;

  if (kind === 'FORM_POST' || kind === 'HTML')
    return isAttemptPageUrl(payment.url, context)
      ? { type: 'ASSIGN', url: payment.url }
      : orderPage;

  return orderPage;
}
