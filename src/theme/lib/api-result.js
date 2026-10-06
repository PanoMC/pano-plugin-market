// Pure part of the request wrapper (14 §4.2). No SDK import, unit-tested.

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

/**
 * Turns the raw API answer into an ApiResult.
 *  undefined / non-object / no `result` -> { ok: false, code: 'NETWORK' }
 *  result === 'error'                    -> { ok: false, ...raw, code: raw.error }
 * The envelope's own `error` is the code. Some answers carry a `code` of their own (PAYMENT_PROVIDER_ERROR: the provider's
 * `GATEWAY_UNREACHABLE`): it is kept as `providerCode` and never replaces the envelope's code, or the page would not
 * recognise the answer (the checkout would show "something went wrong" instead of going to the order page).
 *  otherwise                             -> { ok: true, ...raw }
 */
export function normalize(raw) {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw) || !('result' in raw))
    return { ok: false, code: 'NETWORK' };

  if (raw.result === 'error') {
    const { result, error, code: providerCode, ...rest } = raw;

    return {
      ok: false,
      ...rest,
      ...(providerCode === undefined ? {} : { providerCode }),
      code: typeof error === 'string' && error ? error : 'GENERIC',
    };
  }

  const { result, ...rest } = raw;

  return { ok: true, ...rest };
}
