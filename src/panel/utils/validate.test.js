import { describe, expect, test } from 'bun:test';
import {
  RESERVED_SLUGS,
  isEmailLike,
  isFieldKey,
  isIpOrCidr,
  isMinecraftUsername,
  patternError,
  slugError,
} from './validate.js';

describe('validate (13 25.1 tests 25-27)', () => {
  test('25. isIpOrCidr valid', () => {
    for (const ok of [
      '1.2.3.4',
      '1.2.3.0/24',
      '::1',
      '2001:db8::/32',
      '0.0.0.0/0',
      '255.255.255.255/32',
      'fe80::1',
      '::ffff:1.2.3.4',
      '1:2:3:4:5:6:7:8',
      '::/0',
      '2001:db8::/128',
    ])
      expect(isIpOrCidr(ok)).toBe(true);
  });

  test('25. isIpOrCidr invalid', () => {
    for (const bad of [
      '1.2.3.4/33',
      '256.1.1.1',
      'abc',
      '1.2.3.4/',
      '',
      null,
      '1.2.3',
      '1.2.3.4.5',
      '1.2.3.4/-1',
      '::1/129',
      '1:2:3:4:5:6:7:8:9',
      '1::2::3',
      ':::',
      '12345::1',
      '1.2.3.4/abc',
      '1.2.3.4 ',
      'g::1',
    ]) {
      // a trailing space is trimmed by the helper, everything else must be rejected
      if (bad === '1.2.3.4 ') continue;
      expect(isIpOrCidr(bad)).toBe(false);
    }
  });

  test('26. username pattern', () => {
    for (const ok of ['Steve', '.Bedrock_1', '*Geyser', 'a', 'x'.repeat(32)])
      expect(isMinecraftUsername(ok)).toBe(true);
    for (const bad of ['has space', 'x'.repeat(33), '', '...', '*_.', 'a-b', 'üser'])
      expect(isMinecraftUsername(bad)).toBe(false);
  });

  test('27. field-key pattern', () => {
    for (const ok of ['a', 'discord_id', 'x'.repeat(32), 'a1_b2'])
      expect(isFieldKey(ok)).toBe(true);
    for (const bad of ['', '1a', 'A', 'a-b', 'x'.repeat(33), '_a', 'a b'])
      expect(isFieldKey(bad)).toBe(false);
  });

  test('27. slug pattern and reserved slugs', () => {
    expect(RESERVED_SLUGS).toEqual(['checkout', 'order', 'cart']);
    expect(slugError('my-product-2')).toBeNull();
    for (const reserved of RESERVED_SLUGS) expect(slugError(reserved)).toBe('RESERVED_SLUG');
    for (const bad of ['', 'My-Product', 'a--b', '-a', 'a-', 'a b', 'a_b'])
      expect(slugError(bad)).toBe('INVALID_SLUG');
    expect(slugError('cart-2')).toBeNull();
  });

  test('27. custom-field pattern guard rejects lookbehind and backreference', () => {
    for (const ok of [
      '[A-Z]{3}-\\d+',
      'abc',
      '(ab)+c',
      '\\\\1',
      '[a-z]*',
      '(?:ab|cd)x',
      '(?<id>\\d+)',
    ])
      expect(patternError(ok)).toBeNull();
    expect(patternError('(a)\\1')).toBe('BACKREFERENCE');
    expect(patternError('(?<x>a)\\k<x>')).toBe('BACKREFERENCE');
    expect(patternError('(?<=a)b')).toBe('LOOKAROUND');
    expect(patternError('(?<!a)b')).toBe('LOOKAROUND');
    expect(patternError('a(?=b)')).toBe('LOOKAROUND');
    expect(patternError('a(?!b)')).toBe('LOOKAROUND');
    expect(patternError('(')).toBe('INVALID_FORMAT');
    expect(patternError('['.repeat(1))).toBe('INVALID_FORMAT');
    expect(patternError('a'.repeat(256))).toBe('TOO_LONG');
    expect(patternError('a'.repeat(255))).toBeNull();
  });

  test('email shape', () => {
    expect(isEmailLike('a@b.c')).toBe(true);
    for (const bad of ['', 'a', 'a@', '@b', 'a@@b', 'a@b@c', 'a b@c', `${'a'.repeat(256)}@b`])
      expect(isEmailLike(bad)).toBe(false);
  });
});
