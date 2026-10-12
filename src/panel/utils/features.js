// Feature switches of the build (MarketFeature on the backend), read once from GET /context
// (`features`, `featurePreview`). A switched-off feature is hidden; while the platform runs in
// development mode it is shown as passive instead (there, but not usable). Pure apart from the
// module state; a context that names no switch leaves every feature on.

/** @type {{ features: Record<string, boolean>, preview: boolean, loaded: boolean }} */
let state = { features: {}, preview: false, loaded: false };

/** @type {Promise<void> | null} */
let pending = null;

/** Takes the switches of a GET /context answer; anything else is ignored. */
export function setFeatures(ctx) {
  if (!ctx || typeof ctx !== 'object') return;
  const features = ctx.features && typeof ctx.features === 'object' ? ctx.features : {};
  state = { features: { ...features }, preview: ctx.featurePreview === true, loaded: true };
}

/** Back to "nothing known" (every feature on); for tests. */
export function resetFeatures() {
  state = { features: {}, preview: false, loaded: false };
  pending = null;
}

/** 'on', 'off' (hidden) or 'passive' (off, shown as disabled in development mode). No key = 'on'. */
export function featureState(key) {
  if (!key || state.features[key] !== false) return 'on';
  return state.preview ? 'passive' : 'off';
}

export const featureOn = (key) => featureState(key) === 'on';

/**
 * Reads the switches once; `loadContext` answers GET /context (or null). A failed read leaves every
 * feature on and is tried again on the next call: the backend refuses a switched-off route anyway.
 */
export function ensureFeatures(loadContext) {
  if (state.loaded) return Promise.resolve();
  pending ??= Promise.resolve()
    .then(loadContext)
    .then(setFeatures)
    .catch(() => {})
    .finally(() => {
      pending = null;
    });
  return pending;
}
