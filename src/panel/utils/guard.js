// The permission guard every page load starts with (13 §2.1). Pure apart from calling `event.parent()`.
import { can } from './permissions.js';

/** Data a page gets instead of its payload when the user lacks every node. */
export const NO_PERMISSION_DATA = Object.freeze({ error: 'NO_PERMISSION' });

/**
 * Checks `can()` against the layout user. Returns `{ user, pageTitle }` when allowed, else
 * `{ denied: { data: { error: 'NO_PERMISSION' } } }`. `nodes` are keys of NODE ('OV', 'PAY', ...).
 */
export async function guard(event, nodes) {
  const parent = await event.parent();
  if (!can(parent?.user, ...nodes)) return { denied: { data: { ...NO_PERMISSION_DATA } } };
  return { user: parent.user, pageTitle: parent.pageTitle };
}

/**
 * Wraps a page `load` so it starts with the guard. register.js applies it to every page, so a page
 * never renders data-less chrome even if its own load forgets the check.
 */
export function guardedLoad(load, nodes) {
  return async (event) => {
    const result = await guard(event, nodes);
    if (result.denied) return result.denied;
    return typeof load === 'function' ? load(event) : { data: {} };
  };
}
