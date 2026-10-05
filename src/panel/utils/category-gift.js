// Pure helpers of CreateCategoryModal (tiered, partial PUT; 13 §10) and CreateGiftModal (limits; 13 §12.1).
import { parseInteger } from './format.js';

export const UPGRADE_MODES = ['DIFFERENCE', 'FULL'];
export const DEFAULT_UPGRADE_MODE = 'DIFFERENCE';

/**
 * PUT /categories/:id is a partial update: only the keys whose value changed are sent.
 * `original` and `current` use the same key names; `removeImage` and a new image file are
 * handled by the caller. `upgradeMode` follows `tiered` (never sent for an untiered category).
 */
export function changedCategoryFields(original, current) {
  const out = {};
  for (const key of ['name', 'description', 'icon', 'color', 'status', 'tiered']) {
    if (current[key] !== undefined && current[key] !== original?.[key]) out[key] = current[key];
  }
  if (current.tiered) {
    const before = original?.tiered ? original.upgradeMode : null;
    if (current.upgradeMode !== before) out.upgradeMode = current.upgradeMode;
  }
  return out;
}

/** FormData value of a field (booleans as 'true' / 'false'). */
export const formValue = (value) => (typeof value === 'boolean' ? String(value) : String(value ?? ''));

const NAME_MAX = 255;

/**
 * Gift modal fields: name (<= 255), redeemLimit (integer >= 1, empty = unlimited = null),
 * customerRedeemLimit (integer >= 1, empty = 1). Returns { errors, values }.
 */
export function validateGiftLimits({ name = '', redeemLimit = '', customerRedeemLimit = '' }) {
  const errors = {};
  const values = {};
  const trimmed = String(name ?? '').trim();
  if (trimmed.length > NAME_MAX) errors.name = 'TOO_LONG';
  values.name = trimmed;

  const limit = parseInteger(String(redeemLimit ?? ''), { min: 1 });
  if (Number.isNaN(limit)) errors.redeemLimit = 'INVALID';
  values.redeemLimit = limit;

  const raw = String(customerRedeemLimit ?? '');
  const perCustomer = parseInteger(raw, { min: 1 });
  if (Number.isNaN(perCustomer)) errors.customerRedeemLimit = 'INVALID';
  values.customerRedeemLimit = perCustomer ?? 1;
  return { errors, values };
}

/** Gift dates are epoch ms; both set and start after end is invalid. */
export const giftDatesReversed = (startEpoch, endEpoch) =>
  startEpoch !== null && endEpoch !== null && startEpoch > endEpoch;

/** `usedCount / redeemLimit ?? '∞'`. */
export const usedCell = (gift) => `${gift?.usedCount ?? 0} / ${gift?.redeemLimit ?? '∞'}`;

/**
 * Keyboard alternative to drag-and-drop (13 §10): the sort request that moves category `id` one
 * place up or down among its siblings (`dir` 'up' | 'down'), or null at the edge / unknown id.
 * `tree` = nested rows `{ id, children[] }`; the result is the body of POST /categories/sort.
 */
export function siblingMove(tree, id, dir) {
  const search = (items) => {
    const index = items.findIndex((item) => item.id === id);
    if (index >= 0) return { items, index };
    for (const item of items) {
      const found = search(item.children ?? []);
      if (found) return found;
    }
    return null;
  };
  const found = search(tree ?? []);
  if (!found) return null;
  const { items, index } = found;
  if (dir === 'up') {
    return index > 0 ? { id, position: 'BEFORE', targetId: items[index - 1].id } : null;
  }
  if (dir === 'down') {
    return index < items.length - 1 ? { id, position: 'AFTER', targetId: items[index + 1].id } : null;
  }
  return null;
}
