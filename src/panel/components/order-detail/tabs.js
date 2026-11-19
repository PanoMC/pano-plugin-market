// Tabs of the order detail page and the "needs attention" list above them. Pure: no Svelte, no SDK.

/** Tab keys in nav order. `overview` = items + totals. */
export const TABS = ['overview', 'payments', 'fulfillment', 'history'];

/** Anchors other pages link to, and the tab that holds the card (orders list "Re-run Delivery"). */
export const ANCHOR_TABS = { deliveries: 'fulfillment' };

const len = (list) => (Array.isArray(list) ? list.length : 0);
const count = (list, test) => (Array.isArray(list) ? list.filter(test).length : 0);
const num = (value) => (Number.isFinite(Number(value)) ? Number(value) : 0);

/** `?tab=` value, else the tab of a known `#anchor`, else overview. */
export function initialTab(search, hash) {
  const fromAnchor = ANCHOR_TABS[String(hash ?? '').replace(/^#/, '')];
  if (fromAnchor) return fromAnchor;
  const wanted = new URLSearchParams(search ?? '').get('tab');
  return TABS.includes(wanted) ? wanted : 'overview';
}

/** The location with `?tab=` set (dropped for overview); hash and other params are kept. */
export function tabHref(href, tab) {
  const url = new URL(href);
  if (tab === 'overview') url.searchParams.delete('tab');
  else url.searchParams.set('tab', tab);
  url.hash = '';
  return `${url.pathname}${url.search}`;
}

/** Rows each tab holds (its badge); overview shows none. `shipping` = the Shipments card is on. */
export function tabCounts(detail, { shipping = false } = {}) {
  return {
    overview: null,
    payments: len(detail?.payments) + len(detail?.refunds) + len(detail?.disputes),
    fulfillment:
      len(detail?.deliveries) +
      (shipping && detail?.order?.requiresShipping === true ? len(detail?.shipments) : 0),
    history: len(detail?.mails) + len(detail?.events),
  };
}

const SHIPMENT_PROBLEMS = ['EXCEPTION', 'LOST'];
const OPEN_DISPUTES = ['OPEN', 'INQUIRY'];

/**
 * What needs an admin on this order, in display order: `{ key, tab, count, values, danger }`.
 * Empty when nothing does. The keys are `pages.order-detail.attention.items.<key>`.
 */
export function attentionItems(detail, { shipping = false } = {}) {
  if (!detail) return [];
  const items = [];
  const push = (key, tab, n, danger, values = { count: n }) => {
    if (n > 0) items.push({ key, tab, count: n, values, danger });
  };

  const pending = num(detail.revokePending);
  const failed = num(detail.revokeFailed);
  // the revoke alert this replaces was always red
  push('revoke', 'fulfillment', pending + failed, true, { pending, failed });
  push(
    'delivery-failed',
    'fulfillment',
    count(detail.deliveries, (d) => d.status === 'FAILED'),
    true,
  );
  if (shipping && detail.order?.requiresShipping === true)
    push(
      'shipment-problem',
      'fulfillment',
      count(detail.shipments, (s) => SHIPMENT_PROBLEMS.includes(s.status)),
      true,
    );
  push(
    'dispute-open',
    'payments',
    count(detail.disputes, (d) => OPEN_DISPUTES.includes(d.status)),
    true,
  );
  push(
    'refund-failed',
    'payments',
    count(detail.refunds, (r) => r.status === 'FAILED'),
    true,
  );
  push(
    'payment-review',
    'payments',
    count(detail.payments, (p) => p.status === 'REVIEW'),
    false,
  );
  push(
    'mail-failed',
    'history',
    count(detail.mails, (m) => m.status === 'FAILED'),
    false,
  );
  return items;
}

/** Tabs that hold at least one attention item (their pill gets a marker). */
export function tabsNeedingAttention(items) {
  return new Set((items ?? []).map((item) => item.tab));
}

/** The latest payment attempt (highest id), or null. */
export function latestPayment(payments) {
  if (!Array.isArray(payments) || payments.length === 0) return null;
  return payments.reduce((best, p) => (num(p?.id) > num(best?.id) ? p : best), payments[0]);
}
