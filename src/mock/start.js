// Tiny always-loaded entry: the browser imports the preview boot lazily, the server never does.
// side = 'panel' | 'theme': where the drawer is mounted, so its links to the other side leave this app.
export function startDevPreview(pano, side) {
  if (typeof document === 'undefined') return;
  import('./boot.js').then((m) => m.startDevPreview(pano, side)).catch(() => {});
}
