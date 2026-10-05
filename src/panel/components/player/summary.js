// SDK-free logic of the player-page market tab / card (13 section 24). The host calls are injected
// (like list-core.js), so the data loading and the view model are unit tested.
import { marketPath } from '../../utils/api.js';
import { guard } from '../../utils/guard.js';
import { can } from '../../utils/permissions.js';
import { PLUGIN_ID } from '../../utils/plugin.js';

export const PLAYER_KEYS = ['OV', 'PAY'];

/** GET /players/:username/summary path; the username is encoded (it comes from a route param). */
export const summaryPath = (username) =>
  marketPath(`/players/${encodeURIComponent(username)}/summary`);

/** `/market/orders?search=<username>` (the "All Orders" card action; needs OV). */
export const ordersLink = (username) => `/market/orders?search=${encodeURIComponent(username)}`;

/** `/market/orders/create-order?player=<username>` (needs PAY). */
export const createOrderLink = (username) =>
  `/market/orders/create-order?player=${encodeURIComponent(username)}`;

/** The block list filtered to the player (the modal itself takes no prefill, see MPU-19 evidence). */
export const blocksLink = (username) => `/market/blocks?search=${encodeURIComponent(username)}`;

const failed = (body) => !body || typeof body !== 'object' || Boolean(body.error);
const errorOf = (body) => (body && typeof body === 'object' && body.error) || 'NETWORK_ERROR';

/**
 * Shared load of PlayerMarket.svelte and of PlayerMarketCard.svelte (hook load).
 * `deps` = { get, loadContext }; `event.params.username` is the player. Never throws: every
 * failure becomes `{ data: { error } }`, which the card variant renders as nothing.
 */
export async function loadPlayerSummary(deps, event, { title = null } = {}) {
  const username = event?.params?.username ?? '';
  const empty = (error) => ({ data: { username, summary: null, ctx: null, error } });
  try {
    const allowed = await guard(event, PLAYER_KEYS);
    if (allowed.denied) return empty('NO_PERMISSION');
    if (title) allowed.pageTitle?.set?.(`plugins.${PLUGIN_ID}.${title}`);
    if (!username) return empty('NOT_FOUND');
    const [body, ctx] = await Promise.all([
      deps.get({ path: summaryPath(username), request: event }),
      deps.loadContext(event),
    ]);
    if (failed(body)) return empty(errorOf(body));
    return { data: { username, summary: body, ctx: ctx ?? null, error: null } };
  } catch {
    return empty('NETWORK_ERROR');
  }
}

/**
 * View model of the panel. `fmt` = { money, credits } (injected), `user` = the layout user.
 * Returns `{ error }` alone when there is no summary, so a caller (and the hook card) can decide to
 * render nothing; a malformed body never throws.
 */
export function buildSummaryView({ summary, username, ctx, user, error, fmt }) {
  if (error || !summary || typeof summary !== 'object') {
    return { error: error || 'NETWORK_ERROR', hasContent: false };
  }
  const totals = summary.totals && typeof summary.totals === 'object' ? summary.totals : {};
  const registered = summary.user != null && typeof summary.user === 'object';
  const currency = totals.currency ?? ctx?.currency ?? '';
  const money = (v) => fmt.money(v ?? 0, currency);
  const list = (v) => (Array.isArray(v) ? v : []);

  const stats = [
    { key: 'orders', cls: 'text-bg-primary', value: String(totals.orders ?? 0) },
    { key: 'spent', cls: 'text-bg-success', value: money(totals.spent) },
    { key: 'refunded', cls: 'text-bg-warning', value: money(totals.refunded) },
    {
      key: 'credits',
      cls: 'text-bg-info',
      value: registered ? fmt.credits(summary.creditBalance ?? 0, ctx?.creditName ?? '') : '—',
    },
  ];

  const name = username || summary.user?.username || '';
  return {
    error: null,
    hasContent: true,
    registered,
    stats,
    orders: list(summary.orders),
    entitlements: list(summary.entitlements),
    subscriptions: list(summary.subscriptions),
    blocks: list(summary.blocks),
    actions: {
      allOrders: can(user, 'OV') && name !== '',
      grant: can(user, 'PAY') && registered,
      revoke: can(user, 'PAY') && registered,
      createOrder: can(user, 'PAY') && name !== '',
      block: can(user, 'OM') && name !== '',
    },
    account: registered
      ? {
          userId: summary.user.id,
          username: summary.user.username ?? name,
          balance: summary.creditBalance ?? 0,
        }
      : null,
  };
}

/** The hook card renders nothing while there is no usable summary (it must never break the host page). */
export const cardVisible = (view) => Boolean(view?.hasContent);
