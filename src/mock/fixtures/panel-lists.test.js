import { describe, expect, test } from 'bun:test';
import { createRouter } from '../core.js';
import { routes, pages, PAGE_SIZE } from './panel-lists.js';
import { categoryRows, orderRows, productRows } from './world.js';

const router = createRouter(routes);
const get = (path, volume = 'many') => router.answer('GET', `/api/panel/market${path}`, volume);

// [path, rows key, count key, keys of one row]
const LISTS = [
  [
    '/orders',
    'orders',
    'orderCount',
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
    'deliveries',
    'deliveryCount',
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
    'shipments',
    'shipmentCount',
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
    'subscriptions',
    'subscriptionCount',
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
    'events',
    'eventCount',
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
    'products',
    'productCount',
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
    expect(ctx.result).toBe('ok');
    expect(ctx.shippingEnabled).toBe(true);
    expect(ctx.creditsEnabled).toBe(true);
    expect(ctx.runtimeState).toBe('READY');
    expect(ctx.additionalCurrencies.length).toBeGreaterThan(1);
    expect(ctx.currencies[0]).toEqual({ code: 'TRY', symbol: '₺', exponent: 2 });
  });
});

describe.each(LISTS)('GET %s', (path, key, countKey, keys) => {
  test('row shape', () => {
    const body = get(path);
    expect(body.result).toBe('ok');
    expect(Object.keys(body).sort()).toEqual(['result', key, countKey, 'totalPage'].sort());
    expect(Object.keys(body[key][0])).toEqual(keys);
    expect(body[key].length).toBeLessThanOrEqual(PAGE_SIZE);
  });

  test('pagination: page 2 differs, past the end is PAGE_NOT_FOUND, pageSize is honoured', () => {
    const first = get(path);
    expect(first[countKey]).toBeGreaterThan(PAGE_SIZE);
    expect(first[key]).toHaveLength(PAGE_SIZE);
    expect(first.totalPage).toBe(Math.ceil(first[countKey] / PAGE_SIZE));
    const second = get(`${path}?page=2`);
    expect(second[key][0].id).not.toBe(first[key][0].id);
    expect(get(`${path}?page=${first.totalPage + 1}`)).toEqual({
      result: 'error',
      error: 'PAGE_NOT_FOUND',
    });
    expect(get(`${path}?pageSize=3`)[key]).toHaveLength(3);
  });

  test('deterministic', () => {
    expect(get(`${path}?page=2`)).toEqual(get(`${path}?page=2`));
    expect(JSON.stringify(get(path, 'few'))).toBe(JSON.stringify(get(path, 'few')));
  });

  test('few has rows, empty has none', () => {
    expect(get(path, 'few')[key].length).toBeGreaterThan(0);
    const empty = get(path, 'empty');
    expect(empty.result).toBe('ok');
    expect(empty[key]).toEqual([]);
    expect(empty[countKey]).toBe(0);
  });
});

const all = (path) => get(`${path}${path.includes('?') ? '&' : '?'}pageSize=100`);

describe('filters', () => {
  test('orders: status csv, search, source, testMode, shippingStatus, paymentMethodId, from / to', () => {
    const byStatus = all('/orders?status=COMPLETED,PARTIALLY_REFUNDED').orders;
    expect(byStatus.length).toBeGreaterThan(0);
    expect(byStatus.every((o) => ['COMPLETED', 'PARTIALLY_REFUNDED'].includes(o.status))).toBe(
      true,
    );

    const name = orderRows('many')[0].playerUsername;
    const found = all(`/orders?search=${name.toLowerCase()}`).orders;
    expect(found.length).toBeGreaterThan(0);
    expect(found.every((o) => [o.playerUsername, o.recipientUsername].includes(name))).toBe(true);
    expect(all('/orders?search=no-such-thing-zzz').orderCount).toBe(0);

    expect(
      all('/orders?source=PANEL,RENEWAL').orders.every((o) =>
        ['PANEL', 'RENEWAL'].includes(o.source),
      ),
    ).toBe(true);
    expect(all('/orders?testMode=true').orders.every((o) => o.testMode)).toBe(true);
    expect(all('/orders?testMode=all').orderCount).toBe(orderRows('many').length);
    const toShip = all('/orders?shippingStatus=PENDING,PARTIAL').orders;
    expect(toShip.length).toBeGreaterThan(0);
    expect(toShip.every((o) => ['PENDING', 'PARTIAL'].includes(o.shippingStatus))).toBe(true);
    expect(
      all('/orders?paymentMethodId=paypal').orders.every((o) => o.paymentMethodId === 'paypal'),
    ).toBe(true);
    expect(
      all('/orders?fulfillmentStatus=FULFILLED').orders.every(
        (o) => o.fulfillmentStatus === 'FULFILLED',
      ),
    ).toBe(true);

    const mid = orderRows('many')[40].createdAt;
    expect(all(`/orders?from=${mid}`).orders.every((o) => o.createdAt >= mid)).toBe(true);
    expect(all(`/orders?to=${mid}`).orders.every((o) => o.createdAt <= mid)).toBe(true);
    expect(all(`/orders?from=${mid}`).orderCount + all(`/orders?to=${mid}`).orderCount).toBe(
      orderRows('many').length + 1,
    );
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
    ).deliveries;
    expect(waiting.length).toBeGreaterThan(0);
    expect(waiting.every((d) => d.status !== 'CONFIRMED' && d.status !== 'FAILED')).toBe(true);
    expect(
      all('/deliveries?status=FAILED').deliveries.every(
        (d) => d.status === 'FAILED' && d.lastErrorCode,
      ),
    ).toBe(true);
    expect(all('/deliveries?phase=REVOKE').deliveries.every((d) => d.phase === 'REVOKE')).toBe(
      true,
    );
    expect(all('/deliveries?serverId=2').deliveries.every((d) => d.serverId === 2)).toBe(true);
    expect(
      all('/deliveries?actionType=CREDIT').deliveries.every((d) => d.actionType === 'CREDIT'),
    ).toBe(true);
    const one = all('/deliveries').deliveries[0];
    expect(all(`/deliveries?search=${one.orderId}`).deliveries.some((d) => d.id === one.id)).toBe(
      true,
    );
  });

  test('shipments: status csv, providerId, search', () => {
    const transit = all(
      '/shipments?status=CREATED,LABEL_READY,IN_TRANSIT,OUT_FOR_DELIVERY',
    ).shipments;
    expect(transit.length).toBeGreaterThan(0);
    expect(
      transit.every((s) =>
        ['CREATED', 'LABEL_READY', 'IN_TRANSIT', 'OUT_FOR_DELIVERY'].includes(s.status),
      ),
    ).toBe(true);
    expect(
      all('/shipments?providerId=manual').shipments.every((s) => s.providerId === 'manual'),
    ).toBe(true);
    const one = all('/shipments').shipments.find((s) => s.trackingNumber);
    expect(all(`/shipments?search=${one.trackingNumber}`).shipments.map((s) => s.id)).toEqual([
      one.id,
    ]);
  });

  test('subscriptions: PENDING is hidden by default, status csv, search', () => {
    expect(all('/subscriptions').subscriptions.some((s) => s.status === 'PENDING')).toBe(false);
    const ended = all('/subscriptions?status=CANCELLED,EXPIRED,COMPLETED').subscriptions;
    expect(ended.length).toBeGreaterThan(0);
    expect(ended.every((s) => ['CANCELLED', 'EXPIRED', 'COMPLETED'].includes(s.status))).toBe(true);
    expect(
      all('/subscriptions?search=vip%2B').subscriptions.every((s) =>
        s.productName.includes('VIP+'),
      ),
    ).toBe(true);
  });

  test('payment events: the default list is the rows needing attention', () => {
    expect(
      all('/payment-events').events.every((e) =>
        ['DEFERRED', 'FAILED', 'REJECTED'].includes(e.status),
      ),
    ).toBe(true);
    expect(all('/payment-events?status=FAILED').events.every((e) => e.status === 'FAILED')).toBe(
      true,
    );
    const processed = all('/payment-events?status=PROCESSED').events;
    expect(processed.length).toBeGreaterThan(0);
    const provider = processed[0].providerId;
    expect(
      all(`/payment-events?providerId=${provider}`).events.every((e) => e.providerId === provider),
    ).toBe(true);
  });

  test('products: search, status, kind, categoryId', () => {
    const crates = all('/products?search=crate').products;
    expect(crates.length).toBeGreaterThan(0);
    expect(crates.every((p) => /crate/i.test(p.name))).toBe(true);
    expect(all('/products?status=ARCHIVED').products.every((p) => p.status === 'ARCHIVED')).toBe(
      true,
    );
    expect(all('/products?kind=CREDIT_PACK').products.every((p) => p.kind === 'CREDIT_PACK')).toBe(
      true,
    );
    expect(all('/products?categoryId=3').products.every((p) => p.categoryId === 3)).toBe(true);
    expect(all('/products').productCount).toBe(productRows('many').length);
  });
});

describe('categories and pickers', () => {
  test('categories: a tree, search promotes orphans, empty volume', () => {
    const body = get('/categories');
    expect(body.categoryCount).toBe(categoryRows('many').length);
    expect(body.totalPage).toBe(1);
    expect(Object.keys(body.categories[0])).toEqual([
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
    expect(body.categories.every((c) => c.parentId === null)).toBe(true);
    expect(body.categories.some((c) => c.children.length > 0)).toBe(true);
    const count = (nodes) => nodes.reduce((n, c) => n + 1 + count(c.children), 0);
    expect(count(body.categories)).toBe(body.categoryCount);

    const tiers = get('/categories?search=tier');
    expect(tiers.categoryCount).toBeGreaterThan(0);
    expect(count(tiers.categories)).toBe(tiers.categoryCount);
    expect(get('/categories')).toEqual(get('/categories'));
    expect(get('/categories', 'empty')).toEqual({
      result: 'ok',
      categories: [],
      categoryCount: 0,
      totalPage: 1,
    });
  });

  test('products/simple is not shadowed by a :id route and lists every product by name', () => {
    const body = get('/products/simple');
    expect(body.products).toHaveLength(productRows('many').length);
    expect(Object.keys(body.products[0])).toEqual([
      'id',
      'name',
      'kind',
      'billingMode',
      'hasVariants',
      'status',
    ]);
    expect(get('/products/simple', 'empty').products).toEqual([]);
  });

  test('servers', () => {
    const body = get('/servers', 'few');
    expect(body.servers.length).toBeGreaterThan(1);
    expect(Object.keys(body.servers[0])).toEqual([
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
