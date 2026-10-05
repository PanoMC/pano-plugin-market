// Permission helper of the market panel (13 §2.1). `@panomc/sdk/utils/auth` throws in the panel,
// so the market keeps its own pure copy of the rule. UI gating is cosmetic: every endpoint still
// enforces its node.
const P = 'pano.plugin.pano-plugin-market.';

export const NODE = {
  ALL: P + 'manage.market',
  CAT: P + 'manage.market.catalog',
  OV: P + 'view.market.orders',
  OM: P + 'manage.market.orders',
  PAY: P + 'manage.market.payments',
  DISC: P + 'manage.market.discounts',
  SET: P + 'manage.market.settings',
  STATS: P + 'view.market.stats',
};

export const ANY_NODE = Object.values(NODE);

const lowerSet = (user) => new Set((user?.permissions || []).map((p) => String(p).toLowerCase()));

/**
 * user = $page.data.user ({ admin, permissions[] }); keys = 'CAT' | 'OV' | ...
 * True for an admin, for a holder of the umbrella node and for a holder of any listed node.
 */
export function can(user, ...keys) {
  if (!user) return false;
  if (user.admin) return true;
  const have = lowerSet(user);
  if (have.has(NODE.ALL)) return true;
  return keys.some((k) => NODE[k] !== undefined && have.has(NODE[k]));
}

/** Any-of node list for host registrations (page, nav item, hook): the node(s) or the umbrella node. */
export const perm = (...keys) => [...keys.map((k) => NODE[k]), NODE.ALL];

/**
 * Host rule for a `permission` value of a hook or registration contributed by ANOTHER plugin:
 * absent / empty = allowed, a string or an any-of array of full node names, admin passes.
 */
export function canNode(user, permission) {
  const list = Array.isArray(permission) ? permission : permission ? [permission] : [];
  if (list.length === 0) return true;
  if (!user) return false;
  if (user.admin) return true;
  const have = lowerSet(user);
  return list.some((node) => have.has(String(node).toLowerCase()));
}
