// Core of the development-only preview mode (no SDK / Svelte import, so it is unit tested).
//
// The seam (seam.js) replaces `@panomc/sdk/utils/api` for the whole plugin at build time. With the
// cookie absent it is a pass-through; with the cookie present it asks the host whether the platform
// runs in development mode and only then loads the fixtures (dynamic import) and answers from them.
import { DEFAULT_VOLUME, VOLUMES, queryOf } from './kit.js';

export const COOKIE = 'pano_market_mock';

/** Cookie value `<volume>` (on) or absent (off). Anything unknown is off. */
export function parseCookie(header) {
  if (!header) return null;
  for (const part of String(header).split(';')) {
    const [name, ...rest] = part.trim().split('=');
    if (name === COOKIE) {
      const volume = decodeURIComponent(rest.join('='));
      return VOLUMES.includes(volume) ? volume : DEFAULT_VOLUME;
    }
  }
  return null;
}

export function cookieString(volume) {
  if (!volume) return `${COOKIE}=; Path=/; Max-Age=0; SameSite=Lax`;
  return `${COOKIE}=${encodeURIComponent(volume)}; Path=/; Max-Age=86400; SameSite=Lax`;
}

/** Reads the cookie in the browser (document) or on the server (SvelteKit event / request). */
export function readVolume(event, doc = typeof document !== 'undefined' ? document : null) {
  if (doc) return parseCookie(doc.cookie);
  const fromKit = event?.cookies?.get?.(COOKIE);
  if (fromKit) return parseCookie(`${COOKIE}=${fromKit}`);
  const header = event?.request?.headers?.get?.('cookie') ?? event?.headers?.get?.('cookie');
  return parseCookie(header);
}

/** '/orders/detail/:id' style pattern to a RegExp + param names. */
function compile(pattern) {
  const names = [];
  const source = pattern
    .replace(/\/+$/, '')
    .split('/')
    .map((seg) => {
      if (seg.startsWith(':')) {
        names.push(seg.slice(1));
        return '([^/]+)';
      }
      return seg.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    })
    .join('/');
  return { regex: new RegExp(`^${source}/?$`), names };
}

/**
 * Route table. A route is { method, path, handler({ query, params, volume, body, method }), safe? }.
 * `safe` marks a POST that only reads (a quote): it is answered from the fixture without the toast.
 */
export function createRouter(routes) {
  const compiled = routes.map((route) => ({ ...route, ...compile(route.path) }));

  function find(method, fullPath) {
    const url = new URL(fullPath, 'http://mock.local');
    const pathname = url.pathname.replace(/\/+$/, '') || '/';
    for (const route of compiled) {
      if (route.method !== method) continue;
      const m = route.regex.exec(pathname);
      if (!m) continue;
      const params = Object.fromEntries(
        route.names.map((name, i) => [name, decodeURIComponent(m[i + 1])]),
      );
      return { route, params, query: queryOf(url.search) };
    }
    return null;
  }

  return {
    routes: compiled,
    find,
    /** Fixture body for a GET (or safe POST), or undefined when the path has no fixture. */
    answer(method, fullPath, volume, body) {
      const hit = find(method, fullPath);
      if (!hit) return undefined;
      return hit.route.handler({ ...hit, volume, body, method });
    },
    isSafe(method, fullPath) {
      return find(method, fullPath)?.route.safe === true;
    },
  };
}

/**
 * Hydration guard. A themed (SSR) page cannot read the cookie on the server (SvelteKit's universal
 * load event has no cookies), so the server renders real data. Until boot.js has mounted the preview
 * and re-run the loads, the browser must therefore answer like the server did: no fixtures.
 */
export const gate = { deferred: false };

export const SAVED_NOTHING = { result: 'ok', id: 1 };

/**
 * createSeam({ real, getDevMode, loadRouter, notify })
 *   real        the host ApiUtil
 *   getDevMode  async (event) => boolean (cached by the caller)
 *   loadRouter  async () => router (dynamic import of the fixtures)
 *   notify      () => void, the "nothing was saved" toast
 * Returns an ApiUtil-shaped object (get / post / put / delete / customRequest + the rest of real).
 */
export function createSeam({ real, getDevMode, loadRouter, notify, readVolumeFn = readVolume }) {
  // The gate: cookie first (free), then the development-mode check, then the fixtures.
  async function activeVolume(event) {
    let volume;
    try {
      volume = readVolumeFn(event);
    } catch {
      return null;
    }
    if (!volume || gate.deferred) return null;
    try {
      if ((await getDevMode(event)) !== true) return null;
    } catch {
      return null;
    }
    return volume;
  }

  async function dispatch(method, options, passthrough) {
    const volume = await activeVolume(options?.request);
    if (!volume) return passthrough();

    const router = await loadRouter();
    const path = options?.path ?? '';
    if (method === 'GET' || router.isSafe(method, path)) {
      const answered = router.answer(method, path, volume, parseBody(options?.body));
      return answered === undefined ? passthrough() : answered;
    }
    // POST / PUT / DELETE never reach the backend while the preview is on.
    notify();
    return router.answer(method, path, volume, parseBody(options?.body)) ?? SAVED_NOTHING;
  }

  const wrapped = Object.create(real);
  for (const method of ['get', 'post', 'put', 'delete']) {
    wrapped[method] = (options) =>
      dispatch(method.toUpperCase(), options, () => real[method](options));
  }
  wrapped.customRequest = (options) => {
    const method = String(options?.data?.method || 'GET').toUpperCase();
    return dispatch(method, options, () => real.customRequest(options));
  };
  return wrapped;
}

function parseBody(body) {
  if (typeof body === 'string') {
    try {
      return JSON.parse(body);
    } catch {
      return undefined;
    }
  }
  return body;
}
