// The shared fake store behind the preview fixtures: categories, products and orders of a volume.
// Pure and deterministic (kit.js only). The rows are the exact LIST shapes of the panel endpoints;
// money on the wire is a decimal number (the backend's MoneyUtil.toDecimal), not minor units.
import {
  CATEGORY_NAMES,
  CURRENCIES,
  DAY,
  NOW,
  PLAYERS,
  PRODUCT_NAMES,
  countFor,
  rows,
  uuidFor,
} from './../kit.js';

const HOUR = 3600000;
const cache = new Map();

/** One array per (name, volume): the handlers and the detail fixtures share the same objects. */
function memo(name, volume, build) {
  const key = `${name}:${volume}`;
  if (!cache.has(key)) cache.set(key, build());
  return cache.get(key);
}

/** Weighted pick: `table` is [[value, weight], ...], `r` a float in [0, 1). */
export function weighted(r, table) {
  const total = table.reduce((sum, [, w]) => sum + w, 0);
  let at = r * total;
  for (const [value, w] of table) {
    at -= w;
    if (at < 0) return value;
  }
  return table[table.length - 1][0];
}

/** Money with 2 decimals as a Number (no float dust). */
export const money = (value) => Math.round(value * 100) / 100;

/** `id` = the `paymentMethodId` of an order, `label` its frozen `paymentLabel`. */
export const PAYMENT_METHODS = [
  { id: 'stripe', label: 'Credit / Debit Card', providerId: 'pano-plugin-market-stripe' },
  { id: 'paypal', label: 'PayPal', providerId: 'pano-plugin-market-paypal' },
  { id: 'bank-transfer', label: 'Bank transfer (EFT / wire)', providerId: 'bank-transfer' },
  { id: 'credits', label: 'Store credit', providerId: 'credits' },
];

export const SERVERS = [
  { id: 1, name: 'Survival' },
  { id: 2, name: 'Skyblock' },
  { id: 3, name: 'Creative Plots' },
  { id: 4, name: 'Lobby (BungeeCord proxy network hub)' },
];

const CATEGORY_ICONS = [
  'fa-crown',
  'fa-box-open',
  'fa-key',
  'fa-hat-wizard',
  'fa-coins',
  'fa-paw',
  'fa-ticket',
  'fa-ghost',
];
const CATEGORY_COLORS = [
  '#f59f00',
  '#2f9e44',
  '#1971c2',
  '#9c36b5',
  '#e8590c',
  '#0ca678',
  '#e03131',
  '#495057',
];

/** Flat category rows (with `parentId`); GET /categories nests them into `children`. */
export function categoryRows(volume) {
  return memo('categories', volume, () => {
    const n = countFor(volume, 5, 24);
    const roots = Math.min(n, CATEGORY_NAMES.length);
    return rows('category', n, (i, random) => {
      const root = i < roots;
      const parentId = root ? null : 1 + ((i - roots) % Math.min(roots, 4));
      const base = CATEGORY_NAMES[i % CATEGORY_NAMES.length];
      const createdAt = NOW - (200 - i) * DAY;
      return {
        id: i + 1,
        name: root ? base : `${CATEGORY_NAMES[parentId - 1]} / Tier ${i - roots + 1}`,
        description: i % 3 === 0 ? null : `Everything about ${base.toLowerCase()} on the server.`,
        icon: CATEGORY_ICONS[i % CATEGORY_ICONS.length],
        color: CATEGORY_COLORS[i % CATEGORY_COLORS.length],
        status: weighted(random(), [
          ['ACTIVE', 7],
          ['INACTIVE', 1],
          ['HIDDEN', 1],
        ]),
        parentId,
        position: i,
        imageFileName: null,
        tiered: i === 0,
        upgradeMode: i === 0 ? 'DIFFERENCE' : 'FULL',
        productsCount: 0,
        createdAt,
        updatedAt: createdAt + int01(random) * 30 * DAY,
      };
    }).map((row, _, all) => ({
      ...row,
      productsCount: productCountOf(volume, row.id, all.length),
    }));
  });
}

const int01 = (random) => Math.floor(random() * 100) / 100;

const productCategory = (i, categoryCount) => (categoryCount ? 1 + (i % categoryCount) : null);

function productCountOf(volume, categoryId, categoryCount) {
  const n = countFor(volume, 7, 83);
  let count = 0;
  for (let i = 0; i < n; i++) if (productCategory(i, categoryCount) === categoryId) count++;
  return count;
}

/** kit's product names plus one physical item (index 5), so shipping has something to ship. */
export const NAMES = [
  ...PRODUCT_NAMES.slice(0, 5),
  'Creeper Hoodie (official merch)',
  ...PRODUCT_NAMES.slice(5),
];
export const PHYSICAL_INDEX = 5;
const PRODUCT_ICONS = [
  'fa-crown',
  'fa-gem',
  'fa-star',
  'fa-box-open',
  'fa-key',
  'fa-shirt',
  'fa-key',
  'fa-feather',
  'fa-coins',
  'fa-egg',
  'fa-dove',
  'fa-dragon',
  'fa-mountain',
  'fa-gavel',
  'fa-tag',
];
const BASE_PRICES = [
  9.99, 14.99, 49.99, 4.99, 12.5, 34.99, 3.49, 19.99, 5, 24.99, 7.99, 11.99, 29.99, 15, 2.99,
];
const slugOf = (text) =>
  text
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-|-$/g, '');

/** Product rows of GET /products (ProductJson.row), ordered by id. */
export function productRows(volume) {
  return memo('products', volume, () => {
    const n = countFor(volume, 7, 83);
    const categories = categoryRows(volume);
    return rows('product', n, (i, random) => {
      const k = i % NAMES.length;
      const round = Math.floor(i / NAMES.length);
      const name = round === 0 ? NAMES[k] : `${NAMES[k]} #${round + 1}`;
      const categoryId = productCategory(i, categories.length);
      const price = money(BASE_PRICES[k] * (1 + round * 0.5));
      const kind = k === 8 ? 'CREDIT_PACK' : k === 9 ? 'BUNDLE' : 'STANDARD';
      const billingMode = k === 1 ? 'SUBSCRIPTION' : k === 7 ? 'TIMED' : 'ONE_TIME';
      const physical = k === PHYSICAL_INDEX;
      const stock =
        k === 4 ? 0 : k === PHYSICAL_INDEX ? 3 : k % 4 === 0 ? null : Math.floor(random() * 500);
      return {
        id: i + 1,
        slug: slugOf(name),
        name,
        categoryId,
        categoryName: categories.find((c) => c.id === categoryId)?.name ?? null,
        price,
        creditPrice: kind === 'CREDIT_PACK' ? null : k % 3 === 0 ? money(price * 100) : null,
        compareAtPrice: k % 5 === 2 ? money(price * 1.4) : null,
        stock,
        status: weighted(random(), [
          ['ACTIVE', 8],
          ['INACTIVE', 1],
          ['HIDDEN', 1],
          ['ARCHIVED', 1],
        ]),
        featured: k === 0 || k === 2,
        priority: n - i,
        icon: PRODUCT_ICONS[k],
        imageFileName: null,
        kind,
        physical,
        billingMode,
        hasVariants: k === PHYSICAL_INDEX,
        soldCount: Math.floor(random() * random() * 4000),
      };
    });
  });
}

export const ORDER_STATUSES = [
  'PENDING',
  'REVIEW',
  'COMPLETED',
  'PARTIALLY_REFUNDED',
  'REFUNDED',
  'CHARGEBACK',
  'FAILED',
  'CANCELLED',
  'EXPIRED',
];
const UNPAID = new Set(['PENDING', 'FAILED', 'CANCELLED', 'EXPIRED']);
const REVIEW_REASONS = [
  'UNDERPAID',
  'OVERPAID',
  'LATE',
  'AMOUNT_MISMATCH',
  'CURRENCY_MISMATCH',
  'FRAUD_REVIEW',
  'OTHER',
];
const VARIANT_NAMES = ['Red', 'Blue / Large', 'Gold Edition'];

function fulfillmentOf(status, r) {
  if (UNPAID.has(status)) return 'NONE';
  if (status === 'REVIEW') return 'PENDING';
  if (status === 'REFUNDED' || status === 'CHARGEBACK') return r < 0.8 ? 'REVOKED' : 'FULFILLED';
  return weighted(r, [
    ['FULFILLED', 14],
    ['PENDING', 3],
    ['PARTIAL', 2],
    ['FAILED', 1],
  ]);
}

/**
 * Order rows of GET /orders (OrderQueryService.listRow), newest first, unfiltered. The e-mail is
 * the unmasked one (the preview user holds the PII tier).
 */
export function orderRows(volume) {
  return memo('orders', volume, () => {
    const n = countFor(volume, 7, 83);
    const products = productRows(volume);
    return rows('order', n, (i, random) => {
      const id = 1000 + n - i;
      // Every third order ships the physical product and is paid, so the shipping pages have rows.
      const ships = i % 3 === 1 && products.length > PHYSICAL_INDEX;
      const drawn = weighted(random(), [
        ['COMPLETED', 52],
        ['PENDING', 10],
        ['REVIEW', 5],
        ['PARTIALLY_REFUNDED', 6],
        ['REFUNDED', 7],
        ['CHARGEBACK', 3],
        ['FAILED', 7],
        ['CANCELLED', 5],
        ['EXPIRED', 5],
      ]);
      const status = ships && UNPAID.has(drawn) ? 'COMPLETED' : drawn;
      const currency = weighted(
        random(),
        CURRENCIES.map((c, at) => [c, [8, 4, 3, 2][at] ?? 1]),
      );
      const rate = { USD: 1, EUR: 0.9, TRY: 40, GBP: 0.8 }[currency] ?? 1;
      const player = PLAYERS[Math.floor(random() * PLAYERS.length)];
      const isGift = random() < 0.15;
      const recipient =
        PLAYERS[(PLAYERS.indexOf(player) + 1 + Math.floor(random() * 5)) % PLAYERS.length];
      const createdAt = NOW - i * 7 * HOUR - Math.floor(random() * 6 * HOUR);
      const paid = !UNPAID.has(status);
      const paidAt = paid ? createdAt + 20000 + Math.floor(random() * 15 * 60000) : null;

      const lineCount = products.length
        ? weighted(random(), [
            [1, 6],
            [2, 3],
            [3, 1],
          ])
        : 0;
      const items = [];
      for (let line = 0; line < lineCount; line++) {
        const drawnProduct = products[Math.floor(random() * products.length)];
        const product = ships && line === 0 ? products[PHYSICAL_INDEX] : drawnProduct;
        const quantity = weighted(random(), [
          [1, 8],
          [2, 2],
          [5, 1],
        ]);
        const unitPrice = money(product.price * rate);
        items.push({
          id: id * 10 + line,
          productId: product.id,
          productName: product.name,
          variantName: product.hasVariants
            ? VARIANT_NAMES[Math.floor(random() * VARIANT_NAMES.length)]
            : null,
          quantity,
          unitPrice,
          lineTotal: money(unitPrice * quantity),
          createdAt,
          updatedAt: createdAt,
        });
      }
      const totalPrice = money(items.reduce((sum, item) => sum + item.lineTotal, 0));
      const method = weighted(random(), [
        [PAYMENT_METHODS[0], 10],
        [PAYMENT_METHODS[1], 5],
        [PAYMENT_METHODS[2], 2],
        [PAYMENT_METHODS[3], 2],
      ]);
      const mixed = random();
      const creditValue =
        method.id === 'credits' ? totalPrice : mixed < 0.1 ? money(totalPrice * 0.25) : 0;
      const refundedTotal =
        status === 'REFUNDED'
          ? totalPrice
          : status === 'PARTIALLY_REFUNDED'
            ? money(totalPrice * 0.4)
            : 0;
      const physical = items.some((item) => products[item.productId - 1]?.physical);
      const shipRoll = random();
      const shippingStatus = !physical
        ? 'NOT_REQUIRED'
        : !paid
          ? 'PENDING'
          : ships
            ? ['SHIPPED', 'DELIVERED', 'PENDING', 'PARTIAL', 'RETURNED', 'DELIVERED'][
                Math.floor(i / 3) % 6
              ]
            : weighted(shipRoll, [
                ['PENDING', 3],
                ['PARTIAL', 1],
                ['SHIPPED', 3],
                ['DELIVERED', 4],
                ['RETURNED', 1],
              ]);
      const source = weighted(random(), [
        ['STOREFRONT', 14],
        ['PANEL', 2],
        ['GIFT_CODE', 1],
        ['RENEWAL', 2],
        ['EXTERNAL', 1],
      ]);
      const fulfillmentStatus = fulfillmentOf(status, random());
      const testMode = random() < 0.1;
      const reviewReason =
        status === 'REVIEW' ? REVIEW_REASONS[Math.floor(random() * REVIEW_REASONS.length)] : null;

      return {
        id,
        publicId: uuidFor('order', id),
        source,
        userId: source === 'EXTERNAL' ? null : 1 + PLAYERS.indexOf(player),
        playerUsername: player,
        recipientUsername: isGift ? recipient : null,
        isGift,
        email: `${player.toLowerCase().replace(/[^a-z0-9]+/g, '.')}@example.com`,
        totalPrice,
        currency,
        paymentMethodId: method.id,
        paymentLabel: method.label,
        status,
        gatewayAmount: money(totalPrice - creditValue),
        creditValue,
        refundedTotal,
        fulfillmentStatus,
        shippingStatus,
        paidAt,
        testMode,
        reviewReason,
        createdAt,
        updatedAt: (paidAt ?? createdAt) + Math.floor(mixed * 2 * DAY),
        items,
      };
    });
  });
}
