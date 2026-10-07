import { describe, expect, test } from 'bun:test';
import { createRouter } from '../core.js';
import { COUPON, catalog, orders, pages, quoteOf, routes, subscriptions } from './storefront.js';

const router = createRouter(routes);
const get = (path, volume = 'few') => router.answer('GET', path, volume);
const quote = (body, volume = 'few') =>
  router.answer('POST', '/api/market/checkout/quote', volume, body).quote;
const cents = (n) => Math.round(n * 100);

const CARD_KEYS = [
  'id',
  'slug',
  'name',
  'shortDescription',
  'categoryId',
  'kind',
  'price',
  'compareAtPrice',
  'creditPrice',
  'currency',
  'priceFrom',
  'inStock',
  'stock',
  'featured',
  'priority',
  'icon',
  'imageFileName',
  'physical',
  'billingMode',
  'period',
  'hasVariants',
  'tierRank',
  'sale',
  'needsOptions',
  'owned',
];

describe('store', () => {
  test('GET /store carries settings, the category tree and the first page', () => {
    const body = get('/api/market/store');

    expect(body.result).toBe('ok');
    expect(body.settings.currency).toBe('USD');
    expect(body.settings.pageSize).toBe(12);
    expect(body.settings.modules.saleBadges).toBe(true);
    expect(body.categories.length).toBe(4);
    expect(body.categories[0].children.length).toBe(2);
    expect(body.categories[0].productsCount).toBeGreaterThan(0);
    expect(body.products.length).toBe(9);
    expect(body.productCount).toBe(9);
    expect(body.totalPage).toBe(1);
    expect(Object.keys(body.products[0])).toEqual(CARD_KEYS);
    expect(body.featured.length).toBeGreaterThan(0);
    expect(body.bestsellers.length).toBeGreaterThan(0);
    expect(body.comparisons[0].productIds).toEqual(body.comparisonProducts.map((p) => p.id));
  });

  test('the catalogue has sale, out of stock, subscription, bundle and variant products', () => {
    const { products } = get('/api/market/store/products?pageSize=60');

    expect(products.some((p) => p.sale && p.sale.percent > 0 && p.compareAtPrice > p.price)).toBe(
      true,
    );
    expect(products.some((p) => p.sale && p.sale.amountOff > 0)).toBe(true);
    expect(products.some((p) => !p.inStock)).toBe(true);
    expect(
      products.some((p) => p.billingMode === 'SUBSCRIPTION' && p.period.unit === 'MONTH'),
    ).toBe(true);
    expect(products.some((p) => p.kind === 'BUNDLE')).toBe(true);
    expect(products.some((p) => p.hasVariants && p.priceFrom)).toBe(true);
    expect(products.some((p) => p.name.length > 60)).toBe(true);
  });

  test('pagination and PAGE_NOT_FOUND', () => {
    const first = get('/api/market/store/products', 'many');
    const second = get('/api/market/store/products?page=2', 'many');

    expect(first.products.length).toBe(12);
    expect(first.productCount).toBe(64);
    expect(first.totalPage).toBe(6);
    expect(second.products[0].id).not.toBe(first.products[0].id);
    expect(get('/api/market/store/products?page=6', 'many').products.length).toBe(4);
    expect(get('/api/market/store/products?page=7', 'many')).toEqual({
      result: 'error',
      error: 'PAGE_NOT_FOUND',
    });
    expect(get('/api/market/store/products?pageSize=500', 'many').products.length).toBe(60);
    expect(new Set(catalog('many').products.map((p) => p.slug)).size).toBe(64);
  });

  test('category (with its sub categories), search, sort, featured and kind filters', () => {
    const ranks = get('/api/market/store/products?category=1');
    expect(ranks.productCount).toBeGreaterThan(0);
    expect(ranks.products.every((p) => [1, 101, 102].includes(p.categoryId))).toBe(true);
    expect(
      get('/api/market/store/products?category=101').products.every((p) => p.categoryId === 101),
    ).toBe(true);
    expect(get('/api/market/store/products?category=999')).toEqual({
      result: 'error',
      error: 'NOT_FOUND',
    });

    const search = get('/api/market/store/products?search=RANK');
    expect(search.productCount).toBe(3);
    expect(search.products.every((p) => /rank/i.test(p.name))).toBe(true);
    expect(get('/api/market/store/products?search=zzzz').products).toEqual([]);

    const asc = get('/api/market/store/products?sort=price-asc&pageSize=60', 'many').products.map(
      (p) => p.id,
    );
    const desc = get('/api/market/store/products?sort=price-desc&pageSize=60', 'many').products.map(
      (p) => p.id,
    );
    const price = (id) => catalog('many').byId.get(id).price;
    for (let i = 1; i < asc.length; i++)
      expect(price(asc[i])).toBeGreaterThanOrEqual(price(asc[i - 1]));
    for (let i = 1; i < desc.length; i++)
      expect(price(desc[i])).toBeLessThanOrEqual(price(desc[i - 1]));

    const newest = get('/api/market/store/products?sort=newest').products.map((p) => p.id);
    expect(newest[0]).toBe(1);
    expect(get('/api/market/store/products?sort=nope').error).toBe('BAD_REQUEST');

    expect(get('/api/market/store/products?featured=true').products.every((p) => p.featured)).toBe(
      true,
    );
    expect(
      get('/api/market/store/products?kind=BUNDLE').products.every((p) => p.kind === 'BUNDLE'),
    ).toBe(true);
  });

  test('?currency= converts the prices of an offered currency', () => {
    const usd = get('/api/market/store/products').products[0];
    const tr = get('/api/market/store/products?currency=try').products[0];

    expect(tr.currency).toBe('TRY');
    expect(tr.price).toBeGreaterThan(usd.price);
    expect(get('/api/market/store/products?currency=JPY').products[0].currency).toBe('USD');
    expect(get('/api/market/store?currency=EUR').settings.displayCurrency).toBe('EUR');
  });

  test('empty volume', () => {
    const body = get('/api/market/store', 'empty');

    expect(body.result).toBe('ok');
    expect(body.categories).toEqual([]);
    expect(body.products).toEqual([]);
    expect(body.productCount).toBe(0);
    expect(body.totalPage).toBe(0);
    expect(body.comparisons).toEqual([]);
    expect(get('/api/market/store/products', 'empty')).toEqual({
      result: 'ok',
      products: [],
      productCount: 0,
      totalPage: 0,
    });
    expect(get('/api/market/me/orders', 'empty')).toEqual({
      result: 'ok',
      orders: [],
      orderCount: 0,
      totalPage: 0,
    });
    expect(get('/api/market/me/entitlements', 'empty').entitlements).toEqual([]);
    expect(get('/api/market/me/credits', 'empty').entries).toEqual([]);
    expect(get('/api/market/me/credits', 'empty').balance).toBe(0);
    expect(get('/api/market/me/subscriptions', 'empty').subscriptions).toEqual([]);
    expect(get('/api/market/me/creator', 'empty')).toEqual({ result: 'error', error: 'NOT_FOUND' });
    expect(get('/api/market/me/cart', 'empty').quote.canCheckout).toBe(false);
    expect(get('/api/market/widgets', 'empty').recentBuyers).toEqual([]);
  });
});

describe('product', () => {
  test('detail by slug', () => {
    const { result, product } = get('/api/market/products/vip-rank');

    expect(result).toBe('ok');
    expect(product.slug).toBe('vip-rank');
    expect(product.billingMode).toBe('SUBSCRIPTION');
    expect(product.description).toContain('<p>');
    expect(product.categoryName).toBe('Monthly Ranks');
    expect(product.purchasable).toEqual({ ok: true, reason: null });
    for (const key of [
      'variantOptions',
      'variants',
      'fields',
      'bundleItems',
      'requiredProducts',
      'serverChoices',
      'vatPercent',
      'allowGift',
      'upgrade',
      'maxQuantityPerOrder',
    ])
      expect(product).toHaveProperty(key);
  });

  test('variants, bundle, fields, out of stock, requirement', () => {
    const keys = get('/api/market/products/legendary-crate-key-x5').product;
    expect(keys.variantOptions[0].values.length).toBe(3);
    expect(keys.variants.length).toBe(3);
    expect(keys.variants[1].optionValues).toEqual({ amount: '5' });
    expect(keys.price).toBe(Math.min(...keys.variants.map((v) => v.price)));

    const wings = get('/api/market/products/cosmetic-wings', 'many').product;
    expect(wings.variantOptions.length).toBe(2);
    expect(wings.variants.length).toBe(6);
    expect(wings.variants.some((v) => !v.inStock)).toBe(true);

    const kit = get('/api/market/products/starter-kit').product;
    expect(kit.kind).toBe('BUNDLE');
    expect(kit.bundleItems.length).toBe(3);
    expect(kit.bundleItems[0]).toEqual({
      productId: 5,
      variantId: 0,
      name: 'Legendary Crate Key x5',
      quantity: 2,
      imageFileName: null,
    });

    const fly = get(
      '/api/market/products/fly-pass-permanent-all-survival-worlds-and-the-creative-plot-server',
    ).product;
    expect(fly.fields.map((f) => f.type)).toEqual(['USERNAME', 'SELECT']);
    expect(fly.serverChoices.length).toBe(2);

    expect(get('/api/market/products/mythic-crate-key').product.purchasable).toEqual({
      ok: false,
      reason: 'OUT_OF_STOCK',
    });
    expect(get('/api/market/products/mvp-rank-lifetime').product.requiredProducts[0].slug).toBe(
      'vip-rank',
    );
    expect(get('/api/market/products/nope')).toEqual({ result: 'error', error: 'NOT_FOUND' });
  });
});

describe('quote', () => {
  test('totals are computed from the posted lines', () => {
    const cat = catalog('few');
    const sale = cat.byId.get(2); // 14.99, was 19.99
    const plain = cat.byId.get(9); // 11.99
    const q = quote({
      items: [
        { productId: 2, quantity: 2 },
        { productId: 9, variantId: 0, quantity: 3 },
      ],
    });

    expect(q.currency).toBe('USD');
    expect(q.lines.length).toBe(2);
    expect(cents(q.lines[0].unitPrice)).toBe(sale.price);
    expect(cents(q.lines[0].listUnitPrice)).toBe(sale.compareAt);
    expect(cents(q.lines[0].lineTotal)).toBe(sale.price * 2);
    expect(cents(q.lines[0].discountAmount)).toBe((sale.compareAt - sale.price) * 2);
    expect(cents(q.subtotal)).toBe(sale.compareAt * 2 + plain.price * 3);
    expect(cents(q.discountTotal)).toBe((sale.compareAt - sale.price) * 2);
    expect(cents(q.total)).toBe(sale.price * 2 + plain.price * 3);
    expect(cents(q.total)).toBe(
      cents(q.subtotal) - cents(q.discountTotal) - cents(q.couponDiscount) + cents(q.paymentFee),
    );
    expect(cents(q.total)).toBe(q.lines.reduce((sum, l) => sum + cents(l.lineTotal), 0));
    expect(cents(q.vatTotal)).toBe(q.lines.reduce((sum, l) => sum + cents(l.vatAmount), 0));
    expect(q.gatewayAmount).toBe(q.total);
    expect(q.canCheckout).toBe(true);
    expect(q.paymentMethods.filter((m) => m.available).length).toBe(2);
    expect(q.legal.required).toBe(true);
  });

  test('coupon, payment fee, credits and variants', () => {
    const base = quote({ items: [{ productId: 9, quantity: 1 }] });
    const q = quote({
      items: [{ productId: 9, quantity: 1 }],
      couponCode: COUPON.code.toLowerCase(),
      paymentMethodId: 'bank-transfer',
      useCredits: 5,
    });

    expect(q.coupon).toEqual({ code: COUPON.code, valid: true, reason: null });
    expect(cents(q.couponDiscount)).toBe(Math.round(cents(base.total) / 10));
    expect(cents(q.paymentFee)).toBe(150);
    expect(cents(q.total)).toBe(cents(base.total) - cents(q.couponDiscount) + 150);
    expect(q.credits.applied).toBe(5);
    expect(cents(q.gatewayAmount)).toBe(cents(q.total) - 500);
    expect(quote({ items: [{ productId: 9, quantity: 1 }], couponCode: 'NOPE' }).coupon.valid).toBe(
      false,
    );

    const variant = quote({ items: [{ productId: 5, variantId: 502, quantity: 2 }] });
    expect(variant.lines[0].variantName).toBe('x5');
    expect(cents(variant.total)).toBe(cents(variant.lines[0].unitPrice) * 2);

    const eur = quote({ items: [{ productId: 9, quantity: 1 }], currency: 'EUR' });
    expect(eur.currency).toBe('EUR');
    expect(cents(eur.total)).toBe(Math.round(1199 * 0.92));
  });

  test('equal lines merge, problems are messages', () => {
    const merged = quote({
      items: [
        { productId: 9, quantity: 1 },
        { productId: 9, quantity: 2, fieldValues: { a: '' } },
      ],
    });
    expect(merged.lines.length).toBe(1);
    expect(merged.lines[0].quantity).toBe(3);
    expect(merged.lines[0].lineKey).toBe('9|0|{}|');

    const soldOut = quote({ items: [{ productId: 6, quantity: 1 }] });
    expect(soldOut.canCheckout).toBe(false);
    expect(soldOut.messages[0]).toEqual({
      code: 'OUT_OF_STOCK',
      level: 'ERROR',
      lineKey: '6|0|{}|',
    });
    expect(soldOut.lines[0].errors).toEqual(['OUT_OF_STOCK']);

    expect(quote({ items: [{ productId: 12345, quantity: 1 }] }).lines).toEqual([]);
    expect(quote({ items: [] }).total).toBe(0);
    expect(
      quote({ items: [{ productId: 1, quantity: 1 }] }).paymentMethods.find(
        (m) => m.id === 'bank-transfer',
      ).available,
    ).toBe(false);
    expect(quote({}).lines.length).toBe(3); // the stored cart
    expect(router.isSafe('POST', '/api/market/checkout/quote')).toBe(true);
  });

  test('cart and checkout config', () => {
    const cart = get('/api/market/me/cart');
    expect(cart.cart.items.length).toBe(3);
    expect(cart.quote.lines.length).toBe(3);
    expect(Object.keys(cart.cart.items[0])).toEqual([
      'id',
      'productId',
      'variantId',
      'quantity',
      'fieldValues',
      'targetServerId',
    ]);

    const config = get('/api/market/checkout/config');
    expect(config.result).toBe('ok');
    expect(config.legal.content).toContain('<p>');
    expect(config.creditTopUp.enabled).toBe(true);
    expect(config.currencies).toEqual(['USD', 'EUR', 'TRY']);
    expect(get('/api/market/me/addresses').addresses[0].isDefault).toBe(true);
  });
});

describe('orders', () => {
  test('order page by public id, status poll', () => {
    const list = orders('few');
    const paid = list.find((o) => o.status === 'COMPLETED');
    const { result, order } = get(`/api/market/orders/${paid.publicId}?token=abc`);

    expect(result).toBe('ok');
    expect(order.publicId).toBe(paid.publicId);
    expect(order.publicId).toMatch(/^[A-Za-z0-9_-]{1,64}$/);
    expect(order.limited).toBe(false);
    expect(order.items.length).toBe(paid.items.length);
    expect(cents(order.totals.subtotal)).toBe(
      order.items.reduce((sum, i) => sum + cents(i.lineTotal), 0),
    );
    expect(cents(order.totals.total)).toBe(
      cents(order.totals.subtotal) -
        cents(order.totals.couponDiscount) +
        cents(order.totals.paymentFee),
    );
    expect(order.payment.status).toBe('SUCCEEDED');
    expect(order.canRetryPayment).toBe(false);

    const pending = get(
      `/api/market/orders/${list.find((o) => o.status === 'PENDING').publicId}`,
    ).order;
    expect(pending.canCancel).toBe(true);
    expect(pending.canRetryPayment).toBe(true);
    expect(pending.paymentMethods.length).toBeGreaterThan(0);
    expect(pending.expiresAt).toBeGreaterThan(pending.createdAt);

    expect(get(`/api/market/orders/${paid.publicId}/status`)).toEqual({
      result: 'ok',
      status: 'COMPLETED',
      paymentStatus: 'SUCCEEDED',
      fulfillmentStatus: paid.fulfillment,
      shippingStatus: 'NOT_REQUIRED',
      updatedAt: paid.paidAt,
    });
    // the drawer's order link opens in every volume
    expect(get(`/api/market/orders/${paid.publicId}`, 'empty').result).toBe('ok');
    expect(get('/api/market/orders/nope')).toEqual({ result: 'error', error: 'NOT_FOUND' });
  });

  test('purchases: orders (status filter, pages, mixed currencies) and entitlements', () => {
    const body = get('/api/market/me/orders');
    expect(body.orderCount).toBe(6);
    expect(Object.keys(body.orders[0])).toEqual([
      'publicId',
      'number',
      'status',
      'fulfillmentStatus',
      'shippingStatus',
      'total',
      'currency',
      'createdAt',
      'paidAt',
      'itemNames',
      'isGift',
      'recipientUsername',
      'received',
    ]);
    expect(body.orders[0].createdAt).toBeGreaterThan(body.orders[1].createdAt);
    expect(new Set(body.orders.map((o) => o.currency)).size).toBeGreaterThan(1);

    const many = get('/api/market/me/orders?page=5', 'many');
    expect(many.orderCount).toBe(47);
    expect(many.totalPage).toBe(5);
    expect(many.orders.length).toBe(7);
    expect(get('/api/market/me/orders?page=6', 'many').error).toBe('PAGE_NOT_FOUND');

    const filtered = get('/api/market/me/orders?status=PENDING,CANCELLED&pageSize=50', 'many');
    expect(filtered.orders.length).toBeGreaterThan(0);
    expect(filtered.orders.every((o) => ['PENDING', 'CANCELLED'].includes(o.status))).toBe(true);

    const all = get('/api/market/me/entitlements').entitlements;
    const active = get('/api/market/me/entitlements?active=true').entitlements;
    expect(Object.keys(all[0])).toEqual([
      'id',
      'productId',
      'productName',
      'variantName',
      'status',
      'startsAt',
      'expiresAt',
      'subscriptionId',
      'orderPublicId',
    ]);
    expect(active.length).toBeLessThanOrEqual(all.length);
    expect(active.every((e) => e.status === 'ACTIVE')).toBe(true);
  });
});

describe('profile', () => {
  test('credits ledger', () => {
    const body = get('/api/market/me/credits');
    expect(body.balance).toBe(1234.5);
    expect(body.creditName).toBe('Coins');
    expect(body.entryCount).toBe(8);
    expect(body.entries[0].balanceAfter).toBe(body.balance);
    expect(cents(body.entries[1].balanceAfter)).toBe(
      cents(body.entries[0].balanceAfter) - cents(body.entries[0].amount),
    );
    expect(body.entries.some((e) => e.amount < 0)).toBe(true);
    expect(body.entries.some((e) => e.orderPublicId)).toBe(true);
    expect(get('/api/market/me/credits?page=3', 'many').entries.length).toBe(17);
    expect(get('/api/market/me/credits?page=4', 'many').error).toBe('PAGE_NOT_FOUND');
  });

  test('subscriptions, summary, creator', () => {
    const { subscriptions: list } = get('/api/market/me/subscriptions');
    expect(list.length).toBe(4);
    expect(Object.keys(list[0])).toEqual([
      'id',
      'productName',
      'status',
      'mode',
      'price',
      'currency',
      'intervalUnit',
      'intervalCount',
      'currentPeriodEnd',
      'graceEndsAt',
      'cancelAtPeriodEnd',
      'endReason',
      'endedAt',
      'methodLabel',
      'storedMethodLabel',
      'canCancel',
      'canResume',
      'canManageAtGateway',
      'renewalOrderPublicId',
    ]);
    expect(new Set(list.map((s) => s.status)).size).toBeGreaterThan(2);
    expect(list.find((s) => s.status === 'PAST_DUE').renewalOrderPublicId).toBe(
      orders('few').find((o) => o.status === 'PENDING').publicId,
    );
    expect(subscriptions('many').length).toBe(14);

    const summary = get('/api/market/me/summary');
    expect(summary).toEqual({
      result: 'ok',
      creditsEnabled: true,
      creditBalance: 1234.5,
      creditName: 'Coins',
      cartItemCount: 4,
      activeSubscriptionCount: 3,
      subscriptionCount: 4,
      isCreator: true,
    });

    const creator = get('/api/market/me/creator');
    expect(creator.codes.length).toBe(2);
    expect(creator.earningCount).toBe(6);
    expect(creator.totals.currency).toBe('USD');
    expect(creator.payouts.length).toBe(2);
  });

  test('widgets', () => {
    const body = get('/api/market/widgets');
    expect(body.recentBuyers.length).toBe(5);
    expect(body.topSupporters[0].rank).toBe(1);
    expect(body.goals.length).toBe(2);
    expect(body.stats.productsTotal).toBe(9);
    expect(body.sidebars).toEqual(['home', 'profile']);
    expect(Object.keys(get('/api/market/widgets?include=stats'))).toEqual([
      'result',
      'stats',
      'sidebars',
    ]);
  });
});

describe('determinism and pages', () => {
  test('the same request always answers the same body', () => {
    for (const volume of ['empty', 'few', 'many'])
      for (const route of routes.filter((r) => r.method === 'GET' && !r.path.includes(':'))) {
        const a = JSON.stringify(route.handler({ query: {}, params: {}, volume }));
        const b = JSON.stringify(route.handler({ query: {}, params: {}, volume }));

        expect(a).toBe(b);
        expect(JSON.parse(a).result).toBeDefined();
      }

    expect(JSON.stringify(quoteOf({ items: [{ productId: 2, quantity: 2 }] }, 'few'))).toBe(
      JSON.stringify(quoteOf({ items: [{ productId: 2, quantity: 2 }] }, 'few')),
    );
    expect(orders('few')[0].publicId).toMatch(/^[0-9A-Za-z]{20}$/);
    expect(
      orders('many')
        .slice(0, 6)
        .map((o) => o.publicId),
    ).toEqual(orders('few').map((o) => o.publicId));
  });

  test('every drawer page points at something the fixtures answer', () => {
    expect(pages.length).toBeGreaterThan(10);

    for (const page of pages) {
      expect(page.side).toBe('theme');
      expect(page.href.startsWith('/')).toBe(true);

      const order = /^\/store\/order\/(.+)$/.exec(page.href);
      const product = /^\/store\/([^/?]+)$/.exec(page.href);

      if (order) expect(get(`/api/market/orders/${order[1]}`).result).toBe('ok');
      else if (product && product[1] !== 'checkout')
        expect(get(`/api/market/products/${product[1]}`).result).toBe('ok');
    }
  });
});

describe('unique keys of every keyed storefront list', () => {
  const dups = (list, key) => {
    const keys = list.map(key);
    return keys.filter((k, i) => keys.indexOf(k) !== i);
  };
  const lists = [
    ['/api/market/store', 'categories', (x) => x.id],
    ['/api/market/store', 'products', (x) => x.id],
    ['/api/market/store', 'featured', (x) => x.id],
    ['/api/market/store', 'bestsellers', (x) => x.id],
    ['/api/market/store', 'comparisons', (x) => x.id],
    ['/api/market/store', 'comparisonProducts', (x) => x.id],
    ['/api/market/store/products', 'products', (x) => x.id],
    ['/api/market/me/orders', 'orders', (x) => x.publicId],
    ['/api/market/me/entitlements', 'entitlements', (x) => x.id],
    ['/api/market/me/subscriptions', 'subscriptions', (x) => x.id],
  ];
  for (const volume of ['empty', 'few', 'many']) {
    for (const [path, key, id] of lists) {
      test(`${path} ${key} (${volume})`, () => {
        const body = router.answer('GET', path, volume);
        expect(dups(body[key] ?? [], id)).toEqual([]);
      });
    }
  }

  test('comparison features have unique ids and every cell is keyed featureId-productId', () => {
    for (const volume of ['few', 'many']) {
      for (const c of router.answer('GET', '/api/market/store', volume).comparisons) {
        expect(dups(c.features, (f) => f.id)).toEqual([]);
        for (const f of c.features)
          for (const pid of c.productIds) expect(c.cellValues[`${f.id}-${pid}`]).toBeDefined();
      }
    }
  });
});

describe('order public ids', () => {
  test('are 20 alphanumeric characters (ORDER_ID of the theme)', () => {
    for (const volume of ['few', 'many']) {
      for (const o of router.answer('GET', '/api/market/me/orders', volume).orders)
        expect(o.publicId).toMatch(/^[0-9A-Za-z]{20}$/);
    }
  });
});
