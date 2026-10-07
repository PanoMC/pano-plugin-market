// Tiny always-loaded entry: the browser imports the preview boot lazily, the server never does.
import { gate } from './core.js';

export function startDevPreview(pano) {
  if (typeof document === 'undefined') return;
  // Themes are server rendered with real data; the preview takes over once the page is hydrated.
  if (!pano.isPanel) gate.deferred = true;
  import('./boot.js').then((m) => m.startDevPreview(pano)).catch(() => {});
}
