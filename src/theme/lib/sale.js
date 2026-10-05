// Sale helpers for product cards (14 §8.3). Pure: no SDK, no clock access (callers pass `now`).
import { withinWindow } from './countdown.js';

const num = (value) => {
  const n = Number(value);
  return Number.isFinite(n) ? n : null;
};

/**
 * True while the sale of `product` still counts at `now`. `now` of 0 is the server render (no clock):
 * the sale is shown as delivered. A product without `sale` has no sale.
 */
export function saleActive(product, now = 0) {
  const sale = product?.sale;
  if (!sale) return false;

  const endsAt = num(sale.endsAt);
  if (endsAt !== null && endsAt > 0 && now > 0 && endsAt <= now) return false;

  return true;
}

/**
 * Percent off: `sale.percent`, else the difference between compareAtPrice and price, else null.
 * Values below 1 are not shown (null). Whole numbers.
 */
export function salePercent(product) {
  const given = num(product?.sale?.percent);
  let percent = null;

  if (given !== null && given > 0) {
    percent = Math.round(given);
  } else {
    const compare = num(product?.compareAtPrice);
    const price = num(product?.price);
    if (compare !== null && price !== null && compare > price && compare > 0)
      percent = Math.round((1 - price / compare) * 100);
  }

  return percent !== null && percent >= 1 ? percent : null;
}

/**
 * What the sale badge shows: { kind: 'percent', percent } or { kind: 'amount', amount }
 * (only `sale.amountOff` exists) or null. Nothing when the module is off or the sale is over locally.
 */
export function saleBadge(product, settings, now = 0) {
  if (!settings?.modules?.saleBadges) return null;
  if (product?.sale && !saleActive(product, now)) return null;

  const percent = salePercent(product);
  if (percent !== null) return { kind: 'percent', percent };

  const amountOff = num(product?.sale?.amountOff);
  if (amountOff !== null && amountOff > 0) return { kind: 'amount', amount: amountOff };

  return null;
}

/** The strikethrough list price: only with the badge module, a lower price and a sale that still counts. */
export function strikePrice(product, settings, now = 0) {
  if (!settings?.modules?.saleBadges) return null;
  if (product?.sale && !saleActive(product, now)) return null;

  const compare = num(product?.compareAtPrice);
  const price = num(product?.price);

  return compare !== null && price !== null && compare > price ? compare : null;
}

/** The countdown renders only with the module, a running clock and 0 < end - now <= 72 h. */
export function countdownVisible(product, settings, now) {
  if (!settings?.modules?.saleCountdown || !(now > 0)) return false;

  return withinWindow(product?.sale?.endsAt, now);
}

/** Sorted end times (epoch ms) of the sales that have run out locally; each needs one data refetch. */
export function expiredSaleEnds(products, now) {
  if (!(now > 0)) return [];

  const ends = new Set();
  for (const product of products || []) {
    const endsAt = num(product?.sale?.endsAt);
    if (endsAt !== null && endsAt > 0 && endsAt <= now) ends.add(endsAt);
  }

  return [...ends].sort((a, b) => a - b);
}
