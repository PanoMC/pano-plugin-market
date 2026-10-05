import { describe, expect, test } from 'bun:test';
import { canonicalFieldValues, lineKey } from '../lineKey.js';

describe('lineKey', () => {
  test('has the documented shape', () => {
    expect(
      lineKey({ productId: 5, variantId: 2, fieldValues: { a: 'x' }, targetServerId: 7 }),
    ).toBe('5|2|{"a":"x"}|7');
    expect(lineKey({ productId: 5 })).toBe('5|0|{}|');
  });

  test('key order of fieldValues is irrelevant', () => {
    const a = lineKey({ productId: 1, fieldValues: { name: 'Steve', color: 'red' } });
    const b = lineKey({ productId: 1, fieldValues: { color: 'red', name: 'Steve' } });
    expect(a).toBe(b);
  });

  test("'' / null / undefined values are ignored", () => {
    const plain = lineKey({ productId: 1, fieldValues: { a: 'x' } });
    expect(lineKey({ productId: 1, fieldValues: { a: 'x', b: '', c: null, d: undefined } })).toBe(
      plain,
    );
    expect(lineKey({ productId: 1, fieldValues: { b: '' } })).toBe(lineKey({ productId: 1 }));
  });

  test('false and 0 are real values', () => {
    expect(lineKey({ productId: 1, fieldValues: { ok: false } })).not.toBe(
      lineKey({ productId: 1 }),
    );
    expect(lineKey({ productId: 1, fieldValues: { n: 0 } })).not.toBe(lineKey({ productId: 1 }));
  });

  test('variantId undefined, null and 0 are the same', () => {
    expect(lineKey({ productId: 3 })).toBe(lineKey({ productId: 3, variantId: 0 }));
    expect(lineKey({ productId: 3, variantId: null })).toBe(
      lineKey({ productId: 3, variantId: 0 }),
    );
    expect(lineKey({ productId: 3, variantId: 4 })).not.toBe(lineKey({ productId: 3 }));
  });

  test('a different server gives a different key; none is the same as null', () => {
    expect(lineKey({ productId: 1, targetServerId: 1 })).not.toBe(
      lineKey({ productId: 1, targetServerId: 2 }),
    );
    expect(lineKey({ productId: 1, targetServerId: null })).toBe(lineKey({ productId: 1 }));
  });

  test('a number value differs from the same value as a string', () => {
    expect(lineKey({ productId: 1, fieldValues: { amount: 5 } })).not.toBe(
      lineKey({ productId: 1, fieldValues: { amount: '5' } }),
    );
  });

  test('different products differ', () => {
    expect(lineKey({ productId: 1 })).not.toBe(lineKey({ productId: 2 }));
  });

  test('garbage fieldValues count as none', () => {
    expect(canonicalFieldValues(null)).toBe('{}');
    expect(canonicalFieldValues([1, 2])).toBe('{}');
    expect(canonicalFieldValues('x')).toBe('{}');
  });

  test('a quote line (null fieldValues, no targetServerId) matches its local line', () => {
    const local = { productId: 9, variantId: 0, fieldValues: {}, targetServerId: null };
    const quote = { productId: 9, variantId: 0, fieldValues: null, targetServerId: null };
    expect(lineKey(quote)).toBe(lineKey(local));
  });
});
