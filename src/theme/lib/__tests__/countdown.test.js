import { describe, expect, test } from 'bun:test';
import {
  COUNTDOWN_WINDOW_MS,
  DAY_MS,
  HOUR_MS,
  REFETCH_DELAY_MS,
  format,
  remaining,
  withinWindow,
} from '../countdown.js';

describe('format', () => {
  test('renders days, hours, minutes and seconds', () => {
    expect(format(2 * DAY_MS + 3 * HOUR_MS + 4 * 60000 + 5000)).toBe('2d 03:04:05');
  });

  test('omits the day part when it is 0', () => {
    expect(format(3 * HOUR_MS + 4 * 60000 + 5000)).toBe('03:04:05');
    expect(format(59 * 1000)).toBe('00:00:59');
  });

  test('keeps the day part when only the clock is zero', () => {
    expect(format(DAY_MS)).toBe('1d 00:00:00');
  });

  test('rounds the seconds down', () => {
    expect(format(1999)).toBe('00:00:01');
    expect(format(999)).toBe('00:00:00');
  });

  test('a zero, negative or non-numeric value renders zeros', () => {
    expect(format(0)).toBe('00:00:00');
    expect(format(-5000)).toBe('00:00:00');
    expect(format(Number.NaN)).toBe('00:00:00');
    expect(format(undefined)).toBe('00:00:00');
  });
});

describe('remaining and window', () => {
  test('remaining is clamped at zero and 0 without an end', () => {
    expect(remaining(10_000, 4_000)).toBe(6_000);
    expect(remaining(10_000, 12_000)).toBe(0);
    expect(remaining(null, 1)).toBe(0);
    expect(remaining(0, 1)).toBe(0);
    expect(remaining('x', 1)).toBe(0);
  });

  test('the window is 0 < left <= 72 h', () => {
    const now = 1_000_000;
    expect(COUNTDOWN_WINDOW_MS).toBe(72 * HOUR_MS);
    expect(withinWindow(now + COUNTDOWN_WINDOW_MS, now)).toBe(true);
    expect(withinWindow(now + COUNTDOWN_WINDOW_MS + 1, now)).toBe(false);
    expect(withinWindow(now + 1, now)).toBe(true);
    expect(withinWindow(now, now)).toBe(false);
    expect(withinWindow(now - 1, now)).toBe(false);
  });

  test('the refetch waits for the 30 s response cache', () => {
    expect(REFETCH_DELAY_MS).toBe(35_000);
  });
});
