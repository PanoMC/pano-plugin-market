// Failure handling of the Adjust Stock dialog (13 section 8.2). Pure: no Svelte, no SDK import.
const PREFIX = 'pages.create-product.field-errors.';

/** Server codes of `fieldErrors.value` (INVALID_PRODUCT) and the field error text they read as. */
const BY_FIELD_CODE = {
  STOCK_OUT_OF_RANGE: 'OUT_OF_RANGE',
  STOCK_UNLIMITED: 'INVALID',
  OUT_OF_RANGE: 'OUT_OF_RANGE',
  REQUIRED: 'REQUIRED',
};

/**
 * Locale key of the message under the quantity input when the server refused the request because of the quantity, else null (the
 * caller toasts the error). The contract names `BAD_REQUEST`; the backend answers a refused adjustment (a negative result, a
 * limited-stock write on an unlimited stock) as `INVALID_PRODUCT` with `fieldErrors.value`, so both mark the value.
 */
export function stockFieldErrorKey(result) {
  if (!result || result.ok) return null;
  if (result.error === 'BAD_REQUEST') return PREFIX + 'OUT_OF_RANGE';
  if (result.error !== 'INVALID_PRODUCT') return null;
  const code = result.body?.fieldErrors?.value;
  if (typeof code !== 'string') return null;
  return PREFIX + (BY_FIELD_CODE[code] ?? 'INVALID');
}
