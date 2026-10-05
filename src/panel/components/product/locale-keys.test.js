// Every dynamic locale key the MPU-08 components build exists in all three locales.
import { describe, expect, test } from 'bun:test';
import fs from 'node:fs';
import path from 'node:path';
import {
  ACTION_ERROR_CODES,
  ACTION_TYPES,
  PERMISSION_VIAS,
  PHASES,
  SERVER_MODES,
  WEBHOOK_FORMATS,
  WEBHOOK_SIGNINGS,
  actionErrorKey,
} from '../../utils/actions.js';
import {
  FIELD_ERROR_CODES,
  FIELD_PROPS,
  FIELD_TYPES,
  fieldPropKey,
  fieldRowErrorKey,
} from './fields.js';
import { COOLDOWN_UNITS, extraErrorKey } from './extras.js';
import { META_ERROR_CODES, metaErrorKey } from './provider-meta.js';
import { KIND_FILTERS, STATUS_FILTERS, typeBadges } from '../products/filters.js';

const dir = path.resolve(import.meta.dir, '../../../locales/panel');
const locales = Object.fromEntries(
  ['en-US', 'tr', 'ru'].map((name) => [
    name,
    JSON.parse(fs.readFileSync(path.join(dir, `${name}.json`), 'utf8')),
  ]),
);

const lookup = (tree, key) => key.split('.').reduce((node, part) => node?.[part], tree);

function expectKeys(keys) {
  for (const [name, tree] of Object.entries(locales)) {
    const missing = keys.filter((key) => typeof lookup(tree, key) !== 'string');
    expect({ locale: name, missing }).toEqual({ locale: name, missing: [] });
  }
}

describe('locale keys of the MPU-08 components', () => {
  test('action error codes, types, phases, modes, formats, signings and vias', () => {
    expectKeys([
      ...ACTION_ERROR_CODES.map(actionErrorKey),
      ...ACTION_TYPES.flatMap((type) => [
        `enums.action-type.${type}`,
        `pages.create-product.action-type.${type}`,
        `pages.create-product.action-type-desc.${type}`,
      ]),
      ...PHASES.map((phase) => `enums.phase.${phase}`),
      ...SERVER_MODES.map((mode) => `components.action-editor.mode-${mode}`),
      ...WEBHOOK_FORMATS.map((format) => `components.action-editor.format-${format}`),
      ...WEBHOOK_SIGNINGS.map((signing) => `components.action-editor.signing-${signing}`),
      ...PERMISSION_VIAS.flatMap((via) => [
        `components.action-editor.via-${via}`,
        `components.action-editor.via-${via}-help`,
      ]),
    ]);
  });
  test('field error codes, properties and types', () => {
    expectKeys([
      ...FIELD_ERROR_CODES.map(fieldRowErrorKey),
      ...META_ERROR_CODES.map(metaErrorKey),
      ...[
        'REQUIRED',
        'TOO_LONG',
        'INVALID',
        'INVALID_FORMAT',
        'OUT_OF_RANGE',
        'NOT_INTEGER',
        'ALL_OR_NONE',
      ].map(extraErrorKey),
      ...[...FIELD_PROPS, 'unknown'].map(fieldPropKey),
      ...FIELD_TYPES.map((type) => `modals.product-field.types.${type}`),
    ]);
  });
  test('shipping dimensions, cooldown units, list filters and badges', () => {
    expectKeys([
      ...['lengthMm', 'widthMm', 'heightMm'].map((name) => `pages.create-product.shipping.${name}`),
      ...COOLDOWN_UNITS.map((unit) => `pages.create-product.units.${unit}`),
      ...STATUS_FILTERS.map((status) => `pages.create-product.statuses.${status}`),
      ...KIND_FILTERS.map((kind) => `enums.product-kind.${kind}`),
      ...typeBadges({
        kind: 'STANDARD',
        physical: true,
        billingMode: 'TIMED',
        hasVariants: true,
      }).map((badge) => badge.key),
      ...['TIMED', 'SUBSCRIPTION', 'ONE_TIME'].map((mode) => `enums.billing-mode.${mode}`),
    ]);
  });
  test('the three locales carry the same MPU-08 keys', () => {
    const flat = (tree, prefix = '') =>
      Object.entries(tree).flatMap(([key, value]) =>
        typeof value === 'object' ? flat(value, `${prefix}${key}.`) : [`${prefix}${key}`],
      );
    const keep = (key) =>
      /^(components\.(action-editor|server-picker)|modals\.product-field|pages\.create-product\.(actions|action-type|action-type-desc|action-errors|fields|shipping|limits|seo))\./.test(
        key,
      );
    const en = flat(locales['en-US']).filter(keep).sort();
    expect(en.length).toBeGreaterThan(100);
    expect(flat(locales.tr).filter(keep).sort()).toEqual(en);
    expect(flat(locales.ru).filter(keep).sort()).toEqual(en);
  });
});
