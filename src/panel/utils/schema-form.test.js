import { describe, expect, test } from 'bun:test';
import {
  SECRET_MASK,
  applyReveal,
  buildSettingsPayload,
  errorText,
  groupFields,
  initialValues,
  isConfigured,
  isDirty,
  isVisible,
  readonlyValue,
  resolveText,
  secretOnBlur,
  secretOnFocus,
  secretOnInput,
  validate,
  validateField,
} from './schema-form.js';

const field = (key, type, extra = {}) => ({ key, type, label: { default: key }, required: false, ...extra });

describe('resolveText (test 16)', () => {
  const echo = (key) => key;
  test('key text with a missing key falls back', () => {
    expect(resolveText({ key: 'plugins.x.a', fallback: 'Fallback' }, 'en-US', echo)).toBe('Fallback');
  });
  test('key text with a present key uses the translation', () => {
    const t = (key) => (key === 'plugins.x.a' ? 'Translated' : key);
    expect(resolveText({ key: 'plugins.x.a', fallback: 'Fallback' }, 'en-US', t)).toBe('Translated');
  });
  test('locale map: exact, language fallback, default', () => {
    const text = { default: 'Default', translations: { tr: 'Turkce', 'en-US': 'English' } };
    expect(resolveText(text, 'tr')).toBe('Turkce');
    expect(resolveText(text, 'tr-TR')).toBe('Turkce');
    expect(resolveText(text, 'en-US')).toBe('English');
    expect(resolveText(text, 'ru')).toBe('Default');
    expect(resolveText({ default: 'Only' }, 'ru')).toBe('Only');
  });
  test('null and undefined are empty, a plain string stays', () => {
    expect(resolveText(null)).toBe('');
    expect(resolveText(undefined, 'tr')).toBe('');
    expect(resolveText('plain', 'tr')).toBe('plain');
  });
});

describe('initialValues (test 17)', () => {
  const schema = {
    fields: [
      field('a', 'TEXT', { default: 'dflt' }),
      field('b', 'SWITCH'),
      field('c', 'SWITCH', { default: true }),
      field('d', 'NUMBER', { default: 5 }),
      field('e', 'PASSWORD', { secret: true }),
      field('f', 'READONLY', { readonly: { kind: 'STATIC', value: 'x' } }),
      field('g', 'NOTICE'),
      field('h', 'TEXT'),
    ],
  };
  test('default applied, switch default false, text default empty', () => {
    const v = initialValues(schema, {});
    expect(v.a).toBe('dflt');
    expect(v.b).toBe(false);
    expect(v.c).toBe(true);
    expect(v.d).toBe(5);
    expect(v.h).toBe('');
  });
  test('READONLY and NOTICE are excluded', () => {
    const v = initialValues(schema, {});
    expect('f' in v).toBe(false);
    expect('g' in v).toBe(false);
  });
  test('stored values win, a stored mask is kept', () => {
    const v = initialValues(schema, { a: 'mine', e: SECRET_MASK, b: true, c: false });
    expect(v.a).toBe('mine');
    expect(v.e).toBe(SECRET_MASK);
    expect(v.b).toBe(true);
    expect(v.c).toBe(false);
  });
});

describe('isVisible (test 18)', () => {
  const schema = {
    fields: [
      field('mode', 'SELECT', { options: [{ value: 'A' }, { value: 'B' }] }),
      field('child', 'TEXT', { visibleWhen: { field: 'mode', anyOf: ['A'] } }),
      field('grandchild', 'TEXT', { visibleWhen: { field: 'child', anyOf: ['yes'] } }),
      field('flag', 'SWITCH'),
      field('onflag', 'TEXT', { visibleWhen: { field: 'flag', anyOf: ['true'] } }),
      field('x', 'TEXT', { visibleWhen: { field: 'y', anyOf: ['1'] } }),
      field('y', 'TEXT', { visibleWhen: { field: 'x', anyOf: ['1'] } }),
    ],
  };
  const by = (key) => schema.fields.find((f) => f.key === key);
  test('no condition is visible', () => {
    expect(isVisible(by('mode'), {}, schema)).toBe(true);
  });
  test('simple condition', () => {
    expect(isVisible(by('child'), { mode: 'A' }, schema)).toBe(true);
    expect(isVisible(by('child'), { mode: 'B' }, schema)).toBe(false);
  });
  test('boolean values are compared as strings', () => {
    expect(isVisible(by('onflag'), { flag: true }, schema)).toBe(true);
    expect(isVisible(by('onflag'), { flag: false }, schema)).toBe(false);
  });
  test('a hidden parent hides the child', () => {
    expect(isVisible(by('grandchild'), { mode: 'A', child: 'yes' }, schema)).toBe(true);
    expect(isVisible(by('grandchild'), { mode: 'B', child: 'yes' }, schema)).toBe(false);
    expect(isVisible(by('grandchild'), { mode: 'A', child: 'no' }, schema)).toBe(false);
  });
  test('cyclic conditions terminate', () => {
    expect(typeof isVisible(by('x'), { x: '1', y: '1' }, schema)).toBe('boolean');
    expect(typeof isVisible(by('y'), {}, schema)).toBe('boolean');
  });
});

describe('validateField (test 19)', () => {
  test('required and empty', () => {
    expect(validateField(field('a', 'TEXT', { required: true }), '')).toBe('REQUIRED');
    expect(validateField(field('a', 'TEXT', { required: true }), null)).toBe('REQUIRED');
    expect(validateField(field('a', 'TEXT'), '')).toBeNull();
    expect(validateField(field('a', 'SWITCH', { required: true }), false)).toBeNull();
  });
  test('a required secret showing the mask passes', () => {
    const f = field('k', 'PASSWORD', { required: true, secret: true });
    expect(validateField(f, SECRET_MASK)).toBeNull();
    expect(validateField(f, '')).toBe('REQUIRED');
    expect(validateField(f, null)).toBe('REQUIRED');
  });
  test('NUMBER non numeric and out of range', () => {
    const f = field('n', 'NUMBER', { min: 1, max: 10 });
    expect(validateField(f, 'abc')).toBe('INVALID_NUMBER');
    expect(validateField(f, '1.5')).toBe('INVALID_NUMBER');
    expect(validateField(f, '0')).toBe('OUT_OF_RANGE');
    expect(validateField(f, '11')).toBe('OUT_OF_RANGE');
    expect(validateField(f, '5')).toBeNull();
    expect(validateField(f, 7)).toBeNull();
  });
  test('URL with a javascript: scheme is rejected', () => {
    const f = field('u', 'URL');
    expect(validateField(f, 'javascript:alert(1)')).toBe('INVALID_URL');
    expect(validateField(f, 'ftp://example.com')).toBe('INVALID_URL');
    expect(validateField(f, 'not a url')).toBe('INVALID_URL');
    expect(validateField(f, 'https://example.com/x')).toBeNull();
    expect(validateField(f, 'http://example.com')).toBeNull();
  });
  test('SELECT with an unknown option', () => {
    const f = field('s', 'SELECT', { options: [{ value: 'A' }, { value: 'B' }] });
    expect(validateField(f, 'C')).toBe('INVALID_OPTION');
    expect(validateField(f, 'A')).toBeNull();
  });
  test('pattern mismatch', () => {
    const f = field('p', 'TEXT', { pattern: '^[A-Z]{3}$' });
    expect(validateField(f, 'abc')).toBe('INVALID_FORMAT');
    expect(validateField(f, 'ABC')).toBeNull();
    expect(validateField(f, '')).toBeNull();
  });
  test('a secret skips the pattern check', () => {
    const f = field('p', 'PASSWORD', { secret: true, pattern: '^sk_' });
    expect(validateField(f, 'not-sk')).toBeNull();
  });
  test('hidden required field is skipped', () => {
    const f = field('a', 'TEXT', { required: true, visibleWhen: { field: 'm', anyOf: ['1'] } });
    expect(validateField(f, '', false)).toBeNull();
    const schema = { fields: [field('m', 'TEXT'), f] };
    expect(validate(schema, { m: '0', a: '' })).toEqual({});
    expect(validate(schema, { m: '1', a: '' })).toEqual({ a: 'REQUIRED' });
  });
  test('an uncompilable pattern is ignored', () => {
    const f = field('p', 'TEXT', { pattern: '([' });
    expect(validateField(f, 'anything')).toBeNull();
  });
  test('READONLY, NOTICE and HIDDEN are never validated', () => {
    expect(validateField(field('r', 'READONLY', { required: true }), '')).toBeNull();
    expect(validateField(field('n', 'NOTICE', { required: true }), '')).toBeNull();
    expect(validateField(field('h', 'HIDDEN', { required: true }), '')).toBeNull();
  });
});

describe('buildSettingsPayload (test 20)', () => {
  const schema = {
    fields: [
      field('merchant', 'TEXT'),
      field('secret', 'PASSWORD', { secret: true }),
      field('pem', 'SECRET_TEXTAREA', { secret: true }),
      field('limit', 'NUMBER'),
      field('flag', 'SWITCH'),
      field('mode', 'SELECT', { options: [{ value: 'A' }] }),
      field('later', 'TEXT', { visibleWhen: { field: 'mode', anyOf: ['B'] } }),
      field('ro', 'READONLY', { readonly: { kind: 'STATIC', value: 'x' } }),
      field('note', 'NOTICE'),
    ],
  };
  test('untouched secret is sent as the mask', () => {
    const p = buildSettingsPayload(schema, { secret: SECRET_MASK, pem: SECRET_MASK });
    expect(p.secret).toBe(SECRET_MASK);
    expect(p.pem).toBe(SECRET_MASK);
  });
  test('a removed secret is null, a typed one is kept', () => {
    const p = buildSettingsPayload(schema, { secret: null, pem: 'typed' });
    expect(p.secret).toBeNull();
    expect(p.pem).toBe('typed');
  });
  test('hidden field value is included', () => {
    const p = buildSettingsPayload(schema, { mode: 'A', later: 'kept' });
    expect(p.later).toBe('kept');
  });
  test('READONLY and NOTICE are omitted', () => {
    const p = buildSettingsPayload(schema, {});
    expect('ro' in p).toBe(false);
    expect('note' in p).toBe(false);
  });
  test('NUMBER becomes a Number, empty NUMBER becomes null', () => {
    expect(buildSettingsPayload(schema, { limit: '42' }).limit).toBe(42);
    expect(buildSettingsPayload(schema, { limit: 7 }).limit).toBe(7);
    expect(buildSettingsPayload(schema, { limit: '' }).limit).toBeNull();
    expect(buildSettingsPayload(schema, {}).limit).toBeNull();
  });
  test('SWITCH is a boolean', () => {
    expect(buildSettingsPayload(schema, { flag: 1 }).flag).toBe(true);
    expect(buildSettingsPayload(schema, {}).flag).toBe(false);
  });
});

describe('isConfigured, isDirty, groupFields, readonlyValue', () => {
  const schema = {
    fields: [
      field('a', 'TEXT', { required: true }),
      field('b', 'TEXT', { required: true, visibleWhen: { field: 'a', anyOf: ['go'] } }),
      field('c', 'TEXT', { group: 'g1' }),
      field('d', 'TEXT', { group: 'g2', visibleWhen: { field: 'a', anyOf: ['never'] } }),
      field('e', 'TEXT', { group: 'unknown' }),
    ],
    groups: [
      { key: 'g1', label: { default: 'One' } },
      { key: 'g2', label: { default: 'Two' } },
    ],
  };
  test('isConfigured looks at visible required fields only', () => {
    expect(isConfigured(schema, { a: '' })).toBe(false);
    expect(isConfigured(schema, { a: 'x' })).toBe(true);
    expect(isConfigured(schema, { a: 'go', b: '' })).toBe(false);
    expect(isConfigured(schema, { a: 'go', b: 'y' })).toBe(true);
  });
  test('isDirty compares storable values', () => {
    expect(isDirty(schema, { a: 'x' }, { a: 'x' })).toBe(false);
    expect(isDirty(schema, { a: 'y' }, { a: 'x' })).toBe(true);
    expect(isDirty(schema, { a: '' }, { a: undefined })).toBe(false);
    expect(isDirty(schema, { a: null }, { a: SECRET_MASK })).toBe(true);
  });
  test('groupFields: loose first, empty group dropped, unknown group is loose', () => {
    const blocks = groupFields(schema, { a: 'x' });
    expect(blocks.map((b) => b.key)).toEqual([null, 'g1']);
    expect(blocks[0].fields.map((f) => f.key)).toEqual(['a', 'e']);
    expect(blocks[1].fields.map((f) => f.key)).toEqual(['c']);
  });
  test('readonlyValue prefers the live webhook url of the channel', () => {
    const f = field('cb', 'READONLY', { readonly: { kind: 'WEBHOOK_URL', channel: 'default', value: 'https://a/b' } });
    expect(readonlyValue(f, {})).toBe('https://a/b');
    expect(readonlyValue(f, { default: 'https://live/x' })).toBe('https://live/x');
    const s = field('s', 'READONLY', { readonly: { kind: 'STATIC', value: 'text' } });
    expect(readonlyValue(s, { default: 'https://live/x' })).toBe('text');
  });
});

describe('secret protocol', () => {
  test('focus clears the mask only', () => {
    expect(secretOnFocus(SECRET_MASK)).toBe('');
    expect(secretOnFocus('typed')).toBe('typed');
  });
  test('blur restores the mask, keeps removal and typed values', () => {
    expect(secretOnBlur('', { hadMask: true })).toBe(SECRET_MASK);
    expect(secretOnBlur('', { hadMask: false })).toBe('');
    expect(secretOnBlur('', { hadMask: true, removed: true })).toBeNull();
    expect(secretOnBlur('abc', { hadMask: true })).toBe('abc');
  });
  test('typing over the mask drops the mask prefix', () => {
    expect(secretOnInput('********abc')).toBe('abc');
    expect(secretOnInput(SECRET_MASK)).toBe(SECRET_MASK);
    expect(secretOnInput('plain')).toBe('plain');
  });
  test('reveal fills only fields still showing the mask', () => {
    const schema = {
      fields: [
        field('a', 'PASSWORD', { secret: true }),
        field('b', 'PASSWORD', { secret: true }),
        field('c', 'PASSWORD', { secret: true }),
        field('t', 'TEXT'),
      ],
    };
    const out = applyReveal(
      schema,
      { a: SECRET_MASK, b: 'freshly typed', c: null, t: 'x' },
      { a: 'real-a', b: 'real-b', c: 'real-c', t: 'real-t' },
    );
    expect(out).toEqual({ a: 'real-a', b: 'freshly typed', c: null, t: 'x' });
  });
});

describe('errorText', () => {
  const translate = (key) => `T:${key}`;
  const rawTranslate = (key) => key;
  test('client codes use schema.errors', () => {
    expect(errorText('REQUIRED', { translate, rawTranslate })).toBe('T:schema.errors.REQUIRED');
  });
  test('server LocalizedText is resolved, plain strings are shown as is', () => {
    expect(
      errorText({ default: 'Bad', translations: { tr: 'Kotu' } }, { locale: 'tr-TR', translate, rawTranslate }),
    ).toBe('Kotu');
    expect(errorText('Merchant id is wrong', { translate, rawTranslate })).toBe('Merchant id is wrong');
    expect(errorText(null, { translate, rawTranslate })).toBe('');
  });
});
