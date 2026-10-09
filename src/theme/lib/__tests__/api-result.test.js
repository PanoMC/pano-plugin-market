import { describe, expect, test } from 'bun:test';
import { buildQuery, NETWORK_CODES, normalize, readList } from '../api-result.js';

describe('normalize', () => {
  test('undefined is NETWORK', () => {
    expect(normalize(undefined)).toEqual({ ok: false, code: 'NETWORK' });
  });

  test('string body, null and array are NETWORK', () => {
    expect(normalize('<html>502</html>')).toEqual({ ok: false, code: 'NETWORK' });
    expect(normalize(null).code).toBe('NETWORK');
    expect(normalize([]).code).toBe('NETWORK');
  });

  test('the transport codes of the host are NETWORK', () => {
    for (const code of NETWORK_CODES)
      expect(normalize({ error: { code } })).toEqual({ ok: false, code: 'NETWORK' });
  });

  test('an answer without an error key is a success, whatever else it holds', () => {
    expect(normalize({ b: 2 })).toEqual({ ok: true, b: 2 });
    expect(normalize({})).toEqual({ ok: true });
  });

  test('the error envelope keeps the code, the details spread to the top and stay whole', () => {
    expect(
      normalize({ error: { code: 'X', details: { a: 1, reason: 'CODE_ATTEMPTS_LOCKED' } } }),
    ).toEqual({
      ok: false,
      code: 'X',
      a: 1,
      reason: 'CODE_ATTEMPTS_LOCKED',
      details: { a: 1, reason: 'CODE_ATTEMPTS_LOCKED' },
    });
  });

  test('error.fields and error.message are kept; an error without details has no details key', () => {
    expect(
      normalize({ error: { code: 'INVALID_FIELDS', message: 'bad', fields: { email: 'EXISTS' } } }),
    ).toEqual({ ok: false, code: 'INVALID_FIELDS', message: 'bad', fields: { email: 'EXISTS' } });
  });

  test('a code in the details never replaces the error code of the envelope', () => {
    // PAYMENT_PROVIDER_ERROR carries the provider's own `code` next to the order the checkout created
    expect(
      normalize({
        error: {
          code: 'PAYMENT_PROVIDER_ERROR',
          details: { code: 'GATEWAY_UNREACHABLE', order: { publicId: 'P' } },
        },
      }),
    ).toMatchObject({
      ok: false,
      code: 'PAYMENT_PROVIDER_ERROR',
      providerCode: 'GATEWAY_UNREACHABLE',
      order: { publicId: 'P' },
    });
  });

  test('details.fields (a list of field names) and error.fields (a map) do not clash', () => {
    expect(
      normalize({ error: { code: 'BUYER_INFO_REQUIRED', details: { fields: ['phone'] } } }).fields,
    ).toEqual(['phone']);
  });

  test('an error without a usable code is GENERIC', () => {
    expect(normalize({ error: {} })).toEqual({ ok: false, code: 'GENERIC' });
    expect(normalize({ error: 'LEGACY' })).toEqual({ ok: false, code: 'GENERIC' });
  });
});

describe('readList', () => {
  const page = { number: 2, size: 10, totalItems: 25, totalPages: 3 };

  test('reads items and the page object', () => {
    expect(readList({ ok: true, items: [1, 2], page })).toEqual({
      items: [1, 2],
      number: 2,
      size: 10,
      totalItems: 25,
      totalPages: 3,
    });
  });

  test('an empty list is one page (the pager wants at least 1)', () => {
    expect(
      readList({ items: [], page: { number: 1, size: 10, totalItems: 0, totalPages: 0 } }),
    ).toEqual({
      items: [],
      number: 1,
      size: 10,
      totalItems: 0,
      totalPages: 1,
    });
    expect(readList(undefined)).toEqual({
      items: [],
      number: 1,
      size: null,
      totalItems: 0,
      totalPages: 1,
    });
  });

  test('without a page object the count is the length of the list', () => {
    expect(readList({ items: [1, 2, 3] }).totalItems).toBe(3);
  });

  test('an array under another key is not a list', () => {
    expect(readList({ orders: [1], orderCount: 7, page })).toMatchObject({
      items: [],
      totalItems: 25,
    });
    expect(readList({ orders: [1], orderCount: 7 })).toMatchObject({ items: [], totalItems: 0 });
    expect(readList({ items: 'no' }).items).toEqual([]);
  });
});

describe('buildQuery', () => {
  test('keeps 0 and false, drops null, undefined and empty string', () => {
    const q = buildQuery({ a: 0, b: false, c: null, d: undefined, e: '', f: 'x y' });
    expect(q).toBe('?a=0&b=false&f=x+y');
  });

  test('nothing to send gives an empty string', () => {
    expect(buildQuery({ a: null })).toBe('');
    expect(buildQuery(undefined)).toBe('');
  });

  test('arrays repeat the key and skip empty items', () => {
    expect(buildQuery({ id: [1, null, 2] })).toBe('?id=1&id=2');
  });
});
