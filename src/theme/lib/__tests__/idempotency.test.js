import { describe, expect, test } from 'bun:test';
import { bodyHash, canonicalJson, cyrb53, resolveIdempotency, uuidV4 } from '../idempotency.js';

const UUID4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

function counterBytes() {
  let n = 0;
  return (length) => Uint8Array.from({ length }, () => (n++ * 37 + 11) & 255);
}

describe('canonicalJson', () => {
  test('is independent of key order at every depth', () => {
    const a = { b: 1, a: { y: [1, { q: 1, p: 2 }], x: null } };
    const b = { a: { x: null, y: [1, { p: 2, q: 1 }] }, b: 1 };
    expect(canonicalJson(a)).toBe(canonicalJson(b));
  });

  test('keeps array order and drops undefined members', () => {
    expect(canonicalJson([2, 1])).not.toBe(canonicalJson([1, 2]));
    expect(canonicalJson({ a: 1, b: undefined })).toBe(canonicalJson({ a: 1 }));
    expect(canonicalJson({ a: 1 })).toBe('{"a":1}');
  });

  test('distinguishes values by type', () => {
    expect(canonicalJson({ a: 1 })).not.toBe(canonicalJson({ a: '1' }));
    expect(canonicalJson({ a: null })).not.toBe(canonicalJson({}));
  });
});

describe('cyrb53', () => {
  test('is deterministic and spreads different input', () => {
    expect(cyrb53('abc')).toBe(cyrb53('abc'));
    expect(cyrb53('abc')).not.toBe(cyrb53('abd'));
    expect(cyrb53('')).toBeGreaterThanOrEqual(0);
    expect(Number.isSafeInteger(cyrb53('some longer text'))).toBe(true);
  });
});

describe('uuidV4', () => {
  test('is a UUID v4, with the default source and with an injected one', () => {
    expect(uuidV4()).toMatch(UUID4);
    expect(uuidV4(counterBytes())).toMatch(UUID4);
  });

  test('two default keys differ', () => {
    expect(uuidV4()).not.toBe(uuidV4());
  });

  test('falls back to getRandomValues when randomUUID is missing', () => {
    const original = globalThis.crypto;
    Object.defineProperty(globalThis, 'crypto', {
      value: { getRandomValues: (a) => original.getRandomValues(a) },
      configurable: true,
    });
    try {
      expect(uuidV4()).toMatch(UUID4);
    } finally {
      Object.defineProperty(globalThis, 'crypto', { value: original, configurable: true });
    }
  });
});

describe('resolveIdempotency', () => {
  const body = { items: [{ productId: 1, quantity: 2 }], currency: 'USD', acceptLegal: true };

  test('first call makes a UUID v4 key', () => {
    const r = resolveIdempotency({}, body);
    expect(r.idempotencyKey).toMatch(UUID4);
    expect(r.bodyHash).toBe(bodyHash(body));
    expect(r.reused).toBe(false);
  });

  test('same body => same key', () => {
    const first = resolveIdempotency({}, body);
    const again = resolveIdempotency(first, { ...body });
    expect(again.idempotencyKey).toBe(first.idempotencyKey);
    expect(again.reused).toBe(true);
  });

  test('same body with reordered keys => same key', () => {
    const first = resolveIdempotency({}, body);
    const reordered = {
      acceptLegal: true,
      currency: 'USD',
      items: [{ quantity: 2, productId: 1 }],
    };
    expect(resolveIdempotency(first, reordered).idempotencyKey).toBe(first.idempotencyKey);
  });

  test('changed body => new key', () => {
    const first = resolveIdempotency({}, body);
    const changed = resolveIdempotency(first, { ...body, items: [{ productId: 1, quantity: 3 }] });
    expect(changed.idempotencyKey).not.toBe(first.idempotencyKey);
    expect(changed.idempotencyKey).toMatch(UUID4);
    expect(changed.reused).toBe(false);
    expect(changed.bodyHash).not.toBe(first.bodyHash);
  });

  test('a draft with a hash but no key is not reused', () => {
    const r = resolveIdempotency({ bodyHash: bodyHash(body) }, body);
    expect(r.reused).toBe(false);
    expect(r.idempotencyKey).toMatch(UUID4);
  });

  test('null draft is fine', () => {
    expect(resolveIdempotency(null, body).idempotencyKey).toMatch(UUID4);
  });
});
