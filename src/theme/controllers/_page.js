// Private helpers of the four page loaders (store, product, order, checkout).
/**
 * The request URL of a page load: `params.url` (string or URL) when the caller passed it, else an empty one on the host's origin.
 * @param {import('@panomc/plugin-kit/controller').ControllerHost} host
 * @param {{ url?: string | URL }} [params]
 */
export function pageUrl(host, params) {
  const base = host.baseUrl || 'http://localhost';
  const raw = params && params.url;

  try {
    if (raw instanceof URL) return raw;
    if (typeof raw === 'string' && raw) return new URL(raw, base);
  } catch (e) {
    // an unusable url is the same as none
  }

  try {
    return new URL('/', base);
  } catch (e) {
    return new URL('http://localhost/');
  }
}
