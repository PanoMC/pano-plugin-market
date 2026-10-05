import { viewComponent } from '@panomc/sdk';
import { ANY_NODE, NODE, perm } from './utils/permissions.js';
import { guardedLoad } from './utils/guard.js';
import { PLUGIN_ID } from './utils/plugin.js';
import { setPano } from './utils/runtime.js';

const ALL_KEYS = Object.keys(NODE);

// 13 §2.3. `nodes` = keys of which any one opens the page (the umbrella node always does).
// Component paths are under ./pages/. Static-vs-param collisions are avoided by construction
// (detail/[id], account/[userId], creator/[id]).
export const PAGES = [
  ['/market', () => import('./pages/Overview.svelte'), ALL_KEYS],
  ['/market/orders', () => import('./pages/Orders.svelte'), ['OV']],
  ['/market/orders/detail/[id]', () => import('./pages/OrderDetail.svelte'), ['OV']],
  ['/market/orders/create-order', () => import('./pages/CreateOrder.svelte'), ['PAY']],
  ['/market/deliveries', () => import('./pages/Deliveries.svelte'), ['OV']],
  ['/market/shipments', () => import('./pages/Shipments.svelte'), ['OV']],
  ['/market/subscriptions', () => import('./pages/Subscriptions.svelte'), ['OV']],
  ['/market/subscriptions/detail/[id]', () => import('./pages/SubscriptionDetail.svelte'), ['OV']],
  ['/market/payment-events', () => import('./pages/PaymentEvents.svelte'), ['OV']],
  ['/market/products', () => import('./pages/Products.svelte'), ['CAT']],
  ['/market/products/create-product', () => import('./pages/CreateProduct.svelte'), ['CAT']],
  ['/market/categories', () => import('./pages/Categories.svelte'), ['CAT']],
  ['/market/comparisons', () => import('./pages/Comparisons.svelte'), ['CAT']],
  [
    '/market/comparisons/create-comparison',
    () => import('./pages/CreateComparison.svelte'),
    ['CAT'],
  ],
  ['/market/goals', () => import('./pages/Goals.svelte'), ['CAT']],
  ['/market/discounts', () => import('./pages/Discounts.svelte'), ['DISC']],
  ['/market/discounts/creator/[id]', () => import('./pages/CreatorDetail.svelte'), ['DISC']],
  ['/market/gifts', () => import('./pages/Gifts.svelte'), ['DISC']],
  ['/market/credits', () => import('./pages/Credits.svelte'), ['PAY']],
  ['/market/credits/account/[userId]', () => import('./pages/CreditAccount.svelte'), ['PAY']],
  ['/market/blocks', () => import('./pages/Blocks.svelte'), ['OM']],
  ['/market/settings', () => import('./pages/Settings.svelte'), ['SET']],
  ['/market/settings/shipping-method', () => import('./pages/ShippingMethodForm.svelte'), ['SET']],
];

const nodesFor = (keys) => (keys === ALL_KEYS ? ANY_NODE : perm(...keys));

// Every page load starts with the can() guard (13 §2.1): the page gets { error: 'NO_PERMISSION' }
// instead of data-less chrome, whatever its own load does.
export function guarded(importer, keys) {
  return async () => {
    const module = await importer();
    return { ...module, load: guardedLoad(module.load, keys) };
  };
}

function register(pano, anyOf, { path, importer, keys, ...extra }) {
  const registration = {
    path,
    component: viewComponent(guarded(importer, keys)),
    ...extra,
  };
  // Without the host feature `permission-any-of` a registration takes one node only; an any-of list
  // would be refused and the umbrella-role holders would get 404 on every split page. The page
  // guard above enforces the nodes instead.
  if (anyOf) registration.permission = nodesFor(keys);
  pano.ui.page.register(registration);
}

export function registerPanel(pano) {
  setPano(pano);
  const anyOf = pano.features?.has?.('permission-any-of') === true;

  for (const [path, importer, keys] of PAGES) register(pano, anyOf, { path, importer, keys });

  // 13 §24 market tab on the player page (X-9), else a card on the player overview.
  const playerKeys = ['OV', 'PAY'];
  if (pano.ui.player?.detail?.editMenu) {
    register(pano, anyOf, {
      path: '/players/detail/[username]/market',
      importer: () => import('./pages/PlayerMarket.svelte'),
      keys: playerKeys,
      systemLayout: 'PlayerDetailLayout',
      resetLayout: false,
    });
    pano.ui.player.detail.editMenu((items) => [
      ...items,
      {
        id: 'market',
        href: '/market',
        text: `plugins.${PLUGIN_ID}.nav-market`,
        startsWith: true,
        ...(anyOf ? { permission: perm(...playerKeys) } : {}),
      },
    ]);
  } else {
    const cardImporter = () => import('./components/player/PlayerMarketCard.svelte');
    pano.ui.hook.register({
      name: 'panel:player-detail:bottom',
      component: viewComponent(cardImporter),
      ...(anyOf ? { permission: perm(...playerKeys) } : {}),
    });
  }

  // Add Sidebar Link in Panel
  pano.ui.nav.site.editNavLinks((navigationItems) => {
    const marketNav = {
      href: '/market',
      icon: 'fas fa-store',
      text: `plugins.${PLUGIN_ID}.nav-market`,
      ...(anyOf ? { permission: ANY_NODE } : {}),
    };

    // Try to place it after statistics/dashboard if it exists
    const statsIndex = navigationItems.findLastIndex(
      (item) => item.href === '/statistics' || item.href === '/',
    );

    if (statsIndex !== -1) {
      navigationItems.splice(statsIndex + 1, 0, marketNav);
    } else {
      navigationItems.push(marketNav);
    }

    return navigationItems;
  });
}
