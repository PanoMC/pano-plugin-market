// List loading shared by the market list pages (13 §3.3).
import { buildQueryParams } from '@panomc/sdk/utils/api';
import { api } from '@panomc/sdk/plugin-api';
import { base, goto } from '@panomc/sdk/svelte';
import { loadListWith } from './list-core.js';

const deps = { get: (options) => api.panel.get(options), buildQueryParams };

/**
 * load() of a list page.
 *   path     endpoint relative to the plugin's panel API, e.g. '/orders'
 *   params   names of the URL filters forwarded to the API (page is handled here)
 *   nodes    permission keys, any of which opens the page
 *   title    optional page-title key below the plugin root, e.g. 'pages.orders.title'
 */
export function loadList(event, options) {
  return loadListWith(deps, event, options);
}

/** goto() for list state: filters and search changes drop `page`; the page is remounted by load(). */
export function gotoList(pathname, params = {}) {
  return goto(base + pathname + buildQueryParams(params), {
    invalidateAll: true,
    keepFocus: true,
    noscroll: true,
  });
}
