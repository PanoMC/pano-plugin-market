import { viewComponent } from '@panomc/sdk';
import { pluginId } from '../i18n';

export function registerTheme(pano) {
  // Register Theme Public Page
  pano.ui.page.register({
    path: '/store',
    component: viewComponent(() => import('./pages/StorePage.svelte')),
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
