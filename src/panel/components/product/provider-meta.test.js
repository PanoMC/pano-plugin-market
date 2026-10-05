import { describe, expect, test } from 'bun:test';
import {
  SECRET_MASK,
  buildMeta,
  buildProviderMeta,
  groupFields,
  initialValues,
  isSecretField,
  isVisible,
  resolveText,
  secretOnBlur,
  secretOnFocus,
  secretOnInput,
  validateField,
  validateProviderMeta,
  validateSchema,
} from './provider-meta.js';

const schema = {
  fields: [
    {
      key: 'mode',
      type: 'SELECT',
      required: true,
      options: [{ value: 'a' }, { value: 'b' }],
      default: 'a',
    },
    { key: 'extra', type: 'TEXT', visibleWhen: { field: 'mode', anyOf: ['b'] }, required: true },
    { key: 'limit', type: 'NUMBER', min: 1, max: 10 },
    { key: 'site', type: 'URL' },
    { key: 'flag', type: 'SWITCH' },
    { key: 'token', type: 'PASSWORD', required: true, pattern: '^[a-z]+$' },
    { key: 'info', type: 'NOTICE' },
    { key: 'ro', type: 'READONLY' },
  ],
  groups: [{ key: 'adv', label: { default: 'Advanced' } }],
};

describe('resolveText', () => {
  test('null is empty, a plain string is itself', () => {
    expect(resolveText(null)).toBe('');
    expect(resolveText('x')).toBe('x');
  });
  test('{key, fallback} uses the translation when it exists', () => {
    const t = (key) => (key === 'known' ? 'Translated' : key);
    expect(resolveText({ key: 'known', fallback: 'F' }, 'en-US', t)).toBe('Translated');
    expect(resolveText({ key: 'missing', fallback: 'F' }, 'en-US', t)).toBe('F');
  });
  test('locale map: exact, language, default', () => {
    const text = { default: 'D', translations: { tr: 'T', 'en-US': 'E' } };
    expect(resolveText(text, 'en-US')).toBe('E');
    expect(resolveText(text, 'tr-TR')).toBe('T');
    expect(resolveText(text, 'ru')).toBe('D');
  });
});

describe('initialValues / buildMeta', () => {
  test('stored first, then default, then false / empty; READONLY and NOTICE excluded', () => {
    expect(initialValues(schema, { limit: 3 })).toEqual({
      mode: 'a',
      extra: '',
      limit: 3,
      site: '',
      flag: false,
      token: '',
    });
  });
  test('a stored masked secret is kept as is', () => {
    expect(initialValues(schema, { token: SECRET_MASK }).token).toBe(SECRET_MASK);
  });
  test('buildMeta: numbers as numbers (null when empty), switches boolean, hidden values kept', () => {
    const out = buildMeta(schema, { mode: 'a', extra: 'keep', limit: '4', flag: 1, token: 'abc' });
    expect(out).toEqual({ mode: 'a', extra: 'keep', limit: 4, site: '', flag: true, token: 'abc' });
    expect(buildMeta(schema, { limit: '' }).limit).toBeNull();
  });
  test('buildProviderMeta fills defaults for every declared provider and drops unknown ones', () => {
    const schemas = [{ providerId: 'p1', name: 'P', schema }];
    const out = buildProviderMeta(schemas, { p1: { limit: 2 }, gone: { x: 1 } });
    expect(out.p1.mode).toBe('a');
    expect(out.p1.limit).toBe(2);
    expect(out.gone).toBeUndefined();
  });
});

describe('visibility', () => {
  test('a conditional field is shown only when the referenced value matches', () => {
    const extra = schema.fields[1];
    expect(isVisible(extra, { mode: 'a' }, schema)).toBe(false);
    expect(isVisible(extra, { mode: 'b' }, schema)).toBe(true);
  });
  test('a chain hides the child of a hidden parent', () => {
    const chained = {
      fields: [
        { key: 'a', type: 'SELECT' },
        { key: 'b', type: 'TEXT', visibleWhen: { field: 'a', anyOf: ['x'] } },
        { key: 'c', type: 'TEXT', visibleWhen: { field: 'b', anyOf: ['y'] } },
      ],
    };
    expect(isVisible(chained.fields[2], { a: 'z', b: 'y' }, chained)).toBe(false);
    expect(isVisible(chained.fields[2], { a: 'x', b: 'y' }, chained)).toBe(true);
  });
  test('cyclic conditions terminate', () => {
    const cyc = {
      fields: [
        { key: 'a', type: 'TEXT', visibleWhen: { field: 'b', anyOf: ['1'] } },
        { key: 'b', type: 'TEXT', visibleWhen: { field: 'a', anyOf: ['1'] } },
      ],
    };
    expect(typeof isVisible(cyc.fields[0], { a: '1', b: '1' }, cyc)).toBe('boolean');
  });
});

describe('validation', () => {
  test('required empty, required masked secret passes', () => {
    expect(validateField(schema.fields[5], '')).toBe('REQUIRED');
    expect(validateField(schema.fields[5], SECRET_MASK)).toBeNull();
  });
  test('the pattern applies to plain fields only, never to secrets (13 §16.3)', () => {
    const plain = { key: 'code', type: 'TEXT', pattern: '^[a-z]+$' };
    expect(validateField(plain, 'ABC')).toBe('INVALID_FORMAT');
    expect(validateField(plain, 'abc')).toBeNull();
    expect(validateField(schema.fields[5], 'ABC')).toBeNull();
  });
  test('NUMBER: non-numeric and out of range', () => {
    expect(validateField(schema.fields[2], 'x')).toBe('INVALID_NUMBER');
    expect(validateField(schema.fields[2], '0')).toBe('OUT_OF_RANGE');
    expect(validateField(schema.fields[2], '11')).toBe('OUT_OF_RANGE');
    expect(validateField(schema.fields[2], '5')).toBeNull();
  });
  test('URL rejects javascript: and non-URLs', () => {
    expect(validateField(schema.fields[3], 'javascript:alert(1)')).toBe('INVALID_URL');
    expect(validateField(schema.fields[3], 'nope')).toBe('INVALID_URL');
    expect(validateField(schema.fields[3], 'https://a.test')).toBeNull();
  });
  test('SELECT value must be an option', () => {
    expect(validateField(schema.fields[0], 'z')).toBe('INVALID_OPTION');
  });
  test('hidden required fields are skipped, a bad pattern is ignored', () => {
    expect(validateField(schema.fields[1], '', false)).toBeNull();
    expect(validateField({ key: 'k', type: 'TEXT', pattern: '(' }, 'x')).toBeNull();
  });
  test('validateSchema keys by field; validateProviderMeta by provider path', () => {
    expect(validateSchema(schema, { mode: 'b', extra: '', token: 'abc' })).toEqual({
      extra: 'REQUIRED',
    });
    const errors = validateProviderMeta([{ providerId: 'p1', schema }], {
      p1: { mode: 'b', token: '' },
    });
    expect(errors).toEqual({
      'providerMeta.p1.extra': 'REQUIRED',
      'providerMeta.p1.token': 'REQUIRED',
    });
  });
  test('a product with no provider schemas has no meta errors', () => {
    expect(validateProviderMeta([], {})).toEqual({});
  });
});

describe('groupFields', () => {
  test('ungrouped fields first, then one block per declared group; empty groups dropped', () => {
    const grouped = groupFields({
      fields: [
        { key: 'a', type: 'TEXT' },
        { key: 'b', type: 'TEXT', group: 'g1' },
        { key: 'c', type: 'TEXT', group: 'unknown' },
      ],
      groups: [
        { key: 'g1', label: 'One' },
        { key: 'g2', label: 'Two' },
      ],
    });
    expect(grouped.map((g) => g.key)).toEqual([null, 'g1']);
    expect(grouped[0].fields.map((f) => f.key)).toEqual(['a', 'c']);
  });
});

describe('secret mask protocol (13 §16.3)', () => {
  test('isSecretField: secret flag, PASSWORD and SECRET_TEXTAREA', () => {
    expect(isSecretField({ type: 'TEXT', secret: true })).toBe(true);
    expect(isSecretField({ type: 'PASSWORD' })).toBe(true);
    expect(isSecretField({ type: 'SECRET_TEXTAREA' })).toBe(true);
    expect(isSecretField({ type: 'TEXT' })).toBe(false);
    expect(isSecretField(null)).toBe(false);
  });

  test('focus on a masked value empties it, any other value is kept', () => {
    expect(secretOnFocus(SECRET_MASK)).toBe('');
    expect(secretOnFocus('abc')).toBe('abc');
    expect(secretOnFocus('')).toBe('');
  });

  test('blur with nothing typed restores the mask only when the field was masked', () => {
    expect(secretOnBlur('', true)).toBe(SECRET_MASK);
    expect(secretOnBlur('', false)).toBe('');
    expect(secretOnBlur('typed', true)).toBe('typed');
  });

  test('a typed secret replaces the mask and the mask is never concatenated', () => {
    let value = secretOnFocus(SECRET_MASK);
    value = secretOnInput(value + 'abc');
    expect(value).toBe('abc');
    // a paste or autofill that kept the mask in front of the new secret
    expect(secretOnInput(SECRET_MASK + 'abc')).toBe('abc');
    expect(secretOnInput(SECRET_MASK)).toBe(SECRET_MASK);
    expect(secretOnInput('')).toBe('');
  });

  test('focus, type and build never send a mask-prefixed secret', () => {
    const secretSchema = { fields: [{ key: 'token', type: 'PASSWORD' }] };
    const typed = secretOnInput(secretOnFocus(SECRET_MASK) + 'newsecret');
    const meta = buildMeta(secretSchema, { token: typed });
    expect(meta.token).toBe('newsecret');
    expect(meta.token.startsWith(SECRET_MASK)).toBe(false);
    // untouched: focus + blur round trip keeps the mask, i.e. "keep the stored secret"
    const back = secretOnBlur(secretOnFocus(SECRET_MASK), true);
    expect(buildMeta(secretSchema, { token: back }).token).toBe(SECRET_MASK);
  });
});
