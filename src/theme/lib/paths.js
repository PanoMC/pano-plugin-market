// Where the market's site endpoints live on /api/v1 (doc 04 section 1). Pure: no SDK, no Svelte, no globals, so the
// standalone controllers module and the readable view helpers can both import it.
//
// Callers of the `market/api` controller write paths relative to the plugin (`/store/products`); the request wrapper
// adds `PLUGIN_API_PREFIX`, the same prefix `createPluginApi('pano-plugin-market')` of `@panomc/sdk/plugin-api` adds
// (a controller imports no SDK code, so it cannot use that module). URLs that are not API calls (an `<img src>`, a
// download link, the payment attempt page) need the full path and use `siteUrl`.

/** The full plugin id, the only form allowed in an API path (the namespace `market` never appears there). */
export const PLUGIN_ID = 'pano-plugin-market';

/** What follows `/api/v1` for every site endpoint of the market. */
export const PLUGIN_API_PREFIX = `/plugins/${PLUGIN_ID}`;

/** Absolute path of the market's site API, for markup and links: `/api/plugins/pano-plugin-market`. */
export const SITE_API_ROOT = `/api${PLUGIN_API_PREFIX}`;

/** `/api/plugins/pano-plugin-market/<path>` for a plugin-relative path such as `/products/image/a.png`. */
export const siteUrl = (path) => `${SITE_API_ROOT}${path.startsWith('/') ? '' : '/'}${path}`;

/** The plugin-relative path as the host sends it: `/store` -> `/plugins/pano-plugin-market/store`. */
export const hostPath = (path) => `${PLUGIN_API_PREFIX}${path.startsWith('/') ? '' : '/'}${path}`;
