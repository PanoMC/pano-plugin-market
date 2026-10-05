import { viewComponent } from '@panomc/sdk';
import { pluginId } from '../i18n';
import { has, setPano } from './utils/host.js';
import { initProfileNav, registerDropdown } from './stores/profileNav.js';
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

  // 3. Order page; three segments, so it never competes with the two-segment /store/[slug]
  pano.ui.page.register({
    path: '/store/order/[id]',
    component: viewComponent(() => import('./pages/OrderPage.svelte')),
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

  // 5. Profile pages (the login guard comes from ProfileLayout; the loads add the return URL)
  pano.ui.page.register({
    path: '/profile/purchases',
    component: viewComponent(() => import('./pages/profile/PurchasesPage.svelte')),
    systemLayout: 'ProfileLayout',
  });

  pano.ui.page.register({
    path: '/profile/credits',
    component: viewComponent(() => import('./pages/profile/CreditsPage.svelte')),
    systemLayout: 'ProfileLayout',
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

  // 9. Purchases entry in the account dropdown (14 §5 row 9)
  optional('profile-dropdown', () => registerDropdown(pano));

  // 10. Profile navigation: four link items with profile-nav (hidden / badge follow me/summary), else the
  // profile block in the profile-content slot (14 §12.1)
  optional('profile-nav', () => {
    if (has('profile-nav')) {
      initProfileNav(pano);
      return;
    }

    pano.ui.profile.content.edit((items) => {
      items.push({
        id: 'market',
        priority: 50,
        component: viewComponent(() => import('./components/profile/MarketProfileBlock.svelte')),
      });

      return items;
    });
  });

  // Item 11 (sidebar widgets) is added by the slice that creates its components, through optional(name, fn).
}

export { optional as registerOptional };
