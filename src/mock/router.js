// Loaded lazily (dynamic import in seam.js): only evaluated when the preview mode is active.
import { createRouter } from './core.js';
import { routes as listRoutes, pages as listPages } from './fixtures/panel-lists.js';
import { routes as detailRoutes, pages as detailPages } from './fixtures/panel-details.js';
import { routes as storeRoutes, pages as storePages } from './fixtures/storefront.js';

export const router = createRouter([...listRoutes, ...detailRoutes, ...storeRoutes]);

/** What the drawer lists: { side: 'panel' | 'theme', label, href } (href relative to the app base). */
export const PAGES = [...listPages, ...detailPages, ...storePages];
