import { describe, expect, test } from 'bun:test';
import { createRouter } from '../core.js';
import { VOLUMES } from '../kit.js';
import { orderRows } from './world.js';
import { pages, routes, serverRows } from './panel-details.js';

const router = createRouter(routes);
const API = '/api/panel/market';
const get = (path, volume = 'many') => router.answer('GET', API + path, volume);
const keys = (value) => Object.keys(value).sort();

/** [path, row key, count key, [filter query, predicate] ...] of every paged list. */
const LISTS = [
  [
    '/credits/accounts',
    'accounts',
    'accountCount',
    ['search=steve', (r) => /steve/i.test(r.username)],
  ],
  ['/credits/accounts/1', 'entries', 'entryCount'],
  [
    '/credits/transactions',
    'transactions',
    'transactionCount',
    ['type=TOPUP', (r) => r.type === 'TOPUP'],
    ['userId=2', (r) => r.userId === 2],
  ],
  [
    '/blocks',
    'blocks',
    'blockCount',
    ['type=IP', (r) => r.type === 'IP'],
    ['source=CHARGEBACK', (r) => r.source === 'CHARGEBACK'],
    ['search=203.', (r) => r.value.startsWith('203.')],
  ],
  [
    '/discounts',
    'discounts',
    'discountCount',
    ['status=INACTIVE', (r) => r.status === 'INACTIVE'],
    ['search=sale', (r) => /sale/i.test(r.name)],
  ],
  [
    '/coupons',
    'coupons',
    'couponCount',
    ['status=ACTIVE', (r) => r.status === 'ACTIVE'],
    ['search=welcome', (r) => /welcome/i.test(r.code + r.name)],
  ],
  [
    '/creator-codes',
    'creatorCodes',
    'creatorCodeCount',
    ['status=ACTIVE', (r) => r.status === 'ACTIVE'],
    ['search=dream', (r) => /dream/i.test(r.code + r.creator)],
  ],
  [
    '/gifts',
    'gifts',
    'giftCount',
    ['status=ACTIVE', (r) => r.status === 'ACTIVE'],
    ['search=halloween', (r) => /halloween/i.test(r.code + r.name)],
  ],
  [
    '/comparisons',
    'comparisons',
    'comparisonCount',
    ['status=ACTIVE', (r) => r.status === 'ACTIVE'],
    ['search=rank', (r) => /rank/i.test(r.name)],
  ],
];

describe('paged lists', () => {
  for (const [path, key, countKey, ...filters] of LISTS) {
    test(`${path}: shape, default page size 10, pagination`, () => {
      const body = get(path);
      expect(body.result).toBe('ok');
      expect(Array.isArray(body[key])).toBe(true);
      expect(body[key].length).toBeLessThanOrEqual(10);
      expect(body[countKey]).toBeGreaterThan(0);
      expect(body.totalPage).toBe(Math.max(1, Math.ceil(body[countKey] / 10)));

      const all = get(`${path}?pageSize=100`)[key];
      const small = get(`${path}?pageSize=3&page=2`);
      if (all.length > 3) expect(small[key]).toEqual(all.slice(3, 6));
      expect(get(`${path}?page=9999`)).toEqual({ result: 'error', error: 'PAGE_NOT_FOUND' });
    });

    for (const [query, predicate] of filters) {
      test(`${path}?${query} filters`, () => {
        const all = get(`${path}?pageSize=100`);
        const filtered = get(`${path}?${query}&pageSize=100`);
        expect(filtered.result).toBe('ok');
        expect(filtered[key].every(predicate)).toBe(true);
        expect(filtered[countKey]).toBe(filtered[key].length);
        expect(filtered[countKey]).toBeLessThan(all[countKey]);
        expect(filtered[countKey]).toBeGreaterThan(0);
      });
    }

    test(`${path}: empty volume`, () => {
      const body = get(path, 'empty');
      expect(body).toMatchObject({ result: 'ok', [key]: [], [countKey]: 0, totalPage: 0 });
    });
  }
});

describe('row shapes', () => {
  test('credits', () => {
    const accounts = get('/credits/accounts');
    expect(keys(accounts.accounts[0])).toEqual(['balance', 'userId', 'username']);
    expect(keys(accounts.totals)).toEqual([
      'external',
      'held',
      'issued',
      'outstanding',
      'revoked',
      'spent',
    ]);
    const account = get('/credits/accounts/1');
    expect(account.balance).toBe(account.entries[0].balanceAfter);
    expect(keys(account.entries[0])).toEqual([
      'actorUsername',
      'amount',
      'balanceAfter',
      'createdAt',
      'deliveryId',
      'id',
      'note',
      'orderId',
      'refundId',
      'shortfall',
      'type',
    ]);
    expect(get('/credits/accounts/1?pageSize=100').entries.every((e) => e.balanceAfter >= 0)).toBe(
      true,
    );
    expect(keys(get('/credits/transactions').transactions[0])).toEqual([
      'actorUsername',
      'amount',
      'createdAt',
      'deliveryId',
      'id',
      'note',
      'orderId',
      'refundId',
      'shortfall',
      'type',
      'userId',
      'username',
    ]);
    expect(get('/credits/accounts/999')).toEqual({ result: 'error', error: 'NOT_FOUND' });
  });

  test('blocks', () => {
    expect(keys(get('/blocks').blocks[0])).toEqual([
      'createdAt',
      'createdBy',
      'createdByUsername',
      'expiresAt',
      'hitCount',
      'id',
      'lastHitAt',
      'orderId',
      'reason',
      'source',
      'type',
      'value',
    ]);
  });

  test('promotions', () => {
    expect(keys(get('/discounts').discounts[0])).toEqual([
      'categoryIds',
      'createdAt',
      'expiryDate',
      'id',
      'minPaymentAmount',
      'name',
      'productIds',
      'products',
      'scope',
      'showBadge',
      'startDate',
      'status',
      'unit',
      'updatedAt',
      'usageLimit',
      'usedCount',
      'value',
    ]);
    expect(keys(get('/coupons').coupons[0])).toEqual([
      'categoryIds',
      'code',
      'createdAt',
      'customerRedeemLimit',
      'discount',
      'expiryDate',
      'id',
      'minPaymentAmount',
      'name',
      'productIds',
      'redeemLimit',
      'scope',
      'startDate',
      'status',
      'unit',
      'updatedAt',
      'usedCount',
    ]);
    expect(keys(get('/creator-codes').creatorCodes[0])).toEqual([
      'code',
      'commissionPercent',
      'createdAt',
      'creator',
      'creatorUserId',
      'discount',
      'earnings',
      'expiryDate',
      'id',
      'paidOut',
      'redeemLimit',
      'startDate',
      'status',
      'unit',
      'updatedAt',
      'usedCount',
    ]);
    expect(keys(get('/gifts').gifts[0])).toEqual([
      'code',
      'createdAt',
      'creditAmount',
      'customerRedeemLimit',
      'expiryDate',
      'id',
      'name',
      'productId',
      'productIds',
      'productName',
      'productNames',
      'redeemLimit',
      'startDate',
      'status',
      'type',
      'updatedAt',
      'usedCount',
    ]);
  });

  test('redemptions of coupons, gifts and creator codes', () => {
    for (const [path, key] of [
      ['/coupons', 'coupons'],
      ['/gifts', 'gifts'],
      ['/creator-codes', 'creatorCodes'],
    ]) {
      const row = get(`${path}?pageSize=100`)[key].find((r) => r.usedCount > 0);
      const body = get(`${path}/${row.id}/redemptions`);
      expect(body.result).toBe('ok');
      expect(body.redemptionCount).toBeGreaterThan(0);
      expect(keys(body.redemptions[0])).toEqual([
        'amount',
        'createdAt',
        'currency',
        'orderId',
        'playerUsername',
        'state',
      ]);
      const ids = get(`${path}/${row.id}/redemptions?pageSize=100`).redemptions.map(
        (r) => r.orderId,
      );
      expect(new Set(ids).size).toBe(ids.length);
      expect(get(`${path}/99999/redemptions`)).toEqual({ result: 'error', error: 'NOT_FOUND' });
    }
  });

  test('creator detail: report, earnings (state filter), payouts', () => {
    const report = get('/creator-codes/report');
    expect(report.currency).toBe('USD');
    expect(keys(report.creators[0])).toEqual([
      'available',
      'code',
      'creator',
      'earned',
      'id',
      'paidOut',
      'pending',
      'revenue',
      'reversed',
      'uses',
    ]);
    expect(report.creators.map((c) => c.id).sort()).toEqual(
      get('/creator-codes?pageSize=100')
        .creatorCodes.map((c) => c.id)
        .sort(),
    );
    expect(report.creators.every((c) => c.available >= 0)).toBe(true);

    const code = get('/creator-codes?pageSize=100').creatorCodes.find((c) => c.usedCount > 20);
    const earnings = get(`/creator-codes/${code.id}/earnings`);
    expect(keys(earnings.earnings[0])).toEqual([
      'amount',
      'availableAt',
      'baseAmount',
      'commissionPercent',
      'createdAt',
      'id',
      'orderId',
      'reversedAmount',
      'state',
    ]);
    const paid = get(`/creator-codes/${code.id}/earnings?state=PAID&pageSize=100`);
    expect(paid.earnings.length).toBeGreaterThan(0);
    expect(paid.earnings.every((e) => e.state === 'PAID')).toBe(true);
    const payouts = get(`/creator-codes/${code.id}/payouts`);
    expect(keys(payouts.payouts[0])).toEqual([
      'amount',
      'createdAt',
      'currency',
      'id',
      'method',
      'note',
      'paidAt',
      'paidBy',
      'state',
    ]);
    expect(get('/creator-codes/99999/earnings')).toEqual({ result: 'error', error: 'NOT_FOUND' });
    expect(get('/creator-codes/99999/payouts')).toEqual({ result: 'error', error: 'NOT_FOUND' });
    expect(get('/creator-codes/report', 'empty')).toEqual({
      result: 'ok',
      creators: [],
      currency: 'USD',
    });
  });

  test('comparisons: list row and detail', () => {
    const list = get('/comparisons');
    expect(keys(list.comparisons[0])).toEqual([
      'createdAt',
      'id',
      'name',
      'priority',
      'products',
      'status',
      'updatedAt',
    ]);
    const detail = get(`/comparisons/${list.comparisons[0].id}`);
    expect(keys(detail)).toEqual([
      'cellValues',
      'features',
      'id',
      'name',
      'priority',
      'result',
      'selectedProducts',
      'status',
    ]);
    expect(keys(detail.features[0])).toEqual(['id', 'name']);
    const pid = detail.selectedProducts.find((id) => id !== null);
    expect(detail.cellValues[`${detail.features[0].id}-${pid}`]).toBeDefined();
    expect(get('/comparisons/99999')).toEqual({ result: 'error', error: 'NOT_FOUND' });
  });

  test('goals', () => {
    const body = get('/goals');
    expect(keys(body)).toEqual(['goals', 'result']);
    expect(keys(body.goals[0])).toEqual([
      'completedAt',
      'createdAt',
      'currency',
      'description',
      'endsAt',
      'id',
      'metric',
      'name',
      'percent',
      'period',
      'periodStart',
      'position',
      'productIds',
      'progress',
      'showOnStore',
      'startsAt',
      'status',
      'target',
      'updatedAt',
    ]);
    expect(
      body.goals.every((g) => g.percent >= 0 && g.percent <= 100 && g.progress <= g.target),
    ).toBe(true);
    expect(get('/goals', 'empty')).toEqual({ result: 'ok', goals: [] });
  });
});

describe('overview', () => {
  test('stats', () => {
    const body = get('/stats', 'few');
    expect(keys(body)).toEqual([
      'charts',
      'result',
      'statsCurrency',
      'statsCurrencySymbol',
      'summary',
    ]);
    expect(keys(body.summary)).toEqual([
      'activeSubscriptions',
      'monthly',
      'refunds',
      'total',
      'weekly',
    ]);
    expect(keys(body.summary.weekly)).toEqual(['count', 'previous', 'revenue', 'spark', 'trend']);
    expect(body.summary.weekly.spark).toHaveLength(7);
    expect(body.summary.monthly.spark).toHaveLength(30);
    expect(keys(body.summary.refunds)).toEqual(['amount', 'count']);
    expect(keys(body.charts)).toEqual([
      'currencies',
      'monthlyRevenue',
      'paymentMethods',
      'topProducts',
      'weeklyRevenue',
    ]);
    expect(body.charts.weeklyRevenue.labels).toHaveLength(8);
    expect(body.charts.monthlyRevenue.labels).toEqual([
      '2026-05',
      '2026-06',
      '2026-07',
      '2026-08',
      '2026-09',
      '2026-10',
    ]);
    for (const chart of Object.values(body.charts))
      expect(chart.labels.length).toBe(chart.values.length);
    expect(body.charts.weeklyRevenue.labels.at(-1)).toBe('202640');
  });

  test('stats: from / to bound the total', () => {
    const all = get('/stats');
    const week = get(`/stats?from=${Date.UTC(2026, 8, 24, 12)}&to=${Date.UTC(2026, 9, 1, 12)}`);
    expect(week.summary.total.revenue).toBeLessThan(all.summary.total.revenue);
    expect(week.summary.total.revenue).toBeGreaterThan(0);
    expect(week.summary.weekly).toEqual(all.summary.weekly);
  });

  test('stats: empty volume is all zero', () => {
    const body = get('/stats', 'empty');
    expect(body.summary.total).toMatchObject({ count: 0, revenue: 0 });
    expect(body.summary.weekly).toMatchObject({ count: 0, revenue: 0, trend: 0 });
    expect(body.summary.activeSubscriptions).toBe(0);
    expect(body.charts.topProducts).toEqual({ labels: [], values: [] });
  });

  test('servers and health', () => {
    const servers = { servers: serverRows('many') };
    expect(keys(servers.servers[0])).toEqual([
      'connected',
      'downloadUrl',
      'id',
      'integrations',
      'marketState',
      'mcComponentVersion',
      'name',
      'platform',
      'proxy',
      'queuedDeliveries',
      'requiredVersion',
      'settings',
      'type',
      'waitingDeliveries',
    ]);
    expect(servers.servers.some((s) => s.marketState !== 'READY' && s.waitingDeliveries > 0)).toBe(
      true,
    );
    expect(serverRows('empty')).toEqual([]);

    const health = get('/health');
    expect(keys(health)).toEqual([
      'bootstrapErrors',
      'credits',
      'ipTrust',
      'jobs',
      'lockedSubjects',
      'mail',
      'mailEnabled',
      'providers',
      'queues',
      'rejectedEventsLastHour',
      'result',
      'routes',
      'runtimeState',
      'schema',
      'servers',
    ]);
    expect(keys(health.queues)).toEqual([
      'deferredEvents',
      'deliveriesFailed',
      'deliveriesPending',
      'failedEvents',
      'mailsPending',
      'webhooksPending',
    ]);
    expect(keys(health.jobs[0])).toEqual(['lagSeconds', 'lastError', 'lastRunAt', 'name']);
    expect(keys(health.servers[0])).toEqual(['id', 'marketState', 'waitingDeliveries']);
    expect(get('/health', 'empty').queues.deliveriesFailed).toBe(0);
  });
});

describe('order detail', () => {
  const DETAIL_KEYS = [
    'allowed',
    'deliveries',
    'disputes',
    'events',
    'invoices',
    'items',
    'mails',
    'order',
    'payments',
    'refunds',
    'result',
    'revokeFailed',
    'revokePending',
    'shipments',
    'subscription',
  ];

  test('every order of the list has a consistent detail', () => {
    for (const volume of ['few', 'many']) {
      for (const row of orderRows(volume)) {
        const body = get(`/orders/${row.id}`, volume);
        expect(keys(body)).toEqual(DETAIL_KEYS);
        const { order, items } = body;
        for (const key of [
          'id',
          'publicId',
          'playerUsername',
          'status',
          'currency',
          'totalPrice',
          'gatewayAmount',
          'creditValue',
          'refundedTotal',
          'paymentLabel',
          'paidAt',
          'createdAt',
          'fulfillmentStatus',
          'shippingStatus',
          'testMode',
          'email',
          'source',
          'isGift',
        ]) {
          expect(order[key]).toEqual(row[key]);
        }
        expect(items.map((i) => [i.id, i.productName, i.quantity, i.lineTotal])).toEqual(
          row.items.map((i) => [i.id, i.productName, i.quantity, i.lineTotal]),
        );
        expect(body.payments.at(-1).id).toBe(order.paymentId);
        expect(body.events.at(-1).type).toBe('CREATED');
        for (let i = 1; i < body.events.length; i++)
          expect(body.events[i - 1].createdAt).toBeGreaterThanOrEqual(body.events[i].createdAt);
        expect(new Set(body.events.map((e) => e.id)).size).toBe(body.events.length);
        expect(new Set(body.deliveries.map((d) => d.id)).size).toBe(body.deliveries.length);
        if (order.paidAt === null) expect(body.deliveries).toEqual([]);
        if (order.refundedTotal > 0) expect(body.refunds[0].amount).toBe(order.refundedTotal);
        expect(body.allowed.refund).toBe(body.allowed.refundMax > 0);
        expect(order.requiresShipping).toBe(order.shippingStatus !== 'NOT_REQUIRED');
      }
    }
  });

  test('row shapes', () => {
    const rows = orderRows('many');
    const details = rows.map((row) => get(`/orders/${row.id}`));
    const first = (key) => details.find((d) => d[key].length)[key][0];
    expect(keys(first('payments'))).toEqual([
      'adminMessage',
      'amount',
      'createdAt',
      'creditAmount',
      'duplicate',
      'failureMessage',
      'gatewayTransactionId',
      'id',
      'paidAmount',
      'paidAt',
      'providerId',
      'status',
      'testMode',
    ]);
    expect(keys(first('events'))).toEqual([
      'actorType',
      'actorUsername',
      'createdAt',
      'fromStatus',
      'id',
      'message',
      'toStatus',
      'type',
    ]);
    expect(keys(first('mails'))).toEqual([
      'attempts',
      'createdAt',
      'id',
      'kind',
      'lastError',
      'recipient',
      'sentAt',
      'status',
    ]);
    expect(keys(first('invoices'))).toEqual(['id', 'issuedAt', 'number', 'refundId', 'type']);
    expect(keys(first('refunds'))).toContain('initiatedByUsername');
    expect(keys(first('disputes'))).toEqual([
      'amount',
      'currency',
      'id',
      'openedAt',
      'origin',
      'reason',
      'resolvedAt',
      'status',
    ]);
    expect(keys(first('deliveries'))).toEqual([
      'actionId',
      'actionType',
      'attempts',
      'cancelRequested',
      'confirmedAt',
      'id',
      'idempotencyKey',
      'lastError',
      'lastErrorCode',
      'orderId',
      'orderItemId',
      'payload',
      'phase',
      'playerUsername',
      'productName',
      'requiresOnline',
      'result',
      'runAfter',
      'sentAt',
      'serverId',
      'serverName',
      'status',
      'transport',
      'waitUntil',
    ]);
    expect(keys(first('items'))).toContain('snapshot');
    expect(keys(first('items'))).toContain('expiresAt');
    expect(keys(details[0].allowed)).toEqual([
      'anonymize',
      'bankTransfer',
      'cancel',
      'createShipment',
      'dispute',
      'editShippingAddress',
      'markPaid',
      'refund',
      'refundMax',
      'refundModes',
      'rerunDelivery',
      'resendMail',
      'review',
      'revoke',
      'runChargebackActions',
    ]);
    const statuses = new Set(details.map((d) => d.order.status));
    expect(statuses.size).toBeGreaterThan(5);
    expect(details.some((d) => d.subscription)).toBe(true);
  });

  test('unknown and foreign ids', () => {
    expect(get('/orders/1')).toEqual({ result: 'error', error: 'NOT_FOUND' });
    expect(get('/orders/1001', 'empty')).toEqual({ result: 'error', error: 'NOT_FOUND' });
    expect(get('/orders/export')).toBeUndefined();
  });

  test('payment events of an attempt', () => {
    const detail = get(`/orders/${orderRows('few')[0].id}`, 'few');
    const body = get(`/payments/${detail.order.paymentId}/events`, 'few');
    expect(body.result).toBe('ok');
    expect(body.eventCount).toBe(body.events.length);
    expect(keys(body.events[0])).toEqual([
      'attempts',
      'channel',
      'createdAt',
      'direction',
      'duplicateCount',
      'error',
      'eventKey',
      'eventTypes',
      'id',
      'orderId',
      'paymentId',
      'providerId',
      'remoteIp',
      'responseStatus',
      'status',
      'verified',
    ]);
    expect(body.events.every((e) => e.orderId === detail.order.id)).toBe(true);
    expect(get('/payments/1/events', 'few')).toEqual({ result: 'error', error: 'NOT_FOUND' });
  });
});

describe('determinism and the page list', () => {
  test('every route answers the same body twice, in every volume', () => {
    const paths = [
      '/stats',
      '/health',
      '/credits/accounts',
      '/credits/accounts/1',
      '/credits/transactions',
      '/blocks',
      '/discounts',
      '/coupons',
      '/creator-codes',
      '/creator-codes/report',
      '/gifts',
      '/comparisons',
      '/goals',
    ];
    for (const volume of VOLUMES) {
      for (const path of paths) {
        const a = JSON.stringify(get(path, volume));
        expect(JSON.stringify(createRouter(routes).answer('GET', API + path, volume))).toBe(a);
        expect(JSON.parse(a).result).toBe('ok');
      }
    }
    const id = orderRows('many')[5].id;
    expect(JSON.stringify(get(`/orders/${id}`))).toBe(JSON.stringify(get(`/orders/${id}`)));
  });

  test('routes are GETs below the panel API, no duplicates', () => {
    expect(
      routes.every(
        (r) =>
          r.method === 'GET' && r.path.startsWith(`${API}/`) && typeof r.handler === 'function',
      ),
    ).toBe(true);
    expect(new Set(routes.map((r) => r.path)).size).toBe(routes.length);
  });

  test('pages', () => {
    expect(pages.length).toBeGreaterThan(10);
    expect(pages.every((p) => p.side === 'panel' && p.label && p.href.startsWith('/market'))).toBe(
      true,
    );
    const orderPage = pages.find((p) => p.href.startsWith('/market/orders/detail/'));
    const id = Number(orderPage.href.split('/').pop());
    expect(orderRows('few').some((o) => o.id === id)).toBe(true);
    expect(orderRows('many').some((o) => o.id === id)).toBe(true);
  });
});
