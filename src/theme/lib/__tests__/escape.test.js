import { expect, test } from 'bun:test';
import { escapeHtml } from '../escape.js';

test('escapes the five HTML characters', () => {
  expect(escapeHtml(`<>&"'`)).toBe('&lt;&gt;&amp;&quot;&#39;');
});

test('escapes ampersand once and handles null', () => {
  expect(escapeHtml('<b>a&b</b>')).toBe('&lt;b&gt;a&amp;b&lt;/b&gt;');
  expect(escapeHtml(null)).toBe('');
  expect(escapeHtml(12)).toBe('12');
});
