// Tiny always-loaded entry: the browser imports the preview boot lazily, the server never does.
export function startDevPreview(pano) {
  if (typeof document === 'undefined') return;
  import('./boot.js').then((m) => m.startDevPreview(pano)).catch(() => {});
}
