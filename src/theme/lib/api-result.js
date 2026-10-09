// Pure part of the request wrapper (14 §4.2) on the /api/v1 envelope (doc 04 sections 3 and 4). No SDK import, unit-tested.

/** Builds "?a=1&b=2" (or ''). Keeps 0 and false, drops null, undefined and ''. */
export function buildQuery(query) {
  if (!query || typeof query !== 'object') return '';

  const params = new URLSearchParams();

  for (const [key, value] of Object.entries(query)) {
    if (value === null || value === undefined || value === '') continue;

    if (Array.isArray(value)) {
      for (const item of value) {
        if (item === null || item === undefined || item === '') continue;
        params.append(key, String(item));
      }
    } else {
      params.append(key, String(value));
    }
  }

  const text = params.toString();

  return text ? `?${text}` : '';
}

/** Transport failures (no answer from Pano at all): the host's own codes, all reported as `NETWORK`. */
export const NETWORK_CODES = new Set([
  'NETWORK_ERROR',
  'NULL_HOST',
  'NO_REQUEST_EVENT',
  'INVALID_RESPONSE',
]);

const isObject = (value) => value !== null && typeof value === 'object' && !Array.isArray(value);

/**
 * Turns the raw API answer into an ApiResult. A success has no `error` key (and no `result` key); a failure is
 * `{ error: { code, message?, details?, fields? } }`.
 *  undefined / non-object                 -> { ok: false, code: 'NETWORK' }
 *  error.code is a transport code         -> { ok: false, code: 'NETWORK' }
 *  error present                          -> { ok: false, ...error.details, fields?, message?, details?, code }
 *  otherwise                              -> { ok: true, ...raw }
 * The members of `error.details` are spread to the top of the result (`reason`, `retryAfter`, `lineErrors`, ...), so the
 * readers keep their shape; `details` is also kept whole. Some answers carry a `code` of their own in their details
 * (PAYMENT_PROVIDER_ERROR: the provider's `GATEWAY_UNREACHABLE`): it is kept as `providerCode` and never replaces the
 * envelope's code, or the page would not recognise the answer (the checkout would show "something went wrong" instead
 * of going to the order page).
 */
export function normalize(raw) {
  if (!isObject(raw)) return { ok: false, code: 'NETWORK' };

  const error = raw.error;

  if (error === undefined || error === null) return { ok: true, ...raw };

  const failure = isObject(error) ? error : {};
  const code = typeof failure.code === 'string' && failure.code ? failure.code : 'GENERIC';

  if (NETWORK_CODES.has(code)) return { ok: false, code: 'NETWORK' };

  const details = isObject(failure.details) ? failure.details : {};
  const { code: providerCode, ...extras } = details;

  return {
    ok: false,
    ...extras,
    ...(failure.fields === undefined ? {} : { fields: failure.fields }),
    ...(typeof failure.message === 'string' ? { message: failure.message } : {}),
    ...(providerCode === undefined ? {} : { providerCode }),
    ...(Object.keys(details).length ? { details } : {}),
    code,
  };
}

/**
 * Reads a list answer (doc 04 section 4): `{ items, page: { number, size, totalItems, totalPages } }`, other top-level keys
 * stay on the result. An unpaged list has no `page`: its length is the total. `totalPages` is at least 1, as the pager wants it.
 * @param {object | null | undefined} res a normalised ApiResult
 * @returns {{ items: any[], number: number, size: number | null, totalItems: number, totalPages: number }}
 */
export function readList(res) {
  const source = isObject(res) ? res : {};
  const page = isObject(source.page) ? source.page : null;
  const list = Array.isArray(source.items) ? source.items : [];
  const count = (value) => (Number.isFinite(Number(value)) ? Number(value) : null);
  const totalItems = count(page?.totalItems) ?? list.length;
  const totalPages = count(page?.totalPages) ?? 1;

  return {
    items: list,
    number: count(page?.number) ?? 1,
    size: count(page?.size),
    totalItems,
    totalPages: Math.max(1, Math.floor(totalPages)),
  };
}
