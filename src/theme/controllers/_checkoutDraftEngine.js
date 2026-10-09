// The stateful part of the checkout draft (14 §10.2): sessionStorage['pano-plugin-market-checkout'], restored on mount and
// written on every change (debounced 200 ms). Legal acceptance is never stored. Cleared after a successful checkout and on
// logout. The logout clear is global: the `market/checkoutDraft` controller is eager and listens to the session, so it also
// fires when the buyer logs out on any other page; in addition the stored JSON carries the owner's user key (never part of
// the draft) and a draft of another user is not restored.
// `createCheckoutDraft(deps)` builds an instance with injectable storage and timers (tests, and the controller).
// Nothing here reads storage at module top level.
import { createState } from '@panomc/plugin-kit/controller';
import {
  DRAFT_DEBOUNCE,
  DRAFT_KEY,
  defaultDraft,
  parseDraft,
  storedOwner,
} from '../lib/checkoutDraftModel.js';

function defaultStorage() {
  try {
    return globalThis.sessionStorage ?? null;
  } catch (e) {
    return null;
  }
}

const defaultTiming = () => ({
  ms: DRAFT_DEBOUNCE,
  set: (fn, ms) => setTimeout(fn, ms),
  clear: (id) => clearTimeout(id),
});

export function createCheckoutDraft(overrides = {}) {
  const deps = { storage: defaultStorage, timing: defaultTiming(), ...overrides };
  const state = createState(defaultDraft());

  let restored = false;
  let timer = null;
  // user key the draft belongs to: null = not told yet, '' = guest
  let owner = null;

  const store = () => {
    try {
      return typeof deps.storage === 'function' ? deps.storage() : deps.storage;
    } catch (e) {
      return null;
    }
  };

  function write() {
    timer = null;

    try {
      store()?.setItem(DRAFT_KEY, JSON.stringify({ ...state.get(), owner: owner ?? '' }));
    } catch (e) {
      // storage unavailable (private mode / quota): the draft lives for this page only
    }
  }

  function schedule() {
    if (!restored) return;
    if (timer !== null) deps.timing.clear(timer);
    timer = deps.timing.set(write, deps.timing.ms);
  }

  /**
   * Reads the stored draft (once per mount; garbage => defaults) and starts persisting changes. A draft stored
   * for another (non-empty) user key is dropped: `ownerKey` = the current user key ('' guest), default = the one
   * the session told us last.
   */
  function restore(ownerKey) {
    let raw = null;

    if (typeof ownerKey === 'string') owner = ownerKey;

    try {
      raw = store()?.getItem(DRAFT_KEY) ?? null;
    } catch (e) {
      raw = null;
    }

    const foreign = raw !== null && storedOwner(raw) !== '' && storedOwner(raw) !== (owner ?? '');

    if (foreign) {
      try {
        store()?.removeItem(DRAFT_KEY);
      } catch (e) {
        // not critical
      }
    }

    state.set(raw === null || foreign ? defaultDraft() : parseDraft(raw));
    restored = true;

    return state.get();
  }

  function patch(partial) {
    // only draft members are kept: legal acceptance and other page state never reach the storage
    const known = {};
    for (const key of Object.keys(defaultDraft())) if (key in partial) known[key] = partial[key];

    state.update((draft) => ({ ...draft, ...known }));
    schedule();
  }

  function set(draft) {
    state.set(parseDraft(draft));
    schedule();
  }

  /** Writes at once (before navigating away). */
  function flush() {
    if (timer !== null) deps.timing.clear(timer);
    if (restored) write();
    timer = null;
  }

  /** After a successful checkout and on logout: storage emptied, state back to the defaults. */
  function clear() {
    if (timer !== null) deps.timing.clear(timer);
    timer = null;

    try {
      store()?.removeItem(DRAFT_KEY);
    } catch (e) {
      // not critical
    }

    state.set(defaultDraft());
  }

  /** Leaving the page: stop pending writes (the next mount restores). */
  function detach() {
    flush();
    restored = false;
  }

  /**
   * The session's user changed (`key`: '' = guest). Logging out or switching from a logged-in user to another
   * key clears the draft; logging in from a guest session keeps it (the buyer returns to the same checkout).
   */
  function sessionChanged(key) {
    const previous = owner;
    owner = key;

    if (previous !== null && previous !== '' && previous !== key) clear();
  }

  return {
    subscribe: state.subscribe,
    get: () => state.get(),
    restore,
    patch,
    set,
    flush,
    clear,
    detach,
    sessionChanged,
  };
}
