// The host `pano` object, kept for components that talk to the host plugin API (PluginHook).
// Rollup puts this module into the shared chunk, so every lazy page sees the same instance.
let current = null;

export const setPano = (pano) => {
  current = pano;
};

export const getPano = () => current;
