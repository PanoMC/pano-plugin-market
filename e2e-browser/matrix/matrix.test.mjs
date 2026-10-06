// Pure parts of the theme matrix (E2E-20): the theme list parser and the colour modes. The browser run itself is `bun run e2e:browser -- MX-`.
import { describe, expect, test } from 'bun:test';
import { MODES, WIDTHS, modesOf, parseThemes } from './matrix.scenario.mjs';

describe('matrix', () => {
  test('parseThemes reads name=url pairs and trims the trailing slash', () => {
    expect(parseThemes('vanilla=http://127.0.0.1:1/, blaze=http://127.0.0.1:2')).toEqual([
      { name: 'vanilla', url: 'http://127.0.0.1:1' },
      { name: 'blaze', url: 'http://127.0.0.1:2' },
    ]);
    expect(parseThemes(undefined)).toEqual([]);
    expect(parseThemes('')).toEqual([]);
  });

  test('parseThemes rejects an entry without a name', () => {
    expect(() => parseThemes('http://127.0.0.1:1')).toThrow('is not name=url');
  });

  test('widths and modes are those of 14 section 20.4; blaze is dark only', () => {
    expect(WIDTHS).toEqual([360, 768, 1280]);
    expect(MODES).toEqual(['light', 'dark']);
    expect(modesOf('blaze')).toEqual(['dark']);
    expect(modesOf('frost')).toEqual(['light', 'dark']);
  });
});
