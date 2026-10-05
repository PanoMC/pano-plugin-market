import { beforeEach, describe, expect, test } from 'bun:test';
import { NODE, perm, ANY_NODE } from './utils/permissions.js';

const { PAGES, guarded, registerPanel } = await import('./register.js?real');

// 13 section 2.3: path -> node keys (the umbrella node is added by perm()).
const EXPECTED = {
  '/market': 'ANY',
  '/market/orders': ['OV'],
  '/market/orders/detail/[id]': ['OV'],
  '/market/orders/create-order': ['PAY'],
  '/market/deliveries': ['OV'],
  '/market/shipments': ['OV'],
  '/market/subscriptions': ['OV'],
  '/market/subscriptions/detail/[id]': ['OV'],
  '/market/payment-events': ['OV'],
  '/market/products': ['CAT'],
  '/market/products/create-product': ['CAT'],
  '/market/categories': ['CAT'],
  '/market/comparisons': ['CAT'],
  '/market/comparisons/create-comparison': ['CAT'],
  '/market/goals': ['CAT'],
  '/market/discounts': ['DISC'],
  '/market/discounts/creator/[id]': ['DISC'],
  '/market/gifts': ['DISC'],
  '/market/credits': ['PAY'],
  '/market/credits/account/[userId]': ['PAY'],
  '/market/blocks': ['OM'],
  '/market/settings': ['SET'],
  '/market/settings/shipping-method': ['SET'],
  '/players/detail/[username]/market': ['OV', 'PAY'],
};

function fakePano({ features, playerTab = true } = {}) {
  const pages = [];
  const hooks = [];
  const menus = [];
  let navHandler = null;
  const pano = {
    features,
    ui: {
      page: { register: (r) => pages.push(r) },
      hook: { register: (h) => hooks.push(h) },
      nav: { site: { editNavLinks: (fn) => (navHandler = fn) } },
      ...(playerTab ? { player: { detail: { editMenu: (fn) => menus.push(fn) } } } : {}),
    },
  };
  return { pano, pages, hooks, menus, nav: () => navHandler };
}

const anyOfFeatures = { has: (id) => id === 'permission-any-of' };

describe('registerPanel routes (13 2.3)', () => {
  test('the page table has no duplicate paths', () => {
    const paths = PAGES.map((p) => p[0]);
    expect(new Set(paths).size).toBe(paths.length);
  });

  test('with permission-any-of every route is registered with perm(...)', () => {
    const { pano, pages } = fakePano({ features: anyOfFeatures });
    registerPanel(pano);
    expect(pages.map((p) => p.path).sort()).toEqual(Object.keys(EXPECTED).sort());
    for (const reg of pages) {
      const want = EXPECTED[reg.path];
      expect(reg.permission).toEqual(want === 'ANY' ? ANY_NODE : perm(...want));
      expect(reg.component).toBeTruthy(); // viewComponent wrapper (another test file may mock the SDK)
      expect(reg.permission).toContain(NODE.ALL);
    }
  });

  test('without the feature no route carries a permission field', () => {
    for (const features of [undefined, { has: () => false }, { list: () => [] }]) {
      const { pano, pages } = fakePano({ features });
      registerPanel(pano);
      expect(pages).toHaveLength(Object.keys(EXPECTED).length);
      for (const reg of pages) expect('permission' in reg).toBe(false);
    }
  });

  test('the player tab uses the player layout and does not reset the layout', () => {
    const { pano, pages, menus } = fakePano({ features: anyOfFeatures });
    registerPanel(pano);
    const reg = pages.find((p) => p.path === '/players/detail/[username]/market');
    expect(reg.systemLayout).toBe('PlayerDetailLayout');
    expect(reg.resetLayout).toBe(false);
    const items = menus[0]([{ id: 'overview' }]);
    expect(items.at(-1)).toMatchObject({
      id: 'market',
      href: '/market',
      text: 'plugins.pano-plugin-market.nav-market',
      permission: perm('OV', 'PAY'),
    });
  });

  test('on an older panel the player view is a hook card instead of a tab', () => {
    const { pano, pages, hooks } = fakePano({ features: anyOfFeatures, playerTab: false });
    registerPanel(pano);
    expect(pages.some((p) => p.path.startsWith('/players/'))).toBe(false);
    expect(hooks).toHaveLength(1);
    expect(hooks[0].name).toBe('panel:player-detail:bottom');
    expect(hooks[0].permission).toEqual(perm('OV', 'PAY'));
  });

  test('the sidebar item points at /market with ANY_NODE only when any-of is supported', () => {
    const withFeature = fakePano({ features: anyOfFeatures });
    registerPanel(withFeature.pano);
    const items = withFeature.nav()([{ href: '/' }, { href: '/x' }]);
    expect(items[1]).toMatchObject({ href: '/market', permission: ANY_NODE });
    const without = fakePano({});
    registerPanel(without.pano);
    expect('permission' in without.nav()([]).at(0)).toBe(false);
  });
});

describe('every page load starts with the can() guard', () => {
  let loaded;
  beforeEach(() => {
    loaded = 0;
  });
  const importer = async () => ({
    default: {},
    load: async () => {
      loaded++;
      return { data: { ok: true } };
    },
  });
  const event = (user) => ({ parent: async () => ({ user, pageTitle: { set() {} } }) });

  test('denied user gets NO_PERMISSION and the page load never runs', async () => {
    const module = await guarded(importer, ['PAY'])();
    expect(await module.load(event({ admin: false, permissions: [NODE.OV] }))).toEqual({
      data: { error: 'NO_PERMISSION' },
    });
    expect(await module.load(event(null))).toEqual({ data: { error: 'NO_PERMISSION' } });
    expect(loaded).toBe(0);
  });

  test('allowed user reaches the page load, the umbrella node counts', async () => {
    const module = await guarded(importer, ['PAY'])();
    expect(await module.load(event({ admin: false, permissions: [NODE.PAY] }))).toEqual({
      data: { ok: true },
    });
    expect(await module.load(event({ admin: false, permissions: [NODE.ALL] }))).toEqual({
      data: { ok: true },
    });
    expect(loaded).toBe(2);
  });

  test('a page without its own load still gets the guard and a default', async () => {
    const module = await guarded(async () => ({ default: {} }), ['OV'])();
    expect(await module.load(event({ admin: true }))).toEqual({ data: {} });
    expect(await module.load(event({ admin: false, permissions: [] }))).toEqual({
      data: { error: 'NO_PERMISSION' },
    });
  });
});
