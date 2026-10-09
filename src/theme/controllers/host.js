// `market/host`: feature detection and login / register links over the host. No state.
import { defineController } from '@panomc/plugin-kit/controller';

export default defineController({
  name: 'host',
  version: 1,
  actions: ({ host }) => ({
    /** True when the host announces `feature` (pano.features.has). */
    has: (feature) => host.feature(feature) === true,
    loginUrl: (returnTo) => host.loginUrl(returnTo),
    registerUrl: (returnTo) => host.registerUrl(returnTo),
  }),
});
