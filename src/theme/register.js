import { viewComponent } from '@panomc/sdk';
import { pluginId } from '../i18n';
import { setPano } from './utils/host.js';

// Registers the storefront. Items 1-6 of 14 §5 always run; items 7-11 are each wrapped so a theme
// that lacks a namespace only loses that item (a console.warn), never the whole plugin.

function optional(name, fn) {
  try {
    fn();
  } catch (e) {
    console.warn(`pano-plugin-market: registration "${name}" skipped`, e);
  }
}

export function registerTheme(pano) {
  setPano(pano);

  // 1. Theme public pages
  pano.ui.page.register({
    path: '/store',
    component: viewComponent(() => import('./pages/StorePage.svelte')),
  });

  // 6. Navigation link in the theme
  pano.ui.nav.site.editNavLinks((navigationItems) => {
    navigationItems.push({
      href: '/store',
      text: `plugins.${pluginId}.nav-store`,
    });
    return navigationItems;
  });

  // Items 7-11 (cart button, cart offcanvas, profile dropdown / profile navigation, sidebar widgets)
  // are added by the slices that create their components, each through optional(name, fn).
}

export { optional as registerOptional };
