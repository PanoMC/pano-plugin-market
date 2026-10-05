import { viewComponent } from '@panomc/sdk';
import { pluginId } from '../i18n';
import { setPano } from './utils/host.js';
// side effect: the checkout draft clears itself on logout from any page (14 §10.2)
import './stores/checkoutDraft.js';

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

  // 4. Product page; the pattern loses against the exact routes (/store/checkout)
  pano.ui.page.register({
    path: '/store/[slug]',
    component: viewComponent(() => import('./pages/ProductPage.svelte')),
  });

  // 5. Checkout; registered as an exact path, it wins over the /store/[slug] pattern
  pano.ui.page.register({
    path: '/store/checkout',
    component: viewComponent(() => import('./pages/CheckoutPage.svelte')),
  });

  // 6. Navigation link in the theme
  pano.ui.nav.site.editNavLinks((navigationItems) => {
    navigationItems.push({
      href: '/store',
      text: `plugins.${pluginId}.nav-store`,
    });
    return navigationItems;
  });

  // 7. Cart button in the navbar (14 §7.3)
  optional('nav-cart', () => {
    pano.ui.nav.rightComponents.edit((components) => {
      components.push({
        id: 'market-cart',
        priority: 50,
        component: viewComponent(() => import('./components/cart/NavCart.svelte')),
      });
      return components;
    });
  });

  // 8. Cart offcanvas outside the navbar DOM (14 §7.1)
  optional('cart-offcanvas', () => {
    pano.ui.hook.register({
      name: 'theme:top',
      component: viewComponent(() => import('./components/cart/CartOffcanvas.svelte')),
      skipLoad: true,
    });
  });

  // Items 9-11 (profile dropdown / profile navigation, sidebar widgets) are added by the slices that
  // create their components, each through optional(name, fn).
}

export { optional as registerOptional };
