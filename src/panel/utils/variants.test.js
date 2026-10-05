import { describe, expect, test } from 'bun:test';
import {
  MAX_VARIANTS,
  blankVariant,
  combinationCount,
  duplicateVariant,
  generateVariants,
  isOrphan,
  keyFromLabel,
  markOrphans,
  moveItem,
  renameAxisKey,
  renameValueKey,
  renumber,
  signature,
  uniqueKey,
  uniqueName,
  usableAxes,
} from './variants.js';

const axis = (key, labels) => ({
  key,
  label: key.toUpperCase(),
  values: labels.map((label) => ({ key: keyFromLabel(label), label })),
});

const sizeColor = () => [axis('size', ['A', 'B']), axis('color', ['X', 'Y', 'Z'])];

describe('13 §25.1 test 28: generation', () => {
  test('2 x 3 axes produce 6 variants named "A / X" ...', () => {
    const result = generateVariants(sizeColor(), []);
    expect(result.error).toBeUndefined();
    expect(result.variants.map((v) => v.name)).toEqual([
      'A / X',
      'A / Y',
      'A / Z',
      'B / X',
      'B / Y',
      'B / Z',
    ]);
    expect(result.added).toBe(6);
    expect(result.variants.every((v) => v.status === 'ACTIVE')).toBe(true);
    expect(result.variants.map((v) => v.position)).toEqual([0, 1, 2, 3, 4, 5]);
    expect(result.variants[4].optionValues).toEqual({ size: 'b', color: 'y' });
  });

  test('generated rows have distinct row identities', () => {
    const result = generateVariants(sizeColor(), []);
    expect(new Set(result.variants.map((v) => v._key)).size).toBe(6);
  });

  test('a single axis works', () => {
    const result = generateVariants([axis('days', ['30', '90'])], []);
    expect(result.variants.map((v) => v.name)).toEqual(['30', '90']);
  });

  test('values without a label are ignored, an axis without values makes no options', () => {
    const axes = [axis('size', ['A', '']), { key: 'color', label: 'Color', values: [] }];
    expect(usableAxes(axes)).toHaveLength(1);
    expect(generateVariants(axes, []).variants.map((v) => v.name)).toEqual(['A']);
    expect(generateVariants([{ key: 'c', label: 'C', values: [] }], [])).toEqual({
      error: 'NO_OPTIONS',
    });
    expect(generateVariants([], [])).toEqual({ error: 'NO_OPTIONS' });
  });
});

describe('13 §25.1 test 29: existing variants with the same signature are kept', () => {
  test('id and price survive, the rest is added', () => {
    const axes = sizeColor();
    const existing = [
      blankVariant({
        id: 77,
        name: 'Custom name',
        price: 12.5,
        stock: 4,
        optionValues: { size: 'a', color: 'y' },
      }),
    ];
    const result = generateVariants(axes, existing);
    const kept = result.variants.find((v) => v.id === 77);
    expect(kept.price).toBe(12.5);
    expect(kept.stock).toBe(4);
    expect(kept.name).toBe('Custom name');
    expect(kept.orphan).toBe(false);
    expect(result.variants).toHaveLength(6);
    expect(result.kept).toBe(1);
    expect(result.added).toBe(5);
    expect(result.variants[1].id).toBe(77); // position of A / Y
  });

  test('regenerating twice is stable', () => {
    const axes = sizeColor();
    const first = generateVariants(axes, []);
    const second = generateVariants(axes, first.variants);
    expect(second.added).toBe(0);
    expect(second.kept).toBe(6);
    expect(second.variants.map((v) => v._key)).toEqual(first.variants.map((v) => v._key));
  });

  test('the input is never mutated', () => {
    const existing = [blankVariant({ id: 1, optionValues: { size: 'z', color: 'x' } })];
    const copy = JSON.stringify(existing);
    generateVariants(sizeColor(), existing);
    expect(JSON.stringify(existing)).toBe(copy);
  });
});

describe('13 §25.1 test 30: removed option values flag orphans, nothing is deleted', () => {
  test('variants of a removed value are kept, flagged and sorted last', () => {
    const axes = sizeColor();
    const first = generateVariants(axes, []);
    const withIds = first.variants.map((v, i) => ({ ...v, id: i + 1 }));
    const smaller = [axis('size', ['A']), axis('color', ['X', 'Y', 'Z'])];
    const result = generateVariants(smaller, withIds);
    expect(result.variants).toHaveLength(6);
    expect(result.orphans).toBe(3);
    const flagged = result.variants.filter((v) => v.orphan);
    expect(flagged.map((v) => v.name)).toEqual(['B / X', 'B / Y', 'B / Z']);
    expect(flagged.map((v) => v.id)).toEqual([4, 5, 6]);
    expect(result.variants.slice(0, 3).every((v) => !v.orphan)).toBe(true);
    expect(result.variants.map((v) => v.position)).toEqual([0, 1, 2, 3, 4, 5]);
  });

  test('a manual row without option values is an orphan once axes exist and is never matched', () => {
    const manual = blankVariant({ id: 9, name: 'Manual' });
    const result = generateVariants([axis('size', ['A'])], [manual]);
    expect(result.variants).toHaveLength(2);
    expect(result.variants.find((v) => v.id === 9).orphan).toBe(true);
  });

  test('re-adding the value un-flags the variant', () => {
    const axes = [axis('size', ['A', 'B'])];
    const first = generateVariants(axes, []).variants.map((v, i) => ({ ...v, id: i + 1 }));
    const removed = generateVariants([axis('size', ['A'])], first).variants;
    expect(removed.find((v) => v.id === 2).orphan).toBe(true);
    const back = generateVariants(axes, removed).variants;
    expect(back.find((v) => v.id === 2).orphan).toBe(false);
  });

  test('isOrphan / markOrphans', () => {
    const axes = [axis('size', ['A'])];
    const rows = [
      blankVariant({ optionValues: { size: 'a' } }),
      blankVariant({ optionValues: { size: 'b' } }),
      blankVariant({ optionValues: {} }),
    ];
    expect(markOrphans(axes, rows).map((v) => v.orphan)).toEqual([false, true, true]);
    expect(isOrphan([], rows[2])).toBe(false);
  });
});

describe('13 §25.1 test 31: more than 100 combinations', () => {
  test('refused with TOO_MANY and nothing changes', () => {
    const many = (key) =>
      axis(
        key,
        Array.from({ length: 11 }, (_, i) => `v${i}`),
      );
    const axes = [many('a'), many('b')]; // 121
    expect(combinationCount(usableAxes(axes))).toBe(121);
    const existing = [blankVariant({ id: 1, optionValues: { a: 'v0', b: 'v0' } })];
    const before = JSON.stringify(existing);
    const result = generateVariants(axes, existing);
    expect(result).toEqual({ error: 'TOO_MANY', count: 121 });
    expect(result.variants).toBeUndefined();
    expect(JSON.stringify(existing)).toBe(before);
  });

  test('exactly 100 is allowed', () => {
    const ten = (key) =>
      axis(
        key,
        Array.from({ length: 10 }, (_, i) => `v${i}`),
      );
    const result = generateVariants([ten('a'), ten('b')], []);
    expect(result.variants).toHaveLength(MAX_VARIANTS);
  });

  test('three axes of 5 x 5 x 5 = 125 is refused', () => {
    const five = (key) => axis(key, ['1', '2', '3', '4', '5']);
    expect(generateVariants([five('a'), five('b'), five('c')], []).error).toBe('TOO_MANY');
  });
});

describe('helpers', () => {
  test('keyFromLabel', () => {
    expect(keyFromLabel('Extra Large')).toBe('extra_large');
    expect(keyFromLabel('  Şişe / Çanta! ')).toBe('sise_canta');
    expect(keyFromLabel('İstanbul ığ')).toBe('istanbul_ig');
    expect(keyFromLabel('---')).toBe('');
    expect(keyFromLabel(null)).toBe('');
    expect(keyFromLabel('Ünite_1')).toBe('unite_1');
  });

  test('uniqueKey', () => {
    expect(uniqueKey('size', [])).toBe('size');
    expect(uniqueKey('size', ['size'])).toBe('size_2');
    expect(uniqueKey('size', new Set(['size', 'size_2']))).toBe('size_3');
    expect(uniqueKey('', [])).toBe('option');
  });

  test('signature joins values in axis order', () => {
    expect(signature(sizeColor(), { color: 'x', size: 'a' })).toBe('a|x');
    expect(signature(sizeColor(), {})).toBe('|');
  });

  test('moveItem and renumber', () => {
    expect(moveItem([1, 2, 3], 0, 1)).toEqual([2, 1, 3]);
    expect(moveItem([1, 2, 3], 2, 1)).toEqual([1, 2, 3]);
    expect(moveItem([1, 2, 3], 0, -1)).toEqual([1, 2, 3]);
    expect(moveItem([1, 2, 3], 2, -1)).toEqual([1, 3, 2]);
    expect(renumber([{ position: 5 }, { position: 9 }]).map((v) => v.position)).toEqual([0, 1]);
  });

  test('duplicateVariant creates an unsaved row that does not share state', () => {
    const original = blankVariant({
      id: 5,
      name: 'A',
      attributes: [{ key: 'color', value: 'red' }],
      optionValues: { size: 'a' },
      prices: [{ currency: 'EUR', price: 1, compareAtPrice: null }],
      imageFileName: 'x.png',
    });
    const copy = duplicateVariant(original);
    expect(copy.id).toBeUndefined();
    expect(copy._key).not.toBe(original._key);
    expect(copy.imageFileName).toBeNull();
    copy.attributes[0].value = 'blue';
    copy.optionValues.size = 'b';
    copy.prices[0].price = 2;
    expect(original.attributes[0].value).toBe('red');
    expect(original.optionValues.size).toBe('a');
    expect(original.prices[0].price).toBe(1);
  });
});

describe('renaming keys of unsaved axes and values', () => {
  const rows = () => [
    blankVariant({ name: 'a', optionValues: { size: 'a', color: 'x' } }),
    blankVariant({ name: 'b', optionValues: { size: 'b' } }),
  ];

  test('renameAxisKey moves the chosen value to the new key and leaves other rows alone', () => {
    const renamed = renameAxisKey(rows(), 'size', 'sz');
    expect(renamed[0].optionValues).toEqual({ color: 'x', sz: 'a' });
    expect(renamed[1].optionValues).toEqual({ sz: 'b' });
    const same = rows();
    expect(renameAxisKey(same, 'size', 'size')).toBe(same);
    expect(renameAxisKey(rows(), 'missing', 'x')[0].optionValues).toEqual({
      size: 'a',
      color: 'x',
    });
  });

  test('renameValueKey only touches the matching axis and value', () => {
    const renamed = renameValueKey(rows(), 'size', 'a', 'small');
    expect(renamed[0].optionValues).toEqual({ size: 'small', color: 'x' });
    expect(renamed[1].optionValues).toEqual({ size: 'b' });
    expect(renameValueKey(rows(), 'color', 'a', 'q')[0].optionValues.size).toBe('a');
  });

  test('uniqueName', () => {
    expect(uniqueName('A', ['B'])).toBe('A');
    expect(uniqueName('A', ['a'])).toBe('A (2)');
    expect(uniqueName('A', ['A', 'A (2)'])).toBe('A (3)');
  });
});
