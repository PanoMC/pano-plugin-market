/**
 * Format a plain-decimal money amount with the store currency symbol.
 * Amounts arrive from the backend as plain decimals (MoneyUtil.toDecimal).
 */
export function formatPrice(amount, symbol) {
  const n = Number(amount);
  const value = Number.isFinite(n) ? n : 0;
  return `${symbol || ''}${value.toFixed(2)}`;
}
