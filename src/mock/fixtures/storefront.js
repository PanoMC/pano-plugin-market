// Storefront (theme side) fixtures of the development-only preview mode. Deterministic: everything is derived
// from kit.js seeds and the fixed NOW. Amounts are kept as x100 integers here and sent as plain decimals, as the
// public endpoints do (MoneyUtil.toDecimal).
import {
  CATEGORY_NAMES,
  DAY,
  NOW,
  PLAYERS,
  PRODUCT_NAMES,
  countFor,
  int,
  matches,
  paginate,
  pick,
  rng,
  uuidFor,
} from './../kit.js';

const API = '/api/market';
const ok = (body) => ({ result: 'ok', ...body });
const fail = (error, extra = {}) => ({ result: 'error', error, ...extra });
const dec = (cents) => cents / 100;
const HOUR = 3600000;

// ------------------------------------------------------------------------------------------------ currencies

export const BASE_CURRENCY = 'USD';
const RATES = { USD: 1, EUR: 0.92, TRY: 41.5, GBP: 0.79 };
const SYMBOLS = { USD: '$', EUR: '€', TRY: '\u20BA', GBP: '£' };
const OFFERED = ['USD', 'EUR', 'TRY'];

/** The offered currency of `?currency=` (upper-cased), else the store currency. */
function currencyOf(raw) {
  const code = String(raw || '').toUpperCase();

  return OFFERED.includes(code) ? code : BASE_CURRENCY;
}

const convert = (cents, currency) => Math.round(cents * (RATES[currency] || 1));

// ------------------------------------------------------------------------------------------------ catalogue

const VAT_BP = 2000; // 20 %, prices include VAT
const PAGE_SIZE = 12;
const MAX_PAGE_SIZE = 60;
const ICONS = [
  'fa-crown',
  'fa-box-open',
  'fa-key',
  'fa-hat-wizard',
  'fa-coins',
  'fa-dragon',
  'fa-bolt',
  'fa-ghost',
];
const COLORS = [
  '#f5a524',
  '#17c964',
  '#7828c8',
  '#f31260',
  '#006fee',
  '#f97316',
  '#06b6d4',
  '#a16207',
];
const SERVERS = [
  { id: 1, name: 'Survival', type: 'PAPER' },
  { id: 2, name: 'Skyblock', type: 'PAPER' },
  { id: 3, name: 'Lobby Network (proxy)', type: 'VELOCITY' },
];

const slugify = (text) =>
  text
    .toLowerCase()
    .replace(/\+/g, ' plus ')
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '');

function buildCategories(volume) {
  const top = volume === 'empty' ? 0 : volume === 'many' ? CATEGORY_NAMES.length : 4;
  const list = [];

  for (let i = 0; i < top; i++) {
    list.push({
      id: i + 1,
      name: CATEGORY_NAMES[i],
      description:
        i % 3 === 2 ? null : `Everything about ${CATEGORY_NAMES[i].toLowerCase()} on the server.`,
      icon: ICONS[i % ICONS.length],
      color: COLORS[i % COLORS.length],
      parentId: null,
      position: i,
      imageFileName: null,
      tiered: i === 0,
    });
  }

  // Sub categories: "Ranks" always has two, the big volume adds one under "Crates and Keys".
  const children =
    top === 0
      ? []
      : [
          [1, 'Monthly Ranks'],
          [1, 'Lifetime Ranks'],
        ];
  if (volume === 'many') children.push([3, 'Event Keys']);

  children.forEach(([parentId, name], k) => {
    list.push({
      id: 100 + k + 1,
      name,
      description: null,
      icon: null,
      color: null,
      parentId,
      position: k,
      imageFileName: null,
      tiered: false,
    });
  });

  return list;
}

// What makes a product special, by its index in PRODUCT_NAMES (every volume but `empty` has the first nine).
const ARCHETYPES = {
  0: {
    billingMode: 'SUBSCRIPTION',
    period: { unit: 'MONTH', count: 1 },
    price: 999,
    featured: true,
    categoryId: 101,
  },
  1: {
    billingMode: 'TIMED',
    period: { unit: 'DAY', count: 30 },
    price: 1499,
    compareAt: 1999,
    sale: 'percent',
    featured: true,
    categoryId: 101,
  },
  2: { price: 7999, compareAt: 9999, sale: 'amount', categoryId: 102, requires: [1] },
  3: {
    kind: 'BUNDLE',
    price: 1299,
    compareAt: 1800,
    sale: 'percent',
    featured: true,
    categoryId: 2,
  },
  4: { variants: 'amount', price: 499, categoryId: 3 },
  5: { price: 899, stock: 0, categoryId: 3 },
  6: { price: 2499, categoryId: 4, servers: [1, 2], fields: true },
  7: { kind: 'CREDIT_PACK', price: 500, categoryId: 4 },
  8: { price: 1199, stock: 3, categoryId: 2, fields: true },
  9: { variants: 'matrix', price: 699, categoryId: 4 },
};

function buildProducts(volume, categories) {
  const n = countFor(volume, 9, 64);
  const topIds = categories.filter((c) => c.parentId === null).map((c) => c.id);
  const allIds = new Set(categories.map((c) => c.id));
  const list = [];

  for (let i = 0; i < n; i++) {
    const base = i % PRODUCT_NAMES.length;
    const round = Math.floor(i / PRODUCT_NAMES.length);
    const a = round === 0 ? ARCHETYPES[base] || {} : {};
    const name = round === 0 ? PRODUCT_NAMES[base] : `${PRODUCT_NAMES[base]} - Season ${round + 1}`;
    const r = rng(`store:product:${i}`);
    const price = a.price ?? int('store:price', i, 3, 80) * 100 - 1;
    const randomSale = round > 0 && r() < 0.25;
    const compareAt =
      a.compareAt ?? (randomSale ? Math.round((price * 1.3) / 100) * 100 - 1 : null);
    const categoryId = allIds.has(a.categoryId) ? a.categoryId : pick('store:category', i, topIds);
    const stock = a.stock !== undefined ? a.stock : round > 0 && r() < 0.12 ? 0 : null;

    list.push({
      id: i + 1,
      slug: slugify(name),
      name,
      shortDescription:
        base % 5 === 4
          ? null
          : `${name}: delivered to your account right after the payment, on every server of the network.`,
      categoryId,
      kind: a.kind || 'STANDARD',
      price,
      compareAt,
      saleKind: compareAt ? a.sale || (r() < 0.5 ? 'percent' : 'amount') : null,
      saleEndsAt: compareAt ? (i % 2 === 0 ? NOW + (3 + (i % 5)) * DAY + 5 * HOUR : null) : null,
      stock,
      featured: a.featured === true || (round > 0 && r() < 0.1),
      priority: n - i,
      icon: ICONS[categoryId % ICONS.length],
      billingMode: a.billingMode || 'ONE_TIME',
      period: a.period || null,
      variantKind: a.variants || null,
      tierRank: categoryId === 101 || categoryId === 102 ? base + 1 : null,
      requires: (a.requires || []).filter((id) => id <= n),
      servers: a.servers || [],
      fields: a.fields === true,
      soldCount: int('store:sold', i, 0, 900),
      owned: i % 9 === 2,
      createdAt: NOW - (i * 3 + 2) * DAY - int('store:created', i, 0, 20) * HOUR,
    });
  }

  return list;
}

const VARIANT_AXES = {
  amount: [
    {
      key: 'amount',
      label: 'Amount',
      values: [
        { key: '1', label: 'x1' },
        { key: '5', label: 'x5' },
        { key: '25', label: 'x25 (best value)' },
      ],
    },
  ],
  matrix: [
    {
      key: 'color',
      label: 'Color',
      values: [
        { key: 'red', label: 'Crimson' },
        { key: 'blue', label: 'Ocean' },
        { key: 'gold', label: 'Gold' },
      ],
    },
    {
      key: 'size',
      label: 'Size',
      values: [
        { key: 's', label: 'Small' },
        { key: 'l', label: 'Large' },
      ],
    },
  ],
};

/** Variants of a product in x100: [{ id, name, optionValues, price, compareAt, stock }]. */
function variantsOf(p) {
  if (!p.variantKind) return [];

  const axes = VARIANT_AXES[p.variantKind];
  const out = [];

  if (p.variantKind === 'amount') {
    [1, 5, 25].forEach((count, k) => {
      const full = p.price * count;
      const price = k === 0 ? full : Math.round((full * (k === 1 ? 0.9 : 0.75)) / 10) * 10 - 1;

      out.push({
        id: p.id * 100 + k + 1,
        name: axes[0].values[k].label,
        optionValues: { amount: String(count) },
        price,
        compareAt: k === 0 ? null : full,
        stock: null,
      });
    });
  } else {
    let k = 0;
    for (const color of axes[0].values) {
      for (const size of axes[1].values) {
        out.push({
          id: p.id * 100 + k + 1,
          name: `${color.label} / ${size.label}`,
          optionValues: { color: color.key, size: size.key },
          price: p.price + (size.key === 'l' ? 300 : 0) + (color.key === 'gold' ? 500 : 0),
          compareAt: null,
          stock: k === 3 ? 0 : k === 4 ? 2 : null,
        });
        k++;
      }
    }
  }

  return out;
}

const catalogCache = new Map();

/** The catalogue of a volume: categories, products and the lookups the handlers need. */
export function catalog(volume) {
  if (!catalogCache.has(volume)) {
    const categories = buildCategories(volume);
    const products = buildProducts(volume, categories);

    catalogCache.set(volume, {
      categories,
      products,
      byId: new Map(products.map((p) => [p.id, p])),
      bySlug: new Map(products.map((p) => [p.slug, p])),
    });
  }

  return catalogCache.get(volume);
}

function subtree(categories, rootId) {
  const ids = new Set([rootId]);
  let grew = true;

  while (grew) {
    grew = false;
    for (const c of categories) {
      if (c.parentId !== null && ids.has(c.parentId) && !ids.has(c.id)) {
        ids.add(c.id);
        grew = true;
      }
    }
  }

  return ids;
}

function categoryTree(cat) {
  const node = (c) => ({
    ...c,
    productsCount: cat.products.filter((p) => subtree(cat.categories, c.id).has(p.categoryId))
      .length,
    children: cat.categories.filter((child) => child.parentId === c.id).map(node),
  });

  return cat.categories.filter((c) => c.parentId === null).map(node);
}

const inStock = (p) => {
  const variants = variantsOf(p);

  return variants.length
    ? variants.some((v) => v.stock === null || v.stock > 0)
    : p.stock === null || p.stock > 0;
};

function saleOf(p, currency) {
  if (!p.compareAt) return null;

  return {
    percent: p.saleKind === 'percent' ? Math.round((1 - p.price / p.compareAt) * 100) : null,
    amountOff: p.saleKind === 'amount' ? dec(convert(p.compareAt - p.price, currency)) : null,
    endsAt: p.saleEndsAt,
  };
}

/** `ProductCard` of the store lists. */
function card(p, currency) {
  const variants = variantsOf(p);
  const from = variants.length ? Math.min(...variants.map((v) => v.price)) : p.price;

  return {
    id: p.id,
    slug: p.slug,
    name: p.name,
    shortDescription: p.shortDescription,
    categoryId: p.categoryId,
    kind: p.kind,
    price: dec(convert(from, currency)),
    compareAtPrice: p.compareAt ? dec(convert(p.compareAt, currency)) : null,
    creditPrice: dec(from),
    currency,
    priceFrom: variants.length > 0,
    inStock: inStock(p),
    stock: variants.length ? null : p.stock !== null && p.stock <= 5 ? p.stock : null,
    featured: p.featured,
    priority: p.priority,
    icon: p.icon,
    imageFileName: null,
    physical: false,
    billingMode: p.billingMode,
    period: p.billingMode === 'ONE_TIME' ? null : p.period,
    hasVariants: variants.length > 0,
    tierRank: p.tierRank,
    sale: saleOf(p, currency),
    needsOptions:
      variants.length > 0 || p.billingMode === 'SUBSCRIPTION' || p.fields || p.servers.length > 1,
    owned: p.owned,
  };
}

const BUNDLE_CHILDREN = [
  { index: 4, quantity: 2 },
  { index: 7, quantity: 1 },
  { index: 8, quantity: 3 },
];

/** `ProductDetail` of the product page. */
function detail(p, cat, currency) {
  const category = cat.categories.find((c) => c.id === p.categoryId);
  const variants = variantsOf(p);
  const stocked = inStock(p);

  return {
    ...card(p, currency),
    description:
      `<p><strong>${p.name}</strong> is one of the most popular items of the store.</p>` +
      '<ul><li>Delivered automatically within a minute</li><li>Works on Survival and Skyblock</li>' +
      '<li>Covered by the 14 day refund policy</li></ul>' +
      '<p>You have to be online on the server to receive in-game items. Ranks are applied on your next login.</p>',
    categoryName: category?.name ?? null,
    metaTitle: null,
    metaDescription: p.shortDescription,
    variantOptions: p.variantKind ? VARIANT_AXES[p.variantKind] : null,
    variants: variants.map((v) => ({
      id: v.id,
      name: v.name,
      optionValues: v.optionValues,
      price: dec(convert(v.price, currency)),
      compareAtPrice: v.compareAt ? dec(convert(v.compareAt, currency)) : null,
      creditPrice: dec(v.price),
      inStock: v.stock === null || v.stock > 0,
      stock: v.stock !== null && v.stock <= 5 ? v.stock : null,
      imageFileName: null,
      periodCount: null,
    })),
    fields: p.fields
      ? [
          {
            fieldKey: 'nickname',
            label: 'In-game name of the receiver',
            helpText: 'Leave empty to use your own account.',
            type: 'USERNAME',
            required: false,
            options: null,
            pattern: null,
            minLength: 3,
            maxLength: 16,
            minValue: null,
            maxValue: null,
            placeholder: 'Steve',
            defaultValue: null,
          },
          {
            fieldKey: 'color',
            label: 'Prefix color',
            helpText: null,
            type: 'SELECT',
            required: true,
            options: ['Red', 'Aqua', 'Gold', 'Light purple'],
            pattern: null,
            minLength: null,
            maxLength: null,
            minValue: null,
            maxValue: null,
            placeholder: null,
            defaultValue: 'Gold',
          },
        ]
      : [],
    bundleItems:
      p.kind === 'BUNDLE'
        ? BUNDLE_CHILDREN.filter((b) => cat.products[b.index]).map((b) => ({
            productId: cat.products[b.index].id,
            variantId: 0,
            name: cat.products[b.index].name,
            quantity: b.quantity,
            imageFileName: null,
          }))
        : [],
    requiredProducts: p.requires
      .map((id) => cat.byId.get(id))
      .filter(Boolean)
      .map((r) => ({ id: r.id, name: r.name, slug: r.slug, owned: r.owned })),
    requireOnlyOne: false,
    limitPerPlayer: p.billingMode === 'ONE_TIME' && p.tierRank ? 1 : null,
    maxQuantityPerOrder: p.kind === 'CREDIT_PACK' ? 10 : null,
    cooldownSeconds: null,
    allowGift: p.billingMode !== 'SUBSCRIPTION',
    serverChoices: SERVERS.filter((s) => p.servers.includes(s.id)),
    vatPercent: VAT_BP / 100,
    pricesIncludeVat: true,
    weightGrams: null,
    upgrade: null,
    purchasable: { ok: stocked, reason: stocked ? null : 'OUT_OF_STOCK' },
    durationType: p.billingMode === 'TIMED' ? 'TEMPORARY' : 'PERMANENT',
    durationStart: null,
    durationExpiry: null,
    createdAt: p.createdAt,
    updatedAt: p.createdAt + DAY,
  };
}

const SORTS = {
  priority: (a, b) => b.priority - a.priority || a.id - b.id,
  newest: (a, b) => b.createdAt - a.createdAt || b.id - a.id,
  'price-asc': (a, b) => a.price - b.price || b.priority - a.priority,
  'price-desc': (a, b) => b.price - a.price || b.priority - a.priority,
  bestselling: (a, b) => b.soldCount - a.soldCount || b.priority - a.priority,
};

function settings(currency) {
  return {
    storeName: 'Blockcraft Network Store',
    storeDescription:
      'Ranks, crate keys, kits and cosmetics for the Blockcraft Network. Every purchase keeps the servers online.',
    currency: BASE_CURRENCY,
    currencySymbol: SYMBOLS[BASE_CURRENCY],
    creditsEnabled: true,
    creditName: 'Coins',
    removeCents: false,
    showBestsellers: true,
    showFeaturedProducts: true,
    showComparisons: true,
    currencyMode: 'MULTI',
    currencies: OFFERED,
    currencySymbols: Object.fromEntries(OFFERED.map((c) => [c, SYMBOLS[c]])),
    displayCurrency: currency,
    pricesIncludeVat: true,
    allowGuestCheckout: true,
    allowGiftPurchase: true,
    testMode: false,
    onlyAcceptCredits: false,
    creditTopUpEnabled: true,
    modules: {
      recentBuyers: true,
      topSupporters: true,
      goal: true,
      saleBadges: true,
      saleCountdown: true,
      stats: true,
    },
    pageSize: PAGE_SIZE,
  };
}

function storeBody({ query, volume }) {
  const cat = catalog(volume);
  const currency = currencyOf(query.currency);
  const listed = [...cat.products].sort(SORTS.priority);
  const ranks = cat.products.filter((p) => p.tierRank !== null).slice(0, 3);
  const comparisons =
    ranks.length >= 2
      ? [
          {
            id: 1,
            name: 'Compare the ranks',
            productIds: ranks.map((p) => p.id),
            features: [
              'Chat prefix',
              '/fly in the lobby',
              'Homes',
              'Monthly crate keys',
              'Priority queue',
            ],
            cellValues: Object.fromEntries(
              ranks.map((p, k) => [
                String(p.id),
                {
                  'Chat prefix': 'yes',
                  '/fly in the lobby': k > 0 ? 'yes' : 'no',
                  Homes: String(3 * (k + 1)),
                  'Monthly crate keys': k === 0 ? '-' : `${k * 2}`,
                  'Priority queue': k === 2 ? 'yes' : 'no',
                },
              ]),
            ),
          },
        ]
      : [];

  return ok({
    settings: settings(currency),
    categories: categoryTree(cat),
    products: listed.slice(0, PAGE_SIZE).map((p) => card(p, currency)),
    productCount: listed.length,
    totalPage: Math.max(1, Math.ceil(listed.length / PAGE_SIZE)),
    featured: listed
      .filter((p) => p.featured)
      .slice(0, 6)
      .map((p) => card(p, currency)),
    bestsellers: [...cat.products]
      .sort(SORTS.bestselling)
      .slice(0, 6)
      .map((p) => card(p, currency)),
    comparisons,
    comparisonProducts: ranks.length >= 2 ? ranks.map((p) => card(p, currency)) : [],
  });
}

function productsBody({ query, volume }) {
  const cat = catalog(volume);
  const currency = currencyOf(query.currency);
  let ids = null;

  if (query.category !== undefined && query.category !== '') {
    const id = Number(query.category);
    if (!Number.isInteger(id) || id < 1) return fail('BAD_REQUEST');
    if (!cat.categories.some((c) => c.id === id)) return fail('NOT_FOUND');
    ids = subtree(cat.categories, id);
  }

  const sort = query.sort ? SORTS[query.sort] : SORTS.priority;
  if (!sort) return fail('BAD_REQUEST');

  const featured = query.featured === 'true' ? true : query.featured === 'false' ? false : null;
  const listed = cat.products
    .filter(
      (p) =>
        (!ids || ids.has(p.categoryId)) &&
        (featured === null || p.featured === featured) &&
        (!query.kind || p.kind === query.kind) &&
        matches(query.search, p.name, p.shortDescription),
    )
    .sort(sort);
  const pageSize = Math.min(MAX_PAGE_SIZE, Math.max(1, parseInt(query.pageSize) || PAGE_SIZE));
  const page = paginate(listed, { ...query, pageSize }, PAGE_SIZE);

  if (page.error) return fail(page.error);

  return ok({
    products: page.rows.map((p) => card(p, currency)),
    productCount: page.count,
    totalPage: page.totalPage,
  });
}

function productBody({ params, query, volume }) {
  const cat = catalog(volume);
  const p = cat.bySlug.get(params.slug);

  return p ? ok({ product: detail(p, cat, currencyOf(query.currency)) }) : fail('NOT_FOUND');
}

// ------------------------------------------------------------------------------------------------ widgets

function widgetsBody({ query, volume }) {
  const cat = catalog(volume);
  const include = query.include
    ? new Set(
        String(query.include)
          .split(',')
          .map((s) => s.trim()),
      )
    : null;
  const want = (name) => !include || include.has(name);
  const n = countFor(volume, 5, 10);
  const names = (i) => {
    const first = cat.products[int('widget:product', i, 0, Math.max(0, cat.products.length - 1))];

    return first ? [first.name] : [];
  };
  const body = {};

  if (want('recentBuyers'))
    body.recentBuyers = Array.from({ length: n }, (_, i) => ({
      username: PLAYERS[i % PLAYERS.length],
      productNames: names(i),
      amount: dec(int('widget:amount', i, 3, 90) * 100 - 1),
      currency: BASE_CURRENCY,
      createdAt: NOW - (i + 1) * 47 * 60000,
    }));

  if (want('topSupporters'))
    body.topSupporters = Array.from({ length: Math.min(n, 5) }, (_, i) => ({
      username: PLAYERS[(i * 5 + 3) % PLAYERS.length],
      total: dec((60 - i * 9) * 1000 - 1),
      rank: i + 1,
    }));

  if (want('goals'))
    body.goals =
      volume === 'empty'
        ? []
        : [
            {
              id: 1,
              name: 'October server costs',
              description: 'Hosting, DDoS protection and the new Skyblock machine.',
              metric: 'REVENUE',
              target: 500,
              progress: 342.5,
              percent: 68,
              currency: BASE_CURRENCY,
              endsAt: NOW + 30 * DAY,
            },
            {
              id: 2,
              name: 'Community goal: 250 crate keys opened this month for the Halloween event',
              description: null,
              metric: 'PRODUCT_SALES',
              target: 250,
              progress: 250,
              percent: 100,
              currency: null,
              endsAt: null,
            },
          ];

  if (want('stats'))
    body.stats = {
      ordersToday: volume === 'empty' ? 0 : countFor(volume, 4, 37),
      ordersTotal: volume === 'empty' ? 0 : countFor(volume, 128, 15482),
      customersTotal: volume === 'empty' ? 0 : countFor(volume, 61, 6210),
      productsTotal: cat.products.length,
    };

  body.sidebars = ['home', 'profile'];

  return ok(body);
}

// ------------------------------------------------------------------------------------------------ quote / checkout

const canonical = (fieldValues) => {
  const out = {};

  if (fieldValues && typeof fieldValues === 'object' && !Array.isArray(fieldValues))
    for (const key of Object.keys(fieldValues).sort()) {
      const value = fieldValues[key];
      if (value !== '' && value !== null && value !== undefined) out[key] = value;
    }

  return out;
};

const keyOf = (l) =>
  `${l.productId}|${l.variantId || 0}|${JSON.stringify(canonical(l.fieldValues))}|${l.targetServerId || ''}`;

export const COUPON = { code: 'WELCOME10', percent: 10 };
export const CREDIT_BALANCE = 123450; // 1234.50 credits
const CARD_FEE = 0;
const TRANSFER_FEE = 150;

const METHODS = [
  {
    id: 'mock-card',
    label: 'Credit / debit card',
    description: 'Visa, Mastercard, American Express',
    icon: 'fa-credit-card',
    color: '#635bff',
    fee: CARD_FEE,
    recurring: 'GATEWAY_MANAGED',
  },
  {
    id: 'bank-transfer',
    label: 'Bank transfer',
    description: 'Approved by hand within one business day',
    icon: 'fa-building-columns',
    color: null,
    fee: TRANSFER_FEE,
    recurring: null,
  },
  {
    id: 'mock-wallet',
    label: 'A wallet with a very long name that is not available in your country',
    description: null,
    icon: 'fa-wallet',
    color: null,
    fee: 0,
    recurring: null,
    unavailable: 'CURRENCY_NOT_SUPPORTED',
  },
];

/** The lines of the stored cart of the logged-in preview user (a quote without `items` prices these). */
function storedCart(volume) {
  const cat = catalog(volume);
  if (!cat.products.length) return [];

  const lines = [
    { id: 1, productId: 2, variantId: 0, quantity: 1, fieldValues: {}, targetServerId: null },
    { id: 2, productId: 5, variantId: 502, quantity: 2, fieldValues: {}, targetServerId: null },
    { id: 3, productId: 4, variantId: 0, quantity: 1, fieldValues: {}, targetServerId: null },
  ];

  return lines.filter((l) => cat.byId.has(l.productId));
}

/** `Quote` of a `CartInput` body: totals are computed from the posted lines with the fixture prices. */
export function quoteOf(body, volume) {
  const input = body && typeof body === 'object' ? body : {};
  const cat = catalog(volume);
  const currency = currencyOf(input.currency);
  const posted = Array.isArray(input.items) ? input.items : storedCart(volume);
  const messages = [];

  // equal lines are summed, like the backend does
  const merged = new Map();
  for (const item of posted) {
    if (!item || typeof item !== 'object') continue;
    const key = keyOf(item);
    const quantity = Math.max(1, Math.min(99, parseInt(item.quantity) || 1));
    if (merged.has(key))
      merged.get(key).quantity = Math.min(99, merged.get(key).quantity + quantity);
    else merged.set(key, { ...item, quantity });
  }

  const lines = [];
  for (const [lineKey, item] of merged) {
    const p = cat.byId.get(Number(item.productId));

    if (!p) {
      messages.push({ code: 'PRODUCT_UNAVAILABLE', level: 'ERROR', lineKey });
      continue;
    }

    const variants = variantsOf(p);
    const variant = variants.find((v) => v.id === Number(item.variantId)) || null;
    const errors = [];

    if (variants.length && !variant) errors.push('VARIANT_REQUIRED');

    const stock = variant ? variant.stock : variants.length ? null : p.stock;
    if (stock !== null && stock < item.quantity) errors.push('OUT_OF_STOCK');

    const unit = convert(
      variant
        ? variant.price
        : variants.length
          ? Math.min(...variants.map((v) => v.price))
          : p.price,
      currency,
    );
    const compareAt = variant ? variant.compareAt : variants.length ? null : p.compareAt;
    const list = compareAt ? Math.max(unit, convert(compareAt, currency)) : unit;
    const lineTotal = unit * item.quantity;

    for (const code of errors) messages.push({ code, level: 'ERROR', lineKey });

    lines.push({
      lineKey,
      productId: p.id,
      variantId: variant ? variant.id : 0,
      name: p.name,
      variantName: variant ? variant.name : null,
      slug: p.slug,
      imageFileName: null,
      quantity: item.quantity,
      maxQuantity: stock !== null ? Math.max(1, stock) : p.kind === 'CREDIT_PACK' ? 10 : 99,
      listUnitPrice: list,
      unitPrice: unit,
      discountAmount: (list - unit) * item.quantity,
      upgradeAmount: 0,
      couponAmount: 0,
      vatPercent: VAT_BP / 100,
      vatAmount: 0,
      lineTotal,
      creditUnitPrice: variant ? variant.price : p.price,
      fieldValues: canonical(item.fieldValues),
      targetServerId: item.targetServerId || null,
      physical: false,
      kind: p.kind === 'BUNDLE' ? 'BUNDLE' : 'PRODUCT',
      parentLineKey: null,
      billingMode: p.billingMode,
      errors,
    });
  }

  const subtotal = lines.reduce((sum, l) => sum + l.listUnitPrice * l.quantity, 0);
  const discountTotal = lines.reduce((sum, l) => sum + l.discountAmount, 0);
  const net = subtotal - discountTotal;

  let coupon = null;
  let couponDiscount = 0;
  if (typeof input.couponCode === 'string' && input.couponCode.trim()) {
    const code = input.couponCode.trim().toUpperCase();
    const valid = code === COUPON.code && net > 0;

    coupon = { code, valid, reason: valid ? null : 'COUPON_NOT_APPLICABLE' };
    if (valid) {
      let left = (couponDiscount = Math.round((net * COUPON.percent) / 100));
      lines.forEach((l, k) => {
        const share =
          k === lines.length - 1
            ? left
            : Math.min(left, Math.round((l.lineTotal * COUPON.percent) / 100));
        l.couponAmount = share;
        l.lineTotal -= share;
        left -= share;
      });
    } else messages.push({ code: 'COUPON_NOT_APPLICABLE', level: 'WARNING' });
  }

  let creatorCode = null;
  if (typeof input.creatorCode === 'string' && input.creatorCode.trim())
    creatorCode = { code: input.creatorCode.trim().toUpperCase(), valid: true, reason: null };

  for (const l of lines) l.vatAmount = Math.round((l.lineTotal * VAT_BP) / (10000 + VAT_BP));
  const vatTotal = lines.reduce((sum, l) => sum + l.vatAmount, 0);

  const hasSubscription = lines.some((l) => l.billingMode === 'SUBSCRIPTION');
  const paymentMethods = METHODS.map((m) => {
    const reason =
      m.unavailable || (hasSubscription && !m.recurring ? 'RECURRING_NOT_SUPPORTED' : null);

    return {
      id: m.id,
      label: m.label,
      description: m.description,
      hint:
        m.id === 'bank-transfer'
          ? 'The order is delivered after the transfer has been confirmed.'
          : null,
      icon: m.icon,
      logoUrl: null,
      color: m.color,
      feeAmount: dec(convert(m.fee, currency)),
      available: reason === null,
      unavailableReason: reason,
      pricing: 'MARKET',
      recurring: m.recurring,
      testMode: false,
      notices:
        m.id === 'mock-card'
          ? [
              {
                label: 'Payments are processed by the preview gateway',
                url: 'https://example.com/terms',
              },
            ]
          : [],
      requiredBuyerFields: [],
    };
  });
  const method = METHODS.find((m) => m.id === input.paymentMethodId && !m.unavailable) || null;
  const paymentFee = method && lines.length ? convert(method.fee, currency) : 0;
  const total = net - couponDiscount + paymentFee;

  // credits: one credit is worth one unit of the base currency (creditValue 1), so x100 credits == x100 base money
  const creditTotal = lines.reduce((sum, l) => sum + l.creditUnitPrice * l.quantity, 0);
  const maxApplicable = Math.min(CREDIT_BALANCE, Math.round(total / (RATES[currency] || 1)));
  let applied = 0;
  if (input.payWithCredits === true) applied = Math.min(CREDIT_BALANCE, creditTotal);
  else if (input.useCredits === 'MAX') applied = maxApplicable;
  else if (typeof input.useCredits === 'number' && input.useCredits > 0)
    applied = Math.min(maxApplicable, Math.round(input.useCredits * 100));
  const appliedValue = Math.min(total, convert(applied, currency));

  if (!lines.length) messages.push({ code: 'CART_EMPTY', level: 'ERROR' });

  const money = (l) => ({
    ...l,
    listUnitPrice: dec(l.listUnitPrice),
    unitPrice: dec(l.unitPrice),
    discountAmount: dec(l.discountAmount),
    upgradeAmount: 0,
    couponAmount: dec(l.couponAmount),
    vatAmount: dec(l.vatAmount),
    lineTotal: dec(l.lineTotal),
    creditUnitPrice: dec(l.creditUnitPrice),
  });

  return {
    currency,
    baseCurrency: BASE_CURRENCY,
    lines: lines.map(money),
    pricingMode: 'MARKET',
    pricesIncludeVat: true,
    fxRate: RATES[currency] || 1,
    display: null,
    minimumOrderAmount: 0,
    subtotal: dec(subtotal),
    discountTotal: dec(discountTotal),
    couponDiscount: dec(couponDiscount),
    creatorDiscount: 0,
    upgradeDiscount: 0,
    shippingTotal: 0,
    paymentFee: dec(paymentFee),
    vatTotal: dec(vatTotal),
    total: dec(total),
    credits: {
      enabled: true,
      name: 'Coins',
      balance: dec(CREDIT_BALANCE),
      payableInCredits: lines.length > 0 && !hasSubscription && creditTotal <= CREDIT_BALANCE,
      creditTotal: dec(creditTotal),
      maxApplicable: dec(maxApplicable),
      applied: dec(applied),
      appliedValue: dec(appliedValue),
    },
    gatewayAmount: dec(total - appliedValue),
    coupon,
    creatorCode,
    requiresShipping: false,
    shippingOptions: [],
    shippingMethodId: null,
    paymentMethods,
    requiredBuyerFields: [],
    legal: { required: true, id: 1, version: 3, title: 'Terms of sale' },
    messages,
    canCheckout: lines.length > 0 && !messages.some((m) => m.level === 'ERROR'),
  };
}

function cartBody({ query, volume }) {
  return ok({
    cart: {
      items: storedCart(volume),
      couponCode: null,
      creatorCode: null,
      recipientUsername: null,
      giftMessage: null,
      shippingAddressId: null,
      shippingMethodId: null,
      currency: currencyOf(query.currency),
    },
    quote: quoteOf({ currency: query.currency }, volume),
  });
}

const ADDRESS_FIELDS = {
  DEFAULT: [
    'firstName',
    'lastName',
    'phone',
    'country',
    'state',
    'city',
    'line1',
    'line2',
    'postalCode',
  ],
  TR: [
    'firstName',
    'lastName',
    'phone',
    'country',
    'city',
    'district',
    'neighborhood',
    'line1',
    'postalCode',
  ],
};

function checkoutConfigBody() {
  return ok({
    guestCheckout: true,
    giftPurchase: true,
    billingInfoMode: 'OPTIONAL',
    creditsEnabled: true,
    creditName: 'Coins',
    mixedCredit: true,
    legal: {
      required: true,
      id: 1,
      version: 3,
      title: 'Terms of sale',
      content:
        '<p>All purchases are digital goods delivered in game. By completing the order you accept that the delivery starts immediately.</p>' +
        '<p>Refunds are possible within 14 days for items that have not been used.</p>',
    },
    currencies: OFFERED,
    addressFields: ADDRESS_FIELDS,
    shippingCountries: [],
    minimumOrderAmount: 0,
    creditTopUp: {
      enabled: true,
      freeAmount: true,
      min: 5,
      max: 500,
      creditValue: 1,
      currency: BASE_CURRENCY,
    },
  });
}

function addressesBody({ volume }) {
  const one = (id, label, isDefault, country, city) => ({
    id,
    label,
    isDefault,
    firstName: 'Alex',
    lastName: 'Miner',
    company: null,
    phone: '+1 555 0100',
    email: null,
    country,
    state: country === 'US' ? 'WA' : null,
    city,
    district: null,
    neighborhood: null,
    line1: `${id * 12} Redstone Avenue`,
    line2: null,
    postalCode: country === 'US' ? '98101' : '34000',
    identityNumber: null,
  });

  return ok({
    addresses:
      volume === 'empty'
        ? []
        : [one(1, 'Home', true, 'US', 'Seattle'), one(2, 'Parents', false, 'TR', 'Istanbul')],
  });
}

// ------------------------------------------------------------------------------------------------ orders

const ORDER_STATUSES = [
  'COMPLETED',
  'COMPLETED',
  'COMPLETED',
  'PENDING',
  'COMPLETED',
  'REFUNDED',
  'CANCELLED',
  'PARTIALLY_REFUNDED',
  'COMPLETED',
  'EXPIRED',
  'REVIEW',
  'FAILED',
];
const ORDER_CURRENCIES = ['USD', 'USD', 'EUR', 'USD', 'TRY', 'USD', 'GBP'];

/** Public id of the i-th order of the preview user (URL safe, 22 characters). */
export const orderPublicId = (i) =>
  `ord_${uuidFor('store:order', i).replace(/-/g, '').slice(0, 18)}`;

const orderCache = new Map();

/** The orders of the preview user (newest first), x100 money. */
export function orders(volume) {
  if (orderCache.has(volume)) return orderCache.get(volume);

  const cat = catalog(volume);
  const n = cat.products.length ? countFor(volume, 6, 47) : 0;
  const list = [];

  for (let i = 0; i < n; i++) {
    const status = ORDER_STATUSES[i % ORDER_STATUSES.length];
    const currency = ORDER_CURRENCIES[i % ORDER_CURRENCIES.length];
    const count = 1 + (i % 3);
    const items = [];

    for (let k = 0; k < count; k++) {
      const p = cat.products[int('store:order:item', i * 7 + k, 0, cat.products.length - 1)];
      const variant = variantsOf(p)[0] || null;
      const quantity = 1 + ((i + k) % 2);
      const unit = convert(variant ? variant.price : p.price, currency);

      items.push({
        id: (i + 1) * 10 + k,
        product: p,
        variant,
        quantity,
        unit,
        lineTotal: unit * quantity,
      });
    }

    const subtotal = items.reduce((sum, item) => sum + item.lineTotal, 0);
    const couponDiscount = i % 4 === 1 ? Math.round(subtotal / 10) : 0;
    const paymentFee = i % 5 === 3 ? convert(TRANSFER_FEE, currency) : 0;
    const total = subtotal - couponDiscount + paymentFee;
    const createdAt = NOW - i * 2 * DAY - int('store:order:time', i, 1, 20) * HOUR;
    const paid = !['PENDING', 'CANCELLED', 'EXPIRED', 'FAILED'].includes(status);
    const received = i % 8 === 6 && paid;

    list.push({
      number: 1000 + n - i,
      publicId: orderPublicId(i),
      status,
      currency,
      items,
      subtotal,
      couponDiscount,
      paymentFee,
      total,
      refunded: status === 'REFUNDED' ? total : status === 'PARTIALLY_REFUNDED' ? items[0].unit : 0,
      createdAt,
      paidAt: paid ? createdAt + 4 * 60000 : null,
      isGift: i % 8 === 5 || received,
      recipientUsername: i % 8 === 5 ? PLAYERS[(i + 3) % PLAYERS.length] : null,
      received,
      bankTransfer: paymentFee > 0,
      fulfillment: !paid
        ? 'NONE'
        : status === 'REFUNDED'
          ? 'REVOKED'
          : status === 'REVIEW'
            ? 'PENDING'
            : i % 9 === 4
              ? 'PARTIAL'
              : 'FULFILLED',
    });
  }

  orderCache.set(volume, list);

  return list;
}

function orderRow(o) {
  return {
    publicId: o.publicId,
    number: o.number,
    status: o.status,
    fulfillmentStatus: o.fulfillment,
    shippingStatus: 'NOT_REQUIRED',
    total: dec(o.total),
    currency: o.currency,
    createdAt: o.createdAt,
    paidAt: o.paidAt,
    itemNames: o.items.map((item) => item.product.name),
    isGift: o.isGift,
    recipientUsername: o.recipientUsername,
    received: o.received,
  };
}

function orderView(o) {
  const pending = o.status === 'PENDING';
  const vat = Math.round((o.total * VAT_BP) / (10000 + VAT_BP));
  const view = {
    publicId: o.publicId,
    number: o.number,
    status: o.status,
    fulfillmentStatus: o.fulfillment,
    shippingStatus: 'NOT_REQUIRED',
    limited: false,
    createdAt: o.createdAt,
    paidAt: o.paidAt,
    expiresAt: pending ? o.createdAt + 3 * DAY : null,
    currency: o.currency,
    testMode: false,
    totals: {
      subtotal: dec(o.subtotal),
      discountTotal: 0,
      couponDiscount: dec(o.couponDiscount),
      creatorDiscount: 0,
      upgradeDiscount: 0,
      shippingTotal: 0,
      paymentFee: dec(o.paymentFee),
      vatTotal: dec(vat),
      total: dec(o.total),
      creditAmount: 0,
      creditValue: 0,
      gatewayAmount: dec(o.total),
      refundedTotal: dec(o.refunded),
    },
    items: o.items.map((item, k) => ({
      id: item.id,
      productId: item.product.id,
      name: item.product.name,
      variantName: item.variant ? item.variant.name : null,
      imageFileName: null,
      quantity: item.quantity,
      unitPrice: dec(item.unit),
      lineTotal: dec(item.lineTotal),
      fieldValues: item.product.fields ? { color: 'Gold' } : {},
      targetServerName: item.product.servers.length ? SERVERS[0].name : null,
      delivery: o.fulfillment === 'PARTIAL' ? (k === 0 ? 'FULFILLED' : 'PENDING') : o.fulfillment,
      expiresAt: item.product.billingMode === 'TIMED' && o.paidAt ? o.paidAt + 30 * DAY : null,
    })),
    recipientUsername: o.recipientUsername,
    isGift: o.isGift,
    payment: {
      methodId: o.bankTransfer ? 'bank-transfer' : 'mock-card',
      label: o.bankTransfer ? 'Bank transfer' : 'Credit / debit card',
      status: o.paidAt
        ? 'SUCCEEDED'
        : pending
          ? 'CREATED'
          : o.status === 'FAILED'
            ? 'FAILED'
            : o.status === 'EXPIRED'
              ? 'EXPIRED'
              : 'CANCELLED',
      start: null,
    },
    shipping: null,
    shipments: [],
    shippingAddress: null,
    billingInfo: null,
    email: 'alex.miner@example.com',
    invoiceAvailable: o.paidAt !== null && o.status !== 'REVIEW',
    canCancel: pending,
    canRetryPayment: pending,
    refundPending: false,
  };

  if (pending) {
    const quote = quoteOf({ currency: o.currency }, 'few');

    view.paymentMethods = quote.paymentMethods.map((m) => ({ ...m, feeAmount: 0 }));
    view.credits = null;
  }

  return view;
}

const findOrder = (volume, publicId) =>
  orders(volume).find((o) => o.publicId === publicId) ||
  // an order link must open in every volume (the drawer links one of the `few` orders)
  orders('few').find((o) => o.publicId === publicId) ||
  null;

function orderBody({ params, volume }) {
  const o = findOrder(volume, params.publicId);

  return o ? ok({ order: orderView(o) }) : fail('NOT_FOUND');
}

function orderStatusBody({ params, volume }) {
  const o = findOrder(volume, params.publicId);

  if (!o) return fail('NOT_FOUND');

  const view = orderView(o);

  return ok({
    status: o.status,
    paymentStatus: view.payment.status,
    fulfillmentStatus: o.fulfillment,
    shippingStatus: 'NOT_REQUIRED',
    updatedAt: o.paidAt ?? o.createdAt,
  });
}

function myOrdersBody({ query, volume }) {
  const wanted = query.status
    ? new Set(
        String(query.status)
          .split(',')
          .map((s) => s.trim().toUpperCase())
          .filter(Boolean),
      )
    : null;
  const list = orders(volume).filter((o) => !wanted || wanted.has(o.status));
  const page = paginate(list, query, 10);

  if (page.error) return fail(page.error);

  return ok({ orders: page.rows.map(orderRow), orderCount: page.count, totalPage: page.totalPage });
}

function entitlementsBody({ query, volume }) {
  const paid = orders(volume).filter((o) => o.paidAt !== null);
  const list = [];

  paid.forEach((o, i) => {
    const item = o.items[0];
    const timed = item.product.billingMode !== 'ONE_TIME';
    const status = o.status === 'REFUNDED' ? 'REVOKED' : i % 6 === 4 ? 'EXPIRED' : 'ACTIVE';
    const expiresAt =
      status === 'EXPIRED'
        ? NOW - 5 * DAY
        : timed
          ? o.paidAt + 30 * DAY
          : i % 4 === 1
            ? NOW + 2 * DAY
            : null;

    list.push({
      id: 500 + i,
      productId: item.product.id,
      productName: item.product.name,
      variantName: item.variant ? item.variant.name : null,
      status,
      startsAt: o.paidAt,
      expiresAt,
      subscriptionId: item.product.billingMode === 'SUBSCRIPTION' ? 1 : null,
      orderPublicId: o.publicId,
    });
  });

  const active = query.active === 'true';

  return ok({
    entitlements: list.filter(
      (e) => !active || (e.status === 'ACTIVE' && (e.expiresAt === null || e.expiresAt > NOW)),
    ),
  });
}

// ------------------------------------------------------------------------------------------------ credits

const CREDIT_TYPES = [
  'TOPUP',
  'CAPTURE',
  'GRANT',
  'CASHBACK',
  'REFUND',
  'GIFT',
  'CAPTURE',
  'REVOKE',
];
const CREDIT_NOTES = {
  GRANT: 'Vote reward: thank you for voting 30 days in a row on every server list!',
  REVOKE: 'Correction by an administrator',
  GIFT: 'Gift from EnderQueen',
};

function ledger(volume) {
  const n = countFor(volume, 8, 57);
  const own = orders(volume);
  const entries = [];
  let balance = n ? CREDIT_BALANCE : 0;

  for (let i = 0; i < n; i++) {
    const type = CREDIT_TYPES[i % CREDIT_TYPES.length];
    const magnitude = int('store:credit', i, 2, 60) * 250;
    const amount = type === 'CAPTURE' || type === 'REVOKE' ? -magnitude : magnitude;
    const order =
      ['CAPTURE', 'TOPUP', 'REFUND', 'CASHBACK'].includes(type) && own.length
        ? own[i % own.length]
        : null;

    entries.push({
      id: 9000 + n - i,
      type,
      amount: dec(amount),
      balanceAfter: dec(balance),
      note: CREDIT_NOTES[type] ?? null,
      orderPublicId: order ? order.publicId : null,
      createdAt: NOW - i * DAY - int('store:credit:time', i, 1, 20) * HOUR,
    });
    balance -= amount;
  }

  return entries;
}

function creditsBody({ query, volume }) {
  const entries = ledger(volume);
  const page = paginate(entries, query, 20);

  if (page.error) return fail(page.error);

  return ok({
    balance: entries.length ? dec(CREDIT_BALANCE) : 0,
    creditName: 'Coins',
    entries: page.rows,
    entryCount: page.count,
    totalPage: page.totalPage,
  });
}

// ------------------------------------------------------------------------------------------------ subscriptions

const SUBSCRIPTION_SHAPES = [
  { status: 'ACTIVE', mode: 'GATEWAY' },
  { status: 'PAST_DUE', mode: 'MERCHANT' },
  { status: 'ACTIVE', mode: 'GATEWAY', cancelAtPeriodEnd: true },
  { status: 'CANCELLED', mode: 'GATEWAY', endReason: 'BUYER_CANCEL' },
  { status: 'ACTIVE', mode: 'MANUAL' },
  { status: 'EXPIRED', mode: 'MERCHANT', endReason: 'PAYMENT_FAILED' },
  { status: 'PAUSED', mode: 'GATEWAY' },
  { status: 'COMPLETED', mode: 'MERCHANT', endReason: 'COMPLETED' },
];
const SUBSCRIPTION_PRODUCTS = [
  'VIP Rank',
  'MVP Rank (monthly, renews automatically until it is cancelled)',
  'Island Expansion',
  'Fly Pass',
];

export function subscriptions(volume) {
  const n = countFor(volume, 4, 14);
  const own = orders(volume);
  const pendingOrder = own.find((o) => o.status === 'PENDING') || null;

  return Array.from({ length: n }, (_, i) => {
    const shape = SUBSCRIPTION_SHAPES[i % SUBSCRIPTION_SHAPES.length];
    const open = ['ACTIVE', 'PAST_DUE', 'PAUSED'].includes(shape.status);
    const currency = ['USD', 'EUR', 'TRY'][i % 3];
    const yearly = i % 5 === 4;
    const cancelAtPeriodEnd = shape.cancelAtPeriodEnd === true;

    return {
      id: 300 + n - i,
      productName: SUBSCRIPTION_PRODUCTS[i % SUBSCRIPTION_PRODUCTS.length],
      status: shape.status,
      mode: shape.mode,
      price: dec(convert((yearly ? 8999 : 999) + (i % 4) * 500, currency)),
      currency,
      intervalUnit: yearly ? 'YEAR' : 'MONTH',
      intervalCount: i % 7 === 3 ? 3 : 1,
      currentPeriodEnd: open
        ? NOW + (shape.status === 'PAST_DUE' ? -2 : 6 + i * 3) * DAY
        : NOW - (10 + i) * DAY,
      graceEndsAt: shape.status === 'PAST_DUE' ? NOW + 5 * DAY : null,
      cancelAtPeriodEnd,
      endReason: shape.endReason ?? (cancelAtPeriodEnd ? 'BUYER_CANCEL' : null),
      endedAt: open ? null : NOW - (10 + i) * DAY,
      methodLabel: shape.mode === 'MANUAL' ? 'Bank transfer' : 'Credit / debit card',
      storedMethodLabel: shape.mode === 'MERCHANT' ? 'Visa ending in 4242' : null,
      canCancel: open,
      canResume: cancelAtPeriodEnd,
      canManageAtGateway: shape.mode === 'GATEWAY' && open,
      renewalOrderPublicId:
        shape.status === 'PAST_DUE' || shape.mode === 'MANUAL'
          ? (pendingOrder?.publicId ?? null)
          : null,
    };
  });
}

function summaryBody({ volume }) {
  const list = subscriptions(volume);

  return ok({
    creditsEnabled: true,
    creditBalance: volume === 'empty' ? 0 : dec(CREDIT_BALANCE),
    creditName: 'Coins',
    cartItemCount: storedCart(volume).reduce((sum, l) => sum + l.quantity, 0),
    activeSubscriptionCount: list.filter((s) => ['ACTIVE', 'PAST_DUE', 'PAUSED'].includes(s.status))
      .length,
    subscriptionCount: list.length,
    isCreator: volume !== 'empty',
  });
}

// ------------------------------------------------------------------------------------------------ creator

const EARNING_STATES = ['PENDING', 'AVAILABLE', 'PAID', 'AVAILABLE', 'REVERSED', 'PAID'];

function creatorBody({ query, volume }) {
  if (volume === 'empty') return fail('NOT_FOUND');

  const n = countFor(volume, 6, 44);
  const earnings = Array.from({ length: n }, (_, i) => {
    const state = EARNING_STATES[i % EARNING_STATES.length];
    const createdAt = NOW - i * DAY - 3 * HOUR;

    return {
      orderNumber: `#${2000 + n - i}`,
      amount: dec(int('store:earning', i, 1, 40) * 25),
      state,
      availableAt: createdAt + 14 * DAY,
      createdAt,
    };
  });
  const sum = (states) =>
    Math.round(
      earnings.filter((e) => states.includes(e.state)).reduce((s, e) => s + e.amount * 100, 0),
    );
  const paidOut = sum(['PAID']);
  const page = paginate(earnings, query, 20);

  if (page.error) return fail(page.error);

  return ok({
    codes: [
      {
        code: 'ENDERQUEEN',
        discount: 10,
        unit: 'PERCENT',
        commissionPercent: 15,
        usedCount: n * 3,
        status: 'ACTIVE',
      },
      {
        code: 'A-VERY-LONG-CREATOR-CODE-FOR-THE-HALLOWEEN-STREAM',
        discount: 2.5,
        unit: 'FIXED',
        commissionPercent: 7.5,
        usedCount: 4,
        status: 'INACTIVE',
      },
    ],
    totals: {
      earned: dec(sum(['PENDING', 'AVAILABLE', 'PAID', 'REVERSED'])),
      reversed: dec(sum(['REVERSED'])),
      pending: dec(sum(['PENDING'])),
      paidOut: dec(paidOut),
      available: dec(sum(['AVAILABLE'])),
      currency: BASE_CURRENCY,
    },
    earnings: page.rows,
    earningCount: page.count,
    totalPage: page.totalPage,
    payouts: [
      {
        amount: dec(paidOut),
        method: 'CREDIT',
        state: 'PAID',
        paidAt: NOW - 20 * DAY,
        createdAt: NOW - 21 * DAY,
      },
      { amount: 12.5, method: 'MANUAL', state: 'PENDING', paidAt: null, createdAt: NOW - 2 * DAY },
    ],
  });
}

// ------------------------------------------------------------------------------------------------ routes / pages

export const routes = [
  { method: 'GET', path: `${API}/store`, handler: storeBody },
  { method: 'GET', path: `${API}/store/products`, handler: productsBody },
  { method: 'GET', path: `${API}/products/:slug`, handler: productBody },
  { method: 'GET', path: `${API}/widgets`, handler: widgetsBody },
  { method: 'GET', path: `${API}/checkout/config`, handler: checkoutConfigBody },
  {
    method: 'POST',
    path: `${API}/checkout/quote`,
    safe: true,
    handler: ({ body, volume }) => ok({ quote: quoteOf(body, volume) }),
  },
  { method: 'GET', path: `${API}/me/cart`, handler: cartBody },
  { method: 'GET', path: `${API}/me/addresses`, handler: addressesBody },
  { method: 'GET', path: `${API}/me/summary`, handler: summaryBody },
  { method: 'GET', path: `${API}/me/orders`, handler: myOrdersBody },
  { method: 'GET', path: `${API}/me/entitlements`, handler: entitlementsBody },
  { method: 'GET', path: `${API}/me/credits`, handler: creditsBody },
  {
    method: 'GET',
    path: `${API}/me/subscriptions`,
    handler: ({ volume }) => ok({ subscriptions: subscriptions(volume) }),
  },
  { method: 'GET', path: `${API}/me/creator`, handler: creatorBody },
  { method: 'GET', path: `${API}/orders/:publicId/status`, handler: orderStatusBody },
  { method: 'GET', path: `${API}/orders/:publicId`, handler: orderBody },
];

const few = catalog('few');
const fewOrders = orders('few');
const productHref = (index) => `/store/${few.products[index].slug}`;
const orderHref = (status) => `/store/order/${fewOrders.find((o) => o.status === status).publicId}`;

export const pages = [
  { side: 'theme', label: 'Store', href: '/store' },
  { side: 'theme', label: 'Store: category', href: '/store?category=1' },
  {
    side: 'theme',
    label: 'Store: search, cheapest first',
    href: '/store?search=rank&sort=price-asc',
  },
  { side: 'theme', label: 'Product: subscription', href: productHref(0) },
  { side: 'theme', label: 'Product: on sale, timed', href: productHref(1) },
  { side: 'theme', label: 'Product: bundle', href: productHref(3) },
  { side: 'theme', label: 'Product: variants', href: productHref(4) },
  { side: 'theme', label: 'Product: out of stock', href: productHref(5) },
  { side: 'theme', label: 'Product: long name, fields, servers', href: productHref(6) },
  { side: 'theme', label: 'Checkout', href: '/store/checkout' },
  { side: 'theme', label: 'Order: completed', href: orderHref('COMPLETED') },
  { side: 'theme', label: 'Order: waiting for payment', href: orderHref('PENDING') },
  { side: 'theme', label: 'Order: refunded', href: orderHref('REFUNDED') },
  { side: 'theme', label: 'Profile: purchases', href: '/profile/purchases' },
  { side: 'theme', label: 'Profile: credits', href: '/profile/credits' },
  { side: 'theme', label: 'Profile: subscriptions', href: '/profile/subscriptions' },
  { side: 'theme', label: 'Profile: creator', href: '/profile/creator' },
];
