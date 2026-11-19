// Every period unit a product can be sold in has a storefront label in all three locales.
import { describe, expect, test } from 'bun:test';
import fs from 'node:fs';
import path from 'node:path';
import { SUBSCRIPTION_UNITS, TIMED_UNITS } from '../../../panel/components/product/model.js';

const dir = path.resolve(import.meta.dir, '../../../locales/theme');

describe('theme.period locale keys', () => {
  test('cover every TIMED and SUBSCRIPTION unit', () => {
    const units = [...new Set([...TIMED_UNITS, ...SUBSCRIPTION_UNITS])];

    for (const name of ['en-US', 'tr', 'ru']) {
      const tree = JSON.parse(fs.readFileSync(path.join(dir, `${name}.json`), 'utf8'));
      const period = tree?.theme?.period ?? {};
      const missing = units.filter((unit) => typeof period[unit] !== 'string');
      expect({ locale: name, missing }).toEqual({ locale: name, missing: [] });
    }
  });
});
