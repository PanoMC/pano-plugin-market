import { describe, expect, test } from 'bun:test';
import { createRouter } from '../core.js';
import { COUPON, catalog, orders, pages, quoteOf, routes, subscriptions } from './storefront.js';

const router = createRouter(routes);
const get = (path, volume = 'few') => router.answer('GET', path, volume);
const quote = (body, volume = 'few') =>
  router.answer('POST', '/plugins/pano-plugin-market/checkout/quote', volume, body).quote;
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
    const body = get('/plugins/pano-plugin-market/store');

    expect(body).not.toHaveProperty('result');
    expect(body).not.toHaveProperty('error');
    expect(body.settings.currency).toBe('USD');
    expect(body.settings.pageSize).toBe(12);
    expect(body.settings.modules.saleBadges).toBe(true);
    expect(body.categories.length).toBe(4);
    expect(body.categories[0].children.length).toBe(2);
    expect(body.categories[0].productsCount).toBeGreaterThan(0);
    // the first page of the list, in the core page shape (items and page, extras beside them)
    expect(body.items.length).toBe(9);
    expect(body.page).toEqual({ number: 1, size: 12, totalItems: 9, totalPages: 1 });
    expect(body).not.toHaveProperty('products');
    expect(body).not.toHaveProperty('productCount');
    expect(body).not.toHaveProperty('totalPage');
    expect(Object.keys(body.items[0])).toEqual(CARD_KEYS);
    expect(body.featured.length).toBeGreaterThan(0);
    expect(body.bestsellers.length).toBeGreaterThan(0);
    expect(body.comparisons[0].productIds).toEqual(body.comparisonProducts.map((p) => p.id));
  });

  test('the catalogue has sale, out of stock, subscription, bundle and variant products', () => {
    const { items: products } = get('/plugins/pano-plugin-market/store/products?pageSize=60');

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
    const first = get('/plugins/pano-plugin-market/store/products', 'many');
    const second = get('/plugins/pano-plugin-market/store/products?page=2', 'many');

    expect(first.items.length).toBe(12);
    expect(first.page).toEqual({ number: 1, size: 12, totalItems: 64, totalPages: 6 });
    expect(second.page.number).toBe(2);
    expect(second.items[0].id).not.toBe(first.items[0].id);
    expect(get('/plugins/pano-plugin-market/store/products?page=6', 'many').items.length).toBe(4);
    expect(get('/plugins/pano-plugin-market/store/products?page=7', 'many')).toEqual({
      error: { code: 'PAGE_NOT_FOUND' },
    });
    // the core page rule: the store allows 60 per page, more is refused, never clamped
    expect(get('/plugins/pano-plugin-market/store/products?pageSize=60', 'many').items.length).toBe(
      60,
    );
    expect(get('/plugins/pano-plugin-market/store/products?pageSize=500', 'many')).toEqual({
      error: { code: 'INVALID_FIELDS', fields: { pageSize: 'OUT_OF_RANGE' } },
    });
    expect(new Set(catalog('many').products.map((p) => p.slug)).size).toBe(64);
  });

  test('category (with its sub categories), search, sort, featured and kind filters', () => {
    const ranks = get('/plugins/pano-plugin-market/store/products?category=1');
    expect(ranks.page.totalItems).toBeGreaterThan(0);
    expect(ranks.items.every((p) => [1, 101, 102].includes(p.categoryId))).toBe(true);
    expect(
      get('/plugins/pano-plugin-market/store/products?category=101').items.every(
        (p) => p.categoryId === 101,
      ),
    ).toBe(true);
    expect(get('/plugins/pano-plugin-market/store/products?category=999')).toEqual({
      error: { code: 'NOT_FOUND' },
    });

    const search = get('/plugins/pano-plugin-market/store/products?search=RANK');
    expect(search.page.totalItems).toBe(3);
    expect(search.items.every((p) => /rank/i.test(p.name))).toBe(true);
    expect(get('/plugins/pano-plugin-market/store/products?search=zzzz').items).toEqual([]);

    const asc = get(
      '/plugins/pano-plugin-market/store/products?sort=price-asc&pageSize=60',
      'many',
    ).items.map((p) => p.id);
    const desc = get(
      '/plugins/pano-plugin-market/store/products?sort=price-desc&pageSize=60',
      'many',
    ).items.map((p) => p.id);
    const price = (id) => catalog('many').byId.get(id).price;
    for (let i = 1; i < asc.length; i++)
      expect(price(asc[i])).toBeGreaterThanOrEqual(price(asc[i - 1]));
    for (let i = 1; i < desc.length; i++)
      expect(price(desc[i])).toBeLessThanOrEqual(price(desc[i - 1]));

    const newest = get('/plugins/pano-plugin-market/store/products?sort=newest').items.map(
      (p) => p.id,
    );
    expect(newest[0]).toBe(1);
    expect(get('/plugins/pano-plugin-market/store/products?sort=nope').error.code).toBe(
      'BAD_REQUEST',
    );

    expect(
      get('/plugins/pano-plugin-market/store/products?featured=true').items.every(
        (p) => p.featured,
      ),
    ).toBe(true);
    expect(
      get('/plugins/pano-plugin-market/store/products?kind=BUNDLE').items.every(
        (p) => p.kind === 'BUNDLE',
      ),
    ).toBe(true);
  });

  test('?currency= converts the prices of an offered currency', () => {
    const usd = get('/plugins/pano-plugin-market/store/products').items[0];
    const tr = get('/plugins/pano-plugin-market/store/products?currency=try').items[0];

    expect(tr.currency).toBe('TRY');
    expect(tr.price).toBeGreaterThan(usd.price);
    expect(get('/plugins/pano-plugin-market/store/products?currency=JPY').items[0].currency).toBe(
      'USD',
    );
    expect(get('/plugins/pano-plugin-market/store?currency=EUR').settings.displayCurrency).toBe(
      'EUR',
    );
  });

  test('empty volume', () => {
    const body = get('/plugins/pano-plugin-market/store', 'empty');

    expect(body).not.toHaveProperty('error');
    expect(body.categories).toEqual([]);
    expect(body.items).toEqual([]);
    expect(body.page).toEqual({ number: 1, size: 12, totalItems: 0, totalPages: 0 });
    expect(body.comparisons).toEqual([]);
    expect(get('/plugins/pano-plugin-market/store/products', 'empty')).toEqual({
      items: [],
      page: { number: 1, size: 12, totalItems: 0, totalPages: 0 },
    });
    expect(get('/plugins/pano-plugin-market/me/orders', 'empty')).toEqual({
      items: [],
      page: { number: 1, size: 10, totalItems: 0, totalPages: 0 },
    });
    expect(get('/plugins/pano-plugin-market/me/entitlements', 'empty').items).toEqual([]);
    expect(get('/plugins/pano-plugin-market/me/credits', 'empty').items).toEqual([]);
    expect(get('/plugins/pano-plugin-market/me/credits', 'empty').balance).toBe(0);
    expect(get('/plugins/pano-plugin-market/me/subscriptions', 'empty').items).toEqual([]);
    expect(get('/plugins/pano-plugin-market/me/creator', 'empty')).toEqual({
      error: { code: 'NOT_FOUND' },
    });
    expect(get('/plugins/pano-plugin-market/me/cart', 'empty').quote.canCheckout).toBe(false);
    expect(get('/plugins/pano-plugin-market/widgets', 'empty').recentBuyers).toEqual([]);
  });
});

describe('product', () => {
  test('detail by slug', () => {
    const body = get('/plugins/pano-plugin-market/products/vip-rank');
    const { product } = body;

    expect(body).not.toHaveProperty('error');
    expect(body).not.toHaveProperty('result');
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
    const keys = get('/plugins/pano-plugin-market/products/legendary-crate-key-x5').product;
    expect(keys.variantOptions[0].values.length).toBe(3);
    expect(keys.variants.length).toBe(3);
    expect(keys.variants[1].optionValues).toEqual({ amount: '5' });
    expect(keys.price).toBe(Math.min(...keys.variants.map((v) => v.price)));

    const wings = get('/plugins/pano-plugin-market/products/cosmetic-wings', 'many').product;
    expect(wings.variantOptions.length).toBe(2);
    expect(wings.variants.length).toBe(6);
    expect(wings.variants.some((v) => !v.inStock)).toBe(true);

    const kit = get('/plugins/pano-plugin-market/products/starter-kit').product;
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
      '/plugins/pano-plugin-market/products/fly-pass-permanent-all-survival-worlds-and-the-creative-plot-server',
    ).product;
    expect(fly.fields.map((f) => f.type)).toEqual(['USERNAME', 'SELECT']);
    expect(fly.serverChoices.length).toBe(2);

    expect(
      get('/plugins/pano-plugin-market/products/mythic-crate-key').product.purchasable,
    ).toEqual({
      ok: false,
      reason: 'OUT_OF_STOCK',
    });
    expect(
      get('/plugins/pano-plugin-market/products/mvp-rank-lifetime').product.requiredProducts[0]
        .slug,
    ).toBe('vip-rank');
    expect(get('/plugins/pano-plugin-market/products/nope')).toEqual({
      error: { code: 'NOT_FOUND' },
    });
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
    expect(router.isSafe('POST', '/plugins/pano-plugin-market/checkout/quote')).toBe(true);
  });

  test('cart and checkout config', () => {
    const cart = get('/plugins/pano-plugin-market/me/cart');
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

    const config = get('/plugins/pano-plugin-market/checkout/config');
    expect(config).not.toHaveProperty('error');
    expect(config).not.toHaveProperty('result');
    expect(config.legal.content).toContain('<p>');
    expect(config.creditTopUp.enabled).toBe(true);
    expect(config.currencies).toEqual(['USD', 'EUR', 'TRY']);
    expect(get('/plugins/pano-plugin-market/me/addresses').items[0].isDefault).toBe(true);
  });
});

describe('orders', () => {
  test('order page by public id, status poll', () => {
    const list = orders('few');
    const paid = list.find((o) => o.status === 'COMPLETED');
    const answer = get(`/plugins/pano-plugin-market/orders/${paid.publicId}?token=abc`);
    const { order } = answer;

    expect(answer).not.toHaveProperty('error');
    expect(answer).not.toHaveProperty('result');
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
      `/plugins/pano-plugin-market/orders/${list.find((o) => o.status === 'PENDING').publicId}`,
    ).order;
    expect(pending.canCancel).toBe(true);
    expect(pending.canRetryPayment).toBe(true);
    expect(pending.paymentMethods.length).toBeGreaterThan(0);
    expect(pending.expiresAt).toBeGreaterThan(pending.createdAt);

    expect(get(`/plugins/pano-plugin-market/orders/${paid.publicId}/status`)).toEqual({
      status: 'COMPLETED',
      paymentStatus: 'SUCCEEDED',
      fulfillmentStatus: paid.fulfillment,
      shippingStatus: 'NOT_REQUIRED',
      updatedAt: paid.paidAt,
    });
    // the drawer's order link opens in every volume
    expect(get(`/plugins/pano-plugin-market/orders/${paid.publicId}`, 'empty').order).toBeDefined();
    expect(get('/plugins/pano-plugin-market/orders/nope')).toEqual({
      error: { code: 'NOT_FOUND' },
    });
  });

  test('purchases: orders (status filter, pages, mixed currencies) and entitlements', () => {
    const body = get('/plugins/pano-plugin-market/me/orders');
    expect(body.page).toEqual({ number: 1, size: 10, totalItems: 6, totalPages: 1 });
    expect(Object.keys(body.items[0])).toEqual([
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
    expect(body.items[0].createdAt).toBeGreaterThan(body.items[1].createdAt);
    expect(new Set(body.items.map((o) => o.currency)).size).toBeGreaterThan(1);

    const many = get('/plugins/pano-plugin-market/me/orders?page=5', 'many');
    expect(many.page).toEqual({ number: 5, size: 10, totalItems: 47, totalPages: 5 });
    expect(many.items.length).toBe(7);
    expect(get('/plugins/pano-plugin-market/me/orders?page=6', 'many')).toEqual({
      error: { code: 'PAGE_NOT_FOUND' },
    });

    const filtered = get(
      '/plugins/pano-plugin-market/me/orders?status=PENDING,CANCELLED&pageSize=50',
      'many',
    );
    expect(filtered.items.length).toBeGreaterThan(0);
    expect(filtered.items.every((o) => ['PENDING', 'CANCELLED'].includes(o.status))).toBe(true);

    const all = get('/plugins/pano-plugin-market/me/entitlements').items;
    const active = get('/plugins/pano-plugin-market/me/entitlements?active=true').items;
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
    const body = get('/plugins/pano-plugin-market/me/credits');
    expect(body.balance).toBe(1234.5);
    expect(body.creditName).toBe('Coins');
    expect(body.page.totalItems).toBe(8);
    expect(body.items[0].balanceAfter).toBe(body.balance);
    expect(cents(body.items[1].balanceAfter)).toBe(
      cents(body.items[0].balanceAfter) - cents(body.items[0].amount),
    );
    expect(body.items.some((e) => e.amount < 0)).toBe(true);
    expect(body.items.some((e) => e.orderPublicId)).toBe(true);
    expect(get('/plugins/pano-plugin-market/me/credits?page=3', 'many').items.length).toBe(17);
    expect(get('/plugins/pano-plugin-market/me/credits?page=4', 'many')).toEqual({
      error: { code: 'PAGE_NOT_FOUND' },
    });
  });

  test('subscriptions, summary, creator', () => {
    const { items: list } = get('/plugins/pano-plugin-market/me/subscriptions');
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

    const summary = get('/plugins/pano-plugin-market/me/summary');
    expect(summary).toEqual({
      creditsEnabled: true,
      creditBalance: 1234.5,
      creditName: 'Coins',
      cartItemCount: 4,
      activeSubscriptionCount: 3,
      subscriptionCount: 4,
      isCreator: true,
    });

    const creator = get('/plugins/pano-plugin-market/me/creator');
    expect(creator.codes.length).toBe(2);
    expect(creator.page.totalItems).toBe(6);
    expect(creator.items.length).toBe(6);
    expect(creator.totals.currency).toBe('USD');
    expect(creator.payouts.length).toBe(2);
  });

  test('widgets', () => {
    const body = get('/plugins/pano-plugin-market/widgets');
    expect(body.recentBuyers.length).toBe(5);
    expect(body.topSupporters[0].rank).toBe(1);
    expect(body.goals.length).toBe(2);
    expect(body.stats.productsTotal).toBe(9);
    expect(body.sidebars).toEqual(['home', 'profile']);
    expect(Object.keys(get('/plugins/pano-plugin-market/widgets?include=stats'))).toEqual([
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
        // no `result` key on any answer: a success has no `error` key, a failure is the envelope
        expect(JSON.parse(a)).not.toHaveProperty('result');
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

      if (order) expect(get(`/plugins/pano-plugin-market/orders/${order[1]}`).order).toBeDefined();
      else if (product && product[1] !== 'checkout')
        expect(get(`/plugins/pano-plugin-market/products/${product[1]}`).product).toBeDefined();
    }
  });
});

describe('unique keys of every keyed storefront list', () => {
  const dups = (list, key) => {
    const keys = list.map(key);
    return keys.filter((k, i) => keys.indexOf(k) !== i);
  };
  const lists = [
    ['/plugins/pano-plugin-market/store', 'categories', (x) => x.id],
    ['/plugins/pano-plugin-market/store', 'items', (x) => x.id],
    ['/plugins/pano-plugin-market/store', 'featured', (x) => x.id],
    ['/plugins/pano-plugin-market/store', 'bestsellers', (x) => x.id],
    ['/plugins/pano-plugin-market/store', 'comparisons', (x) => x.id],
    ['/plugins/pano-plugin-market/store', 'comparisonProducts', (x) => x.id],
    ['/plugins/pano-plugin-market/store/products', 'items', (x) => x.id],
    ['/plugins/pano-plugin-market/me/orders', 'items', (x) => x.publicId],
    ['/plugins/pano-plugin-market/me/entitlements', 'entitlements', (x) => x.id],
    ['/plugins/pano-plugin-market/me/subscriptions', 'subscriptions', (x) => x.id],
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
      for (const c of router.answer('GET', '/plugins/pano-plugin-market/store', volume)
        .comparisons) {
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
      for (const o of router.answer('GET', '/plugins/pano-plugin-market/me/orders', volume).items)
        expect(o.publicId).toMatch(/^[0-9A-Za-z]{20}$/);
    }
  });
});
