import { describe, expect, test } from 'bun:test';
import { buildQuery, normalize } from '../api-result.js';

describe('normalize', () => {
  test('undefined is NETWORK', () => {
    expect(normalize(undefined)).toEqual({ ok: false, code: 'NETWORK' });
  });

  test('string body is NETWORK', () => {
    expect(normalize('<html>502</html>')).toEqual({ ok: false, code: 'NETWORK' });
  });

  test('null, array and object without result are NETWORK', () => {
    expect(normalize(null).code).toBe('NETWORK');
    expect(normalize([]).code).toBe('NETWORK');
    expect(normalize({ a: 1 })).toEqual({ ok: false, code: 'NETWORK' });
  });

  test('error result keeps the code and the extras', () => {
    expect(normalize({ result: 'error', error: 'X', a: 1 })).toEqual({
      ok: false,
      code: 'X',
      a: 1,
    });
  });

  test('a code of the answer itself never replaces the error code of the envelope', () => {
    // PAYMENT_PROVIDER_ERROR carries the provider's own `code` next to the order the checkout created
    expect(
      normalize({
        result: 'error',
        error: 'PAYMENT_PROVIDER_ERROR',
        code: 'GATEWAY_UNREACHABLE',
        order: { publicId: 'P' },
      }),
    ).toEqual({
      ok: false,
      code: 'PAYMENT_PROVIDER_ERROR',
      providerCode: 'GATEWAY_UNREACHABLE',
      order: { publicId: 'P' },
    });
    expect(normalize({ result: 'error', code: 'ONLY_A_CODE' })).toEqual({
      ok: false,
      code: 'GENERIC',
      providerCode: 'ONLY_A_CODE',
    });
  });

  test('error result without a code is GENERIC', () => {
    expect(normalize({ result: 'error' })).toEqual({ ok: false, code: 'GENERIC' });
  });

  test('ok result spreads the keys', () => {
    expect(normalize({ result: 'ok', b: 2 })).toEqual({ ok: true, b: 2 });
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
