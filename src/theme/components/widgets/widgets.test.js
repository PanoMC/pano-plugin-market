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
  unwrapWidgets,
  withSidebar,
} from './widgetsModel.js';
import widgets from '../../controllers/widgets.js';

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

describe('market/widgets controller', () => {
  const fakeHost = (answer, clock = { now: 1000 }) => {
    const sent = [];
    const host = {
      browser: true,
      now: () => clock.now,
      request: async (request) => {
        sent.push(request);
        if (typeof answer === 'function') return answer(request);

        return answer;
      },
    };

    return { host, sent, clock };
  };

  test('is an instance controller with a load and nothing else', () => {
    expect(widgets.name).toBe('widgets');
    expect(widgets.version).toBe(1);
    expect(widgets.scope).toBe('instance');
    expect(typeof widgets.load).toBe('function');
  });

  test('asks /widgets once with the four keys and hands out the payload without the envelope', async () => {
    const { host, sent } = fakeHost({ goals: [{ id: 1 }], sidebars: ['home'] });
    const payload = await widgets.load({ host, params: {} });

    expect(sent).toHaveLength(1);
    expect(sent[0].method).toBe('GET');
    const url = new URL(sent[0].path, 'https://shop.test');
    expect(url.pathname).toBe('/plugins/pano-plugin-market/widgets');
    expect(url.searchParams.get('include')).toBe('recentBuyers,topSupporters,goals,stats');
    expect(payload).toEqual({ goals: [{ id: 1 }], sidebars: ['home'] });
  });

  test('four loads on one host share one request, a later load after 5 s asks again', async () => {
    const { host, sent, clock } = fakeHost({ goals: [] });
    const all = await Promise.all([1, 2, 3, 4].map(() => widgets.load({ host, params: {} })));

    expect(sent).toHaveLength(1);
    expect(all[3]).toBe(all[0]);
    clock.now += 4999;
    await widgets.load({ host, params: {} });
    expect(sent).toHaveLength(1);
    clock.now += 2;
    await widgets.load({ host, params: {} });
    expect(sent).toHaveLength(2);
  });

  test('another host (a server request) never reuses the answer', async () => {
    const a = fakeHost({ goals: [] });
    const b = fakeHost({ goals: [] });
    await widgets.load({ host: a.host, params: {} });
    await widgets.load({ host: b.host, params: {} });

    expect(a.sent).toHaveLength(1);
    expect(b.sent).toHaveLength(1);
  });

  test('failures and throwing hosts give {} (nothing renders), never reject', async () => {
    expect(
      await widgets.load({ host: fakeHost({ error: { code: 'NETWORK_ERROR' } }).host, params: {} }),
    ).toEqual({});
    expect(
      await widgets.load({
        host: fakeHost(() => {
          throw new Error('x');
        }).host,
        params: {},
      }),
    ).toEqual({});
  });
});

describe('unwrapWidgets', () => {
  test('a block or element prop is the payload, a slot prop wraps it once more', () => {
    const payload = { goals: [1], sidebars: ['home'] };

    expect(unwrapWidgets(payload)).toBe(payload);
    expect(unwrapWidgets({ data: payload })).toBe(payload);
    expect(unwrapWidgets(undefined)).toEqual({});
    expect(unwrapWidgets(null)).toEqual({});
    expect(unwrapWidgets({ data: {} })).toEqual({});
  });
});

describe('sidebar placement', () => {
  test('withSidebar adds the sidebar id prop to the data once and never overwrites one', () => {
    const data = { goals: [1], sidebars: ['home'] };
    expect(withSidebar(data, 'home')).toEqual({
      goals: [1],
      sidebars: ['home'],
      sidebarId: 'home',
    });
    expect(withSidebar(data, '')).toBe(data);
    expect(withSidebar(data, undefined)).toBe(data);
    const placed = { sidebarId: 'profile' };
    expect(withSidebar(placed, 'home')).toBe(placed);
    // the placement rule sees the prop: a widget the admin did not place in this sidebar stays hidden
    expect(shouldRender(withSidebar(data, 'profile'), 'goals')).toBe(false);
    expect(shouldRender(withSidebar(data, 'home'), 'goals')).toBe(true);
  });

  test('the four widget views carry their sidebar injection and widget: true as view metadata', () => {
    const expected = {
      GoalWidget: ['market-goals', 70],
      TopSupportersWidget: ['market-top-supporters', 60],
      RecentBuyersWidget: ['market-recent-buyers', 50],
      StatsWidget: ['market-stats', 40],
    };
    for (const [file, [id, priority]] of Object.entries(expected)) {
      const src = read(`./${file}.svelte`);
      // prettier may wrap the object over several lines: compare it with the whitespace folded
      expect(src.replace(/\s+/g, ' '), file).toContain(
        `export const view = { sidebar: ['home', 'profile'], id: '${id}', priority: ${priority}, widget: true, };`,
      );
      // the module load is the one `market/widgets` load of the page; the payload is the `data` prop
      expect(src, file).toContain('export const load = (event) =>');
      expect(src, file).toContain(".load('widgets', { event })");
      expect(src, file).toContain('.then((data) => ({ data: data ?? {} }));');
    }
  });

  test('the five widget tags are distinct and follow pano-market-<view without Widget>', () => {
    const tags = {
      GoalWidget: ['widgets', 'pano-market-goal'],
      RecentBuyersWidget: ['widgets', 'pano-market-recent-buyers'],
      TopSupportersWidget: ['widgets', 'pano-market-top-supporters'],
      StatsWidget: ['widgets', 'pano-market-stats'],
      NavCart: ['cart', 'pano-market-nav-cart'],
    };
    const seen = new Set();

    for (const [view, [dir, tag]] of Object.entries(tags)) {
      const src = read(`../${dir}/${view}.svelte`).replace(/\s+/g, ' ');

      expect(src, view).toMatch(/export const view = \{[^}]*\bwidget: true,? ?\}/);
      expect(
        `pano-market-${view
          .replace(/(Widget|Block)$/, '')
          .replace(/([a-z0-9])([A-Z])/g, '$1-$2')
          .toLowerCase()}`,
      ).toBe(tag);
      seen.add(tag);
    }

    expect(seen.size).toBe(5);
  });

  test('register.js no longer registers the widgets (the build does)', () => {
    const src = read('../../register.js');
    expect(src).not.toContain('registerSidebarWidgets');
    expect(src).not.toContain('sidebar.register');
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
    for (const f of FILES.slice(0, 4))
      expect(src[f]).toContain('shouldRender(withSidebar(payload, sidebarId),');
    expect(src.StoreModules).toContain('storeModules(settings, widgets)');
  });

  test('only allow-listed SDK imports', () => {
    for (const s of Object.values(src))
      for (const m of s.matchAll(/from '(@panomc\/sdk[^']*)'/g))
        expect(['@panomc/sdk/components/theme', '@panomc/sdk/controllers']).toContain(m[1]);
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

describe('the store page shows the store modules (14 section 8.2, E2E-18)', () => {
  const page = read('../../pages/StorePage.svelte');

  test('StoreModules is rendered twice: under the categories on large screens, below the grid on small ones', () => {
    expect(page).toContain("import StoreModules from '../components/widgets/StoreModules.svelte'");
    expect(page.match(/<StoreModules \{settings\} \{widgets\} \/>/g)).toHaveLength(2);
    expect(page).toMatch(/<div class="d-none d-lg-block mt-3">\s*<StoreModules/);
    expect(page).toMatch(/<div class="d-lg-none">\s*<StoreModules/);
  });

  test('the widgets answer of load() is what it shows, and the flags of the settings decide which cards', () => {
    expect(page).toContain('let widgets = $state(init.widgets ?? {})');
    expect(
      storeModules(
        { modules: { goal: true, topSupporters: false, recentBuyers: true } },
        {
          goals: [{ id: 1 }],
          topSupporters: [{ username: 'a' }],
          recentBuyers: [{ username: 'b' }],
        },
      ),
    ).toEqual(['goals', 'recentBuyers']);
  });
});
