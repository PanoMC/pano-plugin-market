// Session binding (14 §4.3). Pages and slot components call bindSession(hostSession(getContext)) first.
import { derived, writable } from 'svelte/store';

const bound = writable(null);
const initializers = new Set();
let initialized = false;
let lastUserKey;

// The bound value is a store (getContext('session')) or, defensively, a plain object.
const sessionValue = derived(
  bound,
  ($bound, set) => {
    if (!$bound) {
      set(null);
      return undefined;
    }

    if (typeof $bound.subscribe === 'function')
      return $bound.subscribe((value) => set(value || null));

    set($bound);
    return undefined;
  },
  null,
);

export const user = derived(sessionValue, ($s) => $s?.user || null);
export const isLoggedIn = derived(user, ($u) => $u !== null);
export const csrfToken = derived(sessionValue, ($s) => $s?.csrfToken || null);

const userKey = (u) => (u ? `${u.id ?? ''}:${u.username ?? ''}` : '');

/**
 * Registers a function run on the first bind in the browser and again whenever the user
 * logs in or out without a full reload (the cart store registers its init here).
 */
export function onSessionInit(fn) {
  initializers.add(fn);
  return () => initializers.delete(fn);
}

function runInitializers() {
  for (const fn of [...initializers]) {
    try {
      fn();
    } catch (e) {
      console.warn('pano-plugin-market: session initializer failed', e);
    }
  }
}

export function bindSession(sessionStore) {
  if (sessionStore) bound.set(sessionStore);

  if (typeof window === 'undefined' || initialized) return;
  initialized = true;

  let first = true;
  user.subscribe(($u) => {
    const key = userKey($u);
    const changed = first || key !== lastUserKey;
    first = false;
    lastUserKey = key;
    if (changed) runInitializers();
  });
}

/**
 * The host's `session` context, or null where it cannot be read. The server bundle of this plugin carries its own copy of
 * the Svelte server runtime (the host's is only shared in the browser), so `getContext` throws `lifecycle_outside_component`
 * during SSR; the page then renders as a guest and the client binds the real store on hydration (E2E-13).
 */
export function hostSession(getContext) {
  if (typeof window === 'undefined') return null;

  try {
    return getContext('session') || null;
  } catch {
    return null;
  }
}

/** Test helper: forget all module state. */
export function resetSession() {
  bound.set(null);
  initializers.clear();
  initialized = false;
  lastUserKey = undefined;
}
