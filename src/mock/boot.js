// Client-only entry of the development preview: shows the floating button when (and only when) the
// platform runs in development mode. Nothing here runs on the server or in production: the module is
// imported dynamically from register.js and the first thing it checks is the mode.
import { get } from 'svelte/store';
import { mount, tick } from 'svelte';
import { invalidateAll } from '@panomc/sdk/svelte';
import { real } from './seam.js';
import { developmentMode } from './dev.js';
import { gate, readVolume } from './core.js';

let mounted = false;

const CACHE_KEY = 'pano_market_dev_mode';
const CACHE_TTL = 60000;

function isLocalAddress(host = location.hostname) {
  return (
    host === 'localhost' ||
    host === '[::1]' ||
    /^127\./.test(host) ||
    /^(10|192\.168|172\.(1[6-9]|2\d|3[01]))\./.test(host) ||
    /\.(localhost|local|test)$/.test(host)
  );
}

function cached(now = Date.now()) {
  try {
    const [at, flag] = String(sessionStorage.getItem(CACHE_KEY) || '').split(':');
    if (at && now - Number(at) < CACHE_TTL) return flag === '1';
  } catch {
    // storage blocked: probe every time
  }
  return null;
}

function remember(flag, now = Date.now()) {
  try {
    sessionStorage.setItem(CACHE_KEY, `${now}:${flag ? 1 : 0}`);
  } catch {
    // ignore
  }
}

/**
 * Host page data first (no request; only readable inside a component context, so it usually is not),
 * then, on a local address or with the preview cookie set, a per-tab cache of the last answer (60 s)
 * and only then one GET /api/siteInfo.
 */
async function isDevelopment(pano) {
  try {
    const known = (get(pano.page)?.data?.siteInfo ?? get(pano.page)?.data?.session?.siteInfo)
      ?.developmentMode;
    if (typeof known === 'boolean') return known;
  } catch {
    // fall through
  }
  // No request for ordinary visitors of a live site: the probe below only runs on a local address
  // (where development happens) or when the preview cookie is already set.
  if (!isLocalAddress() && !readVolume()) return false;
  const hit = cached();
  if (hit !== null) return hit;
  const flag = await developmentMode(real);
  remember(flag);
  return flag;
}

export async function startDevPreview(pano) {
  if (mounted || typeof document === 'undefined') return;
  // The page data of the host carries siteInfo once the page is hydrated: no request needed in production.
  await hydrated();
  if ((await isDevelopment(pano)) !== true) return;
  mounted = true;
  const { default: DevPreview } = await import('./DevPreview.svelte');
  mount(DevPreview, { target: document.body });
  if (gate.deferred) {
    gate.deferred = false;
    if (readVolume()) {
      await invalidateAll();
      window.dispatchEvent(new CustomEvent('pano-market-mock-changed'));
    }
  }
}

/**
 * Resolves once the server-rendered page has been hydrated. Hydration is a synchronous call inside
 * the host's page component, started after the document's module scripts ran: so `load`, then the
 * Svelte flush (`tick`) and two animation frames mean no hydration is in flight any more.
 */
async function hydrated() {
  if (document.readyState !== 'complete') {
    await new Promise((resolve) => window.addEventListener('load', resolve, { once: true }));
  }
  await tick();
  const frame = () => new Promise((resolve) => requestAnimationFrame(() => resolve()));
  await frame();
  await frame();
  await tick();
}
