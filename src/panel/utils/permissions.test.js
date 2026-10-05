import { describe, expect, test } from 'bun:test';
import { ANY_NODE, NODE, can, canNode, perm } from './permissions.js';

const user = (...perms) => ({ admin: false, permissions: perms });

describe('permissions (13 25.1 tests 1-6)', () => {
  test('1. admin passes for every key', () => {
    for (const key of Object.keys(NODE)) expect(can({ admin: true }, key)).toBe(true);
  });

  test('2. the umbrella node passes for every key', () => {
    const u = user(NODE.ALL);
    for (const key of Object.keys(NODE)) expect(can(u, key)).toBe(true);
  });

  test('3. OV only', () => {
    const u = user(NODE.OV);
    expect(can(u, 'OV')).toBe(true);
    expect(can(u, 'PAY')).toBe(false);
    expect(can(u, 'PAY', 'OV')).toBe(true);
  });

  test('4. node comparison is case-insensitive', () => {
    expect(can(user(NODE.OV.toUpperCase()), 'OV')).toBe(true);
    expect(can(user('Pano.Plugin.Pano-Plugin-Market.Manage.Market'), 'SET')).toBe(true);
  });

  test('5. null user or missing permissions is false', () => {
    expect(can(null, 'OV')).toBe(false);
    expect(can(undefined, 'OV')).toBe(false);
    expect(can({ admin: false }, 'OV')).toBe(false);
    expect(can({ admin: false, permissions: null }, 'OV')).toBe(false);
  });

  test('6. perm() returns the node followed by the umbrella node', () => {
    expect(perm('OV')).toEqual([NODE.OV, NODE.ALL]);
    expect(perm('OV', 'PAY')).toEqual([NODE.OV, NODE.PAY, NODE.ALL]);
  });

  test('an unknown key never matches (no undefined node leaks through)', () => {
    expect(can(user('undefined'), 'NOPE')).toBe(false);
  });

  test('ANY_NODE lists the eight nodes', () => {
    expect(ANY_NODE).toHaveLength(8);
    expect(new Set(ANY_NODE).size).toBe(8);
  });
});

describe('canNode (host rule for hooks of other plugins)', () => {
  test('absent or empty permission is allowed', () => {
    expect(canNode(null, undefined)).toBe(true);
    expect(canNode(null, [])).toBe(true);
  });
  test('string and any-of list', () => {
    expect(canNode(user('a.b'), 'A.B')).toBe(true);
    expect(canNode(user('a.b'), ['x.y', 'a.b'])).toBe(true);
    expect(canNode(user('a.b'), ['x.y'])).toBe(false);
  });
  test('no user fails a required node, admin passes', () => {
    expect(canNode(null, 'a.b')).toBe(false);
    expect(canNode({ admin: true }, 'a.b')).toBe(true);
  });
});
