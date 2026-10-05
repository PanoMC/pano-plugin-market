import { describe, expect, test } from 'bun:test';
import {
  afterCheckout,
  isAttemptPageUrl,
  isSafeExternalUrl,
  orderPagePath,
} from '../paymentStart.js';

const ctx = { origin: 'https://shop.example.com', base: '' };
const order = { publicId: 'ab12CD_-34' };
const ORDER_PAGE = { type: 'GOTO', path: '/store/order/ab12CD_-34' };

describe('isSafeExternalUrl', () => {
  test.each([
    'https://pay.gateway.test/checkout/123',
    'http://localhost:8080/pay',
    'HTTPS://pay.gateway.test/x?a=1&b=2#frag',
  ])('accepts %s', (url) => {
    expect(isSafeExternalUrl(url)).toBe(true);
  });

  test.each([
    'javascript:alert(1)',
    'JaVaScRiPt:alert(1)',
    ' javascript:alert(1)',
    'data:text/html,<script>alert(1)</script>',
    'vbscript:msgbox(1)',
    'file:///etc/passwd',
    'ftp://example.com/x',
    '//evil.example.com/x',
    '/relative/path',
    'https:',
    'https://',
    'https://pay.test/a b',
    'https://pay.test/\u0000',
    'https://pay.test/\nSet-Cookie: a=b',
    '',
    null,
    undefined,
    42,
    {},
    `https://pay.test/${'a'.repeat(5000)}`,
  ])('rejects %p', (url) => {
    expect(isSafeExternalUrl(url)).toBe(false);
  });
});

describe('isAttemptPageUrl', () => {
  test('accepts the attempt page, absolute or root-relative', () => {
    expect(isAttemptPageUrl('/api/market/payments/attempts/tok123/page', ctx)).toBe(true);
    expect(
      isAttemptPageUrl('https://shop.example.com/api/market/payments/attempts/tok123/page', ctx),
    ).toBe(true);
  });

  test('honours a site base path', () => {
    const based = { origin: 'https://shop.example.com', base: '/site' };

    expect(isAttemptPageUrl('/site/api/market/payments/attempts/t/page', based)).toBe(true);
    expect(isAttemptPageUrl('/api/market/payments/attempts/t/page', based)).toBe(false);
  });

  test.each([
    'https://evil.example.com/api/market/payments/attempts/tok/page',
    '//evil.example.com/api/market/payments/attempts/tok/page',
    '/\\evil.example.com/api/market/payments/attempts/tok/page',
    'http://shop.example.com/api/market/payments/attempts/tok/page',
    'https://shop.example.com:8443/api/market/payments/attempts/tok/page',
    'https://user:pw@shop.example.com/api/market/payments/attempts/tok/page',
    '/api/market/payments/attempts/',
    '/api/market/payments/attempts',
    '/api/market/payments/other/tok/page',
    '/api/market/payments/attempts/../../orders/x',
    '/api/market/payments/attempts/%2e%2e/%2e%2e/orders',
    'javascript:alert(1)',
    '/api/market/payments/attempts/tok\npage',
    '',
    null,
    42,
  ])('rejects %p', (url) => {
    expect(isAttemptPageUrl(url, ctx)).toBe(false);
  });

  test('rejects everything when the origin is unknown', () => {
    expect(isAttemptPageUrl('/api/market/payments/attempts/t/page', {})).toBe(false);
  });
});

describe('orderPagePath', () => {
  test('a plain public id builds the order path', () => {
    expect(orderPagePath('ab12CD_-34')).toBe('/store/order/ab12CD_-34');
  });

  test.each(['', '../x', 'a/b', 'a b', 'a?b', '<script>', null, undefined, 7, 'x'.repeat(65)])(
    'a non-id (%p) falls back to /store',
    (id) => {
      expect(orderPagePath(id)).toBe('/store');
    },
  );
});

describe('afterCheckout', () => {
  test('REDIRECT to an https gateway leaves the site', () => {
    expect(afterCheckout({ kind: 'REDIRECT', url: 'https://pay.test/s/1' }, order, ctx)).toEqual({
      type: 'ASSIGN',
      url: 'https://pay.test/s/1',
    });
  });

  test('REDIRECT with a javascript: URL goes to the order page instead', () => {
    expect(afterCheckout({ kind: 'REDIRECT', url: 'javascript:alert(1)' }, order, ctx)).toEqual(
      ORDER_PAGE,
    );
    expect(afterCheckout({ kind: 'REDIRECT', url: 'data:text/html,x' }, order, ctx)).toEqual(
      ORDER_PAGE,
    );
    expect(afterCheckout({ kind: 'REDIRECT' }, order, ctx)).toEqual(ORDER_PAGE);
    expect(afterCheckout({ kind: 'REDIRECT', url: '//evil.test' }, order, ctx)).toEqual(ORDER_PAGE);
  });

  test.each(['FORM_POST', 'HTML'])('%s on the attempt page is followed', (kind) => {
    expect(
      afterCheckout({ kind, url: '/api/market/payments/attempts/tok/page' }, order, ctx),
    ).toEqual({ type: 'ASSIGN', url: '/api/market/payments/attempts/tok/page' });
  });

  test.each(['FORM_POST', 'HTML'])(
    '%s on another origin or path goes to the order page',
    (kind) => {
      expect(
        afterCheckout(
          { kind, url: 'https://evil.test/api/market/payments/attempts/tok/page' },
          order,
          ctx,
        ),
      ).toEqual(ORDER_PAGE);
      expect(afterCheckout({ kind, url: '/somewhere/else' }, order, ctx)).toEqual(ORDER_PAGE);
      expect(afterCheckout({ kind, url: 'javascript:alert(1)' }, order, ctx)).toEqual(ORDER_PAGE);
      expect(afterCheckout({ kind }, order, ctx)).toEqual(ORDER_PAGE);
    },
  );

  test.each(['IFRAME', 'EMBEDDED', 'INSTRUCTIONS', 'COMPLETED'])(
    '%s is rendered on the order page',
    (kind) => {
      expect(afterCheckout({ kind, url: 'https://pay.test/never-used' }, order, ctx)).toEqual(
        ORDER_PAGE,
      );
    },
  );

  test('an unknown kind, a missing payment and an order without id are safe', () => {
    expect(afterCheckout({ kind: 'SOMETHING_NEW' }, order, ctx)).toEqual(ORDER_PAGE);
    expect(afterCheckout(null, order, ctx)).toEqual(ORDER_PAGE);
    expect(afterCheckout(undefined, order, ctx)).toEqual(ORDER_PAGE);
    expect(afterCheckout({ kind: 'COMPLETED' }, null, ctx)).toEqual({
      type: 'GOTO',
      path: '/store',
    });
    expect(afterCheckout({ kind: 'COMPLETED' }, { publicId: '../x' }, ctx)).toEqual({
      type: 'GOTO',
      path: '/store',
    });
  });
});
