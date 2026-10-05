import { describe, expect, test } from 'bun:test';
import {
  dayToEpoch,
  formatCredits,
  formatDuration,
  formatMoney,
  formatPercent,
  parseInteger,
  parseMoney,
  toDateInput,
  toEpoch,
  toLocalInput,
} from './format.js';

describe('format (13 25.1 tests 7-11)', () => {
  test('7. parseMoney decimal separators and rejects', () => {
    expect(parseMoney('12,5', 2)).toBe(12.5);
    expect(parseMoney('12.50', 2)).toBe(12.5);
    expect(parseMoney('  7 ', 2)).toBe(7);
    expect(parseMoney('', 2)).toBeNull();
    expect(parseMoney('   ', 2)).toBeNull();
    for (const bad of ['1.234', '-1', '1,2,3', 'abc', '1 000', '1.', '.5', '1e3', '1,234.5'])
      expect(parseMoney(bad, 2)).toBeNaN();
  });

  test('8. exponent 0 accepts integers only', () => {
    expect(parseMoney('500', 0)).toBe(500);
    expect(parseMoney('500.5', 0)).toBeNaN();
    expect(parseMoney('500,0', 0)).toBeNaN();
  });

  test('9. formatMoney: null is a dash, an invalid currency falls back without throwing', () => {
    expect(formatMoney(null, 'USD', 'en-US')).toBe('—');
    expect(formatMoney(undefined, 'USD', 'en-US')).toBe('—');
    expect(formatMoney(12.5, 'XXXX', 'en-US')).toBe('12.50 XXXX');
    expect(formatMoney(12.5, '', 'en-US')).toBe('12.50');
    expect(formatMoney(12.5, 'USD', 'en-US')).toBe('$12.50');
    expect(formatMoney(0, 'USD', 'en-US')).toBe('$0.00');
  });

  test('10. parseInteger', () => {
    expect(parseInteger('07', { min: 1 })).toBe(7);
    expect(parseInteger('1.5')).toBeNaN();
    expect(parseInteger('0', { min: 1 })).toBeNaN();
    expect(parseInteger('11', { max: 10 })).toBeNaN();
    expect(parseInteger('')).toBeNull();
    expect(parseInteger('-3')).toBeNaN();
  });

  test('11. toEpoch(toLocalInput(t)) round-trips to the minute', () => {
    const t = new Date(2026, 9, 5, 14, 37, 21, 500).getTime();
    const minute = Math.floor(t / 60000) * 60000;
    expect(toEpoch(toLocalInput(t))).toBe(minute);
    expect(toLocalInput(null)).toBe('');
    expect(toEpoch('')).toBeNull();
    expect(toEpoch('not a date')).toBeNull();
    expect(toLocalInput(new Date(2026, 0, 2, 3, 4).getTime())).toBe('2026-01-02T03:04');
  });
});

describe('format helpers', () => {
  test('credits and percent', () => {
    expect(formatCredits(1234.5, 'Coins', 'en-US')).toBe('1,234.5 Coins');
    expect(formatCredits(3, '', 'en-US')).toBe('3');
    expect(formatPercent(20, 'en-US')).toBe('20%');
    expect(formatPercent(12.345, 'en-US')).toBe('12.35%');
  });

  test('formatDuration asks the i18n function for the ICU plural', () => {
    const seen = [];
    const out = formatDuration('MONTH', 3, (key, opts) => {
      seen.push([key, opts]);
      return 'x';
    });
    expect(out).toBe('x');
    expect(seen).toEqual([['enums.period-unit.MONTH', { values: { count: 3 } }]]);
  });

  test('date range bounds: start of day and 23:59:59.999', () => {
    const start = dayToEpoch('2026-10-05', false);
    const end = dayToEpoch('2026-10-05', true);
    expect(new Date(start).getHours()).toBe(0);
    const e = new Date(end);
    expect([e.getHours(), e.getMinutes(), e.getSeconds(), e.getMilliseconds()]).toEqual([
      23, 59, 59, 999,
    ]);
    expect(toDateInput(start)).toBe('2026-10-05');
    expect(dayToEpoch('', false)).toBeNull();
    expect(dayToEpoch('2026-1-5', false)).toBeNull();
  });
});
