import { describe, expect, test } from 'bun:test';
import { stockFieldErrorKey } from './stock.js';

const failure = (error, body = {}) => ({ ok: false, error, body });

describe('stockFieldErrorKey', () => {
  test('the answer of a refused ADJUST on a stock of 0 marks the value (INVALID_PRODUCT + fieldErrors.value)', () => {
    // POST /products/:id/stock {mode: 'ADJUST', value: -1} on stock 0, as the E2E-14 browser run saw it
    const refused = failure('INVALID_PRODUCT', { fieldErrors: { value: 'STOCK_OUT_OF_RANGE' } });

    expect(stockFieldErrorKey(refused)).toBe('pages.create-product.field-errors.OUT_OF_RANGE');
  });

  test('an adjustment of an unlimited stock reads as invalid', () => {
    const refused = failure('INVALID_PRODUCT', { fieldErrors: { value: 'STOCK_UNLIMITED' } });

    expect(stockFieldErrorKey(refused)).toBe('pages.create-product.field-errors.INVALID');
  });

  test('the contract code BAD_REQUEST still marks the value', () => {
    expect(stockFieldErrorKey(failure('BAD_REQUEST'))).toBe(
      'pages.create-product.field-errors.OUT_OF_RANGE',
    );
  });

  test('range and required codes of the request validation', () => {
    expect(
      stockFieldErrorKey(failure('INVALID_PRODUCT', { fieldErrors: { value: 'OUT_OF_RANGE' } })),
    ).toBe('pages.create-product.field-errors.OUT_OF_RANGE');
    expect(
      stockFieldErrorKey(failure('INVALID_PRODUCT', { fieldErrors: { value: 'REQUIRED' } })),
    ).toBe('pages.create-product.field-errors.REQUIRED');
  });

  test('an unknown field code still marks the value, other errors are left to the toast', () => {
    expect(
      stockFieldErrorKey(failure('INVALID_PRODUCT', { fieldErrors: { value: 'SOMETHING_NEW' } })),
    ).toBe('pages.create-product.field-errors.INVALID');
    expect(
      stockFieldErrorKey(failure('INVALID_PRODUCT', { fieldErrors: { name: 'REQUIRED' } })),
    ).toBe(null);
    expect(stockFieldErrorKey(failure('NETWORK_ERROR'))).toBe(null);
    expect(stockFieldErrorKey(failure('PRODUCT_NOT_FOUND'))).toBe(null);
    expect(stockFieldErrorKey({ ok: true, body: {} })).toBe(null);
    expect(stockFieldErrorKey(null)).toBe(null);
  });
});
