import { describe, expect, test } from 'bun:test';
import { COUNTRY_CODES, countryOptions, isCountryCode } from '../countries.js';

describe('countries', () => {
  test('the list holds 249 distinct upper-case alpha-2 codes', () => {
    expect(COUNTRY_CODES.length).toBe(249);
    expect(new Set(COUNTRY_CODES).size).toBe(249);
    expect(COUNTRY_CODES.every((c) => /^[A-Z]{2}$/.test(c))).toBe(true);
    expect(['TR', 'US', 'DE', 'RU', 'GB'].every((c) => COUNTRY_CODES.includes(c))).toBe(true);
  });

  test('isCountryCode accepts only listed upper-case codes', () => {
    expect(isCountryCode('TR')).toBe(true);
    expect(isCountryCode('tr')).toBe(false);
    expect(isCountryCode('XX')).toBe(false);
    expect(isCountryCode(null)).toBe(false);
  });

  test('countryOptions sorts by label and drops duplicates and garbage', () => {
    const names = { TR: 'Türkiye', DE: 'Germany', US: 'United States' };
    const out = countryOptions(['US', 'tr', 'DE', 'DE', '', null, 7], (c) => names[c], 'en');

    expect(out.map((o) => o.code)).toEqual(['DE', 'TR', 'US']);
    expect(out[1]).toEqual({ code: 'TR', label: 'Türkiye' });
  });

  test('countryOptions falls back to the code as label and to the full list', () => {
    expect(countryOptions(['ZZ']).map((o) => o.label)).toEqual(['ZZ']);
    expect(countryOptions().length).toBe(249);
    expect(countryOptions('not a list')).toEqual([]);
  });
});
