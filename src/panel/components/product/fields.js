// Pure model of the custom product fields (13 §8.5, 01 §2.5): blank rows, key suggestion, per-row
// validation, the wire form. No Svelte, no SDK import. State rows carry one local flag, `isNew`
// (a row that is not saved yet may still change its key); `serializeFields` strips it.
import { FIELD_KEY_PATTERN, isEmailLike, patternError } from '../../utils/validate.js';

export const FIELD_TYPES = [
  'TEXT',
  'NUMBER',
  'SELECT',
  'CHECKBOX',
  'USERNAME',
  'EMAIL',
  'DISCORD_ID',
];
export const MAX_FIELDS = 20;
export const MAX_OPTIONS = 50;
export const OPTION_VALUE_PATTERN = /^[A-Za-z0-9_-]{1,64}$/;
export const USERNAME_PATTERN = /^[A-Za-z0-9_.*]{1,32}$/;
export const DISCORD_ID_PATTERN = /^\d{17,20}$/;

/** A new empty field row. */
export function blankField(overrides = {}) {
  return {
    isNew: true,
    fieldKey: '',
    label: '',
    helpText: '',
    type: 'TEXT',
    required: false,
    options: [],
    pattern: '',
    minLength: null,
    maxLength: null,
    minValue: null,
    maxValue: null,
    placeholder: '',
    defaultValue: '',
    usableInCommands: true,
    ...overrides,
  };
}

/** Fills the optional parts of a loaded row so the modal can bind to it. */
export function fieldFromApi(row) {
  return blankField({
    isNew: false,
    fieldKey: row.fieldKey ?? '',
    label: row.label ?? '',
    helpText: row.helpText ?? '',
    type: FIELD_TYPES.includes(row.type) ? row.type : 'TEXT',
    required: !!row.required,
    options: (row.options ?? []).map((o) => ({ value: o.value ?? '', label: o.label ?? '' })),
    pattern: row.pattern ?? '',
    minLength: row.minLength ?? null,
    maxLength: row.maxLength ?? null,
    minValue: row.minValue ?? null,
    maxValue: row.maxValue ?? null,
    placeholder: row.placeholder ?? '',
    defaultValue: row.defaultValue ?? '',
    usableInCommands: row.usableInCommands !== false,
  });
}

/** Suggested key from a label: lower-case ASCII, `_` between words, starts with a letter, <= 32. */
export function suggestFieldKey(label) {
  let key = String(label ?? '')
    .toLocaleLowerCase('en-US')
    .replace(/ı/g, 'i')
    .normalize('NFD')
    .replace(/[̀-ͯ]/g, '')
    .replace(/[^a-z0-9]+/g, '_')
    .replace(/^_+|_+$/g, '');
  key = key.replace(/^[0-9_]+/, '');
  return key.slice(0, 32).replace(/_+$/, '');
}

const isSet = (value) => value !== null && value !== undefined && value !== '';
const intIssue = (value) => (Number.isInteger(Number(value)) ? null : 'NOT_INTEGER');

/** The rules every type shares or that depend on the type; returns `{ <key>: CODE }`. */
export function validateField(field, others = []) {
  const errors = {};
  const label = String(field.label ?? '').trim();
  if (label === '') errors.label = 'REQUIRED';
  else if (label.length > 255) errors.label = 'TOO_LONG';

  const key = String(field.fieldKey ?? '');
  if (key === '') errors.fieldKey = 'REQUIRED';
  else if (!FIELD_KEY_PATTERN.test(key)) errors.fieldKey = 'INVALID_FORMAT';
  else if (others.some((other) => other.fieldKey === key)) errors.fieldKey = 'DUPLICATE';

  if (!FIELD_TYPES.includes(field.type)) errors.type = 'INVALID';
  if (String(field.helpText ?? '').length > 512) errors.helpText = 'TOO_LONG';
  if (String(field.placeholder ?? '').length > 255) errors.placeholder = 'TOO_LONG';
  if (String(field.defaultValue ?? '').length > 255) errors.defaultValue = 'TOO_LONG';

  if (field.type === 'SELECT') {
    const options = field.options ?? [];
    if (options.length < 1) errors.options = 'REQUIRED';
    else if (options.length > MAX_OPTIONS) errors.options = 'TOO_MANY';
    const seen = new Set();
    options.forEach((option, i) => {
      if (!OPTION_VALUE_PATTERN.test(option.value ?? ''))
        errors[`options.${i}.value`] = 'INVALID_FORMAT';
      else if (seen.has(option.value)) errors[`options.${i}.value`] = 'DUPLICATE';
      seen.add(option.value);
      const text = String(option.label ?? '');
      if (text.trim() === '') errors[`options.${i}.label`] = 'REQUIRED';
      else if (text.length > 255) errors[`options.${i}.label`] = 'TOO_LONG';
    });
  }

  if (field.type === 'TEXT') {
    if (isSet(field.pattern)) {
      const issue = patternError(field.pattern);
      if (issue) errors.pattern = issue;
    }
    for (const name of ['minLength', 'maxLength']) {
      if (!isSet(field[name])) continue;
      const issue = intIssue(field[name]);
      if (issue) errors[name] = issue;
      else if (Number(field[name]) < 0 || Number(field[name]) > 128) errors[name] = 'OUT_OF_RANGE';
    }
    if (
      !errors.minLength &&
      !errors.maxLength &&
      isSet(field.minLength) &&
      isSet(field.maxLength) &&
      Number(field.minLength) > Number(field.maxLength)
    )
      errors.minLength = 'MIN_ABOVE_MAX';
  }

  if (field.type === 'NUMBER') {
    for (const name of ['minValue', 'maxValue']) {
      if (!isSet(field[name])) continue;
      const issue = intIssue(field[name]);
      if (issue) errors[name] = issue;
    }
    if (
      !errors.minValue &&
      !errors.maxValue &&
      isSet(field.minValue) &&
      isSet(field.maxValue) &&
      Number(field.minValue) > Number(field.maxValue)
    )
      errors.minValue = 'MIN_ABOVE_MAX';
  }

  // the default must satisfy the field's own rules
  if (!errors.defaultValue && isSet(field.defaultValue)) {
    const issue = defaultValueIssue(field);
    if (issue) errors.defaultValue = issue;
  }
  return errors;
}

function defaultValueIssue(field) {
  const value = String(field.defaultValue);
  switch (field.type) {
    case 'NUMBER': {
      if (!/^-?\d+$/.test(value)) return 'INVALID_NUMBER';
      const n = Number(value);
      if (isSet(field.minValue) && n < Number(field.minValue)) return 'OUT_OF_RANGE';
      if (isSet(field.maxValue) && n > Number(field.maxValue)) return 'OUT_OF_RANGE';
      return null;
    }
    case 'SELECT':
      return (field.options ?? []).some((o) => o.value === value) ? null : 'INVALID_OPTION';
    case 'CHECKBOX':
      return value === 'true' || value === 'false' ? null : 'INVALID';
    case 'USERNAME':
      return USERNAME_PATTERN.test(value) && /[A-Za-z0-9]/.test(value) ? null : 'INVALID_FORMAT';
    case 'EMAIL':
      return isEmailLike(value) ? null : 'INVALID_FORMAT';
    case 'DISCORD_ID':
      return DISCORD_ID_PATTERN.test(value) ? null : 'INVALID_FORMAT';
    default: {
      if (isSet(field.minLength) && value.length < Number(field.minLength)) return 'OUT_OF_RANGE';
      if (isSet(field.maxLength) && value.length > Number(field.maxLength)) return 'OUT_OF_RANGE';
      if (isSet(field.pattern) && !patternError(field.pattern)) {
        try {
          if (!new RegExp('^(?:' + field.pattern + ')$').test(value)) return 'INVALID_FORMAT';
        } catch {
          return null;
        }
      }
      return null;
    }
  }
}

/** Product-level validation: `{ 'fields.<i>.<key>': CODE }` plus `fields` = TOO_MANY. */
export function validateFields(fields) {
  const errors = {};
  const list = fields ?? [];
  if (list.length > MAX_FIELDS) errors.fields = 'TOO_MANY';
  list.forEach((field, i) => {
    const others = list.filter((_row, j) => j < i);
    for (const [key, code] of Object.entries(validateField(field, others)))
      errors[`fields.${i}.${key}`] = code;
  });
  return errors;
}

/** Wire form of the rows (04 §5 `fields` part): local flags removed, type-bound parts only. */
export function serializeFields(fields) {
  return (fields ?? []).map((field) => {
    const text = (value) => (isSet(value) && String(value) !== '' ? String(value) : null);
    const num = (value) => (isSet(value) ? Number(value) : null);
    const out = {
      fieldKey: field.fieldKey,
      label: String(field.label ?? '').trim(),
      helpText: text(field.helpText),
      type: field.type,
      required: !!field.required,
      placeholder: text(field.placeholder),
      defaultValue: text(field.defaultValue),
      usableInCommands: field.type === 'CHECKBOX' ? false : field.usableInCommands !== false,
      options:
        field.type === 'SELECT'
          ? (field.options ?? []).map((o) => ({ value: o.value, label: o.label }))
          : null,
      pattern: field.type === 'TEXT' ? text(field.pattern) : null,
      minLength: field.type === 'TEXT' ? num(field.minLength) : null,
      maxLength: field.type === 'TEXT' ? num(field.maxLength) : null,
      minValue: field.type === 'NUMBER' ? num(field.minValue) : null,
      maxValue: field.type === 'NUMBER' ? num(field.maxValue) : null,
    };
    return out;
  });
}

/** Moves row `index` by `delta` (-1 / +1); returns a new array (same array when out of range). */
export function moveRow(rows, index, delta) {
  const target = index + delta;
  if (target < 0 || target >= rows.length || index < 0 || index >= rows.length) return rows;
  const next = [...rows];
  [next[index], next[target]] = [next[target], next[index]];
  return next;
}

export const FIELD_ERROR_CODES = [
  'REQUIRED',
  'TOO_LONG',
  'INVALID',
  'INVALID_FORMAT',
  'INVALID_NUMBER',
  'INVALID_OPTION',
  'OUT_OF_RANGE',
  'NOT_INTEGER',
  'DUPLICATE',
  'TOO_MANY',
  'BACKREFERENCE',
  'LOOKAROUND',
  'MIN_ABOVE_MAX',
];

/** Locale key (below the plugin root) of a field error code; unknown codes read as invalid. */
export function fieldRowErrorKey(code) {
  return `pages.create-product.field-errors.${FIELD_ERROR_CODES.includes(code) ? code : 'INVALID'}`;
}

export const FIELD_PROPS = [
  'label',
  'fieldKey',
  'type',
  'helpText',
  'placeholder',
  'defaultValue',
  'options',
  'pattern',
  'minLength',
  'maxLength',
  'minValue',
  'maxValue',
];

/** Locale key of a field property name in an error line; an unknown name reads as "Field". */
export function fieldPropKey(name) {
  return `pages.create-product.fields.prop.${FIELD_PROPS.includes(name) ? name : 'other'}`;
}
