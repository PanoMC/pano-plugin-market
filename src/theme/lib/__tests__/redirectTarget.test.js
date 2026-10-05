import { describe, expect, test } from 'bun:test';
import { buildLoginPath, buildRegisterPath, sanitizeRedirectTarget } from '../redirectTarget.js';

describe('sanitizeRedirectTarget', () => {
  test('keeps a normal site path with query and hash', () => {
    expect(sanitizeRedirectTarget('/store/checkout')).toBe('/store/checkout');
    expect(sanitizeRedirectTarget('/store/order/ab?token=1#x')).toBe('/store/order/ab?token=1#x');
  });

  test.each([
    '//evil',
    '/\\evil',
    'https://evil',
    'javascript:alert(1)',
    '/a\\b',
    '/a\nb',
    '/a\x00b',
    '/a\x7fb',
    '',
    undefined,
    null,
    42,
  ])('rejects %p', (value) => {
    expect(sanitizeRedirectTarget(value)).toBe('/');
  });

  test('3000 characters fall back, 2048 are allowed', () => {
    expect(sanitizeRedirectTarget(`/${'a'.repeat(2999)}`)).toBe('/');
    expect(sanitizeRedirectTarget(`/${'a'.repeat(2047)}`)).toHaveLength(2048);
  });

  test('auth routes and their sub paths are a loop and fall back', () => {
    for (const p of [
      '/login',
      '/register',
      '/reset-password',
      '/renew-password',
      '/activate',
      '/activate-new-email',
      '/login/x',
    ])
      expect(sanitizeRedirectTarget(p)).toBe('/');
    expect(sanitizeRedirectTarget('/loginx')).toBe('/loginx');
  });

  test('custom fallback', () => {
    expect(sanitizeRedirectTarget('//evil', '/store')).toBe('/store');
  });
});

describe('login / register paths', () => {
  test('plain when nothing safe is given', () => {
    expect(buildLoginPath('//evil')).toBe('/login');
    expect(buildLoginPath('/')).toBe('/login');
    expect(buildRegisterPath(undefined)).toBe('/register');
  });

  test('encodes the target', () => {
    expect(buildLoginPath('/store/checkout?a=1')).toBe(
      '/login?redirect=%2Fstore%2Fcheckout%3Fa%3D1',
    );
    expect(buildRegisterPath('/store')).toBe('/register?redirect=%2Fstore');
  });
});
