// The product model with the MPU-08 tabs: loading keeps action ids and locks saved field keys,
// validation covers every tab in one pass, the payload carries the wire forms.
import { describe, expect, test } from 'bun:test';
import { actionFromApi, newAction } from '../../utils/actions.js';
import { blankField } from './fields.js';
import { buildPayload, defaultProduct, fromApi, tabOfPath, validateProduct } from './model.js';

const ctx = {
  currencyMode: 'SINGLE',
  currencies: [{ code: 'USD', symbol: '$', exponent: 2 }],
  productMetaSchemas: [
    {
      providerId: 'prov',
      name: 'Prov',
      schema: {
        fields: [
          { key: 'mode', type: 'SELECT', options: [{ value: 'a' }, { value: 'b' }], default: 'a' },
          { key: 'code', type: 'TEXT', required: true },
        ],
      },
    },
  ],
};

const apiProduct = () => ({
  id: 7,
  name: 'Rank',
  slug: 'rank',
  kind: 'STANDARD',
  billingMode: 'ONE_TIME',
  price: 5,
  fields: [
    {
      fieldKey: 'rank',
      label: 'Rank',
      type: 'SELECT',
      required: true,
      options: [{ value: 'a', label: 'A' }],
    },
  ],
  actions: [
    {
      id: 'a1',
      type: 'COMMAND',
      phase: 'GRANT',
      value: ['give {username} diamond'],
      serverMode: 'FIXED',
      targetServers: [1],
    },
    { id: 'a4', type: 'CREDIT', phase: 'GRANT', value: 12.5 },
  ],
  serverChoices: [],
  providerMeta: { prov: { code: 'X' } },
});

describe('loading', () => {
  test('action ids are kept verbatim on reload and on the wire', () => {
    const product = fromApi(apiProduct(), ctx);
    expect(product.actions.map((a) => a.id)).toEqual(['a1', 'a4']);
    const { json } = buildPayload(product, ctx, { isEdit: true });
    expect(json.actions.map((a) => a.id)).toEqual(['a1', 'a4']);
  });
  test('a loaded product with untouched actions sends them as they came', () => {
    const source = apiProduct();
    const product = fromApi(source, ctx);
    const { json } = buildPayload(product, ctx, { isEdit: true });
    expect(json.actions[1]).toEqual({
      id: 'a4',
      type: 'CREDIT',
      phase: 'GRANT',
      value: 12.5,
    });
    expect(json.actions[0].value).toEqual(['give {username} diamond']);
    expect(json.actions[0].targetServers).toEqual([1]);
  });
  test('saved fields come back as saved rows (key locked)', () => {
    const product = fromApi(apiProduct(), ctx);
    expect(product.fields[0].isNew).toBe(false);
    expect(product.fields[0].usableInCommands).toBe(true);
  });
  test('actionFromApi fills only what the server defaults', () => {
    const loaded = actionFromApi({ id: 'a2', type: 'COMMAND', value: ['say hi'] });
    expect(loaded).toMatchObject({
      id: 'a2',
      phase: 'GRANT',
      delay: 0,
      serverMode: 'FIXED',
      targetServers: [],
      requiresOnline: false,
      perUnit: false,
    });
    expect(actionFromApi({ id: 'a3', type: 'CREDIT', value: 3 }).serverMode).toBeUndefined();
    expect(actionFromApi({ type: 'PERMISSION', value: 'vip' }).value).toEqual(['vip']);
    expect(
      actionFromApi({ type: 'WEBHOOK', value: { url: 'https://a.test' } }).value,
    ).toMatchObject({
      format: 'JSON',
      signing: 'NONE',
    });
  });
});

describe('payload', () => {
  test('a new action has no id, a new field has no local flag', () => {
    const product = { ...defaultProduct(ctx), name: 'X', slug: 'x' };
    product.actions = [{ ...newAction('COMMAND'), value: ['/say hi'], targetServers: [1] }];
    product.fields = [blankField({ fieldKey: 'k', label: 'K' })];
    const { json } = buildPayload(product, ctx);
    expect('id' in json.actions[0]).toBe(false);
    expect(json.actions[0].value).toEqual(['say hi']);
    expect('isNew' in json.fields[0]).toBe(false);
    expect(json.fields[0].fieldKey).toBe('k');
  });
  test('provider meta gets the defaults of untouched fields and keeps unknown providers', () => {
    const product = {
      ...defaultProduct(ctx),
      providerMeta: { prov: { code: 'X' }, gone: { a: 1 } },
    };
    const { json } = buildPayload(product, ctx);
    expect(json.providerMeta.prov).toEqual({ mode: 'a', code: 'X' });
    expect(json.providerMeta.gone).toEqual({ a: 1 });
  });
  test('without schemas the stored meta passes through untouched', () => {
    const product = { ...defaultProduct(null), providerMeta: { prov: { code: 'X' } } };
    expect(buildPayload(product, null).json.providerMeta).toEqual({ prov: { code: 'X' } });
  });
  test('serverChoices and other set parts are still sent', () => {
    const product = { ...defaultProduct(ctx), serverChoices: [1, 2] };
    expect(buildPayload(product, ctx).json.serverChoices).toEqual([1, 2]);
  });
});

describe('validation covers the second half of the form', () => {
  const valid = () => ({
    ...defaultProduct(ctx),
    name: 'X',
    slug: 'x',
    providerMeta: { prov: { code: 'ok' } },
  });
  test('a valid product has no errors', () => {
    expect(validateProduct(valid(), ctx).errors).toEqual({});
  });
  test('an invalid action blocks saving and maps to the Actions tab', () => {
    const product = valid();
    product.actions = [{ ...newAction('COMMAND'), value: [], targetServers: [1] }];
    const { errors } = validateProduct(product, ctx);
    expect(errors['actions.0.value']).toBe('INVALID_VALUE');
    expect(tabOfPath('actions.0.value')).toBe('actions');
  });
  test('an action in a phase the billing mode lacks is invalid', () => {
    const product = valid();
    product.actions = [{ ...newAction('CREDIT'), value: 5, phase: 'EXPIRE' }];
    expect(validateProduct(product, ctx).errors['actions.0.phase']).toBe('INVALID_PHASE');
  });
  test('field, provider-meta, shipping and SEO errors reach the right tabs', () => {
    const product = valid();
    product.fields = [blankField({ fieldKey: 'Bad Key', label: 'L' })];
    product.providerMeta = {};
    product.physical = true;
    product.weightGrams = 100;
    product.hsCode = 'x';
    product.metaTitle = 'x'.repeat(300);
    const { errors } = validateProduct(product, ctx);
    expect(tabOfPath('fields.0.fieldKey')).toBe('fields');
    expect(errors['fields.0.fieldKey']).toBe('INVALID_FORMAT');
    expect(errors['providerMeta.prov.code']).toBe('REQUIRED');
    expect(tabOfPath('providerMeta.prov.code')).toBe('providers');
    expect(errors.hsCode).toBe('INVALID_FORMAT');
    expect(errors.metaTitle).toBe('TOO_LONG');
  });
  test('the server list, when given, is checked for action targets', () => {
    const product = valid();
    product.actions = [{ ...newAction('COMMAND'), value: ['say hi'], targetServers: [9] }];
    expect(
      validateProduct(product, ctx, { servers: [{ id: 1 }] }).errors['actions.0.targetServers'],
    ).toBe('UNKNOWN_SERVER');
    expect(validateProduct(product, ctx).errors['actions.0.targetServers']).toBeUndefined();
  });
});
