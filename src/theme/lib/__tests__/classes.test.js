import { describe, expect, test } from 'bun:test';
import { alertClass, badgeClass, iconClass, toneClass } from '../classes.js';

describe('classes that come from data', () => {
  test('a given class passes through unchanged', () => {
    expect(badgeClass('text-bg-success')).toBe('text-bg-success');
    expect(alertClass('alert-danger')).toBe('alert-danger');
    expect(iconClass('fa-solid fa-coins')).toBe('fa-solid fa-coins');
    expect(toneClass('text-warning')).toBe('text-warning');
  });

  test('an empty value falls back to the default of its family', () => {
    for (const empty of [undefined, null, '']) {
      expect(badgeClass(empty)).toBe('text-bg-secondary');
      expect(alertClass(empty)).toBe('alert-info');
      expect(iconClass(empty)).toBe('fa-solid fa-circle');
      expect(toneClass(empty)).toBe('text-body-secondary');
    }
  });

  test('the caller may give its own fallback', () => {
    expect(iconClass(null, 'fa-box')).toBe('fa-box');
    expect(alertClass(undefined, 'alert-danger')).toBe('alert-danger');
  });
});
