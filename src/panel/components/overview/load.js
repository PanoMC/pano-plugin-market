// load() of Overview.svelte (13 §4). SDK-free: the host calls are injected so the gating and the
// per-block failure rules are unit tested. `deps` = { get, buildQueryParams }.
import { marketPath } from '../../utils/api.js';
import { loadContextWith } from '../../utils/list-core.js';
import { guard } from '../../utils/guard.js';
import { can, NODE } from '../../utils/permissions.js';
import { PLUGIN_ID } from '../../utils/plugin.js';
import { parseRange } from './range.js';

const ok = (body) => (body && typeof body === 'object' && !body.error ? body : null);

export async function loadOverviewWith(deps, event, now = Date.now()) {
  const { get, buildQueryParams } = deps;
  const allowed = await guard(event, Object.keys(NODE));
  if (allowed.denied) return allowed.denied;
  allowed.pageTitle?.set?.(`plugins.${PLUGIN_ID}.pages.overview.title`);

  const user = allowed.user;
  const canStats = can(user, 'STATS');
  const canOrders = can(user, 'OV');
  const canSettings = can(user, 'SET');

  const searchParams = event.url.searchParams;
  const view = searchParams.get('view') === 'chart' ? 'chart' : 'table';
  const { range, from, to } = parseRange(searchParams, now);

  const request = (path, params) =>
    get({ path: marketPath(path) + (params ? buildQueryParams(params) : ''), request: event });
  const skip = Promise.resolve(undefined);

  const [ctx, statsBody, ordersBody, serversBody, healthBody, reviewBody] = await Promise.all([
    loadContextWith(deps, event),
    canStats ? request('/stats', { from, to }) : skip,
    canOrders && view !== 'chart' ? request('/orders', { pageSize: 10 }) : skip,
    request('/servers'),
    canSettings ? request('/health') : skip,
    canOrders ? request('/orders', { status: 'REVIEW', pageSize: 1 }) : skip,
  ]);

  const stats = ok(statsBody);
  const orders = ok(ordersBody);
  const review = ok(reviewBody);
  return {
    data: {
      view,
      range,
      from,
      to,
      ctx,
      canStats,
      canOrders,
      stats,
      statsError: canStats && !stats ? (statsBody?.error ?? 'NETWORK_ERROR') : null,
      orders: orders && Array.isArray(orders.orders) ? orders.orders : [],
      ordersError:
        canOrders && view !== 'chart' && !orders ? (ordersBody?.error ?? 'NETWORK_ERROR') : null,
      servers: Array.isArray(ok(serversBody)?.servers) ? serversBody.servers : [],
      health: ok(healthBody),
      reviewCount: Number(review?.orderCount ?? review?.count ?? 0) || 0,
    },
  };
}
