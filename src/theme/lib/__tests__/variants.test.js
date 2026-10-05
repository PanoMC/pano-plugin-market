import { describe, expect, test } from 'bun:test';
import {
  effectiveProduct,
  hasAxes,
  initialSelection,
  initialVariant,
  isValueSelectable,
  parseVariantParam,
  resolve,
  selectValue,
  selectionOf,
  usableAxes,
} from '../variants.js';

const oneAxis = [
  {
    key: 'size',
    label: 'Size',
    values: [
      { key: 's', label: 'S' },
      { key: 'm', label: 'M' },
    ],
  },
];
const twoAxes = [
  ...oneAxis,
  {
    key: 'color',
    label: 'Colour',
    values: [
      { key: 'red', label: 'Red' },
      { key: 'blue', label: 'Blue' },
    ],
  },
];
const v1 = [
  { id: 1, name: 'S', optionValues: { size: 's' }, price: 5, inStock: false },
  { id: 2, name: 'M', optionValues: { size: 'm' }, price: 8, inStock: true },
];
// no "m / blue" combination
const v2 = [
  { id: 10, optionValues: { size: 's', color: 'red' }, inStock: false },
  { id: 11, optionValues: { size: 's', color: 'blue' }, inStock: true },
  { id: 12, optionValues: { size: 'm', color: 'red' }, inStock: true },
];

describe('resolve', () => {
  test('one axis', () => {
    expect(resolve(v1, { size: 'm' }, oneAxis).id).toBe(2);
    expect(resolve(v1, { size: 'l' }, oneAxis)).toBe(null);
  });

  test('two axes', () => {
    expect(resolve(v2, { size: 's', color: 'blue' }, twoAxes).id).toBe(11);
    expect(resolve(v2, { size: 'm', color: 'red' }, twoAxes).id).toBe(12);
  });

  test('incomplete selection resolves to null', () => {
    expect(resolve(v2, { size: 's' }, twoAxes)).toBe(null);
    expect(resolve(v2, {}, twoAxes)).toBe(null);
    expect(resolve(v2, { size: 's', color: '' }, twoAxes)).toBe(null);
  });

  test('a missing combination resolves to null', () => {
    expect(resolve(v2, { size: 'm', color: 'blue' }, twoAxes)).toBe(null);
  });

  test('axes may be given as variantOptions and keys compare as strings', () => {
    expect(
      resolve([{ id: 3, optionValues: { n: 1 } }], { n: '1' }, [{ key: 'n', values: [{ key: 1 }] }])
        .id,
    ).toBe(3);
  });

  test('no axes, no variant', () => {
    expect(resolve(v1, { size: 's' }, [])).toBe(null);
    expect(resolve(undefined, {}, oneAxis)).toBe(null);
  });
});

describe('isValueSelectable', () => {
  test('a value is selectable when some variant matches the other axes', () => {
    expect(isValueSelectable(v2, twoAxes, { size: 's', color: 'red' }, 'color', 'blue')).toBe(true);
    expect(isValueSelectable(v2, twoAxes, { size: 'm', color: 'red' }, 'color', 'blue')).toBe(
      false,
    );
    expect(isValueSelectable(v2, twoAxes, { size: 'm', color: 'red' }, 'size', 's')).toBe(true);
  });

  test('a missing combination disables the value', () => {
    expect(isValueSelectable(v2, twoAxes, { size: 'm' }, 'color', 'blue')).toBe(false);
  });

  test('the axis itself does not restrict, an unselected other axis does not either', () => {
    expect(isValueSelectable(v2, twoAxes, { color: 'blue' }, 'size', 'm')).toBe(false);
    expect(isValueSelectable(v2, twoAxes, {}, 'size', 'm')).toBe(true);
  });

  test('a sold-out variant stays selectable', () => {
    expect(isValueSelectable(v2, twoAxes, { size: 's' }, 'color', 'red')).toBe(true);
    expect(isValueSelectable(v1, oneAxis, {}, 'size', 's')).toBe(true);
  });

  test('unknown value is not selectable', () => {
    expect(isValueSelectable(v1, oneAxis, {}, 'size', 'xl')).toBe(false);
  });
});

describe('initial selection', () => {
  test('the variant of ?variant=<id> when listed', () => {
    expect(initialVariant(v1, '1').id).toBe(1);
    expect(initialSelection(v1, oneAxis, '1')).toEqual({
      variant: v1[0],
      selection: { size: 's' },
    });
  });

  test('an unlisted or malformed id falls back to the first variant in stock', () => {
    expect(initialVariant(v1, '99').id).toBe(2);
    expect(initialVariant(v1, 'abc').id).toBe(2);
    expect(initialVariant(v1, '-1').id).toBe(2);
    expect(initialVariant(v1, null).id).toBe(2);
  });

  test('all sold out: the first variant', () => {
    const soldOut = v1.map((variant) => ({ ...variant, inStock: false }));
    expect(initialVariant(soldOut, null).id).toBe(1);
  });

  test('no variants', () => {
    expect(initialVariant([], '1')).toBe(null);
    expect(initialSelection([], oneAxis, null)).toEqual({ variant: null, selection: {} });
  });

  test('parseVariantParam', () => {
    expect(parseVariantParam('12')).toBe(12);
    expect(parseVariantParam(' 7 ')).toBe(7);
    expect(parseVariantParam('0')).toBe(null);
    expect(parseVariantParam('1.5')).toBe(null);
    expect(parseVariantParam(undefined)).toBe(null);
  });
});

describe('selectValue', () => {
  test('keeps the other axes when the combination exists', () => {
    expect(selectValue(v2, twoAxes, { size: 's', color: 'red' }, 'color', 'blue')).toEqual({
      size: 's',
      color: 'blue',
    });
  });

  test('moves the other axes to a variant that has the clicked value instead of a dead end', () => {
    expect(selectValue(v2, twoAxes, { size: 's', color: 'blue' }, 'size', 'm')).toEqual({
      size: 'm',
      color: 'red',
    });
  });
});

describe('helpers', () => {
  test('usableAxes drops axes without values; hasAxes', () => {
    expect(usableAxes([{ key: 'a', values: [] }, ...oneAxis])).toHaveLength(1);
    expect(hasAxes([])).toBe(false);
    expect(hasAxes(undefined)).toBe(false);
    expect(hasAxes(oneAxis)).toBe(true);
  });

  test('selectionOf stringifies the values of known axes only', () => {
    expect(selectionOf({ optionValues: { size: 's', other: 'x' } }, oneAxis)).toEqual({
      size: 's',
    });
  });

  test('effectiveProduct takes price, stock, image and period count from the variant', () => {
    const product = {
      price: 5,
      compareAtPrice: 9,
      creditPrice: 50,
      inStock: true,
      stock: null,
      priceFrom: true,
      imageFileName: 'p.png',
      period: { unit: 'MONTH', count: 1 },
    };
    const variant = {
      price: 8,
      compareAtPrice: null,
      creditPrice: 80,
      inStock: false,
      stock: 2,
      imageFileName: 'v.png',
      periodCount: 3,
    };
    const merged = effectiveProduct(product, variant);

    expect(merged).toMatchObject({
      price: 8,
      compareAtPrice: null,
      creditPrice: 80,
      inStock: false,
      stock: 2,
      imageFileName: 'v.png',
      priceFrom: false,
      period: { unit: 'MONTH', count: 3 },
    });
    expect(effectiveProduct(product, null)).toBe(product);
    expect(effectiveProduct(product, { price: 6 }).imageFileName).toBe('p.png');
  });
});
