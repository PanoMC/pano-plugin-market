// Loaded lazily (dynamic import in seam.js): only evaluated when the preview mode is active.
import { createRouter } from './core.js';
import { routes as listRoutes, pages as listPages } from './fixtures/panel-lists.js';
import { routes as detailRoutes, pages as detailPages } from './fixtures/panel-details.js';
import { routes as storeRoutes, pages as storePages } from './fixtures/storefront.js';
import { routes as checkoutRoutes, pages as checkoutPages } from './fixtures/checkout.js';

// The checkout routes come before the storefront ones: their order GETs answer the preview orders first (first match wins).
export const router = createRouter([
  ...listRoutes,
  ...detailRoutes,
  ...checkoutRoutes,
  ...storeRoutes,
]);

/** What the drawer lists: { side: 'panel' | 'theme', label, href } (href relative to the app base). */
export const PAGES = [...listPages, ...detailPages, ...storePages, ...checkoutPages];
