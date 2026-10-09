// Request wrapper of the `market/api` controller (doc 02 section 3). `createApi(host)` is the body of the old
// the `call` of the market/api controller, with the transport behind `host.request` (a theme, fetch or null host). Never throws;
// callers branch on `ok` / `code`. Pure: no SDK, no Svelte, so it also runs inside the standalone controllers module.
//
// Paths are relative to the plugin (`/store/products`); the wrapper sends `/plugins/pano-plugin-market/store/products`,
// which the host puts after `/api/v1` (what `createPluginApi` of `@panomc/sdk/plugin-api` does for code that may import
// the SDK; a controller may not). The host answers in doc 04's shape: a success has no `error` key, a failure is
// `{ error: { code, message?, details?, fields? } }`, and `normalize` turns both into an ApiResult.
import { buildQuery, normalize } from './api-result.js';
import { hostPath } from './paths.js';

/** Name of the global the preview boot (src/mock/boot.js) sets; a global because controllers and views may hold separate copies of this module. */
export const PREVIEW_HOOK = '__PANO_MARKET_PREVIEW__';

/**
 * @param {import('@panomc/plugin-kit/controller').ControllerHost} host
 */
export function createApi(host) {
  /**
   * call(method, path, { body, query, headers, blob }) -> ApiResult
   * `event` and `session` of the old wrapper are not options any more: the host carries the request event and the
   * session (a server host is created per call from the event, the CSRF token is the host session's).
   */
  async function call(method, path, options = {}) {
    const { body, query, headers, blob } = options || {};
    const verb = String(method || 'GET').toUpperCase();

    if (!['GET', 'POST', 'PUT', 'DELETE'].includes(verb)) return { ok: false, code: 'GENERIC' };

    try {
      const hostedPath = `${hostPath(String(path))}${buildQuery(query)}`;

      // Development preview (fake data): the browser-only boot of src/mock installs this hook while the preview is on in
      // development mode; it answers from the fixtures (undefined = not answered, the request goes on). Absent otherwise.
      const preview = globalThis[PREVIEW_HOOK];
      if (typeof preview === 'function' && !blob) {
        const answered = await preview(verb, hostedPath, body);
        if (answered !== undefined) return normalize(answered);
      }

      const raw = await host.request({
        method: verb,
        path: hostedPath,
        ...(verb === 'GET' || verb === 'DELETE' ? {} : { body }),
        ...(headers && Object.keys(headers).length ? { headers } : {}),
        ...(blob ? { blob } : {}),
      });

      // A blob answer is not a JSON envelope: pass it through as a successful result.
      if (blob && typeof Blob !== 'undefined' && raw instanceof Blob)
        return { ok: true, blob: raw };

      return normalize(raw);
    } catch (e) {
      return { ok: false, code: 'NETWORK' };
    }
  }

  return {
    call,
    apiGet: (path, options) => call('GET', path, options),
    post: (path, options) => call('POST', path, options),
    put: (path, options) => call('PUT', path, options),
    del: (path, options) => call('DELETE', path, options),
  };
}
