// The attention alerts of 13 §4.2 as plain data, in display order. Pure: the component renders the
// result, the unit test covers every row of the table.
export const REVOKE_PENDING_GRACE_MS = 10 * 60 * 1000;
export const MAX_MISSING_SHOWN = 10;

const BAD_PROVIDER_STATES = ['UNAVAILABLE', 'INCOMPATIBLE'];

/** Only http(s) URLs may become a link target (the value comes from another system). */
export function safeUrl(value) {
  if (typeof value !== 'string') return null;
  try {
    const url = new URL(value);
    return url.protocol === 'https:' || url.protocol === 'http:' ? url.href : null;
  } catch {
    return null;
  }
}

/** Server rows that need a banner entry: component missing / outdated, or offline with a backlog. */
export function mcPluginItems(servers) {
  const items = [];
  for (const server of Array.isArray(servers) ? servers : []) {
    const count = Number(server.waitingDeliveries) || 0;
    const base = {
      id: server.id,
      name: server.name,
      count,
      downloadUrl: safeUrl(server.downloadUrl),
    };
    if (server.marketState === 'COMPONENT_MISSING') items.push({ ...base, kind: 'missing' });
    else if (server.marketState === 'VERSION_MISMATCH') {
      items.push({
        ...base,
        kind: 'mismatch',
        have: String(server.mcComponentVersion ?? ''),
        want: String(server.requiredVersion ?? ''),
      });
    } else if (server.marketState === 'OFFLINE' && count > 0)
      items.push({ ...base, kind: 'offline' });
  }
  return items;
}

function missingObjects(schema) {
  const all = [...(schema?.missing ?? []), ...(schema?.unfixed ?? [])].map(String);
  return {
    shown: all.slice(0, MAX_MISSING_SHOWN),
    more: Math.max(0, all.length - MAX_MISSING_SHOWN),
  };
}

function revokeOrders(orders, now) {
  return (Array.isArray(orders) ? orders : []).filter((o) => {
    if ((Number(o.revokeFailed) || 0) > 0) return true;
    if ((Number(o.revokePending) || 0) <= 0) return false;
    const since = Number(o.revokePendingSince ?? o.updatedAt ?? o.paidAt ?? o.createdAt);
    return Number.isFinite(since) && now - since > REVOKE_PENDING_GRACE_MS;
  });
}

/**
 * `health` = GET /health (SET holders only, else null), `servers` = GET /servers `servers[]`,
 * `ctx` = GET /context. `options`: `reviewCount` (orders in REVIEW), `orders` (recent order rows
 * that may carry revokeFailed / revokePending), `can(...keys)` (permission test; default allows),
 * `now`. Every item: `{ key, variant, icon, title, body?, ... , links[] }`; `title` / `body` are
 * locale keys below `alerts.`; link `href`s are panel paths without the host base.
 */
export function alertsFor(health, servers, ctx, options = {}) {
  const { reviewCount = 0, orders = [], can = () => true, now = Date.now() } = options;
  const alerts = [];
  const link = (nodes, href, label) => (can(...nodes) ? [{ href, label }] : []);
  const add = (key, variant, extra = {}) =>
    alerts.push({
      key,
      variant,
      icon:
        variant === 'danger'
          ? 'fa-circle-exclamation'
          : variant === 'info'
            ? 'fa-circle-info'
            : 'fa-triangle-exclamation',
      title: `${key}.title`,
      body: `${key}.body`,
      links: [],
      ...extra,
    });

  if (health?.schema?.ok === false) {
    const { shown, more } = missingObjects({ missing: health.schema.missing });
    add('schema-degraded', 'danger', {
      missing: shown,
      more,
      links: link(['SET'], '/market/settings?section=health', 'open-health'),
    });
  }

  if (ctx?.testMode) {
    add('test-mode', 'warning', { links: link(['SET'], '/market/settings', 'open-settings') });
  }

  const mcItems = mcPluginItems(servers);
  if (mcItems.length > 0) {
    add('mc-plugin', 'warning', {
      items: mcItems,
      links: link(['OV'], '/market/deliveries?status=WAITING_SERVER', 'open-deliveries'),
    });
  }

  const mailTooOld = health?.mail === 'HOST_TOO_OLD';
  if (mailTooOld) add('mail-host-too-old', 'warning', { title: 'mail-disabled.title' });

  if (health && health.runtimeState !== undefined && health.runtimeState !== 'READY') {
    const { shown, more } = missingObjects(health.schema);
    add('store-unavailable', 'danger', {
      missing: shown,
      more,
      links: link(['SET'], '/market/settings?section=health', 'open-health'),
    });
  }

  for (const order of revokeOrders(orders, now)) {
    add(`revoke-pending-${order.id}`, 'danger', {
      title: 'revoke-pending.title',
      body: 'revoke-pending.body',
      orderId: order.id,
      links: link(['OV'], `/market/orders/detail/${order.id}`, 'open-order'),
    });
  }

  if (Number(reviewCount) > 0) {
    add('review', 'warning', {
      count: Number(reviewCount),
      links: link(['OV'], '/market/orders?status=REVIEW', 'open-orders'),
    });
  }

  const failed = Number(health?.queues?.deliveriesFailed) || 0;
  if (failed > 0) {
    add('failed-deliveries', 'danger', {
      count: failed,
      links: link(['OV'], '/market/deliveries?status=FAILED', 'open-deliveries'),
    });
  }

  const deferred = Number(health?.queues?.deferredEvents) || 0;
  if (deferred > 0) {
    add('deferred-events', 'warning', {
      count: deferred,
      links: link(['OV'], '/market/payment-events?status=DEFERRED', 'open-events'),
    });
  }

  const badProviders = (health?.providers ?? []).filter((p) =>
    BAD_PROVIDER_STATES.includes(p.state),
  );
  if (badProviders.length > 0) {
    add('provider-unavailable', 'warning', {
      providers: badProviders.map((p) => String(p.id)),
      links: link(['SET'], '/market/settings?section=payments', 'open-payments'),
    });
  }

  // One notice for a disabled mail system: the host-too-old warning above already says so.
  if (ctx?.mailEnabled === false && !mailTooOld) {
    add('mail-disabled', 'info');
  }

  return alerts;
}
