// Link target of a product name shown in a list (orders, deliveries, subscriptions). Pure.
import { can } from './permissions.js';

/** Edit page of the product (router base not included), or null without an id or the CAT node. */
export function productHref(productId, user) {
  const id = Number(productId);
  if (!Number.isInteger(id) || id <= 0 || !can(user, 'CAT')) return null;
  return `/market/products/create-product?id=${id}`;
}
