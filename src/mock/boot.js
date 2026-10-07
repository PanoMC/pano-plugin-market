// Client-only entry of the development preview: shows the floating button when (and only when) the
// platform runs in development mode. Nothing here runs on the server or in production: the module is
// imported dynamically from register.js and the first thing it checks is the mode.
import { get } from 'svelte/store';
import { mount } from 'svelte';
import { real } from './seam.js';
import { developmentMode } from './dev.js';

let mounted = false;

/** Host page data first (no request); /api/siteInfo only when the page data does not carry it. */
async function isDevelopment(pano) {
  try {
    const known = get(pano.page)?.data?.siteInfo?.developmentMode;
    if (typeof known === 'boolean') return known;
  } catch {
    // fall through to the probe
  }
  return developmentMode(real);
}

export async function startDevPreview(pano) {
  if (mounted || typeof document === 'undefined') return;
  if ((await isDevelopment(pano)) !== true) return;
  mounted = true;
  const { default: DevPreview } = await import('./DevPreview.svelte');
  mount(DevPreview, { target: document.body });
}
