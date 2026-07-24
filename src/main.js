import { PanoPlugin, viewComponent } from '@panomc/sdk';
import { pluginId } from './i18n';

export default class PanoMarketPlugin extends PanoPlugin {
  onLoad() {
    const pano = this.pano;

    if (pano.isPanel) {
      // Register Panel Admin Page
      pano.ui.page.register({
        path: '/market',
        component: viewComponent(() => import('./panel/pages/Stats.svelte')),
        permission: `pano.plugin.${pluginId}.manage.market`,
      });

      pano.ui.page.register({
        path: '/market/orders',
        component: viewComponent(() => import('./panel/pages/Orders.svelte')),
        permission: `pano.plugin.${pluginId}.manage.market`,
      });

      pano.ui.page.register({
        path: '/market/categories',
        component: viewComponent(() => import('./panel/pages/Categories.svelte')),
        permission: `pano.plugin.${pluginId}.manage.market`,
      });

      pano.ui.page.register({
        path: '/market/products',
        component: viewComponent(() => import('./panel/pages/Products.svelte')),
        permission: `pano.plugin.${pluginId}.manage.market`,
      });

      pano.ui.page.register({
        path: '/market/products/create-product',
        component: viewComponent(() => import('./panel/pages/CreateProduct.svelte')),
        permission: `pano.plugin.${pluginId}.manage.market`,
      });

      pano.ui.page.register({
        path: '/market/comparisons',
        component: viewComponent(() => import('./panel/pages/Comparisons.svelte')),
        permission: `pano.plugin.${pluginId}.manage.market`,
      });

      pano.ui.page.register({
        path: '/market/comparisons/create-comparison',
        component: viewComponent(() => import('./panel/pages/CreateComparison.svelte')),
        permission: `pano.plugin.${pluginId}.manage.market`,
      });
      
      pano.ui.page.register({
        path: '/market/gifts',
        component: viewComponent(() => import('./panel/pages/Gifts.svelte')),
        permission: `pano.plugin.${pluginId}.manage.market`,
      });

      pano.ui.page.register({
        path: '/market/discounts',
        component: viewComponent(() => import('./panel/pages/Discounts.svelte')),
        permission: `pano.plugin.${pluginId}.manage.market`,
      });

      pano.ui.page.register({
        path: '/market/settings',
        component: viewComponent(() => import('./panel/pages/Settings.svelte')),
        permission: `pano.plugin.${pluginId}.manage.market`,
      });

      // Add Sidebar Link in Panel
      pano.ui.nav.site.editNavLinks((navigationItems) => {
        const marketNav = {
          href: '/market',
          icon: 'fas fa-store',
          text: `plugins.${pluginId}.nav-market`,
          permission: `pano.plugin.${pluginId}.manage.market`,
        };

        // Try to place it after statistics/dashboard if it exists
        const statsIndex = navigationItems.findLastIndex(
          (item) => item.href === '/statistics' || item.href === '/'
        );

        if (statsIndex !== -1) {
          navigationItems.splice(statsIndex + 1, 0, marketNav);
        } else {
          navigationItems.push(marketNav);
        }

        return navigationItems;
      });
    } else {
      // Register Theme Public Page
      pano.ui.page.register({
        path: '/store',
        component: viewComponent(() => import('./theme/pages/StorePage.svelte')),
      });

      // Add Navigation Link in Theme
      pano.ui.nav.site.editNavLinks((navigationItems) => {
        navigationItems.push({
          href: '/store',
          text: `plugins.${pluginId}.nav-store`,
        });
        return navigationItems;
      });
    }
  }

  onContextUpdate(ctx) { }

  onUnload() { }
}
