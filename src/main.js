import { PanoPlugin, viewComponent } from '@panomc/sdk';
import { derived } from 'svelte/store';
import { _ as i18n } from '@panomc/sdk/utils/language';

const pluginId = 'pano-plugin-market';

// this is to render plugin translations
export const _ = derived(i18n, ($_fn) => {
  return (key, options) => $_fn(`plugins.${pluginId}.${key}`, options);
});

export default class PanoMarketPlugin extends PanoPlugin {
  onLoad() {
    const pano = this.pano;

    if (pano.isPanel) {
      // Register Panel Admin Page
      pano.ui.page.register({
        path: '/market',
        component: viewComponent(() => import('./panel/pages/MarketAdminPage.svelte')),
        permission: `pano.plugin.${pluginId}.manage`,
      });

      pano.ui.page.register({
        path: '/market/categories',
        component: viewComponent(() => import('./panel/pages/MarketCategoriesPage.svelte')),
        permission: `pano.plugin.${pluginId}.manage`,
      });

      pano.ui.page.register({
        path: '/market/products',
        component: viewComponent(() => import('./panel/pages/MarketProductsPage.svelte')),
        permission: `pano.plugin.${pluginId}.manage`,
      });

      pano.ui.page.register({
        path: '/market/comparisons',
        component: viewComponent(() => import('./panel/pages/MarketComparisonsPage.svelte')),
        permission: `pano.plugin.${pluginId}.manage`,
      });

      pano.ui.page.register({
        path: '/market/settings',
        component: viewComponent(() => import('./panel/pages/MarketSettingsPage.svelte')),
        permission: `pano.plugin.${pluginId}.manage`,
      });

      // Add Sidebar Link in Panel
      pano.ui.nav.site.editNavLinks((navigationItems) => {
        const marketNav = {
          href: '/market',
          icon: 'fas fa-store',
          text: `plugins.${pluginId}.nav-market`,
          permission: `pano.plugin.${pluginId}.manage`,
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
