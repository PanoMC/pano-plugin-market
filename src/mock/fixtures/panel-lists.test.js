import { describe, expect, test } from 'bun:test';
import { createRouter } from '../core.js';
import { routes, pages, PAGE_SIZE } from './panel-lists.js';
import { categoryRows, orderRows, productRows } from './world.js';

const router = createRouter(routes);
const get = (path, volume = 'many') =>
  router.answer('GET', `/plugins/pano-plugin-market/panel${path}`, volume);

// [path, keys of one row]
const LISTS = [
  [
    '/orders',
    [
      'id',
      'publicId',
      'source',
      'userId',
      'playerUsername',
      'recipientUsername',
      'isGift',
      'email',
      'totalPrice',
      'currency',
      'paymentMethodId',
      'paymentLabel',
      'status',
      'gatewayAmount',
      'creditValue',
      'refundedTotal',
      'fulfillmentStatus',
      'shippingStatus',
      'paidAt',
      'testMode',
      'reviewReason',
      'createdAt',
      'updatedAt',
      'items',
    ],
  ],
  [
    '/deliveries',
    [
      'id',
      'orderId',
      'orderItemId',
      'productName',
      'playerUsername',
      'phase',
      'actionId',
      'actionType',
      'transport',
      'idempotencyKey',
      'serverId',
      'serverName',
      'status',
      'attempts',
      'requiresOnline',
      'waitUntil',
      'cancelRequested',
      'lastErrorCode',
      'lastError',
      'runAfter',
      'sentAt',
      'confirmedAt',
      'payload',
      'result',
    ],
  ],
  [
    '/shipments',
    [
      'id',
      'orderId',
      'orderPublicId',
      'playerUsername',
      'methodId',
      'providerId',
      'providerName',
      'entryMode',
      'serviceCode',
      'status',
      'merchantReference',
      'carrierReference',
      'trackingNumber',
      'trackingUrl',
      'carrierName',
      'hasLabel',
      'labelFormat',
      'documents',
      'cost',
      'costCurrency',
      'weightGrams',
      'packages',
      'testMode',
      'stale',
      'lastErrorCode',
      'lastError',
      'estimatedDeliveryAt',
      'shippedAt',
      'deliveredAt',
      'cancelledAt',
      'lastPolledAt',
      'nextPollAt',
      'itemsReleased',
      'note',
      'createdAt',
      'updatedAt',
      'allowed',
    ],
  ],
  [
    '/subscriptions',
    [
      'id',
      'playerUsername',
      'userId',
      'productName',
      'providerId',
      'mode',
      'status',
      'price',
      'currency',
      'intervalUnit',
      'intervalCount',
      'cycleCount',
      'maxCycles',
      'currentPeriodEnd',
      'nextChargeAt',
      'graceEndsAt',
      'cancelAtPeriodEnd',
      'failCount',
      'endReason',
      'testMode',
      'createdAt',
    ],
  ],
  [
    '/payment-events',
    [
      'id',
      'providerId',
      'direction',
      'channel',
      'eventKey',
      'paymentId',
      'orderId',
      'verified',
      'status',
      'eventTypes',
      'responseStatus',
      'error',
      'remoteIp',
      'attempts',
      'duplicateCount',
      'createdAt',
      'body',
      'headers',
      'url',
    ],
  ],
  [
    '/products',
    [
      'id',
      'slug',
      'name',
      'categoryId',
      'categoryName',
      'price',
      'creditPrice',
      'compareAtPrice',
      'stock',
      'status',
      'featured',
      'priority',
      'icon',
      'imageFileName',
      'kind',
      'physical',
      'billingMode',
      'hasVariants',
      'soldCount',
    ],
  ],
];

describe('context', () => {
  test('has the flags the list pages read', () => {
    const ctx = get('/context');
    expect(ctx).not.toHaveProperty('result');
    expect(ctx).not.toHaveProperty('error');
    expect(ctx.shippingEnabled).toBe(true);
    expect(ctx.creditsEnabled).toBe(true);
    expect(ctx.runtimeState).toBe('READY');
    expect(ctx.additionalCurrencies.length).toBeGreaterThan(1);
    expect(ctx.currencies[0]).toEqual({ code: 'TRY', symbol: '₺', exponent: 2 });
  });
});

describe.each(LISTS)('GET %s', (path, keys) => {
  test('row shape: items and page, no result key and no per-list names', () => {
    const body = get(path);
    expect(Object.keys(body).sort()).toEqual(['items', 'page']);
    expect(Object.keys(body.page)).toEqual(['number', 'size', 'totalItems', 'totalPages']);
    expect(Object.keys(body.items[0])).toEqual(keys);
    expect(body.items.length).toBeLessThanOrEqual(PAGE_SIZE);
  });

  test('pagination: page 2 differs, past the end is PAGE_NOT_FOUND, pageSize is honoured', () => {
    const first = get(path);
    expect(first.page.totalItems).toBeGreaterThan(PAGE_SIZE);
    expect(first.items).toHaveLength(PAGE_SIZE);
    expect(first.page).toEqual({
      number: 1,
      size: PAGE_SIZE,
      totalItems: first.page.totalItems,
      totalPages: Math.ceil(first.page.totalItems / PAGE_SIZE),
    });
    const second = get(`${path}?page=2`);
    expect(second.page.number).toBe(2);
    expect(second.items[0].id).not.toBe(first.items[0].id);
    expect(get(`${path}?page=${first.page.totalPages + 1}`)).toEqual({
      error: { code: 'PAGE_NOT_FOUND' },
    });
    expect(get(`${path}?pageSize=3`).items).toHaveLength(3);
    expect(get(`${path}?pageSize=3`).page.size).toBe(3);
  });

  test('a page or pageSize out of range is INVALID_FIELDS, never clamped', () => {
    expect(get(`${path}?pageSize=101`)).toEqual({
      error: { code: 'INVALID_FIELDS', fields: { pageSize: 'OUT_OF_RANGE' } },
    });
    expect(get(`${path}?page=0`).error.fields).toEqual({ page: 'OUT_OF_RANGE' });
  });

  test('deterministic', () => {
    expect(get(`${path}?page=2`)).toEqual(get(`${path}?page=2`));
    expect(JSON.stringify(get(path, 'few'))).toBe(JSON.stringify(get(path, 'few')));
  });

  test('few has rows, empty has none', () => {
    expect(get(path, 'few').items.length).toBeGreaterThan(0);
    const empty = get(path, 'empty');
    expect(empty).not.toHaveProperty('error');
    expect(empty.items).toEqual([]);
    expect(empty.page).toEqual({ number: 1, size: PAGE_SIZE, totalItems: 0, totalPages: 0 });
  });
});

const all = (path) => get(`${path}${path.includes('?') ? '&' : '?'}pageSize=100`);

describe('filters', () => {
  test('orders: status csv, search, source, testMode, shippingStatus, paymentMethodId, from / to', () => {
    const byStatus = all('/orders?status=COMPLETED,PARTIALLY_REFUNDED').items;
    expect(byStatus.length).toBeGreaterThan(0);
    expect(byStatus.every((o) => ['COMPLETED', 'PARTIALLY_REFUNDED'].includes(o.status))).toBe(
      true,
    );

    const name = orderRows('many')[0].playerUsername;
    const found = all(`/orders?search=${name.toLowerCase()}`).items;
    expect(found.length).toBeGreaterThan(0);
    expect(found.every((o) => [o.playerUsername, o.recipientUsername].includes(name))).toBe(true);
    expect(all('/orders?search=no-such-thing-zzz').page.totalItems).toBe(0);

    expect(
      all('/orders?source=PANEL,RENEWAL').items.every((o) =>
        ['PANEL', 'RENEWAL'].includes(o.source),
      ),
    ).toBe(true);
    expect(all('/orders?testMode=true').items.every((o) => o.testMode)).toBe(true);
    expect(all('/orders?testMode=all').page.totalItems).toBe(orderRows('many').length);
    const toShip = all('/orders?shippingStatus=PENDING,PARTIAL').items;
    expect(toShip.length).toBeGreaterThan(0);
    expect(toShip.every((o) => ['PENDING', 'PARTIAL'].includes(o.shippingStatus))).toBe(true);
    expect(
      all('/orders?paymentMethodId=paypal').items.every((o) => o.paymentMethodId === 'paypal'),
    ).toBe(true);
    expect(
      all('/orders?fulfillmentStatus=FULFILLED').items.every(
        (o) => o.fulfillmentStatus === 'FULFILLED',
      ),
    ).toBe(true);

    const mid = orderRows('many')[40].createdAt;
    expect(all(`/orders?from=${mid}`).items.every((o) => o.createdAt >= mid)).toBe(true);
    expect(all(`/orders?to=${mid}`).items.every((o) => o.createdAt <= mid)).toBe(true);
    expect(
      all(`/orders?from=${mid}`).page.totalItems + all(`/orders?to=${mid}`).page.totalItems,
    ).toBe(orderRows('many').length + 1);
  });

  test('orders: money is consistent and several currencies appear', () => {
    const orders = orderRows('many');
    expect(new Set(orders.map((o) => o.currency)).size).toBeGreaterThan(2);
    for (const o of orders) {
      const sum = Math.round(o.items.reduce((s, i) => s + i.lineTotal, 0) * 100) / 100;
      expect(o.totalPrice).toBe(sum);
      expect(o.refundedTotal).toBeLessThanOrEqual(o.totalPrice);
    }
    for (let i = 1; i < orders.length; i++)
      expect(orders[i].createdAt).toBeLessThan(orders[i - 1].createdAt);
  });

  test('deliveries: status csv, phase, serverId, actionType, search', () => {
    const waiting = all(
      '/deliveries?status=PENDING,SCHEDULED,WAITING_SERVER,SENT,QUEUED,SENDING',
    ).items;
    expect(waiting.length).toBeGreaterThan(0);
    expect(waiting.every((d) => d.status !== 'CONFIRMED' && d.status !== 'FAILED')).toBe(true);
    expect(
      all('/deliveries?status=FAILED').items.every((d) => d.status === 'FAILED' && d.lastErrorCode),
    ).toBe(true);
    expect(all('/deliveries?phase=REVOKE').items.every((d) => d.phase === 'REVOKE')).toBe(true);
    expect(all('/deliveries?serverId=2').items.every((d) => d.serverId === 2)).toBe(true);
    expect(all('/deliveries?actionType=CREDIT').items.every((d) => d.actionType === 'CREDIT')).toBe(
      true,
    );
    const one = all('/deliveries').items[0];
    expect(all(`/deliveries?search=${one.orderId}`).items.some((d) => d.id === one.id)).toBe(true);
  });

  test('shipments: status csv, providerId, search', () => {
    const transit = all('/shipments?status=CREATED,LABEL_READY,IN_TRANSIT,OUT_FOR_DELIVERY').items;
    expect(transit.length).toBeGreaterThan(0);
    expect(
      transit.every((s) =>
        ['CREATED', 'LABEL_READY', 'IN_TRANSIT', 'OUT_FOR_DELIVERY'].includes(s.status),
      ),
    ).toBe(true);
    expect(all('/shipments?providerId=manual').items.every((s) => s.providerId === 'manual')).toBe(
      true,
    );
    const one = all('/shipments').items.find((s) => s.trackingNumber);
    expect(all(`/shipments?search=${one.trackingNumber}`).items.map((s) => s.id)).toEqual([one.id]);
  });

  test('subscriptions: PENDING is hidden by default, status csv, search', () => {
    expect(all('/subscriptions').items.some((s) => s.status === 'PENDING')).toBe(false);
    const ended = all('/subscriptions?status=CANCELLED,EXPIRED,COMPLETED').items;
    expect(ended.length).toBeGreaterThan(0);
    expect(ended.every((s) => ['CANCELLED', 'EXPIRED', 'COMPLETED'].includes(s.status))).toBe(true);
    expect(
      all('/subscriptions?search=vip%2B').items.every((s) => s.productName.includes('VIP+')),
    ).toBe(true);
  });

  test('payment events: the default list is the rows needing attention', () => {
    expect(
      all('/payment-events').items.every((e) =>
        ['DEFERRED', 'FAILED', 'REJECTED'].includes(e.status),
      ),
    ).toBe(true);
    expect(all('/payment-events?status=FAILED').items.every((e) => e.status === 'FAILED')).toBe(
      true,
    );
    const processed = all('/payment-events?status=PROCESSED').items;
    expect(processed.length).toBeGreaterThan(0);
    const provider = processed[0].providerId;
    expect(
      all(`/payment-events?providerId=${provider}`).items.every((e) => e.providerId === provider),
    ).toBe(true);
  });

  test('products: search, status, kind, categoryId', () => {
    const crates = all('/products?search=crate').items;
    expect(crates.length).toBeGreaterThan(0);
    expect(crates.every((p) => /crate/i.test(p.name))).toBe(true);
    expect(all('/products?status=ARCHIVED').items.every((p) => p.status === 'ARCHIVED')).toBe(true);
    expect(all('/products?kind=CREDIT_PACK').items.every((p) => p.kind === 'CREDIT_PACK')).toBe(
      true,
    );
    expect(all('/products?categoryId=3').items.every((p) => p.categoryId === 3)).toBe(true);
    expect(all('/products').page.totalItems).toBe(productRows('many').length);
  });
});

describe('categories and pickers', () => {
  test('categories: a tree, search promotes orphans, empty volume', () => {
    const body = get('/categories');
    // the whole tree in one page (PanelGetCategoriesAPI)
    expect(body.page).toEqual({
      number: 1,
      size: categoryRows('many').length,
      totalItems: categoryRows('many').length,
      totalPages: 1,
    });
    expect(Object.keys(body.items[0])).toEqual([
      'id',
      'name',
      'description',
      'icon',
      'color',
      'status',
      'parentId',
      'position',
      'imageFileName',
      'tiered',
      'upgradeMode',
      'productsCount',
      'createdAt',
      'updatedAt',
      'children',
    ]);
    expect(body.items.every((c) => c.parentId === null)).toBe(true);
    expect(body.items.some((c) => c.children.length > 0)).toBe(true);
    const count = (nodes) => nodes.reduce((n, c) => n + 1 + count(c.children), 0);
    expect(count(body.items)).toBe(body.page.totalItems);

    const tiers = get('/categories?search=tier');
    expect(tiers.page.totalItems).toBeGreaterThan(0);
    expect(count(tiers.items)).toBe(tiers.page.totalItems);
    expect(get('/categories')).toEqual(get('/categories'));
    expect(get('/categories', 'empty')).toEqual({
      items: [],
      page: { number: 1, size: 1, totalItems: 0, totalPages: 0 },
    });
  });

  test('products/simple is not shadowed by a :id route and lists every product by name', () => {
    const body = get('/products/simple');
    expect(body.items).toHaveLength(productRows('many').length);
    expect(Object.keys(body.items[0])).toEqual([
      'id',
      'name',
      'kind',
      'billingMode',
      'hasVariants',
      'status',
    ]);
    expect(get('/products/simple', 'empty').items).toEqual([]);
  });

  test('servers', () => {
    const body = get('/servers', 'few');
    expect(body.items.length).toBeGreaterThan(1);
    expect(Object.keys(body.items[0])).toEqual([
      'id',
      'name',
      'type',
      'connected',
      'proxy',
      'mcComponentVersion',
      'requiredVersion',
      'marketState',
      'waitingDeliveries',
      'queuedDeliveries',
      'downloadUrl',
      'platform',
      'integrations',
      'settings',
    ]);
  });

  test('pages are panel links', () => {
    expect(pages.length).toBe(7);
    expect(pages.every((p) => p.side === 'panel' && p.href.startsWith('/market/'))).toBe(true);
  });
});
