// Build-time replacement of `@panomc/sdk/utils/api` for every file of this plugin (rollup.config.js,
// pano-market-api-seam). Production cost: a cookie check per request, nothing else. The fixtures
// (./router.js) are a dynamic import that only runs when the cookie is set AND the platform reports
// development mode.
import realApi, { NETWORK_ERROR, networkErrorBody, buildQueryParams } from '@panomc/sdk/utils/api';
import { get } from 'svelte/store';
import { _ as i18n } from '@panomc/sdk/utils/language';
import { showToast } from '@panomc/sdk/toasts';
import { createSeam } from './core.js';
import { developmentMode } from './dev.js';

let last = 0;
function notify() {
  // One toast per burst (a modal that saves twice should not stack two).
  const now = typeof performance !== 'undefined' ? performance.now() : 0;
  if (last && now - last < 1500) return;
  last = now;
  try {
    const text = get(i18n)('plugins.pano-plugin-market.mock.saved-nothing');
    showToast(text, {}, undefined, { variant: 'warning' });
  } catch {
    // no toast host (SSR): nothing to show
  }
}

const seam = createSeam({
  real: realApi,
  getDevMode: (event) => developmentMode(realApi, event),
  loadRouter: () => import('./router.js').then((m) => m.router),
  notify,
});

export { realApi as real, seam as ApiUtil, NETWORK_ERROR, networkErrorBody, buildQueryParams };
export default seam;
