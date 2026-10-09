import { hostHas, initProfileNav, registerDropdown } from './lib/profileNav.js';

// Development-only preview mode (fake data); the module checks the platform mode itself.
import { startDevPreview } from '../mock/start.js';

const pluginId = 'pano-plugin-market';

// Registers what the view metadata cannot say. The pages, the navbar cart, the cart offcanvas and the sidebar widgets
// come from `export const view` (the build calls pano.views.add before onLoad); what stays here depends on the host:
// item 6 (nav link) always runs, items 9-10 are each wrapped so a theme that lacks a namespace only loses that item
// (a console.warn), never the whole plugin.

function optional(name, fn) {
  try {
    fn();
  } catch (e) {
    console.warn(`pano-plugin-market: registration "${name}" skipped`, e);
  }
}

export function registerTheme(pano) {
  startDevPreview(pano);

  // 1-5. The store pages, the navbar cart (market:NavCart), the cart offcanvas (market:CartOffcanvas) and the
  // four sidebar widgets are registered by the build from `export const view` in each view file (doc 01 section 2).

  // 6. Navigation link in the theme
  pano.ui.nav.site.editNavLinks((navigationItems) => {
    navigationItems.push({
      href: '/store',
      text: `plugins.${pluginId}.nav-store`,
    });
    return navigationItems;
  });

  // 9. Purchases entry in the account dropdown (14 §5 row 9)
  optional('profile-dropdown', () => registerDropdown(pano));

  // 10. Profile navigation: four link items with profile-nav (hidden / badge follow me/summary), else the
  // profile block in the profile-content slot (14 §12.1)
  optional('profile-nav', () => {
    if (hostHas(pano, 'profile-nav')) {
      initProfileNav(pano);
      return;
    }

    pano.ui.profile.content.edit((items) => {
      items.push({
        id: 'market',
        priority: 50,
        view: 'market:MarketProfileBlock',
      });

      return items;
    });
  });
}

export { optional as registerOptional };
