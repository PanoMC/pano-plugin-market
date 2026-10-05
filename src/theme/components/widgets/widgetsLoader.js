// Widget data loading and sidebar registration (14 §13.2).
import { apiGet } from '../../utils/api.js';
import { has } from '../../utils/host.js';
import { SIDEBAR_IDS } from './widgetsModel.js';

export const WIDGETS_PATH = '/api/market/widgets';
export const INCLUDE = 'recentBuyers,topSupporters,goals,stats';

// The four sidebar widgets of one page load receive the same event object (F7): one request per event.
const pending = new WeakMap();

function strip(result) {
  if (!result || result.ok === false) return {};
  const { ok, result: status, ...rest } = result;

  return rest;
}

/** loadWidgets(event) -> Promise<widgets payload without envelope, {} on failure>. Never rejects. */
export function loadWidgets(event, fetcher = apiGet) {
  const key = event && typeof event === 'object' ? event : null;
  if (key && pending.has(key)) return pending.get(key);

  const promise = Promise.resolve(fetcher(WIDGETS_PATH, { query: { include: INCLUDE }, event }))
    .then(strip)
    .catch(() => ({}));
  if (key) pending.set(key, promise);

  return promise;
}

/** Thunk resolving to the component module plus a load() that learns which sidebar it sits in (F7, F8). */
export function sidebarWidget(loader, sidebarId) {
  return async () => ({
    ...(await loader()),
    load: async (event) => ({ ...(await loadWidgets(event)), sidebarId }),
  });
}

export const SIDEBAR_WIDGETS = [
  { id: 'market-goals', priority: 70, loader: () => import('./GoalWidget.svelte') },
  {
    id: 'market-top-supporters',
    priority: 60,
    loader: () => import('./TopSupportersWidget.svelte'),
  },
  { id: 'market-recent-buyers', priority: 50, loader: () => import('./RecentBuyersWidget.svelte') },
  { id: 'market-stats', priority: 40, loader: () => import('./StatsWidget.svelte') },
];

/** Registration item 11: only with page-sidebar-id; each widget in `home` and `profile`. */
export function registerSidebarWidgets(pano) {
  if (!has('page-sidebar-id')) return 0;
  let count = 0;

  for (const sidebarId of SIDEBAR_IDS) {
    for (const widget of SIDEBAR_WIDGETS) {
      pano.ui.sidebar.register({
        sidebarId,
        id: widget.id,
        component: sidebarWidget(widget.loader, sidebarId),
        priority: widget.priority,
      });
      count++;
    }
  }

  return count;
}
