import { describe, expect, test } from 'bun:test';
import { createAnchorScroller } from './scroll.js';

function setup() {
  const calls = [];
  const element = { scrollIntoView: () => calls.push('scroll') };
  const state = { element };
  const scroller = createAnchorScroller('deliveries', () => state.element);
  return { calls, state, scroller };
}

describe('createAnchorScroller', () => {
  test('scrolls once, later refreshes do not pull the viewport back', () => {
    const { calls, scroller } = setup();
    expect(scroller('#deliveries', true)).toBe(true);
    expect(scroller('#deliveries', true)).toBe(false);
    expect(scroller('#deliveries', true)).toBe(false);
    expect(calls).toEqual(['scroll']);
  });

  test('waits until the detail is loaded and the element exists', () => {
    const { calls, state, scroller } = setup();
    expect(scroller('#deliveries', false)).toBe(false);
    state.element = null;
    expect(scroller('#deliveries', true)).toBe(false);
    state.element = { scrollIntoView: () => calls.push('late') };
    expect(scroller('#deliveries', true)).toBe(true);
    expect(calls).toEqual(['late']);
  });

  test('ignores other or missing hashes', () => {
    const { calls, scroller } = setup();
    expect(scroller('', true)).toBe(false);
    expect(scroller('#payments', true)).toBe(false);
    expect(calls).toEqual([]);
  });
});
