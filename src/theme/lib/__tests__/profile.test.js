import { afterEach, beforeEach, describe, expect, test } from 'bun:test';
import fs from 'node:fs';
import path from 'node:path';
import { get, writable } from 'svelte/store';
import './sdkMocks.js';
import { ERROR_KEYS } from '../errorMap.js';
import {
  CREDITS_TITLE_KEY,
  GIFT_NEEDS_OPTIONS_KEY,
  LEDGER_TYPE_KEYS,
  ORDER_FILTERS,
  ORDER_STATUSES,
  PROFILE_LINKS,
  PURCHASES_TITLE_KEY,
  SOON_MS,
  creditBadge,
  creditsGate,
  dropdownItem,
  entitlementView,
  giftMarker,
  giftOutcome,
  itemsSummary,
  ledgerAmount,
  ledgerRow,
  ledgerType,
  linkVisibility,
  listSearch,
  navItems,
  normalizeGiftCode,
  orderBadge,
  orderRow,
  ordersQuery,
  parseListQuery,
  profileExtras,
  readOrders,
  readSummary,
  readTopUp,
  resolveCreditsLoad,
  resolvePurchasesLoad,
  toCents,
  topUpCostCents,
  topUpErrorKey,
  topUpHref,
  validateTopUp,
  visibleLinks,
} from '../profileModel.js';

const host = await import('../../utils/host.js');
const session = await import('../../stores/session.js');
const profileNav = await import('../../stores/profileNav.js');
const { registerTheme } = await import('../../register.js?profile');

const root = path.resolve(import.meta.dir, '../../../..');
const read = (file) => fs.readFileSync(path.join(root, file), 'utf8');
const locale = (lang) => JSON.parse(read(`src/locales/theme/${lang}.json`));
const lookup = (obj, dotted) => dotted.split('.').reduce((o, k) => (o == null ? o : o[k]), obj);

const summary = (over = {}) => ({
  ok: true,
  creditsEnabled: true,
  creditBalance: 12.5,
  creditName: 'Coins',
  activeSubscriptionCount: 1,
  subscriptionCount: 2,
  isCreator: true,
  ...over,
});

describe('profile links and nav items (14 §5 row 10, §12.1)', () => {
  test('four links, priorities 50 / 49 / 48 / 47 and the icons of the spec', () => {
    expect(PROFILE_LINKS.map((l) => [l.navId, l.priority, l.href, l.icon])).toEqual([
      ['market-purchases', 50, '/profile/purchases', 'fa-solid fa-bag-shopping'],
      ['market-credits', 49, '/profile/credits', 'fa-solid fa-coins'],
      ['market-subscriptions', 48, '/profile/subscriptions', 'fa-solid fa-rotate'],
      ['market-creator', 47, '/profile/creator', 'fa-solid fa-bullhorn'],
    ]);
  });

  test('without a summary: purchases only (the other three are hidden: true)', () => {
    const items = navItems(null);
    expect(items).toHaveLength(4);
    expect(items[0]).toEqual({
      id: 'market-purchases',
      priority: 50,
      props: {
        href: '/profile/purchases',
        text: 'plugins.pano-plugin-market.theme.profile.nav.purchases',
        icon: 'fa-solid fa-bag-shopping',
      },
    });
    expect('hidden' in items[0]).toBe(false);
    expect(items.slice(1).map((i) => [i.id, i.hidden])).toEqual([
      ['market-credits', true],
      ['market-subscriptions', true],
      ['market-creator', true],
    ]);
  });

  test('a failed summary (readSummary null) behaves like no summary', () => {
    expect(readSummary({ ok: false, code: 'NETWORK' })).toBeNull();
    expect(readSummary(undefined)).toBeNull();
    expect(linkVisibility(readSummary({ ok: false }))).toEqual({
      purchases: true,
      credits: false,
      subscriptions: false,
      creator: false,
    });
  });

  test('the summary decides credits / subscriptions / creator', () => {
    expect(linkVisibility(readSummary(summary()))).toEqual({
      purchases: true,
      credits: true,
      subscriptions: true,
      creator: true,
    });
    expect(linkVisibility(readSummary(summary({ creditsEnabled: false })))).toMatchObject({
      credits: false,
    });
    expect(linkVisibility(readSummary(summary({ subscriptionCount: 0 })))).toMatchObject({
      subscriptions: false,
    });
    expect(linkVisibility(readSummary(summary({ isCreator: false })))).toMatchObject({
      creator: false,
    });
    // only the total subscription count counts, not the active ones
    expect(
      linkVisibility(readSummary(summary({ subscriptionCount: 1, activeSubscriptionCount: 0 }))),
    ).toMatchObject({ subscriptions: true });
  });

  test('visible items drop hidden and the credits item carries the balance badge', () => {
    const items = navItems(readSummary(summary()), (n) => `${n}`);
    expect(items.every((i) => !('hidden' in i))).toBe(true);
    expect(items[1].props.badge).toBe('12.5');
    expect(items[0].props.badge).toBeUndefined();
  });

  test('no badge without credits or with an unusable balance', () => {
    expect(creditBadge(readSummary(summary({ creditsEnabled: false })), String)).toBeNull();
    expect(creditBadge(null, String)).toBeNull();
    expect(navItems(readSummary(summary({ creditsEnabled: false })))[1].hidden).toBe(true);
    expect(
      navItems(readSummary(summary({ creditsEnabled: false })))[1].props.badge,
    ).toBeUndefined();
  });

  test('readSummary coerces garbage to safe values', () => {
    expect(readSummary({ ok: true, creditBalance: 'x', subscriptionCount: 'y' })).toEqual({
      creditsEnabled: false,
      creditBalance: 0,
      creditName: '',
      activeSubscriptionCount: 0,
      subscriptionCount: 0,
      isCreator: false,
    });
  });

  test('dropdown entry sits between settings (70) and logout (10)', () => {
    const item = dropdownItem();
    expect(item).toMatchObject({
      id: 'market-purchases',
      priority: 60,
      props: { href: '/profile/purchases', icon: 'fa-solid fa-bag-shopping' },
    });
    expect(item.props.text).toBe('plugins.pano-plugin-market.theme.profile.nav.purchases');
  });

  test('visibleLinks (block and pills): same four rules, badge only on credits', () => {
    expect(visibleLinks(null).map((l) => l.id)).toEqual(['purchases']);
    const links = visibleLinks(readSummary(summary()), (n) => `${n} Coins`);
    expect(links.map((l) => l.id)).toEqual(['purchases', 'credits', 'subscriptions', 'creator']);
    expect(links.map((l) => l.badge)).toEqual([null, '12.5 Coins', null, null]);
  });
});

describe('list query', () => {
  const q = (s) => parseListQuery(new URLSearchParams(s));

  test('page and status with defaults', () => {
    expect(q('')).toEqual({ page: 1, status: '' });
    expect(q('page=3&status=PENDING')).toEqual({ page: 3, status: 'PENDING' });
  });

  test('garbage falls back, never reaches the API', () => {
    expect(q('page=0&status=HACKED')).toEqual({ page: 1, status: '' });
    expect(q('page=-2')).toEqual({ page: 1, status: '' });
    expect(q('page=1.5')).toEqual({ page: 1, status: '' });
    expect(q('page=99999999999999999999')).toEqual({ page: 1, status: '' });
    expect(q('status=REVIEW')).toEqual({ page: 1, status: '' });
  });

  test('the filter set is exactly the five of 14 §12.2 (all + four)', () => {
    expect(ORDER_FILTERS).toEqual(['COMPLETED', 'PENDING', 'REFUNDED', 'CANCELLED']);
  });

  test('address bar and request query', () => {
    expect(listSearch({ page: 1, status: '' })).toBe('');
    expect(listSearch({ page: 2, status: '' })).toBe('page=2');
    expect(listSearch({ page: 2, status: 'REFUNDED' })).toBe('status=REFUNDED&page=2');
    expect(ordersQuery({ page: 2, status: '' })).toEqual({ page: 2, status: undefined });
    expect(ordersQuery({ page: 1, status: 'PENDING' })).toEqual({ page: 1, status: 'PENDING' });
  });

  test('page extras: sidebar only with page-sidebar-id, robots with page-meta', () => {
    expect(profileExtras({})).toEqual({});
    expect(profileExtras({ sidebar: true })).toEqual({ sidebar: 'profile' });
    expect(profileExtras({ meta: true })).toEqual({ meta: { robots: 'noindex,nofollow' } });
  });
});

describe('purchases load', () => {
  const orders = {
    ok: true,
    orders: [{ publicId: 'a' }],
    orderCount: 21,
    totalPage: 2,
  };
  const ents = { ok: true, entitlements: [{ id: 1 }] };

  test('ready: orders, entitlements, title, noindex only with page-meta, sidebar only with page-sidebar-id', () => {
    const r = resolvePurchasesLoad({
      orders,
      entitlements: ents,
      filter: { page: 1, status: '' },
      settings: { removeCents: true },
      features: { sidebar: true, meta: true },
    });
    expect(r.data.state).toBe('READY');
    expect(r.data.orders).toMatchObject({ state: 'READY', orderCount: 21, totalPage: 2 });
    expect(r.data.entitlements).toEqual([{ id: 1 }]);
    expect(r.pageTitle).toEqual({ title: PURCHASES_TITLE_KEY });
    expect(r.sidebar).toBe('profile');
    expect(r.meta).toEqual({ robots: 'noindex,nofollow' });

    const old = resolvePurchasesLoad({ orders, entitlements: ents, features: {} });
    expect('sidebar' in old).toBe(false);
    expect('meta' in old).toBe(false);
  });

  test('a guest (NOT_LOGGED_IN from either call) is redirected', () => {
    expect(
      resolvePurchasesLoad({ orders: { ok: false, code: 'NOT_LOGGED_IN' }, entitlements: ents }),
    ).toEqual({
      redirect: true,
    });
    expect(
      resolvePurchasesLoad({ orders, entitlements: { ok: false, code: 'NOT_LOGGED_IN' } }),
    ).toEqual({
      redirect: true,
    });
  });

  test('a failed order list keeps the page (error part), a failed entitlement call hides that part only', () => {
    const r = resolvePurchasesLoad({
      orders: { ok: false, code: 'NETWORK' },
      entitlements: { ok: false, code: 'NETWORK' },
    });
    expect(r.data.orders).toMatchObject({ state: 'ERROR', code: 'NETWORK', orders: [] });
    expect(r.data.entitlements).toEqual([]);
    expect(r.data.entitlementsFailed).toBe(true);
  });

  test('pills mode: the summary read travels with the data', () => {
    const r = resolvePurchasesLoad({ orders, entitlements: ents, summary: summary() });
    expect(r.data.summary.creditsEnabled).toBe(true);
    expect(resolvePurchasesLoad({ orders, entitlements: ents }).data.summary).toBeNull();
  });

  test('readOrders: totalPage is at least 1, counts are numbers', () => {
    expect(readOrders({ ok: true, orders: [] })).toEqual({
      state: 'READY',
      orders: [],
      orderCount: 0,
      totalPage: 1,
    });
    expect(readOrders({ ok: true, orders: 'no', orderCount: 'x', totalPage: 0 }).orders).toEqual(
      [],
    );
    expect(readOrders(undefined)).toMatchObject({ state: 'ERROR', code: 'NETWORK' });
  });
});

describe('entitlements', () => {
  const NOW = 1_700_000_000_000;

  test('no expiry = permanent, never "soon"', () => {
    const v = entitlementView({ id: 1, productName: 'VIP', expiresAt: null }, NOW);
    expect(v).toMatchObject({ name: 'VIP', permanent: true, expiresAt: null, soon: false });
  });

  test('less than three days left is soon, exactly three days is not', () => {
    expect(entitlementView({ productName: 'x', expiresAt: NOW + SOON_MS - 1 }, NOW).soon).toBe(
      true,
    );
    expect(entitlementView({ productName: 'x', expiresAt: NOW + SOON_MS }, NOW).soon).toBe(false);
    expect(entitlementView({ productName: 'x', expiresAt: NOW + 10 * SOON_MS }, NOW).soon).toBe(
      false,
    );
  });

  test('SSR (clock 0) never marks soon, so the first render matches', () => {
    expect(entitlementView({ productName: 'x', expiresAt: 5 }, 0).soon).toBe(false);
  });

  test('variant and subscription link', () => {
    const v = entitlementView({ productName: 'VIP', variantName: 'Gold', subscriptionId: 7 });
    expect(v.variant).toBe('Gold');
    expect(v.subscriptionId).toBe(7);
    expect(entitlementView({ productName: 'VIP' }).variant).toBe('');
    expect(entitlementView(null).name).toBe('');
  });
});

describe('order rows', () => {
  test('first two item names, then +N', () => {
    expect(itemsSummary(['a'])).toEqual({ names: ['a'], more: 0 });
    expect(itemsSummary(['a', 'b'])).toEqual({ names: ['a', 'b'], more: 0 });
    expect(itemsSummary(['a', 'b', 'c', 'd', 'e'])).toEqual({ names: ['a', 'b'], more: 3 });
    expect(itemsSummary(undefined)).toEqual({ names: [], more: 0 });
  });

  test('gift marker: received wins, sent shows the recipient', () => {
    expect(giftMarker({ received: true, isGift: true, recipientUsername: 'me' })).toEqual({
      kind: 'RECEIVED',
    });
    expect(giftMarker({ isGift: true, recipientUsername: 'Alex' })).toEqual({
      kind: 'SENT',
      username: 'Alex',
    });
    expect(giftMarker({ isGift: false })).toBeNull();
    expect(giftMarker({})).toBeNull();
  });

  test('row: real link to the order page with an escaped id', () => {
    const row = orderRow({
      publicId: 'Ab_1-x',
      number: 42,
      createdAt: 5,
      itemNames: ['a'],
      total: 9.5,
      currency: 'EUR',
      status: 'COMPLETED',
    });
    expect(row.href).toBe('/store/order/Ab_1-x');
    expect(row).toMatchObject({
      number: 42,
      total: 9.5,
      currency: 'EUR',
      status: 'COMPLETED',
      gift: null,
    });
    expect(orderRow({ publicId: 'a/b?c' }).href).toBe('/store/order/a%2Fb%3Fc');
  });

  test('badges of 14 §12.2 and the nine status keys', () => {
    const cls = (s) => orderBadge(s).className;
    expect(cls('PENDING')).toBe('text-bg-warning');
    expect(cls('REVIEW')).toBe('text-bg-info');
    expect(cls('COMPLETED')).toBe('text-bg-success');
    for (const s of ['PARTIALLY_REFUNDED', 'REFUNDED', 'CANCELLED', 'EXPIRED'])
      expect(cls(s)).toBe('text-bg-secondary');
    for (const s of ['CHARGEBACK', 'FAILED']) expect(cls(s)).toBe('text-bg-danger');
    expect(ORDER_STATUSES).toHaveLength(9);
    expect(orderBadge('PENDING').key).toBe('theme.status.order.PENDING');
  });

  test('an unknown status is secondary and keeps its raw value, never a key', () => {
    expect(orderBadge('WEIRD')).toEqual({
      className: 'text-bg-secondary',
      key: null,
      raw: 'WEIRD',
    });
    expect(orderBadge(undefined).raw).toBe('');
    // inherited object members are not statuses
    expect(orderBadge('constructor').key).toBeNull();
    expect(orderBadge('toString').key).toBeNull();
  });
});

describe('gift redemption (14 §12.2)', () => {
  test('code: trimmed, capped at 64, empty stays empty', () => {
    expect(normalizeGiftCode('  ab12  ')).toBe('ab12');
    expect(normalizeGiftCode('x'.repeat(100))).toHaveLength(64);
    expect(normalizeGiftCode(null)).toBe('');
  });

  test('success opens the order', () => {
    expect(giftOutcome({ ok: true, order: { publicId: 'AbC' } })).toEqual({
      kind: 'GOTO',
      path: '/store/order/AbC',
    });
  });

  test('an ok answer without an order id is a generic error, not a blind navigation', () => {
    expect(giftOutcome({ ok: true })).toEqual({ kind: 'ERROR', key: 'theme.errors.GENERIC' });
    expect(giftOutcome({ ok: true, order: { publicId: '' } }).kind).toBe('ERROR');
  });

  test('the order id is escaped into the path', () => {
    expect(giftOutcome({ ok: true, order: { publicId: 'a/../b' } }).path).toBe(
      '/store/order/a%2F..%2Fb',
    );
  });

  test('INVALID_GIFT_CODE reasons map to theme.errors.<reason>, fallback INVALID_GIFT_CODE', () => {
    for (const reason of [
      'CODE_NOT_FOUND',
      'CODE_EXPIRED',
      'CODE_LIMIT_REACHED',
      'CODE_NOT_STARTED',
      'PRODUCT_UNAVAILABLE',
      'PHYSICAL_NOT_SUPPORTED',
    ])
      expect(giftOutcome({ ok: false, code: 'INVALID_GIFT_CODE', reason })).toEqual({
        kind: 'INVALID',
        key: `theme.errors.${reason}`,
      });
    expect(giftOutcome({ ok: false, code: 'INVALID_GIFT_CODE', reason: 'NEW_REASON' })).toEqual({
      kind: 'INVALID',
      key: 'theme.errors.INVALID_GIFT_CODE',
    });
    expect(giftOutcome({ ok: false, code: 'INVALID_GIFT_CODE' }).key).toBe(
      'theme.errors.INVALID_GIFT_CODE',
    );
  });

  test('SERVER_REQUIRED and FIELD_REQUIRED say the gift needs options', () => {
    for (const reason of ['SERVER_REQUIRED', 'FIELD_REQUIRED'])
      expect(giftOutcome({ ok: false, code: 'INVALID_GIFT_CODE', reason })).toEqual({
        kind: 'INVALID',
        key: GIFT_NEEDS_OPTIONS_KEY,
      });
  });

  test('CODE_ATTEMPTS_LOCKED disables with the retry countdown (1..3600, 60 when missing)', () => {
    expect(giftOutcome({ ok: false, code: 'CODE_ATTEMPTS_LOCKED', retryAfter: 90 })).toEqual({
      kind: 'LOCKED',
      key: 'theme.errors.CODE_ATTEMPTS_LOCKED',
      seconds: 90,
    });
    expect(giftOutcome({ ok: false, code: 'CODE_ATTEMPTS_LOCKED' }).seconds).toBe(60);
    expect(
      giftOutcome({ ok: false, code: 'CODE_ATTEMPTS_LOCKED', retryAfter: 999999 }).seconds,
    ).toBe(3600);
    expect(giftOutcome({ ok: false, code: 'TOO_MANY_REQUESTS', retryAfter: 5 }).kind).toBe(
      'LOCKED',
    );
  });

  test('every other failure is an alert; an unknown code never shows a raw identifier', () => {
    expect(giftOutcome({ ok: false, code: 'NETWORK' })).toEqual({
      kind: 'ERROR',
      key: 'theme.errors.NETWORK',
    });
    expect(giftOutcome({ ok: false, code: 'SOMETHING_NEW' }).key).toBe('theme.errors.GENERIC');
    expect(giftOutcome(undefined).key).toBe('theme.errors.GENERIC');
  });

  test('every key a gift outcome can produce exists in the error key set', () => {
    for (const reason of [
      'CODE_NOT_FOUND',
      'CODE_EXPIRED',
      'CODE_LIMIT_REACHED',
      'CODE_NOT_STARTED',
      'PRODUCT_UNAVAILABLE',
      'PHYSICAL_NOT_SUPPORTED',
    ])
      expect(ERROR_KEYS).toContain(reason);
    expect(ERROR_KEYS).toContain('CODE_ATTEMPTS_LOCKED');
    expect(ERROR_KEYS).toContain('INVALID_GIFT_CODE');
  });
});

describe('credits load', () => {
  const credits = {
    ok: true,
    balance: 12.5,
    creditName: 'Coins',
    entries: [{ id: 1 }],
    entryCount: 1,
    totalPage: 1,
  };
  const config = {
    ok: true,
    creditTopUp: {
      enabled: true,
      freeAmount: true,
      min: 1,
      max: 500,
      creditValue: 0.1,
      currency: 'EUR',
    },
  };

  test('gate: credits off is a 404, a missing settings answer is an error, top-up follows creditTopUpEnabled', () => {
    expect(creditsGate({ creditsEnabled: false })).toEqual({ state: 'NOT_FOUND' });
    expect(creditsGate(null)).toEqual({ state: 'ERROR' });
    expect(creditsGate({ creditsEnabled: true })).toEqual({ state: 'OK', topUp: false });
    expect(creditsGate({ creditsEnabled: true, creditTopUpEnabled: true })).toEqual({
      state: 'OK',
      topUp: true,
    });
  });

  test('creditsEnabled === false answers not found (also CREDITS_DISABLED from the API)', () => {
    expect(resolveCreditsLoad({ credits, settings: { creditsEnabled: false } })).toEqual({
      notFound: true,
    });
    expect(
      resolveCreditsLoad({
        credits: { ok: false, code: 'CREDITS_DISABLED' },
        settings: { creditsEnabled: true },
      }),
    ).toEqual({ notFound: true });
  });

  test('a guest is redirected', () => {
    expect(
      resolveCreditsLoad({
        credits: { ok: false, code: 'NOT_LOGGED_IN' },
        settings: { creditsEnabled: true },
      }),
    ).toEqual({ redirect: true });
  });

  test('ready without top-up: balance, ledger, no config reading', () => {
    const r = resolveCreditsLoad({
      credits,
      config,
      settings: { creditsEnabled: true, creditTopUpEnabled: false },
      features: { sidebar: true, meta: true },
    });
    expect(r.data).toMatchObject({
      state: 'READY',
      balance: 12.5,
      creditName: 'Coins',
      topUp: null,
      packs: [],
    });
    expect(r.data.ledger).toEqual({ entries: [{ id: 1 }], entryCount: 1, totalPage: 1 });
    expect(r.pageTitle).toEqual({ title: CREDITS_TITLE_KEY });
    expect(r.sidebar).toBe('profile');
    expect(r.meta).toEqual({ robots: 'noindex,nofollow' });
  });

  test('free-amount limits come from checkout/config', () => {
    const r = resolveCreditsLoad({
      credits,
      config,
      packs: { ok: true, products: [{ id: 1 }, { id: 2 }] },
      settings: { creditsEnabled: true, creditTopUpEnabled: true },
    });
    expect(r.data.topUp).toEqual({
      freeAmount: true,
      min: 1,
      max: 500,
      creditValue: 0.1,
      currency: 'EUR',
    });
    expect(r.data.packs).toHaveLength(2);
  });

  test('a failed config hides the top-up card instead of guessing limits', () => {
    const r = resolveCreditsLoad({
      credits,
      config: { ok: false, code: 'NETWORK' },
      packs: { ok: true, products: [{ id: 1 }] },
      settings: { creditsEnabled: true, creditTopUpEnabled: true },
    });
    expect(r.data.topUp).toBeNull();
    // packs are only shown together with an enabled top-up
    expect(r.data.packs).toEqual([]);
  });

  test('top-up disabled in the config block hides it', () => {
    expect(
      readTopUp({
        ok: true,
        creditTopUp: { enabled: false, freeAmount: true, min: 1, max: 5, creditValue: 1 },
      }),
    ).toBeNull();
    expect(readTopUp({ ok: true })).toBeNull();
    expect(readTopUp(null)).toBeNull();
  });

  test('packs only (freeAmount false) still count as a top-up', () => {
    const t = readTopUp({
      ok: true,
      creditTopUp: { enabled: true, freeAmount: false, min: 1, max: 5, creditValue: 1 },
    });
    expect(t.freeAmount).toBe(false);
  });

  test('an unlimited or unusable max is Infinity, an unusable min is one hundredth', () => {
    const t = readTopUp({
      ok: true,
      creditTopUp: { enabled: true, freeAmount: true, min: 0, max: null, creditValue: 'x' },
    });
    expect(t.min).toBe(0.01);
    expect(t.max).toBe(Infinity);
    expect(t.creditValue).toBe(0);
  });

  test('a failed ledger is an error state with the code, never a broken page', () => {
    const r = resolveCreditsLoad({
      credits: { ok: false, code: 'NETWORK' },
      settings: { creditsEnabled: true },
    });
    expect(r.data).toMatchObject({ state: 'ERROR', code: 'NETWORK' });
    const missing = resolveCreditsLoad({ credits, settings: null });
    expect(missing.data).toMatchObject({ state: 'ERROR', code: 'NETWORK' });
  });
});

describe('free-amount top-up (07 §8.2)', () => {
  const topUp = { freeAmount: true, min: 1, max: 500, creditValue: 0.1, currency: 'EUR' };

  test('toCents: plain decimals with at most two places only', () => {
    expect(toCents('12')).toBe(1200);
    expect(toCents('12.5')).toBe(1250);
    expect(toCents('12,5')).toBe(1250);
    expect(toCents('0.07')).toBe(7);
    expect(toCents('1.005')).toBeNull();
    expect(toCents('')).toBeNull();
    expect(toCents('abc')).toBeNull();
    expect(toCents('-3')).toBeNull();
    expect(toCents('1e3')).toBeNull();
    expect(toCents('Infinity')).toBeNull();
    expect(toCents(' 5 ')).toBe(500);
    // no float drift: 0.29 * 100 is 28.999999999999996 in floating point
    expect(toCents('0.29')).toBe(29);
    expect(toCents('4.35')).toBe(435);
  });

  test('range checks with the reasons of the server', () => {
    expect(validateTopUp('10', topUp)).toEqual({ ok: true, amount: 10, cents: 1000 });
    expect(validateTopUp('1', topUp).ok).toBe(true);
    expect(validateTopUp('500', topUp).ok).toBe(true);
    expect(validateTopUp('0.99', topUp)).toEqual({ ok: false, reason: 'BELOW_MINIMUM' });
    expect(validateTopUp('500.01', topUp)).toEqual({ ok: false, reason: 'ABOVE_MAXIMUM' });
    expect(validateTopUp('', topUp)).toEqual({ ok: false, reason: 'NOT_A_NUMBER' });
    expect(validateTopUp('1.234', topUp)).toEqual({ ok: false, reason: 'NOT_A_NUMBER' });
    expect(validateTopUp('x', topUp).reason).toBe('NOT_A_NUMBER');
  });

  test('no top-up or no free amount is TOPUP_DISABLED', () => {
    expect(validateTopUp('10', null).reason).toBe('TOPUP_DISABLED');
    expect(validateTopUp('10', { ...topUp, freeAmount: false }).reason).toBe('TOPUP_DISABLED');
  });

  test('an unlimited max accepts large amounts', () => {
    expect(validateTopUp('999999', { ...topUp, max: Infinity }).ok).toBe(true);
  });

  test('error keys exist (the four reasons) and unknown reasons fall back to the generic code', () => {
    for (const reason of ['BELOW_MINIMUM', 'ABOVE_MAXIMUM', 'NOT_A_NUMBER', 'TOPUP_DISABLED'])
      expect(ERROR_KEYS).toContain(`INVALID_CREDIT_AMOUNT_${reason}`);
    expect(topUpErrorKey('BELOW_MINIMUM')).toBe('theme.errors.INVALID_CREDIT_AMOUNT_BELOW_MINIMUM');
    expect(topUpErrorKey('WHATEVER')).toBe('theme.errors.INVALID_CREDIT_AMOUNT');
  });

  test('live cost: credits x creditValue in hundredths, at least one unit, rounded half up', () => {
    expect(topUpCostCents(1000, 0.1)).toBe(100);
    expect(topUpCostCents(1250, 0.07)).toBe(88);
    expect(topUpCostCents(1, 0.01)).toBe(1);
    expect(topUpCostCents(5, 0.01)).toBe(1);
    expect(topUpCostCents(100, 1.005)).toBe(101);
    expect(topUpCostCents(0, 1)).toBe(0);
    expect(topUpCostCents(100, 0)).toBe(0);
  });

  test('checkout address carries the validated amount', () => {
    expect(topUpHref(1000)).toBe('/store/checkout?topup=10');
    expect(topUpHref(1250)).toBe('/store/checkout?topup=12.5');
    expect(topUpHref(1005)).toBe('/store/checkout?topup=10.05');
    expect(topUpHref(7)).toBe('/store/checkout?topup=0.07');
  });
});

describe('ledger', () => {
  test('signed amount with colour: + success, - danger, 0 plain', () => {
    expect(ledgerAmount(5)).toEqual({ value: 5, sign: '+', className: 'text-success' });
    expect(ledgerAmount(-5)).toEqual({ value: -5, sign: '', className: 'text-danger' });
    expect(ledgerAmount(0)).toEqual({ value: 0, sign: '', className: '' });
    expect(ledgerAmount('x').value).toBe(0);
  });

  test('type key for the known values, raw for an unknown one', () => {
    expect(ledgerType('TOPUP')).toEqual({ key: 'theme.profile.credits.type.TOPUP', raw: null });
    expect(ledgerType('FUTURE')).toEqual({ key: null, raw: 'FUTURE' });
    expect(ledgerType('constructor').key).toBeNull();
    expect(LEDGER_TYPE_KEYS).toHaveLength(15);
  });

  test('row: order link only with an order id, escaped', () => {
    const row = ledgerRow({
      id: 3,
      type: 'GIFT',
      amount: 4.5,
      balanceAfter: 9,
      note: 'hi',
      orderPublicId: 'AbC',
      createdAt: 7,
    });
    expect(row).toMatchObject({
      id: 3,
      balanceAfter: 9,
      note: 'hi',
      orderHref: '/store/order/AbC',
    });
    expect(row.amount.sign).toBe('+');
    expect(ledgerRow({ type: 'GRANT' }).orderHref).toBeNull();
    expect(ledgerRow({ orderPublicId: 'a b' }).orderHref).toBe('/store/order/a%20b');
  });
});

// ---- registration and the summary driven nav -----------------------------------------------------------------

/** A fake host with the slot engine's edit semantics (callback mutates the array, de-duplicated by id, last wins). */
function fakeHost(features = []) {
  const slots = {};
  const calls = { pages: [] };
  const edit = (id) => (cb) => {
    slots[id] ??= [];
    cb(slots[id]);
    const seen = new Map();
    for (const item of slots[id]) seen.set(item.id, item);
    slots[id] = [...seen.values()];
  };

  return {
    slots,
    calls,
    features: { has: (id) => features.includes(id), list: () => features },
    ui: {
      page: { register: (p) => calls.pages.push(p) },
      nav: {
        site: { editNavLinks: () => {} },
        rightComponents: { edit: edit('navbar-right') },
        profileDropdown: { edit: edit('navbar-profile-dropdown') },
      },
      hook: { register: () => {} },
      profile: { nav: { edit: edit('profile-nav') }, content: { edit: edit('profile-content') } },
    },
  };
}

describe('registerTheme: profile items', () => {
  beforeEach(() => {
    profileNav.resetProfileNav();
    session.resetSession();
  });
  afterEach(() => {
    profileNav.resetProfileNav();
    session.resetSession();
    delete globalThis.window;
  });

  test('profile pages use the ProfileLayout system layout', () => {
    const pano = fakeHost(['profile-nav']);
    registerTheme(pano);
    const profile = pano.calls.pages.filter((p) => p.path.startsWith('/profile/'));
    expect(profile.map((p) => p.path)).toEqual([
      '/profile/purchases',
      '/profile/credits',
      '/profile/subscriptions',
      '/profile/creator',
    ]);
    expect(profile.every((p) => p.systemLayout === 'ProfileLayout')).toBe(true);
    expect(profile.every((p) => typeof p.component.load === 'function')).toBe(true);
  });

  test('with has(profile-nav): four link items, only purchases visible; no profile-content block', () => {
    const pano = fakeHost(['profile-nav']);
    registerTheme(pano);
    const items = pano.slots['profile-nav'];
    expect(items.map((i) => i.id)).toEqual([
      'market-purchases',
      'market-credits',
      'market-subscriptions',
      'market-creator',
    ]);
    expect(items.map((i) => !!i.hidden)).toEqual([false, true, true, true]);
    expect(items.map((i) => i.priority)).toEqual([50, 49, 48, 47]);
    // link items in the host's shape, not plugin components
    expect(items.every((i) => !('component' in i) && typeof i.props.href === 'string')).toBe(true);
    expect(pano.slots['profile-content']).toBeUndefined();
  });

  test('without has(profile-nav): MarketProfileBlock in profile-content (priority 50), no nav items', () => {
    const pano = fakeHost([]);
    registerTheme(pano);
    expect(pano.slots['profile-nav']).toBeUndefined();
    expect(pano.slots['profile-content']).toHaveLength(1);
    expect(pano.slots['profile-content'][0]).toMatchObject({ id: 'market', priority: 50 });
    expect(typeof pano.slots['profile-content'][0].component.load).toBe('function');
  });

  test('the account dropdown always gets the purchases entry', () => {
    for (const features of [[], ['profile-nav']]) {
      const pano = fakeHost(features);
      registerTheme(pano);
      expect(pano.slots['navbar-profile-dropdown']).toHaveLength(1);
      expect(pano.slots['navbar-profile-dropdown'][0]).toMatchObject({
        id: 'market-purchases',
        priority: 60,
      });
    }
  });

  test('a theme without the profile namespaces loses only those items (one warning each)', () => {
    const warn = console.warn;
    const seen = [];
    console.warn = (...a) => seen.push(a);
    const pano = fakeHost(['profile-nav']);
    delete pano.ui.profile;
    delete pano.ui.nav.profileDropdown;
    registerTheme(pano);
    console.warn = warn;
    expect(pano.calls.pages.map((p) => p.path)).toContain('/store');
    expect(seen.filter((a) => /profile-(nav|dropdown)/.test(String(a[0])))).toHaveLength(2);
    expect(pano.slots['navbar-right']).toHaveLength(1);
  });

  test('summary after the session is known: credits / subscriptions / creator are revealed with the balance badge', async () => {
    globalThis.window = {};
    const pano = fakeHost(['profile-nav']);
    registerTheme(pano);
    let requests = 0;
    profileNav.setSummaryLoader(async () => {
      requests++;
      return summary();
    });

    const store = writable({ user: { id: 1, username: 'Steve' } });
    session.bindSession(store);
    await Bun.sleep(0);

    expect(requests).toBe(1);
    const items = pano.slots['profile-nav'];
    expect(items).toHaveLength(4);
    expect(items.every((i) => !i.hidden)).toBe(true);
    expect(items.find((i) => i.id === 'market-credits').props.badge).toBe('12.5');
    expect(get(profileNav.profileSummary)).toMatchObject({ isCreator: true });

    // the same user again: no second request
    session.bindSession(store);
    await profileNav.refreshSummary();
    expect(requests).toBe(1);
  });

  test('a failed summary leaves only the purchases link and may be retried', async () => {
    globalThis.window = {};
    const pano = fakeHost(['profile-nav']);
    registerTheme(pano);
    let requests = 0;
    profileNav.setSummaryLoader(async () => {
      requests++;
      return { ok: false, code: 'NETWORK' };
    });

    session.bindSession(writable({ user: { id: 1, username: 'Steve' } }));
    await Bun.sleep(0);

    expect(pano.slots['profile-nav'].map((i) => !!i.hidden)).toEqual([false, true, true, true]);
    expect(get(profileNav.profileSummary)).toBeNull();

    await profileNav.refreshSummary();
    expect(requests).toBe(2);
  });

  test('a guest never asks for the summary', async () => {
    globalThis.window = {};
    const pano = fakeHost(['profile-nav']);
    registerTheme(pano);
    let requests = 0;
    profileNav.setSummaryLoader(async () => {
      requests++;
      return summary();
    });

    session.bindSession(writable({}));
    await Bun.sleep(0);

    expect(requests).toBe(0);
    expect(pano.slots['profile-nav'].map((i) => !!i.hidden)).toEqual([false, true, true, true]);
  });

  test('logout puts the nav back to purchases only and drops the badge', async () => {
    globalThis.window = {};
    const pano = fakeHost(['profile-nav']);
    registerTheme(pano);
    profileNav.setSummaryLoader(async () => summary());

    const store = writable({ user: { id: 1, username: 'Steve' } });
    session.bindSession(store);
    await Bun.sleep(0);
    expect(pano.slots['profile-nav'].every((i) => !i.hidden)).toBe(true);

    store.set({});
    await Bun.sleep(0);

    expect(pano.slots['profile-nav'].map((i) => !!i.hidden)).toEqual([false, true, true, true]);
    expect(
      pano.slots['profile-nav'].find((i) => i.id === 'market-credits').props.badge,
    ).toBeUndefined();
    expect(get(profileNav.profileSummary)).toBeNull();
  });

  test('an answer for a user who has logged out meanwhile is dropped', async () => {
    globalThis.window = {};
    const pano = fakeHost(['profile-nav']);
    registerTheme(pano);
    let release;
    profileNav.setSummaryLoader(
      () =>
        new Promise((resolve) => {
          release = () => resolve(summary());
        }),
    );

    const store = writable({ user: { id: 1, username: 'Steve' } });
    session.bindSession(store);
    await Bun.sleep(0);
    store.set({});
    release();
    await Bun.sleep(0);

    expect(pano.slots['profile-nav'].map((i) => !!i.hidden)).toEqual([false, true, true, true]);
    expect(get(profileNav.profileSummary)).toBeNull();
  });

  test('the server side never requests anything', async () => {
    const pano = fakeHost(['profile-nav']);
    registerTheme(pano);
    let requests = 0;
    profileNav.setSummaryLoader(async () => {
      requests++;
      return summary();
    });
    await profileNav.refreshSummary();
    expect(requests).toBe(0);
  });

  test('host.has gates profile-nav (a host without features has none)', () => {
    host.setPano({});
    expect(host.has('profile-nav')).toBe(false);
    host.setPano(fakeHost(['profile-nav']));
    expect(host.has('profile-nav')).toBe(true);
  });
});

// ---- source pins ----------------------------------------------------------------------------------------------

const FILES = [
  'src/theme/pages/profile/PurchasesPage.svelte',
  'src/theme/pages/profile/CreditsPage.svelte',
  'src/theme/components/profile/MarketProfileBlock.svelte',
  'src/theme/components/profile/OrderStatusBadge.svelte',
  'src/theme/components/profile/GiftRedeemForm.svelte',
  'src/theme/components/profile/LedgerTable.svelte',
  'src/theme/components/profile/TopUpCard.svelte',
  'src/theme/components/profile/ProfilePills.svelte',
];

describe('source rules of the profile files (14 §2)', () => {
  test('runes only, no <style>, no style=, no {@html}, no raw browser globals at module level', () => {
    for (const file of FILES) {
      const src = read(file);
      expect(src, file).not.toMatch(/<style[\s>]/);
      expect(src, file).not.toMatch(/\sstyle\s*=/);
      expect(src, file).not.toContain('{@html');
      expect(src, file).not.toMatch(/export\s+let\s/);
      expect(src, file).not.toMatch(/^\s*\$:\s/m);
      expect(src, file).not.toContain('createEventDispatcher');
      expect(src, file).not.toContain('<slot');
      expect(src, file).not.toMatch(/\bon:[a-z]/);
      expect(src, file).not.toMatch(/\b(bg-white|bg-light|text-dark|text-white)\b/);
    }
  });

  test('SDK imports stay inside the allow-list', () => {
    const allowed = [
      '@panomc/sdk',
      '@panomc/sdk/utils/api',
      '@panomc/sdk/utils/language',
      '@panomc/sdk/toasts',
      '@panomc/sdk/svelte',
      '@panomc/sdk/components/theme',
      '@panomc/sdk/utils/component',
    ];
    for (const file of [
      ...FILES,
      'src/theme/stores/profileNav.js',
      'src/theme/lib/profileModel.js',
    ]) {
      for (const m of read(file).matchAll(/from\s+['"](@panomc\/sdk[^'"]*)['"]/g))
        expect(allowed, `${file}: ${m[1]}`).toContain(m[1]);
    }
    expect(read('src/theme/lib/profileModel.js')).not.toContain('@panomc/sdk');
  });

  test('pages: guest guard with the return URL, sidebar only behind page-sidebar-id, robots behind page-meta', () => {
    for (const file of FILES.slice(0, 2)) {
      const src = read(file);
      expect(src, file).toContain('await event.parent()');
      expect(src, file).toContain('loginUrl(returnTo)');
      expect(src, file).toContain("has('page-sidebar-id')");
      expect(src, file).toContain("has('page-meta')");
      // the pill navigation exists exactly for hosts without page sidebars
      expect(src, file).toContain('ProfilePills');
      expect(src, file).toMatch(/pills = !has\('page-sidebar-id'\)/);
    }
  });

  test('purchases: right endpoints and the gift endpoint', () => {
    const page = read('src/theme/pages/profile/PurchasesPage.svelte');
    expect(page).toContain("'/api/market/me/orders'");
    expect(page).toContain("'/api/market/me/entitlements'");
    expect(read('src/theme/components/profile/GiftRedeemForm.svelte')).toContain(
      "'/api/market/me/gifts/redeem'",
    );
  });

  test('credits: ledger, config for the limits and credit packs', () => {
    const page = read('src/theme/pages/profile/CreditsPage.svelte');
    expect(page).toContain("'/api/market/me/credits'");
    expect(page).toContain("'/api/market/checkout/config'");
    expect(page).toContain("kind: 'CREDIT_PACK'");
    expect(page).toContain('pageSize: 60');
    expect(page).toContain('throw error(404)');
  });

  test('forms: labelled inputs, a submit button, errors announced', () => {
    const gift = read('src/theme/components/profile/GiftRedeemForm.svelte');
    expect(gift).toContain('for="market-gift-code"');
    expect(gift).toContain('maxlength={GIFT_CODE_MAX}');
    expect(gift).toContain('is-invalid');
    expect(gift).toContain('role="alert"');
    const top = read('src/theme/components/profile/TopUpCard.svelte');
    expect(top).toContain('for="market-topup-amount"');
    expect(top).toContain('step="0.01"');
    expect(top).toContain('aria-live="polite"');
    expect(top).toContain('goto(topUpHref(check.cents))');
  });

  test('tables: table-responsive and header scope=col', () => {
    for (const file of [
      'src/theme/pages/profile/PurchasesPage.svelte',
      'src/theme/components/profile/LedgerTable.svelte',
    ]) {
      const src = read(file);
      expect(src, file).toContain('table-responsive');
      expect(src.match(/<th scope="col"/g)?.length, file).toBeGreaterThanOrEqual(5);
    }
  });

  test('the profile block loads the summary in a slot load and renders links, not buttons', () => {
    const block = read('src/theme/components/profile/MarketProfileBlock.svelte');
    expect(block).toContain('export async function load(event)');
    expect(block).toContain('list-group');
    expect(block).toContain('theme.profile.block.title');
    expect(block).not.toContain('<button');
  });
});

describe('locale keys of the profile files', () => {
  const langs = ['en-US', 'tr', 'ru'];
  const literal = /\$_\(\s*(?:'([^']+)'|`([^`$]+)`)/g;

  test('every literal $_() key resolves in all three locales', () => {
    for (const file of FILES) {
      const src = read(file);
      for (const m of src.matchAll(literal)) {
        const key = m[1] ?? m[2];
        for (const lang of langs)
          expect(typeof lookup(locale(lang), key), `${file}: ${key} (${lang})`).toBe('string');
      }
    }
  });

  test('every dynamic family has all of its keys: order statuses, ledger types, nav labels, filters', () => {
    for (const lang of langs) {
      const t = locale(lang).theme;
      for (const status of ORDER_STATUSES)
        expect(typeof t.status.order[status], `${lang} status ${status}`).toBe('string');
      for (const type of LEDGER_TYPE_KEYS)
        expect(typeof t.profile.credits.type[type], `${lang} type ${type}`).toBe('string');
      for (const link of PROFILE_LINKS)
        expect(typeof lookup(t, link.key.replace(/^theme\./, '')), `${lang} ${link.key}`).toBe(
          'string',
        );
      expect(typeof lookup(t, GIFT_NEEDS_OPTIONS_KEY.replace(/^theme\./, ''))).toBe('string');
    }
  });

  test('keys of the three locales are identical under theme.profile and theme.status', () => {
    const flat = (obj, prefix = '') =>
      Object.entries(obj).flatMap(([k, v]) =>
        v && typeof v === 'object' ? flat(v, `${prefix}${k}.`) : [`${prefix}${k}`],
      );
    const sets = langs.map((l) => ({
      profile: flat(locale(l).theme.profile).sort(),
      status: flat(locale(l).theme.status).sort(),
    }));
    expect(sets[1]).toEqual(sets[0]);
    expect(sets[2]).toEqual(sets[0]);
  });

  test('placeholders agree across locales', () => {
    const vars = (s) => [...String(s).matchAll(/\{(\w+)/g)].map((m) => m[1]).sort();
    const en = locale('en-US').theme.profile;
    for (const lang of ['tr', 'ru']) {
      const other = locale(lang).theme.profile;
      for (const [k, v] of Object.entries(en.purchases))
        expect(vars(other.purchases[k]), `${lang} purchases.${k}`).toEqual(vars(v));
      for (const [k, v] of Object.entries(en.credits))
        if (typeof v === 'string')
          expect(vars(other.credits[k]), `${lang} credits.${k}`).toEqual(vars(v));
    }
  });
});
