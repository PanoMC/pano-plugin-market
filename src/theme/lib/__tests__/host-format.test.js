import { beforeEach, describe, expect, test } from 'bun:test';
import { language } from './sdkMocks.js';

const host = await import('../../utils/host.js');
const format = await import('../../utils/format.js');

describe('host', () => {
  beforeEach(() => host.setPano(null));

  test('no pano or no features means no feature', () => {
    expect(host.has('page-meta')).toBe(false);
    host.setPano({});
    expect(host.has('page-meta')).toBe(false);
    host.setPano({ features: {} });
    expect(host.has('page-meta')).toBe(false);
  });

  test('has follows pano.features.has', () => {
    host.setPano({ features: { has: (id) => id === 'page-meta' } });
    expect(host.has('page-meta')).toBe(true);
    expect(host.has('profile-nav')).toBe(false);
  });

  test('loginUrl uses the host when it can return, else /login', () => {
    host.setPano({});
    expect(host.loginUrl('/store')).toBe('/login');
    host.setPano({
      features: { has: (id) => id === 'login-return-url' },
      auth: { loginUrl: (r) => `/login?redirect=${r}` },
    });
    expect(host.loginUrl('/store')).toBe('/login?redirect=/store');
    host.setPano({ features: { has: () => true } });
    expect(host.loginUrl('/store')).toBe('/login');
  });

  test('registerUrl', () => {
    host.setPano({});
    expect(host.registerUrl('/store')).toBe('/register');
    host.setPano({ features: { has: (id) => id === 'login-return-url' } });
    expect(host.registerUrl('/store/checkout')).toBe('/register?redirect=%2Fstore%2Fcheckout');
    expect(host.registerUrl('//evil')).toBe('/register');
  });

  test('view delegates to pano.ui.view.get', () => {
    host.setPano({ ui: { view: { get: (id) => `items:${id}` } } });
    expect(host.view('profile-content')).toBe('items:profile-content');
  });
});

describe('format', () => {
  test('formatPrice keeps its behaviour', () => {
    expect(format.formatPrice(5, { currency: 'USD' })).toBe('$5.00');
    expect(format.formatPrice(5, { currency: 'USD', removeCents: true })).toBe('$5');
    expect(format.formatPrice('x', { currencySymbol: '#' })).toBe('#0.00');
    expect(format.formatPrice(1.5, { currency: 'NOPE1', currencySymbol: '$' })).toBe('$1.50');
  });

  test('formatMoney with explicit currency and fallback', () => {
    expect(format.formatMoney(12.5, 'EUR')).toBe('€12.50');
    expect(format.formatMoney(12, 'EUR', { removeCents: true })).toBe('€12');
    expect(format.formatMoney(3, 'NOPE1')).toBe('3.00 NOPE1');
  });

  test('formatCredits has at most two decimals', () => {
    expect(format.formatCredits(10, 'Gold')).toBe('10 Gold');
    expect(format.formatCredits(1.2345, 'Gold')).toBe('1.23 Gold');
    expect(format.formatCredits(1000, '')).toBe('1,000');
  });

  test('dates', () => {
    const ms = Date.UTC(2026, 0, 15, 12, 0, 0);
    expect(format.formatDate(ms)).toContain('2026');
    expect(format.formatDateTime(ms).length).toBeGreaterThan(format.formatDate(ms).length);
    expect(format.formatDate(0)).toBe('');
    expect(format.formatDate('nope')).toBe('');
  });

  test('formatPeriod passes the count as ICU value', () => {
    let got;
    const $_ = (key, options) => {
      got = [key, options];
      return 'x';
    };
    format.formatPeriod('MONTH', 3, $_);
    expect(got).toEqual(['theme.period.MONTH', { values: { count: 3 } }]);
  });

  test('countryName', () => {
    expect(format.countryName('TR')).toBe('Türkiye');
    expect(format.countryName('')).toBe('');
    expect(format.countryName('zz9')).toBe('zz9');
  });

  test('locale follows the current language', () => {
    language.set({ code: 'tr' });
    expect(format.formatMoney(1234.5, 'TRY')).toContain('1.234,50');
    language.set({ code: 'en-US' });
  });
});
