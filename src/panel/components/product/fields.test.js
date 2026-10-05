import { describe, expect, test } from 'bun:test';
import {
  MAX_FIELDS,
  blankField,
  fieldFromApi,
  fieldRowErrorKey,
  moveRow,
  serializeFields,
  suggestFieldKey,
  validateField,
  validateFields,
} from './fields.js';

const field = (extra = {}) => blankField({ fieldKey: 'rank', label: 'Rank', ...extra });

describe('suggestFieldKey', () => {
  test('lower-cases, drops accents and joins words with an underscore', () => {
    expect(suggestFieldKey('Minecraft Kullanıcı Adı')).toBe('minecraft_kullanici_adi');
    expect(suggestFieldKey('  Rank  ')).toBe('rank');
  });
  test('starts with a letter and stays within 32 characters', () => {
    expect(suggestFieldKey('1st place')).toBe('st_place');
    expect(suggestFieldKey('x'.repeat(50))).toHaveLength(32);
    expect(suggestFieldKey('')).toBe('');
    expect(suggestFieldKey('123')).toBe('');
  });
  test('the suggestion satisfies the key pattern whenever it is non-empty', () => {
    for (const label of ['Hello World', 'Ünï cödé', 'A-B-C', 'foo.bar', '#1 Fan'])
      expect(suggestFieldKey(label)).toMatch(/^[a-z][a-z0-9_]{0,31}$/);
  });
});

describe('validateField', () => {
  test('a minimal field is valid', () => {
    expect(validateField(field())).toEqual({});
  });
  test('label is required and at most 255', () => {
    expect(validateField(field({ label: ' ' })).label).toBe('REQUIRED');
    expect(validateField(field({ label: 'x'.repeat(256) })).label).toBe('TOO_LONG');
  });
  test('key pattern and uniqueness', () => {
    expect(validateField(field({ fieldKey: '' })).fieldKey).toBe('REQUIRED');
    expect(validateField(field({ fieldKey: 'Rank' })).fieldKey).toBe('INVALID_FORMAT');
    expect(validateField(field({ fieldKey: '1a' })).fieldKey).toBe('INVALID_FORMAT');
    expect(validateField(field({ fieldKey: 'a'.repeat(33) })).fieldKey).toBe('INVALID_FORMAT');
    expect(validateField(field(), [{ fieldKey: 'rank' }]).fieldKey).toBe('DUPLICATE');
  });
  test('length limits of the texts', () => {
    const errors = validateField(
      field({
        helpText: 'x'.repeat(513),
        placeholder: 'x'.repeat(256),
        defaultValue: 'x'.repeat(256),
      }),
    );
    expect(errors.helpText).toBe('TOO_LONG');
    expect(errors.placeholder).toBe('TOO_LONG');
    expect(errors.defaultValue).toBe('TOO_LONG');
  });
  test('SELECT needs 1 to 50 options with unique values matching the pattern', () => {
    expect(validateField(field({ type: 'SELECT' })).options).toBe('REQUIRED');
    const many = Array.from({ length: 51 }, (_, i) => ({ value: `v${i}`, label: 'L' }));
    expect(validateField(field({ type: 'SELECT', options: many })).options).toBe('TOO_MANY');
    const errors = validateField(
      field({
        type: 'SELECT',
        options: [
          { value: 'a', label: 'A' },
          { value: 'a', label: 'B' },
          { value: 'bad value', label: 'C' },
          { value: 'd', label: '' },
        ],
      }),
    );
    expect(errors['options.1.value']).toBe('DUPLICATE');
    expect(errors['options.2.value']).toBe('INVALID_FORMAT');
    expect(errors['options.3.label']).toBe('REQUIRED');
    expect(errors['options.0.value']).toBeUndefined();
  });
  test('pattern: TEXT only, RE2-safe subset', () => {
    expect(validateField(field({ pattern: '[A-Z]{3}' }))).toEqual({});
    expect(validateField(field({ pattern: '(a)\\1' })).pattern).toBe('BACKREFERENCE');
    expect(validateField(field({ pattern: '(?<=a)b' })).pattern).toBe('LOOKAROUND');
    expect(validateField(field({ pattern: '(?!x)y' })).pattern).toBe('LOOKAROUND');
    expect(validateField(field({ pattern: '(' })).pattern).toBe('INVALID_FORMAT');
    expect(validateField(field({ pattern: 'x'.repeat(256) })).pattern).toBe('TOO_LONG');
    expect(validateField(field({ type: 'NUMBER', pattern: '(a)\\1' })).pattern).toBeUndefined();
  });
  test('TEXT length bounds: 0 <= min <= max <= 128', () => {
    expect(validateField(field({ minLength: 5, maxLength: 3 })).minLength).toBe('MIN_ABOVE_MAX');
    expect(validateField(field({ maxLength: 129 })).maxLength).toBe('OUT_OF_RANGE');
    expect(validateField(field({ minLength: -1 })).minLength).toBe('OUT_OF_RANGE');
    expect(validateField(field({ minLength: 1.5 })).minLength).toBe('NOT_INTEGER');
    expect(validateField(field({ minLength: 0, maxLength: 128 }))).toEqual({});
  });
  test('NUMBER bounds are integers with min <= max', () => {
    const number = (extra) => field({ type: 'NUMBER', ...extra });
    expect(validateField(number({ minValue: 10, maxValue: 1 })).minValue).toBe('MIN_ABOVE_MAX');
    expect(validateField(number({ minValue: 1.5 })).minValue).toBe('NOT_INTEGER');
    expect(validateField(number({ minValue: -5, maxValue: 5 }))).toEqual({});
  });
  test('defaultValue must satisfy the field own rules', () => {
    const base = { defaultValue: '' };
    expect(validateField(field({ ...base, type: 'NUMBER', defaultValue: 'x' })).defaultValue).toBe(
      'INVALID_NUMBER',
    );
    expect(
      validateField(field({ type: 'NUMBER', defaultValue: '50', minValue: 1, maxValue: 10 }))
        .defaultValue,
    ).toBe('OUT_OF_RANGE');
    expect(
      validateField(
        field({ type: 'SELECT', options: [{ value: 'a', label: 'A' }], defaultValue: 'b' }),
      ).defaultValue,
    ).toBe('INVALID_OPTION');
    expect(validateField(field({ type: 'CHECKBOX', defaultValue: 'maybe' })).defaultValue).toBe(
      'INVALID',
    );
    expect(validateField(field({ type: 'CHECKBOX', defaultValue: 'true' }))).toEqual({});
    expect(validateField(field({ type: 'USERNAME', defaultValue: 'bad name' })).defaultValue).toBe(
      'INVALID_FORMAT',
    );
    expect(validateField(field({ type: 'USERNAME', defaultValue: '.Steve' }))).toEqual({});
    expect(validateField(field({ type: 'EMAIL', defaultValue: 'nope' })).defaultValue).toBe(
      'INVALID_FORMAT',
    );
    expect(validateField(field({ type: 'DISCORD_ID', defaultValue: '123' })).defaultValue).toBe(
      'INVALID_FORMAT',
    );
    expect(validateField(field({ type: 'DISCORD_ID', defaultValue: '12345678901234567' }))).toEqual(
      {},
    );
    expect(validateField(field({ pattern: '[a-z]+', defaultValue: 'ABC' })).defaultValue).toBe(
      'INVALID_FORMAT',
    );
    expect(validateField(field({ minLength: 3, defaultValue: 'ab' })).defaultValue).toBe(
      'OUT_OF_RANGE',
    );
  });
});

describe('validateFields', () => {
  test('prefixes the index and reports duplicates on the later row', () => {
    const errors = validateFields([field(), field(), field({ fieldKey: 'x y' })]);
    expect(errors['fields.1.fieldKey']).toBe('DUPLICATE');
    expect(errors['fields.2.fieldKey']).toBe('INVALID_FORMAT');
    expect(errors['fields.0.fieldKey']).toBeUndefined();
  });
  test('more than 20 fields is TOO_MANY', () => {
    const rows = Array.from({ length: MAX_FIELDS + 1 }, (_, i) => field({ fieldKey: `f${i}` }));
    expect(validateFields(rows).fields).toBe('TOO_MANY');
    expect(validateFields(rows.slice(0, MAX_FIELDS))).toEqual({});
  });
  test('no fields is valid', () => {
    expect(validateFields([])).toEqual({});
    expect(validateFields(undefined)).toEqual({});
  });
});

describe('serializeFields', () => {
  test('strips the local flag and keeps only the parts of the type', () => {
    const [wire] = serializeFields([
      field({
        type: 'SELECT',
        options: [{ value: 'a', label: 'A' }],
        pattern: 'x',
        minLength: 2,
        minValue: 1,
      }),
    ]);
    expect('isNew' in wire).toBe(false);
    expect(wire.options).toEqual([{ value: 'a', label: 'A' }]);
    expect(wire.pattern).toBeNull();
    expect(wire.minLength).toBeNull();
    expect(wire.minValue).toBeNull();
  });
  test('TEXT keeps pattern and lengths, NUMBER keeps its bounds', () => {
    const [text, number] = serializeFields([
      field({ pattern: '[a-z]+', minLength: '2', maxLength: 9 }),
      field({ fieldKey: 'n', type: 'NUMBER', minValue: '1', maxValue: '9' }),
    ]);
    expect(text.pattern).toBe('[a-z]+');
    expect(text.minLength).toBe(2);
    expect(text.maxLength).toBe(9);
    expect(number.minValue).toBe(1);
    expect(number.maxValue).toBe(9);
    expect(number.options).toBeNull();
  });
  test('empty optional texts become null and CHECKBOX is never usable in commands', () => {
    const [wire] = serializeFields([field({ type: 'CHECKBOX', usableInCommands: true })]);
    expect(wire.helpText).toBeNull();
    expect(wire.placeholder).toBeNull();
    expect(wire.defaultValue).toBeNull();
    expect(wire.usableInCommands).toBe(false);
  });
  test('a loaded row round-trips through the model and the wire', () => {
    const api = {
      fieldKey: 'rank',
      label: 'Rank',
      helpText: 'Pick one',
      type: 'SELECT',
      required: true,
      options: [
        { value: 'a', label: 'A' },
        { value: 'b', label: 'B' },
      ],
      placeholder: null,
      defaultValue: 'a',
      usableInCommands: true,
      pattern: null,
      minLength: null,
      maxLength: null,
      minValue: null,
      maxValue: null,
    };
    const state = fieldFromApi(api);
    expect(state.isNew).toBe(false);
    expect(serializeFields([state])[0]).toEqual(api);
  });
  test('an API row without usableInCommands defaults to on', () => {
    expect(fieldFromApi({ fieldKey: 'a', label: 'A' }).usableInCommands).toBe(true);
    expect(
      fieldFromApi({ fieldKey: 'a', label: 'A', usableInCommands: false }).usableInCommands,
    ).toBe(false);
  });
});

describe('moveRow and error keys', () => {
  test('moveRow swaps neighbours and ignores out-of-range moves', () => {
    expect(moveRow([1, 2, 3], 0, 1)).toEqual([2, 1, 3]);
    expect(moveRow([1, 2, 3], 2, -1)).toEqual([1, 3, 2]);
    const same = [1, 2];
    expect(moveRow(same, 0, -1)).toBe(same);
    expect(moveRow(same, 1, 1)).toBe(same);
  });
  test('fieldRowErrorKey falls back to INVALID', () => {
    expect(fieldRowErrorKey('MIN_ABOVE_MAX')).toBe(
      'pages.create-product.field-errors.MIN_ABOVE_MAX',
    );
    expect(fieldRowErrorKey('???')).toBe('pages.create-product.field-errors.INVALID');
  });
});
