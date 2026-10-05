import { viewComponent } from '@panomc/sdk';
import { pluginId } from '../i18n';

const permission = `pano.plugin.${pluginId}.manage.market`;

const pages = [
  ['/market', () => import('./pages/Stats.svelte')],
  ['/market/orders', () => import('./pages/Orders.svelte')],
  ['/market/categories', () => import('./pages/Categories.svelte')],
  ['/market/products', () => import('./pages/Products.svelte')],
  ['/market/products/create-product', () => import('./pages/CreateProduct.svelte')],
  ['/market/comparisons', () => import('./pages/Comparisons.svelte')],
  ['/market/comparisons/create-comparison', () => import('./pages/CreateComparison.svelte')],
  ['/market/gifts', () => import('./pages/Gifts.svelte')],
  ['/market/discounts', () => import('./pages/Discounts.svelte')],
  ['/market/settings', () => import('./pages/Settings.svelte')],
];

export function registerPanel(pano) {
  for (const [path, load] of pages) {
    pano.ui.page.register({ path, component: viewComponent(load), permission });
  }

  // Add Sidebar Link in Panel
  pano.ui.nav.site.editNavLinks((navigationItems) => {
    const marketNav = {
      href: '/market',
      icon: 'fas fa-store',
      text: `plugins.${pluginId}.nav-market`,
      permission,
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
