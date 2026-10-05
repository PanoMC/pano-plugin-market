import { describe, expect, test } from 'bun:test';
import {
  compilePattern,
  initialFieldValues,
  normalizeValue,
  requiredAddressFields,
  toFieldValues,
  validateAddress,
  validateEmail,
  validateField,
  validateFields,
  validateGiftRecipient,
  validateIdentityNumber,
  validatePhone,
  validateUsername,
} from '../validation.js';

const F = 'FIELD_REQUIRED';
const I = 'FIELD_INVALID';

describe('TEXT', () => {
  test('required needs a non-empty value after trim', () => {
    const field = { type: 'TEXT', required: true };
    expect(validateField(field, '')).toBe(F);
    expect(validateField(field, '   ')).toBe(F);
    expect(validateField(field, undefined)).toBe(F);
    expect(validateField(field, ' a ')).toBe(null);
  });

  test('optional empty is valid', () => {
    expect(validateField({ type: 'TEXT' }, '')).toBe(null);
    expect(validateField({ type: 'TEXT', minLength: 3 }, '  ')).toBe(null);
  });

  test('min and max length, default max 128', () => {
    expect(validateField({ type: 'TEXT', minLength: 3 }, 'ab')).toBe(I);
    expect(validateField({ type: 'TEXT', minLength: 3 }, 'abc')).toBe(null);
    expect(validateField({ type: 'TEXT', maxLength: 4 }, 'abcde')).toBe(I);
    expect(validateField({ type: 'TEXT' }, 'a'.repeat(128))).toBe(null);
    expect(validateField({ type: 'TEXT' }, 'a'.repeat(129))).toBe(I);
  });

  test('pattern is anchored; an uncompilable pattern is skipped', () => {
    expect(validateField({ type: 'TEXT', pattern: '[a-z]+' }, 'abc')).toBe(null);
    expect(validateField({ type: 'TEXT', pattern: '[a-z]+' }, 'abc1')).toBe(I);
    expect(validateField({ type: 'TEXT', pattern: 'a|b' }, 'ab')).toBe(I);
    expect(validateField({ type: 'TEXT', pattern: '([unclosed' }, 'anything')).toBe(null);
    expect(compilePattern('([unclosed')).toBe(null);
    expect(compilePattern('')).toBe(null);
  });
});

describe('NUMBER', () => {
  const field = { type: 'NUMBER', required: true, minValue: 1, maxValue: 10 };

  test('integer within bounds', () => {
    expect(validateField(field, '5')).toBe(null);
    expect(validateField(field, 5)).toBe(null);
    expect(validateField(field, '1')).toBe(null);
    expect(validateField(field, '10')).toBe(null);
  });

  test('rejects decimals, text and out of range', () => {
    expect(validateField(field, '1.5')).toBe(I);
    expect(validateField(field, 'x')).toBe(I);
    expect(validateField(field, '0')).toBe(I);
    expect(validateField(field, '11')).toBe(I);
  });

  test('required empty', () => {
    expect(validateField(field, '')).toBe(F);
    expect(validateField({ type: 'NUMBER' }, '')).toBe(null);
  });

  test('a zero bound is a bound', () => {
    expect(validateField({ type: 'NUMBER', minValue: 0 }, '-1')).toBe(I);
  });
});

describe('SELECT / CHECKBOX', () => {
  const select = {
    type: 'SELECT',
    required: true,
    options: [{ value: 'a', label: 'A' }, { value: 'b' }],
  };

  test('SELECT value must be an option', () => {
    expect(validateField(select, 'a')).toBe(null);
    expect(validateField(select, 'z')).toBe(I);
    expect(validateField(select, '')).toBe(F);
    expect(validateField({ ...select, required: false }, '')).toBe(null);
  });

  test('CHECKBOX required must be checked', () => {
    expect(validateField({ type: 'CHECKBOX', required: true }, false)).toBe(F);
    expect(validateField({ type: 'CHECKBOX', required: true }, true)).toBe(null);
    expect(validateField({ type: 'CHECKBOX' }, false)).toBe(null);
  });
});

describe('USERNAME / EMAIL / DISCORD_ID', () => {
  test('username accepts .Steve and *Bedrock_1, rejects spaces and 33 chars', () => {
    const field = { type: 'USERNAME' };
    expect(validateField(field, '.Steve')).toBe(null);
    expect(validateField(field, '*Bedrock_1')).toBe(null);
    expect(validateField(field, 'a'.repeat(32))).toBe(null);
    expect(validateField(field, 'a'.repeat(33))).toBe(I);
    expect(validateField(field, 'Steve Jobs')).toBe(I);
    expect(validateField(field, 'a-b')).toBe(I);
    expect(validateUsername('.Steve')).toBe(null);
    expect(validateUsername('')).toBe(F);
    expect(validateUsername('', { required: false })).toBe(null);
  });

  test('email', () => {
    const field = { type: 'EMAIL' };
    expect(validateField(field, 'a@b.co')).toBe(null);
    expect(validateField(field, 'a@b')).toBe(I);
    expect(validateField(field, 'a b@c.de')).toBe(I);
    expect(validateField(field, `${'a'.repeat(251)}@b.co`)).toBe(I);
    expect(validateEmail('x@y.zz')).toBe(null);
    expect(validateEmail('')).toBe(F);
  });

  test('discord id is 17 to 20 digits', () => {
    const field = { type: 'DISCORD_ID' };
    expect(validateField(field, '1'.repeat(17))).toBe(null);
    expect(validateField(field, '1'.repeat(20))).toBe(null);
    expect(validateField(field, '1'.repeat(16))).toBe(I);
    expect(validateField(field, '1'.repeat(21))).toBe(I);
    expect(validateField(field, '12345678901234567a')).toBe(I);
  });
});

describe('field collections', () => {
  const fields = [
    { fieldKey: 'name', type: 'TEXT', required: true },
    { fieldKey: 'amount', type: 'NUMBER' },
    { fieldKey: 'agree', type: 'CHECKBOX' },
    { fieldKey: 'note', type: 'TEXT' },
  ];

  test('validateFields collects codes by field key', () => {
    expect(validateFields(fields, { name: '', amount: 'x' })).toEqual({ name: F, amount: I });
    expect(validateFields(fields, { name: 'a' })).toEqual({});
    expect(validateFields(undefined, {})).toEqual({});
  });

  test('values are trimmed and optional empty values omitted', () => {
    expect(
      toFieldValues(fields, { name: '  Steve ', amount: '5', agree: true, note: '  ' }),
    ).toEqual({
      name: 'Steve',
      amount: 5,
      agree: true,
    });
    expect(toFieldValues(fields, { name: 'a', agree: false })).toEqual({ name: 'a' });
    expect(normalizeValue({ type: 'NUMBER' }, 'x')).toBe(null);
  });

  test('initial values come from defaultValue', () => {
    expect(
      initialFieldValues([
        { fieldKey: 'a', type: 'TEXT', defaultValue: 'x' },
        { fieldKey: 'b', type: 'CHECKBOX', defaultValue: true },
        { fieldKey: 'c', type: 'NUMBER', defaultValue: 3 },
        { fieldKey: 'd', type: 'TEXT' },
      ]),
    ).toEqual({ a: 'x', b: true, c: '3', d: '' });
  });
});

describe('buyer, gift and address rules', () => {
  test('E.164 phone', () => {
    expect(validatePhone('+905551234567')).toBe(null);
    expect(validatePhone('+1234567')).toBe(null);
    expect(validatePhone('05551234567')).toBe(I);
    expect(validatePhone('+0123456789')).toBe(I);
    expect(validatePhone('+123456')).toBe(I);
    expect(validatePhone('+1234567890123456')).toBe(I);
    expect(validatePhone('')).toBe(null);
    expect(validatePhone('', { required: true })).toBe(F);
  });

  test('identity number: TR is 11 digits, others up to 32 characters', () => {
    expect(validateIdentityNumber('12345678901', 'TR')).toBe(null);
    expect(validateIdentityNumber('1234567890', 'TR')).toBe(I);
    expect(validateIdentityNumber('1234567890a', 'TR')).toBe(I);
    expect(validateIdentityNumber('AB-123', 'DE')).toBe(null);
    expect(validateIdentityNumber('x'.repeat(33), 'DE')).toBe(I);
    expect(validateIdentityNumber('', 'TR', { required: true })).toBe(F);
  });

  test('gift recipient equal to self is rejected case-insensitively', () => {
    expect(validateGiftRecipient('steve', 'Steve')).toBe('GIFT_SELF');
    expect(validateGiftRecipient('Alex', 'Steve')).toBe(null);
    expect(validateGiftRecipient('', 'Steve')).toBe(F);
    expect(validateGiftRecipient('bad name', 'Steve')).toBe(I);
    expect(validateGiftRecipient('Alex', '')).toBe(null);
  });

  test('address required sets: country entry wins over the * entry', () => {
    const map = { '*': ['firstName', 'country'], TR: ['firstName', 'neighborhood', 'country'] };
    expect([...requiredAddressFields(map, 'TR')].sort()).toEqual([
      'country',
      'firstName',
      'neighborhood',
    ]);
    expect([...requiredAddressFields(map, 'DE')].sort()).toEqual(['country', 'firstName']);
    expect([...requiredAddressFields(map, 'DE', ['phone'])].sort()).toEqual([
      'country',
      'firstName',
      'phone',
    ]);
    expect(requiredAddressFields(undefined, 'TR').size).toBe(0);
  });

  test('validateAddress: required, lengths, phone, country list', () => {
    const required = new Set(['firstName', 'city']);
    expect(validateAddress({ firstName: '', city: ' ' }, required)).toEqual({
      firstName: F,
      city: F,
    });
    expect(validateAddress({ firstName: 'A', city: 'B' }, required)).toEqual({});
    expect(validateAddress({ firstName: 'A', city: 'x'.repeat(256) }, required)).toEqual({
      city: I,
    });
    expect(validateAddress({ postalCode: 'x'.repeat(17) })).toEqual({ postalCode: I });
    expect(validateAddress({ phone: '555' })).toEqual({ phone: I });
    expect(validateAddress({ country: 'XX' }, new Set(), ['TR', 'DE'])).toEqual({ country: I });
    expect(validateAddress({ country: 'TR' }, new Set(), ['TR', 'DE'])).toEqual({});
  });
});
