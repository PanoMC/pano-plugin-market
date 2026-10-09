import { describe, expect, test } from 'bun:test';
import fs from 'node:fs';
import path from 'node:path';
import './sdkMocks.js';
import { ERROR_KEYS } from '../errorMap.js';
import {
  CANCEL_BODY,
  CREATOR_PATH,
  CREATOR_TITLE_KEY,
  EARNING_STATES,
  PAYOUT_METHODS,
  PAYOUT_STATES,
  SUBSCRIPTIONS_TITLE_KEY,
  SUBSCRIPTION_PATH,
  SUBSCRIPTION_STATUSES,
  actionOutcome,
  actionPath,
  codeRow,
  earningBadge,
  earningRow,
  payoutBadge,
  payoutMethod,
  payoutRow,
  readEarnings,
  readSubscriptions,
  replaceSubscription,
  resolveCreatorLoad,
  resolveSubscriptionsLoad,
  subscriptionBadge,
  subscriptionView,
} from '../subscriptionModel.js';

const root = path.resolve(import.meta.dir, '../../../..');
const read = (file) => fs.readFileSync(path.join(root, file), 'utf8');
const locale = (lang) => JSON.parse(read(`src/locales/theme/${lang}.json`));
const lookup = (obj, dotted) => dotted.split('.').reduce((o, k) => (o == null ? o : o[k]), obj);
const LANGS = ['en-US', 'tr', 'ru'];

const row = (over = {}) => ({
  id: 7,
  productName: 'VIP',
  status: 'ACTIVE',
  mode: 'RECURRING',
  price: 9.99,
  currency: 'USD',
  intervalUnit: 'MONTH',
  intervalCount: 1,
  currentPeriodEnd: 1790000000000,
  cancelAtPeriodEnd: false,
  methodLabel: 'Card',
  storedMethodLabel: 'Visa 4242',
  canCancel: true,
  canResume: true,
  canManageAtGateway: false,
  renewalOrderPublicId: null,
  ...over,
});

describe('subscription status badges (14 §12.4)', () => {
  test('the seven statuses and their classes', () => {
    expect(SUBSCRIPTION_STATUSES).toEqual([
      'ACTIVE',
      'PAST_DUE',
      'PAUSED',
      'PENDING',
      'CANCELLED',
      'EXPIRED',
      'COMPLETED',
    ]);
    expect(subscriptionBadge('ACTIVE').className).toBe('text-bg-success');
    expect(subscriptionBadge('PAST_DUE').className).toBe('text-bg-warning');
    expect(subscriptionBadge('PAUSED').className).toBe('text-bg-info');
    for (const s of ['PENDING', 'CANCELLED', 'EXPIRED', 'COMPLETED'])
      expect(subscriptionBadge(s).className).toBe('text-bg-secondary');
    expect(subscriptionBadge('ACTIVE').key).toBe('theme.status.subscription.ACTIVE');
  });

  test('an unknown status is secondary and shown raw; no inherited keys', () => {
    expect(subscriptionBadge('WEIRD')).toEqual({
      className: 'text-bg-secondary',
      key: null,
      raw: 'WEIRD',
    });
    expect(subscriptionBadge('toString').key).toBeNull();
    expect(subscriptionBadge(undefined).raw).toBe('');
  });
});

describe('subscriptionView', () => {
  test('active row: cancel offered, no keep', () => {
    const v = subscriptionView(row());
    expect(v.canCancel).toBe(true);
    expect(v.canKeep).toBe(false);
    expect(v.id).toBe('7');
    expect(v.periodEnd).toBe(1790000000000);
    expect(v.pastDue).toBe(false);
  });

  test('cancel scheduled: keep instead of cancel, even when the server flag says canCancel', () => {
    const v = subscriptionView(row({ cancelAtPeriodEnd: true, canCancel: true }));
    expect(v.canCancel).toBe(false);
    expect(v.canKeep).toBe(true);
  });

  test('keep is not offered when the server says canResume false', () => {
    expect(subscriptionView(row({ cancelAtPeriodEnd: true, canResume: false })).canKeep).toBe(
      false,
    );
  });

  test('the server flag canCancel false hides cancel', () => {
    expect(subscriptionView(row({ canCancel: false })).canCancel).toBe(false);
    expect(subscriptionView(row({ canCancel: 'yes' })).canCancel).toBe(false);
  });

  test('PAST_DUE shows the alert flag; update-method needs the gateway portal', () => {
    expect(subscriptionView(row({ status: 'PAST_DUE' })).pastDue).toBe(true);
    expect(subscriptionView(row({ status: 'PAST_DUE' })).canUpdateMethod).toBe(false);
    expect(
      subscriptionView(row({ status: 'PAST_DUE', canManageAtGateway: true })).canUpdateMethod,
    ).toBe(true);
    expect(subscriptionView(row({ canManageAtGateway: true })).canUpdateMethod).toBe(false);
    expect(subscriptionView(row({ canManageAtGateway: true })).canManage).toBe(true);
  });

  test('renewal order link is built from a plain id only', () => {
    expect(subscriptionView(row({ renewalOrderPublicId: 'abc123' })).renewalHref).toBe(
      '/store/order/abc123',
    );
    expect(subscriptionView(row({ renewalOrderPublicId: 'a/b?c' })).renewalHref).toBe(
      '/store/order/a%2Fb%3Fc',
    );
    expect(subscriptionView(row({ renewalOrderPublicId: '' })).renewalHref).toBeNull();
  });

  test('garbage input does not throw', () => {
    expect(subscriptionView(null).id).toBe('');
    expect(subscriptionView({ currentPeriodEnd: 'x' }).periodEnd).toBeNull();
    expect(subscriptionView({ intervalCount: 0 }).intervalCount).toBe(1);
  });

  test('readSubscriptions: null on a failure, rows otherwise, non-objects dropped', () => {
    expect(readSubscriptions({ ok: false, code: 'NETWORK' })).toBeNull();
    expect(readSubscriptions(undefined)).toBeNull();
    expect(readSubscriptions({ ok: true })).toEqual([]);
    expect(readSubscriptions({ ok: true, items: [row(), 3, null] })).toHaveLength(1);
  });

  test('replaceSubscription swaps the returned row and leaves others alone', () => {
    const list = readSubscriptions({ ok: true, items: [row(), row({ id: 8 })] });
    const next = replaceSubscription(list, row({ cancelAtPeriodEnd: true }));
    expect(next[0].cancelAtPeriodEnd).toBe(true);
    expect(next[1]).toBe(list[1]);
    expect(replaceSubscription(list, row({ id: 99 }))).toEqual(list);
    expect(replaceSubscription(list, null)).toBe(list);
  });
});

describe('requests', () => {
  test('cancel is at period end only; the body cannot be mutated into an immediate cancel', () => {
    expect(CANCEL_BODY).toEqual({ atPeriodEnd: true });
    expect(Object.isFrozen(CANCEL_BODY)).toBe(true);
    expect(actionPath(7, 'cancel')).toBe('/me/subscriptions/7/cancel');
    expect(actionPath('a b', 'portal')).toBe('/me/subscriptions/a%20b/portal');
    expect(SUBSCRIPTION_PATH).toBe('/me/subscriptions');
    expect(CREATOR_PATH).toBe('/me/creator');
  });
});

describe('actionOutcome (cancel, resume, portal)', () => {
  test('a returned subscription replaces the card', () => {
    expect(actionOutcome({ ok: true, subscription: row() })).toEqual({
      kind: 'REPLACE',
      subscription: row(),
    });
  });

  test('{action: REDIRECT} opens a checked http(s) URL', () => {
    expect(
      actionOutcome({ ok: true, action: 'REDIRECT', url: 'https://pay.example.com/x' }),
    ).toEqual({ kind: 'REDIRECT', url: 'https://pay.example.com/x' });
    // the portal answers {url}
    expect(actionOutcome({ ok: true, url: 'https://pay.example.com/portal' }).kind).toBe(
      'REDIRECT',
    );
  });

  test('an unsafe or missing redirect URL is a generic error, never a navigation', () => {
    for (const url of [
      'javascript:alert(1)',
      'data:text/html,x',
      '//evil.com',
      '/relative',
      '',
      undefined,
      5,
    ]) {
      const out = actionOutcome({ ok: true, action: 'REDIRECT', url });
      expect(out.kind, String(url)).toBe('ERROR');
      expect(out.key).toBe('theme.errors.GENERIC');
    }
    expect(actionOutcome({ ok: true, url: 'javascript:alert(1)' }).kind).toBe('ERROR');
  });

  test('an ok answer with neither subscription nor url is an error', () => {
    expect(actionOutcome({ ok: true }).kind).toBe('ERROR');
    expect(actionOutcome({ ok: true, subscription: 'x' }).kind).toBe('ERROR');
    expect(actionOutcome(undefined).kind).toBe('ERROR');
  });

  test('a 409 reloads the list with a toast', () => {
    expect(actionOutcome({ ok: false, code: 'SUBSCRIPTION_NOT_CANCELLABLE' })).toEqual({
      kind: 'RELOAD',
      key: 'theme.errors.SUBSCRIPTION_NOT_CANCELLABLE',
    });
    expect(actionOutcome({ ok: false, code: 'SUBSCRIPTION_NOT_RESUMABLE' }).kind).toBe('RELOAD');
  });

  test('provider and unknown errors are texts from the error map', () => {
    expect(actionOutcome({ ok: false, code: 'PAYMENT_PROVIDER_ERROR' })).toEqual({
      kind: 'ERROR',
      key: 'theme.errors.PAYMENT_PROVIDER_ERROR',
    });
    expect(actionOutcome({ ok: false, code: 'SUBSCRIPTION_NOT_MANAGEABLE' }).key).toBe(
      'theme.errors.SUBSCRIPTION_NOT_MANAGEABLE',
    );
    expect(actionOutcome({ ok: false, code: 'NETWORK' }).key).toBe('theme.errors.NETWORK');
    expect(actionOutcome({ ok: false, code: 'SOMETHING_NEW' }).key).toBe('theme.errors.GENERIC');
  });
});

describe('resolveSubscriptionsLoad', () => {
  test('guest => redirect', () => {
    expect(
      resolveSubscriptionsLoad({ subscriptions: { ok: false, code: 'NOT_LOGGED_IN' } }),
    ).toEqual({ redirect: true });
  });

  test('rows, title and the page extras behind the feature flags', () => {
    const r = resolveSubscriptionsLoad({
      subscriptions: { ok: true, items: [row()] },
      features: { sidebar: true, meta: true },
    });
    expect(r.data.state).toBe('READY');
    expect(r.data.subscriptions).toHaveLength(1);
    expect(r.pageTitle).toEqual({ title: SUBSCRIPTIONS_TITLE_KEY });
    expect(r.sidebar).toBe('profile');
    expect(r.meta).toEqual({ robots: 'noindex,nofollow' });

    const plain = resolveSubscriptionsLoad({ subscriptions: { ok: true, items: [] } });
    expect('sidebar' in plain).toBe(false);
    expect('meta' in plain).toBe(false);
  });

  test('a failed read is an ERROR state with the code', () => {
    const r = resolveSubscriptionsLoad({ subscriptions: { ok: false, code: 'NETWORK' } });
    expect(r.data).toMatchObject({ state: 'ERROR', code: 'NETWORK' });
  });
});

describe('creator rows and badges (14 §12.5)', () => {
  test('earning, payout and method vocab', () => {
    expect(EARNING_STATES).toEqual(['PENDING', 'AVAILABLE', 'PAID', 'REVERSED']);
    expect(PAYOUT_STATES).toEqual(['PENDING', 'PAID', 'FAILED', 'CANCELLED']);
    expect(PAYOUT_METHODS).toEqual(['CREDIT', 'ACTION', 'MANUAL']);
    expect(earningBadge('PAID').key).toBe('theme.profile.creator.state.PAID');
    expect(earningBadge('???')).toEqual({ className: 'text-bg-secondary', key: null, raw: '???' });
    expect(payoutBadge('FAILED').className).toBe('text-bg-danger');
    expect(payoutMethod('CREDIT').key).toBe('theme.profile.creator.method.CREDIT');
    expect(payoutMethod('X')).toEqual({ key: null, raw: 'X' });
  });

  test('codeRow: percent vs money discount', () => {
    expect(
      codeRow({
        code: 'ABC',
        discount: 10,
        unit: 'PERCENT',
        commissionPercent: 5,
        usedCount: 3,
        status: 'ACTIVE',
      }),
    ).toMatchObject({
      code: 'ABC',
      discount: { percent: 10 },
      commissionPercent: 5,
      usedCount: 3,
    });
    expect(codeRow({ code: 'X', discount: 2.5, unit: 'FIXED' }).discount).toEqual({ money: 2.5 });
    expect(codeRow(null).code).toBe('');
  });

  test('earningRow and payoutRow', () => {
    expect(
      earningRow({ orderNumber: 12, amount: 3, state: 'PENDING', createdAt: 5 }),
    ).toMatchObject({
      orderNumber: 12,
      amount: 3,
      createdAt: 5,
    });
    expect(payoutRow({ amount: 4, method: 'MANUAL', state: 'PAID', paidAt: 9 }).paidAt).toBe(9);
    expect(payoutRow({ amount: 4, method: 'MANUAL', state: 'PENDING' }).paidAt).toBeNull();
  });

  test('readEarnings clamps the page count', () => {
    expect(readEarnings(null)).toEqual({ earnings: [], earningCount: 0, totalPages: 1 });
    expect(
      readEarnings({
        items: [{ orderNumber: 1 }, 5],
        page: { number: 1, size: 10, totalItems: 2, totalPages: 0 },
      }).totalPages,
    ).toBe(1);
  });

  test('readEarnings reads the page object and ignores the array under its old name', () => {
    const page = { number: 2, size: 10, totalItems: 11, totalPages: 2 };
    const row = { orderNumber: 1, amount: 1, state: 'AVAILABLE', createdAt: 1 };

    expect(readEarnings({ items: [row], page })).toMatchObject({ earningCount: 11, totalPages: 2 });
    expect(readEarnings({ items: [row], page }).earnings).toMatchObject([{ orderNumber: 1 }]);
    expect(readEarnings({ earnings: [row], page }).earnings).toEqual([]);
  });
});

describe('resolveCreatorLoad', () => {
  const creator = (over = {}) => ({
    ok: true,
    codes: [
      {
        code: 'ABC',
        discount: 10,
        unit: 'PERCENT',
        commissionPercent: 5,
        usedCount: 1,
        status: 'ACTIVE',
      },
    ],
    totals: { earned: 12.5, paidOut: 5, available: 7.5, currency: 'USD' },
    items: [{ orderNumber: 3, amount: 2, state: 'AVAILABLE', createdAt: 1 }],
    page: { number: 1, size: 10, totalItems: 1, totalPages: 1 },
    payouts: [{ amount: 5, method: 'CREDIT', state: 'PAID', paidAt: 2 }],
    ...over,
  });

  test('NOT_FOUND (a non-creator) answers notFound, never an empty page', () => {
    expect(resolveCreatorLoad({ creator: { ok: false, code: 'NOT_FOUND' } })).toEqual({
      notFound: true,
    });
  });

  test('guest => redirect', () => {
    expect(resolveCreatorLoad({ creator: { ok: false, code: 'NOT_LOGGED_IN' } })).toEqual({
      redirect: true,
    });
  });

  test('ready data: totals, codes, earnings, payouts and title', () => {
    const r = resolveCreatorLoad({ creator: creator(), page: 2, features: { meta: true } });
    expect(r.data.state).toBe('READY');
    expect(r.data.page).toBe(2);
    expect(r.data.totals).toEqual({ earned: 12.5, paidOut: 5, available: 7.5, currency: 'USD' });
    expect(r.data.codes).toHaveLength(1);
    expect(r.data.earnings).toHaveLength(1);
    expect(r.data.payouts).toHaveLength(1);
    expect(r.pageTitle).toEqual({ title: CREATOR_TITLE_KEY });
    expect(r.meta).toEqual({ robots: 'noindex,nofollow' });
    expect('sidebar' in r).toBe(false);
  });

  test('missing totals become zero, not NaN', () => {
    const r = resolveCreatorLoad({ creator: creator({ totals: { earned: 'x' } }) });
    expect(r.data.totals).toEqual({ earned: 0, paidOut: 0, available: 0, currency: '' });
  });

  test('other failures are an ERROR state', () => {
    expect(resolveCreatorLoad({ creator: { ok: false, code: 'NETWORK' } }).data).toMatchObject({
      state: 'ERROR',
      code: 'NETWORK',
    });
    expect(resolveCreatorLoad({}).data.state).toBe('ERROR');
  });
});

describe('locales', () => {
  test('every key the model and the components use exists in tr, en-US and ru', () => {
    const keys = [
      ...SUBSCRIPTION_STATUSES.map((s) => `theme.status.subscription.${s}`),
      ...EARNING_STATES.map((s) => `theme.profile.creator.state.${s}`),
      ...PAYOUT_STATES.map((s) => `theme.profile.creator.payout-state.${s}`),
      ...PAYOUT_METHODS.map((s) => `theme.profile.creator.method.${s}`),
      'theme.profile.creator.code-status.ACTIVE',
      'theme.profile.creator.code-status.INACTIVE',
      ...ERROR_KEYS.filter(
        (k) => k.startsWith('SUBSCRIPTION_') || k === 'PAYMENT_PROVIDER_ERROR',
      ).map((k) => `theme.errors.${k}`),
    ];

    for (const file of [
      'src/theme/pages/profile/SubscriptionsPage.svelte',
      'src/theme/pages/profile/CreatorPage.svelte',
      'src/theme/components/profile/SubscriptionCard.svelte',
    ])
      for (const m of read(file).matchAll(/\$_\(\s*'(theme\.[a-z0-9.-]+)'/gi)) keys.push(m[1]);

    for (const lang of LANGS) {
      const l = locale(lang);
      for (const key of keys) {
        const value = lookup({ theme: l.theme }, key);
        expect(typeof value, `${lang}: ${key}`).toBe('string');
        expect(value.length, `${lang}: ${key}`).toBeGreaterThan(0);
      }
    }
  });

  test('subscriptions and creator subtrees have identical key sets', () => {
    const flat = (o, p = '') =>
      Object.entries(o).flatMap(([k, v]) =>
        v && typeof v === 'object' ? flat(v, `${p}${k}.`) : [`${p}${k}`],
      );
    const pick = (lang) => [
      ...flat(locale(lang).theme.profile.subscriptions),
      ...flat(locale(lang).theme.profile.creator),
      ...flat(locale(lang).theme.status.subscription),
    ];
    const base = pick('en-US').sort();
    expect(pick('tr').sort()).toEqual(base);
    expect(pick('ru').sort()).toEqual(base);
  });

  test('the cancel confirmation carries the end date placeholder in every language', () => {
    for (const lang of LANGS)
      expect(locale(lang).theme.profile.subscriptions['cancel-confirm']).toContain('{date}');
  });
});

describe('source rules of the subscription and creator files (14 §2)', () => {
  const FILES = [
    'src/theme/pages/profile/SubscriptionsPage.svelte',
    'src/theme/pages/profile/CreatorPage.svelte',
    'src/theme/components/profile/SubscriptionCard.svelte',
  ];

  test('runes only, no <style>, no style=, no {@html}', () => {
    for (const file of FILES) {
      const src = read(file);
      expect(src, file).not.toMatch(/<style[\s>]/);
      expect(src, file).not.toMatch(/\sstyle\s*=/);
      expect(src, file).not.toContain('{@html');
      expect(src, file).not.toMatch(/export\s+let\s/);
      expect(src, file).not.toMatch(/^\s*\$:\s/m);
      expect(src, file).not.toMatch(/\bon:[a-z]/);
      expect(src, file).not.toMatch(/\b(bg-white|bg-light|text-dark|text-white)\b/);
    }
    expect(read('src/theme/lib/subscriptionModel.js')).not.toContain('@panomc/sdk');
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
      '@panomc/sdk/controllers',
    ];
    for (const file of FILES)
      for (const m of read(file).matchAll(/from\s+['"](@panomc\/sdk[^'"]*)['"]/g))
        expect(allowed, `${file}: ${m[1]}`).toContain(m[1]);
  });

  test('pages: guest guard with the return URL, sidebar flag, not-found for a non-creator', () => {
    for (const file of FILES.slice(0, 2)) {
      const src = read(file);
      expect(src, file).toContain('await event.parent()');
      expect(src, file).toContain('loginUrl(returnTo)');
      expect(src, file).toContain("has('page-sidebar-id')");
      expect(src, file).toContain("has('page-meta')");
      expect(src, file).toContain('ProfilePills');
    }
    expect(read(FILES[1])).toContain('throw error(404)');
    expect(read(FILES[1])).toContain('<CopyButton');
  });

  test('the card: cancel at period end through ConfirmModal, resume, portal, checked redirect', () => {
    const card = read(FILES[2]);
    expect(card).toContain('<ConfirmModal');
    expect(card).toContain("run('cancel', { body: { ...CANCEL_BODY } })");
    expect(card).toContain("run('resume', { body: {} })");
    expect(card).toContain("run('portal'");
    expect(card).toContain('actionOutcome(res)');
    expect(card).toContain('window.location.assign(outcome.url)');
    expect(card).toContain('alert alert-warning py-2');
    expect(card).toContain('alert alert-danger py-2');
    // no immediate-cancel control exists
    expect(card).not.toMatch(/atPeriodEnd:\s*false/);
    // every window.location.assign goes through the outcome (a checked URL)
    expect(card.match(/location\.assign\(/g)).toHaveLength(1);
  });

  test('tables: table-responsive and header scope=col; stat cards use col-md-4', () => {
    const src = read(FILES[1]);
    expect(src.match(/table-responsive/g)?.length).toBeGreaterThanOrEqual(2);
    expect(src).toContain('<th scope="col"');
    expect(src).toContain('col-md-4');
    expect(src).toContain('list-group');
  });
});

describe('registration', () => {
  test('both pages are registered with ProfileLayout (view metadata of the page files)', () => {
    for (const [file, route] of [
      ['SubscriptionsPage', '/profile/subscriptions'],
      ['CreatorPage', '/profile/creator'],
    ]) {
      expect(read(`src/theme/pages/profile/${file}.svelte`)).toContain(
        `export const view = { path: '${route}', systemLayout: 'ProfileLayout' };`,
      );
    }
    expect(read('src/theme/register.js')).not.toContain('systemLayout');
  });
});
