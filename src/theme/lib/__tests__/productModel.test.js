import { describe, expect, test } from 'bun:test';
import {
  buildLine,
  buyState,
  clampQuantity,
  currentVariant,
  formValid,
  productBreadcrumbs,
  quantityMax,
  quantityVisible,
  reasonCode,
  resolveProductLoad,
  resolveSlug,
  targetServer,
  validateForm,
  variantMissing,
} from '../../components/product/productModel.js';

const ORIGIN = 'https://shop.example';
const product = {
  id: 7,
  slug: 'vip',
  name: 'VIP',
  categoryId: 3,
  categoryName: 'Ranks',
  price: 10,
  currency: 'EUR',
  inStock: true,
  billingMode: 'ONE_TIME',
  description: '<p>x</p>',
  variants: [],
  fields: [],
  serverChoices: [],
  purchasable: { ok: true, reason: null },
};

describe('resolveProductLoad', () => {
  const ok = { ok: true, product };

  test('ok: data, plain title, breadcrumbs, no meta without page-meta', () => {
    const r = resolveProductLoad({ res: ok, settings: { a: 1 }, slug: 'vip', origin: ORIGIN });

    expect(r.data).toMatchObject({
      state: 'READY',
      slug: 'vip',
      settings: { a: 1 },
      titleOptions: false,
    });
    expect(r.pageTitle).toBe('VIP');
    expect(r.meta).toBeUndefined();
    expect('meta' in r).toBe(false);
    expect(r.breadcrumbs).toEqual([
      { label: 'plugins.pano-plugin-market.nav-store', href: '/store' },
      { label: 'Ranks', raw: true, href: '/store?category=3' },
      { label: 'VIP', raw: true },
    ]);
  });

  test('meta only with page-meta, title object only with page-title-options', () => {
    const r = resolveProductLoad({
      res: ok,
      settings: null,
      slug: 'vip',
      origin: ORIGIN,
      features: { meta: true, titleOptions: true },
      variantParam: '4',
    });

    expect(r.meta.type).toBe('product');
    expect(r.meta.canonical).toBe(`${ORIGIN}/store/vip`);
    expect(r.pageTitle).toEqual({ title: 'VIP', raw: true, hidden: true });
    expect(r.data).toMatchObject({ settings: {}, titleOptions: true, variantParam: '4' });
  });

  test('NOT_FOUND asks for a 404', () => {
    expect(
      resolveProductLoad({ res: { ok: false, code: 'NOT_FOUND' }, slug: 'x', origin: ORIGIN }),
    ).toEqual({
      notFound: true,
    });
  });

  test('STORE_DISABLED and any other failure', () => {
    expect(
      resolveProductLoad({ res: { ok: false, code: 'STORE_DISABLED' }, slug: 'x', origin: ORIGIN })
        .data,
    ).toEqual({
      state: 'DISABLED',
    });
    expect(
      resolveProductLoad({
        res: { ok: false, code: 'STORE_UNAVAILABLE' },
        slug: 'x',
        origin: ORIGIN,
      }).data,
    ).toEqual({ state: 'ERROR', code: 'STORE_UNAVAILABLE' });
    expect(
      resolveProductLoad({ res: { ok: false, code: 'NETWORK' }, slug: 'x', origin: ORIGIN }).data
        .state,
    ).toBe('ERROR');
    expect(resolveProductLoad({ res: undefined, slug: 'x', origin: ORIGIN }).data).toEqual({
      state: 'ERROR',
      code: 'NETWORK',
    });
    expect(resolveProductLoad({ res: { ok: true }, slug: 'x', origin: ORIGIN }).data.state).toBe(
      'ERROR',
    );
  });

  test('breadcrumbs skip the category crumb without a category name', () => {
    expect(productBreadcrumbs({ name: 'N' })).toHaveLength(2);
  });

  test('slug: decoded by the host or here, a broken escape stays raw', () => {
    expect(resolveSlug('%C3%A7ay', false)).toBe('çay');
    expect(resolveSlug('çay', true)).toBe('çay');
    expect(resolveSlug('%E0%A4%A', false)).toBe('%E0%A4%A');
    expect(resolveSlug(undefined, false)).toBe('');
  });
});

describe('variant of the choice', () => {
  const axes = {
    hasVariants: true,
    variantOptions: [{ key: 'size', label: 'Size', values: [{ key: 's' }, { key: 'm' }] }],
    variants: [
      { id: 1, optionValues: { size: 's' } },
      { id: 2, optionValues: { size: 'm' } },
    ],
  };
  const plain = {
    hasVariants: true,
    variantOptions: [],
    variants: [
      { id: 5, name: 'A' },
      { id: 6, name: 'B' },
    ],
  };

  test('axis buttons use the selection, the select uses the variant id', () => {
    expect(currentVariant(axes, { selection: { size: 'm' } }).id).toBe(2);
    expect(currentVariant(axes, { selection: {} })).toBe(null);
    expect(currentVariant(plain, { variantId: 6 }).id).toBe(6);
    expect(currentVariant(plain, { variantId: null })).toBe(null);
    expect(currentVariant(product, {})).toBe(null);
  });

  test('variantMissing only when the product has variants and none resolved', () => {
    expect(variantMissing(axes, null)).toBe(true);
    expect(variantMissing(axes, axes.variants[0])).toBe(false);
    expect(variantMissing(product, null)).toBe(false);
  });
});

describe('quantity', () => {
  test('max = min(maxQuantityPerOrder, stock, limitPerPlayer, 99)', () => {
    expect(quantityMax({}, null)).toBe(99);
    expect(quantityMax({ maxQuantityPerOrder: 20, stock: 9, limitPerPlayer: 15 }, null)).toBe(9);
    expect(quantityMax({ maxQuantityPerOrder: 5, stock: 9, limitPerPlayer: 15 }, null)).toBe(5);
    expect(quantityMax({ stock: 50, limitPerPlayer: 3 }, null)).toBe(3);
    expect(quantityMax({ maxQuantityPerOrder: 500 }, null)).toBe(99);
  });

  test('the resolved variant stock replaces the product stock', () => {
    expect(quantityMax({ stock: 2 }, { stock: 8 })).toBe(8);
    expect(quantityMax({ stock: 2 }, { stock: null })).toBe(99);
  });

  test('hidden when max <= 1 or the product is not one-time', () => {
    expect(quantityVisible({ billingMode: 'ONE_TIME' }, null)).toBe(true);
    expect(quantityVisible({ billingMode: 'ONE_TIME', limitPerPlayer: 1 }, null)).toBe(false);
    expect(quantityVisible({ billingMode: 'ONE_TIME', stock: 1 }, null)).toBe(false);
    expect(quantityVisible({ billingMode: 'SUBSCRIPTION' }, null)).toBe(false);
    expect(quantityVisible({ billingMode: 'TIMED' }, null)).toBe(false);
  });

  test('clamp to [1, max]', () => {
    expect(clampQuantity('0', 5)).toBe(1);
    expect(clampQuantity('-3', 5)).toBe(1);
    expect(clampQuantity('abc', 5)).toBe(1);
    expect(clampQuantity('2.9', 5)).toBe(2);
    expect(clampQuantity(50, 5)).toBe(5);
    expect(clampQuantity(3, 5)).toBe(3);
    expect(clampQuantity(3, 0)).toBe(1);
  });
});

describe('buyState', () => {
  test('LOGIN_REQUIRED shows the sign-in link', () => {
    expect(
      buyState({ ...product, purchasable: { ok: false, reason: 'LOGIN_REQUIRED' } }, null).kind,
    ).toBe('LOGIN');
  });

  test('another reason disables the buttons with that reason; an unknown reason is GENERIC', () => {
    expect(
      buyState({ ...product, purchasable: { ok: false, reason: 'ALREADY_OWNED' } }, null),
    ).toEqual({
      kind: 'BLOCKED',
      reason: 'ALREADY_OWNED',
    });
    expect(
      buyState({ ...product, purchasable: { ok: false, reason: 'WHATEVER' } }, null).reason,
    ).toBe('GENERIC');
    expect(reasonCode(null)).toBe('GENERIC');
  });

  test('sold out follows the resolved variant', () => {
    const withVariants = { ...product, hasVariants: true, variants: [{ id: 1, inStock: false }] };
    expect(buyState(withVariants, withVariants.variants[0]).kind).toBe('SOLD_OUT');
    expect(buyState({ ...product, inStock: false }, null).kind).toBe('SOLD_OUT');
    expect(buyState({ ...product, inStock: false }, { id: 1, inStock: true }).kind).toBe('BUY');
  });

  test('subscription vs one-time', () => {
    expect(buyState({ ...product, billingMode: 'SUBSCRIPTION' }, null).kind).toBe('SUBSCRIBE');
    expect(buyState(product, null).kind).toBe('BUY');
    expect(buyState({ ...product, purchasable: undefined }, null).kind).toBe('BUY');
  });
});

describe('server choice', () => {
  test('one choice is fixed, none is null, several need a valid pick', () => {
    expect(targetServer({ serverChoices: [{ id: 4 }] }, null)).toBe(4);
    expect(targetServer({ serverChoices: [] }, 4)).toBe(null);
    expect(targetServer({ serverChoices: [{ id: 4 }, { id: 5 }] }, null)).toBe(null);
    expect(targetServer({ serverChoices: [{ id: 4 }, { id: 5 }] }, '5')).toBe(5);
    expect(targetServer({ serverChoices: [{ id: 4 }, { id: 5 }] }, 9)).toBe(null);
  });
});

describe('validateForm and buildLine', () => {
  const rich = {
    ...product,
    hasVariants: true,
    variantOptions: [],
    variants: [{ id: 1, name: 'Gold', inStock: true }],
    fields: [
      { fieldKey: 'nick', type: 'USERNAME', required: true },
      { fieldKey: 'note', type: 'TEXT' },
    ],
    serverChoices: [{ id: 1 }, { id: 2 }],
  };

  test('focus goes to the first invalid control: variant, fields in order, server', () => {
    const none = validateForm(rich, { variant: null, fieldValues: {}, serverId: null });
    expect(none).toMatchObject({
      variant: 'VARIANT_REQUIRED',
      server: 'SERVER_REQUIRED',
      firstInvalid: 'mp-variant',
    });
    expect(none.fields).toEqual({ nick: 'FIELD_REQUIRED' });
    expect(formValid(none)).toBe(false);

    const noVariantIssue = validateForm(rich, {
      variant: rich.variants[0],
      fieldValues: {},
      serverId: null,
    });
    expect(noVariantIssue.firstInvalid).toBe('mf-nick');

    const serverOnly = validateForm(rich, {
      variant: rich.variants[0],
      fieldValues: { nick: 'Steve' },
      serverId: null,
    });
    expect(serverOnly.firstInvalid).toBe('mp-server');
  });

  test('valid form', () => {
    const errors = validateForm(rich, {
      variant: rich.variants[0],
      fieldValues: { nick: 'Steve' },
      serverId: 2,
    });
    expect(formValid(errors)).toBe(true);
    expect(errors.firstInvalid).toBe(null);
  });

  test('line: ids, trimmed values, optional empties omitted, server, variant name as display meta', () => {
    const line = buildLine(rich, {
      variant: rich.variants[0],
      fieldValues: { nick: ' Steve ', note: '  ' },
      serverId: 2,
      quantity: 3,
    });

    expect(line).toEqual({
      productId: 7,
      quantity: 3,
      variantId: 1,
      variantName: 'Gold',
      fieldValues: { nick: 'Steve' },
      targetServerId: 2,
    });
  });

  test('a plain product line has only product and quantity; quantity is clamped; subscriptions are 1', () => {
    expect(
      buildLine(product, { variant: null, fieldValues: {}, serverId: null, quantity: 500 }),
    ).toEqual({
      productId: 7,
      quantity: 99,
    });
    expect(
      buildLine(
        { ...product, billingMode: 'SUBSCRIPTION' },
        { variant: null, fieldValues: {}, serverId: null, quantity: 4 },
      ).quantity,
    ).toBe(1);
    expect(
      buildLine(
        { ...product, limitPerPlayer: 2 },
        { variant: null, fieldValues: {}, serverId: null, quantity: 9 },
      ).quantity,
    ).toBe(2);
  });

  test('a single server choice is sent without a pick', () => {
    expect(
      buildLine(
        { ...product, serverChoices: [{ id: 8 }] },
        { variant: null, fieldValues: {}, serverId: null, quantity: 1 },
      ).targetServerId,
    ).toBe(8);
  });
});
