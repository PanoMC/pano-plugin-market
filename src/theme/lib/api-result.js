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
 *  result === 'error'                    -> { ok: false, code: raw.error, ...raw }
 *  otherwise                             -> { ok: true, ...raw }
 */
export function normalize(raw) {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw) || !('result' in raw))
    return { ok: false, code: 'NETWORK' };

  if (raw.result === 'error') {
    const { result, error, ...rest } = raw;
    return { ok: false, code: typeof error === 'string' && error ? error : 'GENERIC', ...rest };
  }

  const { result, ...rest } = raw;

  return { ok: true, ...rest };
}
