import { describe, expect, test } from 'bun:test';
import { ACTION_VARIABLES } from './action-variables.js';

describe('ACTION_VARIABLES', () => {
  test('carries the minimum set fixed by 01 and no duplicates', () => {
    expect(ACTION_VARIABLES).toContain('username');
    expect(ACTION_VARIABLES).toContain('quantity');
    expect(new Set(ACTION_VARIABLES).size).toBe(ACTION_VARIABLES.length);
  });
  test('never offers the buyer-controlled gift message', () => {
    expect(ACTION_VARIABLES.some((name) => name.startsWith('gift'))).toBe(false);
  });
  test('every entry matches the token grammar of 08 §3.1', () => {
    for (const name of ACTION_VARIABLES)
      expect(name).toMatch(/^[a-z][A-Za-z0-9]*(\.[A-Za-z0-9_]+)?$/);
  });
});
