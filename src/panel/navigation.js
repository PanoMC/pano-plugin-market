// Areas and sections of the market panel (13 §2.2). Pure: no Svelte, no SDK import.
import { NODE, can } from './utils/permissions.js';

const ALL_KEYS = Object.keys(NODE);

/** Level 1. `nodes` = keys of which any one makes the area visible. */
export const AREAS = [
  { key: 'overview', label: 'nav-overview', nodes: ALL_KEYS },
  { key: 'orders', label: 'nav-orders', nodes: ['OV'] },
  { key: 'catalog', label: 'nav-catalog', nodes: ['CAT'] },
  { key: 'discounts', label: 'nav-discounts', nodes: ['DISC'] },
  { key: 'customers', label: 'nav-customers', nodes: ['PAY', 'OM'] },
  { key: 'settings', label: 'nav-settings', nodes: ['SET'] },
];

const section = (area, key, href, nodes) => ({
  area,
  key,
  href,
  label: `nav-section-${key}`,
  nodes,
});

/** Level 2, declared once: { area, key, href, label, nodes } in nav order. */
export const SECTIONS = [
  section('orders', 'orders', '/market/orders', ['OV']),
  section('orders', 'deliveries', '/market/deliveries', ['OV']),
  section('orders', 'shipments', '/market/shipments', ['OV']),
  section('orders', 'subscriptions', '/market/subscriptions', ['OV']),
  section('orders', 'payment-events', '/market/payment-events', ['OV']),
  section('catalog', 'products', '/market/products', ['CAT']),
  section('catalog', 'categories', '/market/categories', ['CAT']),
  section('catalog', 'comparisons', '/market/comparisons', ['CAT']),
  section('catalog', 'goals', '/market/goals', ['CAT']),
  section('discounts', 'general', '/market/discounts?section=general', ['DISC']),
  section('discounts', 'coupons', '/market/discounts?section=coupons', ['DISC']),
  section('discounts', 'creators', '/market/discounts?section=creators', ['DISC']),
  section('discounts', 'payouts', '/market/discounts?section=payouts', ['DISC', 'PAY']),
  section('discounts', 'gifts', '/market/gifts', ['DISC']),
  section('customers', 'credits', '/market/credits', ['PAY']),
  section('customers', 'blocks', '/market/blocks', ['OM']),
  ...[
    'general',
    'checkout',
    'currencies',
    'billing',
    'legal',
    'payments',
    'credits',
    'delivery',
    'shipping-methods',
    'shipping-zones',
    'shipping-carriers',
    'webhooks',
    'webhook-deliveries',
    'modules',
    'security',
    'mail',
    'minecraft',
    'health',
  ].map((key) => section('settings', key, `/market/settings?section=${key}`, ['SET'])),
];

/** Sections of an area the user may open. */
export function sectionsFor(area, user) {
  return SECTIONS.filter((s) => s.area === area && can(user, ...s.nodes));
}

/** First href of an area for this user (the first section that passes can()); null when hidden. */
export function firstHref(area, user) {
  const def = AREAS.find((a) => a.key === area);
  if (!def || !can(user, ...def.nodes)) return null;
  if (area === 'overview') return '/market';
  return sectionsFor(area, user)[0]?.href ?? null;
}

/** Level-1 items visible to the user, each with its per-user `href`. */
export function visibleAreas(user) {
  return AREAS.filter((a) => can(user, ...a.nodes)).flatMap((a) => {
    const href = firstHref(a.key, user);
    return href ? [{ ...a, href }] : [];
  });
}

/**
 * Active section key for the current location. `pathname` has the router base removed.
 * A section matches its path (or a sub-path) and, when its href carries ?section=, that value
 * (default = the first section on the same path).
 */
export function activeSection(sections, pathname, searchParams) {
  const pathOf = (s) => s.href.split('?')[0];
  const param = (s) => new URL(s.href, 'http://x').searchParams.get('section');
  let best = null;
  for (const s of sections) {
    const path = pathOf(s);
    if (pathname !== path && !pathname.startsWith(path + '/')) continue;
    const wanted = param(s);
    if (wanted !== null) {
      const first = sections.find((o) => pathOf(o) === path);
      const current = searchParams?.get?.('section') ?? param(first);
      if (current !== wanted) continue;
    }
    if (!best || path.length > pathOf(best).length) best = s;
  }
  return best?.key ?? null;
}
