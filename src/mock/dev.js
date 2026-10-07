// Development-mode probe. The host exposes it in /api/siteInfo (`developmentMode`); the answer is
// cached (client: per page life, server: a few seconds) so a request cookie costs one tiny GET.
const TTL = 5000;
let cached = null;

/** async (ApiUtil, event) => boolean. Never throws; unknown = false. */
export function developmentMode(api, event, now = Date.now) {
  const t = now();
  if (cached && t - cached.at < TTL) return cached.promise;
  const promise = Promise.resolve(api.get({ path: '/api/siteInfo', request: event }))
    .then((body) => body?.developmentMode === true)
    .catch(() => false);
  cached = { at: t, promise };
  return promise;
}

/** Test hook. */
export function resetDevCache() {
  cached = null;
}
