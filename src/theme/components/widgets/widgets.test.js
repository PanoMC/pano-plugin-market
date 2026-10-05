import { describe, expect, test } from 'bun:test';
import { readFileSync } from 'node:fs';
import '../../lib/__tests__/sdkMocks.js';
import {
  buyerView,
  clampPercent,
  goalView,
  hasContent,
  placementAllows,
  rankView,
  shouldRender,
  statsRows,
  storeModules,
  supporterView,
} from './widgetsModel.js';

const { INCLUDE, SIDEBAR_WIDGETS, loadWidgets, registerSidebarWidgets, sidebarWidget } =
  await import('./widgetsLoader.js');
const { setPano } = await import('../../utils/host.js');

const read = (p) => readFileSync(new URL(p, import.meta.url), 'utf8');
const FILES = [
  'GoalWidget',
  'TopSupportersWidget',
  'RecentBuyersWidget',
  'StatsWidget',
  'StoreModules',
];
const HOUR = 3600 * 1000;

describe('clampPercent / goalView', () => {
  test('clamps to 0..100 and tolerates garbage', () => {
    expect(clampPercent(150)).toBe(100);
    expect(clampPercent(-5)).toBe(0);
    expect(clampPercent('abc')).toBe(0);
    expect(clampPercent(42.5)).toBe(42.5);
  });

  test('bar width never exceeds 100 and bg-success starts at 100', () => {
    const over = goalView({
      id: 1,
      name: 'A',
      percent: 180,
      metric: 'REVENUE',
      progress: 18,
      target: 10,
    });
    expect(over.width).toBe('width: 100%');
    expect(over.valueNow).toBe(100);
    expect(over.complete).toBe(true);
    const part = goalView({ id: 2, name: 'B', percent: 99.9 });
    expect(part.complete).toBe(false);
    expect(part.valueNow).toBe(100);
    expect(part.width).toBe('width: 99.9%');
  });

  test('revenue goals are money, other metrics plain counts', () => {
    expect(goalView({ metric: 'REVENUE', currency: 'EUR' }).revenue).toBe(true);
    expect(goalView({ metric: 'ORDERS' }).revenue).toBe(false);
  });

  test('end: countdown within 72 h, date beyond it or without a clock, none without endsAt', () => {
    const now = 1_000_000_000_000;
    expect(goalView({ endsAt: now + 2 * HOUR }, now).end).toEqual({
      kind: 'COUNTDOWN',
      text: '02:00:00',
    });
    expect(goalView({ endsAt: now + 100 * HOUR }, now).end).toEqual({
      kind: 'DATE',
      ms: now + 100 * HOUR,
    });
    expect(goalView({ endsAt: now + HOUR }, 0).end.kind).toBe('DATE');
    expect(goalView({ endsAt: now - HOUR }, now).end.kind).toBe('DATE');
    expect(goalView({ endsAt: 0 }, now).end).toBeNull();
    expect(goalView({}, now).end).toBeNull();
  });
});

describe('ranks, supporters, buyers, stats', () => {
  test('rank 1 to 3 get a trophy tone, others none', () => {
    expect(rankView(1).tone).toBe('text-warning');
    expect(rankView(2).tone).toBe('text-secondary');
    expect(rankView(3).tone).toBe('text-warning-emphasis');
    expect(rankView(4)).toBeNull();
    expect(rankView(undefined)).toBeNull();
  });

  test('supporter total only when present (0 is present)', () => {
    expect(supporterView({ username: 'a', rank: 1 }).total).toBeNull();
    expect(supporterView({ username: 'a', rank: 1, total: 0 }).total).toBe(0);
    expect(supporterView({ username: 'a', rank: 2, total: 12.5 }).total).toBe(12.5);
  });

  test('buyer: first product, +N, amount only when present', () => {
    const b = buyerView({
      username: 'u',
      productNames: ['VIP', 'Key', 'Coin'],
      amount: 5,
      currency: 'EUR',
      createdAt: 5,
    });
    expect(b).toMatchObject({
      username: 'u',
      product: 'VIP',
      more: 2,
      amount: 5,
      currency: 'EUR',
      createdAt: 5,
    });
    const hidden = buyerView({ username: 'u', productNames: ['VIP'], createdAt: 5 });
    expect(hidden.more).toBe(0);
    expect(hidden.amount).toBeNull();
    expect(buyerView({ username: 'u' }).product).toBe('');
  });

  test('user text stays text: values are passed through as plain strings', () => {
    expect(buyerView({ username: '<b>x</b>', productNames: ['<i>p</i>'] })).toMatchObject({
      username: '<b>x</b>',
      product: '<i>p</i>',
    });
  });

  test('stats rows: present keys only, fixed order', () => {
    expect(statsRows({ productsTotal: 3, ordersToday: 0, junk: 1 })).toEqual([
      { key: 'ordersToday', value: 0 },
      { key: 'productsTotal', value: 3 },
    ]);
    expect(statsRows(undefined)).toEqual([]);
    expect(statsRows({ ordersToday: '5' })).toEqual([]);
  });
});

describe('visibility rules', () => {
  test('a missing or empty key renders nothing', () => {
    expect(hasContent({}, 'goals')).toBe(false);
    expect(hasContent({ goals: [] }, 'goals')).toBe(false);
    expect(hasContent({ goals: [{ id: 1 }] }, 'goals')).toBe(true);
    expect(hasContent({ stats: {} }, 'stats')).toBe(false);
    expect(hasContent({ stats: { ordersToday: 0 } }, 'stats')).toBe(true);
    expect(hasContent(undefined, 'goals')).toBe(false);
  });

  test('inside a sidebar the placement list decides; on the store page it does not apply', () => {
    expect(placementAllows({ goals: [] })).toBe(true);
    expect(placementAllows({ sidebarId: 'home', sidebars: ['home'] })).toBe(true);
    expect(placementAllows({ sidebarId: 'profile', sidebars: ['home'] })).toBe(false);
    expect(placementAllows({ sidebarId: 'home' })).toBe(false);
    expect(
      shouldRender({ sidebarId: 'profile', sidebars: ['home'], goals: [{ id: 1 }] }, 'goals'),
    ).toBe(false);
    expect(
      shouldRender({ sidebarId: 'home', sidebars: ['home'], goals: [{ id: 1 }] }, 'goals'),
    ).toBe(true);
  });

  test('store modules: order goal, supporters, buyers; each flag off => absent', () => {
    const widgets = {
      goals: [{ id: 1 }],
      topSupporters: [{ username: 'a' }],
      recentBuyers: [{ username: 'b' }],
    };
    const on = { modules: { goal: true, topSupporters: true, recentBuyers: true } };
    expect(storeModules(on, widgets)).toEqual(['goals', 'topSupporters', 'recentBuyers']);
    expect(storeModules({ modules: { ...on.modules, goal: false } }, widgets)).toEqual([
      'topSupporters',
      'recentBuyers',
    ]);
    expect(storeModules({ modules: { ...on.modules, topSupporters: false } }, widgets)).toEqual([
      'goals',
      'recentBuyers',
    ]);
    expect(storeModules({ modules: { ...on.modules, recentBuyers: false } }, widgets)).toEqual([
      'goals',
      'topSupporters',
    ]);
    expect(storeModules({ modules: {} }, widgets)).toEqual([]);
    expect(storeModules({}, widgets)).toEqual([]);
    expect(storeModules(on, {})).toEqual([]);
  });
});

describe('loadWidgets', () => {
  test('one request per event, even for four concurrent callers', async () => {
    const calls = [];
    const fetcher = async (path, options) => {
      calls.push({ path, options });
      return { ok: true, result: 'ok', goals: [{ id: 1 }], sidebars: ['home'] };
    };
    const event = {};
    const all = await Promise.all([1, 2, 3, 4].map(() => loadWidgets(event, fetcher)));
    expect(calls).toHaveLength(1);
    expect(calls[0].path).toBe('/api/market/widgets');
    expect(calls[0].options.query.include).toBe('recentBuyers,topSupporters,goals,stats');
    expect(calls[0].options.query.include).toBe(INCLUDE);
    expect(all[0]).toEqual({ goals: [{ id: 1 }], sidebars: ['home'] });
    expect(all[3]).toBe(all[0]);
    await loadWidgets({}, fetcher);
    expect(calls).toHaveLength(2);
  });

  test('failures and throwing fetchers give {} (nothing renders), never reject', async () => {
    expect(await loadWidgets({}, async () => ({ ok: false, code: 'NETWORK' }))).toEqual({});
    expect(
      await loadWidgets({}, async () => {
        throw new Error('x');
      }),
    ).toEqual({});
    expect(await loadWidgets({}, async () => null)).toEqual({});
  });

  test('without an event object nothing is cached and nothing breaks', async () => {
    let n = 0;
    const fetcher = async () => (n++, { ok: true });
    await loadWidgets(undefined, fetcher);
    await loadWidgets(undefined, fetcher);
    expect(n).toBe(2);
  });
});

describe('sidebarWidget / registration', () => {
  test('the thunk merges the module and a load() that adds the sidebar id', async () => {
    const mod = await sidebarWidget(async () => ({ default: 'C' }), 'profile')();
    expect(mod.default).toBe('C');
    // the shared failing network path: load still resolves, carrying the sidebar id
    const data = await mod.load({});
    expect(data.sidebarId).toBe('profile');
  });

  function fakePano(features) {
    const registered = [];
    return {
      registered,
      features: { has: (f) => features.includes(f) },
      ui: { sidebar: { register: (o) => registered.push(o) } },
    };
  }

  test('registers 4 widgets in home and profile only with page-sidebar-id', () => {
    const pano = fakePano(['page-sidebar-id']);
    setPano(pano);
    expect(registerSidebarWidgets(pano)).toBe(8);
    expect(pano.registered).toHaveLength(8);
    for (const sid of ['home', 'profile']) {
      const rows = pano.registered.filter((r) => r.sidebarId === sid);
      expect(rows.map((r) => [r.id, r.priority])).toEqual([
        ['market-goals', 70],
        ['market-top-supporters', 60],
        ['market-recent-buyers', 50],
        ['market-stats', 40],
      ]);
      expect(rows.every((r) => typeof r.component === 'function')).toBe(true);
      expect(Object.keys(rows[0]).sort()).toEqual(['component', 'id', 'priority', 'sidebarId']);
    }
    expect(SIDEBAR_WIDGETS.every((w) => w.priority < 80)).toBe(true);
  });

  test('without the feature nothing is registered', () => {
    const pano = fakePano([]);
    setPano(pano);
    expect(registerSidebarWidgets(pano)).toBe(0);
    expect(pano.registered).toHaveLength(0);
  });

  test('register.js wires item 11 through the per-item guard', () => {
    const src = read('../../register.js');
    expect(src).toContain("optional('sidebar-widgets'");
    expect(src).toContain('registerSidebarWidgets(pano)');
  });
});

describe('markup rules', () => {
  const src = Object.fromEntries(FILES.map((f) => [f, read(`./${f}.svelte`)]));

  test('no <style>, no {@html}, no on: directives, user text only as text nodes', () => {
    for (const [name, s] of Object.entries(src)) {
      expect(s, name).not.toContain('<style');
      expect(s, name).not.toContain('{@html');
      expect(s, name).not.toMatch(/\son:\w+/);
    }
  });

  test('style= only for the progress-bar width, only in GoalWidget', () => {
    for (const [name, s] of Object.entries(src)) {
      const n = (s.match(/\sstyle=/g) || []).length;
      expect(n, name).toBe(name === 'GoalWidget' ? 1 : 0);
    }
    expect(src.GoalWidget).toContain('style={goal.width}');
    expect(src.GoalWidget).toContain('role="progressbar"');
    expect(src.GoalWidget).toContain('aria-valuemin="0"');
    expect(src.GoalWidget).toContain('aria-valuemax="100"');
    expect(src.GoalWidget).toContain("goal.complete && 'bg-success'");
  });

  test('widget markup follows 14 13.1', () => {
    expect(src.TopSupportersWidget).toContain('list-group-flush');
    expect(src.TopSupportersWidget).toContain('width={24}');
    expect(src.RecentBuyersWidget).toContain('list-group-flush');
    expect(src.RecentBuyersWidget).toContain('relativeFormat={true}');
    expect(src.StatsWidget).toContain('row row-cols-2 g-2');
  });

  test('every widget gates on shouldRender and the store modules on storeModules', () => {
    for (const f of FILES.slice(0, 4)) expect(src[f]).toContain('shouldRender(data,');
    expect(src.StoreModules).toContain('storeModules(settings, widgets)');
  });

  test('only allow-listed SDK imports', () => {
    for (const s of Object.values(src))
      for (const m of s.matchAll(/from '(@panomc\/sdk[^']*)'/g))
        expect(['@panomc/sdk/components/theme']).toContain(m[1]);
  });
});

describe('locales', () => {
  const langs = ['en-US', 'tr', 'ru'];
  const widgets = langs.map(
    (l) => JSON.parse(read(`../../../locales/theme/${l}.json`)).theme.widgets,
  );
  const flat = (o, p = '') =>
    Object.entries(o).flatMap(([k, v]) =>
      typeof v === 'object' ? flat(v, `${p}${k}.`) : [`${p}${k}`],
    );

  test('identical key sets and every literal key used by the components exists', () => {
    expect(flat(widgets[1]).sort()).toEqual(flat(widgets[0]).sort());
    expect(flat(widgets[2]).sort()).toEqual(flat(widgets[0]).sort());
    const have = new Set(flat(widgets[0]).map((k) => `theme.widgets.${k}`));
    for (const f of FILES) {
      for (const m of read(`./${f}.svelte`).matchAll(/\$_\(\s*'(theme\.widgets\.[^']+)'/g))
        expect(have.has(m[1]), m[1]).toBe(true);
    }
    for (const k of have) {
      const used = FILES.some((f) => read(`./${f}.svelte`).includes(`'${k}'`));
      expect(used, k).toBe(true);
    }
  });

  test('placeholders agree across languages', () => {
    const ph = (s) => (s.match(/\{(\w+)\}/g) || []).sort().join();
    const [en, tr, ru] = widgets.map((w) =>
      flat(w).map((k) => [k, k.split('.').reduce((a, p) => a[p], w)]),
    );
    en.forEach(([k, v], i) => {
      expect(ph(tr[i][1]), k).toBe(ph(v));
      expect(ph(ru[i][1]), k).toBe(ph(v));
    });
  });
});
