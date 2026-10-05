// Request wrapper around the SDK ApiUtil (14 §4.2). Never throws; callers branch on `ok` / `code`.
import ApiUtil from '@panomc/sdk/utils/api';
import { buildQuery, normalize } from '../lib/api-result.js';
import { get } from 'svelte/store';
import { csrfToken as boundCsrfToken } from '../stores/session.js';

/**
 * call(method, path, { body, query, event, headers, session, blob }) -> ApiResult
 * `session` may be a session object or a store value carrying `csrfToken`; when omitted the bound session is used.
 */
export async function call(method, path, options = {}) {
  const { body, query, event, headers, session, blob } = options;
  const verb = String(method || 'GET').toUpperCase();
  const fullPath = `${path}${buildQuery(query)}`;
  const csrfToken = session?.csrfToken || get(boundCsrfToken) || undefined;

  try {
    let raw;

    if (verb === 'GET') {
      raw =
        headers && Object.keys(headers).length
          ? await ApiUtil.customRequest({
              path: fullPath,
              data: { method: 'GET', headers },
              request: event,
              csrfToken,
              blob,
            })
          : await ApiUtil.get({ path: fullPath, request: event, csrfToken, blob });
    } else if (verb === 'POST') {
      raw = await ApiUtil.post({ path: fullPath, request: event, body, headers, csrfToken, blob });
    } else if (verb === 'PUT') {
      raw = await ApiUtil.put({ path: fullPath, request: event, body, headers, csrfToken, blob });
    } else if (verb === 'DELETE') {
      raw = await ApiUtil.delete({ path: fullPath, request: event, headers, csrfToken, blob });
    } else {
      return { ok: false, code: 'GENERIC' };
    }

    // A blob answer is not a JSON envelope: pass it through as a successful result.
    if (blob && raw instanceof Blob) return { ok: true, blob: raw };

    return normalize(raw);
  } catch (e) {
    return { ok: false, code: 'NETWORK' };
  }
}

export const apiGet = (path, options) => call('GET', path, options);
export const post = (path, options) => call('POST', path, options);
export const put = (path, options) => call('PUT', path, options);
export const del = (path, options) => call('DELETE', path, options);
